package com.neethu.companion

import android.content.Context
import android.content.SharedPreferences

/**
 * 产品独立的偏好存储（§6.3 独立身份）：与 demo 的 demo_settings 分文件，
 * 拆库后随产品走。拷自 demo 的库类（CardLibrary/LorebookLibrary）都经
 * [PREFS_NAME] 取同一文件。
 */
internal const val PREFS_NAME = "companion_settings"

/** G10 问候后台推送的偏好键与默认值。 */
internal object GreetingNotifyPrefs {
    const val KEY_ENABLED = "greeting_notify_enabled"
    const val KEY_LAST_NOTIFY_MS = "greeting_notify_last_ms"
    const val KEY_DAY_STAMP = "greeting_notify_day"
    const val KEY_DAY_COUNT = "greeting_notify_day_count"
    const val KEY_LAST_FOREGROUND_MS = "app_last_foreground_ms"
    const val KEY_PENDING_GREETING = "pending_notification_greeting"

    /** 后台通知每日上限（比前台问候的 3 更保守——锁屏触达更打扰）。 */
    const val DAILY_CAP = 1

    /** 两次后台通知的最小间隔（前台问候是 25min；后台 4h 起）。 */
    const val MIN_INTERVAL_MS = 4 * 60 * 60 * 1000L

    /** 前台宽限：刚用过 app 就不推（前台调度链正在工作）。 */
    const val FOREGROUND_GRACE_MS = 10 * 60 * 1000L

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun recordNotified(prefs: SharedPreferences, nowMs: Long) {
        val day = nowMs / DAY_MS
        val sameDay = prefs.getLong(KEY_DAY_STAMP, -1) == day
        prefs.edit()
            .putLong(KEY_LAST_NOTIFY_MS, nowMs)
            .putLong(KEY_DAY_STAMP, day)
            .putInt(KEY_DAY_COUNT, if (sameDay) prefs.getInt(KEY_DAY_COUNT, 0) + 1 else 1)
            .apply()
    }

    fun dayCount(prefs: SharedPreferences, nowMs: Long): Int =
        if (prefs.getLong(KEY_DAY_STAMP, -1) == nowMs / DAY_MS) prefs.getInt(KEY_DAY_COUNT, 0) else 0

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}

internal fun companionPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
