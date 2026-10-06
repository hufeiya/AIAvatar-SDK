# 「看这边」反应力技能（方向反转游戏）可行性分析

> 生成时间：2026-10-06。对应代码状态：`main@9120377` + 工作区（`12_技能_看这边` 四个指向手势 VRMA 已就位）。
> 结论先行：**可行，且是比猜拳 P2 更"端侧"的技能**——判定完全不经过大模型（本地头部姿态 →
> 本地胜负 → `speak()` 直通 TTS），LLM 整场零参与，只在退场时发一次"战报调侃"回合。
> 用户要求的「200ms 内检测出头偏方向」轻松达标（帧节奏 80ms + 单帧推理 10~30ms ≈ ≤110ms）；
> **真正的节奏瓶颈在云 TTS**（宣判首声 ~1.2s），实际一轮 ~2.5~3.5s 而非 1~2s（§4 有压缩手段）。
> 需要新增的硬件缝只有一个：MediaPipe **FaceLandmarker** 判定件（+~3.7MB，复用现有 tasks-vision
> 依赖）+ 一条 `onHeadPose` 事件缝；技能框架、状态机先例、快照、TTS、ai_cmd 全部现成。

> **落地状态（2026-10-06，同日实现完成）**：§3 方案 A（FaceLandmarker）落地——
> `facialTransformationMatrixes()` 实为 `Optional<List<float[]>>` 纯数组，无需 Matrix 包装类；
> §4 时序落地为 **go 信号 speak 播完才宣判**的链式节拍（新增 `AvatarSkill.onSpeakCompleted`
> 完成事件缝，防止自己的宣判抢占自己的 go 信号音频；TTS 失败即时完成时由后续样本自愈）；
> §7 建议的 registry **单活跃收口**已采纳（后激活者胜出）；§12-1 呆住判负定稿（用户拍板）；
> `SkillHost.snapshotLatest()`（`ring.newest()`）落地替代 §6 预估的默认实现退化。
> 单测 +40 全量 450 全绿（app130/orchestrator202/corelib44/adapter74）。**真机待办**：
> §5 符号标定（`face_pose` 四向→`LookHereTuning` 两布尔；`look_throw` 四向→`screenToAssetDir`
> 映射）+ §9 验收清单 + 节奏体感确认。

> **真机首验归因（2026-10-06，用户实测）**：链路全通——激活/指向/战报/连局/第 2 局
> `user=下` 识别正确（FaceLandmarker 车道+基线+分类+宣判链全部工作）。第 1 局被误判
> Frozen 的根因是**窗口时序**：窗从手势播出起算 1200ms，但用户 cues 的是 go 信号**语音**
> （云 TTS 首声晚 ~0.7s、短语播完 ~1.9s），再加反应+转头 0.3~0.6s——语音还没响完窗就关了。
> 修=`windowMs` 1200→**2200ms**；节奏几乎无损（宣判本要等 go 信号播完 ~1.9s 才发，
> 快反应者仍走 2 帧早退）。§1 的"窗口头 250ms 基线自校准"不受影响。遗留观察项：
> 基线漂移（开局时头未回中会把回中动作误判成转向）与符号标定仍待 `face_pose`/`look_throw`
> 真机核验。

---

## 0. 结论速览

