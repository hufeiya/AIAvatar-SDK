# AIAvatar-SDK 接手指南 —— 写给 Android View 体系老兵

> **读者画像**：Java/Kotlin 熟、View 体系熟；Jetpack Compose 和 Google Filament 不熟。
> **目标**：一小时建立全貌，当天能改代码，一周能独立开发新功能。
> 基线：commit `6808513`（SDK 0.1.5 / APK v1.3），2026-10-24 整理。
> 配套：`docs/project-guide.md` 是更细的全仓手册（基线略早，个别行号有漂移但内容仍准）；本文与其互补，不重复其细节。

---

## 0. 项目 30 秒简介

**Android 3D 虚拟人 SDK + 全功能 Demo**：Filament 渲染 VRM 模型，纯端侧（无自建服务器）直连各家 OpenAI 兼容 API 完成 LLM 对话 + TTS 语音 + ASR 听写；口型、表情、视线、呼吸、运镜全部端侧驱动。三个库模块已发布 Maven Central（`io.github.hufeiya:*`）。

最省力的入口：**先读 `app/src/main/java/com/neethu/aiavatar_sdk/SimpleDemoActivity.kt`（199 行）**。它只用 SDK 公开 API 演示了「渲染 + 会话 + 事件 + 说话」完整闭环，是理解全项目的钥匙。全功能参考 `MainActivity.kt`（4213 行，已知技术债：太长）。

---

## 1. 四个模块与依赖铁律

```
:app  (com.neethu.aiavatar_sdk, demo 应用, 纯 Compose)
 │
 ├──> :avatar-orchestrator (com.neethu.orchestrator, 会话编排)
 │       │   AIAvatarSdk 门面 / AvatarSession / 工具调用 / 技能 / 记忆 / 圆桌
 │       └──> :avatar-ai-adapter (com.neethu.aiadapter, ★纯 JVM 无 Android 依赖)
 │               LlmAdapter / TtsAdapter / AsrAdapter 四类接口 + 各服务商实现
 │
 └──> :corelib (com.neethu.corelib, Filament 渲染底座)
         AvatarController 门面 / AvatarView / internal 引擎群
```

**两条铁律：adapter 不感知渲染、corelib 不感知 AI；依赖只允许向下。**

心智模型：corelib = 身体，adapter = 耳朵和嘴，orchestrator = 小脑（把听到的变成身体动作），app = 装修好的样板间。

另外两个目录不是 Android 模块：`model-viewer/`（three.js 网页查看器，`node server.js` 起 8173 端口，看模型/动画用）；`tools/`（Blender 建模脚本、发布脚本、弹簧骨骼模拟器等）。

---

## 2. Compose 速成（View → Compose 映射）

本项目 app **零 XML 布局、零 Fragment、零 ViewModel、零导航框架、零 DI 框架**，全部手动装配——对 View 老兵反而友好，没有魔法。

| 你熟悉的 View 概念 | 本项目里的对应物 | 说明 |
|---|---|---|
| `setContentView` | `setContent { Theme { ... } }` | Activity 唯一入口，见 `MainActivity.kt:171` |
| XML 布局文件 | `@Composable` 函数 | 函数即布局，`Modifier` 链 ≈ LayoutParams + 属性 |
| 自定义 View 绘制 | 仍可用，经 `AndroidView { }` 互操作 | **Filament 的 SurfaceView 就这么嵌进来的**（`corelib/AvatarView.kt:45`）|
| `invalidate()` 重绘 | **重组（recomposition）** | 状态变了 → 读该状态的 Composable 函数自动重新执行 |
| LiveData/StateFlow 观察 | `mutableStateOf` / `collectAsState()` | `var x by mutableStateOf(v)`，写 x 即触发重组 |
| `onAttach/onDetach` | `LaunchedEffect` / `DisposableEffect` | 进组合/离组合的生命周期副作用 |
| View 保存状态跨重建 | `rememberSaveable` | `remember` 只跨重组、不跨 Activity 重建 |
| Fragment/页面切换 | 本项目不用 Navigation | 四个 Activity 互相 `startActivity`；页内「导航」是 `activePanel` 枚举切面板 |
| SharedPreferences 直读写 | 同样是 SharedPreferences | 但包了一层可观察状态（见下） |

### 本项目的两个状态模式（重点）

