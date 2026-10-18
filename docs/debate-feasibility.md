# 4v4 虚拟人辩论赛：可行性分析与落地方案

> 日期：2026-10-18；源码基线：`bbe2956`。

> 后续 P0 修复和模拟器实测另见 [验证记录](debate-validation.md)。本轮按用户要求暂缓椅子，保留九人复用三个模型。
> 下文为初始实施设计与当时的代码核查，不代表全部功能已通过验收；最新实测范围和限制以验证记录为准。

## 1. 结论与范围

**可行。建议新增独立 `DebateActivity`，复用现有多人舞台与 `home.glb`，由确定性赛制引擎控制八位辩手和一位主持人。** AI 负责生成各自的发言，程序决定谁能说、何时停止、谁起立、镜头看谁。不能把赛制交给现有圆桌 LLM 导演自行遵守。

六项需求的落地判断：

| 需求 | 判断 | 实施要点 |
|---|---|---|
| 现有场景 | 可复用 | 先用 `scene/home.glb`；另存辩论专用站位/座位标定，不改原场景配置 |
| 横屏模拟器验证 | 可实施 | 已发现在线 `emulator-5554`；新页面固定横屏，以实际窗口尺寸取景 |
| 单人、队伍、主持人、全景实时切换 | 基础已具备，需扩展 | 现有角色特写和指定名单同框可复用；补统一镜头仲裁、横屏包围盒取景和遮挡检查 |
| 九位角色 | 架构支持扩展，九人效果待测 | 内置 3 个 VRM；外部指定目录另有 10 个，可选取不同模型 |
| 辩题、赛程、人员、模型、性格可配置 | 需新增 | JSON 规则包 + Intent 命令入口；配置先校验再整体应用 |
| 默认坐姿、发言起立、单手持麦并兼容动作 | 有素材和挂件基础，需新增动作分层 | 坐姿/起立动画已存在；持麦手臂持续约束、座位校准和道具所有权是关键工作 |

主要难点是**九人空间安排、坐站接续和持麦动作组合**，其次是严格计时与异步语音的协调。按用户要求，本阶段不把性能优化作为主线，也不以双人实测帧率推断九人帧率。

首版包含完整 4v4 赛程、主持串场、自由辩论、自动/手动镜头、坐站持麦、Intent 控制和日志。自动评委、胜负评分、观众、网络多人参赛不属于首版必需范围。主持人只宣布程序给出的结果，不自行裁定胜负。

## 2. 已核查的项目基础

| 现有代码/资产 | 核查结果与复用边界 |
|---|---|
| `corelib/.../stage/AvatarStage.kt`、`AvatarSpec.kt` | 单舞台可加载多个独立演员，支持 assets 或设备本地文件；公开 API 没有写死两人上限 |
| `app/.../multiavatar/MultiAvatarActivity.kt` 的 `Slots` | 虽默认 A/B，实际用 Map，支持动态扩槽；新页面应使用业务 ID，不沿用 A/B 绑定假设 |
| `app/.../multiavatar/StageChat.kt` | 每槽独立配置、声音、人设、scope 和 SDK 会话，适合作为装配参考；避免复制整个调试页面 |
| `avatar-orchestrator/.../roundtable/` | 可参考多人消息同步和注视接力；现有自动圆桌选人、走位编排不能直接控制正式辩论 |
| `AvatarEvent` | 有 `SentenceStarted/Ended/Failed`、`TurnCompleted`、`TurnFailed`、`PlaybackInterrupted`；`TurnCompleted` 的契约含 LLM、TTS、播放全部排空 |
| `AvatarStage.setCameraShot/setGroupShot` | 能以单演员或显式名单作为目标；`GROUP_SHOT` 现有名称是“双人同框”，不能据此宣称已验证四人/九人构图 |
| `stage/internal/GroupShotMath.kt` | 当前按头部距离估取景，span 上限为 4；并非视锥/全身包围盒精确取景，需要扩展 |
| `internal/VrmaAnimationEngine.kt` | 已有 base/overlay、crossfade 和骨骼 mask，主要用于走动配上半身手势；还没有通用“坐站 + 手势 + 独立持麦臂”三层 API |
| `stage/internal/AvatarRuntime.kt` | 帧序为动画/位移、呼吸/视线/模仿、蒙皮/物理；新增姿态约束须在最终骨矩阵传播前接入 |
| `accessory/` | 已能把 GLB 挂到手骨，支持每模型校准；动画绑定器会随剪辑切换隐藏道具，须处理与辩论持麦状态的冲突 |
| `assets/accessories/microphone.glb` | 已有麦克风，道具默认挂右手；“跟手”不等于“手臂会举麦到嘴边” |
| `assets/scene/home.meta.json` | 当前活动范围 X 为 -2.9～2.3，Z 为 -5.8～-2.1；九人不能直接套原来的双人落点 |

