# 虚拟人「真人感」增强：六项方案可行性分析

> 背景：与外部 AI 讨论「怎样给虚拟人增加真人感」，得到 3+3 项提案（呼吸 / 空闲状态机 / 手部全身分离 / 微跳视 / 拟真眨眼 / 倾听反馈）。本文逐项对照本仓库真实代码做可行性分析，修正外部方案中与本仓库现状不符的假设，并给出实施路线图。
> 基线：commit `87047ff`（2026-10-04，单测 app 74 / corelib 19 / adapter 54 / orchestrator 108 debug 变体全绿）。
> 配套阅读：`docs/ai-layer-handoff.md`（AI 层交接）、`docs/springbone-debug-context.md`（弹簧骨骼）。

---

## 0. 结论速览

| # | 方案 | 现状 | 结论 | 预估工作量 | 优先级 |
|---|---|---|---|---|---|
| 1 | 呼吸（胸肩起伏） | **✅ 已实现（2026-10-04，见 §2.1 末尾落地记录）**（此前：完全没有，SK_Sun 无呼吸 morph） | ✅ 骨骼程序化驱动，完全可行 | 1~1.5 天 | **P1，最优先** |
| 2 | 空闲行为状态机 | idle 槽 + 一次性动作播完自动回待机**已在 corelib**（最难部分已存在）；缺行为调度器 | ✅ 高度可行，几乎纯新增 | ~1 天 | P2 |
| 3 | 手部/全身分离（分层混合） | 引擎**单动画槽**、无遮罩无混合；但「讲话时肢体动作」经 `<act:>` 协议**已实现** | 🔧 可行但属引擎级改造，六项中最重；建议拆三阶段、放最后 | 3~5 天 | P4（观感评估后决定） |
| 4 | 微跳视 micro-saccade | SaccadeEngine（0.8~4.8s 注视点重选 ±0.25 抖动）+ VrmLookAtEngine（眼先头后）**已实现主体** | ✅ 已基本完成，增量=高频微抖层+眨眼联动 | 0.5 天 | P1（与眨眼一起做） |
| 5 | 拟真眨眼 | 自动眨眼已有：sin(πt)/0.2s **对称**曲线、只写 `blink` 单预设、间隔 U(1,6)s | ✅ 低成本改造（非对称曲线+事件触发 API） | 0.5~1 天 | P1 |
| 6 | 倾听反馈 | VAD hearing 信号**只到 UI 层**；orchestrator/FaceDriver 完全无感知；LookAt **无 roll 通道** | ✅ 可行，需加信号桥 + corelib roll 通道 | 1.5~2 天 | P3 |

**总判断：六项全部可落地。其中 #4 的一半、#5 的主体、#2 的最难部分、#3 的「讲话手势」语义在本仓库已经存在——外部方案是按「从零开始」的通用架构给的，落地时应先做增量而不是重造。P1+P2 合计约 3 天即可拿到「静止不再冻结、眨眼眼神变活」这两个感知最强的升级。**

---

## 1. 现状盘点（动手前必读，避免重复造轮子）

### 1.1 每帧渲染管线——一切程序化叠加的挂点

`corelib/src/main/java/com/neethu/corelib/internal/SoulLinkRenderer.kt` 的 `choreoCallback`（:269-348）每帧依次：

| 步骤 | 内容 | 锚点 |
|---|---|---|
| 0 | hips 平移快照 `captureHipsTranslation()` | :273 |
| 1 | VRMA 动画分支：`consumeIdleSwap` → `vrma.update(elapsed)`（或内置 glTF `animator.applyAnimation`） | :277-292 |
| 2 | `applyHipsDragOffset(hipsBefore)` 拖拽位移重钉 | :301 |
| （空位） | **呼吸/倾听等新程序化层的挂点 = 第 2 步后、第 3 步前** | — |
| 3 | `lookAtEngine?.update(gazeDt)` 视线叠加 | :311 |
| 4 | `animator.updateBoneMatrices()` 蒙皮矩阵传播 | :312 |
| 5 | `expressionManager?.update()` morph 插值 | :315 |
| 6 | `pendingSpringReset` → 弹簧骨骼重置 | :319-322 |
| 7 | `springBoneManager.update(dt)` + 二次 `updateBoneMatrices()` | :328-331 |
| 8 | 相机 / `modelViewer.render` / 重新投递回调 | :337-346 |