**① `DemoUiState` —— 手写"ViewModel"**（`MainActivity.kt:368`）。一个普通类，几十个 `var x by mutableStateOf(...)` 属性，`remember { DemoUiState(appContext) }` 挂在 Activity 上（`:173`）。每个 setter 里顺手写 SharedPreferences。**为什么要提出组合之外**：adb 调试命令（`AiDebugExecutor`）要和按钮走同一份状态（`:363` 注释）。

**② 会话用 `produceState` 异步构建 + 800ms 防抖**（`MainActivity.kt:1074`）。设置页每敲一个字符都会改配置，不防抖会导致会话被逐字符重建、正在播的 TTS 反复被杀。

### 已踩过的 Compose 坑（改 UI 前扫一眼）

- `ModalNavigationDrawer` 必须 `gesturesEnabled = false`，否则全屏横滑手势被抽屉吃掉，虚拟人轨道手势失效；副作用是返回键不再关抽屉，需手写 `BackHandler`（`MainActivity.kt:176-201`）。
- 全屏 3D 视图（`AvatarView`）铺底，HUD/面板全部以兄弟节点叠在 `Box` 里（`:2511-2566`）——Z 序即声明顺序。
- 语言切换 = 整树重组：`LocalStrings` 用 `staticCompositionLocalOf`（`i18n/Strings.kt:651`），一次失效全部文本。
- 传 lambda 别传捕获的 val：`produceState` 会持续更新，技能拿 `sessionProvider = { session }` 而不是 `session` 本体（`:1751`）。
- 拖拽手势中先写状态再读状态会漂移：在 `onDragStart` 里捕获一次初值、增量累加（`VideoCallPip`，`:3745`）。

---

## 3. Filament 速成（渲染盲区补课）

Filament 是 Google 的 PBR 渲染引擎（C++ 实现、Android 上以 `.so` 提供）。**不需要 OpenGL 知识**，把它当一个「带 ECS 风格的场景图引擎」理解即可。

### 3.1 核心概念 → 类比表

| Filament 概念 | 一句话理解 | View 世界的类比 |
|---|---|---|
| `Engine` | 渲染线程的总管，一切资源的工厂 | GL 线程的 EGL 上下文 |
| `SwapChain`/`UiHelper` | 拿到 Surface 并管理尺寸/旋转 | SurfaceHolder |
| `View` + `Scene` + camera | 视口 + 场景内容 + 相机三件套 | 相当于「画布分区 + 显示列表 + 取景器」 |
| **`Entity`（就是个 Int）** | 一切东西的 ID：骨骼节点、灯、网格、材质实例都是 Entity | 没有 View 类比——ECS 风格 |
| `TransformManager` | 按 Entity 存/取变换矩阵（位置骨骼就是写这里） | `View.setTranslationX` 的 ECS 版 |
| `FilamentAsset` | gltfio 加载出的一个 GLB 资产（含骨骼树） | 一个已解析的 3D 资源对象 |
| `RenderableManager.setMorphWeights` | 写 blendshape 权重（表情/口型靠它） | 没有类比，morph 目标数组 |

**本项目从不手工搭 Engine 管线**——用的是 filament-utils 的 `ModelViewer`，它打包了 Engine/Scene/View/camera/SwapChain（`corelib/internal/SoulLinkRenderer.kt:100`）。你要做的只是理解「往 Scene 里塞 Entity、每帧改 TransformManager、最后 render」。

### 3.2 像素怎么上屏（单人路径）

```
Compose: AvatarView(composable)
  └─ AndroidView { SurfaceView + SoulLinkRenderer }      corelib/AvatarView.kt:45
       └─ ModelViewer(SurfaceView, Engine, UiHelper, Manipulator)   真正的宿主
            └─ 渲染循环 = Choreographer.postFrameCallback 自驱     SoulLinkRenderer.kt:501
```

传统 View 入口同样存在：`AvatarSurfaceView`（Glide 式从 Context 链自动找 LifecycleOwner）。

**帧循环不是协程**，是 `Choreographer.FrameCallback.doFrame` 每帧自 post（`:628`），`onResume/onPause` 起停。每帧顺序（**顺序敏感，改前必读**）：

