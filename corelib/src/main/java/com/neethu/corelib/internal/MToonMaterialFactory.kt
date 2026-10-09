package com.neethu.corelib.internal

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.filamat.MaterialBuilder
import com.google.android.filament.filamat.MaterialPackage

/**
 * Runtime-compiles the MToon material set with filamat (same engine version
 * as the renderer, so the .filamat payload is always compatible).
 *
 * The shader math is a port of three-vrm's `mtoon.frag` (three r180
 * semantics) — the same sources used by the desktop parity viewer in
 * `/home/neethu/projects/mtoon/filament/samples/mtoon_viewer/materials/` (mtoon_surface.mat + mtoon_outline.mat + _t/_tzw variants).
 * Differences from the desktop variant, both required by this project's
 * animated (non-baked) pipeline:
 *
 *  - The vertex normal comes from `material.worldNormal` — Filament computes
 *    it in the vertex stage (tangent decode + morph + skin + world matrix)
 *    *before* invoking `materialVertex`, so the skinned normal is available
 *    there without any custom vertex attribute. The desktop viewer baked
 *    rest-pose world normals into a `custom0` attribute instead, which only
 *    works for static geometry.
 *  - The outline width mask (`outlineWidthMultiplyTexture.g`) is sampled in
 *    the vertex stage at `material.uv0` (vertex texture fetch; the material
 *    samplers are declared for both stages) instead of being pre-baked per
 *    vertex on the CPU.
 *  - No fragment-side sRGB OETF: Filament's post-processed color grading
 *    (LinearToneMapper, applied by [SoulLinkRenderer] when MToon is active)
 *    performs the linear→sRGB conversion, so materials must output linear
 *    color. The desktop viewer ran with post-processing disabled and encoded
 *    to sRGB in-shader to byte-match three's blending.
 *
 * Variants (same matrix as three-vrm's renderQueue handling):
 *  - opaque          — OPAQUE / MASK (shader-side alphaCutoff discard)
 *  - transparent     — BLEND, depthWrite off
 *  - transparentZWrite — BLEND (`transparentWithZWrite`), depthWrite on
 *  - hidden          — draws nothing (color/depth write off); used for the
 *    outline copies of non-MToon primitives in a patched mesh
 */
internal class MToonMaterialFactory private constructor(private val engine: Engine) {

    private data class VariantConfig(
        val blending: MaterialBuilder.BlendingMode = MaterialBuilder.BlendingMode.OPAQUE,
        val depthWrite: Boolean = true,
        val culling: MaterialBuilder.CullingMode = MaterialBuilder.CullingMode.NONE,
        val doubleSided: Boolean = true,
    )

    private val OPAQUE_CONFIG = VariantConfig()
    private val OUTLINE_CONFIG = VariantConfig(
        culling = MaterialBuilder.CullingMode.FRONT,
        doubleSided = false,
    )

    val surfaceOpaque: Material
    val surfaceTransparent: Material
    val surfaceTransparentZWrite: Material
    val outlineOpaque: Material
    val outlineTransparent: Material
    val outlineTransparentZWrite: Material
    val hidden: Material

    init {
        surfaceOpaque = compile("MToonSurface", surfaceGlsl(premultiplied = false), OPAQUE_CONFIG)
        surfaceTransparent = compile(
            "MToonSurfaceT", surfaceGlsl(premultiplied = true),
            OPAQUE_CONFIG.copy(blending = MaterialBuilder.BlendingMode.TRANSPARENT),
        )
        surfaceTransparentZWrite = compile(
            "MToonSurfaceTZW", surfaceGlsl(premultiplied = true),
            OPAQUE_CONFIG.copy(blending = MaterialBuilder.BlendingMode.TRANSPARENT, depthWrite = true),
        )
        outlineOpaque = compile("MToonOutline", outlineGlsl(premultiplied = false), OUTLINE_CONFIG)
        outlineTransparent = compile(
            "MToonOutlineT", outlineGlsl(premultiplied = true),
            OUTLINE_CONFIG.copy(blending = MaterialBuilder.BlendingMode.TRANSPARENT),
        )
        outlineTransparentZWrite = compile(
            "MToonOutlineTZW", outlineGlsl(premultiplied = true),
            OUTLINE_CONFIG.copy(blending = MaterialBuilder.BlendingMode.TRANSPARENT, depthWrite = true),
        )
        hidden = compileHidden()
    }

