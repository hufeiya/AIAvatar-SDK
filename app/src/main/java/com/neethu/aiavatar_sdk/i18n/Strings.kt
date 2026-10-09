package com.neethu.aiavatar_sdk.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.neethu.aiavatar_sdk.AsrEngine
import com.neethu.aiavatar_sdk.AiProvider
import com.neethu.aiavatar_sdk.InputMode
import com.neethu.aiavatar_sdk.TtsEngine
import com.neethu.corelib.Lang

/**
 * demo 全部用户可读 UI 文案的中英双语表（多语言支持，2026-10）。
 *
 * **维护约定**：UI 文案只允许出现在本文件（提示词在 orchestrator 的
 * PromptTexts/RpsTexts，TTS 错误文案在 adapter 的 TtsErrorTexts）——
 * 每个成员中英两版按相同顺序对齐，改一处同步两语。
 * `StringsI18nTest` 反射断言 EN 实例的全部成员无中文字符，防漂移。
 *
 * 用法：组合根部 `CompositionLocalProvider(LocalStrings provides Strings(lang))`，
 * 任何 @Composable 里 `val s = LocalStrings.current`；非组合代码（错误回调/
 * 调试命令）由调用方显式传 [Strings]。提示词语言（orchestrator）与 UI 语言
 * 同源：同一个 [Lang]，经 AiChatController.ensure 传入会话。
 */
class Strings(val lang: Lang) {

    // ── 通用 ──────────────────────────────────────────────────────────────
    val currentSuffix: String get() = zh("（当前）", " (current)")
    fun slashCount(n: Int): String = zh("· $n 条消息", "· $n messages")
    val secondsUnit: String get() = zh("秒", "s")
    val bpmUnit: String get() = zh("次/分", "bpm")

    // ── 输入模式 / 引擎 / 服务商标签 ────────────────────────────────────────
    fun inputMode(m: InputMode): String = when (m) {
        InputMode.MANUAL -> zh("手动点击", "Manual")
        InputMode.TEXT -> zh("打字输入", "Typing")
        InputMode.VOICE -> zh("语音模式", "Voice")
        InputMode.VIDEO -> zh("视频模式", "Video")
    }

    fun asrEngine(e: AsrEngine): String = when (e) {
        AsrEngine.CLOUD -> zh("云端 ASR（按大模型服务商）", "Cloud ASR (follows the LLM provider)")
        AsrEngine.SYSTEM -> zh("系统识别（免费无 Key）", "System recognition (free, no key)")
    }

    fun ttsEngine(e: TtsEngine): String = when (e) {
        TtsEngine.OPENAI_COMPATIBLE -> zh("OpenAI 兼容", "OpenAI-compatible")
        TtsEngine.EDGE -> zh("Edge-TTS（免费无 Key）", "Edge-TTS (free, no key)")
    }

    fun provider(p: AiProvider): String = when (p) {
        AiProvider.SILICONFLOW -> zh("硅基流动", "SiliconFlow")
        AiProvider.VOLCANO -> zh("火山引擎", "Volcano Engine")
        AiProvider.OPENROUTER -> "OpenRouter"
    }

    /** 服务商 API Key 字段标签（大模型侧）。 */
    fun llmKeyLabel(p: AiProvider): String = when (p) {
        AiProvider.SILICONFLOW -> zh("硅基流动 API Key", "SiliconFlow API key")
        AiProvider.VOLCANO -> zh("火山方舟 API Key（大模型用）", "Volcano Ark API key (for LLM)")
        AiProvider.OPENROUTER -> "OpenRouter API key"
    }

    /** 服务商 API Key 字段标签（语音合成侧；火山是另一把豆包语音 key）。 */
    fun ttsKeyLabel(p: AiProvider): String = when (p) {
        AiProvider.SILICONFLOW -> zh("硅基流动 API Key（语音合成用）", "SiliconFlow API key (for TTS)")
        AiProvider.VOLCANO -> zh("豆包语音 API Key（语音合成用）", "Douban Voice API key (for TTS)")
        AiProvider.OPENROUTER -> "OpenRouter API key (for TTS)"
    }

    /** Key 输入框占位。 */
    fun keyHint(p: AiProvider, tts: Boolean): String = when {
        tts && p == AiProvider.VOLCANO -> zh("粘贴豆包语音控制台 Key", "Paste a Doubao Voice console key")
        tts && p == AiProvider.OPENROUTER -> "sk-or-v1-..."
        p == AiProvider.VOLCANO -> "ark-..."
        p == AiProvider.OPENROUTER -> "sk-or-v1-..."
        else -> "sk-..."
    }

    /** Edge-TTS 音色显示名（中文音色的「女·晓晓」式释义随语言切换）。 */
    fun edgeVoiceLabel(id: String): String {
        val gloss = when {
            id.endsWith("MultilingualNeural") -> if (id.contains("Emma")) f(" (female · multilingual)", "（女·多语种）") else f(" (male · multilingual)", "（男·多语种）")
            id.startsWith("zh-CN-liaoning") -> f(" (female · Northeastern)", "（女·东北）")
            id.startsWith("zh-CN-shaanxi") -> f(" (female · Shaanxi)", "（女·陕西）")
            id.contains("Yun") -> f(" (male)", "（男）")
            else -> f(" (female)", "（女）")
        }
        return id + gloss
    }

