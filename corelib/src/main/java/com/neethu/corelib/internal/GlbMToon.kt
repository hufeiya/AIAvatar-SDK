package com.neethu.corelib.internal

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure-JSON MToon layer for VRM models — material parameter extraction
 * (VRM 0.x `VRM/MToon` + Unlit shaders via three-vrm's v0compat mapping, and
 * VRM 1.0 `VRMC_materials_mtoon`) plus the GLB primitive duplication that
 * gives every MToon primitive a second slot for the inverted-hull outline
 * pass. No android imports: the parsing is unit-tested on the JVM.
 *
 * Math and v0compat semantics ported from the desktop parity viewer
 * (/home/neethu/projects/mtoon, see its README), which in turn ports
 * three-vrm's VRMMaterialsV0CompatPlugin + MToonMaterial.
 *
 * Pipeline position (see [SoulLinkRenderer.loadModelBytes]): AFTER
 * [GlbMorphPatcher]/[GlbBoneCuller] (they rewrite joints/morph accessors —
 * duplicating primitives first would make the bone culler process shared
 * accessors twice), BEFORE `loadModelGlb`. The BIN chunk is never touched:
 * duplicated primitives re-reference the same accessors/bufferViews.
 */
internal object GlbMToon {

    // ── parameter model (defaults = three-vrm MToonMaterial defaults) ────

    /** three setUvTransform(tx, ty, sx, sy, rot, cx=0, cy=0) semantics. */
    data class UvTransform(
        val tx: Float = 0f,
        val ty: Float = 0f,
        val sx: Float = 1f,
        val sy: Float = 1f,
        val rot: Float = 0f,
    ) {
        val isIdentity: Boolean
            get() = tx == 0f && ty == 0f && sx == 1f && sy == 1f && rot == 0f

        /**
         * Column-major mat3 for `setParameter(FloatElement.MAT3)`, matching
         * the shader expression `(uvMat * vec3(uv, 1)).xy`:
         * rows [sx·c, −sy·s, tx; sx·s, sy·c, ty; 0, 0, 1].
         */
        fun toMat3ColumnMajor(): FloatArray {
            val c = cos(rot)
            val s = sin(rot)
            return floatArrayOf(
                sx * c, sx * s, 0f,     // column 0 (m00, m10, m20)
                -sy * s, sy * c, 0f,    // column 1 (m01, m11, m21)
                tx, ty, 1f,             // column 2 (m02, m12, m22)
            )
        }
    }

    data class TextureSlot(val index: Int = -1, val uv: UvTransform = UvTransform())

    enum class AlphaMode { OPAQUE, MASK, BLEND }

    data class MToonParams(
        val name: String,
        val baseColorFactor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
        val baseColorTexture: TextureSlot = TextureSlot(),
        val shadeColorFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
        val shadeMultiplyTexture: TextureSlot = TextureSlot(),
        val shadingShiftFactor: Float = 0f,
        val shadingToonyFactor: Float = 0.9f,
        val normalTexture: TextureSlot = TextureSlot(),
        val normalScale: Float = 1f,
        val emissiveFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
        val emissiveIntensity: Float = 1f,
        val emissiveTexture: TextureSlot = TextureSlot(),
        val rimColorFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
        val rimLightingMixFactor: Float = 1f,
        val rimFresnelPowerFactor: Float = 5f,
        val rimLiftFactor: Float = 0f,
        val matcapFactor: FloatArray = floatArrayOf(1f, 1f, 1f),
        val matcapTexture: TextureSlot = TextureSlot(), // matcap ignores uv transforms (spec)
        val outlineWidthMultiplyTexture: TextureSlot = TextureSlot(),
        val outlineColorFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
        val outlineLightingMixFactor: Float = 1f,
        val outlineWidthMode: String = "none", // "none" | "worldCoordinates" | "screenCoordinates"
        val outlineWidthFactor: Float = 0f,
        val doubleSided: Boolean = false,
        val alphaMode: AlphaMode = AlphaMode.OPAQUE,
        val alphaCutoff: Float = 0.5f,
        val transparentWithZWrite: Boolean = false,
        val renderQueueOffsetNumber: Int = 0,
    ) {
        /** 0 opaque, 1 transparent, 2 transparentWithZWrite — material variant selector. */
        val blendVariant: Int
            get() = when {
                alphaMode != AlphaMode.BLEND -> 0
                transparentWithZWrite -> 2
                else -> 1
            }
    }