    /** Surface/outline variant by MToon alpha mode (0 opaque, 1 blend, 2 blend+zwrite). */
    fun surface(variant: Int): Material = when (variant) {
        1 -> surfaceTransparent
        2 -> surfaceTransparentZWrite
        else -> surfaceOpaque
    }

    fun outline(variant: Int): Material = when (variant) {
        1 -> outlineTransparent
        2 -> outlineTransparentZWrite
        else -> outlineOpaque
    }

    fun destroy() {
        val e = engine
        listOf(
            surfaceOpaque, surfaceTransparent, surfaceTransparentZWrite,
            outlineOpaque, outlineTransparent, outlineTransparentZWrite, hidden,
        ).forEach { e.destroyMaterial(it) }
    }

    private fun compile(name: String, fragmentGlsl: String, config: VariantConfig): Material {
        val pkg = buildPackage(name, fragmentGlsl, config)
        check(pkg.isValid) { "MToon material $name failed to compile" }
        return Material.Builder().payload(pkg.buffer, pkg.buffer.remaining()).build(engine)
    }

    /**
     * Assembles the fragment source. `premultiplied` selects the final output
     * line: OPAQUE/MASK variants force alpha to 1.0 (three's OPAQUE define);
     * TRANSPARENT variants emit premultiplied color as filament's transparent
     * blending expects.
     */
    private fun surfaceGlsl(premultiplied: Boolean): String =
        fragmentGlsl(SURFACE_BODY, premultiplied)

    private fun outlineGlsl(premultiplied: Boolean): String =
        fragmentGlsl(OUTLINE_BODY, premultiplied)

    private fun fragmentGlsl(body: String, premultiplied: Boolean): String {
        val outLine = if (premultiplied) {
            // LINEAR output: ColorGrading (LinearToneMapper) applies the sRGB
            // OETF at the end of the post-processing pipeline.
            "material.baseColor = vec4(col * diffuseColor.a, diffuseColor.a);"
        } else {
            "material.baseColor = vec4(col, 1.0);"
        }
        return body.replace("/*OUTPUT_LINE*/", outLine)
    }

    private fun compileHidden(): Material {
        MaterialBuilder.init()
        val builder = MaterialBuilder()
            .name("MToonHidden")
            .shading(MaterialBuilder.Shading.UNLIT)
            .platform(MaterialBuilder.Platform.MOBILE)
            .optimization(MaterialBuilder.Optimization.SIZE)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .require(MaterialBuilder.VertexAttribute.TANGENTS)
            .blending(MaterialBuilder.BlendingMode.OPAQUE)
            .colorWrite(false)
            .depthWrite(false)
            .material(
                """
                void material(inout MaterialInputs material) {
                    prepareMaterial(material);
                    material.baseColor = vec4(0.0);
                }
                """.trimIndent()
            )
        val pkg = builder.build()
        check(pkg.isValid) { "MToon hidden material failed to compile" }
        return Material.Builder().payload(pkg.buffer, pkg.buffer.remaining()).build(engine)
    }

