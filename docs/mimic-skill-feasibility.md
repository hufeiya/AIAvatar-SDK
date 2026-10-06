# 「模仿我」动作模仿技能可行性分析

> 生成时间：2026-10-06。对应代码状态：`main@9120377`（看这边技能已落地后的工作区）。

> **落地状态（2026-10-06，同日实现完成）**：P1 全量落地——§3 方案 A（PoseLandmarker
> lite）+ §4 方向绝对驱动（`PoseMimicSolver` 纯函数 + `VrmPoseMimicEngine`，绝对驱动
> 幂等故**无需 strip 记账**，退场走 300ms nlerp 缓动）+ §5 镜像映射预填（镜像=
> (−x,−y,−z)+换侧；推导修正：**前后轴两种玩法都翻**——面对面角色"向前"世界方向
> 相反，镜像/人偶的区别只在左右轴，`mirrorFrame` 一处收口）+ §7 数据通路（车道→
> `MimicDirectionFilter`（One-Euro+死区+z 阻滞）→ ticker → `controller.setMimicPose`
> 原子换手）+ §9 状态机（`MimicSkill`，插话放行；新缝 `AvatarSkill.onBodyTracking`
> 可见性阶梯 2s 催促/8s·3 次 退场）+ §8 接线全表（含 mimic_status/mimic_pose/
> mimic_force 三条 ai_cmd 与激活自动切视频模式）。
> 单测 +48 全量 498 全绿（app144/corelib50/orchestrator230/adapter74 debug 变体）。
> **真机待办**：§5.2 符号标定（`mimic_pose` 四姿势 + `mimic_force` 四渲染，任何轴
> 反了改 `mirrorFrame` 一处）+ §11 验收清单 + 平滑观感调参（One-Euro/SMOOTH_RATE/
> 死区）+ 呈现率顿挫观察。

> **P2 落地状态（2026-10-06，用户真机确认 P1「胳膊已经可以动了」后同日实现）**：
> §1.2 P2 范围全量落地——①**躯干三段**（spine/chest/upperChest 按 0.35/0.35/0.30
> 分摊 yaw/pitch/roll 世界轴旋转，`PoseMimicSolver.torsoAngles` 从躯干轴+肩线分解，
> 限幅 ±40°/±30°/±30°）+ **双锁骨**跟随肩线倾滚（半幅 ±15°，`axisAngleQuat` 新增
> 到 GazeMath，`applyWorldRotation` 从 driveRotation 抽出复用）； MimicPose +2 字段
> （torsoAxis/shoulderLine，髋部关键点 23/24 可见性门控）；②**穿插表情车道**——
> FaceLandmarker（blendshapes 开启的独立实例，不背累看这边车道）与 PoseLandmarker
> 同分析线程**隔帧轮转**（身体 80ms 主通道、表情 160ms），52 ARKit blendshapes →
> `MimicFaceMapper`（直配/VRM 别名降级/多源取 max）→ FaceDriver 表情通道
> （`setMimicFace`：**说话期嘴部让口型**〔VISEME_SET 跳过+jawOpen 系归零〕、
> **用户真实眨眼替代自动眨眼**、**情绪通道整体让位**〔blender 状态保留断供即接回〕、
> 断供一次性归零上次驱动的 morph）；③**头部精解升级**——表情矩阵在 400ms 保鲜窗内
> 替代鼻-耳估算（±2° 级），`FACE_FORWARD_LOCAL` 是唯一标定旋钮；④**呼吸让位**——
> mimic 激活期 app 常驻循环 `setBreathEnabled(false)`、退场恢复用户设置；⑤
> `mimic_force` 新增 lean_left/lean_right/bow/turn_left 躯干标定预设 + `mimic_face
> on|off` 隔离开关 + `mimic_status` 增躯干角与表情车道段。单测 +17 全量 515 全绿
> （app149/corelib56/orchestrator230→236/adapter74）。**真机待办**：躯干/锁骨方向
> 标定（`mimic_force` 四个躯干预设）、表情映射观感（mapper 的 deadzone/别名表）、
> 表情车道性能（隔帧 160ms 下低端机 CPU）。
> 结论先行：**可行，且是三个技能里"感知最重、LLM 最轻"的一个**——MediaPipe
> **PoseLandmarker**（世界坐标系 33 关节点，米制）逐帧解算肩-肘-腕方向向量，经镜像映射后
> 用仓库现成的 `GazeMath` 原语（`quatFromTo` + `L'=inv(P)·D·P·L` 相似变换）直接驱动 VRM
> 骨骼；**不需要搬 Kalidokit，不需要 IK 求解器，不需要新增 Gradle 依赖**（tasks-vision
> 0.10.14 已含 PoseLandmarker，已反编译 AAR 实证），只新增一个 `pose_landmarker.task`
> 模型（lite ≈5.4MB，对照现役 gesture_recognizer.task 8MB）。
> 感知时延 ≈120-160ms（80ms 车道节奏 + 推理 15-30ms + 渲染下一帧）， casual 模仿完全够用。
> 逐帧数据**不经 orchestrator、不经 LLM**（app 车道 → corelib 引擎直通）；技能层只管
> 激活/退场/降级阶梯，退场时一次战报调侃回合——与看这边"赛中 LLM 零参与"同构。
> **最大风险与看这边完全同款：坐标系符号**（相机镜像 × MediaPipe 轴向 × VRM 世界系 ×
> 镜像玩法语义），必须真机标定 + 合成姿态已知答案单测锁死（§5）；其次是与 idle/呼吸/
> 视线三个既有骨骼写入者的所有权仲裁（§6，方案是"最后写入者赢"+单发 VRMA 期间挂起，
> 零框架改动）。

---

## 0. 结论速览

