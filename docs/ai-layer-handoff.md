# AIAvatar-SDK · AI 交互层攻坚交接文档

> 用途：新会话冷启动用的自包含上下文。任何一轮新对话开始时，**先读完本文档**，再按「第六节」的任务提示词开工。
> 生成时间：2026-10-03。对应提交：`83d2914`（四层架构中两层落地，46 文件 +4146 行）。

---

## 一、项目背景（30 秒版）

开源 Android 虚拟人 SDK + Demo：Filament(C++/JNI) 渲染底座 + 纯客户端（Serverless）AI 对话——Android 端直连公网 OpenAI 兼容 API（LLM + TTS），端侧口型解算与生理微动作，支持酒馆人物卡（SillyTavern Character Card）。

四层 Gradle 架构（依赖只能向下）：

```
:app (demo)  →  :avatar-orchestrator (业务中台)  →  :avatar-ai-adapter (能力适配)
                        └──────────────→  :corelib (渲染底座,已存在)  ←──┘ (严禁 adapter→corelib)
```

对齐参照物：**AIRI**（moeru-ai/airi）本地源码在 `/home/neethu/projects/airi`，其关键 file:line 锚点与对齐参数已固化在项目记忆 `airi-source-anchors` 中（新会话自动加载 MEMORY.md 索引，需要细节时读该记忆文件）。

## 二、当前进度（已完成，勿重做）

### 2.1 `:avatar-ai-adapter`（纯 JVM 模块，包 `com.neethu.aiadapter`）

| 文件 | 职责 |
|---|---|
| `api/LlmAdapter.kt` | `streamChat(messages, config): Flow<LlmStreamEvent>`，事件 = TextDelta/Finish/Error |
| `api/TtsAdapter.kt` | `suspend synthesize(text, config): TtsResult`（V1 句级整段返回） |
| `api/LipSyncProcessor.kt` | `analyze(pcm16, sampleRate): VisemeTimeline`（**离线时间线**，帧 ≈64ms 一帧） |
| `api/EmotionExtractor.kt` | 流式标记过滤：`feed(delta) → ExtractionResult(cleanText, cues)` |
| `model/ChatTypes.kt` | ChatMessage/LlmConfig/LlmStreamEvent/TtsConfig/TtsResult/TtsAudioFormat |
| `openai/OpenAiCompatibleLlmAdapter.kt` | OkHttp SSE 手解 `data:` 行；`channelFlow + awaitClose{call.cancel()}` 取消即断连 |
| `openai/OpenAiCompatibleTtsAdapter.kt` | POST `{base}/audio/speech`，response_format 可配（demo 默认 wav） |
| `lipsync/Dsp.kt` + `MfccFrontend.kt` | **wLipSync/uLipSync MFCC 管线精确移植**：RMS→FIR低通→16k降采样→预加重0.97→汉明窗→峰值归一→FFT→30 Mel→10log10→DCT→取系数1..12 |
| `lipsync/WlipsyncProfile.kt` | 解析标定 profile（`src/main/resources/wlipsync/profile.json`，**直接复用 AIRI 的文件**），cosine^100 打分归一化 |
| `lipsync/VowelDriver.kt` | AIRI vowel-driver.ts 逐常量移植：音量 `min(0.9v,1)^0.7`、winner≤0.7/runner×0.6≤0.35、静音门限 0.04/0.05/160ms、非对称指数平滑升50/降30、死区0.01、输出×0.7。元音槽序 **AA,IH,OU,EE,OH** |
| `lipsync/WlipsyncLipSyncProcessor.kt` | 组合前端+profile→VisemeTimeline；暴露 `vowelLayout`（音素→元音槽映射） |
| `emotion/MarkerEmotionExtractor.kt` | 协议 `<\|emotion:happy\|>` / `<\|emotion:happy:0.8\|>`（强度可负，clamp 0..1），跨 delta 缓冲半截标记，flush 丢弃悬尾 |

### 2.2 `:avatar-orchestrator`（android library，包 `com.neethu.orchestrator`）

