package com.neethu.aiavatar_sdk.multiavatar.show

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.LlmUsage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点播路由全分支（docs/multi-dance-feasibility.md §7-P2）：规则档精确命中
 * （不走 LLM）、人名/曲目名单缺 → 降 LLM、LLM 档白名单双向校验（人不在场/
 * 曲不在单/垃圾输出/超时/网络失败 → 拒绝）、缺 Key 静默规则档、串场词截断
 * 与润色降级、词边界匹配防单字母 id 误中。
 *
 * LLM fake 记录调用次数——规则档命中的用例用「调用即炸」的 fake 锁死零调用。
 */
class DanceRequestRouterTest {

    private val cast = listOf(
        RequestCastMember("A", "小暖"),
        RequestCastMember("B", "小柔"),
    )
    private val whitelist = setOf(
        "animations/08_舞蹈与表演/Bboy Hip Hop Variation One.vrma",
        "animations/08_舞蹈与表演/Doing The Shuffling Dance.vrma",
        "animations/03_打招呼与礼仪/Quick Informal Bow.vrma",
    )

    /** 可编程 fake：回固定 reply 并计数；boom=true 直接流内抛错；hang=true 挂死测超时。 */
    private class FakeLlm(
        private val reply: String = "",
        private val boom: Boolean = false,
        private val hang: Boolean = false,
    ) : LlmAdapter {
        var calls = 0
        override fun streamChat(
            messages: List<ChatMessage>,
            config: LlmConfig,
        ): Flow<LlmStreamEvent> = flow {
            calls++
            if (boom) throw java.io.IOException("boom")
            if (hang) {
                delay(60_000)
                return@flow
            }
            emit(LlmStreamEvent.TextDelta(reply))
            emit(LlmStreamEvent.Finish(null, LlmUsage(10, 5), emptyList()))
        }
    }

    private fun llmRouter(fake: FakeLlm, timeoutMs: Long = RequestPolicy.Llm.DEFAULT_TIMEOUT_MS) =
        DanceRequestRouter(RequestPolicy.Llm(fake, { LlmConfig("http://d", "k", model = "m") }, timeoutMs))

    private fun ruleRouter(fake: FakeLlm = FakeLlm()) =
        DanceRequestRouter(RequestPolicy.Rule).let { it to fake }

    private val goodReply =
        "{\"performer\":\"B\",\"clip\":\"08_舞蹈与表演/Doing The Shuffling Dance\"," +
            "\"line\":\"下面有请小柔带来鬼步舞！\",\"reason\":\"点播鬼步舞\"}"

    // ── 规则档 ───────────────────────────────────────────────────────────

    @Test
    fun `规则档人名加曲目名精确命中不走LLM`() = runTest {
        val fake = FakeLlm(reply = "不应被调用")
        val router = DanceRequestRouter(RequestPolicy.Llm(fake, { null }))
        val d = router.route("小柔 来一段 Doing The Shuffling Dance", cast, whitelist)
        assertTrue(d is RequestDecision.Insert)
        d as RequestDecision.Insert
        assertEquals("rule", d.track)
        assertEquals("B", d.act.performer)
        assertEquals("animations/08_舞蹈与表演/Doing The Shuffling Dance.vrma", d.act.clip)
        assertEquals(0, fake.calls) // 规则档命中：零 LLM 调用零成本
    }

    @Test
    fun `规则档槽位id用词边界匹配防误中`() = runTest {
        val fake = FakeLlm()
        val router = DanceRequestRouter(RequestPolicy.Llm(fake, { null }))
        // 「bad」里的 a 不算点名 A；「让A跳」才算
        assertNull(router.ruleTrack("bad 唱歌", cast, whitelist))
        assertTrue(router.ruleTrack("让A跳", cast, whitelist) == null) // 有演员无曲目→降 LLM
        assertEquals(0, fake.calls) // ruleTrack 是纯函数不触发调用
        // 显示名包含匹配
        val hit = router.route("小暖跳舞 Doing The Shuffling Dance", cast, whitelist)
        assertTrue(hit is RequestDecision.Insert && (hit as RequestDecision.Insert).act.performer == "A")
    }

    @Test
    fun `规则档单缺信息不插幕交给LLM补全`() = runTest {
        val router = llmRouter(FakeLlm())
        assertNull(router.ruleTrack("让A跳一段舞", cast, whitelist)) // 只有演员
        assertNull(router.ruleTrack("来一段 Bboy Hip Hop", cast, whitelist)) // 只有曲目
    }

    @Test
    fun `规则档未命中且LLM档未开启时拒绝`() = runTest {
        val (router, fake) = ruleRouter()
        val d = router.route("让A跳一段街舞", cast, whitelist)
        assertTrue(d is RequestDecision.Reject)
        assertEquals(0, fake.calls) // policy=Rule：LLM 通道根本不存在
    }