已有相关动作素材（存在不等于已逐模型验收）：

- `animations/06_坐姿/Sitting Backwards On Chair, Hands Relaxed.vrma`
- `animations/06_坐姿/Sitting To Standing.vrma`
- `animations/06_坐姿/Standing Up From Seated Position.vrma`
- `animations/06_坐姿/Standing To Sitting Transition.vrma`
- `animations/04_交流手势/Sitting And Talking To Another Person.vrma`
- `animations/04_交流手势/Standing And Arguing With Another Person.vrma`
- `animations/08_舞蹈与表演/MC Rapping On The Microphone.vrma`

其中“反坐椅子”不能直接当成正式辩手默认姿态，须检查朝向与腿部姿势；说唱动作只能参考持麦臂，不能直接作为整段正式发言动作。

环境核查：外部目录 `/home/neethu/下载/3d资源/vrm测试模型` 有 10 个 VRM，合计约 343 MB，单个约 5.9～98 MB。可先用 `CoolTiger.vrm`、`VIPE_Hero__1984.vrm` 等较小模型跑通，再覆盖重模型；这只是加载顺序建议，不降低九人要求。`adb devices -l` 显示模拟器在线。`wm size` 返回的是物理尺寸 1080×2400，不能用它否定用户所述的当前横屏状态，实施时读取实际窗口宽高。

## 3. 默认赛制：可配置的四人制攻辩赛

“标准辩论赛”存在多种四人制版本，本文明确采用一个**项目默认四人制攻辩模板**，不宣称是所有赛事的统一官方规则。更换真实赛事时，应导入该赛事明确的规则包。

角色固定为 `HOST`、`PRO1..PRO4`、`CON1..CON4`。队名、显示名、性格、模型、音色均可变，正反方立场在整场比赛中固定。

| 阶段 | 顺序/权限 | 默认净比赛时间 |
|---|---|---|
| 主持开场 | HOST 介绍辩题、立场、人员和规则 | 不计入辩手用时 |
| 开篇立论 | PRO1 → CON1 | 各 180 秒，共 6 分钟 |
| 攻辩 | PRO2 → CON2 → PRO3 → CON3 发起；每轮选择对方二辩或三辩应答 | 每轮 90 秒，共 6 分钟 |
| 攻辩小结 | PRO1 → CON1 | 各 90 秒，共 3 分钟 |
| 自由辩论 | 正方先发，双方交替；队内选人由规则决定 | 每队累计 240 秒，共 8 分钟 |
| 总结陈词 | CON4 → PRO4 | 各 240 秒，共 8 分钟 |
| 主持结束 | HOST 宣布比赛结束 | 不计入辩手用时 |

默认净比赛时间上限 **31 分钟**，加主持、动作切换和准备等待后，实际总时长会更长；提前结束发言时按实际用时计。

攻辩细则必须落在规则中：每轮开头确定应答者，轮内只能是该提问者和应答者轮流说；单次提问最多 10 秒、回答最多 20 秒；整轮共享 90 秒墙钟，轮内生成、换麦、动作等待均不延长轮时；回答不能反问；轮时耗尽即结束。未指定应答者时，默认从对方二/三辩按最少已应答次数选择，同次数按辩位排序。两位参与者可在该轮保持站立，轮末一起坐下，其余人保持坐姿。

自由辩论细则：队伍严格交替，队内先按循环辩位，后续可加合法的举手/点名申请；默认单次上限 30 秒。个人实际发言及本次发言内的停顿从本队余额扣除；换人准备不扣，但有硬超时。某队耗尽或主动放弃后，另一队可连续使用余额。无权发言者的请求只能排队，不启动音频。

LLM 可以建议下一位，但规则引擎只接受当前合法集合内的结果；默认不需要第二个 LLM 来选人。主持词由阶段事件填入确定模板，再交 TTS，避免主持人口头宣布错误阶段或剩余时间。

## 4. 推荐架构与职责

```text
adb Intent / 页面按钮
        ↓
DebateCommandRouter → 校验、去重、串行执行
        ↓
DebateEngine（唯一比赛事实源）
  ├─ RuleCompiler：规则 → 阶段/子轮/合法发言集合
  ├─ DebateClock：个人、攻辩轮、队伍累计计时
  ├─ SpeechCoordinator：唯一音频令牌、发言生成/播放/取消
  ├─ DebateTranscript：事件与已播内容、摘要、导出
  ├─ PostureDirector：坐站、座位、手臂持麦、动作权限
  └─ CameraDirector：自动取景、手动接管、跟踪
        ↓
AvatarStage + 9 × AvatarHandle + 角色语音/表情会话
```

新增 `app/.../debate/DebateActivity.kt`，UI 只呈现状态和提交命令。规则、计时、发言调度放入 `avatar-orchestrator/.../debate/` 的可测试逻辑；渲染骨骼约束和镜头数学放 corelib。禁止让 corelib 依赖 AI 或 Android Activity。