1. 动画（VRMA 优先 / 内置 glTF 动画）
2. hips 拖拽偏移重钉 → 3. 走动根位移（LocomotionEngine 写 root，与骨骼局部正交）
4. 呼吸叠加 → 5. 视线叠加 → 6. 姿态模仿叠加
7. `commitLocalTransformTransaction()` + `animator.updateBoneMatrices()`（**必须显式提交**，漏了头部抽搐）
8. 表情 morph 权重 → 9. 弹簧骨骼（Verlet）
10. 运镜 steering → `modelViewer.render()`

**贯穿所有骨骼写入层的范式：「先剥再叠」**——叠加层发现骨骼局部还等于自己上次写的值（没被动画覆盖）就先剥旧偏移再叠新的；被动画重写则全量写。破坏这个范式会复现「锁死/累积/抽搐」三类历史 bug。改任何骨骼写入代码前先读 `project-guide.md` §1.3。

### 3.3 VRM 模型与 VRMA 动画（自研解析，无三方 VRM 库）

加载链 `SoulLinkRenderer.loadModelBytes`（`:1082`）：

```
GLB 字节 → GlbMorphPatcher(注入 morph 默认权重+剥 unlit，修 gltfio 不传 morph 法线)
        → GlbBoneCuller(Filament 256 骨骼上限裁剪)
        → GlbMToon(卡通材质 JSON 级改造+轮廓线双 primitive)
        → modelViewer.loadModelGlb(gltfio 接管)
        → 自研 Gson 解析 VRM 扩展 → 绑定六个引擎:
          VrmaAnimationEngine / VrmExpressionManager / VrmSpringBoneManager
          VrmLookAtEngine / VrmBreathEngine / VrmPoseMimicEngine
```

- **VRMA**（`.vrma` = 带 `VRMC_vrm_animation` 的 GLB）：自研 `VrmaParser` 解析 + 旋转重定向到通用空间，`VrmaAnimationEngine` 双槽播放（底层动作 + 上半身手势 overlay，crossfade 淡入淡出）。
- **表情**：`VrmExpressionManager` 统一 VRM0/1 两代命名（0.x 的 `a/i/u/e/o` 自动归一成 `aa/ih/ou/ee/oh`——历史上"说话不动嘴"的根因就在这）。
- **MToon**：`MToonMaterialFactory` 运行时用 filamat 编译 three-vrm 的 mtoon shader 移植版；模型自动检测走 MTOON 还是 PBR（`AvatarRenderSettings.renderMode`）。
- **相机**：filament-utils `Manipulator`（ORBIT 模式）。**程序化运镜没有 setFrame API**——必须换算成手势原语 `scroll()/grabBegin/grabUpdate/grabEnd` 驱动，`AvatarController` 的 zoom/pan/orbit 全是这个套路（`:2769-2814`）；7 个预设机位 `CameraShot` 在其上加平滑跟拍。

### 3.4 多人同屏（stage 包）

`AvatarStage`（`stage/AvatarStage.kt:37`）：一个共享 Scene 装 N 个 `AvatarRuntime`，每演员发一个 `AvatarHandle`（实现同一个 `AvatarActor` 接口）。帧循环纪律：N 个 runtime 各自 preAnimation → **一次** commit → 各自 postAnimation/皮肤物理 → render（`stage/internal/Stage.kt:447`）。

### 3.5 Filament 相关红线（前人血泪，改前必读）

- **销毁顺序**：配件 → VFX → asset → MToon 实例 → factory → scene → lights，顺序错了 Filament 直接 native abort。`SoulLinkRenderer.onDestroy`（`:1934`）有完整注释。
- `ModelViewer` 内建 detach listener 会 `engine.destroy()`，二次 onDestroy 撞 `flushAndWait` = SIGABRT（已加双护栏，别拆）。
- `TransformManager.getParent` 返回的是 **entity 不是 instance**，混用是弹簧骨骼真机 bug 的元凶。
- 每场景只允许一盏方向光；本项目三灯全用 SPOT。阴影默认 1024+PCF；**PCSS 在 Adreno 上 1-5 FPS**，别开。
- Engine 相关对象一律主线程构造。

---

## 4. AI 链路：一次对话的旅程（精简版）

