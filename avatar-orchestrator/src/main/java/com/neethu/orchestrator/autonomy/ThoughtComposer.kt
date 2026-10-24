package com.neethu.orchestrator.autonomy

import com.neethu.corelib.Lang
import kotlin.random.Random

/**
 * 思考/问候文本的模板池（docs/autonomy-feasibility.md §6.1/§5）：状态×时段×
 * 记忆槽位，中文/英文双份，纯本地零成本。槽位模板里的 `{m}` 由调用方填记忆
 * 事实（截短过），无记忆走无记忆分支——绝不生成「想起来你说过{null}」
 * （§10.7 冷启动坑）。问候文本可带挥手微笑标签（调用方拼 `<emo:>` `<act:>`）。
 */
class ThoughtComposer(private val lang: Lang, private val rng: Random = Random.Default) {

    /**
     * 状态转移/长停留尾段的内心独白（≤20 字）：[memoryFact] 非空时按概率
     * 走记忆槽模板（「想你了才开口」生命感），否则状态池。无池可命中返回
     * null（调用方就不显示）。
     */
    fun thoughtFor(state: AutonomyStateKind, hour: Int, memoryFact: String?): String? {
        val texts = textsOf(lang)
        if (memoryFact != null && memoryFact.isNotBlank() && rng.nextFloat() < MEMORY_SLOT_PROBABILITY) {
            return texts.memoryThoughts.random(rng)
                .replace("{m}", memoryFact)
                .takeIf { it.isNotBlank() }
        }
        val pool = texts.thoughtsByDaypart(hour, state)
        return pool.randomOrNull(rng)
    }

    /**
     * 问候的「内心版」——开口前 1~2s 先弹的思考气泡（§5 与思考气泡联动）。
     */
    fun greetingThought(): String = textsOf(lang).greetingThoughts.random(rng)

    /**
     * 问候正文（≤2 句，不含标签）：[memoryFact] 命中走记忆模板（问候的内容
     * 素材），否则通用池（时段无关）。返回 null = 无池命中（调用方放弃本次）。
     */
    fun greetingText(hour: Int, memoryFact: String?): String? {
        val texts = textsOf(lang)
        val pool = if (memoryFact.isNullOrBlank()) {
            texts.greetingPlain(hour)
        } else {
            texts.greetingMemory.map { it.replace("{m}", memoryFact) }
        }
        return pool.randomOrNull(rng)
    }

    private companion object {
        /** 记忆槽触发概率：有记忆也不必每条思考都提往事。 */
        const val MEMORY_SLOT_PROBABILITY = 0.35f
    }
}

/** 单语言的模板集（zh/en 各一份，[textsOf] 按语言取）。 */
internal class AutonomyTexts(
    /** 状态 → 三个时段（白天/傍晚/深夜）的思考池；缺省池兜底。 */
    val thoughtsByDaypart: (hour: Int, state: AutonomyStateKind) -> List<String>,
    val memoryThoughts: List<String>,
    val greetingThoughts: List<String>,
    /** 无记忆问候池（按时段细分，可能为空 = 无命中）。 */
    val greetingPlain: (hour: Int) -> List<String>,
    /** 记忆槽问候模板（`{m}` 占位）。 */
    val greetingMemory: List<String>,
)

internal fun textsOf(lang: Lang): AutonomyTexts = if (lang == Lang.EN) TEXTS_EN else TEXTS_ZH

// ── 中文模板池 ──────────────────────────────────────────────────────────