    // ── 主屏：输入条 / 状态徽标 / 按钮 ──────────────────────────────────────
    val phaseThinking: String get() = zh("思考中", "Thinking")
    val phaseSpeaking: String get() = zh("说话中", "Speaking")
    val transcriptUserPrefix: String get() = zh("我：", "Me: ")
    val sendA11y: String get() = zh("发送", "Send")
    val interruptA11y: String get() = zh("打断", "Interrupt")
    val toggleInputModeA11y: String get() = zh("切换输入模式", "Switch input mode")
    val hideButtons: String get() = zh("隐藏按钮", "Hide buttons")
    val showButtons: String get() = zh("显示按钮", "Show buttons")
    val fabMove: String get() = zh("移", "Move")
    val fabView: String get() = zh("视", "View")
    val fabCard: String get() = zh("卡", "Card")
    val pipFront: String get() = zh("前", "Front")
    val pipBack: String get() = zh("后", "Back")

    // 输入框占位
    val placeholderNotConfigured: String get() = zh("先在 ⚙️ 设置里配置 AI 服务", "Configure the AI service in ⚙️ Settings first")
    val placeholderVideo: String get() = zh("说话（视频模式：每轮附相机画面）…", "Speak (video mode: each turn attaches a camera frame)…")
    val placeholderVoiceConfirm: String get() = zh("识别结果确认后发送…", "Review the recognized text, then send…")
    val placeholderType: String get() = zh("说点什么，回车或发送…", "Type a message, press Enter or Send…")

    // 按住说话 / 自由说话
    val holdReleaseToRecognize: String get() = zh("松开 识别", "Release to recognize")
    val recognizing: String get() = zh("识别中…", "Recognizing…")
    val holdToTalk: String get() = zh("按住 说话", "Hold to talk")
    val notConfigured: String get() = zh("未配置 AI 服务", "AI service not configured")
    val toggleFree: String get() = zh("自由", "Free")
    val toggleHold: String get() = zh("按住", "Hold")
    val talk: String get() = zh("说话", "Talk")
    val freeListening: String get() = zh("自由说话中，直接开口", "Free talk on — just start speaking")
    val hearingYou: String get() = zh("听到你说话…", "Hearing you…")

    // ── 主屏错误条 ────────────────────────────────────────────────────────
    fun sentenceFailed(index: Int, message: String?): String =
        zh("第 ${index + 1} 句语音合成失败：$message", "Sentence ${index + 1} TTS failed: $message")
    val turnFailed: String get() = zh("对话失败", "Conversation failed")
    val micPermissionNeeded: String get() = zh("需要麦克风权限才能语音输入", "Microphone permission is required for voice input")
    val cameraPermissionNeeded: String get() =
        zh("需要相机权限才能视频通话（打字/按住说话仍可用，无抓拍与注视追踪）",
            "Camera permission is required for video calls (typing/hold-to-talk still work, without snapshots or gaze tracking)")
    fun asrNeedsKey(p: AiProvider): String =
        zh("语音识别走${provider(p)}：请先在 ⚙️ 设置里填${provider(p)} API Key",
            "ASR uses ${provider(p)}: fill in the ${provider(p)} API key in ⚙️ Settings first")
    fun systemAsrError(message: String): String =
        zh("系统语音识别：$message", "System recognition: $message")
    val systemAsrConsent: String get() =
        zh("系统语音识别等待授权：请在屏幕弹窗中点「允许」，授权后自动继续",
            "System recognition is waiting for consent: tap \"Allow\" in the system dialog — recognition resumes automatically afterwards")
    val noSpeechHeard: String get() =
        zh("未识别到语音内容，请靠近一点重试", "No speech recognized — move closer and try again")
    val threwPlaceholder: String get() = zh("（出拳）", "(a throw)")
    fun asrFailed(message: String?): String =
        zh("语音识别失败：$message", "Speech recognition failed: $message")
    val freeTalkCloudNeedsKey: String get() =
        zh("自由说话（云端识别）需要硅基流动 API Key，先在 ⚙️ 设置里配置或改用系统识别",
            "Free talk (cloud) needs a SiliconFlow API key — configure it in ⚙️ Settings or switch to system recognition")
    val freeTalkNoSystemAsr: String get() =
        zh("本机没有系统语音识别服务，请改用云端识别",
            "No system recognition service on this device — switch to cloud ASR")
    fun freeTalkEnabled(engine: AsrEngine): String =
        zh("自由说话已开启（${asrEngine(engine)}）：直接开口，说完一句自动发送；系统识别在虚拟人说话期间暂停聆听",
            "Free talk on (${asrEngine(engine)}): just start speaking — each finished sentence sends automatically; system recognition pauses while the avatar is speaking")
    fun freeTalkStartFailed(message: String?): String =
        zh("自由说话启动失败：$message", "Failed to start free talk: $message")
    val systemAsrStartFailed: String get() = zh("系统语音识别启动失败", "Failed to start system recognition")
    val recordStartFailed: String get() = zh("录音启动失败", "Failed to start recording")
    val noAudioRecorded: String get() =
        zh("没录到声音——按下后稍等半秒再开口", "No audio captured — wait half a second after pressing, then speak")