| 问题 | 答案 |
|---|---|
| 能不能成立 | **能**。PoseLandmarker 世界坐标 → 肢体方向向量 → 世界系最短弧旋转 → 局部四元数，每帧 5-11 根骨骼的纯数学覆写；全部原语仓库已有 |
| Kalidokit 要不要搬 | **不要**。它 deprecated 的原因=新 MediaPipe Tasks 原生输出了它要算的东西（面部 52 BlendShapes）；但**身体骨骼依然要自己做"向量→旋转"**——而这层在本仓库已经是成建制的范式（视线/呼吸引擎），新增的只是"更多几根骨头"，约 100 行纯 Kotlin |
| 实时性 | 达标：分析车道 80ms 节奏 + PoseLandmarker(lite) 单帧 CPU 15-30ms + One-Euro 滤波零滞后 + 渲染 16-33ms ≈ **120-160ms 端到端**。不是判定型玩法，无硬性 200ms 死线 |
| 模型与包体 | +`pose_landmarker.task`（**lite float16 ≈5.4MB**；方向向量精度不够再换 full ≈9.4MB，drop-in）；依赖**零新增** |
| 范围定稿 | **P1（本期）= 头/颈 + 双臂 5 骨半身镜像**（neck/head + upperArm/lowerArm ×2）；P2 = 躯干脊柱 + 肩 + 脸部表情；P3 = 手指（HandLandmarker）。hips/腿**永远锁定**给 idle（前摄拍不到下半身，硬解必抽搐） |
| LLM 的角色 | 模仿中**零回合**（逐帧数据 app→corelib 直通，orchestrator 无感）；激活时一次开场回合 + 退场时一次战报调侃回合（`sendTurn`，挂 user 前缀缝隙） |
| 侵入性 | app：`PoseSpotter`（镜像 FaceSpotter）+ 第三条 MediaPipe 车道 + `PoseMimicSolver/DirectionFilter` 纯函数 + ai_cmd 三条；orchestrator：`MimicSkill` 状态机 + `MimicTexts` 双语 + `AvatarSkill.onBodyTracking` 一个默认空实现的新缝；corelib：`VrmPoseMimicEngine`（镜像 VrmLookAtEngine 范式）+ `AvatarController` 三个方法；**adapter 零改动** |
| 技能资产 | **零 VRMA 资产**——模仿是程序化解算，不播动画文件（猜拳/看这边都要手势 VRMA，这个不要）；`SKILL_GESTURE_DIRS` 不动 |
| 最大风险 | ①坐标系符号搞反（同看这边 §5，想当然必翻车）；②单目 z 轴噪声——手臂前伸指向相机时方向抖（One-Euro + 死区 + z 阻尼缓解，属于体验调参不是可行性障碍）；③三条 MediaPipe 车道互斥（同分析线程串行，先例已有） |

---

## 1. 玩法定义与范围钉死

### 1.1 镜像语义（用户原话定稿）

> "虚拟人可以模仿我的动作（当然是镜像的，比如我动左手其实他是右手）"

**镜像（mirror）= 照镜子**：用户抬左手，虚拟人抬**它自己的右手**，观感上"镜像里的人抬的是
同一边的手"。代码含义 = **左右骨骼换侧 + 方向向量 x 分量取反**（§5 有推导），不是简单的
"同侧跟随"。解算器留一个 `mirror: Boolean` 常量开关，万一体感别扭可一键切成"人偶模式"
（同侧跟随，如影随形），数学完全复用（§14-2）。

### 1.2 范围分期（前摄物理约束决定）

| 期 | 驱动骨骼 | 数据来源 | 说明 |
|---|---|---|---|
| **P1（本期）** | neck、head、left/right UpperArm、left/right LowerArm（**5 骨**） | PoseLandmarker 关节点 0/2/5/7/8（头）+ 11-16（肩肘腕） | 头+手臂="模仿我"辨识度最高的 80%；不需要 hips 关键点（P1 全部目标是**两点差向量**，髋部原点天然消掉）；`Arms Down` 待机的手部姿势直接保留 |
| P2 | spine/chest/upperChest + 双肩 + 表情 | +关节点 23/24（髋）；表情走 FaceLandmarker blendshapes | 躯干侧倾/前倾（肩线-髋线）；**需要与看这边的 FaceLandmarker 车道做穿插或分时**（两条 MediaPipe 任务不并跑，§8-4） |
| P3 | 手指 | HandLandmarker（另一任务） | 车道冲突更贵，且手部对手腕精度的要求高；先不做 |

**永远不做**：hips 平移与腿骨（前摄拍不到下半身，膝盖/脚踝关键点丢失即抽搐——这是
单目前摄的物理边界，不是工程短板）。用户转身/走动 = 超出能力范围，§11 验收清单里
按"侧脸/出画降级阶梯"处理而非硬解。

### 1.3 触发与退出

- 激活词（中英合并一条正则，同猜拳/看这边先例，不随界面语言切换）：
  `模仿我|学我|跟我做|copy me|mimic me|do as i do|imitate me`
- 退出词：`不模仿了|停止模仿|别学了|stop mimicking`（刻意不收"好了/停下"这类高频日常词，
  避免用户聊天时误退场）
- 激活时若不在视频模式（相机没开）→ 开场回合里 LLM 顺口引导 + app 侧自动切 VIDEO 模式
  （§8-5 推荐项）；相机权限缺失则错误条说明。

---

## 2. 现状映射：这单生意要买的东西，仓库里已经有什么

