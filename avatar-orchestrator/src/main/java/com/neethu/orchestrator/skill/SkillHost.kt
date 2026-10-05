package com.neethu.orchestrator.skill

/**
 * 技能框架的能力缝（docs/rps-skill-feasibility.md §4.2）：技能需要的全部外部
 * 能力都经这个接口取用——orchestrator 只定义缝，实现由集成方（demo 的
 * DemoSkillHost）注入。这样技能纯逻辑可 JVM 单测，且不感知设备细节
 * （相机/麦克风/渲染控制器）。
 *
 * null host（headless / 未接线）时 [SkillRegistry] 用 [NopSkillHost] 兜底：
 * 技能状态机照常运转，能力调用全部落空。
 */
interface SkillHost {
    /**
     * 播放一个动作 clip（assets 相对路径或绝对文件路径）。技能专用通道：
     * **不经** LLM 动作目录（`<act:>` 广告位），不会出现在协议块里。
     * 非循环语义，播完由引擎自动回待机。
     */
    fun playGestureFile(path: String, loop: Boolean = false): Boolean

    /** 无 LLM 直通语音（同 [com.neethu.orchestrator.session.AvatarSession.speak] 语义）。 */
    fun speak(text: String)

    /** 发起一轮正常对话回合（文本进历史，图片只挂本轮请求）。 */
    fun sendTurn(text: String, images: List<String>)

    /** 当前相机抓拍缓存里的一帧（data URL）；无缓存/相机未开返回 null。 */
    fun snapshotImage(): String?

    /** 采音中实时调整 VAD 句尾静默悬停（技能态收短、退出恢复）。 */
    fun setVadHangover(ms: Long)

    /** 用户在设置页配置的句尾悬停默认值（技能退出时恢复用）。 */
    val defaultVadHangoverMs: Long

    fun nowMs(): Long

    /** 均匀随机 [0, bound)；技能本地的随机决策（猜拳出什么）。 */
    fun randomInt(bound: Int): Int
}

/** host 未接线时的兜底实现：全部落空，绝不抛异常。 */
object NopSkillHost : SkillHost {
    override val defaultVadHangoverMs: Long = 800L
    override fun playGestureFile(path: String, loop: Boolean): Boolean = false
    override fun speak(text: String) = Unit
    override fun sendTurn(text: String, images: List<String>) = Unit
    override fun snapshotImage(): String? = null
    override fun setVadHangover(ms: Long) = Unit
    override fun nowMs(): Long = 0L
    override fun randomInt(bound: Int): Int = 0
}
