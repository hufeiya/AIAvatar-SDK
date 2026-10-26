package com.neethu.aiavatar_sdk.multiavatar.show

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 团播节目单（docs/multi-dance-feasibility.md §4.4）：一份 JSON 描述整场演出的
 * 幕次序列（切机位/走位/独舞/齐舞/谢幕/停顿/串场词/配乐）。手写资产，
 * **解析失败整单拒绝**不做容错续跑——早失败优于演一半。
 *
 * `clip` 写类别目录下的文件名（可省 `.vrma`、可省 `animations/` 前缀），
 * [resolveClipAsset] 归一成完整 assets 路径；白名单校验在控制器侧（要扫 assets）。
 */
@Serializable
data class ShowScript(
    val version: Int = DEFAULT_VERSION,
    val title: String = "未命名团播",
    /** 背景音乐（assets 相对路径，如 `music/show.mp3`；music action=start 播它）。 */
    val music: String? = null,
    val acts: List<ShowAct>,
) {
    /** 幕次 clip 路径归一（不动其余字段）。 */
    fun resolveClips(): ShowScript = copy(acts = acts.map { act ->
        if (act.clip == null) act else act.copy(clip = resolveClipAsset(act.clip))
    })

    companion object {
        const val DEFAULT_VERSION = 1
    }
}

/** 幕类型词表（§4.4 act 表；P1 增 fx 特效插幕；P2 道具系统增 activity 活动幕）。 */
object ActTypes {
    const val DANCE = "dance"
    const val GROUP_DANCE = "group_dance"
    const val BOW = "bow"
    const val SAY = "say"
    const val MOVE = "move"
    const val CAMERA = "camera"
    const val MUSIC = "music"
    const val WAIT = "wait"
    const val FX = "fx"
    const val ACTIVITY = "activity"

    val ALL = setOf(DANCE, GROUP_DANCE, BOW, SAY, MOVE, CAMERA, MUSIC, WAIT, FX, ACTIVITY)
}

/** 机位词表（STAGE_CMD cam 的同名子集）。 */
object CameraNames {
    val KNOWN = setOf("closeup", "macro", "medium", "full", "long", "over", "follow", "group")
}

/** 观众席行为词表（§4.4：gaze 默认恒开）。 */
object FanActions {
    const val GAZE = "gaze"
    const val CLAP = "clap"
    const val SWAY = "sway"
}

/** music 幕的 action 词表（P1 §4.4）：start/stop 直接控制；duck/full 是音量两档。 */
object MusicActions {
    const val START = "start"
    const val STOP = "stop"
    const val DUCK = "duck"
    const val FULL = "full"
    val ALL = setOf(START, STOP, DUCK, FULL)

    /** duck 档音量（串场词让主旋律，§4.8 的手动 duck act）。 */
    const val DUCK_VOLUME = 0.35f

    /** 背景音乐常驻音量（开演 start 的默认档，压在 TTS 之下）。 */
    const val BASE_VOLUME = 0.65f
}