要点：**程序化骨骼写入必须在动画分支之后、`updateBoneMatrices()` 之前**（改的是 TransformManager 局部变换，蒙皮矩阵传播才会带上）；位于 lookAt 之前的层（呼吸）会被 LookAt 的头部位姿读取自然感知（视线跟随呼吸起伏，正确方向）。

### 1.2 已有的「真人感」系统清单

| 系统 | 实现 | 锚点 | 现状与参数 |
|---|---|---|---|
| 自动眨眼 | `MicroMotionEngine` | `avatar-orchestrator/.../face/MicroMotionEngine.kt` | 间隔均匀 U(1,6)s；单周期 0.2s **对称** sin(πt) 半波；只写 VRM 预设 `blink` 单名；情绪眼区激活时被 FaceDriver 压 0（引擎计时照常） |
| 视线 saccade | `SaccadeEngine`（AIRI eye-motions.ts 精确移植） | `avatar-orchestrator/.../face/SaccadeEngine.kt` | 注视点 0.8~4.8s 分段均匀换点 + ±0.25 世界单位抖动（仅 x/y）+ `snap()`；零平滑，平滑交给 corelib |
| 骨骼注视解算 | `VrmLookAtEngine` + `GazeMath` | `corelib/.../internal/VrmLookAtEngine.kt` | 头+颈 yaw±55°/pitch±35° 限幅（相对动画朝向）；颈 35%/头 65% 分摊、300ms 指数收敛；**眼骨携带未平滑余量 ±25°（眼神先到头颈跟进）**；「先剥再叠」`stripPreviousWrite` 防逐帧累积；关闭恢复 rest。**只有 yaw/pitch，无 roll** |
| idle 槽位 | `VrmaAnimationEngine.setIdleAnimation` | `corelib/.../internal/VrmaAnimationEngine.kt` :138-160, :288-299 | 挂 idle 无动作在播→立即循环；**一次性动作播完自动无缝切回 idle**（`idleAnchor` 相位锚定 + `consumeIdleSwap` 联动弹簧骨骼重置）；无 idle 回 rest pose |
| 讲话手势 | `<act:tag>` 协议 → `GestureDriver` | `avatar-orchestrator/.../gesture/GestureDriver.kt` | 目录 455 tag（全量扫描 assets 生成）；单动画槽 last-wins；LLM 说话时自发触发，播完自动回 idle |
| 表情 | `EmotionBlender` 13 情绪 + FaceDriver 挂句开播 | `avatar-orchestrator/.../face/EmotionBlender.kt` | VRM 预设打底 + ARKit 微表情叠层；`apply(cue, holdMs)`（HOLD_NO_RESET=-1 不归零）；权重经 `FaceDriver.send` → `controller.setExpression`（instant 模式，缓动全在 orchestrator 侧）；嘴部通道与口型取 `max` 混合 |
| 口型 | `FaceDriver` + VowelDriver/wLipSync 管线 | `avatar-orchestrator/.../face/FaceDriver.kt` :185-236 | tick 顺序：口型 → 情绪 tick → 眨眼 → **gaze+saccade** → 合并发送 |
| 程序化叠加范式① | `HipsDragOffsetSolver` | `corelib/.../internal/HipsDragOffsetSolver.kt` | 帧首快照 → 动画写完后判覆写 → 以新姿态为基线叠加偏移 → **幂等落盘**（不逐帧累加）；JVM 纯函数，7 单测锁不变量 |
| 程序化叠加范式② | `VrmLookAtEngine` 的 BoneRef + `stripPreviousWrite` | VrmLookAtEngine.kt :80-108 | 本帧局部若仍等于上次写入值→先剥旧偏移再叠新的；被动画重写→以当前为新基线。**新程序化层一律照抄此范式** |

### 1.3 模型与资产事实（本轮实测核验）

**SK_Sun（默认模型 `SK_Sun_PERFORMANCE_jacket_off_1024.vrm`）**：
- morph：52 个 ARKit 命名 custom（`browInnerUp / browDownLeft/Right / eyeBlinkLeft/Right / eyeSquint* / eyeWide* / jawOpen / mouth* / cheekSquint*` 全套）+ VRM 预设 18 个（含 `blink / blinkLeft / blinkRight / lookUp/Down/Left/Right`）。**没有任何呼吸类 morph**（`breathe/breath` 零命中）。
- 人形骨骼齐全：`hips / spine / chest / upperChest / left+rightShoulder / neck / head / left+rightEye` + 全套十指骨骼——**骨骼驱动的呼吸素材完备**。
- 弹簧骨骼 springs=0（呼吸不会带动头发/衣饰，10.vrm 有弹簧链会自然联动）。
- 眼球=蒙皮到 eye_l/eye_r 骨骼（剔除问题已由 `applyAvatarCulling` 修复，见 87047ff）。

