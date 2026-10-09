package com.neethu.corelib.internal

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.neethu.corelib.RenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MToon 纯 JSON 层的不变量：VRM0 v0compat 转换（three-vrm VRMMaterialsV0CompatPlugin
 * 语义）、VRM1 VRMC_materials_mtoon 提取、GLB primitive 复制布局（表面槽/轮廓槽
 * 交错）、UV 变换矩阵列主序。数值基准来自桌面像素级对齐 viewer
 * （/home/neethu/projects/mtoon）的同名实现。
 */
class GlbMToonTest {

    private fun json(s: String): JsonObject = Gson().fromJson(s, JsonObject::class.java)

    // ── VRM 0.x v0compat 转换 ────────────────────────────────────────────

    private fun v0Json(materialProps: String) = json(
        """{"extensions": {"VRM": {"materialProperties": $materialProps}}}""",
    )

    @Test
    fun `v0 shade shift and toony follow the three-vrm conversion`() {
        val materials = GlbMToon.parseMaterials(
            v0Json(
                """[{
                    "shader": "VRM/MToon", "name": "Face",
                    "floatProperties": {"_ShadeShift": -0.5, "_ShadeToony": 0.9},
                    "vectorProperties": {}, "textureProperties": {}, "keywordMap": {}
                }]""",
            )
        )
        val p = materials[0]!!
        // toony = 0.9 + (1-0.9)*(0.5 + 0.5*(-0.5)) = 0.925
        assertEquals(0.925f, p.shadingToonyFactor, 1e-4f)
        // shift = -(-0.5) - (1 - 0.925) = 0.425
        assertEquals(0.425f, p.shadingShiftFactor, 1e-4f)
    }

    @Test
    fun `v0 outline width converts cm to m and mode index maps to names`() {
        val materials = GlbMToon.parseMaterials(
            v0Json(
                """[{
                    "shader": "VRM/MToon",
                    "floatProperties": {"_OutlineWidthMode": 2, "_OutlineWidth": 5},
                    "vectorProperties": {}, "textureProperties": {}, "keywordMap": {}
                }]""",
            )
        )
        val p = materials[0]!!
        assertEquals("screenCoordinates", p.outlineWidthMode)
        assertEquals(0.05f, p.outlineWidthFactor, 1e-6f)
    }

    @Test
    fun `v0 transparent with zwrite selects variant 2 and opaque stays 0`() {
        val materials = GlbMToon.parseMaterials(
            v0Json(
                """[
                    {"shader": "VRM/MToon",
                     "floatProperties": {"_ZWrite": 1}, "keywordMap": {"_ALPHABLEND_ON": true},
                     "vectorProperties": {}, "textureProperties": {}},
                    {"shader": "VRM/MToon",
                     "floatProperties": {}, "keywordMap": {},
                     "vectorProperties": {}, "textureProperties": {}}
                ]""",
            )
        )
        assertEquals(2, materials[0]!!.blendVariant)
        assertEquals(GlbMToon.AlphaMode.BLEND, materials[0]!!.alphaMode)
        assertTrue(materials[0]!!.transparentWithZWrite)
        assertEquals(0, materials[1]!!.blendVariant)
    }

    @Test
    fun `v0 unlit shaders convert to mtoon with shade equal to base color`() {
        val materials = GlbMToon.parseMaterials(
            v0Json(
                """[{
                    "shader": "VRM/UnlitTransparent",
                    "vectorProperties": {"_Color": [1.0, 0.5, 0.25, 1.0]},
                    "floatProperties": {}, "textureProperties": {}, "keywordMap": {}
                }]""",
            )
        )
        val p = materials[0]!!
        assertEquals(GlbMToon.AlphaMode.BLEND, p.alphaMode)
        // shade = base rgb（pow 2.2 与 _Color sRGB→线性一致）
        assertEquals(p.baseColorFactor[0], p.shadeColorFactor[0], 1e-6f)
        assertEquals(p.baseColorFactor[1], p.shadeColorFactor[1], 1e-6f)
    }

    @Test
    fun `non-mtoon v0 shaders are excluded`() {
        val materials = GlbMToon.parseMaterials(
            v0Json("""[{"shader": "Standard", "floatProperties": {}}]""")
        )
        assertTrue(materials.isEmpty())
    }

    // ── 材质自动判定（fresh 切模型：有 MToon 材质→MTOON，没有→PBR）────────

    @Test
    fun `detectRenderMode follows materials`() {
        val mtoon = GlbMToon.detectRenderMode(
            v0Json("""[{"shader": "VRM/MToon", "floatProperties": {}}]""")
        )
        assertEquals(RenderMode.MTOON, mtoon)
        // VRM1 扩展同样判定为 MToon
        val v1 = GlbMToon.detectRenderMode(
            json("""{"materials": [{"extensions": {"VRMC_materials_mtoon": {}}}]}""")
        )
        assertEquals(RenderMode.MTOON, v1)
    }

