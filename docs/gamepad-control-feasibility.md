# 手柄控制可行性方案（虚拟人移动 + 相机视角，兼 AI 原生调试接口）

> 目标：像 PC 游戏一样用蓝牙/USB 手柄控制虚拟人走动和相机环绕——左摇杆走路、右摇杆转视角；
> 同一条输入通道对 AI 开放（ai_cmd `pad` 命令族），成为「模拟量连续控制」的原生调试接口。
> 2026-10-24 调研定稿；真机基线 Redmi K20 Pro / Android 11 / wifi-adb 在线，ai_cmd 链路已验证可用。

---

## 1. 背景与双身份

现有调试手段的语义缺口：

| 现有通道 | 语义 | 缺什么 |
|---|---|---|
| `walk_to` / `<move:锚点>` | 点到点（目标+速度，状态机自动到位） | 无法表达「往前走 2 秒」「边走边停」「跟着手感走」 |
| `move`（ai_cmd） | 直接平移根节点（瞬移，无步态） | 滑步、无走路动画、绕过 bounds |
| `orbit/zoom/pan`（ai_cmd） | 一次性像素增量 | 没有「按住持续转」的过程量 |

手柄摇杆恰好是**模拟量过程控制**的天然载体。本方案让它服务两个身份：

1. **人类调试器**：手持 Xbox/PS 手柄在真机旁直接操纵，比 adb 逐条发指令快一个数量级；
2. **AI 原生调试接口**：`pad` 命令族写的是与硬件**同一个状态枢纽**，AI 获得连续控制语义，
   且人类触碰摇杆可随时抢占（人 > AI 的仲裁天然成立）。

非目标（明确不做）：手柄打字（系统 IME 自带）、体感陀螺仪转向、多手柄同时操控（P2 若做多虚拟人再说）。

---

## 2. 现状盘点（代码锚点）

### 2.1 走动：逐帧 retarget 高度可行

`LocomotionEngine`（`corelib/internal/LocomotionEngine.kt:32`）是纯数学状态机，渲染层每帧喂根位置、取回 `FrameMotion`：

- **retarget 是一等公民**：`startWalk()` 注释明确「Retargeting mid-walk keeps the heading and the ease ramp」，
  且 `SoulLinkRenderer.startWalk`（`:2673`）有同款步态快速路径（不重载剪辑不重播）——逐帧改目标不需要新状态机。
- **默认步态是外部步态（方案 C）**：`DEFAULT_WALK_CLIP = "Female Normal Walk.vrma"`、baseSpeed 1.22 m/s
  （`app/SceneMetaParser.kt:68`），带烘焙位移走提取回灌（`updateLocomotion` `:2944`）。外部步态**没有对齐门**
  （`ALIGN_RAD` 门 `:2657` 只约束程序位移模式）→ 摇杆转向时身体沿弧线自然转弯，正是手柄手感。
- **制动公式现成**：`brakeSpeed = sqrt(2·a·dist)`（`:270`，a=2.2 m/s²）——连续控制下目标距离恒定，
  只要把 lookahead 定为 ≥ v²/2a，制动永不介入，速度恒定不打顿。
- run/strafe 槽已配齐（`ensureWalkGaits`，`AiDebugExecutor.kt:832`），跑步自动选用阈值 `RUN_SPEED_MPS=1.9`（`:2631`）。

**唯一的真坑**：同款步态快速路径要求 `prevSpeed == speed`
（`SoulLinkRenderer.kt:2675`）——逐帧 retarget 时速度是模拟量（每帧都不同），会命中慢路径
→ **每帧重载 VRMA + 重播回第 0 帧 = 原地抽搐**。必须修（§4.3）。

### 2.2 相机：手势原语封装即接口

- 程序化相机没有 setFrame，一切走 Manipulator 手势原语；渲染层已封装好三个入口
  （`SoulLinkRenderer.kt:3056-3083`）：`zoomCamera(spreadPx)` / `panCamera(dx,dy)` / `orbitCamera(yawPx,pitchPx)`，
  经 `AvatarController`（`:977-1000`）公开。**右摇杆 = 每帧喂 `orbitCamera(dx·rate·dt, dy·rate·dt)` 即可。**
