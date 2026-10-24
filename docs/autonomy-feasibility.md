# 自主生命体:决策状态机 + 不定时问候 + 思考气泡(落地设计)

日期:2026-10-24 · 代码基线:`6808513`(SDK 0.1.5) · 地位:**新 P0,前置于酒馆资产四件套**(优先级重排见 docs/tavern-companion-feasibility.md §5)
一句话:让虚拟人在没人理的时候也"活着"——自己决定在房间里走动、坐下、躺下、看窗外,偶尔主动开口问候,头顶会飘出云朵思考气泡且随人移动。

---

## 0. TL;DR

1. **所有执行积木都已在**,缺的只是"决策大脑":走位/坐躺/视线链(`InteractionCoordinator`)、waypoint 路由(`LocomotionDriver`)、场景动作词汇表(dream_bedroom 16 个交互点)、免 LLM 开口原语(`AvatarSession.speak()`)、长期记忆检索、坐躺态查询——全部现成。本设计新增的是三样:**决策状态机(默认零 LLM)、问候调度器、思考气泡(渲染+投影跟随)**。
2. 架构一句话:orchestrator 新增 `autonomy/` 包做大脑,身体动作经 app 注入的 `AutonomyActions` 接口执行(延续 interact_with"orchestrator 不感知场景表"的既有边界);思考气泡是 app 层 Compose 覆盖层,靠 corelib 新增的两个小 API(头顶世界坐标 + 世界→屏幕投影)逐帧跟随。
3. 工作量 ≈ **12~14 人日**(状态机 4~5、问候 2~3、气泡 4、联调验收 2),全部可 JVM 单测的部分(转移表/调度参数/文本模板)单独立出来。
4. 默认策略保守:**决策不调 LLM**(确定性作息表+加权随机,零成本零延迟),LLM 只可选地低频增强;问候有冷却+安静时段硬门(教训来自 docs/agent-feasibility.md §1.4:airi 的主动性验证过"概率+冷却+沉默是工具"的设计);自主行为频率有上限,防"抽搐感"。

---

## 1. 为什么这是最高优先级

- 酒馆兼容解决的是"用户能把资产**搬进来**",自主生命体解决的是"虚拟人**活过来**"——后者才是 3D 化身对 2D 文字流(OMate/ST)的差异化本体,也是人机恋用户"关系感"的第一来源(她/他不是只有我说话时才存在)。
- 竞品横评里没人做到:ST/OMate 是文字;星野/猫箱/Kindroid 的形象是被动立绘/视频,不会在场景里自己走去书桌坐下。**"会自己生活的 3D 人"目前是空白。**
- 技术时机刚好:dream_bedroom(P0 已验收)提供了完整的生活动作词汇表(床/书桌/阅读椅/窗/电视/地毯),自由走动 P0~P2 提供了导航,记忆 M1/M2 提供了问候的内容素材。万事俱备,只欠大脑。

---

## 2. 现有可复用资产(全部已验证)