    @Test
    fun `detectRenderMode falls back to pbr without mtoon materials`() {
        assertEquals(RenderMode.PBR, GlbMToon.detectRenderMode(null))
        assertEquals(
            RenderMode.PBR,
            GlbMToon.detectRenderMode(v0Json("""[{"shader": "Standard", "floatProperties": {}}]""")),
        )
        // 纯 PBR 的 VRM1 模型（无扩展）
        assertEquals(
            RenderMode.PBR,
            GlbMToon.detectRenderMode(json("""{"materials": [{"pbrMetallicRoughness": {}}]}""")),
        )
    }

    // ── VRM 1.0 ──────────────────────────────────────────────────────────

    @Test
    fun `v1 mtoon extraction with texture transform and srgb base factor`() {
        val materials = GlbMToon.parseMaterials(
            json(
                """{"materials": [{
                    "name": "Hair", "doubleSided": true,
                    "alphaMode": "BLEND", "alphaCutoff": 0.4,
                    "pbrMetallicRoughness": {
                        "baseColorFactor": [0.5, 1.0, 0.0, 1.0],
                        "baseColorTexture": {"index": 3}
                    },
                    "extensions": {"VRMC_materials_mtoon": {
                        "shadeColorFactor": [0.2, 0.3, 0.4],
                        "shadingShiftFactor": 0.1,
                        "shadingToonyFactor": 0.8,
                        "outlineWidthMode": "worldCoordinates",
                        "outlineWidthFactor": 0.02,
                        "outlineWidthMultiplyTexture": {
                            "index": 5,
                            "extensions": {"KHR_texture_transform": {
                                "offset": [0.25, 0.5], "scale": [2.0, 3.0]
                            }}
                        },
                        "transparentWithZWrite": false
                    }}
                }]}""",
            )
        )
        val p = materials[0]!!
        assertEquals(0.02f, p.outlineWidthFactor, 1e-6f)
        assertEquals("worldCoordinates", p.outlineWidthMode)
        assertEquals(1, p.blendVariant)
        assertEquals(0.4f, p.alphaCutoff, 1e-6f)
        // base 0.5 sRGB → 线性 ≈ 0.214
        assertEquals(0.214f, p.baseColorFactor[0], 5e-3f)
        assertEquals(1f, p.baseColorFactor[1], 1e-6f)
        // 宽度贴图带 KHR_texture_transform
        assertEquals(5, p.outlineWidthMultiplyTexture.index)
        assertEquals(0.25f, p.outlineWidthMultiplyTexture.uv.tx, 1e-6f)
        assertEquals(2f, p.outlineWidthMultiplyTexture.uv.sx, 1e-6f)
        // matcap 忽略 uv 变换（spec）
    }

    @Test
    fun `v1 non-mtoon materials stay pbr`() {
        val materials = GlbMToon.parseMaterials(
            json("""{"materials": [{"pbrMetallicRoughness": {}}]}""")
        )
        assertTrue(materials.isEmpty())
    }

    // ── GLB primitive 复制 ───────────────────────────────────────────────

    /** 12B header + JSON chunk + BIN chunk 的最小 GLB。 */
    private fun buildGlb(jsonStr: String, bin: ByteArray = ByteArray(16)): ByteArray {
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        val jPad = (4 - jsonBytes.size % 4) % 4
        val total = 12 + 8 + jsonBytes.size + jPad + 8 + bin.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // glTF
        out.putInt(2)
        out.putInt(total)
        out.putInt(jsonBytes.size + jPad)
        out.putInt(0x4E4F534A) // JSON
        out.put(jsonBytes)
        repeat(jPad) { out.put(0x20) }
        out.putInt(bin.size)
        out.putInt(0x004E4942) // BIN
        out.put(bin)
        return out.array()
    }

    @Test
    fun `patch duplicates only mtoon prims of named nodes`() {
        val gltfJson = json(
            """{
                "nodes": [
                    {"name": "Body", "mesh": 0},
                    {"mesh": 1}
                ],
                "meshes": [
                    {"primitives": [
                        {"material": 0, "mode": 4},
                        {"material": 1, "mode": 4}
                    ]},
                    {"primitives": [{"material": 0, "mode": 4}]}
                ],
                "materials": [{"name": "MToon"}, {"name": "PBR"}]
            }""",
        )
        val materials = mapOf(0 to mtoonParams())
        val bin = ByteArray(32) { it.toByte() }
        val glb = ByteBuffer.wrap(buildGlb(gltfJson.toString(), bin))

        val (patched, layouts) = GlbMToon.patchGlb(glb, gltfJson, materials)

        val patchedJson = GlbMToon.readJson(patched)!!
        // mesh 0：mtoon prim 复制 1 份；mesh 1 的节点无名 → 不动
        assertEquals(3, patchedJson.getAsJsonArray("meshes")[0].asJsonObject
            .getAsJsonArray("primitives").size())
        assertEquals(1, patchedJson.getAsJsonArray("meshes")[1].asJsonObject
            .getAsJsonArray("primitives").size())

        val layout = layouts[0]!!
        assertEquals(listOf("Body"), layout.nodeNames)
        // 复制槽紧跟表面槽（interleaved），非 mtoon prim 无轮廓槽
        assertEquals(0, layout.prims[0].surfaceIndex)
        assertEquals(1, layout.prims[0].outlineIndex)
        assertEquals(2, layout.prims[1].surfaceIndex)
        assertEquals(-1, layout.prims[1].outlineIndex)

        // BIN chunk 原样保留
        val patchedBin = binChunkOf(patched)
        assertTrue(patchedBin.contentEquals(bin))
    }

