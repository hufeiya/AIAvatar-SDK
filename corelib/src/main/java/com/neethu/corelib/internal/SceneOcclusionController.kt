package com.neethu.corelib.internal

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import org.json.JSONObject

/**
 * Scene occlusion auto-hide (wall cutaway): when the orbit camera sits
 * outside a closed room, the wall between the camera and the avatar blocks
 * the view. This controller shoots a segment from the camera eye to each
 * avatar body anchor (head/chest/hips) against every occluder group's world
 * AABB union and hides the groups that block the sight line via
 * [com.google.android.filament.RenderableManager.setLayerMask] (bit 0 is the
 * default visible layer; hiding clears all bits, which also drops the wall
 * from the shadow pass so the room lighting stays consistent).
 *
 * Groups come from the scene's cutaway sidecar (`<scene>.cutaway.json`,
 * authored next to the GLB by the asset pipeline): each group names its
 * renderable nodes (`renderableNodes`) and hides/shows them together — a
 * partially-hidden wall frame reads as broken, so group-level hiding is the
 * contract. Furniture grouped into a wall (e.g. the wall-mounted TV under
 * WALL_SOUTH) hides with its wall by design. Without a sidecar the
 * controller falls back to node-name prefix discovery (WALL_/GLASS_/CUTAWAY_/
 * CEILING prefixes, one renderable per group).
 *
 * Flicker discipline: hide is immediate on a fresh hit; unhide requires the
 * sight line to stay clear for [UNHIDE_DELAY_SECONDS] (camera strafing along
 * a wall edge must not strobe the wall). World AABBs are cached per attach
 * and invalidated when the scene placement changes (they include the meta
 * offset).
 */
internal class SceneOcclusionController(private val engine: Engine) {

    companion object {
        private const val TAG = "SceneOcclusion"

        /** Fallback (no sidecar): scene node name prefixes treated as occluders.
         *  ⚠刻意不含 FOREST_/大背景板——弯曲网格的世界 AABB 罩住房间，见 attach。 */
        private val OCCLUDER_PREFIXES = listOf(
            "WALL_",
            "GLASS_",
            "CUTAWAY_",
            "CEILING",
        )

        /** Extra AABB margin so grazing sight lines hide the wall (hide side). */
        private const val HIDE_MARGIN_METERS = 0.06f

        /** Unhide needs a clear sight line for this long (flicker hysteresis). */
        private const val UNHIDE_DELAY_SECONDS = 0.25f

        /** Renderable layerMask: gltfio's default is bit 0 (View default layer). */
        private const val SHOW_LAYER_MASK = 0x01
        private const val ALL_LAYERS_SELECT = 0xFF
    }

    internal class OccluderGroup(
        val name: String,
        val entities: List<Int>,
    ) {
        var wMin: FloatArray = FloatArray(3)
        var wMax: FloatArray = FloatArray(3)
        var aabbValid: Boolean = false
        var hidden: Boolean = false
        /** Seconds the sight line has been continuously clear (unhide hysteresis). */
        var clearFor: Float = 0f
    }

    private val groups = mutableListOf<OccluderGroup>()
    private var worldAabbsDirty = true

    /** Master switch (ai_cmd / host toggle). Inert when no scene is attached. */
    @Volatile
    var enabled: Boolean = true

    /** Number of occluder groups discovered in the current scene. */
    val occluderCount: Int get() = groups.size

    /** Count of currently hidden groups (diagnostics). */
    val hiddenCount: Int get() = groups.count { it.hidden }

    /** Currently hidden group names (diagnostics). */
    fun hiddenNames(): List<String> = groups.filter { it.hidden }.map { it.name }

