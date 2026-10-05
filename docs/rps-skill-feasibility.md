# 猜拳技能（实时体感互动）可行性分析 + 通用技能框架架构设计

> 生成时间：2026-10-05。对应代码状态：`d84aabd` + 工作区自由说话设置改动。
> 结论先行：**可行，且 80% 的基础设施已经就绪**。实时性的真正瓶颈只有一个——
> 「检测到用户说完三二一」的时刻比出拳晚约 0.9~2.3s（VAD 判句尾 + ASR 网络
> 往返），这不影响玩法成立（本地先出手、大模型事后裁判），只影响「同步感」；
> 三个分级方案可以把这个延迟从 ~1s 压到 ~0.3s（详见 §2）。

---

## 0. 结论速览

| 问题 | 答案 |
|---|---|
| 玩法能不能成立 | **能**。本地随机出拳 + 播 VRMA（<150ms），大模型只做「看图裁判」，慢 3~5s 属对话节奏，不破坏游戏 |
| 「三二一」实时检测 | P0 用 VAD 短句启发式（零新依赖，延迟 ~0.9s）；P1 上端侧流式 KWS（sherpa-onnx，~0.3s）；P2 MediaPipe 手势触发（~0.2s，且顺带解决判定准确性） |
| 大模型看图判定 | **链路现成**：视频模式已有 `send(text, images)` + SnapshotRingBuffer + vision 模型门控，判一局只多 ~1-2K vision token |
| 侵入性 | adapter/corelib **零改动**；orchestrator 新增 `skill/` 包 + `buildRequestMessages` 一处钩子（~6 行）；app 接线 ~几十行 |
| 通用技能预留 | 技能框架放 orchestrator（纯 JVM 可测），RPS 只是第一个实现；事件缝（语音文本 / VAD 时机 / 相机手势）+ 能力缝（出动作/说话/带图发回合）与具体玩法解耦 |
| MediaPipe 要不要接 | **要，但排 P2**。它的最大价值不是「判定更快」，而是 ①出拳时机同步 ②本地权威判定（`Closed_Fist`/`Open_Palm`/`Victory` 恰好是预置手势，零训练）③抓拍时机精准化 |
| 内容缺口 | 三个可区分的猜拳手势 VRMA（库里没有；已验证 VRMA 手指骨轨道可行，工作区里的 `AAAAAAAA.vrma` 已是 44 轨有效动画，补食指/中指轨道即可） |

---

## 1. 需求拆解 × 现状映射

用户设想的闭环：说「玩猜拳」→ 进技能态 → 检测到「三二一」→ 本地随机出拳（VRMA）→
说完话自动带图请求，注入技能提示词「用户在玩猜拳，你出的是 X，看图判断用户出什么再回复」。

| 环节 | 现状 | 结论 |
|---|---|---|
| 连续听人说话 | `FreeSpeechController` + `SpeechVad`（9.1）：20ms 帧、句尾切片、barge-in，事件粒度足够 | ✅ 直接复用 |
| 听懂「玩猜拳」激活技能 | ASR 文本（批量，句尾后 ~0.5-1.5s）已有；本地正则匹配「猜拳/石头剪刀布/划拳」即可激活，**无需 LLM 参与** | ✅ 缺的只是正则 |
| 检测「三二一」并即时出拳 | 现链路=VAD 判句尾（+800ms）→ ASR 网络（+0.5-1.5s）→ 才知道文本。**这是唯一不合拍点**，三个方案见 §2 | ⚠️ 分级解决 |
| 本地随机先出拳 | `GestureDriver.play(tag)`（load + 非循环播放，播完自动回 idle）；`controller.loadVrmaAnimation` 毫秒级 | ✅ 缺的只是三个手势资产（§3） |
| 带图发给大模型 | 任务 9 全套：`ChatMessage.images`（数组形态序列化）、`AvatarSession.send(text, images)`（图只挂本轮、不进历史）、`SnapshotRingBuffer`（500ms 一帧、深 3 ≈1.5s 窗、取最清晰）、`isVisionLlm` 门控 | ✅ 直接复用 |
| 注入技能提示词 | 每轮末尾 user 消息前缀槽（现在放【当前镜头视角】行，`buildRequestMessages` 596-603 行）——**正是技能指令该挂的位置**：只改本轮请求副本、不进 store、不破坏 [人设+协议一条 system][历史] 前缀的逐字节稳定（前缀缓存全程命中；协议块与 system 条数都别动，A.1 第 34 条） | ✅ 缝已在 |
| 判定后继续对话 | 正常 LLM 回合（TTS/口型/表情/动作标签协议全通） | ✅ |

