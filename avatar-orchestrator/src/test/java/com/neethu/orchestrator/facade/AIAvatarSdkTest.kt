package com.neethu.orchestrator.facade

import com.neethu.corelib.AvatarController
import com.neethu.orchestrator.history.InMemoryConversationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AIAvatarSdk] 门面：配置解析落位（llmConfig/ttsConfig 装配正本）、身份
 * 判据（全量 config 相等即 no-op）、卡片人设跨重建重放。JVM 单测注入
 * storeFactory 兜住默认 Room 路径；不触 modelReady=true（FaceDriver 走
 * Choreographer，渲染侧不在 JVM 单测范围）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIAvatarSdkTest {

    /** 门面用的 scope 都收在这里：tearDown 先 cancel 再 resetMain，防泄漏的
     *  播放队列写线程在 Main 卸载后异步派发崩溃（污染下一个测试类的全局
     *  异常处理器，AudioTrackPlaybackQueueTest 吃过这个锅）。 */
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        // AvatarSession 的播放回调经 Dispatchers.Main.immediate（同 AvatarSessionSpeakTest）
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    private fun newSdk(controller: AvatarController = AvatarController()): AIAvatarSdk =
        AIAvatarSdk(
            scope = CoroutineScope(UnconfinedTestDispatcher()).also { scopes += it },
            controller = controller,
            appContext = null,
            storeFactory = { _, _ -> InMemoryConversationStore() },
        )

    private val sfConfig = AIAvatarSdk.ChatConfig(
        provider = ChatProvider.SILICONFLOW,
        apiKey = "sk-1",
        ttsEngine = TtsEngine.EDGE,
    )

    @Test
    fun `incomplete config is rejected and leaves any session untouched`() {
        val sdk = newSdk()
        assertNull(sdk.configure(AIAvatarSdk.ChatConfig(apiKey = ""))) // 缺 Key
        assertNull(sdk.configure(sfConfig.copy(apiKey = " "))) // 空白 Key 同判
        assertNull(sdk.session)
        assertNull(sdk.config)

        val built = sdk.configure(sfConfig)
        assertNotNull(built)
        // 不完整配置再来一次：返回 null，已有会话不被关闭
        assertNull(sdk.configure(AIAvatarSdk.ChatConfig()))
        assertSame(built, sdk.session)
    }

    @Test
    fun `configure assembles resolved llm and tts configs`() {
        val sdk = newSdk()
        val session = sdk.configure(
            AIAvatarSdk.ChatConfig(
                provider = ChatProvider.SILICONFLOW,
                apiKey = "sk-1",
                // 清单外值 → 落默认（跨服务商防泄漏语义在门面同样生效）
                llmModel = "doubao-seed-2-0-mini-260428",
                ttsEngine = TtsEngine.EDGE,
                voice = "不在目录的音色",
            ),
        )!!
        val llm = session.llmConfig!!
        assertEquals("https://api.siliconflow.cn/v1", llm.baseUrl)
        assertEquals("Qwen/Qwen3-VL-32B-Instruct", llm.model) // 目录外残留 → SF 默认（视觉优先）
        assertEquals("sk-1", llm.apiKey)
        // 关思考方言按 服务商+模型族 分发（新默认是 Qwen3-VL → enable_thinking=false）
        assertEquals(
            "false",
            (llm.extraBody!!["enable_thinking"] as kotlinx.serialization.json.JsonPrimitive).content,
        )

        val tts = session.ttsConfig!!
        assertEquals(AIAvatarSdk.ChatConfig.EDGE_TTS_MODEL, tts.model)
        assertEquals("zh-CN-XiaoxiaoNeural", tts.voice) // 目录外残留 → 默认音色
        assertEquals("mp3", tts.responseFormat)
    }

    @Test
    fun `openrouter tts rides the mp3 path and volcano needs its own key`() {
        val sdk = newSdk()
        val orSession = sdk.configure(
            AIAvatarSdk.ChatConfig(
                provider = ChatProvider.OPENROUTER,
                apiKey = "sk-or-1",
                ttsEngine = TtsEngine.OPENAI_COMPATIBLE,
            ),
        )!!
        assertEquals("mp3", orSession.ttsConfig!!.responseFormat)
        assertEquals("mistralai/voxtral-mini-tts-2603", orSession.ttsConfig!!.model)
        assertEquals("en_paul_neutral", orSession.ttsConfig!!.voice)

        // 火山「同商共用一把 key」语义不成立：语音合成那把（豆包）缺失 = 未配置
        assertNull(
            sdk.configure(
                AIAvatarSdk.ChatConfig(provider = ChatProvider.VOLCANO, apiKey = "ark-1"),
            ),
        )
        val volcSession = sdk.configure(
            AIAvatarSdk.ChatConfig(
                provider = ChatProvider.VOLCANO,
                apiKey = "ark-1",
                ttsApiKey = "volc-tts-1",
            ),
        )!!
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", volcSession.llmConfig!!.baseUrl)
        // doubao-seed 系默认模型 → thinking 关闭参数进请求体
        assertEquals(
            "disabled",
            ((volcSession.llmConfig!!.extraBody!!["thinking"] as kotlinx.serialization.json.JsonObject)["type"]
                as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }

    @Test
    fun `identity is full config equality - change rebuilds, same is noop`() {
        val sdk = newSdk()
        val first = sdk.configure(sfConfig)!!
        assertSame(first, sdk.configure(sfConfig)) // 身份未变 → no-op
        assertSame(first, sdk.session)

        // 任一字段变化（音色）→ 重建
        val second = sdk.configure(sfConfig.copy(voice = "zh-CN-YunxiNeural"))!!
        assertNotSame(first, second)
        assertSame(second, sdk.session)
        assertSame(second, sdk.config!!.let { sdk.configure(it) }) // 新身份稳定

        // 换上下文 id = 换一段历史，也是重建
        val third = sdk.configure(sfConfig.copy(voice = "zh-CN-YunxiNeural", contextId = "ctx-2"))!!
        assertNotSame(second, third)
    }

    @Test
    fun `character card survives config-change rebuilds`() {
        val sdk = newSdk()
        sdk.configure(sfConfig)
        val card = com.neethu.orchestrator.card.CharacterCard(
            name = "星野",
            description = "测试人设",
        )
        sdk.setCharacterCard(card)
        // 人设已应用到当前会话（assemble 拼进 systemPrompt）
        assertTrue(sdk.session!!.systemPrompt.contains("测试人设"))
        // 重建后卡片自动重放（门面持有，不依赖调用方逐次重设）
        val rebuilt = sdk.configure(sfConfig.copy(voice = "zh-CN-YunxiNeural"))!!
        assertTrue(rebuilt.systemPrompt.contains("测试人设"))
        // clear 后重建的会话不再带人设（systemPrompt 为空）
        sdk.clearCharacterCard()
        val bare = sdk.configure(sfConfig.copy(contextId = "ctx-3"))!!
        assertEquals("", bare.systemPrompt)
    }

    @Test
    fun `custom endpoint passes model through and skips provider quirks`() {
        val sdk = newSdk()
        val session = sdk.configure(
            AIAvatarSdk.ChatConfig(
                provider = ChatProvider.SILICONFLOW,
                baseUrl = "https://my-proxy.example.com/v1",
                apiKey = "sk-x",
                llmModel = "my-fine-tune",
                ttsEngine = TtsEngine.OPENAI_COMPATIBLE,
                ttsModel = "my-tts",
                voice = "my-voice",
            ),
        )!!
        assertEquals("https://my-proxy.example.com/v1", session.llmConfig!!.baseUrl)
        assertEquals("my-fine-tune", session.llmConfig!!.model) // 透传，不落 SF 默认
        assertNull(session.llmConfig!!.extraBody) // 自定义端点不做服务商假设
        assertEquals("my-tts", session.ttsConfig!!.model)
        assertEquals("my-voice", session.ttsConfig!!.voice)

        // 自定义端点缺模型名 = 未配置（无目录可落默认）
        assertFalse(
            AIAvatarSdk.ChatConfig(
                baseUrl = "https://my-proxy.example.com/v1",
                apiKey = "sk-x",
                llmModel = "",
                ttsModel = "my-tts",
            ).isConfigured,
        )
    }

    @Test
    fun `close resets facade and session factory stays reusable`() {
        val sdk = newSdk()
        val first = sdk.configure(sfConfig)!!
        sdk.close()
        assertNull(sdk.session)
        assertNull(sdk.config)
        val second = sdk.configure(sfConfig)!!
        assertNotSame(first, second)
    }

    @Test
    fun `tts key routing mirrors the demo prefs semantics`() {
        // 同商（非火山）：TTS Key 缺省 = 大模型 Key
        val sf = AIAvatarSdk.ChatConfig(provider = ChatProvider.SILICONFLOW, apiKey = "sk-1")
        assertEquals("sk-1", sf.ttsKeyResolved)
        assertTrue(sf.isConfigured)

        // 显式 TTS 服务商 + 显式 Key
        val mixed = sf.copy(ttsProvider = ChatProvider.VOLCANO, ttsApiKey = "volc-tts")
        assertEquals("volc-tts", mixed.ttsKeyResolved)
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", mixed.ttsBaseUrl)
        // 异商缺 Key = 未配置
        assertFalse(mixed.copy(ttsApiKey = null).isConfigured)
        // 异商但 TTS 切 Edge（免费）：不需要任何 TTS Key
        assertTrue(sf.copy(ttsEngine = TtsEngine.EDGE, ttsProvider = ChatProvider.VOLCANO).isConfigured)
    }

    @Test
    fun `deepseek without a tts endpoint falls back to the edge engine`() {
        // DeepSeek 官方无 TTS（/audio/speech 实测 404）：OpenAI 兼容引擎选到它
        // （同商跟随）→ ttsEngineResolved 整体改道 Edge，无 TTS Key 也就绪，
        // 模型/音色全按 Edge 解析（占位模型 + 目录默认音色）
        val ds = AIAvatarSdk.ChatConfig(provider = ChatProvider.DEEPSEEK, apiKey = "sk-ds")
        assertEquals(TtsEngine.EDGE, ds.ttsEngineResolved)
        assertTrue(ds.ttsReady)
        assertTrue(ds.isConfigured)
        assertEquals(AIAvatarSdk.ChatConfig.EDGE_TTS_MODEL, ds.resolvedTtsModel)
        assertEquals("zh-CN-XiaoxiaoNeural", ds.resolvedVoice)

        // 显式把 DeepSeek 指为 TTS 服务商：同样改道 Edge（异商也不需要 TTS Key）
        val mixed = AIAvatarSdk.ChatConfig(
            provider = ChatProvider.SILICONFLOW,
            apiKey = "sk-1",
            ttsProvider = ChatProvider.DEEPSEEK,
        )
        assertEquals(TtsEngine.EDGE, mixed.ttsEngineResolved)
        assertTrue(mixed.isConfigured)

        // 引擎显式选 Edge 原样保持；非 DeepSeek 的 OpenAI 兼容不受影响
        assertEquals(TtsEngine.EDGE, ds.copy(ttsEngine = TtsEngine.EDGE).ttsEngineResolved)
        assertEquals(
            TtsEngine.OPENAI_COMPATIBLE,
            mixed.copy(ttsProvider = ChatProvider.SILICONFLOW).ttsEngineResolved,
        )

        // 装配产物也是 Edge 形态（mp3 / Edge 占位模型），不是打向 404 端点的适配器
        val sdk = newSdk()
        val session = sdk.configure(ds)!!
        assertEquals(AIAvatarSdk.ChatConfig.EDGE_TTS_MODEL, session.ttsConfig!!.model)
        assertEquals("mp3", session.ttsConfig!!.responseFormat)
    }

    @Test
    fun `bailian assembles native tts and shares one key across llm and tts`() {
        // 百炼一把 key 通吃（chat 专属端点 + DashScope 原生 TTS + chat 形态 ASR）
        val bl = AIAvatarSdk.ChatConfig(provider = ChatProvider.BAILIAN, apiKey = "sk-bl")
        assertEquals(TtsEngine.OPENAI_COMPATIBLE, bl.ttsEngineResolved) // 有 TTS，不改道 Edge
        assertTrue(bl.isConfigured)
        assertEquals("sk-bl", bl.ttsKeyResolved) // 同商共用大模型 key
        assertEquals("qwen3.8-flash", bl.resolvedLlmModel) // 默认=视觉优先（红/蓝图实测）
        assertEquals("qwen3-tts-flash", bl.resolvedTtsModel)
        assertEquals("Cherry", bl.resolvedVoice)

        val session = newSdk().configure(bl)!!
        // TTS 走 DashScope 原生协议：适配器内部重采样 16k PCM（wLipSync 标定
        // 输入率，见 BailianTtsAdapter KDoc），配置侧同值对齐
        assertEquals("qwen3-tts-flash", session.ttsConfig!!.model)
        assertEquals("Cherry", session.ttsConfig!!.voice)
        assertEquals(16_000, session.ttsConfig!!.sampleRate)
        // 关思考方言：qwen 系 enable_thinking=false（glm 系不发）
        assertEquals(
            "false",
            (session.llmConfig!!.extraBody!!["enable_thinking"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }

    @Test
    fun `live catalog ids validate without joining session identity`() {
        // 实时目录 id（静态清单外）经 catalogLlmModels 放行
        val config = AIAvatarSdk.ChatConfig(
            provider = ChatProvider.BAILIAN,
            apiKey = "sk-bl",
            llmModel = "qwen3.9-max-future",
        )
        assertEquals("qwen3.8-flash", config.resolvedLlmModel) // 无目录注入 → 默认
        config.catalogLlmModels = setOf("qwen3.9-max-future")
        assertEquals("qwen3.9-max-future", config.resolvedLlmModel)

        // 刻意不在构造参数里（var 属性）：目录注入/刷新不进 equals/copy，
        // 进程存活期拉取缓存不会触发会话重建
        val other = config.copy()
        other.catalogLlmModels = emptySet()
        assertEquals(config, other)

        // 装配产物用注入后的模型真实发请求
        val session = newSdk().configure(config)!!
        assertEquals("qwen3.9-max-future", session.llmConfig!!.model)
    }

    @Test
    fun `persona joins the preamble and participates in identity`() {
        // 纯函数：空 = null（默认前言）；非空 = 默认前言 + persona 同条
        assertNull(personaPreamble(com.neethu.corelib.Lang.ZH, " "))
        val joined = personaPreamble(com.neethu.corelib.Lang.ZH, "你叫小柔")!!
        assertTrue(joined.contains("你叫小柔"))
        assertTrue(joined.length > "你叫小柔".length)

        // persona 参与会话身份判据：改动即重建（上下文轮换仍由 contextId 决定）
        val sdk = newSdk()
        val a = sdk.configure(sfConfig.copy(persona = "你叫小柔"))!!
        val b = sdk.configure(sfConfig.copy(persona = "你叫小刚"))!!
        assertNotSame(a, b)
        assertSame(b, sdk.configure(sfConfig.copy(persona = "你叫小刚")))
    }
}
