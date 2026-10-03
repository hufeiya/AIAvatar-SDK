package com.neethu.corelib.internal

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.TransformManager
import com.neethu.corelib.LookAtInfo
import kotlin.math.abs
import kotlin.math.exp

/**
 * Gaze (look-at) overlay engine — corelib's counterpart of three-vrm's
 * `VRMLookAt` / AIRI's `vrm.lookAt.update(delta)`.
 *
 * The outside world (orchestrator's SaccadeEngine, debug commands, and later
 * a video-based face tracker) feeds a **world-space gaze target**; each frame,
 * after the animation has written bone locals and before skin matrices
 * propagate, the engine reads the head's world pose, solves the additional
 * rotation "current face direction → target direction", clamps it to natural
 * neck/head range and distributes it:
 *
 *  - neck gets 35% and head 65% of the **smoothed** offset (exp(-dt·7) ≈ 300 ms
 *    settle — a natural head turn);
 *  - the eye bones carry the **un-smoothed remainder** (clamped ±25°), so eyes
 *    dart to a new target first and the head lazily catches up — the classic
 *    human saccade layering. Without eye bones the gaze lands ~short (head
 *    fraction only), which reads fine.
 *
 * Every offset is a world-frame rotation converted into the bone's parent
 * frame and multiplied onto the *current* local rotation
 * (`L' = inv(P)·D·P·L`), so it composes with anything that drives bones:
 * VRMA one-shots, the looping idle, hips drag. VRMA rewrites locals each
 * frame and this engine reads the post-animation pose, so the offset simply
 * rides on top. Spring bones see the head motion and sway naturally (desired;
 * no spring reset — the motion is continuous, not a pose snap).
 *
 * The face direction is captured once at [bind] as a head-local vector
 * (`inv(restHeadWorldQ)·+Z`, valid because VRM models face +Z in world at
 * rest — the renderer flips VRM 0.x roots 180° before binding).
 */
internal class VrmLookAtEngine(private val engine: Engine) {

    companion object {
        private const val TAG = "VrmLookAt"

        // Head+neck clamp relative to the current *animated* face direction —
        // the natural turn range when the body stays put. Clamping against the
        // animated (not rest) direction also composes safely with gestures:
        // a head-shaking VRMA plus look-at can only turn the head a bit more,
        // never snap it away from the animation.
        private val MAX_YAW_RAD = Math.toRadians(55.0).toFloat()
        private val MAX_PITCH_RAD = Math.toRadians(35.0).toFloat()

        // Eye-bone clamp for the un-smoothed remainder (real eyeballs ≈ ±40°).
        private val EYE_MAX_RAD = Math.toRadians(25.0).toFloat()

        // Distribution of the smoothed offset; gains must sum to 1 so the eye
        // remainder formula (raw − smoothed) closes the gaze exactly.
        private const val HEAD_GAIN = 0.65f
        private const val NECK_GAIN = 0.35f

        // Head/neck exponential approach rate (1/s): 7 ≈ 300 ms settle.
        private const val HEAD_SMOOTH_RATE = 7f

        // Below this (rad) an offset write is skipped — keeps the resting state
        // fully silent like FaceDriver's expression dedup.
        private const val WRITE_EPS_RAD = 0.002f
    }

    private class BoneRef(val entity: Int, val restLocalMat: FloatArray)

    private var head: BoneRef? = null
    private var neck: BoneRef? = null
    private var leftEye: BoneRef? = null
    private var rightEye: BoneRef? = null

    /** The model's face direction in head-local space (captured at bind). */
    private var faceLocalDir: FloatArray = floatArrayOf(0f, 0f, 1f)

    @Volatile private var target: FloatArray? = null
    private var smoothYaw = 0f
    private var smoothPitch = 0f
    private var appliedAny = false

