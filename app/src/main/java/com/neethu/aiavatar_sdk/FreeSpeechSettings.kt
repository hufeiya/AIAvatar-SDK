package com.neethu.aiavatar_sdk

import android.content.SharedPreferences

/**
 * 自由说话灵敏度设置（设置页「自由说话」区，滑条/输入均可调）。持久化到
 * demo_settings；变更经 [FreeSpeechController.applyTuning] 对采音中的 VAD
 * 实时生效，不需重启聆听。默认值与 [SpeechVad] 构造默认一致（2026-10 从
 * 0.055/0.14 调低：一臂距离正常音量触不到旧门限），改一处需同步另一处。
 */
data class FreeSpeechSettings(
    /** 起音绝对门限（越低越灵敏；RMS 归一化 0..1）。 */
    val startAbsolute: Float = 0.025f,
    /** 打断门限（虚拟人说话期用户开口判打断，越低越容易打断）。 */
    val bargeAbsolute: Float = 0.10f,
    /** 句尾静默悬停毫秒数（说完停顿多久算一句话，越短切句越碎）。 */
    val hangoverMs: Float = 800f,
) {
    companion object {
        /** 滑条范围与手改 prefs 的钳制范围（共用，防止存出无效值）。 */
        val START_ABSOLUTE_RANGE = 0.010f..0.080f
        val BARGE_ABSOLUTE_RANGE = 0.05f..0.25f
        val HANGOVER_MS_RANGE = 400f..1500f

        /** 全部字段钳回合法范围（load 时兜底手改的脏值）。 */
        fun clamp(s: FreeSpeechSettings): FreeSpeechSettings = s.copy(
            startAbsolute = s.startAbsolute.coerceIn(START_ABSOLUTE_RANGE),
            bargeAbsolute = s.bargeAbsolute.coerceIn(BARGE_ABSOLUTE_RANGE),
            hangoverMs = s.hangoverMs.coerceIn(HANGOVER_MS_RANGE),
        )
    }
}

private const val KEY_FS_START_ABSOLUTE = "fs_start_absolute"
private const val KEY_FS_BARGE_ABSOLUTE = "fs_barge_absolute"
private const val KEY_FS_HANGOVER_MS = "fs_hangover_ms"

fun SharedPreferences.loadFreeSpeechSettings(): FreeSpeechSettings = FreeSpeechSettings.clamp(
    FreeSpeechSettings(
        startAbsolute = getFloat(KEY_FS_START_ABSOLUTE, 0.025f),
        bargeAbsolute = getFloat(KEY_FS_BARGE_ABSOLUTE, 0.10f),
        hangoverMs = getFloat(KEY_FS_HANGOVER_MS, 800f),
    )
)

fun SharedPreferences.saveFreeSpeechSettings(s: FreeSpeechSettings) {
    edit()
        .putFloat(KEY_FS_START_ABSOLUTE, s.startAbsolute)
        .putFloat(KEY_FS_BARGE_ABSOLUTE, s.bargeAbsolute)
        .putFloat(KEY_FS_HANGOVER_MS, s.hangoverMs)
        .apply()
}
