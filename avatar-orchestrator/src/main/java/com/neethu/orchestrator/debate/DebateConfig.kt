package com.neethu.orchestrator.debate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 辩论规则包（docs/debate-feasibility.md §9）：UTF-8 JSON、时间统一整数毫秒、
 * 业务 ID 唯一。本文件只负责「JSON ⇄ DTO + 校验」，编译成赛程表见 [RuleCompiler]。
 *
 * 未写的字段全部落在本文件的默认值上（`four_person_attack_v1` 的 schema 固定
 * 默认），导出时经 [DebateConfigDto.expanded] 展开，避免模板日后变化影响复现。
 */
object DebateConfigSchema {
    const val SCHEMA_VERSION = 1
    const val TEMPLATE_FOUR_PERSON_ATTACK_V1 = "four_person_attack_v1"

    /** 攻辩轮的合法应答者候选（对方二辩/三辩）。 */
    val CROSS_ANSWER_CANDIDATES = listOf(2, 3)
}

@Serializable
data class DebateConfigDto(
    val schemaVersion: Int = DebateConfigSchema.SCHEMA_VERSION,
    val matchId: String,
    val topic: String,
    val sides: SidesDto = SidesDto(),
    val scene: SceneDto = SceneDto(),
    val rules: RulesDto = RulesDto(),
    val speech: SpeechDto = SpeechDto(),
    val camera: CameraDto = CameraDto(),
    val posture: PostureDto = PostureDto(),
    val participants: List<ParticipantDto> = emptyList(),
    /** 固定稿（P0）：participantId → phase → 文本。缺失走内置兜底稿。 */
    val scripts: Map<String, Map<String, String>> = emptyMap(),
) {
    @Serializable
    data class SidesDto(val PRO: String = "", val CON: String = "")

    @Serializable
    data class SceneDto(val asset: String = "", val layout: String = "")

    @Serializable
    data class RulesDto(
        val template: String = DebateConfigSchema.TEMPLATE_FOUR_PERSON_ATTACK_V1,
        val openingMs: Long = 180_000L,
        val crossRoundMs: Long = 90_000L,
        val questionMs: Long = 10_000L,
        val answerMs: Long = 20_000L,
        val crossSummaryMs: Long = 90_000L,
        val freeTeamMs: Long = 240_000L,
        val freeTurnMs: Long = 30_000L,
        val closingMs: Long = 240_000L,
        val freeFirstTeam: String = "PRO",
        /** 主持串场单段预算（默认赛制不计入辩手用时，但也不能无限讲）。 */
        val hostSegmentMs: Long = 30_000L,
    )

    @Serializable
    data class SpeechDto(
        /** fixed 为固定稿；ai 由播放层生成短句，失败回退固定稿。 */
        val mode: String = "fixed",
        val generationTimeoutMs: Long = 20_000L,
        /** 首段音频起播前的硬超时（doc §5 默认 10s）。 */
        val firstAudioTimeoutMs: Long = 10_000L,
        /** 单个姿态过渡超时（doc §5 默认 3s）。 */
        val postureTimeoutMs: Long = 3_000L,
        /** 到时取消后等待播放层确认收尾的宽限。 */
        val drainGraceMs: Long = 600L,
        /** 发言失败重试次数（doc §5：重试一次 → 备用/跳过）。 */
        val failureRetries: Int = 1,
    )

    @Serializable
    data class CameraDto(
        val mode: String = "auto",
        val transitionMs: Int = 350,
        val minHoldMs: Long = 3_000L,
    )

    @Serializable
    data class PostureDto(
        val default: String = "seated",
        val standWhenSpeaking: Boolean = true,
        val micHand: String = "right",
    )

    @Serializable
    data class ParticipantDto(
        val id: String,
        val name: String = id,
        /** host / debater。 */
        val role: String = "debater",
        /** PRO / CON（辩手必填）。 */
        val team: String? = null,
        /** 辩位 1..4（辩手必填）。 */
        val position: Int? = null,
        val model: String = "",
        val personality: String = "",
        val voice: String = "",
    )

    /** 展开缺省字段后的完整配置（导出/复现用；校验经 [DebateConfigValidator]）。 */
    fun expanded(): DebateConfigDto = copy(
        rules = rules,
        speech = speech,
        participants = participants.map { p ->
            p.copy(
                name = p.name.ifBlank { p.id },
                role = p.role.ifBlank { "debater" },
            )
        },
    )
}

