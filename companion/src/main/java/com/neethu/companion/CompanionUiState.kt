package com.neethu.companion

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neethu.aiadapter.api.AsrAdapter
import com.neethu.aiadapter.model.AsrConfig
import com.neethu.corelib.AmbiencePreset
import com.neethu.corelib.AvatarController
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.Lang
import com.neethu.orchestrator.card.CharacterCard
import com.neethu.orchestrator.card.CharacterCardStore
import com.neethu.orchestrator.card.expandCardMacros
import com.neethu.orchestrator.i18n.promptTextsOf
import com.neethu.orchestrator.session.AvatarSession

/**
 * 产品主屏的状态仓（demo DemoUiState 的产品版,砍到单虚拟人产品的最小集）：
 * prefs 持久化 + Compose 可观察。写路径一律走 update/set 方法（持久化与
 * 联动集中在这里），UI 只读字段。
 */
internal class CompanionUiState(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    private val TAG = "CompanionUiState"

    // ── AI 配置 ───────────────────────────────────────────────────────────
    var aiPrefs by mutableStateOf(prefs.loadAiPrefs())
        private set

    fun updateAiPrefs(next: AiChatPrefs) {
        val sigChanged = llmIdentitySignature(next) != llmIdentitySignature(aiPrefs)
        aiPrefs = next
        prefs.saveAiPrefs(next)
        // LLM 身份（服务商/模型/Key/端点）变化 → 轮换上下文,旧对话不混新模型
        if (sigChanged) rotateContext()
        // 卡激活期间的音色修改写回卡绑定（P2-3 隐式绑定语义）
        activeCardFile?.let { file ->
            if (next.voice != aiPrefsVoiceSnapshot) cardBindings.update(file, CardBinding(voice = next.voice))
        }
        aiPrefsVoiceSnapshot = next.voice
    }

    private var aiPrefsVoiceSnapshot: String = prefs.loadAiPrefs().voice

    var voicePrefs by mutableStateOf(prefs.loadVoicePrefs())
        private set

    fun updateVoicePrefs(next: VoicePrefs) {
        voicePrefs = next
        prefs.saveVoicePrefs(next)
    }

    // ── 语言 ──────────────────────────────────────────────────────────────
    var langZh by mutableStateOf(prefs.getBoolean(KEY_LANG_ZH, true))
        private set

    val lang: Lang get() = if (langZh) Lang.ZH else Lang.EN

    fun changeLang(zh: Boolean) {
        langZh = zh
        prefs.edit().putBoolean(KEY_LANG_ZH, zh).apply()
    }

    // ── 上下文（对话线程）─────────────────────────────────────────────────
    var contextId by mutableStateOf(prefs.getString(KEY_CONTEXT_ID, null) ?: newContextId().also { prefs.edit().putString(KEY_CONTEXT_ID, it).apply() })
        private set

    fun newContext() {
        contextId = newContextId().also { prefs.edit().putString(KEY_CONTEXT_ID, it).apply() }
    }

    fun switchContext(id: String) {
        contextId = id.also { prefs.edit().putString(KEY_CONTEXT_ID, it).apply() }
    }

    fun deleteContext(id: String) {
        if (id == contextId) return
        kotlin.runCatching {
            com.neethu.orchestrator.history.ConversationDatabase.getInstance(context).let { db ->
                val io = kotlinx.coroutines.Dispatchers.IO
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withContext(io) {
                        db.messageDao().deleteFor(id)
                        db.sessionDao().delete(id)
                    }
                }
            }
        }
        contextListVersion++
    }

    private fun rotateContext() {
        Log.i(TAG, "llm identity changed, rotating context")
        newContext()
    }

    private var contextListVersion: Int = 0

    // ── 模型 ──────────────────────────────────────────────────────────────
    val builtinModelFiles: List<String> = runCatching {
        context.assets.list("vrms")?.filter { it.endsWith(".vrm") }.orEmpty().sorted()
    }.getOrDefault(emptyList())

    val importedModelFiles: List<String> = ImportedModelLibrary.list(context)

    val modelFiles: List<String> get() = builtinModelFiles + importedModelFiles

    var selectedModel by mutableStateOf(
        prefs.getString(KEY_SELECTED_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
    )
        private set

    fun selectModel(name: String) {
        selectedModel = name
        prefs.edit().putString(KEY_SELECTED_MODEL, name).apply()
        // 卡激活期间的模型选择写回卡绑定（P2-3）
        activeCardFile?.let { cardBindings.update(it, CardBinding(modelFile = name)) }
    }

    // ── 场景 ──────────────────────────────────────────────────────────────
    val sceneFiles: List<String> = runCatching {
        context.assets.list("scene")?.filter { it.endsWith(".glb") }.orEmpty().sorted()
    }.getOrDefault(emptyList())

    var selectedScene by mutableStateOf(prefs.getString(KEY_SELECTED_SCENE, null))
        private set

    fun setScene(name: String?) {
        selectedScene = name
        prefs.edit().putString(KEY_SELECTED_SCENE, name).apply()
        // 卡激活期间的场景选择写回卡绑定（companion 分叉:P3-3）
        activeCardFile?.let { cardBindings.update(it, CardBinding(sceneFile = name)) }
    }

    // ── 灯光氛围 ──────────────────────────────────────────────────────────
    var ambience by mutableStateOf(prefs.getString(KEY_AMBIENCE, AmbiencePreset.STUDIO.name) ?: AmbiencePreset.STUDIO.name)
        private set

    fun setAmbience(name: String, controller: AvatarController) {
        val preset = runCatching { AmbiencePreset.valueOf(name) }.getOrNull() ?: return
        ambience = name
        prefs.edit().putString(KEY_AMBIENCE, name).apply()
        controller.updateRenderSettings(preset.applyTo(AvatarRenderSettings()))
    }

    // ── 输入形态 ──────────────────────────────────────────────────────────
    var voiceInputMode by mutableStateOf(prefs.getBoolean(KEY_VOICE_MODE, false))

    // ── 约会模式（P3-3）───────────────────────────────────────────────────
    var dateMode by mutableStateOf(false)
        private set

    fun changeDateMode(on: Boolean) {
        if (dateMode == on) return
        dateMode = on
        if (on) {
            // 记住约会前的场景,结束回退
            prefs.edit().putString(KEY_DATE_PREV_SCENE, selectedScene).apply()
            val boundScene = activeCardFile?.let { cardBindings.forCard(it)?.sceneFile }
            val target = boundScene?.takeIf { it in sceneFiles } ?: DateMode.DEFAULT_SCENE
            if (target in sceneFiles) setScene(target)
        } else {
            setScene(prefs.getString(KEY_DATE_PREV_SCENE, null))
        }
    }

    // ── 关系追踪（G11）────────────────────────────────────────────────────
    var relationshipTracking by mutableStateOf(prefs.getBoolean(KEY_RELATIONSHIP, true))
        private set

    fun setRelationshipTracking(enabled: Boolean, aiChat: AiChatController) {
        relationshipTracking = enabled
        prefs.edit().putBoolean(KEY_RELATIONSHIP, enabled).apply()
        aiChat.setRelationshipEnabled(enabled)
    }

    // ── 问候通知（G10）────────────────────────────────────────────────────
    var greetingNotifyEnabled by mutableStateOf(GreetingNotifyPrefs.enabled(prefs))
        private set

    fun setGreetingNotify(enabled: Boolean) {
        greetingNotifyEnabled = enabled
        prefs.edit().putBoolean(GreetingNotifyPrefs.KEY_ENABLED, enabled).apply()
    }

    // ── 人物卡 ────────────────────────────────────────────────────────────
    val cardLibrary = CardLibrary(context)
    val cardBindings = CardBindings(prefs)
    val lorebookLibrary = LorebookLibrary(context)
    private val presetLibrary = PresetCardLibrary(context)

    var cards by mutableStateOf(cardLibrary.cards)
        private set

    var activeCardFile by mutableStateOf(prefs.getString(KEY_ACTIVE_CARD, null))
        private set

    var pendingGreetingFile by mutableStateOf<String?>(null)

    var presetCards by mutableStateOf(presetLibrary.load())
        private set

    fun refreshCards() {
        cards = cardLibrary.cards
        presetCards = presetLibrary.load()
    }

    fun cardByFile(file: String): CharacterCardStore.Entry? = cards.firstOrNull { it.fileName == file }

    fun setActiveCard(file: String?) {
        activeCardFile = file
        prefs.edit().putString(KEY_ACTIVE_CARD, file).apply()
    }

    fun importCardBytes(bytes: ByteArray): CharacterCardStore.Entry? {
        val entry = cardLibrary.importBytes(bytes)
        refreshCards()
        return entry
    }

    /** 预置卡点选：导入（或复用已导入）并返回条目。 */
    fun selectPresetCard(preset: PresetCard): CharacterCardStore.Entry? {
        val existing = cards.firstOrNull { presetAssetByFile(it.fileName) == preset.assetPath }
        if (existing != null) return existing
        val bytes = presetLibrary.readBytes(preset.assetPath) ?: return null
        val entry = importCardBytes(bytes) ?: return null
        // 预置映射记录（资产路径 ↔ 落盘文件名,本地化/绑定行用）
        val mapJson = prefs.getString(KEY_PRESET_CARDS, null)
        prefs.edit().putString(KEY_PRESET_CARDS, PresetCardImport.record(mapJson, preset.assetPath, entry.fileName)).apply()
        // 内嵌世界书自动提取（OMate 同款行为,P1-1）
        extractEmbeddedLorebook(entry)
        return entry
    }

    fun deleteCard(entry: CharacterCardStore.Entry) {
        cardLibrary.delete(entry.fileName)
        setCardPromptOverride(entry.fileName, null)
        lorebookLibrary.bindCard(entry.fileName, null)
        cardBindings.remove(entry.fileName)
        if (activeCardFile == entry.fileName) setActiveCard(null)
        refreshCards()
    }

    private fun extractEmbeddedLorebook(entry: CharacterCardStore.Entry) {
        entry.card.characterBook?.let { book ->
            if (book.entries.isEmpty()) return
            runCatching { lorebookLibrary.importFromCard(entry.fileName, book) }
                .onFailure { Log.w(TAG, "embedded lorebook extract failed", it) }
        }
    }

    // 预置映射与提示词覆盖（demo 同款 prefs 形态）
    fun presetAssetByFile(file: String): String? =
        PresetCardImport.reverseGet(prefs.getString(KEY_PRESET_CARDS, null), file)

    fun localizedCard(entry: CharacterCardStore.Entry): CharacterCard =
        PresetCardsEn.translate(entry.card, presetAssetByFile(entry.fileName), lang) ?: entry.card

    fun localizedCardName(preset: PresetCard): String =
        PresetCardsEn.translate(preset.card, preset.assetPath, lang)?.name ?: preset.card.name

    fun activeCardName(): String? = activeCardFile?.let { f -> cardByFile(f)?.let { localizedCard(it).name } }

    fun cardPromptOverride(file: String): String? =
        CardPromptOverrides.get(prefs.getString(KEY_CARD_OVERRIDES, null), file)

    fun setCardPromptOverride(file: String, text: String?) {
        val next = CardPromptOverrides.set(prefs.getString(KEY_CARD_OVERRIDES, null), file, text)
        prefs.edit().putString(KEY_CARD_OVERRIDES, next).apply()
    }

    // ── 世界书 ────────────────────────────────────────────────────────────
    var lorebooks by mutableStateOf(lorebookLibrary.entries)
        private set

    var lorebookEnabled by mutableStateOf(lorebookLibrary.enabled)
        private set

    fun importLorebookBytes(bytes: ByteArray, suggestedName: String?): LorebookLibrary.Entry? {
        val entry = lorebookLibrary.import(bytes, suggestedName)
        lorebooks = lorebookLibrary.entries
        return entry
    }

    fun setLorebookEnabled(enabled: Boolean, session: AvatarSession?) {
        lorebookEnabled = enabled
        lorebookLibrary.setEnabled(enabled)
        session?.lorebookEnabled = enabled
    }

    fun setGlobalLorebook(file: String?, session: AvatarSession?) {
        lorebookLibrary.setGlobal(file)
        lorebooks = lorebookLibrary.entries
        session?.let { applyLorebookToSession(it) }
    }

    fun deleteLorebook(file: String, session: AvatarSession?) {
        lorebookLibrary.delete(file)
        lorebooks = lorebookLibrary.entries
        session?.let { applyLorebookToSession(it) }
    }

    fun applyLorebookToSession(session: AvatarSession) {
        lorebookLibrary.applyTo(session, activeCardFile)
    }

    // ── ASR/TTS 解析（按当前 provider;demo asrFor 的产品版）──────────────────
    fun cloudAsr(): AsrAdapter? = runCatching {
        val provider = aiPrefs.provider
        val key = aiPrefs.apiKeyFor(provider)
        if (key.isBlank()) return null
        when (provider) {
            AiProvider.SILICONFLOW -> com.neethu.aiadapter.openai.OpenAiCompatibleAsrAdapter(
                baseUrl = com.neethu.orchestrator.facade.asrProviderFor(provider.sdk).baseUrl,
                apiKey = key,
            )
            AiProvider.BAILIAN -> com.neethu.aiadapter.bailian.BailianAsrAdapter(
                baseUrl = com.neethu.orchestrator.facade.asrProviderFor(provider.sdk).baseUrl,
                apiKey = key,
            )
            else -> null // 火山/OpenRouter/DeepSeek/自定义无稳定 ASR,产品走系统识别
        }
    }.getOrNull()

    fun asrConfig(): AsrConfig = AsrConfig(
        model = com.neethu.orchestrator.facade.resolveAsrModel(
            aiPrefs.provider.sdk.baseUrl,
            voicePrefs.asrModel,
        ),
        language = if (langZh) "zh" else "en",
    )

    companion object {
        private const val KEY_LANG_ZH = "app_lang_zh"
        private const val KEY_CONTEXT_ID = "ai_context_id"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_SELECTED_SCENE = "selected_scene"
        private const val KEY_AMBIENCE = "ambience"
        private const val KEY_VOICE_MODE = "voice_input_mode"
        private const val KEY_DATE_PREV_SCENE = "date_mode_prev_scene"
        private const val KEY_RELATIONSHIP = "relationship_tracking"
        private const val KEY_ACTIVE_CARD = "ai_active_card"
        private const val KEY_PRESET_CARDS = "ai_preset_cards"
        private const val KEY_CARD_OVERRIDES = "ai_card_prompt_overrides"

        /** 默认模型（与 demo 同款,内置资产必有）。 */
        const val DEFAULT_MODEL = "FreeTestCharacterAsuna_1024.vrm"

        fun newContextId(): String = java.util.UUID.randomUUID().toString()
    }
}