**前提条件**：RPS 需要 vision 模型 + 相机权限 + 视频模式（抓拍只随视频模式启停）。
与视频模式同一套门控（`videoModeBlockReason` 同款），默认模型 doubao-seed 系开箱即视觉。

---

## 2. 实时性分析：出拳同步的三级方案

判定回合的延迟（出拳 → 裁判词说出口）由 LLM ttfb（实测 1-4.5s，关思考后 ~1-2s）+
TTS 首句（~1.2s）构成，属于「对话节奏」而非「游戏节奏」，用户可接受——**唯一要优化
的是「用户出拳 → 虚拟人出拳」的同步感**：

```
用户说「…三二一!」 ──VAD静默悬停──▶ 判定句尾 ──ASR网络──▶ 识别出「三二一」
      0ms                +800ms                   +1.3~2.3s        ← 现链路才能触发,太晚
```

| 方案 | 触发时刻 | 出拳延迟 | 新增依赖 | 说明 |
|---|---|---|---|---|
| **P0：VAD 短句启发式** | 句尾（悬停降为 400ms） | **~0.5-0.9s** | 无 | 技能 ARMED 态下：`onUtterance(wav)` 在 **ASR 之前**先看 wav 时长，<2.5s 短句即视为出拳 → 立即播 VRMA + 抓帧；ASR 文本稍后到，作为裁判回合的参考信息照常发送。误触发面=游戏语境下用户随口一句短语，可接受（错误代价只是多出一拳，裁判词自圆其说） |
| **P1：端侧流式 KWS** | 「一」话音刚落 | **~0.3-0.5s** | sherpa-onnx（Apache-2.0，中文流式 zipformer int8 ~40MB，手机 RTF≈0.1-0.3）或 Vosk | AudioRecord 喂流式解码，partial 结果百毫秒级刷新，匹配「三→二→一」序列或尾字「一」即触发。与 VAD 并行不冲突（同一条 AudioRecord 可双消费）。模型包体是主要代价，做成可选下载 |
| **P2：MediaPipe 手势触发** | 手势成形且稳定 3 帧 | **~0.2-0.3s** | MediaPipe tasks-vision ~8MB | 不等语音——用户手势一摆好就触发出拳，比任何语音方案都快且不受噪音影响；顺带拿到本地判定（§5） |

**P0 的拍帧时机**（关键细节）：出拳瞬间立刻从 `ring` 取帧**存进技能态**（而不是等 ASR
回来再取）——ASR 慢 2s 时 1.5s 深的环可能已被覆盖。抓拍间隔 500ms，出拳时刻的帧距
抓拍点 ≤500ms，用户举手出拳的动作必然在画面里（PiP 小窗可自查取景）。

**伴生优化**：技能激活时把 VAD 悬停从 800ms 降到 ~400ms（`SpeechVad.applyTuning`
本就支持采音中实时调），退出技能恢复——出拳延迟直接减半，零新依赖。

---

## 3. 内容缺口：三个猜拳手势 VRMA

- 库里 335 个 VRMA 无一能读作石头/剪刀/布（只有 fist pump/boxing 类整臂动作，手势形状不对）。
- **技术可行性已验证**：现有 VRMA 含手指骨轨道（如 `mixamorig:LeftHandThumb1-3`）；
  工作区里的 `AAAAAAAA.vrma`（用户已自制）是有效动画——1 个 animation、44 条骨骼轨道、
  VRM 标准骨名（`leftThumbDistal` 等），含 6 条拇指轨道。**自制链路已通，只差
  index/middle 手指轨道做出剪刀形**。
- 三个 clip 的技术要求：单手（右手）、0.8~1.5s、举手到肩上高度；石头=五指全屈、
  布=五指伸展、剪刀=食指+中指伸直其余屈。末帧保持手势 0.5s 再归位（引擎非循环播完
  自动 restoreRestPose，尾部加 hold 关键帧让用户看清）。单帧静态姿势有 `Arms Down`
  先例，双保险。
- 若手指动画观感不佳，退路=用「手臂姿态差」表达（石头=握拳收于胸前、布=摊掌前伸、
  剪刀=两指前刺），核心约束只有一条：**三者在屏幕上一眼可区分**。
- ⚠️ 放置位置：`buildLlmActionCatalog` 会把 assets/animations 全量扫描进 LLM 动作
  目录（协议块广告位）——猜拳三件套建议放独立子目录并在扫描时排除（避免 LLM 对话中
  随机刷 `<act:rock>`），技能通过「非广告通道」直接按文件播放（§4.3）。