`DebateRig` 参考 `StageChatRig` 装配九个角色，但不调用 `RoundtableSession.startRoundtable()`，也不启用自动圆桌走位。业务 ID 显式映射到 `AvatarHandle`，不能依赖舞台自动递增的 A/B/C ID；换模后业务身份、立场、成绩和镜头目标保持稳定。

建议新增文件：

| 位置 | 内容 |
|---|---|
| `app/.../debate/` | Activity、ViewModel、Rig、Intent 解析、规则导入、页面 |
| `avatar-orchestrator/.../debate/` | Config/校验、RuleCompiler、Engine、Clock、SpeechCoordinator、Transcript、事件/状态 |
| `corelib/.../stage/` 与 internal | 可选姿态约束门面、骨骼所有权/持麦层、座位约束、分组取景扩展 |
| `app/src/main/assets/debate/` | 默认规则、辩论专用座位配置、动作映射与模型校准 |
| `app/src/main/AndroidManifest.xml` | 注册新 Activity；原入口保留 |
| `tools/` | 后续新增 adb 控制/导入脚本、短赛程验收脚本 |

以上为拟新增组件，不是现有可调用 API。

## 5. 状态机、计时与异步安全

比赛状态：`IDLE → LOADING → READY → RUNNING → FINISHED`；可进入 `PAUSED/ERROR/STOPPED`。单次发言：`PREPARING → STANDING_UP → READY_TO_SPEAK → SPEAKING → DRAINING → SITTING_DOWN → DONE`。主持人的姿态可独立配置，默认也坐着、串场时起立持麦。

核心约束：

1. 同一时刻最多一个可播放音频的角色，包括主持人；主持串场必须等辩手停止并释放令牌。
2. 当前阶段、子轮、队伍和合法辩位决定发言权；LLM 文本、动作标签和工具调用都不能推进赛程。
3. 起立完成并到达稳定站姿后才允许播放首句。不能先调 `send()` 再期望网络恰好足够慢。
4. 不以单个 `SentenceEnded` 换人；以完整回合排空、失败、到时或显式结束收尾。
5. 每条异步回调绑定 `matchId/runEpoch/turnId/speakerId`。现有 `AvatarEvent` 不含完整这些字段，应在适配层封装；迟到的 TTS/LLM/动画回调不能推进新轮。
6. 到时先取消生成、清播放队列和口型，再交还令牌并坐下；下一位不得与尾音重叠。

计时使用单调时钟 `elapsedRealtime`，不使用系统日期、不靠每秒 tick 累减。UI tick 只显示由 deadline 算出的余额。

- 立论/小结/陈词：首段音频实际开始时起表，之后生成停顿、句间空白均计时，直到本次结束；准备期另设超时。
- 攻辩：首个问题音频起播时启动整轮 90 秒 deadline，此后问答之间等待也计入；同时限制每个提问/回答的个人 deadline。
- 自由辩论：个人发言从实际起播到交还令牌持续扣队伍余额，个人 deadline 与队伍余额取较小值。
- 镜头切换不暂停比赛计时；普通阶段转换和主持串场不扣辩手时长。
- `pause` 默认立即取消当前发言、冻结余额；恢复时以已播摘要生成新的续讲，不宣称能在当前 AudioTrack 精确续播。攻辩暂停同时冻结轮 deadline。
- 页面退后台自动暂停；销毁释放资源。进程重启从最近持久化的阶段与已播摘要恢复到 PAUSED，不自动重复播放，不自动重开比赛。

建议默认准备超时：LLM 20 秒、首音频 10 秒、单个姿态过渡 3 秒、主持串场 20 秒；均可配置。超时进入可观测的失败策略：重试一次 → 使用明确标记的备用句/跳过该次发言；不会无限等待。若坐站标定失败，进入准备错误，不以“全员站着”冒充验收通过。

## 6. AI 发言、人格与公共辩论记录

首版建议采用**生成发言文本 → 校验 → 起立 → `speak()` 播放**的链路。复用现有 LLM adapter、TTS、口型和表情能力；由 `DebateSpeechService` 调 LLM，并以比赛记录自行构造上下文，避免依赖每个普通聊天会话自动收齐九人的历史。

这一选择让起立门控、非法标签拦截和限时策略更明确，代价是首句等待增加。长陈词可分段预生成；后续要边生成边说时，必须新增音频播放闸门，不能让 `send()` 绕过姿态和发言令牌。P0 先用固定稿验证舞台与规则，P1 再接真实 AI；最终交付必须有真实 AI 的整场验证。

每次生成请求包括：角色身份/辩位/立场、性格与语言风格、辩题和定义、当前阶段及提问者/回答者、个人和队伍剩余时间、公共已播摘要与最近原话、本队已提出和对手待回应的论点。裁剪历史时保留辩题、身份与规则，按阶段摘要较早内容。