    // ── 视频模式 ─────────────────────────────────────────────────────────
    val videoNeedsConfig: String get() =
        zh("先在 ⚙️ 设置里配置 AI 服务，再开视频模式",
            "Configure the AI service in ⚙️ Settings before entering video mode")
    fun videoNeedsVision(model: String): String =
        zh("当前大模型「$model」不支持图片输入，视频模式需要多模态模型（⚙️ 设置里切换：硅基流动选 Qwen3.8-27B / Qwen3-VL，火山默认模型即可）",
            "The current LLM \"$model\" cannot take images — video mode needs a multimodal model (switch in ⚙️ Settings: pick Qwen3.8-27B / Qwen3-VL on SiliconFlow, or any default Volcano model)")
    val videoCameraNotRunning: String get() =
        zh("视频相机未启动——先进入视频模式 (set_mode video)",
            "Video camera not running — enter video mode first (set_mode video)")
    val videoSnapshotEmpty: String get() =
        zh("抓拍缓存还是空的——相机刚起或第一帧还没编码完，等 1s 再试",
            "Snapshot cache is still empty — the camera just started or the first frame isn't encoded yet; wait 1s and retry")

    // ── 动画/场景/模型面板 ─────────────────────────────────────────────────
    val animationsTitle: String get() = zh("Animations · 长按条目设为待机", "Animations · long-press to set idle")
    val idleSuffix: String get() = zh("· 待机", "· idle")
    val idleSetFailed: String get() = zh("该文件无法设为待机", "This file cannot be set as idle")
    fun idleSet(label: String): String = zh("待机动作：$label", "Idle animation: $label")
    val sceneNone: String get() = zh("无（纯色背景）", "None (solid color)")

    // ── 人物卡面板 ────────────────────────────────────────────────────────
    val presetCardsTitle: String get() = zh("预置角色", "Preset characters")
    val myCardsTitle: String get() = zh("我的卡片", "My cards")
    val importCard: String get() = zh("导入 PNG/JSON…", "Import PNG/JSON…")
    val unnamedCard: String get() = zh("（未命名卡片）", "(unnamed card)")
    val cardInUse: String get() = zh("使用中", "in use")
    val deleteCardA11y: String get() = zh("删除卡片", "Delete card")
    val clearCardA11y: String get() = zh("取消人物卡", "Clear character card")
    val noCard: String get() = zh("无人物卡", "No character")
    val cardsEmptyHint: String get() =
        zh("点上方预置角色即可开始对话；从 SillyTavern 导出的 PNG / JSON 卡导入后也会出现在这里。",
            "Tap a preset character above to start chatting; character cards exported from SillyTavern (PNG / JSON) will also appear here once imported.")
    fun presetCardUnreadable(assetPath: String): String =
        zh("预置卡不可读（$assetPath）", "Preset card unreadable ($assetPath)")
    val cardParseFailed: String get() =
        zh("无法解析所选文件为角色卡（支持 SillyTavern PNG / JSON）",
            "Cannot parse the selected file as a character card (SillyTavern PNG / JSON supported)")

    // ── 模型导入（filesDir/vrms，与内置模型同列表同加载路径）───────────────
    val importModel: String get() = zh("导入 VRM 模型…", "Import VRM model…")
    fun modelImported(name: String): String = zh("模型已导入：$name", "Model imported: $name")
    val modelImportFailed: String get() =
        zh("无法导入所选文件为模型（支持 VRM/GLB）", "Cannot import the selected file as a model (VRM/GLB supported)")

    // ── 上下文 ────────────────────────────────────────────────────────────
    val freeChat: String get() = zh("自由对话", "Free chat")
    val contextCurrent: String get() = zh("当前", "current")
    val deleteContextA11y: String get() = zh("删除上下文", "Delete context")

    // ── 设置页 ────────────────────────────────────────────────────────────
    val settingsTitle: String get() = zh("Settings", "Settings")
    val closeSettingsA11y: String get() = zh("Close settings", "Close settings")
    val sectionLanguage: String get() = zh("语言 (Language)", "Language (语言)")
    val languageTitle: String get() = zh("界面语言", "App language")
    val languageSubtitle: String get() =
        zh("跟随系统：中文系统用简体中文，其余用英文；切换后对话提示词也随语言重建",
            "Follow system: Chinese systems get Simplified Chinese, everything else English; switching also rebuilds the prompts in the new language")
    fun appLangLabel(name: String): String = when (name) {
        "SYSTEM" -> zh("跟随系统 (System)", "System (跟随系统)")
        "ZH" -> "简体中文"
        else -> "English"
        }
    val languageChanged: String get() = zh("界面语言已切换", "App language switched")