---

## 4. 架构设计：通用技能框架（orchestrator `skill/` 包）

### 4.1 分层落点（对齐四层架构，依赖只能向下）

```
:app                    设备缝：VAD 时机/ASR 文本/相机手势 → 事件喂给 skills；
                        SkillHost 能力实现（快照、VAD 调参）；ai_cmd 调试命令
:avatar-orchestrator    skill/ 新包（纯 JVM，全单测）：
                          AvatarSkill / SkillContext / SkillHost / SkillRegistry / RpsSkill
                        AvatarSession：+skills 属性、buildRequestMessages 一处 consult、
                          playGestureFile 一个小 API
:avatar-ai-adapter      零改动
:corelib                零改动
```

设计原则：**技能 = 会话层逻辑**（决定提示词、拦截/发起回合、出动作），所以放
orchestrator 让 SDK 集成者直接复用；设备信号（麦克风事件、相机帧）由 app 层喂进来
——与现有模式一致（人脸观测喂 FaceDriver、抓拍喂 send）。

### 4.2 核心抽象（接口草图，非最终代码）

```kotlin
/** orchestrator: skill 提供的能力缝。app 层实现（SkillHostImpl），测试用 fake。 */
interface SkillHost {
    fun playGestureFile(path: String, loop: Boolean = false): Boolean  // 非广告动作通道
    suspend fun speak(text: String)                 // 无 LLM 直通 pipeline（现成）
    fun sendTurn(text: String, images: List<String>) // 正常回合（现成 send）
    fun snapshotImage(): String?                     // app: ring 取帧
    fun setVadHangover(ms: Long)                     // app: 采音中实时调参
    fun nowMs(): Long
    fun random(): Float                              // 注入 RNG，单测可复现
}

/** orchestrator: 一个技能。全部回调有默认实现，新技能只覆写需要的缝。 */
interface AvatarSkill {
    val id: String
    /** 激活态下每轮注入末尾 user 前缀的指令；null=不注入。 */
    fun turnDirective(ctx: SkillContext): String?
    /** 一句 ASR 文本到达。返回 true=技能消费了这句（本轮由技能主导/改写）。 */
    fun onUtterance(text: String, ctx: SkillContext): Boolean { return false }
    /** VAD 判句尾、ASR 之前（快路径；P0 出拳触发缝）。wav 仅时长/能量可用。 */
    fun onVadUtterance(wavMs: Long, ctx: SkillContext) {}
    fun onTurnCompleted(reply: String, ctx: SkillContext) {}
    fun onTurnFailed(ctx: SkillContext) {}
    /** 相机缝（P2 MediaPipe 落地后喂）：预置 0=无,1=石头,2=剪刀,3=布 */
    fun onUserGesture(gesture: Int, ctx: SkillContext) {}
    fun tick(ctx: SkillContext) {}                   // 超时等状态推进
}

class SkillRegistry(private val host: SkillHost) {
    fun register(skill: AvatarSkill)           // 同 id 覆盖
    fun deactivate(id: String = activeId)
    val activeDirective: String?               // session 每轮读这一个值
    fun onUtterance(text: String) / onVadUtterance(wavMs) / onUserGesture(g)
    fun onTurnCompleted(reply) / onTurnFailed() / deactivateAll()
}
```

### 4.3 三个钩子点（侵入性清单，全部 ≤10 行）

| # | 位置 | 改动 |
|---|---|---|
| 1 | `AvatarSession.buildRequestMessages`（596-603 行视角行处） | 前缀槽从「视角行」扩为「视角行 + `skills.activeDirective`」，同一条 user 消息、同一形态。技能指令与视角行同级：逐轮变化、只改请求副本、不进 store、不碰 system/协议 |
| 2 | `AvatarSession` | 加 `val skills = SkillRegistry(host)`（host 可空=无能力缝，指令仍可注入）；加 `playGestureFile(path)` 转发 GestureDriver 新增的 `playFile(entry)`（不经广告目录，复用 load+play 语义） |
| 3 | `MainActivity` 自由说话 `onUtterance`（1077 行处） | ASR 之前先 `session.skills.onVadUtterance(wav 时长)`（P0 触发缝）；ASR 成功后 `session.skills.onUtterance(text)`，其返回消费与否决定本轮是照常 `session.send(text, videoSnapshotImages())` 还是走技能改写（RPS 的裁判回合=「照常发送 + 技能态自动带图与指令」，几乎不需要改写） |

