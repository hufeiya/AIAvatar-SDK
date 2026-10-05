package com.neethu.corelib

/**
 * 应用界面与提示词的语言（多语言支持，2026-10）。
 *
 * 全链路唯一语言枚举：corelib（[CameraShot] 标签）、orchestrator（提示词目录
 * `com.neethu.orchestrator.i18n.PromptTexts`）与 app（UI 文案表）共用。
 * 纯 JVM 模块不读 Android 资源——语言一律由集成方解析后显式传入。
 */
enum class Lang {
    /** 简体中文（默认，保持既有行为）。 */
    ZH,

    /** English。 */
    EN;

    companion object {
        /**
         * 系统语言 → 应用语言：zh（简体/繁体/各地区一律算中文）→ [ZH]，
         * 其余 → [EN]。入参传 `Locale.language`（如 "zh"/"en"）。
         */
        fun fromSystemLanguage(language: String): Lang =
            if (language.lowercase().startsWith("zh")) ZH else EN
    }
}
