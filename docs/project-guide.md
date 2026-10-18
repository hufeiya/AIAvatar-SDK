# AIAvatar-SDK 项目上手指南

> 面向新接手本仓库的维护者（或隔了几个月回来自已）。目标：**30 分钟建立全貌，半天能动手改代码**。
> 基线：commit `a89ac06`（SDK 0.1.4 / APK v1.2），2026-10-11 整理。
> 配套深读材料：`docs/ai-layer-handoff.md`（AI 层逐日攻坚史+坑清单，**改 AI 链路前必读**）、`docs/springbone-debug-context.md`（弹簧骨骼）、`docs/ai-debug-intents.md`（ai_cmd 命令手册）。

---

## 0. 这是一个什么项目

开源 **Android 3D 虚拟人 SDK + 全功能 Demo**：Filament 渲染 VRM 模型，纯客户端（Serverless）AI 对话——Android 端直连公网 OpenAI 兼容 API（LLM + TTS），口型、表情、视线、呼吸、眨眼、运镜全部在端侧驱动。**没有自建服务端**。

核心卖点：

1. **免费开箱即说**：Edge-TTS（免 Key）+ 系统内置 ASR（免 Key），一个 Key 都不填也能语音对话。
2. **多模态行内标签协议**：LLM 单流输出 `<emo:joy>` `<act:wave>` `<cam:closeup>`，同时驱动文字/表情/动作/镜头。
3. **渲染对标 three-vrm**：MToon 卡通管线与 three.js + three-vrm 参考渲染逐像素对齐（RMSE 0.53/255）。
4. **拟人微动作全端侧**：呼吸（说话加快）、眼球 saccade、注视镜头、口型同步、52 blendshape 表情。
5. **可扩展技能框架**：猜拳 / 看这边 / 模仿我，端侧感知（MediaPipe）+ 端侧判定，LLM 只负责说话。

当前发布状态：三个库模块已上 Maven Central（`io.github.hufeiya:corelib/avatar-orchestrator/avatar-ai-adapter:0.1.4`），APK v1.2 在 GitHub Release。

---

## 1. 五分钟看懂架构

### 1.1 四个 Gradle 模块（依赖只能向下）

```
:app  (com.neethu.aiavatar_sdk, demo)
 │
 ├──> :avatar-orchestrator (com.neethu.orchestrator, 会话编排层)
 │       │                  AIAvatarSdk 门面 / AvatarSession / 表情仲裁 / 技能 / Room 历史
 │       └──> :avatar-ai-adapter (com.neethu.aiadapter, 纯 JVM)
 │                            LlmAdapter / TtsAdapter / AsrAdapter 接口 + 四类服务商实现
 │
 └──> :corelib (com.neethu.corelib, Filament 渲染底座)
                AvatarController / AvatarView / AvatarSurfaceView / SoulLinkRenderer(internal)
```

两条铁律：**adapter 不感知渲染、corelib 不感知 AI**；依赖方向只允许向下。心智模型——corelib 是「身体」，adapter 是「耳朵和嘴」，orchestrator 是「小脑」（把嘴听到的话变成身体动作），app 是「装修好的样板间」。

| 模块 | 性质 | 大小 | 对外门面 |
|---|---|---|---|
| corelib | Android library，Filament 1.68.3 四件套 | ~11.4k 行 | `AvatarController`（唯一入口）+ 双 View |
| avatar-ai-adapter | **纯 JVM**（Java 11，可 CI 跑 MockWebServer） | ~3.2k 行 | 四个接口 + 实现 |
| avatar-orchestrator | Android library（Room 依赖），依赖 adapter+corelib | ~6.7k 行 | `AIAvatarSdk` 高阶门面 / `AvatarSession` 低阶会话 |
| app | application（Compose），依赖全部 | ~12k 行 | `MainActivity`（全功能）/ `SimpleDemoActivity`（最小接入 ~200 行） |

### 1.2 一次对话的完整旅程（说话链路）

```
用户输入(打字/按住说话/自由说话VAD/系统ASR/adb ai_cmd)
   │  app: 四条路径统一先 pushUserLine → skills.onUtterance(可能被技能消费,不发给LLM)
   ▼
AIAvatarSdk.send ──► AvatarSession.send (orchestrator/session/AvatarSession.kt:387)
   │  组请求: system单条(身份前言+人设+协议目录,首轮full/其余brief) + Room历史(最近40条) + 末尾user前缀(视角行+技能指令)
   ▼
OpenAiCompatibleLlmAdapter.streamChat (adapter, SSE 流式)
   │  每个 delta ──► InlineTagExtractor.feed 剥出 <emo:/<act:/<cam: 标签 + 净文本
   │                    │(cue 先入队,挂到下一句 sequence 上)
   ▼
SentenceChunker.feed 断句(硬标点切+软标点 boost,~AIRI 规则)
   ▼
SpeechPipeline.submit (ttsMaxConcurrent=4, Semaphore; 每句一个协程)
   │  tts.synthesize(Edge-TTS MP3 / 火山 WS PCM / OpenAI 兼容 wav)
   │  PcmDecoder.decode + WlipsyncLipSyncProcessor.analyze   ← 在 heavyWorkDispatcher(默认 Dispatchers.Default)
   │  scheduleOrdered: 按 sequence 严格有序入 AudioTrackPlaybackQueue,失败句跳过不卡队
   ▼
AudioTrackPlaybackQueue 播放(专用 avatar-playback 写线程,WRITE_BLOCKING)
   │  回调: Started → 应用挂接的表情(EmotionChanged) + phase=SPEAKING;  Ended → SentenceEnded
   ▼
FaceDriver.tick (Choreographer 逐帧): 口型viseme(VowelDriver 采样时间线) → 情绪(EmotionBlender 13种)
   → 眨眼(MicroMotionEngine) → 视线(SaccadeEngine→corelib) → 合并 controller.setExpression
   ▼
corelib 渲染循环消费: 表情 morph 权重 → VrmExpressionManager.setMorphWeights → 屏幕上的人开口
```

