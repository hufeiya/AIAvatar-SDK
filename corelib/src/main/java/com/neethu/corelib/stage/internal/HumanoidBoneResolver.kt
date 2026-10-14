package com.neethu.corelib.stage.internal

import android.util.Log
import com.google.android.filament.gltfio.FilamentAsset
import com.neethu.corelib.internal.GlbMToon

/**
 * VRM humanoid 语义骨骼解析（VRM1.0 `VRMC_vrm.humanoid.humanBones` +
 * VRM0.x `VRM.humanoid.humanBones` 双格式，节点名 → Filament 实体）。
 *
 * 从 AvatarRuntime 的私有 resolveHumanoidEntity 提取：配饰挂点（
 * docs/accessory-feasibility.md §5.2 第 4 步）与既有绑定（头/hips/手）
 * 共用同一份解析。[resolveAll] 一次解析全语义表——bind 时取一份给
 * AvatarRuntime 自用 + 配饰挂点查询，替代此前每个语义重复解析 JSON。
 */
internal object HumanoidBoneResolver {
    private const val TAG = "HumanoidBoneResolver"

    /**
     * 语义名（小写）→ 实体。两个扩展都没有 / JSON 损坏 = 空表；
     * 个别语义找不到节点 = 该语义缺席（缺骨优雅降级由调用方处理）。
     */
    fun resolveAll(asset: FilamentAsset, glbBytes: ByteArray): Map<String, Int> {
        return try {
            val json = GlbMToon.readJson(java.nio.ByteBuffer.wrap(glbBytes)) ?: return emptyMap()
            val ext = json.getAsJsonObject("extensions") ?: return emptyMap()
            val nodes = json.getAsJsonArray("nodes") ?: return emptyMap()
            val found = HashMap<String, Int>()

            // VRM 1.0：humanBones = { "<semantic>": { node: <index> } }
            ext.getAsJsonObject("VRMC_vrm")?.getAsJsonObject("humanoid")
                ?.getAsJsonObject("humanBones")?.entrySet()?.forEach { (semantic, value) ->
                    val nodeIndex = value.asJsonObject?.get("node")?.asInt ?: return@forEach
                    putNode(found, asset, nodes, semantic, nodeIndex)
                }
            // VRM 0.x：humanBones = [ { bone: "<semantic>", node: <index> } ]
            if (found.isEmpty()) {
                ext.getAsJsonObject("VRM")?.getAsJsonObject("humanoid")
                    ?.getAsJsonArray("humanBones")?.forEach { el ->
                        val obj = el.asJsonObject
                        val semantic = obj.get("bone")?.asString ?: return@forEach
                        val nodeIndex = obj.get("node")?.asInt ?: return@forEach
                        putNode(found, asset, nodes, semantic, nodeIndex)
                    }
            }
            found
        } catch (e: Exception) {
            Log.w(TAG, "resolveAll failed", e)
            emptyMap()
        }
    }

    private fun putNode(
        found: MutableMap<String, Int>,
        asset: FilamentAsset,
        nodes: com.google.gson.JsonArray,
        semantic: String,
        nodeIndex: Int,
    ) {
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) return
        val nodeName = nodes[nodeIndex].asJsonObject?.get("name")?.asString ?: return
        asset.getFirstEntityByName(nodeName)?.let { found[semantic.lowercase()] = it }
    }
}
