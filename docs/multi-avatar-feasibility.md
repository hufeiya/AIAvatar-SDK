# 一屏多虚拟人（多智能体舞台）可行性调研与落地方案

> 用途：回答「同一个界面里渲染多个虚拟人，各自独立的模型 / API / 上下文 / 位置朝向，人可以和任意虚拟人对话，虚拟人之间也能自由对话」是否可行、要动哪里、分几期落地。
> 生成时间：2026-10-13。证据来源：corelib / avatar-orchestrator / app 三路并行源码深读（file:line 均已核对），配合 docs/ai-layer-handoff.md 与 docs/project-guide.md 的既有结论。
> 关联文档：docs/locomotion-feasibility.md（走动=本方案的空间基座）、docs/agent-feasibility.md（工具循环=对话能力的基座）、docs/memory-feasibility.md（记忆=本方案要加 per-avatar 维度的对象）。

---

## 一、结论速览

**总判定：可行。** 四层架构里三层（adapter / orchestrator 会话 / app 装配机制）对多实例天然友好或只需参数化，唯一需要外科手术的是 **corelib 渲染层的「单模型假设」**。逐项判定：

| 需求项 | 现状 | 差距 | 结论 |
|---|---|---|---|
| 一屏渲染 N 个 VRM | Filament 一 Scene 已同挂场景 GLB + 模型 + 三灯 + IBL，多资产天然可行；单模型约束只在 `ModelViewer` 的单 `asset/animator` 字段与渲染器字段绑定 | `SoulLinkRenderer` 拆成 Stage（共享）+ AvatarRuntime（每人一套）；六个 per-model 引擎已验证无单模型假设，可直接复用 | **要动一次大手术，但方向已被代码验证** |
| 独立模型（每人一个 VRM） | 加载管线 per-asset 干净 | AvatarRuntime 化即可 | 随渲染手术一并解决 |
| 独立 API / 服务商 / 音色 | `AvatarSession` 零全局状态、全依赖注入构造，可 N 实例；`AIAvatarSdk` 是普通 class（每 facade 一个 session），N 个 facade 即 N 套配置 | 无硬障碍 | **现成** |
| 独立上下文（历史/人设） | Room 历史按 `sessionId`（=contextId）隔离，DAO 全部按 sessionId 过滤；人设卡 `setCharacterCard` 是 per-session API | 各 session 必须用不同 contextId（现在共用 `"default"`） | **现成，参数化即可** |
| 独立长期记忆 | `MemoryStore` 是**全局共享设计**（注释明说跨上下文共享），实体无 avatarId 维度；提炼窗口是**单行表 id=1** | 双人设共享=记忆混池+提炼窗口互踩（last-writer-wins） | **要改：加 avatarId 维度** |
| 位置 / 朝向（同场景共站） | `LocomotionEngine` 纯数学、per-实例化无障碍；走动写 root 平移的落点、站位、步态状态机都是「当前唯一模型」的 | 站位偏移（现在 `MODEL_CENTER(0,0,-4)` 所有人叠同一点）、走动/步态 per-avatar 化 | 随渲染手术一并解决 |
| 人 ↔ 虚拟人 | 单 session 点对点已完善（语音四模式/打断链路/字幕） | 增加「这句话给谁」的路由层；麦克风三链路本来就互斥、天然单链复用 | 新增薄路由层 |
| 虚拟人 ↔ 虚拟人 | 无。但发言权调度/多方对话在业界是成熟模式（AutoGen group chat、DialogLab 2025） | 新增 ConversationDirector（导演）：发言权仲裁 + 多人消息进上下文的格式 + 防死循环 | **纯新增，不侵入现有链路** |
| 多人同时说话 | `AudioTrackPlaybackQueue` 无静态状态、独立 AudioTrack（独立 audio session），系统混音器天然支持并行发声；但全仓库**零音频焦点处理** | 产品上同一时刻只应有一人开口 → 由导演保证；可选 ducking | 调度保证，非技术障碍 |

**推荐架构一句话**：渲染加 `Stage/AvatarRuntime` 两层抽象；编排 N 个 `AvatarSession` + 一个 `ConversationDirector`（规则档零成本起步、LLM 档热切换）；虚拟人间消息以 `【说话人】:文本` 形态走 user 通道进各自历史；逐轮发言权提示挂在既有「末尾 user 前缀槽」。

**四期路线**：P0 渲染多实例（同屏双人各自呼吸眨眼）→ P1 会话多实例（独立配置/上下文，人点谁谁答）→ P2 圆桌对话（导演调度 + 虚拟人互聊 + 防失控）→ P3 空间智能（走动站位、说话人注视、双人运镜）。总量预估 **15~24 人日**（渲染手术与真机回归占大头）。

---

## 二、目标场景定义（作为验收口径）

**必须支持（v1 目标 2~3 人，架构上不设上限）：**

1. 同一个 Filament View / Scene 内渲染 N 个虚拟人，同一场景（home.glb 级别的房间），各自有独立站位与朝向，不重叠。
2. 每个虚拟人独立：VRM 模型、人设卡（SillyTavern 卡）、会话历史（contextId）、LLM 服务商/模型/API Key、TTS 引擎/音色、长期记忆库。
3. 人可以点名与任意虚拟人对话（打字 / 语音），被点者接话，其余人保持倾听状态（看向说话方，不抢话）。
4. 虚拟人之间自由对话：给定话题后 A 说一句、B 接一句，可多轮；人可随时插话或打断。
5. 全程端侧 Serverless 架构不变：直连 OpenAI 兼容 API，无自建服务端。

**明确不做（非目标，写进边界防发散）：**

- 多设备/联网同步、云端房间；
- 物理碰撞与自动避障（虚拟人互相穿模不在 v1 解决，靠站位锚点错开）；
- >3 人的大圆桌产品化（导演协议线性成本没问题，瓶颈在字幕 UI 与用户注意力，见 §8）；
- 虚拟人「抢话/同时说」的自由混音（v1 严格轮转，见 §4.5）。

---

## 三、现状盘点（逐层证据）

### 3.1 渲染层：单模型假设长在哪里

corelib 的单模型约束**不在 Filament**，而在两处：

1. **`ModelViewer`（filament-utils 库）**：内部是单个 `private FilamentAsset asset` + 单个 `Animator`。`SoulLinkRenderer` 构造时创建整套 `Engine/Scene/View/Camera/Renderer/SwapChain/AssetLoader/ResourceLoader`（internal/SoulLinkRenderer.kt:89-94）。
2. **渲染器把全部 per-model 状态绑在「当前唯一模型」上**：`loadModelBytes`（SoulLinkRenderer.kt:952-1206）每次先 `destroyModel` + 清空全部子系统字段再重建——`vrmaEngine/expressionManager/lookAtEngine/breathEngine/mimicEngine/springBoneManager` 全是渲染器级可空字段（:104-140）， hips 拖拽、运镜骨锚（head/chest/spine/neck，:158-161）同理。

**关键的「已经是对的」部分**（探索逐项确认过，这是可行性的核心依据）：