| 积木 | 位置 | 说明 |
| --- | --- | --- |
| 就位执行链 | `app/.../InteractionCoordinator.kt:88` `runInteraction` | 走到接近点(waypoint)→转向→坐/躺接触定姿(hips 吸附)→视线 POINT 8s→回 CAMERA;坐/躺中先起身(1.2s)。**自主行为直接调它,不用重写** |
| 站定判定 | `InteractionCoordinator.kt:171` | 轮询 walkState IDLE+无排队腿两拍(120ms×2)——WalkArrived 事件多段腿序不可靠,这是已踩平的路 |
| 走动驱动 | `orchestrator/locomotion/LocomotionDriver.kt:73` | `walkTo(anchor名)` BFS 图路由多段腿;`stop` 让路已有 |
| 动作词汇表 | `assets/scene/dream_bedroom.interactions.json` | 16 交互点:`bed_sit/bed_lie`(睡觉)、`desk_seat`(书桌,带 look)、`reading_seat`、`window_view/rug_watch_tv/tv_approach/game_approach`(站姿+视线)、approach 中转点等;字段含 kind/x/y/z/yawDeg/approach/sitInClip/sitLoopClip/standUpClip/hipsLift/look |
| 开口原语 | `orchestrator/session/AvatarSession.kt:870` `speak()` | 免 LLM 直说:同一条 标签→断句→TTS→有序回放 链,**可带 `<emo:>/<act:>/<cam:>` 标签**(问候能挥手微笑),不写进对话历史,phase 走 IDLE→SPEAKING→IDLE |
| 会话相位 | `AvatarSession.kt:249` `phase: StateFlow<ConversationPhase>` | THINKING/SPEAKING 时 FSM 必须冻结——现成的仲裁输入 |
| 身体位置 | `corelib/AvatarController.kt:858` `getWalkState()` | `WalkSnapshot(x, z, headingDeg, phase, targetX/Z, speed)`(corelib/Locomotion.kt:50)——气泡跟随的 XZ 源 |
| 坐躺态 | `controller.getContactPoseState()` | kind/phase/aligned——FSM 冷启动/切场景后恢复"现在在哪"的依据 |
| 待机动画 | `corelib/internal/VrmaAnimationEngine.kt:57` | 一次性动作播完 0.25s 淡回 idle;坐姿有 sitLoopClip 循环——无需新动画资产 |
| 视线 | `FaceDriver` `GazeMode.POINT/CAMERA` | 看窗外/看电视的"神"已有 |
| 记忆 | `orchestrator/memory/MemoryRetriever.kt:45` | `lineFor(query, texts)` 一行记忆摘要——问候/思考文本的素材注入 |
| 主动性研究结论 | `docs/agent-feasibility.md:90`(§1.3 三路径)、`:133-156`(§1.4 四教训) | airi 验证过:沉默是显式出口、概率+冷却调制、主动发言要有回执、默认保守。**本设计直接继承这四条** |
| 事件总线 | `AvatarEvent`(`session/AvatarEvent.kt`) | WalkStarted/Arrived/Interrupted、ToolCall*、TurnCompleted——FSM 的感知输入 |

确认无关的旧物:`ai_loop` 只是 `play_animation` 的循环参数(AiDebug.kt:46),与自主决策无关,不复用。

---

## 3. 总体架构

```
orchestrator 新包 autonomy/                      app 层(先 :app demo,:companion 后搬)
┌──────────────────────────────┐                ┌─────────────────────────────┐
│ AutonomyDirector(大脑)        │                │ InteractionCoordinator+      │
│  FSM + 作息先验 + 加权随机     │──AutonomyActions──▶│ LocomotionDriver 适配成      │
│  冲突仲裁(听 phase/事件)      │   (接口,app 注入) │ executor(执行互斥,串行)      │
│ GreetingScheduler(问候调度)  │                │                             │
│ ThoughtComposer(思考文本)    │                │ ThoughtBubbleOverlay        │
└──────┬───────────────────────┘                │  (Compose 覆盖层,云朵+跟随)  │
       │ speak()/记忆/可选LLM                     └──────────┬──────────────────┘
       ▼                                                   │ 每帧取投影
  现有 AvatarSession / MemoryRetriever                      ▼
                                                   corelib 新增 2 个公开 API:
                                                   getHeadWorldPosition() +
                                                   projectToScreen(x,y,z)
```

两条边界铁律(延续既有架构):
1. **orchestrator 不感知场景表**:FSM 的动作词汇表(锚点 id+kind)由 app 层投影注入,与 `interact_with` 工具同款模式(AgentToolCatalog.kt:674 先例);换场景=换注入,FSM 零改动。
2. **执行互斥**:所有身体动作(自主、LLM 工具、技能)过同一把锁(app 侧 executor 串行),从根上消灭"FSM 起身与 move_to 同时起步"竞态——InteractionCoordinator 已有 `isBusy` 拒绝语义,补排队即可。

---

## 4. 决策状态机(AutonomyDirector)

### 4.1 状态集(数据驱动,从交互点表派生,不硬编码场景)