关键点：**TTS 是句级流水**（LLM 第一句出来就开始合成第二句，句间无缝）；**口型是离线时间线**（TTS 解码后一次性 MFCC 分析出 viseme 时间轴，播放时按 AudioTrack 时钟采样）；**表情挂句开播**（`<emo:>` 标签在 LLM 吐出瞬间入队、下一句音频开播瞬间才应用，防止 TTS 排队期间被 3s 自动归零吃掉）。

### 1.3 每帧渲染管线（corelib 渲染循环，顺序敏感）

`SoulLinkRenderer.choreoCallback`（internal/SoulLinkRenderer.kt:306）每帧按此顺序执行：

| # | 步骤 | 实现 | 说明 |
|---|---|---|---|
| 1 | 动画前快照 | `capturePreAnimation()` | 抓 hips 平移+呼吸/视线骨骼局部，供「先剥再叠」判定动画是否重写 |
| 2 | 动画 | VRMA 优先 / 内置 glTF 动画 | VRMA 播完自动回 idle；idle 冷启动即接管 |
| 3 | hips 拖拽 | `applyHipsDragOffset` | 拖拽位移以「动画刚写出的平移」为基线重钉（HipsDragOffsetSolver 双信号） |
| 3.5 | 走动根位移 | `updateLocomotion`（LocomotionEngine） | 写 asset root 平移+绕 Y 旋转（与骨骼局部写入正交）；到达切回 idle |
| 4 | 呼吸 | VrmBreathEngine | 脊柱三段+双肩 ~4.7°，说话 ×1.15 频率 ×0.6 幅度 |
| 5 | 视线 | VrmLookAtEngine | 头颈 35/65 平滑，眼睛带未平滑余量（眼先动头后追） |
| 6 | 动作模仿 | VrmPoseMimicEngine | 方向绝对驱动（无 IK），后写者赢（mimic 激活期间走动被拒绝） |
| 7 | 显式提交 | `commitLocalTransformTransaction` + `updateBoneMatrices` | 惰性求值必须显式提交，否则头部抽搐 |
| 8 | 表情 morph | VrmExpressionManager | 各激活表达式按权重累加 → setMorphWeights |
| 9 | 弹簧骨骼 | VrmSpringBoneManager | Verlet 积分，动画后渲染前 |
| 10 | 运镜/上屏/渲染 | CameraShot steering / render | 运镜 7 镜位（follow=跟拍，闭环持续跟随不「到位」） |

**每一层骨骼写入都遵循同一范式**：「先剥再叠」——叠加层如果发现骨骼局部仍等于上次写入值（未被动画重写），先剥掉旧偏移再叠新的；被动画重写则全量写。纯函数 `BreathBaseResolver` / `HipsDragOffsetSolver` / `VrmLookAtEngine.stripPreviousWrite` 都有单测锁死。**改任何骨骼写入层之前先理解这个范式**，否则会复现「锁死/累积/抽搐」三类已踩过的坑。

---

## 2. 模块详解与源码地图

### 2.1 corelib —— 渲染底座

**公开 API 全在 `com.neethu.corelib` 根包**，internal 全在 `internal`：

| 公开类 | 作用 |
|---|---|
| `AvatarController`（AvatarController.kt:42） | SDK 唯一门面。`state/fps/detectedRenderMode` 状态流；`loadModel(.vrm/.glb)`/`loadModelFromFile`；动画 `playVrmaAnimation`/`setVrmaIdleAnimation`；表情 `setExpression`/`getAvailableExpressions`；视线 `setLookAtTarget`；模仿 `setMimicPose`；呼吸 `setBreathEnabled`；场景 `loadScene`；设置 `updateRenderSettings`；运镜 `setCameraShot`/`orbitCamera`/`resetCamera`；截帧 `captureFrame` |
| `AvatarView` / `AvatarSurfaceView` | Compose / 传统 View 双入口（后者生命周期自动绑定 Activity） |
| `AvatarConfig` | 模型+IBL 配置；`AvatarConfig()` 内置默认环境光零资产即亮，`IBL_NONE` 显式关 |
| `AvatarRenderSettings` | 15 项渲染设置（IBL 强度/旋转、三档灯光布、阴影图尺寸/PCSS/接触阴影、AO、色调映射、Bloom、AA、DoF、材质增强、渲染风格） |
| `CameraShot` | 6 个预设机位（特写/中景/全景/远景/环绕/微距 MACRO） |
| `Lang` | ZH/EN 语言枚举，全链路唯一语言标识 |

**加载管线** `SoulLinkRenderer.loadModelBytes`（:872）——GLB 字节级三连预处理后交给 gltfio：

1. `GlbMorphPatcher`：注入 morph 默认权重 + 剥 KHR_materials_unlit（修 gltfio 不上传 morph 法线）；
2. `GlbBoneCuller`：蒙皮关节数压到 Filament 256 骨上限内；
3. `GlbMToon`：解析 MToon 材质并复制 outline primitive（轮廓第二 pass 零逐帧成本）；`detectRenderMode` 按材质自动判定 PBR/MTOON；
4. 加载 → VRM 0.x 根节点 180° Y 翻转（统一面向 +Z）→ 依次建 VRMA 引擎、表情管理器、弹簧骨骼 → 绑定视线/呼吸/模仿三引擎 → IBL → MToon 材质替换。

**MToon 卡通渲染**（`MToonMaterialFactory`/`MToonApplier`）：filamat **运行时编译** 7 个材质变体（表面×3 混合 + 轮廓×3 + hidden），shader 逐行移植 three-vrm 的 mtoon.frag。MToon 不吃场景灯，以 key 灯方向作材质参数；激活时强制 LINEAR 色调映射。

**灯光 rig**：三灯**全用 SPOT**（方向光在本项目的相机变换下方向错乱，实测只能走 punctual 路径）；key 灯带阴影，仅在阴影签名变化时重建。