不动的东西：协议钉住机制、system 恒单条（A.1 第 34 条）、`SpeechVad`/`FreeSpeechController`
内部、`UserCameraTracker`、SpeechPipeline、adapter、corelib。

### 4.4 RpsSkill 状态机

```
IDLE ──onUtterance 命中「猜拳/石头剪刀布/划拳」──▶ INVITED（激活）
   · setVadHangover(400) · 本轮照常发送，指令告诉 LLM：答应邀约、说明规则、
     让用户喊三二一、把手举到镜头前
INVITED ──激活回合 TurnCompleted──▶ ARMED（等出拳）
ARMED ──onVadUtterance(wavMs<2500)──▶ THROWN
   · choice = rng 随机(石头/剪刀/布)
   · playGestureFile(三者之一)          ← 本地先出手
   · frame = snapshotImage() 存进技能态 ← 立刻抓帧,防环被覆盖
THROWN ──ASR 文本到达──▶ JUDGING（裁判回合）
   · sendTurn(asr文本或"(出拳)", [frame])，指令：
     【技能:猜拳·第N轮】用户刚出拳(他说的是"<asr>")。你出的是<choice>(已展示给用户,不要改口)。
     看图判断用户出的石头/剪刀/布并宣布这一轮结果;看不清就说明并提议重出一局。
   · 回合结束后自回 ARMED（连局）；ASR 空=照常裁判（图是主证据）
任何态 ──「不玩了/结束/退出」──▶ 恢复 VAD 悬停 800ms，回 IDLE
ARMED ──90s 无出拳──▶ 静默回 IDLE（可选补一句收尾话）
边界：裁判回合 TurnFailed → 回 ARMED 不计局；THROWN 中又来一句 → 排队为普通对话；
     barge-in 天然支持（虚拟人念裁判词时用户直接喊三二一 → 打断 + 出下一拳）
```

纯逻辑全部可测：状态迁移表、触发启发式（wav 时长阈值）、指令文本快照、rng 注入后
的出拳分布、退出关键词、超时。**不需要 Android 依赖。**

### 4.5 观测与调试（沿用项目惯例）

- `ai_cmd skill_status`（技能态/轮次/上次选择/帧龄）、`rps_throw rock|paper|scissors`
  （跳过语音强制出拳，真机 A/B）、`skill_exit`。
- 事件进 `AIDebug` 的 `chat:` 流（SkillActivated/Throw/Judging/Deactivated）。
- 裁判回合的 REQUEST/RESPONSE 本就在 `LlmPrompt` tag 里，`grep InfoStreamDectect`
  即得完整时序——出拳时刻 → ASR → REQUEST → RESPONSE 全链路可审计。

### 4.6 通用性证明（这个框架还能装什么）

| 未来技能 | 用到的缝 | 缺口 |
|---|---|---|
| 123 木头人 | onUtterance 激活 + onVadUtterance 判「回头」时机 + playGestureFile 转身 | 无 |
| 猜数字/猜谜 | 仅 onUtterance + turnDirective | 无（纯对话技能） |
| 你比我猜 | onUserGesture（MediaPipe 预置手势当输入） | P2 相机缝 |
| 击掌/握手 | onUserGesture + 时机判定 | P2 + 自定义手势训练 |

框架刻意保持小：事件缝三条（文本/时机/手势）+ 能力缝五个（动作/说话/回合/帧/VAD），
新技能=一个类 + 若干资产，框架本体不再膨胀。

---

## 5. MediaPipe 评估（问题 3：要不要接）

**结论：接，排 P2；且它的价值排序要纠正——「更快判定」只是第三位。**

技术事实：`com.google.mediapipe:tasks-vision` 的 **GestureRecognizer** 自带 6 个
预置手势：`Closed_Fist / Open_Palm / Victory / Thumb_Up / Thumb_Down / ILoveYou`
——**石头/布/剪刀恰好一一对应，零训练零标注**（精度不够再用 few-shot CSV 自训）。
bundled 模型 ~8MB 不依赖 GMS（与项目选 ML Kit bundled 同理由，国产机可跑）；单帧
CPU 10-20ms，10-15fps 门控跑在现有 `UserCameraTracker` 分析线程毫无压力（与
ML Kit 人脸检测错峰，复用帧级门模式）。

它买到的三件事（按价值排序）：

1. **出拳同步**：手势成形即触发（~0.2s），比语音方案都快、不怕环境噪音、不怕用户
   只比划不喊——P0/P1 的触发本质是「猜用户出拳了」，P2 是「看见用户出拳了」。
