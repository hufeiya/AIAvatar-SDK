package com.neethu.orchestrator.skill

/**
 * 一个「虚拟人技能」：围绕实时互动小游戏的会话层逻辑（docs/rps-skill-feasibility.md §4）。
 *
 * 框架语义：
 *  - 事件广播——[SkillRegistry] 把语音文本 / VAD 时机 / 相机手势 / 回合结果
 *    广播给全部注册技能；技能自己按内部状态决定是否响应（多技能共存时各自
 *    守门，框架不做仲裁）。
 *  - 指令注入——激活态（[isActive]）技能经 [turnDirective] 提供的指令行会挂到
 *    每轮请求末尾 user 消息前缀（与【当前镜头视角】行同槽），不进 system、
 *    不进历史、不破坏请求前缀的逐字节稳定（前缀缓存友好）。
 *  - 消费语义——[onUtterance] 返回 true 表示这句由技能主导（技能已自行发起
 *    回合），调用方必须跳过默认发送，避免一句话发两轮。
 *
 * 全部回调有默认实现：新技能只覆写需要的缝。回调都在主线程触发（app 侧
 * scope.launch(main) / session 回合回调）。
 */
interface AvatarSkill {
    val id: String

    /** 当前是否处于激活态（激活态才有 turnDirective、才参与回合事件）。 */
    val isActive: Boolean

    /** 激活态下每轮注入请求的指令行；null = 本轮不注入。 */
    fun turnDirective(ctx: SkillContext): String? = null

    /**
     * 一句识别文本到达（ASR 完成 / 打字发送 / ai_cmd send_chat）。
     * 返回 true = 本句已被技能消费（技能自行发起了回合）。
     */
    fun onUtterance(text: String, ctx: SkillContext): Boolean = false

    /**
     * VAD 判定句尾、**ASR 之前**的快路径（只有自由说话/按住说话的语音句会
     * 触发）——技能用它拿「时机」而不是「内容」（猜拳：短句=出拳信号）。
     */
    fun onVadUtterance(wavMs: Long, ctx: SkillContext) {}

    /**
     * 相机手势观测（P2：MediaPipe GestureRecognizer 经稳定性门控的确认帧，
     * app 层喂）：0=无,1=石头,2=剪刀,3=布。只在技能激活时才有事件
     * （app 侧按 isActive 门控整条检测车道）。
     */
    fun onUserGesture(gesture: Int, ctx: SkillContext) {}

    /**
     * 头部姿态观测（「看这边」技能：MediaPipe FaceLandmarker 的
     * facialTransformationMatrix 分解出的 yaw/pitch，度，app 层逐帧喂）。
     * 只在技能激活时才有事件（app 侧按 isActive 门控整条检测车道，与手势
     * 车道互斥——同一分析线程不并跑两个 MediaPipe 任务）。
     */
    fun onHeadPose(yawDeg: Float, pitchDeg: Float, ctx: SkillContext) {}

    /**
     * 身体关键点可见性观测（「模仿我」技能：PoseLandmarker 上半身质量门，
     * app 层**节流到状态变化**才广播——逐帧事件对技能层是滥用）。技能用它跑
     * 降级阶梯（持续不可见 → 催促 → 退场提示）；模仿的逐帧姿态数据**不走本缝**
     * （app 车道直驱渲染引擎，绕过 orchestrator——感知-渲染直通，技能只管
     * 生命周期）。
     */
    fun onBodyTracking(visible: Boolean, ctx: SkillContext) {}

    /**
     * 一次直通 [SkillHost.speak] 的播放完成（成功或失败都会来；被 interrupt
     * 掐断的不会——打断走 [onInterrupted]）。「看这边」用它做回合节拍
     * （go 信号播完才宣判、宣判播完才下一局，避免自己的 speak 抢占自己的
     * 音频）；不依赖播放时序的技能无需理会。
     */
    fun onSpeakCompleted(spokenText: String, ctx: SkillContext) {}

    /** 一轮回合（含技能经 sendTurn 发起的）完整结束。 */
    fun onTurnCompleted(reply: String, ctx: SkillContext) {}

    /** 一轮回合失败。 */
    fun onTurnFailed(ctx: SkillContext) {}

    /** 回合被 interrupt 打断（含 send supersede / barge-in）。 */
    fun onInterrupted(ctx: SkillContext) {}

    /** 被强制退场（registry.deactivate，ai_cmd skill_exit）；技能在此恢复副作用。 */
    fun onExit(ctx: SkillContext) {}

    /** 调试命令（ai_cmd skill <id> <arg>）；返回给 adb 的结果行。 */
    fun debugCommand(arg: String?, ctx: SkillContext): String? = null
}
