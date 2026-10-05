package com.neethu.aiavatar_sdk.skills

import android.os.SystemClock
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.skill.RpsSkill
import com.neethu.orchestrator.skill.SkillHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 技能资产的 assets 目录（docs/rps-skill-feasibility.md §3）：猜拳手势三件套
 * 放在这里而不进 LLM 动作目录——[buildLlmActionCatalog 的调用方][com.neethu.aiavatar_sdk.buildLlmActionCatalog]
 * 用 [isSkillGestureAsset] 排除，`<act:>` 协议块永远看不到它们；技能经
 * session 的非广告通道（playGestureFile）直接按路径播放。
 */
const val SKILL_GESTURE_DIR = "11_技能_猜拳"

/** 相对/绝对路径是否属于技能目录（动作目录扫描排除用）。 */
fun isSkillGestureAsset(path: String): Boolean = path.contains("$SKILL_GESTURE_DIR/")

/** 猜拳三件套的 assets 路径（占位手势，后续替换 VRMA 只动文件、路径不变）。 */
val rpsHandAssets: Map<RpsSkill.Hand, String> = mapOf(
    RpsSkill.Hand.ROCK to "animations/$SKILL_GESTURE_DIR/gesture_rock.vrma",
    RpsSkill.Hand.SCISSORS to "animations/$SKILL_GESTURE_DIR/gesture_scissor.vrma",
    RpsSkill.Hand.PAPER to "animations/$SKILL_GESTURE_DIR/gesture_paper.vrma",
)

/**
 * demo 的技能能力缝实现（docs/rps-skill-feasibility.md §4.3）。组合时序上
 * session / videoTracker / freeSpeech 都晚于 AiChatController 创建，全部用
 * provider 懒引用——调用时（技能真正跑起来时）它们一定已就绪。
 */
class DemoSkillHost(private val scope: CoroutineScope) : SkillHost {

    var sessionProvider: () -> AvatarSession? = { null }
    var snapshotProvider: () -> String? = { null }

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

    override fun setVadHangover(ms: Long) {
        vadTuner?.invoke(ms)
    }

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun randomInt(bound: Int): Int = if (bound <= 0) 0 else Random.nextInt(bound)
}
