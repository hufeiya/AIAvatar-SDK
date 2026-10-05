package com.neethu.aiavatar_sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.neethu.corelib.Lang
import com.neethu.aiavatar_sdk.i18n.Strings

/**
 * 系统内置语音识别（Android 标准 `SpeechRecognizer`）：免费、无 Key，不消耗
 * 云端 ASR 额度。识别服务由平台提供——GMS 设备走 Google 服务（海外用户的
 * 主场景），国产 ROM 走厂商服务（如小米 mibrain），都没有时 [isAvailable]
 * 为 false，调用方提示改回云端识别。
 *
 * 两条用法（与云端链路互补，同一批发送接缝）：
 *  - [startSingleShot] + 松手 [stopListening]：按住说话（松手取终稿）；
 *  - [startContinuous]：自由说话（服务自带断句，onFinal 即一句终稿；自动重启）。
 *
 * 半双工防自回声：虚拟人说话期（SPEAKING）扬声器播放的 TTS 会被麦克风收进
 * 识别器产生幽灵台词——连续模式用 [setPaused] 暂停聆听（含恢复 600ms 宽限），
 * 暂停期的终稿/部分结果一律丢弃。
 */

/** 错误码 → 用户可读消息（纯函数可测；文案表在 i18n.Strings，默认中文）。 */
internal fun systemAsrErrorMessage(code: Int, lang: Lang = Lang.ZH): String =
    Strings(lang).systemAsrErrorMessage(code)

/**
 * 连续聆听的重启策略（纯 JVM 可测）：静默类错误（没听到/没匹配）是自由说话
 * 的常态——立即重启；瞬时类错误（网络/服务端/忙/客户端）退避重启（500ms 起
 * 翻倍，5s 封顶，识别成功清零）；**权限类错误（首次使用弹出「允许使用语音
 * 识别」授权框期间会持续报 ERROR_INSUFFICIENT_PERMISSIONS）进入等待授权轮询
 * ——绝不能按致命错误销毁会话，否则系统弹窗跟着一起消失**（真机实测：HyperOS
 * mibrain 服务首用授权时，销毁识别器=弹窗撤下，用户永远点不到）；瞬时错误
 * 连续 [maxTransientStrikes] 次或剩余真致命类（音频/未知）判停交给调用方报错。
 */
class SystemAsrRestartPolicy(
    private val backoffBaseMs: Long = 500,
    private val backoffCapMs: Long = 5_000,
    private val maxTransientStrikes: Int = 5,
    /** 等待授权期间的轮询间隔（授权框由用户操作，轮询只负责「允许后自动续上」）。 */
    private val consentPollMs: Long = 2_500,
) {
    sealed interface Decision {
        data object RestartNow : Decision
        data class RestartAfter(val delayMs: Long) : Decision
        /** 授权弹窗等待中：保持会话，按 [delayMs] 轮询直到用户允许。 */
        data class WaitForConsent(val delayMs: Long) : Decision
        data object Stop : Decision
    }

    private var transientStrikes = 0

    /** 识别成功（有终稿）→ 清零连败计数。 */
    fun onResult() {
        transientStrikes = 0
    }

    fun onError(code: Int): Decision = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
            transientStrikes = 0
            Decision.RestartNow
        }
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            Decision.WaitForConsent(consentPollMs)
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        // ERROR_CLIENT 归瞬时：OEM 服务（实测小米 mibrain）在暂停/恢复切换时会把
        // 「未在聆听态就 stopListening」报成 client error，属正常半双工节奏的副作用，
        // 按致命处理会被一次切换打死（真机实测：一次 ERROR_CLIENT 即「连续失败已停止」）
        SpeechRecognizer.ERROR_CLIENT,
        -> {
            transientStrikes++
            if (transientStrikes >= maxTransientStrikes) {
                Decision.Stop
            } else {
                Decision.RestartAfter(minOf(backoffBaseMs shl (transientStrikes - 1), backoffCapMs))
            }
        }
        else -> Decision.Stop
    }

    fun reset() {
        transientStrikes = 0
    }
}

/**
 * 文本量 → 口说时长估计（毫秒）。系统识别路径没有 WAV 可测真实时长，但技能
 * 快路径（猜拳「短句=出拳信号 ≤2.5s」）需要一个时长判定——中文 ≈4 字/秒、
 * 英文 ≈3 词/秒，取两者较大值并 clamp [300ms, 10s]（单字/单词也不至于短到
 * 无意义）。纯 JVM 可测。
 */