| 状态 | 来源 | 停留时长 |
| --- | --- | --- |
| IDLE_STAND | 兜底 | 20~60s |
| WANDER | 随机 waypoint/approach 锚点(走过去即算) | 单程 |
| LOOKING | kind=stand 且带 look 的点(window_view/rug_watch_tv…) | 30~90s |
| SITTING | kind=sit(bed_sit/desk_seat/reading_seat) | 3~10min |
| LYING | kind=lie(bed_lie) | 8~20min(作息表调制) |
| (被动)TALKING | phase∈{THINKING,SPEAKING} | 会话期间 |

每个状态附带表现:IDLE_STAND=GazeMode.CAMERA(看着你);LOOKING=GazeMode.POINT 到锚点 look 坐标(超时回 CAMERA 的现有 8s 逻辑改为状态期间持续);SITTING/LYING 坐躺循环剪辑由定姿层管。躺姿太久(>20min 白天)自动转入"睡醒起身"。

### 4.2 转移决策(默认零 LLM)

- **作息先验表**(现实时钟 hour → 各状态权重,配置文件 `autonomy.schedule.json`,i18n 双份):如 0~6 点 LYING 权重×5、9~17 点 SITTING(desk/reading)×3、18~23 点 LOOKING/LOOKING×2。可调可关。
- **加权随机 + 禁忌**:不连去同一锚点;LYING 之后偏好 WANDER(醒一醒);距离远的锚点权重衰减(不想横穿房间就降权,用锚点坐标算)。
- **可选 LLM 增强(默认关)**:每 5~10min 或状态转移时,把"当前状态+最近对话摘要+记忆一行"发给小模型,从**白名单动作枚举**里选下一个(输出受限枚举,执行仍走同一链路;失败/超时回落本地决策)。与行内标签可靠性同思路:提示词只提高概率,确定性校验在客户端。

### 4.3 冲突仲裁(优先级从高到低)

| 优先级 | 来源 | 行为 |
| --- | --- | --- |
| 1 | 用户会话(phase≠IDLE、ASR/VAD 活跃) | FSM 冻结;若在走动,先停步让路(复用 stop 语义);坐/躺**不强制起身**(默认只视线回镜头;可选"起身迎客"开关,默认关——起身 1.2s 打断感强) |
| 2 | 技能/表演(猜拳/舞蹈/VFX show) | FSM 让位,结束后回 IDLE_STAND |
| 3 | LLM 工具指令(move_to/interact_with/stand_up) | **外部指令=状态转移**:工具完成后 FSM 以新位置为新状态续跑,不回弹 |
| 4 | 自主 FSM | 以上都空闲才决策 |

被动精细规则:问候前查 phase≠IDLE 则推迟重试;用户 30s 内说过话→问候冷却重置。

### 4.4 冷启动与可观测

- 挂载时读 `getContactPoseState()`+`getWalkState()` 推导当前状态(坐着的别突然起身)。
- adb 观测(照 ai_cmd 惯例):`ai_cmd autonomy_state`(当前状态/锚点/下次决策倒计时)、`ai_cmd autonomy_log`(最近 20 条转移记录)。

---

## 5. 不定时问候(GreetingScheduler)

- **调度(前台版)**:app 可见且 FSM 挂载时,最小间隔 25min + 泊松随机(等效 20~60min 一次),每日上限 3 次;**安静时段硬门**(默认 23:00~08:00 静默);phase≠IDLE 或用户正在输入 → 推迟。参数进设置页(默认克制,可关——Replika 因骚扰感被投诉过,airi 教训同)。
- **内容生成**:`MemoryRetriever.lineFor("用户 最近的对话与偏好")` 拿记忆事实 + 时段/天气无关的模板池("刚才想起来你说过{记忆},后来怎么样了?");可选 LLM 润色(默认关)。长度 ≤2 句。
- **播报**:走 `speak()`,文本可带 `<emo:joy> <act:wave>` 标签——挥手微笑说出来;**不写入对话历史**(speak 天然如此),但**写一条回执日志**(Room,airi 教训"主动发言要有回执",下次对话可被记忆提炼引用)。
- **与思考气泡联动**:开口前 1~2s 先弹思考气泡(内容=问候的"内心版",如"要不要问问 ta 今天怎么样"),制造"想你了才开口"的生命感(可选,默认开)。
- 后台推送(WorkManager+通知,点开 app 才播)留 **P3 延伸 ≈2 人日**,不在本期。

