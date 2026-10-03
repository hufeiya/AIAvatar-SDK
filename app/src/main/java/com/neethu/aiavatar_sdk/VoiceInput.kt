package com.neethu.aiavatar_sdk

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 对话输入三模式（互斥，不能混用；左上角下拉框切换，持久化）：
 * - [MANUAL] 手动打字：完整 demo UI——全部 FAB 面板按钮可见 + 打字输入；
 * - [TEXT]   打字输入：隐藏所有界面按钮，只留输入条（打字）；
 * - [VOICE]  语音模式：隐藏所有界面按钮，只留按住说话 + 字幕。
 */
enum class InputMode(val label: String) {
    MANUAL("手动打字"),
    TEXT("打字输入"),
    VOICE("语音模式"),
}

/** 从文件扩展名推 ASR multipart 所需的 MIME（[VoiceRecorder] 产物是 m4a）。 */
fun mimeForFileName(name: String): String = when (File(name).extension.lowercase()) {
    "m4a", "mp4" -> "audio/mp4"
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "amr" -> "audio/amr"
    "ogg", "opus" -> "audio/ogg"
    "webm" -> "audio/webm"
    "flac" -> "audio/flac"
    else -> "application/octet-stream"
}

/**
 * 按住说话的 MediaRecorder 封装（任务 4）：AAC 编码、单声道 16 kHz、
 * MPEG_4（.m4a）容器，落 cacheDir，识别完成后即删。
 *
 * 选型依据：m4a 是 OpenAI /audio/transcriptions 的官方格式之一（硅基流动
 * 同协议兼容）；16 kHz 单声道足够语音识别且文件小；AMR 音质差、采样率
 * 只有 8k，MediaRecorder 又没有 mp3 编码器，故 AAC/m4a 最稳。
 */
class VoiceRecorder(private val context: Context) {

    private val cacheDir = context.cacheDir
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /** 开始录音到新临时文件；麦克风被占等失败时抛 [IllegalStateException]。 */
    fun start(): File {
        check(recorder == null) { "录音已在进行中" }
        val file = File(cacheDir, "voice_input_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setAudioChannels(1)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (t: Throwable) {
            r.release()
            file.delete()
            throw IllegalStateException("录音启动失败：${t.message}", t)
        }
        recorder = r
        outFile = file
        return file
    }

    /**
     * 停止并返回录音文件；按住太短/无有效采样（`stop()` 抛 RuntimeException）
     * 返回 null 并清理现场。
     */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val file = outFile
        outFile = null
        return try {
            r.stop()
            file
        } catch (_: RuntimeException) {
            file?.delete()
            null
        } finally {
            r.release()
        }
    }

    /** 停止（如还在录）并删除产物。 */
    fun cancel() {
        stop()?.delete()
    }
}
