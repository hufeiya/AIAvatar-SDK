package com.neethu.aiavatar_sdk

import android.content.SharedPreferences

/**
 * 拟人感设置（呼吸/视线/眨眼的开关与参数）。持久化到 demo_settings；
 * 变更经 [com.neethu.orchestrator.session.AvatarSession.applyLivenessSettings]
 * 实时生效，不重建会话。
 */
data class MotionSettings(
    /** 呼吸开关：胸肩起伏。 */
    val breathEnabled: Boolean = true,
    /** 呼吸幅度倍率（1 = 设计幅度，0~2）。 */
    val breathAmplitude: Float = 1.0f,
    /** 呼吸频率（次/分，8~30）。 */
    val breathRateBpm: Float = 15.0f,
    /** 视线微动（saccade）开关：注视点自然游移。 */
    val saccadeEnabled: Boolean = true,
    /** 视线抖动幅度（世界单位，0~0.25）。 */
    val saccadeJitter: Float = 0.08f,
    /** 眨眼开关。 */
    val blinkEnabled: Boolean = true,
    /** 眨眼平均间隔（秒，1~8）。 */
    val blinkIntervalS: Float = 3.5f,
)

private const val KEY_MOTION_BREATH_ENABLED = "motion_breath_enabled"
private const val KEY_MOTION_BREATH_AMPLITUDE = "motion_breath_amplitude"
private const val KEY_MOTION_BREATH_RATE = "motion_breath_rate_bpm"
private const val KEY_MOTION_SACCADE_ENABLED = "motion_saccade_enabled"
private const val KEY_MOTION_SACCADE_JITTER = "motion_saccade_jitter"
private const val KEY_MOTION_BLINK_ENABLED = "motion_blink_enabled"
private const val KEY_MOTION_BLINK_INTERVAL = "motion_blink_interval_s"

fun SharedPreferences.loadMotionSettings(): MotionSettings = MotionSettings(
    breathEnabled = getBoolean(KEY_MOTION_BREATH_ENABLED, true),
    breathAmplitude = getFloat(KEY_MOTION_BREATH_AMPLITUDE, 1.0f),
    breathRateBpm = getFloat(KEY_MOTION_BREATH_RATE, 15.0f),
    saccadeEnabled = getBoolean(KEY_MOTION_SACCADE_ENABLED, true),
    saccadeJitter = getFloat(KEY_MOTION_SACCADE_JITTER, 0.08f),
    blinkEnabled = getBoolean(KEY_MOTION_BLINK_ENABLED, true),
    blinkIntervalS = getFloat(KEY_MOTION_BLINK_INTERVAL, 3.5f),
)

fun SharedPreferences.saveMotionSettings(m: MotionSettings) {
    edit()
        .putBoolean(KEY_MOTION_BREATH_ENABLED, m.breathEnabled)
        .putFloat(KEY_MOTION_BREATH_AMPLITUDE, m.breathAmplitude)
        .putFloat(KEY_MOTION_BREATH_RATE, m.breathRateBpm)
        .putBoolean(KEY_MOTION_SACCADE_ENABLED, m.saccadeEnabled)
        .putFloat(KEY_MOTION_SACCADE_JITTER, m.saccadeJitter)
        .putBoolean(KEY_MOTION_BLINK_ENABLED, m.blinkEnabled)
        .putFloat(KEY_MOTION_BLINK_INTERVAL, m.blinkIntervalS)
        .apply()
}