    /** Bind humanoid bone entities (0 = missing) and capture rest state. */
    fun bind(headEntity: Int, neckEntity: Int, leftEyeEntity: Int, rightEyeEntity: Int) {
        val tm = engine.transformManager
        this.head = ref(tm, headEntity)
        this.neck = ref(tm, neckEntity)
        this.leftEye = ref(tm, leftEyeEntity)
        this.rightEye = ref(tm, rightEyeEntity)

        val h = head
        if (h == null) {
            Log.w(TAG, "no head bone — gaze disabled for this model")
            return
        }
        // Face direction in head-local space: the world +Z the model faces at
        // rest, pulled back through the head's rest world rotation.
        val headWorld = FloatArray(16)
        val inst = tm.getInstance(h.entity)
        if (inst != 0) tm.getWorldTransform(inst, headWorld)
        val restWorldQ = GazeMath.matToQuat(headWorld)
        faceLocalDir = GazeMath.normalize3(
            GazeMath.rotateVector(GazeMath.quatInverse(restWorldQ), floatArrayOf(0f, 0f, 1f))
        )
        smoothYaw = 0f
        smoothPitch = 0f
        appliedAny = false
        Log.i(
            TAG,
            "bound: head=${headEntity != 0} neck=${neckEntity != 0} " +
                "eyes=${leftEyeEntity != 0 && rightEyeEntity != 0} " +
                "faceLocal=${faceLocalDir.joinToString { "%.2f".format(it) }}",
        )
    }

    /** Track the world-space point [x,y,z] (typically the camera eye — where the user's face is). */
    fun setTarget(x: Float, y: Float, z: Float) {
        val t = target
        if (t != null && t[0] == x && t[1] == y && t[2] == z) return
        target = floatArrayOf(x, y, z)
    }

    /** Stop tracking; the head eases back once (rest locals restored on the next update). */
    fun clearTarget() {
        target = null
    }

    fun info(): LookAtInfo = LookAtInfo(
        target = target?.clone(),
        yawDegrees = Math.toDegrees(smoothYaw.toDouble()).toFloat(),
        pitchDegrees = Math.toDegrees(smoothPitch.toDouble()).toFloat(),
        headBoneBound = head != null,
        eyeBonesBound = leftEye != null && rightEye != null,
    )

    // ── Frame update (render thread) ─────────────────────────────────────

    fun update(dtSeconds: Float) {
        val t = target
        if (t == null) {
            smoothYaw = 0f
            smoothPitch = 0f
            if (appliedAny) restoreRest()
            return
        }
        val h = head ?: return
        val tm = engine.transformManager
        val headInst = tm.getInstance(h.entity)
        if (headInst == 0) return

        // World transforms must reflect this frame's animation-driven locals
        // before we read the head pose (animation above wrote locals only).
        tm.commitLocalTransformTransaction()
        val headWorld = FloatArray(16)
        tm.getWorldTransform(headInst, headWorld)
        val headQ = GazeMath.matToQuat(headWorld)
        val headPos = floatArrayOf(headWorld[12], headWorld[13], headWorld[14])

        val forward = GazeMath.normalize3(GazeMath.rotateVector(headQ, faceLocalDir))
        val toTarget = GazeMath.normalize3(
            floatArrayOf(t[0] - headPos[0], t[1] - headPos[1], t[2] - headPos[2])
        )
        val raw = GazeMath.signedYawPitch(forward, toTarget)
        val yaw = raw[0].coerceIn(-MAX_YAW_RAD, MAX_YAW_RAD)
        val pitch = raw[1].coerceIn(-MAX_PITCH_RAD, MAX_PITCH_RAD)

        val k = 1f - exp(-dtSeconds * HEAD_SMOOTH_RATE)
        smoothYaw += (yaw - smoothYaw) * k
        smoothPitch += (pitch - smoothPitch) * k

        // Parent quats are read before any offset writes this frame; the
        // closed loop re-reads everything next frame, so the neck-offset
        // affecting the head's parent frame is second-order noise.
        applyOffset(neck, smoothYaw * NECK_GAIN, smoothPitch * NECK_GAIN, forward)
        applyOffset(head, smoothYaw * HEAD_GAIN, smoothPitch * HEAD_GAIN, forward)

        // Eyes carry the un-smoothed remainder (raw clamped target minus what
        // the head has caught up to) — they lead, the head follows.
        val eyeYaw = (yaw - smoothYaw).coerceIn(-EYE_MAX_RAD, EYE_MAX_RAD)
        val eyePitch = (pitch - smoothPitch).coerceIn(-EYE_MAX_RAD, EYE_MAX_RAD)
        applyOffset(leftEye, eyeYaw, eyePitch, forward)
        applyOffset(rightEye, eyeYaw, eyePitch, forward)
    }

