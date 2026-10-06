package com.neethu.orchestrator.face

import com.neethu.corelib.AvatarController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模型重载后的表情集合刷新（导入/切换模型功能，2026-10）：FaceDriver 的
 * supportedExpressions 原来只在首次 start 时捕获，同一会话内换模型后新模型
 * 的 morph 名会被 send 门控静默丢弃。现在 start 二次调用（session.
 * startFaceDriving）即刷新；刷新同时清 dedup 账本——控制器已随旧模型清空
 * 权重，旧账本会把同值重写挡住（新模型上该 morph 永远到不了位）。
 *
 * Choreographer 在 JVM 单测不可用，走 expressionsProvider 注入直测刷新路径
 * （start 的重入分支就是 refreshExpressions）。
 */
class FaceDriverExpressionRefreshTest {

    private fun driver(names: () -> List<String>) = FaceDriver(
        AvatarController(),
        CoroutineScope(Dispatchers.Unconfined),
        expressionsProvider = names,
    )

    @Test
    fun `refresh re-captures the expression set of the new model`() {
        var names = listOf("happy", "blink")
        val fd = driver { names }
        fd.refreshExpressions()
        assertEquals(setOf("happy", "blink"), fd.availableExpressions)

        names = listOf("joy", "blinkLeft") // 新模型的表情目录
        fd.refreshExpressions()
        assertEquals(setOf("joy", "blinkLeft"), fd.availableExpressions)
    }

    @Test
    fun `refresh re-binds name resolution to the new model`() {
        var names = listOf<String>()
        val fd = driver { names }
        fd.refreshExpressions()
        assertNull(fd.resolveExpression("blinkLeft")) // 旧模型没有这个 morph

        names = listOf("blinkLeft")
        fd.refreshExpressions()
        assertEquals("blinkLeft", fd.resolveExpression("blinkleft")) // 大小写不敏感回配
        assertNull(fd.resolveExpression("happy")) // 旧模型独有的名字随刷新失效
    }
}