fun estimateSpeechMs(text: String): Long {
    val t = text.trim()
    if (t.isEmpty()) return 0
    val cjk = t.count { it.code in 0x2E80..0x9FFF }
    val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
    return maxOf(cjk * 250L, words * 333L).coerceIn(300L, 10_000L)
}

/**
 * 平台识别服务可用性（控制器与设置页共用）。三重判定：
 *  1. 官方 [SpeechRecognizer.isRecognitionAvailable]；
 *  2. API 31+ 的设备端识别 [SpeechRecognizer.isOnDeviceRecognitionAvailable]；
 *  3. 兜底：`RecognitionService` 在册**且**为系统默认
 *     （Settings.Secure.VOICE_RECOGNITION_SERVICE 指向）——实测 HyperOS/Android 16
 *     上小米 mibrain 服务与默认设置都在，官方判定仍返回 false（门控误报）。
 */
fun isSystemAsrAvailable(context: Context): Boolean {
    if (SpeechRecognizer.isRecognitionAvailable(context)) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    ) {
        return true
    }
    // "voice_recognition_service" 键的公开常量在 SDK 中是 @hide，用字面量
    val default = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        ?: return false
    val services = context.packageManager.queryIntentServices(
        Intent(RecognitionService.SERVICE_INTERFACE), 0,
    )
    return services.any {
        val cn = ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
        default == cn.flattenToString() || default == cn.flattenToShortString()
    }
}

/**
 * [SpeechRecognizer] 薄封装。全部公开方法要求主线程调用（API 约束），回调也
 * 都在主线程触发，UI 侧自行 scope.launch。单实例由 DemoScreen 持有，[stop]
 * 配对（切引擎/切模式/组合销毁）。
 */
class SystemAsrController(private val context: Context) {

    /**
     * 用户可读文案表（多语言支持）：错误回调与异常消息的语言。MainActivity 在
     * 语言设置变化时更新（实例不重建，识别会话跨语言切换保持）。
     */
    var texts: Strings = Strings(Lang.ZH)

    /**
     * 识别语言（BCP-47，如 "zh-CN"/"en-US"；null = 跟随系统默认）。多语言：
     * 显式指定让识别语言与界面语言一致（EN 界面 + 中文系统语言的设备也能
     * 正确收英文）。下一个 startListening 生效。
     */
    var languageTag: String? = null

    /** 一句终稿（[singleShot] 区分按住说话/自由说话两条处理路径）。主线程回调。 */
    var onFinal: ((text: String, singleShot: Boolean) -> Unit)? = null

    /** 实时部分结果（"听到你说话"指示）。主线程回调。 */
    var onPartial: ((text: String) -> Unit)? = null

    /** 首用授权等待：系统弹窗「允许使用语音识别」在屏幕上，用户点允许后自动续上。主线程回调。 */
    var onConsentNeeded: (() -> Unit)? = null

    /** 不可恢复错误（策略判停/单发失败/服务不可用）。主线程回调。 */
    var onError: ((message: String) -> Unit)? = null

    /** 连续聆听运行中（自由说话生命周期判定）。 */
    @Volatile
    var running: Boolean = false
        private set

    /** 按住说话的单发监听中（松手 [stopListening] 后等终稿）。 */
    @Volatile
    var singleShot: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var policy = SystemAsrRestartPolicy()
    private var listening = false
    private var paused = false
    private var waitingConsent = false

    /** 平台识别服务是否可用（GMS=Google，国产 ROM=厂商服务）。 */
    fun isAvailable(): Boolean = isSystemAsrAvailable(context)

    /** 自由说话：连续聆听，服务断句即一句（onFinal），自动重启。主线程调用。 */
    fun startContinuous() {
        startInternal(singleShotMode = false)
    }

    /** 按住说话：单发监听，松手 [stopListening] 取终稿。主线程调用。 */
    fun startSingleShot() {
        startInternal(singleShotMode = true)
    }

