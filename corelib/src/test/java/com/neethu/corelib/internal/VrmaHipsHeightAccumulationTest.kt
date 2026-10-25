package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [accumulateHeightY]（bindToModel 的 hips 高度链积）单测：VRM1 中间父节点
 * 骨架（默认模型 Asuna：hips 局部 y=0.20，链积 1.16）是 P0 道具验收①的
 * 根因——只读 hips 自身局部 y 会把步态提取速度压小 5.8×。纯函数,链结构
 * 用 map 模拟。
 */
class VrmaHipsHeightAccumulationTest {

    /** 链:instance → (局部 y, 父 instance)。 */
    private fun accumulator(chain: Map<Int, Pair<Float, Int>>): (Int, Int) -> Float {
        return { hips: Int, root: Int ->
            accumulateHeightY(
                hipsInstance = hips,
                rootInstance = root,
                localY = { i -> chain[i]?.first ?: 0f },
                parent = { i -> chain[i]?.second ?: 0 },
            )
        }
    }

    @Test
    fun `VRM1 中间父节点——链积才是真身高`() {
        // 默认模型 FreeTestCharacterAsuna_1024.vrm 的实测链（glTF 直读）:
        // hips(0.201)←Spine0(-0.089)←cMainH(0)←cMainV(1.048)←root
        val sum = accumulator(
            mapOf(
                10 to (0.201f to 11),
                11 to (-0.089f to 12),
                12 to (0f to 13),
                13 to (1.048f to 1),
            ),
        )(10, 1)
        assertEquals(1.16f, sum, 0.01f)
    }

    @Test
    fun `hips 直挂 root——与只读局部 y 等价(回归)`() {
        // 4705834820915235427.vrm:J_Bip_C_Hips(0.893)←Root(0)
        val sum = accumulator(mapOf(10 to (0.893f to 1)))(10, 1)
        assertEquals(0.893f, sum, 1e-4f)
    }

    @Test
    fun `hips 就是 root——零(调用方回落 1_0)`() {
        assertEquals(0f, accumulator(emptyMap())(1, 1), 1e-6f)
    }

    @Test
    fun `链中含负偏移照常累加`() {
        val sum = accumulator(
            mapOf(
                10 to (0.5f to 11),
                11 to (-0.2f to 1),
            ),
        )(10, 1)
        assertEquals(0.3f, sum, 1e-4f)
    }

    @Test
    fun `环链防御性截断不挂死`() {
        // 畸形数据:a↔b 互相为父——maxHops 截断返回部分和,不抛不循环
        val sum = accumulator(
            mapOf(
                10 to (0.1f to 11),
                11 to (0.1f to 10),
            ),
        )(10, 1)
        assertEquals(2.4f, sum, 0.01f) // 24 hops × 0.1
    }

    @Test
    fun `链中途断裂(父实例 0)——返回已积累部分`() {
        val sum = accumulator(
            mapOf(
                10 to (0.4f to 0), // parent=0 = 断链
            ),
        )(10, 1)
        assertEquals(0.4f, sum, 1e-4f)
    }
}