    val sectionAi: String get() = zh("AI 配置 (AI Chat · 大模型/语音 双服务商)", "AI Chat (LLM / speech providers)")
    val aiConfigured: String get() = zh("已配置，保存后立即生效", "Configured — changes apply as soon as saved")
    val aiEdgeReadyHint: String get() = zh("填大模型 API Key 即可对话（Edge-TTS 免 Key）", "Just fill the LLM API key to chat (Edge-TTS needs no key)")
    val aiPickProviderHint: String get() = zh("选择服务商、填 API Key 即可对话", "Pick a provider and fill the API key to chat")
    val llmProviderTitle: String get() = zh("大模型服务商", "LLM provider")
    val llmModelTitle: String get() = zh("大模型", "LLM")
    val ttsEngineTitle: String get() = zh("TTS 引擎", "TTS engine")
    val edgeTtsHint: String get() =
        zh("Edge-TTS：微软朗读接口，免费、无需 Key（接口无 SLA，失败会提示）",
            "Edge-TTS: Microsoft's read-aloud endpoint — free, no key (no SLA; failures surface as errors)")
    val voiceTitle: String get() = zh("音色 Voice", "Voice")
    val sameProviderTitle: String get() = zh("语音合成与 大模型 同服务商", "TTS uses the same provider as the LLM")
    val sameProviderSubtitle: String get() =
        zh("勾选时 TTS 直接使用上面的大模型服务商；取消可为 TTS 单独选服务商（必要时单独填 Key）",
            "When checked, TTS uses the LLM provider above; uncheck to pick a separate TTS provider (and key, if needed)")
    val ttsProviderTitle: String get() = zh("TTS 服务商", "TTS provider")
    val ttsModelTitle: String get() = zh("TTS 模型", "TTS model")
    fun asrGroupLabel(p: AiProvider, volcanoFallback: Boolean): String =
        zh("语音输入（按住说话 · ASR 走${provider(p)}${if (volcanoFallback) "（火山回落）" else ""}）",
            "Voice input (hold-to-talk · ASR via ${provider(p)}${if (volcanoFallback) ", falls back to SiliconFlow" else ""})")
    val sfAsrKeyTitle: String get() = zh("硅基流动 API Key（语音识别用）", "SiliconFlow API key (for ASR)")
    val recognitionEngineTitle: String get() = zh("识别引擎", "Recognition engine")
    val systemAsrAvailableHint: String get() =
        zh("本机可用：系统语音识别服务已就绪（免费、无 Key、不消耗云端额度）；虚拟人说话期间自动暂停聆听",
            "Available: the system recognition service is ready (free, no key, no cloud quota); it pauses automatically while the avatar is speaking")
    val systemAsrUnavailableHint: String get() =
        zh("本机不可用：未检测到系统语音识别服务（无 Google 服务/厂商服务），请改用云端识别",
            "Unavailable: no system recognition service found (no Google/vendor service) — use cloud ASR instead")
    val asrModelTitle: String get() = zh("ASR 模型", "ASR model")
    val asrModelAuto: String get() = zh("自动（推荐）", "Auto (recommended)")
    val voiceAutoSendTitle: String get() = zh("语音直接发送", "Send voice input directly")
    val voiceAutoSendSubtitle: String get() =
        zh("松手识别成功即发送；关闭则识别文本先填入输入框，确认后再发",
            "Send as soon as release recognition succeeds; when off, the recognized text goes to the input box for confirmation first")
    val llmCameraTitle: String get() = zh("AI 可控镜头", "AI camera control")
    val llmCameraSubtitle: String get() =
        zh("允许模型用 <cam:…> 标签切换视角；关闭后模型不再动你的取景",
            "Let the model switch framing with <cam:…> tags; when off the model never moves your camera")

    val sectionCard: String get() = zh("人物卡 (Character Card · 人设提示词)", "Character Card (persona prompt)")
    val cardNotActive: String get() =
        zh("未激活人物卡。在「卡」面板点选一位角色后，这里会显示该卡的人设提示词，并可以编辑成你自己想要的版本。",
            "No character card active. Pick a character in the \"Card\" panel and their persona prompt will show up here — editable to whatever you like.")
    fun cardCurrent(name: String): String = zh("当前：$name", "Current: $name")
    val cardPromptEdited: String get() = zh("（人工编辑版，只读展示）", "(manually edited, read-only)")
    val cardPromptDefault: String get() = zh("（卡片默认，只读展示）", "(card default, read-only)")
    val cardPromptEmpty: String get() = zh("（该卡没有人设文本）", "(this card has no persona text)")
    val cardEdit: String get() = zh("编辑", "Edit")
    val cardEditHint: String get() =
        zh("编辑人物卡提示词（该卡的完整 system 人设，逐字替换）",
            "Edit the character card prompt (the card's full system persona, replaced verbatim)")
    val cardRestoreDefault: String get() = zh("还原默认", "Restore default")
    val cancel: String get() = zh("取消", "Cancel")

    val sectionContext: String get() = zh("对话上下文 (Contexts)", "Conversations (Contexts)")
    val newContextTitle: String get() = zh("新建上下文", "New context")
    val newContextSubtitle: String get() =
        zh("开始一段全新对话；旧上下文保留，可随时切回", "Start a fresh conversation; old contexts are kept and can be switched back anytime")
    val contextHistoryLabel: String get() = zh("历史上下文（点按切换，删除不可恢复）", "History (tap to switch; deletion is permanent)")
    val contextEmptyHint: String get() =
        zh("还没有历史上下文。发送第一条消息后，当前上下文会出现在这里。",
            "No history yet. After you send the first message, the current context shows up here.")
    val protocolPromptTitle: String get() = zh("标签协议提示词（只读）", "Tag protocol prompt (read-only)")
    val protocolCollapseA11y: String get() = zh("收起提示词", "Collapse prompt")
    val protocolExpandA11y: String get() = zh("查看提示词", "View prompt")
    val protocolEmpty: String get() = zh("（AI 会话尚未就绪，或协议指令已关闭）", "(AI session not ready, or protocol instructions are off)")
    fun protocolIntro(chars: Int): String =
        zh("以下 $chars 字符与实际发给大模型的 system 提示词逐字一致：",
            "The following $chars characters match the system prompt actually sent to the LLM, verbatim:")