- **一 Scene 多 asset 今天就成立**：场景 GLB 由渲染器自己的第二套 AssetLoader 加载后 `modelViewer.scene.addEntities(...)`（:1714-1725），灯也是 `scene.addEntity`（:702）——场景、模型、灯、Skybox、IBL 本就同挂一个 Scene。第二个 VRM 只要用类似场景 GLB 的路径（独立 AssetLoader→createAsset→addEntities）进同一个 Scene 即可。
- **六个 per-model 引擎全部无单模型假设**：`VrmaAnimationEngine/VrmExpressionManager/VrmSpringBoneManager/VrmBreathEngine/VrmLookAtEngine/VrmPoseMimicEngine` 都只吃 `Engine` + bind 的 entity，bindToModel 时另存 rest pose——每 avatar 各 new 一套即可直接用。
- **`LocomotionEngine` 是纯数学状态机**（JVM 可测），per-avatar 实例化零障碍；走动写的是 asset root 的 TransformManager 矩阵（:2337-2421），root 是 per-asset 的，天然分得开。
- **灯光/IBL/渲染设置天然是「舞台级」**：三盏 SPOT（:257-259）、`AvatarRenderSettings`（:244）、ColorGrading 都属于 Scene；MToon 不吃场景灯、每个模型自己的 `MToonApplier` 逐材质实例吃 key 灯参数（MToonApplier.kt:144-162）——两个模型吃同一盏灯无障碍，只需 `mtoonFactory`（编译好的材质库）上移到舞台级共享（:1191 现在是渲染器内 lazy 复用，模式可直接平移）。
- **全模块无静态可变状态**：9 个 `object` 全是无状态纯函数，companion 全是常量。两个 `AvatarController` 并存（各带一个 AvatarSurfaceView=两个 Engine）完全可行——这是「能用但不对」的方案 A（见 §4.1）。

**必须补的 per-avatar 能力**：

- **站位**：`transformToUnitCube` + `MODEL_CENTER(0,0,-4)`（:291, :1033）把模型钉在世界原点——需要 per-avatar 的 spawn 偏移（等价于走动的 root 平移写入，机制已有，只差「每人一个值」）。
- **VRMA 共享缓存**：现在每个 VRMA 文件每次 `loadVrmaAnimation/setVrmaIdleAnimation/步态加载` 都重新读 assets 重新 parse（:1436-1447, :1938-1975），无任何缓存。`VrmaAnimation` 解析产物是归一化空间数据、与具体模型无关，可以舞台级共享 `source → VrmaAnimation` 只读缓存（N 个人挂同一 idle / 同一步态时省 N-1 份解析与内存）。
- **运镜目标参数化**：`shotPose/boneAnchor`（:2557-2743）从当前模型的骨锚推 pivot；FOLLOW 跟拍骑在当前模型 hips 上（:2702）。要变成「shot 指定跟谁」，并新增双人同框机位（GROUP_SHOT：pivot=两人 head 中点，距离按两人 span 合计缩放）。
- **帧提交时序**：`commitLocalTransformTransaction` 是引擎级（:463），N 个 runtime 每帧各自写骨骼后必须**共享同一次 commit**；`animator.updateBoneMatrices()` 则 per-asset 各调各的（:464/:484）。这是拆分时最容易踩的时序点。

### 3.2 编排层：AvatarSession 天然可多实例

- `AvatarSession` 构造全依赖注入（AvatarSession.kt:97-145）：`scope/llm/tts/controller/options/playbackQueue/lipSyncProcessor/gestureDriver/locomotionDriver/store/memoryStore/embeddingAdapter/skillHost/heavyWorkDispatcher`。**全模块零静态可变状态**（object 全是纯函数/常量），两个实例并存无硬障碍。
- `AIAvatarSdk` 是普通 class（AIAvatarSdk.kt:61），companion 只有常量；`_session`/`builtFor`/`config` 全实例字段（:214-241）——**每个 avatar 一个 facade 实例**即得完全独立的配置解析与会话重建语义（configure 的 `ChatConfig` data class 全量相等判身份，:256）。facade 的 `events/phase` 用 `flatMapLatest` 只接当前会话（:226-233），N 个 facade 互不干扰。
- per-session 已隔离的：`SpeechPipeline`（Semaphore 是实例字段，ttsMaxConcurrent=4，SpeechPipeline.kt:55）、`ToolRegistry`（:321）、`SkillRegistry`（:376）、FaceDriver 及其 EmotionBlender/MicroMotionEngine/SaccadeEngine（:253-254 构造注入各自的 controller；`Choreographer.getInstance()` 是进程单例但多 FrameCallback 注册互不覆盖）、GestureDriver/LocomotionDriver、AudioTrackPlaybackQueue（专属 writer 线程 + `AUDIO_SESSION_ID_GENERATE` 独立 audio session，AudioTrackPlaybackQueue.kt:63-65, :229-251）、`PcmDecoder` 纯函数并发安全。
- **历史隔离现成**：Room `MessageEntity` 有 `sessionId` 列+索引，DAO 全按 sessionId 过滤（ConversationDatabase.kt:29-40）；`RoomConversationStore.from(db, sessionId=config.contextId, …)`（AIAvatarSdk.kt:271-275）。共用一个 RoomDatabase 实例安全，前提是**各 session 用不同 contextId**。
- **记忆是唯一的语义障碍**：`AgentMemoryEntity` 无 avatarId/sessionId 维度（MemoryDatabase.kt:20-38），`MemoryStore.kt:4-6` 注释明说「记忆跨上下文全局共享——不挂 contextId」；提炼窗口 `PendingWindowEntity` 是**单行表 id=1**（:40-46）。两个人设共用=检索混池（A 会想起对 B 说的私事）+ 窗口 last-writer-wins 互踩。改造小而明确：实体加 `avatarId` 列 + 窗口表加维度（或每 avatar 独立 db 文件，`MemoryDatabase.getInstance` 加 name 参数）。

### 3.3 音频与输入资源

- **播放**：每个 queue 独立 AudioTrack（USAGE_MEDIA + CONTENT_TYPE_SPEECH），Android 混音器天然支持多轨并行——技术上多人齐说没问题，产品上必须轮转（导演保证）。
- **音频焦点：全仓库零处理**（四模块 grep `requestAudioFocus/AudioFocusRequest` 无命中）。现状单虚拟人靠「打断链路」兼职了焦点语义；多虚拟人后「谁在说话」必须显式仲裁，反而把焦点问题收编进导演协议（§4.5）。
- **麦克风**：三条聆听链（按住说话/自由说话 VAD/系统 ASR）已有严格互斥——同一时刻最多一条活着（MainActivity.kt:1737-1757 生命周期 effect），按住说话半双工先 interrupt 再录（:1777/:1787）。多虚拟人**天然共享一条聆听链**：用户对「舞台」说话，路由层决定给谁（§4.6）。
- **相机**：`UserCameraTracker` 是进程级用户侧资源，与虚拟人数无关，共享即可。

### 3.4 app 层（体力活清单）

