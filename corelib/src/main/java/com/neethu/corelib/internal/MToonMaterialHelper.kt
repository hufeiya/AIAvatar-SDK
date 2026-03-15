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
        
        // Default MToon parameters - tuned for visible toon shading
        private val DEFAULT_BASE_COLOR = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        private val DEFAULT_SHADE_COLOR = floatArrayOf(0.5f, 0.5f, 0.6f, 1.0f)
        private const val DEFAULT_SHADE_TOONY = 0.5f
        private const val DEFAULT_SHADE_SHIFT = -0.1f
        private val DEFAULT_RIM_COLOR = floatArrayOf(0.8f, 0.8f, 1.0f)
        private const val DEFAULT_RIM_POWER = 3.0f
        private const val DEFAULT_RIM_LIFT = 0.2f
    }
    
    // MToon lit materials — one per blending mode
    private var mtoonOpaqueMaterial: Material? = null
    private var mtoonMaskedMaterial: Material? = null
    private var mtoonTransparentMaterial: Material? = null
    
    // Unlit material for non-lit VRM materials
    private var unlitMaterial: Material? = null
    
    private var dummyTexture: Texture? = null
    private val materialInstances = mutableListOf<MaterialInstance>()
    
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
            
            // Usable if at least one MToon variant loaded
            mtoonOpaqueMaterial != null || mtoonMaskedMaterial != null || mtoonTransparentMaterial != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MToon materials", e)
            false
        }
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
        applyToAssetWithTextures(asset, emptyList(), emptyList(), emptyList())
    }
    
    /**
     * Apply MToon material to a FilamentAsset with parsed VRM textures.
     * Selects the correct material variant per-primitive based on the original blending mode.
     */
    fun applyToAssetWithTextures(
        asset: FilamentAsset,
        parsedTextures: List<Texture>,
        materialInfos: List<VrmGlbParser.MaterialInfo>,
        primitiveInfos: List<VrmGlbParser.PrimitiveInfo>
    ) {
        val renderableManager = engine.renderableManager
        
        val sampler = TextureSampler(
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
                
                newInstance.setParameter("baseColor", baseColor[0], baseColor[1], baseColor[2], baseColor[3])
                newInstance.setParameter("flipV", true)
                
                texture?.let { tex ->
                    newInstance.setParameter("mainTexture", tex, sampler)
                }
                
                // --- MToon-specific parameters (skip for unlit) ---
                if (targetMaterial !== unlitMaterial) {
                    applyMtoonParameters(newInstance, matInfo, baseColor, parsedTextures, sampler)
                }
                
                renderableManager.setMaterialInstanceAt(ri, primitiveIndex, newInstance)
                appliedCount++
            }
        }
        
        Log.i(TAG, "Applied material to $appliedCount primitives with ${parsedTextures.size} textures")
    }
    
    /**
     * Set all MToon-specific shader parameters on a material instance.
     */
    private fun applyMtoonParameters(
        instance: MaterialInstance,
        matInfo: VrmGlbParser.MaterialInfo?,
        baseColor: FloatArray,
        parsedTextures: List<Texture>,
        sampler: TextureSampler
    ) {
        if (matInfo?.isMToon == true) {
            // Use parsed MToon extension values
            val shade = matInfo.shadeColorFactor ?: floatArrayOf(
                baseColor[0] * 0.7f, baseColor[1] * 0.7f, baseColor[2] * 0.7f
            )
            instance.setParameter("shadeColor", shade[0], shade[1], shade[2], 1f)
            instance.setParameter("shadingToony", matInfo.shadingToonyFactor)
            instance.setParameter("shadingShift", matInfo.shadingShiftFactor)
            instance.setParameter("giEqualization", matInfo.giEqualizationFactor)
            
            val rim = matInfo.parametricRimColorFactor ?: floatArrayOf(0f, 0f, 0f)
            instance.setParameter("rimColor", rim[0], rim[1], rim[2])
            instance.setParameter("rimPower", matInfo.parametricRimFresnelPowerFactor)
            instance.setParameter("rimLift", matInfo.parametricRimLiftFactor)
            instance.setParameter("rimLightingMix", matInfo.rimLightingMixFactor)
            
            val emissive = matInfo.emissiveFactor ?: floatArrayOf(0f, 0f, 0f)
            instance.setParameter("emissiveColor", emissive[0], emissive[1], emissive[2])
            
            // Shade texture
            val shadeTexIdx = matInfo.shadeMultiplyTextureIndex
            if (shadeTexIdx != null && shadeTexIdx < parsedTextures.size) {
                instance.setParameter("shadeTexture", parsedTextures[shadeTexIdx], sampler)
                instance.setParameter("hasShadeTexture", true)
            } else {
                dummyTexture?.let { instance.setParameter("shadeTexture", it, sampler) }
                instance.setParameter("hasShadeTexture", false)
            }
            
            // Emissive texture
            val emissiveTexIdx = matInfo.emissiveTextureIndex
            if (emissiveTexIdx != null && emissiveTexIdx < parsedTextures.size) {
                instance.setParameter("emissiveTexture", parsedTextures[emissiveTexIdx], sampler)
                instance.setParameter("hasEmissiveTexture", true)
            } else {
                dummyTexture?.let { instance.setParameter("emissiveTexture", it, sampler) }
                instance.setParameter("hasEmissiveTexture", false)
            }
        } else {
            // Non-MToon but still using lit material — apply sensible defaults
            instance.setParameter("shadeColor",
                baseColor[0] * 0.7f, baseColor[1] * 0.7f, baseColor[2] * 0.75f, 1f)
            instance.setParameter("shadingToony", DEFAULT_SHADE_TOONY)
            instance.setParameter("shadingShift", DEFAULT_SHADE_SHIFT)
            instance.setParameter("giEqualization", 0.9f)
            instance.setParameter("rimColor", DEFAULT_RIM_COLOR[0], DEFAULT_RIM_COLOR[1], DEFAULT_RIM_COLOR[2])
            instance.setParameter("rimPower", DEFAULT_RIM_POWER)
            instance.setParameter("rimLift", DEFAULT_RIM_LIFT)
            instance.setParameter("rimLightingMix", 1.0f)
            instance.setParameter("emissiveColor", 0f, 0f, 0f)
            dummyTexture?.let { 
                instance.setParameter("shadeTexture", it, sampler)
                instance.setParameter("emissiveTexture", it, sampler) 
            }
            instance.setParameter("hasShadeTexture", false)
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