- `orbitSpeed = 0.01 rad/px`（`MANIP_ORBIT_SPEED`，`:465`）→ **90° 环绕 = 157px 定值**，dpad 快照转向直接换算。
- 预设机位是逐帧闭环 steering（`updateCameraShot` `:3163`），而 `orbitCamera` 每次调用都 `cancelShotTransition()`
  （`:3067`）→ 摇杆一碰就释放机位回自由相机，语义干净，无需额外仲裁。
- 触摸手势在渲染层内部接线（`surfaceView.setOnTouchListener`，`:706`）；手柄事件走 Activity 分发，互不相干。

### 2.3 ai_cmd：有状态 pad 命令族天然契合

Intent→`Channel`→`LaunchedEffect` 消费→`AiDebugExecutor.when(name)`→logcat `AIDebug` 回读
（`MainActivity.kt:215/2622`、`AiDebugExecutor.kt:155`）。ai_cmd 是一次性 intent，pad 用
**「锁存直到释放」**语义即可把过程量摊平成一两次命令（§6）。

### 2.4 输入入口现状

- Activity 层目前**没有** `onKeyDown`/`onGenericMotionEvent` 覆写（grep 零命中）——手柄接入是全新增量，无历史包袱。
- 现有输入只有 SurfaceView 触摸 + Compose 手势；Compose 文本框聚焦会吃按键 → 手柄必须在
  `dispatchKeyEvent` 顶层拦截（§5.3 坑）。
- 真机当前无手柄接入（`dumpsys input` 无 GAMEPAD 设备）；Android 11 标准支持 BT/USB 手柄，无需权限。

---

## 3. 总体架构：一个枢纽、两个生产者、一个消费者

```
┌ 硬件层（app）──────────────────────────────────────────────┐
│ MainActivity.dispatchKeyEvent / onGenericMotionEvent      │
│   → GamepadHub（轴位归一+死区+按键沿检测+热插拔）          │
└──────────────┬────────────────────────────────────────────┘
               │ 写                     ┌ ai_cmd pad 命令族（app）┐
               ▼                        │ pad move/look/release… │
        ┌───────────────┐  写           └──────────┬─────────────┘
        │  GamepadHub   │◄─────────────────────────┘
        │ (双摇杆状态+   │   生产者二：AI 锁存（人触碰即抢占）
        │  按键位图)     │
        └───────┬───────┘
                │ controller.setDriveAxis(fx, fz) / setLookAxis(dx, dy) / setRunBoost(b)
                ▼   （主线程写入，与其它 controller API 同契约）
┌ corelib 消费层 ───────────────────────────────────────────┐
│ SoulLinkRenderer.doFrame：                                │
│   drive 轴 → updateLocomotion 内逐帧 retarget（§4）        │
│   look 轴  → orbitCamera(dx·rate·dt, dy·rate·dt)（§5）     │
└───────────────────────────────────────────────────────────┘
```

分层原则与仓库铁律一致：

- **corelib 只提供「模拟量通道」**（drive/look 两个 -1..1 轴 + 一个速度增益），不知道「手柄」为何物
  ——SDK 使用者可接键盘、遥控车把手、蓝牙踏板任何来源。这是能进 Maven 发布的 SDK 级能力。
- **app 层承载 Android 手柄协议**（轴位映射、死区、热插拔、按键语义）+ ai_cmd 生产者 + HUD。
- **两条生产路径写同一个 Hub**，`pad_state` 可回读全部状态，AI 与人看到的是同一份世界。

---

## 4. 走动：左摇杆 → 连续移动

### 4.1 方案选型：逐帧 retarget（复用状态机），不新增速度模式

| 候选 | 结论 |
|---|---|
| A. 引擎新增 `velocityMode`（速度矢量直驱） | 否。新状态机分支、新 ease/制动/到达语义、全套新单测；与 TURNING/WALKING 两相正交性被打破 |
| B. **逐帧 retarget**：target = pos + 方向 × lookahead，speed = 摇杆模长 × 上限 | **采用**。`startWalk` 本来就支持走动中改目标且保留 ease；步态选择（walk/run）、bounds clamp、到达/停步 crossfade、AI 防抖语义全部免费复用 |
| C. app 层每帧调 `controller.startWalk` | 否。有 §4.3 ①的坑且相机相对方向需要相机位姿——渲染层自持 `cameraManipulator`，就近消费 |