**VRMA 动画库（334 个 / 9 分类）**：
- `assets/animations/01_待机与站姿/` 65 个——含 `Neutral Idle`、`Weight Shift Idle`（**现成的重心切换 idle**）、`Standing Idle- Loop`、`Idle Stand Looking Around`、Happy/Sad Idle Variation 等；
- `07_日常互动/` 45 个、`04_交流手势/` 37 个——空闲小动作池的候选来源；
- 轨道结构（实测 Arms Down / Neutral Idle / Boxing 等）：**52 条 rotation 轨道（Mixamo 命名，含 Spine/Spine1/Spine2 全脊柱链 + 32 条手指轨道）+ 1 条 hips translation**。
- ⚠️ **默认待机 `Arms Down.vrma` 是 0.0417s 单帧静态姿势**——挂机时模型每帧被重写成同一个姿势，**完全冻结**。这是「塑料感」最大的单一来源，也是呼吸列为 P1 的最直接论据。

### 1.4 两个前置认知

1. **程序化层统一范式**：本仓库已有两条被真机验证的程序化叠加层（hips 拖拽位移、视线旋转），共同范式 = 「先剥再叠 + 幂等落盘 + 动画重写时以新姿态为基线」。呼吸/倾听/任何新微动层都应同构实现，而不是发明第三种写法。这正是 A.1 第 36 条教训（对动画驱动骨骼的一次性写入都会被动画吃掉）的正面解法。
2. **corelib 双 Choreographer**：渲染循环（SoulLinkRenderer）与 FaceDriver（orchestrator）各有一个 Choreographer 帧回调，互不同步。morph 类（表情/眨眼/口型）走 FaceDriver，骨骼类（呼吸/roll/点头）必须走渲染循环——两条线不要混。

---

## 2. 逐项可行性分析

### 2.1 方案一：呼吸系统 ✅（P1，最优先）

> **落地记录（2026-10-04，真机 62fabe84，经三轮调参+一次真根因修复）**：已实现——`VrmBreathEngine`（bind 五骨，挂点=choreoCallback 第 2/3 步之间、lookAt 之前）+ `BreathWave` 纯函数（10 单测）+ `AvatarController.setBreathEnabled/setBreathSpeaking/getBreathInfo` + FaceDriver 开播/收播/打断置位 + ai_cmd `breath on|off|status`。**真根因教训（用户观察「99% 静止+2 帧抽搐」→Python 仿真定位）**：初版照抄 lookAt 的 strip 单信号范式（「当前≈上次写入→剥旧偏移」）对从 0 爬升的连续小角度层产生**吸收态陷阱**——待机每帧重置骨骼后 strip 永远误判，渲染恒等于基座+单帧增量（0.2°），fold/方波隔离实验从满角度起步所以全部假阴性。修复=`BreathBaseResolver` 双信号（动画分支前快照，重写必现绝不 strip；HipsDragOffsetSolver/A.1 第 36 条同款模式），`tools/breath_sim/breath_sim.py` 仿真锁死复现+修复验证（旧 0.2°/0% 可见→新 12°/86.7% 连续正弦），7 条新单测含锁死回归。幅度现档链 ~24°（spine 5/chest 12/upperChest 7+耸肩 4），待用户观感确认后回调。方法论沉淀：**「先剥再叠」范式有适用边界——偏移从 0 缓慢爬升的层必须用快照双信号；新程序化层先写 Python 仿真再上真机**。

**结论：完全可行，且必须走骨骼程序化路线（SK_Sun 无呼吸 morph，morph 路线不存在）。这是六项中性价比最高的——默认待机 Arms Down 是单帧静态姿势，模型挂机时纹丝不动，呼吸直接消灭最大的塑料感来源。**

外部方案的判断（骨骼双轴驱动优于 morph 缩放、0.25~0.3Hz、吸呼不对称）方向正确，全部采纳；需要修正的是落地位置——呼吸不能写在 FaceDriver（morph 通道），必须像视线一样做成 corelib 内的骨骼叠加引擎。

**实现设计**：