---

## 6. 思考气泡(ThoughtBubble)

### 6.1 触发与内容

- 触发源:①状态转移时(走向书桌→"先把昨天那章看完…";躺下→"今天也辛苦啦");②长停留尾段(SITTING 超 5min 随机);③问候前奏(§5);④(可选)LLM 决策的中间想法。
- 文本来源:ThoughtComposer 模板池(状态×时段×记忆槽位,中文/英文双份,纯本地零成本);可选 LLM 生成(默认关)。**≤20 字**,显示 5~8s 淡入淡出;同屏最多 1 个。
- 与 TTS 的关系:思考**不出声**(区别于问候),这是"内心独白"的设定;表情可以联动一个轻微 `<emo:think>` 语义(EmotionBlender 已有 think)。

### 6.2 渲染与跟随(关键技术点)

- **2D 覆盖层,不做 3D 贴片**:Compose overlay + Canvas 画经典思考云(主云朵 + 2~3 个递减小圆点尾巴指向头顶;用 Canvas Path 或 9-patch,优先 Canvas 矢量,无损缩放)。Filament 无文本引擎,3D 方案收益低成本高,不做。
- **跟随**:有气泡显示时才挂帧回调(`withFrameNanos`),每帧:
  1. `controller.getHeadWorldPosition()` → 头顶世界坐标(单模型 root+头骨高度;**corelib 新增 API①**,取景逻辑源已有——CameraShot 取景就是按 humanoid bones 推导的,AvatarController.kt:1020 注释);
  2. 世界坐标 Y + 0.25m 偏移 → `controller.projectToScreen(x,y,z)` → 归一化屏幕坐标(**corelib 新增 API②**,SoulLinkRenderer 内用 Filament camera 的 view×proj 矩阵;`getCameraLookAt()`(AvatarController.kt:1008)已公开 eye/target/up,但投影矩阵(FOV/aspect)未公开,需在 renderer 层补);
  3. 坐标换像素 → 气泡锚点(底部中心对准头顶偏上),水平 clamp 进屏;w<0(背对相机)或出视锥 → 隐藏。
- 坐/躺时头位随姿势变(投影源是头骨,天然正确);相机在 CameraShot 运镜/手势操作中矩阵持续变化——每帧现取,天然兼容。
- v1 单 avatar;多 avatar(MultiAvatarActivity)按 avatarId 扩展,后置。

### 6.3 corelib API 变更(仅两个,小而稳)

```kotlin
// AvatarController 新增
fun getHeadWorldPosition(): FloatArray?   // [x,y,z] 米;无模型/未就绪 null
fun projectToScreen(x: Float, y: Float, z: Float): FloatArray?
// 返回 [0..1] 归一化屏幕坐标 + w(≤0 或在视锥外 = null/隐藏),线程语义同 getCameraLookAt
```

---

## 7. 分层落点与改动清单

| 层 | 改动 | 规模 |
| --- | --- | --- |
| corelib | `getHeadWorldPosition()` + `projectToScreen()`(SoulLinkRenderer 补投影矩阵访问) | ~100 行 + 单测(矩阵可 JVM 对拍) |
| orchestrator | 新包 `autonomy/`:AutonomyDirector(FSM+仲裁)、GreetingScheduler、ThoughtComposer、`AutonomyActions` 接口、`autonomy.schedule.json` 解析、autonomy_state/log 事件 | ~700 行,转移表/调度/模板全部纯逻辑可 JVM 测 |
| app(→:companion) | executor 适配(InteractionCoordinator 串行化)、ThoughtBubbleOverlay(云朵 Canvas+帧跟随)、设置开关(自主行为/问候/气泡,默认开/开/开但问候克制)、adb 观测命令 | ~400 行 |

不动的东西:AvatarSession 主链路(只消费 phase/events)、AgentToolCatalog(工具照旧,只是结果会同步 FSM 状态)、InteractionCoordinator 就位链本身(只包一层互斥)。

---

## 8. 排期与工作量

