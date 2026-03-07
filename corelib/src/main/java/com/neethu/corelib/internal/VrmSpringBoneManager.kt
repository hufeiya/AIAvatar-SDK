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
import kotlin.math.max
import kotlin.math.abs

/**
 * VRM Spring Bone physics manager.
 *
 * Implements the `VRMC_springBone` extension to simulate secondary motion
 * (hair, clothing, ribbons) using Verlet integration with collision.
 *
 * Algorithm ported from vrm-c/UniVRM:
 * - UpdateFastSpringBoneJob.cs  (Verlet integration + rotation recovery)
 * - SpringBoneCollision.cs      (Sphere / Capsule collision response)
 * - SpringBoneJointInit.cs      (Initialization + bone axis computation)
 *
 * The simulation runs every frame after animation updates and before rendering.
 */
internal class VrmSpringBoneManager(
    private val engine: Engine
) {
    companion object {
        private const val TAG = "SpringBone"
        private const val CHUNK_TYPE_JSON = 0x4E4F534A
        /** Length of the virtual end bone as a fraction of parent bone length. */
        private const val TAIL_APPROX_LENGTH_RATIO = 0.07f
        private const val TAIL_APPROX_MIN_LENGTH = 0.01f
    }

    // ── Data Classes ─────────────────────────────────────────────────────

    /** Collider shape: sphere (tail == null) or capsule (tail != null). */
    private data class SpringCollider(
        val nodeIndex: Int,
        val offset: FloatArray,     // [x,y,z] in bone-local space
        val radius: Float,
        val tail: FloatArray?       // [x,y,z] in bone-local space, null = sphere
    )

    private data class SpringColliderGroup(
        val name: String?,
        val colliderIndices: List<Int>
    )

    /** Joint parameters from the VRMC_springBone spec. */
    private data class SpringJointParams(
        val nodeIndex: Int,
        val stiffness: Float,
        val gravityPower: Float,
        val gravityDir: FloatArray,  // [x,y,z]
        val dragForce: Float,
        val hitRadius: Float
    )

    /** A spring chain: ordered joints + associated collider groups. */
    private data class SpringChain(
        val name: String?,
        val joints: List<SpringJointParams>,
        val colliderGroupIndices: List<Int>
    )

    /** Runtime state for each joint in a chain (updated every frame). */
    private class JointState(
        val entity: Int,              // Filament entity
        val boneLength: Float,
        val boneAxis: FloatArray,     // local-space direction [x,y,z]
        val restLocalQuat: FloatArray, // [x,y,z,w]
        val restLocalMat: FloatArray, // 4x4 column-major
        var prevTail: FloatArray,     // world position [x,y,z]
        var currentTail: FloatArray   // world position [x,y,z]
    )

    /** Resolved runtime spring chain with state for each joint. */
    private class RuntimeSpring(
        val jointStates: List<JointState>,
        val jointParams: List<SpringJointParams>,
        val colliders: List<SpringCollider>
    )

    // ── Parsed Data ──────────────────────────────────────────────────────

    private var colliders: List<SpringCollider> = emptyList()
    private var colliderGroups: List<SpringColliderGroup> = emptyList()
    private var springChains: List<SpringChain> = emptyList()

    // ── Runtime State ────────────────────────────────────────────────────

    private var runtimeSprings: List<RuntimeSpring> = emptyList()
    private var nodeEntities: Map<Int, Int> = emptyMap()  // glTF node index → entity
    private var isEnabled: Boolean = true
    private var isInitialized: Boolean = false

    // ── Public API ───────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) { isEnabled = enabled }

    /**
     * Parse VRMC_springBone extension from GLB bytes.
     */
    fun parseFromGlb(glbBytes: ByteArray) {
        val json = parseGlbJson(glbBytes) ?: return
        val ext = json.getAsJsonObject("extensions") ?: return

        // Try VRM 1.0: VRMC_springBone
        val springBoneExt = ext.getAsJsonObject("VRMC_springBone")
        if (springBoneExt != null) {
            parseVrmc10SpringBone(springBoneExt)
            return
        }

        // Try VRM 0.x: VRM.secondaryAnimation
        val vrm0Ext = ext.getAsJsonObject("VRM")
        val secAnim = vrm0Ext?.getAsJsonObject("secondaryAnimation")
        if (secAnim != null) {
            parseVrm0SecondaryAnimation(secAnim)
            return
        }

        Log.i(TAG, "No spring bone data found in model")
    }

    /**
     * Bind parsed spring bone data to a loaded Filament asset.
     * Must be called after parseFromGlb and after the asset is fully loaded.
     */
    fun bindToAsset(asset: FilamentAsset, glbBytes: ByteArray) {
        if (springChains.isEmpty()) {
            Log.i(TAG, "No spring chains to bind")
            return
        }

        val tm = engine.transformManager
        val json = parseGlbJson(glbBytes) ?: return
        val nodes = json.getAsJsonArray("nodes") ?: return

        // Build node index → entity map
        val nodeEntityMap = mutableMapOf<Int, Int>()
        for (i in 0 until nodes.size()) {
            val nodeName = nodes[i].asJsonObject.get("name")?.asString ?: continue
            val entity = asset.getFirstEntityByName(nodeName)
            if (entity != 0) {
                nodeEntityMap[i] = entity
            }
        }
        nodeEntities = nodeEntityMap

        // Build runtime springs
        val springs = mutableListOf<RuntimeSpring>()

        for (chain in springChains) {
            val jointStates = mutableListOf<JointState>()
            val validParams = mutableListOf<SpringJointParams>()

            for (i in chain.joints.indices) {
                val joint = chain.joints[i]
                val entity = nodeEntityMap[joint.nodeIndex] ?: continue
                val instance = tm.getInstance(entity)
                if (instance == 0) continue

                // Get rest-pose local transform
                val localMat = FloatArray(16)
                tm.getTransform(instance, localMat)
                val localQuat = matrixToQuaternion(localMat)

                // Get world position of this bone
                val worldMat = FloatArray(16)
                tm.getWorldTransform(instance, worldMat)
                val headPos = floatArrayOf(worldMat[12], worldMat[13], worldMat[14])

                // Determine bone axis and length by looking at the next joint's world position
                var boneAxis: FloatArray
                var boneLength: Float

                val nextJoint = if (i + 1 < chain.joints.size) chain.joints[i + 1] else null
                val nextEntity = nextJoint?.let { nodeEntityMap[it.nodeIndex] }

                if (nextEntity != null && nextEntity != 0) {
                    val nextInstance = tm.getInstance(nextEntity)
                    if (nextInstance != 0) {
                        val nextWorldMat = FloatArray(16)
                        tm.getWorldTransform(nextInstance, nextWorldMat)
                        val childWorldPos = floatArrayOf(nextWorldMat[12], nextWorldMat[13], nextWorldMat[14])

                        // World-space direction from head to child
                        val worldDir = floatArrayOf(
                            childWorldPos[0] - headPos[0],
                            childWorldPos[1] - headPos[1],
                            childWorldPos[2] - headPos[2]
                        )
                        boneLength = vecLength(worldDir)

                        if (boneLength > 1e-6f) {
                            // Convert world direction to local space
                            // localDir = inv(worldRot) * worldDir
                            val worldQuat = matrixToQuaternion(worldMat)
                            val invWorldQuat = quatInverse(worldQuat)
                            boneAxis = quatRotateVec(invWorldQuat, worldDir)
                            val axisLen = vecLength(boneAxis)
                            if (axisLen > 1e-6f) {
                                boneAxis[0] /= axisLen
                                boneAxis[1] /= axisLen
                                boneAxis[2] /= axisLen
                            }
                        } else {
                            boneAxis = floatArrayOf(0f, -1f, 0f)
                            boneLength = TAIL_APPROX_MIN_LENGTH
                        }
                    } else {
                        boneAxis = floatArrayOf(0f, -1f, 0f)
                        boneLength = TAIL_APPROX_MIN_LENGTH
                    }
                } else {
                    // Last joint in chain: create a virtual tail
                    // Use the parent bone direction scaled by TAIL_APPROX_LENGTH_RATIO
                    if (jointStates.isNotEmpty()) {
                        val prevState = jointStates.last()
                        boneLength = max(prevState.boneLength * TAIL_APPROX_LENGTH_RATIO, TAIL_APPROX_MIN_LENGTH)
                        boneAxis = prevState.boneAxis.clone()
                    } else {
                        boneAxis = floatArrayOf(0f, -1f, 0f)
                        boneLength = TAIL_APPROX_MIN_LENGTH
                    }
                }

                // Initial tail position in world space
                val worldQuat = matrixToQuaternion(worldMat)
                val worldBoneDir = quatRotateVec(worldQuat, boneAxis)
                val tailPos = floatArrayOf(
                    headPos[0] + worldBoneDir[0] * boneLength,
                    headPos[1] + worldBoneDir[1] * boneLength,
                    headPos[2] + worldBoneDir[2] * boneLength
                )

                jointStates.add(JointState(
                    entity = entity,
                    boneLength = boneLength,
                    boneAxis = boneAxis,
                    restLocalQuat = localQuat,
                    restLocalMat = localMat.clone(),
                    prevTail = tailPos.clone(),
                    currentTail = tailPos.clone()
                ))
                validParams.add(joint)
            }

            if (jointStates.isNotEmpty()) {
                // Resolve colliders for this chain
                val chainColliders = mutableListOf<SpringCollider>()
                for (cgIdx in chain.colliderGroupIndices) {
                    if (cgIdx < colliderGroups.size) {
                        for (cIdx in colliderGroups[cgIdx].colliderIndices) {
                            if (cIdx < colliders.size) {
                                chainColliders.add(colliders[cIdx])
                            }
                        }
                    }
                }

                springs.add(RuntimeSpring(jointStates, validParams, chainColliders))
            }
        }

        runtimeSprings = springs
        isInitialized = true
        Log.i(TAG, "Bound ${springs.size} spring chains, ${springs.sumOf { it.jointStates.size }} joints")
    }

    // ── Physics Update (called every frame) ──────────────────────────────

    /**
     * Update spring bone simulation. Call after animation update, before render.
     * @param deltaTime Seconds since last frame, clamped to [0.001, 0.05].
     */
    fun update(deltaTime: Float) {
        if (!isEnabled || !isInitialized) return

        val tm = engine.transformManager

        for (spring in runtimeSprings) {
            for (i in spring.jointStates.indices) {
                val state = spring.jointStates[i]
                val params = spring.jointParams[i]

                val instance = tm.getInstance(state.entity)
                if (instance == 0) continue

                // Get current world transform of the head bone (after animation)
                val worldMat = FloatArray(16)
                tm.getWorldTransform(instance, worldMat)
                val headPos = floatArrayOf(worldMat[12], worldMat[13], worldMat[14])

                // Get parent rotation (from the parent's world transform)
                val parentInstance = tm.getParent(instance)
                val parentRot: FloatArray
                if (parentInstance != 0) {
                    val parentWorldMat = FloatArray(16)
                    tm.getWorldTransform(parentInstance, parentWorldMat)
                    parentRot = matrixToQuaternion(parentWorldMat)
                } else {
                    parentRot = QUAT_IDENTITY
                }

                // ── Verlet Integration ──
                // nextTail = currentTail
                //   + (currentTail - prevTail) * (1 - dragForce)           // inertia
                //   + parentRot * localRot * boneAxis * stiffness * dt     // stiffness
                //   + gravityDir * gravityPower * dt                       // gravity

                val inertia = floatArrayOf(
                    (state.currentTail[0] - state.prevTail[0]) * (1f - params.dragForce),
                    (state.currentTail[1] - state.prevTail[1]) * (1f - params.dragForce),
                    (state.currentTail[2] - state.prevTail[2]) * (1f - params.dragForce)
                )

                // Stiffness: parent world rotation * local rest rotation * bone axis
                val combinedRot = quatMultiply(parentRot, state.restLocalQuat)
                val stiffnessDir = quatRotateVec(combinedRot, state.boneAxis)
                val stiffness = floatArrayOf(
                    stiffnessDir[0] * params.stiffness * deltaTime,
                    stiffnessDir[1] * params.stiffness * deltaTime,
                    stiffnessDir[2] * params.stiffness * deltaTime
                )

                // Gravity
                val gravity = floatArrayOf(
                    params.gravityDir[0] * params.gravityPower * deltaTime,
                    params.gravityDir[1] * params.gravityPower * deltaTime,
                    params.gravityDir[2] * params.gravityPower * deltaTime
                )

                var nextTail = floatArrayOf(
                    state.currentTail[0] + inertia[0] + stiffness[0] + gravity[0],
                    state.currentTail[1] + inertia[1] + stiffness[1] + gravity[1],
                    state.currentTail[2] + inertia[2] + stiffness[2] + gravity[2]
                )

                // ── Length Constraint ──
                // Force nextTail to be exactly boneLength from headPos
                nextTail = constrainLength(headPos, nextTail, state.boneLength)

                // ── Collision ──
                for (collider in spring.colliders) {
                    val colliderEntity = nodeEntities[collider.nodeIndex]
                    if (colliderEntity == null || colliderEntity == 0) continue
                    val colliderInstance = tm.getInstance(colliderEntity)
                    if (colliderInstance == 0) continue

                    val colliderWorldMat = FloatArray(16)
                    tm.getWorldTransform(colliderInstance, colliderWorldMat)

                    val result = resolveCollision(
                        headPos, nextTail, state.boneLength, params.hitRadius,
                        collider, colliderWorldMat
                    )
                    if (result != null) {
                        nextTail = result
                    }
                }

                // ── Update State ──
                state.prevTail = state.currentTail.clone()
                state.currentTail = nextTail

                // ── Rotation Recovery ──
                // Compute rotation that maps the rest-pose bone direction to the
                // simulated direction (headPos → nextTail)
                val currentDir = floatArrayOf(
                    nextTail[0] - headPos[0],
                    nextTail[1] - headPos[1],
                    nextTail[2] - headPos[2]
                )
                val dirLen = vecLength(currentDir)
                if (dirLen > 1e-6f) {
                    currentDir[0] /= dirLen
                    currentDir[1] /= dirLen
                    currentDir[2] /= dirLen
                }

                // Rest direction in world space
                val restDir = quatRotateVec(combinedRot, state.boneAxis)
                val restDirLen = vecLength(restDir)
                if (restDirLen > 1e-6f) {
                    restDir[0] /= restDirLen
                    restDir[1] /= restDirLen
                    restDir[2] /= restDirLen
                }

                // rotation = fromToRotation(restDir, currentDir) * parentRot * localRot
                val fromTo = fromToRotation(restDir, currentDir)
                val newWorldRot = quatMultiply(fromTo, combinedRot)

                // Convert to local rotation: localRot = inv(parentWorldRot) * worldRot
                val invParentRot = quatInverse(parentRot)
                val newLocalRot = quatMultiply(invParentRot, newWorldRot)

                // Write back to Filament TransformManager, preserving translation
                val localMat = FloatArray(16)
                tm.getTransform(instance, localMat)
                val tx = localMat[12]; val ty = localMat[13]; val tz = localMat[14]
                quaternionToMatrix(newLocalRot, localMat)
                localMat[12] = tx; localMat[13] = ty; localMat[14] = tz
                tm.setTransform(instance, localMat)
            }
        }
    }

    /**
     * Reset all spring bone states to their rest pose positions.
     * Call this when switching animations to prevent spring bones from "exploding".
     */
    fun reset() {
        if (!isInitialized) return
        val tm = engine.transformManager

        for (spring in runtimeSprings) {
            for (state in spring.jointStates) {
                val instance = tm.getInstance(state.entity)
                if (instance == 0) continue

                // Restore rest local transform
                tm.setTransform(instance, state.restLocalMat.clone())

                // Recompute tail position from restored transform
                val worldMat = FloatArray(16)
                tm.getWorldTransform(instance, worldMat)
                val headPos = floatArrayOf(worldMat[12], worldMat[13], worldMat[14])
                val worldQuat = matrixToQuaternion(worldMat)
                val worldBoneDir = quatRotateVec(worldQuat, state.boneAxis)
                val tailPos = floatArrayOf(
                    headPos[0] + worldBoneDir[0] * state.boneLength,
                    headPos[1] + worldBoneDir[1] * state.boneLength,
                    headPos[2] + worldBoneDir[2] * state.boneLength
                )
                state.prevTail = tailPos.clone()
                state.currentTail = tailPos.clone()
            }
        }
    }

    // ── Collision Detection ──────────────────────────────────────────────

    /**
     * Check collision between nextTail and a collider. Returns corrected nextTail
     * position if collision occurred, null otherwise.
     */
    private fun resolveCollision(
        headPos: FloatArray, nextTail: FloatArray,
        boneLength: Float, jointRadius: Float,
        collider: SpringCollider, colliderWorldMat: FloatArray
    ): FloatArray? {
        // Transform collider offset to world space
        val colliderWorldPos = mat4MulPoint(colliderWorldMat, collider.offset)

        if (collider.tail != null) {
            // Capsule collider
            val colliderWorldTail = mat4MulPoint(colliderWorldMat, collider.tail)
            return resolveCapsuleCollision(
                headPos, nextTail, boneLength, jointRadius,
                colliderWorldPos, colliderWorldTail, collider.radius
            )
        } else {
            // Sphere collider
            return resolveSphereCollision(
                headPos, nextTail, boneLength, jointRadius,
                colliderWorldPos, collider.radius
            )
        }
    }

    /**
     * Sphere collision: if nextTail is inside collider sphere, push it out.
     * Ported from UniVRM SpringBoneCollision.TryResolveSphereCollision.
     */
    private fun resolveSphereCollision(
        headPos: FloatArray, nextTail: FloatArray,
        boneLength: Float, jointRadius: Float,
        spherePos: FloatArray, sphereRadius: Float
    ): FloatArray? {
        val r = jointRadius + sphereRadius
        val dx = nextTail[0] - spherePos[0]
        val dy = nextTail[1] - spherePos[1]
        val dz = nextTail[2] - spherePos[2]
        val distSq = dx * dx + dy * dy + dz * dz

        if (distSq <= r * r) {
            // Hit — push out along normal direction
            val dist = sqrt(distSq.toDouble()).toFloat()
            val nx: Float; val ny: Float; val nz: Float
            if (dist > 1e-6f) {
                nx = dx / dist; ny = dy / dist; nz = dz / dist
            } else {
                nx = 0f; ny = 1f; nz = 0f
            }
            val posFromCollider = floatArrayOf(
                spherePos[0] + nx * r,
                spherePos[1] + ny * r,
                spherePos[2] + nz * r
            )
            // Constrain to bone length
            return constrainLength(headPos, posFromCollider, boneLength)
        }
        return null
    }

    /**
     * Capsule collision: find closest point on segment, then sphere-check at that point.
     * Ported from UniVRM SpringBoneCollision.TryResolveCapsuleCollision.
     */
    private fun resolveCapsuleCollision(
        headPos: FloatArray, nextTail: FloatArray,
        boneLength: Float, jointRadius: Float,
        capsuleStart: FloatArray, capsuleEnd: FloatArray, capsuleRadius: Float
    ): FloatArray? {
        val dx = capsuleEnd[0] - capsuleStart[0]
        val dy = capsuleEnd[1] - capsuleStart[1]
        val dz = capsuleEnd[2] - capsuleStart[2]
        val segLenSq = dx * dx + dy * dy + dz * dz

        if (segLenSq < 1e-10f) {
            // Degenerate capsule → sphere
            return resolveSphereCollision(
                headPos, nextTail, boneLength, jointRadius,
                capsuleStart, capsuleRadius
            )
        }

        // Project nextTail onto the capsule segment
        val segLen = sqrt(segLenSq.toDouble()).toFloat()
        val px = dx / segLen; val py = dy / segLen; val pz = dz / segLen
        val qx = nextTail[0] - capsuleStart[0]
        val qy = nextTail[1] - capsuleStart[1]
        val qz = nextTail[2] - capsuleStart[2]
        val dot = px * qx + py * qy + pz * qz

        val closestPoint: FloatArray = when {
            dot <= 0f -> capsuleStart
            dot >= segLen -> capsuleEnd
            else -> floatArrayOf(
                capsuleStart[0] + px * dot,
                capsuleStart[1] + py * dot,
                capsuleStart[2] + pz * dot
            )
        }

        return resolveSphereCollision(
            headPos, nextTail, boneLength, jointRadius,
            closestPoint, capsuleRadius
        )
    }

    // ── Length Constraint ─────────────────────────────────────────────────

    private fun constrainLength(headPos: FloatArray, tail: FloatArray, length: Float): FloatArray {
        val dx = tail[0] - headPos[0]
        val dy = tail[1] - headPos[1]
        val dz = tail[2] - headPos[2]
        val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        if (dist < 1e-8f) {
            // Tail collapsed onto head — push it straight down
            return floatArrayOf(headPos[0], headPos[1] - length, headPos[2])
        }
        val scale = length / dist
        return floatArrayOf(
            headPos[0] + dx * scale,
            headPos[1] + dy * scale,
            headPos[2] + dz * scale
        )
    }

    // ── JSON Parsing: VRM 1.0 ────────────────────────────────────────────

    private fun parseVrmc10SpringBone(springBoneExt: JsonObject) {
        // Parse colliders
        val collidersArray = springBoneExt.getAsJsonArray("colliders")
        val parsedColliders = mutableListOf<SpringCollider>()
        collidersArray?.forEach { el ->
            val obj = el.asJsonObject
            val nodeIdx = obj.get("node")?.asInt ?: return@forEach
            val shape = obj.getAsJsonObject("shape") ?: return@forEach

            val sphere = shape.getAsJsonObject("sphere")
            val capsule = shape.getAsJsonObject("capsule")

            if (sphere != null) {
                val offset = parseVec3(sphere.getAsJsonArray("offset"))
                val radius = sphere.get("radius")?.asFloat ?: 0f
                parsedColliders.add(SpringCollider(nodeIdx, offset, radius, null))
            } else if (capsule != null) {
                val offset = parseVec3(capsule.getAsJsonArray("offset"))
                val radius = capsule.get("radius")?.asFloat ?: 0f
                val tail = parseVec3(capsule.getAsJsonArray("tail"))
                parsedColliders.add(SpringCollider(nodeIdx, offset, radius, tail))
            }
        }
        colliders = parsedColliders

        // Parse collider groups
        val groupsArray = springBoneExt.getAsJsonArray("colliderGroups")
        val parsedGroups = mutableListOf<SpringColliderGroup>()
        groupsArray?.forEach { el ->
            val obj = el.asJsonObject
            val name = obj.get("name")?.asString
            val indices = mutableListOf<Int>()
            obj.getAsJsonArray("colliders")?.forEach { idx -> indices.add(idx.asInt) }
            parsedGroups.add(SpringColliderGroup(name, indices))
        }
        colliderGroups = parsedGroups

        // Parse springs
        val springsArray = springBoneExt.getAsJsonArray("springs")
        val parsedChains = mutableListOf<SpringChain>()
        springsArray?.forEach { el ->
            val obj = el.asJsonObject
            val name = obj.get("name")?.asString
            val joints = mutableListOf<SpringJointParams>()

            obj.getAsJsonArray("joints")?.forEach { jEl ->
                val jObj = jEl.asJsonObject
                val nodeIdx = jObj.get("node")?.asInt ?: return@forEach
                joints.add(SpringJointParams(
                    nodeIndex = nodeIdx,
                    stiffness = jObj.get("stiffness")?.asFloat ?: 1.0f,
                    gravityPower = jObj.get("gravityPower")?.asFloat ?: 0f,
                    gravityDir = parseVec3(jObj.getAsJsonArray("gravityDir"), default = floatArrayOf(0f, -1f, 0f)),
                    dragForce = jObj.get("dragForce")?.asFloat ?: 0.5f,
                    hitRadius = jObj.get("hitRadius")?.asFloat ?: 0f
                ))
            }

            val colliderGroupIndices = mutableListOf<Int>()
            obj.getAsJsonArray("colliderGroups")?.forEach { idx ->
                colliderGroupIndices.add(idx.asInt)
            }

            if (joints.isNotEmpty()) {
                parsedChains.add(SpringChain(name, joints, colliderGroupIndices))
            }
        }
        springChains = parsedChains

        Log.i(TAG, "Parsed VRM 1.0: ${colliders.size} colliders, " +
                "${colliderGroups.size} groups, ${springChains.size} springs")
    }

    // ── JSON Parsing: VRM 0.x ────────────────────────────────────────────

    private fun parseVrm0SecondaryAnimation(secAnim: JsonObject) {
        // Parse collider groups (VRM 0.x: colliderGroups[] with node + colliders[])
        val groupsArray = secAnim.getAsJsonArray("colliderGroups")
        val parsedColliders = mutableListOf<SpringCollider>()
        val parsedGroups = mutableListOf<SpringColliderGroup>()

        groupsArray?.forEach { el ->
            val obj = el.asJsonObject
            val nodeIdx = obj.get("node")?.asInt ?: return@forEach
            val groupStartIdx = parsedColliders.size
            val indices = mutableListOf<Int>()

            obj.getAsJsonArray("colliders")?.forEach { cEl ->
                val cObj = cEl.asJsonObject
                val offset = parseVec3(cObj.getAsJsonArray("offset"))
                val radius = cObj.get("radius")?.asFloat ?: 0f
                indices.add(parsedColliders.size)
                parsedColliders.add(SpringCollider(nodeIdx, offset, radius, null))
            }

            parsedGroups.add(SpringColliderGroup(null, indices))
        }
        colliders = parsedColliders
        colliderGroups = parsedGroups

        // Parse bone groups (VRM 0.x: boneGroups[])
        val boneGroupsArray = secAnim.getAsJsonArray("boneGroups")
        val parsedChains = mutableListOf<SpringChain>()

        boneGroupsArray?.forEach { el ->
            val obj = el.asJsonObject
            val comment = obj.get("comment")?.asString
            val stiffness = obj.get("stiffiness")?.asFloat ?: obj.get("stiffness")?.asFloat ?: 1.0f
            val gravityPower = obj.get("gravityPower")?.asFloat ?: 0f
            val gravityDir = parseVec3(obj.getAsJsonArray("gravityDir"), default = floatArrayOf(0f, -1f, 0f))
            val dragForce = obj.get("dragForce")?.asFloat ?: 0.5f
            val hitRadius = obj.get("hitRadius")?.asFloat ?: 0f

            val colliderGroupIndices = mutableListOf<Int>()
            obj.getAsJsonArray("colliderGroups")?.forEach { idx ->
                colliderGroupIndices.add(idx.asInt)
            }

            // VRM 0.x: "bones" is an array of root nodes;
            // each root + its descendants form one chain.
            // For simplicity we treat each root as a chain with just that node.
            // The full tree traversal would need the glTF node children info,
            // which we handle in bindToAsset by walking the Filament transform hierarchy.
            obj.getAsJsonArray("bones")?.forEach { boneEl ->
                val nodeIdx = boneEl.asInt
                val joints = mutableListOf<SpringJointParams>()
                joints.add(SpringJointParams(nodeIdx, stiffness, gravityPower, gravityDir, dragForce, hitRadius))
                parsedChains.add(SpringChain(comment, joints, colliderGroupIndices))
            }
        }
        springChains = parsedChains

        Log.i(TAG, "Parsed VRM 0.x: ${colliders.size} colliders, " +
                "${colliderGroups.size} groups, ${springChains.size} springs")
    }

    /**
     * For VRM 0.x, expand single-node chains by walking the Filament transform
     * hierarchy to discover child nodes.
     */
    fun expandVrm0Chains(asset: FilamentAsset) {
        if (springChains.isEmpty()) return

        val tm = engine.transformManager
        val expanded = mutableListOf<SpringChain>()

        for (chain in springChains) {
            if (chain.joints.size != 1) {
                expanded.add(chain)
                continue
            }

            val rootJoint = chain.joints[0]
            val rootEntity = nodeEntities[rootJoint.nodeIndex]
            if (rootEntity == null) {
                expanded.add(chain)
                continue
            }

            // Walk down the first-child chain
            val allJoints = mutableListOf(rootJoint)
            var currentEntity: Int = rootEntity

            // Find node index → entity reverse map
            val entityToNode = nodeEntities.entries.associateBy({ it.value }, { it.key })

            while (true) {
                val inst = tm.getInstance(currentEntity)
                if (inst == 0) break

                var childEntity = 0
                val entities = asset.entities
                for (i in entities.indices) {
                    val e = entities[i]
                    val ci = tm.getInstance(e)
                    if (ci != 0 && tm.getParent(ci) == inst) {
                        childEntity = e
                        break
                    }
                }

                if (childEntity == 0) break

                val childNodeIdx = entityToNode[childEntity] ?: break

                allJoints.add(SpringJointParams(
                    childNodeIdx, rootJoint.stiffness, rootJoint.gravityPower,
                    rootJoint.gravityDir, rootJoint.dragForce, rootJoint.hitRadius
                ))
                currentEntity = childEntity
            }

            expanded.add(SpringChain(chain.name, allJoints, chain.colliderGroupIndices))
        }

        springChains = expanded
    }

    // ── Vector / Quaternion Math ──────────────────────────────────────────

    private val QUAT_IDENTITY = floatArrayOf(0f, 0f, 0f, 1f)

    private fun vecLength(v: FloatArray): Float =
        sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()

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

    private fun quatInverse(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    /** Rotate a vector by a quaternion: q * v * q^-1 */
    private fun quatRotateVec(q: FloatArray, v: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val vx = v[0]; val vy = v[1]; val vz = v[2]

        // t = 2 * cross(q.xyz, v)
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)

        // result = v + qw * t + cross(q.xyz, t)
        return floatArrayOf(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx)
        )
    }

    /**
     * Compute quaternion that rotates vector `from` to vector `to`.
     * Both should be unit vectors.
     */
    private fun fromToRotation(from: FloatArray, to: FloatArray): FloatArray {
        val dot = from[0] * to[0] + from[1] * to[1] + from[2] * to[2]

        if (dot > 0.999999f) {
            // Vectors are nearly identical
            return QUAT_IDENTITY.clone()
        }

        if (dot < -0.999999f) {
            // Vectors are opposite — find an arbitrary perpendicular axis
            var perp = floatArrayOf(1f, 0f, 0f)
            if (abs(from[0]) > 0.9f) perp = floatArrayOf(0f, 1f, 0f)
            // cross(from, perp)
            val ax = from[1] * perp[2] - from[2] * perp[1]
            val ay = from[2] * perp[0] - from[0] * perp[2]
            val az = from[0] * perp[1] - from[1] * perp[0]
            val len = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
            return floatArrayOf(ax / len, ay / len, az / len, 0f)
        }

        // cross(from, to)
        val cx = from[1] * to[2] - from[2] * to[1]
        val cy = from[2] * to[0] - from[0] * to[2]
        val cz = from[0] * to[1] - from[1] * to[0]
        val w = 1f + dot
        val len = sqrt((cx * cx + cy * cy + cz * cz + w * w).toDouble()).toFloat()
        return floatArrayOf(cx / len, cy / len, cz / len, w / len)
    }

    /** Transform a point by a 4x4 column-major matrix. */
    private fun mat4MulPoint(m: FloatArray, p: FloatArray): FloatArray {
        return floatArrayOf(
            m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
            m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
            m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]
        )
    }

    private fun quaternionToMatrix(q: FloatArray, mat: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        mat[0] = 1f - (yy + zz); mat[1] = xy + wz;       mat[2] = xz - wy;       mat[3] = 0f
        mat[4] = xy - wz;        mat[5] = 1f - (xx + zz); mat[6] = yz + wx;       mat[7] = 0f
        mat[8] = xz + wy;        mat[9] = yz - wx;        mat[10] = 1f - (xx + yy); mat[11] = 0f
        mat[15] = 1f
    }

    private fun matrixToQuaternion(m: FloatArray): FloatArray {
        val m00 = m[0]; val m01 = m[4]; val m02 = m[8]
        val m10 = m[1]; val m11 = m[5]; val m12 = m[9]
        val m20 = m[2]; val m21 = m[6]; val m22 = m[10]
        val trace = m00 + m11 + m22

        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt((trace + 1f).toDouble()).toFloat() * 2f
            q[3] = s * 0.25f; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt((1f + m00 - m11 - m22).toDouble()).toFloat() * 2f
            q[3] = (m21 - m12) / s; q[0] = s * 0.25f; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt((1f + m11 - m00 - m22).toDouble()).toFloat() * 2f
            q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = s * 0.25f; q[2] = (m12 + m21) / s
        } else {
            val s = sqrt((1f + m22 - m00 - m11).toDouble()).toFloat() * 2f
            q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = s * 0.25f
        }
        val len = sqrt((q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).toDouble()).toFloat()
        if (len > 0f) { q[0] /= len; q[1] /= len; q[2] /= len; q[3] /= len }
        return q
    }

    // ── GLB JSON Parsing ─────────────────────────────────────────────────

    private fun parseVec3(arr: com.google.gson.JsonArray?, default: FloatArray = floatArrayOf(0f, 0f, 0f)): FloatArray {
        if (arr == null || arr.size() < 3) return default
        return floatArrayOf(arr[0].asFloat, arr[1].asFloat, arr[2].asFloat)
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