```
用户输入(打字/语音/adb)                          app 层，四路统一
  → AIAvatarSdk.send → AvatarSession.send        orchestrator/session/AvatarSession.kt:632
      组请求(系统协议+人设+Room历史+记忆检索行) + 工具循环(≤3轮)
  → OpenAiCompatibleLlmAdapter.streamChat        adapter，SSE 冷流 Flow
      每个 delta → InlineTagExtractor 剥 <emo:/<act:/<cam:/<move: 行内标签
  → SentenceChunker 断句(硬标点切+软标点加速首句)
  → SpeechPipeline(4 路并发 TTS + 解码 + MFCC 口型离线分析)
  → AudioTrackPlaybackQueue 严格按句序播放；第 N 句播时 N+1 句在合成
  → 播放回调触发 FaceDriver(Choreographer):
      口型viseme采样 + 情绪 + 眨眼 + 眼动 → 合并成 controller.setExpression
  → corelib 渲染循环消费 morph 权重 → 屏幕上的人开口
```

LLM 控制虚拟人有**两条通道**：

1. **行内标签**（`<emo:joy>` `<act:wave>` `<cam:closeup>` `<move:anchor>`）：随对话文本流出，`AvatarSession.dispatchCues`（`:1053`）分发到表情/手势/运镜/走位驱动，白名单校验不过会以 `TagRejected` 事件静默丢弃（不进 TTS）。
2. **原生函数工具**（`ToolSpec` + OpenAI function calling）：`ToolRegistry`（`orchestrator/agent/ToolRegistry.kt:42`）注册，`AvatarSession.send` 里循环执行（未知名/JSON 错/超时都变成错误结果回喂模型，不打断回合）。目录在 `agent/AgentToolCatalog.kt`：`move_to`、`wear_accessory`、`play_effect`、`take_photo` 等 15 个。

**对上以 Flow 为主**：`AvatarSession.events: SharedFlow<AvatarEvent>`（20 种事件）+ `phase: StateFlow`（THINKING/SPEAKING/IDLE）；`AIAvatarSdk.events` 跨会话重建自动续接。**对下以接口为主**：`AvatarActor`（`corelib/AvatarActor.kt:16`）——`AvatarController`（单人）和 `AvatarHandle`（多人）都实现它，orchestrator 的 FaceDriver/GestureDriver/LocomotionDriver 只认这个接口。这就是「AI 层不 import 渲染内部」的关键。

### 服务商体系（adapter 层）

抽象叫 **`Adapter`** 不叫 Provider：`LlmAdapter.streamChat(): Flow` / `TtsAdapter.synthesize(): suspend` / `AsrAdapter.transcribe(): suspend` / `EmbeddingAdapter`。

- 绝大多数服务商（硅基流动/DeepSeek/OpenRouter/vLLM/Groq…）共用 `OpenAiCompatibleLlmAdapter` 一个类，只是 baseUrl/key 不同。
- 特殊协议单独成类：`edge/EdgeTtsAdapter`（免 Key）、`volcengine/VolcanoEngineTtsAdapter`（WebSocket）、`bailian/BailianTtsAdapter`（DashScope 原生）。
- **服务商目录的唯一事实源是 `orchestrator/facade/ChatProviders.kt` 的 `ChatProvider` 枚举**（端点/模型清单/音色/默认值/ASR 回落全在这），实例化分支在 `AIAvatarSdk.configure`（`:326`）。app 的 `AiProviders.kt` 只是 UI 标签。

---

## 5. 源码地图（按模块，改代码先查这张表）

### corelib（`corelib/src/main/java/com/neethu/corelib/`）

| 类/包 | 作用 |
|---|---|
| `AvatarController.kt:45` | **SDK 唯一门面**：loadModel/playVrma/setExpression/setLookAtTarget/startWalk/playVfx/setCameraShot/captureFrame… |
| `AvatarView.kt:37` / `AvatarSurfaceView.kt:44` | Compose / 传统 View 双入口 |
| `AvatarState.kt` | sealed：Idle/Loading/Ready/Error |
| `AvatarConfig` / `AvatarRenderSettings` | 环境光配置 / 15 项渲染设置（灯光、阴影、AO、色调映射、Bloom…）|
| `internal/SoulLinkRenderer.kt:74` | 单人渲染引擎真身（3100 行，全 internal）|
| `internal/GlbMorphPatcher` `GlbBoneCuller` `GlbMToon` `MToonMaterialFactory` `MToonApplier` | GLB 预处理 + 卡通材质管线 |
| `internal/VrmaParser` `VrmaAnimationEngine` `LocomotionExtractor` | VRMA 解析/重定向/双槽播放/步态位移提取 |
| `internal/VrmExpressionManager` `VrmLookAtEngine` `VrmBreathEngine` `VrmPoseMimicEngine` `VrmSpringBoneManager` | 六引擎之五（每模型一份状态）|
| `stage/` | 多人同屏：`AvatarStage` 门面 + `AvatarHandle` 演员柄 + `internal/Stage` 帧泵 |
| `accessory/` | 配件系统：`AccessoryManager`（setParent 挂骨骼，零每帧成本）|
| `vfx/` | 特效：`VfxManager` + 粒子模拟（`vfx/sim` 纯 JVM 可单测）|