- **新增 `corelib/.../internal/VrmBreathEngine.kt`**（参照 VrmLookAtEngine 的结构）：
  - `bind(tm, asset)` 时抓取 `spine / chest / upperChest / leftShoulder / rightShoulder` 五骨的 BoneRef（rest 局部/世界四元数），复用「先剥再叠」记账；
  - `update(dt, speaking: Boolean)`：呼吸相位器累积 `phase += dt * f`，按非对称波形算归一化幅度 a∈[0,1]，换算成每骨局部旋转偏移后经 `applyOffset` 写入（世界系→父骨骼系 `L' = inv(P)·D·P·L`，与 LookAt 同款数学，`GazeMath` 纯函数直接复用）；
  - 波形：周期归一 u∈[0,1)，吸气段 u<0.4 用 easeOutSine 上升，呼气段用 easeInOutSine 下降（吸快呼慢）；静息 f≈0.25Hz（15 次/分），**说话时 f×1.15、幅度×0.6**（`speaking` 标志由外部置位）；
  - 幅度起始建议：chest pitch +1.2°、upperChest +0.8°、spine +0.6°（合计 ~2.6° 俯仰起伏，视觉可感不夸张）、双肩绕轴外旋 +0.8°（耸肩微抬）——具体真机调参，全部做成构造参数。
- **挂点**：`choreoCallback` 第 2 步之后（`applyHipsDragOffset` 后、`lookAtEngine.update` 前）。VRMA 每帧重写全脊柱链（52 条轨道已实测），所以每帧「动画刚写完 → 呼吸叠加」天然成立，与任何 idle/动作 clip 无冲突；VRM0 根翻转后绑定（与 LookAt 绑定同位置，SoulLinkRenderer.kt:852-859 附近）。
- **说话信号桥**：`AvatarController.setBreathSpeaking(Boolean)` 公开 API；FaceDriver 在 `onPlaybackStarted/Ended/Interrupted`（它已持有 controller 引用）置位即可，无需逐帧调用。
- **API 面**：`setBreathEnabled(Boolean)`（默认开）、`setBreathSpeaking(Boolean)`；换模型随引擎重建；ai_cmd 加 `breath on|off|status`（真机 A/B 用，沿用 `culling on/off` 的验证套路）。
- **单测**：波形纯函数抽 `BreathWave`（object）：锁死——吸呼时长比 40/60、相位连续无跳变、幅度有界、speaking 调制边界、长时间推进（模拟 10 万帧）无漂移。

**风险**：极低。GazeMath `matToQuat` 的根缩放精度问题（A.1 记录在案的独立项）不影响本方案——呼吸只做局部四元数乘法，不与世界矩阵互提。剔除回归：呼吸幅度在厘米级，不会把胸部 mesh 顶出绑定姿态包围盒（对比：眼球修复案例里几厘米就能触发窄视锥剔除，但那是 2.5cm 的小盒；胸/肩盒大得多，且角色已全局关剔除）。

**验证**：`camera_shot macro` + 截图差分（呼吸开/关各 3s 采样，肩胸区域像素差分应呈现 ~4s 周期起伏）；静置 30min hips 平移零漂移（复用拖拽防漂移判据）；动作/手势播放期间呼吸不间断；10.vrm 弹簧链发梢随呼吸微动。

### 2.2 方案二：空闲行为状态机 ✅（P2）

**结论：高度可行，而且 corelib 已经完成了最难的半件事——一次性动作播完自动无缝切回 idle 循环（VrmaAnimationEngine :288-299）+ idle 热切换（:138-160）。要补的只是 orchestrator 侧一个随机调度器 + 资产白名单策展。**

**设计**（新增 `avatar-orchestrator/.../behavior/IdleBehaviorDriver.kt`）：

```
状态图：BASE_IDLE ──(随机 8~20s 触发)──▶ BEHAVIOR(一次性小动作)
   ▲                                        │
   └──────── 播完自动回（corelib 内建）◀────┘
```

