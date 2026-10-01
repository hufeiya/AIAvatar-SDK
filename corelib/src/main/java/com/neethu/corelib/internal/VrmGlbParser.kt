package com.neethu.corelib.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for VRM/GLB files to extract texture data.
 * GLB is a binary format that embeds both JSON and binary data.
 * VRM models use the VRMC_materials_mtoon extension which references baseColorTexture.
 */
internal class VrmGlbParser(private val engine: Engine) {
    
    companion object {
        private const val TAG = "VrmGlbParser"
        
        // GLB magic number: "glTF" in little endian
        private const val GLB_MAGIC = 0x46546C67
        private const val GLB_VERSION = 2
        private const val CHUNK_TYPE_JSON = 0x4E4F534A  // "JSON"
        private const val CHUNK_TYPE_BIN = 0x004E4942   // "BIN\0"
    }
    
    data class ParsedVrm(
        val json: JsonObject,
        val textures: List<Texture>
    )
    
    data class MaterialInfo(
        val name: String?,
        val baseColorTextureIndex: Int?,  // Image index (resolved from texture index)
        val baseColorFactor: FloatArray? = null,
        // MToon extension (VRMC_materials_mtoon) properties
        val shadeColorFactor: FloatArray? = null,       // RGB shade color
        val shadingShiftFactor: Float = 0.0f,
        val shadingToonyFactor: Float = 0.9f,
        val shadeMultiplyTextureIndex: Int? = null,     // Image index for shade texture
        val shadingShiftTextureIndex: Int? = null,      // Image index for shading shift ramp
        val shadingShiftTextureScale: Float = 1.0f,
        val emissiveFactor: FloatArray? = null,          // RGB emissive color
        val emissiveTextureIndex: Int? = null,           // Image index for emissive texture
        val parametricRimColorFactor: FloatArray? = null, // RGB rim color
        val parametricRimFresnelPowerFactor: Float = 5.0f,
        val parametricRimLiftFactor: Float = 0.0f,
        val rimLightingMixFactor: Float = 1.0f,
        val rimMultiplyTextureIndex: Int? = null,        // Image index for rim mask
        val matcapTextureIndex: Int? = null,             // Image index for matcap
        val matcapFactor: FloatArray? = null,            // RGB matcap tint
        val giEqualizationFactor: Float = 0.9f,
        val isMToon: Boolean = false                     // Whether material has MToon extension
    )
    
    /**
     * Info for each mesh primitive in iteration order
     * Stores the material index this primitive uses
     */
    data class PrimitiveInfo(
        val meshIndex: Int,
        val primitiveIndex: Int,
        val materialIndex: Int?
    )
    
    /**
     * Parse a GLB file and extract textures
     */
    fun parse(glbBuffer: ByteBuffer): ParsedVrm? {
        glbBuffer.order(ByteOrder.LITTLE_ENDIAN)
        glbBuffer.rewind()
        
        // Read GLB header (12 bytes)
        val magic = glbBuffer.int
        val version = glbBuffer.int
        val length = glbBuffer.int
        
        if (magic != GLB_MAGIC) {
            Log.e(TAG, "Invalid GLB magic number: $magic (expected $GLB_MAGIC)")
            return null
        }
        
        if (version != GLB_VERSION) {
            Log.w(TAG, "GLB version $version (expected $GLB_VERSION)")
        }
        
        Log.d(TAG, "GLB file: version=$version, length=$length")
        
        var jsonChunk: String? = null
        var binaryBuffer: ByteBuffer? = null
        
        // Read chunks
        while (glbBuffer.hasRemaining()) {
            val chunkLength = glbBuffer.int
            val chunkType = glbBuffer.int
            
            when (chunkType) {
                CHUNK_TYPE_JSON -> {
                    val jsonBytes = ByteArray(chunkLength)
                    glbBuffer.get(jsonBytes)
                    jsonChunk = String(jsonBytes, Charsets.UTF_8)
                    Log.d(TAG, "Found JSON chunk: ${chunkLength} bytes")
                }
                CHUNK_TYPE_BIN -> {
                    val binBytes = ByteArray(chunkLength)
                    glbBuffer.get(binBytes)
                    binaryBuffer = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)
                    Log.d(TAG, "Found BIN chunk: ${chunkLength} bytes")
                }
                else -> {
                    // Skip unknown chunks
                    glbBuffer.position(glbBuffer.position() + chunkLength)
                    Log.d(TAG, "Skipping unknown chunk type: $chunkType")
                }
            }
        }
        