    /** One primitive of a patched mesh: surface slot + optional outline copy slot. */
    data class PrimLayout(
        val materialIndex: Int,
        val surfaceIndex: Int,
        val outlineIndex: Int, // -1 = not duplicated (non-MToon material)
    )

    /** Patch layout of one mesh: which nodes reference it and the per-prim slot map. */
    data class MeshLayout(val nodeNames: List<String>, val prims: List<PrimLayout>)

    data class ParseResult(
        val isVrm0: Boolean,
        /** materialIndex → params for every MToon-converted material. */
        val materials: Map<Int, MToonParams>,
        /** meshIndex → layout for the meshes the patcher duplicated. */
        val meshLayouts: Map<Int, MeshLayout>,
    )

    // ── entry points ─────────────────────────────────────────────────────

    /**
     * Extract MToon parameters for every convertible material of the glTF
     * JSON (VRM0: `VRM.materialProperties` shaders; VRM1:
     * `VRMC_materials_mtoon`). Returns an empty map for non-VRM models.
     */
    fun parseMaterials(json: JsonObject): Map<Int, MToonParams> {
        val v0 = parseV0MaterialProperties(json)
        if (v0 != null) {
            val mapTransparent = LinkedHashMap<Int, Int>()
            val mapZWrite = LinkedHashMap<Int, Int>()
            populateRenderQueueMaps(v0, mapTransparent, mapZWrite)
            val out = LinkedHashMap<Int, MToonParams>()
            v0.forEachIndexed { index, props ->
                val shader = props.shader
                if (shader == "VRM/MToon" || shader.startsWith("VRM/Unlit")) {
                    out[index] = convertV0ToMtoon(props, mapTransparent, mapZWrite)
                }
            }
            return out
        }
        return parseV1Materials(json)
    }

    /**
     * Duplicate the MToon primitives of every mesh that is referenced by a
     * **named** node, so [MToonApplier] can find the renderable via
     * `getFirstEntityByName`. Returns the rebuilt GLB (JSON chunk rewritten,
     * BIN chunk byte-identical) and the per-mesh layouts. Returns the input
     * untouched when there is nothing to duplicate.
     */
    fun patchGlb(glb: ByteBuffer, json: JsonObject, materials: Map<Int, MToonParams>): Pair<ByteBuffer, Map<Int, MeshLayout>> {
        val nodes = json.getAsJsonArray("nodes")
        val meshes = json.getAsJsonArray("meshes")
            ?: return glb.also { it.rewind() } to emptyMap()

        // meshIndex → names of referencing nodes
        val namesByMesh = HashMap<Int, MutableList<String>>()
        nodes?.forEach { nodeEl ->
            val node = nodeEl.asJsonObject
            val mesh = node.get("mesh")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@forEach
            val name = node.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            namesByMesh.getOrPut(mesh) { mutableListOf() }.add(name)
        }

        val layouts = HashMap<Int, MeshLayout>()
        meshes.forEachIndexed { meshIdx, meshEl ->
            val mesh = meshEl.asJsonObject
            val prims = mesh.getAsJsonArray("primitives") ?: return@forEachIndexed
            val nodeNames = namesByMesh[meshIdx]?.distinct() ?: return@forEachIndexed
            if (nodeNames.isEmpty()) return@forEachIndexed

            val newPrims = JsonArray()
            val layout = ArrayList<PrimLayout>(prims.size())
            prims.forEachIndexed { _, primEl ->
                val prim = primEl.asJsonObject
                // surface slot = index in the REBUILT array (earlier prims may
                // have shifted it); the outline copy lands right after
                val surfaceIndex = newPrims.size()
                newPrims.add(prim)
                val matIdx = prim.get("material")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
                if (materials.containsKey(matIdx)) {
                    newPrims.add(prim.deepCopy())
                    layout.add(PrimLayout(matIdx, surfaceIndex, surfaceIndex + 1))
                } else {
                    layout.add(PrimLayout(matIdx, surfaceIndex, -1))
                }
            }
            if (layout.any { it.outlineIndex >= 0 }) {
                mesh.add("primitives", newPrims)
                layouts[meshIdx] = MeshLayout(nodeNames, layout)
            }
        }
        if (layouts.isEmpty()) return glb.also { it.rewind() } to emptyMap()

        return rebuildGlb(glb, json) to layouts
    }

