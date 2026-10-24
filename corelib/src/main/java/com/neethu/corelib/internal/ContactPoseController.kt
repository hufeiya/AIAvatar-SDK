package com.neethu.corelib.internal

import com.neethu.corelib.ContactKind
import com.neethu.corelib.ContactPosePhase
import com.neethu.corelib.ContactPoseRequest
import com.neethu.corelib.ContactPoseResult
import com.neethu.corelib.ContactPoseSnapshot

/**
 * Contact pose state machine (scene interaction alignment): walks end at an
 * approach point, then this controller rotates the body into the contact
 * orientation, samples the actual hips bone world position and slides the
 * root so the hips land on the furniture contact surface
 * (rootTranslation += targetContactWorld − measuredContactWorld — the asset
 * README's alignment formula).
 *
 * SIT plays an optional stand→sit transition one-shot first (the seated pose
 * must exist before the hips can be measured), crossfades into the seated
 * loop clip while the root slides onto the seat. LIE has no lying clip in the
 * built-in library — the standing pose is rotated flat via the supine root
 * rotation ([ContactPoseMath.lieTargetMatrix]) and keeps the idle loop.
 *
 * Every phase animates the root matrix between captured snapshots, so
 * interruptions (clear mid-transition) always converge back to the exact
 * stand matrix captured at pose start — rotation slerp + translation lerp
 * preserve the baked unit-cube scale and the VRM0 Y-180 flip.
 *
 * Glue only: transform/clip access arrives as lambdas from SoulLinkRenderer;
 * the math lives in [ContactPoseMath] (JVM tested). Runs on the Choreographer
 * thread inside the renderer's frame callback, after locomotion.
 */
