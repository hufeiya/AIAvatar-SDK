package com.neethu.aiavatar_sdk.multiavatar.show

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * 团播背景音乐（docs/multi-dance-feasibility.md §7-P1）：assets 曲目的
 * MediaPlayer 薄封装——start(loop)/stop/duck 两档音量/onError 兜底行。
 *
 * 与 TTS 并存靠系统混音器天然多路（§3.3），基线音量压在 TTS 之下
 * （[MusicActions.BASE_VOLUME]）；duck 档给手动 `music action=duck` 幕。
 * 音频焦点不申请（§4.8：自家 app 前台播放，v1 不引入焦点管理）。
 *
 * 全部方法只从 Main 线程调（导演协程/生命周期回调都在 Main）；本地 assets
 * 的 prepare 是毫秒级，同步调用换确定性。
 */
internal class MusicPlayer(context: Context, private val onLog: (String) -> Unit) {

    private val appContext = context.applicationContext

    private var player: MediaPlayer? = null

    /** 当前曲目（null = 没在播）；resume 只恢复它。 */
    var currentAsset: String? = null
        private set

    /** 当前音量档（duck/full 切换与 resume 后重放都用它）。 */
    var volume: Float = MusicActions.BASE_VOLUME
        private set

    val isPlaying: Boolean get() = player?.isPlaying == true

    /** 起播（loop）：换曲先停旧的；失败兜底成日志行，演出继续（宁缺音乐不卡场）。 */
    fun start(assetPath: String): Boolean {
        stop()
        return runCatching {
            val afd = appContext.assets.openFd(assetPath)
            val mp = MediaPlayer()
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = true
            mp.setVolume(volume, volume)
            mp.setOnErrorListener { _, what, extra ->
                log("音乐播放错误 what=$what extra=$extra，停止配乐")
                stop()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            currentAsset = assetPath
            log("音乐起: $assetPath（loop，vol=$volume）")
            true
        }.getOrElse {
            log("FAIL: 音乐起播 $assetPath → ${it.message}")
            releasePlayer()
            false
        }
    }

    /** 停播并释放（幂等）。 */
    fun stop() {
        currentAsset = null
        releasePlayer()
    }

    /** 音量两档切换（duck=0.35 / full=基线）；没在播也记档，下次起播生效。 */
    fun setVolume(volume: Float, tag: String) {
        this.volume = volume
        runCatching { player?.setVolume(volume, volume) }
        log("音乐音量 → $tag($volume)")
    }

    /** Activity onStop：暂停（保留曲目与进度，onStart 恢复——风险 9 的最低语义）。 */
    fun pause() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            log("音乐暂停（onStop）")
        }
    }

    /** Activity onStart：有曲目且未在播则续播。 */
    fun resume() {
        val mp = player ?: return
        if (!mp.isPlaying) {
            runCatching { mp.start(); log("音乐恢复（onStart）") }
                .onFailure { log("音乐恢复失败: ${it.message}") }
        }
    }

    /** 彻底关闭（控制器 close 时）。 */
    fun release() {
        currentAsset = null
        releasePlayer()
    }

    private fun releasePlayer() {
        runCatching {
            player?.let { it.stop(); it.release() }
        }
        player = null
    }

    private fun log(msg: String) {
        // 只走 onLog（控制器统一 Log.i+面板回显），避免双重日志
        onLog(msg.take(120))
    }
}