2. **本地权威判定**：用户手势本地已知 → 胜负本地算（<1ms）→ 虚拟人立即给表情/动作
   反应 + `speak()` 短句宣判（TTS ~1.2s 出声，比 LLM 裁判的 3-5s 快一个量级）；
   LLM 回合降级为「气氛组」：指令写明「本地判定你出 X 用户出 Y、你赢了，请自然
   反应（不要重新判定）」，异步进行互不阻塞。判定准确率从「看 VLM 心情」变成确定值。
3. **抓拍时机**：手势稳定帧即最佳帧，图的质量不再赌 500ms 抓拍相位。

代价与风险：+8MB 包体；取景要求手进前置相机画面（PiP 可自查；比「石头剪刀布」时
手通常在脸旁，大概率在 512² 中心裁剪内）；分析线程双检测器的 CPU（10fps 门控后
可控）；`onUserGesture` 缝已在框架里预留，接入只是 app 层多一个检测器。

**不接 MediaPipe 的替代**（如果坚持不加包体）：P1 的端侧 KWS 解决同步问题、
提示词工程 + 重赛机制兜判定准确率——玩法成立，只是判定仍靠 VLM、同步略逊。

---

## 6. 分期路线图

| 期 | 内容 | 依赖 | 验收 |
|---|---|---|---|
| **P0 玩法闭环** | 技能框架三件套 + RpsSkill + VAD 短句触发 + 三手势 VRMA 资产 + app 接线 + ai_cmd + 单测 | 零新依赖 | 真机：说玩猜拳→AI 答应→喊三二一并出拳→虚拟人 ~1s 内出手→裁判词正确对图判胜负→连局→退出；误触发与 ASR 空路径不炸 |
| **P1 触发升级（可选）** | sherpa-onnx 流式 KWS「三二一」检测 + 模型下载/打包策略 | +40MB 模型（可后置下载） | 出拳延迟 ~0.3-0.5s；噪音下误触发率可接受 |
| **P2 视觉升级** | MediaPipe GestureRecognizer 接入分析线程 + 本地权威判定 + onUserGesture 缝激活 | +8MB 模型 | 手势成形即触发；本地判定+即时反应；LLM 降级气氛组；判定准确率固定 |

P0 完成即可玩；P1/P2 相互独立、都是纯增益，P2 优先级建议高于 P1（包体小一半、
收益面大——同步+判定双收）。

---

## 7. 风险清单

| 风险 | 概率 | 对策 |
|---|---|---|
| VLM 看错手势（手小/糊/背光/只拍到半只手） | 中 | 提示词约束三选一+「看不清就重赛」；出拳瞬间抓帧；P2 本地判定根治 |
| P0 短句启发式误触发（ARMED 态随口说短句被当出拳） | 中 | 游戏语境可接受；错误代价=多玩一局；退出关键词随时可跑 |
| 手指骨骼动画观感差（模型手指骨权重/穿插） | 低 | 手臂姿态差方案兜底（§3）；单帧静态姿势有先例 |
| 快照帧过期（ASR 慢时环被覆盖） | 低 | 出拳瞬间取帧存技能态（§2） |
| 三个手势文件被扫进 LLM 动作目录造成协议污染 | 低 | 独立目录+扫描排除，走非广告播放通道 |
| 技能指令破坏前缀缓存/触发多 system 400 | 无（设计已规避） | 指令只挂 user 前缀；system 恒单条；协议钉住不动 |
| vision token 成本 | 低 | 一局一张 ~27KB JPEG ≈1-2K token，游戏回合密度可控 |
| HyperOS 权限墙（相机/麦克风） | 已知 | 与视频模式同一套降级+真手授权流程，无新增面 |

---

## 8. 与既有约束的对照（实现时必须尊重的既定事实）

- **system 永远只发一条**（A.1 第 34 条）——技能指令不新增 system，挂 user 前缀。
- **few-shot 示例与长清单保持提示词最末**（§7.10 近因效应）——技能指令是短状态行，
  放前缀（与视角行同位）不与该结论冲突；裁判回合的关键判定要求写在指令行内部靠后位置。
- **图片只挂本轮请求、不进历史**（send 现有语义）——每局一张图，历史不膨胀。
- **VRMA 非循环播完自动回 idle/rest**——手势 clip 尾部 hold 关键帧表达「亮手」。
- **历史 assistant 消息保留原始标签**——裁判回合 LLM 的 `<emo:>/<act:>` 反应照常
  走三路分派，胜利=celebrate、失败=懊恼等反应无需技能代码干预，协议免费送。
- **org.json 在 JVM 单测不可用**（A.1 第 33 条）——技能纯逻辑不碰 JSON 即可无忧。