- **触发**：`scope.launch` 的 while+delay 循环（app 层视频模式 30Hz ticker 同款模式；间隔用 `-ln(Random)*mean` 指数分布，mean≈12s——泊松过程的时间间隔就是这个一行变换，不需要引数学库）。
- **门控（关键避让规则）**：仅 `phase == IDLE` 时触发。THINKING/SPEAKING 期间静默——LLM 手势走同一单动画槽（last-wins），调度器若在动作播放中触发会顶掉 LLM 手势；而 `<act:>` 只在 send/speak 流程里派发，IDLE 相位不可能有手势在播，所以 `phase==IDLE` 一个条件就够。phase 回到 IDLE 时重置计时器。
- **资产白名单**：从 `01_待机与站姿`（65 个）+ `07_日常互动`（45 个）人工策展 10~20 个**短时长（<6s）、站姿兼容、观感自然**的一次性小动作（候选：`Looking Around` 系、`Acknowledging Gesture`、`Right Hand Behind Head`、`Left Hand On Hip` 等；逐个真机过一遍再定，剔除腿位变化大的）。加载用现有 `loadVrmaAnimation(assetPath)` + `playVrmaAnimation(loop=false)`，播完 corelib 自动回 idle——**一行「播完回待机」代码都不用写**。
- **外部方案中「重心左右脚切换」**：不用程序化实现——库里现成 `Weight Shift Idle` / `Neutral Idle` / `Standing Idle- Loop`。做成可选项：行为调度器触发时以低概率（如 20%）顺带 `setIdleAnimation` 轮换 base idle。⚠️ 但 idle 热切换是从第 0 帧起播、**无 crossfade**，不同站姿间切换会有姿态跳变——首期建议轮换限定在同族站姿（上述三个都是自然站姿，跳变小），并把轮换做成默认关的开关，真机看过观感再开。
- **放置层**：orchestrator（SDK 集成者直接受益），`Options.enableIdleBehaviors` 开关 + 行为池可注入；demo app 加 prefs 持久化。判「空闲」的唯一权威就是 orchestrator 的 `ConversationPhase.IDLE`，app 层只有 collect 来的镜像，放 app 层生命周期不对。
- **单测**：调度器抽纯逻辑（时钟可注入）：IDLE 才触发 / SPEAKING 静默且恢复后重置计时 / 触发后到播完前不重复触发 / 指数间隔蒙特卡洛落在 [2s, 60s] / 池子不放空不重复连抽同一个。

**工作量**：~1 天（调度器+白名单策展+开关+单测）。**验证**：`ai_cmd idle_behavior on` + 静置观察 3 分钟；`chat_state` 看 phase 门控；对话进行中动作不被顶掉。

### 2.3 方案三：手部/全身分离（分层骨骼混合）🔧（P4，最大工程，建议放最后）

**先澄清现状**：「虚拟人讲话时运用肢体动作」这半句**已经实现**——协议教 LLM 说话时自发 `<act:>` 标签（455 个动作 tag 目录），GestureDriver 播放，播完自动回 idle。真机多轮验证过（`<act:wave>` 等全程触发）。所以本方案的真实增量是两点：

1. 动作播放期间 base 微动（呼吸/idle 摆动）被完全顶掉——单动画槽，clip 期间模型只做 clip（配合方案一落地后此问题已缓解大半：呼吸叠加层在动画分支之后，**动作播放期间呼吸照常**）；
2. 手势 clip 带动全身（Mixamo 资产 52 条轨道含双腿双脚），站姿手势会带走下半身姿态，播完瞬切回 idle 还有姿态跳变。

**结论：引擎级改造可行，但工作量与风险是六项之最，建议拆三阶段，前两阶段做完就评估是否还值得做第三阶段。**

| 阶段 | 内容 | 成本 | 判断 |
|---|---|---|---|
| A | 方案一呼吸叠加层（动作期间微动不冻结） | 已含在 P1 | 先做，收益立现 |
| B | **双 clip 混合**：引擎支持 base clip（idle 循环）+ overlay clip（手势，带骨骼遮罩），overlay 权重进出各 ~0.3s slerp 淡入淡出 | 3~5 天 | 核心增量 |
| C | 真加法混合（subtractive delta） | >5 天 | **不建议**：VRMA 资产没有标定「减去 rest 的 delta」，Mixamo 转换件质量参差，additive 出来的手势大概率走形 |

**阶段 B 实现要点**（改动集中在 `VrmaAnimationEngine.update`，:212-281）：

- 结构上就是「逐 track 循环 + `boneEntityMap[name] ?: continue`」，**加遮罩是自然插入**：overlay clip 维护 `mask: Set<String>`（`leftShoulder/leftUpperArm/leftLowerArm/leftHand + 右侧 + 十指`），update 里对两个 clip 各算 time，mask 内骨骼 `slerp(baseRot, overlayRot, w)`，mask 外只写 base；hips 平移恒以 base 为准（`applyTranslation` :313-321 的覆写标志语义保持，HipsDragOffsetSolver 不受影响——旋转遮罩不碰平移）；
- 混合数学抽纯函数 `LayerBlendMath`（slerp 权重、淡入淡出曲线、mask 过滤），corelib 现成的 JVM 纯函数测试模式（HipsDragOffsetSolverTest 的多帧序列断言）直接套用；
- `consumeIdleSwap` / `restoreRestPose` / `stop()` 语义要重新梳理：双层模式下「播完」指 overlay 淡出完毕；
- ⚠️ 手指轨道事实（本轮实测）：VRMA 全部带 32 条手指轨道——mask 必须含手指，否则 overlay 的手型会盖掉 base idle 的手型，且淡出瞬间手指跳变。