- `AiChatPrefs` 全部键平铺在单一 `demo_settings`（AiChat.kt:145-164），无 avatar 命名空间——per-slot 化需键名前缀或 per-slot prefs；**API Key 类保持全局共享（一份钱），provider/model/voice/contextId 才 per-slot**。
- session 装配链已收敛：`AiChatController.ensure → AIAvatarSdk.configure`（AiChat.kt:340-353）每 slot 实例化一套即可；SimpleDemoActivity（199 行，五步裸装配）是 MultiAvatarActivity 的理想骨架。
- MainActivity 约 95 处引用单 session（produceState + ~10 个接线 effect + chatHooks 闭包 35+ lambda，:1040-1253, :1929-2437）——**不要在 MainActivity 里改**，新开 MultiAvatarActivity，把「slot=一组 (controller, session, chatLines, 事件收集器)」抽成可复用 composable。
- 技能实例（rps/lookHere/mimic）是 `remember` 单例、注册进每个 session 的 SkillRegistry（:993-995, :1067-1069）——多人时必须 per-slot 实例化，否则对局状态串台。
- `ai_cmd` 调试链集中（一个巨型 when + hooks 结构体，AiDebug.kt:24-80 / AiDebugExecutor.kt:28-138），加 `ai_target` extra + session 查表难度中低；真机验收主力手段必须同步多人化，否则无法调试。

---

## 四、关键设计决策

### 4.1 渲染：Stage + AvatarRuntime（正解）vs 多 View 拼贴（歧路）

**方案 A（不推荐，仅记录）**：叠 2-3 个 AvatarView，各自独立 Engine/Scene。能跑（无静态障碍），但每个 View 一个 Scene 意味着场景 GLB 加载 N 份、两层之间无遮挡关系（A 走到 B 身后仍画在上层）、灯光/IBL/内存全部 N 倍。只适合「分屏各自对话」形态，与「同场景共站」的目标根本冲突。

**方案 B（推荐）**：corelib 增加两层抽象，一次手术拆分 `SoulLinkRenderer`（现 ~2800 行）：

```
Stage（舞台，每 View 一个）                    AvatarRuntime（演员，每人一个）
├─ Engine/Scene/View/SwapChain               ├─ FilamentAsset + Animator 引用
├─ 相机 + Manipulator + 运镜（目标可参数化）     ├─ VrmaAnimationEngine（动画/步态播放）
├─ 三灯 rig + IBL + Skybox + ColorGrading     ├─ VrmExpressionManager（表情/口型落点）
├─ AvatarRenderSettings + MToonMaterialFactory├─ VrmSpringBoneManager / BreathEngine
├─ 场景 GLB + ScenePlacement/WalkBounds       ├─ VrmLookAtEngine / PoseMimicEngine
├─ VRMA 只读缓存（source → VrmaAnimation）     ├─ LocomotionEngine + 步态槽（走动状态机）
├─ FPS 统计 / captureFrame / Choreographer 泵  ├─ hips 拖拽状态 + 骨锚（运镜目标提供者）
└─ 每帧泵：遍历 runtime.tick() → 共享 commit   └─ spawn 偏移（站位）+ 独立 root 平移/朝向
```

动刀面（依据探索报告的粗估）：`SoulLinkRenderer.kt` 按上表拆两半（主体工作）；`AvatarController` 拆 Stage 门面（loadScene/运镜/renderSettings/captureFrame）+ `loadAvatar(spec) → AvatarHandle`（现全部单数 API 挂到 handle）；`AvatarView/AvatarSurfaceView` 构造 Stage + 新增多演员入口；`AvatarState.Ready.scenePath` 迁出到 Stage 状态；新增 `VrmaClipCache`。**可以不动**：六个引擎本体、`LocomotionEngine`、全部纯函数 object、GLB 工具（MorphPatcher/BoneCuller/MToon 解析）、`AvatarRenderSettings/CameraShot/Locomotion` 类型（加 spawn 字段）。

**兼容性红线**：现有单虚拟人 API 与行为零变化——`AvatarController` 保留旧门面语义（内部=Stage+单 runtime 的糖），SimpleDemoActivity/MainActivity 不受影响；既有回归面（呼吸/视线/口型/走动/拖拽/运镜/MToon/弹簧骨）全部要在手术后真机回归一遍，这是 P0 工作量的大头。

### 4.2 会话：N 组独立实例，facade 一人一个

- 每 avatar：一个 `AIAvatarSdk` 实例（自得配置解析/重建语义/事件流）+ 一个 `AvatarSession` + 独立 `contextId`（如 `stage_alice`）+ 自己的 `SupervisorJob` 子 scope。
- **scope 隔离是硬要求**：`close()` 不取消共享 scope 上 init 注册的 collector（AvatarSession.kt:453-469 的注释就是僵尸 collector 真机事故记录）——共享 scope 下重建某个 slot 的会话会留下旧 collector。
- 共享的：OkHttpClient（应该全局单例，连接池复用）、`heavyWorkDispatcher`、`ConversationDatabase`（Room 单例库按 sessionId 隔离）、麦克风三链路、`UserCameraTracker`。
- **TTS 并发预算共享**：N 个 session 各自 Semaphore(4)，同时合成=4N 路在途请求，会顶到服务商限流。舞台层持一个全局预算（如 Semaphore(4) 注入各 session 的 Options——`ttsMaxConcurrent` 已是 Options 字段，天然支持）。

### 4.3 虚拟人 ↔ 虚拟人通信：导演制

三种候选模式：

| 模式 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A 链式广播 | 每个 avatar 发言后，文本广播给其余所有 session 当 user 消息 | 实现最简 | 抢话无法仲裁、两个客套模型无限互捧、上下文膨胀失控 |
| **B 导演制（推荐）** | `ConversationDirector` 集中仲裁：每轮决定「下一个开口的是谁/要不要收场」，被点名者的 session 才发起生成 | 发言权显式、防循环有抓手、规则档零成本、易调试（导演决策可打日志/进 UI） | 每句多一次轻量决策（规则档为 0，LLM 档一次百 token 级调用） |
| C 完全自主 | 每 avatar 独立 loop 自行举手/插话 | 最像真人 | 成本与失控风险最高，打断仲裁复杂，v1 不做 |

**导演的两档实现（同一接口，热切换）**：

1. **规则档（默认，零成本）**：轮转 + 约束。被用户点名者优先；话题模式下 round-robin；同一人连续发言上限；回合数上限（如 20 轮强制收场）；可选「话题相关才接话」的关键词门（v1 不做，交给 LLM 档）。
2. **LLM 档（P2）**：一次非流式轻量调用（硅基流动便宜模型/Qwen3.8 关思考，复用提炼 prompt 的可靠性矩阵结论），输入=最近 K 条发言摘要 + 各 avatar 一句人设简介，输出=结构化 JSON `{"next": "alice"|"user"|"END", "reason": "..."}`（解析复用 MemoryParser 的「截首个平衡 JSON+lenient」套路）。`END` → 导演生成收场白或让话题发起者总结。

**导演不进生成链路**：它只发「发言权」，正文仍由各 avatar 自己的独立模型生成——保住「独立模型/独立人设」的目标（两个不同服务商、不同情商的模型在同一个圆桌上，这正是卖点）。

### 4.4 多人消息进上下文的格式

