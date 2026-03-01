package com.neethu.corelib.internal

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pre-processes GLB data to work around Filament's CONFIG_MAX_BONE_COUNT = 256 limit.
 *
 * Strategy (in order of preference):
 * 1. **Cull**: remove joints not referenced by any vertex → remap indices.
 * 2. **Merge**: if used joints still > 256, merge the least-referenced joints
 *    into their parent joints in the skeleton hierarchy. This causes minimal
 *    visual quality loss (the parent's transform approximates the child's).
 *
 * Memory: reads directly from the source buffer (no full BIN copy).
 * Output uses [ByteBuffer.allocateDirect] to avoid Java heap pressure.
 */
internal object GlbBoneCuller {

    private const val TAG = "GlbBoneCuller"
    private const val MAX_BONE_COUNT = 256

    private const val GLB_MAGIC = 0x46546C67
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val COMP_UBYTE = 5121
    private const val COMP_USHORT = 5123
    private const val COMP_FLOAT = 5126

    fun cullUnusedBones(glbBuffer: ByteBuffer): ByteBuffer? = try {
        processGlb(glbBuffer)
    } catch (e: Exception) {
        Log.e(TAG, "Bone processing failed, passing original GLB", e)
        null
    }

    // ── data classes ────────────────────────────────────────────────────

    private data class JointsRef(
        val attrName: String,
        val accessorIdx: Int,
        val componentType: Int,
        val count: Int,
        val bvOffset: Int,
        val bvStride: Int,
        val accOffset: Int
    )

    private data class PrimAnalysis(
        val meshIdx: Int,
        val primIdx: Int,
        val usedJoints: Set<Int>,
        val jointsRefs: List<JointsRef>
    )

    // ── main ────────────────────────────────────────────────────────────