**表情解析** `VrmExpressionManager.parseExpressions`（纯 JSON，JVM 可测）：统一 VRM 0.x/1.0 两代规范，**0.x preset 名归一**（a→aa、i→ih、u→ou、e→ee、o→oh、blink_l→blinkLeft…，不归一就是「说话不动嘴」的著名坑），joy→happy 等 canonical 别名只在缺位时补。绑定权重按最强 bind 归一到 1.0。

**Filament 1.68.3 硬约束**（代码注释里有完整记录）：
- `TransformManager.getParent()` 返回的是 **entity 不是 instance**，必须 getInstance 再用，混用即 SIGSEGV（弹簧骨骼当年真机元凶）；
- `Engine.destroyMaterialInstance` 对仍被 renderable 持有的实例直接 abort——必须**先 destroyModel 再销材质**（MToon 切角色必崩的根因）；
- gltfio 蒙皮 mesh 的剔除盒是绑定姿态静态盒 → 角色 renderable **剔除默认关闭**（「眼球丢失」根因）；
- PCSS 软阴影在 Adreno 上 1-5 FPS，默认关。

### 2.2 avatar-ai-adapter —— AI 服务商适配（纯 JVM）

| 接口（api/） | 签名 | 实现 |
|---|---|---|
| `LlmAdapter` | `streamChat(messages, config): Flow<LlmStreamEvent>` | `OpenAiCompatibleLlmAdapter`——任意 OpenAI 兼容端点，OkHttp SSE 手解 `data:` 行，多模态走 content 数组 |
| `TtsAdapter` | `suspend synthesize(text, config): TtsResult` | ① `OpenAiCompatibleTtsAdapter`（硅基流动/OpenRouter）② `EdgeTtsAdapter`（**免 Key**，WSS+Sec-MS-GEC DRM token，恒 MP3 24kHz）③ `VolcanoEngineTtsAdapter`（V3 双向流 WS，`X-Api-Key`+`X-Api-Resource-Id: seed-tts-2.0`，恒 PCM 16k） |
| `AsrAdapter` | `suspend transcribe(audio, mime, config): String` | `OpenAiCompatibleAsrAdapter`（multipart 上传 /audio/transcriptions） |
| `LipSyncProcessor` | `analyze(pcm16, sampleRate): VisemeTimeline` | `WlipsyncLipSyncProcessor`——wLipSync/uLipSync MFCC 管线精确移植（Dsp/MfccFrontend/WlipsyncProfile），profile.json 直接复用 AIRI |
| `TagExtractor` | `feed(delta)/flush()` | `InlineTagExtractor`——流式剥 `<emo:名:强度>/<act:名[:强度]>/<cam:机位[:强度]>` + 老协议 `<|emotion:..|>`，跨 delta 缓冲半截标签，名字收 Unicode（中文仿写标签不漏进 TTS；cue 带 raw 原文段，未知名由 orchestrator 过滤并发 UnknownTag 事件） |
| `ToolCallAccumulator` | `feed(deltaTools)/complete()` | 流式 `delta.tool_calls` 累积器（P0）：多 index 并行槽；后续分片 id=null/name=""/字段省略**都当缺失**（硅基流动显式发 null、火山/OpenRouter 省略，实测矩阵 docs/agent-provider-matrix.md）；`isToolRelatedError`（ToolErrors.kt）=「端点不支持工具」降级正则 |

要点：
- **TTS 是句级整段契约**（V1）：每句新建一条 WS/HTTP（结构性 ~1s 延迟），流式 TTS 留作远期。
- **SiliconFlowVoiceCatalog**：在线音色清单，**永不抛异常**（失败恒空列表，UI 合并静态兜底）。
- 火山是**两把钥匙**：方舟 Ark（LLM）与豆包语音控制台（TTS）互不通用，分字段存储。
- Edge-TTS 无 SLA：`CHROMIUM_FULL_VERSION` 等常量需跟随 python edge-tts 上游更新，长期 403 先对齐这里。

### 2.3 avatar-orchestrator —— 会话编排

**高阶门面 `AIAvatarSdk`**（facade/AIAvatarSdk.kt:58）：接入方看到的一切。`configure(ChatConfig, modelReady)` 一条完成 adapter 工厂+配置解析+会话重建；`send/speak/interrupt/close`；`events: Flow<AvatarEvent>`/`phase: StateFlow<ConversationPhase>` 经 flatMapLatest 自动接新会话；卡片跨重建自动重放。`ChatConfig` 15 个字段**全量相等即会话身份**，任何变化下次 configure 自动重建。服务商目录（端点/模型清单/默认值）单一事实源在 `facade/ChatProviders.kt`。

**低阶会话 `AvatarSession`**（session/AvatarSession.kt:80，927 行，核心编排器）：上面说话链路的发生地。`interrupt()` 三层打断 = 取消 LLM 流 + pipeline.cancelTurn（杀在途 TTS）+ queue.stopAll（即刻静音）。14 种 `AvatarEvent`（SentenceQueued/Started/Ended/Failed、EmotionChanged、ActionStarted、CameraChanged、TurnCompleted/Failed、PlaybackInterrupted、SkillEvent、UnknownTag、P0 工具三件套 ToolCallStarted/ToolCallsIssued/ToolCallFinished）。**P0 工具循环**：`Finish.toolCalls` 非空 → 顺序执行工具（`tools: ToolRegistry` 公开注册表，未知名/坏 JSON/超时/异常全转 isError 回灌不炸回合）→ assistant(tool_calls)+tool 结果回灌 → 再请求，最多 3 轮；文本增量照旧进断句→TTS 流水（跨轮连续）；工具消息不落库（store 仍只存 user+全量 raw，裁剪/Room 零改动）；工具相关 400 自动降级并按 modelKey 会话内禁用；工具 systemHint 聚合进钉住协议块（同一条 system）。