@Serializable
data class ShowAct(
    val type: String,
    /** dance/say/move 的演员；camera 幕的机位锚定目标（演员 id）。 */
    val performer: String? = null,
    /** dance/group_dance 的曲目（类别目录/文件名，可省前缀与 .vrma）。 */
    val clip: String? = null,
    /** wait/group_dance 的时长（秒）；dance 的显式兜底时长（缺省用引擎实测）。 */
    val seconds: Float? = null,
    /** camera 幕的机位（词表见 [CameraNames]）。 */
    val shot: String? = null,
    /** dance/group_dance/bow 幕内的机位切换（同词表）。 */
    val camera: String? = null,
    /** camera 幕的目标演员 id；move 幕的目标锚点名（词表 = home.anchors.json）。 */
    val target: String? = null,
    /** say 的串场词。 */
    val text: String? = null,
    /** dance 的观众席行为（["clap"]；gaze 恒开无需声明）。 */
    val fans: List<String>? = null,
    /** dance 开跳前的表演者串场词（P1：接 TTS，说完才起舞）。 */
    val line: String? = null,
    /** music 幕的动作（词表见 [MusicActions]）。 */
    val action: String? = null,
    /**
     * 幕次道具（P2 配饰生态，docs/accessory-feasibility.md §5.6）：accessory
     * id 列表（目录见 assets/accessories/accessory.json）。dance/group_dance/
     * bow 幕开演时给表演者挂上、收幕摘下——确定性导演语义，不走 LLM；
     * 与动画绑定表（bindings.json 自动出道具）正交：同 id 已在场则跳过挂载。
     */
    val props: List<String>? = null,
    /**
     * fx 幕的特效预设 id（P1 docs/vfx-feasibility.md §六：目录见
     * assets/vfx/presets.json）。特效插幕不打断编舞（fire-and-forget 播放 +
     * hold [seconds] 后停，§4.4 对齐 enqueueAct 语义）。
     */
    val fx: String? = null,
    /**
     * fx 幕的锚定骨骼名（可选；缺省：performer 给了 = 锚其头顶世界位快照，
     * 没给 = 舞台中心）。给了 bone 则走骨骼锚定（发射器跟随、已存活粒子留
     * 世界系），如 lightstick_spark 配 rightHand。
     */
    val bone: String? = null,
    /**
     * activity 幕的活动 id（P2 道具系统，docs/prop-system-feasibility.md
     * §7-P2.4 团播多人活动）：目录见 assets/activities/activities.json。
     * 幕开始 = 活动配方确定性落地（目录道具开幕挂上→VFX→动画起播,loop 幕
     * hold [seconds] / one-shot 幕等播完,收幕逆序收净）；per-runtime 道具
     * 隔离保证双人同款活动各持各的道具不互抢。导演语义,不走 LLM。
     */
    val activity: String? = null,
)

/**
 * 解析+校验：JSON 形状、act 类型词表、各类型必填字段、枚举词表、数值合法性
 * 全在这里过；失败返回原因行（含幕序号），调试通道原样回显。
 * 演员在不在场（cast）、曲目在不在白名单，依赖运行时环境，归控制器开演前校验。
 */
object ShowScriptParser {

    private val json = Json {
        // 手写节目单：未知键多半是手误，宁可报错也不静默吞掉
        ignoreUnknownKeys = false
    }

    /** @return 节目单，或 IllegalArgumentException（原因行含幕序号）。 */
    fun parse(text: String): Result<ShowScript> = runCatching {
        val script = json.decodeFromString<ShowScript>(text.trim())
        validate(script).getOrThrow()
        script
    }.recoverCatching { e ->
        throw IllegalArgumentException("节目单拒绝：${e.message ?: e.javaClass.simpleName}", e)
    }