| 文件 | 职责 |
|---|---|
| `session/AvatarSession.kt` | **门面**：`send/sendAndAwait/interrupt/close/setCharacterCard`；`llmConfig`/`ttsConfig` 属性；`phase: StateFlow` + `events: SharedFlow`。内部组装 extractor→chunker→pipeline，collect LLM 流 |
| `session/ConversationPhase.kt` | IDLE/THINKING/SPEAKING（注意与 corelib 的 `AvatarState` 渲染状态是两回事） |
| `chunker/SentenceChunker.kt` | AIRI tts-chunker 规则：硬标点 `.。?？!！…⋯～~\n\r\t`、软标点 boost=2/min=4/max=12、小数点保护、`...`点串一次切、`*动作*` 剥离（跨 delta 缓冲未闭合段）；词计数 `java.text.BreakIterator`（可注入 WordCounter） |
| `pipeline/SpeechPipeline.kt` | TTS 并发≤4（Semaphore）、completed 按 sequence 严格有序入队、失败用 failedSequences 跳过前沿不卡队、`awaitTurnComplete()`、cancelTurn 级联 |
| `audio/PlaybackQueue.kt` / `AudioTrackPlaybackQueue.kt` | maxVoices=1 FIFO；写线程 4096 帧块写；stopAll 从调用线程 pause+flush 即时静音；`ActivePlayback.positionSeconds()` 用 AudioTimestamp 音频时钟（FaceDriver 靠它对口型） |
| `audio/PcmDecoder.kt` | WAV 纯 Kotlin 解析（16bit PCM/8bit/32f、立体声下混）+ MP3/OGG 走 MediaExtractor+MediaCodec(MediaDataSource) |
| `face/FaceDriver.kt` | Choreographer 每帧混合：口型(时间线采样+VowelDriver 状态机)→情绪→眨眼；**所有权规则**：说话中口型独占嘴部、结束后 viseme 从 0 淡入情绪目标(blend-back)、眼区情绪压制眨眼；`controller.setExpressionTransitionDuration(0)` 后全部缓动自绘 |
| `face/EmotionBlender.kt` | AIRI expression.ts 组合表(happy=happy0.7+aa0.2 等7种)、easeInOutCubic、起点捕获当前显示值、3s 自动回 neutral、eyeAreaActive 判定 |
| `face/MicroMotionEngine.kt` | 眨眼：sin(πt)/0.2s/间隔U(1,6)s（gaze/saccade 未做，见任务5） |
| `card/CharacterCard(+Parser)` | Tavern V1 平铺/V2/V3(`data.*`)；**PNG tEXt/zTXt 解析，`ccv3` 与酒馆 `chara` 双键兼容**（AIRI 只有导出没有导入，这是我们补的能力）；未知字段 extensions 保留 |
| `card/SystemPromptAssembler.kt` | AIRI resolveSystemPrompt 顺序 `[systemPrompt,description,personality,scenario]` join `\n\n` + 情绪协议指令块（告诉模型怎么发 `<\|emotion:..\|>`） |
| `history/ConversationStore.kt` | 接口 + InMemoryConversationStore(80条环形)；**Room 版未做**（任务3） |

### 2.3 `:app` 集成

- `AiChat.kt`：AiChatPrefs(5项配置+持久化键 `ai_baseUrl/ai_apiKey/ai_llmModel/ai_ttsModel/ai_voice`，prefs=`demo_settings`)、AiChatController(配置变化即重建 session，AIRI getProviderInstance 语义)
- `MainActivity.kt`：底部 `AiChatBar`(输入/发送/打断/状态徽标/回复字幕/错误条)、produceState 装配 session、事件收集；视角 badge 移到 bottom=180dp
- `SettingsScreen.kt`：新增「AI 对话 (AI Chat · OpenAI 兼容)」区块（5 个 SettingsTextFieldRow）
- `AndroidManifest.xml`：补了 INTERNET 权限

### 2.4 验证状态

- **50 单测全绿**：adapter 22（emotion 9 / vowel 6 / processor 4 / profile 3）+ orchestrator 28（chunker 12 / card 7 / assembler 4 / pipeline 5）；任务 2 后增至 **62**（orchestrator +12：卡片库 8 / speak 4）
- **任务 1 已完成（2026-10-03，真机 2c3769db，硅基流动 DeepSeek-V3 + CosyVoice2）**：成功路径全链路验证通过——
  - ①断句流水：LLM 首句 ~2s 出声，N 句播放时 N+1/N+2 已在合成，句间无缝（前句 Ended 10ms 后下句 Started）
  - ②口型：FaceDriver 采样日志（tag `FaceDriver`，2Hz）确认 viseme 跟随语音（ih/ou/oh 交替，峰值 0.47）；截图帧间嘴部差分 17-19
  - ③情绪：`<|emotion:..|>` 标记正确提取（happy/relaxed/think/surprised），字幕无标记泄漏，表情可见且 3s 回落
  - ④眨眼：自动眨眼每 1-6s 一次（14s 抓到 2 次），情绪回落不压制眨眼
  - ⑤打断：`interrupt_chat` 后 45ms 内 PlaybackInterrupted，AudioTrack 立即消失（dumpsys 验证），无崩溃
  - 过程中修了 4 个真 bug + 1 个观测坑，见附录 A.1；调试命令 send_chat/interrupt_chat/chat_state 见 docs/ai-debug-intents.md