**风险**：a) 同一骨骼两个写入源的四元数 slerp 在大角度时走最短弧可能抖动；b) 手势 clip 的 hips 高度与 idle 不同但平移被遮罩→手臂挂点位置微错位（视觉上通常可接受，真机验）；c) 弹簧骨骼 `consumeIdleSwap` 重置时机在双层下要回归验证（10.vrm）；d) **现有单测没有覆盖 VrmaAnimationEngine 本身**（retarget 函数全是 private），改造前需要先把插值/retarget 抽纯函数补测试，否则是盲改。

**验证**：`play_animation` 打一个手势 while idle 在播 → 下半身持续微动、手臂混合无跳变、播完淡出自然；拖拽/视线/呼吸全量回归。

### 2.4 方案四：微跳视 micro-saccade ✅（P1，与眨眼一起做）

**结论：主体已实现。** SaccadeEngine 的注视点重选（0.8~4.8s）+ 抖动就是 saccade 的近似，VrmLookAtEngine 的「眼骨携带未平滑余量、眼神先到头颈跟进」正是人类 saccade 的分层生理结构——这部分外部方案不用再做。增量只有两小点：

1. **注视期微抖层**：在 `SaccadeEngine.tick`（:41-53）内加第二层独立计时器：间隔 0.4~1.2s、幅度 ±0.01~0.03 世界单位（对应注视距离 ~0.3~1°视角），叠加进 `fixation`。⚠️ 两个工程细节：现 `WRITE_EPS_SQ = 0.0001²`（FaceDriver :340）的去重阈值会把 1m 距离下 0.5° 的微抖（≈0.009 单位）整层吃掉——微抖通道要么放大 epsilon 判定单独放行，要么直接调大微抖幅度下限；微抖最终还要过 VrmLookAtEngine 300ms 平滑与 ±25° 眼骨限幅，**实际观感幅度必须真机调**，外部给的 ±0.015 数值只能当起点。
2. **saccade→眨眼联动**：`saccade.tick` 返回 true（换点）且位移 >0.1 单位（大幅转移，微抖不触发）时，30% 概率 `microMotion.triggerBlink()`——需要方案五的触发 API 先落地，所以两件事一起做。

**工作量**：0.5 天含单测（间隔蒙特卡洛 + 幅度界内 + 联动概率门控）。

### 2.5 方案五：拟真眨眼 ✅（P1）

**结论：低成本改造，现有实现底子好（间隔 U(1,6)s 已经接近真实对话眨眼率）。**

外部方案「人类眨眼间隔平均 26 秒」的数据不适用于对话场景——那是特定凝视任务的自发眨眼率；对话中眨眼 15~20 次/分（间隔 3~4s），现有 U(1,6)s 均匀分布**不需要大改**（可选微调：改成截断指数分布让短间隔更常见，一行 `-ln(U)` 变换）。

真正的增量：

1. **非对称曲线**：`MicroMotionEngine.tickBlink` 现在是单段 0.2s sin(πt) 对称半波（:38-42）。改成两段：闭合 0.08s（easeIn 至 1）+ 张开 0.12s（easeOut 回 0）——闭快开慢，总时长不变；
2. **左右眼分离**：现在只写单名 `blink`（FaceDriver 常量 `BLINK`，:315）。SK_Sun 有 `blinkLeft/blinkRight` 预设——改成写双名（值相同）为未来 wink 留路，注意 FaceDriver 的 `send` 去重缓存（ε=0.004，:300-310）对两个名字独立记账，无冲突；
3. **事件触发 API**：`triggerBlink(times = 1)`——把 `nextBlinkIn` 清零（连眨=队列两个相位）。事件源：方案四的 saccade 联动 + surprised 情绪触发连眨。
4. **与情绪眼区的纠缠（唯一要小心的点）**：`eyeAreaActive` 时 FaceDriver 把眨眼权重钳 0（:210-211），而 surprised 恰恰激活 `eyeWide`——「惊吓连眨」会被自己的情绪压制。解法：情绪触发的连眨延迟 ~400ms（等 eyeWide 进场完）+ 触发后 0.3s 内临时豁免压制（FaceDriver 压制处加豁免窗口）。若嫌复杂，首期只做 saccade 联动（saccade 换点时情绪眼区通常不活跃），惊吓连眨留调参期。