/** 配置校验失败的一条：字段路径 + 原因（doc §9「失败返回字段路径与原因」）。 */
data class ConfigIssue(val path: String, val reason: String) {
    override fun toString(): String = "$path: $reason"
}

/**
 * 配置校验（doc §9 导入需校验清单的 P0 子集）：恰好一位 HOST、每队四人、
 * 辩位 1..4 齐全无重复、ID 唯一、时间合法、模型来源可解析、模板受支持。
 * 「模型存在且加载就绪 / 动作与骨骼可用 / 布局有九个座位」在 app 层按资产
 * 实况校验（orchestrator 不碰文件系统）。
 */
object DebateConfigValidator {

    fun validate(dto: DebateConfigDto): List<ConfigIssue> {
        val issues = mutableListOf<ConfigIssue>()
        if (dto.schemaVersion != DebateConfigSchema.SCHEMA_VERSION) {
            issues += ConfigIssue("schemaVersion", "仅支持 ${DebateConfigSchema.SCHEMA_VERSION}，收到 ${dto.schemaVersion}")
        }
        if (dto.matchId.isBlank()) issues += ConfigIssue("matchId", "不能为空")
        if (dto.topic.isBlank()) issues += ConfigIssue("topic", "不能为空")
        if (dto.rules.template != DebateConfigSchema.TEMPLATE_FOUR_PERSON_ATTACK_V1) {
            issues += ConfigIssue(
                "rules.template",
                "未知模板「${dto.rules.template}」，当前支持 ${DebateConfigSchema.TEMPLATE_FOUR_PERSON_ATTACK_V1}",
            )
        }
        validateTimings(dto.rules, issues)
        validateSpeech(dto.speech, issues)
        validateParticipants(dto.participants, issues)
        validateScripts(dto, issues)
        return issues
    }

    private fun validateTimings(r: DebateConfigDto.RulesDto, issues: MutableList<ConfigIssue>) {
        val fields = mapOf(
            "rules.openingMs" to r.openingMs,
            "rules.crossRoundMs" to r.crossRoundMs,
            "rules.questionMs" to r.questionMs,
            "rules.answerMs" to r.answerMs,
            "rules.crossSummaryMs" to r.crossSummaryMs,
            "rules.freeTeamMs" to r.freeTeamMs,
            "rules.freeTurnMs" to r.freeTurnMs,
            "rules.closingMs" to r.closingMs,
            "rules.hostSegmentMs" to r.hostSegmentMs,
        )
        for ((path, v) in fields) {
            if (v <= 0) issues += ConfigIssue(path, "必须为正整数毫秒，收到 $v")
        }
        if (r.questionMs >= r.crossRoundMs) {
            issues += ConfigIssue("rules.questionMs", "提问上限须小于整轮墙钟")
        }
        if (r.answerMs >= r.crossRoundMs) {
            issues += ConfigIssue("rules.answerMs", "回答上限须小于整轮墙钟")
        }
        if (r.freeTurnMs > r.freeTeamMs) {
            issues += ConfigIssue("rules.freeTurnMs", "单次发言上限不能超过队伍总额")
        }
        when (r.freeFirstTeam.uppercase()) {
            "PRO", "CON" -> Unit
            else -> issues += ConfigIssue("rules.freeFirstTeam", "必须是 PRO 或 CON")
        }
    }

    private fun validateSpeech(s: DebateConfigDto.SpeechDto, issues: MutableList<ConfigIssue>) {
        if (s.mode !in setOf("fixed", "ai")) issues += ConfigIssue("speech.mode", "仅支持 fixed 或 ai")
        if (s.generationTimeoutMs <= 0) issues += ConfigIssue("speech.generationTimeoutMs", "必须为正整数毫秒")
        if (s.firstAudioTimeoutMs <= 0) issues += ConfigIssue("speech.firstAudioTimeoutMs", "必须为正整数毫秒")
        if (s.postureTimeoutMs <= 0) issues += ConfigIssue("speech.postureTimeoutMs", "必须为正整数毫秒")
        if (s.failureRetries < 0) issues += ConfigIssue("speech.failureRetries", "不能为负")
    }

