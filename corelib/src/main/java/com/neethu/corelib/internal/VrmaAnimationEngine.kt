package com.neethu.corelib.internal

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.TransformManager
import com.google.android.filament.gltfio.FilamentAsset
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.cos

/**
 * Runtime engine that applies VRMA animation to a loaded VRM model.
 *
 * Handles the full retargeting pipeline following the VRM spec:
 * 1. Animations arrive as **normalized** rotations (from VrmaParser)
 * 2. For VRM 0.x: Flip x/z components (coordinate system difference)
 * 3. Convert from normalized to target bone space:
 *    `targetLocal = restLocal * inv(restWorld) * normalized * restWorld`
 *
 * Reference: pixiv/three-vrm createVRMAnimationClip.ts
 */
internal class VrmaAnimationEngine(
    private val engine: Engine
) {
    companion object {
        private const val TAG = "VrmaAnimEngine"
        private const val CHUNK_TYPE_JSON = 0x4E4F534A
    }

    // Currently loaded animation
    private var currentAnimation: VrmaParser.VrmaAnimation? = null

    // Bone mapping: VRM bone name → Filament entity
    private var boneEntityMap: Map<String, Int> = emptyMap()

    // Rest pose quaternions (extracted from the target VRM model)
    private var boneRestLocalQuat: Map<String, FloatArray> = emptyMap() // boneName -> [x,y,z,w]
    private var boneRestWorldQuat: Map<String, FloatArray> = emptyMap() // boneName -> [x,y,z,w]
    private var boneRestTransforms: Map<String, FloatArray> = emptyMap() // boneName -> mat4x4

    // VRM version: "0" or "1"
    private var vrmMetaVersion: String = "1"

    // Playback
    private var isPlaying = false
    private var isLooping = true
    private var targetHipsY: Float = 1.0f

    // Idle takeover: when set, a finished one-shot (or manual stop) switches
    // to the looping idle animation instead of the rest pose — the VRM rest
    // pose is the A-pose, which reads as "arms spread" (§7.10).
    private var idleAnimation: VrmaParser.VrmaAnimation? = null
    private var idleTakeover = false
    private var idleAnchor = 0f
    private var idleSwapPending = false

    // ── Model Binding ────────────────────────────────────────────────────

    fun bindToModel(asset: FilamentAsset, vrmGlbBytes: ByteArray) {
        val tm = engine.transformManager

        // Detect VRM version
        vrmMetaVersion = detectVrmVersion(vrmGlbBytes)
        Log.i(TAG, "VRM meta version: $vrmMetaVersion")

        // Parse VRM bone name → glTF node name mapping
        val vrmBoneNodeNames = parseVrmBoneNodeNames(vrmGlbBytes)

        val entityMap = mutableMapOf<String, Int>()
        val restLocal = mutableMapOf<String, FloatArray>()
        val restWorld = mutableMapOf<String, FloatArray>()
        val restMats = mutableMapOf<String, FloatArray>()

        if (vrmBoneNodeNames.isNotEmpty()) {
            Log.i(TAG, "Using VRM extension bone mapping (${vrmBoneNodeNames.size} bones)")
            for ((boneName, nodeName) in vrmBoneNodeNames) {
                val entity = asset.getFirstEntityByName(nodeName)
                if (entity == 0) continue

                entityMap[boneName] = entity
                val instance = tm.getInstance(entity)
                if (instance == 0) continue

                // Save rest local transform
                val localMat = FloatArray(16)
                tm.getTransform(instance, localMat)
                restMats[boneName] = localMat.clone()
                restLocal[boneName] = matrixToQuaternion(localMat)

                // Save rest world transform
                val worldMat = FloatArray(16)
                tm.getWorldTransform(instance, worldMat)
                restWorld[boneName] = matrixToQuaternion(worldMat)
            }
        }

        boneEntityMap = entityMap
        boneRestLocalQuat = restLocal
        boneRestWorldQuat = restWorld
        boneRestTransforms = restMats

        // Hips Y for translation scaling
        val hipsEntity = entityMap["hips"]
        if (hipsEntity != null) {
            val inst = tm.getInstance(hipsEntity)
            if (inst != 0) {
                val wm = FloatArray(16)
                tm.getWorldTransform(inst, wm)
                targetHipsY = wm[13]
                if (targetHipsY < 0.01f) targetHipsY = 1.0f
            }
        }

        Log.i(TAG, "Bound ${entityMap.size} bones, hipsY=$targetHipsY, version=$vrmMetaVersion")
    }

    fun setAnimation(animation: VrmaParser.VrmaAnimation) { currentAnimation = animation }

    /** Set the looping idle the engine returns to after one-shots / stops (null = rest pose). */
    fun setIdleAnimation(animation: VrmaParser.VrmaAnimation?) { idleAnimation = animation }
    fun getIdleDuration(): Float = idleAnimation?.duration ?: 0f

    fun play(loop: Boolean = true) { isPlaying = true; isLooping = loop; idleTakeover = false }

    /**
     * Stop playback. With an idle configured this resumes the idle loop
     * (manual stop = "return to idle"); without one it restores the rest pose.
     */
    fun stop() {
        val idle = idleAnimation
        if (idle != null) {
            currentAnimation = idle
            isPlaying = true
            isLooping = true
            idleTakeover = true
            idleAnchor = 0f
            idleSwapPending = true
        } else {
            isPlaying = false
            restoreRestPose()
        }
    }

    fun isActive(): Boolean = isPlaying && currentAnimation != null
    fun getVrmMetaVersion(): String = vrmMetaVersion

    /** Duration of the currently loaded animation in seconds (0 when none). */
    fun getDuration(): Float = currentAnimation?.duration ?: 0f

    /** One-shot→idle transition happened since the last call (renderer resets spring bones). */
    fun consumeIdleSwap(): Boolean {
        val pending = idleSwapPending
        idleSwapPending = false
        return pending
    }

    // ── Frame Update ─────────────────────────────────────────────────────

    fun update(elapsedSeconds: Float) {
        val anim = currentAnimation ?: return
        if (!isPlaying || anim.duration <= 0f) return

        // After an idle takeover the clock restarts from the swap moment.
        val elapsed = if (idleTakeover) (elapsedSeconds - idleAnchor).coerceAtLeast(0f) else elapsedSeconds
        val time = if (isLooping) elapsed % anim.duration
                   else elapsed.coerceAtMost(anim.duration)

        val tm = engine.transformManager

        for (track in anim.humanoidTracks) {
            val entity = boneEntityMap[track.boneName] ?: continue
            val instance = tm.getInstance(entity)
            if (instance == 0) continue

            when (track.path) {
                "rotation" -> {
                    var normalized = interpolateQuaternion(track.times, track.values, time)

                    // VRM 0.x coordinate flip: negate x and z
                    // (three-vrm createVRMAnimationClip: i % 2 === 0 ? -v : v)
                    if (vrmMetaVersion == "0") {
                        normalized = floatArrayOf(-normalized[0], normalized[1], -normalized[2], normalized[3])
                    }

                    // Retarget from normalized space to target bone space:
                    // targetLocal = restLocal * inv(restWorld) * normalized * restWorld
                    val restL = boneRestLocalQuat[track.boneName] ?: QUAT_IDENTITY
                    val restW = boneRestWorldQuat[track.boneName] ?: QUAT_IDENTITY
                    val restWInv = quatInverse(restW)

                    // Retarget: L * (W^-1 * (norm * W))
                    val step1 = quatMultiply(normalized, restW)
                    val step2 = quatMultiply(restWInv, step1)
                    val final_ = quatMultiply(restL, step2)

                    applyRotation(tm, instance, track.boneName, final_)
                }
                "translation" -> {
                    val pos = interpolateVector3(track.times, track.values, time)

                    // Scale by height ratio
                    val animHipsY = anim.restHipsPosition[1]
                    val scale = if (animHipsY > 0.01f) targetHipsY / animHipsY else 1.0f

                    // Compute DELTA from animation's rest hips position, then add
                    // to the model's actual rest local translation. This keeps the
                    // character centered and only applies relative movement.
                    val animRest = anim.restHipsPosition
                    val restMat = boneRestTransforms[track.boneName]
                    val restTx = restMat?.get(12) ?: 0f
                    val restTy = restMat?.get(13) ?: 0f
                    val restTz = restMat?.get(14) ?: 0f

                    if (vrmMetaVersion == "0") {
                        pos[0] = restTx + -(pos[0] - animRest[0]) * scale
                        pos[1] = restTy +  (pos[1] - animRest[1]) * scale
                        pos[2] = restTz + -(pos[2] - animRest[2]) * scale
                    } else {
                        pos[0] = restTx + (pos[0] - animRest[0]) * scale
                        pos[1] = restTy + (pos[1] - animRest[1]) * scale
                        pos[2] = restTz + (pos[2] - animRest[2]) * scale
                    }

                    applyTranslation(tm, instance, pos)
                }
            }
        }

        // One-shot playback finished: with an idle configured, seamlessly take
        // over with the looping idle (LLM gestures `<act:…>` return to idle
        // automatically); without one, restore the rest pose. Curation prefers
        // clips whose last frame is near the rest pose so the snap is
        // invisible (docs/ai-layer-handoff.md §7.6/§7.10).
        if (!isLooping && !idleTakeover && elapsedSeconds >= anim.duration) {
            val idle = idleAnimation
            if (idle != null) {
                currentAnimation = idle
                idleAnchor = elapsedSeconds
                idleTakeover = true
                isLooping = true
                idleSwapPending = true
            } else {
                stop()
            }
        }
    }

    // ── Transform Application ────────────────────────────────────────────

    private fun applyRotation(tm: TransformManager, instance: Int, boneName: String, quat: FloatArray) {
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        val tx = mat[12]; val ty = mat[13]; val tz = mat[14]
        quaternionToMatrix(quat, mat)
        mat[12] = tx; mat[13] = ty; mat[14] = tz
        tm.setTransform(instance, mat)
    }

    private fun applyTranslation(tm: TransformManager, instance: Int, pos: FloatArray) {
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        mat[12] = pos[0]; mat[13] = pos[1]; mat[14] = pos[2]
        tm.setTransform(instance, mat)
    }

    private fun restoreRestPose() {
        val tm = engine.transformManager
        for ((boneName, restMat) in boneRestTransforms) {
            val entity = boneEntityMap[boneName] ?: continue
            val inst = tm.getInstance(entity)
            if (inst != 0) tm.setTransform(inst, restMat)
        }
    }

    // ── Interpolation ────────────────────────────────────────────────────

    private fun interpolateQuaternion(times: FloatArray, values: FloatArray, t: Float): FloatArray {
        if (times.isEmpty()) return QUAT_IDENTITY.clone()
        if (t <= times.first()) return floatArrayOf(values[0], values[1], values[2], values[3])
        if (t >= times.last()) {
            val i = (times.size - 1) * 4
            return floatArrayOf(values[i], values[i+1], values[i+2], values[i+3])
        }
        var idx = 0
        for (i in 0 until times.size - 1) { if (t >= times[i] && t < times[i+1]) { idx = i; break } }
        val alpha = if (times[idx+1] > times[idx]) (t - times[idx]) / (times[idx+1] - times[idx]) else 0f
        val i0 = idx * 4; val i1 = (idx+1) * 4
        return slerp(
            floatArrayOf(values[i0], values[i0+1], values[i0+2], values[i0+3]),
            floatArrayOf(values[i1], values[i1+1], values[i1+2], values[i1+3]),
            alpha
        )
    }

    private fun interpolateVector3(times: FloatArray, values: FloatArray, t: Float): FloatArray {
        if (times.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        if (t <= times.first()) return floatArrayOf(values[0], values[1], values[2])
        if (t >= times.last()) {
            val i = (times.size - 1) * 3
            return floatArrayOf(values[i], values[i+1], values[i+2])
        }
        var idx = 0
        for (i in 0 until times.size - 1) { if (t >= times[i] && t < times[i+1]) { idx = i; break } }
        val a = if (times[idx+1] > times[idx]) (t - times[idx]) / (times[idx+1] - times[idx]) else 0f
        val i0 = idx * 3; val i1 = (idx+1) * 3
        return floatArrayOf(
            values[i0] + (values[i1] - values[i0]) * a,
            values[i0+1] + (values[i1+1] - values[i0+1]) * a,
            values[i0+2] + (values[i1+2] - values[i0+2]) * a
        )
    }

    // ── Quaternion Math ──────────────────────────────────────────────────

    private val QUAT_IDENTITY = floatArrayOf(0f, 0f, 0f, 1f)

    private fun quatMultiply(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        return floatArrayOf(
            aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx,
            aw*bz + ax*by - ay*bx + az*bw,
            aw*bw - ax*bx - ay*by - az*bz
        )
    }

    private fun quatInverse(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    private fun slerp(q0: FloatArray, q1: FloatArray, t: Float): FloatArray {
        var dot = q0[0]*q1[0] + q0[1]*q1[1] + q0[2]*q1[2] + q0[3]*q1[3]
        val b = if (dot < 0f) { dot = -dot; floatArrayOf(-q1[0], -q1[1], -q1[2], -q1[3]) } else q1
        if (dot > 0.9995f) {
            val r = FloatArray(4) { q0[it] + (b[it] - q0[it]) * t }
            val len = sqrt((r[0]*r[0]+r[1]*r[1]+r[2]*r[2]+r[3]*r[3]).toDouble()).toFloat()
            if (len > 0f) { r[0]/=len; r[1]/=len; r[2]/=len; r[3]/=len }
            return r
        }
        val theta0 = acos(dot.toDouble().coerceIn(-1.0, 1.0))
        val theta = theta0 * t
        val s0 = (cos(theta) - dot * sin(theta) / sin(theta0)).toFloat()
        val s1 = (sin(theta) / sin(theta0)).toFloat()
        return floatArrayOf(s0*q0[0]+s1*b[0], s0*q0[1]+s1*b[1], s0*q0[2]+s1*b[2], s0*q0[3]+s1*b[3])
    }

    // ── Matrix ↔ Quaternion ──────────────────────────────────────────────

    private fun quaternionToMatrix(q: FloatArray, mat: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val x2 = x+x; val y2 = y+y; val z2 = z+z
        val xx = x*x2; val xy = x*y2; val xz = x*z2
        val yy = y*y2; val yz = y*z2; val zz = z*z2
        val wx = w*x2; val wy = w*y2; val wz = w*z2
        mat[0]=1f-(yy+zz); mat[1]=xy+wz;      mat[2]=xz-wy;      mat[3]=0f
        mat[4]=xy-wz;      mat[5]=1f-(xx+zz); mat[6]=yz+wx;      mat[7]=0f
        mat[8]=xz+wy;      mat[9]=yz-wx;      mat[10]=1f-(xx+yy); mat[11]=0f
        mat[15]=1f
    }

    /**
     * Extract quaternion [x,y,z,w] from column-major 4x4 rotation matrix.
     * Uses Shepperd's method for numerical stability.
     */
    private fun matrixToQuaternion(m: FloatArray): FloatArray {
        val m00 = m[0]; val m01 = m[4]; val m02 = m[8]
        val m10 = m[1]; val m11 = m[5]; val m12 = m[9]
        val m20 = m[2]; val m21 = m[6]; val m22 = m[10]
        val trace = m00 + m11 + m22

        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt((trace + 1f).toDouble()).toFloat() * 2f
            q[3] = s * 0.25f; q[0] = (m21-m12)/s; q[1] = (m02-m20)/s; q[2] = (m10-m01)/s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt((1f + m00 - m11 - m22).toDouble()).toFloat() * 2f
            q[3] = (m21-m12)/s; q[0] = s * 0.25f; q[1] = (m01+m10)/s; q[2] = (m02+m20)/s
        } else if (m11 > m22) {
            val s = sqrt((1f + m11 - m00 - m22).toDouble()).toFloat() * 2f
            q[3] = (m02-m20)/s; q[0] = (m01+m10)/s; q[1] = s * 0.25f; q[2] = (m12+m21)/s
        } else {
            val s = sqrt((1f + m22 - m00 - m11).toDouble()).toFloat() * 2f
            q[3] = (m10-m01)/s; q[0] = (m02+m20)/s; q[1] = (m12+m21)/s; q[2] = s * 0.25f
        }
        // Normalize
        val len = sqrt((q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]).toDouble()).toFloat()
        if (len > 0f) { q[0]/=len; q[1]/=len; q[2]/=len; q[3]/=len }
        return q
    }

    // ── VRM Extension Parsing ────────────────────────────────────────────

    private fun detectVrmVersion(glbBytes: ByteArray): String {
        val json = parseGlbJson(glbBytes) ?: return "1"
        val ext = json.getAsJsonObject("extensions") ?: return "1"
        if (ext.has("VRMC_vrm")) return "1"
        if (ext.has("VRM")) return "0"
        return "1"
    }

    private fun parseVrmBoneNodeNames(glbBytes: ByteArray): Map<String, String> {
        val json = parseGlbJson(glbBytes) ?: return emptyMap()
        val nodes = json.getAsJsonArray("nodes") ?: return emptyMap()
        fun nodeName(i: Int): String? = if (i < nodes.size()) nodes[i].asJsonObject.get("name")?.asString else null

        val result = mutableMapOf<String, String>()
        val ext = json.getAsJsonObject("extensions") ?: return emptyMap()

        // VRM 1.0
        ext.getAsJsonObject("VRMC_vrm")?.getAsJsonObject("humanoid")
            ?.getAsJsonObject("humanBones")?.entrySet()?.forEach { (bone, el) ->
                el?.asJsonObject?.get("node")?.asInt?.let { idx ->
                    nodeName(idx)?.let { result[bone] = it }
                }
            }
        if (result.isNotEmpty()) return result

        // VRM 0.x
        ext.getAsJsonObject("VRM")?.getAsJsonObject("humanoid")
            ?.getAsJsonArray("humanBones")?.forEach { el ->
                val obj = el.asJsonObject
                val bone = obj.get("bone")?.asString ?: return@forEach
                val idx = obj.get("node")?.asInt ?: return@forEach
                nodeName(idx)?.let { result[bone] = it }
            }
        return result
    }

    private fun parseGlbJson(glbBytes: ByteArray): JsonObject? {
        val buf = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) return null
        buf.int; buf.int; buf.int // magic, version, length
        if (buf.remaining() < 8) return null
        val chunkLen = buf.int; val chunkType = buf.int
        if (chunkType != CHUNK_TYPE_JSON) return null
        val jsonBytes = ByteArray(chunkLen); buf.get(jsonBytes)
        return Gson().fromJson(String(jsonBytes, Charsets.UTF_8), JsonObject::class.java)
    }
}
