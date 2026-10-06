# AIAvatar-SDK

开源 Android 3D 虚拟人 SDK：**Filament PBR 渲染 + 纯客户端 AI 对话（LLM / TTS 直连 OpenAI 兼容 API）+ 端侧口型 / 表情 / 微动作驱动**——没有自建服务端，语音合成、口型同步、情绪表达、视线、呼吸、眨眼全部在端侧完成。

| | |
|---|---|
| 渲染 | Filament 1.68（PBR）、VRM / GLB 模型、弹簧骨骼物理、IBL 环境光、程序化运镜 |
| 对话编排 | LLM 流式 → 智能断句 → 并发 TTS → 顺序播放 → 口型 / 表情全端侧驱动 |
| 多模态行内标签 | `<emo:joy>` 表情、`<act:wave>` 动作（VRMA）、`<cam:closeup>` 运镜，LLM 直出 |
| 语音 | TTS：Edge-TTS（**免费无 Key**）/ OpenAI 兼容 / 火山引擎；LLM：任意 OpenAI 兼容端点 |
| 其他 | SillyTavern 人物卡、多语言（zh / en）、可选技能框架（猜拳 / 看这边 / 模仿我） |

## 架构

```
:app  （Demo：全功能演示 + 最小接入样例）
 │
 ├──> :avatar-orchestrator   会话编排：LLM 流 / TTS 管线 / 表情仲裁 / 技能框架
 │      │
 │      └──> :avatar-ai-adapter   LlmAdapter / TtsAdapter / AsrAdapter 接口与实现
 │
 └──> :corelib               Filament 渲染底座：AvatarController + AvatarView（Compose）
```

依赖方向只允许向下；adapter 不感知渲染，corelib 不感知 AI。

## 快速开始

### 0. 环境要求

- Android Studio（AGP 8.13+ / Kotlin 2.0+），`minSdk 29`
- `:corelib` 含 NDK 原生构建，装好 NDK 后 Gradle 自动编译
- 当前以**源码模块**方式集成（Maven 发布见文末路线图）

### 1. 集成模块

把 `corelib/`、`avatar-ai-adapter/`、`avatar-orchestrator/` 三个目录拷入（或 git submodule 引入）你的工程：

```kotlin
// settings.gradle.kts
include(":corelib", ":avatar-ai-adapter", ":avatar-orchestrator")

// 你的 app/build.gradle.kts
dependencies {
    implementation(project(":corelib"))
    implementation(project(":avatar-orchestrator"))
}
```

### 2. 几行代码，先显示虚拟人

```kotlin
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = rememberAvatarController()

            AvatarView(
                modifier = Modifier.fillMaxSize(),
                controller = controller,
                config = AvatarConfig(iblPath = "default_env.ktx"), // IBL 可选
            )

            LaunchedEffect(Unit) {
                controller.loadModel("model.vrm") // assets/ 里放你自己的 VRM 模型
            }
        }
    }
}
```

模型就绪状态通过 `controller.state`（`Idle → Loading → Ready / Error`）观察。

### 3. 让她开口说话（免费，无需任何 Key）

```kotlin
val scope = rememberCoroutineScope()
val session = remember {
    AvatarSession(
        scope = scope,
        llm = null,                    // 不传 LLM = 纯说话会话
        tts = EdgeTtsAdapter(),        // 微软 Edge 朗读接口，免费
        controller = controller,
    ).apply {
        ttsConfig = TtsConfig(
            model = "edge-readaloud",
            voice = "zh-CN-XiaoxiaoNeural",
            responseFormat = "mp3",
        )
    }
}

// 模型就绪后：启动口型/表情/眨眼/呼吸驱动，说一句开场白
LaunchedEffect(state) {
    if (state is AvatarState.Ready) {
        session.startFaceDriving()
        session.speak("<emo:joy>你好呀！很高兴见到你。") // 行内标签会变成表情
    }
}
```

### 4. 开启对话（填一个 API Key）