### avatar-ai-adapter（`avatar-ai-adapter/src/main/java/com/neethu/aiadapter/`）

| 类/包 | 作用 |
|---|---|
| `api/` | 六个接口：Llm/Tts/Asr/EmbeddingAdapter、LipSyncProcessor、TagExtractor |
| `model/ChatTypes.kt` | 全部线上类型：ChatMessage/ToolSpec/LlmStreamEvent/TtsConfig… |
| `openai/` | OpenAI 兼容全家桶 + `ToolCallAccumulator`（流式工具调用重组）|
| `edge/` `volcengine/` `bailian/` | 三种非 OpenAI 协议实现 |
| `lipsync/` | wLipSync MFCC 口型算法复刻 |
| `emotion/InlineTagExtractor` | 行内标签正则剥离器（增量流式）|

### avatar-orchestrator（`avatar-orchestrator/src/main/java/com/neethu/orchestrator/`）

| 类/包 | 作用 |
|---|---|
| `facade/AIAvatarSdk.kt` | 高阶门面：configure/send/speak/interrupt(三路打断) |
| `facade/ChatProviders.kt` | 服务商目录枚举（唯一事实源）|
| `session/AvatarSession.kt` | 低阶会话：请求组装/工具循环/标签分发/事件总线 |
| `session/AvatarEvent.kt` | 20 种事件的 sealed interface |
| `pipeline/SpeechPipeline.kt` + `audio/` | 句级 TTS 流水 + AudioTrack 播放队列 |
| `face/FaceDriver.kt` | 口型/情绪/眨眼/眼动四合一逐帧驱动 |
| `agent/ToolRegistry.kt` + `AgentToolCatalog.kt` | 工具注册表 + 内置工具目录 |
| `skill/` | 技能框架：`AvatarSkill` 接口 + `SkillRegistry` + Rps/Mimic/LookHere 三个成品 |
| `memory/` | 长期记忆：Room 存储 + 向量检索 + LLM 提炼（remember/recall/forget 三工具）|
| `roundtable/` | 多人圆桌：N 个 AvatarSession + 导演轮转（规则/LLM 两档）|
| `debate/` | 辩论赛状态机（ scripted 回合引擎）|
| `history/` | 对话历史（Room / 内存双实现）|
| `chunker/SentenceChunker.kt` | 断句（AIRI 规则移植）|

### app（`app/src/main/java/com/neethu/aiavatar_sdk/`）

| 文件 | 作用 |
|---|---|
| `MainActivity.kt`（4213 行）| 全功能 Demo：`DemoUiState` + `DemoScreen` + 聊天条 + 面板 + adb 命令队列 |
| `SimpleDemoActivity.kt`（199 行）| **最小接入样例，从它读起** |
| `AiChat.kt` | provider 偏好(SharedPreferences) + 会话控制器（拥有 AIAvatarSdk 实例）|
| `AiDebug.kt` / `AiDebugExecutor.kt` | adb 调试命令协议与执行器 |
| `ui/SettingsScreen.kt`（1977 行）| 设置大面板（半/全屏拖拽 sheet，可复用的行组件）|
| `AgentDeviceTools` `AgentEffectTools` `AgentAccessoryTools` | app 侧注册的 LLM 工具（摄像头/特效/配件）|
| `multiavatar/` | 多人同屏 Demo + 舞蹈点播导演（`show/DanceShowController`）|
| `debate/` | 辩论 Demo（`DebateRig` 驱动 orchestrator 的 DebateEngine）|
| `video/` | MediaPipe 摄像头跟踪（脸/手势/姿态）|
| `skills/DemoSkillHost.kt` | 技能宿主：把手势映射到 vrma 资产路径 |