/** 音色显示名（绑定行/设置下拉共用;短名展示）。 */
internal fun voiceLabel(voice: String): String = voice.substringAfterLast('/').ifBlank { voice }

/**
 * G11 低调状态行:一条小字,非数值游戏化——阶段词 + 相识天数 + 称呼/纪念日。
 */
internal fun relationshipStatusLine(
    snapshot: com.neethu.orchestrator.memory.RelationshipSnapshot?,
): String? {
    snapshot ?: return null
    val parts = mutableListOf("相识 ${snapshot.daysTogether} 天", "关系·${com.neethu.orchestrator.memory.levelLabelOf(snapshot.level)}")
    snapshot.record.nickname?.let { parts += "称呼你「$it」" }
    if (snapshot.isAnniversaryToday) parts += "今天是相识纪念日"
    return parts.joinToString(" · ")
}

/** assets/animations 全量路径（LLM 动作目录的数据源;外置库不进产品 v1）。 */
internal fun builtinAnimationPaths(context: Context): List<String> = runCatching {
    val out = mutableListOf<String>()
    fun walk(dir: String) {
        context.assets.list(dir)?.forEach { name ->
            val path = "$dir/$name"
            if (name.endsWith(".vrma")) out += path else walk(path)
        }
    }
    walk("animations")
    out.sorted()
}.getOrDefault(emptyList())