    // ── GLB structure (same chunk layout as GlbMorphPatcher) ─────────────

    private const val GLB_MAGIC = 0x46546C67
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    /** First (JSON) chunk of a GLB as a parsed object; null when not a GLB. */
    fun readJson(glb: ByteBuffer): JsonObject? {
        glb.order(ByteOrder.LITTLE_ENDIAN); glb.rewind()
        if (glb.remaining() < 12) return null
        if (glb.int != GLB_MAGIC) return null
        glb.int // version
        glb.int // total length
        if (glb.remaining() < 8) return null
        val chunkLen = glb.int
        if (glb.int != CHUNK_JSON) return null
        val jsonBytes = ByteArray(chunkLen)
        glb.get(jsonBytes)
        return com.google.gson.Gson().fromJson(
            String(jsonBytes, Charsets.UTF_8), JsonObject::class.java,
        )
    }

    private fun rebuildGlb(src: ByteBuffer, json: JsonObject): ByteBuffer {
        src.order(ByteOrder.LITTLE_ENDIAN); src.rewind()
        src.int; src.int; src.int // magic, version, total length
        var binOff = -1; var binLen = -1
        while (src.hasRemaining()) {
            if (src.remaining() < 8) break
            val cLen = src.int; val cType = src.int
            if (cType == CHUNK_BIN) { binOff = src.position(); binLen = cLen; break }
            src.position(src.position() + cLen)
        }

        // Gson round-trip, same as GlbMorphPatcher (compact, HTML-safe)
        val newJson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
            .toJson(json).toByteArray(Charsets.UTF_8)
        val jPad = (4 - newJson.size % 4) % 4
        val hasBin = binOff >= 0
        val total = 12 + 8 + newJson.size + jPad + if (hasBin) 8 + binLen else 0

        val out = ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(GLB_MAGIC); out.putInt(GLB_VERSION); out.putInt(total)
        out.putInt(newJson.size + jPad); out.putInt(CHUNK_JSON)
        out.put(newJson); repeat(jPad) { out.put(0x20.toByte()) }
        if (hasBin) {
            out.putInt(binLen); out.putInt(CHUNK_BIN)
            src.position(binOff)
            val slice = src.slice().order(ByteOrder.LITTLE_ENDIAN); slice.limit(binLen)
            out.put(slice)
        }
        out.rewind()
        return out
    }

    // ── VRM 0.x (three-vrm VRMMaterialsV0CompatPlugin mapping) ───────────

    private class V0Props(
        val shader: String,
        val root: JsonObject,
    )

    /** Returns the per-material `VRM.materialProperties` list, or null for non-VRM0 files. */
    private fun parseV0MaterialProperties(json: JsonObject): List<V0Props>? {
        val vrm = json.getAsJsonObject("extensions")?.getAsJsonObject("VRM") ?: return null
        val matProps = vrm.getAsJsonArray("materialProperties") ?: return null
        return matProps.map { el ->
            val obj = el.asJsonObject
            V0Props(
                shader = obj.get("shader")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                root = obj,
            )
        }
    }

    /** v0 renderQueue → VRM1 renderQueueOffsetNumber (three _populateRenderQueueMap). */
    private fun populateRenderQueueMaps(
        propsList: List<V0Props>,
        mapTransparent: MutableMap<Int, Int>,
        mapZWrite: MutableMap<Int, Int>,
    ) {
        val queuesTransparent = ArrayList<Int>()
        val queuesZWrite = ArrayList<Int>()
        for (props in propsList) {
            val isTransparentZWrite = props.shader == "VRM/UnlitTransparentZWrite"
            val isTransparent = v0keyword(props, "_ALPHABLEND_ON") ||
                props.shader == "VRM/UnlitTransparent" || isTransparentZWrite
            val enabledZWrite = v0float(props, "_ZWrite", 0.0) == 1f || isTransparentZWrite
            if (!isTransparent) continue
            val q = props.root.get("renderQueue")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            (if (enabledZWrite) queuesZWrite else queuesTransparent).add(q)
        }
        queuesTransparent.distinct().sorted().forEachIndexed { i, q ->
            mapTransparent[q] = (i - queuesTransparent.size + 1).coerceIn(-9, 0)
        }
        queuesZWrite.distinct().sorted().forEachIndexed { i, q ->
            mapZWrite[q] = i.coerceIn(0, 9)
        }
    }