```kotlin
val session = remember {
    AvatarSession(
        scope = scope,
        llm = OpenAiCompatibleLlmAdapter(           // 任意 OpenAI 兼容端点
            baseUrl = "https://api.siliconflow.cn/v1",
            apiKey = "sk-...",
        ),
        tts = EdgeTtsAdapter(),
        controller = controller,
    ).apply {
        ttsConfig = TtsConfig(model = "edge-readaloud", voice = "zh-CN-XiaoxiaoNeural", responseFormat = "mp3")
        llmConfig = LlmConfig(
            baseUrl = "https://api.siliconflow.cn/v1",
            apiKey = "sk-...",
            model = "Qwen/Qwen3.8-27B",
        )
    }
}

session.send("今晚吃什么好？")        // LLM 流式回复，逐句 TTS 播报
session.interrupt()                  // 随时打断（LLM + TTS + 播放三层即刻静默）
```

用户消息经 `session.events`（`SharedFlow<AvatarEvent>`）回吐全过程：`SentenceQueued / SentenceStarted / EmotionChanged / TurnCompleted / TurnFailed…`。

> 完整可运行的对照实现：[app/src/main/java/com/neethu/aiavatar_sdk/SimpleDemoActivity.kt](app/src/main/java/com/neethu/aiavatar_sdk/SimpleDemoActivity.kt)（约 200 行，只 import SDK 公开 API）。

## 资产准备

| 资产 | 必须 | 说明 |
|---|---|---|
| VRM / GLB 模型 | ✅ | 放 `assets/`，`controller.loadModel("model.vrm")`；本地文件用 `loadModelFromFile()` |
| IBL 环境光（.ktx） | 可选 | 放 `assets/` 后传 `AvatarConfig(iblPath = …)`；不传则使用灯光 rig 默认照明 |

口型同步、眨眼、呼吸、表情仲裁由会话内建的 FaceDriver 自动驱动，无需额外接线。

## API 速查

**`AvatarController`**（渲染，corelib）
`loadModel / loadModelFromFile` · `state: StateFlow<AvatarState>` · `setExpression` · `playVrmaAnimation / setVrmaIdleAnimation` · `setLookAtTarget` · `setCameraShot / orbitCamera / resetCamera` · `captureFrame` · `updateRenderSettings`

**`AvatarSession`**（对话编排，orchestrator）
`send(text, images)` LLM 回合 · `speak(text)` 纯 TTS 播报（免 LLM） · `interrupt()` · `startFaceDriving()` · `setCharacterCard(card)` · `events: SharedFlow<AvatarEvent>` · `phase: StateFlow<ConversationPhase>`

**TTS 适配器**（`TtsAdapter` 实现，adapter）
`EdgeTtsAdapter()` 免费 · `OpenAiCompatibleTtsAdapter(baseUrl, key)` 硅基流动 / OpenRouter 等 · `VolcanoEngineTtsAdapter(key)` 火山 seed-tts

**LLM 适配器**（`LlmAdapter` 实现）
`OpenAiCompatibleLlmAdapter(baseUrl, key)` —— 任何 OpenAI 兼容端点

## Demo 工程

- **`MainActivity`** —— 全功能演示：语音输入（按住说话 / 连续聆听 / 系统 ASR）、视频通话模式（摄像头注视 / 动作模仿 / 表情跟随）、技能（猜拳 / 看这边 / 模仿我）、画质设置、人物卡管理、对话历史。
- **`SimpleDemoActivity`** —— 本 README 的最小接入对照样例：

```bash
adb shell am start -n com.neethu.aiavatar_sdk/.SimpleDemoActivity
```

`docs/` 目录是面向维护者的内部文档（架构交接、调试协议、技能可行性报告）。

## Roadmap（接口优化方向）

1. **Maven 发布**：四模块补 `maven-publish`，支持 `implementation("…")` 一行依赖，摆脱源码集成。
2. **高阶门面**：把「adapter 工厂 + 配置装配 + 会话重建」从 demo 层下沉为 `AIAvatarSdk` 门面（现在这套逻辑在 demo 的 `AiChatController` / `AiProviders`，约 600 行），对外收敛成 `configure(key, model, voice)` + `send(text)`。
3. **非 Compose View 入口**：`SoulLinkRenderer` 目前是 `internal`，传统 View 工程接不进来；补一个公开的 `AvatarSurfaceView(context, controller)`。
4. **内置默认 IBL**：corelib 打包默认环境光，`AvatarConfig()` 零资产开箱即得高级照明。

## License

[Apache License 2.0](LICENSE)