---

## 6. 开发工作流

### 构建与测试

```bash
./gradlew assembleDebug          # 构建 demo APK
./gradlew :corelib:testDebugUnitTest   # 单模块测试（其它模块同理）
./gradlew test                   # 全部测试
adb install -r app/build/outputs/apk/debug/AIAvatar-v1.3-debug.apk
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity
```

**测试全是纯 JVM**（无 Robolectric/模拟器依赖，CI 可跑）：136 个文件、1242 个 `@Test`。风格是 JUnit4 + 反引号中文测试名；数学/协议类刻意写成无 Android 依赖以便单测（`corelib` 配了 `isReturnDefaultValues` 让 `android.util.Log` 打桩）。orchestrator 测试用 `kotlinx-coroutines-test`；**坑**：挂起 LLM 的测试必须无参 `UnconfinedTestDispatcher`；`advanceUntilIdle` 会推过 `delay`，测延时逻辑用 `runCurrent`。

### 真机调试三板斧（比打断点快得多）

本项目有完整的 adb 驱动协议（`docs/ai-debug-intents.md` 是完整手册），命令走 `am start` extras，结果打到 logcat 标签 `AIDebug`：

```bash
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd state
adb logcat -d -s AIDebug

adb shell am start ... --es ai_cmd "list animations"        # 先查有效取值
adb shell am start ... --es ai_cmd play_animation --es ai_arg "打招呼"   # 参数带空格必须整体加引号（多词参数双引号是历史大坑）
adb shell am start ... --es ai_cmd send_chat --es ai_arg "你好"
adb shell am start ... --es ai_cmd camera_shot --es ai_arg closeup
```

`state` 输出模型/场景/动作/表情/相机位姿/FPS（测帧率看这里的 fps 字段，gfxinfo 对 Filament 无效）。截图用 `screenshot` 命令或 `adb exec-out screencap`（1:1 物理分辨率）。

**API Key 推送**：`secrets.properties` 里的 key 一键写入真机（技能 `/push-api-keys`，内部 run-as 写 SharedPreferences）。

### 约定

- **commit 模板**：标题 `TYPE: feat 一句话`，正文普通段落展开。禁止 ①②③ 分号串联长体。
- **README 中英双份**（`README.md` / `README_EN.md`）改任一份必须同步另一份。
- **发版**用 `/publish-release` 技能一键走 Maven Central + APK + GitHub Release；版本号在 `gradle.properties` 的 `SDK_VERSION` 和 `app/build.gradle.kts`。

---

## 7. 新功能开发手册（五个最常见配方）

### 配方 A：给 LLM 加一个工具（function calling）

1. 在 `orchestrator/agent/AgentToolCatalog.kt` 加一个工厂函数，返回 `Pair<ToolSpec, ToolExecutor>`——`ToolSpec` 带 JSON Schema；**枚举白名单直接生成进 schema、执行时再校验**（参考 `wear_accessory`，`:615-649`）。
2. 能力用 lambda 注入（`wear = suspend (id) -> Failure?`），别让 orchestrator import app 的类。
3. 注册：SDK 内置工具在 `AvatarSession` 构造时注册；app 专属工具在 `MainActivity.kt:1398` 附近或 `AgentDeviceTools.kt:119` 注册。
4. 执行失败返回 `ToolResult(content, isError=true)` 即可——错误会回喂模型自纠，别抛异常。
5. 坑：DeepSeek-V4-Flash 有 toolRounds=0 的模型级不调工具问题，验证工具链换其它模型。

### 配方 B：加一个动作/表情资产

1. `.vrma` 文件放进 `app/src/main/assets/animations/<分类>/`（现有 346 个、12 类，LLM 目录由启动时扫描生成）。
2. `<act:name>` 标签 → `GestureDriver` → `AvatarActor.playVrmaAnimation`。动作目录是 app 侧 `LlmActionCatalog.kt` 构建的（绑定校验在 orchestrator `tag/TagCapabilityRegistry`，未知名静默丢弃并报 `UnknownTag` 事件）。
3. 排查表情先 `ai_cmd list expressions`。

### 配方 C：加一个 LLM/TTS 服务商