    val sectionAnimations: String get() = zh("动画资源 (Animations)", "Animations")
    val animBuiltInTitle: String get() = zh("APK 内置动画", "Built-in animations (APK)")
    val animBuiltInSubtitle: String get() =
        zh("打包在 assets/animations 中，开箱即用（默认）", "Bundled in assets/animations, works out of the box (default)")
    val animExternalTitle: String get() = zh("手机外存动画", "On-device storage animations")
    val animExternalSubtitle: String get() =
        zh("扫描 App data 目录及其子文件夹中的 .vrma，不占 APK 体积",
            "Scans the app's data directory (and subfolders) for .vrma files — adds nothing to the APK size")
    fun animExternalDir(path: String?): String =
        zh("目录：${path ?: "外部存储不可用"}\n将 .vrma 文件放入该目录即可（支持子文件夹分类）",
            "Directory: ${path ?: "external storage unavailable"}\nPut .vrma files there (subfolders become categories)")

    val sectionQuality: String get() = zh("画质设置 (Quality)", "Graphics (Quality)")
    val sectionLiveness: String get() = zh("拟人感 (呼吸 / 视线 / 眨眼)", "Liveness (breath / gaze / blink)")
    val livenessGroupLabel: String get() = zh("自主微动作；改动立即生效并持久化", "Autonomous micro-motions; changes apply instantly and persist")
    val restoreDefault: String get() = zh("恢复默认", "Restore defaults")
    val livenessRestoreSubtitle: String get() = zh("呼吸/视线/眨眼全部回到默认参数", "Reset breath/gaze/blink to default parameters")
    val breathTitle: String get() = zh("呼吸", "Breathing")
    val breathSubtitle: String get() = zh("胸肩起伏与头部微动（自然呼吸）", "Chest/shoulder rise and subtle head motion (natural breathing)")
    val breathAmplitude: String get() = zh("呼吸幅度", "Breath amplitude")
    val breathRate: String get() = zh("呼吸频率", "Breath rate")
    val saccadeTitle: String get() = zh("视线微动 (Saccade)", "Gaze micro-movements (saccade)")
    val saccadeSubtitle: String get() = zh("注视点自然游移（模拟人视线不锁定一点）", "The gaze point wanders naturally (like a human, never locked)")
    val saccadeAmplitude: String get() = zh("视线幅度", "Gaze amplitude")
    val blinkTitle: String get() = zh("眨眼", "Blinking")
    val blinkSubtitle: String get() = zh("自动眨眼（间隔随机）", "Automatic blinks (randomized interval)")
    val blinkInterval: String get() = zh("眨眼平均间隔", "Mean blink interval")

    val sectionFreeSpeech: String get() = zh("自由说话 (Free Talk · 灵敏度)", "Free Talk (sensitivity)")
    val freeSpeechSystemNote: String get() =
        zh("当前识别引擎为系统识别：断句由系统服务决定，以下灵敏度参数仅对云端识别生效",
            "Recognition engine is SYSTEM: segmentation is decided by the system service — the sensitivity settings below only affect cloud recognition")
    val freeSpeechGroupLabel: String get() =
        zh("语音端点检测参数；改动实时生效并持久化，门限越低越灵敏",
            "Voice endpoint detection; changes apply instantly and persist — lower thresholds are more sensitive")
    val freeSpeechRestoreSubtitle: String get() =
        zh("起音/打断门限与切句停顿回到默认值", "Reset onset/barge thresholds and the end-of-utterance pause to defaults")
    val startThreshold: String get() = zh("说话门限（越低越灵敏）", "Speech onset threshold (lower = more sensitive)")
    val bargeThreshold: String get() = zh("打断门限（越低越容易打断）", "Barge-in threshold (lower = easier to interrupt)")
    val hangoverTitle: String get() = zh("切句停顿（说完静默多久算一句话）", "End-of-utterance pause (silence that ends a sentence)")