### 4.2 关键数学（新 `corelib/internal/DriveMath.kt`，纯 JVM 可单测）

```
死区（径向）：m = |stick|，m ≤ 0.12 → 0；否则 m' = (m − 0.12) / (1 − 0.12)
方向（相机相对，PC 游戏惯例）：
    gaze = normalize(target − eye)           // cameraManipulator.getLookAt
    fwd  = normalize(gaze.xz)                // 投水平面
    right = (−fwd.z, 0, fwd.x)               // 右手系验证：gaze=+Z 时 right=−X ✓
    dir  = fwd · fz + right · fx             // fx=右+，fz=前+
速度：v = m' × V_WALK_MAX × runBoost      // V_WALK_MAX=1.2，runBoost: 1.0/1.8
lookahead：d = max(0.35, v²/(2·a) × 1.3)  // a=2.2 与引擎制动常数同源
    // v=1.2 → d=0.35（下限兜底）；v=2.16(跑) → d=1.39 → 制动 cap=√(2·2.2·1.39)=2.47 ≥ v，
    // 制动永不介入 → 恒速平滑；松摇杆走 stopWalk() 的停步 crossfade，不用制动曲线
```

- **到达永不触发**：dist ≡ lookahead ≥ 0.35 ≫ ARRIVE_EPS(0.06)，松手前不会误发 arrived。
- **bounds**：engine 的 clamp 照常生效，撞墙即停在边界上，无额外处理。
- **turn**：引擎限速 2.6 rad/s（150°/s）对手柄手感合适；外部步态走弧线转弯，程序步态原地转（`ALIGN_RAD` 门）
  ——两者都自然。
- **方向反转**：摇杆从前进拉到后退，外部步态沿弧线掉头（约 1.5s 转半圈）——符合「角色是活物」的预期；
  不做「后退播放倒走步态」（素材库无优质倒走循环，P2 可加）。

### 4.3 corelib 三处改动

① **修同款步态快速路径**（`SoulLinkRenderer.startWalk` `:2675`，本方案的前置修正）：

```kotlin
// 旧：prevGait == gait && prevSpeed == speed && …（速度变了=重播剪辑=抽搐）
// 新：prevGait == gait && …；速度差异用 setPlaybackSpeed 补上：
val sameGaitPlaying = prevGait == gait &&
    locomotionEngine.snapshot().phase != WalkPhase.IDLE && vrmaEngine?.isActive() == true
if (!sameGaitPlaying) { /* 现有重载路径 */ }
else if (speed != prevSpeed) vrmaEngine?.setPlaybackSpeed(speed / gait.baseSpeed)
```

程序位移步态（非提取）同样受益：`setPlaybackSpeed` 已有先例（mode C 每帧 `:2941`、stopWalk 冻结 `:2777`）。
回归风险：`walk_to` retarget 行为不变（走 `locomotionEngine.startWalk`，播速不再因速度微调重置）——
补一条 retarget 不重播的单测。

② **drive 通道与帧泵**：渲染层新增持有两个字段 + `updateLocomotion` 开头消费：

```kotlin
// 主线程写入（Activity 手柄回调与 doFrame 同线程，无并发）
fun setDriveAxis(forward: Float, strafe: Float)   // -1..1，渲染层内换算相机相对方向
fun setLookAxis(yaw: Float, pitch: Float)         // -1..1，doFrame 内换算像素
fun clearDrive()                                  // = 双轴归零 + stopWalk()
```

消费点在 `updateLocomotion(dtSeconds)`（`:2905`）：
- 轴非零 → 算 lookahead/speed → `locomotionEngine.startWalk(tx, tz, v, bounds)`（若当前 IDLE，
  这会自然走 gait 起步 + crossfade）；轴为零且「上一帧在 drive」→ `stopWalk()`。
- 拒绝态直接落日志（NO_SCENE 虚空行走守卫 / MIMIC_ACTIVE / POSE_ACTIVE 沿用 `WalkStartResult`，
  渲染层已有同款处理路径），不抛异常——摇杆挂着不呛帧。