- **任务 2 已完成（2026-10-03，真机 62fabe84 小米14/HyperOS Android16，硅基流动）**：人物卡导入 UI + 开场白——
  - orchestrator：`CharacterCardStore`（filesDir 字节落盘，纯 JVM 可测）、`AvatarSession.speak()`（无 LLM 直通 pipeline）、`clearCharacterCard()`、`spokenGreeting()`（{{char}}/{{user}} 宏替换）
  - app：`CardLibrary`（索引 ai_cards + 激活 ai_active_card 持久化 demo_settings）、CARDS 面板（双行列表：名称 + spec·version，使用中徽标，删除钮，SAF 导入 PNG/JSON，导入即自动激活）、激活 = setCharacterCard + clearHistory + 自动 speak(first_mes)；会话（重）建后自动重挂卡片提示词，激活时 AI 未配置则记 pendingGreetingFile 待会话就绪补播
  - 调试命令新增 `import_card` / `active_card` / `open_panel` / `list cards`（见 docs/ai-debug-intents.md）
  - 真机验证：PNG(V2)/JSON(V3) 导入激活 ✓、坏卡拒绝 ✓、开场白自动朗读（`*动作*` 剥离、宏替换、按序播放）✓、按人设回答 ✓、系统提示词重写（388/440 chars）✓、重启后卡片与激活态持久化 ✓、面板 UI 截图 ✓、全程无崩溃
  - 未覆盖：SAF 选择器手势流与面板内点按激活/删除（HyperOS 禁止 shell 注入触摸；二者与已验证的 import_card 命令共用同一条落盘/索引/激活路径）
- 工程底座：libs.versions.toml 加了 coroutines 1.9.0 / okhttp 4.12.0 / serialization-json 1.7.3 / kotlin-jvm / kotlin-serialization 插件；两新模块已入 settings.gradle.kts

## 三、关键设计决策（改代码前必读）

1. **口型走离线时间线，不做实时 tap**：TTS 解码后一次性 `analyze()` 出时间线，播放时按 AudioTrack 时钟采样 + VowelDriver 状态机逐帧平滑。比 AIRI 的 AudioWorklet 实时分析更稳、无黑盒依赖。若要改口型手感，调 `VowelDriver` 常量区。
2. **FaceDriver 独占全部缓动**：corelib `VrmExpressionManager` 语义是「setExpression 写目标权重 + 内部按 transitionDuration lerp + 多表情按 bind 加法累加 clamp」。FaceDriver 启动时 `setExpressionTransitionDuration(0)` 切瞬时模式，自己画所有曲线——对齐 three-vrm 每帧 `setValue` 语义。**不要**在 corelib 里再加缓动。
3. **VRM 表情名→morph 走模型自带的 expressionMap**（corelib 已解析 VRM1.0 preset+custom / 0.x blendShapeGroups），FaceDriver 按 `getAvailableExpressions()` 门控发送，缺名字自动跳过，**禁止硬编码 aa→jawOpen 之类映射**。corelib 解析时会对每个表达式的绑定权重做归一化（最强 bind 缩放到 1.0，比率保持，见 A.1 第 16 条），使"表达式权重 1.0 = 主 morph 满幅"跨模型语义一致。
4. **情绪协议是自定的** `<|emotion:name:intensity|>`，由 SystemPromptAssembler 注入指令教模型使用；可情绪集 = EmotionBlender.defs 的 7 个键。
5. **adapter 的 okhttp/serialization/coroutines 必须 `api()`** 不能 `implementation`——构造器默认参数把 OkHttpClient/JsonObject 泄进了公共签名，改 implementation 会编译失败。
6. TTS response_format 固定 `"wav"`（AiChat.kt），换 provider 若报 400 需要把格式做成设置项。

## 四、已知坑清单（每条都真实踩过）

1. AIRI 的 profile.json 有 **12 个音素条目且重名**（A/I/U/E/O/S 各两套录音）：打分按 12 条目归一化，`VowelDriver.setPhonemeGroups` 按名分组 max 合并，S 视作 ih 别名。
2. profile 里 `useStandardization` 是 JSON 布尔，**不能** `jsonPrimitive.int` 解析（直接抛 NumberFormatException）。
3. `VowelDriver.lastActiveTime` 必须初始化 0 不能 NEG_INF——t=0 时 `t-last=∞` 会让首帧起永远静音（AIRI 时钟从 0 起）。
4. 情绪标记强度正则要带 `-?`（负号），否则 `<|emotion:sad:-2|>` 整体不匹配、原文穿透进语音。
5. kotlin-jvm 插件在混 AGP 工程必须在 root `build.gradle.kts` `apply false` 声明，否则 "already on the classpath with an unknown version"。
6. wLipSync C 版 `downSample` 的 `i1=min(i0,size-1)` 是上游笔误（插值失效），Kotlin 版已改成 `i0+1`，勿"改回去"。
7. wLipSync `low_pass_filter` 是把滤波项**加在原始数据上**的半边对称卷积（ quirky 但标定数据就是这么采的）——Dsp 移植保持原样，别"修正"。
8. Compose UI 在 MIUI 上 uiautomator 只能看到部分节点（TextField 不可见）：真机冒烟走 `run-as` 直接写 prefs（先 force-stop）+ 截图(1:1 物理分辨率) + bounds 中心 tap。
9. corelib 的 `AvatarState`（渲染状态）与 orchestrator 的 `ConversationPhase`（会话状态）是两套，别混。

## 五、测试与 API 配置现状