| 问题 | 答案 |
|---|---|
| 玩法能不能成立 | **能**。指向动作本地 RNG + VRMA（<150ms）；头部转向本地判定（≤110ms/帧）；胜负本地算；`speak()` 即时宣判——全程无 LLM 往返 |
| 200ms 检测要求 | 达标且富余：分析车道 80ms 节奏 + FaceLandmarker 单帧 CPU 10~30ms + 判定 <1ms；连续 2 帧确认 = 160ms 内可宣判方向 |
| 头部姿态怎么拿 | **推荐 MediaPipe FaceLandmarker**（`facialTransformationMatrixes` 直接给 yaw/pitch，依赖已在）；备选=复用仓库现役 ML Kit FaceDetector 的 eulerY（仅 yaw 可靠，pitch 缺，§3） |
| 指向动作 | 四个手势 VRMA 已由用户备好（`12_技能_看这边/gesture_{up,down,left,right}.vrma`，45~62KB，与猜拳三件套同规格）；`playGestureFile` 非广告通道现成 |
| LLM 的角色 | 赛中 **零回合**（保护 1~2s 节奏）；退场时一次 `sendTurn` 战报+被抓拍帧调侃（走猜拳裁判回合同款缝隙）；LLM 的 `<emo:/act:>` 协议反应免费送 |
| 侵入性 | orchestrator 新增 `LookHereSkill` + 纯函数判定窗 + 双语文案；`AvatarSkill/SkillRegistry` 各 +1 个默认空实现的 `onHeadPose`；app 新增 `FaceSpotter`（镜像 `GestureSpotter`）+ 车道门控 + ai_cmd；**adapter/corelib 零改动** |
| 包体 | +~3.7MB（face_landmarker.task）；依赖零新增（`tasks-vision 0.10.14` 已含 FaceLandmarker） |
| 最大风险 | **不是视觉，是符号**：屏幕方向 × 相机镜像 × VRMA 作者视角三个坐标系两两相反，必须真机标定 + 已知答案单测锁死（§5）；其次是不动/无脸的判定语义要定死（§1/§4） |
| 与猜拳共存 | 激活词零冲突；视觉车道互斥门控；⚠ 广播语义下"游戏中说猜拳"会双技能同活，建议 registry +3 行单活跃收口（§7 边界） |

---

## 1. 玩法规则定稿（先把判定语义钉死）

与日韩综艺「あっち向いてホイ／看这边」同构：**指向方希望同向命中，躲避方必须反向**。

```
虚拟人指 ──┬─ 上/下/左/右（屏幕视角，观众看到手摆向哪边）
           │
用户转头 ──┴─ 与指向同向 → 用户输（"被抓到"）
              与指向反向 → 用户赢（"躲开了"）
              呆住没动   → 用户输（推荐，保节奏；首局给一次重赛宽限，见 §12）
              画面里没脸 → 本局作废重指；连续 3 局 → 技能退场并提示检查前摄
```

- **方向以屏幕为准**（用户看到手指向画面的哪一侧），不是虚拟人自身的左右——虚拟人面向观众，
  它抬右手指向的是画面左侧。四个 VRMA 文件名（`left/right/up/down`）的作者语义与屏幕语义
  **不保证一致，必须真机标定**（§5），代码用一张「文件 → 屏幕方向」常量表收口。
- 判定窗口内以**基线相对位移**为准（用户头不会绝对正对相机）：窗口开头 ~250ms（用户来不及
  做出反应，人类反应时 ≥200ms）取中位数为基线，此后每帧 delta 超阈才算"转头"。
- 阈值（可调常量，初值）：dead zone 8°（yaw）/ 6°（pitch，颈部俯仰天然更小），确认线
  15° / 12°，连续 2 帧同向越线即提前宣判（早退，不傻等窗口）。
- 比分（虚拟人 vs 用户）只用于退场战报调侃的素材，连胜/连败短语进本地宣判词表。

---

## 2. 现状映射：这单生意要买的东西，仓库里已经有什么