③ **startWalk 日志降级**：`:2689` 的 `Log.i("startWalk target=…")` 逐帧刷会淹死 logcat——
drive 路径 500ms 节流或只在（目标变化 > 0.1m / 速度变化 > 0.1 / IDLE→WALKING 边沿）时打。

### 4.4 与既有语义的仲裁（全部免费成立，写进文档即可）

| 场景 | 行为 | 出处 |
|---|---|---|
| AI `walk_to` 走动中用户推摇杆 | 摇杆 retarget 直接改目标（人抢 AI） | retarget 语义 |
| 摇杆走动中 AI 发 `walk_to` | `ALREADY_WALKING` 忽略（防抖坑 9） | `LocomotionDriver.walkTo` |
| LLM 单发手势 | `walk_overlay` 开=上半身叠加继续播（边走边比划） | P2 既有 |
| 坐/躺接触定姿中推摇杆 | `POSE_ACTIVE` 拒绝 + HUD toast；P1 改为「自动起身再走」 | `WalkStartResult` |
| 模仿我（mimic）激活 | `MIMIC_ACTIVE` 拒绝 | 同上 |
| 无场景 | `NO_SCENE` 拒绝（虚空行走守卫坑 4） | 同上 |
| 松手 | `stopWalk()` → crossfade 回待机 + `walkFinished(arrived=false)` → AI 多段腿队列清空 | 既有 |

---

## 5. 相机：右摇杆 → 环绕 + 缩放

- **右摇杆**：doFrame 内每帧 `orbitCamera(dx · LOOK_RATE_PX · dt, dy · LOOK_RATE_PX · dt)`。
  `LOOK_RATE_PX ≈ 900 px/s`（满杆 ~9 rad/s 太快，实测调参；0.01 rad/px × 900px/s = 9 rad/s，
  实际按摇杆模长缩放后 ~5 rad/s 满杆，合适）。像素取整最小 1px（`grabCamera` `:3077` 已按 Int 处理）。
- **与预设机位互斥**：`orbitCamera` 自带 `cancelShotTransition()`——摇杆碰相机即释放机位；
  R3/`camera_shot follow` 随时召回跟拍。跟拍中推右摇杆 = 临时自由视角，松手不自动回跟拍（显式召回，
  避免「相机打架」的玄学体验）。
- **扳机键缩放**：LT/RT（AXIS_BRAKE/GAS）按住 → 每帧 `zoomCamera(∓ZOOM_RATE_PX · dt)`，
  `ZOOM_SPEED_PER_PIXEL=0.1`（`:540`）换算；推拉速率 ~600 px/s 起调。
- **dpad 90° 快照**：orbit 速率恒定 → 90° = (π/2)/0.01 ≈ **157px 定值**，左右各一次
  `orbitCamera(∓157, 0)`。上/下 = 预设机位循环（closeup→medium→full→long→off）。
- **Y 键回正**：`resetCamera()` + `setCameraShot(FOLLOW)` 组合（「相机回到角色身后跟拍」）。

---

## 6. AI 原生接口：`pad` 命令族

写的是与硬件**同一个 GamepadHub**；单次 intent 用「锁存」表达过程量：

| 命令 | 参数 | 语义 |
|---|---|---|
| `pad move` | `<左右-1..1> <前后-1..1> [秒]`（或 `ai_x/ai_y/ai_z` 按位） | 设置左摇杆（**相机相对**，与手柄一致，+1=前）；`秒>0` 到时自动归零（过程指令），省略=锁存（最长 10s 安全阀） |
| `pad look` | `<yaw> <pitch> [秒]`（或 `ai_x/ai_y/ai_z` 按位） | 设置右摇杆；同样锁存/限时 |
| `pad release` | — | 双轴归零（=松开手柄） |
| `pad run` | `[on\|off]` | 跑步增益开关（省略=翻转，=A 键） |
| `pad press` | `a\|b` | 单发按键事件注入（沿触发，同硬件按键） |
| `pad state` | — | 回读：连接的输入设备名/轴值/锁存源(HW\|AI)/跑步/走动相位 |
| `pad on\|off` | — | 手柄控制总开关（禁用时硬件事件直通不消费，防误触） |

设计要点：