- **API 要求**：一个同时提供 OpenAI 兼容 `/chat/completions`(流式) 与 `/audio/speech` 的服务，LLM/TTS 共用同一 Base URL + Key。
  - 已验证：硅基流动 `https://api.siliconflow.cn/v1`（2026-10-03 实测）：LLM=`deepseek-ai/DeepSeek-V3`（仍在模型列表；流式 SSE 与适配器完全兼容）；TTS=`FunAudioLLM/CosyVoice2-0.5B`，Voice=`FunAudioLLM/CosyVoice2-0.5B:alex`，**必须带 `sample_rate:16000`（数字，传字符串报 400）**，原因见附录 A.1 第 2 条；demo 的 Key 经 run-as 写入 demo_settings（明文本体在仓库根 `secrets.properties`，已 gitignore，勿提交）
  - 备选：OpenAI 官方（需海外网络，`gpt-4o-mini`+`tts-1`+`alloy`）；或任意 one-api/new-api 网关
- 填写入口：App ⚙️ 设置 →「AI 对话」→ 五项即填即存。
- 单测：`./gradlew :avatar-ai-adapter:test :avatar-orchestrator:testDebugUnitTest`
- 构建/安装：`./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 设备：`62fabe84`（小米14/HyperOS Android16，任务2起；已配硅基流动，注意 HyperOS 禁 shell input）与 `2c3769db`（任务1）；日志关注 `adb logcat -d -s AIDebug`（调试命令与事件）与 `adb logcat -d -s AndroidRuntime:E`（崩溃）与 UI 错误条（TurnFailed）。

## 六、接下来的工作 —— 任务提示词（按优先级）

> 使用方式：新会话中直接粘贴对应任务块。每个提示词都假设对方已读过本文档。

### 任务 1：成功路径真机验证 + 观感调参（拿到 API Key 后第一件事）

```
继续 AIAvatar-SDK 的 AI 层工作。先读 docs/ai-layer-handoff.md 第二章与第三章。
当前状态:48单测绿,真机冒烟通过但音频成功路径未验证(无真实Key)。
任务:设备 2c3769db 已连接。我会提供硅基流动的 API Key(见下)。请:
1. 用 run-as 把五项配置写入 app 的 demo_settings prefs(force-stop 后写,键名 ai_baseUrl/ai_apiKey/ai_llmModel/ai_ttsModel/ai_voice,
   值: https://api.siliconflow.cn/v1 / <KEY> / deepseek-ai/DeepSeek-V3 / FunAudioLLM/CosyVoice2-0.5B / FunAudioLLM/CosyVoice2-0.5B:alex),
   重启 app,让虚拟人说一段 3-4 句的长回复。
2. 逐项检查并截图:①断句流水(字幕逐句追加、第一句出声时后续已在合成) ②口型音画同步(有无明显滞后/嘴型单调)
   ③情绪标记是否被模型遵守(字幕里不应出现 <|emotion:..|> 原文;表情是否变化且3秒回落) ④眨眼 ⑤播放中点打断✕是否立即静音
3. 若口型单调:检查 logcat 里 VisemeTimeline 采样是否正常,必要时微调 avatar-ai-adapter VowelDriver 常量区(各常量含义有注释);
   若断句太碎/太迟:调 avatar-orchestrator SentenceChunker.Options(boost/minimumWords/maximumWords)。
4. 把调过的参数与观察结论写进 docs/ai-layer-handoff.md 附录A,提交代码(仓库风格 TYPE: fix/feat 中文描述)。
API Key: <在这里贴上Key>
```

### 任务 2：人物卡导入 UI（✅ 已完成，2026-10-03，见第二章 2.4）

```
继续 AIAvatar-SDK 的 AI 层工作。先读 docs/ai-layer-handoff.md 第二章;本次做人物卡导入 UI。
解析器已就绪: avatar-orchestrator card/CharacterCardParser.parse(bytes) 自动识别 PNG(酒馆 chara 键 + AIRI ccv3 键,含 zTXt)与 JSON(V1平铺/V2/V3)。
要求:
1. MainActivity 的 PanelType 加 CARDS,底部 FAB 列表加入口(参照现有 Expression FAB 写法),面板用现有 ListPanel 风格扩展成卡片列表
   (标题用 CharacterCard.name,副标题 spec+characterVersion)。
2. 导入: ActivityResultContracts.OpenDocument 选 image/png 与 application/json,读 bytes → CharacterCardParser.parse →
   成功后把【原始 bytes 存文件】到 context.filesDir/cards/<nanoid或name>.<png|json>(别序列化 data class,extensions 是 JsonObject);
   卡片索引持久化到 demo_settings(ai_cards 列表 + ai_active_card)。
3. 激活卡片: session.setCharacterCard(card)(AvatarSession 已有);切换卡片时 clearHistory + 重写系统提示。
4. 开场白: 给 AvatarSession 加 suspend fun speak(text: String)——跳过 LLM,直接 chunker切句→pipeline.submit→播放
   (复用 handleDelta 的情绪提取路径吗?不需要,开场白无标记,直接 pipeline.submit 即可,记得 emit SentenceQueued 与状态流转 THINKING→跳过直接 SPEAKING 由播放回调驱动)。
   卡片激活后自动 speak(firstMessage)。
5. 单测: 卡片文件持久化往返(存→读→parse→setCharacterCard 后 systemPrompt 包含 description)。
验收: 把任意 SillyTavern 导出的 PNG 卡导入后,提问角色能按人设回答,开场白自动朗读。提交代码,风格 TYPE: feat 中文描述。
```

### 任务 3：Room 会话存储 + 历史裁剪

```
继续 AIAvatar-SDK 的 AI 层工作。先读 docs/ai-layer-handoff.md 第二章。
任务: 用 Room 实现 history/ConversationStore 接口(现为 InMemoryConversationStore 杀进程即失)。
1. orchestrator 加 room 依赖(room-runtime/room-ktx 2.6.1 + ksp 插件,ksp 版本需匹配 kotlin 2.0.21,查 google maven)。
   实体: session(id, characterId, updatedAt) + message(id, sessionId, role, content, createdAt)。
   实现 RoomConversationStore;AvatarSession 构造参数加 store: ConversationStore = InMemoryConversationStore()(现在是内部 new 的,要改成可注入),
   demo 在 AiChatController 里装配,持久化 session id 到 prefs。
2. 历史裁剪: AvatarSession.buildRequestMessages 目前全量发,加 Options.recentTurnLimit(Int?=null)——只保留最近 N 条 user/assistant
   (system 消息永远保留且置顶)。
3. 单测: 裁剪边界(N 条/N+1 条/只有 system)、Room 读写(instrumented 或用 robolectric,若嫌重就只测裁剪逻辑+DAO 抽象)。
注意: kotlin 2.0.21 + ksp 版本兼容是本任务最大风险,先跑通一个空 Room 工程再铺开。提交代码。
```

### 任务 4：语音输入（ASR 适配器）

```
继续 AIAvatar-SDK 的 AI 层工作。先读 docs/ai-layer-handoff.md 第二章。
任务: 给对话加语音输入。
1. :avatar-ai-adapter 加 api/AsrAdapter 接口(对齐 TtsAdapter 风格): 两个实现方向二选一先做简单的——
   a) android.speech.SpeechRecognizer(免费离线可能不可用,在线需 Google 服务,国内设备大概率没有) → 
   b) OpenAI 兼容 /audio/transcriptions 适配器(whisper-1 格式,multipart 上传 wav) —— 推荐 b,
      录音用 MediaRecorder 输出 mp3/amr 到 cacheDir,松手即发。接口: suspend transcribe(audio: ByteArray, mime: String, config): String。