| 环节 | 现状 | 结论 |
|---|---|---|
| 技能状态机 | `AvatarSkill/SkillContext/SkillHost/SkillRegistry` 四件套 + 两个先例（RpsSkill 五态、LookHereSkill 四态）；**registry 单活跃收口已落地**（`SkillRegistry.onUtterance` 广播后后激活者胜出） | ✅ 照抄结构，激活/退出/退场战报全有先例 |
| 逐帧骨骼叠加引擎范式 | `VrmLookAtEngine`（头/颈/眼"先剥再叠"+双信号基座判定）与 `VrmBreathEngine`（脊柱五骨）——**两个生产级先例**，含全部踩坑修复（strip 锁死陷阱、matToQuat 缩放偏差、WRITE_EPS 死区） | ✅ 新引擎 `VrmPoseMimicEngine` 镜像该范式 |
| 向量→旋转数学 | `GazeMath.quatFromTo`（最短弧，含反平行边界）:124、`matToQuat`（**列基归一化版**，缩放免疫）:52、`quatMultiply/quatInverse/rotateVector`、世界→局部相似变换 `L'=inv(P)·D·P·L`（lookAt `applyOffset` :301-305 生产验证） | ✅ **一行都不用新写**，直接复用 |
| 人形骨骼查找 | `SoulLinkRenderer.resolveHumanoidEntity`（VRM 0.x/1.0 双 schema，:946-987）；头/颈/脊柱/胸/肩已在用；upperArm/lowerArm/hand 同一解析器加语义名即可 | ✅ 加 6 个语义名 |
| 帧循环挂点 | `SoulLinkRenderer` choreoCallback :280-379：动画分支→hipsDrag→呼吸→视线→**commit+updateBoneMatrices**→表情→弹簧；"All bone-programmatic layers must land before updateBoneMatrices()" 注释明确 | ✅ mimic 插在视线之后、commit 之前 |
| 相机分析车道 | `UserCameraTracker`：CameraX 480×640 前摄 + 单线程 `"video-analysis"` + 80ms 帧级门（`DETECT_INTERVAL_MS` :474）+ 已有两条 MediaPipe 车道（手势/头部姿态）的**门控 lambda + 懒建 + 互斥**完整先例（:85-121、:322-325） | ✅ 加第三条车道，模式照抄 |
| MediaPipe 薄封装 | `FaceSpotter.kt`（FaceLandmarker IMAGE 模式同步 detect，:58-88）/ `GestureSpotter.kt`（:118-145） | ✅ `PoseSpotter` 照抄骨架 |
| 平滑滤波 | `OneEuroFilter` 在 orchestrator `face/FacePointProjector.kt:18-54`（纯 JVM，app 可直接 import——app 依赖 orchestrator） | ✅ 直接复用，不重写 |
| 线程安全的逐帧数据传递 | `latestHeadPose` @Volatile 字段 + 30Hz 主线程 ticker 消费（MainActivity :1106-1127） | ✅ `latestMimicPose` 同款 |
| 即时语音 | `SkillHost.speak()`（无 LLM 直通 TTS，不进历史）+ `onSpeakCompleted` 事件缝 | ✅ 降级提示/催促用 |
| 抓拍 | `SkillHost.snapshotLatest()`（看这边判负存证同款） | ✅ 退场战报带"用户最后姿势"帧 |
| 调试命令缝 | `AiDebugExecutor.executeAiCommand` when 分发（look_status/look_throw/face_pose 先例 :331-344）+ help 双份（AiDebug.kt :152-172） | ✅ 照抄三条 |
| 双语文案 | `LookHereTexts`/`RpsTexts`（中英相邻单文件正本 + i18n 无中文不变量测试先例） | ✅ 照抄 `MimicTexts` |
| LLM 动作目录排除 | 不需要——本技能**没有手势 VRMA 资产**，`SKILL_GESTURE_DIRS` 不动 | ✅ 比前两个技能还少一件事 |

**唯一的真实缺口**：①`pose_landmarker.task` 模型文件（§3）；②corelib 的 `VrmPoseMimicEngine`
（§4/§6）；③符号标定（§5）。

---

## 3. 核心选型：身体姿态从哪来

### 方案 A（推荐）：MediaPipe PoseLandmarker（tasks-vision）

```
app/build.gradle.kts:62  implementation(libs.mediapipe.tasks.vision)   ← 已有,0.10.14 已含 PoseLandmarker
app/src/main/assets/pose_landmarker.task                                  ← 新增,lite float16 ≈5.4MB
```

**AAR 实证（2026-10-06 反编译 tasks-vision-0.10.14.aar）**：

```
PoseLandmarker.createFromOptions(context, PoseLandmarkerOptions)     ✓
PoseLandmarker.detect(MPImage)                                       ✓ 同步 IMAGE 模式(与 FaceSpotter 同款)
PoseLandmarkerResult.worldLandmarks(): List<List<Landmark>>          ✓ 米制世界坐标
PoseLandmarkerResult.landmarks(): List<List<NormalizedLandmark>>     ✓ 归一化图像坐标
Options: setRunningMode / setNumPoses / setMinPoseDetectionConfidence
         / setMinPosePresenceConfidence / setMinTrackingConfidence   ✓
```

- `Landmark`（世界系）自带 `visibility()/presence()`（0-1）→ **人形可见性门控免费获得**
  （torso 关节点 visibility 持续过低 = 用户出画，喂降级阶梯）。
- 官方语义：世界系 33 关节点、原点=髋部中点、米制；x/y 与图像方向一致（y 向下）、z 为
  深度（值越小越靠近相机）。**按项目铁律，官网语义只作预填，轴向最终以真机标定为准**（§5）。
- P1 只消费上半身关键点 **0/2/5/7/8/11/12/13/14/15/16**（鼻/眼/耳/肩/肘/腕，MediaPipe
  通用编号，左偶右奇）；下半身点不看不校验。
- 单帧 CPU 15-30ms（lite，中端机；与 FaceLandmarker 同量级偏重一点）。80ms 节奏下占空比
  <40%；低端机可 `setDelegate(Delegate.GPU)`（tasks-vision 支持），先不动保持与现役
  spotter 一致。
- 模型档位：**lite 起步**（5.4MB）。方向向量对关键点精度的要求低于绝对坐标，lite 大概率
  够；真机实测方向抖动超标再换 full（9.4MB，改一个文件名的事）。

### 方案 B（备选）：ML Kit Pose Detection

`com.google.mlkit:pose-detection`（bundled，与现役 ML Kit face-detection 同家族），也输出
33 点。**缺点**：①没有米制 worldLandmarks（z 只有粗略相对值）——而肢体方向向量对 z 分量
最敏感；②API 家族与现有两条 MediaPipe 车道不同构（FaceSpotter/GestureSpotter 模式照抄不了）；
③精度口碑低于 tasks-vision 同源模型。**定位：方案 A 真机实测翻车时的退路，不作首选。**

| | A：PoseLandmarker (tasks) | B：ML Kit Pose |
|---|---|---|
| 世界坐标 | 米制 worldLandmarks 直出 | 只有归一化+粗略 z |
| 封装复用 | 完全镜像 FaceSpotter | 要另写一套 ML Kit 封装 |
| 包体 | +5.4MB | +~10MB（accurate 档） |
| 可见性 | visibility/presence 免费 | 有 but API 不同构 |

**推荐 A**：与现有两条车道同构、与 face_landmarker.task 同打包策略，是"最小惊讶"路线。