    private fun processGlb(src: ByteBuffer): ByteBuffer? {
        src.order(ByteOrder.LITTLE_ENDIAN); src.rewind()
        if (src.remaining() < 12) return null
        val magic = src.int; val ver = src.int; @Suppress("UNUSED_VARIABLE") val len = src.int
        if (magic != GLB_MAGIC || ver != GLB_VERSION) return null

        // ── find chunk offsets (no copies) ──────────────────────────────
        var jsonOff = -1; var jsonLen = -1
        var binOff = -1;  var binLen = -1
        while (src.hasRemaining()) {
            if (src.remaining() < 8) break
            val cLen = src.int; val cType = src.int
            when (cType) {
                CHUNK_JSON -> { jsonOff = src.position(); jsonLen = cLen }
                CHUNK_BIN  -> { binOff  = src.position(); binLen  = cLen }
            }
            src.position(src.position() + cLen)
        }
        if (jsonOff < 0 || binOff < 0) return null

        // ── parse JSON ──────────────────────────────────────────────────
        val jsonBytes = ByteArray(jsonLen)
        src.position(jsonOff); src.get(jsonBytes)
        val json = Gson().fromJson(String(jsonBytes, Charsets.UTF_8), JsonObject::class.java)

        val skins     = json.getAsJsonArray("skins")      ?: return null
        val accessors = json.getAsJsonArray("accessors")  ?: return null
        val bvs       = json.getAsJsonArray("bufferViews") ?: return null
        val nodes     = json.getAsJsonArray("nodes")      ?: return null
        val meshes    = json.getAsJsonArray("meshes")     ?: return null

        val oversized = (0 until skins.size()).filter {
            (skins[it].asJsonObject.getAsJsonArray("joints")?.size() ?: 0) > MAX_BONE_COUNT
        }
        if (oversized.isEmpty()) { Log.d(TAG, "All skins OK"); return null }

        // ── build node parent map (for bone merging) ────────────────────
        val parentMap = HashMap<Int, Int>()   // childNodeIdx → parentNodeIdx
        for (ni in 0 until nodes.size()) {
            nodes[ni].asJsonObject.getAsJsonArray("children")?.forEach { c ->
                parentMap[c.asInt] = ni
            }
        }

        var modified = false
        val extraBin = mutableListOf<ByteArray>()

        for (skinIdx in oversized) {
            val skinObj = skins[skinIdx].asJsonObject
            val jointsArr = skinObj.getAsJsonArray("joints")!!
            val jc = jointsArr.size()
            Log.i(TAG, "Processing skin $skinIdx: $jc joints")

            // find mesh nodes using this skin
            val meshNodes = (0 until nodes.size()).mapNotNull { ni ->
                val n = nodes[ni].asJsonObject
                if (n.get("skin")?.asInt == skinIdx && n.has("mesh"))
                    ni to n.get("mesh").asInt else null
            }
            if (meshNodes.isEmpty()) continue

            // ── per-primitive joint analysis ─────────────────────────────
            val analyses = mutableListOf<PrimAnalysis>()
            for ((_, mi) in meshNodes) {
                if (mi >= meshes.size()) continue
                val primsArr = meshes[mi].asJsonObject.getAsJsonArray("primitives") ?: continue
                for (pi in 0 until primsArr.size()) {
                    val attrs = primsArr[pi].asJsonObject.getAsJsonObject("attributes") ?: continue
                    val used = mutableSetOf<Int>()
                    val refs = mutableListOf<JointsRef>()
                    for ((key, value) in attrs.entrySet()) {
                        if (!key.startsWith("JOINTS_")) continue
                        val ai = value.asInt
                        val acc = accessors[ai].asJsonObject
                        val ct = acc.get("componentType").asInt
                        val cnt = acc.get("count").asInt
                        val bi = acc.get("bufferView")?.asInt ?: continue
                        val ao = acc.get("byteOffset")?.asInt ?: 0
                        val bv = bvs[bi].asJsonObject
                        val bo = bv.get("byteOffset")?.asInt ?: 0
                        val bs = bv.get("byteStride")?.asInt ?: 0
                        val cs = if (ct == COMP_USHORT) 2 else 1
                        val stride = if (bs > 0) bs else 4 * cs
                        refs.add(JointsRef(key, ai, ct, cnt, bo, stride, ao))
                        for (v in 0 until cnt) {
                            val base = binOff + bo + ao + v * stride
                            for (c in 0 until 4) {
                                val idx = readJointIdx(src, base, c, ct)
                                if (idx < jc) used.add(idx)
                            }
                        }
                    }
                    analyses.add(PrimAnalysis(mi, pi, used, refs))
                }
            }

            val allUsed = analyses.flatMapTo(mutableSetOf()) { it.usedJoints }
            Log.i(TAG, "Skin $skinIdx: ${allUsed.size}/$jc joints used")

            // ── decide strategy ─────────────────────────────────────────
            val keptJoints: Set<Int>            // old skin indices to keep
            val remap: Map<Int, Int>            // old skin index → new skin index

            if (allUsed.size <= MAX_BONE_COUNT) {
                // Strategy 1: simple cull (remove unused)
                keptJoints = allUsed
                val sorted = keptJoints.sorted()
                remap = buildRemap(sorted, jc)
                Log.i(TAG, "Skin $skinIdx: culled ${jc - keptJoints.size} unused joints → ${keptJoints.size}")
            } else {
                // Strategy 2: merge least-referenced joints into parents
                val excess = allUsed.size - MAX_BONE_COUNT
                Log.i(TAG, "Skin $skinIdx: need to merge $excess joints into parents")

                // count how many vertex-components reference each joint
                val refCount = IntArray(jc)
                for (pa in analyses) {
                    for (jr in pa.jointsRefs) {
                        for (v in 0 until jr.count) {
                            val base = binOff + jr.bvOffset + jr.accOffset + v * jr.bvStride
                            for (c in 0 until 4) {
                                val idx = readJointIdx(src, base, c, jr.componentType)
                                if (idx < jc) refCount[idx]++
                            }
                        }
                    }
                }

                // nodeIdx → skinIdx reverse lookup
                val nodeToSkinIdx = HashMap<Int, Int>()
                for (si in 0 until jointsArr.size()) {
                    nodeToSkinIdx[jointsArr[si].asInt] = si
                }

                // pick the `excess` least-referenced joints to drop
                val sortedUsed = allUsed.sortedBy { refCount[it] }
                val toDrop = sortedUsed.take(excess).toSet()
                keptJoints = allUsed - toDrop

                Log.i(TAG, "Dropping ${toDrop.size} joints (refs: ${sortedUsed.take(excess).map { refCount[it] }})")

                // for each dropped joint, find a kept ancestor via skeleton tree
                val mergeTarget = HashMap<Int, Int>()   // dropped skinIdx → kept skinIdx
                for (dropSI in toDrop) {
                    var nodeIdx = jointsArr[dropSI].asInt
                    var target: Int? = null
                    var limit = 200
                    while (limit-- > 0) {
                        val pNode = parentMap[nodeIdx] ?: break
                        val pSI = nodeToSkinIdx[pNode]
                        if (pSI != null && pSI in keptJoints) { target = pSI; break }
                        nodeIdx = pNode
                    }
                    mergeTarget[dropSI] = target ?: keptJoints.first()
                }

                // build final remap
                val sortedKept = keptJoints.sorted()
                val baseRemap = HashMap<Int, Int>(sortedKept.size * 2)
                for ((newI, oldI) in sortedKept.withIndex()) baseRemap[oldI] = newI

                val fullRemap = HashMap<Int, Int>(jc * 2)
                for (i in 0 until jc) {
                    fullRemap[i] = when {
                        i in baseRemap -> baseRemap[i]!!
                        i in mergeTarget -> baseRemap[mergeTarget[i]!!]!!
                        else -> 0
                    }
                }
                remap = fullRemap
                Log.i(TAG, "Skin $skinIdx: merged → ${keptJoints.size} joints")
            }

            // ── apply remap ─────────────────────────────────────────────

            // new joints array
            val sortedKept = keptJoints.sorted()
            skinObj.add("joints", JsonArray().apply { sortedKept.forEach { add(jointsArr[it]) } })

            // new inverseBindMatrices
            skinObj.get("inverseBindMatrices")?.asInt?.let { ibmAi ->
                val ibmAcc = accessors[ibmAi].asJsonObject
                val ibmBi = ibmAcc.get("bufferView")?.asInt ?: return@let
                val ibmAo = ibmAcc.get("byteOffset")?.asInt ?: 0
                val ibmBo = bvs[ibmBi].asJsonObject.get("byteOffset")?.asInt ?: 0
                val m4 = 64
                val data = ByteArray(sortedKept.size * m4)
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                for ((ni, oi) in sortedKept.withIndex()) {
                    val s = binOff + ibmBo + ibmAo + oi * m4
                    for (f in 0 until 16) buf.putFloat(ni * m4 + f * 4, src.getFloat(s + f * 4))
                }
                val off = binLen + extraBin.sumOf { it.size }
                extraBin.add(data)
                val bvi = bvs.size()
                bvs.add(JsonObject().apply {
                    addProperty("buffer", 0); addProperty("byteOffset", off)
                    addProperty("byteLength", data.size)
                })
                val ai = accessors.size()
                accessors.add(JsonObject().apply {
                    addProperty("bufferView", bvi); addProperty("byteOffset", 0)
                    addProperty("componentType", COMP_FLOAT)
                    addProperty("count", sortedKept.size); addProperty("type", "MAT4")
                })
                skinObj.addProperty("inverseBindMatrices", ai)
            }

            // remap JOINTS data → create new accessor + bufferView per primitive
            for (pa in analyses) {
                val origPrim = meshes[pa.meshIdx].asJsonObject
                    .getAsJsonArray("primitives")[pa.primIdx].asJsonObject
                val pAttrs = origPrim.getAsJsonObject("attributes")

                for (jr in pa.jointsRefs) {
                    val cs = if (jr.componentType == COMP_USHORT) 2 else 1
                    val es = 4 * cs
                    val data = ByteArray(jr.count * es)
                    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    for (v in 0 until jr.count) {
                        val sBase = binOff + jr.bvOffset + jr.accOffset + v * jr.bvStride
                        val dBase = v * es
                        for (c in 0 until 4) {
                            val oldIdx = readJointIdx(src, sBase, c, jr.componentType)
                            val newIdx = remap[oldIdx] ?: 0
                            if (jr.componentType == COMP_USHORT)
                                buf.putShort(dBase + c * 2, newIdx.toShort())
                            else
                                buf.put(dBase + c, newIdx.toByte())
                        }
                    }
                    val off = binLen + extraBin.sumOf { it.size }
                    extraBin.add(data)
                    val bvi = bvs.size()
                    bvs.add(JsonObject().apply {
                        addProperty("buffer", 0); addProperty("byteOffset", off)
                        addProperty("byteLength", data.size)
                    })
                    val ai = accessors.size()
                    accessors.add(JsonObject().apply {
                        addProperty("bufferView", bvi); addProperty("byteOffset", 0)
                        addProperty("componentType", jr.componentType)
                        addProperty("count", jr.count); addProperty("type", "VEC4")
                    })
                    pAttrs.addProperty(jr.attrName, ai)
                }
            }

            modified = true
        }

        if (!modified) return null

        // ── update buffer[0].byteLength ─────────────────────────────────
        val newBinLen = binLen + extraBin.sumOf { it.size }
        json.getAsJsonArray("buffers")?.get(0)?.asJsonObject
            ?.addProperty("byteLength", newBinLen)

        // ── rebuild GLB ─────────────────────────────────────────────────
        val newJson = GsonBuilder().disableHtmlEscaping().create()
            .toJson(json).toByteArray(Charsets.UTF_8)
        val jPad = (4 - newJson.size % 4) % 4
        val bPad = (4 - newBinLen % 4) % 4
        val total = 12 + 8 + newJson.size + jPad + 8 + newBinLen + bPad

        val out = ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN)
        // header
        out.putInt(GLB_MAGIC); out.putInt(GLB_VERSION); out.putInt(total)
        // JSON chunk
        out.putInt(newJson.size + jPad); out.putInt(CHUNK_JSON)
        out.put(newJson); repeat(jPad) { out.put(0x20.toByte()) }
        // BIN chunk
        out.putInt(newBinLen + bPad); out.putInt(CHUNK_BIN)
        src.position(binOff)
        val slice = src.slice().order(ByteOrder.LITTLE_ENDIAN); slice.limit(binLen)
        out.put(slice)
        for (e in extraBin) out.put(e)
        repeat(bPad) { out.put(0.toByte()) }

        out.rewind()
        Log.i(TAG, "GLB rebuilt: ${src.capacity()} → $total bytes")
        return out
    }

    // ── utilities ───────────────────────────────────────────────────────

    /** Build oldSkinIdx → newSkinIdx map covering ALL original indices. */
    private fun buildRemap(sortedKept: List<Int>, totalJoints: Int): Map<Int, Int> {
        val m = HashMap<Int, Int>(totalJoints * 2)
        for ((newI, oldI) in sortedKept.withIndex()) m[oldI] = newI
        for (i in 0 until totalJoints) if (i !in m) m[i] = 0
        return m
    }

    /** Read one joint index component from the source buffer. */
    private fun readJointIdx(buf: ByteBuffer, base: Int, component: Int, compType: Int): Int =
        if (compType == COMP_USHORT)
            buf.getShort(base + component * 2).toInt() and 0xFFFF
        else
            buf.get(base + component).toInt() and 0xFF
}