    // 画质
    val qualityPresetHeader: String get() = zh("画质预设 · 一键档位", "Quality presets · one-tap tiers")
    val presetLow: String get() = zh("低配 Low", "Low")
    val presetLowSubtitle: String get() = zh("单主光 · 1024 阴影 · 无AO · FXAA · 移动端稳 60fps", "Key light only · 1024 shadows · no AO · FXAA · steady 60fps on mobile")
    val presetMedium: String get() = zh("主流 Medium", "Medium")
    val presetMediumSubtitle: String get() = zh("主光+辅光 · 1024 阴影 · SSAO · ACES + 轻度泛光", "Key + fill · 1024 shadows · SSAO · ACES + light bloom")
    val presetHigh: String get() = zh("高配 High", "High")
    val presetHighSubtitle: String get() = zh("三点布光 · 2048 阴影 · GTAO · ACES + 泛光", "Three-point lighting · 2048 shadows · GTAO · ACES + bloom")
    val presetUltra: String get() = "3A Ultra"
    val presetUltraSubtitle: String get() = zh("4096 阴影 + 接触阴影 · GTAO · TAA · 泛光 + 景深 + 材质增强", "4096 shadows + contact shadows · GTAO · TAA · bloom + DoF + material enhancements")
    val renderModeHeader: String get() = zh("渲染风格 (Render Style)", "Render style")
    val renderModePbr: String get() = zh("PBR 写实", "PBR (realistic)")
    val renderModePbrSubtitle: String get() =
        zh("金属粗糙度工作流 + 摄影棚布光，写实质感", "Metallic-roughness workflow with studio lighting — realistic look")
    val renderModeMtoon: String get() = zh("MToon 卡通", "MToon (anime)")
    val renderModeMtoonSubtitle: String get() =
        zh("三段式赛璐璐着色 + 描边 + matcap，three-vrm 同款观感", "Three-tone cel shading + outline + matcap, matching the three-vrm look")
    val renderModeHint: String get() = zh("切换时重新加载当前模型", "Switching reloads the current model")
    val lightingHeader: String get() = zh("光照与环境 (Lighting & IBL)", "Lighting & environment (IBL)")
    val iblIntensity: String get() = zh("IBL 环境光强度", "IBL ambient intensity")
    val iblRotation: String get() = zh("IBL 环境光旋转", "IBL ambient rotation")
    val lightingRigLabel: String get() = zh("布光方案", "Lighting rig")
    val rigKeyOnly: String get() = zh("单主光", "Key only")
    val rigKeyOnlySubtitle: String get() = zh("仅 1 盏主平行光 + IBL，性能最优", "A single key directional light + IBL — best performance")
    val rigKeyFill: String get() = zh("双点光", "Key + fill")
    val rigKeyFillSubtitle: String get() = zh("主光 + 冷色辅光", "Key light + cool fill light")
    val rigStudio: String get() = zh("摄影棚三点布光", "Studio three-point")
    val rigStudioSubtitle: String get() = zh("主光 + 辅光 + 强轮廓背光，勾勒发丝与肩膀", "Key + fill + strong rim light, outlining hair and shoulders")
    val shadowsHeader: String get() = zh("阴影 (Shadows)", "Shadows")
    val shadowResolutionLabel: String get() = zh("阴影贴图分辨率", "Shadow map resolution")
    val shadow1024Subtitle: String get() = zh("低端机适用", "For low-end devices")
    val shadow2048Subtitle: String get() = zh("精度与开销平衡，旗舰机适用", "Balanced quality/cost — for flagship devices")
    val shadow4096Subtitle: String get() = zh("发丝级阴影细节，高端机适用", "Hair-level shadow detail — for high-end devices")
    val pcssTitle: String get() = zh("软阴影 (PCSS)", "Soft shadows (PCSS)")
    val pcssSubtitle: String get() =
        zh("基于物理的阴影半影，边缘近实远虚；Adreno 上开销极大（实测个位数帧率），仅限旗舰机尝试",
            "Physically-based shadow penumbra, sharp near and soft far; extremely expensive on Adreno (single-digit fps measured) — flagship devices only, try at your own risk")
    val contactShadowsTitle: String get() = zh("接触阴影 (Contact Shadows)", "Contact shadows")
    val contactShadowsSubtitle: String get() =
        zh("屏幕空间微阴影：睫毛、鼻翼极近距离暗部，轻微 GPU 开销", "Screen-space micro shadows: lash and nose-tip contact darkening; slight GPU cost")
    val aoHeader: String get() = zh("环境光遮蔽 (Ambient Occlusion)", "Ambient occlusion")
    val off: String get() = zh("关闭", "Off")
    val aoOffSubtitle: String get() = zh("省电，模型易显“漂浮感”", "Saves power, but the model may look \"floaty\"")
    val aoSsao: String get() = zh("标准 SSAO", "Standard SSAO")
    val aoSsaoSubtitle: String get() = zh("屏幕空间环境光遮蔽，中等采样", "Screen-space AO, medium sampling")
    val aoGtao: String get() = zh("高质量 GTAO (推荐)", "High-quality GTAO (recommended)")
    val aoGtaoSubtitle: String get() =
        zh("Ground-Truth AO + 双边滤波，眼眶/鼻窝/褶皱暗角自然", "Ground-truth AO + bilateral filter — natural socket/nose/crease darkening")
    val postHeader: String get() = zh("后处理 (Post-Processing)", "Post-processing")
    val toneMappingLabel: String get() = zh("色调映射", "Tone mapping")
    val toneLinearSubtitle: String get() = zh("线性，不推荐：高光极易过曝", "Linear, not recommended: highlights blow out easily")
    val toneFilmicSubtitle: String get() = zh("高对比度电影感", "High-contrast cinematic look")
    val toneAces: String get() = zh("ACES (推荐)", "ACES (recommended)")
    val toneAcesSubtitle: String get() = zh("Unreal/3A 标配，极佳的高光滚降", "The Unreal/AAA standard — excellent highlight rolloff")
    val bloomTitle: String get() = zh("泛光 (Bloom)", "Bloom")
    val bloomSubtitle: String get() = zh("强光照射金属/眼球时的漫溢辉光", "The glow that spills when bright light hits metal or eyes")
    val bloomStrength: String get() = zh("泛光强度", "Bloom strength")
    val dofTitle: String get() = zh("景深 (Depth of Field)", "Depth of field")
    val dofSubtitle: String get() =
        zh("特写模式虚化背景，单反微距质感；全身全景建议关闭", "Blurs the background in close-ups (DSLR macro feel); keep it off for full-body wide shots")
    val aaHeader: String get() = zh("抗锯齿 (Anti-Aliasing)", "Anti-aliasing")
    val aaOffSubtitle: String get() = zh("无抗锯齿", "No anti-aliasing")
    val aaFxaaSubtitle: String get() = zh("轻量，低端机适用，画面略糊", "Lightweight, for low-end devices; slightly blurrier")
    val aaMsaaSubtitle: String get() = zh("几何边缘清晰，开销较大", "Crisp geometric edges, higher cost")
    val aaTaa: String get() = zh("TAA (3A 画质首选)", "TAA (the AAA choice)")
    val aaTaaSubtitle: String get() =
        zh("抹平高频闪烁，带锐化；快速运动可能有轻微拖影", "Smooths high-frequency shimmer with sharpening; fast motion may leave slight ghosting")
    val materialsHeader: String get() = zh("材质增强 (Materials)", "Materials")
    val enhanceMaterialsTitle: String get() = zh("材质特性增强", "Material enhancements")
    val enhanceMaterialsSubtitle: String get() =
        zh("眼球 ClearCoat + 皮肤/头发粗糙度优化；实际效果取决于模型材质支持，关闭后自动重载模型还原",
            "Eye ClearCoat + skin/hair roughness tweaks; effect depends on the model's materials. Toggling off reloads the pristine model")
    val displayHeader: String get() = zh("显示 (Display)", "Display")
    val showFpsTitle: String get() = zh("显示 FPS 帧率", "Show FPS")
    val showFpsSubtitle: String get() = zh("在右上角实时显示渲染帧率", "Live render framerate in the top-right corner")
    val collapseA11y: String get() = zh("收起", "Collapse")
    val expandA11y: String get() = zh("展开", "Expand")
    val chooseA11y: (String) -> String get() = { title -> zh("选择$title", "Choose $title") }

