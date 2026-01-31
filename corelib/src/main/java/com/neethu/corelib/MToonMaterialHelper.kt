package com.neethu.corelib

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
class MToonMaterialHelper(
    private val engine: Engine,
    private val context: Context
) {
    companion object {
        private const val TAG = "MToonMaterialHelper"
        
        // Default MToon parameters - tuned for visible toon shading
        private val DEFAULT_BASE_COLOR = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        private val DEFAULT_SHADE_COLOR = floatArrayOf(0.5f, 0.5f, 0.6f, 1.0f)  // Darker shade
        private const val DEFAULT_SHADE_TOONY = 0.5f   // Lower = more gradual transition, Higher = sharper
        private const val DEFAULT_SHADE_SHIFT = -0.1f  // Negative = more shadowed areas
        private val DEFAULT_RIM_COLOR = floatArrayOf(0.8f, 0.8f, 1.0f)  // Subtle rim
        private const val DEFAULT_RIM_POWER = 3.0f     // Lower = wider rim
        private const val DEFAULT_RIM_LIFT = 0.2f
    }
    
    private var mtoonMaterial: Material? = null
    private var simpleToonMaterial: Material? = null
    private var dummyTexture: Texture? = null
    private var unlitMaterial: Material? = null
    private var litMaterial: Material? = null  // Simple lit material for VRM
    private val materialInstances = mutableListOf<MaterialInstance>()
    
    /**
     * Load the MToon material from compiled .filamat file
     * @return true if material loaded successfully
     */
    fun loadMaterial(): Boolean {
        return try {
            // Load simple lit material (preferred - has lighting but simpler than MToon)
            loadMaterialFromAsset("materials/vrm_lit.filamat")?.let {
                litMaterial = it
                Log.i(TAG, "Loaded VRM lit material")
            }
            
            // Load unlit material as fallback
            loadMaterialFromAsset("materials/vrm_unlit.filamat")?.let {
                unlitMaterial = it
                Log.i(TAG, "Loaded VRM unlit material")
            }
            
            // Try to load full MToon material
            loadMaterialFromAsset("materials/mtoon.filamat")?.let {
                mtoonMaterial = it
                Log.i(TAG, "Loaded full MToon material")
            }
            
            // Also load simple toon as fallback
            loadMaterialFromAsset("materials/simple_toon.filamat")?.let {
                simpleToonMaterial = it
                Log.i(TAG, "Loaded simple toon material")
            }
            
            // Create a dummy white texture for materials without textures
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
        // Create a 1x1 white texture
        dummyTexture = Texture.Builder()
            .width(1)
            .height(1)
            .levels(1)
            .format(Texture.InternalFormat.RGBA8)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .build(engine)
        
        val whitePixel = ByteBuffer.allocateDirect(4)
        whitePixel.put(0xFF.toByte()) // R
        whitePixel.put(0xFF.toByte()) // G
        whitePixel.put(0xFF.toByte()) // B
        whitePixel.put(0xFF.toByte()) // A
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
        
        // Set base parameters
        instance.setParameter("baseColor", baseColor[0], baseColor[1], baseColor[2], baseColor[3])
        instance.setParameter("shadeColor", shadeColor[0], shadeColor[1], shadeColor[2], shadeColor[3])
        
        if (isFullMToon) {
            // Full MToon parameters
            instance.setParameter("shadeToony", shadeToony)
            instance.setParameter("shadeShift", shadeShift)
            instance.setParameter("rimColor", rimColor[0], rimColor[1], rimColor[2])
            instance.setParameter("rimPower", rimPower)
            instance.setParameter("rimLift", rimLift)
        }
        
        // Set dummy textures
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
     * Uses dummy textures - for use when original textures cannot be extracted
     */
    fun applyToAsset(asset: FilamentAsset) {
        applyToAssetWithTextures(asset, emptyList(), emptyList(), emptyList())
    }
    
    /**
     * Apply MToon material to a FilamentAsset with parsed VRM textures
     * @param asset The FilamentAsset to apply materials to
     * @param parsedTextures List of Filament Textures extracted from the GLB (indexed by image index)
     * @param materialInfos Material info list with resolved image indices from VRM (one per glTF material)
     * @param primitiveInfos Mapping of primitive order to material index
     */
    fun applyToAssetWithTextures(
        asset: FilamentAsset,
        parsedTextures: List<Texture>,
        materialInfos: List<VrmGlbParser.MaterialInfo>,
        primitiveInfos: List<VrmGlbParser.PrimitiveInfo>
    ) {
        // Use lit material (preferred - has proper lighting)
        // Falls back to unlit, then MToon, then simple toon
        val material = litMaterial ?: unlitMaterial ?: mtoonMaterial ?: simpleToonMaterial ?: run {
            Log.w(TAG, "No material available, skipping")
            return
        }
        val useLit = litMaterial != null
        val useUnlit = !useLit && unlitMaterial != null
        val isFullMToon = !useLit && !useUnlit && mtoonMaterial != null
        
        Log.i(TAG, "Using ${if (useLit) "LIT" else if (useUnlit) "UNLIT" else if (isFullMToon) "MTOON" else "SIMPLE_TOON"} material")
        
        val renderableManager = engine.renderableManager
        
        // Use LINEAR filter without mipmaps since we don't have them
        val sampler = TextureSampler(
            TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.REPEAT
        )
        
        var appliedCount = 0
        
        // Build a map from material name to MaterialInfo for lookup
        val materialInfoByName = materialInfos.associateBy { it.name }
        Log.d(TAG, "Material name map: ${materialInfoByName.keys}")
        
        // Apply to each renderable entity
        for (entity in asset.entities) {
            val ri = renderableManager.getInstance(entity)
            if (ri == 0) continue
            
            val primitiveCount = renderableManager.getPrimitiveCount(ri)
            for (primitiveIndex in 0 until primitiveCount) {
                // Get the ORIGINAL material instance to read its name
                val originalMi = renderableManager.getMaterialInstanceAt(ri, primitiveIndex)
                val originalMaterialName = originalMi.name
                
                // Create new MToon instance
                val newInstance = material.createInstance()
                materialInstances.add(newInstance)
                
                // Default parameters
                var baseColor = floatArrayOf(1f, 1f, 1f, 1f)
                var texture: Texture? = dummyTexture
                
                // Look up material by NAME matching
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
                
                // Set parameters based on material type
                newInstance.setParameter("baseColor", baseColor[0], baseColor[1], baseColor[2], baseColor[3])
                
                // Flip V coordinate for proper UV handling (all materials support this)
                newInstance.setParameter("flipV", true)
                
                // Set texture
                texture?.let { tex ->
                    newInstance.setParameter("mainTexture", tex, sampler)
                }
                
                // Additional parameters for lit material (shadeColor, shadingToony, shadingShift)
                if (useLit) {
                    // Shade color for toon-like effect (slightly darker than base)
                    newInstance.setParameter("shadeColor", 
                        baseColor[0] * 0.7f, baseColor[1] * 0.7f, baseColor[2] * 0.75f, 1f)
                    newInstance.setParameter("shadingToony", DEFAULT_SHADE_TOONY)
                    newInstance.setParameter("shadingShift", DEFAULT_SHADE_SHIFT)
                }
                
                // Additional parameters for full MToon materials
                if (isFullMToon) {
                    newInstance.setParameter("shadeColor", 
                        baseColor[0] * 0.7f, baseColor[1] * 0.7f, baseColor[2] * 0.75f, 1f)
                    newInstance.setParameter("shadeToony", DEFAULT_SHADE_TOONY)
                    newInstance.setParameter("shadeShift", DEFAULT_SHADE_SHIFT)
                    newInstance.setParameter("rimColor", DEFAULT_RIM_COLOR[0], DEFAULT_RIM_COLOR[1], DEFAULT_RIM_COLOR[2])
                    newInstance.setParameter("rimPower", DEFAULT_RIM_POWER)
                    newInstance.setParameter("rimLift", DEFAULT_RIM_LIFT)
                    
                    texture?.let { tex ->
                        newInstance.setParameter("shadeTexture", tex, sampler)
                    }
                }
                
                // Apply the new material
                renderableManager.setMaterialInstanceAt(ri, primitiveIndex, newInstance)
                appliedCount++
            }
        }
        
        Log.i(TAG, "Applied material to $appliedCount primitives with ${parsedTextures.size} textures")
    }
    
    /**
     * Apply MToon material with specific colors (useful for skin, hair, clothes separately)
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
