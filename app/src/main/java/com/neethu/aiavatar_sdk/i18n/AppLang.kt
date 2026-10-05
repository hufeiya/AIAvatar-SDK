package com.neethu.aiavatar_sdk.i18n

import com.neethu.corelib.Lang

/**
 * 用户的界面语言偏好（设置页「语言 (Language)」下拉框的值，持久化
 * `app_language`）。[SYSTEM] 跟随系统：系统是中文（简体/繁体/各地区一律算）
 * 就用简体中文，否则用英文——这是默认值，海外用户开箱即英文。
 */
enum class AppLang {
    /** 跟随系统语言（默认）。 */
    SYSTEM,

    /** 强制简体中文。 */
    ZH,

    /** 强制 English。 */
    EN,
}

/**
 * 解析生效语言：显式偏好直接映射；[AppLang.SYSTEM] 按系统主语言判定
 * （[systemLanguage] 传 `Locale.language`，如 "zh"/"en"）。纯函数可测。
 */
fun resolveAppLang(pref: AppLang, systemLanguage: String): Lang = when (pref) {
    AppLang.ZH -> Lang.ZH
    AppLang.EN -> Lang.EN
    AppLang.SYSTEM -> Lang.fromSystemLanguage(systemLanguage)
}
