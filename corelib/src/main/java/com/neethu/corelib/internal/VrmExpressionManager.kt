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

        /**
         * Scale an expression's binds so its strongest bind reaches weight 1.0.
         *
         * Every consumer of expressions (lip-sync visemes, emotion blends, the
         * blink engine, the manual expression panel) drives them with normalized
         * 0..1 intensity values, where "1.0" must mean "the expression's primary
         * morph fully applied" to be model-independent. Converter-exported models
         * (ARKit morph sets with generated VRM presets) routinely author preset
         * binds at 0.2-0.5, which leaves visemes and emotions at a fraction of
         * the amplitude every full-weight model shows. Identity for models whose
         * binds are already at 1.0; relative ratios between binds are preserved.
         */
        internal fun normalizeBindWeights(binds: List<MorphTargetBind>): List<MorphTargetBind> {
            val max = binds.maxOfOrNull { it.weight } ?: return binds
            if (max <= 0f || max >= 0.999f) return binds
            return binds.map { it.copy(weight = it.weight / max) }
        }
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

    /** Target expression weights (what we want to reach): name → weight (0.0–1.0) */
    private val targetWeights = mutableMapOf<String, Float>()

    /** Current (interpolated) expression weights actually applied this frame */
    private val currentWeights = mutableMapOf<String, Float>()

    /** Transition duration in milliseconds (how long to blend from current to target) */
    private var transitionDurationMs: Long = 300L

    /** Last frame timestamp in nanoseconds for delta time computation */
    private var lastUpdateTimeNs: Long = 0L

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
                    val binds = normalizeBindWeights(parseMorphTargetBindsV1(exprObj, nodes, meshes))
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

                    val binds = normalizeBindWeights(parseMorphTargetBindsV0(group))
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
     * The transition to this weight will be smoothly interpolated over [transitionDurationMs].
     */
    fun setExpression(name: String, weight: Float) {
        if (weight <= 0f) {
            targetWeights.remove(name)
        } else {
            targetWeights[name] = weight.coerceIn(0f, 1f)
        }
    }

    /**
     * Clear a specific expression (smoothly fades out).
     */
    fun clearExpression(name: String) {
        targetWeights.remove(name)
    }

    /**
     * Clear all active expressions (smoothly return to neutral).
     */
    fun clearAllExpressions() {
        targetWeights.clear()
    }

    /**
     * Set the duration of expression transitions in milliseconds.
     * Use 0 for instant transitions (no interpolation).
     * @param durationMs Transition duration in milliseconds. Default is 300ms.
     */
    fun setTransitionDuration(durationMs: Long) {
        transitionDurationMs = durationMs.coerceAtLeast(0L)
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
     *
     * Smoothly interpolates current weights toward target weights each frame.
     *
     * @param frameTimeNanos Current frame timestamp in nanoseconds (from Choreographer).
     */
    fun update(frameTimeNanos: Long) {
        // Skip if nothing to do: no targets, no current weights fading out, no expressions parsed
        if (targetWeights.isEmpty() && currentWeights.isEmpty() && expressions.isEmpty()) return

        // ── Interpolate current weights toward targets ────────────────────
        val deltaNs = if (lastUpdateTimeNs > 0L) frameTimeNanos - lastUpdateTimeNs else 0L
        lastUpdateTimeNs = frameTimeNanos

        if (transitionDurationMs <= 0L || deltaNs <= 0L) {
            // Instant mode: snap current weights to match targets
            currentWeights.clear()
            currentWeights.putAll(targetWeights)
        } else {
            val deltaSeconds = deltaNs.toFloat() / 1_000_000_000f
            val transitionSeconds = transitionDurationMs.toFloat() / 1000f
            // Compute smoothing factor: how far to move toward target this frame
            // Using a simple lerp rate: alpha = deltaTime / transitionDuration, clamped to [0,1]
            val alpha = (deltaSeconds / transitionSeconds).coerceIn(0f, 1f)

            // Lerp existing current weights toward their targets (or toward 0 if no target)
            val toRemove = mutableListOf<String>()
            for ((name, current) in currentWeights) {
                val target = targetWeights[name] ?: 0f
                val newValue = current + (target - current) * alpha
                if (newValue < 0.001f && target <= 0f) {
                    toRemove.add(name)
                } else {
                    currentWeights[name] = newValue
                }
            }
            toRemove.forEach { currentWeights.remove(it) }

            // Add any new target expressions not yet in current weights
            for ((name, target) in targetWeights) {
                if (name !in currentWeights) {
                    // Start from 0 and lerp toward target
                    currentWeights[name] = target * alpha
                }
            }
        }

        // ── Build accumulated morph weights per entity ────────────────────
        val rm = engine.renderableManager
        val entityWeights = mutableMapOf<Int, FloatArray>()

        // Initialize all bound entities with base weights
        for ((meshIndex, entity) in meshEntities) {
            val count = entityMorphTargetCount[entity] ?: continue
            val weights = baseWeights[entity]?.clone() ?: FloatArray(count)
            entityWeights[entity] = weights
        }

        // Accumulate interpolated expression weights
        for ((name, expressionWeight) in currentWeights) {
            val expression = expressions[name] ?: continue
            for (bind in expression.binds) {
                val entity = meshEntities[bind.meshIndex] ?: continue
                val weights = entityWeights[entity] ?: continue
                val count = entityMorphTargetCount[entity] ?: continue

                if (bind.morphTargetIndex < count) {
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