**表情仲裁 `FaceDriver`**（face/FaceDriver.kt:44）：Choreographer 逐帧混合五通道（口型→情绪→眨眼→视线→模仿表情）。所有权规则：说话中口型独占嘴部（viseme 与情绪的嘴部 morph 取 max）；模仿表情激活时情绪整体让位、说话时嘴部让位口型、用户真实眨眼替代自动眨眼；send 有门槛——名字必须在模型实有 morph 集合内，静止只写一次 0，防零风暴擦掉手动表情。

**`EmotionBlender`**（face/EmotionBlender.kt:26，AIRI 移植）：13 种 canonical 情绪（happy/sad/angry/surprised/neutral/think/relaxed/smug/shy/worried/confused/sleepy/determined），每种是多 morph 组合；缺 morph 逐条降级；默认 3s 自动回 neutral，`hold=-1` 手动表情永不重置。`EmotionAliases`：约 60 个中文情绪仿写（轻笑→happy）兜底。

**技能框架**（skill/，纯 JVM）：
- 契约 `AvatarSkill`：`id/isActive/turnDirective`（激活时注入每轮请求的指令行）+ 8 个事件缝（onUtterance 返回 true=消费该句不发 LLM、onVadUtterance ASR 前快路径、onUserGesture/onHeadPose/onBodyTracking 相机感知、onTurnCompleted/onInterrupted/onExit/debugCommand）。
- 能力缝 `SkillHost`：playGestureFile（技能专用动作通道，不经 LLM 目录）/speak/sendTurn/snapshotImage/setVadHangover 等，app 侧 `DemoSkillHost` 全懒 provider 实现。
- `SkillRegistry`：事件全广播；**单活跃收口**——一句话激活两个技能时后到者胜、先者 onExit。P0 工具缝：`AvatarSkill.tools()`（默认空）注册进 session 的 ToolRegistry，挂/摘跟随激活态（转激活挂上、退场/被顶替/反注册摘除）。
- 三个技能的设计原则：**判定在端侧、LLM 只当嘴替**（一局 2-3s，LLM 往返跟不上）：
  - `RpsSkill` 猜拳：本地 RNG 出拳 + MediaPipe 手势本地判定（P2）即时宣判，VLM 看图裁判（P0）降级为气氛组；五态状态机 IDLE→INVITED→ARMED→THROWN→JUDGING。
  - `LookHereSkill` 看这边：虚拟人随机指方向、用户反向转头，「呆住判负」；`LookHereJudge` 按会话中性位的绝对方向判定，符号标定常量在 `LookHereTuning` 两布尔。
  - `MimicSkill` 模仿我：姿态数据 app 车道直驱渲染引擎（绕过 orchestrator/LLM），技能只管生命周期与可见性降级（看不到人 2s 催促、8s 退场）。

**提示词系统**（card/）：`CharacterCardParser` 支持 SillyTavern V1/V2/V3 JSON 与 PNG 内嵌 ccv3/chara chunk；`SystemPromptAssembler` 按 AIRI 顺序拼 `[systemPrompt, description, personality, scenario]`。**system 恒单条**（硅基流动对两条 system 直接 400）：完整形态=【身份前言】【人设全文】【协议目录】只在上下文首轮/重发轮发，精简形态=只有身份前言；身份块只在 LLM 流**成功走完**后提交（失败/打断自动重发）；视角行与技能指令挂在末尾 user 消息前缀（请求里唯一逐轮变化的部分，保前缀缓存命中）。

**历史存储**（history/）：Room `avatar_conversations.db`（Session+Message 两表）；读路径=内存镜像（事实源），写=单线程 IO scope 串行落库；`recentTurnLimit=40` 只裁请求不裁存储。

**i18n**（i18n/）：四份双语目录（PromptTexts/RpsTexts/LookHereTexts/MimicTexts），同构「interface + Zh/En object + of(lang)」；单测断言 EN 无中文字符防漂移。技能激活/退出词表中英合并、刻意不随语言切。

### 2.4 app —— Demo 应用

**`MainActivity`**（3744 行，巨石文件）：纯 Compose 单 Activity。结构=`DemoScreen` 一个巨型 composable 铺满 `AvatarView` + 底部 `AiChatBar`（字幕/输入/按住说话/自由聆听）+ 右下 FAB 列拉起 8 个底部面板（挪动/视角/设置/场景/表情/卡片/动画/模型）。状态集中 `DemoUiState`，SharedPreferences 持久化（单一 `demo_settings` 文件）。会话身份=配置+上下文+语言+模型 Ready，800ms 防抖后 `AiChatController.ensure` 装配。

**输入四模式**（InputMode）：MANUAL（全 UI）/ TEXT / VOICE / VIDEO（后两者按钮自动隐藏）；`show_buttons` 临时翻转显隐。语音三形态：
- 按住说话 `VoiceInput`：MediaRecorder AAC → cacheDir → 云端 ASR；
- 自由说话 `FreeSpeech`：AudioRecord 16k 采音 + `SpeechVad` 软件端点检测（噪声地板自适应/起音确认 60ms/悬停 800ms 切句/barge-in 三重门打断）→ WAV → 同一 ASR 链路；
- 系统内置 ASR `SystemAsr`：平台 SpeechRecognizer 免 Key（国产机走厂商服务如 mibrain），Android 11+ manifest `<queries>` 坑、可用性三重判定、授权弹窗期不销毁会话。

**视频模式**（video/）：CameraX 480×640 + 四条 MediaPipe/ML Kit 车道**互斥串行**在单线程 executor（懒建、关=零开销）：
| 车道 | 模型 | 消费方 |
|---|---|---|
| 人脸检测（注视） | ML Kit BlazeFace | FacePointProjector → 世界注视点 |
| 手势识别 | MediaPipe GestureRecognizer（bundled 8.4MB） | 猜拳 P2 本地判定 |
| 头部姿态 | MediaPipe FaceLandmarker（3.7MB） | 看这边判定 + 模仿表情（52 blendshapes 独立实例） |
| 身体姿态 | MediaPipe PoseLandmarker（5.8MB） | 模仿我（PoseMimicMath 镜像解算 → corelib 引擎） |