    // ── LLM 档：命中与白名单双向校验 ─────────────────────────────────────

    @Test
    fun `LLM命中插幕且clip归一成白名单路径`() = runTest {
        val fake = FakeLlm(reply = "好的！$goodReply 完毕")
        val router = llmRouter(fake)
        val d = router.route("让B跳鬼步舞", cast, whitelist)
        assertTrue(d is RequestDecision.Insert)
        d as RequestDecision.Insert
        assertEquals("llm", d.track)
        assertEquals("B", d.act.performer)
        assertEquals("animations/08_舞蹈与表演/Doing The Shuffling Dance.vrma", d.act.clip)
        assertEquals("下面有请小柔带来鬼步舞！", d.act.line)
        assertEquals("点播鬼步舞", d.reason)
        assertEquals(1, fake.calls)
        assertEquals(1, router.llmCalls)
        assertEquals(0, router.llmFails)
        assertEquals(10, router.tokPrompt)
        assertEquals(5, router.tokCompletion)
    }

    @Test
    fun `LLM发明演员被白名单拒绝`() = runTest {
        val reply = "{\"performer\":\"C\",\"clip\":\"08_舞蹈与表演/Doing The Shuffling Dance\"}"
        val router = llmRouter(FakeLlm(reply))
        val d = router.route("让C跳", cast, whitelist)
        assertTrue(d is RequestDecision.Reject)
        assertEquals(1, router.llmFails)
    }

    @Test
    fun `LLM发明曲目被白名单拒绝`() = runTest {
        val reply = "{\"performer\":\"A\",\"clip\":\"animations/09_x/不存在.vrma\"}"
        val router = llmRouter(FakeLlm(reply))
        val d = router.route("让A跳街舞", cast, whitelist)
        assertTrue(d is RequestDecision.Reject)
        assertEquals(1, router.llmFails)
    }

    @Test
    fun `LLM垃圾输出拒绝且固定台本语义`() = runTest {
        val router = llmRouter(FakeLlm(reply = "我觉得今天天气不错，大家说是不是"))
        val d = router.route("来点气氛", cast, whitelist)
        assertTrue(d is RequestDecision.Reject)
        assertEquals(1, router.llmFails)
    }

    @Test
    fun `LLM空performer输出拒绝`() = runTest {
        val reply = "{\"performer\":\"\",\"clip\":\"\",\"reason\":\"无合适曲目\"}"
        val d = llmRouter(FakeLlm(reply)).route("来一段默剧", cast, whitelist)
        assertTrue(d is RequestDecision.Reject)
    }

    @Test
    fun `LLM网络失败拒绝`() = runTest {
        val router = llmRouter(FakeLlm(boom = true))
        val d = router.route("让A跳街舞", cast, whitelist)
        assertTrue(d is RequestDecision.Reject)
        assertEquals(1, router.llmFails)
    }

    @Test
    fun `LLM超时拒绝不留悬挂等待`() = runTest {
        val router = llmRouter(FakeLlm(hang = true), timeoutMs = 100)
        val d = router.route("让A跳街舞", cast, whitelist)
        assertTrue(d is RequestDecision.Reject)
        assertEquals(1, router.llmFails)
    }

    @Test
    fun `缺Key时LLM档拒绝并提示配置`() = runTest {
        val fake = FakeLlm(reply = goodReply)
        val router = DanceRequestRouter(RequestPolicy.Llm(fake, { null }))
        val d = router.route("让A跳街舞", cast, whitelist)
        assertTrue(d is RequestDecision.Reject)
        assertEquals(0, fake.calls) // 缺 Key 在调用前就短路
    }

    // ── 串场词：截断与润色 ───────────────────────────────────────────────

    @Test
    fun `LLM的line超长截断到硬上限`() = runTest {
        val longLine = "一二三四五六七八九十".repeat(4) // 40 字
        val reply = "{\"performer\":\"A\",\"clip\":\"08_舞蹈与表演/Bboy Hip Hop Variation One\"," +
            "\"line\":\"$longLine\"}"
        val d = llmRouter(FakeLlm(reply)).route("让A跳街舞", cast, whitelist)
        assertTrue(d is RequestDecision.Insert)
        assertEquals(DanceRequestRouterLineMax, (d as RequestDecision.Insert).act.line?.length)
    }

    @Test
    fun `润色命中返回变体失败返回null`() = runTest {
        val router = llmRouter(FakeLlm(reply = " 接下来，把舞台交给小暖！ "))
        assertEquals("接下来，把舞台交给小暖！", router.polishLine("小暖", "下面有请A"))
        // 关闭（policy=Rule）→ null 用原文
        val ruleOnly = DanceRequestRouter(RequestPolicy.Rule)
        assertNull(ruleOnly.polishLine("小暖", "下面有请A"))
    }