    private fun buildPackage(
        name: String,
        fragmentGlsl: String,
        config: VariantConfig,
    ): MaterialPackage {
        MaterialBuilder.init()
        val builder = MaterialBuilder()
            .name(name)
            .shading(MaterialBuilder.Shading.UNLIT)
            .platform(MaterialBuilder.Platform.MOBILE)
            .optimization(MaterialBuilder.Optimization.SIZE)
            .flipUV(false) // sample raw glTF uvs, matching three r180 image loading
            .blending(config.blending)
            .depthWrite(config.depthWrite)
            .culling(config.culling)
            .doubleSided(config.doubleSided)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .require(MaterialBuilder.VertexAttribute.TANGENTS)
            .variable(MaterialBuilder.Variable.CUSTOM0, "wnormal")
            .materialVertex(VERTEX_GLSL)

        // parameter set shared by surface & outline (outline simply ignores
        // the matcap params it doesn't declare)
        COMMON_SAMPLER_PARAMS.forEach { (samplerType, format, param) ->
            builder.samplerParameter(samplerType, format, MaterialBuilder.ParameterPrecision.DEFAULT, param)
        }
        COMMON_UNIFORM_PARAMS.forEach { (type, param) ->
            builder.uniformParameter(type, param)
        }
        OUTLINE_ONLY_UNIFORM_PARAMS.forEach { (type, param) ->
            builder.uniformParameter(type, param)
        }
        builder.material(fragmentGlsl)
        return builder.build()
    }

