package com.neethu.corelib.internal

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pre-processes GLB data so that gltfio uploads correct morph target data (positions AND
 * normals) to the GPU. VRM exports need two JSON patches for Filament's morphing to work:
 *
 * 1. **Inject default mesh `weights`.** gltfio's ResourceLoader only runs its tangent-frame
 *    job for morph targets when `mesh.weights_count > 0`. Most VRM exports omit the optional
 *    glTF default `weights`, so the morph tangent texture layers are never uploaded and stay
 *    uninitialized. As soon as an expression drives any weight non-zero, Filament's vertex
 *    morphNormal() fetches that garbage and corrupts vertex normals. Zero weights keep the
 *    neutral look; they merely flip gltfio's gate.
 *
 * 2. **Strip `KHR_materials_unlit`.** Even with weights present, gltfio skips the morph
 *    tangent job for materials flagged unlit (`!prim.material->unlit` gate). VRM exporters
 *    routinely stamp KHR_materials_unlit on every material, which would leave the morph
 *    normals as garbage — shading corrupts as soon as an expression drives a weight
 *    non-zero. Stripping it also lets gltfio light the model with its standard PBR
 *    ubershader instead of flat unlit shading.
 */
internal object GlbMorphPatcher {

    private const val TAG = "GlbMorphPatcher"
    private const val GLB_MAGIC = 0x46546C67
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    /**
     * Returns a GLB buffer whose morph meshes declare default weights and whose materials
     * carry no KHR_materials_unlit. The input (or the unchanged original, rewound to 0) is
     * returned when no patch is needed; otherwise a rebuilt GLB is returned.
     */
    fun injectMorphDefaultWeights(glbBuffer: ByteBuffer): ByteBuffer = try {
        processGlb(glbBuffer)
    } catch (e: Exception) {
        Log.e(TAG, "GLB morph patch failed, passing original GLB", e)
        glbBuffer.rewind()
        glbBuffer
    }

    private fun processGlb(src: ByteBuffer): ByteBuffer {
        src.order(ByteOrder.LITTLE_ENDIAN); src.rewind()
        if (src.remaining() < 12) return src
        val magic = src.int; val ver = src.int; src.int // magic, version, total length
        if (magic != GLB_MAGIC || ver != GLB_VERSION) return src

        // ── find chunk offsets ──────────────────────────────────────────
        var jsonOff = -1; var jsonLen = -1
        var binOff = -1; var binLen = -1
        while (src.hasRemaining()) {
            if (src.remaining() < 8) break
            val cLen = src.int; val cType = src.int
            when (cType) {
                CHUNK_JSON -> { jsonOff = src.position(); jsonLen = cLen }
                CHUNK_BIN  -> { binOff = src.position(); binLen = cLen }
            }
            src.position(src.position() + cLen)
        }
        if (jsonOff < 0) return src.also { it.rewind() }

        val jsonBytes = ByteArray(jsonLen)
        src.position(jsonOff); src.get(jsonBytes)
        val json = Gson().fromJson(String(jsonBytes, Charsets.UTF_8), JsonObject::class.java)

        var patched = 0

        // ── patch 1: default weights on morph meshes ────────────────────
        val meshes = json.getAsJsonArray("meshes")
        meshes?.forEach { meshEl ->
            val mesh = meshEl.asJsonObject
            val prims = mesh.getAsJsonArray("primitives") ?: return@forEach
            val targetCount = prims.firstNotNullOfOrNull { p ->
                p.asJsonObject.getAsJsonArray("targets")?.size()
            } ?: return@forEach
            if (targetCount <= 0) return@forEach
            // gltfio gates the tangent upload on weights_count; treat a missing,
            // empty, or short array as absent
            if ((mesh.getAsJsonArray("weights")?.size() ?: 0) >= targetCount) return@forEach
            mesh.add("weights", JsonArray(targetCount).apply {
                repeat(targetCount) { add(0f) }
            })
            patched++
        }

        // ── patch 2: strip KHR_materials_unlit so the tangent job runs ──
        val materials = json.getAsJsonArray("materials")
        materials?.forEach { matEl ->
            val exts = matEl.asJsonObject.getAsJsonObject("extensions") ?: return@forEach
            if (exts.remove("KHR_materials_unlit") != null) patched++
        }

        if (patched == 0) return src.also { it.rewind() }
        Log.i(TAG, "Patched GLB for morph correctness ($patched mesh/material entries)")

        // ── rebuild GLB: new JSON chunk, original BIN chunk byte-for-byte ──
        val newJson = GsonBuilder().disableHtmlEscaping().create()
            .toJson(json).toByteArray(Charsets.UTF_8)
        val jPad = (4 - newJson.size % 4) % 4
        val hasBin = binOff >= 0
        val total = 12 + 8 + newJson.size + jPad + if (hasBin) 8 + binLen else 0

        val out = ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN)
        // header
        out.putInt(GLB_MAGIC); out.putInt(GLB_VERSION); out.putInt(total)
        // JSON chunk
        out.putInt(newJson.size + jPad); out.putInt(CHUNK_JSON)
        out.put(newJson); repeat(jPad) { out.put(0x20.toByte()) }
        // BIN chunk (unchanged)
        if (hasBin) {
            out.putInt(binLen); out.putInt(CHUNK_BIN)
            src.position(binOff)
            val slice = src.slice().order(ByteOrder.LITTLE_ENDIAN); slice.limit(binLen)
            out.put(slice)
        }

        out.rewind()
        Log.i(TAG, "GLB rebuilt: ${src.capacity()} → $total bytes")
        return out
    }
}
