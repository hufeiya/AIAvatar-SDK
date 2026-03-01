package com.neethu.corelib.internal

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for VRMA (VRM Animation) files.
 *
 * VRMA files are GLB containers with a `VRMC_vrm_animation` extension.
 * During parsing, rotation tracks are **normalized** using the VRMA model's
 * world matrices, following three-vrm's VRMAnimationLoaderPlugin approach:
 *   `normalized = parentWorldRot * rawLocalRot * inv(boneWorldRot)`
 *
 * This produces rotations in a universal "normalized" space that can then be
 * retargeted to any VRM model regardless of its rest pose.
 */
internal class VrmaParser {

    companion object {
        private const val TAG = "VrmaParser"
        private const val GLB_MAGIC = 0x46546C67
        private const val CHUNK_TYPE_JSON = 0x4E4F534A
        private const val CHUNK_TYPE_BIN = 0x004E4942
        private const val COMPONENT_TYPE_FLOAT = 5126

        private val ACCESSOR_TYPE_SIZE = mapOf(
            "SCALAR" to 1, "VEC2" to 2, "VEC3" to 3, "VEC4" to 4,
            "MAT2" to 4, "MAT3" to 9, "MAT4" to 16
        )

        /**
         * VRM bone parent map (from three-vrm VRMHumanBoneParentMap.ts).
         * Used to walk up the hierarchy when the direct parent isn't in the humanoid map.
         */
        private val BONE_PARENT_MAP = mapOf(
            "hips" to null,
            "spine" to "hips", "chest" to "spine", "upperChest" to "chest",
            "neck" to "upperChest", "head" to "neck",
            "leftEye" to "head", "rightEye" to "head", "jaw" to "head",
            "leftUpperLeg" to "hips", "leftLowerLeg" to "leftUpperLeg",
            "leftFoot" to "leftLowerLeg", "leftToes" to "leftFoot",
            "rightUpperLeg" to "hips", "rightLowerLeg" to "rightUpperLeg",
            "rightFoot" to "rightLowerLeg", "rightToes" to "rightFoot",
            "leftShoulder" to "upperChest", "leftUpperArm" to "leftShoulder",
            "leftLowerArm" to "leftUpperArm", "leftHand" to "leftLowerArm",
            "rightShoulder" to "upperChest", "rightUpperArm" to "rightShoulder",
            "rightLowerArm" to "rightUpperArm", "rightHand" to "rightLowerArm",
            "leftThumbMetacarpal" to "leftHand", "leftThumbProximal" to "leftThumbMetacarpal",
            "leftThumbDistal" to "leftThumbProximal",
            "leftIndexProximal" to "leftHand", "leftIndexIntermediate" to "leftIndexProximal",
            "leftIndexDistal" to "leftIndexIntermediate",
            "leftMiddleProximal" to "leftHand", "leftMiddleIntermediate" to "leftMiddleProximal",
            "leftMiddleDistal" to "leftMiddleIntermediate",
            "leftRingProximal" to "leftHand", "leftRingIntermediate" to "leftRingProximal",
            "leftRingDistal" to "leftRingIntermediate",
            "leftLittleProximal" to "leftHand", "leftLittleIntermediate" to "leftLittleProximal",
            "leftLittleDistal" to "leftLittleIntermediate",
            "rightThumbMetacarpal" to "rightHand", "rightThumbProximal" to "rightThumbMetacarpal",
            "rightThumbDistal" to "rightThumbProximal",
            "rightIndexProximal" to "rightHand", "rightIndexIntermediate" to "rightIndexProximal",
            "rightIndexDistal" to "rightIndexIntermediate",
            "rightMiddleProximal" to "rightHand", "rightMiddleIntermediate" to "rightMiddleProximal",
            "rightMiddleDistal" to "rightMiddleIntermediate",
            "rightRingProximal" to "rightHand", "rightRingIntermediate" to "rightRingProximal",
            "rightRingDistal" to "rightRingIntermediate",
            "rightLittleProximal" to "rightHand", "rightLittleIntermediate" to "rightLittleProximal",
            "rightLittleDistal" to "rightLittleIntermediate"
        )
    }

    // ── Data Classes ─────────────────────────────────────────────────────

    data class VrmaAnimation(
        val duration: Float,
        val humanoidTracks: List<HumanoidTrack>,
        val expressionTracks: List<ExpressionTrack>,
        val restHipsPosition: FloatArray
    )

    data class HumanoidTrack(
        val boneName: String,
        val path: String,         // "rotation" or "translation"
        val times: FloatArray,
        val values: FloatArray    // NORMALIZED quaternions or translations
    )

    data class ExpressionTrack(
        val name: String,
        val times: FloatArray,
        val values: FloatArray
    )

