package com.neethu.corelib.vfx.internal

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.filamat.MaterialBuilder
import com.google.android.filament.filamat.MaterialPackage

/**
 * 特效材质运行时编译（docs/vfx-feasibility.md §4.6）：照 MToonMaterialFactory
 * 的 init()/compile() 生命周期，filamat 与引擎同版本保证 .filamat 兼容。
 *
 * 两档材质（P2 增第三档 vfx_decal）：
 *  - vfx_additive    — BlendingMode.ADD（ONE/ONE，不读目标 alpha）：淡出必须
 *    在源端预乘 rgb，输出 alpha 恒 1；
 *  - vfx_transparent — BlendingMode.TRANSPARENT（premultiplied ONE/
 *    ONE_MINUS_SRC_ALPHA）：同样预乘 rgb，输出真实 alpha。
 *
 * 参数表（唯一事实源，坑 10：设置未声明的 uniform 是 SIGABRT）：
 *  mainTexture（sampler）+ intensity（float）。
 */
internal class VfxMaterialFactory private constructor(private val engine: Engine) {

    val additive: Material
    val transparent: Material

    init {
        additive = compile(
            "vfx_additive",
            MaterialBuilder.BlendingMode.ADD,
            OUT_LINE = "material.baseColor = vec4(c.rgb * c.a * materialParams.intensity, 1.0);",
        )
        transparent = compile(
            "vfx_transparent",
            MaterialBuilder.BlendingMode.TRANSPARENT,
            OUT_LINE = "material.baseColor = vec4(c.rgb * c.a * materialParams.intensity, c.a);",
        )
    }

    fun destroy() {
        engine.destroyMaterial(additive)
        engine.destroyMaterial(transparent)
    }

    private fun compile(
        name: String,
        blending: MaterialBuilder.BlendingMode,
        OUT_LINE: String,
    ): Material {
        MaterialBuilder.init()
        val builder = MaterialBuilder()
            .name(name)
            .platform(MaterialBuilder.Platform.MOBILE)
            .optimization(MaterialBuilder.Optimization.SIZE)
            .shading(MaterialBuilder.Shading.UNLIT)          // 不受灯光 rig 牵连
            .vertexDomain(MaterialBuilder.VertexDomain.WORLD) // 顶点已是世界坐标
            .blending(blending)
            .depthWrite(false)                                // 透明边缘互切黑框防线
            .culling(MaterialBuilder.CullingMode.NONE)
            .flipUV(false)                                    // MToon 同款（raw UV）
            .require(MaterialBuilder.VertexAttribute.UV0)
            .require(MaterialBuilder.VertexAttribute.COLOR)
            .samplerParameter(
                MaterialBuilder.SamplerType.SAMPLER_2D,
                MaterialBuilder.SamplerFormat.FLOAT,
                MaterialBuilder.ParameterPrecision.DEFAULT,
                "mainTexture",
            )
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "intensity")
            .material(
                """
                void material(inout MaterialInputs material) {
                    prepareMaterial(material);
                    vec4 tex = texture(materialParams_mainTexture, getUV0());
                    vec4 c = tex * getColor();
                    $OUT_LINE
                }
                """.trimIndent()
            )
        val pkg = builder.build()
        check(pkg.isValid) { "VFX material $name failed to compile" }
        return Material.Builder().payload(pkg.buffer, pkg.buffer.remaining()).build(engine)
    }

    companion object {
        private const val TAG = "VfxMaterials"

        /** 编译失败返回 null（渲染层降级为「特效不可用」，不炸主流程）。 */
        fun create(engine: Engine): VfxMaterialFactory? = try {
            VfxMaterialFactory(engine)
        } catch (e: Exception) {
            Log.e(TAG, "VFX material compilation failed", e)
            null
        }

    }
}