- **安全阀**：AI 锁存最长 10s 无刷新自动归零（泄漏的 `pad move 0 -1` 不能让角色无限走到烧屏）；
  硬件摇杆真实越死区 = 立即清 AI 锁存、源切 HW（人抢占 AI，粘性）。
- **`ai_x/ai_z` 复用现有 extras**（`parseAiDebugCommand` `AiDebug.kt:68` 零改动，float/字符串手误兼容白送）。
- `pad state` 是 AI 的「手感回执」：`drive=AI f=(0.00,-0.80) v=0.96 phase=WALKING pos=(1.2,0.3) dev="Xbox Wireless Controller"`。
- 时长语义让 AI 能说「往前走两秒」：`pad move 0 -1 --ef ai_y 2`——这是 `walk_to` 做不到的过程性表达。
- 每条 pad 命令照常打 `AIDebug` OK/FAIL，`docs/ai-debug-intents.md` 追加章节（该文档是手册唯一事实源）。

---

## 7. 手柄硬件层（app，Android 标准输入）

### 7.1 事件捕获

```kotlin
// MainActivity 覆写，顶层拦截（先于 Compose/焦点分发）
override fun dispatchKeyEvent(e: KeyEvent): Boolean =
    if (gamepadHub.onKey(e)) true else super.dispatchKeyEvent(e)
override fun onGenericMotionEvent(e: MotionEvent): Boolean =
    if (gamepadHub.onMotion(e)) true else super.onGenericMotionEvent(e)
```

- 手柄识别：`e.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0` 或
  `e.source and InputDevice.SOURCE_GAMEPAD != 0`（KeyEvent 走 `event.isGamepadButton`）。
- **必须用 `dispatchKeyEvent` 而非 `onKeyDown`**：聊天输入框聚焦时按键先被焦点视图消费，
  onKeyDown 拿不全（§2.4 坑）。摇杆/扳机走 `onGenericMotionEvent`（MotionEvent 不进 IME，无抢占问题）。

### 7.2 轴位标准映射（Android 约定）

| 轴 | 常量 | 用途 |
|---|---|---|
| 左摇杆 | `AXIS_X`(左右) `AXIS_Y`(前后，**上=−1**) | drive：fz=−AXIS_Y, fx=AXIS_X |
| 右摇杆 | `AXIS_Z`/`AXIS_RZ`（部分杂牌 `AXIS_RX`/`AXIS_RY`） | look |
| 扳机 | `AXIS_LTRIGGER`/`AXIS_BRAKE`、`AXIS_RTRIGGER`/`AXIS_GAS` | 缩放 |
| 十字键 | `AXIS_HAT_X`/`AXIS_HAT_Y` | 快照/机位 |

右摇杆双常量兼容：Xbox 系走 Z/RZ，部分手柄走 RX/RY——按 motionRanges 实际存在哪个非零范围选用
（`pad state` 打印全部轴原始值，标定工具自带）。

### 7.3 按键映射（默认表，收敛在单一 `GamepadMap` 对象，改映射不改逻辑）

| 键 | P0 | P1 |
|---|---|---|
| **A** | 跑步增益 toggle | — |
| **B** | 停走+停转（双轴归零） | — |
| **Y** | — | resetCamera + FOLLOW |
| **X** | — | 触发最近交互点（§9 P2 前置探路） |
| dpad | 左右=90° 环绕快照 | 上下=机位循环 |
| LT/RT | — | 按住缩放 |
| L1/R1 | — | 上/下一个预设机位 |
| R3 | — | camera_shot follow toggle |
| L3 | — | resetCamera |
| START | — | screenshot |

P0 只做**双摇杆 + A/B**——摇杆是本方案 90% 的价值，按键是甜点；P0 控制范围 = 最小可验收。

### 7.4 热插拔与 HUD

- `InputManager.registerInputDeviceListener` 监听手柄接入/拔出（BT/USB 通用）；
  启动时扫 `InputDevice.getDeviceIds()` 处理「已连接再启动 app」。
- HUD：现有 Box 覆盖层加一枚 🎓chip（`🎮 Xbox — L:move R:cam`），连接时出现、8s 后淡出，
  `pad state` 可随时回读；接入/断开写 `AIDebug` 日志（AI 也能感知手柄在不在）。
- 震动（P2）：`InputDevice.vibrate` 反馈跑步切换/到达——Android 11 可用，纯甜点。