    // ── Main Parse Entry ─────────────────────────────────────────────────

    fun parse(glbBuffer: ByteBuffer): VrmaAnimation? {
        glbBuffer.order(ByteOrder.LITTLE_ENDIAN)
        glbBuffer.rewind()

        val magic = glbBuffer.int
        val version = glbBuffer.int
        val length = glbBuffer.int
        if (magic != GLB_MAGIC) { Log.e(TAG, "Invalid GLB magic"); return null }

        var jsonChunk: String? = null
        var binaryBuffer: ByteBuffer? = null

        while (glbBuffer.hasRemaining()) {
            val chunkLength = glbBuffer.int
            val chunkType = glbBuffer.int
            when (chunkType) {
                CHUNK_TYPE_JSON -> {
                    val b = ByteArray(chunkLength); glbBuffer.get(b)
                    jsonChunk = String(b, Charsets.UTF_8)
                }
                CHUNK_TYPE_BIN -> {
                    val b = ByteArray(chunkLength); glbBuffer.get(b)
                    binaryBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                }
                else -> glbBuffer.position(glbBuffer.position() + chunkLength)
            }
        }
        if (jsonChunk == null) { Log.e(TAG, "No JSON chunk"); return null }

        val json = Gson().fromJson(jsonChunk, JsonObject::class.java)
        val vrmaExt = json.getAsJsonObject("extensions")
            ?.getAsJsonObject("VRMC_vrm_animation") ?: run {
            Log.e(TAG, "No VRMC_vrm_animation extension"); return null
        }

        // Build node maps
        val humanoidNodeMap = buildHumanoidNodeMap(vrmaExt)
        val expressionNodeMap = buildExpressionNodeMap(vrmaExt)

        // Build world rotation map from VRMA's nodes (for normalization)
        val worldRotMap = buildWorldRotationMap(json, vrmaExt, humanoidNodeMap)

        // Rest hips position
        val restHipsPosition = extractRestHipsPosition(json, vrmaExt)

        val animationsArray = json.getAsJsonArray("animations")
        if (animationsArray == null || animationsArray.size() == 0) {
            return VrmaAnimation(0f, emptyList(), emptyList(), restHipsPosition)
        }

        return parseAnimation(
            json, animationsArray[0].asJsonObject, binaryBuffer,
            humanoidNodeMap, expressionNodeMap, worldRotMap, restHipsPosition
        )
    }

    // ── Node Map Building ────────────────────────────────────────────────