每个 avatar 的会话视角（以 A 为例）：

- 用户发言：`role=user`，内容原样（圆桌模式下加前缀 `【用户】`）；
- 其他 avatar 的发言：`role=user`，内容前缀 `【B】:…`；
- 自己的发言：`role=assistant`，原样（现有 store 只存 user+assistant 全量 raw，工具消息不落库——多人模式完全沿用，`recentTurnLimit=40` 裁剪零改动）。

**协议注入缝（探索确认的最优落点）**：

- 静态圆桌规则（「你在和一个用户与 B、C 同场对话；听到别人点名你再接话；发言以一行为主」+ 在场者名单）：走 `Options.identityPreamble`（AvatarSession.kt:174）或自定义 `Options.assembler`——每轮恒带、不破坏钉住协议指纹；
- **逐轮变化的发言权提示**（「下一个开口的是你」/「现在是 B 在说话」）：挂**末尾 user 前缀槽**（viewLine/memoryLine/skillLine 旁边再加一行 roundtableLine，:1236-1244）——逐轮变化、不进 store、不碰钉住协议块（协议块按内容指纹缓存，:1300-1315，塞可变清单=每轮重发 12K 字符打穿前缀缓存，**严禁**）。

### 4.5 发言权与打断仲裁（舞台音频策略）

- **同时只有一人开口**：由导演发言权保证；播放层加一道保险——舞台级「当前说话者」令牌，未持令牌的 queue 到点静默丢弃或压音量（v1 用前者，简单可调试）。
- **人打断虚拟人**：打断目标是**正在说话的那个** session（现有 `interrupt()` 三层打断链路原样复用：取消 LLM 流 + cancelTurn + stopAll 即刻静音）。谁在说话由舞台跟踪各 session 的 `SentenceStarted/Ended` 事件维护。
- **虚拟人打断虚拟人**（P3）：举手协议——非当前发言者在句间隙通过导演请求插话，LLM 档导演裁决。v1 不做，严格轮转。
- **语音对讲半双工**：自由说话 VAD 的 barge-in 门（打断在播的虚拟人）天然作用于「当前说话者」；ASR 半双工暂停逻辑（SPEAKING 时暂停采音）语义不变——「SPEAKING」变成舞台级任一人在说。

### 4.6 听觉路由：一句话给谁

麦克风只有一条链，识别出的文本需要路由：

1. **点名优先**：文本含某 avatar 名字/称呼（人设卡的 name + 别名字表）→ 路由给它，进入它的历史；
2. **默认路由**：无点名 → 发给「最后一个和用户交互的 avatar」（`lastAddressed`，UI 高亮提示当前对话对象）；
3. **圆桌话题模式**：人插话 → 广播进所有人历史（`【用户】` 前缀），导演决定谁接。

打字通道同理：输入框支持 `@名字 前缀`；字幕面板显示当前路由对象。

### 4.7 空间：站位、注视、走动、镜头

- **站位**：每 avatar 的 `AvatarSpec` 带 spawn 位置/朝向（root 平移+Y 旋转，机制=走动同款 root 写入）；场景 placement/WalkBounds 是舞台级共享，bounds clamp 各自生效。
- **注视闭环（倾听感的最大来源，成本极低）**：A 开口时，导演把 B 的 `setLookAtTarget` 指向 A 的头部世界坐标（corelib 已支持世界点目标 + FaceDriver GazeMode.POINT），A 说完 B 回看镜头。已有的 saccade/头颈平滑直接生效。
- **走动**：`LocomotionEngine`+步态槽 per-runtime；锚点表舞台级共享、注入各 session 的 `locomotionAnchors`（现有 per-session 注入缝不动）；两人同时走互不干扰（无避碰，靠锚点错开）；`walkFinished` 流要带 avatarId。
- **镜头**：`CameraShot` 加目标参数（跟谁）；新增 `GROUP_SHOT` 双人同框；`<cam:>` 标签在圆桌模式下**默认禁用**（N 个模型各自发镜头指令=运镜混乱）——镜头控制权收归导演/用户，这是共享资源仲裁清单的一部分。

### 4.8 共享资源冲突总清单（多虚拟人暴露的单例语义）

| 资源 | 现状归属 | 多人策略 |
|---|---|---|
| 相机/运镜 | 舞台级单相机 | 目标参数化；LLM `<cam:>` 圆桌默认关 |
| 场景氛围 `set_ambience` / 灯光 | 舞台级 | 工具圆桌默认关（或仅话题发起者可用） |
| 手电筒/震动/拍照 | 设备级 | 每人注册一份无意义→舞台级一份，点名路由 |
| TTS 并发预算 | per-session 4 | 舞台共享 Semaphore(4) |
| 音频焦点/静音令牌 | 无 | 舞台「当前说话者」令牌 |
| 麦克风/相机 tracker | 进程级用户侧 | 共享不变 |
| 长期记忆库 | 全局共享 | **加 avatarId 维度（必改）** |
| Observe_camera 视觉 | 拍的是共享相机画面 | 语义不变（看到的都是同一个房间），保留 |

### 4.9 UI / 调试形态

- 新开 `MultiAvatarActivity`（SimpleDemoActivity 骨架 × N slot + 舞台面板），不动 MainActivity 巨石；manifest 注册 exported 供 ai_cmd 直达。
- 字幕：单流聊天记录加说话人着色/头像标记（`ChatLine` 加 speaker 字段，ChatTranscript 小改）；每 slot 一条字幕行浮在各自人物上方或底部对齐分道。
- 配置页：slot 选择器 + 现有 AI 配置区块参数化（键名 per-slot 化）。
- ai_cmd：加 `ai_target`（`send_chat --es ai_target alice`）；新增 `stage_state`（各 slot phase/当前说话者/导演决策日志）、`stage_topic <话题>`（圆桌开题）、`stage_stop`。

---

## 五、总体架构与数据流

```
                        ┌────────────────────────────────────────────┐
                        │            MultiAvatarActivity (app)       │
                        │  AvatarSlot ×N: 配置UI/字幕/事件收集/技能     │
                        │  StagePanel: 话题/导演日志/说话人高亮          │
                        └──────┬─────────────────────────┬───────────┘
                               │ send(text, target)      │ events(合流+带 avatarId)
                        ┌──────▼─────────────┐   ┌───────▼──────────────────┐
   麦克风三链路(共享) ──→ │  InputRouter 路由层  │   │  ConversationDirector    │
                        └──────┬─────────────┘   │  规则档/LLM档 发言权仲裁    │
                               │ route            └───────┬──────────────────┘
              ┌────────────────▼─────────────────────────┐│
              │      RoundtableStage (orchestrator 门面)   │◄┘ next_speaker
              │   sessions: Map<id, AvatarSession>        │
              │   speakToken / listenGaze / topic state   │
              └───┬───────────────┬───────────────┬───────┘
                  │               │               │
          ┌───────▼─────┐ ┌───────▼─────┐ ┌───────▼─────┐
          │AvatarSession│ │AvatarSession│ │AvatarSession│   ← 现有类，零改动
          │ (alice)     │ │ (bob)       │ │ (carol)     │     独立 LLM/TTS/卡/contextId
          │ AIAvatarSdk │ │ AIAvatarSdk │ │ AIAvatarSdk │     独立记忆(avatarId)
          └───────┬─────┘ └───────┬─────┘ └───────┬─────┘
                  │ AvatarHandle  │               │
          ┌───────▼───────────────▼───────────────▼─────┐
          │  Stage (corelib): Scene/灯/相机/VRMA缓存/泵    │
          │  AvatarRuntime ×N: 动画/表情/弹簧骨/呼吸/视线/走动│
          └──────────────────────────────────────────────┘
```

