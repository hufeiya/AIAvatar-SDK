package com.neethu.aiavatar_sdk

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统内置语音识别（SystemAsr.kt）的纯 JVM 部分：错误码消息映射、连续聆听
 * 重启策略、文本量时长估计（技能快路径的「短句」判定输入）。
 */
class SystemAsrTest {

    // ── 错误码消息 ───────────────────────────────────────────────────────
    @Test
    fun `error codes map to user readable messages`() {
        assertEquals("没有听到说话", systemAsrErrorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals("没有匹配到语音内容", systemAsrErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals("缺少麦克风权限", systemAsrErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals("识别服务忙", systemAsrErrorMessage(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        // 未知码带上原码，不留谜语
        assertEquals("识别错误(code=99)", systemAsrErrorMessage(99))
    }

    // ── 重启策略 ─────────────────────────────────────────────────────────
    @Test
    fun `silence errors restart immediately and never accumulate strikes`() {
        val p = SystemAsrRestartPolicy()
        repeat(20) {
            assertEquals(SystemAsrRestartPolicy.Decision.RestartNow, p.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
            assertEquals(SystemAsrRestartPolicy.Decision.RestartNow, p.onError(SpeechRecognizer.ERROR_NO_MATCH))
        }
    }

    @Test
    fun `transient errors back off exponentially with a cap then stop`() {
        val p = SystemAsrRestartPolicy(backoffBaseMs = 500, backoffCapMs = 2_000, maxTransientStrikes = 4)
        assertEquals(SystemAsrRestartPolicy.Decision.RestartAfter(500), p.onError(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SystemAsrRestartPolicy.Decision.RestartAfter(1_000), p.onError(SpeechRecognizer.ERROR_SERVER))
        // 500 shl 2 = 2000，恰好到封顶
        assertEquals(SystemAsrRestartPolicy.Decision.RestartAfter(2_000), p.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        // 第 4 次瞬时错误 = 连败 ≥4 → 判停（连败期间不重启翻倍到封顶之外）
        assertEquals(SystemAsrRestartPolicy.Decision.Stop, p.onError(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
    }

    @Test
    fun `a successful result resets the transient strike counter`() {
        val p = SystemAsrRestartPolicy(maxTransientStrikes = 3)
        assertEquals(SystemAsrRestartPolicy.Decision.RestartAfter(500), p.onError(SpeechRecognizer.ERROR_SERVER))
        assertEquals(SystemAsrRestartPolicy.Decision.RestartAfter(1_000), p.onError(SpeechRecognizer.ERROR_SERVER))
        p.onResult() // 一次成功归零，重新起算
        assertEquals(SystemAsrRestartPolicy.Decision.RestartAfter(500), p.onError(SpeechRecognizer.ERROR_SERVER))
    }

    @Test
    fun `permission error waits for consent instead of stopping`() {
        val p = SystemAsrRestartPolicy()
        // 首用授权弹窗期间服务持续报权限错误：等待轮询，绝不判停（判停=销毁
        // 会话=弹窗跟着消失，用户永远点不到）
        repeat(10) {
            assertEquals(
                SystemAsrRestartPolicy.Decision.WaitForConsent(2_500),
                p.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
            )
        }
        // 等待授权不计入瞬时连败：授权通过后服务照常工作
        assertEquals(SystemAsrRestartPolicy.Decision.RestartAfter(500), p.onError(SpeechRecognizer.ERROR_SERVER))
    }

    @Test
    fun `client errors are transient so half-duplex switching does not kill the session`() {
        val p = SystemAsrRestartPolicy(maxTransientStrikes = 3)
        // OEM 服务（小米 mibrain）暂停/恢复切换时会把「未聆听态 stopListening」
        // 报成 client error——按瞬时退避重启，一次切换不许打死会话
        assertEquals(SystemAsrRestartPolicy.Decision.RestartAfter(500), p.onError(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(SystemAsrRestartPolicy.Decision.RestartAfter(1_000), p.onError(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(SystemAsrRestartPolicy.Decision.Stop, p.onError(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun `fatal errors stop immediately`() {
        val p = SystemAsrRestartPolicy()
        assertEquals(SystemAsrRestartPolicy.Decision.Stop, p.onError(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(SystemAsrRestartPolicy.Decision.Stop, p.onError(12345))
    }

    // ── 文本量时长估计（技能快路径输入）──────────────────────────────────
    @Test
    fun `estimateSpeechMs is zero for blank text`() {
        assertEquals(0L, estimateSpeechMs(""))
        assertEquals(0L, estimateSpeechMs("   "))
    }

    @Test
    fun `short chinese utterance counts as a throw signal`() {
        // 猜拳「三二一」= 3 个汉字 → 750ms，落在 ≤2.5s 短句阈值内
        assertTrue(estimateSpeechMs("三二一") <= 2_500)
        assertEquals(750L, estimateSpeechMs("三二一"))
        // 下限钳制：单字也不至于 0
        assertTrue(estimateSpeechMs("嗯") >= 300)
    }

    @Test
    fun `long utterances exceed the throw threshold`() {
        val longSentence = "我们再玩一局吧这次我一定不会让你赢的"
        assertTrue(estimateSpeechMs(longSentence) > 2_500)
        // 英文按词计
        assertTrue(estimateSpeechMs("rock paper scissors show me what you got") > 2_500)
    }

    @Test
    fun `estimate clamps at ten seconds`() {
        val veryLong = "啊".repeat(100) // 100×250ms = 25s → 钳到 10s
        assertEquals(10_000L, estimateSpeechMs(veryLong))
    }
}
