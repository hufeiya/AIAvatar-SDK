package com.neethu.corelib.internal

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import com.google.android.filament.gltfio.FilamentAsset
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages VRM expression (blend shape / morph target) parsing and runtime application.
 *
 * Follows the pixiv/three-vrm VRMExpressionLoaderPlugin pattern:
 * 1. Parse expression definitions from VRM extension JSON (both 1.0 and 0.x)
 * 2. Bind mesh indices to Filament renderable entities
 * 3. Drive RenderableManager.setMorphWeights() per frame
 *
 * Reference: pixiv/three-vrm packages/three-vrm-core/src/expressions/
 */
internal class VrmExpressionManager(
    private val engine: Engine
) {
    companion object {
        private const val TAG = "VrmExpressionMgr"
        private const val CHUNK_TYPE_JSON = 0x4E4F534A // "JSON"
    }

    // ── Data Model ───────────────────────────────────────────────────────

    /**
     * A single morph target binding within an expression.
     * @param meshIndex glTF mesh index
     * @param morphTargetIndex morph target index within that mesh
     * @param weight target weight when this expression is fully active (0.0–1.0)
     */
    data class MorphTargetBind(
        val meshIndex: Int,
        val morphTargetIndex: Int,
        val weight: Float
    )

    /**
     * A named expression consisting of one or more morph target binds.
     */
    data class VrmExpression(
        val name: String,
        val binds: List<MorphTargetBind>
    )

    // ── State ────────────────────────────────────────────────────────────

    /** All parsed expressions keyed by name */
    private var expressions: Map<String, VrmExpression> = emptyMap()

    /** Currently active expression weights: name → weight (0.0–1.0) */
    private val activeWeights = mutableMapOf<String, Float>()

    /** Filament entity for each mesh index */
    private var meshEntities: Map<Int, Int> = emptyMap()

    /** Morph target count for each entity (cached to avoid OOB) */
    private var entityMorphTargetCount: Map<Int, Int> = emptyMap()

    /** Base/default morph weights per entity (preserved to avoid breaking structural shapes) */
    private var baseWeights: Map<Int, FloatArray> = emptyMap()

    // ── GLB JSON Parsing ─────────────────────────────────────────────────

    /**
     * Parse expression definitions from GLB bytes.
     * Supports both VRM 1.0 and VRM 0.x formats.
     */
    fun parseFromGlb(glbBytes: ByteArray) {
        val json = parseGlbJson(glbBytes) ?: run {
            Log.w(TAG, "Failed to parse GLB JSON for expressions")
            return
        }

        val ext = json.getAsJsonObject("extensions")
        val nodes = json.getAsJsonArray("nodes")
        val meshes = json.getAsJsonArray("meshes")

        val parsed = mutableMapOf<String, VrmExpression>()

        // ── VRM 1.0: VRMC_vrm.expressions ────────────────────────────
        ext?.getAsJsonObject("VRMC_vrm")?.getAsJsonObject("expressions")?.let { expressions ->
            // Parse both preset and custom expressions
            for (category in listOf("preset", "custom")) {
                expressions.getAsJsonObject(category)?.entrySet()?.forEach { (name, element) ->
                    val exprObj = element.asJsonObject
                    val binds = parseMorphTargetBindsV1(exprObj, nodes, meshes)
                    if (binds.isNotEmpty()) {
                        parsed[name] = VrmExpression(name, binds)
                        Log.d(TAG, "VRM1.0 expression '$name': ${binds.size} binds")
                    }
                }
            }
        }

        // ── VRM 0.x: VRM.blendShapeMaster.blendShapeGroups ───────────
        if (parsed.isEmpty()) {
            ext?.getAsJsonObject("VRM")
                ?.getAsJsonObject("blendShapeMaster")
                ?.getAsJsonArray("blendShapeGroups")?.forEach { groupEl ->
                    val group = groupEl.asJsonObject
                    // Use presetName first, fall back to name
                    val presetName = group.get("presetName")?.asString?.lowercase()
                    val name = if (!presetName.isNullOrBlank() && presetName != "unknown") {
                        presetName
                    } else {
                        group.get("name")?.asString ?: return@forEach
                    }

                    val binds = parseMorphTargetBindsV0(group)
                    if (binds.isNotEmpty()) {
                        parsed[name] = VrmExpression(name, binds)
                        Log.d(TAG, "VRM0.x expression '$name': ${binds.size} binds")
                    }
                }
        }

        expressions = parsed
        Log.i(TAG, "Parsed ${parsed.size} expressions: ${parsed.keys.joinToString()}")
    }

    /**
     * Parse morphTargetBinds from VRM 1.0 expression object.
     * Schema: { morphTargetBinds: [{ node: int, index: int, weight: float }] }
     * node → nodes[node].mesh gives the mesh index.
     */
    private fun parseMorphTargetBindsV1(
        exprObj: JsonObject,
        nodes: com.google.gson.JsonArray?,
        meshes: com.google.gson.JsonArray?
    ): List<MorphTargetBind> {
        val binds = mutableListOf<MorphTargetBind>()
        exprObj.getAsJsonArray("morphTargetBinds")?.forEach { bindEl ->
            val bind = bindEl.asJsonObject
            val nodeIndex = bind.get("node")?.asInt ?: return@forEach
            val morphIndex = bind.get("index")?.asInt ?: return@forEach
            val weight = bind.get("weight")?.asFloat ?: 1.0f

            // Resolve node index → mesh index
            val meshIndex = nodes?.get(nodeIndex)?.asJsonObject?.get("mesh")?.asInt ?: return@forEach

            binds.add(MorphTargetBind(meshIndex, morphIndex, weight))
        }
        return binds
    }

    /**
     * Parse morph target binds from VRM 0.x blendShapeGroup.
     * Schema: { binds: [{ mesh: int, index: int, weight: float(0–100) }] }
     */
    private fun parseMorphTargetBindsV0(group: JsonObject): List<MorphTargetBind> {
        val binds = mutableListOf<MorphTargetBind>()
        group.getAsJsonArray("binds")?.forEach { bindEl ->
            val bind = bindEl.asJsonObject
            val meshIndex = bind.get("mesh")?.asInt ?: return@forEach
            val morphIndex = bind.get("index")?.asInt ?: return@forEach
            // VRM 0.x uses 0–100 range, normalize to 0.0–1.0
            val weight = (bind.get("weight")?.asFloat ?: 100f) / 100f

            binds.add(MorphTargetBind(meshIndex, morphIndex, weight))
        }
        return binds
    }

    // ── Asset Binding ────────────────────────────────────────────────────

    /**
     * Bind expression system to a loaded FilamentAsset.
     * Maps mesh indices to Filament entity IDs and caches morph target counts.
     */
    fun bindToAsset(asset: FilamentAsset, glbBytes: ByteArray) {
        val rm = engine.renderableManager
        val json = parseGlbJson(glbBytes) ?: return
        val nodesArray = json.getAsJsonArray("nodes") ?: return

        val entityMap = mutableMapOf<Int, Int>()
        val morphCounts = mutableMapOf<Int, Int>()
        val baseWts = mutableMapOf<Int, FloatArray>()

        // Build node name → mesh index mapping from glTF JSON
        val nodeMeshMap = mutableMapOf<String, Int>()
        for (i in 0 until nodesArray.size()) {
            val node = nodesArray[i].asJsonObject
            val name = node.get("name")?.asString ?: continue
            val meshIdx = node.get("mesh")?.asInt ?: continue
            nodeMeshMap[name] = meshIdx
        }

        // Walk FilamentAsset entities and match by name
        val entities = asset.entities
        for (entity in entities) {
            val name = asset.getName(entity) ?: continue
            val meshIndex = nodeMeshMap[name] ?: continue

            val rInstance = rm.getInstance(entity)
            if (rInstance == 0) continue

            // Get the total morph target count for this entity
            // Filament stores morph targets at the entity level (summed across primitives)
            val morphCount = try {
                rm.getMorphTargetCount(rInstance)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get morph target count for entity $entity ($name)", e)
                0
            }

            if (morphCount > 0) {
                entityMap[meshIndex] = entity
                morphCounts[entity] = morphCount

                // Save base morph weights (some models use non-zero defaults for structural shape)
                val base = FloatArray(morphCount)
                // Filament does not expose a getMorphWeights(), so defaults are 0
                baseWts[entity] = base

                Log.d(TAG, "Bound mesh $meshIndex → entity $entity ('$name'), $morphCount morph targets")
            }
        }

        meshEntities = entityMap
        entityMorphTargetCount = morphCounts
        baseWeights = baseWts

        Log.i(TAG, "Bound ${entityMap.size} mesh entities with morph targets")
    }

    // ── Runtime API ──────────────────────────────────────────────────────

    /**
     * Set expression weight. Use weight=1.0 for full expression, 0.0 to clear.
     */
    fun setExpression(name: String, weight: Float) {
        if (weight <= 0f) {
            activeWeights.remove(name)
        } else {
            activeWeights[name] = weight.coerceIn(0f, 1f)
        }
    }

    /**
     * Clear a specific expression.
     */
    fun clearExpression(name: String) {
        activeWeights.remove(name)
    }

    /**
     * Clear all active expressions (return to neutral).
     */
    fun clearAllExpressions() {
        activeWeights.clear()
    }

    /**
     * Get list of available expression names parsed from the model.
     */
    fun getAvailableExpressions(): List<String> {
        return expressions.keys.toList().sorted()
    }

    /**
     * Update morph weights on the Filament renderables.
     * Call this once per frame from the choreographer callback.
     */
    fun update() {
        if (activeWeights.isEmpty() && expressions.isEmpty()) return

        val rm = engine.renderableManager

        // Build accumulated weights per entity
        // Start with base weights, then layer on active expressions
        val entityWeights = mutableMapOf<Int, FloatArray>()

        // Initialize all bound entities with base weights
        for ((meshIndex, entity) in meshEntities) {
            val count = entityMorphTargetCount[entity] ?: continue
            val weights = baseWeights[entity]?.clone() ?: FloatArray(count)
            entityWeights[entity] = weights
        }

        // Accumulate active expression weights
        for ((name, expressionWeight) in activeWeights) {
            val expression = expressions[name] ?: continue
            for (bind in expression.binds) {
                val entity = meshEntities[bind.meshIndex] ?: continue
                val weights = entityWeights[entity] ?: continue
                val count = entityMorphTargetCount[entity] ?: continue

                if (bind.morphTargetIndex < count) {
                    // Blend: add the expression's contribution scaled by expression weight
                    weights[bind.morphTargetIndex] =
                        (weights[bind.morphTargetIndex] + bind.weight * expressionWeight)
                            .coerceIn(0f, 1f)
                }
            }
        }

        // Apply to Filament
        for ((entity, weights) in entityWeights) {
            val rInstance = rm.getInstance(entity)
            if (rInstance == 0) continue
            try {
                rm.setMorphWeights(rInstance, weights, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set morph weights on entity $entity", e)
            }
        }
    }

    // ── GLB JSON Utility ─────────────────────────────────────────────────

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