---

## 8. 分阶段计划

### P0 —— 模拟量通道 + 双摇杆 + pad 命令族（≈2.5 人日）

1. corelib：`DriveMath`（纯数学：死区/相机相对方向/lookahead）+ 单测；
   `startWalk` 快速路径修 speed（§4.3 ①）+ retarget 不重播单测；drive/look 通道 + doFrame 消费（≈1 人日）。
2. app：`GamepadHub`（轴归一/死区/按键沿/热插拔）+ MainActivity 分发覆写 + 双摇杆+A/B 接线（≈0.5 人日）。
3. app：`pad` 命令族七条 + `ai-debug-intents.md` 手册 + help 文本（≈0.5 人日）。
4. HUD chip + 真机验收（§9，≈0.5 人日）。

### P1 —— 按键全家桶 + 体验打磨（≈1.5 人日）

dpad 快照/机位循环、扳机缩放、L1/R1 机位步进、Y 回正、R3 跟拍、L3 复位、START 截图；
`ai_y` 时长语义；POSE_ACTIVE 自动起身再走；震动反馈；GamepadMap 全表单测。

### P2 —— 探路性质，逐个单独立项

- **X 键=最近交互点**：接 `InteractionCoordinator`（dream_bedroom 16 交互点）——注意
  `awaitStandstill` 固定 30s、多段腿途中发起会静默 abort 的已知坑（memory：桃隐仙居接入）；
- 屏幕虚拟摇杆（Compose 覆盖层，无手柄手机也能用同一通道）；
- drive 作为 LLM 工具（`drive <fx> <fz> <seconds>`）——「自动代驾」演示，让虚拟人自己开着摇杆逛房间；
- 舞台路径 `AvatarHandle` 挂 drive 通道（多虚拟人各控一个手柄）；
- 倒走步态槽 + 后退语义。

---

## 9. 真机验收清单（wifi-adb 全程可跑）

前置：Xbox 手柄长按配对键 → 手机蓝牙设置配对（或 USB-C 线直连）。

```bash
PKG=com.neethu.aiavatar_sdk
adb shell am start -n $PKG/.MainActivity --es ai_cmd state
adb logcat -d -s AIDebug | grep -i gamepad        # P0 后：连接事件
adb shell am start -n $PKG/.MainActivity --es ai_cmd "list" --es ai_arg scenes
```

| # | 验收项 | 方法 |
|---|---|---|
| 1 | 手柄识别 | 连接后 `pad state` 回显设备名；HUD chip 出现 |
| 2 | 走路手感 | 左摇杆前推/斜推/缓推：起步 ease、斜走、缓推慢走；松手 crossfade 回待机 |
| 3 | 相机相对 | 相机 orbit 180° 后前推摇杆，角色应朝屏幕里走（非世界 +Z） |
| 4 | 跑步 | A 键后满杆 ≥1.9 m/s 自动换跑步步态（`walk_state` 看 gait=RUN/extract） |
| 5 | 撞墙 | 摇杆怼墙方向：clamp 在 bounds 内无抖动 |
| 6 | 无步态/无场景守卫 | `load_scene none` 后推摇杆：HUD toast `NO_SCENE`，帧率不掉 |
| 7 | 视角 | 右摇杆环绕平滑无阶梯；dpad 左右=90°；跟拍中推右摇杆→释放，R3 召回 |
| 8 | AI 锁存 | `pad move 0 -1`（锁存）走直线；硬件碰摇杆→立即接管；`pad release` 停 |
| 9 | AI 过程指令 | `pad move 0 -1 --ef ai_y 2`：走 2 秒自动停 |
| 10 | 安全阀 | 锁存后静置 10s：自动归零（logcat 看 drive timeout） |
| 11 | 人抢 AI | AI `walk_to` 途中推摇杆立即改道；摇杆走动中 AI `walk_to` 回 `ALREADY_WALKING` |
| 12 | 帧率 | drive 开/关各 `ai_cmd state` 看 fps，差值 ≤2（纯数学消费，应 ≈0） |
| 13 | 与 AI 同屏共存 | `send_chat` 让 LLM 说话+手势时摇杆走动：口型/手势 overlay 正常叠加 |