/** demo ensureWalkGaits 产品版（首回 <move:> 前步态就绪）。 */
internal fun ensureWalkGaits(controller: AvatarController) {
    if (controller.isWalkGaitConfigured()) return
    runCatching {
        controller.setWalkGait(DEFAULT_WALK_CLIP, DEFAULT_WALK_BASE_SPEED)
        controller.setRunGait(DEFAULT_RUN_CLIP)
        controller.setStrafeGaits(DEFAULT_STRAFE_LEFT_CLIP, DEFAULT_STRAFE_RIGHT_CLIP)
    }.onFailure { Log.w("CompanionUiState", "walk gaits failed", it) }
}

/**
 * 卡人设进会话（demo applyCardToSession 同语义）：卡 → 门面 setCharacterCard
 * （含世界书重置）+ 每卡提示词人工覆盖。
 */
internal fun applyCardToSession(
    session: AvatarSession,
    entry: CharacterCardStore.Entry,
    overrideFor: (String) -> String?,
    lang: Lang,
) {
    session.setCharacterCard(entry.card)
    overrideFor(entry.fileName)?.let { override ->
        // 人工覆盖 = 整段替换 system（拼装顺序照旧,末段为覆盖文本）
        val base = com.neethu.orchestrator.card.SystemPromptAssembler(promptTextsOf(lang)).assemble(entry.card)
        session.systemPrompt = "$base\n\n$override"
    }
}

/** 开场白宏展开（弹层朗读用;P1-3）。 */
internal fun spokenGreetingText(card: CharacterCard, raw: String, lang: Lang): String =
    expandCardMacros(raw, card.name, promptTextsOf(lang).userAlias).trim()