    private fun v0vec(props: V0Props, key: String): List<Float> {
        val vp = props.root.getAsJsonObject("vectorProperties") ?: return emptyList()
        val arr = vp.getAsJsonArray(key) ?: return emptyList()
        return arr.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asFloat }
    }

    private fun v0float(props: V0Props, key: String, def: Double): Float =
        props.root.getAsJsonObject("floatProperties")?.get(key)?.takeIf { it.isJsonPrimitive }?.asDouble?.toFloat()
            ?: def.toFloat()

    private fun v0tex(props: V0Props, key: String): Int =
        props.root.getAsJsonObject("textureProperties")?.get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: -1

    private fun v0keyword(props: V0Props, key: String): Boolean {
        val v = props.root.getAsJsonObject("keywordMap")?.get(key) ?: return false
        return when {
            v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
            v.isJsonPrimitive -> v.asString == "true" || v.asInt != 0
            else -> false
        }
    }

    /** v0 `_MainTex_ST` [offsetX, offsetY, scaleX, scaleY] with flipped offsetY. */
    private fun v0TextureTransform(props: V0Props): UvTransform {
        val v = v0vec(props, "_MainTex")
        if (v.size < 4) return UvTransform()
        return UvTransform(
            tx = v[0],
            ty = 1f - v[3] - v[1],
            sx = v[2],
            sy = v[3],
        )
    }

    /** VRM 0.x stores material colors as sRGB; three-vrm v0compat applies pow(x, 2.2). */
    private fun gammaEOTF(v: Float): Float = v.toDouble().pow(2.2).toFloat()

    private fun convertV0ToMtoon(
        props: V0Props,
        mapTransparent: Map<Int, Int>,
        mapZWrite: Map<Int, Int>,
    ): MToonParams {
        val shader = props.shader
        val unlitTransparentZWrite = shader == "VRM/UnlitTransparentZWrite"
        val unlitTransparent = shader == "VRM/UnlitTransparent" || unlitTransparentZWrite
        val isMToon = shader == "VRM/MToon"
        val isUnlit = shader.startsWith("VRM/Unlit")

        val isTransparent = if (isMToon) v0keyword(props, "_ALPHABLEND_ON") else unlitTransparent
        val enabledZWrite = v0float(props, "_ZWrite", 0.0) == 1f || unlitTransparentZWrite
        val transparentWithZWrite = enabledZWrite && isTransparent
        val isCutoff = if (isMToon) v0keyword(props, "_ALPHATEST_ON") else shader == "VRM/UnlitCutout"
        val alphaMode = when {
            isTransparent -> AlphaMode.BLEND
            isCutoff -> AlphaMode.MASK
            else -> AlphaMode.OPAQUE
        }
        val alphaCutoff = if (isCutoff) v0float(props, "_Cutoff", 0.5) else 0.5f

        // render queue offset (transparent materials only)
        var renderQueueOffset = 0
        if (isTransparent) {
            val q = props.root.get("renderQueue")?.takeIf { it.isJsonPrimitive }?.asInt
            if (q != null) {
                renderQueueOffset = (if (enabledZWrite) mapZWrite else mapTransparent)[q] ?: 0
            }
        }

        // shared uv transform for every map except matcap
        val uv = v0TextureTransform(props)

        // base color
        var baseColorFactor = floatArrayOf(1f, 1f, 1f, 1f)
        v0vec(props, "_Color").takeIf { it.size >= 4 }?.let { c ->
            baseColorFactor = floatArrayOf(gammaEOTF(c[0]), gammaEOTF(c[1]), gammaEOTF(c[2]), c[3])
        }
        var baseColorTexture = TextureSlot()
        v0tex(props, "_MainTex").takeIf { it >= 0 }?.let { baseColorTexture = TextureSlot(it, uv) }

        // normal
        var normalTexture = TextureSlot()
        var normalScale = 1f
        v0tex(props, "_BumpMap").takeIf { it >= 0 }?.let {
            normalTexture = TextureSlot(it, uv)
            normalScale = v0float(props, "_BumpScale", 1.0)
        }

        // emissive
        var emissiveFactor = floatArrayOf(0f, 0f, 0f)
        v0vec(props, "_EmissionColor").takeIf { it.size >= 3 }?.let { c ->
            emissiveFactor = floatArrayOf(gammaEOTF(c[0]), gammaEOTF(c[1]), gammaEOTF(c[2]))
        }
        var emissiveTexture = TextureSlot()
        v0tex(props, "_EmissionMap").takeIf { it >= 0 }?.let { emissiveTexture = TextureSlot(it, uv) }

        // v0compat converts unlit to mtoon with shade = base color
        if (isUnlit) {
            return MToonParams(
                name = props.root.get("name")?.asString ?: "",
                baseColorFactor = baseColorFactor,
                baseColorTexture = baseColorTexture,
                shadeColorFactor = baseColorFactor.copyOfRange(0, 3),
                shadeMultiplyTexture = baseColorTexture,
                alphaMode = alphaMode,
                alphaCutoff = alphaCutoff,
                transparentWithZWrite = transparentWithZWrite,
                doubleSided = v0float(props, "_CullMode", 2.0) == 0f,
                renderQueueOffsetNumber = renderQueueOffset,
            )
        }

        // shade
        var shadeColorFactor = floatArrayOf(
            gammaEOTF(0.97f), gammaEOTF(0.81f), gammaEOTF(0.86f),
        )
        v0vec(props, "_ShadeColor").takeIf { it.size >= 3 }?.let { c ->
            shadeColorFactor = floatArrayOf(gammaEOTF(c[0]), gammaEOTF(c[1]), gammaEOTF(c[2]))
        }
        var shadeMultiplyTexture = TextureSlot()
        v0tex(props, "_ShadeTexture").takeIf { it >= 0 }?.let {
            shadeMultiplyTexture = TextureSlot(it, uv)
        }

        // v0 shade shift / toony conversion (three-vrm _ShadeShift/_ShadeToony)
        val shift0 = v0float(props, "_ShadeShift", 0.0)
        val toony0 = v0float(props, "_ShadeToony", 0.9)
        val toony = toony0 + (1f - toony0) * (0.5f + 0.5f * shift0)
        val shadingToonyFactor = toony
        val shadingShiftFactor = -shift0 - (1f - toony)

        // matcap (no uv transform)
        var matcapTexture = TextureSlot()
        var matcapFactor = floatArrayOf(0f, 0f, 0f)
        v0tex(props, "_SphereAdd").takeIf { it >= 0 }?.let {
            matcapTexture = TextureSlot(it)
            matcapFactor = floatArrayOf(1f, 1f, 1f)
        }

        // parametric rim
        var rimColorFactor = floatArrayOf(0f, 0f, 0f)
        v0vec(props, "_RimColor").takeIf { it.size >= 3 }?.let { c ->
            rimColorFactor = floatArrayOf(gammaEOTF(c[0]), gammaEOTF(c[1]), gammaEOTF(c[2]))
        }
        val rimLightingMixFactor = v0float(props, "_RimLightingMix", 0.0)
        val rimFresnelPowerFactor = v0float(props, "_RimFresnelPower", 1.0)
        val rimLiftFactor = v0float(props, "_RimLift", 0.0)

        // outline
        val outlineWidthMode = when (v0float(props, "_OutlineWidthMode", 0.0).toInt()) {
            1 -> "worldCoordinates"
            2 -> "screenCoordinates"
            else -> "none"
        }
        // cm → m
        val outlineWidthFactor = 0.01f * v0float(props, "_OutlineWidth", 0.0)
        var outlineWidthMultiplyTexture = TextureSlot()
        v0tex(props, "_OutlineWidthTexture").takeIf { it >= 0 }?.let {
            outlineWidthMultiplyTexture = TextureSlot(it, uv)
        }
        var outlineColorFactor = floatArrayOf(0f, 0f, 0f)
        v0vec(props, "_OutlineColor").takeIf { it.size >= 3 }?.let { c ->
            outlineColorFactor = floatArrayOf(gammaEOTF(c[0]), gammaEOTF(c[1]), gammaEOTF(c[2]))
        }
        val colorMode = v0float(props, "_OutlineColorMode", 0.0).toInt()
        val outlineLightingMixFactor =
            if (colorMode == 1) v0float(props, "_OutlineLightingMix", 1.0) else 0f

        return MToonParams(
            name = props.root.get("name")?.asString ?: "",
            baseColorFactor = baseColorFactor,
            baseColorTexture = baseColorTexture,
            shadeColorFactor = shadeColorFactor,
            shadeMultiplyTexture = shadeMultiplyTexture,
            shadingShiftFactor = shadingShiftFactor,
            shadingToonyFactor = shadingToonyFactor,
            normalTexture = normalTexture,
            normalScale = normalScale,
            emissiveFactor = emissiveFactor,
            emissiveTexture = emissiveTexture,
            rimColorFactor = rimColorFactor,
            rimLightingMixFactor = rimLightingMixFactor,
            rimFresnelPowerFactor = rimFresnelPowerFactor,
            rimLiftFactor = rimLiftFactor,
            matcapFactor = matcapFactor,
            matcapTexture = matcapTexture,
            outlineWidthMultiplyTexture = outlineWidthMultiplyTexture,
            outlineColorFactor = outlineColorFactor,
            outlineLightingMixFactor = outlineLightingMixFactor,
            outlineWidthMode = outlineWidthMode,
            outlineWidthFactor = outlineWidthFactor,
            doubleSided = v0float(props, "_CullMode", 2.0) == 0f,
            alphaMode = alphaMode,
            alphaCutoff = alphaCutoff,
            transparentWithZWrite = transparentWithZWrite,
            renderQueueOffsetNumber = renderQueueOffset,
        )
    }

    // ── VRM 1.0 (VRMC_materials_mtoon) ───────────────────────────────────

    /** three GLTFLoader converts glTF color factors from sRGB to linear. */
    private fun srgb2lin(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun col3(el: JsonObject?, key: String, def: FloatArray): FloatArray {
        val arr = el?.getAsJsonArray(key) ?: return def
        if (arr.size() < 3) return def
        return floatArrayOf(arr[0].asFloat, arr[1].asFloat, arr[2].asFloat)
    }

    private fun num(el: JsonObject?, key: String, def: Float): Float =
        el?.get(key)?.takeIf { it.isJsonPrimitive }?.asFloat ?: def

    /** `{ "index": N, "extensions": { "KHR_texture_transform": {…} } }` slot. */
    private fun texSlot(el: JsonObject?, key: String): TextureSlot {
        val t = el?.getAsJsonObject(key) ?: return TextureSlot()
        val idx = t.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: return TextureSlot()
        val tt = t.getAsJsonObject("extensions")?.getAsJsonObject("KHR_texture_transform")
        var uv = UvTransform()
        if (tt != null) {
            fun arr2(name: String, i: Int, def: Float): Float =
                tt.getAsJsonArray(name)?.takeIf { it.size() > i }?.get(i)?.takeIf { it.isJsonPrimitive }?.asFloat ?: def
            uv = UvTransform(
                tx = arr2("offset", 0, 0f),
                ty = arr2("offset", 1, 0f),
                sx = arr2("scale", 0, 1f),
                sy = arr2("scale", 1, 1f),
                rot = tt.get("rotation")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f,
            )
        }
        return TextureSlot(idx, uv)
    }

    private fun parseV1Materials(json: JsonObject): Map<Int, MToonParams> {
        val materials = json.getAsJsonArray("materials") ?: return emptyMap()
        val out = LinkedHashMap<Int, MToonParams>()
        materials.forEachIndexed { index, matEl ->
            val mat = matEl.asJsonObject
            val mtoon = mat.getAsJsonObject("extensions")
                ?.getAsJsonObject("VRMC_materials_mtoon") ?: return@forEachIndexed

            val pbr = mat.getAsJsonObject("pbrMetallicRoughness")
            var baseColorFactor = floatArrayOf(1f, 1f, 1f, 1f)
            pbr?.getAsJsonArray("baseColorFactor")?.takeIf { it.size() >= 4 }?.let { c ->
                baseColorFactor = floatArrayOf(srgb2lin(c[0].asFloat), srgb2lin(c[1].asFloat), srgb2lin(c[2].asFloat), c[3].asFloat)
            }
            var baseColorTexture = TextureSlot()
            texSlot(pbr, "baseColorTexture").takeIf { it.index >= 0 }?.let { baseColorTexture = it }

            var normalTexture = TextureSlot()
            var normalScale = 1f
            mat.getAsJsonObject("normalTexture")?.let { nt ->
                val slot = texSlot(mat, "normalTexture")
                if (slot.index >= 0) {
                    normalTexture = slot
                    normalScale = nt.get("scale")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1f
                }
            }

            var emissiveFactor = floatArrayOf(0f, 0f, 0f)
            mat.getAsJsonArray("emissiveFactor")?.takeIf { it.size() >= 3 }?.let { e ->
                emissiveFactor = floatArrayOf(e[0].asFloat, e[1].asFloat, e[2].asFloat)
            }
            var emissiveTexture = TextureSlot()
            texSlot(mat, "emissiveTexture").takeIf { it.index >= 0 }?.let { emissiveTexture = it }

            val alphaMode = when (mat.get("alphaMode")?.asString) {
                "MASK" -> AlphaMode.MASK
                "BLEND" -> AlphaMode.BLEND
                else -> AlphaMode.OPAQUE
            }

            // shadingShiftTexture.scale multiplies the factor (spec) — three-vrm
            // adds texture.g * scale to the shift; the static-viewer port folds
            // only the factor in, keep parity and ignore the texture.
            out[index] = MToonParams(
                name = mat.get("name")?.asString ?: "",
                baseColorFactor = baseColorFactor,
                baseColorTexture = baseColorTexture,
                shadeColorFactor = col3(mtoon, "shadeColorFactor", floatArrayOf(0f, 0f, 0f)),
                shadeMultiplyTexture = texSlot(mtoon, "shadeMultiplyTexture"),
                shadingShiftFactor = num(mtoon, "shadingShiftFactor", 0f),
                shadingToonyFactor = num(mtoon, "shadingToonyFactor", 0.9f),
                normalTexture = normalTexture,
                normalScale = normalScale,
                emissiveFactor = emissiveFactor,
                emissiveTexture = emissiveTexture,
                rimColorFactor = col3(mtoon, "parametricRimColorFactor", floatArrayOf(0f, 0f, 0f)),
                rimLightingMixFactor = num(mtoon, "rimLightingMixFactor", 1f),
                rimFresnelPowerFactor = num(mtoon, "parametricRimFresnelPowerFactor", 5f),
                rimLiftFactor = num(mtoon, "parametricRimLiftFactor", 0f),
                matcapFactor = col3(mtoon, "matcapFactor", floatArrayOf(1f, 1f, 1f)),
                matcapTexture = TextureSlot(texSlot(mtoon, "matcapTexture").index),
                outlineWidthMultiplyTexture = texSlot(mtoon, "outlineWidthMultiplyTexture"),
                outlineColorFactor = col3(mtoon, "outlineColorFactor", floatArrayOf(0f, 0f, 0f)),
                outlineLightingMixFactor = num(mtoon, "outlineLightingMixFactor", 1f),
                outlineWidthMode = mtoon.get("outlineWidthMode")?.asString ?: "none",
                outlineWidthFactor = num(mtoon, "outlineWidthFactor", 0f),
                doubleSided = mat.get("doubleSided")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                alphaMode = alphaMode,
                alphaCutoff = num(mat, "alphaCutoff", 0.5f),
                transparentWithZWrite = mtoon.get("transparentWithZWrite")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                renderQueueOffsetNumber = num(mtoon, "renderQueueOffsetNumber", 0f).toInt(),
            )
        }
        return out
    }
}