每轮请求自动附 512×512 最清晰抓拍帧（3 帧环形缓冲）；PiP 小窗可拖动/前后摄切换；进模式自动面部特写、回合结束自动回特写。

**调试工具链 `ai_cmd`**（AiDebug/AiDebugExecutor，~50 条命令）：adb 经 onNewIntent 驱动与 UI 相同代码路径，结果打 logcat `AIDebug`：
```bash
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd state
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd load_model --es ai_arg 10.vrm
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd send_chat --es ai_arg 你好
```
完整命令表 `adb ... --es ai_cmd help`，文档 `docs/ai-debug-intents.md`。**这是真机验证的主力手段**（MIUI 禁 shell 触摸注入，点 UI 不可靠）。

**资产**（app/src/main/assets，~65MB）：
- `animations/`：9 类 ~340 个 VRMA 动画（Mixamo 转换）+ 11_技能_猜拳 + 12_技能_看这边（技能专用目录，从 LLM 动作目录排除）；
- `cards/`：18 张预置人物卡（5 张 SillyTavern 官方 + 13 张自创中文卡，EN 翻译在 `PresetCardsEn.kt`）；
- `vrms/`：3 个内置模型（10.vrm 全 MToon / 4705…vrm 是 VRM 0.x / Asuna）；
- `gesture_recognizer.task` / `face_landmarker.task` / `pose_landmarker.task`：MediaPipe bundled 模型 ~17MB（无 GMS 依赖，国产机可跑）；
- `scene/home.glb`、`guide/`（引导截图）、`default_env.ktx`（app 内还有一份；corelib assets 的 `corelib/default_env.ktx` 是 SDK 内置 IBL）。

---

## 3. 功能项清单与实现方式

> 「入口」列 = 用户从哪里触达；「实现」列 = 改它要动哪里。

### 3.1 渲染与形象

| 功能 | 入口 | 实现 | 关键文件 |
|---|---|---|---|
| VRM/GLB 模型加载（内置+导入） | 模型面板 / `load_model` / `import_model` | GLB 三连预处理→gltfio→子系统绑定；导入走 SAF→GLB magic 校验→filesDir/vrms→自动选中+新建上下文 | `SoulLinkRenderer.loadModelBytes`、`ImportedModelLibrary` |
| PBR 写实渲染 | 默认 | gltfio ubershader + IBL | SoulLinkRenderer |
| MToon 卡通渲染 | 设置→渲染风格 / `render_mode` | 按材质**自动判定**（有 MToon 材质默认 MTOON，手动覆盖仅当前模型）；filamat 运行时编译 7 材质+outline primitive 复制 | `GlbMToon`/`MToonMaterialFactory`/`MToonApplier` |
| 弹簧骨骼物理（头发/衣物摆动） | 默认开，`spring on/off` | three-vrm 移植，Verlet 积分 center 空间，球/胶囊碰撞体 | `VrmSpringBoneManager` |
| 拖拽/挪动人物 | 挪动 FAB / `set_drag_mode` | hips 平移拖拽，双信号基线防漂移 | `HipsDragOffsetSolver` |
| 程序化运镜 | 视角 FAB / `<cam:>` 标签 / `camera_shot` | 6 镜位+steering 平滑过渡；手势 orbit/pan/zoom | `CameraShot`、Manipulator 自建 |
| 场景加载 | 场景面板 / `load_scene`（none=纯色背景） | 独立 loader；无场景=满帧率；可选对位 meta（`<场景名>.meta.json`，Y 旋转+平移） | SoulLinkRenderer |
| 自由走动 | `walk_to` / SDK `startWalk` / LLM `<move:锚点>` / `move_to` 工具 / `walk_strafe` | 步态驱动（方案 C 优先：带烘焙位移素材由 `LocomotionExtractor` 剥离 hips 前向分量回灌 root，脚步与位移按构造同步，baseSpeed 自动标定；原地素材回退程序位移）；双槽 crossfade（起步/停步/one-shot→idle 淡入淡出，`walk_fade`）；走动中手势 overlay（上半身 mask 边走边比划，`walk_overlay`）；跑步/侧移步态（speed≥1.9 自动跑步，`walk_strafe` 朝向锁死横移）；waypoint 图（`home.anchors.json` links+BFS 多段腿）；接近段提前制动减速；`camera_shot follow` 跟拍+静态机位角色逼近自护 | `LocomotionEngine`、`LocomotionExtractor`、`Locomotion.kt`、`WaypointRouter`、`locomotion/`（orchestrator）、`tools/walk-calib` |
| 画质 15 项设置 | 设置→画质 | 大多热插拔；renderMode 切换触发模型重载 | `AvatarRenderSettings`、`QualityPreset` 四档 |

### 3.2 对话与 AI