| 环节 | 现状 | 结论 |
|---|---|---|
| 技能状态机骨架 | `AvatarSkill/SkillContext/SkillHost/SkillRegistry` 四件套 + `RpsSkill` 五态先例（激活词正则、退出词、消费语义、debugCommand 全有） | ✅ 照抄结构 |
| 本地随机选方向 | `SkillHost.randomInt(bound)`（注入 RNG，单测可复现） | ✅ 直接用 |
| 播指向动作 | `SkillHost.playGestureFile(path)` 非广告通道（`GestureDriver.playFile`，load+非循环+播完回 idle） | ✅ 直接用，只差目录排除泛化（§6） |
| 即时宣判 | `SkillHost.speak()`：无 LLM 直通 TTS、不进历史、内联 `<emo:/act:>` 标签会被解释——猜拳 P2 即时宣判同款 | ✅ 直接用 |
| 宣判词双语 | `RpsTexts`（orchestrator i18n）+ `skill.lang` 属性 + `LaunchedEffect(uiState.lang)` 注入 | ✅ 照抄 `LookHereTexts` |
| 头部姿态检测 | ⚠️ **唯一缺口**。仓库现役只有 ML Kit FaceDetector（`UserCameraTracker.kt:145-152`，FAST 模式，跑注视追踪） | ❌ 新增 FaceSpotter（§3） |
| 帧源与分析车道 | `UserCameraTracker`：CameraX 480×640 前摄、`"video-analysis"` 单线程、80ms 帧级门（`DETECT_INTERVAL_MS`）、引擎懒建/随车道启停 | ✅ 加一条 pose 车道即可 |
| "懵逼表情"抓拍 | `snapshotDataUrl()`：512×512 JPEG q80 深度 3 环（500ms/帧 ≈1.5s 窗）；⚠ 环帧最旧 500ms，判负瞬间要更鲜的帧 → `snapshotLatest()`（§6） | ✅ 小改 |
| 退场战报回合 | `SkillHost.sendTurn(text, images)` + 指令挂 user 前缀（猜拳裁判回合同款，不碰 system/协议/前缀缓存） | ✅ 直接用 |
| 调试命令 | `AiDebugExecutor.executeAiCommand` → `chat.skillDebug(id, arg)` → `SkillRegistry.debug` → 技能自己的 `debugCommand`；help 双份（`AiDebug.kt` + `docs/ai-debug-intents.md`） | ✅ 照抄三条命令 |
| 视频模式门控 | 技能激活不硬性要求视频模式（先例：猜拳无帧时降级提示）；pose 车道要求**前摄**（`lensFacing == FRONT`，可 `video_camera front|back` 切换） | ✅ 沿用无帧降级阶梯 |

---

## 3. 核心选型：头部姿态从哪来

### 方案 A（推荐）：MediaPipe FaceLandmarker

```
app/build.gradle.kts:62  implementation(libs.mediapipe.tasks.vision)   ← 已有,0.10.14 已含 FaceLandmarker
app/src/main/assets/face_landmarker.task                                  ← 新增,~3.7MB(float16)
```

- `FaceLandmarker.createFromOptions`：`RunningMode.IMAGE` + `outputFacialTransformationMatrixes(true)`
  → 每帧给 4×4 变换矩阵，取 3×3 旋转部分做欧拉分解得 yaw/pitch——**两个轴同一来源、同一噪声特性**，
  符号约定用已知答案向量单测锁死（同 `EdgeTtsDrm` 先例：固定输入 → 断言精确角度）。
- 新建 `FaceSpotter.kt` 完全镜像 `GestureSpotter.kt`（薄封装、懒建、IMAGE 模式同步 recognize）。
- 单帧 CPU 10~30ms（现代中端机；低端机可换 GPU delegate，先不动），80ms 节奏下单车道占空比 <40%。
- **只在技能激活 + 前摄时跑**（复刻 P2 的车道门控：`wantFacePose = poseDue && FRONT && lookHereActive()`，
  关 = 整条车道零开销）。

### 方案 B（零新增包体的备选）：复用现役 ML Kit FaceDetector

`UserCameraTracker` 本来就每 80ms 跑一次 ML Kit 人脸检测（注视追踪用）。`Face` 直接给
`headEulerAngleY`（yaw）；**没有 pitch（eulerX 不提供）**，只能拿鼻尖-双眼连线在包围盒内的
几何比例估，FAST 模式下欧拉角可靠性也一般。省 3.7MB，代价是俯仰轴（游戏的上下方向）变成
最弱环节。**定位：低端机实测方案 A 超时的退路，不作为首选。**