**工作量**：0.5~1 天含单测（曲线形状：峰位/单调段/时长；trigger 语义；豁免窗口）。

### 2.6 方案六：倾听反馈 ✅（P3）

**结论：表现层三件套（歪头/点头/挑眉）都可实现，但缺一段信号桥——orchestrator 层完全不知道「用户正在说话」。**

**信号现状**：
- `SpeechVad` 有完整事件（`SpeechStarted/SpeechEnded/BargeIn`），`FreeSpeechController.onHearingChanged(Boolean)` 已回调到 MainActivity 的 `freeHearing` Compose state——**但目前只喂了 UI 指示条**（FreeListenIndicator）；
- `ConversationPhase` 只有 IDLE/THINKING/SPEAKING，无 LISTENING；`AvatarEvent` 无用户语音事件；
- ⚠️ VAD 只在**自由说话/视频模式**存在；**按住说话模式没有实时 VAD**（MediaRecorder 录完才 ASR）——但按住本身就是天然的 listening 信号（按下=用户在说，松开=结束）。

**实现设计**：

1. **信号桥**：`FaceDriver.setUserListening(Boolean)` 公开方法（内部带去重）。app 侧两处喂入：`onHearingChanged → session?.faceDriver?.setUserListening(hearing)`；按住说话的 `onPress/onRelease` 同样调用。SDK 集成者可接任意 VAD——桥开在 FaceDriver 而不是 AvatarSession 事件流，避免为它扩协议。
2. **歪头 roll + 程序化点头（corelib）**：VrmLookAtEngine **目前只有 yaw/pitch**，roll 需要新增几何：现有 `quatFromTo(forward, dir)` 表达不了绕视线轴的滚转，需 `dWorld' = q_axisAngle(forward, roll) ∘ quatFromTo(...)`，头/颈分摊、strip、平滑态全部复用现成机制（加 `setRollOffset(rad)` 通道即可）。程序化点头同样做成 additive pitch 振荡通道——**不要用点头 VRMA 动画**（单动画槽会顶掉 idle/手势，为了半次点头打断 base 姿态太重）。参数：倾听时 roll 缓动至 5~8°（进入 ~0.5s），每 2.5~5s 半次点头（下 4~6° 回弹），退出缓动回 0。
3. **挑眉（browInnerUp 0.25~0.35）**：**不能**走 EmotionBlender 手动通道——那是单状态机，倾听挑眉会顶掉 LLM 正在表达的情绪（后 apply 顶前面）。两个选项：a) 首期不做挑眉，只做头部反馈（推荐，头部反馈的感知权重本来就远大于 0.3 的眉毛）；b) FaceDriver tick 里加独立的 brow 叠加项（情绪合并后 `max` 进 browInnerUp/browOuterUp 通道，与口型 max 混合同款机制）。
4. **交互边界**：barge-in 时 hearing=true 且虚拟人被打断进 IDLE——倾听反馈合理；THINKING 期间用户继续说话，hearing 保持——反馈持续，正确；说话半双工期（虚拟人 SPEAKING）普通响度不触发 VAD（SpeechVad 三重门），不会出现「自己听自己点头」。

**工作量**：1.5~2 天（roll 通道 0.5~1 天 + 信号桥与表现逻辑 0.5 天 + 真机调参 0.5 天）。
**验证**：`ai_cmd listen on|off`（模拟 hearing，不依赖麦克风——HyperOS 麦克风权限墙老约束下的手动验证通道）+ `camera_shot closeup` 截图看 roll 角度；自由说话模式下真人开口观察。

---

## 3. 外部方案的修正清单（对齐讨论用）