2. AiChatBar 加按住说话按钮(按住 MediaRecorder 录音,松手→transcribe→文本自动填入输入框,由用户确认发送;或直接发送,做成设置开关)。
3. 半双工: 按下录音键时若 phase==SPEAKING 自动 session.interrupt()(对齐 AIRI: 说话时抑制聆听)。
4. RECORD_AUDIO 权限运行时申请。
验收: 真机按住说话→识别文本→发送→回复正常。提交代码。
```

### 任务 5：视线系统（corelib lookAt + saccade，难度最高，放后）

```
继续 AIAvatar-SDK 的 AI 层工作。先读 docs/ai-layer-handoff.md 第三章(设计决策2)与记忆 filament-camera-programmatic-control。
任务: 实现注视(gaze)与 saccade 微颤。
1. 先勘察 corelib: VrmAnimationEngine/humanoid 骨骼访问能力——确认能否在 Kotlin/JNI 层对头颈/眼骨叠加旋转
   (注意记忆 filament-1683-api-constraints 的 getParent entity/instance 坑)。若无现成通道,需在 JNI 加 setLookAtTarget(x,y,z) 或骨骼偏移接口。
2. orchestrator face/ 加 SaccadeEngine,精确移植 AIRI packages/stage-ui-three/src/composables/vrm/utils/eye-motions.ts:
   分段均匀分布(400ms一档,首档0-400ms概率0.075) + 注视点±0.25世界单位抖动;目标由 trackingMode 决定(camera/touch/none),平滑交给骨骼解算。
3. 接入 FaceDriver.tick: 眨眼之后、情绪之前,把视线目标写 corelib。
4. 回归: 必须真机验证不破 SpringBone 与 VRMA 播放(记忆 vrm-springbone-center-semantics、vrma-anim-library)。
提交代码,附真机截图。
```

### 任务 6：Edge-TTS 免费适配器（开源友好）

```
继续 AIAvatar-SDK 的 AI 层工作。先读 docs/ai-layer-handoff.md 第二章。
任务: :avatar-ai-adapter 加 EdgeTtsAdapter(微软 Edge 朗读接口,免费无 Key),实现 TtsAdapter 接口。
要点: WSS 连接 speech.platform.bing.com 的 wss 端点,二进制音频(mp3/24k)分片回传;
2024 年起需要 Sec-MS-GEC 鉴权头(SHA-256 DRM token),参考开源实现 edge-tts(python) 的 token 算法,别凭记忆写。
输出 mp3 → orchestrator PcmDecoder 的 MediaCodec 路径已能解,无需新解码器。
demo 设置加 TTS 引擎切换(OpenAI 兼容 / Edge-TTS,后者不需要 baseUrl/key)。
验收: 不填任何 Key,选 Edge-TTS,虚拟人开口说话。注意该接口无 SLA,失败要优雅降级并给明确错误事件。
```

### 任务 7：SDK 化收尾（文档/发布/锦上添花）

```
继续 AIAvatar-SDK 的 AI 层工作。任务(按顺序,做完一项提交一项):
1. SDK README: 四层架构图、3 步集成示例(Gradle 依赖→AvatarView+AvatarSession→send)、API 速查表。
2. CameraDirector: orchestrator director/ 下规则式实现——phase→CameraShot 映射(THINKING→CLOSE_UP, SPEAKING→MEDIUM_SHOT,
   IDLE→FULL_SHOT,可配置),订阅 session.events 驱动 controller.setCameraShot(已有平滑运镜,勿重复造轮子);默认关闭。