    companion object {
        private const val TAG = "MToonMaterials"

        fun create(engine: Engine): MToonMaterialFactory? = try {
            MToonMaterialFactory(engine)
        } catch (e: Exception) {
            Log.e(TAG, "MToon material compilation failed", e)
            null
        }

        // ── vertex stage ────────────────────────────────────────────────
        //
        // material.worldNormal is populated by the generated vertex shader
        // before materialVertex() runs: tangent decode + morphing + skinning
        // + world normal matrix — i.e. the skinned, world-space normal. It is
        // forwarded as the CUSTOM0 variable (interpolated to the fragment as
        // variable_wnormal) and re-used for the outline's inverse-hull
        // extrusion. Requires `tangents` so the attribute pipeline runs for
        // this unlit material.
        private val VERTEX_GLSL = """
            void materialVertex(inout MaterialVertexInputs material) {
                vec3 wnormal = normalize(material.worldNormal);
                material.VARIABLE_CUSTOM0 = vec4(wnormal, 0.0);

                #if defined(MTOON_OUTLINE)
                    vec3 outlineOffset = materialParams.outlineWidthFactor * wnormal;
                    if (materialParams.hasOutlineWidthMap > 0.5) {
                        // vertex texture fetch of the width mask (g channel),
                        // raw glTF uv (flipUV is false)
                        outlineOffset *= texture(
                            materialParams_outlineWidthMultiplyMap,
                            (materialParams.outlineWidthUv * vec3(material.uv0, 1.0)).xy
                        ).g;
                    }
                    if (materialParams.outlineWidthMode > 1.5) {
                        // screen-coordinate mode: scale by view depth like
                        // three's mtoon.vert (vViewPosition.z / projection[1].y)
                        vec4 viewPos = getViewFromWorldMatrix() * material.worldPosition;
                        outlineOffset *= -viewPos.z / getClipFromViewMatrix()[1][1];
                    }
                    material.worldPosition.xyz += outlineOffset;
                #endif
            }
        """.trimIndent()

        // ── fragment stage ──────────────────────────────────────────────
        //
        // Line-by-line port of the desktop mtoon_surface/mtoon_outline frag
        // blocks (three-vrm mtoon.frag, single directional light), with the
        // OETF removed: output stays linear so the ColorGrading pass does the
        // sRGB encode.
        private val SURFACE_BODY = """
            const float MTOON_RECIPROCAL_PI = 0.31830988618379;

            float mtoonLinearstep(float a, float b, float t) {
                return clamp((t - a) / (b - a), 0.0, 1.0);
            }

            // Port of three-vrm getTangentFrame() (three >= r151 branch),
            // world space — identical to three's view-space version because
            // the view transform is rigid.
            mat3 mtoonGetTangentFrame(vec3 eye_pos, vec3 surf_norm, vec2 uv) {
                vec3 q0 = dFdx(eye_pos);
                vec3 q1 = dFdy(eye_pos);
                vec2 st0 = dFdx(uv);
                vec2 st1 = dFdy(uv);
                vec3 N = surf_norm;
                vec3 q1perp = cross(q1, N);
                vec3 q0perp = cross(N, q0);
                vec3 T = q1perp * st0.x + q0perp * st1.x;
                vec3 B = q1perp * st0.y + q0perp * st1.y;
                float det = max(dot(T, T), dot(B, B));
                float scale = (det == 0.0) ? 0.0 : inversesqrt(det);
                return mat3(T * scale, B * scale, N);
            }

            void material(inout MaterialInputs material) {
                prepareMaterial(material);

                vec2 uv = getUV0();
                float faceDirection = gl_FrontFacing ? 1.0 : -1.0;
                vec3 normal = normalize(variable_wnormal.xyz) * faceDirection;

                vec4 diffuseColor = materialParams.baseColorFactor;

                vec2 mapUv = (materialParams.baseColorUv * vec3(uv, 1.0)).xy;
                vec4 sampledDiffuse = texture(materialParams_baseColorMap, mapUv);
                diffuseColor *= sampledDiffuse;
                // three alphatest_fragment: discard below cutoff (MASK materials)
                if (materialParams.alphaCutoff > 0.0 && diffuseColor.a < materialParams.alphaCutoff) {
                    discard;
                }

                vec3 diffuse = diffuseColor.rgb;
                vec2 shadeUv = (materialParams.shadeUv * vec3(uv, 1.0)).xy;
                vec3 shadeColor = materialParams.shadeColorFactor *
                    texture(materialParams_shadeMultiplyMap, shadeUv).rgb;
                float shadingShift = materialParams.shadingShiftFactor;

                if (materialParams.hasNormalMap > 0.5) {
                    vec2 normalUv = (materialParams.normalUv * vec3(uv, 1.0)).xy;
                    mat3 tbn = mtoonGetTangentFrame(getWorldPosition(), normal, uv);
                    tbn[0] *= faceDirection;
                    tbn[1] *= faceDirection;
                    vec3 mapN = texture(materialParams_normalMap, normalUv).xyz * 2.0 - 1.0;
                    mapN.xy *= materialParams.normalScale;
                    normal = normalize(tbn * mapN);
                }

                // -- lighting: single directional light -----------------------
                vec3 L = normalize(materialParams.lightDirection);
                vec3 lightColor = materialParams.lightColor;

                float dotNL = clamp(dot(normal, L), -1.0, 1.0);

                // getShading(): toony/shift shaping of dotNL
                float shading = dotNL + shadingShift;
                shading = mtoonLinearstep(
                    -1.0 + materialParams.shadingToonyFactor,
                     1.0 - materialParams.shadingToonyFactor, shading);

                // getDiffuse(): BRDF_Lambert = /PI
                vec3 col = lightColor * (mix(shadeColor, diffuse, shading) * MTOON_RECIPROCAL_PI);

                // -- rim lighting ----------------------------------------------
                vec3 V = normalize(getWorldViewVector());
                vec3 rimMix = mix(vec3(1.0), lightColor * MTOON_RECIPROCAL_PI,
                    materialParams.rimLightingMixFactor);
                vec3 rim = materialParams.rimColorFactor * pow(
                    clamp(1.0 - dot(V, normal) + materialParams.rimLiftFactor, 0.0, 1.0),
                    materialParams.rimFresnelPowerFactor);

                if (materialParams.hasMatcap > 0.5) {
                    // three computes matcap uv in view space; matcap ignores its
                    // own uv transform only in VRM0 — apply it like three does
                    mat3 Vm = mat3(getViewFromWorldMatrix());
                    vec3 viewDir = normalize(Vm * V);
                    vec3 nView = normalize(Vm * normal);
                    vec3 x = normalize(vec3(viewDir.z, 0.0, -viewDir.x));
                    vec3 y = cross(viewDir, x);
                    vec2 sphereUv = 0.5 + 0.5 * vec2(dot(x, nView), -dot(y, nView));
                    sphereUv = (materialParams.matcapUv * vec3(sphereUv, 1.0)).xy;
                    vec3 matcap = texture(materialParams_matcapMap, sphereUv).rgb;
                    rim += materialParams.matcapFactor * matcap;
                }

                col += rimMix * rim;

                // -- emission --------------------------------------------------
                vec3 totalEmissive = materialParams.emissiveFactor * materialParams.emissiveIntensity;
                vec2 emissiveUv = (materialParams.emissiveUv * vec3(uv, 1.0)).xy;
                totalEmissive *= texture(materialParams_emissiveMap, emissiveUv).rgb;
                col += totalEmissive;

                /*OUTPUT_LINE*/
            }
        """.trimIndent()

        private val OUTLINE_BODY = """
            const float MTOON_RECIPROCAL_PI = 0.31830988618379;

            float mtoonLinearstep(float a, float b, float t) {
                return clamp((t - a) / (b - a), 0.0, 1.0);
            }

            mat3 mtoonGetTangentFrame(vec3 eye_pos, vec3 surf_norm, vec2 uv) {
                vec3 q0 = dFdx(eye_pos);
                vec3 q1 = dFdy(eye_pos);
                vec2 st0 = dFdx(uv);
                vec2 st1 = dFdy(uv);
                vec3 N = surf_norm;
                vec3 q1perp = cross(q1, N);
                vec3 q0perp = cross(N, q0);
                vec3 T = q1perp * st0.x + q0perp * st1.x;
                vec3 B = q1perp * st0.y + q0perp * st1.y;
                float det = max(dot(T, T), dot(B, B));
                float scale = (det == 0.0) ? 0.0 : inversesqrt(det);
                return mat3(T * scale, B * scale, N);
            }

            void material(inout MaterialInputs material) {
                prepareMaterial(material);

                // three renders the outline with side=BackSide: the vertex
                // stage negates the normal (FLIP_SIDED) and the OUTLINE define
                // negates it again, so the effective shading normal is the
                // original outward normal. No face flip.
                vec2 uv = getUV0();
                vec3 normal = normalize(variable_wnormal.xyz);

                vec4 diffuseColor = materialParams.baseColorFactor;

                vec2 mapUv = (materialParams.baseColorUv * vec3(uv, 1.0)).xy;
                vec4 sampledDiffuse = texture(materialParams_baseColorMap, mapUv);
                diffuseColor *= sampledDiffuse;
                if (materialParams.alphaCutoff > 0.0 && diffuseColor.a < materialParams.alphaCutoff) {
                    discard;
                }

                vec3 diffuse = diffuseColor.rgb;
                vec2 shadeUv = (materialParams.shadeUv * vec3(uv, 1.0)).xy;
                vec3 shadeColor = materialParams.shadeColorFactor *
                    texture(materialParams_shadeMultiplyMap, shadeUv).rgb;
                float shadingShift = materialParams.shadingShiftFactor;

                if (materialParams.hasNormalMap > 0.5) {
                    vec2 normalUv = (materialParams.normalUv * vec3(uv, 1.0)).xy;
                    mat3 tbn = mtoonGetTangentFrame(getWorldPosition(), normal, uv);
                    vec3 mapN = texture(materialParams_normalMap, normalUv).xyz * 2.0 - 1.0;
                    mapN.xy *= materialParams.normalScale;
                    normal = normalize(tbn * mapN);
                }

                vec3 L = normalize(materialParams.lightDirection);
                vec3 lightColor = materialParams.lightColor;

                float dotNL = clamp(dot(normal, L), -1.0, 1.0);
                float shading = dotNL + shadingShift;
                shading = mtoonLinearstep(
                    -1.0 + materialParams.shadingToonyFactor,
                     1.0 - materialParams.shadingToonyFactor, shading);

                vec3 col = lightColor * (mix(shadeColor, diffuse, shading) * MTOON_RECIPROCAL_PI);

                vec3 V = normalize(getWorldViewVector());
                vec3 rimMix = mix(vec3(1.0), lightColor * MTOON_RECIPROCAL_PI,
                    materialParams.rimLightingMixFactor);
                vec3 rim = materialParams.rimColorFactor * pow(
                    clamp(1.0 - dot(V, normal) + materialParams.rimLiftFactor, 0.0, 1.0),
                    materialParams.rimFresnelPowerFactor);
                col += rimMix * rim;

                vec3 totalEmissive = materialParams.emissiveFactor * materialParams.emissiveIntensity;
                vec2 emissiveUv = (materialParams.emissiveUv * vec3(uv, 1.0)).xy;
                totalEmissive *= texture(materialParams_emissiveMap, emissiveUv).rgb;
                col += totalEmissive;

                // -- outline override -----------------------------------------
                col = materialParams.outlineColorFactor.rgb *
                      mix(vec3(1.0), col, materialParams.outlineLightingMixFactor);

                /*OUTPUT_LINE*/
            }
        """.trimIndent()

        // (sampler type, format, name) — set on every surface/outline variant.
        // outlineWidthMultiplyMap is only read by the outline vertex stage but
        // declaring it on the surface materials too keeps one parameter list;
        // the extra sampler binding is free.
        private val COMMON_SAMPLER_PARAMS = listOf(
            Triple(MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT, "baseColorMap"),
            Triple(MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT, "shadeMultiplyMap"),
            Triple(MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT, "normalMap"),
            Triple(MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT, "emissiveMap"),
            Triple(MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT, "matcapMap"),
            Triple(MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT, "outlineWidthMultiplyMap"),
        )

        private val COMMON_UNIFORM_PARAMS = listOf(
            Pair(MaterialBuilder.UniformType.FLOAT4, "baseColorFactor"),
            Pair(MaterialBuilder.UniformType.MAT3, "baseColorUv"),
            Pair(MaterialBuilder.UniformType.FLOAT3, "shadeColorFactor"),
            Pair(MaterialBuilder.UniformType.MAT3, "shadeUv"),
            Pair(MaterialBuilder.UniformType.FLOAT, "shadingShiftFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "shadingToonyFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "hasNormalMap"),
            Pair(MaterialBuilder.UniformType.FLOAT2, "normalScale"),
            Pair(MaterialBuilder.UniformType.MAT3, "normalUv"),
            Pair(MaterialBuilder.UniformType.FLOAT3, "emissiveFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "emissiveIntensity"),
            Pair(MaterialBuilder.UniformType.MAT3, "emissiveUv"),
            Pair(MaterialBuilder.UniformType.FLOAT3, "rimColorFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "rimLightingMixFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "rimFresnelPowerFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "rimLiftFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "hasMatcap"),
            Pair(MaterialBuilder.UniformType.FLOAT3, "matcapFactor"),
            Pair(MaterialBuilder.UniformType.MAT3, "matcapUv"),
            Pair(MaterialBuilder.UniformType.FLOAT, "alphaCutoff"),
            Pair(MaterialBuilder.UniformType.FLOAT3, "lightDirection"),
            Pair(MaterialBuilder.UniformType.FLOAT3, "lightColor"),
        )

        private val OUTLINE_ONLY_UNIFORM_PARAMS = listOf(
            Pair(MaterialBuilder.UniformType.FLOAT3, "outlineColorFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "outlineLightingMixFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "outlineWidthFactor"),
            Pair(MaterialBuilder.UniformType.FLOAT, "outlineWidthMode"), // 0 none, 1 world, 2 screen
            Pair(MaterialBuilder.UniformType.FLOAT, "hasOutlineWidthMap"),
            Pair(MaterialBuilder.UniformType.MAT3, "outlineWidthUv"),
        )
    }
}