---

## 4. 数学方案：方向向量 → 骨骼旋转（不搬 Kalidokit 的理由）

### 4.1 为什么不需要 Kalidokit / 两骨 IK

Kalidokit 的核心贡献有二：①面部 blendshape 几何解算——**已被 MediaPipe Tasks
`outputFaceBlendshapes` 原生取代**（deprecated 声明的真实含义）；②身体向量→旋转——这一层
的数学本仓库在视线/呼吸引擎里已经是成建制范式，新增的只是"目标方向多几组"。两骨 IK
（手腕当 end-effector + 肘部 pole vector）需要额外求解器且引入极向量翻转边界；而
**逐段方向驱动**（大臂、小臂各按自己的骨段方向摆到目标）天然无翻转、无累积误差，
Kalidokit 自己也是这个思路。

### 4.2 逐段方向驱动（swing-only）

对每根被驱动的臂骨（upperArm/lowerArm）：

```
绑定时刻(bind)：读 rest 局部矩阵存 L_rest（退场还原用），无需记 rest 方向
每帧渲染循环：
  1. commit 后读关节世界平移：upperArm/lowerArm/hand 三关节位置
  2. dCurrent(upperArm) = normalize(elbowPos − shoulderPos)   ← 本帧动画刚写完的姿势
     dCurrent(lowerArm) = normalize(wristPos − elbowPos)
  3. dTarget 来自 MimicPose（app 车道解算好的镜像方向，§5）
  4. D = GazeMath.quatFromTo(dCurrent, dTarget)               ← 世界系最短弧
  5. L' = inv(parentQ) · D · parentQ · L_current              ← 与 lookAt applyOffset 同式
  6. |angle(D)| < 死区 → 跳写（同 WRITE_EPS 语义，静止零写入）
```

关键性质：

- **绝对驱动、天然幂等**：D 永远是"从当前指向摆到目标指向"，不是增量——写多少帧都不会
  累积，动画这帧把基座重写成什么都能纠正回来。**因此不需要 lookAt 那套 strip/lastWritten
  记账**（那是"相对偏移"叠加层的必需品；绝对驱动只需退场时 restoreRest）。唯一保留
  `capturePreAnimation` 快照的是**退场还原**判定（骨骼是否被动画重写过→决定要不要补写
  L_rest，双信号复用 `BreathBaseResolver`）。
- **无 twist**：swing-only 丢小臂沿轴扭转（如弯肘 90° 后翻腕）。观感代价小（VRM 手部本来
  由待机姿势兜着），P1 接受；真机不满意再补 Kalidokit 式叉积 twist 估计（纯函数增量）。
- **头/颈**：复用 lookAt 的 yaw/pitch 分解路径——`signedYawPitch(forward, toTarget)` +
  `dirFromYawPitch` + clamp（颈部 ±35°/头部 ±45° 收窄到自然范围），faceLocalDir 绑定捕获
  同款。目标方向来自鼻尖−双耳中点向量（P1 精度 ±5-10°，粗糙但够；P2 可穿插
  FaceLandmarker 矩阵提精度，§8-4）。
- **退化保护**：骨段两端世界距离 <2cm（关键点塌缩）或 visibility <0.5 → 该骨本帧**保持
  上一目标**（不跳写不归零）；连续超时 → 该骨交还动画（本技能语义=局部失效，不整场退场）。

### 4.3 时延预算

| 段 | 耗时 | 说明 |
|---|---|---|
| 相机帧→分析回调 | ≤80ms | 既有车道节奏（KEEP_ONLY_LATEST + 80ms 帧级门） |
| PoseLandmarker(lite) 推理 | 15-30ms | CPU，仅技能激活期 |
| 方向解算 + One-Euro | <1ms | 纯函数；One-Euro 对快动作近零滞后 |
| 主线程 ticker 消费 | ≤33ms | 既有 30Hz ticker 顺路（§8-3） |
| 渲染下一帧 | 16-33ms | GPU 帧时决定（SK_Sun+场景 ≈30fps 呈现率，§12） |
| **合计（动作发生→画面跟上）** | **≈120-160ms** | casual 模仿体感"跟手"；无判定死线，超标只是"略迟钝"不是"判错" |

采样率 12.5Hz（80ms）对慢中速动作够用；快速挥臂在 30fps 呈现下会有轻微步进感——缓解靠
引擎侧指数平滑（收敛速率 ≈10-14/s，比 lookAt 的 7 快）+ One-Euro 自适应，属调参项。

---

## 5. 坐标系与镜像：最大的正确性风险，必须标定后锁死

四个坐标系（比看这边还多一个"玩法镜像"），任何一处想当然就是"左右全反"：