    private fun startInternal(singleShotMode: Boolean) {
        check(!running && !singleShot) { texts.systemAsrAlreadyRunning }
        check(isAvailable()) { texts.systemAsrUnavailable }
        ensureRecognizer()
        policy.reset()
        paused = false
        waitingConsent = false
        listening = false
        if (singleShotMode) singleShot = true else running = true
        startListening()
        Log.i(TAG, "started mode=${if (singleShotMode) "single-shot" else "continuous"}")
    }

    /** 按住松手：结束当前单发监听，终稿经 [onFinal] 异步到达。主线程调用。 */
    fun stopListening() {
        if (!singleShot) return
        mainHandler.post {
            runCatching { recognizer?.stopListening() }
        }
    }

    /**
     * 连续模式的半双工暂停（虚拟人 SPEAKING 期）。恢复带 [resumeGraceMs]
     * 宽限——扬声器尾音衰减期不开麦，防止残留触发幽灵台词。
     */
    fun setPaused(p: Boolean, resumeGraceMs: Long = 600) {
        if (paused == p) return
        paused = p
        mainHandler.removeCallbacksAndMessages(RESUME_TOKEN)
        if (p) {
            mainHandler.post {
                runCatching { recognizer?.stopListening() }
                listening = false
            }
        } else if (running && !singleShot) {
            mainHandler.postDelayed({ if (running && !paused && !listening) startListening() },
                RESUME_TOKEN, resumeGraceMs)
        }
    }

    /** 完全停止并释放（自由说话关/切引擎/销毁）。主线程调用。 */
    fun stop() {
        running = false
        singleShot = false
        paused = false
        waitingConsent = false
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            recognizer?.let { runCatching { it.destroy() } }
            recognizer = null
        }
        Log.i(TAG, "stopped")
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
        }
    }

    private fun startListening() {
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 识别语言跟随界面语言（languageTag 非空时）；null = 系统默认
            // （海外用户=其设备语言）
            languageTag?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it)
            }
        }
        listening = runCatching { r.startListening(intent) }
            .onFailure { Log.w(TAG, "startListening failed: ${it.message}") }
            .isSuccess
    }

    private fun handleFinal(text: String) {
        val wasSingle = singleShot
        singleShot = false
        listening = false
        if (paused && !wasSingle) return // 暂停期(防自回声)丢弃
        policy.onResult()
        onFinal?.invoke(text.trim(), wasSingle)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening = true
            if (waitingConsent) {
                // 授权已通过（服务接单了）——退出等待授权态，正常聆听
                waitingConsent = false
                Log.i(TAG, "consent granted, recognition started")
            }
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (paused && !singleShot) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) onPartial?.invoke(text.trim())
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            handleFinal(text)
            maybeRestart()
        }

        override fun onError(error: Int) {
            listening = false
            if (singleShot) {
                // 单发（按住说话）：错误直接进回调（"没有听到说话"等），路径终止
                singleShot = false
                onError?.invoke(texts.systemAsrErrorMessage(error))
                return
            }
            when (val d = policy.onError(error)) {
                SystemAsrRestartPolicy.Decision.RestartNow -> maybeRestart()
                is SystemAsrRestartPolicy.Decision.RestartAfter ->
                    mainHandler.postDelayed({ maybeRestart() }, d.delayMs)
                is SystemAsrRestartPolicy.Decision.WaitForConsent -> {
                    // 首用授权弹窗在屏幕上：会话绝不能销毁（销毁=弹窗撤下），
                    // 提示一次后轮询，用户点「允许」的下一个重试自然续上
                    if (!waitingConsent) {
                        waitingConsent = true
                        Log.i(TAG, "waiting for user consent (dialog on screen)")
                        onConsentNeeded?.invoke()
                    }
                    mainHandler.postDelayed(
                        { if (running && !paused && !listening) startListening() },
                        d.delayMs,
                    )
                }
                SystemAsrRestartPolicy.Decision.Stop -> {
                    running = false
                    onError?.invoke(
                        texts.systemAsrStopped(texts.systemAsrErrorMessage(error))
                    )
                }
            }
        }

        /** 连续模式按需重启；暂停期不重启（恢复时 setPaused(false) 会开）。 */
        private fun maybeRestart() {
            if (running && !paused && !listening) startListening()
        }
    }

    companion object {
        private const val TAG = "SystemAsr"
        private const val RESUME_TOKEN = 1
    }
}