| 外部方案的说法 | 本仓库事实 | 处置 |
|---|---|---|
| 「在已有的 MediaPipe Gaze 追随基础上叠加微抖」 | 本项目视线基线是**注视相机**（CAMERA 模式）或视频模式的人脸投影（FacePointProjector→POINT），**没有 MediaPipe**；saccade 已由 SaccadeEngine 实现 | 微抖叠加到 SaccadeEngine.fixation，思路照搬、位置改对 |
| 「人类眨眼间隔平均 26 秒，不能 3 秒一眨」 | 对话场景 15~20 次/分；现实现 U(1,6)s 已接近 | 间隔不动（可选截断指数微调），改造重点在曲线非对称+事件触发 |
| 「呼吸用 BlendShape 会像气球，要骨骼双轴驱动」 | SK_Sun **根本没有**呼吸 morph，骨骼路线是唯一路线 | 采纳骨骼方案；「吸 40%/呼 60% 不对称」「说话时收窄加快」全部采纳 |
| 「crossFade(0.8s) 平滑混入」 | 引擎**单动画槽、无任何混合能力**，idle 热切换从第 0 帧起播瞬时切换 | crossFade 不是调参数，是阶段 B 双 clip 基础设施（§2.3），首期用「同族 idle + 瞬切」规避 |
| 「泊松分布触发」 | 现有眨眼就是均匀随机；泊松过程间隔=指数分布=`-ln(U)*mean` 一行变换 | 采纳，但按一行代码对待，不引依赖 |
| 「Head Tilt 5°~8°」「browInnerUp: 0.3」 | LookAt 无 roll 通道；EmotionBlender 是单状态机会被顶掉 | roll 走 VrmLookAtEngine 新通道；挑眉二选一（建议首期不做） |
| 「手部层直接覆盖播放对应手势动画，与下半身互不干扰」 | 「讲话时肢体动作」已有（`<act:>` 协议）；互不干扰需要阶段 B 遮罩混合 | 澄清现状后按三阶段推进 |

---

## 4. 建议实施路线图

```
P1（~2.5 天）呼吸引擎 + 眨眼改造 + 微跳视 + saccade→眨眼联动
    → 感知收益最大：静止不再冻结、眼神变活。彼此独立，可分开提交。
P2（~1 天）  空闲行为状态机（调度器 + 资产白名单 + 同族 idle 轮换开关）
P3（~2 天）  倾听反馈（roll 通道 → 信号桥 → 头部三件套调参）
P4（3~5 天） 双 clip 骨骼遮罩混合（前置：先给 VrmaAnimationEngine 抽纯函数补单测）
    → 做 P1~P3 后重新评估：呼吸叠加已让动作期间的冻结感大减，P4 的边际收益可能不匹配其风险。
```

**每期共通回归清单**（全部真机过）：
1. 10.vrm 弹簧骨骼（`spring_debug` 诊断 len=rest 零爆炸）——SK_Sun springs=0 测不到弹簧，必须换 10.vrm 验；
2. 眼球剔除 A/B（`culling on/off`，87047ff 的复现脚本序列）；
3. VRMA 手势播放→播完回待机→拖拽位移→视线叠加，全链路；
4. 静置 30min 无漂移（hips/呼吸基线幂等）；
5. 多轮对话压测（表情/动作/机位/打断混合）零 FATAL。

**验证工具建议新增的 ai_cmd**：`breath on|off|status`、`blink_trigger [n]`、`listen on|off`（模拟 hearing）、`idle_behavior on|off|status`、`saccade_status`（微抖层参数 dump）。截图差分判据沿用：`camera_shot macro/closeup` + 帧间像素差分。

---

## 5. 附：关键文件索引

| 文件 | 本文档引用点 |
|---|---|
| `corelib/.../internal/SoulLinkRenderer.kt` | 帧管线 :269-348；LookAt 绑定 :852-859；hips 拖拽 :1406-1431 |
| `corelib/.../internal/VrmaAnimationEngine.kt` | update :212-281；idle 槽 :138-160/:288-299；hips 平移 :313-321 |
| `corelib/.../internal/VrmLookAtEngine.kt` | strip :80-108；update :173-218；applyOffset :230-278 |
| `corelib/.../internal/HipsDragOffsetSolver.kt` | solve :36-66 |
| `avatar-orchestrator/.../face/FaceDriver.kt` | tick :185-236；updateGaze :247-274；send 去重 :298-311 |
| `avatar-orchestrator/.../face/MicroMotionEngine.kt` | 眨眼曲线 :38-42；间隔 :45-46 |
| `avatar-orchestrator/.../face/SaccadeEngine.kt` | tick :41-53；分布表 :75-84 |
| `avatar-orchestrator/.../face/EmotionBlender.kt` | defs :44-151；apply :196-226；eyeAreaActive :173-183 |
| `avatar-orchestrator/.../gesture/GestureDriver.kt` | play :52-65 |
| `avatar-orchestrator/.../session/AvatarSession.kt` | phase :124；dispatchCues :461-489；播放回调 :206-249 |
| `app/.../FreeSpeech.kt` | SpeechVad :67-200；onHearingChanged :219 |
| `app/.../MainActivity.kt` | applyIdle :688-705；VAD 接线 :984-1016 |
| `app/src/main/assets/animations/` | 334 个；01_待机与站姿 65 个 |
