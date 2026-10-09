package com.neethu.corelib.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.FilamentAsset
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Swaps the gltfio PBR materials of the loaded avatar for MToon material
 * instances (see [MToonMaterialFactory] for the shader source and
 * [GlbMToon] for the parameter model).
 *
 * The avatar asset must have been loaded from the [GlbMToon.patchGlb]-patched
 * GLB, so every MToon primitive is followed by a duplicate slot for the
 * outline pass. Surface slots are re-materialized per [GlbMToon.MToonParams];
 * duplicate slots get the outline material (or the draw-nothing material when
 * the primitive has no outline). Because the duplicates live on the SAME
 * renderable, skinning (setBones) and morph weights (setMorphWeights) that
 * gltfio's Animator pushes per renderable deform the outline in lockstep with
 * the surface — zero per-frame bookkeeping.
 *
 * Owns every GPU resource it creates (textures, material instances, the
 * material set); [destroy] must run before the asset is destroyed — see
 * [SoulLinkRenderer]'s model-load path. Filament's color grading must be on
 * LinearToneMapper while these materials are active (they output linear
 * color; the grading pass performs the sRGB encode).
 */
internal class MToonApplier(
    private val engine: Engine,
    private val asset: FilamentAsset,
    private val glbBytes: ByteArray,
    private val parseResult: GlbMToon.ParseResult,
    private val factory: MToonMaterialFactory,
) {

    // ── created GPU resources (all destroyed in [destroy]) ───────────────

    private val textures = ArrayList<Texture>()

    /**
     * Surface/outline instances — the only ones that declare the light params.
     * Hidden (draw-nothing) instances are tracked separately: setting an
     * undeclared uniform on a MaterialInstance is a filament precondition
     * abort ("uniform not found"), which killed the first on-device run.
     */
    private val instances = ArrayList<MaterialInstance>()
    private val hiddenInstances = ArrayList<MaterialInstance>()
    private val textureCache = HashMap<Pair<Int, Boolean>, Texture?>()
    private var whiteTexture: Texture? = null

    // ── key light (single directional light, three-vrm semantics) ────────

    /** Direction TOWARD the light (dot(normal, L) in the shader). */
    private var lightDirection = floatArrayOf(0f, 1f, 1f)
    private var lightColor = floatArrayOf(MTOON_PI, MTOON_PI, MTOON_PI)

    // ── apply ─────────────────────────────────────────────────────────────

    private val renderableManager get() = engine.renderableManager

    fun apply() {
        val rm = renderableManager
        var swappedPrims = 0
        var outlinedPrims = 0

        parseResult.meshLayouts.values.forEach { layout ->
            layout.nodeNames.forEach { nodeName ->
                val entity = asset.getFirstEntityByName(nodeName)
                if (entity == 0) return@forEach
                val instance = rm.getInstance(entity)
                if (instance == 0) return@forEach
                val primCount = rm.getPrimitiveCount(instance)
                if (primCount < layout.prims.size) {
                    Log.w(TAG, "Renderable '$nodeName' has $primCount prims, layout expects ${layout.prims.size}")
                    return@forEach
                }

                var renderablePriority = 0
                layout.prims.forEach { prim ->
                    val params = parseResult.materials[prim.materialIndex]
                    if (params == null) {
                        // non-MToon material in a patched mesh: keep the gltfio
                        // PBR surface, hide its outline copy
                        setOutlineHidden(instance, prim.outlineIndex)
                        return@forEach
                    }

                    val surfaceMi = createSurfaceInstance(params)
                    rm.setMaterialInstanceAt(instance, prim.surfaceIndex, surfaceMi)
                    if (!params.doubleSided) surfaceMi.setCullingMode(com.google.android.filament.Material.CullingMode.BACK)
                    swappedPrims++

                    val wantsOutline = params.outlineWidthMode != "none" && params.outlineWidthFactor > 0f
                    if (prim.outlineIndex >= 0) {
                        if (wantsOutline) {
                            val outlineMi = createOutlineInstance(params)
                            rm.setMaterialInstanceAt(instance, prim.outlineIndex, outlineMi)
                            outlinedPrims++
                        } else {
                            setOutlineHidden(instance, prim.outlineIndex)
                        }
                    }

                    // three renderOrder = (transparentWithZWrite ? 0 : 19) +
                    // renderQueueOffsetNumber, drawn lower-first; filament
                    // priority draws lower-first too (0..7)
                    if (params.alphaMode == GlbMToon.AlphaMode.BLEND) {
                        val renderOrder = (if (params.transparentWithZWrite) 0 else 19) +
                            params.renderQueueOffsetNumber
                        renderablePriority = renderOrder.coerceIn(0, 7)
                    }
                }
                if (renderablePriority > 0) rm.setPriority(instance, renderablePriority)
            }
        }

        Log.i(
            TAG,
            "MToon applied: ${parseResult.materials.size} materials, " +
                "$swappedPrims surface prims, $outlinedPrims outlined",
        )
        applyLightParams()
    }

    /**
     * Update the single directional light used by the MToon shading math.
     * [direction] points TOWARD the light, [color] is linear RGB at the
     * three-vrm `lightColor = color * intensity` scale (π ≈ neutral).
     */
    fun setKeyLight(direction: FloatArray, color: FloatArray) {
        val len = sqrt(
            direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2],
        )
        lightDirection = if (len > 1e-8f) {
            floatArrayOf(direction[0] / len, direction[1] / len, direction[2] / len)
        } else {
            lightDirection
        }
        lightColor = color.copyOf()
        applyLightParams()
    }

    private fun applyLightParams() {
        instances.forEach { mi ->
            mi.setParameter("lightDirection", lightDirection[0], lightDirection[1], lightDirection[2])
            mi.setParameter("lightColor", lightColor[0], lightColor[1], lightColor[2])
        }
    }

    // ── material instances ────────────────────────────────────────────────

    private fun createSurfaceInstance(p: GlbMToon.MToonParams): MaterialInstance {
        val mi = factory.surface(p.blendVariant).createInstance()
        instances.add(mi)
        setCommonParams(mi, p)
        mi.setParameter("hasMatcap", if (p.matcapTexture.index >= 0) 1f else 0f)
        mi.setParameter("matcapFactor", p.matcapFactor[0], p.matcapFactor[1], p.matcapFactor[2])
        setTexture(mi, "matcapMap", p.matcapTexture, srgb = true)
        setMat3(mi, "matcapUv", p.matcapTexture.uv)
        return mi
    }

    private fun createOutlineInstance(p: GlbMToon.MToonParams): MaterialInstance {
        val mi = factory.outline(p.blendVariant).createInstance()
        instances.add(mi)
        setCommonParams(mi, p)
        mi.setParameter("outlineColorFactor", p.outlineColorFactor[0], p.outlineColorFactor[1], p.outlineColorFactor[2])
        mi.setParameter("outlineLightingMixFactor", p.outlineLightingMixFactor)
        mi.setParameter("outlineWidthFactor", p.outlineWidthFactor)
        mi.setParameter(
            "outlineWidthMode",
            if (p.outlineWidthMode == "screenCoordinates") 2f else 1f,
        )
        mi.setParameter("hasOutlineWidthMap", if (p.outlineWidthMultiplyTexture.index >= 0) 1f else 0f)
        setMat3(mi, "outlineWidthUv", p.outlineWidthMultiplyTexture.uv)
        return mi
    }

    private fun setOutlineHidden(renderable: Int, primIndex: Int) {
        if (primIndex < 0) return
        val hiddenMi = factory.hidden.createInstance()
        hiddenInstances.add(hiddenMi)
        renderableManager.setMaterialInstanceAt(renderable, primIndex, hiddenMi)
    }

    private fun setCommonParams(mi: MaterialInstance, p: GlbMToon.MToonParams) {
        val b = p.baseColorFactor
        mi.setParameter("baseColorFactor", b[0], b[1], b[2], b[3])
        setTexture(mi, "baseColorMap", p.baseColorTexture, srgb = true)
        setMat3(mi, "baseColorUv", p.baseColorTexture.uv)
        mi.setParameter("shadeColorFactor", p.shadeColorFactor[0], p.shadeColorFactor[1], p.shadeColorFactor[2])
        setTexture(mi, "shadeMultiplyMap", p.shadeMultiplyTexture, srgb = true)
        setMat3(mi, "shadeUv", p.shadeMultiplyTexture.uv)
        mi.setParameter("shadingShiftFactor", p.shadingShiftFactor)
        mi.setParameter("shadingToonyFactor", p.shadingToonyFactor)
        mi.setParameter("hasNormalMap", if (p.normalTexture.index >= 0) 1f else 0f)
        mi.setParameter("normalScale", p.normalScale, p.normalScale)
        setTexture(mi, "normalMap", p.normalTexture, srgb = false)
        setMat3(mi, "normalUv", p.normalTexture.uv)
        val e = p.emissiveFactor
        mi.setParameter("emissiveFactor", e[0], e[1], e[2])
        mi.setParameter("emissiveIntensity", p.emissiveIntensity)
        setTexture(mi, "emissiveMap", p.emissiveTexture, srgb = true)
        setMat3(mi, "emissiveUv", p.emissiveTexture.uv)
        val r = p.rimColorFactor
        mi.setParameter("rimColorFactor", r[0], r[1], r[2])
        mi.setParameter("rimLightingMixFactor", p.rimLightingMixFactor)
        mi.setParameter("rimFresnelPowerFactor", p.rimFresnelPowerFactor)
        mi.setParameter("rimLiftFactor", p.rimLiftFactor)
        mi.setParameter("alphaCutoff", if (p.alphaMode == GlbMToon.AlphaMode.MASK) p.alphaCutoff else 0f)
        // re-set the per-map samplers: shade falls back to the base color
        // sampler like the desktop viewer; setTexture above already bound the
        // textures, this only refines sampler state per source texture
        mi.setParameter("shadeMultiplyMap", textureFor(p.shadeMultiplyTexture.index, srgb = true)
            ?: whiteTexture(), samplerFor(p.shadeMultiplyTexture.index, p.baseColorTexture.index))
        mi.setParameter("normalMap", textureFor(p.normalTexture.index, srgb = false)
            ?: whiteTexture(), samplerFor(p.normalTexture.index))
        mi.setParameter("emissiveMap", textureFor(p.emissiveTexture.index, srgb = true)
            ?: whiteTexture(), samplerFor(p.emissiveTexture.index))
    }

    private fun setMat3(mi: MaterialInstance, name: String, uv: GlbMToon.UvTransform) {
        mi.setParameter(name, MaterialInstance.FloatElement.MAT3, uv.toMat3ColumnMajor(), 0, 1)
    }

    private fun setTexture(
        mi: MaterialInstance,
        param: String,
        slot: GlbMToon.TextureSlot,
        srgb: Boolean,
        fallbackSamplerOf: GlbMToon.TextureSlot? = null,
    ) {
        mi.setParameter(param, textureFor(slot.index, srgb), samplerFor(slot.index, fallbackSamplerOf?.index))
    }

    private fun textureFor(imageIndex: Int, srgb: Boolean): Texture =
        if (imageIndex >= 0) loadTexture(imageIndex, srgb) else whiteTexture()

    /** glTF JSON, parsed once (material/texture/image metadata lookups). */
    private val gltfJson: JsonObject? by lazy { SoulLinkRendererUtils.parseGlbJson(glbBytes) }

    /** GLB BIN chunk, sliced once. */
    private val bin: ByteBuffer? by lazy {
        gltfJson?.let { parseGlbBin(glbBytes) }
    }

    private fun textureSamplerIndexOf(textureIndex: Int): Int? {
        if (textureIndex < 0) return null
        val textures = gltfJson?.getAsJsonArray("textures") ?: return null
        if (textureIndex >= textures.size()) return null
        return textures[textureIndex].asJsonObject
            .get("sampler")?.takeIf { it.isJsonPrimitive }?.asInt
    }

    // ── textures ──────────────────────────────────────────────────────────

    private fun whiteTexture(): Texture {
        whiteTexture?.let { return it }
        val tex = Texture.Builder()
            .width(1).height(1).levels(1)
            .format(Texture.InternalFormat.RGBA8)
            .build(engine)
        val px = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
            .put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte())
        px.rewind()
        tex.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(px, Texture.Format.RGBA, Texture.Type.UBYTE),
        )
        whiteTexture = tex
        textures.add(tex)
        return tex
    }

    /** glTF image index → uploaded [Texture]; sRGB choice is part of the key. */
    private fun loadTexture(imageIndex: Int, srgb: Boolean): Texture {
        val key = imageIndex to srgb
        textureCache[key]?.let { return it }

        val tex = decodeAndUpload(imageIndex, srgb)
        textureCache[key] = tex
        return tex
    }

    private fun decodeAndUpload(imageIndex: Int, srgb: Boolean): Texture {
        val bytes = imageBytes(imageIndex)
        if (bytes == null) {
            Log.w(TAG, "MToon image #$imageIndex not found in GLB; using white fallback")
            return whiteTexture()
        }
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: run {
                Log.w(TAG, "MToon image #$imageIndex decode failed (KTX2/unsupported?); white fallback")
                return whiteTexture()
            }
        try {
            val w = bitmap.width
            val h = bitmap.height
            val levels = max(1.0, floor(ln(max(w, h).toDouble()) / ln(2.0)) + 1.0).toInt()
            val tex = Texture.Builder()
                .width(w).height(h).levels(levels)
                .format(if (srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
                .usage(
                    Texture.Usage.SAMPLEABLE or Texture.Usage.GEN_MIPMAPPABLE or Texture.Usage.UPLOADABLE,
                )
                .build(engine)
            val px = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.LITTLE_ENDIAN)
            bitmap.copyPixelsToBuffer(px)
            px.rewind()
            tex.setImage(
                engine, 0,
                Texture.PixelBufferDescriptor(px, Texture.Format.RGBA, Texture.Type.UBYTE),
            )
            tex.generateMipmaps(engine)
            // Upload commands execute on the driver thread at the next
            // beginFrame; without a flush here every pixel buffer stays alive
            // until then and 20+ 2K textures OOM the 256 MB java heap (measured
            // on device). Draining per texture keeps at most one buffer pending.
            engine.flushAndWait()
            textures.add(tex)
            return tex
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Raw encoded image bytes (PNG/JPEG) for `images[index]` from the GLB BIN
     * chunk. Only buffer-view images are supported (GLB always stores them so).
     */
    private fun imageBytes(imageIndex: Int): ByteArray? {
        val json = gltfJson ?: return null
        val images = json.getAsJsonArray("images") ?: return null
        if (imageIndex >= images.size()) return null
        val image = images[imageIndex].asJsonObject
        val bvIndex = image.get("bufferView")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null

        val bin = bin ?: return null
        val bufferViews = json.getAsJsonArray("bufferViews") ?: return null
        if (bvIndex >= bufferViews.size()) return null
        val bv = bufferViews[bvIndex].asJsonObject
        val offset = bv.get("byteOffset")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val length = bv.get("byteLength")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        if (offset + length > bin.capacity()) return null

        val out = ByteArray(length)
        bin.position(offset)
        bin.get(out)
        return out
    }

    private fun parseGlbBin(bytes: ByteArray): ByteBuffer? {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) return null
        buf.int; buf.int; buf.int // magic, version, length
        while (buf.remaining() >= 8) {
            val chunkLen = buf.int
            val chunkType = buf.int
            if (chunkType == 0x004E4942) { // BIN
                val slice = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
                slice.limit(chunkLen)
                return slice
            }
            buf.position(buf.position() + chunkLen)
        }
        return null
    }

    // ── samplers ──────────────────────────────────────────────────────────

    /**
     * glTF sampler → filament sampler. Defaults match the desktop viewer:
     * LINEAR_MIPMAP_LINEAR / LINEAR / REPEAT.
     */
    private fun samplerFor(textureIndex: Int, fallbackTextureIndex: Int? = null): TextureSampler {
        val samplerIndex = textureSamplerIndexOf(textureIndex)
            ?: (fallbackTextureIndex?.let { textureSamplerIndexOf(it) })
        val gltfSampler = samplerIndex?.let { idx ->
            gltfJson?.getAsJsonArray("samplers")?.get(idx)?.takeIf { it.isJsonObject }?.asJsonObject
        }
        var minFilter = TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR
        var magFilter = TextureSampler.MagFilter.LINEAR
        var wrap = TextureSampler.WrapMode.REPEAT
        if (gltfSampler != null) {
            when (gltfSampler.get("minFilter")?.takeIf { it.isJsonPrimitive }?.asInt) {
                9728 -> minFilter = TextureSampler.MinFilter.NEAREST
                9729 -> minFilter = TextureSampler.MinFilter.LINEAR
                9984 -> minFilter = TextureSampler.MinFilter.NEAREST_MIPMAP_NEAREST
                9985 -> minFilter = TextureSampler.MinFilter.LINEAR_MIPMAP_NEAREST
                9986 -> minFilter = TextureSampler.MinFilter.NEAREST_MIPMAP_LINEAR
                9987, null -> minFilter = TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR
            }
            if (gltfSampler.get("magFilter")?.takeIf { it.isJsonPrimitive }?.asInt == 9728) {
                magFilter = TextureSampler.MagFilter.NEAREST
            }
            wrap = when (gltfSampler.get("wrapS")?.takeIf { it.isJsonPrimitive }?.asInt) {
                33071 -> TextureSampler.WrapMode.CLAMP_TO_EDGE
                33648 -> TextureSampler.WrapMode.MIRRORED_REPEAT
                else -> TextureSampler.WrapMode.REPEAT
            }
        }
        return TextureSampler(minFilter, magFilter, wrap, wrap, wrap)
    }

    // ── teardown ──────────────────────────────────────────────────────────

    /** Destroy every GPU resource this applier created. Idempotent. */
    fun destroy() {
        (instances + hiddenInstances).forEach { engine.destroyMaterialInstance(it) }
        instances.clear()
        hiddenInstances.clear()
        textures.forEach { engine.destroyTexture(it) }
        textures.clear()
        textureCache.clear()
        whiteTexture = null
    }

    companion object {
        private const val TAG = "MToonApplier"
        private const val MTOON_PI = 3.14159265f

        /**
         * Default key light for the MToon math, expressed the way three-vrm's
         * directional light feeds mtoon.frag: [lightColor] = color · intensity
         * with the neutral intensity π (so `lightColor/π` cancels in the
         * shader and `mix(shade, diffuse, shading)` is exact). Direction comes
         * from the studio key spot of [SoulLinkRenderer].
         */
        fun defaultLightColor(keyColor: FloatArray): FloatArray =
            floatArrayOf(keyColor[0] * MTOON_PI, keyColor[1] * MTOON_PI, keyColor[2] * MTOON_PI)
    }
}

/**
 * GLB JSON helpers shared with [MToonApplier]; kept free of android imports
 * so the parsing stays JVM-testable.
 */
internal object SoulLinkRendererUtils {
    fun parseGlbJson(glbBytes: ByteArray): com.google.gson.JsonObject? {
        val buf = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) return null
        buf.int; buf.int; buf.int // magic, version, length
        if (buf.remaining() < 8) return null
        val chunkLen = buf.int
        val chunkType = buf.int
        if (chunkType != 0x4E4F534A) return null
        val jsonBytes = ByteArray(chunkLen)
        buf.get(jsonBytes)
        return com.google.gson.Gson()
            .fromJson(String(jsonBytes, Charsets.UTF_8), com.google.gson.JsonObject::class.java)
    }
}
