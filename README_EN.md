<p align="center">
  <img src="docs/images/logo.png" width="180" alt="AIAvatar SDK" /><br/>
  <a href="README.md">简体中文</a> | English
</p>

# AIAvatar-SDK

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.hufeiya/corelib.svg?label=Maven%20Central&color=orange)](https://central.sonatype.com/artifact/io.github.hufeiya/corelib)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20%2F%20API%2029%2B-green.svg?logo=android)](https://developer.android.com)
[![GitHub release](https://img.shields.io/github/v/release/hufeiya/AIAvatar-SDK?logo=github)](https://github.com/hufeiya/AIAvatar-SDK/releases)

An open-source Android 3D avatar SDK: **Filament PBR rendering + a fully client-side AI conversation stack (LLM / TTS connecting directly to OpenAI-compatible APIs) + on-device lip sync / expressions / micro-motions** — no self-hosted server. Speech synthesis, lip sync, emotional expression, gaze, breathing, and blinking all run on the device.

| | |
|---|---|
| Rendering | Filament 1.68 (PBR), VRM / GLB models, spring-bone physics, IBL environment lighting, procedural camera work |
| Conversation pipeline | Streaming LLM → smart sentence splitting → concurrent TTS → sequential playback → fully on-device lip sync / expressions |
| Multimodal inline tags | `<emo:joy>` expressions, `<act:wave>` motions (VRMA), `<cam:closeup>` camera shots — emitted directly by the LLM |
| Voice | TTS: Edge-TTS (**free, no key**) / OpenAI-compatible / Volcano Engine; LLM: any OpenAI-compatible endpoint |
| Other | SillyTavern character cards, bilingual zh / en, optional skill framework (Rock-Paper-Scissors / Look Over Here / Mimic Me) |

## Feature Highlights

| Spring Bones | Human-like Micro-motions | 52 Expressions |
|:---:|:---:|:---:|
| <img width="240" height="533" alt="Image" src="https://github.com/user-attachments/assets/2f9d5407-5399-414a-b6fb-6d3c98759b16" /><br>**Spring-bone physics**<br>Hair, clothes, and accessories sway in real time with movement; dragging the body or breathing ripples everything together | <img width="240" height="533" alt="Image" src="https://github.com/user-attachments/assets/0f13976f-6beb-4df7-8e38-0dfe1078bdad" /><br>**Human-like micro-motions**<br>Breathing (faster while speaking), eye saccades, gaze at the lens, natural blinking, and lip sync — all driven on-device | <img width="240" height="533" alt="Image" src="https://github.com/user-attachments/assets/37ce0d3a-7c97-4b2f-aae4-f31b8ead3db5" /><br>**52-expression driving**<br>ARKit 52 blendshapes + VRM preset emotions, emitted inline by the LLM via `<emo:>`, with automatic fallback when morph targets are missing |
| <img width="240" height="533" alt="Image" src="https://github.com/user-attachments/assets/6a684307-00a4-42f6-b4b2-59f9a1d9a6b7" /><br>**Character card import**<br>SillyTavern V1 / V2 / V3 PNG cards work with one tap; 18 preset characters built in, with overridable persona and prompts | <img width="240" height="533" alt="Image" src="https://github.com/user-attachments/assets/0913f847-b61f-4b84-852e-9183d57ca0e9" />**Avatar skills**<br>Rock-Paper-Scissors (local hand play + vision / MediaPipe judging), Look Over Here (head-turn reaction game), Mimic Me (mirror your motion via the camera); the framework is extensible | <img width="240" height="533" alt="Image" src="https://github.com/user-attachments/assets/b487b97f-927e-45cb-a97c-06c0cf19e479" /><br>**Procedural camera**<br>The LLM emits cinematic shots inline via `<cam:closeup>`; switch between close-up / long shot / orbit in one tag, and observe freely with gestures |

> Note: The commercial models shown in the GIFs are for demonstration only and are not bundled with the project due to copyright restrictions — you can import your own VRM model instead.

**More features**

- **Fully client-side conversation pipeline**: streaming LLM → smart sentence splitting → concurrent TTS → sequential playback, with no self-hosted server
- **Free voice out of the box**: Edge-TTS (no key) + built-in system ASR (no key) — voice chat works without entering a single API key
- **Built-in provider directory**: switch among SiliconFlow / Volcano Engine / OpenRouter in one tap, or override with any OpenAI-compatible `baseUrl`
- **Multimodal inline tags**: `<emo:joy>` expressions, `<act:wave>` motions (334 VRMA animations built in), `<cam:closeup>` camera shots — all emitted by the LLM in a single stream
- **Video-call mode**: camera gaze (look at the lens / follow your face), expression mirroring, and motion mimicry
- **Import external VRM models**: pick a file and swap characters instantly; the expression catalog refreshes automatically and conversation contexts rotate per model
- **Three voice input modes**: push-to-talk / continuous listening (VAD auto-splitting) / system ASR
- **Bilingual zh / en**: prompts, default voices, and UI strings fully localized
- **Bright rendering out of the box**: built-in IBL environment lighting with zero assets; dual entry points for Compose (`AvatarView`) and classic View (`AvatarSurfaceView`)
- **Persistent chat history**: Room storage, multi-context management, and automatic replay of character cards across session rebuilds

## Architecture

```
:app  (Demo: full-featured showcase + minimal integration sample)
 │
 ├──> :avatar-orchestrator   Session orchestration: LLM stream / TTS pipeline / expression arbitration / skill framework
 │      │                      + the AIAvatarSdk high-level facade (one-step configuration)
 │      └──> :avatar-ai-adapter   LlmAdapter / TtsAdapter / AsrAdapter interfaces and implementations
 │
 └──> :corelib               Filament rendering foundation: AvatarController
                               + AvatarView (Compose) / AvatarSurfaceView (classic View)
```

Dependencies point downward only; the adapter layer knows nothing about rendering, and corelib knows nothing about AI.

## Quick Start

### 0. Requirements

- Android Studio (AGP 8.13+ / Kotlin 2.0+), `minSdk 29`
- `:corelib` contains an NDK native build; install the NDK and Gradle compiles it automatically

### 1. Add the modules

**Option A: Maven dependencies (recommended)** — the three library modules are published on Maven Central:

```kotlin
// your app/build.gradle.kts
dependencies {
    implementation("io.github.hufeiya:corelib:0.1.2")                 // rendering foundation
    implementation("io.github.hufeiya:avatar-orchestrator:0.1.2")     // session orchestration (includes the AIAvatarSdk facade)
    // io.github.hufeiya:avatar-ai-adapter comes in automatically as a transitive dependency
}
```

**Option B: source integration** — copy the `corelib/`, `avatar-ai-adapter/`, and `avatar-orchestrator/` directories into your project (or add them as git submodules):

```kotlin
// settings.gradle.kts
include(":corelib", ":avatar-ai-adapter", ":avatar-orchestrator")

// your app/build.gradle.kts
dependencies {
    implementation(project(":corelib"))
    implementation(project(":avatar-orchestrator"))
}
```

### 2. Show the avatar in a few lines of code

```kotlin
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = rememberAvatarController()

            AvatarView(
                modifier = Modifier.fillMaxSize(),
                controller = controller,
                config = AvatarConfig(), // built-in default IBL lighting, zero assets needed
            )

            LaunchedEffect(Unit) {
                controller.loadModel("model.vrm") // put your own VRM model in assets/
            }
        }
    }
}
```

For classic View (non-Compose) projects, use `AvatarSurfaceView`:

```kotlin
val avatarView = AvatarSurfaceView(this)               // lifecycle auto-bound to the Activity
setContentView(avatarView)
avatarView.controller.loadModel("model.vrm")
```

Observe the model-loading state via `controller.state` (`Idle → Loading → Ready / Error`).

### 3. Make her speak (free — no keys needed)

```kotlin
val scope = rememberCoroutineScope()
val session = remember {
    AvatarSession(
        scope = scope,
        llm = null,                    // omit the LLM for a speech-only session
        tts = EdgeTtsAdapter(),        // Microsoft Edge read-aloud endpoint, free
        controller = controller,
    ).apply {
        ttsConfig = TtsConfig(
            model = "edge-readaloud",
            voice = "zh-CN-XiaoxiaoNeural",
            responseFormat = "mp3",
        )
    }
}

// Once the model is ready: start the lip-sync / expression / blinking / breathing drivers, then speak an opening line
LaunchedEffect(state) {
    if (state is AvatarState.Ready) {
        session.startFaceDriving()
        session.speak("<emo:joy>Hi there! Great to meet you.") // inline tags become expressions
    }
}
```

### 4. Start chatting (one API key)

**Option A: the `AIAvatarSdk` facade (recommended)** — adapter factories, config parsing, and session rebuilding are all wrapped up; one `configure` call and you're chatting:

```kotlin
val chat = AIAvatarSdk(scope, controller, applicationContext)

// Built-in directory of three providers (SiliconFlow / Volcano Engine / OpenRouter); empty model/voice = provider defaults
chat.configure(AIAvatarSdk.ChatConfig(apiKey = "sk-...", ttsEngine = TtsEngine.EDGE))

chat.send("What should we have for dinner tonight?")   // streaming LLM reply, spoken sentence by sentence
chat.interrupt()                                       // interrupt any time
```

Any change to the configuration (key / model / voice / language / context id…) automatically rebuilds the session on the next `configure`; for a custom OpenAI-compatible endpoint just pass a `baseUrl` override — model names are passed through as-is.

**Option B: assemble `AvatarSession` directly** (when you need finer control):

```kotlin
val session = remember {
    AvatarSession(
        scope = scope,
        llm = OpenAiCompatibleLlmAdapter(           // any OpenAI-compatible endpoint
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

session.send("What should we have for dinner tonight?")  // streaming LLM reply, spoken sentence by sentence
session.interrupt()                                      // interrupt any time (LLM + TTS + playback all go silent instantly)
```

The full lifecycle of each user message is emitted through `session.events` (`SharedFlow<AvatarEvent>`): `SentenceQueued / SentenceStarted / EmotionChanged / TurnCompleted / TurnFailed…`

> For a complete runnable reference implementation, see [app/src/main/java/com/neethu/aiavatar_sdk/SimpleDemoActivity.kt](app/src/main/java/com/neethu/aiavatar_sdk/SimpleDemoActivity.kt) (~200 lines, importing only the SDK's public API).

## Assets

| Asset | Required | Notes |
|---|---|---|
| VRM / GLB model | ✅ | Put it in `assets/`, then `controller.loadModel("model.vrm")`; for local files use `loadModelFromFile()` |
| IBL environment lighting (.ktx) | Optional | **A default IBL is built in** (`AvatarConfig()` works with zero assets); to customize, put a `.ktx` in `assets/` and pass `AvatarConfig(iblPath = …)`, or pass `AvatarConfig.IBL_NONE` to disable |

Lip sync, blinking, breathing, and expression arbitration are driven automatically by the FaceDriver built into the session — no extra wiring needed.

## API Quick Reference

**`AvatarController`** (rendering, corelib)
`loadModel / loadModelFromFile` · `state: StateFlow<AvatarState>` · `setExpression` · `playVrmaAnimation / setVrmaIdleAnimation` · `setLookAtTarget` · `setCameraShot / orbitCamera / resetCamera` · `captureFrame` · `updateRenderSettings`

**Render entry points** (corelib)
`AvatarView(modifier, controller, config)` Compose · `AvatarSurfaceView(context, attrs, style, config, controller)` classic View (lifecycle auto-bound to Activity/Fragment; get the controller via the `controller` property)

**`AIAvatarSdk`** (high-level facade, orchestrator)
`configure(ChatConfig, modelReady)` assembles/rebuilds the session · `send(text, images)` · `speak(text)` · `interrupt()` · `events / phase` (current session events and phase) · `setCharacterCard(card)` (auto-replayed across rebuilds) · `close()`.
`ChatConfig` fields: `provider` (built-in directory: SILICONFLOW / VOLCANO / OPENROUTER) · `baseUrl` (custom endpoint override) · `apiKey` · `llmModel` (empty = default) · `ttsEngine` (EDGE free / OPENAI_COMPATIBLE) · `ttsProvider / ttsApiKey / ttsModel / voice` · `lang` · `contextId` (switch history segments).

**`AvatarSession`** (low-level session, orchestrator)
`send(text, images)` one LLM turn · `speak(text)` speech-only playback (no LLM) · `interrupt()` · `startFaceDriving()` · `setCharacterCard(card)` · `events: SharedFlow<AvatarEvent>` · `phase: StateFlow<ConversationPhase>`

**TTS adapters** (`TtsAdapter` implementations, adapter)
`EdgeTtsAdapter()` free · `OpenAiCompatibleTtsAdapter(baseUrl, key)` SiliconFlow / OpenRouter etc. · `VolcanoEngineTtsAdapter(key)` Volcano seed-tts

**LLM adapters** (`LlmAdapter` implementations)
`OpenAiCompatibleLlmAdapter(baseUrl, key)` — any OpenAI-compatible endpoint

## Demo Project

- **`MainActivity`** — full-featured showcase: voice input (push-to-talk / continuous listening / system ASR), video-call mode (camera gaze / motion mimicry / expression mirroring), skills (Rock-Paper-Scissors / Look Over Here / Mimic Me), quality settings, character card management, chat history.
- **`SimpleDemoActivity`** — the minimal sample matching this README:

```bash
adb shell am start -n com.neethu.aiavatar_sdk/.SimpleDemoActivity
```

**Signing**: drop a `keystore.properties` at the repo root (four lines: `storeFile` / `storePassword` / `keyAlias` / `keyPassword`; both the file and the keystore are excluded by .gitignore), then `./gradlew :app:assembleRelease` produces a properly signed `app/build/outputs/apk/release/AIAvatar-v<version>-release.apk`; without that file it falls back to debug signing automatically, so a fresh clone just runs. Once you publish, guard the keystore forever — a signature change makes the update impossible to install over the existing app.

The `docs/` directory holds internal, maintainer-facing documentation (architecture handoff, debug protocols, skill feasibility reports).

## Reference Projects & Acknowledgements

This project's implementation benefits greatly from the following open-source projects and asset sources:

**Rendering & model formats**

- [Filament](https://github.com/google/filament) (Google) — PBR rendering engine, the rendering foundation of `:corelib`
- [three-vrm](https://github.com/pixiv/three-vrm) (pixiv) — reference implementation for VRM runtime semantics (expression weights, hips dragging, gaze, and other behaviors aligned with it)
- [VRM](https://vrm.dev/) (VRM Consortium) — the open 3D avatar model format and VRMA animation spec

**Orchestration & "lifelike" algorithms**

- [AIRI](https://github.com/moeru-ai/airi) (moeru-ai) — reference implementation for conversation orchestration and micro-motion algorithms: streaming sentence splitting, vowel-based lip sync, eye saccades, and system-prompt assembly were ported constant-by-constant, and the lip-sync calibration profiles are reused directly

**AI services & protocols**

- [edge-tts](https://github.com/rany2/edge-tts) — open-source implementation of the free Edge-TTS speech synthesis protocol; the source we aligned the `Sec-MS-GEC` auth algorithm with
- [SillyTavern](https://github.com/SillyTavern/SillyTavern) — definition of the character card (Tavern Card) format; the built-in presets include the official 5 cards ([SillyTavern-Content](https://github.com/SillyTavern/SillyTavern-Content))

**On-device perception**

- [MediaPipe](https://developers.google.com/mediapipe) (Google) — hand / face / pose Landmarkers; the perception foundation for Rock-Paper-Scissors judging, Look Over Here, and motion mimicry

**Motion assets**

- [Mixamo](https://www.mixamo.com/) (Adobe) — source of the original motions for the built-in VRMA animation library (FBX → VRMA conversion)

Plus the open-source infrastructure of Kotlin Coroutines / Jetpack Compose / OkHttp / Room — our thanks to all of them.

## License

[Apache License 2.0](LICENSE)
