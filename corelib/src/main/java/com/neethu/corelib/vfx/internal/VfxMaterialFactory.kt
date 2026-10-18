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
 * 三档材质（画质升级 2026-10-17 增 vfx_beam）：
 *  - vfx_additive    — BlendingMode.ADD（ONE/ONE，不读目标 alpha）：淡出必须
 *    在源端预乘 rgb，输出 alpha 恒 1；
 *  - vfx_transparent — BlendingMode.TRANSPARENT（premultiplied ONE/
 *    ONE_MINUS_SRC_ALPHA）：同样预乘 rgb，输出真实 alpha；
 *  - vfx_beam        — 舞台灯光束锥（ADD）：UV0.x=径向占比（0=轴心 1=锥缘）、
 *    UV0.y=长度进度（0=灯口 1=末端），fragment 纯数学算软边+纵向渐隐——
 *    beam 局部坐标逐顶点烘焙进 UV，任意视角截面都是软光柱（drei SpotLight
 *    的径向渐隐思路，docs/vfx-quality-upgrade.md §3.2）；
 *  - vfx_decal       — 贴花（P2 法阵/护盾）：OBJECT 域静态网格 + 实体变换
 *    （自转/跟随锚点），tex × 顶点色 × intensity——Fresnel 明度由 CPU 逐帧
 *    写进穹顶顶点色（DecalGeometry.fresnelShades），本色/淡出全在顶点色里。
 *
 * 参数表（唯一事实源，坑 10：设置未声明的 uniform 是 SIGABRT）：
 *  mainTexture（sampler）+ intensity（float）；beam/decal: color（float3）+ intensity。
 */
internal class VfxMaterialFactory private constructor(private val engine: Engine) {

    val additive: Material
    val transparent: Material
    val beam: Material
    val decal: Material

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
        beam = compileBeam()
        decal = compileDecal()
    }

    fun destroy() {
        engine.destroyMaterial(additive)
        engine.destroyMaterial(transparent)
        engine.destroyMaterial(beam)
        engine.destroyMaterial(decal)
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

    /**
     * vfx_beam：光束锥材质。OBJECT 域（锥体静态几何 + 实体变换扫摆），
     * UV 在几何构建期烘焙（x=径向占比、y=长度进度）。坑 12：uniform 全走
     * materialParams.xxx 结构体语法。
     */
    private fun compileBeam(): Material {
        MaterialBuilder.init()
        val builder = MaterialBuilder()
            .name("vfx_beam")
            .platform(MaterialBuilder.Platform.MOBILE)
            .optimization(MaterialBuilder.Optimization.SIZE)
            .shading(MaterialBuilder.Shading.UNLIT)
            .blending(MaterialBuilder.BlendingMode.ADD)
            .depthWrite(false)
            .culling(MaterialBuilder.CullingMode.NONE)   // 内外面都画：视线穿锥双面叠加更接近体积感
            .flipUV(false)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "color")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "intensity")
            .material(
                """
                void material(inout MaterialInputs material) {
                    prepareMaterial(material);
                    float radial = 1.0 - getUV0().x;                       // 1=轴心 0=锥缘
                    float along = getUV0().y;
                    float edge = pow(max(radial, 0.0), 1.7);               // 软边：向锥缘衰减
                    float len = pow(1.0 - along, 1.1) * smoothstep(0.0, 0.04, along);
                    float a = edge * len * materialParams.intensity;
                    material.baseColor = vec4(materialParams.color * a, 1.0); // ADD 预乘
                }
                """.trimIndent()
            )
        val pkg = builder.build()
        check(pkg.isValid) { "VFX material vfx_beam failed to compile" }
        return Material.Builder().payload(pkg.buffer, pkg.buffer.remaining()).build(engine)
    }

    /**
     * vfx_decal：贴花材质（P2 法阵/护盾）。OBJECT 域（静态网格 + 实体变换，
     * beam 同款）；顶点色 RGB = 本色×Fresnel 明度（CPU 逐帧）、alpha = 淡出
     * 包络；ADD 预乘同 vfx_additive 语义。坑 12：uniform 全走结构体语法。
     */
    private fun compileDecal(): Material {
        MaterialBuilder.init()
        val builder = MaterialBuilder()
            .name("vfx_decal")
            .platform(MaterialBuilder.Platform.MOBILE)
            .optimization(MaterialBuilder.Optimization.SIZE)
            .shading(MaterialBuilder.Shading.UNLIT)
            .blending(MaterialBuilder.BlendingMode.ADD)
            .depthWrite(false)
            .culling(MaterialBuilder.CullingMode.NONE)   // 地面/穹顶各自在 MI 上剔 BACK
            .flipUV(false)
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
                    vec4 vc = getColor();
                    float a = tex.a * vc.a * materialParams.intensity;
                    material.baseColor = vec4(tex.rgb * vc.rgb * a, 1.0); // ADD 预乘
                }
                """.trimIndent()
            )
        val pkg = builder.build()
        check(pkg.isValid) { "VFX material vfx_decal failed to compile" }
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