公共记录独立保存 `turnId、phase、speaker、team、text、播放状态、起止时间、结束原因`。区分 `generated/queued/played/interrupted`；仅生成但未播出的内容不能算对方已经听到。当前事件只能可靠标记完整播完的句子；硬打断的半句标记为 partial，精确到字需另加音频文本对齐，不在首版虚报精度。

不同辩手使用独立人设和音色，但共享同一份公开比赛事实。比赛默认不把普通聊天长期记忆带入论证；比赛历史使用 `debate_<matchId>_<participantId>` 隔离，避免与 A/B 圆桌串历史。API Key 复用现有配置，不放规则 JSON 和日志。

时长控制依靠字数预算 + 实际 TTS 时长估计 + 硬 deadline 三层。不能通过“请说三分钟”保证三分钟。允许提前结束；需要用满时可在剩余时间充分的情况下续讲。时间不足时不启动长句，最终仍以硬截止为准。

比赛模式禁用模型的 `<cam:>`、自由走动和可修改比赛状态的工具；`<act:>` 只能映射到安全手势白名单。必须在执行入口过滤，不能仅在提示词中要求遵守。情绪和口型仍可使用，手势由姿态导演按当前骨骼权限执行。

## 7. 横屏布局、座位与坐站持麦

### 7.1 场地方案

继续加载 `home.glb`，正方四人左侧、反方四人右侧、主持人在中后方，座位整体面向观众并向中线偏转。每位角色配置 `seatAnchor、standingAnchor、yaw、seatHeight、hipOffset`，先在模拟器逐位校准。

现有 room bounds 较紧，不直接复制九个并排坐标。先以两侧浅弧/两排错位试排，测量肩宽、椅宽和起立净空；若被家具或墙体遮挡，使用该 Activity 独有的场景 placement/缩放和布局。新坐标需要与场景一起标定，不能只扩 bounds 却忽略墙和桌子。

坐姿必须有座面承托。先检查现有场景可用座位是否足够且方向合适；若不足，复用现有可用椅子资源，或补最简静态座椅道具作为布置，不重做场景 GLB。当前配饰 API 主要是骨骼挂载，不能把椅子挂手骨；若使用独立椅子，需要新增少量世界静态道具加载能力。是否需要这一步在空间试排时确认。

模型当前会做 unit-cube 归一化，配置中的座高和间距应以最终渲染世界尺寸标定，不直接假设每个原始 VRM 的一米都对应同样画面尺寸。

### 7.2 动作分层

“不影响其他动作”的可实现定义：**保留坐站、呼吸、转头、口型、表情和非持麦侧手势；持麦侧骨骼由麦克风姿态独占。** 同一只手不能同时稳定持麦和完整执行挥手/双手鼓掌，冲突动作须改用空闲手版本或拒绝。

建议层次与骨骼所有权：

| 层 | 职责/权限 |
|---|---|
| 基础姿态 | seated idle、sit-to-stand、standing idle、stand-to-sit；控制 hips、腿和基础躯干 |
| 交流手势 | 只写空闲侧手臂和允许的躯干；排除持麦臂、hips、腿；不能由全身争论动画随意覆盖 |
| 呼吸/视线 | 复用现有引擎；头颈用于看对手/主持人，幅度限制避免持麦穿脸 |
| 持麦约束 | 持麦侧肩/上臂/前臂/腕/手指的持续姿态；跟随最终胸口或嘴部目标，在矩阵传播前应用 |
| 座位/脚底约束 | 限制坐姿 hips 漂移、脚底滑动和起立根位移；不以每帧硬重置全部动画来实现 |
| 蒙皮/弹簧骨/配饰 | 使用最终骨骼矩阵更新，配饰跟随手骨 |

现有双槽 crossfade 不能直接等同三条独立持久轨。建议保留 VRMA base/overlay，新增可选 `HoldPropPoseLayer` 和座位约束；持麦层关闭时维持原动画路径。持麦臂优先采用归一化骨骼姿态 + 每模型偏移，跨体型不够准确时加二骨 IK，以嘴部附近为目标，限定肘方向、关节范围和麦头距嘴距离。不要用固定世界坐标把手钉住。

写骨层遵守项目既有“先剥旧偏移、再叠新偏移”纪律，防静止累积；持麦约束应看到本帧最终胸口/头部姿态。新增层的位置以实际帧泵及 transform commit 顺序为准，补必要的矩阵传播，不能只改 Kotlin 调用顺序而不检查世界矩阵是否已更新。辩论模式不启用会覆盖手臂的 mimic。

推荐姿态闭环：坐姿待机（麦克风在手侧放低）→ 起立 → 举麦就位 → 站姿发言/空闲手手势 → 放低麦 → 坐下。每人独立一支麦，避免首版引入传麦过程。持麦层在坐下时保持低位握持，不让麦克风凭空消失。

