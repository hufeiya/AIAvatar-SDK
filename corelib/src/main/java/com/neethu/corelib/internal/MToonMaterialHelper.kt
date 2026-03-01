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
    
    private var mtoonMaterial: Material? = null
    private var simpleToonMaterial: Material? = null
    private var dummyTexture: Texture? = null
    private var unlitMaterial: Material? = null
    private var litMaterial: Material? = null
    private val materialInstances = mutableListOf<MaterialInstance>()
    
    /**
     * Load the MToon material from compiled .filamat file
     * @return true if material loaded successfully
     */
    fun loadMaterial(): Boolean {
        return try {
            loadMaterialFromAsset("materials/vrm_lit.filamat")?.let {
                litMaterial = it
                Log.i(TAG, "Loaded VRM lit material")
            }
            
            loadMaterialFromAsset("materials/vrm_unlit.filamat")?.let {
                unlitMaterial = it
                Log.i(TAG, "Loaded VRM unlit material")
            }
            
            loadMaterialFromAsset("materials/mtoon.filamat")?.let {
                mtoonMaterial = it
                Log.i(TAG, "Loaded full MToon material")
            }
            
            loadMaterialFromAsset("materials/simple_toon.filamat")?.let {
                simpleToonMaterial = it
                Log.i(TAG, "Loaded simple toon material")
            }
            
            createDummyTexture()
            
            unlitMaterial != null || mtoonMaterial != null || simpleToonMaterial != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MToon material", e)
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
     * Create a new MToon material instance with default parameters
     */
    fun createInstance(
        baseColor: FloatArray = DEFAULT_BASE_COLOR,
        shadeColor: FloatArray = DEFAULT_SHADE_COLOR,
        shadeToony: Float = DEFAULT_SHADE_TOONY,
        shadeShift: Float = DEFAULT_SHADE_SHIFT,
        rimColor: FloatArray = DEFAULT_RIM_COLOR,
        rimPower: Float = DEFAULT_RIM_POWER,
        rimLift: Float = DEFAULT_RIM_LIFT
    ): MaterialInstance? {
        val material = mtoonMaterial ?: simpleToonMaterial ?: return null
        val isFullMToon = mtoonMaterial != null
        
        val instance = material.createInstance()
        materialInstances.add(instance)
        
        instance.setParameter("baseColor", baseColor[0], baseColor[1], baseColor[2], baseColor[3])
        instance.setParameter("shadeColor", shadeColor[0], shadeColor[1], shadeColor[2], shadeColor[3])
        
        if (isFullMToon) {
            instance.setParameter("shadeToony", shadeToony)
            instance.setParameter("shadeShift", shadeShift)
            instance.setParameter("rimColor", rimColor[0], rimColor[1], rimColor[2])
            instance.setParameter("rimPower", rimPower)
            instance.setParameter("rimLift", rimLift)
        }
        
        dummyTexture?.let { tex ->
            val sampler = TextureSampler()
            instance.setParameter("mainTexture", tex, sampler)
            if (isFullMToon) {
                instance.setParameter("shadeTexture", tex, sampler)
            }
        }
        
        return instance
    }

    /**
     * Apply MToon material to all renderables in a FilamentAsset
     */
    fun applyToAsset(asset: FilamentAsset) {
        applyToAssetWithTextures(asset, emptyList(), emptyList(), emptyList())
    }
    
    /**
     * Apply MToon material to a FilamentAsset with parsed VRM textures
     */
    fun applyToAssetWithTextures(
        asset: FilamentAsset,
        parsedTextures: List<Texture>,
        materialInfos: List<VrmGlbParser.MaterialInfo>,
        primitiveInfos: List<VrmGlbParser.PrimitiveInfo>
    ) {
        val material = litMaterial ?: unlitMaterial ?: mtoonMaterial ?: simpleToonMaterial ?: run {
            Log.w(TAG, "No material available, skipping")
            return
        }
        val useLit = litMaterial != null
        val useUnlit = !useLit && unlitMaterial != null
        val isFullMToon = !useLit && !useUnlit && mtoonMaterial != null
        
        Log.i(TAG, "Using ${if (useLit) "LIT" else if (useUnlit) "UNLIT" else if (isFullMToon) "MTOON" else "SIMPLE_TOON"} material")
        
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
                
                val newInstance = material.createInstance()
                materialInstances.add(newInstance)
                
                var baseColor = floatArrayOf(1f, 1f, 1f, 1f)
                var texture: Texture? = dummyTexture
                
                val matInfo = materialInfoByName[originalMaterialName]
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
                
                if (useLit) {
                    if (matInfo?.isMToon == true) {
                        val shade = matInfo.shadeColorFactor ?: floatArrayOf(
                            baseColor[0] * 0.7f, baseColor[1] * 0.7f, baseColor[2] * 0.7f
                        )
                        newInstance.setParameter("shadeColor", shade[0], shade[1], shade[2], 1f)
                        newInstance.setParameter("shadingToony", matInfo.shadingToonyFactor)
                        newInstance.setParameter("shadingShift", matInfo.shadingShiftFactor)
                        newInstance.setParameter("giEqualization", matInfo.giEqualizationFactor)
                        
                        val rim = matInfo.parametricRimColorFactor ?: floatArrayOf(0f, 0f, 0f)
                        newInstance.setParameter("rimColor", rim[0], rim[1], rim[2])
                        newInstance.setParameter("rimPower", matInfo.parametricRimFresnelPowerFactor)
                        newInstance.setParameter("rimLift", matInfo.parametricRimLiftFactor)
                        newInstance.setParameter("rimLightingMix", matInfo.rimLightingMixFactor)
                        
                        val emissive = matInfo.emissiveFactor ?: floatArrayOf(0f, 0f, 0f)
                        newInstance.setParameter("emissiveColor", emissive[0], emissive[1], emissive[2])
                        
                        val shadeTexIdx = matInfo.shadeMultiplyTextureIndex
                        if (shadeTexIdx != null && shadeTexIdx < parsedTextures.size) {
                            newInstance.setParameter("shadeTexture", parsedTextures[shadeTexIdx], sampler)
                            newInstance.setParameter("hasShadeTexture", true)
                        } else {
                            dummyTexture?.let { newInstance.setParameter("shadeTexture", it, sampler) }
                            newInstance.setParameter("hasShadeTexture", false)
                        }
                        
                        val emissiveTexIdx = matInfo.emissiveTextureIndex
                        if (emissiveTexIdx != null && emissiveTexIdx < parsedTextures.size) {
                            newInstance.setParameter("emissiveTexture", parsedTextures[emissiveTexIdx], sampler)
                            newInstance.setParameter("hasEmissiveTexture", true)
                        } else {
                            dummyTexture?.let { newInstance.setParameter("emissiveTexture", it, sampler) }
                            newInstance.setParameter("hasEmissiveTexture", false)
                        }
                    } else {
                        newInstance.setParameter("shadeColor", 
                            baseColor[0] * 0.7f, baseColor[1] * 0.7f, baseColor[2] * 0.75f, 1f)
                        newInstance.setParameter("shadingToony", DEFAULT_SHADE_TOONY)
                        newInstance.setParameter("shadingShift", DEFAULT_SHADE_SHIFT)
                        newInstance.setParameter("giEqualization", 0.9f)
                        newInstance.setParameter("rimColor", DEFAULT_RIM_COLOR[0], DEFAULT_RIM_COLOR[1], DEFAULT_RIM_COLOR[2])
                        newInstance.setParameter("rimPower", DEFAULT_RIM_POWER)
                        newInstance.setParameter("rimLift", DEFAULT_RIM_LIFT)
                        newInstance.setParameter("rimLightingMix", 1.0f)
                        newInstance.setParameter("emissiveColor", 0f, 0f, 0f)
                        dummyTexture?.let { 
                            newInstance.setParameter("shadeTexture", it, sampler)
                            newInstance.setParameter("emissiveTexture", it, sampler) 
                        }
                        newInstance.setParameter("hasShadeTexture", false)
                        newInstance.setParameter("hasEmissiveTexture", false)
                    }
                }
                
                if (isFullMToon) {
                    val shade = matInfo?.shadeColorFactor ?: floatArrayOf(
                        baseColor[0] * 0.7f, baseColor[1] * 0.7f, baseColor[2] * 0.7f
                    )
                    newInstance.setParameter("shadeColor", shade[0], shade[1], shade[2], 1f)
                    newInstance.setParameter("shadeToony", matInfo?.shadingToonyFactor ?: DEFAULT_SHADE_TOONY)
                    newInstance.setParameter("shadeShift", matInfo?.shadingShiftFactor ?: DEFAULT_SHADE_SHIFT)
                    newInstance.setParameter("rimColor", DEFAULT_RIM_COLOR[0], DEFAULT_RIM_COLOR[1], DEFAULT_RIM_COLOR[2])
                    newInstance.setParameter("rimPower", DEFAULT_RIM_POWER)
                    newInstance.setParameter("rimLift", DEFAULT_RIM_LIFT)
                    
                    texture?.let { tex ->
                        newInstance.setParameter("shadeTexture", tex, sampler)
                    }
                }
                
                renderableManager.setMaterialInstanceAt(ri, primitiveIndex, newInstance)
                appliedCount++
            }
        }
        
        Log.i(TAG, "Applied material to $appliedCount primitives with ${parsedTextures.size} textures")
    }
    
    /**
     * Apply MToon material with specific colors
     */
    fun applyToAssetWithColors(
        asset: FilamentAsset,
        baseColor: FloatArray,
        shadeColor: FloatArray
    ) {
        val instance = createInstance(baseColor = baseColor, shadeColor = shadeColor) ?: run {
            Log.w(TAG, "No MToon material available, skipping")
            return
        }
        
        val renderableManager = engine.renderableManager
        
        for (entity in asset.entities) {
            val ri = renderableManager.getInstance(entity)
            if (ri != 0) {
                val primitiveCount = renderableManager.getPrimitiveCount(ri)
                for (i in 0 until primitiveCount) {
                    renderableManager.setMaterialInstanceAt(ri, i, instance)
                }
            }
        }
    }
    
    /**
     * Check if MToon material is available
     */
    fun isAvailable(): Boolean = mtoonMaterial != null || simpleToonMaterial != null
    
    /**
     * Clean up resources
     */
    fun destroy() {
        materialInstances.forEach { 
            engine.destroyMaterialInstance(it)
        }
        materialInstances.clear()
        
        mtoonMaterial?.let { engine.destroyMaterial(it) }
        simpleToonMaterial?.let { engine.destroyMaterial(it) }
        dummyTexture?.let { engine.destroyTexture(it) }
        
        mtoonMaterial = null
        simpleToonMaterial = null
        dummyTexture = null
    }
}