    fun validate(script: ShowScript): Result<ShowScript> {
        val errors = mutableListOf<String>()
        if (script.version != ShowScript.DEFAULT_VERSION) {
            errors += "version=${script.version} 不支持（仅 ${ShowScript.DEFAULT_VERSION}）"
        }
        if (script.acts.isEmpty()) errors += "acts 为空"
        script.acts.forEachIndexed { i, act ->
            val at = "act ${i + 1} (${act.type})"
            if (act.type !in ActTypes.ALL) {
                errors += "$at: 未知类型（可用 ${ActTypes.ALL.sorted().joinToString("/")}）"
                return@forEachIndexed
            }
            when (act.type) {
                ActTypes.DANCE -> {
                    if (act.performer.isNullOrBlank()) errors += "$at: 缺 performer"
                    if (act.clip.isNullOrBlank()) errors += "$at: 缺 clip"
                    act.fans.orEmpty()
                        .filter { it !in setOf(FanActions.GAZE, FanActions.CLAP, FanActions.SWAY) }
                        .forEach { errors += "$at: 未知观众行为 '$it'" }
                }
                ActTypes.GROUP_DANCE -> {
                    if (act.clip.isNullOrBlank()) errors += "$at: 缺 clip"
                    if (act.seconds == null || act.seconds <= 0f) errors += "$at: 缺 seconds（齐舞时长）"
                }
                ActTypes.BOW -> Unit
                ActTypes.SAY -> {
                    if (act.performer.isNullOrBlank()) errors += "$at: 缺 performer"
                    if (act.text.isNullOrBlank()) errors += "$at: 缺 text"
                }
                ActTypes.MOVE -> {
                    if (act.performer.isNullOrBlank()) errors += "$at: 缺 performer"
                    if (act.target.isNullOrBlank()) errors += "$at: 缺 target（锚点名）"
                }
                ActTypes.CAMERA -> if (act.shot.isNullOrBlank()) errors += "$at: 缺 shot"
                ActTypes.MUSIC -> {
                    if (act.action !in MusicActions.ALL) {
                        errors += "$at: 缺/未知 action（可用 ${MusicActions.ALL.sorted().joinToString("/") }）"
                    } else if (act.action == MusicActions.START && script.music.isNullOrBlank()) {
                        errors += "$at: action=start 但节目单未声明 music 文件"
                    }
                }
                ActTypes.WAIT -> if (act.seconds == null || act.seconds <= 0f) {
                    errors += "$at: 缺/非法 seconds"
                }
                ActTypes.FX -> {
                    if (act.fx.isNullOrBlank()) errors += "$at: 缺 fx（预设 id，见 assets/vfx/presets.json）"
                    if (act.seconds != null && act.seconds <= 0f) errors += "$at: 非法 seconds（>0 或缺省）"
                }
                ActTypes.ACTIVITY -> {
                    if (act.performer.isNullOrBlank()) errors += "$at: 缺 performer"
                    if (act.activity.isNullOrBlank()) errors += "$at: 缺 activity（活动 id，见 assets/activities/activities.json）"
                    // loop 活动幕的 hold 时长；one-shot 幕忽略（引擎时长自理）
                    if (act.seconds != null && act.seconds <= 0f) errors += "$at: 非法 seconds（>0 或缺省）"
                }
            }
            // 幕内机位与 camera 幕的 shot 共用词表
            val shot = act.shot ?: act.camera
            if (shot != null && shot !in CameraNames.KNOWN) errors += "$at: 未知机位 '$shot'"
            // 道具 id 形状校验（在不在目录归控制器开演前校验——运行时环境）
            if (act.type !in PROPS_ACT_TYPES && act.props != null) {
                errors += "$at: props 仅支持 ${PROPS_ACT_TYPES.sorted().joinToString("/")}"
            }
            act.props.orEmpty().forEach { p ->
                if (p.isBlank()) errors += "$at: props 含空白 id"
            }
        }
        return if (errors.isEmpty()) Result.success(script)
        else Result.failure(IllegalArgumentException(errors.joinToString("; ")))
    }

    /** 支持声明道具的幕型（表演类；camera/music/wait 等纯调度幕不收）。 */
    val PROPS_ACT_TYPES = setOf(ActTypes.DANCE, ActTypes.GROUP_DANCE, ActTypes.BOW)
}

/** clip 路径归一：`08_x/Y` / `animations/08_x/Y.vrma` → `animations/08_x/Y.vrma`。 */
fun resolveClipAsset(clip: String): String {
    val rel = clip.removePrefix("animations/").removeSuffix(".vrma")
    return "animations/$rel.vrma"
}

/** 幕次时长粗估（dance/bow 按默认秒）——`dance load` 回显用。 */
fun estimateSeconds(script: ShowScript): Float = script.acts.sumOf { act ->
    when (act.type) {
        ActTypes.WAIT, ActTypes.GROUP_DANCE -> (act.seconds ?: 0f).toDouble()
        ActTypes.DANCE -> (act.seconds ?: DEFAULT_DANCE_SECONDS).toDouble()
        ActTypes.ACTIVITY -> (act.seconds ?: DEFAULT_ACTIVITY_SECONDS).toDouble()
        else -> DEFAULT_ACT_SECONDS.toDouble()
    }
}.toFloat()

const val DEFAULT_DANCE_SECONDS = 8f
const val DEFAULT_ACT_SECONDS = 3f

/** activity 幕缺省时长（秒；loop 活动的 hold 时长，节目单可用 seconds 覆盖）。 */
const val DEFAULT_ACTIVITY_SECONDS = 12f