        if (jsonChunk == null) {
            Log.e(TAG, "No JSON chunk found in GLB")
            return null
        }
        
        val gson = Gson()
        val json = gson.fromJson(jsonChunk, JsonObject::class.java)
        
        // Extract textures
        val textures = extractTextures(json, binaryBuffer)
        // check bones number
        val skins = json.getAsJsonArray("skins")
        skins?.forEach { skinElement ->
            val joints = skinElement.asJsonObject.getAsJsonArray("joints")
            Log.i("VrmParser", "Skin has ${joints?.size()} joints/bones")
        }

        return ParsedVrm(json, textures)
    }
    
    /**
     * Extract all textures from the GLB
     */
    private fun extractTextures(json: JsonObject, binaryBuffer: ByteBuffer?): List<Texture> {
        if (binaryBuffer == null) {
            Log.w(TAG, "No binary buffer, cannot extract textures")
            return emptyList()
        }
        
        val textures = mutableListOf<Texture>()
        val imagesArray = json.getAsJsonArray("images") ?: return emptyList()
        val bufferViewsArray = json.getAsJsonArray("bufferViews") ?: return emptyList()
        
        Log.d(TAG, "Found ${imagesArray.size()} images in GLB")
        
        for (i in 0 until imagesArray.size()) {
            val imageObj = imagesArray[i].asJsonObject
            val bufferViewIndex = imageObj.get("bufferView")?.asInt ?: continue
            val mimeType = imageObj.get("mimeType")?.asString ?: "image/png"
            
            if (bufferViewIndex >= bufferViewsArray.size()) continue
            
            val bufferView = bufferViewsArray[bufferViewIndex].asJsonObject
            val byteOffset = bufferView.get("byteOffset")?.asInt ?: 0
            val byteLength = bufferView.get("byteLength")?.asInt ?: continue
            
            try {
                // Extract image bytes from buffer
                val imageBytes = ByteArray(byteLength)
                binaryBuffer.position(byteOffset)
                binaryBuffer.get(imageBytes)
                
                // Decode to bitmap
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, byteLength)
                if (bitmap != null) {
                    val texture = createTextureFromBitmap(bitmap)
                    textures.add(texture)
                    Log.d(TAG, "Created texture $i: ${bitmap.width}x${bitmap.height}")
                } else {
                    Log.w(TAG, "Failed to decode image $i (mimeType: $mimeType)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting image $i", e)
            }
        }
        
        return textures
    }
    
    /**
     * Create a Filament Texture from a Bitmap
     */
    private fun createTextureFromBitmap(bitmap: Bitmap): Texture {
        val width = bitmap.width
        val height = bitmap.height
        
        val rgbaBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        
        val texture = Texture.Builder()
            .width(width)
            .height(height)
            .levels(1)
            .format(Texture.InternalFormat.SRGB8_A8)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .build(engine)
        
        val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
        pixelBuffer.order(ByteOrder.nativeOrder())
        rgbaBitmap.copyPixelsToBuffer(pixelBuffer)
        pixelBuffer.flip()
        
        texture.setImage(engine, 0, Texture.PixelBufferDescriptor(
            pixelBuffer,
            Texture.Format.RGBA,
            Texture.Type.UBYTE
        ))
        
        if (rgbaBitmap !== bitmap) {
            rgbaBitmap.recycle()
        }
        
        return texture
    }
    
    /**
     * Get material information including texture indices.
     * Handles both VRM 1.0 (VRMC_materials_mtoon) and VRM 0.x (VRM/materialProperties).
     */
    fun getMaterialInfos(json: JsonObject): List<MaterialInfo> {
        val materials = mutableListOf<MaterialInfo>()
        val materialsArray = json.getAsJsonArray("materials") ?: return emptyList()
        val texturesArray = json.getAsJsonArray("textures")
        
        // Check for VRM 0.x materialProperties (top-level extensions.VRM.materialProperties[])
        val v0MaterialProperties = json.getAsJsonObject("extensions")
            ?.getAsJsonObject("VRM")
            ?.getAsJsonArray("materialProperties")
        
        for (i in 0 until materialsArray.size()) {
            val materialObj = materialsArray[i].asJsonObject
            val name = materialObj.get("name")?.asString
            
            var imageIndex: Int? = null
            var baseColorFactor: FloatArray? = null
            
            materialObj.getAsJsonObject("pbrMetallicRoughness")?.let { pbr ->
                pbr.getAsJsonObject("baseColorTexture")?.let { texInfo ->
                    val textureIndex = texInfo.get("index")?.asInt
                    if (textureIndex != null && texturesArray != null && textureIndex < texturesArray.size()) {
                        val textureObj = texturesArray[textureIndex].asJsonObject
                        imageIndex = textureObj.get("source")?.asInt
                    }
                }
                pbr.getAsJsonArray("baseColorFactor")?.let { factor ->
                    baseColorFactor = FloatArray(4) { j -> 
                        if (j < factor.size()) factor[j].asFloat else 1f 
                    }
                }
            }
            
            if (baseColorFactor == null) {
                baseColorFactor = floatArrayOf(1f, 1f, 1f, 1f)
            }
            
            // Parse emissive
            var emissiveFactor: FloatArray? = null
            var emissiveTextureIndex: Int? = null
            materialObj.getAsJsonArray("emissiveFactor")?.let { factor ->
                emissiveFactor = FloatArray(3) { j ->
                    if (j < factor.size()) factor[j].asFloat else 0f
                }
            }
            materialObj.getAsJsonObject("emissiveTexture")?.let { texInfo ->
                val textureIndex = texInfo.get("index")?.asInt
                if (textureIndex != null && texturesArray != null && textureIndex < texturesArray.size()) {
                    val textureObj = texturesArray[textureIndex].asJsonObject
                    emissiveTextureIndex = textureObj.get("source")?.asInt
                }
            }
            
            // Parse VRMC_materials_mtoon extension (VRM 1.0)
            var shadeColorFactor: FloatArray? = null
            var shadingShiftFactor = 0.0f
            var shadingToonyFactor = 0.9f
            var shadeMultiplyTextureIndex: Int? = null
            var shadingShiftTextureIndex: Int? = null
            var shadingShiftTextureScale = 1.0f
            var parametricRimColorFactor: FloatArray? = null
            var parametricRimFresnelPowerFactor = 5.0f
            var parametricRimLiftFactor = 0.0f
            var rimLightingMixFactor = 1.0f
            var rimMultiplyTextureIndex: Int? = null
            var matcapTextureIndex: Int? = null
            var matcapFactor: FloatArray? = null
            var giEqualizationFactor = 0.9f
            var isMToon = false

            materialObj.getAsJsonObject("extensions")
                ?.getAsJsonObject("VRMC_materials_mtoon")?.let { mtoon ->
                    isMToon = true

                    mtoon.getAsJsonArray("shadeColorFactor")?.let { factor ->
                        shadeColorFactor = FloatArray(3) { j ->
                            if (j < factor.size()) factor[j].asFloat else 0f
                        }
                    }

                    mtoon.get("shadingShiftFactor")?.asFloat?.let { shadingShiftFactor = it }
                    mtoon.get("shadingToonyFactor")?.asFloat?.let { shadingToonyFactor = it }
                    mtoon.get("giEqualizationFactor")?.asFloat?.let { giEqualizationFactor = it }

                    shadeMultiplyTextureIndex = resolveImageIndex(mtoon.getAsJsonObject("shadeMultiplyTexture"), texturesArray)
                    mtoon.getAsJsonObject("shadingShiftTexture")?.let { texInfo ->
                        shadingShiftTextureIndex = resolveImageIndex(texInfo, texturesArray)
                        texInfo.get("scale")?.asFloat?.let { shadingShiftTextureScale = it }
                    }
                    mtoon.getAsJsonArray("matcapFactor")?.let { factor ->
                        matcapFactor = FloatArray(3) { j ->
                            if (j < factor.size()) factor[j].asFloat else 1f
                        }
                    }
                    matcapTextureIndex = resolveImageIndex(mtoon.getAsJsonObject("matcapTexture"), texturesArray)
                    rimMultiplyTextureIndex = resolveImageIndex(mtoon.getAsJsonObject("rimMultiplyTexture"), texturesArray)

                    mtoon.getAsJsonArray("parametricRimColorFactor")?.let { factor ->
                        parametricRimColorFactor = FloatArray(3) { j ->
                            if (j < factor.size()) factor[j].asFloat else 0f
                        }
                    }
                    mtoon.get("parametricRimFresnelPowerFactor")?.asFloat?.let { parametricRimFresnelPowerFactor = it }
                    mtoon.get("parametricRimLiftFactor")?.asFloat?.let { parametricRimLiftFactor = it }
                    mtoon.get("rimLightingMixFactor")?.asFloat?.let { rimLightingMixFactor = it }

                    Log.d(TAG, "MToon V1 ext for '$name': shade=${shadeColorFactor?.contentToString()}, shift=$shadingShiftFactor, toony=$shadingToonyFactor, matcapTex=$matcapTextureIndex, rimTex=$rimMultiplyTextureIndex, shiftTex=$shadingShiftTextureIndex")
                }
            
            // Fallback: parse VRM 0.x materialProperties if no V1 MToon found
            if (!isMToon && v0MaterialProperties != null && i < v0MaterialProperties.size()) {
                val v0Mat = v0MaterialProperties[i].asJsonObject
                val shader = v0Mat.get("shader")?.asString
                
                if (shader == "VRM/MToon") {
                    isMToon = true
                    val floatProps = v0Mat.getAsJsonObject("floatProperties")
                    val vecProps = v0Mat.getAsJsonObject("vectorProperties")
                    val texProps = v0Mat.getAsJsonObject("textureProperties")
                    
                    // Base color: _Color (gamma-encoded RGB, linear alpha)
                    vecProps?.getAsJsonArray("_Color")?.let { c ->
                        baseColorFactor = FloatArray(4) { j ->
                            val v = if (j < c.size()) c[j].asFloat else 1f
                            if (j < 3) gammaEOTF(v) else v  // RGB: gamma→linear, A: keep linear
                        }
                    }
                    
                    // Base texture: _MainTex
                    texProps?.get("_MainTex")?.asInt?.let { texIdx ->
                        if (texturesArray != null && texIdx < texturesArray.size()) {
                            imageIndex = texturesArray[texIdx].asJsonObject.get("source")?.asInt
                        }
                    }
                    
                    // Shade color: _ShadeColor (gamma-encoded)
                    vecProps?.getAsJsonArray("_ShadeColor")?.let { c ->
                        shadeColorFactor = FloatArray(3) { j ->
                            gammaEOTF(if (j < c.size()) c[j].asFloat else 0.97f)
                        }
                    }
                    
                    // Shade texture: _ShadeTexture
                    texProps?.get("_ShadeTexture")?.asInt?.let { texIdx ->
                        if (texturesArray != null && texIdx < texturesArray.size()) {
                            shadeMultiplyTextureIndex = texturesArray[texIdx].asJsonObject.get("source")?.asInt
                        }
                    }
                    
                    // Shade shift/toony: V0→V1 conversion (three-vrm formula)
                    // V0: _ShadeShift, _ShadeToony
                    // V1: shadingToonyFactor = lerp(v0Toony, 1.0, 0.5 + 0.5 * v0ShadeShift)
                    //     shadingShiftFactor = -v0ShadeShift - (1.0 - shadingToonyFactor)
                    val v0ShadeShift = floatProps?.get("_ShadeShift")?.asFloat ?: 0.0f
                    val v0ShadeToony = floatProps?.get("_ShadeToony")?.asFloat ?: 0.9f
                    val lerpT = 0.5f + 0.5f * v0ShadeShift
                    shadingToonyFactor = v0ShadeToony + (1.0f - v0ShadeToony) * lerpT
                    shadingShiftFactor = -v0ShadeShift - (1.0f - shadingToonyFactor)
                    
                    // GI: _IndirectLightIntensity → giEqualizationFactor = 1.0 - intensity
                    val giIntensity = floatProps?.get("_IndirectLightIntensity")?.asFloat ?: 0.1f
                    giEqualizationFactor = if (giIntensity > 0f) 1.0f - giIntensity else 0.9f
                    
                    // Emissive: _EmissionColor (gamma-encoded)
                    vecProps?.getAsJsonArray("_EmissionColor")?.let { c ->
                        emissiveFactor = FloatArray(3) { j ->
                            gammaEOTF(if (j < c.size()) c[j].asFloat else 0f)
                        }
                    }
                    
                    // Emissive texture: _EmissionMap
                    texProps?.get("_EmissionMap")?.asInt?.let { texIdx ->
                        if (texturesArray != null && texIdx < texturesArray.size()) {
                            emissiveTextureIndex = texturesArray[texIdx].asJsonObject.get("source")?.asInt
                        }
                    }
                    
                    // Rim: _RimColor (gamma-encoded), _RimFresnelPower, _RimLift, _RimLightingMix
                    vecProps?.getAsJsonArray("_RimColor")?.let { c ->
                        parametricRimColorFactor = FloatArray(3) { j ->
                            gammaEOTF(if (j < c.size()) c[j].asFloat else 0f)
                        }
                    }
                    parametricRimFresnelPowerFactor = floatProps?.get("_RimFresnelPower")?.asFloat ?: 1.0f
                    parametricRimLiftFactor = floatProps?.get("_RimLift")?.asFloat ?: 0.0f
                    rimLightingMixFactor = floatProps?.get("_RimLightingMix")?.asFloat ?: 0.0f

                    // Rim mask texture: _RimTexture (three-vrm v0compat maps this to rimMultiplyTexture)
                    texProps?.get("_RimTexture")?.asInt?.let { texIdx ->
                        if (texturesArray != null && texIdx < texturesArray.size()) {
                            rimMultiplyTextureIndex = texturesArray[texIdx].asJsonObject.get("source")?.asInt
                        }
                    }

                    // Matcap: _SphereAdd (three-vrm v0compat uses factor [1,1,1] for V0)
                    texProps?.get("_SphereAdd")?.asInt?.let { texIdx ->
                        if (texturesArray != null && texIdx < texturesArray.size()) {
                            matcapTextureIndex = texturesArray[texIdx].asJsonObject.get("source")?.asInt
                            if (matcapFactor == null) matcapFactor = floatArrayOf(1f, 1f, 1f)
                        }
                    }
                    
                    // Detailed debug: raw V0 values vs converted values
                    val rawColor = vecProps?.getAsJsonArray("_Color")
                    val rawShadeColor = vecProps?.getAsJsonArray("_ShadeColor")
                    val rawMainTex = texProps?.get("_MainTex")
                    val rawShadeTex = texProps?.get("_ShadeTexture")
                    Log.d(TAG, "MToon V0 RAW for '$name': _Color=$rawColor, _ShadeColor=$rawShadeColor, " +
                        "_MainTex=$rawMainTex, _ShadeTexture=$rawShadeTex")
                    Log.d(TAG, "MToon V0 CONVERTED for '$name': baseColor=${baseColorFactor?.contentToString()}, " +
                        "shade=${shadeColorFactor?.contentToString()}, " +
                        "baseTexIdx=$imageIndex, shadeTexIdx=$shadeMultiplyTextureIndex, " +
                        "shift=$shadingShiftFactor, toony=$shadingToonyFactor, gi=$giEqualizationFactor")
                }
            }
            
            materials.add(MaterialInfo(
                name = name,
                baseColorTextureIndex = imageIndex,
                baseColorFactor = baseColorFactor,
                shadeColorFactor = shadeColorFactor,
                shadingShiftFactor = shadingShiftFactor,
                shadingToonyFactor = shadingToonyFactor,
                shadeMultiplyTextureIndex = shadeMultiplyTextureIndex,
                shadingShiftTextureIndex = shadingShiftTextureIndex,
                shadingShiftTextureScale = shadingShiftTextureScale,
                emissiveFactor = emissiveFactor,
                emissiveTextureIndex = emissiveTextureIndex,
                parametricRimColorFactor = parametricRimColorFactor,
                parametricRimFresnelPowerFactor = parametricRimFresnelPowerFactor,
                parametricRimLiftFactor = parametricRimLiftFactor,
                rimLightingMixFactor = rimLightingMixFactor,
                rimMultiplyTextureIndex = rimMultiplyTextureIndex,
                matcapTextureIndex = matcapTextureIndex,
                matcapFactor = matcapFactor,
                giEqualizationFactor = giEqualizationFactor,
                isMToon = isMToon
            ))
            Log.d(TAG, "Material $i '$name': imageIndex=$imageIndex, factor=${baseColorFactor?.contentToString()}, isMToon=$isMToon")
        }
        
        return materials
    }
    
    /**
     * Resolve a glTF texture info object ({ index } into the textures array) to an image index.
     */
    private fun resolveImageIndex(texInfo: JsonObject?, texturesArray: JsonArray?): Int? {
        if (texInfo == null) return null
        val textureIndex = texInfo.get("index")?.asInt ?: return null
        if (texturesArray == null || textureIndex >= texturesArray.size()) return null
        return texturesArray[textureIndex].asJsonObject.get("source")?.asInt
    }

    /**
     * Convert gamma-encoded value to linear (three-vrm gammaEOTF).
     * VRM 0.x stores colors in gamma space; we need linear for rendering.
     */
    private fun gammaEOTF(v: Float): Float = Math.pow(v.toDouble(), 2.2).toFloat()
    
    /**
     * Parse glTF meshes to get material index for each primitive in order.
     */
    fun getPrimitiveMaterialMapping(json: JsonObject): List<PrimitiveInfo> {
        val primitives = mutableListOf<PrimitiveInfo>()
        val meshesArray = json.getAsJsonArray("meshes") ?: return emptyList()
        
        for (meshIndex in 0 until meshesArray.size()) {
            val meshObj = meshesArray[meshIndex].asJsonObject
            val primitivesArray = meshObj.getAsJsonArray("primitives") ?: continue
            
            for (primIndex in 0 until primitivesArray.size()) {
                val primObj = primitivesArray[primIndex].asJsonObject
                val materialIndex = primObj.get("material")?.asInt
                
                primitives.add(PrimitiveInfo(meshIndex, primIndex, materialIndex))
                Log.d(TAG, "Mesh $meshIndex primitive $primIndex -> material $materialIndex")
            }
        }
        
        Log.d(TAG, "Total primitives: ${primitives.size}")
        return primitives
    }
    
    /**
     * Get texture index from glTF texture object (resolves to image index)
     */
    fun getImageIndexForTexture(json: JsonObject, textureIndex: Int): Int? {
        val texturesArray = json.getAsJsonArray("textures") ?: return null
        if (textureIndex >= texturesArray.size()) return null
        
        val textureObj = texturesArray[textureIndex].asJsonObject
        return textureObj.get("source")?.asInt
    }
}
