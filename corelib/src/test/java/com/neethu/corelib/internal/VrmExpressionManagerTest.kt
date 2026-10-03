package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class VrmExpressionManagerTest {

    private fun bind(mesh: Int, morph: Int, weight: Float) =
        VrmExpressionManager.MorphTargetBind(mesh, morph, weight)

    @Test
    fun `weak binds scale up so the strongest reaches 1`() {
        // SK_Sun 类转换模型：aa 只有 0.5、sad 只有 0.25 的绑定
        val out = VrmExpressionManager.normalizeBindWeights(listOf(bind(0, 49, 0.5f)))
        assertEquals(listOf(bind(0, 49, 1f)), out)

        val out2 = VrmExpressionManager.normalizeBindWeights(
            listOf(bind(0, 49, 0.25f), bind(1, 30, 0.125f)),
        )
        assertEquals(listOf(bind(0, 49, 1f), bind(1, 30, 0.5f)), out2)
    }

    @Test
    fun `relative ratios between binds are preserved`() {
        val out = VrmExpressionManager.normalizeBindWeights(
            listOf(bind(0, 49, 0.3f), bind(0, 27, 0.2f)),
        )
        assertEquals(1f, out[0].weight, 1e-6f)
        assertEquals(0.2f / 0.3f, out[1].weight, 1e-6f)
    }

    @Test
    fun `full weight binds are untouched`() {
        val binds = listOf(bind(0, 39, 1f), bind(1, 5, 0.4f))
        assertEquals(binds, VrmExpressionManager.normalizeBindWeights(binds))
    }

    @Test
    fun `empty and all-zero binds pass through`() {
        assertEquals(0, VrmExpressionManager.normalizeBindWeights(emptyList()).size)
        val zeros = listOf(bind(0, 1, 0f), bind(0, 2, 0f))
        assertEquals(zeros, VrmExpressionManager.normalizeBindWeights(zeros))
    }
}