3. maven-publish 配置: 两个 SDK 模块发布到 mavenLocal 起步。
4. 流式 TTS(远期): TtsAdapter 加 streaming 变体(Flow<AudioChunk>),对齐 AIRI bidirectional-ws 语义,播放按 chunk 走。
```

## 附录 A：观感调参速查（成功路径验证后更新此表）

### A.1 任务 1 实测新坑（每条都真踩过，2026-10-03）

1. **硅基流动 CosyVoice2 的 WAV 头是流式占位值**：`data` 块 size 字段写死 `0xFFFFFF00`（=-256），RIFF size 也是负数，音频实际延伸到文件尾。PcmDecoder 按 size 切片会 `copyOfRange(174, -82)` 抛 `"174 > -82"`（JDK copyOfRange 的越界消息格式），每句 TTS 全灭。已修：size 非法（≤0 或超剩余字节）时按"到文件尾"取。
2. **CosyVoice2 默认输出 24kHz，会踩 wLipSync 的分数降采样路径**：24k→16k（1.5 倍率）走 `downSample` 线性插值分支，实测语音帧的 MFCC cosine^100 得分全部塌缩为 0（静音帧反而完美匹配 S 模板），口型死。上游 C 代码该分支 AIRI 从未用过（浏览器 AudioContext 恒 48k，整数 skip）。**修法：TTS 请求直接要 `sample_rate:16000`**（硅基流动支持；OpenAI 不认识该字段，故做成 `TtsConfig.sampleRate: Int?` 可选，仅非空时发送）。不要给 8k 输入——`inputWindowSize` 会算出非 2 的幂窗口，FFT 越界。
3. **VowelDriver 静音保持的移植顺序错误（口型永久锁死）**：AIRI 原文是三步——先算 `silent = amplitude<0.04 || winnerWeight<0.05`，再 `if(!silent) lastActiveAt=now`，**最后**才补 `if(now-lastActiveAt>160ms) silent=true`。响亮帧恢复时会先刷新 lastActiveAt 再查保持，所以长停顿后能重新开口。初版把保持条件并进了第一行 `silent` 表达式，导致静默 160ms 后 lastActiveTime 永不更新→永远静音（真机表现为 top=none 恒成立）。已按上游语义修复并加回归测试。
4. **MIUI 上采样已 release 的 AudioTrack 直接崩溃**：写线程句末 `track.release()` 置空 `activePlayback` 与主线程 Choreographer 的 `FaceDriver.tick → positionSeconds()` 存在竞态，MIUI 抛 `IllegalStateException: Unable to retrieve AudioTrack pointer for getPosition()` 且是 FATAL（app 闪退重启、视口黑屏）。已修：`TrackPlayback.positionSeconds()` 全程 try/catch，异常时返回"已播完"（口型自然闭合）。另：AvatarSession 对 SentenceFailed 补了堆栈日志（tag AvatarSession），此类问题以后直接看堆栈。
5. **CosyVoice2 输出电平很低**（帧 RMS p95≈0.08 满量程），VowelDriver 的 AIRI 常量按麦克风级输入（0.3~1.0）标定，直接喂进去口型只开 15%。已修：`WlipsyncLipSyncProcessor.analyze` 末尾按整段 clip 做 p95 音量归一化（目标 0.75，增益上限 12 倍，静音不动）——uLipSync "profile gain" 的等价物，provider 无关。修后响亮帧 viseme 达 0.47（AIRI 文档示例 0.49）。
6. **观测坑：相机每次重启 app 都复位到默认全身景**。截图差分验证口型/表情前必须先 `ai_cmd camera_shot closeup`，且差分坐标要按当前取景重新标定——本次在全身景下按旧特写坐标量了半天"鼻子"，误判表情系统全坏，浪费近一小时。`set_expression jawOpen` + closeup 全帧差分 2.0 才是可信信号。
7. **adb run-as 写文件的引号坑**：`adb shell run-as PKG sh -c 'cat > shared_prefs/xx.xml'` 会被 adb 拆词，重定向落在 device shell（cwd=/）报 `can't create file`。正确写法：`adb shell "run-as PKG sh -c 'cat > /data/data/PKG/shared_prefs/xx.xml'" < local_file`（外层双引号 + 绝对路径）。
8. **coroutines-test 1.9.0 的 `advanceUntilIdle()` 不投递 SharedFlow 挂起恢复**：runTest 里 `backgroundScope` 订阅 `MutableSharedFlow` 后同步 `tryEmit`，`testScheduler.advanceUntilIdle()` 实测收不到（订阅协程仍挂起），`testScheduler.runCurrent()` 才可靠投递。AvatarSessionSpeakTest 已按此写法（注释也写在测试里），以后写事件流单测别再用 advanceUntilIdle 泵。
9. **HyperOS/Android 16 禁止 shell 注入触摸**：`adb shell input tap` 抛 `SecurityException: Injecting input events requires INJECT_EVENTS`（旧设备的 MIUI 也只有部分机型放行）。UI 驱动改走 `ai_cmd open_panel <面板名>`（本次新增），文件选择器这类必须真手点的就放弃自动化。
10. **设置页逐字符提交 + 会话即时重建 = "打字把正在播的回复杀掉"**：设置五项是即填即存，`produceState` 以 `aiPrefs` 为 key，每敲一个字符就 close+rebuild 一次会话——打字期间在播的回合反复被打断（用户感知"还没输完就没声/崩了"），且**半截配置会被持久化**（真机实测 prefs 里存着 `...:a` 的残缺音色，重启后继续 400 全灭）。已修：`produceState` 里 `delay(800)` 防抖，输入停稳才重建；注意 run-as 写 prefs 模拟不了"打字中"（SharedPreferences 不重载已运行的进程），只能靠真手测。
11. **HTTP 200 + 非 WAV body 会让 PcmDecoder 抛谜语 "Not a RIFF file"**：TTS 适配器原本只拦非 2xx；实测硅基流动对残缺音色回的是 `400 Invalid voice`（能正常报错），但网关/代理可能对失败请求回 200 的 HTML/JSON 页面，喂进解码器只剩谜语（本次真机事故的唯一线索，200 来源未能离线复现——疑似网络层劫持）。已修：适配器按声明的 response_format 校验魔数（WAV=RIFF / MP3=ID3或帧同步 / OGG=OggS），不符时抛带 content-type + 24 字节预览的 IOException，真实原因直接进堆栈；MockWebServer 单测覆盖 400 / 200非WAV / 正常WAV / MP3 四条路径。同时 `SentenceFailed` 上了 UI 错误条（"第 N 句语音合成失败：…"，6s 自动清除），句子失败不再静默。
12. **切分决策只能在标点处做，不能在任何字符位置**：初版 SentenceChunker 把 `maximumWords` 实现成"逐字符扫描一旦超 12 词就在当前位置强制下刀"——这是对 AIRI 的移植偏差（上游只在标点字符处评估 'limit' 切分，无标点的长句一直等到 flush）。中文 12 个词很快就到，长句被腰斩在短语中间，TTS 韵律和字幕都破碎（用户感知"在任意地方断句"）。已修：删除任意位置切分；超限后**软标点也成为切点**（AIRI 'limit' 语义），无标点长句等下一个标点或 flush。顺带修了同源问题：连续标点（`什么？！`）切出的裸 `！` 碎片此前会变成独立 TTS 请求并消耗 boost 预算，现在纯标点/纯动作碎片直接丢弃。
13. **`WRITE_BLOCKING` 写完 ≠ 播完，`release()` 会吞句尾**：AudioTrackPlaybackQueue.playItem 写完 PCM 后立刻 `stop()+release()`——write 返回只代表数据进了轨道内部缓冲（本项目 `minBuf*2` ≈ 0.13-0.3s @16k），release 销毁轨道把未播出的句尾整段丢掉，用户感知"前一句最后一个字没说完就断句"。任务 1 观测到的"前句 Ended 10ms 后下句 Started"其实是 Ended 在尾巴未播完时就触发的证据。已修：写完后 `drainPlayback` 轮询播放头到片尾再 stop/release（20ms epsilon 吸收混音器位置粒度，remaining+1.5s 截止防卡死设备堵队列；stopRequested 期间照常走 interrupted 路径）。验证方法：对照 logcat 里 `clip #N pcm=X.XXs`（AvatarSession tag）与 SentenceStarted/Ended 时间戳，墙钟差 ≥ pcm 时长即完整播出；注意 Started/Ended 走 Main 派发，±50ms 抖动正常。
14. **orchestrator JVM 单测遇到 `android.util.Log` 会抛 "not mocked"**：AvatarSession 的监听器常驻路径上一旦加了 Log 调用，AvatarSessionSpeakTest 就红。已在该模块 build.gradle.kts 加 `testOptions { unitTests.isReturnDefaultValues = true }`（Log 变 no-op），以后在 orchestrator 主代码加日志不必绕道。
15. **FaceDriver 静止态必须"静默"，零值不能每帧重发**：`send()` 的去重守卫原本带 `&& value != 0f` 例外——说话结束后 else 分支每帧产出 0，守卫永不跳过，于是每帧 `setExpression(vowel, 0)`；而 corelib `VrmExpressionManager.setExpression` 对 `weight <= 0` 的语义是 **`targetWeights.remove(name)`**（见其源码 248-254 行，同名权重是替换、0 是删除），等于说话一结束就把手动设置的嘴部表情（面板 presetExpressions 的 aa/ih/ou/ee/oh）每帧抹掉。说话前 `sent` 缓存为空、零值走 `previous == null` 跳过，所以**只有说过话才复现**。已修：守卫去掉零值例外，静止时送最后一次清零即静默（所有权交还手动表情）；说话期间 viseme 变化超容差照常下发、独占嘴部不变。真机两轮验证：每轮结束后 `set_expression aa 1.0` 截屏差分集中在嘴部且 4 秒保持，第二轮说话口型日志正常。注意裁剪所有权时的行为模型：情绪活跃期（3s auto-reset 内）手动嘴部表情仍会被情绪通道覆盖，这是设计内所有权。
16. **转换模型的 VRM preset 弱绑定会让口型/情绪整体变弱，解析时必须归一化**：换默认模型到 SK_Sun_PERFORMANCE（ARKit 52 词素 + 生成 preset 的转换模型）后嘴部动作非常小。解析其 GLB 发现全部 preset 绑定权重弱（aa 0.5 / ih·ou 0.2 / ee·oh 0.3 / sad·surprised 0.25 / happy 0.5），而 custom ARKit 词素全是 1.0——这意味着不只口型，LLM 情绪驱动的表情也只有 1/4~1/2 强度（blink 恰好 1.0 所以眨眼正常，最容易漏查）。驱动侧幅度正常（FaceDriver 日志 volume/top 与旧模型一致），纯模型资产问题。已修：`VrmExpressionManager.normalizeBindWeights` 在解析时（VRM 1.0 与 0.x 两条路径）把每个表达式的绑定权重缩放到最强 bind=1.0（比率保持；已全权重的模型恒等、零行为变化，四个仓库模型实测只有 SK_Sun 被放大）。注意副作用：同一 morph 组合、仅幅度不同的 preset（如此模型的 ee=[0.3×jaw,0.3×stretchL,0.3×stretchR] 与 ih=[0.2×同三 morph]）归一化后形状相同——幅度差异本就被 viseme 动态淹没，可接受。真机验证：aa=1.0 从半开变全开（截屏），说话中段嘴部帧间差 6~9%、句间停顿 1.7%。