| | A：FaceLandmarker | B：ML Kit 复用 |
|---|---|---|
| yaw/pitch | 矩阵直出，±2~3° 噪声 | yaw 可用；pitch 几何估算 ±5~8° |
| 包体 | +3.7MB | 0 |
| 新增代码 | FaceSpotter（~80 行，镜像现成） | 0（读现有 Face 结果） |
| CPU | +10~30ms/帧（仅游戏期间） | 0（搭便车） |
| 可测性 | 矩阵→角度纯函数，已知答案单测 | 几何估算同样纯函数 |

**推荐 A**：这是一款"判定即玩法"的技能，判定权威性不能是最弱环节；3.7MB 对比现有
assets（47MB）与 gesture_recognizer.task（8MB）可忽略。

---

## 4. 回合时序与节奏预算（"1~2 秒一轮"的诚实答案）

单轮回合（无 LLM 参与）：

```
t0     playGestureFile(方向) + speak("看这边!") 同时发起     ← 视觉即 go 信号
t0+200ms    判定窗开启（先取 250ms 中位数当基线）
t0+200ms~   每帧(80ms) onHeadPose → LookHereJudge.onSample
            连续 2 帧同向越确认线 → 提前宣判（早退）
t0+1400ms   窗口截止：峰值方向判 → 无峰则"呆住"判
宣判   speak(结果短语)，败局同时 snapshotLatest() 存证
       ── speak 完成事件(TurnCompleted)──▶ 下一轮（连局自动续）
```

延迟预算：

| 段 | 耗时 | 说明 |
|---|---|---|
| 帧捕获→分析回调 | ≤80ms | 既有车道节奏 |
| FaceLandmarker 推理 | 10~30ms | CPU，仅游戏期间 |
| 连续 2 帧确认 | +80~160ms | 防单帧噪声 |
| **用户转头→方向已知** | **≤110~180ms** | ✅ 满足 200ms 要求 |
| 宣判 TTS 首声 | 800~1200ms | **云 TTS，真正的节奏地板**（与猜拳宣判同款可接受） |
| 宣判音频播完 | 0.5~0.8s | 决定下一轮何时能开（speak 会抢占在播回合，不能叠） |

- 用户感知的"判负→听到抓到你了"≈ 1.3~1.6s；**一轮 ≈ 2.5~3.5s**。要到 1~2s 只有三条路：
  ①胜局轻宣判（"哦?!"一个音节 + `<emo:>` 表情，省 0.5s）；②败局短句化（抓到你了! ≤4 字变体轮换）；
  ③端侧 TTS（超出本技能范围）。①②落地后 ~2.3~2.8s/轮，接近体感"两三秒一局"，够上头。
- **TTS 首声比手势晚 ~1s 到达是既有可接受项**（猜拳宣判同样如此）：用户以视觉（手抬起）为 go 信号，
  语音是气氛组。
- 基线自校准（窗口头部取中位数）使回合**不需要口播倒计时**——省掉一整段 speak，这是节奏能
  压到 3s 内的关键。若真机体感用户在窗口头 250ms 内就抢跑（老玩家），再补可选的"预备~"短句。

---

## 5. 镜像与符号：最大的正确性风险，必须标定后锁死

三个坐标系两两相反，任何一处想当然就是"方向全反"级别的事故：

