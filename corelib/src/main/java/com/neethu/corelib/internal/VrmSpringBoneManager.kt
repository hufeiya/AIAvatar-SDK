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
 * Implements the `VRMC_springBone` (VRM 1.0) and `VRM.secondaryAnimation` (VRM 0.x)
 * extensions to simulate secondary motion (hair, clothing, ribbons) using Verlet
 * integration with collision.
 *
 * Ported from pixiv/three-vrm (VRMSpringBoneJoint.ts / VRMSpringBoneLoaderPlugin.ts):
 * - The Verlet tail state (`prevTail` / `currentTail`) lives in the spring's
 *   **center space** when the spring declares a `center` node, and in world space
 *   otherwise. Inertia is integrated in that space, so translating or teleporting
 *   the whole model produces no false spring reaction — most VRoid models set
 *   `center` to their Root node exactly for this purpose. Stiffness, gravity,
 *   length constraint and collision all run in world space.
 * - A joint's tail is the next joint node of its spring (VRM 1.0), the first
 *   hierarchy child (VRM 0.x subtree traversal), or a virtual tail 7 cm along
 *   the hierarchy parent→node direction when the joint has no child node.
 * - Collider radii and `hitRadius` are model-unit values and are scaled by each
 *   node's world scale (the renderer's `transformToUnitCube` scales the asset root).
 *
 * The simulation runs every frame after animation updates and before rendering.
 */
