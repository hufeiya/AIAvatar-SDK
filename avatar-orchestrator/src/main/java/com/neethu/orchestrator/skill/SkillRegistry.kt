package com.neethu.orchestrator.skill

import com.neethu.aiadapter.model.ToolSpec
import com.neethu.orchestrator.agent.ToolExecutor
import com.neethu.orchestrator.agent.ToolRegistry
import com.neethu.orchestrator.session.AvatarEvent

/**
 * 技能注册表：AvatarSession 持有，事件缝的汇集点（docs/rps-skill-feasibility.md §4.3）。
 *
 * - app 侧喂事件：语音文本 [onUtterance]、VAD 时机 [onVadUtterance]、相机手势
 *   [onUserGesture]、调试 [debug]；
 * - session 侧喂回合结果：[onTurnCompleted] / [onTurnFailed] / [onInterrupted]；
 * - session 每轮组请求时读 [activeDirective]。
 *
 * 广播语义：事件发给全部注册技能，激活与否由技能内部状态守门；[activeDirective]
 * 取第一个激活技能的指令。主线程调用（app scope/main dispatcher）。
 *
 * 工具缝（docs/agent-feasibility.md §4.7）：注册技能时把它的 [AvatarSkill.tools]
 * 挂进 [ToolRegistry]（同名后注册覆盖）；技能**转激活**时重挂、退场/被顶替/
 * 反注册时摘除——工具广告位跟随激活态。null registry（headless）整条缝静默。
 */
class SkillRegistry(
    host: SkillHost?,
    private val tools: ToolRegistry? = null,
    private val emit: (AvatarEvent) -> Unit = {},
) {
    private val skills = LinkedHashMap<String, AvatarSkill>()
    private val effectiveHost: SkillHost = host ?: NopSkillHost
    /** skillId → 该技能已注册的 (spec, executor) 对（摘除时校验所有权，防同名误伤）。 */
    private val skillTools = LinkedHashMap<String, List<Pair<ToolSpec, ToolExecutor>>>()

    /** 同 id 覆盖注册（会话重建后重注册同一实例是常态）。 */
    fun register(skill: AvatarSkill) {
        skills[skill.id] = skill
        // 静默技能也可能直接处于激活态（重注册同一实例）：按当前状态决定挂不挂
        if (skill.isActive) hookSkillTools(skill)
    }

    fun unregister(id: String) {
        skills.remove(id)?.let { unhookSkillTools(it) }
    }

    fun find(id: String): AvatarSkill? = skills[id]

    val bodyOccupied: Boolean get() = skills.values.any { it.isActive && it.occupiesBody }

    /** 第一个激活技能的指令行；无激活技能返回 null。 */
    fun activeDirective(): String? {
        for (skill in skills.values) {
            if (!skill.isActive) continue
            val directive = skill.turnDirective(contextFor(skill)) ?: continue
            return directive
        }
        return null
    }

    /**
     * 一句识别文本到达；返回是否有技能消费了它（消费=调用方跳过默认发送）。
     * 广播后做**单活跃收口**：一局之内一句话让两个技能同时转活（比如游戏
     * 中说"猜拳"唤醒了 RpsSkill），后激活者胜出、先激活的走 onExit 副作用
     * 退场——技能互不知晓，仲裁收在框架里。
     */
    fun onUtterance(text: String): Boolean {
        val before = skills.values.filterTo(ArrayList()) { it.isActive }
        val consumed = skills.values.any { skill -> skill.onUtterance(text, contextFor(skill)) }
        val after = skills.values.filter { it.isActive }
        if (after.size > 1) {
            val keep = after.lastOrNull { it !in before } ?: after.first()
            for (skill in after) if (skill !== keep) {
                skill.onExit(contextFor(skill))
                unhookSkillTools(skill)
            }
        }
        // 激活态变化跟随工具广告位：新激活的挂上（退场的已在上面摘除）
        for (skill in after) if (skill !in before) hookSkillTools(skill)
        return consumed
    }

    /** VAD 句尾快路径（ASR 之前的时机信号）。 */
    fun onVadUtterance(wavMs: Long) {
        for (skill in skills.values) skill.onVadUtterance(wavMs, contextFor(skill))
    }

    /** 相机手势观测（P2 MediaPipe 缝）。 */
    fun onUserGesture(gesture: Int) {
        for (skill in skills.values) skill.onUserGesture(gesture, contextFor(skill))
    }

    /** 头部姿态观测（「看这边」缝，度）。 */
    fun onHeadPose(yawDeg: Float, pitchDeg: Float) {
        for (skill in skills.values) skill.onHeadPose(yawDeg, pitchDeg, contextFor(skill))
    }

    /** 身体关键点可见性（「模仿我」缝；app 侧已节流到状态变化才广播）。 */
    fun onBodyTracking(visible: Boolean) {
        for (skill in skills.values) skill.onBodyTracking(visible, contextFor(skill))
    }

    /** 直通 speak 播放完成（成功/失败都广播；被掐断的不广播，见 AvatarSkill doc）。 */
    fun onSpeakCompleted(spokenText: String) {
        for (skill in skills.values) skill.onSpeakCompleted(spokenText, contextFor(skill))
    }

    fun onTurnCompleted(reply: String) {
        for (skill in skills.values) skill.onTurnCompleted(reply, contextFor(skill))
    }

    fun onTurnFailed() {
        for (skill in skills.values) skill.onTurnFailed(contextFor(skill))
    }

    fun onInterrupted() {
        for (skill in skills.values) skill.onInterrupted(contextFor(skill))
    }

    /** 强制退场全部激活技能（恢复其副作用，如 VAD 悬停），返回结果行。 */
    fun deactivate(): String {
        var n = 0
        for (skill in skills.values) {
            if (!skill.isActive) continue
            skill.onExit(contextFor(skill))
            unhookSkillTools(skill)
            n++
        }
        return if (n == 0) "no active skill" else "deactivated $n skill(s)"
    }

    /** 调试命令：路由给指定 id 的技能（不要求激活态）。 */
    fun debug(id: String, arg: String?): String {
        val skill = skills[id] ?: return "no skill registered as '$id' (registered: ${skills.keys})"
        return skill.debugCommand(arg, contextFor(skill)) ?: "$id: no debug handler for '$arg'"
    }

    private fun hookSkillTools(skill: AvatarSkill) {
        val registry = tools ?: return
        val list = skill.tools()
        if (list.isEmpty()) return
        skillTools[skill.id] = list
        list.forEach { (spec, executor) -> registry.register(spec, executor) }
    }

    private fun unhookSkillTools(skill: AvatarSkill) {
        val registry = tools ?: return
        val list = skillTools.remove(skill.id) ?: return
        list.forEach { (spec, _) ->
            // 只摘本技能注册的那份：同名被后来者覆盖后不再归它所有
            if (registry.find(spec.name) == spec) registry.unregister(spec.name)
        }
    }

    private fun contextFor(skill: AvatarSkill): SkillContext =
        SkillContext(effectiveHost) { detail ->
            emit(AvatarEvent.SkillEvent(skill.id, detail))
        }
}