| 坐标系 | 语义 | 事实 |
|---|---|---|
| 屏幕 | 用户看到的指向/转头方向（**判定语义的唯一基准**） | — |
| 相机帧 | FaceSpotter 的输入 | 前摄分析帧**不镜像**（`rotatedUpright` 只旋转，`UserCameraTracker.kt:367-373`；镜像只存在于 gaze 归一化一步）。用户把头转向**他自己的左**，在非镜像前摄帧里鼻尖移向**画面右**（面对面效应）；FaceLandmarker 矩阵符号随相机约定 |
| VRMA 作者 | 四个文件名 left/right/up/down 的作者本意 | 与屏幕语义不保证一致（虚拟人面向观众，抬"右臂"指向画面左） |

**标定协议（真机一次，把所有符号固化成常量 + 单测）：**

1. `ai_cmd face_pose`：实时回显 yaw/pitch 度数。测试者依次向自己左/右/上/下转头 → 记录四组
   符号 → 写死进 `HeadPoseMath` 的轴映射，已知答案单测锁死（固定矩阵 → 断言精确角度与方向枚举）。
2. `ai_cmd look_throw left|right|up|down`：逐个强制指向 → 人眼看屏幕记录每个文件实际指向的
   **屏幕方向** → 写死「文件 → 屏幕方向」常量表（同名直映是巧合不是约定）。
3. 之后任何"方向反了"都是常量表问题，改表 + 单测，不动逻辑。

---

## 6. 架构落点（对齐四层架构，依赖只能向下）

```
:app                    FaceSpotter(新,镜像GestureSpotter) + UserCameraTracker pose车道门控
                        + snapshotLatest() + ai_cmd 三条 + isSkillGestureAsset 泛化
:avatar-orchestrator    skill/LookHereSkill.kt(状态机) + skill/LookHereJudge.kt(纯函数判定窗)
                        + i18n/LookHereTexts.kt(双语,镜像RpsTexts)
                        AvatarSkill/SkillRegistry: +onHeadPose(默认空实现,零破坏)
                        SkillHost: +snapshotLatest()(默认方法委托snapshotImage,SDK兼容)
:avatar-ai-adapter      零改动
:corelib                零改动
```

新增缝隙草图：

```kotlin
// AvatarSkill 新增（默认空实现）：app 层 FaceSpotter 确认帧后经 SkillRegistry 广播
fun onHeadPose(yawDeg: Float, pitchDeg: Float, ctx: SkillContext) {}

// SkillHost 新增（带默认实现，既有集成方零破坏）：
// 判负瞬间的"懵逼表情"要 ≤80ms 新鲜度的最新帧，环内 best() 最旧 500ms 不够
fun snapshotLatest(): String? = snapshotImage()

// orchestrator 纯函数判定窗（全部 JVM 可测）：
class LookHereJudge(tuning) {
    fun begin(t0: Long)                                          // 指向时刻
    fun onSample(t: Long, yawDeg: Float, pitchDeg: Float): Verdict?  // null=未决
    // 窗口头 250ms 取中位数=基线 → delta 越 dead zone → 连续 2 帧同向越确认线=早退
    // 窗口 1200ms 截止按峰值判；峰值 <dead zone → None(呆住)；超窗无样本 → NoFace
}
```

app 接线清单：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `video/FaceSpotter.kt`（新） | FaceLandmarker 薄封装；模型 `assets/face_landmarker.task` |
| 2 | `UserCameraTracker.analyzeFrame` | `wantFacePose` 车道（80ms 同节奏、与手势/ML Kit 错峰）；**与手势车道互斥**（同一分析线程，两个 MediaPipe 任务不并跑）；结果 `mainHandler.post { skills.onHeadPose(...) }` |
| 3 | `MainActivity` | `videoTracker.onHeadPose = { y,p -> scope.launch { session?.skills?.onHeadPose(y,p) } }`（镜像 `onGestureConfirmed` 接线，MainActivity.kt:1262-1264）；注册 `lookHereSkill` |
| 4 | `DemoSkillHost.isSkillGestureAsset` | 现 `contains("11_技能_猜拳/")` 单目录（DemoSkillHost.kt:17-20）→ 泛化为目录集合 `{"11_技能_猜拳", "12_技能_看这边"}`，`any { path.contains("$it/") }`；手动面板走全量列表不受影响（MainActivity.kt:349） |
| 5 | `AiDebugExecutor` + `AiDebug` + `ai-debug-intents.md` | `look_status`（状态/比分/上次方向）、`look_throw up|down|left|right`（强制指向=标定与免麦 A/B 入口）、`face_pose`（yaw/pitch 实时回显）；help 双份同步。注意现 `skill_status` 硬编码 `"rps"`（AiDebugExecutor.kt:308-311），新命令各自带 id 路由即可，不动旧命令 |
| 6 | `SkillWiringTest` | 排除泛化的用例 + 四资产映射完备性（照抄猜拳目录排除测试 SkillWiringTest.kt:41） |