**一次圆桌回合的时序**（人问「你们怎么看周末去哪玩」）：

```
InputRouter: 无点名 + 圆桌模式 → 广播进 A/B 历史(【用户】前缀), 通知 Director
Director(规则档): lastAddressed=A → 授予 A 发言权
A: AvatarSession.send → A 的 LLM 流 → 断句→TTS→口型 (现链路原样)      ← B 的 FaceDriver
   ├ SentenceStarted: 舞台令牌→A; B/C setLookAtTarget(A头部)            同帧注视 A
   └ TurnCompleted → Director → LLM 档: 裁决 next=B(reason: A 问了 B 的意见)
B: 逐轮 user 前缀槽注入「轮到你接话」→ B.send → …… → END 收场
```

**API 草图**（签名级，供评审；不是终稿）：

```kotlin
// corelib
val stage = rememberAvatarStage()                 // Stage 门面（内部兼容单 AvatarController 语义）
val alice = stage.loadAvatar(AvatarSpec(          // spec: 模型路径 + spawn(x,z,yaw) + 步态等
    model = "vrms/10.vrm", spawn = Spawn(x=-0.8f, z=-3.2f, yawDeg=15f)))
stage.cameraShot(CameraShot.GROUP_SHOT, targets = listOf(alice, bob))
alice.playVrmaAnimation(...); alice.setLookAtWorldPoint(...); alice.startWalk(...)

// orchestrator
val stageSession = RoundtableSession(             // N 个 AvatarSession + Director + 令牌/注视编排
    slots = listOf(slot("alice", aiSdkA, greetingA), slot("bob", aiSdkB, greetingB)),
    director = DirectorPolicy.RuleBased(maxRounds = 20) /* 或 DirectorPolicy.Llm(cheapLlm) */)
stageSession.startRoundtable(topic = "周末去哪玩")
stageSession.sendFromUser(text = "你们怎么看…")     // 内部走 InputRouter + Director
```

---

## 六、性能与成本预算

**渲染**（测试基线=Redmi K20 Pro / Snapdragon 855，单虚拟人 MToon 60fps 实测）：

- 每增加一个 VRM：纹理显存与顶点各 +1 份（MToon 贴图 per-applier 不共享）、动画采样/弹簧骨/呼吸/视线每帧成本 ×N。弹簧骨是逐子步 Verlet 积分、每帧最贵的一档（且现存 GC 压力问题，project-guide §5.2），**2 人无忧、3 人是舒适上限**（每帧预算 16ms 内 N×(动画+弹簧骨) 仍 <5ms 量级）。
- 杠杆：共享 VRMA 缓存省内存；远处/非焦点 runtime 降频（弹簧骨隔帧步进、呼吸/眨眼照常）留作 P3 优化项；PCSS 悬崖约束不变（阴影档位默认 1024+PCF，多演员**绝不开 PCSS**）。
- 已知放大项：模型加载是主线程同步管线（单模型单消息 1.2s 实测）——**两个模型启动顺序加载 + 切模型时机避开对话**，并把「加载异步化」列为 P0 顺手项（否则双人启动 ANR 风险翻倍）。

**网络与成本**（Serverless 不变，线性放大）：

- 圆桌 1 轮（A、B 各一句）= 2 次 LLM 流式 + 2×N 句 TTS + 导演 1 次（LLM 档，百 token 级，规则档 0）。LLM 档导演用免费/低价模型即可（矩阵结论：提炼类结构化输出 Qwen3.8 关思考/doubao-mini 均 5/5 可靠）。
- 限流：TTS 全局 Semaphore(4) 收口；双服务商天然分流（A 走硅基 B 走火山时互不限流——这本身就是多服务商卖点）。
- 上下文：多人消息走 user 通道，`recentTurnLimit=40` 不变；钉住协议 per-session 各自一份，圆桌规则放 preamble 不打穿前缀缓存。

---

## 七、分期落地计划

> 每期独立可验收、独立可发布；遵循仓库惯例：纯逻辑下沉 JVM 单测 + ai_cmd 真机验收。

### P0 渲染多实例（同屏双人静态）——约 5~8 人日

- 动刀：Stage/AvatarRuntime 拆分（§4.1 清单）、VRMA 只读缓存、per-avatar spawn、运镜目标参数化、加载异步化顺手项；`AvatarController` 旧语义零变化的兼容层。
- 不含：任何 AI（双人各自挂 idle、呼吸、眨眼即可）。
- 验收：同屏 2 模型（1×MToon + 1×VRM0.x）+ home.glb 场景 60fps（`ai_cmd state` fps 字段）；各自独立表情/视线指令；单人全功能回归（口型/走动/拖拽/运镜/MToon 切换/弹簧骨，对拍手术前录屏）；`captureFrame` 截帧含双人；杀进程/重载无 SIGABRT（材质 in-use 顺序在多实例下复核）。
- 单测：LocomotionEngine/表达式归一等既有 JVM 套件全绿 + 新增 spawn 矩阵/双 runtime 状态隔离的纯数学测试。

### P1 会话多实例 + 人机路由——约 3~5 人日

- 动刀：orchestrator 加 `RoundtableSession` 门面（先只有 N session 编排 + 令牌，无 Director）+ `AvatarEvent` 带 slotId（或舞台层包一层路由事件）；记忆库 avatarId 维度（迁移：现库加列默认值）；MultiAvatarActivity 骨架（slot 配置页/字幕分道/事件收集 composable）；ai_cmd `ai_target`。
- 交互：打字/语音点名路由（§4.6），被点者接话，其余人注视发言者（倾听态）。
- 验收：A/B 各自独立人设卡+独立服务商（A 硅基 B 火山）+独立音色同时在线；「@A 你好」A 答 B 不答；分别问各自人设问题答案不串；历史/记忆按人隔离（`memory_list` 分库可见）；打断命中说话者；双人同时合成不超全局 TTS 预算。
- 单测：路由器（点名/默认/广播）、令牌状态机、记忆 avatarId 隔离、RoundtableSession 双 session 编排（ScriptedLlm fake 套路复用）。

### P2 圆桌对话（导演制）——约 4~6 人日