private val TEXTS_ZH = AutonomyTexts(
    thoughtsByDaypart = { hour, state ->
        when {
            hour in 0..5 -> when (state) { // 深夜
                AutonomyStateKind.LYING -> listOf("困了…再睡一会儿", "梦还没做完呢")
                AutonomyStateKind.SITTING -> listOf("夜深了…有点安静", "睡不着,坐一会儿")
                AutonomyStateKind.LOOKING -> listOf("窗外是星星吗", "月亮好亮啊")
                else -> listOf("好困啊…", "夜真安静")
            }

            hour in 6..11 -> when (state) { // 上午
                AutonomyStateKind.SITTING -> listOf("先把今天的事列一列", "看会儿书吧")
                AutonomyStateKind.LOOKING -> listOf("今天天气怎么样呢", "早上好,新的一天")
                AutonomyStateKind.WANDER -> listOf("去那边看看~", "咦,那边有什么")
                AutonomyStateKind.LYING -> listOf("再赖一会儿床…")
                else -> listOf("今天做点什么呢")
            }

            hour in 17..22 -> when (state) { // 傍晚/晚间
                AutonomyStateKind.LOOKING -> listOf("天色变了呢", "晚霞好好看")
                AutonomyStateKind.SITTING -> listOf("看会儿电视吧", "晚上想吃什么好呢")
                AutonomyStateKind.LYING -> listOf("今天也辛苦啦", "就这样躺一会儿…")
                AutonomyStateKind.WANDER -> listOf("晚上走一走", "去窗边看看")
                else -> listOf("晚上了呢…")
            }

            else -> when (state) { // 午后
                AutonomyStateKind.SITTING -> listOf("先把昨天那章看完…", "认真一回吧")
                AutonomyStateKind.LOOKING -> listOf("外面天气怎么样呢", "发会儿呆也好")
                AutonomyStateKind.WANDER -> listOf("去那边转转", "换个地方待待~")
                AutonomyStateKind.LYING -> listOf("午睡一会儿吧…")
                else -> listOf("有点闲呢…")
            }
        }
    },
    memoryThoughts = listOf(
        "还记得「{m}」呢…",
        "想起ta说过{m}…",
        "「{m}」…后来怎么样了",
    ),
    greetingThoughts = listOf(
        "要不要问问ta今天怎么样",
        "好久没说话了…打个招呼?",
        "有点想ta了…",
        "ta在忙吗…喊一下试试",
    ),
    greetingPlain = { hour ->
        when {
            hour in 5..11 -> listOf("早上好呀!今天也要元气满满哦", "早啊,吃早饭了吗?")
            hour in 11..14 -> listOf("中午啦,吃饭了吗?", "午安!下午一起玩吗")
            hour in 17..22 -> listOf("晚上好呀,今天过得怎么样?", "晚上啦,来陪我聊聊天嘛")
            else -> listOf("还没睡呢?要注意休息哦", "这么晚了,睡不着吗?")
        }
    },
    greetingMemory = listOf(
        "刚才想起来你说过{m},后来怎么样了?",
        "突然想起你提过{m},还好吗?",
        "诶,我想起来你说过{m}——最近有新进展吗?",
    ),
)

// ── 英文模板池 ──────────────────────────────────────────────────────────

private val TEXTS_EN = AutonomyTexts(
    thoughtsByDaypart = { hour, state ->
        when {
            hour in 0..5 -> when (state) {
                AutonomyStateKind.LYING -> listOf("Sleepy… just a bit longer", "Still dreaming…")
                AutonomyStateKind.SITTING -> listOf("So quiet at night…", "Can't sleep, sitting a bit")
                AutonomyStateKind.LOOKING -> listOf("Are those stars outside?", "The moon is bright")
                else -> listOf("So sleepy…", "What a quiet night")
            }

            hour in 6..11 -> when (state) {
                AutonomyStateKind.SITTING -> listOf("Let me plan today", "Some reading, maybe")
                AutonomyStateKind.LOOKING -> listOf("How's the weather today?", "Good morning, new day")
                AutonomyStateKind.WANDER -> listOf("Let me look around", "What's over there?")
                AutonomyStateKind.LYING -> listOf("Five more minutes…")
                else -> listOf("What to do today?")
            }

            hour in 17..22 -> when (state) {
                AutonomyStateKind.LOOKING -> listOf("The sky is changing", "Lovely sunset")
                AutonomyStateKind.SITTING -> listOf("TV time, maybe", "What's for dinner?")
                AutonomyStateKind.LYING -> listOf("What a day…", "Just lying here a bit")
                AutonomyStateKind.WANDER -> listOf("An evening stroll", "Let's see the window")
                else -> listOf("Evening already…")
            }

            else -> when (state) {
                AutonomyStateKind.SITTING -> listOf("Finish that chapter first…", "Focus for once")
                AutonomyStateKind.LOOKING -> listOf("How's it like outside?", "Daydreaming a little")
                AutonomyStateKind.WANDER -> listOf("Let me wander a bit", "Change of scenery~")
                AutonomyStateKind.LYING -> listOf("A little nap…")
                else -> listOf("A bit bored…")
            }
        }
    },
    memoryThoughts = listOf(
        "Remember \"{m}\"…",
        "They mentioned {m}…",
        "\"{m}\"… I wonder how it went",
    ),
    greetingThoughts = listOf(
        "Should I ask how their day went?",
        "We haven't talked in a while…",
        "I kind of miss them…",
        "Are they busy? Let me say hi",
    ),
    greetingPlain = { hour ->
        when {
            hour in 5..11 -> listOf("Good morning! Ready for the day?", "Morning! Had breakfast?")
            hour in 11..14 -> listOf("It's noon — eaten yet?", "Afternoon! Want to chat?")
            hour in 17..22 -> listOf("Evening! How was your day?", "It's night — keep me company?")
            else -> listOf("Still up? Don't stay up too late", "Can't sleep, huh?")
        }
    },
    greetingMemory = listOf(
        "I just remembered you mentioned {m} — how did it go?",
        "It suddenly hit me: {m}. How are things?",
        "Hey, you once said {m} — any news on that?",
    ),
)