不动的东西：协议钉住机制、system 恒单条（A.1 第 34 条）、`SpeechVad/FreeSpeechController` 内部、
`LlmActionCatalog` 扫描器本体、adapter、corelib。VAD 悬停**不需要**像猜拳那样收短——本技能
回合内不依赖语音时机，激活/退出照走 utterance 缝。

---

## 7. 状态机

```
IDLE ──激活词「看这边/看你这边/方向反转/反应力测试/look (this|over) (here|there)」──▶ INTRO
   · 本轮照常发送不消费（return false，同猜拳），指令告诉 LLM：答应邀约+一句话讲规则
     （"我指哪你反着转头，同向就算你输"）+ 让用户盯紧
INTRO ──激活回合 TurnCompleted──▶ ROUND（进入自动连局环）
ROUND:
   指向 = rng 四选一 → playGestureFile + speak("看这边!")
   同时 LookHereJudge.begin；onHeadPose 逐帧喂 → 早退/窗口截止出 Verdict
   ── Verdict ──▶ 宣判 speak（败局 snapshotLatest() 存证 + 更新比分）
        ── speak 的 TurnCompleted ──▶ 下一轮 ROUND（自动连局）
边界：
· 任何态命中退出词「不玩了/退出/stop」──▶ EXIT
· onInterrupted/TurnFailed（用户 barge-in 打断宣判/指向）──▶ 弃当前轮回 ROUND 重指
· 游戏中非退出词的插话：消费并忽略（return true 不发送，保护节奏）——与猜拳
  "JUDGING 消费、其余放行"不同，本技能语音不参与玩法，全程可收口
· 连续 3 局 NoFace（没脸/后摄/出画）──▶ EXIT + speak 提示检查前摄
· 90s 无有效回合 ──▶ 静默 EXIT（惰性超时：在下一个事件缝检查 nowMs，无需定时器）
EXIT：speak 告辞 + sendTurn 战报调侃回合（§8）→ IDLE；恢复无 VAD 副作用（本技能没动过）
⚠ 双技能同活边界：广播语义下，游戏中说"猜拳"会同时唤醒 RpsSkill（registry 是
  any-消费广播，消费与否不影响其他技能收到）。建议 SkillRegistry +3 行：广播后检测到
  "有技能转活跃"即 deactivate 其余活跃技能（各自的 exit 副作用照常跑）——这是框架级
  单活跃收口，比让技能互相认识干净。
```

纯逻辑全部 JVM 可测：状态迁移表、判定窗（合成姿态流：基线漂移/抢跑/抖动/无样本）、
RNG 分布、指令与宣判词快照、退出词、惰性超时。不需要 Android 依赖。

---

## 8. LLM 的角色：赛中零回合，退场一次"战报调侃"

- **赛中坚决不发 LLM 回合**：一局 2~3s，LLM 回合（ttfb 1~2s + TTS 1.2s）必然糊在下一局的
  指向与宣判上；且自发合成消息与播放抢跑是猜拳 P2 已踩过的坑（其解法就是把气氛组回合挂到
  用户下一句话上）。本地 `speak()` 宣判 + `<emo:/act:>` 内联标签已足够"戏"。