| 功能 | 入口 | 实现 | 关键文件 |
|---|---|---|---|
| 流式对话（LLM） | 输入框 / `send_chat` | OpenAI 兼容 SSE；四家服务商目录（硅基流动/火山/OpenRouter/DeepSeek）+ 自定义 baseUrl；关思考参数按模型方言分发 | `OpenAiCompatibleLlmAdapter`、`AiProviders`、`ChatProviders` |
| 逐句 TTS 流水 | 自动 | 断句→并发合成（≤4）→严格有序播放 | `SentenceChunker`、`SpeechPipeline`、`AudioTrackPlaybackQueue` |
| 免费语音合成 | 设置→TTS 引擎=Edge-TTS | WSS+Sec-MS-GEC，恒 MP3 24kHz | `EdgeTtsAdapter` |
| 口型同步 | 自动 | 离线 MFCC viseme 时间线+VowelDriver 五元音平滑，AudioTrack 时钟采样 | `WlipsyncLipSyncProcessor`、`VowelDriver`、FaceDriver |
| 情绪表情 | `<emo:joy>` 标签 / 表情面板 / `set_expression` | 13 canonical 情绪组合+未知名单 morph 降级+中文别名兜底；挂句开播；3s 自动回落 | `EmotionBlender`、`FaceDriver`、`EmotionAliases` |
| LLM 动作 | `<act:wave>` 标签 / 动画面板 | 340+ 动画全目录注入协议块（首轮）；单动画槽 last-cue-wins；播完回 idle | `GestureDriver`、`ActCatalog`、`LlmActionCatalog` |
| LLM 运镜 | `<cam:closeup>` 标签 / 视角行注入 | 当轮机位写进 user 前缀，LLM 知道取景；开关 `ai_llm_camera` | AvatarSession、CameraShot |
| 视频多模态 | 视频模式每轮自动附抓拍 | data URL 数组形态（仅当轮 user 消息，历史不带图）；非视觉模型有门控 | `OpenAiCompatibleLlmAdapter`、`UserCameraTracker` |
| 人物卡 | 卡片面板 / `import_card` | SillyTavern V1/V2/V3 PNG+JSON 解析；18 预置卡 grid；人设提示词可编辑覆写 | `CharacterCardParser`、`PresetCards*`、`CardLibrary` |
| 多上下文历史 | 设置→上下文 / `new_context` | Room 持久化；请求带最近 40 条；换 LLM 模型/换 VRM 模型自动轮换上下文 | `RoomConversationStore`、`llmIdentitySignature` |
| 中英双语 | 设置→语言 | Lang 枚举贯穿；提示词四目录+UI Strings+预置卡翻译；语言变化重建会话不轮换上下文 | `i18n/` 四目录、`Strings.kt` |
| 长期记忆 | 设置→长期记忆 / `memory_list` 等 5 命令 | 提炼式记忆（docs/memory-feasibility.md）：remember/recall/forget 三工具 + 攒批自动提炼（5 轮或 600 字双阈值、12 字静默门、窗口持久化跨会话）+ 检索注入末尾 user 槽（`1.2×cosine+0.2×新鲜度`，身份/关系/偏好豁免衰减 + 重要度≥8 常驻槽）；bge-m3 向量主路（硅基流动 Key 复用 ASR）→ 2-gram 关键词降级；Room `agent_memory.db` 跨上下文共享；管理面板列表/搜索/删除/清空 | `memory/`（MemoryStore/Retriever/Extractor/Coordinator/Tools）、`OpenAiCompatibleEmbeddingAdapter`、`MemoryPanel` |
| 智能体工具调用 | 自动（模型请求）/ `tools_call` `observe` | 回合内工具循环（≤3 轮）；内置 get_device_time/get_current_view/observe_camera + P0.5 设备六件（get_device_status/set_flashlight/send_haptic/read_clipboard〔独立开关默认关〕/set_ambience/take_photo）；工具失败转 isError 回灌；400 自动降级禁用；Qwen3.8 关思考的悬空衔接句→required 静默重试+模板文本解析兜底（⚠ 视觉+工具选型优先 Qwen3-VL-32B/doubao，见矩阵可靠性节） | `agent/ToolRegistry`、`AgentToolCatalog`、`TemplateToolCalls`、`AgentDeviceTools`、`OpenAiCompatibleLlmAdapter` |
| 新手引导 | 未配 Key 冷启动自动弹 | 双受众（CN=硅基流动 / INTL=OpenRouter free+Edge-TTS+系统 ASR），一键配置，点输入框重弹 | `OnboardingGuide` |

### 3.3 语音输入与感知

| 功能 | 入口 | 实现 | 关键文件 |
|---|---|---|---|
| 按住说话 | 长按话筒 | MediaRecorder m4a→云端 ASR；半双工（按下先打断） | `VoiceInput` |
| 自由说话（VAD 连续聆听） | 聊天条药丸 / `voice_free` | 软件端点检测+barge-in 打断+280ms 前滚防削头；灵敏度三项可调 | `FreeSpeech.kt`、`SpeechVad` |
| 系统内置 ASR | 设置→识别引擎 | 平台 SpeechRecognizer 免 Key；重启策略/半双工暂停/授权轮询三件套 | `SystemAsr.kt` |
| 视频模式注视 | 视频模式 | ML Kit 人脸→One-Euro 滤波→世界注视点；人脸离开 1.5s 回退看镜头 | `FacePointProjector` |
| 表情跟随 | 视频模式+模仿表情开关 | FaceLandmarker 52 blendshapes→MimicFaceMapper→FaceDriver mimic 通道 | `MimicFaceMapper` |
| 动作模仿 | 「模仿我」 | PoseLandmarker 车道→镜像解算→绝对方向驱动头颈躯干双臂；与表情/呼吸/口型有完整让位仲裁 | `PoseMimicMath`、`VrmPoseMimicEngine` |

### 3.4 技能（小游戏）

| 技能 | 激活词 | 判定 | LLM 角色 |
|---|---|---|---|
| 猜拳 | 「玩猜拳/石头剪刀布…」 | P2=MediaPipe 手势本地判定即时宣判；P0=本地出拳+VLM 看图裁判 | 宣判气氛组/讲规则 |
| 看这边 | 「玩看这边…」 | 端侧头部姿态绝对方向判定（呆住=输） | 退场收战报 |
| 模仿我 | 「模仿我/学我动作…」 | 无判定，边模仿边聊天 | 插话放行 |

---

## 4. 快速上手开发

### 4.1 环境与构建