- 动刀：`ConversationDirector`（规则档→LLM 档，§4.3）；多人消息上下文格式（§4.4）；圆桌协议 preamble + 逐轮发言权前缀槽；防失控三件套（轮数上限/重复检测/END 收场）；StagePanel（话题输入/导演决策日志/说话人高亮）；`stage_topic/stage_stop/state` 命令。
- 验收（真机）：「你们聊聊周末去哪玩」→ A、B 交替 ≥10 轮不重复不互捧死循环；规则档/LLM 档热切换；人插话广播后导演正确移交发言权；轮数上限触发收场白；两模型异构对话（如 A=DeepSeek 活泼、B=doubao 沉稳）人设不漂移；全程 LlmPrompt 日志核对：钉住协议未被打穿（cached>0）。
- 单测：Director 规则档全分支（轮转/上限/点名优先/END）、LLM 档解析（含垃圾输出→回退规则档）、多人消息 store 格式、前缀槽注入顺序（view→memory→skill→roundtable）。

### P3 空间智能——约 3~5 人日

- 动刀：话题模式站位编排（开题时各自走到舞台标记点——复用 `<move:锚点>` 全链）；发言切换的注视接力自动化；`GROUP_SHOT` 双人同框 + `follow` 目标参数化；`walkFinished` 带 avatarId 的续腿 relay 修正；可选：非焦点 runtime 降频。
- 验收：开题后两人走到对谈位（互相对着、用户在侧）；发言时对方看发言者、空闲回镜头；`camera_shot group` 双人同框稳定不漂（防自参照固化，坑 22 的多目标版）；走动中对话 TTS 不断流（P1 既有语义回归）。

### 总预估与顺序依赖

15~24 人日；P0 是其余全部的前置（渲染多实例是硬地基）；P1 与 P2 可并行度低（P2 依赖 P1 的路由/令牌）；P3 独立于 P2 后段可穿插。发布策略：P0+P1 合一版（「双人同屏各自对话」已是完整卖点），P2 一版（「虚拟人互聊」），P3 一版。

---

## 八、风险与开放问题

| # | 风险 | 定性 | 缓解 |
|---|---|---|---|
| 1 | Stage 手术回归面大：单虚拟人全部既有行为（口型/走动/拖拽/运镜/材质切换）都可能被拆分破坏 | 最大工程风险 | 兼容层保持旧 API 字节级语义；P0 验收即全量回归清单；手术分两步（先抽 runtime 后抽 stage）可回退 |
| 2 | 内存翻倍：双 VRM 纹理 + 双弹簧骨状态；低端机 OOM | 中 | 共享 VRMA 缓存；贴图按 MToon applier 已有 destroy 链路；v1 明确 2~3 人上限，超配降频/拒载 |
| 3 | 模型加载同步 ANR 被双人放大 | 中 | P0 顺手异步化；顺序加载+加载期禁新请求 |
| 4 | 虚拟人互聊失控（互捧死循环/复读/跑题） | 中（体验） | 导演三件套+LLM 档 END+重复检测；导演决策全量落日志可复盘 |
| 5 | 弱模型不守圆桌协议（对 B 说话当独白/抢话） | 中 | 协议靠前缀槽每轮强化（「现在轮到 B」是硬指令）；规则档保底轮转不依赖模型自觉；Provider 矩阵方法论复用（先 curl 测圆桌遵循度再选默认模型） |
| 6 | ASR 路由误判（无点名时给错人） | 低 | lastAddressed + UI 高亮当前对象；点名别名字表可编辑 |
| 7 | 双人同帧骨骼写入时序错误（commit 共享一次的纪律） | 低-中（实现细节） | 拆分时单测锁「N runtime → 1 commit」顺序；真机抽帧回归 |
| 8 | open 问题：>3 人是否要分层导演（分组）| 搁置 | v1 上限 3 人，字幕/注意力先于成本成为瓶颈 |
| 9 | open 问题：虚拟人间的「私下记忆」（A 知道 B 说过的话是否该进 A 的长期记忆） | 搁置 | v1：圆桌发言一视同仁进各自记忆提炼；如需「背地里的话」标记来源，P4 再议 |

---

## 九、相关工作（发言权调度的业界参照）

- **AutoGen**（Microsoft）：多智能体会话框架的事实标准，group chat 模式=manager 代理做发言权调度（轮转/LLM 选人两档）——与本方案导演制同构，规则档/LLM 档的接口划分即参照它。
- **DialogLab**（Hu et al., 2025, ACM）：人-AI 群聊的编排/仿真/测试框架，确认「moderator + turn-taking」是人机群对话的主流结构。
- **Generative Agents / Smallville**（Park et al., 2023）与 **AI Town**（Convex 开源）：25 个智能体小镇，重要性排序+反思的记忆架构（本项目记忆设计已对齐其公式），对话由「环境事件驱动谁和谁聊」——对应本方案导演的话题发起。
- **Agent Group Chat**（2024）：群聊模拟中用「发言者选择 + 评价者」双机制防对话崩坏——本方案导演的 END 收场与重复检测同源。
- **Character.AI / 星野等产品的群聊功能**：产品层面验证多人格同场对话的需求真实存在；其共性问题（抢话、失忆、人设漂移）即 §8 风险 4/5。
- 检索结论（2026-10）：尚无「Android 端侧渲染 + Serverless 多人格 + 导演调度」三合一的开源实现——本项目做这件事在开源生态里是空白位。

---

## 十、探索证据核心索引

- 单模型约束：ModelViewer 单 asset/animator（SoulLinkRenderer.kt:89-94 建整套 Filament 对象）；per-model 字段清空重建（:952-1206 loadModelBytes）；场景/模型/灯同 Scene（:1714-1725, :702）；无静态状态（全模块 object=纯函数）。
- 引擎可复用：六引擎只吃 Engine+entity（VrmaAnimationEngine.kt:101-109 bindToModel 存 rest pose）；LocomotionEngine 纯数学（LocomotionEngine.kt:32-33）；走动写 per-asset root（SoulLinkRenderer.kt:2337-2421）。
- 会话可多实例：AvatarSession.kt:97-145 构造全注入；AIAvatarSdk.kt:61 class/:217 单槽 session/:256 身份=:264 重建即 close 旧的；AudioTrackPlaybackQueue.kt:229-251 独立 AudioTrack；SpeechPipeline.kt:55 per 实例 Semaphore。
- 隔离与共享：ConversationDatabase.kt:29-40 sessionId 隔离；MemoryDatabase.kt:20-46 无 avatarId+单行窗口表（必改）；AvatarSession.kt:453-469 scope 僵尸 collector 教训；:1236-1244 逐轮前缀槽；:1300-1315 协议指纹缓存（可变清单禁入）。
- app 层：AiChat.kt:145-164 平铺 prefs/:340-353 ensure 链；MainActivity.kt:1737-1757 麦克风互斥/:993-995 技能单例/:1040-1253 单 slot 装配；SimpleDemoActivity 五步骨架；AiDebug.kt:24-80 命令解析。

---

## 十一、P0 实现记录（2026-10-13，已真机验收）

**落地形态与 §4.1 的偏差：平行 Stage，不做原位手术。** `SoulLinkRenderer`/`AvatarController`/`MainActivity` **零改动**（单人路径字节级不变，比「兼容层」红线更强）；新代码全部在 corelib 新包 `com.neethu.corelib.stage`：