| 项 | 内容 | 人日 |
| --- | --- | --- |
| P0-a | corelib 两 API + 投影 JVM 对拍单测 | 1.5 |
| P0-b | AutonomyDirector(FSM+作息+仲裁+事件接入)+ 单测 | 4~5 |
| P0-c | GreetingScheduler + speak 接入 + 回执 + 设置开关 | 2~3 |
| P0-d | ThoughtComposer + ThoughtBubbleOverlay(云朵+跟随+边界) | 3 |
| P0-e | 联调验收(冲突五场景+真机) | 2 |
| 合计 | | **12~14** |

顺序建议:P0-b → P0-a → P0-d → P0-c → P0-e(先让身体自己动起来,气泡最出效果但要等投影 API,问候最简单最后收尾)。

---

## 9. 验收标准(真机,dream_bedroom)

1. **自主行为**:放置 10 分钟不说话,发生 ≥6 次自主行为(含 ≥1 次坐/躺、≥1 次跨房间走动),无穿模、无卡死在 approach 点;深夜时段 LYING 权重明显(作息表生效)。
2. **仲裁**:自主走动中突然说话 → 立即停步并回应;舞蹈技能执行中 FSM 零动作;LLM move_to 到书桌后,FSM 从"坐书桌"续跑不回弹。
3. **问候**:开启后 30~60min 窗口收到一次,内容含真实记忆事实,可带挥手表情;23:00 后静默;phase≠IDLE 时不插话。
4. **气泡**:走动中气泡平滑跟随无跳变(帧率不拖累 HUD fps>25);坐/躺时高度自动贴姿势;相机拉近/运镜中不丢;头像背对相机时隐藏。
5. **可观测**:`ai_cmd autonomy_state`/`autonomy_log` 输出完整决策轨迹。
6. **回归**:单测全绿(新增 FSM/调度/投影对拍用例);多人对话、辩论、团播 demo 不受影响(默认关 autonomy)。

---

## 10. 风险与预埋的坑

1. **投影矩阵访问**:SoulLinkRenderer 现在只回 lookAt 三元组,view/proj 矩阵要新开只读口——注意 Filament camera 的 proj 是 fov/aspect 推导,分辨率变化/分屏时每帧现取即可,不要缓存。
2. **竞态三连**:①FSM 转移 vs 工具起步 → 单 executor 互斥;②起身 1.2s 内的新指令 → 排队不丢弃;③CameraShot 运镜与气泡帧取投影 → 无冲突(只读),但气泡更新别占用渲染线程(Compose 帧回调即可)。
3. **站定判定照抄轮询两拍**,别信 WalkArrived(多段腿事件序,InteractionCoordinator 注释原话)。
4. **"抽搐感"上限**:同状态最短停留 + 每小时自主行为总数上限(如 ≤12 次),超限进纯 idle——宁可安静也不要频繁乱动,生命感≠多动。
5. 躺姿根旋转方案下 `getHeadWorldPosition` 的头骨世界坐标要实测(躺下时头骨 y 低,气泡贴脸的问题 → 偏移取 max(头顶+0.25, 床面+0.4))。
6. FreeSpeech VAD/系统 ASR 活跃信号要接进仲裁(用户正在说话的判定不只 phase,还有输入侧),否则问候会插用户话头。
7. 记忆为空的冷启动:模板池要有"无记忆"分支(别生成"想起来你说过{null}")。

---

## 11. 与其他文档的关系

- 优先级重排依据:docs/tavern-companion-feasibility.md §5(本设计=新 P0,酒馆四件套顺延为 P1)。
- 主动性设计继承:docs/agent-feasibility.md §1.3(airi 三路径)与 §1.4 四教训——沉默出口、概率+冷却、回执、保守默认。
- 执行链背景:docs/locomotion-feasibility.md(走动/锚点图)、docs/dream-bedroom-integration.md(交互点表/接触定姿)。
- 后续延伸(P3):WorkManager 后台问候推送、多 avatar 各自 FSM、LLM 决策增强默认化、`set_reminder` 工具(让 LLM 亲手定闹钟,agent-feasibility P1 原案)。