辩论模式对 microphone 使用显式道具所有权；不注册会自动挂摘它的动画绑定，或由新的所有权机制压住 binder 自动隐藏。不能只在发言开始重新 attach，因为旧剪辑的延迟 hide 仍可能随后把麦藏掉。

每个模型至少标定坐姿髋高、站起终点、持麦侧、握持指形、麦的位置/旋转和抬臂角度。必要骨骼缺失时配置校验报错或明确换模；不默默显示 T pose。先验证 2 个体型差异大的模型，再覆盖九人。

## 8. 实时镜头与智能切换

定义业务镜头 `SPEAKER(id)、TEAM(PRO/CON)、HOST、WIDE、DUEL(questioner,responder)`，由 CameraDirector 转换成舞台能力。队伍镜头显式传四人的句柄，主持人镜头只传 HOST；全景包含九人和坐站所需上下边界。

自动策略：

- 主持开场/串场优先 HOST；宣布阶段时可短暂 WIDE。
- 正式发言使用胸部以上的中近景，展示脸、持麦手；需要脸部特写时保留独立选项。
- 攻辩每次问答交接跟随实际起播者，可用 DUEL 过渡；镜头跟随问答而非固定跟提问者。
- 自由辩论跟随当前合法发言者；队伍反应镜头只在较长发言中少量插入。
- 起立/坐下时根据实时骨锚更新目标，避免镜头仍盯着原坐姿高度。

优先级：手动锁定 > 比赛事件 > 反应镜头。默认切换过渡约 350 ms、最短驻留约 3 秒；合法发言人切换和手动操作可打破驻留限制。反应镜头 1～2 秒后回发言者，不因每句结束而甩镜头。

提供 `camera` 命令显式锁定镜头，`camera_auto` 解除，或用 `hold_ms` 临时接管。解除后按当下实际状态重新计算镜头，不回放过期切镜队列。LLM 的镜头指令在执行前拦截，防止多个会话抢镜。

扩展取景数学：把目标组的头、肩、hips、脚及必要道具边界投影到候选相机坐标系，根据横纵 FOV 和实时 aspect 计算距离/安全边距；混合坐站时包含两种高度；不只平均头部坐标，也不继续依赖固定 maxSpan。候选机位限制在标定的无墙体遮挡区域，做简单视线遮挡/预置安全机位回退，首版不承诺任意角度自动寻路。

“实时”验收分开测量：命令接收至镜头开始响应，目标为本机 200 ms 内；镜头到位约 350～600 ms。作为实现目标记录实际结果，不能把云端 LLM/TTS 延迟计入或排除得含糊不清。

## 9. 规则配置设计

规则用 UTF-8 JSON，顶层 `schemaVersion` 必填。人物信息、模型、动作、镜头和赛制分开配置。时间统一整数毫秒，坐标单位在布局文件声明；所有业务 ID 唯一。

以下是拟定的最小完整配置示例。`four_person_attack_v1` 展开为第 3 节的默认赛程；其余未写字段由该版本的 schema 提供固定默认值，导出时应展开，避免模板日后变化影响复现。

```json
{
  "schemaVersion": 1,
  "matchId": "debate-demo-001",
  "topic": "人工智能的发展是否更有利于人类创造力",
  "sides": {"PRO": "更有利", "CON": "更不利"},
  "scene": {"asset": "scene/home.glb", "layout": "home_debate_v1"},
  "rules": {
    "template": "four_person_attack_v1",
    "openingMs": 180000,
    "crossRoundMs": 90000,
    "questionMs": 10000,
    "answerMs": 20000,
    "crossSummaryMs": 90000,
    "freeTeamMs": 240000,
    "freeTurnMs": 30000,
    "closingMs": 240000,
    "freeFirstTeam": "PRO"
  },
  "speech": {"mode": "ai", "generationTimeoutMs": 20000},
  "camera": {"mode": "auto", "transitionMs": 350, "minHoldMs": 3000},
  "posture": {"default": "seated", "standWhenSpeaking": true, "micHand": "right"},
  "participants": [
    {"id":"HOST","name":"主持人","role":"host","model":"asset:vrms/10.vrm","personality":"中立、清晰、严格按裁定串场"},
    {"id":"PRO1","name":"正方一辩","team":"PRO","position":1,"model":"asset:vrms/4705834820915235427.vrm","personality":"理性、善于定义概念"},
    {"id":"PRO2","name":"正方二辩","team":"PRO","position":2,"model":"asset:vrms/FreeTestCharacterAsuna_1024.vrm","personality":"敏锐、善于追问"},
    {"id":"PRO3","name":"正方三辩","team":"PRO","position":3,"model":"file:models/CoolTiger.vrm","personality":"幽默、重视例证"},
    {"id":"PRO4","name":"正方四辩","team":"PRO","position":4,"model":"file:models/VIPE_Hero__1984.vrm","personality":"沉稳、善于归纳"},
    {"id":"CON1","name":"反方一辩","team":"CON","position":1,"model":"file:models/8622788685404555712.vrm","personality":"严谨、重视边界条件"},
    {"id":"CON2","name":"反方二辩","team":"CON","position":2,"model":"file:models/HatsuneMikuNT.vrm","personality":"直接、善于拆解前提"},
    {"id":"CON3","name":"反方三辩","team":"CON","position":3,"model":"file:models/SK_Sun_CASUAL_skirt_with_bones_1024.vrm","personality":"务实、重视反例"},
    {"id":"CON4","name":"反方四辩","team":"CON","position":4,"model":"file:models/SK_MINA_CASUAL_02_with_bones_in_skirt_1024.vrm","personality":"克制、擅长价值比较"}
  ]
}
```