1. OpenAI 兼容服务商：**不用写新类**。在 `orchestrator/facade/ChatProviders.kt` 的 `ChatProvider` 枚举加一项（baseUrl/模型清单/音色/默认值），再在 `AIAvatarSdk.configure`（`:326`）的 when 里补分支。
2. 私有协议才写新 Adapter 类（放 `adapter/<厂商>/`，参考 `BailianTtsAdapter`），实现对应 `api/` 接口。
3. app 侧：`AiProviders.kt` 加 UI 标签、`AiChat.kt` 加 prefs key、`SettingsScreen` 加输入框、`i18n/Strings.kt` 双语文案。
4. 坑：单测用 MockWebServer（adapter 是纯 JVM，这是它能 CI 跑的原因）；Key 永远不进 git（`secrets.properties`）。

### 配方 D：加一个设置项（Compose UI）

1. `DemoUiState`（`MainActivity.kt:368`）加 `var x by mutableStateOf(...)` + setter 里持久化。
2. `ui/SettingsScreen.kt` 用现成行组件拼：`SettingsSwitchRow`（`:1604`）/ `SettingsSliderRow`（`:1639`）/ 下拉组件（`:1735`）。
3. 文案进 `i18n/Strings.kt`（中英两份）；生效若需重建会话，走既有 `produceState` + 800ms 防抖路径，**别新开重建逻辑**。
4. 渲染类设置改走 `AvatarRenderSettings` + `controller.updateRenderSettings`（注意：阴影开关要重建灯光、色调映射走 ColorGrading，材质参数不可还原——见 `AvatarRenderSettings.kt` 注释）。

### 配方 E：加一个技能（AvatarSkill）

技能 = 抢占话语权的模式插件（猜拳/模仿我/看这边都是它）。接口 `orchestrator/skill/AvatarSkill.kt:19`：`onUtterance` 返回 true 即消费该句话不发 LLM；`occupiesBody` 声明独占身体；`tools()` 提供技能专属 LLM 工具。参考 `RpsSkill` 实现 + `MainActivity.kt:1029` 的实例化注册 + `DemoSkillHost` 提供资产能力。注意辩论/舞蹈不是技能——它们是 app 层直接驱动 `DebateEngine`/`RoundtableSession` 的独立引擎。

---

## 8. 必读红线速查（一句话版，出处见各文档）

| 红线 | 出处 |
|---|---|
| 骨骼写入遵守「先剥再叠」，改前读 project-guide §1.3 | 历史 bug 三连 |
| Filament 资源销毁顺序固定，别拆双护栏 | springbone-debug-context.md |
| `getParent` 返回 entity 不是 instance | 同上 |
| 每帧骨骼写入后必须显式 commit + updateBoneMatrices | SoulLinkRenderer.doFrame |
| PCSS 软阴影 Adreno 1-5 FPS，默认 1024+PCF 别动 | 真机归因表 2026-10-02 |
| 方向光每场景一盏、本项目灯光全 SPOT | applyLighting 约定 |
| 相机程序化控制只有 scroll/grab 原语，无 setFrame | CameraShot 驱动换算 |
| LLM 输出进 TTS 前必须过标签白名单（TagCapabilityRegistry）| 行内标签可靠性 P0/P1 |
| adb 多词参数整体双引号 | 历史静默失败多起 |
| 挂起 LLM 的单测用无参 Unconfined；delay 用 runCurrent | 测试坑 |
| 模型加载在主线程（已知 ANR 技术债，stage 路径已异步）| 待办清单 |

---

## 9. 深入阅读索引（按需）

| 想深入 | 读 |
|---|---|
| 全仓细节手册（基线略早仍准）| `docs/project-guide.md` |
| AI 层逐日攻坚史 + 坑清单（改 AI 链路前必读）| `docs/ai-layer-handoff.md` |
| adb 调试命令完整手册 | `docs/ai-debug-intents.md` |
| 弹簧骨骼 5 轮修复史 | `docs/springbone-debug-context.md` |
| 各功能设计文档（配件/特效/走动/多人/记忆/辩论…）| `docs/*-feasibility.md` 系列 |
| 渲染 API 细节 | Filament 官方 docs + 本仓库 `corelib` KDoc |

**建议的接手路径**：SimpleDemoActivity 通读 → 跑起来 `ai_cmd state/list` 玩一圈 → 读本文 §4 对照 logcat 走一遍说话链路 → 挑一个配方 A/B 的小改动练手 → 需要动渲染时再回 §3 + project-guide。
