package com.neethu.corelib.stage.internal

import com.google.gson.JsonObject
import com.neethu.corelib.RenderMode
import com.neethu.corelib.internal.GlbBoneCuller
import com.neethu.corelib.internal.GlbMorphPatcher
import com.neethu.corelib.internal.GlbMToon
import java.nio.ByteBuffer

/**
 * 模型字节预处理管线（SoulLinkRenderer.loadModelBytes 前半段的纯字节部分，
 * 与 Filament 零接触 → 可在后台线程跑，加载异步化的本体）。
 *
 * 步骤与单虚拟人逐字节一致（顺序不能动）：
 * 1. morph 默认权重注入（gltfio 对 weights_count==0 不上传 morph 法线）；
 * 2. 未用骨骼裁剪（Filament 256 骨上限）；
 * 3. MToon 扩展解析（检测 fresh 加载的渲染模式；effective MTOON 时复制
 *    描边 pass 的 primitives）——必须在裁剪之后（共享 accessor 只处理一次）。
 */
internal class PreparedModel(
    /** 原始字节（MToonApplier 解析贴图/材质用，与单虚拟人同语义）。 */
    val rawBytes: ByteArray,
    /** 全部预处理完成、可直接 createAsset 的 buffer。 */
    val finalBuffer: ByteBuffer,
    /** 非 null = 本次加载应以 MTOON 模式换肤（绑定阶段执行）。 */
    val mtoonParse: GlbMToon.ParseResult?,
    /** 非 null = fresh 加载检测出的渲染模式（与请求不同，舞台需切换）。 */
    val detectedRenderMode: RenderMode?,
)

internal object ModelPreprocessor {

    fun process(rawBytes: ByteArray, requestedMode: RenderMode, autoRenderMode: Boolean): PreparedModel {
        val buffer = ByteBuffer.wrap(rawBytes)

        // morph 权重注入
        buffer.rewind()
        val morphPatched = GlbMorphPatcher.injectMorphDefaultWeights(buffer)

        // 未用骨骼裁剪
        val loadBuffer = GlbBoneCuller.cullUnusedBones(morphPatched)
            ?: morphPatched.also { it.rewind() }

        // MToon 解析 / 描边复制 / 模式检测
        var mtoonParse: GlbMToon.ParseResult? = null
        var finalBuffer = loadBuffer
        var detected: RenderMode? = null
        try {
            // readJson 在 duplicate 上读：不动 loadBuffer 的 position
            val gltfJson: JsonObject? = GlbMToon.readJson(loadBuffer.duplicate())
            if (autoRenderMode) {
                val mode = GlbMToon.detectRenderMode(gltfJson)
                if (mode != requestedMode) detected = mode
            }
            val effectiveMode = detected ?: requestedMode
            val materials = gltfJson?.let { GlbMToon.parseMaterials(it) }
            if (effectiveMode == RenderMode.MTOON && !materials.isNullOrEmpty()) {
                val json = gltfJson ?: error("GLB json unreadable for MToon patch")
                val (patched, layouts) = GlbMToon.patchGlb(loadBuffer, json, materials)
                if (layouts.isNotEmpty()) {
                    finalBuffer = patched
                    mtoonParse = GlbMToon.ParseResult(
                        isVrm0 = parseVrmMetaVersion(rawBytes) == "0",
                        materials = materials,
                        meshLayouts = layouts,
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "MToon GLB patch failed, falling back to PBR", e)
            mtoonParse = null
            finalBuffer = loadBuffer
        }
        return PreparedModel(rawBytes, finalBuffer, mtoonParse, detected)
    }

    /** Detects the VRM meta version ("0" or "1") from GLB bytes. */
    fun parseVrmMetaVersion(glbBytes: ByteArray): String {
        return try {
            val json = GlbMToon.readJson(ByteBuffer.wrap(glbBytes)) ?: return "1"
            val ext = json.getAsJsonObject("extensions") ?: return "1"
            if (ext.has("VRMC_vrm")) "1" else if (ext.has("VRM")) "0" else "1"
        } catch (e: Exception) {
            "1"
        }
    }

    private const val TAG = "ModelPreprocessor"
}
