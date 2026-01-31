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
        
        // Default MToon parameters matching common VRM settings
        private val DEFAULT_BASE_COLOR = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        private val DEFAULT_SHADE_COLOR = floatArrayOf(0.7f, 0.7f, 0.8f, 1.0f)
        private const val DEFAULT_SHADE_TOONY = 0.9f
        private const val DEFAULT_SHADE_SHIFT = 0.0f
        private val DEFAULT_RIM_COLOR = floatArrayOf(1.0f, 1.0f, 1.0f)
        private const val DEFAULT_RIM_POWER = 5.0f
        private const val DEFAULT_RIM_LIFT = 0.3f
    }
    
    private var mtoonMaterial: Material? = null
    private var simpleToonMaterial: Material? = null
    private var dummyTexture: Texture? = null
    private val materialInstances = mutableListOf<MaterialInstance>()
    
    /**
     * Load the MToon material from compiled .filamat file
     * @return true if material loaded successfully
     */
    fun loadMaterial(): Boolean {
        return try {
            // Try to load full MToon material first
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
            
            mtoonMaterial != null || simpleToonMaterial != null
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
     * This preserves original textures while applying toon shading
     */
    fun applyToAsset(asset: FilamentAsset) {
        val material = mtoonMaterial ?: simpleToonMaterial ?: run {
            Log.w(TAG, "No MToon material available, skipping")
            return
        }
        val isFullMToon = mtoonMaterial != null
        
        val renderableManager = engine.renderableManager
        var appliedCount = 0
        
        for (entity in asset.entities) {
            val ri = renderableManager.getInstance(entity)
            if (ri == 0) continue
            
            val primitiveCount = renderableManager.getPrimitiveCount(ri)
            for (primitiveIndex in 0 until primitiveCount) {
                // Get original material instance to extract texture
                val originalMi = renderableManager.getMaterialInstanceAt(ri, primitiveIndex)
                
                // Create new MToon instance
                val newInstance = material.createInstance()
                materialInstances.add(newInstance)
                
                // Set default parameters
                newInstance.setParameter("baseColor", 1f, 1f, 1f, 1f)
                newInstance.setParameter("shadeColor", 0.75f, 0.75f, 0.8f, 1f)
                
                if (isFullMToon) {
                    newInstance.setParameter("shadeToony", DEFAULT_SHADE_TOONY)
                    newInstance.setParameter("shadeShift", DEFAULT_SHADE_SHIFT)
                    newInstance.setParameter("rimColor", DEFAULT_RIM_COLOR[0], DEFAULT_RIM_COLOR[1], DEFAULT_RIM_COLOR[2])
                    newInstance.setParameter("rimPower", DEFAULT_RIM_POWER)
                    newInstance.setParameter("rimLift", DEFAULT_RIM_LIFT)
                }
                
                // Try to copy texture from original material
                val sampler = TextureSampler(
                    TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                    TextureSampler.MagFilter.LINEAR,
                    TextureSampler.WrapMode.REPEAT
                )
                
                // Set dummy texture as fallback (shader will handle white texture case)
                dummyTexture?.let { tex ->
                    newInstance.setParameter("mainTexture", tex, sampler)
                    if (isFullMToon) {
                        newInstance.setParameter("shadeTexture", tex, sampler)
                    }
                }
                
                // Apply the new material
                renderableManager.setMaterialInstanceAt(ri, primitiveIndex, newInstance)
                appliedCount++
            }
        }
        
        Log.i(TAG, "Applied MToon material to $appliedCount primitives")
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