- **败局瞬间存证**：宣判同时 `snapshotLatest()`（≤80~160ms 新鲜度，头正偏着）存进技能态，
  每场只保留**最后一张**（或最夸张的一张）。
- **退场一次 sendTurn**（消费退出词，走猜拳裁判回合同款缝隙——指令挂 user 前缀、图只挂本轮、
  不碰 system 条数与前缀缓存）：

  > 【技能:看这边·战报】用户刚结束「看这边」游戏，最终比分你 3:1。图里是他被判负瞬间的抓拍。
  > 请用一两句口语自然调侃他刚才的反应（比如头偏得飞快还是被抓到），不要复述规则，可加表情动作标签。

  LLM 的三通道协议（`<emo:>/<act:>/<cam:>`）在该回合免费生效——胜利庆祝/挑眉动作不用技能代码管。

---

## 9. 测试计划

单测（预计 +30 左右，全部 debug 变体 JVM）：

| 文件 | 覆盖 |
|---|---|
| `LookHereJudgeTest` | 合成姿态流：基线取中位数抗漂移 / 抢跑（窗口头 250ms 不判）/ 连续 2 帧确认早退 / 单帧噪声不触发 / 窗口截止峰值判 / 呆住=None / 超窗无样本=NoFace / yaw-pitch 阈值不对称 / 已知答案矩阵→角度（锁符号） |
| `LookHereSkillTest` | 激活/退出正则（中英合并一条）× 各状态 / INTRO→ROUND 链 / 宣判→TurnCompleted 连局 / 败局存证与战报回合构造（消费退出词+带图）/ 插话消费 / NoFace 阶梯 / 惰性超时 / debugCommand 三条 / forced throw |
| `SkillRegistryLookTest` | 与 RpsSkill 共存注册互不误触 / onHeadPose 广播 / 单活跃收口（若采纳 §7 建议） |
| `LookHereTextsI18nTest` | EN 逐成员无中文字符 / ZH 锚点 / `of(lang)` 分发（照抄 PromptTextsI18nTest） |
| `SkillWiringTest` 增补 | 12_ 目录排除出 LLM 目录 / 四资产映射完备 |

真机验收清单（标定 §5 之后）：

1. 标定：`face_pose` 四向转头符号正确；`look_throw` ×4 屏幕指向与常量表一致。
2. E2E：说"看这边"→ LLM 讲规则 → 连玩 5 局（含赢/输/呆住各一）→ 宣判正确、节奏可接受、
   零 FATAL → 说"不玩了"→ 战报调侃提到比分且引用了抓拍。
3. 边界：后摄模式激活 → NoFace 三局退场提示；barge-in 打断宣判后回合正常重开；
   游戏中说"猜拳"→ 单活跃收口生效；RPS 回归两局不受影响。
4. 性能：游戏中分析线程不丢帧、低端机帧率可接受；游戏结束 pose 车道归零开销。

---

## 10. 风险清单