| 坐标系 | 语义 | 事实/**真机三轮 A/B 定稿（2026-10-06）** |
|---|---|---|
| MediaPipe 世界系 | worldLandmarks 原始输出 | 原点=髋中点；x+ = 画面右 = 用户左、y+ = 画面下、**z+ = 远离相机**（与 normalized landmarks 官网语义一致）。x/y 由 P1 真机验收锁死；z 曾被第二轮误翻（"z+ 朝相机"），真机「胳膊都跑到身体后面去了」反证原符号正确，第三轮回退。**深度局限**：z 分量精度/比例弱于 x/y（单目深度主轴）——手放胸前等重度深度动作前倾分量偏浅，符号已锁死、幅度属数据质量（§5.2 探针协议） |
| FaceLandmarker 矩阵系 | facialTransformationMatrix | **与 Pose 系不同构**：转换由三轮真机报告三角定位（①误用 Pose 系→左右反；②y 取 +→俯仰反；③rest 恒正确→z 取 −）= `matrixFrame` mirror **(+x,−y,−z)**；换算回矩阵系轴向 ≈ x+ = 画面左、y+ = 下、z+ = 远离相机。⚠ 该组合对右手系代数约束不自洽（列叉积反号）——规范脸模型的 X 轴是镜像存储的，**以真机为准，别按右手系直觉重推** |
| 相机帧 | PoseSpotter 的输入 | 前摄分析帧**不镜像**（`rotatedUpright` 只旋转，`UserCameraTracker.kt:367-373` 同看这边）；用户把头转向他自己的左，帧里鼻尖移向画面右（面对面效应） |
| VRM 世界系 | 渲染世界 | +X 屏幕右、+Y 上、+Z 朝相机（`AvatarController.moveAvatar` doc :461）；模型恒面向 +Z（VRM 0.x 由 renderer 翻转 180° 后绑定） |
| 玩法镜像 | 用户左手 → 虚拟人右手 | **换侧 + 方向 x 取反**（推导见下） |

### 5.1 镜像映射推导（真机三轮标定后锁死）

用户面向相机（即面向 +Z）。设用户左臂抬起，其大臂方向（肩→肘）在世界系里 ≈ (+x, +y, 0)
——用户左侧=观察者右侧=+X。**镜像规则：拿用户左臂数据驱动虚拟人右臂，方向取
`x → −x`（其余分量不变）**。验证：T-pose 时用户左臂 (+1,0,0) → 虚拟人右臂目标 (−1,0,0)
= 虚拟人 T-pose 右臂的自然指向 ✓；抬左臂 (0.2,0.98,0) → (−0.2,0.98,0)，虚拟人右臂上抬 ✓。
即解算器输出统一命名为 **avatarLeftArm/avatarRightArm**（镜像换侧之后的语义），
corelib 引擎按骨名对号入座，不再关心镜像。

```
x_avatar = −x_mp（镜像）    y_avatar = −y_mp（MP y 向下→世界 y 向上）
z_avatar = −z_mp（MP z 越小越近相机→世界 +Z 朝相机；语义反射+纯帧转换合并）
左(用户) → avatarRightArm；右(用户) → avatarLeftArm

矩阵系（头部精解专用）：mirror = (+x, −y, −z) / puppet = (−x, −y, −z)
Pose 系：mirror = (−x, −y, −z) / puppet = (+x, −y, −z)
```

### 5.2 标定协议（真机一次，全部固化成常量+单测，同看这边 §5 精神）

1. `ai_cmd mimic_pose`：回显当前解算的 avatarLeftArm/avatarRightArm 方向分量 + 头部
   yaw/pitch。测试者依次做 **T-pose / 抬左手 / 抬右手 / 双臂前伸** 四个姿势 → 对照预填表
   记录符号 → 修正 `PoseMimicSolver` 的轴映射常量。
2. `ai_cmd mimic_force tpose|left_up|right_up|both_up|forward`：注入合成 MimicPose（免相机
   驱动渲染端）→ 人眼确认虚拟人**抬的是哪边手** → 锁「用户侧 → 虚拟人侧」映射与镜像布尔。
3. 之后任何"方向反了"都是常量表问题：改表 + 已知答案单测，不动逻辑。单测用**合成关键点
   集**（§11）：手工构造 T-pose/抬手/前伸的 33 点坐标，断言解算出的每根骨骼方向分量
   精确值——符号永久锁死。

---

## 6. 帧循环与骨骼所有权：与四个既有写入者的仲裁

`SoulLinkRenderer` doFrame 每帧的写入顺序（:280-379）与新层的落点：

```
captureHipsTranslation / breath.capturePreAnimation / lookAt.capturePreAnimation
→ VRMA 动画分支或内置动画（每帧重写全身局部变换）
→ applyHipsDragOffset（hips 平移重钉）
→ breathEngine.update（spine/chest/upperChest/双肩 微动）
→ lookAtEngine.update（头/颈 偏移 + 眼 注视）
→ ★ mimicEngine.update（neck/head + 双臂 绝对方向驱动）← 新增，唯一落点
→ commitLocalTransformTransaction + updateBoneMatrices（蒙皮传播）
→ expressionManager.update（morph 表情）→ 弹簧骨骼物理 → render
```

### 6.1 所有权矩阵（mimic 激活期）

| 层 | 骨骼 | 冲突 | 仲裁 |
|---|---|---|---|
| idle/VRMA | 全身 | 有（每帧重写） | **mimic 后写=赢**；绝对驱动幂等，无需记账 |
| hipsDrag | hips 平移 | 无 | mimic 不碰 hips（半身锁死） |
| breath | spine/chest/upperChest/肩 | **P1 无冲突**（不重叠）；P2 躯干接管时 mimic 激活期 `setBreathEnabled(false)`、退场恢复（API 现成 :1105） | 后写=赢兜底 |
| lookAt | 头/颈(偏移)+眼 | 头/颈被 mimic 覆写；**眼不冲突** | 头/颈：mimic 后写=赢（lookAt 的双信号记账看到"不是我写的"自动走全量写，无累积——生产验证过的鲁棒性）；**眼保持注视相机**=模仿中眼神接触，白送的效果 |
| mimic | neck/head/双臂 | — | 挂起规则见 6.2 |
| 弹簧骨骼 | 发梢/裙摆 | 无冲突（物理读姿势） | 手臂快速摆动会激励头发弹簧——这正是"真实感"的一部分，观察即可 |

### 6.2 挂起与还原（三条规则）

1. **单发 VRMA 播放期间挂起**：LLM 的 `<act:>` 一次性动作、技能手势播放中
   （`vrmaEngine.isActive()` 且非 idle 槽），mimic 本帧跳写——动画完整播完，播完回 idle
   后 mimic 自动续上（existing `consumeIdleSwap`/`pendingSpringReset` 机制不受影响）。
   传参 `vrmaActive: Boolean` 进 update 即可，引擎不自查。
2. **退场还原**：clearMimicPose 后不再写。idle 在播=下一帧动画自动覆写还原（主路径，
   零代码）；rest pose 定格（idle_off 状态）= 对被驱动骨补写 bind 时 L_rest（双信号判定
   只补"动画没重写的"，照抄 lookAt `restoreRest` :319-342）。建议加 300ms 缓动回位
   （直接 snap 到 idle 姿势观感生硬）。
3. **mimic 自身丢失**：分析车道断供（出画/切后摄）>500ms → 引擎对当前目标做指数衰减到
   "交还动画"（等效 clearMimicPose），技能层同步走可见性降级阶梯（§9）——渲染端不依赖
   技能层也能自愈。

---

## 7. 数据通路：逐帧数据绕过 orchestrator

```
[CameraX 前摄 480×640]                                        UserCameraTracker
   ↓ (80ms 帧级门, 单线程 "video-analysis", 与手势/姿态车道互斥)
[PoseSpotter: PoseLandmarker IMAGE 同步 detect]                 app/video/PoseSpotter.kt(新)
   ↓ worldLandmarks(33) + visibility
[PoseMimicSolver 纯函数: 关键点→镜像后 avatar 坐标系方向集      app/video/PoseMimicMath.kt(新)
   = MimicPose(headYaw,Pitch, avatarLeftArm/RightArm{upper,lower}, visible)]
[MimicDirectionFilter: One-Euro ×6 方向 + 头 yaw/pitch]         (复用 orchestrator OneEuroFilter)
   ↓ @Volatile latestMimicPose 存 tracker
[30Hz 主线程 ticker 消费 → controller.setMimicPose]             MainActivity 既有 ticker(:1106-1127) 顺路
   ↓ (原子引用换手, 渲染线程读)
[VrmPoseMimicEngine.update: 方向→四元数→L'=inv(P)·D·P·L]        corelib(新引擎)
   ↓ (同一帧内, commit 之前)
[updateBoneMatrices 蒙皮传播 → 弹簧 → render]
```

设计要点：

- **orchestrator 全程不在逐帧路径上**——12.5Hz 的事件广播对技能框架是滥用，且 LLM/TTS
  与模仿零关系。技能层只收**低频状态缝**（§9）。
- `MimicPose` 是不可变数据类（corelib 公开类型，`AvatarController.setMimicPose` 参数），
  AtomicReference 换手；引擎读到 null/过期（>500ms）自动交还动画（§6.2-3）。
- 抓拍车道（500ms JPEG 环）不受影响——退场战报的 `snapshotLatest()` 照用。

---

## 8. 架构落点（对齐四层架构，依赖只能向下）

```
:app                    PoseSpotter(新) + UserCameraTracker 第三车道 + PoseMimicMath(纯函数,新)
                        + MainActivity ticker 接线 + ai_cmd 三条 + 视频模式自动切换
:avatar-orchestrator    skill/MimicSkill.kt(状态机) + i18n/MimicTexts.kt(双语)
                        AvatarSkill: +onBodyTracking(默认空实现,零破坏)
:avatar-ai-adapter      零改动
:corelib                internal/VrmPoseMimicEngine.kt(新) + 公开 MimicPose.kt(新)
                        AvatarController: setMimicPose/clearMimicPose/getMimicInfo 三方法
                        SoulLinkRenderer: bind upperArm/lowerArm/hand 六骨 + 引擎接线 + update 落点
```

### app 接线清单

| # | 位置 | 改动 |
|---|---|---|
| 1 | `video/PoseSpotter.kt`（新） | 镜像 `FaceSpotter.kt:58-88`：`PoseLandmarker.createFromOptions`（lite task、IMAGE、numPoses=1、默认 CPU delegate）+ 同步 `detect` + close；返回 worldLandmarks 原样透出 |
| 2 | `video/PoseMimicMath.kt`（新） | 纯函数：`worldLandmarksToMimicPose`（关键点索引表/轴映射/镜像常量全部收口在此）+ `MimicDirectionFilter`（6 路 One-Euro + 死区 + z 阻尼）；JVM 单测主场 |
| 3 | `UserCameraTracker` | `@Volatile var bodyMimicEnabled: () -> Boolean = { false }` + `onBodyVisibility` 回调 + `latestMimicPose` 字段 + `obtainBodyEngine` 懒建（:288-293 同款）+ analyzeFrame 加 `wantBody`（:322-325 同款互斥：`wantPose` 优先（头姿态）→ `wantGesture` → `wantBody`，三条 MediaPipe 车道同线程串行） |
| 4 | `MainActivity` | ticker 里 `videoTracker.latestMimicPose?.let { controller.setMimicPose(it) }` + 可见性节流喂 `session.skills.onBodyTracking`；车道门控 `bodyMimicEnabled = { mimicSkill.isActive && !lookHereSkill.isActive && !rpsSkill.isActive }`（:1269 同款双保险）；注册 mimicSkill（:912-913 同款）；**激活时自动切 VIDEO 模式**（见 #5） |
| 5 | 模式联动 | ticker 或激活观察点：`mimicSkill.isActive && inputMode != VIDEO` → 走 `set_mode video` 同路径切模式（相机权限缺失→错误条提示，不切）。让"模仿我"从任何输入模式一句话进入 |
| 6 | `AiDebugExecutor` + `AiDebug` + `ai-debug-intents.md` | `mimic_status`（状态/可见性/方向回显）/ `mimic_pose`（解算探针，§5.2 标定入口）/ `mimic_force <preset>`（合成姿态注入，免相机 A/B）；help 双份同步 |
| 7 | 构建 | `pose_landmarker.task`（lite float16）拷入 `app/src/main/assets/`；无 Gradle 改动 |

### orchestrator

| # | 位置 | 改动 |
|---|---|---|
| 1 | `skill/MimicSkill.kt`（新） | 状态机（§9）；纯 JVM 可测 |
| 2 | `i18n/MimicTexts.kt`（新） | 激活/退出词正则、开场指令、可见性催促语、退场战报指令（中英相邻单文件） |
| 3 | `AvatarSkill` | `+ fun onBodyTracking(visible: Boolean, ctx: SkillContext) {}`——默认空实现；app 侧**节流**（状态变化或 ≤1Hz）才广播，不逐帧。语义=「身体关键点可见性」，木头人类技能（P2/未来）也可复用 |

---

## 9. 状态机（MimicSkill）

```
IDLE ──激活词──▶ INTRO
   · 本轮照常发送不消费（return false，同猜拳/看这边），指令告诉 LLM：答应+
     一句话讲玩法（"我做什么你 mirror 什么"）+ 提醒正对镜头露出上半身
INTRO ──TurnCompleted──▶ ACTIVE（车道门控放开，引擎开始吃 MimicPose）
ACTIVE:
   · 逐帧数据不经技能层；技能只消费 onBodyTracking(visible)
     visible=false 持续 >2s ──▶ 本地 speak 催促一次（"退后一点/对准镜头"）
     持续 >8s 或催促 3 次 ──▶ EXIT（可见性阶梯；首次催促后重置计数）
   · onBodyTracking(true) ──▶ 计数重置
边界：
· 退出词 ──▶ EXIT
· onInterrupted/TurnFailed（开场/战报回合被打断）──▶ 就地降级：开场被打断→仍进
  ACTIVE（模仿本身不依赖 LLM）；战报被打断→直接 IDLE
· 激活中插话（非退出词）：**放行不消费**（return false）——模仿无回合节奏，
  用户边模仿边聊天是合理场景（与看这边"全程消费"刻意不同）
· 单活跃收口：模仿中说"猜拳"→ RpsSkill 转活 → registry 收口 MimicSkill.onExit
  （车道门控互斥 + 引擎 clearMimicPose 在 onExit 副作用里）
EXIT：clearMimicPose + 车道关 + snapshotLatest() 存证 + sendTurn 战报调侃回合
  （指令：【技能:模仿我·战报】刚结束模仿，图里是他最后的姿势；请口语点评+可加
   表情动作标签，不复述玩法）──▶ IDLE
· 惰性超时兜底：ACTIVE 且 5 分钟无任何事件/样本 ──▶ 静默 EXIT（下一个事件缝检查
  nowMs，无定时器——看这边同款）
```

---

## 10. LLM 的角色：模仿中零回合，首尾各一次

- **模仿中坚决不发 LLM 回合**：逐帧数据 app→corelib 直通；speak 只用于可见性催促（本地
  短句直通 TTS）。开场/战报回合走 `sendTurn`（指令挂 user 前缀、图只挂本轮——猜拳裁判
  回合已验证形态，不碰 system 条数与前缀缓存，A.1 第 34 条不受影响）。
- LLM 三通道协议（`<emo:/act:/>`）在战报回合免费生效；战报里若触发 `<act:>` 单发 VRMA，
  mimic 引擎按 §6.2-1 挂起让路，播完自动续。
- 表情模仿（P2）：FaceLandmarker blendshapes → FaceDriver 既有情绪通道注入，LLM 同样
  无感——留接口不展开。

---

## 11. 测试计划

单测（预计 +30 左右，全部 debug 变体 JVM；Filament 依赖的部分引擎逻辑按惯例抽纯函数 +
真机方波/合成姿态隔离实验终审——呼吸/视线同款方法论）：

| 文件 | 覆盖 |
|---|---|
| `PoseMimicMathTest`（app） | **合成关键点集已知答案**：T-pose/抬左手/抬右手/双臂前伸/弯肘 90° 五组 33 点坐标 → 断言 avatarLeftArm/avatarRightArm 每根方向分量精确值（锁 §5 符号表）/ visibility 门控 / 关键点塌缩保护 / 镜像布尔翻转 / One-Euro 收敛与阶跃响应（照抄 FacePointProjector 测试法）/ 死区与 z 阻尼 |
| `MimicSkillTest`（orchestrator） | 激活/退出正则中英 × 各状态 / INTRO→ACTIVE / 可见性阶梯（催促 1 次→3 次退场→true 重置）/ 插话放行 / 退出词消费+战报回合构造（带图）/ 惰性超时 / debugCommand / barge-in 降级 |
| `SkillRegistryMimicTest`（orchestrator） | 与 RpsSkill/LookHereSkill 三技能共存注册互不误触 / onBodyTracking 广播 / 单活跃收口（模仿中说猜拳） |
| `MimicTextsI18nTest`（orchestrator） | EN 逐成员无中文字符 / ZH 锚点 / of(lang) 分发（照抄 PromptTextsI18nTest） |
| `MimicSolverTest`（corelib） | 纯函数层：MimicPose → 每骨 D 四元数已知答案（合成关节位置）/ 死区跳写 / 退化骨段保持 / 挂起标志（vrmaActive）行为 |
| 真机回归锁定 | `HeadPoseMath`/`matToQuat` 等既有锁不回归；SkillWiringTest 增补 mimic 车道门控组合用例 |

真机验收清单（62fabe84，标定 §5.2 之后）：

1. 标定：`mimic_pose` 四姿势符号正确；`mimic_force` ×4 合成姿态渲染端左右正确。
2. E2E：任意模式说"模仿我"→ 自动进视频模式 + LLM 开场 → 抬左手（虚拟人抬右手）、抬右手、
   双手举高、弯肘、耸肩侧倾——逐个观察方向/幅度/平滑度 → 说"不模仿了"→ 战报点评引用
   最后姿势抓拍。
3. 边界：侧身 90°/出画 → 催促→退场阶梯；模仿中插话聊天正常回复且模仿不中断；模仿中
   `<act:>` 战报动作让路不互搏；模仿中说"猜拳"单活跃切换，车道归手势，猜拳退出后回归。
4. 性能：游戏中分析线程不丢帧（`state` FPS 徽标 + logcat）；idle/呼吸/视线/弹簧在 mimic
   激活/退出前后无残留偏移（每帧骨骼世界姿态日志法——呼吸四轮沉淀的终极仪器）。
5. 观感调参项（非验收门槛）：One-Euro cutoff/β、引擎平滑速率、死区、头 pitch 权重、
   呈现率顿挫（必要时 `load_scene none` A/B）。

---

## 12. 风险清单

| 风险 | 概率 | 对策 |
|---|---|---|
| 坐标系符号搞反（MP 轴向/相机镜像/VRM 世界系/玩法镜像四层） | **高（想当然必翻车）** | §5 预填推导 + 真机标定协议 + 合成关键点已知答案单测；常量表收口，逻辑不写死方向 |
| 单目 z 噪声：手臂前伸指向相机时方向抖 | 中（确定存在，程度待测） | One-Euro + 死区 + z 分量阻尼；引导语"侧对动作更明显"；真机若不可接受→该场景衰减到保持而非跟随 |
| 三条 MediaPipe 车道同线程串行互相挤占 | 低 | 车道互斥已有先例；mimic 激活期手势/头姿态车道关闭（门控双保险）；80ms 门够跑任一单任务 |
| 与 idle/呼吸/视线写入冲突 | 低-中 | §6 所有权矩阵：绝对驱动幂等+后写=赢+单发 VRMA 挂起；复用两个生产引擎已验证的鲁棒性机制 |
| 12.5Hz 采样 + 30fps 呈现的步进感 | 中 | 引擎指数平滑（速率调参）；`load_scene none` 提呈现率；P2 可做样条外推 |
| 模型差异（upperChest 缺失/骨骼命名） | 低 | `resolveHumanoidEntity` 多语义名回退先例；缺骨=对应链路优雅降级（arms 有 hand 无 → 小臂照驱） |
| 弹簧骨骼被快速摆臂激励过头 | 低 | 弹簧参数既有稳定性压测背书（springbone 五轮攻坚）；真机观察，必要时技能激活期不动弹簧 |
| 用户一直不出画/侧身 | 中 | 可见性阶梯（§9）+ 前摄门控 + PiP 自取景 |
| GPU 帧时被推理挤占掉帧 | 低 | 推理在独立分析线程；真机 `state` FPS 观测；低端机 GPU delegate 是反向杠杆（迁渲染压力），留档 |
| 激活词误触发（"学我"日常语义） | 中 | 代价=进模仿态+随时退出词；必要时收紧为「模仿我」单词条 |
| 战报回合图片泄漏进历史/多 system 400 | 无 | 沿用 user 前缀+图挂本轮的已验证形态 |

---

## 13. 分期与工作量

P1 一轨闭环，按可合并粒度切四步（前两步可并行）：

| 步 | 内容 | 验收 |
|---|---|---|
| A 判定件 | pose_landmarker.task 入库 + PoseSpotter + 第三车道 + PoseMimicMath 纯函数 + mimic_pose/mimic_force 调试命令 + 符号标定单测 | JVM：合成姿态已知答案全绿；真机：mimic_pose 四姿势符号正确、mimic_force 四渲染正确 |
| B 引擎 | VrmPoseMimicEngine + MimicPose 类型 + AvatarController 三方法 + SoulLinkRenderer 六骨绑定与 update 落点 + 纯函数单测 | 真机：mimic_force 驱动骨骼、退场还原干净、单发 VRMA 让路、与呼吸/视线共存无残留 |
| C 技能 | MimicSkill + MimicTexts + onBodyTracking 缝 + 车道门控 + 模式自动切换 + ai_cmd 收尾 + 单测 | 真机：激活→模仿→插话聊天→退出战报全链路 |
| D 打磨 | 平滑/死区/z 阻尼调参 + 头部权重 + 可见性阶梯体验 + 观感 A/B | 用户真手感收 |

规模：corelib ~300 行 + app ~400 行 + orchestrator ~250 行 + 测试 ~700 行。估 **2~3 个
工作日**（含真机标定来回；比看这边多在引擎与数学，少在资产与目录排除）。

P2（躯干+表情，另立文档再定）：+肩/脊柱三骨驱动（呼吸暂时让位）+ FaceLandmarker 穿插
策略（分帧轮转 160ms 或双引擎分时）+ blendshapes→FaceDriver 注入。估 1~1.5 天。

---

## 14. 开放问题（需要拍板，均可后改，都有默认值）

1. **`pose_landmarker.task` lite vs full**：默认 lite（5.4MB，推理快）；真机方向抖动超标
   换 full（9.4MB）。打包进 APK（与 face/gesture task 同策略），不做首启下载。
2. **镜像 vs 人偶模式**：默认镜像（用户原话）；`mirror` 常量留切换位，不做设置项。
3. **头部跟随放 P1 还是 P2**：默认 P1（鼻-耳向量，粗糙但"点头摇头"的辨识度高）；若真机
   抖到不可用，P1 砍头保臂（引擎按骨组开关），头部 P2 走 FaceLandmarker 穿插提精度。
4. **模仿中插话**：默认放行（边模仿边聊天）；备选=消费+简短本地回应（会引入 TTS 抢占
   音频的复杂度，不推荐）。
5. **激活时自动切视频模式**：默认自动切（一句话直达玩法）；备选=只提示手动切。相机权限
   缺失两者都降级为错误条提示。
6. **模仿中的本地气氛语**（每 20-30s 一句"学得像不像"）：默认关（TTS 会抢在播音频，
   破坏"安静跟做"的体感）；留 speak 缝，真机体感后再定。
7. **P2 车道穿插策略**：躯干/表情上线时，FaceLandmarker 与 PoseLandmarker 同线程分时
   （奇偶帧轮转 160ms/任务）还是双引擎交替独占（按技能互斥）——P2 立项时定。

---

## 附：与既有约束的对照

- **system 恒单条 / 协议钉住 / 前缀缓存**：开场与战报回合沿用猜拳裁判回合已验证形态
  （指令挂 user 前缀、图只挂本轮），零新增风险面（A.1 第 34 条）。
- **骨骼写入四条铁律**（呼吸四轮攻坚沉淀，全部适用）：①每帧骨骼世界姿态日志是微动归因
  的终极仪器；②数值提取函数的精度偏差经反馈环会变成执行器抖动——mimic 方向解算必须用
  归一化版 `matToQuat`（GazeMath:52）与 atan2 版 `quatAngle`（:183）；③文档在案的精度
  nit 在新增持续运动系统时重新评估；④检测器必须无死区、直读状态。
- **gltfio 静态剔除盒已关**（`applyAvatarCulling` 默认 culling off）：手臂大幅摆动不会
  像眼球丢失案那样被视锥剔除——既有修复白送覆盖。
- **弹簧骨骼 center 语义 / getParent entity 陷阱**：mimic 只读关节世界平移（getWorldTransform）
  与写局部旋转（setTransform），不碰弹簧状态；父 entity→getInstance 往返照 VrmLookAtEngine
  :285-297 先例。
- **onBodyTracking 是通用缝**：落地后「123 木头人」（身体静止检测）类技能即解锁，框架
  本体依旧不膨胀。
- **多语言**：全部用户可读文案走 `MimicTexts`（中英相邻单文件正本）；激活/退出词表中英
  合并在同一正则（识别语言跟随设备）。
- **org.json 在 JVM 单测不可用**（A.1 第 33 条）：技能/解算纯逻辑不碰 JSON。
- **HyperOS 禁 shell 触摸注入**：免麦 A/B 入口=`mimic_force`（合成姿态）与激活词语音；
  设置页若加开关走 ai_cmd 同路径验证。