---

## 10. 风险与坑位预判

| # | 风险 | 预案 |
|---|---|---|
| 1 | 杂牌手柄轴位/极性偏离标准 | `pad state` 打印全轴原始值标定；`GamepadMap` 收敛映射点；实测纠偏只改一处 |
| 2 | 右摇杆 Z/RZ vs RX/RY 两派 | 按 motionRanges 探测选用；两个都给值时取绝对值大者 |
| 3 | Compose 焦点吃按键 | `dispatchKeyEvent` 顶层拦截（§7.1），不依赖 onKeyDown |
| 4 | 逐帧 retarget 重播步态（§4.3 ①） | 本方案前置修正 + 单测锁死；验收项 2/12 兜底 |
| 5 | startWalk 日志刷屏 | §4.3 ③ 节流 |
| 6 | 摇杆漂移（旧手柄中位非零） | 径向死区 0.12 + 上电中位校准（Hub 记录接入后首帧值为零点，P1） |
| 7 | AI 锁存泄漏 | 10s 安全阀 + 硬件抢占 + `pad off` 总闸 |
| 8 | 与 `camera_shot` steering 打架 | 摇杆即 cancelShotTransition（现成语义），不做叠加仲裁 |
| 9 | follow 跟拍下走路相机穿越家具 | 已有防遮挡剖切 + 相机逼近自护（`:2966`）沿用，不加新逻辑 |
| 10 | wifi-adb 高延迟下 pad 命令「迟滞」 | 锁存语义本身就是为高延迟设计的（单命令表达持续状态）；迟滞只影响改变瞬间 |

---

## 11. 测试策略（沿用纯 JVM 风格）

- `DriveMathTest`：死区边界/径向形状、相机相对方向已知答案（gaze=+Z→right=−X）、lookahead-制动恒速
  不变量（v ∈ [0.3, 3.0] 时 √(2a·d) ≥ v）、轴钳制。
- `LocomotionEngine` 补测：retarget 连发不触发 arrived、ease 保留。
- `SoulLinkRenderer` 逻辑抽出的快速路径判定：同 gait 异 speed 不重播（用可见状态断言）。
- `AiDebug`：pad 族解析（字符串 float 手误、ai_y 时长、pad press 白名单）。
- `GamepadHubTest`（轴/键逻辑抽纯类）：死区、按键沿（down+up 一次触发）、AI 锁存超时、硬件抢占清 AI、
  双源仲裁（HW 源标记粘性）。

---

## 12. 结论

**可行且便宜**：走动侧 `LocomotionEngine` 的 retarget 语义与外部步态（默认素材带烘焙位移、无对齐门）
几乎是为摇杆连续控制准备的，只需修一个 speed 等值判定的快速路径坑；相机侧三个手势原语封装就是现成接口；
ai_cmd 的 Channel 架构挂一组 `pad` 命令即可让 AI 与手柄共用同一模拟量通道。P0 约 2.5 人日，
产出同时是 SDK 公开能力（corelib 模拟量通道进 Maven）与 demo 调试外设（app 手柄映射）。

---

## 13. P0 实现与真机验收结果（2026-10-24 收官）

P0 已全部实现并真机验证（Redmi K20 Pro / Android 11 / wifi-adb，无物理手柄——AI pad 通道与按键
注入走的是与硬件事件完全相同的枢纽/驱动路径）。

**落地清单**（与 §8 P0 对应）：

- corelib：`DriveMath`（死区/速度/lookahead/相机相对方向，公开类）+ `DriveMathTest` +
  `LocomotionJoystickDriveTest`（逐帧 retarget 不误到达/制动不压速/变向不炸）；`AvatarController`
  新增 `setDriveAxis/setLookAxis/setDriveRun/clearDrive/isDriveWalking`；`SoulLinkRenderer`
  drive/look 通道 + `updateLocomotion` 帧内消费 + **startWalk 快速路径修 speed 判定**（§4.3①）+
  startWalk 日志按「新起步必打、retarget 500ms 节流」降级。
- app：`GamepadHub`（进程级单例！）+ `MainActivity.dispatchKeyEvent/onGenericMotionEvent/onPause`
  分发 + HUD 🎓chip + `pad` 命令族 + `PadCommandTest`（解析 13 例）+ help 文本 +
  `ai-debug-intents.md` 手册行。

