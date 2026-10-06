package com.neethu.aiavatar_sdk.skills

import android.os.SystemClock
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.skill.LookDir
import com.neethu.orchestrator.skill.LookHereSkill
import com.neethu.orchestrator.skill.RpsSkill
import com.neethu.orchestrator.skill.SkillHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 技能手势资产的 assets 目录（docs/rps-skill-feasibility.md §3、
 * docs/lookhere-skill-feasibility.md §6）：技能手势放在这里而不进 LLM 动作
 * 目录——[buildLlmActionCatalog 的调用方][com.neethu.aiavatar_sdk.buildLlmActionCatalog]
 * 用 [isSkillGestureAsset] 排除，`<act:>` 协议块永远看不到它们；技能经
 * session 的非广告通道（playGestureFile）直接按路径播放。
 */
val SKILL_GESTURE_DIRS = listOf("11_技能_猜拳", "12_技能_看这边")

/** 相对/绝对路径是否属于技能目录（动作目录扫描排除用；子串命中 assets 相对路径与外置绝对路径）。 */
fun isSkillGestureAsset(path: String): Boolean = SKILL_GESTURE_DIRS.any { path.contains("$it/") }

/** 猜拳三件套的 assets 路径（占位手势，后续替换 VRMA 只动文件、路径不变）。 */
val rpsHandAssets: Map<RpsSkill.Hand, String> = mapOf(
    RpsSkill.Hand.ROCK to "animations/11_技能_猜拳/gesture_rock.vrma",
    RpsSkill.Hand.SCISSORS to "animations/11_技能_猜拳/gesture_scissor.vrma",
    RpsSkill.Hand.PAPER to "animations/11_技能_猜拳/gesture_paper.vrma",
)

/** 「看这边」四方向手势的 assets 路径。 */
val lookHereAssets: Map<LookDir, String> = mapOf(
    LookDir.UP to "animations/12_技能_看这边/gesture_up.vrma",
    LookDir.DOWN to "animations/12_技能_看这边/gesture_down.vrma",
    LookDir.LEFT to "animations/12_技能_看这边/gesture_left.vrma",
    LookDir.RIGHT to "animations/12_技能_看这边/gesture_right.vrma",
)

/**
 * demo 的技能能力缝实现（docs/rps-skill-feasibility.md §4.3）。组合时序上
 * session / videoTracker / freeSpeech 都晚于 AiChatController 创建，全部用
 * provider 懒引用——调用时（技能真正跑起来时）它们一定已就绪。
 */
class DemoSkillHost(private val scope: CoroutineScope) : SkillHost {

    var sessionProvider: () -> AvatarSession? = { null }
    var snapshotProvider: () -> String? = { null }

    /** 最新帧 provider（「看这边」判负瞬间的懵逼表情要新鲜度）。 */
    var snapshotLatestProvider: () -> String? = { null }

    /** 技能态 VAD 悬停调整：接 FreeSpeechController.applyTuning（保留用户的起音/打断门限）。 */
    var vadTuner: ((Long) -> Unit)? = null
    var defaultHangoverMsProvider: () -> Long = { 800L }

    override val defaultVadHangoverMs: Long get() = defaultHangoverMsProvider()

    override fun playGestureFile(path: String, loop: Boolean): Boolean =
        sessionProvider()?.playGestureFile(path) ?: false

    override fun speak(text: String) {
        val s = sessionProvider() ?: return
        scope.launch { runCatching { s.speak(text) } }
    }

    override fun sendTurn(text: String, images: List<String>) {
        sessionProvider()?.send(text, images)
    }

    override fun snapshotImage(): String? = snapshotProvider()

    override fun snapshotLatest(): String? = snapshotLatestProvider()

    override fun setVadHangover(ms: Long) {
        vadTuner?.invoke(ms)
    }

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun randomInt(bound: Int): Int = if (bound <= 0) 0 else Random.nextInt(bound)
}