### A.2 调参速查表

| 现象 | 调哪里 |
|---|---|
| 打字/改设置时正在播的回复被打断 | 正常（配置变化即重建）；打字过程中不应发生——确认 MainActivity `produceState` 的 800ms 防抖还在 |
| 全句 TTS 400 Invalid voice / 200 non-wav body | prefs 里 `ai_voice` 多半是半截值（打字被持久化），看错误条或堆栈里的响应预览即可定位 |
| 句子太碎 | `SentenceChunker.Options(minimumWords↑ / boost↓)`（切分只发生在标点处，见 A.1 第 12 条） |
| 句子太迟（等待感） | `maximumWords↓`（超限后逗号更早成为切点）、软标点 boost↑ |
| 嘴张不开/太夸张 | 优先查 A.1 第 5 条（电平归一化是否生效，看 `FaceDriver` 日志 volume）；仍需要时再动 `VowelDriver.OUTPUT_GAIN / WINNER_CAP` |
| 口型拖泥带水 | `RELEASE_RATE↑`（30→更高） |
| 口型抖动 | `ATTACK_RATE↓` |
| 表情太僵 | `EmotionBlender.defs` 主权重（AIRI 用 0.7~0.8 修过僵笑） |
| 情绪切太快/太慢 | `blendDuration`（0.15~0.6s）与 3s 自动回落 |
| 口型完全不动 | 先看 logcat `FaceDriver`（2Hz 采样：t/volume/top）与 `AvatarSession`（句失败堆栈），再对照 A.1 第 1/2/3/4 条 |

验证期临时加的观测点（保留）：`FaceDriver` debugTick（播放中 2Hz 采样日志）、`AvatarSession` 句失败堆栈与 `clip #N pcm=X.XXs` 时长日志（核对句尾是否被截断，见 A.1 第 13 条）、`AIDebug` 的 `chat:` 事件时序（SentenceQueued/Started/Ended/EmotionChanged/Turn*）与 `send_chat`/`interrupt_chat`/`chat_state` 调试命令（用法见 docs/ai-debug-intents.md）。

## 附录 B：记忆索引（新会话自动加载）

- `airi-source-anchors` — AIRI file:line 锚点与对齐参数（本文档的上游依据）
- `sdk-four-layer-progress` — 本次落地的进度/坑清单精简版
- 其余 corelib 相关：`pbr-only-rendering`、`filament-1683-api-constraints`、`filament-light-rig-spot-only`、`filament-pcss-adreno-cliff`、`filament-camera-programmatic-control`、`vrm-springbone-center-semantics`、`vrma-anim-library`、`android-device-debug-workflow`