    @Test
    fun `patch interleaves multiple mtoon prims with shifted surface indices`() {
        val gltfJson = json(
            """{
                "nodes": [{"name": "Face", "mesh": 0}],
                "meshes": [{"primitives": [
                    {"material": 0}, {"material": 0}, {"material": 2}
                ]}],
                "materials": [{"name": "A"}, {"name": "B"}]
            }""",
        )
        val materials = mapOf(0 to mtoonParams())
        val glb = ByteBuffer.wrap(buildGlb(gltfJson.toString()))

        val (patched, layouts) = GlbMToon.patchGlb(glb, gltfJson, materials)
        val prims = GlbMToon.readJson(patched)!!.getAsJsonArray("meshes")[0]
            .asJsonObject.getAsJsonArray("primitives")
        assertEquals(5, prims.size())
        val layout = layouts[0]!!.prims
        // [p0, p0', p1, p1', p2]
        assertEquals(0, layout[0].surfaceIndex); assertEquals(1, layout[0].outlineIndex)
        assertEquals(2, layout[1].surfaceIndex); assertEquals(3, layout[1].outlineIndex)
        assertEquals(4, layout[2].surfaceIndex); assertEquals(-1, layout[2].outlineIndex)
        // 复制的 primitive 与原 primitive 指向同一材质
        assertEquals(0, prims[1].asJsonObject.get("material").asInt)
        assertEquals(0, prims[3].asJsonObject.get("material").asInt)
    }

    @Test
    fun `patch without mtoon materials returns input unchanged`() {
        val gltfJson = json(
            """{"nodes": [{"name": "Body", "mesh": 0}],
                "meshes": [{"primitives": [{"material": 1}]}]}""",
        )
        val glb = ByteBuffer.wrap(buildGlb(gltfJson.toString()))
        val (patched, layouts) = GlbMToon.patchGlb(glb, gltfJson, emptyMap())
        assertTrue(layouts.isEmpty())
        assertSameBuffer(glb, patched)
    }

    // ── readJson / UV 矩阵 ───────────────────────────────────────────────

    @Test
    fun `readJson parses the first chunk and rejects non-glb`() {
        val glb = buildGlb("""{"nodes": []}""")
        assertNotNull(GlbMToon.readJson(ByteBuffer.wrap(glb)))
        assertNull(GlbMToon.readJson(ByteBuffer.wrap(ByteArray(64))))
    }

    @Test
    fun `uv transform builds column-major mat3`() {
        val identity = GlbMToon.UvTransform().toMat3ColumnMajor()
        // [sx·c, sx·s, 0, -sy·s, sy·c, 0, tx, ty, 1]；-sy·s 在 s=0 时是 -0.0，
        // contentEquals 按位比较会挂，用逐元素容差比较
        val expected = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        identity.forEachIndexed { i, v -> assertEquals("[$i]", expected[i], v, 1e-9f) }

        // offset (tx=0.25, ty=0.5), scale (2, 3)：shader 里 (M * vec3(uv,1)).xy
        // = (2u + 0.25, 3v + 0.5)
        val m = GlbMToon.UvTransform(tx = 0.25f, ty = 0.5f, sx = 2f, sy = 3f).toMat3ColumnMajor()
        // 列主序：col0=(m00,m10,m20)=(2,0,0)、col1=(0,3,0)、col2=(0.25,0.5,1)
        assertEquals(2f, m[0], 1e-6f)
        assertEquals(3f, m[4], 1e-6f)
        assertEquals(0.25f, m[6], 1e-6f)
        assertEquals(0.5f, m[7], 1e-6f)
        assertEquals(1f, m[8], 1e-6f)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun mtoonParams() = GlbMToon.MToonParams(name = "Test")

    private fun assertSameBuffer(a: ByteBuffer, b: ByteBuffer) {
        // patchGlb 原样返回时 a===b，position 共享——先各自快照再比
        val snapA = ByteArray(a.remaining()).also { a.duplicate().get(it) }
        val snapB = ByteArray(b.remaining()).also { b.duplicate().get(it) }
        assertTrue(snapA.contentEquals(snapB))
    }

    private fun binChunkOf(glb: ByteBuffer): ByteArray {
        glb.order(ByteOrder.LITTLE_ENDIAN); glb.rewind()
        glb.int; glb.int; glb.int
        while (glb.hasRemaining()) {
            val len = glb.int
            val type = glb.int
            if (type == 0x004E4942) {
                val out = ByteArray(len)
                glb.get(out)
                return out
            }
            glb.position(glb.position() + len)
        }
        error("no BIN chunk")
    }
}
