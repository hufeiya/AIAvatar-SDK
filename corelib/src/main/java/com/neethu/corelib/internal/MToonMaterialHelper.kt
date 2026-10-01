package com.neethu.corelib.internal

import android.content.Context
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.FilamentAsset
import java.nio.ByteBuffer

/**
 * Helper class for loading and applying MToon-style toon shading materials
 * to VRM models in Filament.
 * 
 * MToon is the standard material for VRM avatars, providing anime-style
 * toon shading. Since Filament doesn't natively support MToon, this class
 * provides a custom implementation using Filament's customSurfaceShading.
 *
 * Three MToon variants are maintained for different blending modes (opaque,
 * masked, transparent), plus one unlit material. The correct variant is
 * selected per-primitive based on the original blending mode that gltfio
 * parsed from the glTF/VRM file.
 */
internal class MToonMaterialHelper(
    private val engine: Engine,
    private val context: Context
) {
    companion object {
        private const val TAG = "MToonMaterialHelper"

        // Defaults follow three-vrm MToonMaterial (uniform defaults) / VRM 1.0 spec.
        // shadeColor and rim color default to BLACK: without the MToon extension a plain
        // base texture must not suddenly grow shading ramps or rim glow.
        private val DEFAULT_SHADE_COLOR = floatArrayOf(0.0f, 0.0f, 0.0f)
        private const val DEFAULT_SHADE_TOONY = 0.9f
        private const val DEFAULT_SHADE_SHIFT = 0.0f
        private val DEFAULT_RIM_COLOR = floatArrayOf(0.0f, 0.0f, 0.0f)
        private const val DEFAULT_RIM_POWER = 5.0f
        private const val DEFAULT_RIM_LIFT = 0.0f
        private const val DEFAULT_RIM_LIGHTING_MIX = 1.0f
        private val DEFAULT_MATCAP_FACTOR = floatArrayOf(1.0f, 1.0f, 1.0f)

        // Filament directional lights are specified in lux while three-vrm expects
        // intensities around 1.0. Lux are normalized against this reference so that
        // the scene's sun (see SoulLinkRenderer.setupLighting) maps to ~0.95.
        private const val REFERENCE_LIGHT_LUX = 30_000f
    }
    
    // MToon lit materials — one per blending mode
    private var mtoonOpaqueMaterial: Material? = null
    private var mtoonMaskedMaterial: Material? = null
    private var mtoonTransparentMaterial: Material? = null
    
    // Unlit material for non-lit VRM materials
    private var unlitMaterial: Material? = null

    private var dummyTexture: Texture? = null
    private val materialInstances = mutableListOf<MaterialInstance>()

    // Scene light rig, pushed onto every MToon instance (existing and future).
    // lightIrradianceSum mirrors three-vrm's accumulated directSpecular used by rimLightingMix.
    private var lightIntensityScale = 1.0f / REFERENCE_LIGHT_LUX
    private var lightIrradianceSum = floatArrayOf(1.0f, 1.0f, 1.0f)

    // Repeat sampler for color/data textures, clamp sampler for matcap (must not wrap)
    private var repeatSampler: TextureSampler? = null
    private var clampSampler: TextureSampler? = null

    /**
     * Load all MToon material variants from compiled .filamat files.
     * @return true if at least one MToon material loaded successfully
     */
    fun loadMaterial(): Boolean {
        return try {
            loadMaterialFromAsset("materials/vrm_mtoon_opaque.filamat")?.let {
                mtoonOpaqueMaterial = it
                Log.i(TAG, "Loaded MToon opaque material")
            }

            loadMaterialFromAsset("materials/vrm_mtoon_masked.filamat")?.let {
                mtoonMaskedMaterial = it
                Log.i(TAG, "Loaded MToon masked material")
            }

            loadMaterialFromAsset("materials/vrm_mtoon_transparent.filamat")?.let {
                mtoonTransparentMaterial = it
                Log.i(TAG, "Loaded MToon transparent material")
            }

            loadMaterialFromAsset("materials/vrm_unlit.filamat")?.let {
                unlitMaterial = it
                Log.i(TAG, "Loaded VRM unlit material")
            }

            createDummyTexture()

            repeatSampler = TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.REPEAT
            )
            clampSampler = TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE
            )

            // Usable if at least one MToon variant loaded
            mtoonOpaqueMaterial != null || mtoonMaskedMaterial != null || mtoonTransparentMaterial != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MToon materials", e)
            false
        }
    }

    /**
     * Tell the material about the scene's directional lights so the shader can normalize
     * Filament lux into three-vrm units and blend rim lighting against the total irradiance.
     * Must be called with the same values used to build the lights (SoulLinkRenderer).
     */
    fun setLightRig(
        sunColor: FloatArray, sunLux: Float,
        fillColor: FloatArray, fillLux: Float
    ) {
        lightIntensityScale = 1.0f / REFERENCE_LIGHT_LUX
        val sunScale = sunLux * lightIntensityScale
        val fillScale = fillLux * lightIntensityScale
        lightIrradianceSum = floatArrayOf(
            sunColor[0] * sunScale + fillColor[0] * fillScale,
            sunColor[1] * sunScale + fillColor[1] * fillScale,
            sunColor[2] * sunScale + fillColor[2] * fillScale
        )

        // Re-apply to instances that were already created
        materialInstances.forEach { instance -> applyLightRig(instance) }
    }

    private fun applyLightRig(instance: MaterialInstance) {
        instance.setParameter("lightIntensityScale", lightIntensityScale)
        instance.setParameter("lightIrradianceSum",
            lightIrradianceSum[0], lightIrradianceSum[1], lightIrradianceSum[2])
    }
    
    private fun loadMaterialFromAsset(assetPath: String): Material? {
        return try {
            context.assets.open(assetPath).use { input ->
                val bytes = input.readBytes()
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                buffer.put(bytes)
                buffer.flip()
                
                Material.Builder()
                    .payload(buffer, buffer.remaining())
                    .build(engine)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load material: $assetPath - ${e.message}")
            null
        }
    }
    
    private fun createDummyTexture() {
        dummyTexture = Texture.Builder()
            .width(1)
            .height(1)
            .levels(1)
            .format(Texture.InternalFormat.RGBA8)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .build(engine)
        
        val whitePixel = ByteBuffer.allocateDirect(4)
        whitePixel.put(0xFF.toByte())
        whitePixel.put(0xFF.toByte())
        whitePixel.put(0xFF.toByte())
        whitePixel.put(0xFF.toByte())
        whitePixel.flip()
        
        dummyTexture?.setImage(engine, 0, Texture.PixelBufferDescriptor(
            whitePixel,
            Texture.Format.RGBA,
            Texture.Type.UBYTE
        ))
    }
    
    /**
     * Select the MToon material variant matching the given blending mode.
     * Falls back through masked → opaque if the exact variant is unavailable.
     */
    private fun selectMtoonMaterial(blendingMode: Material.BlendingMode): Material? {
        return when (blendingMode) {
            Material.BlendingMode.TRANSPARENT,
            Material.BlendingMode.FADE ->
                mtoonTransparentMaterial ?: mtoonMaskedMaterial ?: mtoonOpaqueMaterial
            
            Material.BlendingMode.MASKED ->
                mtoonMaskedMaterial ?: mtoonOpaqueMaterial
            
            else -> // OPAQUE and any other mode
                mtoonOpaqueMaterial ?: mtoonMaskedMaterial
        }
    }

    /**
     * Apply MToon material to all renderables in a FilamentAsset (no textures).
     */
    fun applyToAsset(asset: FilamentAsset) {
        applyToAssetWithTextures(asset, emptyList(), emptyList(), emptyList(), false)
    }
    
    /**
     * Apply MToon material to a FilamentAsset with parsed VRM textures.
     * Selects the correct material variant per-primitive based on the original blending mode.
     */
    fun applyToAssetWithTextures(
        asset: FilamentAsset,
        parsedTextures: List<Texture>,
        materialInfos: List<VrmGlbParser.MaterialInfo>,
        primitiveInfos: List<VrmGlbParser.PrimitiveInfo>,
        isV0Compat: Boolean = false
    ) {
        val renderableManager = engine.renderableManager

        val sampler = repeatSampler ?: TextureSampler(
            TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.REPEAT
        )

        var appliedCount = 0
        val materialInfoByName = materialInfos.associateBy { it.name }

        for (entity in asset.entities) {
            val ri = renderableManager.getInstance(entity)
            if (ri == 0) continue

            val primitiveCount = renderableManager.getPrimitiveCount(ri)
            for (primitiveIndex in 0 until primitiveCount) {
                val originalMi = renderableManager.getMaterialInstanceAt(ri, primitiveIndex)
                val originalMaterialName = originalMi.name
                val matInfo = materialInfoByName[originalMaterialName]

                // Read the blending mode that gltfio already parsed from glTF alphaMode
                val originalBlending = originalMi.material.blendingMode

                // Select the correct material variant
                val targetMaterial: Material = if (matInfo != null && !matInfo.isMToon && unlitMaterial != null) {
                    // Non-MToon materials use unlit
                    unlitMaterial!!
                } else {
                    val selected = selectMtoonMaterial(originalBlending)
                    if (selected == null) {
                        Log.w(TAG, "No material available for blending=$originalBlending, skipping")
                        continue
                    }
                    selected
                }

                Log.d(TAG, "Primitive '$originalMaterialName': blending=$originalBlending -> ${targetMaterial.name}")

                val newInstance = targetMaterial.createInstance()
                materialInstances.add(newInstance)

                // --- Common parameters ---
                var baseColor = floatArrayOf(1f, 1f, 1f, 1f)
                var texture: Texture? = dummyTexture

                if (matInfo != null) {
                    matInfo.baseColorFactor?.let { baseColor = it }

                    val imageIndex = matInfo.baseColorTextureIndex
                    if (imageIndex != null && imageIndex < parsedTextures.size) {
                        texture = parsedTextures[imageIndex]
                        Log.d(TAG, "Matched '$originalMaterialName' -> texture $imageIndex")
                    }
                } else {
                    Log.w(TAG, "No match for material: '$originalMaterialName'")
                }

                newInstance.setParameter("baseColorFactor", baseColor[0], baseColor[1], baseColor[2], baseColor[3])
                newInstance.setParameter("flipV", true)

                texture?.let { tex ->
                    newInstance.setParameter("mainTexture", tex, sampler)
                }

                // --- MToon-specific parameters (skip for unlit) ---
                if (targetMaterial !== unlitMaterial) {
                    applyMtoonParameters(newInstance, matInfo, parsedTextures, isV0Compat)
                    applyLightRig(newInstance)
                }

                renderableManager.setMaterialInstanceAt(ri, primitiveIndex, newInstance)
                appliedCount++
            }
        }

        Log.i(TAG, "Applied material to $appliedCount primitives with ${parsedTextures.size} textures")
    }

    /**
     * Set all MToon-specific shader parameters on a material instance.
     * Parameter names/factor semantics mirror the VRMC_materials_mtoon extension
     * (as parsed by pixiv/three-vrm's MToonMaterialLoaderPlugin).
     */
    private fun applyMtoonParameters(
        instance: MaterialInstance,
        matInfo: VrmGlbParser.MaterialInfo?,
        parsedTextures: List<Texture>,
        isV0Compat: Boolean = false
    ) {
        val sampler = repeatSampler ?: TextureSampler(
            TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.REPEAT
        )
        val clamp = clampSampler ?: sampler
        val dummy = dummyTexture ?: run {
            Log.w(TAG, "dummyTexture not initialized; skipping MToon parameters")
            return
        }

        fun textureOrDummy(imageIndex: Int?): Texture =
            imageIndex?.takeIf { it < parsedTextures.size }?.let { parsedTextures[it] } ?: dummy

        // VRM 0.x compatibility: clamp shaded color to prevent overbright
        instance.setParameter("v0CompatShade", isV0Compat)

        if (matInfo?.isMToon == true) {
            // Use parsed MToon extension values
            val shade = matInfo.shadeColorFactor ?: DEFAULT_SHADE_COLOR
            instance.setParameter("shadeColorFactor", shade[0], shade[1], shade[2])
            instance.setParameter("shadingToonyFactor", matInfo.shadingToonyFactor)
            instance.setParameter("shadingShiftFactor", matInfo.shadingShiftFactor)

            val rim = matInfo.parametricRimColorFactor ?: DEFAULT_RIM_COLOR
            instance.setParameter("parametricRimColorFactor", rim[0], rim[1], rim[2])
            instance.setParameter("parametricRimFresnelPowerFactor", matInfo.parametricRimFresnelPowerFactor)
            instance.setParameter("parametricRimLiftFactor", matInfo.parametricRimLiftFactor)
            instance.setParameter("rimLightingMixFactor", matInfo.rimLightingMixFactor)

            val matcap = matInfo.matcapFactor ?: DEFAULT_MATCAP_FACTOR
            instance.setParameter("matcapFactor", matcap[0], matcap[1], matcap[2])

            val emissive = matInfo.emissiveFactor ?: floatArrayOf(0f, 0f, 0f)
            instance.setParameter("emissiveFactor", emissive[0], emissive[1], emissive[2])

            // Shade multiply texture
            val shadeTex = textureOrDummy(matInfo.shadeMultiplyTextureIndex)
            instance.setParameter("shadeMultiplyTexture", shadeTex, sampler)
            instance.setParameter("hasShadeMultiplyTexture", matInfo.shadeMultiplyTextureIndex != null)

            // Shading shift ramp texture
            val shiftTex = textureOrDummy(matInfo.shadingShiftTextureIndex)
            instance.setParameter("shadingShiftTexture", shiftTex, sampler)
            instance.setParameter("hasShadingShiftTexture", matInfo.shadingShiftTextureIndex != null)
            instance.setParameter("shadingShiftTextureScale", matInfo.shadingShiftTextureScale)

            // Rim mask texture
            val rimTex = textureOrDummy(matInfo.rimMultiplyTextureIndex)
            instance.setParameter("rimMultiplyTexture", rimTex, sampler)
            instance.setParameter("hasRimMultiplyTexture", matInfo.rimMultiplyTextureIndex != null)

            // Matcap texture (clamped: sphere UVs already lie in [0,1])
            val matcapTex = textureOrDummy(matInfo.matcapTextureIndex)
            instance.setParameter("matcapTexture", matcapTex, clamp)
            instance.setParameter("hasMatcapTexture", matInfo.matcapTextureIndex != null)

            // Emissive texture
            val emissiveTex = textureOrDummy(matInfo.emissiveTextureIndex)
            instance.setParameter("emissiveTexture", emissiveTex, sampler)
            instance.setParameter("hasEmissiveTexture", matInfo.emissiveTextureIndex != null)
        } else {
            // Non-MToon but still using lit material — apply sensible defaults
            // Note: shade color should NOT be multiplied by baseColor here;
            // it represents a separate darker tint, and the shader mixes lit↔shade
            instance.setParameter("shadeColorFactor",
                DEFAULT_SHADE_COLOR[0], DEFAULT_SHADE_COLOR[1], DEFAULT_SHADE_COLOR[2])
            instance.setParameter("shadingToonyFactor", DEFAULT_SHADE_TOONY)
            instance.setParameter("shadingShiftFactor", DEFAULT_SHADE_SHIFT)
            instance.setParameter("parametricRimColorFactor",
                DEFAULT_RIM_COLOR[0], DEFAULT_RIM_COLOR[1], DEFAULT_RIM_COLOR[2])
            instance.setParameter("parametricRimFresnelPowerFactor", DEFAULT_RIM_POWER)
            instance.setParameter("parametricRimLiftFactor", DEFAULT_RIM_LIFT)
            instance.setParameter("rimLightingMixFactor", DEFAULT_RIM_LIGHTING_MIX)
            instance.setParameter("matcapFactor",
                DEFAULT_MATCAP_FACTOR[0], DEFAULT_MATCAP_FACTOR[1], DEFAULT_MATCAP_FACTOR[2])
            instance.setParameter("emissiveFactor", 0f, 0f, 0f)

            dummyTexture?.let { dummy ->
                instance.setParameter("shadeMultiplyTexture", dummy, sampler)
                instance.setParameter("shadingShiftTexture", dummy, sampler)
                instance.setParameter("rimMultiplyTexture", dummy, sampler)
                instance.setParameter("matcapTexture", dummy, clamp)
                instance.setParameter("emissiveTexture", dummy, sampler)
            }
            instance.setParameter("hasShadeMultiplyTexture", false)
            instance.setParameter("hasShadingShiftTexture", false)
            instance.setParameter("shadingShiftTextureScale", 1.0f)
            instance.setParameter("hasRimMultiplyTexture", false)
            instance.setParameter("hasMatcapTexture", false)
            instance.setParameter("hasEmissiveTexture", false)
        }
    }
    
    /**
     * Check if at least one MToon material variant is available.
     */
    fun isAvailable(): Boolean =
        mtoonOpaqueMaterial != null || mtoonMaskedMaterial != null || mtoonTransparentMaterial != null
    
    /**
     * Clean up all material resources.
     */
    fun destroy() {
        materialInstances.forEach { 
            engine.destroyMaterialInstance(it)
        }
        materialInstances.clear()
        
        mtoonOpaqueMaterial?.let { engine.destroyMaterial(it) }
        mtoonMaskedMaterial?.let { engine.destroyMaterial(it) }
        mtoonTransparentMaterial?.let { engine.destroyMaterial(it) }
        unlitMaterial?.let { engine.destroyMaterial(it) }
        dummyTexture?.let { engine.destroyTexture(it) }
        
        mtoonOpaqueMaterial = null
        mtoonMaskedMaterial = null
        mtoonTransparentMaterial = null
        unlitMaterial = null
        dummyTexture = null
    }
}