internal class ContactPoseController(
    /** Current avatar root matrix (column-major 4×4) or null when no model. */
    private val getRootMatrix: () -> FloatArray?,
    /** World position of the hips bone (3 floats) or null when unresolved. */
    private val getHipsWorld: () -> FloatArray?,
    /** Crossfade-play a VRMA from assets (returns false when load fails). */
    private val playClip: (assetPath: String, loop: Boolean, fadeSeconds: Float) -> Boolean,
    /** Whether a one-shot VRMA is currently playing (transition edge source). */
    private val isOneShotActive: () -> Boolean,
    /**
     * Root matrix → belly heading (radians). Lives renderer-side: VRM 0.x's
     * baked Y-180 flip negates matrix column 2, so the sign is model-specific.
     */
    private val headingOf: (FloatArray) -> Float,
    /**
     * Called when EXITING completes with the restored stand matrix — the
     * absolute yaw changes above bypassed the locomotion engine's internal
     * heading, so the renderer resyncs it here.
     */
    private val onReturnToStand: (FloatArray) -> Unit,
    /** Renderer-side logging. */
    private val onLog: (String) -> Unit,
) {

    companion object {
        /** Stand→sit turn duration (seconds) before the alignment slide. */
        private const val SIT_TURN_SECONDS = 0.35f

        /** Direct sit (no transition clip): pose-settle grace before aligning. */
        private const val SIT_SETTLE_SECONDS = 0.5f

        /** Lie rotation duration (standing → supine). */
        private const val LIE_TURN_SECONDS = 0.5f

        /** Root slide onto the contact surface (alignment). */
        private const val ALIGN_SLIDE_SECONDS = 0.35f

        /** Return to the stand matrix on clear. */
        private const val EXIT_SECONDS = 0.6f
    }

    private var phase = ContactPosePhase.NONE
    private var kind: ContactKind? = null
    private var request: ContactPoseRequest? = null

    /** Root matrix captured when the pose engaged — the exit target. */
    private var standMatrix = FloatArray(16)

    // Phase animation: root matrix interpolated from → to with smoothstep t.
    private var animFrom = FloatArray(16)
    private var animTo = FloatArray(16)
    private var animT = 0f
    private var animDur = 0f

    /** ENTERING (SIT): wait for the stand→sit one-shot to finish before aligning. */
    private var waitingOneShot = false

    /** ENTERING (SIT, no transition clip): settle grace before aligning. */
    private var settleRemaining = 0f

    /** Hips target in world space (contact + lift), computed from the request. */
    private var hipsTarget = FloatArray(3)

    /** True once the hips landed on the contact surface (HOLDING). */
    private var aligned = false

    val isActive: Boolean get() = phase != ContactPosePhase.NONE

    fun snapshot(): ContactPoseSnapshot = ContactPoseSnapshot(phase, kind, aligned)

    /** Reset without restoring the root (model reload — the old root is gone). */
    fun reset() {
        phase = ContactPosePhase.NONE
        kind = null
        request = null
        aligned = false
        waitingOneShot = false
    }

    fun apply(req: ContactPoseRequest): ContactPoseResult {
        val root = getRootMatrix() ?: return ContactPoseResult.NO_MODEL
        if (phase != ContactPosePhase.NONE) return ContactPoseResult.BUSY
        if (!req.x.isFinite() || !req.y.isFinite() || !req.z.isFinite() ||
            !req.yawDeg.isFinite() || req.hipsLiftMeters.isNaN()
        ) {
            return ContactPoseResult.INVALID_TARGET
        }
        standMatrix = root.clone()
        this.request = req
        this.kind = req.kind
        this.aligned = false
        hipsTarget = floatArrayOf(req.x, req.y + req.hipsLiftMeters, req.z)

        when (req.kind) {
            ContactKind.SIT -> {
                animFrom = root.clone()
                animTo = root.clone()
                val turn = Math.toRadians(req.yawDeg.toDouble()).toFloat() - headingOf(root)
                ContactPoseMath.rotateAroundY(animTo, turn)
                waitingOneShot = false
                settleRemaining = SIT_SETTLE_SECONDS
                if (req.sitInClipAssetPath != null) {
                    if (!playClip(req.sitInClipAssetPath, false, 0f)) {
                        reset()
                        return ContactPoseResult.LOAD_FAILED
                    }
                    waitingOneShot = true
                } else if (req.sitLoopClipAssetPath != null) {
                    if (!playClip(req.sitLoopClipAssetPath, true, 0.4f)) {
                        reset()
                        return ContactPoseResult.LOAD_FAILED
                    }
                }
                animDur = SIT_TURN_SECONDS
                animT = 0f
                phase = ContactPosePhase.ENTERING
            }
            ContactKind.LIE -> {
                animFrom = root.clone()
                animTo = ContactPoseMath.lieTargetMatrix(root, req.yawDeg)
                animDur = LIE_TURN_SECONDS
                animT = 0f
                waitingOneShot = false
                phase = ContactPosePhase.ENTERING
            }
        }
        onLog("contact pose ${req.kind} → ENTERING target=(%.2f, %.2f, %.2f) yaw=%.0f"
            .format(req.x, req.y, req.z, req.yawDeg))
        return ContactPoseResult.STARTED
    }

    /** Stand back up (restore the captured stand matrix). False when idle. */
    fun clear(): Boolean {
        if (phase == ContactPosePhase.NONE) return false
        val req = request
        if (req?.kind == ContactKind.SIT && req.standUpClipAssetPath != null) {
            // One-shot sit→stand rides on the root return animation; the
            // engine auto-takes over idle when it finishes.
            playClip(req.standUpClipAssetPath, false, 0f)
        }
        animFrom = currentMatrix()
        animTo = standMatrix.clone()
        animT = 0f
        animDur = EXIT_SECONDS
        waitingOneShot = false
        phase = ContactPosePhase.EXITING
        onLog("contact pose → EXITING (back to stand matrix)")
        return true
    }

    /**
     * Advance one frame. Returns the root matrix to write, or null when the
     * root needs no change this frame (also null while idle).
     */
    fun update(dtSeconds: Float): FloatArray? {
        if (phase == ContactPosePhase.NONE) return null
        animT = (animT + if (animDur > 0f) dtSeconds / animDur else 1f).coerceAtMost(1f)
        val current = currentMatrix()

        when (phase) {
            ContactPosePhase.ENTERING -> {
                val rotationDone = animT >= 1f
                val clipDone = !waitingOneShot || !isOneShotActive()
                if (waitingOneShot && !clipDone) return current
                if (!rotationDone) return current
                if (!waitingOneShot) {
                    // No transition clip: hold the settle grace so the
                    // crossfade lands before the hips are measured.
                    settleRemaining -= dtSeconds
                    if (settleRemaining > 0f) return current
                }
                startAligning(current)
                return current
            }
            ContactPosePhase.ALIGNING -> {
                if (animT >= 1f) {
                    phase = ContactPosePhase.HOLDING
                    aligned = true
                    onLog("contact pose aligned → HOLDING (hips on contact)")
                }
                return current
            }
            ContactPosePhase.HOLDING -> return null
            ContactPosePhase.EXITING -> {
                if (animT >= 1f) {
                    phase = ContactPosePhase.NONE
                    kind = null
                    request = null
                    aligned = false
                    // Absolute yaw changes above bypassed the locomotion
                    // engine's internal heading — the renderer resyncs it.
                    onReturnToStand(animTo)
                }
                return current
            }
            else -> return null
        }
    }

    private fun startAligning(current: FloatArray) {
        val req = request ?: run {
            phase = ContactPosePhase.HOLDING
            return
        }
        val hips = getHipsWorld()
        if (hips == null) {
            onLog("contact pose: hips bone unresolved — skip alignment, HOLDING")
            phase = ContactPosePhase.HOLDING
            return
        }
        if (req.sitLoopClipAssetPath != null && req.kind == ContactKind.SIT &&
            req.sitInClipAssetPath != null
        ) {
            // Transition just finished: crossfade into the seated loop.
            playClip(req.sitLoopClipAssetPath, true, 0.3f)
        }
        animFrom = current
        animTo = current.clone()
        for (i in 0..2) {
            animTo[12 + i] += hipsTarget[i] - hips[i]
        }
        animT = 0f
        animDur = ALIGN_SLIDE_SECONDS
        phase = ContactPosePhase.ALIGNING
        onLog(
            "contact pose → ALIGNING hips (%.2f, %.2f, %.2f) → (%.2f, %.2f, %.2f)".format(
                hips[0], hips[1], hips[2], hipsTarget[0], hipsTarget[1], hipsTarget[2],
            ),
        )
    }

    /** Interpolated matrix between [animFrom] and [animTo] at the eased phase. */
    private fun currentMatrix(): FloatArray {
        val t = ContactPoseMath.ease(animT)
        val q = ContactPoseMath.slerp(
            ContactPoseMath.toQuaternion(animFrom),
            ContactPoseMath.toQuaternion(animTo),
            t,
        )
        val out = ContactPoseMath.withRotation(animFrom, q)
        for (i in 0..2) {
            out[12 + i] = animFrom[12 + i] + t * (animTo[12 + i] - animFrom[12 + i])
        }
        return out
    }
}