`asset:` 是 APK assets；`file:` 是约定相对 `filesDir/debate/` 的应用文件路径。以上外部模型只是候选，不代表已经通过骨骼、坐姿和持麦验收。每人还可选填 `llmProfile、voice、personaCard、seatProfile、animationProfile、micCalibration`；未指定时继承现有有效 AI/TTS 配置与布局中对应业务 ID 的座位。

后续支持显式 `phases[]` 描述 `speech/cross_exam/free_debate/host`，允许修改顺序与合法辩位。配置阶段编译成确定的赛程表，不让任意 JSON 执行脚本。

导入需校验：恰好一位 HOST、每队四人、辩位 1～4 齐全无重复、模型存在且加载就绪、时间合法、赛程引用有效、动作与必要骨骼可用、布局有九个座位。失败返回字段路径与原因，保留上一份有效配置。规则/人员/模型/性格在 READY 或 PAUSED 时整体更新；更新当前比赛关键字段须显式重置，不在当前音频播放中重建角色会话。镜头命令允许实时执行。

## 10. adb Intent 控制协议

**以下协议和命令是实现约定，当前仓库没有 `DebateActivity`，现在执行不会成功。**

新 Activity 建议 `exported=true`、`launchMode=singleTop`、横屏。`onCreate(intent)` 与 `onNewIntent(intent)` 进入同一个命令入口；后者更新 `setIntent(intent)`。控制命令使用显式组件和 `CLEAR_TOP | SINGLE_TOP`，让已存在的页面接收新 Intent。该 flags 组合可能关闭它上方的 Activity，因此调试控制应直接在辩论页进行。