    /**
     * Collect occluder groups for a freshly loaded scene asset.
     *
     * @param cutawayJson contents of the scene's `.cutaway.json` sidecar, or
     *   null to fall back to node-name prefix discovery.
     */
    fun attach(asset: FilamentAsset, cutawayJson: String?) {
        detach()
        val parsed = cutawayJson?.let { runCatching { parseSidecar(it) }.getOrNull() }
        if (parsed != null) {
            for ((name, nodeNames) in parsed) {
                val entities = nodeNames.mapNotNull { asset.getFirstEntityByName(it) }
                    .filter { engine.renderableManager.getInstance(it) != 0 }
                if (entities.isNotEmpty()) groups.add(OccluderGroup(name, entities))
            }
            // ⚠刻意不把大背景板补进候选：它们是弯曲地形/巨面网格，世界 AABB
            // 罩住整个房间（dream_bedroom 旧 FOREST_BACKDROP 实测 22×19m），
            // 相机恒"在盒内"→ 任何机位都误判遮挡 → 窗外永远变灰天空盒。
            // sidecar 契约只声明墙体/天花板是剖切组，背景板交给宿主（README）。
            // （dream_bedroom 的 FOREST_BACKDROP 已于 2026-10-24 从场景删除，
            // 本规则对其余场景/未来资产仍然适用。）
        } else {
            val seenEntities = HashSet<Int>()
            for (prefix in OCCLUDER_PREFIXES) {
                for (entity in asset.getEntitiesByPrefix(prefix)) {
                    if (engine.renderableManager.getInstance(entity) == 0) continue
                    if (!seenEntities.add(entity)) continue
                    groups.add(OccluderGroup("$prefix:$entity", listOf(entity)))
                }
            }
        }
        worldAabbsDirty = true
    }

    /** Parse `groups[].node` + `renderableNodes` out of the cutaway sidecar. */
    private fun parseSidecar(json: String): List<Pair<String, List<String>>> {
        val root = JSONObject(json)
        val groupsJson = root.getJSONArray("groups")
        val out = mutableListOf<Pair<String, List<String>>>()
        for (i in 0 until groupsJson.length()) {
            val g = groupsJson.getJSONObject(i)
            val name = g.getString("node")
            val nodes = g.getJSONArray("renderableNodes")
            out.add(name to (0 until nodes.length()).map { nodes.getString(it) })
        }
        return out
    }

    fun detach() {
        // Restore visibility for anything still hidden before dropping refs —
        // the scene asset is destroyed by the caller afterwards.
        val rm = engine.renderableManager
        for (g in groups) {
            if (!g.hidden) continue
            for (entity in g.entities) {
                val i = rm.getInstance(entity)
                if (i != 0) {
                    rm.setLayerMask(i, ALL_LAYERS_SELECT, SHOW_LAYER_MASK)
                    rm.setCastShadows(i, true)
                }
            }
        }
        groups.clear()
    }

    /** Call after the scene placement changed — world AABBs must recompute. */
    fun invalidateWorldAabbs() {
        worldAabbsDirty = true
    }

    /**
     * Per-frame update: recompute occlusion from the camera eye to the avatar
     * body points and apply layer masks. Must run after the frame's transform
     * commit (TransformManager lazy evaluation) and reads the orbit camera
     * pose the manipulator already wrote this frame.
     *
     * @param eye camera world position (3 floats)
     * @param bodyPoints avatar world anchor points (head/chest/hips), each 3 floats
     * @param dtSeconds frame delta for the unhide hysteresis
     * @param flipsAllowed 翻转放行闸（[OcclusionFlipGate]）：false = 冻结 hide/show
     *   （身体/相机运动窗口内不评估——P2 真机验收 3/3 崩溃的翻转风暴窗口）。
     *   冻结期间连 unhide 消抖计时也一并冻结，放行后从静止状态重新评估。
     */
    fun update(
        eye: FloatArray,
        bodyPoints: List<FloatArray>,
        dtSeconds: Float,
        flipsAllowed: Boolean = true,
    ) {
        if (!enabled || groups.isEmpty()) return
        val tm = engine.transformManager
        val rm = engine.renderableManager
        if (worldAabbsDirty) {
            for (g in groups) {
                if (!computeGroupAabb(g, tm, rm)) continue
            }
            worldAabbsDirty = false
        }
        if (!flipsAllowed) return
        for (g in groups) {
            if (g.entities.any { rm.getInstance(it) == 0 }) continue
            if (!g.aabbValid) continue
            val blocked = bodyPoints.any { point ->
                val (hMin, hMax) = OcclusionMath.expand(g.wMin, g.wMax, HIDE_MARGIN_METERS)
                OcclusionMath.segmentIntersectsAabb(eye, point, hMin, hMax)
            }
            if (blocked) {
                g.clearFor = 0f
                if (!g.hidden) hide(g, rm)
            } else if (g.hidden) {
                g.clearFor += dtSeconds
                if (g.clearFor >= UNHIDE_DELAY_SECONDS) show(g, rm)
            }
        }
    }