    private fun buildHumanoidNodeMap(vrmaExt: JsonObject): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val humanBones = vrmaExt.getAsJsonObject("humanoid")
            ?.getAsJsonObject("humanBones") ?: return map
        for ((boneName, el) in humanBones.entrySet()) {
            val node = el?.asJsonObject?.get("node")?.asInt
            if (node != null) map[node] = boneName
        }
        return map
    }

    private fun buildExpressionNodeMap(vrmaExt: JsonObject): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val expressions = vrmaExt.getAsJsonObject("expressions") ?: return map
        expressions.getAsJsonObject("preset")?.entrySet()?.forEach { (name, el) ->
            el?.asJsonObject?.get("node")?.asInt?.let { map[it] = name }
        }
        expressions.getAsJsonObject("custom")?.entrySet()?.forEach { (name, el) ->
            el?.asJsonObject?.get("node")?.asInt?.let { map[it] = name }
        }
        return map
    }

    // ── World Rotation Map (for normalization) ───────────────────────────

    /**
     * Build world rotation quaternions for each bone in the VRMA model.
     * Also includes "hipsParent" for hips translation normalization.
     *
     * Mirrors three-vrm's `_createBoneWorldMatrixMap`.
     */
    private fun buildWorldRotationMap(
        json: JsonObject,
        vrmaExt: JsonObject,
        humanoidNodeMap: Map<Int, String>
    ): Map<String, FloatArray> {
        val nodes = json.getAsJsonArray("nodes") ?: return emptyMap()
        val result = mutableMapOf<String, FloatArray>()

        // Build parent map from node hierarchy
        val parentMap = mutableMapOf<Int, Int>()
        for (i in 0 until nodes.size()) {
            nodes[i].asJsonObject.getAsJsonArray("children")?.forEach { child ->
                parentMap[child.asInt] = i
            }
        }

        // Read local rotations for all nodes
        val localRots = Array(nodes.size()) { i ->
            val node = nodes[i].asJsonObject
            val rot = node.getAsJsonArray("rotation")
            if (rot != null && rot.size() >= 4)
                floatArrayOf(rot[0].asFloat, rot[1].asFloat, rot[2].asFloat, rot[3].asFloat)
            else
                floatArrayOf(0f, 0f, 0f, 1f) // identity
        }

        // Compute world rotation for a node by walking up parents
        fun computeWorldRot(nodeIndex: Int): FloatArray {
            var world = localRots[nodeIndex].clone()
            var current = parentMap[nodeIndex]
            while (current != null) {
                world = quatMultiply(localRots[current], world)
                current = parentMap[current]
            }
            return world
        }

        // Build world rotations for each humanoid bone
        for ((nodeIndex, boneName) in humanoidNodeMap) {
            result[boneName] = computeWorldRot(nodeIndex)

            if (boneName == "hips") {
                // hipsParent world rotation
                val parentIdx = parentMap[nodeIndex]
                result["hipsParent"] = if (parentIdx != null) computeWorldRot(parentIdx)
                    else floatArrayOf(0f, 0f, 0f, 1f)
            }
        }

        Log.d(TAG, "Built world rotation map for ${result.size} bones")
        return result
    }

    // ── Rest Pose ────────────────────────────────────────────────────────

    private fun extractRestHipsPosition(json: JsonObject, vrmaExt: JsonObject): FloatArray {
        val hipsNode = vrmaExt.getAsJsonObject("humanoid")
            ?.getAsJsonObject("humanBones")?.getAsJsonObject("hips")
            ?.get("node")?.asInt ?: return floatArrayOf(0f, 0f, 0f)
        return computeNodeWorldPosition(json, hipsNode)
    }

    private fun computeNodeWorldPosition(json: JsonObject, nodeIndex: Int): FloatArray {
        val nodes = json.getAsJsonArray("nodes") ?: return floatArrayOf(0f, 0f, 0f)
        val parentMap = mutableMapOf<Int, Int>()
        for (i in 0 until nodes.size()) {
            nodes[i].asJsonObject.getAsJsonArray("children")?.forEach { c ->
                parentMap[c.asInt] = i
            }
        }
        val pos = floatArrayOf(0f, 0f, 0f)
        var cur = nodeIndex
        while (true) {
            val n = nodes[cur].asJsonObject.getAsJsonArray("translation")
            if (n != null && n.size() >= 3) {
                pos[0] += n[0].asFloat; pos[1] += n[1].asFloat; pos[2] += n[2].asFloat
            }
            cur = parentMap[cur] ?: break
        }
        return pos
    }

    // ── Animation Parsing with Normalization ─────────────────────────────

    private fun parseAnimation(
        json: JsonObject,
        defAnimation: JsonObject,
        binaryBuffer: ByteBuffer?,
        humanoidNodeMap: Map<Int, String>,
        expressionNodeMap: Map<Int, String>,
        worldRotMap: Map<String, FloatArray>,
        restHipsPosition: FloatArray
    ): VrmaAnimation {
        val channels = defAnimation.getAsJsonArray("channels") ?: return VrmaAnimation(0f, emptyList(), emptyList(), restHipsPosition)
        val samplers = defAnimation.getAsJsonArray("samplers") ?: return VrmaAnimation(0f, emptyList(), emptyList(), restHipsPosition)

        val humanoidTracks = mutableListOf<HumanoidTrack>()
        val expressionTracks = mutableListOf<ExpressionTrack>()
        var maxDuration = 0f

        for (i in 0 until channels.size()) {
            val channel = channels[i].asJsonObject
            val target = channel.getAsJsonObject("target") ?: continue
            val nodeIndex = target.get("node")?.asInt ?: continue
            val path = target.get("path")?.asString ?: continue
            val samplerIndex = channel.get("sampler")?.asInt ?: continue
            if (samplerIndex >= samplers.size()) continue

            val sampler = samplers[samplerIndex].asJsonObject
            val times = readAccessorData(json, binaryBuffer, sampler.get("input")?.asInt ?: continue) ?: continue
            val values = readAccessorData(json, binaryBuffer, sampler.get("output")?.asInt ?: continue) ?: continue
            if (times.isNotEmpty()) maxDuration = maxOf(maxDuration, times.last())

            // Humanoid bone
            val boneName = humanoidNodeMap[nodeIndex]
            if (boneName != null) {
                if (path == "rotation") {
                    // Normalize rotation: parentWorldRot * rawRot * inv(boneWorldRot)
                    // (three-vrm VRMAnimationLoaderPlugin._parseAnimation)
                    val boneWorldRot = worldRotMap[boneName] ?: floatArrayOf(0f, 0f, 0f, 1f)
                    val boneWorldRotInv = quatInverse(boneWorldRot)

                    // Find parent bone in humanoid map
                    var parentBoneName: String? = BONE_PARENT_MAP[boneName]
                    while (parentBoneName != null && !worldRotMap.containsKey(parentBoneName)) {
                        parentBoneName = BONE_PARENT_MAP[parentBoneName]
                    }
                    val parentWorldRot = worldRotMap[parentBoneName ?: "hipsParent"]
                        ?: floatArrayOf(0f, 0f, 0f, 1f)

                    // Transform each keyframe value
                    val normalizedValues = FloatArray(values.size)
                    for (k in 0 until values.size / 4) {
                        val off = k * 4
                        val raw = floatArrayOf(values[off], values[off + 1], values[off + 2], values[off + 3])
                        // normalized = parentWorldRot * raw * inv(boneWorldRot)
                        val result = quatMultiply(quatMultiply(parentWorldRot, raw), boneWorldRotInv)
                        normalizedValues[off] = result[0]
                        normalizedValues[off + 1] = result[1]
                        normalizedValues[off + 2] = result[2]
                        normalizedValues[off + 3] = result[3]
                    }

                    humanoidTracks.add(HumanoidTrack(boneName, path, times, normalizedValues))
                } else if (path == "translation" && boneName == "hips") {
                    // Transform hips translation by hipsParent world matrix
                    // For simplicity, just rotate by hipsParent world rotation
                    val hipsParentRot = worldRotMap["hipsParent"] ?: floatArrayOf(0f, 0f, 0f, 1f)
                    val transformedValues = FloatArray(values.size)
                    for (k in 0 until values.size / 3) {
                        val off = k * 3
                        val v = floatArrayOf(values[off], values[off + 1], values[off + 2])
                        val rotated = rotateVec3ByQuat(v, hipsParentRot)
                        transformedValues[off] = rotated[0]
                        transformedValues[off + 1] = rotated[1]
                        transformedValues[off + 2] = rotated[2]
                    }
                    humanoidTracks.add(HumanoidTrack(boneName, path, times, transformedValues))
                }
                continue
            }

            // Expression
            val exprName = expressionNodeMap[nodeIndex]
            if (exprName != null && path == "translation") {
                val weights = FloatArray(values.size / 3) { j -> values[j * 3] }
                expressionTracks.add(ExpressionTrack(exprName, times, weights))
            }
        }

        Log.i(TAG, "Parsed VRMA: ${maxDuration}s, ${humanoidTracks.size} bone tracks, ${expressionTracks.size} expression tracks")
        return VrmaAnimation(maxDuration, humanoidTracks, expressionTracks, restHipsPosition)
    }

    // ── Accessor Data Reading ────────────────────────────────────────────

    private fun readAccessorData(json: JsonObject, buf: ByteBuffer?, idx: Int): FloatArray? {
        if (buf == null) return null
        val accessors = json.getAsJsonArray("accessors") ?: return null
        if (idx >= accessors.size()) return null
        val accessor = accessors[idx].asJsonObject

        val bvIdx = accessor.get("bufferView")?.asInt ?: return null
        val compType = accessor.get("componentType")?.asInt ?: return null
        val count = accessor.get("count")?.asInt ?: return null
        val type = accessor.get("type")?.asString ?: return null
        val accOffset = accessor.get("byteOffset")?.asInt ?: 0
        if (compType != COMPONENT_TYPE_FLOAT) return null

        val elemSize = ACCESSOR_TYPE_SIZE[type] ?: return null
        val bvs = json.getAsJsonArray("bufferViews") ?: return null
        if (bvIdx >= bvs.size()) return null
        val bv = bvs[bvIdx].asJsonObject

        val byteOffset = (bv.get("byteOffset")?.asInt ?: 0) + accOffset
        val byteStride = bv.get("byteStride")?.asInt ?: (elemSize * 4)
        val result = FloatArray(count * elemSize)

        try {
            for (i in 0 until count) {
                buf.position(byteOffset + i * byteStride)
                for (j in 0 until elemSize) result[i * elemSize + j] = buf.float
            }
        } catch (e: Exception) { Log.e(TAG, "Error reading accessor", e); return null }
        return result
    }

    // ── Quaternion Utilities ─────────────────────────────────────────────

    private fun quatMultiply(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz
        )
    }

    private fun quatInverse(q: FloatArray): FloatArray =
        floatArrayOf(-q[0], -q[1], -q[2], q[3])

    private fun rotateVec3ByQuat(v: FloatArray, q: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val ix = qw * v[0] + qy * v[2] - qz * v[1]
        val iy = qw * v[1] + qz * v[0] - qx * v[2]
        val iz = qw * v[2] + qx * v[1] - qy * v[0]
        val iw = -qx * v[0] - qy * v[1] - qz * v[2]
        return floatArrayOf(
            ix * qw + iw * -qx + iy * -qz - iz * -qy,
            iy * qw + iw * -qy + iz * -qx - ix * -qz,
            iz * qw + iw * -qz + ix * -qy - iy * -qx
        )
    }
}