    // ── 系统语音识别（SystemAsr）错误 ───────────────────────────────────────
    fun systemAsrErrorMessage(code: Int): String = when (code) {
        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> zh("识别网络超时", "Recognition network timeout")
        android.speech.SpeechRecognizer.ERROR_NETWORK -> zh("识别服务网络错误", "Recognition service network error")
        android.speech.SpeechRecognizer.ERROR_AUDIO -> zh("识别音频录制错误", "Recognition audio recording error")
        android.speech.SpeechRecognizer.ERROR_SERVER -> zh("识别服务端错误", "Recognition server error")
        android.speech.SpeechRecognizer.ERROR_CLIENT -> zh("识别客户端错误", "Recognition client error")
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> zh("没有听到说话", "Didn't hear any speech")
        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> zh("没有匹配到语音内容", "No speech matched")
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> zh("识别服务忙", "Recognizer busy")
        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> zh("缺少麦克风权限", "Missing microphone permission")
        else -> zh("识别错误(code=$code)", "Recognition error (code=$code)")
    }

    val systemAsrAlreadyRunning: String get() = zh("系统语音识别已在进行中", "System recognition is already running")
    val systemAsrUnavailable: String get() =
        zh("本机没有系统语音识别服务（需要 Google 服务或厂商语音服务）",
            "No system recognition service on this device (needs Google services or a vendor speech service)")
    fun systemAsrStopped(finalError: String): String =
        zh("连续失败已停止（最后：$finalError），请重开自由说话或改用云端识别",
            "Stopped after repeated failures (last: $finalError) — restart free talk or switch to cloud ASR")

    // ── 录音 / 自由说话（VoiceRecorder / FreeSpeechController）─────────────
    val recorderAlreadyRunning: String get() = zh("录音已在进行中", "Recording is already in progress")
    fun recorderStartFailed(message: String?): String =
        zh("录音启动失败：$message", "Failed to start recording: $message")
    val freeSpeechAlreadyRunning: String get() = zh("自由说话已在进行中", "Free talk is already running")
    val freeSpeechMinBufferFailed: String get() = zh("AudioRecord 最小缓冲获取失败", "Failed to get the AudioRecord minimum buffer")
    val micUnavailable: String get() = zh("麦克风不可用（可能被其他应用占用）", "Microphone unavailable (possibly held by another app)")

    // ── 新手引导（未配大模型 Key 自动弹出；中文系统=硅基流动版，其余=OpenRouter 版）
    val guideTitle: String get() = zh("1 分钟开始玩", "Get started in 1 minute")

    /** 顶部推荐语（[target] = 引导落地的服务商：硅基流动=国内版，OpenRouter=海外版）。 */
    fun guideIntro(target: AiProvider): String = when (target) {
        AiProvider.SILICONFLOW -> zh(
            "推荐注册硅基流动账号：注册非常快（约 1 分钟），新用户可领 16 元代金券，能免费玩好久。",
            "We recommend a SiliconFlow account: sign-up takes about 1 minute, and new users get a ¥16 voucher — free play for a long time.",
        )
        else -> zh(
            "推荐注册 OpenRouter 账号：注册约 1 分钟，大量模型带免费额度，一把 API Key 即可聊天，开箱免费玩。",
            "We recommend a free OpenRouter account: sign-up takes about a minute, many models have a free tier, and one API key powers the chat — play for free.",
        )
    }
    val guideSwipeHint: String get() = zh("左右滑动查看步骤", "Swipe for the steps")