    @Test
    fun `润色LLM失败降级null`() = runTest {
        val router = llmRouter(FakeLlm(boom = true))
        assertNull(router.polishLine("小暖", "下面有请A"))
        assertEquals(1, router.llmFails)
    }

    // ── 词表内部件 ───────────────────────────────────────────────────────

    @Test
    fun `曲目匹配取最长命中且过短名不参与`() = runTest {
        val router = llmRouter(FakeLlm())
        val wide = whitelist + "animations/08_舞蹈与表演/Bboy.vrma" // 短名 + 长名同前缀
        assertEquals(
            "animations/08_舞蹈与表演/Bboy Hip Hop Variation One.vrma",
            router.matchClip("来一段 Bboy Hip Hop Variation One 吧", wide),
        )
        assertNull("2 字符短名不参与", router.matchClip("跳个 bo 舞", setOf("animations/x/Bo.vrma")))
    }

    @Test
    fun `LLM名册包含显示名与曲目清单`() = runTest {
        val router = llmRouter(FakeLlm())
        val messages = router.buildMessages("让A跳街舞", cast, whitelist)
        assertEquals(2, messages.size)
        assertEquals(ChatRole.SYSTEM, messages[0].role)
        val user = messages[1].content
        assertTrue(user.contains("- A（小暖）"))
        assertTrue(user.contains("- B（小柔）"))
        assertTrue(user.contains("08_舞蹈与表演/Bboy Hip Hop Variation One"))
        assertTrue(user.contains("「让A跳街舞」"))
        assertFalse("白名单路径不该带 animations/ 前缀喂给模型", user.contains("animations/"))
    }

    private companion object {
        const val DanceRequestRouterLineMax = 30
    }
    // ── P2：点播特效走导播链 ─────────────────────────────────────────────

    private val fxWhitelist = setOf("magic_circle", "shield", "stage_lights")

    @Test
    fun `LLM点播特效命中插fx幕`() = runTest {
        val reply = "{\"performer\":\"\",\"clip\":\"\",\"fx\":\"magic_circle\",\"reason\":\"观众要魔法\"}"
        val fake = FakeLlm(reply = reply)
        val router = llmRouter(fake)
        val d = router.route("来个魔法阵", cast, whitelist, fxWhitelist)
        assertTrue(d is RequestDecision.Insert)
        d as RequestDecision.Insert
        assertEquals("llm", d.track)
        assertEquals("fx", d.act.type)
        assertEquals("magic_circle", d.act.fx)
        assertNull(d.act.clip)
        assertEquals("观众要魔法", d.reason)
    }

    @Test
    fun `LLM发明特效被fx白名单拒绝`() = runTest {
        val reply = "{\"performer\":\"\",\"clip\":\"\",\"fx\":\" supernova_firework \"}"
        val fake = FakeLlm(reply = reply)
        val d = llmRouter(fake).route("放个超新星烟花", cast, whitelist, fxWhitelist)
        assertTrue(d is RequestDecision.Reject)
    }

    @Test
    fun `特效清单为空时fx输出拒绝且user消息不含特效段`() = runTest {
        val router = llmRouter(FakeLlm())
        val msgs = router.buildMessages("点播", cast, whitelist, fxWhitelist = emptySet())
        assertFalse(msgs.last().content.contains("特效清单"))
        val reply = "{\"fx\":\"magic_circle\"}"
        val d = llmRouter(FakeLlm(reply = reply)).route("来个魔法阵", cast, whitelist, fxWhitelist = emptySet())
        assertTrue(d is RequestDecision.Reject)
    }

    @Test
    fun `clip与fx同时给出优先跳舞`() = runTest {
        val reply = "{\"performer\":\"A\",\"clip\":\"08_舞蹈与表演/Bboy Hip Hop Variation One\",\"fx\":\"shield\"}"
        val d = llmRouter(FakeLlm(reply = reply)).route("一边跳街舞一边开护盾", cast, whitelist, fxWhitelist)
        assertTrue(d is RequestDecision.Insert)
        d as RequestDecision.Insert
        assertEquals("dance", d.act.type)
        assertEquals("A", d.act.performer)
        assertNull(d.act.fx)
    }

    @Test
    fun `特效清单出现在user消息`() {
        val router = llmRouter(FakeLlm())
        val msgs = router.buildMessages("点播", cast, whitelist, fxWhitelist)
        assertTrue(msgs.last().content.contains("特效清单"))
        assertTrue(msgs.last().content.contains("stage_lights"))
    }
}