    private fun validateParticipants(
        participants: List<DebateConfigDto.ParticipantDto>,
        issues: MutableList<ConfigIssue>,
    ) {
        if (participants.isEmpty()) {
            issues += ConfigIssue("participants", "不能为空：需要 1 位主持 + 双方各四位辩手")
            return
        }
        val seen = mutableSetOf<String>()
        for ((idx, p) in participants.withIndex()) {
            val path = "participants[$idx]"
            if (p.id.isBlank()) {
                issues += ConfigIssue("$path.id", "不能为空")
                continue
            }
            if (!seen.add(p.id)) issues += ConfigIssue("$path.id", "重复的业务 ID「${p.id}」")
            when (p.role) {
                "host" -> {
                    if (p.id != DebateIds.HOST) {
                        issues += ConfigIssue("$path.id", "host 角色的 ID 必须是 ${DebateIds.HOST}")
                    }
                    if (p.team != null || p.position != null) {
                        issues += ConfigIssue("$path.team", "host 不应有队伍/辩位")
                    }
                }
                "debater" -> {
                    val team = DebateIds.teamOf(p.id)
                    if (team == null) {
                        issues += ConfigIssue("$path.id", "辩手 ID 必须是 PRO1..PRO4 / CON1..CON4，收到「${p.id}」")
                    } else {
                        val expected = if (team == DebateTeam.PRO) "PRO" else "CON"
                        if (!p.team.isNullOrBlank() && !p.team.equals(expected, ignoreCase = true)) {
                            issues += ConfigIssue("$path.team", "与 ID 推导的队伍 $expected 不一致")
                        }
                        val pos = DebateIds.positionOf(p.id)
                        if (p.position != null && p.position != pos) {
                            issues += ConfigIssue("$path.position", "与 ID 推导的辩位 $pos 不一致")
                        }
                    }
                }
                else -> issues += ConfigIssue("$path.role", "必须是 host 或 debater，收到「${p.role}」")
            }
            if (p.model.isBlank()) issues += ConfigIssue("$path.model", "不能为空（asset: 或 file: 前缀）")
            if (!p.model.isBlank() && !p.model.startsWith("asset:") && !p.model.startsWith("file:")) {
                issues += ConfigIssue("$path.model", "必须以 asset: 或 file: 开头")
            }
        }
        val hosts = participants.count { it.role == "host" }
        if (hosts != 1) issues += ConfigIssue("participants", "必须恰好一位 host，收到 $hosts")
        for (team in DebateTeam.entries) {
            val prefix = if (team == DebateTeam.PRO) "PRO" else "CON"
            val members = participants.filter { DebateIds.teamOf(it.id) == team }
            if (members.size != DebateIds.POSITIONS) {
                issues += ConfigIssue("participants", "$prefix 队必须恰好 ${DebateIds.POSITIONS} 人，收到 ${members.size}")
            }
            val positions = members.mapNotNull { DebateIds.positionOf(it.id) }.sorted()
            if (positions != (1..DebateIds.POSITIONS).toList()) {
                issues += ConfigIssue("participants", "$prefix 队辩位 1..${DebateIds.POSITIONS} 不齐全或有重复：$positions")
            }
        }
    }

    private fun validateScripts(dto: DebateConfigDto, issues: MutableList<ConfigIssue>) {
        for ((pid, byPhase) in dto.scripts) {
            if (dto.participants.none { it.id == pid }) {
                issues += ConfigIssue("scripts.$pid", "引用了不存在的参与者")
            }
            for (phase in byPhase.keys) {
                runCatching { DebatePhase.valueOf(phase) }
                    .onFailure { issues += ConfigIssue("scripts.$pid.$phase", "未知阶段名") }
            }
        }
    }
}

/** JSON ⇄ DTO。lenient 关（坏 JSON 直接失败，doc §13.1 要求覆盖坏 JSON 用例）。 */
object DebateConfigParser {
    val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun parse(text: String): DebateConfigDto = json.decodeFromString<DebateConfigDto>(text)

    fun encode(dto: DebateConfigDto): String = json.encodeToString(DebateConfigDto.serializer(), dto.expanded())

    /** 解析 + 校验一步走；成功返回编译输入，失败返回全部 [ConfigIssue]。 */
    fun parseAndValidate(text: String): Result<DebateConfigDto> = runCatching { parse(text) }.mapCatching { dto ->
        val issues = DebateConfigValidator.validate(dto)
        if (issues.isNotEmpty()) throw DebateConfigException(issues)
        dto
    }
}

class DebateConfigException(val issues: List<ConfigIssue>) :
    IllegalArgumentException("辩论配置校验失败:\n" + issues.joinToString("\n") { "  - $it" })
