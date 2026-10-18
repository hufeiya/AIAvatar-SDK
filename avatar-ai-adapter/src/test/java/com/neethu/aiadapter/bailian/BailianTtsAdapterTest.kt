package com.neethu.aiadapter.bailian

import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 百炼 TTS 适配器：请求打 DashScope 原生端点（model/input.text/input.voice），
 * 响应只取 output.audio.url（现网 audio.data 恒空串）→ 下载 → 宽式 WAV 解析
 * （data size 占位值）→ 重采样 16k PCM 返回。
 */
class BailianTtsAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: BailianTtsAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = BailianTtsAdapter(
            apiKey = "sk-bl",
            endpoint = server.url("/api/v1/tts").toString(),
            throttleBackoffMs = 1, // 真实退避 1s/2s，测试用 1ms/2ms
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** 造一个「RIFF/data size 全占位」的 WAV（真机百炼产物的病理形态）。 */
    private fun placeholderSizedWav(sampleRate: Int, samples: ShortArray): ByteArray {
        val out = ByteArrayOutputStream()
        val data = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            data[2 * i] = (samples[i].toInt() and 0xFF).toByte()
            data[2 * i + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
        }
        out.write("RIFF".toByteArray())
        out.write(b(0x7F, 0xFF, 0xFF, 0xBF)) // 占位 RIFF size
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(b(16, 0, 0, 0)) // fmt chunk size
        out.write(b(1, 0)) // PCM
        out.write(b(1, 0)) // mono
        val rate = sampleRate
        out.write(b(rate and 0xFF, (rate shr 8) and 0xFF, (rate shr 16) and 0xFF, (rate shr 24) and 0xFF))
        val byteRate = rate * 2
        out.write(b(byteRate and 0xFF, (byteRate shr 8) and 0xFF, (byteRate shr 16) and 0xFF, (byteRate shr 24) and 0xFF))
        out.write(b(2, 0)) // block align
        out.write(b(16, 0)) // bits
        out.write("data".toByteArray())
        out.write(b(0x7F, 0xFF, 0xFF, 0x7F)) // 占位 data size（真机=0x7FFFFF9B 同族）
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `synthesizes via native endpoint and resamples to 16k pcm`() = runBlocking {
        val wav = placeholderSizedWav(24_000, ShortArray(24_000) { (it % 327).toShort() }) // 1s 正弦样
        val audioUrl = server.url("/fake/oss/audio.wav").toString()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"output":{"audio":{"data":"","url":"$audioUrl"},"finish_reason":"stop"},"usage":{"characters":5}}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(wav)),
        )

        val result = adapter.synthesize("你好", TtsConfig(model = "qwen3-tts-flash", voice = "Cherry"))
        assertEquals(TtsAudioFormat.RAW_PCM_16LE, result.format)
        assertEquals(16_000, result.rawPcmSampleRate)
        // 24k → 16k：1s 24000 样 → 16000 样（±1 容差）
        assertTrue(result.audio.size / 2 in 15_999..16_001)

        // 请求形态：原生协议 Bearer 鉴权 + model/input.text/input.voice
        val ttsRequest = server.takeRequest()
        assertEquals("/api/v1/tts", ttsRequest.path)
        assertEquals("Bearer sk-bl", ttsRequest.getHeader("Authorization"))
        val body = ttsRequest.body.readUtf8()
        assertTrue(body.contains("\"model\":\"qwen3-tts-flash\""))
        assertTrue(body.contains("\"text\":\"你好\""))
        assertTrue(body.contains("\"voice\":\"Cherry\""))
        // 音频下载是独立 GET
        val download = server.takeRequest()
        assertEquals("/fake/oss/audio.wav", download.path)
    }

    @Test
    fun `http error surfaces response preview`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"InvalidParameter"}"""))
        val e = assertThrows(IOException::class.java) {
            runBlocking { adapter.synthesize("你好", TtsConfig(model = "qwen3-tts-flash", voice = "Cherry")) }
        }
        assertTrue(e.message!!.contains("HTTP 400"))
        assertTrue(e.message!!.contains("InvalidParameter"))
    }

    // ── 429 限流：串行 + 退避重试（真机病理：并发 4 句撞 Throttling.RateQuota）──

    @Test
    fun `throttled generation retries with backoff and succeeds`() = runBlocking {
        // 真机病理形态：Throttling.RateQuota JSON
        val throttleBody = """{"code":"Throttling.RateQuota","message":"Requests rate limit exceeded"}"""
        server.enqueue(MockResponse().setResponseCode(429).setBody(throttleBody))
        server.enqueue(MockResponse().setResponseCode(429).setBody(throttleBody))
        val wav = placeholderSizedWav(24_000, ShortArray(2_400) { (it % 327).toShort() })
        val audioUrl = server.url("/fake/oss/audio.wav").toString()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"output":{"audio":{"data":"","url":"$audioUrl"}}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(wav)))

        val result = adapter.synthesize("你好", TtsConfig(model = "qwen3-tts-flash", voice = "Cherry"))
        assertEquals(TtsAudioFormat.RAW_PCM_16LE, result.format)
        // 首发 + 两次退避重试都在生成端点，成功后下载一次
        assertEquals(4, server.requestCount)
        assertEquals("/api/v1/tts", server.takeRequest().path)
        assertEquals("/api/v1/tts", server.takeRequest().path)
        assertEquals("/api/v1/tts", server.takeRequest().path)
        assertEquals("/fake/oss/audio.wav", server.takeRequest().path)
    }

    @Test
    fun `persistent throttling surfaces 429 after retries`() {
        val throttleBody = """{"code":"Throttling.RateQuota","message":"Requests rate limit exceeded"}"""
        repeat(BailianTtsAdapter.THROTTLE_RETRIES + 1) {
            server.enqueue(MockResponse().setResponseCode(429).setBody(throttleBody))
        }
        val e = assertThrows(IOException::class.java) {
            runBlocking { adapter.synthesize("你好", TtsConfig(model = "qwen3-tts-flash", voice = "Cherry")) }
        }
        assertTrue(e.message!!.contains("HTTP 429"))
        assertTrue(e.message!!.contains("Throttling.RateQuota"))
        assertEquals(BailianTtsAdapter.THROTTLE_RETRIES + 1, server.requestCount)
    }

    @Test
    fun `non-wav download fails with a clear error`() = runBlocking {
        val audioUrl = server.url("/fake/oss/audio.wav").toString()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"output":{"audio":{"url":"$audioUrl"}}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>gateway page</html>"))
        val e = assertThrows(IOException::class.java) {
            runBlocking { adapter.synthesize("你好", TtsConfig(model = "qwen3-tts-flash", voice = "Cherry")) }
        }
        assertTrue(e.message!!.contains("not a 16-bit PCM WAV"))
    }

    @Test
    fun `empty text is rejected before any request`() {
        assertThrows(IOException::class.java) {
            runBlocking { adapter.synthesize("  ", TtsConfig(model = "m", voice = "v")) }
        }
        assertEquals(0, server.requestCount)
    }

    // ── 纯函数：WAV 宽式解析 + 重采样 ────────────────────────────────────────
    @Test
    fun `aliyun http download url is upgraded to https without touching other hosts`() {
        // 真机病理：OSS 链是 http://，targetSdk≥28 默认禁明文流量，必须升 https
        assertEquals(
            "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/prod/a.wav?Signature=x%3D",
            BailianTtsAdapter.httpsForAliyun(
                "http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/prod/a.wav?Signature=x%3D",
            ),
        )
        // 本地注入端点（MockWebServer）不动
        assertEquals(
            "http://localhost:8080/fake/oss/audio.wav",
            BailianTtsAdapter.httpsForAliyun("http://localhost:8080/fake/oss/audio.wav"),
        )
        // 已经是 https 的不重复处理
        assertEquals(
            "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.wav",
            BailianTtsAdapter.httpsForAliyun("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.wav"),
        )
    }
    @Test
    fun `wav reader tolerates placeholder chunk sizes and downmixes stereo`() {
        val mono = placeholderSizedWav(16_000, shortArrayOf(100, -100, 200, -200))
        assertEquals(4, WavPcm16Reader.read(mono)!!.samples.size)
        assertEquals(16_000, WavPcm16Reader.read(mono)!!.sampleRate)

        // 立体声下混 = 双声道均值
        val stereoBytes = ByteArrayOutputStream()
        stereoBytes.write("RIFF".toByteArray())
        stereoBytes.write(b(0x7F, 0xFF, 0xFF, 0xBF))
        stereoBytes.write("WAVE".toByteArray())
        stereoBytes.write("fmt ".toByteArray())
        stereoBytes.write(b(16, 0, 0, 0, 1, 0, 2, 0))
        stereoBytes.write(b(0x80, 0x3E, 0, 0)) // 16000
        stereoBytes.write(b(0, 0xFA, 0, 0)) // byteRate 64000
        stereoBytes.write(b(4, 0, 16, 0))
        stereoBytes.write("data".toByteArray())
        stereoBytes.write(b(0x7F, 0xFF, 0xFF, 0x7F))
        // 两帧：L=100,R=200 → 150；L=-4,R=4 → 0
        stereoBytes.write(b(100, 0, 200, 0, 0xFC, 0xFF, 4, 0))
        val stereo = WavPcm16Reader.read(stereoBytes.toByteArray())!!
        assertEquals(2, stereo.samples.size)
        assertEquals(150.toShort(), stereo.samples[0])
        assertEquals(0.toShort(), stereo.samples[1])
    }

    @Test
    fun `resample keeps identity on same rate and scales length on 24k to 16k`() {
        val same = ShortArray(100) { it.toShort() }
        assertTrue(same === BailianTtsAdapter.resamplePcm16(same, 16_000, 16_000))
        val up = BailianTtsAdapter.resamplePcm16(ShortArray(24_000) { 1000 }, 24_000, 16_000)
        assertEquals(16_000, up.size)
        // 常量信号重采样后仍是常量（插值无漂移）
        assertTrue(up.all { it == 1000.toShort() })
    }
}