    /** 步骤页标题（[stepIndex] 0-2，与轮播截图一一对应）。 */
    fun guideStepCaption(target: AiProvider, stepIndex: Int): String = when (target) {
        AiProvider.SILICONFLOW -> when (stepIndex) {
            0 -> zh("第 1 步 · 注册账号（手机号 + 验证码）", "Step 1 · Sign up (phone number + code)")
            1 -> zh("第 2 步 · 实名认证（领代金券需要）", "Step 2 · Identity verification (needed for the voucher)")
            else -> zh("第 3 步 · 新建 API 密钥并复制", "Step 3 · Create an API key and copy it")
        }
        else -> when (stepIndex) {
            0 -> zh("第 1 步 · 打开 openrouter.ai，点「Get API Key」", "Step 1 · Open openrouter.ai and tap Get API Key")
            1 -> zh("第 2 步 · 用 Google / GitHub 或邮箱登录", "Step 2 · Sign in with Google / GitHub or email")
            else -> zh("第 3 步 · 点 Key 右侧按钮复制", "Step 3 · Tap the copy button next to your key")
        }
    }

    /** 国内版实名页的手机浏览器提示（海外流程截图本就是手机网页，不需要）。 */
    val guideStep2Hint: String get() = zh("⚠ 手机浏览器请先切换到「电脑版网页」再操作", "⚠ On a phone browser, switch to \"Desktop site\" first")
    fun guideLastTitle(target: AiProvider): String = when (target) {
        AiProvider.SILICONFLOW -> zh("最后一步 · 注册领券，粘贴 Key", "Last step · Register for the voucher, paste your key")
        else -> zh("最后一步 · 粘贴 API Key", "Last step · Paste your API key")
    }
    fun guideOpenRegister(target: AiProvider): String = when (target) {
        AiProvider.SILICONFLOW -> zh("打开注册页（跳转浏览器）", "Open the sign-up page (external browser)")
        else -> zh("打开 OpenRouter（跳转浏览器）", "Open openrouter.ai (external browser)")
    }
    fun guideKeyPlaceholder(target: AiProvider): String = when (target) {
        AiProvider.SILICONFLOW -> zh("粘贴 API Key（sk-…）", "Paste your API key (sk-…)")
        else -> zh("粘贴 API Key（sk-or-v1-…）", "Paste your API key (sk-or-v1-…)")
    }
    val guideConfirm: String get() = zh("确认，开始聊", "Confirm & start chatting")

    /** 确认键上方的自动配置说明（如实描述两套默认值，见 OnboardingGuide.kt）。 */
    fun guideAutoNote(target: AiProvider): String = when (target) {
        AiProvider.SILICONFLOW -> zh(
            "点确认自动配置：大模型 / 语音合成 / 语音识别（都用默认模型）",
            "Confirming auto-configures the LLM / TTS / speech recognition (default models)",
        )
        else -> zh(
            "点确认自动配置：大模型（免费视觉模型）/ 语音合成（Edge-TTS 免费）/ 语音识别（系统内置免费）",
            "Confirming auto-configures: LLM (free vision model) / TTS (free Edge-TTS) / speech recognition (built-in, free)",
        )
    }
    val guideKeyEmpty: String get() = zh("请先粘贴 API Key", "Paste your API key first")
    fun guideConfiguredToast(target: AiProvider): String = when (target) {
        AiProvider.SILICONFLOW -> zh(
            "已自动配置大模型 / 语音合成 / 语音识别（默认模型），开聊吧！",
            "LLM / TTS / speech recognition configured with default models — start chatting!",
        )
        else -> zh(
            "已自动配置：大模型（免费视觉模型）+ Edge-TTS + 系统语音识别，开聊吧！",
            "Configured: free-vision LLM + Edge-TTS + built-in speech recognition — start chatting!",
        )
    }
    val guideBrowserFail: String get() = zh("没有找到可用的浏览器", "No browser available on this device")
    val guideCloseA11y: String get() = zh("关闭引导", "Dismiss guide")

    /** 截图缩放（步骤页点图进全屏，放大层点任意处/返回键退出）。 */
    val guideZoomTapHint: String get() = zh("点图可放大", "Tap image to zoom")
    val guideZoomInA11y: String get() = zh("放大查看截图", "Zoom in on the screenshot")
    val guideZoomOutA11y: String get() = zh("退出放大", "Exit zoom")
    val guideZoomHint: String get() = zh("点击任意位置退出放大", "Tap anywhere to exit zoom")

    private fun zh(zh: String, en: String): String = if (lang == Lang.EN) en else zh
    private fun f(en: String, zh: String): String = if (lang == Lang.EN) en else zh
}

/** Compose 组合内读取当前文案表的入口；DemoScreen 根部 provide。 */
val LocalStrings = staticCompositionLocalOf { Strings(com.neethu.corelib.Lang.ZH) }