- `internal/Stage.kt`：舞台引擎——ModelViewer 宿主 + 三灯 rig + 渲染设置 + 场景 GLB + 共享 MToon 材质库 + VRMA 只读缓存 + 统一帧泵（N 演员 preAnimation → 1 次共享 commit → postAnimation → skinAndPhysics，§8 风险 7 的纪律）+ 运镜（目标参数化）。
- `internal/AvatarRuntime.kt`：每演员一套——asset/animator + 六引擎 + hips 拖拽 + 走动状态机 + spawn + MToon applier；引擎本体零改动复用（§3.1 的可行性结论被逐项证实）。
- `internal/ModelPreprocessor.kt`：加载异步化的本体——morph 补丁/骨骼裁剪/MToon 描边复制移到 Default 线程，主线程只做 createAsset+绑定，纹理经 ResourceLoader 流式上传。
- `internal/VrmaClipCache.kt` + `internal/SpawnMath.kt`（纯 JVM，各带单测）；公开门面 `AvatarStage`/`AvatarHandle`/`AvatarSpec`/`AvatarStageView`（View+Compose 双形态）。
- app 新包 `multiavatar`：`MultiAvatarActivity`（导出，adb 直达）+ `STAGE_CMD` 广播调试通道（state/shot/load/expr/gaze/walk/cam/play…，真机验收主力手段）。

**§6 弹簧骨降频杠杆提前落地**：真机实测 10.vrm（61 条 center 空间弹簧链）单演员即 ~20ms/帧（dt 大时 substep 上探），双人在 SD855 上 15fps 且锁死低帧吸引子。P0 的解法是 **弹簧骨隔帧步进**（多演员交错、小步长半速率采样——Verlet 视觉无损）：`Stage.springStride` 多演员默认 2，单演员=1（与老路径逐位一致）。实测双人 **50~60fps**。⚠步长不乘 stride——累计大步长会把引擎 substeps 顶到 8 上限反而更慢。

**验收（Redmi K20 Pro / SD855 真机）**：同屏 10.vrm(MToon+VRM1.0) + 47058…vrm(MToon v0+VRM0.x，Y-180 翻转) + home.glb 场景 50-60fps；独立表情/视线/VRMA one-shot/走动互不干扰；`cam closeup A/B` 运镜锚定目标参数化；captureFrame 落盘 PNG；×5 force-stop 重启零 SIGABRT；VRMA 缓存生效（同源 idle+步态只解析一次）；MainActivity 单路径 60fps 无回归。737 个 JVM 单测全绿（新增 SpawnMath 8 例 + VrmaClipCache 4 例）。

**已知限制（记入后续期）**：重模型双人要靠 stride=2 才达 60fps（P3 的非焦点降频/LOD 仍值得做）；MToon LINEAR 色调映射是舞台级「任一演员 MToon 即 LINEAR」（per-演员分级留后续）；拖拽命中无拾取（P0 用 focused 演员，UI/命令切换）；多演员共享一台相机的仲裁归 P1 路由层。

---

## 十二、P1 实现记录（2026-10-13，已真机验收）

**落地形态：接口抽取 + 舞台编排门面，单人路径零行为变化。**

- **corelib 新接口 `AvatarActor`**（P1 的地基手术）：per-actor 操控的最小公共面（表情/VRMA/视线/呼吸/走动/walkFinished/机位读写），`AvatarController` 与 `AvatarHandle` 共同实现——orchestrator 的 FaceDriver/GestureDriver/LocomotionDriver/AvatarSession/AIAvatarSdk 只吃接口，同一套会话装配对单人/舞台两形态都成立。单人路径只是把既有方法标注 override + 默认参数值上移到接口声明（Kotlin 覆写不得重声明默认值，调用点经继承解析，源码零变化）；`AvatarHandle` 补齐 `getCameraLookAt/getActiveCameraShot`，`setCameraShot` 代理到舞台并锚定自己（LLM `<cam:>` 标签的语义=「给自己点机位」，多演员各发标签天然收敛到最后说话者，§4.8 的仲裁第一版）。
- **orchestrator 新 `roundtable/` 包**：`InputRouter`（§4.6 的 P1 两档——@token 点名大小写不敏感可剥前缀、自然语言称呼 contains 匹配先出现者赢、默认路由 lastAddressed；纯 JVM）；`RoundtableSession`（N session 编排：路由 send、发言令牌=最近 SentenceStarted、打断命中**全部非 IDLE** 会话〔正在说的+正在想的〕、倾听注视接力〔发言中其余人看发言者头部，回合完全员回看镜头，SentenceEnded 不回看防多句甩头〕、`updateSession` 重挂事件收集〔坑 41 的换绑纪律：先 cancel 旧收集者〕）；`StageEvent(slotId, event)` 包一层带 slotId 的合流事件（**不改 AvatarEvent**，§4.9 字幕分道的数据源）；`perSlotTtsBudget` 预算均分。
- **记忆 avatarId 维度（§3.2 必改项）**：`MemoryStore.scoped(avatarId)` 缝（接口默认返回自身=无维度 store 零感知）；InMemory 实现共享底座分片视图（分片内去重、分窗口按人）；Room 库 v1→v2 迁移——`agent_memories` 加 `avatarId` 列（存量归全局分片 `''`）、待提炼窗口从单行表改按 avatarId 一行（DROP 重建：攒批缓存最坏损失=一批重新提炼）；每 slot 的 session 注入 `RoomMemoryStore.from(ctx).scoped("stage_a")`，管理面板/ai_cmd 的全局库照旧看全部分片。
- **facade 两针**：`ChatConfig.ttsMaxConcurrent`（预算注入缝，舞台传均分值 2+2=全局 4）+ `ChatConfig.persona`（轻量人设并入**每轮恒带**的 identityPreamble——真机发现见下）。
- **app**：新 `StageChatRig`（每 slot 独立 SupervisorJob 子 scope〔§4.2 硬要求〕+ AIAvatarSdk facade + 记忆分片 + 锚点表注入 + StageGazeRelay 的舞台实现）；`StageSlotPrefs`（per-slot 只有名字/服务商/模型/音色/人设，Key 类共享一份钱，`stage_<slot>_` 前缀键）；MultiAvatarActivity 加聊天面板（字幕分道着色/输入路由回显 lastAddressed/打断/槽位配置行）；STAGE_CMD 扩聊天命令族 `chat|say|interrupt|chat_state|cfg|memory`。

**真机发现并修复的一个真问题**：doubao-mini 在 brief 轮（人设只随上下文首轮发送的既有钉住机制下）把首轮人设忘光、自称「我叫豆包」——轻量槽位人设要求「永不漂移」，修法是 `ChatConfig.persona` 走 identityPreamble 缝（每轮都在、逐字节稳定、前缀缓存不破坏），修后 B 稳定自称小刚。另一坑复现：`adb shell am broadcast --es cmd "chat auto hi"` 的引号被远程 shell 剥掉、空格拆参数导致命令静默失效——必须整条 `adb shell "am broadcast ... --es cmd 'chat auto hi'"`（记忆「ai_cmd 空格参数转义」的 STAGE_CMD 版）。

