package com.neethu.companion

import com.neethu.corelib.Lang

/**
 * 语音链路错误文案（产品 v1 内联中文;demo 的 Strings i18n 目录的产品子集）。
 * VoiceRecorder/FreeSpeechController/SystemAsrController 的异常与回调文案。
 */
internal class VoiceTexts(val lang: Lang = Lang.ZH) {
    val freeSpeechAlreadyRunning: String = "自由说话已在进行中"
    val freeSpeechMinBufferFailed: String = "AudioRecord 最小缓冲获取失败"
    val micUnavailable: String = "麦克风不可用（可能被其他应用占用）"
    val recorderAlreadyRunning: String = "录音已在进行中"
    fun recorderStartFailed(message: String?): String = "录音启动失败：${message ?: "未知错误"}"
    val systemAsrAlreadyRunning: String = "系统语音识别已在进行中"
    val systemAsrUnavailable: String = "本机没有系统语音识别服务（需要 Google 服务或厂商语音服务）"
    fun systemAsrStopped(finalError: String): String = "连续失败已停止（最后：$finalError），请重开或改用云端识别"

    fun systemAsrErrorMessage(code: Int): String = when (code) {
        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别网络超时"
        android.speech.SpeechRecognizer.ERROR_NETWORK -> "识别服务网络错误"
        android.speech.SpeechRecognizer.ERROR_AUDIO -> "识别音频录制错误"
        android.speech.SpeechRecognizer.ERROR_SERVER -> "识别服务端错误"
        android.speech.SpeechRecognizer.ERROR_CLIENT -> "识别客户端错误"
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到说话"
        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "没有匹配到语音内容"
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        else -> "识别错误(code=$code)"
    }
}