| 风险 | 概率 | 对策 |
|---|---|---|
| 方向符号搞反（相机镜像/VRMA 作者语义/屏幕语义） | **高（想当然必翻车）** | §5 标定协议 + 已知答案单测 + 常量表收口，逻辑不写死任何方向 |
| 云 TTS 首声 ~1.2s 使一轮 >2.5s | 确定 | ①②压缩手段（§4）；向用户明示这是节奏地板，端侧 TTS 是唯一根治 |
| 用户"只用眼不用头"躲开 | 中 | 规则即"转头"：眼动头不动 = 呆住判负，激活指令里说清；属规则设计非缺陷 |
| 玩家预判节奏抢跑（窗口头 250ms 内已转头） | 低 | 基线窗检测到抢跑→本局作废重指并调侃"抢什么跑"；先记为开放问题 |
| 没脸/侧脸过大/后摄 | 低 | NoFace 阶梯（重试×2→三局退场）；前摄门控 |
| FaceLandmarker 与 ML Kit/手势同线程超载 | 低 | 车道互斥 + 仅游戏期间启用；低端机退方案 B |
| 游戏中说"猜拳"双技能同活 | 中 | registry 单活跃收口（§7，+3 行） |
| 四手势 VRMA 观感/时长不一致 | 低 | 真机逐个过；观感差是资产问题不动代码（猜拳先例） |
| 12_ 目录混进 LLM 动作目录 | 低 | §6-4 泛化排除 + wiring 测试 |
| 激活词误触发（"看这边"日常语义，如拍照喊的） | 中 | 代价=进游戏，退出词随时可跑（猜拳同款接受）；必要时收紧为"玩看这边" |
| 技能指令破坏前缀缓存/多 system 400 | 无 | 沿用 user 前缀指令，猜拳已验证的设计 |

---

## 11. 分期与工作量

一轨做完即闭环（本技能没有"无视觉的 P0"——视觉就是玩法本体），按可合并粒度切三步：

| 步 | 内容 | 验收 |
|---|---|---|
| A 判定件 | FaceSpotter + pose 车道 + onHeadPose 缝 + LookHereJudge 纯函数 + face_pose/look_throw 调试命令 + 符号标定单测 | 真机：face_pose 四向符号正确，判定窗对合成/真人流输出正确 |
| B 技能闭环 | LookHereSkill + LookHereTexts（双语）+ 12_ 目录排除 + registry 单活跃收口 + snapshotLatest + 单测 | 真机：激活→连局→三判型→退出全链路 |
| C 战报与打磨 | 退场战报回合 + 宣判词表调优 + 节奏压缩①② + 真机阈值调参 | 用户真手感收 |

规模与猜拳 P2 相当（orchestrator ~400 行 + app ~250 行 + 测试 ~600 行），估 1~1.5 个工作日
（不含真机标定来回）。

---

## 12. 开放问题（需要拍板，均可后改，都有默认值）

1. **呆住判负 vs 重赛**：推荐判负（保节奏，首局一次宽限）；常量可切。
2. **战报时机**：推荐只在退场时一次；备选=每 3 连败插播一句本地短调侃（不涉 LLM）。
3. **激活词口径**：默认收「看这边/看你这边/方向反转/反应力测试」+ 英文；若误触发烦人收紧为「玩看这边」。
4. **游戏中插话**：默认消费忽略（保护节奏）；备选=暂停游戏转普通对话，说完再问"继续?"。
5. **`face_landmarker.task` 打包进 APK** vs 首启下载：默认打包（+3.7MB，与 gesture_recognizer.task 同策略）。

---

## 附：与既有约束的对照

- **system 恒单条 / 协议钉住 / 前缀缓存**：战报回合沿用猜拳裁判回合已验证形态（指令挂 user 前缀、
  图只挂本轮），零新增风险面（A.1 第 34 条）。
- **`speak()` 不进历史、会抢占在播回合**：连局环靠 speak 的 TurnCompleted 链式推进，天然串行；
  barge-in 走 onInterrupted 重开当前轮。
- **VRMA 非循环播完自动回 idle/rest**：指向 clip 尾部建议 hold 关键帧让用户看清（资产侧自查，
  与猜拳三件套同要求）。
- **org.json 在 JVM 单测不可用**（A.1 第 33 条）：技能纯逻辑不碰 JSON。
- **多语言**：全部用户可读文案走 `LookHereTexts`（中英相邻单文件正本，同 `RpsTexts` 维护约定）；
  激活/退出词表中英合并在同一正则、不随界面语言切换（猜拳先例：识别语言跟随设备）。
- **onHeadPose 是通用缝**：落地后 §猜拳可行性文档 4.6 表里的「123 木头人」（回头检测）即解锁，
  框架本体依旧不膨胀。