Android 的 singleTop 仅在目标位于栈顶时复用；相关依据见 [Android 任务与返回栈](https://developer.android.com/guide/components/activities/tasks-and-back-stack)。配置和模型用文件传递，Intent 仅传短命令/路径；Binder 缓冲区约 1 MB，且由在途事务共享，不能按“单条可塞满 1 MB”设计，见 [TransactionTooLargeException](https://developer.android.com/reference/android/os/TransactionTooLargeException)。

Intent action：`com.neethu.aiavatar_sdk.DEBATE_CONTROL`。公共 extras：`debate_cmd`、`request_id`，可选 `match_id`、`expected_revision`；具体命令附加参数：

| 命令 | 参数与语义 |
|---|---|
| `load` | `config_file`：应用辩论目录内相对路径；加载成功进入 READY |
| `validate` | 只校验指定配置并输出结果，不更改正在运行的比赛 |
| `start` | 从 READY 开始；重复 request_id 不重复启动 |
| `pause/resume/stop/reset` | 冻结、恢复、终止、回到开赛前；reset 取消所有在途任务 |
| `end_turn` | 主动结束当前合法发言，按正常收尾交接 |
| `next_phase` | 调试裁定：中止当前阶段并跳到下一阶段，日志注明人工跳过 |
| `grant` | `speaker_id`；只在当前阶段合法集合内授权；不允许跨队抢话或跳过辩位 |
| `camera` | `shot=speaker/team/host/wide/duel`，配 `target_id/team/other_id`，可选 `hold_ms`；不改变发言权 |
| `camera_auto` | 解除人工镜头接管 |
| `state` | 导出当前状态/余额/发言人/镜头/资源加载状态 |
| `export` | 导出规则快照、事件、字幕、阶段时间统计 |

所有命令主线程串行入队；load/reset 等异步变更期间拒绝冲突命令。对 `request_id` 去重，关键变更比较 revision；返回 `accepted/completed/rejected` 的结构化日志和结果文件。`am start` 成功只表示 Intent 已送达，不表示模型加载或命令执行完成。

建议结果写到 `filesDir/debate/control/<request_id>.json`，状态快照写 `state.json`；未给 request_id 时生成并在 `DebateControl` 日志输出。输入只允许辩论根目录内文件，限制长度，拒绝路径穿越；日志不输出 Key。

### 10.1 导入规则和模型

优先用 debuggable APK 的 `run-as` 写应用私有目录，避开外部存储权限差异。Linux 主机示例，配置文件 `debate.json` 由实施阶段提供：

```bash
adb -s emulator-5554 shell run-as com.neethu.aiavatar_sdk mkdir -p files/debate/models files/debate/control

adb -s emulator-5554 shell run-as com.neethu.aiavatar_sdk sh -c '"cat > files/debate/debate.json"' < debate.json

adb -s emulator-5554 shell run-as com.neethu.aiavatar_sdk sh -c '"cat > files/debate/models/CoolTiger.vrm"' < /home/neethu/下载/3d资源/vrm测试模型/CoolTiger.vrm
```

同理导入配置引用的其余模型。主机路径不能直接填成 Android 的 `modelFilePath`；真正传给渲染器的是解析后的设备私有路径。后续脚本应封装 stdin 重定向和两层 shell 引号，并校验导入文件大小/哈希。release APK 若不允许 run-as，另提供 SAF/受控文件导入入口，不能假定 adb push 能直接写应用私有目录。

### 10.2 启动、控制和实时切镜

```bash
# 导入配置到新页面；组件路径是拟新增路径
adb -s emulator-5554 shell am start -W --activity-clear-top --activity-single-top -n com.neethu.aiavatar_sdk/.debate.DebateActivity -a com.neethu.aiavatar_sdk.DEBATE_CONTROL --es debate_cmd load --es config_file debate.json --es request_id load-001

adb -s emulator-5554 shell am start --activity-clear-top --activity-single-top -n com.neethu.aiavatar_sdk/.debate.DebateActivity -a com.neethu.aiavatar_sdk.DEBATE_CONTROL --es debate_cmd start --es request_id start-001

# 特写正方二辩；未指定 hold_ms 则保持手动锁定
adb -s emulator-5554 shell am start --activity-clear-top --activity-single-top -n com.neethu.aiavatar_sdk/.debate.DebateActivity --es debate_cmd camera --es shot speaker --es target_id PRO2 --es request_id cam-001

# 正方四人同框 5 秒，之后回到当前比赛状态对应的自动镜头
adb -s emulator-5554 shell am start --activity-clear-top --activity-single-top -n com.neethu.aiavatar_sdk/.debate.DebateActivity --es debate_cmd camera --es shot team --es team PRO --el hold_ms 5000 --es request_id cam-002

# 主持人 / 九人全景 / 自动镜头
adb -s emulator-5554 shell am start --activity-clear-top --activity-single-top -n com.neethu.aiavatar_sdk/.debate.DebateActivity --es debate_cmd camera --es shot host --es request_id cam-003
adb -s emulator-5554 shell am start --activity-clear-top --activity-single-top -n com.neethu.aiavatar_sdk/.debate.DebateActivity --es debate_cmd camera --es shot wide --es request_id cam-004
adb -s emulator-5554 shell am start --activity-clear-top --activity-single-top -n com.neethu.aiavatar_sdk/.debate.DebateActivity --es debate_cmd camera_auto --es request_id cam-005

adb -s emulator-5554 shell am start --activity-clear-top --activity-single-top -n com.neethu.aiavatar_sdk/.debate.DebateActivity --es debate_cmd pause --es request_id pause-001
adb -s emulator-5554 shell run-as com.neethu.aiavatar_sdk cat files/debate/state.json
adb -s emulator-5554 logcat -d -s DebateControl DebateEngine DebateCamera DebatePosture
```

命令路由以组件和 `debate_cmd` 为主，因此后续例子可省 action；实现应明确接受这一用法。正文中的长中文辩题和性格通过 JSON 文件更新，避免依赖复杂的 adb 远程 shell 引号转义。

## 11. 页面建议

横屏以舞台为主体，顶部显示辩题/阶段/正反方累计余额，底部显示当前角色与字幕；左右辩位牌显示姓名、辩位、立场、是否在发言。提供折叠控制栏：开赛、暂停、结束本次发言、双方镜头、主持镜头、全景、恢复自动。

准备页显示九人加载状态、配置校验结果、坐站/麦克风标定状态。比赛页把“生成中、起立中、发言中、收尾中”分开显示；倒计时不因为角色仍在准备就假装已开始。调试日志和配置细节放折叠面板，不占舞台主体。

## 12. 实施顺序与工作量

按单名熟悉仓库的开发者估算，约 **12～20 人日**，外部模型需要修骨架/重新制作动作时另计。这是排期估算，不是完成承诺。

| 阶段 | 估算 | 可交付结果与退出条件 |
|---|---|---|
| P0a 场地与动作试验 | 2～3 日 | 新横屏页面；九个角色/座位试排；两种体型坐站持麦闭环；四人和九人构图可用；不通过则先解决素材/布局 |
| P0b 规则与控制骨架 | 3～4 日 | 纯规则引擎、Intent 导入和控制、短固定稿跑完整赛程、单发言令牌和真实计时 |
| P1 AI 比赛 | 2～3 日 | 九人身份/音色、公共记录、按阶段生成、攻辩问答、自由辩论、失败和取消处理 |
| P2 动作完善 | 3～6 日 | 九个模型逐一校准；持续持麦层、空闲手手势、坐站/root 对齐、道具生命周期 |
| P3 镜头与整体验收 | 2～4 日 | 自动/手动镜头、遮挡回退、完整时长比赛、配置/生命周期回归、操作文档 |

P0 的固定稿只用于隔离验证规则和动作，不替代最终 AI 验收。先处理最不确定的坐站和场地，不等全部规则写完才发现九人坐不下。

## 13. 验证与验收标准

### 13.1 必要的自动化验证

规则与计时用虚拟单调时钟验证：阶段顺序、攻辩子轮、非法 grant、双方队伍余额、到时截止、暂停恢复、不重入、旧回调忽略。命令验证覆盖坏 JSON、缺角色、重复 ID、非法文件路径、重复 request_id、加载中 start 和 revision 冲突。

动作数学验证骨骼 mask 排他性、持麦层反复进入退出无累积、坐站终点和座位高度计算；镜头数学验证横屏不同 aspect、四人/九人、混合坐站、span 超过旧上限仍在视锥内。适配层验证 `TurnCompleted`/失败/打断的归一化，以及取消后音频不得再次起播。

不为纯页面文案增加机械测试；保留原单人、多人舞台和配饰相关现有测试回归。

### 13.2 模拟器实测矩阵

| 验收项 | 通过标准 |
|---|---|
| 九人准备 | 八位辩手 + 一位主持人同时可见，身份牌正确；默认有座面承托，无 T pose、重叠或漂浮 |
| 完整赛制 | 默认全部阶段按表运行；攻辩仅合法双方问答，自由辩论按队交替/扣时；主持串场无越权选人 |
| 坐站持麦 | 九个模型分别完整坐→站→说→坐；麦跟手、不因手势或剪辑切换消失；口型、表情、视线、呼吸和空闲手动作可同时工作 |
| 镜头 | 单人、四人队伍、主持、九人全景及问答切换逐项截图；人物关键区域无遮挡/不裁切，手动接管可恢复自动 |
| 硬时限 | 缩短时长并故意让稿件过长，验证到时取消、静音、令牌交接；记录实际截止误差，目标不超过 200 ms，未达标明确原因 |
| 暂停与异常 | 生成/起立/播放/坐下各阶段暂停、stop、重载；模拟断网、TTS 失败、坏模型；无双重播放/死锁/残留持麦层 |
| Intent 连续控制 | 重复和快速切镜不重建舞台；旧命令不在重载后误推进阶段；每条 request_id 有最终结果 |
| 生命周期 | 横屏窗口正确，退后台自动暂停，返回不重复开场；重进页面/进程重启按文档恢复 |
| 长时与回归 | 先跑短模板，再跑一次真实默认完整比赛；检查 crash/ANR、内存和音频资源释放，原单人/多人入口可用 |

性能仅记录模拟器九人帧率、内存和启动加载时间，不以低端真机为本期门槛。模型加载仍应限制并发上传，防止九个大资源同时 create/bind 卡住主线程；这是稳定性工作，不是提前做 LOD。

## 14. 风险与决策

| 风险 | 处理与决策点 |
|---|---|
| 原场景空间/椅子不够 | P0a 先试排；复用场景 + 独立布局，必要时补座椅道具。未经视觉核查不承诺现有家具足够 |
| 坐站素材根位移或跨体型穿模 | 标定 hips/脚底和过渡；必要时制作少量中性 VRMA，不把“素材文件存在”当作完成 |
| 持麦被全身动作覆盖 | 新增持续姿态层 + 骨骼所有权；冲突动作改写或拒绝 |
| 麦克风突然隐藏 | 比赛持麦生命周期与动画 binder 解耦，取消遗留延迟 hide |
| LLM/TTS 延迟破坏节奏 | 生成门控、超时、有限预生成、确定主持词；攻辩轮开始后不因网络延长比赛 |
| AI 内容与时间不匹配 | 字数预算、估时、续讲/提前结束策略、最终硬截断；不承诺固定字数等于固定时长 |
| 角色记住未播内容或串立场 | 公共已播事实源 + per-match 上下文；各人身份/立场每次生成都钉住 |
| 旧回调/重复 Intent 导致跳轮 | epoch/turnId、串行命令、revision、去重和完整结果日志 |
| 横屏全景被旧算法裁切 | 取代头距常数估算，按实际视锥和目标边界拟合 |

建议优先实施 P0a：它能最早确认本需求中最大的视觉不确定性。通过后，以确定性赛制引擎为主线完成控制、AI 和镜头；最终验收必须同时满足“赛制正确”和“九人坐站持麦画面可信”。