internal class VrmSpringBoneManager(
    private val engine: Engine
) {
    companion object {
        private const val TAG = "SpringBone"
        private const val CHUNK_TYPE_JSON = 0x4E4F534A
        /** Length of the virtual tail created for a joint with no child node (model units, per VRM spec). */
        private const val VIRTUAL_TAIL_LENGTH = 0.07f
        private const val VIRTUAL_TAIL_MIN_LENGTH = 0.01f
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

    /** Joint parameters from the spring bone spec, plus the resolved tail node. */
    private data class SpringJointParams(
        val nodeIndex: Int,
        val tailNodeIndex: Int?,     // glTF node whose position defines the tail; null = virtual tail
        val stiffness: Float,
        val gravityPower: Float,
        val gravityDir: FloatArray,  // [x,y,z]
        val dragForce: Float,
        val hitRadius: Float
    )

    /** A spring chain: ordered joints + associated collider groups + optional center node. */
    private data class SpringChain(
        val name: String?,
        val joints: List<SpringJointParams>,
        val colliderGroupIndices: List<Int>,
        val centerNodeIndex: Int?    // VRMC_springBone "center": verlet state space; null = world
    )

    /** Runtime state for each joint in a chain (updated every frame). */
    private class JointState(
        val entity: Int,              // Filament entity
        var boneLength: Float,        // world-unit constraint length, refreshed every frame
        val restBoneLength: Float,    // world-unit rest bone length (bind time, fixed)
        val boneAxis: FloatArray,     // joint-local rest direction [x,y,z]
        val restLocalQuat: FloatArray, // [x,y,z,w]
        val restLocalMat: FloatArray, // 4x4 column-major
        var prevTail: FloatArray,     // tail position in CENTER space [x,y,z]
        var currentTail: FloatArray,  // tail position in CENTER space [x,y,z]
        val hasVirtualTail: Boolean,  // no real tail node: keep the fixed 7cm length
        // World position of the TAIL NODE at the end of the previous frame, i.e. the
        // bone-snapped position headPos + boneDir * restBoneLength (three-vrm reads
        // child.matrixWorld here). NOT the verlet tail: the verlet tail is a free
        // particle and would make the measured length grow without bound while the
        // head keeps moving, locking the chain in the drag direction.
        val lastTailNodeWorld: FloatArray
    )

    /** Resolved runtime spring chain with state for each joint. */
    private class RuntimeSpring(
        val jointStates: List<JointState>,
        val jointParams: List<SpringJointParams>,
        val colliders: List<SpringCollider>,
        val centerEntity: Int         // 0 = simulate in world space
    ) {
        // Center-space conversion for the current frame. The center node is never a
        // spring joint, so its world transform does not change between substeps.
        var centerWorld: FloatArray? = null
        var centerWorldInv: FloatArray? = null
    }

    // ── Parsed Data ──────────────────────────────────────────────────────

    private var colliders: List<SpringCollider> = emptyList()
    private var colliderGroups: List<SpringColliderGroup> = emptyList()
    private var springChains: List<SpringChain> = emptyList()

    /** glTF node hierarchy, needed for VRM 0.x subtree expansion and virtual tails. */
    private var nodeChildren: Map<Int, List<Int>> = emptyMap()
    private var nodeParent: Map<Int, Int> = emptyMap()

    // ── Runtime State ────────────────────────────────────────────────────

    private var runtimeSprings: List<RuntimeSpring> = emptyList()
    private var nodeEntities: Map<Int, Int> = emptyMap()  // glTF node index → entity
    private var nodeNames: Map<Int, String> = emptyMap()  // glTF node index → name (diagnostics)
    private var isEnabled: Boolean = true
    private var isInitialized: Boolean = false

    // Diagnostics: when enabled, logs per-joint constraint length vs rest length and
    // a few strand directions once per second so on-device state is observable.
    private var debugLogEnabled: Boolean = false
    private var debugLogFrameCounter: Int = 0

    // ── Public API ───────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) { isEnabled = enabled }

    /** Enables once-per-second logcat diagnostics (tag "SpringBone") for on-device verification. */
    fun setDebugLogEnabled(enabled: Boolean) { debugLogEnabled = enabled }

    /**
     * Parse VRMC_springBone / VRM.secondaryAnimation extension from GLB bytes.
     */
    fun parseFromGlb(glbBytes: ByteArray) {
        val json = parseGlbJson(glbBytes) ?: return
        parseNodeHierarchy(json)

        val ext = json.getAsJsonObject("extensions") ?: return

        // Try VRM 1.0: VRMC_springBone
        val springBoneExt = ext.getAsJsonObject("VRMC_springBone")
        if (springBoneExt != null) {
            parseVrmc10SpringBone(springBoneExt)
            return
        }

        // Try VRM 0.x: VRM.secondaryAnimation
        val secAnim = ext.getAsJsonObject("VRM")?.getAsJsonObject("secondaryAnimation")
        if (secAnim != null) {
            parseVrm0SecondaryAnimation(secAnim)
            return
        }

        Log.i(TAG, "No spring bone data found in model")
    }

    /** Cache the glTF node hierarchy (children / parent maps). */
    private fun parseNodeHierarchy(json: JsonObject) {
        val nodes = json.getAsJsonArray("nodes") ?: return
        val children = mutableMapOf<Int, MutableList<Int>>()
        val parentMap = mutableMapOf<Int, Int>()
        for (i in 0 until nodes.size()) {
            val kids = nodes[i].asJsonObject.getAsJsonArray("children") ?: continue
            val list = mutableListOf<Int>()
            for (k in 0 until kids.size()) {
                val c = kids[k].asInt
                list.add(c)
                parentMap[c] = i
            }
            if (list.isNotEmpty()) children[i] = list
        }
        nodeChildren = children
        nodeParent = parentMap
    }

    /**
     * Bind parsed spring bone data to a loaded Filament asset.
     * Must be called after parseFromGlb and after the asset is fully loaded
     * (and after any root transform such as transformToUnitCube is applied).
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
        val nodeNameMap = mutableMapOf<Int, String>()
        for (i in 0 until nodes.size()) {
            val nodeName = nodes[i].asJsonObject.get("name")?.asString ?: continue
            val entity = asset.getFirstEntityByName(nodeName)
            if (entity != 0) {
                nodeEntityMap[i] = entity
                nodeNameMap[i] = nodeName
            }
        }
        nodeEntities = nodeEntityMap
        nodeNames = nodeNameMap

        // Build runtime springs
        val springs = mutableListOf<RuntimeSpring>()

        for (chain in springChains) {
            val jointStates = mutableListOf<JointState>()
            val validParams = mutableListOf<SpringJointParams>()

            // Center node defines the space the verlet state lives in (world space when absent)
            var centerEntity = 0
            var centerWorldInv: FloatArray? = null
            val centerIdx = chain.centerNodeIndex
            if (centerIdx != null) {
                val cEntity = nodeEntityMap[centerIdx]
                if (cEntity != null && cEntity != 0) {
                    val cInstance = tm.getInstance(cEntity)
                    if (cInstance != 0) {
                        val cWorld = FloatArray(16)
                        tm.getWorldTransform(cInstance, cWorld)
                        val cInv = mat4Invert(cWorld)
                        if (cInv != null) {
                            centerEntity = cEntity
                            centerWorldInv = cInv
                        } else {
                            Log.w(TAG, "Spring center world matrix is singular; simulating in world space")
                        }
                    }
                }
            }

            for (joint in chain.joints) {
                val entity = nodeEntityMap[joint.nodeIndex] ?: continue
                val instance = tm.getInstance(entity)
                if (instance == 0) continue

                // Rest-pose local transform
                val localMat = FloatArray(16)
                tm.getTransform(instance, localMat)
                val localQuat = matrixToQuaternion(localMat)

                // World position / rotation of this bone (rest pose at bind time)
                val worldMat = FloatArray(16)
                tm.getWorldTransform(instance, worldMat)
                val headPos = floatArrayOf(worldMat[12], worldMat[13], worldMat[14])
                val worldQuat = matrixToQuaternion(worldMat)
                val invWorldQuat = quatInverse(worldQuat)

                // Bone axis (joint-local) and length from the resolved tail node
                var boneAxis: FloatArray? = null
                var boneLength = 0f
                var isVirtualTail = false
                val tailEntity = joint.tailNodeIndex?.let { nodeEntityMap[it] }
                if (tailEntity != null && tailEntity != 0) {
                    val tailInstance = tm.getInstance(tailEntity)
                    if (tailInstance != 0) {
                        val tailWorld = FloatArray(16)
                        tm.getWorldTransform(tailInstance, tailWorld)
                        val worldDir = floatArrayOf(
                            tailWorld[12] - headPos[0],
                            tailWorld[13] - headPos[1],
                            tailWorld[14] - headPos[2]
                        )
                        boneLength = vecLength(worldDir)
                        if (boneLength > 1e-6f) {
                            boneAxis = normalizeVec(quatRotateVec(invWorldQuat, worldDir))
                        }
                    }
                }

                if (boneAxis == null) {
                    // Virtual tail: 7 cm along the hierarchy parent→node direction (VRM spec).
                    // The length is scaled into world units of the asset root.
                    isVirtualTail = true
                    val parentWorldPos = nodeParent[joint.nodeIndex]
                        ?.let { nodeEntityMap[it] }
                        ?.takeIf { it != 0 }
                        ?.let { pEntity ->
                            val pInstance = tm.getInstance(pEntity)
                            if (pInstance != 0) {
                                val pWorld = FloatArray(16)
                                tm.getWorldTransform(pInstance, pWorld)
                                floatArrayOf(pWorld[12], pWorld[13], pWorld[14])
                            } else null
                        }
                    val dir = if (parentWorldPos != null) {
                        normalizeVec(floatArrayOf(
                            headPos[0] - parentWorldPos[0],
                            headPos[1] - parentWorldPos[1],
                            headPos[2] - parentWorldPos[2]
                        ))
                    } else {
                        floatArrayOf(0f, -1f, 0f)
                    }
                    val worldScale = vecLength(floatArrayOf(worldMat[0], worldMat[1], worldMat[2]))
                    boneLength = max(VIRTUAL_TAIL_LENGTH * worldScale, VIRTUAL_TAIL_MIN_LENGTH)
                    boneAxis = normalizeVec(quatRotateVec(invWorldQuat, dir))
                }

                // Initial tail position: world, then into center space
                val worldBoneDir = quatRotateVec(worldQuat, boneAxis)
                val tailWorld = floatArrayOf(
                    headPos[0] + worldBoneDir[0] * boneLength,
                    headPos[1] + worldBoneDir[1] * boneLength,
                    headPos[2] + worldBoneDir[2] * boneLength
                )
                val tailCenter = centerWorldInv?.let { mat4MulPoint(it, tailWorld) } ?: tailWorld

                jointStates.add(JointState(
                    entity = entity,
                    boneLength = boneLength,
                    restBoneLength = boneLength,
                    boneAxis = boneAxis,
                    restLocalQuat = localQuat,
                    restLocalMat = localMat.clone(),
                    prevTail = tailCenter.clone(),
                    currentTail = tailCenter.clone(),
                    hasVirtualTail = isVirtualTail,
                    lastTailNodeWorld = tailWorld.clone()
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

                springs.add(RuntimeSpring(jointStates, validParams, chainColliders, centerEntity))
            }
        }

        runtimeSprings = springs
        isInitialized = true
        val centered = springs.count { it.centerEntity != 0 }
        Log.i(TAG, "Bound ${springs.size} spring chains, " +
                "${springs.sumOf { it.jointStates.size }} joints, $centered with center node")
    }

    // ── Physics Update (called every frame) ──────────────────────────────

    /**
     * Update spring bone simulation. Call after animation update, before render.
     * @param deltaTime Seconds since last frame, clamped to [0.001, 0.05].
     */
    fun update(deltaTime: Float) {
        if (!isEnabled || !isInitialized) return

        val tm = engine.transformManager

        // Refresh the center-space conversion for this frame (null = world space)
        for (spring in runtimeSprings) {
            spring.centerWorld = null
            spring.centerWorldInv = null
            if (spring.centerEntity != 0) {
                val centerInstance = tm.getInstance(spring.centerEntity)
                if (centerInstance != 0) {
                    val cWorld = FloatArray(16)
                    tm.getWorldTransform(centerInstance, cWorld)
                    val cInv = mat4Invert(cWorld)
                    if (cInv != null) {
                        spring.centerWorld = cWorld
                        spring.centerWorldInv = cInv
                    }
                }
            }
        }

        // Substep the integration so no single step exceeds ~1/120 s: a long frame
        // (jank) would otherwise inject an outsized inertia/stiffness step into the
        // Verlet state and send the chains flailing.
        val steps = max(1, kotlin.math.ceil(deltaTime * 120.0).toInt().coerceAtMost(8))
        val sdt = deltaTime / steps
        repeat(steps) { stepSprings(sdt, tm) }

        if (debugLogEnabled && ++debugLogFrameCounter % 60 == 0) {
            debugLogState(tm)
        }
    }

    /**
     * Dumps a compact snapshot: constraint length vs rest length + strand directions.
     * Covers the first 4 chains (back-compat) plus every hair chain's root and tip
     * joint — the tip is where "hair stuck on the shoulder" manifests.
     */
    private fun debugLogState(tm: TransformManager) {
        val worldMat = FloatArray(16)
        var logged = 0
        for (spring in runtimeSprings) {
            val first = spring.jointStates.firstOrNull() ?: continue
            val firstName = nodeNameOf(first.entity)
            val logHair = firstName?.startsWith("J_Sec_Hair") == true
            if (logged >= 4 && !logHair) continue
            val line = StringBuilder("dbg ").append(firstName ?: "?").append(": ")
            val f = jointSnapshot(tm, spring, first, worldMat)
            if (f == null) continue
            line.append("root[").append(f).append("]")
            val tip = spring.jointStates.lastOrNull()
            if (tip != null && tip !== first) {
                val t = jointSnapshot(tm, spring, tip, worldMat)
                if (t != null) line.append(" tip[").append(t).append("]")
            }
            Log.i(TAG, line.toString())
            logged++
            if (logged >= 60) break
        }
    }

    /** One "len=len/rest dir=[x,y,z]" snapshot for a joint of [spring]. */
    private fun jointSnapshot(
        tm: TransformManager,
        spring: RuntimeSpring,
        state: JointState,
        worldMat: FloatArray
    ): String? {
        val instance = tm.getInstance(state.entity)
        if (instance == 0) return null
        tm.getWorldTransform(instance, worldMat)
        val headPos = floatArrayOf(worldMat[12], worldMat[13], worldMat[14])
        val tailWorld = spring.centerWorld?.let { mat4MulPoint(it, state.currentTail) }
            ?: state.currentTail
        val dir = floatArrayOf(
            tailWorld[0] - headPos[0], tailWorld[1] - headPos[1], tailWorld[2] - headPos[2]
        )
        normalizeVecInPlace(dir)
        return "len=" + String.format(java.util.Locale.US, "%.3f/%.3f", state.boneLength, state.restBoneLength) +
                " dir=[" + String.format(java.util.Locale.US, "%.2f,%.2f,%.2f", dir[0], dir[1], dir[2]) + "]"
    }

    private fun nodeNameOf(entity: Int): String? =
        nodeEntities.entries.firstOrNull { it.value == entity }?.key?.let { nodeNames[it] }

    /** Runs one integration substep over all springs. */
    private fun stepSprings(deltaTime: Float, tm: TransformManager) {
        for (spring in runtimeSprings) {
            // Center-space conversion (world space when null)
            val centerWorld = spring.centerWorld
            val centerWorldInv = spring.centerWorldInv

            for (i in spring.jointStates.indices) {
                val state = spring.jointStates[i]
                val params = spring.jointParams[i]

                val instance = tm.getInstance(state.entity)
                if (instance == 0) continue

                // Current world transform of the head bone (after animation)
                val worldMat = FloatArray(16)
                tm.getWorldTransform(instance, worldMat)
                val headPos = floatArrayOf(worldMat[12], worldMat[13], worldMat[14])
                val worldScale = vecLength(floatArrayOf(worldMat[0], worldMat[1], worldMat[2]))

                // ── Per-frame bone length (three-vrm _calcWorldSpaceBoneLength) ──
                // three-vrm measures the length against the tail NODE's world position
                // from the end of the previous frame, which slackens the constraint by
                // exactly the per-frame head displacement and absorbs fast animation
                // motion. Joints with a virtual tail keep their fixed 7cm length.
                if (!state.hasVirtualTail) {
                    val lenX = state.lastTailNodeWorld[0] - headPos[0]
                    val lenY = state.lastTailNodeWorld[1] - headPos[1]
                    val lenZ = state.lastTailNodeWorld[2] - headPos[2]
                    val newLen = vecLength(floatArrayOf(lenX, lenY, lenZ))
                    if (newLen > 1e-6f) {
                        state.boneLength = newLen
                    }
                }

                // Parent rotation (from the parent's current world transform).
                // TransformManager mixes two id spaces: getParent() takes an
                // EntityInstance and returns the parent *entity*, while
                // getWorldTransform() takes an EntityInstance. Passing the parent
                // entity straight into getWorldTransform reads an unrelated node's
                // transform (and SEGVs when the value is out of range).
                val parentEntity = tm.getParent(instance)
                val parentRot: FloatArray = if (parentEntity != 0) {
                    val parentInst = tm.getInstance(parentEntity)
                    if (parentInst != 0) {
                        val parentWorldMat = FloatArray(16)
                        tm.getWorldTransform(parentInst, parentWorldMat)
                        matrixToQuaternion(parentWorldMat)
                    } else {
                        QUAT_IDENTITY
                    }
                } else {
                    QUAT_IDENTITY
                }

                // ── Verlet Integration ──
                // Inertia integrates in CENTER space (three-vrm VRMSpringBoneJoint.update):
                // when the spring has a center node and the whole model is moved/rotated,
                // the tails move with it and produce no false reaction.
                val dragFactor = 1f - params.dragForce
                val nextTailCenter = floatArrayOf(
                    state.currentTail[0] + (state.currentTail[0] - state.prevTail[0]) * dragFactor,
                    state.currentTail[1] + (state.currentTail[1] - state.prevTail[1]) * dragFactor,
                    state.currentTail[2] + (state.currentTail[2] - state.prevTail[2]) * dragFactor
                )

                // Convert the tail point to world space
                val converted = centerWorld?.let { mat4MulPoint(it, nextTailCenter) } ?: nextTailCenter

                // Stiffness: pull toward the rest bone direction; gravity: world-space down.
                val combinedRot = quatMultiply(parentRot, state.restLocalQuat)
                val stiffnessDir = quatRotateVec(combinedRot, state.boneAxis)
                var nextTail = floatArrayOf(
                    converted[0] + stiffnessDir[0] * params.stiffness * deltaTime
                            + params.gravityDir[0] * params.gravityPower * deltaTime,
                    converted[1] + stiffnessDir[1] * params.stiffness * deltaTime
                            + params.gravityDir[1] * params.gravityPower * deltaTime,
                    converted[2] + stiffnessDir[2] * params.stiffness * deltaTime
                            + params.gravityDir[2] * params.gravityPower * deltaTime
                )

                // ── Length Constraint ──
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
                        headPos, nextTail, state.boneLength, params.hitRadius * worldScale,
                        collider, colliderWorldMat
                    )
                    if (result != null) {
                        nextTail = result
                    }
                }

                // ── Update State (back into center space) ──
                state.prevTail = state.currentTail
                state.currentTail = centerWorldInv?.let { mat4MulPoint(it, nextTail) } ?: nextTail

                // ── Rotation Recovery (world space) ──
                val currentDir = floatArrayOf(
                    nextTail[0] - headPos[0],
                    nextTail[1] - headPos[1],
                    nextTail[2] - headPos[2]
                )
                normalizeVecInPlace(currentDir)

                val restDir = quatRotateVec(combinedRot, state.boneAxis)
                normalizeVecInPlace(restDir)

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

                // Record where the tail NODE now sits (bone-snapped: head + dir * restLen).
                // `currentDir` is the final bone direction, so this is exactly what
                // three-vrm reads from child.matrixWorld on the next frame.
                state.lastTailNodeWorld[0] = headPos[0] + currentDir[0] * state.restBoneLength
                state.lastTailNodeWorld[1] = headPos[1] + currentDir[1] * state.restBoneLength
                state.lastTailNodeWorld[2] = headPos[2] + currentDir[2] * state.restBoneLength
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
            var centerWorldInv: FloatArray? = null
            if (spring.centerEntity != 0) {
                val centerInstance = tm.getInstance(spring.centerEntity)
                if (centerInstance != 0) {
                    val cWorld = FloatArray(16)
                    tm.getWorldTransform(centerInstance, cWorld)
                    centerWorldInv = mat4Invert(cWorld)
                }
            }

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
                val tailWorld = floatArrayOf(
                    headPos[0] + worldBoneDir[0] * state.boneLength,
                    headPos[1] + worldBoneDir[1] * state.boneLength,
                    headPos[2] + worldBoneDir[2] * state.boneLength
                )
                val tailCenter = centerWorldInv?.let { mat4MulPoint(it, tailWorld) } ?: tailWorld
                state.prevTail = tailCenter.clone()
                state.currentTail = tailCenter.clone()
                tailWorld.copyInto(state.lastTailNodeWorld)
                state.boneLength = state.restBoneLength
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
        // Collider radius is a model-unit value; scale it into world space
        val colliderScale = vecLength(floatArrayOf(
            colliderWorldMat[0], colliderWorldMat[1], colliderWorldMat[2]
        ))

        if (collider.tail != null) {
            // Capsule collider
            val colliderWorldTail = mat4MulPoint(colliderWorldMat, collider.tail)
            return resolveCapsuleCollision(
                headPos, nextTail, boneLength, jointRadius,
                colliderWorldPos, colliderWorldTail, collider.radius * colliderScale
            )
        } else {
            // Sphere collider
            return resolveSphereCollision(
                headPos, nextTail, boneLength, jointRadius,
                colliderWorldPos, collider.radius * colliderScale
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
                val offset = parseVec3(sphere.get("offset"))
                val radius = sphere.get("radius")?.asFloat ?: 0f
                parsedColliders.add(SpringCollider(nodeIdx, offset, radius, null))
            } else if (capsule != null) {
                val offset = parseVec3(capsule.get("offset"))
                val radius = capsule.get("radius")?.asFloat ?: 0f
                val tail = parseVec3(capsule.get("tail"))
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

        // Parse springs. Per the spec, each joint's tail is the next joint node;
        // the last joint's tail is its first hierarchy child (or a virtual tail
        // is created at bind time when it has no children).
        val springsArray = springBoneExt.getAsJsonArray("springs")
        val parsedChains = mutableListOf<SpringChain>()
        springsArray?.forEach { el ->
            val obj = el.asJsonObject
            val name = obj.get("name")?.asString

            val jointNodes = mutableListOf<Int>()
            obj.getAsJsonArray("joints")?.forEach { jEl ->
                jointNodes.add(jEl.asJsonObject.get("node")?.asInt ?: -1)
            }

            val joints = mutableListOf<SpringJointParams>()
            for (i in jointNodes.indices) {
                val nodeIdx = jointNodes[i]
                if (nodeIdx < 0) continue
                val jObj = obj.getAsJsonArray("joints")[i].asJsonObject
                val tailNode = if (i + 1 < jointNodes.size) {
                    jointNodes[i + 1].takeIf { it >= 0 }
                } else {
                    nodeChildren[nodeIdx]?.firstOrNull()
                }
                joints.add(SpringJointParams(
                    nodeIndex = nodeIdx,
                    tailNodeIndex = tailNode,
                    stiffness = jObj.get("stiffness")?.asFloat ?: 1.0f,
                    gravityPower = jObj.get("gravityPower")?.asFloat ?: 0f,
                    gravityDir = parseVec3(jObj.get("gravityDir"), default = floatArrayOf(0f, -1f, 0f)),
                    dragForce = jObj.get("dragForce")?.asFloat ?: 0.4f,
                    hitRadius = jObj.get("hitRadius")?.asFloat ?: 0f
                ))
            }

            val colliderGroupIndices = mutableListOf<Int>()
            obj.getAsJsonArray("colliderGroups")?.forEach { idx ->
                colliderGroupIndices.add(idx.asInt)
            }

            val centerEl = obj.get("center")
            val centerNode = if (centerEl != null && !centerEl.isJsonNull) centerEl.asInt else null

            // three-vrm's VRM 1.0 import creates joints only for joints[0..n-2]: the
            // last schema joint serves purely as its parent's tail and is never
            // simulated. Its short virtual tail would otherwise flap violently.
            if (joints.isNotEmpty()) {
                joints.removeAt(joints.size - 1)
            }

            if (joints.isNotEmpty()) {
                parsedChains.add(SpringChain(name, joints, colliderGroupIndices, centerNode))
            }
        }
        springChains = parsedChains

        val centered = springChains.count { it.centerNodeIndex != null }
        Log.i(TAG, "Parsed VRM 1.0: ${colliders.size} colliders, " +
                "${colliderGroups.size} groups, ${springChains.size} springs, $centered with center")
    }

    // ── JSON Parsing: VRM 0.x ────────────────────────────────────────────

    private fun parseVrm0SecondaryAnimation(secAnim: JsonObject) {
        // Parse collider groups (VRM 0.x: colliderGroups[] with node + colliders[])
        // Collider offset Z is opposite in VRM 0.0 (three-vrm VRMSpringBoneLoaderPlugin._v0Import).
        val groupsArray = secAnim.getAsJsonArray("colliderGroups")
        val parsedColliders = mutableListOf<SpringCollider>()
        val parsedGroups = mutableListOf<SpringColliderGroup>()

        groupsArray?.forEach { el ->
            val obj = el.asJsonObject
            val nodeIdx = obj.get("node")?.asInt ?: return@forEach
            val indices = mutableListOf<Int>()

            obj.getAsJsonArray("colliders")?.forEach { cEl ->
                val cObj = cEl.asJsonObject
                val offset = parseVec3(cObj.get("offset"))
                val radius = cObj.get("radius")?.asFloat ?: 0f
                indices.add(parsedColliders.size)
                parsedColliders.add(SpringCollider(
                    nodeIdx,
                    floatArrayOf(offset[0], offset[1], -offset[2]),
                    radius, null
                ))
            }

            parsedGroups.add(SpringColliderGroup(null, indices))
        }
        colliders = parsedColliders
        colliderGroups = parsedGroups

        // Parse bone groups (VRM 0.x: boneGroups[] with per-group parameters).
        // The roots' whole subtrees are simulated (three-vrm: root.traverse), each
        // node's tail being its first hierarchy child.
        val boneGroupsArray = secAnim.getAsJsonArray("boneGroups")
        val parsedChains = mutableListOf<SpringChain>()

        boneGroupsArray?.forEach { el ->
            val obj = el.asJsonObject
            val comment = obj.get("comment")?.asString
            val stiffness = obj.get("stiffiness")?.asFloat ?: obj.get("stiffness")?.asFloat ?: 1.0f
            val gravityPower = obj.get("gravityPower")?.asFloat ?: 0f
            val gravityDir = parseVec3(obj.get("gravityDir"), default = floatArrayOf(0f, -1f, 0f))
            val dragForce = obj.get("dragForce")?.asFloat ?: 0.4f
            val hitRadius = obj.get("hitRadius")?.asFloat ?: 0f

            val colliderGroupIndices = mutableListOf<Int>()
            obj.getAsJsonArray("colliderGroups")?.forEach { idx ->
                colliderGroupIndices.add(idx.asInt)
            }

            val centerEl = obj.get("center")
            val centerNode = if (centerEl != null && !centerEl.isJsonNull) centerEl.asInt else null

            obj.getAsJsonArray("bones")?.forEach { boneEl ->
                val rootIdx = boneEl.asInt
                val joints = mutableListOf<SpringJointParams>()
                // DFS pre-order over the subtree: parents before children
                val visited = mutableSetOf<Int>()
                fun visit(idx: Int) {
                    if (!visited.add(idx)) return
                    joints.add(SpringJointParams(
                        nodeIndex = idx,
                        tailNodeIndex = nodeChildren[idx]?.firstOrNull(),
                        stiffness = stiffness,
                        gravityPower = gravityPower,
                        gravityDir = gravityDir,
                        dragForce = dragForce,
                        hitRadius = hitRadius
                    ))
                    nodeChildren[idx]?.forEach { visit(it) }
                }
                visit(rootIdx)

                if (joints.isNotEmpty()) {
                    parsedChains.add(SpringChain(comment, joints, colliderGroupIndices, centerNode))
                }
            }
        }
        springChains = parsedChains

        Log.i(TAG, "Parsed VRM 0.x: ${colliders.size} colliders, " +
                "${colliderGroups.size} groups, ${springChains.size} springs, " +
                "${springChains.sumOf { it.joints.size }} joints")
    }

    // ── Vector / Quaternion Math ──────────────────────────────────────────

    private val QUAT_IDENTITY = floatArrayOf(0f, 0f, 0f, 1f)

    private fun vecLength(v: FloatArray): Float =
        sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()

    /** Returns a normalized copy of [v]; the zero vector maps to (0, -1, 0). */
    private fun normalizeVec(v: FloatArray): FloatArray {
        val len = vecLength(v)
        if (len < 1e-12f) return floatArrayOf(0f, -1f, 0f)
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    /** Normalizes [v] in place; a near-zero vector is left unchanged. */
    private fun normalizeVecInPlace(v: FloatArray) {
        val len = vecLength(v)
        if (len > 1e-12f) {
            v[0] /= len; v[1] /= len; v[2] /= len
        }
    }

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

    /**
     * Invert a 4x4 column-major matrix (MESA gluInvertMatrix).
     * Returns null when the matrix is singular.
     */
    private fun mat4Invert(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)
        inv[0] =  m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10]
        inv[4] = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10]
        inv[8] =  m[4]*m[9]*m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9]
        inv[12] = -m[4]*m[9]*m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9]
        inv[1] = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10]
        inv[5] =  m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10]
        inv[9] = -m[0]*m[9]*m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9]
        inv[13] = m[0]*m[9]*m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9]
        inv[2] =  m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6]
        inv[6] = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6]
        inv[10] = m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5]
        inv[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5]
        inv[3] = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]*m[2]*m[7] + m[9]*m[3]*m[6]
        inv[7] =  m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]*m[2]*m[7] - m[8]*m[3]*m[6]
        inv[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9] + m[4]*m[1]*m[11] - m[4]*m[3]*m[9] - m[8]*m[1]*m[7] + m[8]*m[3]*m[5]
        inv[15] = m[0]*m[5]*m[10] - m[0]*m[6]*m[9] - m[4]*m[1]*m[10] + m[4]*m[2]*m[9] + m[8]*m[1]*m[6] - m[8]*m[2]*m[5]

        val det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12]
        if (abs(det) < 1e-12f) return null
        val invDet = 1f / det
        for (i in 0 until 16) inv[i] *= invDet
        return inv
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

    private fun parseVec3(element: com.google.gson.JsonElement?, default: FloatArray = floatArrayOf(0f, 0f, 0f)): FloatArray {
        if (element == null) return default
        if (element.isJsonArray) {
            val arr = element.asJsonArray
            if (arr.size() < 3) return default
            return floatArrayOf(arr[0].asFloat, arr[1].asFloat, arr[2].asFloat)
        } else if (element.isJsonObject) {
            val obj = element.asJsonObject
            return floatArrayOf(
                obj.get("x")?.asFloat ?: default[0],
                obj.get("y")?.asFloat ?: default[1],
                obj.get("z")?.asFloat ?: default[2]
            )
        }
        return default
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