    /**
     * Rotate [bone] by (yaw/pitch built against [forward], world frame):
     * `L' = inv(P)·D·P·L`, keeping the local translation. [P] is the parent's
     * world rotation — ⚠ TransformManager.getParent returns the parent
     * **entity** and must go through getInstance() before use (the
     * entity/instance mix-up is a known SIGSEGV trap, see
     * VrmSpringBoneManager).
     */
    private fun applyOffset(bone: BoneRef?, yaw: Float, pitch: Float, forward: FloatArray) {
        if (bone == null) return
        if (abs(yaw) < WRITE_EPS_RAD && abs(pitch) < WRITE_EPS_RAD) return
        val tm = engine.transformManager
        val inst = tm.getInstance(bone.entity)
        if (inst == 0) return

        val parentEntity = tm.getParent(inst)
        val parentQ = if (parentEntity != 0) {
            val parentInst = tm.getInstance(parentEntity)
            if (parentInst != 0) {
                val m = FloatArray(16)
                tm.getWorldTransform(parentInst, m)
                GazeMath.matToQuat(m)
            } else {
                IDENTITY_Q
            }
        } else {
            IDENTITY_Q
        }

        val dir = GazeMath.dirFromYawPitch(forward, yaw, pitch)
        val dWorld = GazeMath.quatFromTo(forward, dir)
        val localOffset = GazeMath.quatMultiply(
            GazeMath.quatMultiply(GazeMath.quatInverse(parentQ), dWorld),
            parentQ,
        )

        val mat = FloatArray(16)
        tm.getTransform(inst, mat)
        val l = GazeMath.matToQuat(mat)
        val l2 = GazeMath.quatMultiply(localOffset, l)
        quaternionToMatrix(l2, mat) // quaternionToMatrix keeps the translation columns
        tm.setTransform(inst, mat)
        appliedAny = true
    }

    /** Put the driven bones back to their rest locals (gaze off / model reset). */
    private fun restoreRest() {
        val tm = engine.transformManager
        for (bone in listOf(head, neck, leftEye, rightEye)) {
            val b = bone ?: continue
            val inst = tm.getInstance(b.entity)
            if (inst != 0) tm.setTransform(inst, b.restLocalMat)
        }
        appliedAny = false
    }

    private fun ref(tm: TransformManager, entity: Int): BoneRef? {
        if (entity == 0) return null
        val inst = tm.getInstance(entity)
        if (inst == 0) return null
        val mat = FloatArray(16)
        tm.getTransform(inst, mat)
        return BoneRef(entity, mat.clone())
    }

    private val IDENTITY_Q = floatArrayOf(0f, 0f, 0f, 1f)

    /** Rotation part of [q] into column-major [mat], translation preserved. */
    private fun quaternionToMatrix(q: FloatArray, mat: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        mat[0] = 1f - (yy + zz); mat[1] = xy + wz; mat[2] = xz - wy; mat[3] = 0f
        mat[4] = xy - wz; mat[5] = 1f - (xx + zz); mat[6] = yz + wx; mat[7] = 0f
        mat[8] = xz + wy; mat[9] = yz - wx; mat[10] = 1f - (xx + yy); mat[11] = 0f
        mat[15] = 1f
    }
}