    /** Union of the renderable world AABBs (mesh-local box × world transform). */
    private fun computeGroupAabb(
        g: OccluderGroup,
        tm: com.google.android.filament.TransformManager,
        rm: com.google.android.filament.RenderableManager,
    ): Boolean {
        var valid = false
        var min = FloatArray(3) { Float.POSITIVE_INFINITY }
        var max = FloatArray(3) { Float.NEGATIVE_INFINITY }
        val world = FloatArray(16)
        for (entity in g.entities) {
            val inst = tm.getInstance(entity)
            if (inst == 0) continue
            val rmi = rm.getInstance(entity)
            if (rmi == 0) continue
            // ⚠filament RenderableManager 的 @EntityInstance 参数收的是
            // getInstance(entity) 的 instance，不是 entity 本体（弹簧骨骼
            // getParent 同类坑）：传错 = 调用静默落空，墙永远隐藏不掉。
            val box = rm.getAxisAlignedBoundingBox(rmi, Box())
            val center = box.center
            val half = box.halfExtent
            // filament Box 只暴露 center/halfExtent——还原 min/max 角
            val localMin = floatArrayOf(center[0] - half[0], center[1] - half[1], center[2] - half[2])
            val localMax = floatArrayOf(center[0] + half[0], center[1] + half[1], center[2] + half[2])
            tm.getWorldTransform(inst, world)
            val (wMin, wMax) = OcclusionMath.transformAabb(localMin, localMax, world)
            for (axis in 0..2) {
                if (wMin[axis] < min[axis]) min[axis] = wMin[axis]
                if (wMax[axis] > max[axis]) max[axis] = wMax[axis]
            }
            valid = true
        }
        if (valid) {
            g.wMin = min
            g.wMax = max
        }
        g.aabbValid = valid
        return valid
    }

    private fun hide(g: OccluderGroup, rm: com.google.android.filament.RenderableManager) {
        g.hidden = true
        g.clearFor = 0f
        for (entity in g.entities) {
            val i = rm.getInstance(entity)
            if (i == 0) continue
            rm.setLayerMask(i, ALL_LAYERS_SELECT, 0x00)
            rm.setCastShadows(i, false)
        }
        // 翻转诊断（P2 真机验收 3/3 Filament 崩溃的关联观测面）：每次 hide/show
        // 一行日志——翻转时间线与崩溃时间戳的对拍是下一步归因的关键证据。
        android.util.Log.i(TAG, "occlusion hide: ${g.name} (hidden=${hiddenCount}/${groups.size})")
    }

    private fun show(g: OccluderGroup, rm: com.google.android.filament.RenderableManager) {
        g.hidden = false
        g.clearFor = 0f
        for (entity in g.entities) {
            val i = rm.getInstance(entity)
            if (i == 0) continue
            rm.setLayerMask(i, ALL_LAYERS_SELECT, SHOW_LAYER_MASK)
            rm.setCastShadows(i, true)
        }
        android.util.Log.i(TAG, "occlusion show: ${g.name} (hidden=${hiddenCount}/${groups.size})")
    }
}