**验收（Redmi K20 Pro / SD855 真机，A=硅基 DeepSeek-V4-Flash/小柔，B=火山 doubao-seed-2-0-mini/小刚）**：双人 50-60fps + home.glb；`chat auto @A 你好` 只点亮 A（B 零请求），无点名自动落 lastAddressed（UI 回显「→ B(小刚)」）；分别问名字 A 答「小柔」B 答「小刚」（人设不串）；A 用 remember 工具落库（preference/imp=8）后 `memory A count`=1、`memory B count`=0，问 B「我喜欢喝什么」如实答不知道（检索隔离闭环）；打断命中非空闲会话、令牌释放全员回 IDLE；A 的 `<cam:close_up>` 经演员代理把舞台相机切到自己特写（shot=CLOSE_UP）；×3 force-stop 重启零崩溃、会话自动重配；单测 +38 全量 875 全绿（InputRouter 14 / RoundtableSession 9 / MemoryStoreScope 6 / facade persona 2 / 既有全量回归）。

**已知限制（记入后续期）**：虚拟人互聊与圆桌=P2 导演制（本期的 RoundtableSession 刻意无 Director）；语音三链路（按住/自由/ASR）未接舞台——路由器语音无关，接上即通（打字已闭环）；`AvatarHandle.id`（舞台顺延分配）与 slot 键（"A"/"B"）在 remove 后重载会错位——路由/配置/记忆全按 slot 键，仅诊断显示可能混名；表情标签驱动需要模型加载后 `startFaceDriving`（facade configure 的 modelReady 路径，rig 已接线）。

---

## 十三、P2 实现记录（2026-10-13，已真机验收）

**落地形态：导演进 RoundtableSession，单人/舞台既有语义零变化。** P1 刻意留白的位置（「本类刻意无 Director」）由 P2 填上：

- **`roundtable/ConversationDirector.kt`（新）**：`DirectorPolicy.Rule/Llm` 两档同一接口热切换。规则档=开题点名优先 → 插话点名优先 → 声明序 round-robin（排除上一发言者）→ 单槽连续上限；LLM 档=一次非流式轻量调用（便宜模型 + temperature 0.2 + 关思考，MemoryExtractor 同款收集法）输出 `{"next": id|USER|END, "reason"}`，解析复用 MemoryParser 的截首平衡 JSON；**任何失败（无配置/网络/垃圾输出/无效槽位/连续授予上一发言者）静默回退规则档**。防失控三件套是舞台层硬守卫、两档共同底线，不依赖模型自觉：轮数上限（决策前短路）+ 复读检测（DialogueGuards：归一化相等或 bigram Jaccard ≥0.6，<4 字符不判）连续 2 strike → END → 收场白（话题发起者 speak，不进历史）。
- **多人消息进上下文（§4.4 落地）**：每轮「新发言批」= 上一发言者回复 + 未回应插话；受命者经 `send()` 以 `【用户】/【名字】` 前缀行追加并生成，其余槽位经 `AvatarSession.injectUserMessage`（P2 新缝：入历史不生成）同步收听——自己的话在自己的历史里永远是 assistant 原文，不重复注入；任何人被授予发言权时上下文已齐。
- **逐轮发言权提示（§4.4 前缀槽）**：`AvatarSession.roundtableLine`（P2 第二针，@Volatile 可空）挂末尾 user 前缀槽 **viewLine→memoryLine→skillLine→roundtableLine**（离生成最近），REQUEST 日志加 `rt=Nch`；受命者「轮到你发言」、其余人「保持倾听」，回合收尾/停题/打断清空。null 时与既有形态逐字节一致。
- **RoundtableSession P2 状态机**：`RoundtableState.IDLE/ACTIVE/WAITING_USER` + `startRoundtable/stopRoundtable/setDirector` + directorLog（StateFlow，最近 8 条决策含 LLM reason）。插话三分支：发言中=排队（本轮结束进批）；点名=直授（§4.6 规则 1 优先于导演）；否则广播+导演裁决。TurnFailed=失败轮计入轮数、下一位以「继续」提示接力（批已分发无需补账）。打断同步清授予（迟到的 PlaybackInterrupted 靠 phase 检查忽略）；开题先 interrupt 在途发言防旧回合盖新话题。`InputRouter.Target` 加 `explicit`（@token/称呼命中 vs lastAddressed 兜底）；`StageSlotSpec` 加 `intro`（导演 LLM 花名册的人设简介）。
- **app**：StageChatRig 挂圆桌静态同场规则进 `ChatConfig.persona`（identityPreamble 缝，每轮恒带）+ 导演策略 prefs（`stage_director_mode/model/rounds`；LLM 档默认硅基流动 Qwen3.8-27B 关思考，缺 Key 静默落规则档）+ rebuildRoundtable 收敛为单一漏斗（先按在场名单重配每槽再建编排，圆桌前言依赖在场者名单）；MultiAvatarActivity 加 RoundtablePanel（话题输入/开题/停题/导演切换 + 最近一条导演决策 + ▶说话人高亮）；STAGE_CMD 扩 `topic/stop/director[/rounds]/director_log`。

**真机发现并修复的一个真缺口**：无点名且从无 lastAddressed 时插话被 InputRouter 的 Unroutable 拦截返回 null——话题模式下插话不该依赖「有人被点过名」，修为 Unroutable 时以原文作插话行直接交导演（`chat auto 插一句…` → 「插话已受理（导演安排接话人）」→ 下一轮带【用户】前缀进受命者请求）。

**验收（Redmi K20 Pro / SD855 真机，A=硅基 DeepSeek-V4-Flash/小柔，B=火山 doubao-seed-2-0-mini/小刚，导演 LLM 档=硅基 Qwen3.8-27B）**：「你们聊聊周末去哪玩」12 轮交替（回复实质不重样，人设语言风格分明）→ 12 轮上限触发收场白（speak 播放确认）；rounds=1 ×3 复跑收场白 3/3 稳定；话题中途 `director llm` 热切换，决策 reason 全部落日志（「小刚补充工程师视角的省心细节」「用户追问细节，小柔活泼适合接话」）；B 发言中插话排队 → 下一轮批带【用户】行；LlmPrompt 核对：`rt=37ch` 每轮恒在、首轮 `sys=full` 后恒 `brief`（钉住协议未打穿）、cached=1280~1536；全程问名 A 答「我叫小柔」B 答「我叫小刚，沉稳踏实的工程师」（人设不漂移）；stop 停题 + ×2 force-stop 重启零崩溃；StagePanel 截图核对（字幕分道着色/▶ 高亮/导演日志行）。单测 +22 全量 1690 全绿（Director 全分支 10 / 圆桌流程 9 / 前缀槽+inject 3，含插话无点名回归）。

**已知限制（记入后续期）**：语音三链路仍未接舞台（P1 遗留，打字/adb 已闭环）；LLM 档导演默认绑硅基流动一家（prefs 可换模型，不换商）；「交回用户」后无 UI 提示位（仅导演日志/字幕可见，state=WAITING_USER）；圆桌发言进记忆提炼走既有窗口节奏（批量提炼在话题收场后数轮才触发，属预期）；`<cam:>` 标签多人并发仍是 P1 的「最后说话者赢」仲裁。

---