- Android Studio（AGP 8.13+/Kotlin 2.0+），minSdk 29；corelib 含 NDK 原生构建（装好 NDK 即可，`compileSdk 36`）。
- 构建：`./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 单测（**588 条，改完必跑**）：
  ```bash
  ./gradlew :avatar-ai-adapter:test :avatar-orchestrator:testDebugUnitTest :app:testDebugUnitTest :corelib:testDebugUnitTest
  ```
- API Key：仓库根 `secrets.properties`（已 gitignore）持有三家 Key；往真机推送用 `.agents/skills/push-api-keys` 技能（一键 run-as 写入）。
- 发布：用 `.agents/skills/publish-release` 技能一键发（Maven Central + APK + GitHub Release；**发布说明必须总结新特性/修复，NOTES_FILE 必填**）。

### 4.2 真机调试工作流

```bash
adb logcat -s AIDebug            # ai_cmd 结果与事件
adb logcat -s LlmPrompt          # 每轮请求/响应全文(ttfb/token 观测)
adb logcat -s FaceDriver         # 口型采样(viseme 跟随)
adb logcat -s FreeSpeech         # VAD 电平/门限
```
- 测帧率**只能**用 `ai_cmd state` 的 `fps=` 字段——`dumpsys gfxinfo` 对 Filament 直渲 SurfaceView 永远 0 帧；MIUI 锁屏时 fps=0 是假象先解锁。
- prefs 读写：`adb shell run-as com.neethu.aiavatar_sdk cat shared_prefs/demo_settings.xml`（写要绝对路径，先 force-stop）。
- 表情问题先 `ai_cmd list expressions` 看模型实际名字集合，别假设。
- HyperOS 设备禁 shell 触摸注入：程序化入口一律走 ai_cmd。

### 4.3 常见开发任务速查

| 要做什么 | 动哪里 | 注意 |
|---|---|---|
| 改提示词/协议 | `orchestrator/i18n/PromptTexts.kt`（Zh/En 同步改） | 改完跑 PromptTextsI18nTest；协议目录变了会自动重发 |
| 加 LLM/TTS 模型或音色 | `orchestrator/facade/ChatProviders.kt`（SDK 目录）+ app `AiProviders.kt`（UI 壳） | 两处目录有测试锁（AiProvidersTest/OpenRouterProviderTest），同步改 |
| 加服务商 | adapter 加适配器 + ChatProviders 加目录 + app AiProvider 枚举 + SettingsScreen | 参照 OpenRouter（handoff 2026-10-05 条）/ DeepSeek（2026-10-18 条，无 TTS/ASR 的服务商看 ttsEngineResolved 回落先例） |
| 加内置动画 | assets/animations/<分类>/ 丢 .vrma | 目录扫描自动进协议块；中文名需登记 `LlmActionCatalog.CHINESE_NAME_TAGS`；技能专用动画放 11_/12_ 目录（不进 LLM 目录） |
| 加技能 | orchestrator `skill/` 实现 AvatarSkill + app 注册 + DemoSkillHost 缝 | 判定端侧优先；激活词中英合并；参考 MimicSkill（最简） |
| 换/调口型手感 | adapter `VowelDriver` 常量区 | 各常量有注释；别动 Dsp.kt（标定数据由它产出） |
| 调渲染观感 | `AvatarRenderSettings` 默认值 | 改默认值要考虑老存档一次性迁移（参照 IBL 13000 的 migrated 标记做法） |
| 改渲染循环骨骼层 | corelib internal | 先读懂「先剥再叠」双信号范式（§1.3）；改完真机回归呼吸/视线/模仿三层 |
| 新骨骼微动系统 | 参照 VrmBreathEngine | 检测器必须无死区（逐帧骨骼世界姿态日志），GazeMath.matToQuat 已修缩放偏差 |

### 4.4 工程约定

- 提交信息风格：`TYPE: fix|feat|chore 中文一句话`（可带长排查记录，本仓库惯例详尽）。
- 文档沉淀：重大排查写进 `docs/ai-layer-handoff.md`（第二节按日期追加、坑进第四节）；改前先读该文档的坑清单，别重踩。
- 测试策略：**纯逻辑全部下沉 JVM 单测**（断句/判定/数学/协议解析/提示词组装），Android 依赖层靠 ai_cmd 真机验证。JVM 测试注意：org.json 不可 mock（用 kotlinx.serialization）、MockWebServer 剧本服务端要自己 close、Dispatchers.setMain 泄漏会跨测试类污染。
- 两份 README（中文/英文）改任一份必须同步另一份。

---

## 5. 未完成的功能项与已知不足

### 5.1 明确的路线图未完成项

| 项 | 出处 | 现状 |
|---|---|---|
| **自由走动（Locomotion）** | `docs/locomotion-feasibility.md` | **P0+P1+P2 已完成+真机验收通过**（P1 2026-10-12：锚点踩点/`<move:>` 全链自然触发/clamp/互斥/双模型；P2 2026-10-13：双槽 crossfade、上半身 mask 边走边比划、方案 C 位移提取〔默认步态实测带 1.49m/循环烘焙位移，提取模式脚步零滑步+速度按模型比例自然〕、waypoint 图多段腿、跑步/侧移步态；顺手修 bindToModel 陈旧世界读数〔hips 高度错 7×，坑 40〕+session 重建僵尸 collector〔坑 41〕；单测 832 全绿；详见交接文档 2026-10-13 条目+坑 40/41） |
| **智能体化（Agent 化）** | `docs/agent-feasibility.md`、`docs/memory-feasibility.md` | **P0 原生工具调用已完成**（2026-10-11）；**P2 长期记忆 M1+M2 已实现**（2026-10-13：存储/检索/提炼/工具/管理面板全链，单测 832 全绿；真机验收 §7 九场景未跑——提炼 JSON 可靠性 curl 矩阵〔§10〕+真机九场景待做）；剩 P1 主动性（定时/主动搭话）→ M3 记忆整理/独立提炼模型 → P3 web_search/MCP |
| 流式 TTS | handoff 任务 7 远期项 | 现在 TTS 是句级整段（每句新建连接 ~1s 延迟）；远期=streaming 变体（Flow\<AudioChunk\>，对齐 AIRI bidirectional-ws） |
| CameraDirector | handoff 任务 7.2 | phase→CameraShot 规则式映射（THINKING→特写等），默认关闭；未做 |
| 空闲行为状态机 | `docs/avatar-realism-feasibility.md` #2 | idle 槽已在 corelib，缺行为调度器（随机换待机/伸懒腰等） |
| 拟真眨眼升级 | 同上 #5 | 现在是对称 sin 曲线+固定 blink 单预设；缺非对称曲线+事件触发 API |
| 倾听反馈 | 同上 #6 | VAD「听到你说话」信号只到 UI 层，虚拟人身体无反馈；需加信号桥+corelib roll 通道 |
| 手部/全身分离渲染 | 同上 #3 | 引擎单动画槽无遮罩混合，属引擎级改造，P4 |
| 点击注视（mouse 模式） | handoff 任务 5 已知未覆盖 | 移动端触摸被相机手势占用，等视频系统 POINT 源统一 |

### 5.2 各模块技术债（源码通读发现）

**corelib**
- VRMA 的 `expressionTracks` 解析了但从未被驱动——VRMA 自带表情动画静默丢弃（VrmaParser.kt:284 解析、update :232 不消费）。
- 模型加载在调用线程同步执行整条管线（含 MToon 纹理逐张 flushAndWait），大模型有 ANR 风险；启动期主线程单消息 1.2s 已实测。
- `VrmExpressionManager.targetWeights` 无同步：任意线程写（setExpression）/渲染线程读，存在竞态（实际因都在主线程未爆）。
- 弹簧骨骼每子步每关节分配 FloatArray(16)，GC 压力；GLB JSON 解析/四元数工具在 5+ 处重复实现未收敛。

**orchestrator**
- `AIAvatarSdk.config` 等门面状态是普通 var 无同步；events SharedFlow buffer 64 DROP_OLDEST 高频可能丢事件。
- `RoomConversationStore` 首读 runBlocking（主线程毫秒级卡顿，自认可接受）；进程被杀丢最后一笔写。
- FaceDriver.tick / EmotionBlender.apply 依赖「实际都在主线程」的文档性假设，无锁。
- 每回合多条 Log.i 全量落盘请求/回复（LlmPrompt tag），生产环境有噪声/体积负担。

**app**
- ✅ **签名密钥从未入库（2026-10-16 全面复核，更正本节旧记载）**：此前写「两个 jks 被 git 追踪」有误——`app/aiavatar-release.jks`、`app/release-key.jks`、`keystore.properties` 从未出现在任何提交（本地全部分支 + origin/main 全历史对象扫描零命中），.gitignore 的 `*.jks` 规则自 Initial commit 即存在，GitHub 仓库公开且远端仅 main 一支。**无需移出/轮换**。真实剩余风险：`aiavatar-release.jks`（正式签名，alias aiavatar）仅本地一份，磁盘损坏即永久无法发布同签名更新，建议异地备份；`release-key.jks`（2026-02 建，口令不可知未启用）按原决策原样保留。
- MainActivity 3744 行/DemoScreen 单 composable 超千行，改 UI 的认知成本高。
- 调试钩子 `contextsSnapshot/selectContext` 在主线程 runBlocking 查 Room；release 未开 minify；`VideoPure.VisionKeywords` 已实现未接线（关键词拦截让位于「每轮都附图」策略）；大量 `runCatching{}: null` 吞异常。

**adapter**
- Edge-TTS 24kHz 解码后走分数降采样，口型匹配质量略降（TtsConfig.sampleRate=16000 可规避但 Edge 不支持 wav）；无 SLA，协议常变需跟上游。
- 重试逻辑仅 Edge 403 校时一处，其余服务商错误全靠 TurnFailed 上错误条，无自动重试。

### 5.3 真机待验收/标定遗留

- 猜拳 P2（MediaPipe 手势）真机全链路体感（检测延迟/连局/与 ML Kit 同线程不掉帧）待验收。
- 看这边竖直方向点头幅度天然小于左右（confirmPitchDeg=12°），若复测「判发呆」再降阈值。
- 模仿我深度动作幅度弱=BlazePose 单目深度局限（数据质量，非符号问题），深度增益待探针确认后再调。
- 设置页语言切换/引导翻页等手势流依赖真手（adb 驱动不了），逻辑已被单测覆盖。

### 5.4 「找不回的改动」备忘

- SoulLinkRenderer 的 Manipulator `orbitHomePosition` 远景实验**已按用户要求退回**，别当丢失的改动补回；要远景用 `CameraShot.LONG_SHOT`。
- `VowelDriver` 静音 holdout 的求值顺序、wLipSync lowPass「叠在原数据上」的原版 quirk、Dsp downSample 的 i0+1——都是刻意保持的上游语义，别「顺手修正」。

---

## 6. 文档与工具索引

| 资源 | 位置 | 什么时候读 |
|---|---|---|
| AI 层交接文档 | `docs/ai-layer-handoff.md` | **动 AI 链路前必读**：逐日攻坚史、15 条坑清单、调参速查 |
| 调试命令手册 | `docs/ai-debug-intents.md` | 真机验证任何功能时 |
| 弹簧骨骼交接 | `docs/springbone-debug-context.md` | 改弹簧骨骼前；含 5 轮修复史与验证工具 |
| 可行性报告 | `docs/agent-feasibility.md`、`docs/memory-feasibility.md`、`docs/avatar-realism-feasibility.md`、`docs/{rps,lookhere,mimic}-skill-feasibility.md` | 开对应新功能前 |
| 模拟器 | `tools/breath_sim/`、`tools/springbone-sim/` | 改呼吸/弹簧骨骼算法前先跑 Python 仿真对拍 |
| 发布脚本 | `tools/release.sh` + publish-release 技能 | 发版时（版本号递增/NOTES_FILE 规则见技能） |
| 上下文锚点 | 记忆库 `airi-source-anchors` | 对齐 AIRI 行为时（本地源码 /home/neethu/projects/airi） |

---

*本文档由 2026-10-11 全仓源码通读生成（corelib/adapter/orchestrator/app 四路并行深读 + 交接文档交叉核对）。发现文档与代码不符时，以代码为准，并顺手更新本文档。*