**验收通过项**：锁存走动（`pad move 0 1 3` 走 3s 自动停，目标距离=lookahead 理论值 0.43m）；
相机相对方向（向远离相机方向走、身体转向行进方向）；跑步挡（A 键 → speed 2.16 → `gait=RUN`
自动换挡）；视角环绕（`look 0.9 0 2` 绕角色 ~90°，限时自动释放）；按键注入（KEYCODE 96/97）；
`walk_to` 回归正常；drive 中 fps=60 零损耗；AI 锁存安全阀与人机抢占按设计。

**真机新坑（全在本方案 §10 之外，实锤）**：

1. **设备判定三连坑**：`Virtual` 虚拟注入设备声明 JOYSTICK 类；触摸屏 `goodix_ts` 也带 JOYSTICK
   类位；**电源键 `qpnp_pon` 的 sources=0x301 与 SOURCE_GAMEPAD(0x401) 按位与 = 0x1 非零**——
   `and SOURCE_GAMEPAD != 0` 会把电源键判成手柄！最终判定：排除 virtual/touchscreen →
   `and SOURCE_GAMEPAD == SOURCE_GAMEPAD` 整模式匹配 → JOYSTICK 类还需有 AXIS_X/Y/HAT_X 轴。
2. **MIUI 生命周期闪断**：每次 `am start`（包括 ai_cmd 查询）约 1/3 概率触发瞬时
   onPause→resume（日志实锤 `gamepad axes cleared on pause` 三次）——曾伪装成「AI 锁存泄漏/
   按键丢失」。onPause 清轴是设计行为（防后台走位），归因看该日志；**GamepadHub 必须进程级单例**
   （Activity 重建会丢 run/轴状态并重复注册 InputManager 监听）。
3. **KEYCODE_BUTTON_A = 96 不是 108**（108/109 = START/SELECT）——注入测试按错键码浪费了三轮
   排查；`isGamepadButton` 过滤本身正确。
4. **走路慢 4~7 倍的真正根因（2026-10-24 深夜定案，corelib 修复）**：`VrmaAnimationEngine.bindToModel`
   用 hips **世界** y 当身高比基准——但 `transformToUnitCube` 会把模型**居中**，世界 y 实为
   「hips 相对模型中心的高度」（4705 模型实测 0.108，局部 rest y 0.893），拿它除素材
   restHips(1.04) 得 0.104，把所有 VRMA 平移轨道写值与方案 C 提取位移同比压小 ~8×——
   walk_to/`<move:>` 标签/手柄全部走不快（实测 0.15 m/s），跑/侧移自动标定 baseSpeed 同源
   失真（0.22/0.08/0.23）。**修法 = 改用 hips 局部 rest y**（bind 时 `getTransform` 直读，
   不受居中平移污染；局部/素材两侧同为各自 rig 单位，比例即标准比例重定向，世界位移由反馈
   侧 renderScale 补齐，写骨骼与提取同字段保持零滑步）。修复后：walk 素材净步幅 0.16→1.27
   单位/圈，run 自动标定 0.22→**1.78 m/s**，侧移 0.66/1.83 m/s，实测锁存走动 **4.2m/4s ≈
   1.0-1.2 m/s**（撞 bounds 正确停），提速 ~7×。
5. **测试纪律坑**：真机屏幕超时熄灭 → Activity pause → 渲染冻结 + `onPause` 清轴——伪装成
   「AI 锁存泄漏/按键丢失/走动冻结」，`svc power stayon true` + `input keyevent 224` 先行；
   ai_cmd 的每次 `am start` 在 MIUI 上仍约 1/3 概率瞬时 onPause（归因日志
   `gamepad axes cleared on pause`）。
6. 死区重标公式修正：对角满偏模长 √2，重标后必须**封顶**（`coerceAtMost(1)`），否则斜推放大
   到 1.47×——已修并有单测（输出模长恒 ≤1）。

**真手柄已连（Microsoft X-Box 360 pad）**：热插拔监听与设备判定实测通过（`pad state` 回显
设备名）；摇杆轴位/极性、Z/RZ vs RX/RY 双派、死区手感待用户实操反馈。
