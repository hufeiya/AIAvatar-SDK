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
| `api/TagCue.kt` + `api/TagExtractor.kt` | 多模态行内标签协议（任务 8）：`TagCue` sealed（Emotion/Action/Camera，只带字符串，映射归 orchestrator）；`ExtractionResult(cleanText, cues)`；接口 `TagExtractor` |
| `api/TtsAdapter.kt` | `suspend synthesize(text, config): TtsResult`（V1 句级整段返回） |
| `volcengine/VolcanoEngineTtsAdapter.kt` | 火山「豆包语音合成大模型 2.0」适配（双服务商改造）：V3 双向流式 WebSocket（`wss://openspeech.bytedance.com/api/v3/tts/bidirection`）对外仍是句级整段语义——每次合成开一条连接跑完 StartConnection→StartSession→TaskRequest(整句)→FinishSession→收音频→SessionFinished；鉴权 `X-Api-Key`（豆包语音控制台的 API Key）+ `X-Api-Resource-Id: seed-tts-2.0`；输出恒 PCM 16bit LE（`audio_params.format=pcm`，采样率取 TtsConfig.sampleRate 默认 16k），口型管线免解码直喂；二进制帧 4 字节头 `[ver<<4|hsize, type<<4|flags, ser<<4|comp, 0]` + event(i32) + 可选 conn/session id(u32len+bytes) + payload(u32len)，编解码对齐火山官方 arkitect Python SDK；`endpoint` 参数可注入（MockWebServer ws 测试）；每条消息 30s 看门狗防管道悬挂 |
| `api/AsrAdapter.kt` + `openai/OpenAiCompatibleAsrAdapter.kt` | 语音输入（任务 4）：`suspend transcribe(audio, mime, config): String`；OpenAI 兼容 `POST {base}/audio/transcriptions`（multipart `model`+`file`，文件名/Content-Type 由 mime 推导，m4a→`audio/mp4`）；language/prompt 缺省不发送（硅基流动只收 file+model，多余字段有 400 风险）；非 2xx 与 200 非 JSON 都带响应预览抛 IOException（A.1 第 11 条同源教训） |
| `api/LipSyncProcessor.kt` | `analyze(pcm16, sampleRate): VisemeTimeline`（**离线时间线**，帧 ≈64ms 一帧） |
| `emotion/InlineTagExtractor.kt` | 流式标签过滤：`<emo:名:强度>/<act:名>/<cam:机位>` + 老协议 `<|emotion:..|>`（IGNORE_CASE），跨 delta 缓冲半截标签、尾部 holdback（64 字符上限）、"裸 `<` 在真标签前"防吞段、flush 只丢疑似标签前缀 |
| `model/ChatTypes.kt` | ChatMessage/LlmConfig/LlmStreamEvent/TtsConfig/TtsResult/TtsAudioFormat |
| `openai/OpenAiCompatibleLlmAdapter.kt` | OkHttp SSE 手解 `data:` 行；`channelFlow + awaitClose{call.cancel()}` 取消即断连 |
| `openai/OpenAiCompatibleTtsAdapter.kt` | POST `{base}/audio/speech`，response_format 可配（demo 默认 wav） |
| `openai/SiliconFlowVoiceCatalog.kt` | 音色清单接口（双服务商改造）：GET `{base}/audio/voice/list` 返回 `{"result":[…]}`（条目 uri+name）；**永不抛异常**——网络/鉴权/解析任何失败都返回空列表，设置页合并静态兜底音色（实测空账号返回 `{"result":[]}`，空列表是常态） |
| `lipsync/Dsp.kt` + `MfccFrontend.kt` | **wLipSync/uLipSync MFCC 管线精确移植**：RMS→FIR低通→16k降采样→预加重0.97→汉明窗→峰值归一→FFT→30 Mel→10log10→DCT→取系数1..12 |
| `lipsync/WlipsyncProfile.kt` | 解析标定 profile（`src/main/resources/wlipsync/profile.json`，**直接复用 AIRI 的文件**），cosine^100 打分归一化 |
| `lipsync/VowelDriver.kt` | AIRI vowel-driver.ts 逐常量移植：音量 `min(0.9v,1)^0.7`、winner≤0.7/runner×0.6≤0.35、静音门限 0.04/0.05/160ms、非对称指数平滑升50/降30、死区0.01、输出×0.7。元音槽序 **AA,IH,OU,EE,OH** |
| `lipsync/WlipsyncLipSyncProcessor.kt` | 组合前端+profile→VisemeTimeline；暴露 `vowelLayout`（音素→元音槽映射） |

### 2.2 `:avatar-orchestrator`（android library，包 `com.neethu.orchestrator`）

| 文件 | 职责 |
|---|---|
| `session/AvatarSession.kt` | **门面**：`send/sendAndAwait/interrupt/close/setCharacterCard`；`llmConfig`/`ttsConfig` 属性；`phase: StateFlow` + `events: SharedFlow`。内部组装 extractor→chunker→pipeline，collect LLM 流；任务 8 起三路分派 TagCue（act→gestureDriver / cam→setCameraShot 即发即执行；**emo 挂到其后第一个句子、开播瞬间才应用**（2026-10-04 第三轮，hold=clip 时长+1.2s 余量，防 TTS 排队期间被 3s 归零吃掉；同名序列=眨眼模式按 200ms 步进补发），`actionCatalog` 可注入属性，`speak()` 也走 extractor（开场白支持标签），Options 加 `protocolInstructions/enableLlmGestures/enableLlmCamera` |
| `session/ConversationPhase.kt` | IDLE/THINKING/SPEAKING（注意与 corelib 的 `AvatarState` 渲染状态是两回事） |
| `session/AvatarEvent.kt` | 会话事件流；任务 8 加 `ActionStarted(tag,label)` / `CameraChanged(shot)` |
| `gesture/ActCatalog.kt` | `ActionEntry(tag,label,assetPath?,filePath?)`——LLM 动作目录条目（任务 8） |
| `gesture/GestureDriver.kt` | `<act:>` 播放器：目录查名→loadVrmaAnimation(From file)→playVrmaAnimation(loop=false)；单动画槽"最后指令赢"；interrupt 时 stop；open 类可注 fake（任务 8） |
| `chunker/SentenceChunker.kt` | AIRI tts-chunker 规则：硬标点 `.。?？!！…⋯～~\n\r\t`、软标点 boost=2/min=4/max=12、小数点保护、`...`点串一次切、`*动作*` 剥离（跨 delta 缓冲未闭合段）；词计数 `java.text.BreakIterator`（可注入 WordCounter） |
| `pipeline/SpeechPipeline.kt` | TTS 并发≤4（Semaphore）、completed 按 sequence 严格有序入队、失败用 failedSequences 跳过前沿不卡队、`awaitTurnComplete()`、cancelTurn 级联 |
| `audio/PlaybackQueue.kt` / `AudioTrackPlaybackQueue.kt` | maxVoices=1 FIFO；写线程 4096 帧块写；stopAll 从调用线程 pause+flush 即时静音；`ActivePlayback.positionSeconds()` 用 AudioTimestamp 音频时钟（FaceDriver 靠它对口型） |
| `audio/PcmDecoder.kt` | WAV 纯 Kotlin 解析（16bit PCM/8bit/32f、立体声下混）+ MP3/OGG 走 MediaExtractor+MediaCodec(MediaDataSource) |
| `face/FaceDriver.kt` | Choreographer 每帧混合：口型(时间线采样+VowelDriver 状态机)→情绪→眨眼；**所有权规则**：说话中口型独占嘴部、结束后 viseme 从 0 淡入情绪目标(blend-back)、眼区情绪压制眨眼；`controller.setExpressionTransitionDuration(0)` 后全部缓动自绘 |
| `face/EmotionBlender.kt` | 组合表 13 种标准情绪（2026-10-04 第三轮重做：VRM 预设打底 + ARKit 微表情叠层——eyeSquint/browDown/mouthPress 等，模型没有的 morph 按条门控优雅降级；`think` 曾因模型无此预设整条失效）；`apply(cue, holdMs)`（<0=不自动归零）、easeInOutCubic、起点捕获当前显示值、默认 3s 自动回 neutral、eyeAreaActive 判定 |
| `face/MicroMotionEngine.kt` | 眨眼：sin(πt)/0.2s/间隔U(1,6)s |
| `face/SaccadeEngine.kt` | 视线 saccade（任务 5）：AIRI eye-motions.ts 精确移植——注视点 0.8-4.8s 分段均匀换点 + ±0.25 抖动 + snap 重注视；`GazeMode`(CAMERA 默认/POINT/NONE)，FaceDriver.tick 写 corelib，头颈眼骨骼解算与平滑在 corelib `VrmLookAtEngine` |
| `card/CharacterCard(+Parser)` | Tavern V1 平铺/V2/V3(`data.*`)；**PNG tEXt/zTXt 解析，`ccv3` 与酒馆 `chara` 双键兼容**（AIRI 只有导出没有导入，这是我们补的能力）；未知字段 extensions 保留 |
| `card/SystemPromptAssembler.kt` | AIRI resolveSystemPrompt 顺序 `[systemPrompt,description,personality,scenario]` join `\n\n`；任务 8 起 assemble() **只拼人设**，`multimodalProtocolBlock(cameras, actions)` 由 AvatarSession 每上下文钉一次（2026-10-04 起，修掉旧版卡片双重注入；目录变更原地重钉不丢历史）；**发送时与人设合并为一条 system**（2026-10-04 晚：硅基流动对两条 system 直接 400，A.1 第 34 条） |
| `history/ConversationStore.kt` | 接口 + InMemoryConversationStore(80条环形)；`ConversationDatabase.kt`（Room 实体/DAO/单例库，任务3）+ `RoomConversationStore.kt`（镜像读+异步落库，任务3） |

### 2.3 `:app` 集成

- `AiChat.kt`：AiChatPrefs(5项配置+持久化键 `ai_baseUrl/ai_apiKey/ai_llmModel/ai_ttsModel/ai_voice`，prefs=`demo_settings`)、AiChatController(配置变化即重建 session，AIRI getProviderInstance 语义)
- `MainActivity.kt`：底部 `AiChatBar`(输入/发送/打断/状态徽标/对话记录面板[用户行在上+虚拟人行在下，可滚动自动贴底]/错误条)、produceState 装配 session、事件收集；视角 badge 移到 bottom=180dp
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
- **任务 3 已完成（2026-10-03，真机 62fabe84）**：Room 会话存储 + 历史裁剪 + 设置页改版（折叠分类/拖拽高度/上下文类别）——
  - **orchestrator**：加 room 2.6.1 + ksp `2.0.21-1.0.28`（KSP 插件标记在 **Maven Central** 不在 google maven，google maven 查会 404）；`history/ConversationDatabase.kt`（SessionEntity(id,characterId,updatedAt) + MessageEntity(id 自增,sessionId,role,content,createdAt)，同步 DAO，单例 db `avatar_conversations.db`）+ `RoomConversationStore`——同步 `ConversationStore` 接口的 Room 适配：**内存镜像为读路径事实来源**（追加先动镜像再经单线程 IO scope 写库，进程存活期读写有序；仅被杀时最后一笔有毫秒级丢失窗口），首次访问 `runBlocking(Dispatchers.IO)` 懒加载一次；每次追加顺手 upsert 会话行（updatedAt/characterId），上下文列表按最近使用排序
  - **AvatarSession**：构造参数 `store: ConversationStore = InMemoryConversationStore()` 可注入；`Options.recentTurnLimit(Int?=null)` 裁剪——只发最近 N 条 user/assistant，system 每轮现拼恒置顶不受影响；新增公开 `protocolBlock()`（与请求实际注入的多模态协议块逐字一致，设置页只读展示用）
  - **app**：会话身份 = `SessionIdentity(prefs, contextId)`——配置**或**上下文变化即重建会话；`ai_context_id` 持久化，重启续用同一上下文；`recentTurnLimit = 40`（Room 历史无限增长，请求只带最近 40 条 ≈20 轮）；设置页改版：①半屏(0.6)⇄全屏拖拽（标题+把手 pointerInput 连续跟手、松手 >0.8 吸附全屏、Animatable 回弹）②四个折叠类别（AI 配置/对话上下文/动画资源/画质设置，默认全展开，chevron 旋转 + expandVertically）③「对话上下文」类别 = 新建按钮 + 历史列表（卡片名·消息数·最近时间·当前徽标·删除，删当前自动新建）+「标签协议提示词（只读）」默认折叠展示
  - **调试命令**：`contexts` / `new_context` / `select_context <id前缀>`（见 docs/ai-debug-intents.md）
  - 单测 **117 全绿**（+11：AvatarSessionTrimTest 6——N 条/N+1/只有 system/null 全量/store 注入；RoomConversationStoreTest 5——fake DAO 懒加载/镜像即读+会话 touch/清空/跨实例恢复/最近使用排序，不起 Robolectric）
  - 真机验证（62fabe84，硅基流动 DeepSeek-V3）：对话后 `run-as` 拉 DB 确认 sessions/messages 落库（历史保留原始标签 ✓）；**force-stop 重启后模型逐字复述重启前的第一句提问**（历史恢复铁证）；new_context 后模型对旧对话"不记得"（上下文隔离 ✓）；select_context 切回后按 4 条历史继续对话；contexts 列表按最近使用排序；全程零 FATAL。设置页截图：拖拽把手/折叠类别/半屏默认渲染 ✓
  - 已知未覆盖：设置页拖拽手势与折叠点按需真手验证（HyperOS 禁 shell 注入触摸，adb 无法驱动 swipe）；上下文删除按钮未真机点按（与已验证的 DAO deleteFor 同一路径）

- **默认待机 Arms Down + 冷启动即待机（2026-10-03，真机 62fabe84，用户反馈"默认不要 T-pose、idle 用 Arms Down 不要 look around"）**：
  - **冷启动 T-pose 根因**：引擎 `setIdleAnimation` 只挂槽位不启动播放，idle 只在"一次性动作播完"或 `stop()` 时接管——冷启动谁都不触发，模型停在绑定姿态。已改接管语义：挂 idle 时无动作在播 → **立即进入待机循环**；展示旧 idle 时挂新 idle → 热切换从第 0 帧起播；展示 idle 时挂 null → 回 rest pose；动作播放中不打断（播完/stop 自然回新 idle）
  - **`resolveIdleAction` 二级坑**：候选集先按文件名含 "idle" 过滤，而 **"Arms Down" 文件名不含 idle** 永远进不了候选——IDLE_PREFERENCE 首位改 Arms Down 后仍会静默落到第二优先级。已修：优先级表直接在库全量里按文件名精确匹配，含 idle 的任意文件作末位兜底
  - **idle 与 AI 会话解耦**：原来 idle 挂在 `session.idleAction`（AI 会话建立后才生效，未配 AI 时冷启动必 T-pose）。app 改为 `applyIdle` 直挂 controller（`LaunchedEffect(state)` 模型每次加载后按持久化值/内置优先级挂载），面板长按与 `ai_cmd set_idle/idle_off` 同改；`session.idleAction` API 保留（SDK 集成者用），demo 不再走
  - **renderer 同源去重**：`idleSource` 记录已挂来源，重复 `setVrmaIdleAnimation(同路径)` 直接返回（否则 produceState/LaunchedEffect 重组反复重解析+从头起播，待机莫名词跳）；模型重载时随引擎重建清空
  - `Arms Down.vrma` 是 0.0417s **单帧静态姿势**（循环即恒定垂臂站立）；真机验证：冷启动截图即垂臂站姿、LLM 两手势（挥手+握拳）播完回 Arms Down、`set_idle` 换 Look Around 立即转头（6.33s clip 热切换）再切回 Arms Down，全程零 FATAL

- **任务 4 已完成（2026-10-03，真机 62fabe84，硅基流动）**：语音输入（ASR）+ 三种输入模式——
  - **adapter**：`api/AsrAdapter`（`suspend transcribe(audio: ByteArray, mime: String, config): String`，对齐 TtsAdapter 风格）+ `AsrConfig(model, language?, prompt?)` + `OpenAiCompatibleAsrAdapter`——multipart 上传 `/audio/transcriptions`；错误全带响应预览（`ASR HTTP 4xx` / `non-JSON body`）
  - **三种输入模式（互斥，持久化 `ai_input_mode`，左上角 InputModeSelector 下拉框切换）**：`MANUAL 手动点击`=完整 UI（全部 FAB 可见）；`TEXT 打字输入`/`VOICE 语音模式`=**进入时自动隐藏所有界面按钮**（拖拽 FAB + 整列 FAB 都不渲染，切离手动时自动收起面板）。聊天条按模式变形：VOICE 且输入空=整条"按住 说话"；关闭"语音直接发送"且识别出文本=小按住键 + 可改文本框 + 发送键（确认形态）。聊天条贴底位置随按钮显隐变（按钮显示 96dp 给 FAB 让位，隐藏 12dp）
  - **按钮显隐开关（用户反馈第二轮）**：模式下拉框右侧的「隐藏按钮/显示按钮」药丸——任何模式下都可临时翻转 FAB 显隐（打字/语音模式里偶尔要换模型/开设置）；`buttonsVisible` **刻意不持久化**，每次切模式/冷启动回到该模式默认（MANUAL=显示，其余=隐藏），隐藏时顺手收起面板；`ai_cmd show_buttons on|off`（无参翻转）供 adb 驱动验证
  - **录音**：`VoiceRecorder`——MediaRecorder AAC/16kHz/单声道/MPEG_4(.m4a) 到 cacheDir（识别后即删）；`stop()` 抛 RuntimeException（按太短/无采样）返回 null 并清理；按住手势 = `pointerInput + detectTapGestures(onPress){ start; tryAwaitRelease; end }`；**半双工**：按下的瞬间 `phase==SPEAKING` 先 `session.interrupt()`（对齐 AIRI 说话时抑制聆听）；`DisposableEffect` onDispose `voiceRecorder.cancel()` 防切模式/退出占麦；RECORD_AUDIO 运行时权限：无权限首按弹系统框（拒绝上错误条）
  - **ASR 配置**：`VoicePrefs(asrModel 留空=自动, autoSend 默认开)` 持久化 `ai_asr_model`/`ai_voice_auto_send`——**刻意不并入 AiChatPrefs**（会话身份 = AiChatPrefs + 上下文，改 ASR 配置不应重建会话杀掉在播回复）；`resolveAsrModel`：baseUrl 含 siliconflow → **`Qwen/Qwen3-ASR-1.7B`**（需求指定），其他 → `whisper-1`；设置页「AI 配置」加 ASR 模型行 + 直接发送开关
  - **调试命令**：`transcribe <音频文件>`（不走麦克风、无需权限，与按住说话同一 ASR 链路）/ `voice_record <秒>`（真录音链路，录前先打断）/ `set_mode manual|text|voice`（MIUI 禁触摸注入的 UI 驱动）
  - **单测 122 全绿**（adapter +6：happy/401/200非JSON/空文本/language+prompt 透传/wav mime 映射；app +4：resolveAsrModel）
  - **真机验证**：host 用硅基流动 TTS 合成"今天天气真不错，我们一起出去散步吧。"mp3 → push → `transcribe` 识别**逐字一致**（默认模型推断生效）；半双工打断生效（`voice_record` 发起时正在播的回复 PlaybackInterrupted）；三模式 `screencap` 截图：手动=FAB 齐全+输入条、打字/语音=按钮全隐（输入条/按住说话）、左上角下拉框常驻且标签正确；force-stop 重启保持语音模式（prefs `ai_input_mode=VOICE`）；`voice_record` 无权限路径报错清晰
  - **已知未覆盖**：MediaRecorder **成功**链路需真手——HyperOS 麦克风权限墙（见 A.1 第 21 条），首按"按住说话"弹系统框授权后才可录；autoSend=false 确认流程与下拉菜单点选同样需真手（禁触摸注入）

- **双服务商打通 + 设置页全下拉化（2026-10-03，真机 2c3769db，硅基流动+火山引擎）**：用户需求「除 API Key 外所有 AI 配置都是下拉框」+ 火山引擎全量接入——
  - **adapter**：`volcengine/VolcanoEngineTtsAdapter`（见 2.1 表；协议帧逐字段对齐官方 arkitect SDK，`parseFrame` 的 ptr 必须 `= headerSize*4` 整体定位，逐字节步进差一位就是 StringIndexOutOfBounds）+ `openai/SiliconFlowVoiceCatalog`（永不抛异常的音色清单）
  - **app 目录收口**：`AiProviders.kt`——`AiProvider` 枚举（端点/LLM 清单/TTS 清单/音色清单/全部默认值）+ `resolveLlmModel/resolveTtsModel/resolveVoice` = **校验+默认**（存储值不在本服务商清单一律落默认，兜住 UI/ai_cmd/旧 prefs 三条写入路径的跨服务商泄漏；真机实测：切火山后 DeepSeek-V3 残留被原样发给 Ark 401，修复后 chat_state 全部正确回落）+ `composeVoiceRef`（硅基流动音色=CosyVoice 引用 `FunAudioLLM/CosyVoice2-0.5B:<短名>`，MOSS-TTSD 实测也收这个格式=两模型音色互认；`speech:` 克隆 URI 原样放行）
  - **AiChatPrefs 重构**：`provider/llmModel/apiKeySiliconflow/apiKeyVolcano/ttsSameProvider(默认 true)/ttsProvider/ttsModel/voice/llmCamera`；key 按服务商分存互不覆盖；**旧 prefs 迁移**：`ai_baseUrl` 推断服务商（volces.com→火山）、旧 `ai_apiKey`→硅基流动 key，模型/音色原键复用零丢失（真机 2c3769db 原地升级验证逐字段正确）；ASR 固定走硅基流动（key 复用硅基流动那份），LLM/TTS 都不选硅基流动时设置页才单独露「硅基流动 Key（语音识别用）」
  - **设置页**：大模型服务商/大模型/TTS 服务商/TTS 模型/音色/ASR 模型全部下拉框（`SettingsDropdownRow`），「语音合成与大模型同服务商」Checkbox（默认勾选，取消瞬间 TTS 服务商初始化为当前生效值防跳变），TTS 独立时才显示 TTS 服务商下拉与（服务商不同于 LLM 时的）TTS Key；音色下拉尽力从 `/audio/voice/list` 拉取、失败/为空合并静态兜底
  - **清单与默认值（host 实测核验后锁进单测）**：硅基流动 LLM=DeepSeek-V4-Flash(默认)/DeepSeek-V3/Qwen3.8-27B（三项均在 /v1/models 在册）；硅基流动 TTS=**fnlp/MOSS-TTSD-v0.5(默认，注意 fnlp/ 前缀，裸 MOSS-TTSD-v0.5 报 Model does not exist)**/CosyVoice2-0.5B，音色 alex/anna(默认)/bella/benjamin/charles/claire；火山 LLM=doubao-seed-2-0-mini-260428(默认) 等 6 个（用户给定，Ark key 未签发无法实测）；火山 TTS=seed-tts-2.0 唯一，音色 zh_female_vv_uranus_bigtts(默认) 等 6 个
  - **调试命令**：`set_provider siliconflow|volcano` / `set_tts_provider siliconflow|volcano`（自动取消同服务商勾选）/ `chat_state` 扩展输出已解析生效的双厂商配置
  - **单测 144 全绿**（+22：VolcanoEngineTtsAdapterTest 4——MockWebServer ws 升级扮服务端逐帧断言协议序/speaker+pcm16k 参数/SessionFailed 错误浮出/空音色兜底；SiliconFlowVoiceCatalogTest 5；AiProvidersTest 7——目录锁定的产品默认值/音色引用拼装/跨服务商防泄漏/迁移推断/配置完整性）
  - **真机验证（2c3769db）**：旧 prefs 原地升级迁移逐字段正确→SF 全链路回归（V3+CosyVoice2 流水/口型/标签照旧）→`set_provider volcano` 401 错误清晰上错误条（key 不是 Ark key，见下）→防泄漏修复后 chat_state 三值正确回落→**SF LLM+火山 TTS 混合链路两轮**：`set_tts_provider volcano` 后整轮对话 TurnCompleted，4 句流水播放、FaceDriver viseme 跟随（volume 峰值 0.82）、PCM 16k 免解码直喂口型管线，字幕零标签泄漏，全程零 FATAL→设置页截图：服务商/模型/Key 下拉+Checkbox+条件显隐（TTS 独立时火山 Key 才出现）+音色下拉 6 项带「（当前）」标记，点开交互正常→ASR 闭环（TTS 合成 wav push→transcribe 逐字一致）→恢复默认配置 force-stop 重启持久化正确
  - **⚠️ 火山是两把钥匙 → 已按两字段落地**：`020b5acf-…`（豆包语音控制台，TTS WebSocket `X-Api-Key`）与 `ark-…`（方舟控制台，大模型）实测**互不通用**（互调 401/403）。AiChatPrefs 拆为 `apiKeyVolcano`（方舟·大模型）+ `apiKeyVolcanoTts`（豆包语音·合成），设置页在选火山 TTS 时**恒显**豆包语音 Key 字段（勾「同服务商」也要单填）；旧单字段遗留值按形态迁移（`ark-` 前缀→方舟，UUID 形→豆包语音，`splitLegacyVolcanoKey`）。方舟 key 用户已补签：**火山全链路真机打通**——doubao-seed-2-0-mini + seed-tts-2.0 四句流水/口型/标签协议（cam+emo+act 三路全触发）全过；`doubao-seedream-5-0-pro-260628` 是图像生成模型，chat 返回 RPM 配额限制（保留在清单，对话链路勿选它）。密钥明文在仓库根 secrets.properties（已 gitignore）：SILICONFLOW_API_KEY / VOLCANO_TTS_API_KEY / VOLCANO_ARK_API_KEY
  - 已知未覆盖：设置页下拉菜单真手点选（adb 已验证菜单可开、选项/当前标记正确）；火山 LLM 端到端对话（等用户补 Ark key，`set_provider volcano` + `send_chat` 即可复验）；HyperOS 麦克风权限墙照旧（ASR 验证走 `transcribe` 命令）

- **任务 5 已完成（2026-10-03，真机 2c3769db）**：视线系统（gaze + saccade，设计见第六节任务 5 提示词与 A.1 第 24 条）——
  - **corelib**：`internal/VrmLookAtEngine`（骨骼解算）+ `internal/GazeMath`（纯函数四元数/方向数学，JVM 可测）+ 公开 `LookAtInfo`。API：`controller.setLookAtTarget(x,y,z)`（世界坐标注视目标）/ `clearLookAtTarget()` / `getLookAtInfo()`。每帧在「动画写完骨骼局部变换之后、蒙皮矩阵传播之前」运行（渲染循环新增单次 `updateBoneMatrices()` 收口点）：读头骨世界位姿（先 `commitLocalTransformTransaction()` 保证世界变换反映本帧动画），解算「当前脸朝向→目标方向」的附加旋转，限幅（头+颈 yaw ±55°/pitch ±35°，相对**动画朝向**而非 rest——与点头/摇头类 VRMA 手势安全叠加），按 颈 0.35 / 头 0.65 分摊平滑分量（exp(-dt·7)≈300ms 收敛），**眼骨携带未平滑余量**（clamp ±25°）——眼神先到、头颈跟进的人类 saccade 分层；增益和恒为 1 故头颈收敛后眼回中。偏移=世界系旋转转父骨骼空间局部偏移 `L'=inv(P)·D·P·L`（⚠ getParent 返回 entity 必须 getInstance 往返，见记忆 filament-1683-api-constraints），与 VRMA/待机/拖拽完全可叠加；关闭时恢复 4 骨 rest 局部变换。脸方向在绑定时刻从「头骨静止世界四元数」反推为头局部向量（`faceLocalDir`）——**不假设骨骼轴约定**：实测 SK_Sun 头骨脸朝局部 −Y、10.vrm 朝 +Z，同一套代码两个模型都正确。
  - **orchestrator**：`face/SaccadeEngine`（AIRI `useIdleEyeSaccades`+`randomSaccadeInterval`/eye-motions.ts 精确移植：分段均匀间隔 0.8-4.8s（400ms 一档、首档概率 0.075）+ 注视点 ±0.25 世界单位抖动（仅 x/y，z 恒等于基准）+ `snap()`（基准移动时精确贴住，AIRI watch(focusPos)→instantUpdate 语义；先检查后累加的计时语义照搬））+ `face/GazeMode`（CAMERA=注视相机眼位=注视用户（**默认**，未来视频系统把真实人脸坐标喂 `setGazePoint` 切 POINT 即可、链路零改动）/ POINT / NONE）。`FaceDriver.tick` 新增 3.5 步（眨眼之后、情绪合并之前）：`updateGaze(dt)`——基准目标移动 >1cm 视作"用户动了"触发 snap，注视点变化写 corelib（双 epsilon 去重，静止零写入）。
  - **app**：`ai_cmd look_at`（无参=查状态输出 target+yaw/pitch+骨骼绑定；`camera|off`；`ai_x/ai_y/ai_z`=世界坐标点——**会话内外都可用**：有会话切 FaceDriver GazeMode，无会话直接驱动 controller）；`state` 命令输出增 lookAt 行；help 同步（docs/ai-debug-intents.md 已补）。
  - **单测 167 全绿**（+14：GazeMathTest 9——yaw/pitch 符号约定锁死/往返/quatFromTo 含反平行边界/父系变换恒等式 `P·L'==D·P·L` 探测向量校验/竖直目标退化安全；SaccadeEngineTest 5——首帧即注视/间隔不跟随基准/snap 不动计时器/400s 蒙特卡洛间隔全落 0.8-4.8s 表范围/抖动幅度界内；二轮修复再加 VrmLookAtStripTest 5，见下）。
  - **二轮修复（同日用户实测反馈：眼珠转过头只剩眼白，全模型必现）**：眼骨不在 VRMA 动画轨道里，上一帧写入的偏移一直留在局部变换中，逐帧连乘累积后冻结在滚转位置（头/颈被动画每帧重写所以没暴露）。修法=叠加层记账"先剥再叠"：骨骼局部若仍等于上次写入值（未被动画重写）先剥掉旧偏移再叠新的，纯函数 `VrmLookAtEngine.stripPreviousWrite`+单测（详见 A.1 第 25 条）。真机复验：静置 25s 稳态双眼虹膜清晰看向镜头、相机大幅环绕往返后无残留滚转、对话手势照常、零 FATAL。
  - 真机验证（2c3769db，默认模型 SK_Sun）：启动即注视镜头（FaceDriver 默认 CAMERA，closeup 下 yaw+14.9°/pitch+15.8° 跟随运镜）；`look_at` 侧方点 (2.5,1.0,-3.5) 头颈链准确转到限幅 yaw=+55.0°（截图侧脸朝向正确、眼无斜视）；`look_at off` yaw/pitch 归零、`camera` 恢复；挥手 VRMA 播放中视线叠加照常跟踪（截图：手势姿态+头朝镜头+发梢弹簧自然，SpringBone 诊断 len=rest 零爆炸）；换模型 10.vrm↔SK_Sun 重绑正确（两种头骨轴约定 faceLocal 分别 (0,0.01,1.0)/(0.03,-1.0,0.05)）；全对话链路（LLM 标签+口型+视线同开）TurnCompleted 后视线保持；全程零 FATAL。截图存 /tmp/gaze_{front,side,gesture}.png（会话产物，未入库）。
  - 已知未覆盖：设置页无视线开关（demo 默认开，SDK 侧 `session.faceDriver.setGazeMode` 即可关；UI 开关随视频系统一起做）；点击注视（AIRI mouse 模式）未做——移动端触摸被相机手势占用，等视频系统的脸位置作为唯一 POINT 源更合理。

- **任务 9 视频模式已完成（2026-10-03，真机 62fabe84；相机链路待真手授一次相机权限后复验，见下）**：第四种输入模式 `VIDEO 视频模式`——和用户开摄像头视频通话，"只能多模态（可收图）大模型才能开启"。
  - **多模态事实核验（设计前提，2026-10-03 真实请求实测）**：给模型发 64px 纯色 JPEG 问颜色——**硅基流动**：DeepSeek-V4-Flash/V3 明确拒图（`The model is not a VLM`），`Qwen/Qwen3.8-27B` 收图且答对（清单原有模型本身就是视觉模型），`Qwen/Qwen3-VL-32B-Instruct` 在册可用（已补进清单）；**火山**：5 个 chat 模型里 4 个 doubao 系全部真看图（默认 mini 即视觉，视频模式开箱即用），`deepseek-v4-flash-ga` 是**假视觉**——API 收下 image_url 但答 "image data incomplete"（同图 doubao 描述正确），清单里**不得**标为视觉；`seedream` 图像生成模型照旧排除。全部锁进 `AiProvider.visionLlmModels` + `AiProvidersTest`。
  - **adapter**：`ChatMessage.images`（data URL 列表）——序列化时带图消息走 OpenAI 数组形态 `content=[{type:text},{type:image_url}]`，纯文本消息保持字符串（数组形态会被文本模型 400）；历史 assistant/永不带图。MockWebServer 锁请求体形态。
  - **orchestrator**：`AvatarSession.send(text, images)`/`sendAndAwait` 带图——图片只挂到**本轮请求**的末尾 user 消息，`store` 历史只存文字（历史带图会让 vision-token 成本随轮数平方增长；模型对"刚才那张图"按文字记忆工作，可接受取舍）；`face/FacePointProjector`（纯 JVM）：人脸归一化坐标 nx/ny/面积占比 + `getCameraLookAt()` 位姿 → 世界注视点（喂任务 5 预留的 `setGazePoint`+POINT 缝），内置 One-Euro 滤波（minCutoff 1.2/beta 0.06，自适应平滑）+ 面积估深度（面积越大注视点越向模型前伸，深度钳 0.5-3）；调参常量全在构造参数。
  - **app 追踪引擎**（`video/`）：`UserCameraTracker` = CameraX ImageAnalysis(480×640, KEEP_ONLY_LATEST) + ML Kit FaceDetector（FAST/无 landmark/无分类/minFaceSize 0.15）——检测最大脸，归一化坐标**前置摄像头翻转 nx**（原始帧未镜像：脸在屏幕右时落在画面左，世界对齐 +1=屏幕右；竖直不镜像）；抓拍与检测解耦：每 500ms 独立取帧（ImageProxy.toBitmap 复制像素须在 proxy 关闭前）→ 直立旋转 → 中心裁 512×512 → JPEG(80) → 拉普拉斯方差清晰度 → `SnapshotRingBuffer`（深 3 ≈1.5s 窗）；**proxy 生命周期 = ML Kit Task complete 回调里 close**（fromMediaImage 异步读 buffer，提前 close 就是崩溃/检测全灭）。纯逻辑（ring/清晰度/关键词）与 Android 依赖分离，JVM 单测直测。
  - **app UI**：`InputMode.VIDEO`（复用语音模式的按住说话条，按钮默认隐藏）；准入门控 `videoModeBlockReason()`（未配置/模型非视觉 → 错误条原因，`set_mode video` 同路径 FAIL）；PiP 小窗 `VideoCallPip`（108×144dp，拖动=onDragStart 捕获起点+手势内只累加增量防漂移，位置 0..1 分数持久化 `ai_video_pip_x/y`，底部"前/后"钮切摄像头重绑）；视线消费 ~30Hz ticker：新观测→FaceDriver POINT，人脸离开 >1.5s 回退 CAMERA（看镜头等用户回来），无会话直驱 controller；**发送带图策略 = 视频模式每轮都附缓存窗最清晰一帧**（语音/打字/send_chat 三条路径同源 `videoSnapshotImages()`）——用户的"模式 A 抓拍时机 + 模式 B 意图拦截 + 纯闲聊降级"合并落地：抓拍帧只存在于视频模式，漏判"看这个"比多传一张 ~50KB JPEG 伤得多（≈1-2K vision token），`VisionKeywords` 关键词判定保留为纯函数（带单测）供 SDK 集成者按自家带宽策略接。
  - **回声消除（第一层）**：视频模式录音走 `MediaRecorder.AudioSource.VOICE_COMMUNICATION`（平台通话链路硬件 AEC/NS）——MediaRecorder 拿不到 audio session id 挂不了 AcousticEchoCanceler，音源切换是平台标准做法；半双工（按下先 interrupt）仍是主防线；WebRTC 软件 APM 留作真机实测仍有残留时再引入。
  - **ai_cmd**：`set_mode video`（带门控拒绝原因）/ `video_camera front|back` / `video_snapshot`（探测缓存不发请求）/ `set_llm_model <id>`（新增，清单校验——MIUI 禁触摸注入的模型切换入口）/ `state` 与 `chat_state` 增 video/vision 行；help 同步。
  - **单测 192 全绿（+25）**：adapter 3（数组形态/纯文本保持字符串/assistant 历史不带图）+ orchestrator 11（OneEuro 收敛/阶跃、投影器 nx/ny/深度/旋转基/退化安全/稳定性、会话带图挂队尾/历史不落图/无图不变）+ app 11（视觉清单锁死含假视觉排除、isVisionLlm 先归位再判定、ring 滚动/最清晰/并列取新、平坦≈0清晰度、关键词命中）。
  - **真机 62fabe84 已验**：chat_state `vision=true`（该机已配 Qwen3.8-27B）；`set_mode video` 进入（模式徽标变"视频模式"、按钮自动隐藏）；系统相机权限框正常弹出（截图）；无权限降级——发送走纯文本、追踪器 `active=false`、应用稳定；切 DeepSeek-V4-Flash 后 `set_mode video` **FAIL 且原因文案正确**，恢复 Qwen 后重新放行；全程零 FATAL。
  - **二轮修复（2026-10-04，用户手授相机权限后实测反馈 2 个 bug，均已真机复验）**：
    1. **PiP 预览黑屏**（相机开了但小窗黑）：组合时序坑——`VideoCallPip` 首帧容器无尺寸、内层 AndroidView（PreviewView）尚未创建，而 `LaunchedEffect(lensFront)` 首帧就跑了 `start()`，那次 bind 只带分析流没有 Preview，之后无人触发重绑 → 永远黑。修法=黑屏守卫：`attachPreview(view, owner)` 时若 provider 已绑定且 `previewBound=false` 就强制带 Preview 重绑（`start(force=true)`，no-op 守卫加 `previewBound == (previewView != null)` 条件）。
    2. **注视点恒偏右下**：ML Kit 的 boundingBox 是**旋转后直立系**（480×640），归一化却用了 ImageProxy 的**传感器缓冲系**尺寸（640×480）——居中的脸算出 nx=+0.25/ny=+0.33 的恒定偏置（恰好=纵横比错位量，且与脸位置无关）。修法=抽纯函数 `FaceFrameMath.normalize`（直立尺寸按 rotation 90/270 互换，前置翻转 nx），+5 条单测锁死（居中→零回归用例）。修后真机 `face=(-0.32,-0.05)` 随位置变化、yaw -34°↔-20° 随头动抖动更新。
    3. 顺带：`debugStatus()` 的 `"%.0fB".format(Int)` 抛 `f != java.lang.Integer` 把 state 命令打挂——String.format 的 %f 只吃浮点，Int 必须直接插值。
  - **端到端闭环（2026-10-04 真机，权限就绪后）**：PiP 实时画面（screencap 裁 PiP 区域 std=52 非黑、圆角+边框+前后摄钮正常）；`state` 输出 `video: active=true lens=front face=(x,y) area=0.14 age=30ms ring=3 best=27552B sharpness=3976.1`；**多模态回合**：`send_chat "描述一下你现在通过摄像头看到的画面"` → logcat `multimodal turn: 1 image(s)` + `system prompt: ... images=1` → 模型回复**准确描述真实场景**（圆框眼镜/吸顶灯/空调出风口格栅/白色书架/关着的门，与 PiP 截图一致），自发 `<cam:close_up><emo:…><act:…>` 标签全通道触发，8 句流水播完 TurnCompleted。
  - **观测点补注**：①视频模式下 `look_at` 手动命令会被追踪器 33ms 内覆盖（视频模式视线归追踪器所有，设计内）；②冷启动后立刻采 state 可能读到 yaw/pitch=0 的瞬态（首帧未收敛），连采 2-3 次再看；③`raw reply:` 日志多行回复首行可能显示为空（回复以 `\n<cam:…>` 开头），grep -A 续行才见全文。
  - **剩余待真手**：PiP 拖动手感与前后摄真手点按（adb 禁注入；`video_camera` 命令侧已可驱动）；注视幅度的观感调参（`FacePointProjector` 构造参数 maxLateralDegrees/maxVerticalDegrees，见 A.2）。
  - **四轮·六项修改（2026-10-04 用户需求，真机 62fabe84 全验，209 单测 +3）**：
    1. **视频模式进入默认面部特写**：进模式的 effect 里 `setCameraShot(CLOSE_UP)`+徽标同步。
    2. **每轮请求注入当前视角**：`AvatarSession.currentViewLine()` 实时读 `controller.getActiveCameraShot()`（活动机位=`【当前镜头视角】面部特写 CU（CLOSE_UP），用户正以这个机位看着你。`，无预设=自由视角 FREE），拼在人设之后、协议块之前——**few-shot 示例必须保持提示词最末**（§7.10 近因效应），单测锁死该顺序；headless 不注入。LLM 因此知道自己在什么取景下说话，`<cam:>` 建议与画面描述不再凭空。
    3. **新增 MACRO 面部微距机位**：corelib `CameraShot.MACRO`（"面部微距 MC"）+ renderer ShotPose（pivot=face、distance=0.9×span≈特写的 0.62 倍，面部充满画面）；协议标签 `<cam:macro>` 进 DEFAULT_CAMERA_TAGS/`CAMERA_SHOTS`/`camera_shot` 命令（macro|mc），视角 FAB 轮换自动含它。
    4+5. **专用排查日志 tag `LlmPrompt`**：请求侧=`=== REQUEST model/view/system 长度/history/sent/images ===` 头行 + `REQUEST user:` 行 + system 全文分段（[i/n]，**换行字面化为 \n 保持单行**，logcat 单条上限绕开）；响应侧=`=== RESPONSE raw/clean 长度 ===` + raw（含标签）与 clean（标签剥离正文，新增 cleanBuffer 与 replyBuffer 同步累积）各分段。旧的 AvatarSession `raw reply:` 行保留作时序标记，**完整内容一律看 LlmPrompt**（`adb logcat -s LlmPrompt`）。
    6. **视频模式回合结束自动回特写**：TurnCompleted 后等最后一条 LLM 手势播完（ActionStarted 时按 `getVrmaAnimationDuration()` 推算 `gestureEndsAtMs`，+400ms 归位缓冲）再 `setCameraShot(CLOSE_UP)`，等待后复查仍在视频模式才动镜头；打断（TurnFailed/PlaybackInterrupted）**不**回特写——打断即用户接管镜头。
  - 真机证据：进视频模式 `cameraShot=CLOSE_UP`；`camera_shot macro` → `cameraShot=MACRO`（截图面部充满画面）；发消息后 REQUEST 头行实时读到 `view=【当前镜头视角】面部微距 MC（MACRO）`；RESPONSE `raw=331ch clean=181ch` 分段可读；模型对着微距抓拍回复"这张是正脸怼镜头，仰拍角度把发际线和头顶全拍出来了"（视角提示词+抓拍的联动效果）；回合结束 `cameraShot=CLOSE_UP`；零 FATAL。
  - **三轮调参（2026-10-04 用户反馈"幅度太大"）**：投影器从**世界单位线性映射改为角度语义**——脸贴画面边缘 = 水平 22°/垂直 15° 转角上限（`maxLateralDegrees`/`maxVerticalDegrees`），乘以当前相机到人物的实际距离换算偏移，特写/全景手感一致（首版固定偏移 1.4/1.0 世界单位，特写机位距离 ~1 时边缘脸=50°+ 直接打到 ±55° 限幅："扭头扭过去了"）；"凑近画面"的前伸深度项钳制在距离的 20% 以内（`maxAlongFraction`，深度项缩短注视基线会放大转角，是"低头低太多"的另一半成因）。单测按"头部处量到的转角"锁死：边缘脸在 d=1 与 d=4 机位下都必须 ≈22°。修后真机采样 yaw ±19°/pitch ±9° 内随位置成比例。

- **任务 9.1 自由说话已完成（2026-10-04，真机 62fabe84）**：语音/视频模式新增**自由说话**（连续聆听，此前只有按住说话），聊天条左侧「按住/说话」药丸一键切换（持久化 `VoicePrefs.freeTalk`=`ai_voice_free_talk`，切模式/重启保持）。
  - **架构 = 纯逻辑 + 薄壳**（app `FreeSpeech.kt`）：`WavEncoder`（PCM16LE→WAV 44 字节头，纯函数）；`SpeechVad`（软件端点检测，纯 JVM 全量单测）：20ms 帧 RMS，门限 = max(绝对兜底 0.055, 噪声地板×4, 地板+0.02)，**噪声地板静默期慢速跟随**（说话期不更新防 TTS 残留抬地板）；起音确认 60ms → 句长按**有声时长**累计（悬停静默不计入，咳嗽/碰撞凑不够 280ms 判 TooShort 丢弃）→ 静默悬停 800ms 判句尾；超长 15s 强制断句。
  - **FreeSpeechController**：AudioRecord 16k 单声道采音线程（视频模式 VOICE_COMMUNICATION 走硬件 AEC）；起音前 ~280ms 前滚环入段（首音节不削头）；句尾切片→WAV→复用按住说话同一条 ASR 链路→**自动发送**（freeTalk 下 autoSend 设置不生效；空识别=噪声切片静默忽略；Mutex 串行防乱序；视频模式自动附抓拍帧）。
  - **Barge-in（打断）**：虚拟人 SPEAKING 期间高门限（0.14 或地板×7）+ 持续 350ms + 开始说话后 600ms 宽限（回声建立期），三重门触发 `session.interrupt()`，且该次开口继续录成下一句；普通响度在对方说话期不捕获（半双工兜底，防扬声器残留触发"自己打断自己"——真机观感待用户实测，误触发/不触发都调 `SpeechVad` 构造参数）。
  - **UI**：聊天条左侧 `VoiceTalkModeToggle`（自由态高亮 primaryContainer），右侧 `FreeListenIndicator`（平静"自由说话中，直接开口"/VAD 有人声"听到你说话…"主色/识别中副色，8dp 状态点）；确认形态（autoSend=false 的文本框形态）下同样带切换钮。
  - **ai_cmd**：`voice_free on|off`（无参翻转）、`chat_state` 尾部 `freeTalk=true(listening,hearing)` 状态；help 同步。**生命周期**：语音/视频模式+开关+麦克风权限+硅基流动 Key 四条件齐才跑（`LaunchedEffect(inputMode, freeTalk, micGranted)`），切模式/关开关/组合销毁即停。
  - **单测 +7（203 全绿）**：WAV 头逐字段；VAD 安静无事件/起音+悬停判句尾/短促丢弃/超长断句/barge-in 三重门(宽限内中等声不触发·大声持续恰好一次·不重复)/说话期中等声不捕获且 VAD 存活/reset 清态。
  - **真机 62fabe84 已验**：`voice_free on` → `FreeSpeech: free speech started (aec=true)`、`chat_state` 显示 `freeTalk=true(listening)`、截屏确认「自由/说话」药丸+「● 自由说话中，直接开口」指示条、`off` → `stopped`、零 FATAL。**待用户真口实测**：说话断句触发率（VAD 门限适配度）、句尾等待感（悬停 800ms）、barge-in 是否误触发/不触发、识别准确率与按住说话对比。

- **首响延迟治理：doubao-seed 关深度思考 + LLM 耗时/token 观测（2026-10-04，用户需求「发送到说话间隔太长」，353 单测 +7）**：
  - **归因**（用户抓的日志按三分法拆）：REQUEST 27.96→首句 Queued 32.44（**LLM 首句 4.48s，占 78%**）→首句 Started 33.69（TTS 合成 1.24s），感知等待 5.72s。LLM 大头的两个嫌疑：①火山 doubao-seed 系**默认自适应深度思考**没关（请求里根本没有 `thinking` 参数；思考 token 走 `reasoning_content`，适配器不读=全部变成隐形等待）；②14.6K 字符钉住协议的前缀缓存命中与否**没有观测位**，无法证实。
  - **修改**：①`AiProviders.llmExtraBody(provider, model)` 纯函数，按服务商方言分发关思考参数——火山 + `doubao-seed` 前缀 → `{"thinking":{"type":"disabled"}}`；硅基流动 + `Qwen/Qwen3` 前缀 → `{"enable_thinking": false}`（**同日第二轮：用户实测豆包提速后千问仍慢，Qwen3 系混合推理模型默认开思考，关思考参数与火山不同名**；Qwen3-VL-Instruct 非思考模型带上无害）；其余模型（deepseek 系等）返回 null 不发，防严格端点 400。`AiChatController.ensure` 装配进 `LlmConfig.extraBody`（该字段本来就设计为原样并入请求体，首次真正用上）；②LLM 适配器请求恒带 `stream_options.include_usage`，SSE 循环解析最终 usage 块（含 `prompt_tokens_details.cached_tokens`）→ 新模型类 `LlmUsage(promptTokens, completionTokens, cachedTokens?)` 挂在 `LlmStreamEvent.Finish.usage`（默认 null，旧构造点零改动）；③`AvatarSession.send` 用 `SystemClock.elapsedRealtime()` 记 **ttfb**（请求到首个 TextDelta）与 **stream**（到流结束），并入 RESPONSE 头行：`=== RESPONSE … ttfb=Xms stream=Yms tok: prompt=A cached=B completion=C ===`；REQUEST 头行新增 `thinking=off|default`（请求级确认位）。
  - **边界**：`thinking` 参数对 doubao-seed 系是官方文档参数；若某模型报 400 会直接进错误条可见（不会静默），届时按模型清单收窄 `llmExtraBody` 的匹配即可。usage 各家都支持 `include_usage`（OpenAI/Ark/硅基流动/vLLM）。
  - **单测 +7（355 全绿）**：适配器 4（include_usage 在请求体/extraBody 原样并入/usage 块解析挂 Finish/无 cached_tokens 明细时 null）；app 3（火山 doubao-seed 关思考·硅基流动 Qwen3 enable_thinking=false·两方言跨服务商不泄漏）。
  - **待真机验证**：同一轮发消息看 REQUEST `thinking=off` + RESPONSE `ttfb=` ——豆包已由用户确认"快多了"；**千问待复测**（预期同样降到 1-1.5s）；第二请求起 `cached=` 应显著大于 0（前缀缓存命中）。若 ttfb 仍 >3s 且 cached=0，再查服务商缓存策略。若硅基流动对 Qwen3-VL-Instruct 传 enable_thinking 报 400（理论上 no-op，未实测），把 `llmExtraBody` 的匹配收窄到非 "Instruct" 后缀即可。TTS 侧的连接复用/首包即播留作后续项（见 A.1 第 31 条）。

- **协议目录每上下文只发一次 + 换模型即新开上下文（2026-10-04，用户需求「全量表情/动作目录别每轮都发」，218 单测 +9）**：
  - **背景**：协议块（全量表情/动作/镜头目录，~12K chars）此前每轮重拼进 system 提示词整体发送。目录内容只取决于当前加载的模型与开关、与轮次无关——逐轮重拼纯属浪费。
  - **orchestrator（AvatarSession）**：请求结构改为 **`[人设 system][协议 system（钉住）][历史][末尾 user]`** 四段——①协议文本每上下文钉一次（`pinnedProtocol` 缓存已注入文本，内容没变逐轮复用同一份，**请求前缀逐字节稳定**，服务商前缀缓存可全程命中；目录变了=重载模型/镜头开关，原地重钉并打日志 `protocol pinned:`，历史保留）；**人设与协议在发送时合并为一条 system**（初版拆两条独立 system 被硅基流动 400 拒收——"System message must be at the beginning"，实测两条即使在开头也不行，当晚修复 A.1 第 34 条）；②**视角行从 system 挪到末尾 user 消息前缀**（请求里唯一逐轮变化的指令，只改请求副本、store 仍存干净文字——放 system 会打断前缀稳定性；离生成位置最近，相机语境最新鲜）；③协议消息不进 store、不受 `recentTurnLimit` 裁剪。旧的 `systemPromptWithProtocol()` 已删，`protocolBlock()`（设置页只读展示）保留且与实际注入逐字一致。
  - **app（换模型即新开上下文）**：`AiChat.kt` 新增 `llmIdentitySignature(prefs)` = 服务商+**解析后**模型名（清单外残留值会归位成默认，不会误判轮换）；`MainActivity.updateAiPrefs` 对比新旧签名，变了就 `newContext()`（历史清空、新上下文首请求带新目录）——签名刻意**不含** TTS/音色/Key/镜头开关（不影响模型能力，对话延续）；签名随 `ai_context_llm_sig` 持久化，升级首启无记录时只采纳不轮换。注意：历史仍随每条请求发送（chat API 无状态），本改造的收益=①协议文本不逐轮重拼/重复出现，②前缀稳定吃得满硅基流动/方舟的前缀缓存（真实省钱+降延迟），③`recentTurnLimit` 之外提示词结构不再随轮次膨胀。
  - **单测 +9（218 全绿）**：`AvatarSessionProtocolPinTest` 4（人设/协议分离且协议不进人设/同上下文两轮协议逐字节相同/目录变更原地重钉且历史无损/协议开关关闭不注入）；`AvatarSessionViewLineTest` 重写 3（视角行挂末尾 user 前缀、headless 连前缀都不挂、视角行在钉住协议之后）；`LlmIdentitySignatureTest` 5（同配置同签名/换模型换签名/换服务商必换签名/清单外残留归位不误判/TTS·Key·镜头开关不换签名）。
  - **观测点变化**：LlmPrompt REQUEST 头行 `system=` 拆为 `persona=`+`protocol=`；分段键 `REQUEST system` → `REQUEST persona`/`REQUEST protocol`；**视角行在 `REQUEST user:` 行里**（前缀形态，grep 该行即可看到当轮机位）；AvatarSession 的 `system prompt:` 观测行更名 `prompt: persona=Nch protocol=Nch`，新增 `protocol pinned:` 行（何时重钉一目了然）。


- **任务 8.1 已完成（2026-10-03，真机 62fabe84）**：多模态协议第二轮——动作库扩容 + 表情全量暴露 + IDLE 待机（设计见 7.10）
  - **动作库扩容**：外置库 `fbx2vrma-converter/VRMA_Selected_Categorized` 除 02_行走跑步转向（位移类走出画面）外 9 类 309 个拷入 `assets/animations/<分类>/`（总计 334 个/47MB）；LLM 动作目录不再硬编码——`buildLlmActionCatalog` 全量扫描 assets 生成（文件名→小写下划线 tag，去重，分类取子文件夹名，按对话价值排序），外置动画模式下追加外置库（真机实测 prompt 含 455 个动作 tag）
  - **表情全量暴露**：`<emo:>` 词表合并为一段——7 个标准情绪 + 当前模型全部可用表情原名（`FaceDriver.availableExpressions`，SK_Sun 实测 68 个含 ARKit 52 morph）；未知名经 `EmotionBlender` 回退为"单 morph 直驱"（0.25s ease + 3s 自动回 neutral），`AvatarSession` 分派时用 `FaceDriver.resolveExpression` 大小写不敏感地还原 morph 名
  - **IDLE 待机**：corelib 引擎加 idle 槽——一次性动作播完或手动停止自动无缝切到 idle 循环（`idleAnchor` 相位锚定 + `consumeIdleSwap` 联动弹簧骨骼重置），无 idle 时回落 rest pose；app 默认自动选（Idle Stand Looking Around 优先），动画面板**长按**条目换待机（持久化 `ai_idle_animation`），`ai_cmd set_idle/idle_off` 调试
  - **提示词工程（真机迭代 5 轮的结论）**：①few-shot 输出示例是格式遵循的最强杠杆，且要放在长提示词**最末尾**（近因效应）；②示例里的名字必须动态取自真实清单——硬编码 `blink_l` 在 ARKit 命名模型（blinkLeft）上不存在，模型照抄后被门控静默丢弃；③情绪与直接表情合并为一个 `<emo>` 词表段；④禁止"括号演戏"要显式写（模型爱用（括号）描写动作）；⑤LLM 温度 0.8→0.6（AiChatController）；⑥动作列表量大放末尾
  - **观测点**：`AvatarSession` 新增两条日志——`system prompt: N chars, cameras=5, actions=N, directExpr=N`（注入是否生效）与 `raw reply: ...`（模型原始输出，区分"没发标签"vs"发了被丢弃"）；本次眨眼问题就是靠 raw reply 定位的（模型发了 `<emo:blinkLeft:1>` 被大小写卡掉）
  - 真机验证：眨左眼 → `EmotionChanged blinkLeft intensity=1.0→0.0` 事件 + morph 直驱 ✓；庆祝手势 3.5s 播完后截屏差分角色持续运动（idle 接管，非冻结/T-pose）✓；set_idle × 3 切换 + idle_off 全部生效 ✓；单测 106 全绿（新增 EmotionBlenderTest 4 / FaceDriverResolveExpressionTest 2）

- **任务 8 已完成（2026-10-03，真机 62fabe84 小米14/HyperOS Android16，硅基流动 DeepSeek-V3 + CosyVoice2）**：多模态行内标记协议——LLM 单流输出同时驱动文字(TTS/口型)/情绪(表情)/动作(VRMA)/镜头(CameraShot)，设计全文见第七节
  - adapter：`TagCue` sealed + `TagExtractor` 接口 + `InlineTagExtractor`（`<emo:名:强度>/<act:名>/<cam:机位>` 新三标签 + 老协议 `<|emotion|>` 兼容；跨 delta 缓冲/holdback/裸 `<` 防吞段）
  - orchestrator：AvatarSession 三路分派（映射在本层做，adapter 不碰 corelib）+ `gesture/GestureDriver`（目录可注入，interrupt 时 stop）+ `SystemPromptAssembler.multimodalProtocolBlock(cameras, actions)`（空段省略；顺带修 assemble/buildRequestMessages 双重注入）+ `speak()` 走 extractor（开场白支持标签）+ `AvatarEvent.ActionStarted/CameraChanged`；Options 加 `protocolInstructions/enableLlmGestures/enableLlmCamera`
  - corelib：`VrmaAnimationEngine` 非循环播完自动 stop+restoreRestPose（原先冻结末帧）+ `AvatarController.getVrmaAnimationDuration()`
  - app：内置策展 6 动作目录（wave/nod/thank/celebrate/dismiss/salute→assets 对应文件，策展标准见 7.2）+ 外置库文件名关键词匹配回落内置 + 设置加「AI 可控镜头」开关（prefs `ai_llm_camera`，默认开）+ LLM 切机位与手动视角 FAB 共用同一枚徽标
  - 单测：adapter 32 + orchestrator 51 全绿（新增 InlineTagExtractorTest 15 / AvatarSessionTagsTest 6 / 音频 release 回归 1，重写 assembler 测试）
  - 真机验证：首轮对话 LLM 自发 `<cam:medium_shot><act:wave><emo:happy:0.8>` 开场、中段 `<cam:close_up><emo:happy:1.0>`——CameraChanged/ActionStarted/EmotionChanged 按序触发（`Loaded VRMA 5.08s, 53 bone tracks` 证实手势真加载，播完自动归位），字幕零标签泄漏，5 句按序 TurnCompleted；第二轮 `ActionStarted celebrate`（VRMA 8.54s）+ 4 次情绪变化 + 机位切换，打断 ✕ 后 23ms PlaybackInterrupted、动作停止，全程零 FATAL/TurnFailed
  - 顺带修真崩溃：release() 打断停在 wait() 的写线程 → FATAL（见 A.1 第 17 条）

- **预置人物卡 grid + 人设提示词编辑（2026-10-04，用户需求"点卡按钮显示预置角色 grid / 设置页可编辑人设提示词"；真机 62fabe84，单测 +10 全绿）**：
  - **卡源（重要背景）**：用户原想从本地 sillytavernassets 合集挑选，逐张人工审核后确认该合集整体尺度不可用（性暗示渗透到命名/描述层，关键词过滤拦不住），第二轮改走官方源——**SillyTavern 官方全部角色卡只有 7 张**（SillyTavern-Content 官方内容仓库 `assets/character` 6 张 + 主仓库默认 Seraphina），其中适合语音陪伴场景的 5 张全收（Seraphina 治愈/Gloria 理性秘书/Sakana 傲娇/Amy 毒舌/Coding Sensei 理性导师；CapoGPT 犯罪教唆、Flux 不会说话两剔除）；chub.ai 全线地域封锁、character-tavern 无公开 API、HF 上都是随意备份——**剩余 13 张按官方 V3 规范自创**（中文原创人设：元气萝莉/傲娇同桌/认真班长/杂货铺看板娘/腹黑甜品师/深夜咖啡师/天才发明少女/天体物理研究生/图书馆管理员/急诊科医生/CEO 御姐/古风说书少女/旅行摄影师，全部原创零 ACG 依赖、SFW），头像用方舟 seedream-5-0-pro 统一扁平插画风生成（构建脚本思路：PIL 缩 512² + tEXt(ccv3+chara) 内嵌 JSON 进 PNG，与 ST 导出卡同构），产物 `app/src/main/assets/cards/`（18 张 6.8MB，全部可被 CharacterCardParser 解析）
  - **app**：`PresetCards.kt` 新增三件——`PresetCardLibrary`（assets 逐个解析、坏卡跳过）、`PresetCardImport`（纯 JSON 去重映射 assetPath→落盘文件名：点选即导入激活、重复点选复用不重复落盘、用户删卡后映射自动失效重导）、`CardPromptOverrides`（纯 JSON 按卡文件名存提示词人工编辑覆盖）；CARDS 面板重排=顶部「预置角色」三列 grid（BitmapFactory 按 256px 采样的缩略图 + 名称 + 选中描边，**首位"无人物卡"空卡片=取消人物卡**，仅当完全没有激活卡才高亮）+ 下方「我的卡片」导入/激活/删除原功能保留
  - **提示词编辑**：设置页新增「人物卡 (Character Card · 人设提示词)」折叠区——只读展示当前激活卡的完整人设（`SystemPromptAssembler().assemble(card)`，与实际注入逐字一致）+「编辑」→ OutlinedTextField + 「OK」保存覆盖并立即改写 `session.systemPrompt`（空文本=还原默认）+「还原默认」清除覆盖；覆盖按卡文件名持久化（prefs `ai_card_prompt_overrides`），**session 重建时经 `applyCardToSession` 重放**（否则 assemble 冲掉编辑）；删卡时覆盖一并清除
  - **org.json 坑**：映射/覆盖逻辑最初用 org.json 写，JVM 单测全挂（`Method put in org.json.JSONObject not mocked`——android.jar 是抛异常的桩，见 A.1 第 33 条）；改用 kotlinx.serialization 的 `Json.parseToJsonElement` 纯 API（不需要序列化插件），app 模块加 `libs.kotlinx.serialization.json` 依赖
  - 真机验证（62fabe84）：预置 grid 渲染（图+名+空卡片默认高亮）✓；`import_card` 走同一条落盘/激活路径 → 阿枣开场白 TTS 播报 ✓、`active_card` 显示 sysPromptChars=372（description+personality+scenario 拼装正确）✓、我的卡片「使用中」徽标 ✓、激活后空卡片取消高亮 ✓；设置页人物卡区（卡名+只读人设+编辑钮）✓；HyperOS 禁 shell input 依旧，grid 点选与编辑保存的手势流未真机点按（与已验证的 import_card/activateEntry 同路径，纯逻辑部分 10 条单测覆盖）
- **当晚两连修（用户实测选卡后对话 400 + 面板默认弹开反馈）**：①**硅基流动对两条 system 直接 400**（协议钉住改造把人设/协议拆两条 system，标注真机待验；用户第一次带卡对话即触发）——buildRequestMessages 改为人设+协议合并单条 system（curl 复现→合并通过；A.1 第 34 条，ProtocolPinTest 重写锁单条不变量 +2）；②**AI 未就绪时切卡 clearHistory 被跳过**（延迟激活补挂路径不清历史，Room 旧对话泄漏进新卡请求）——pendingGreetingFile 消费时补 clearHistory（A.1 第 35 条）+ LLM 适配器读超时 10s→60s（14K 协议首 token 超默认超时）；真机复验：冷启动 3s 即 import_card（复刻延迟激活）→ 补挂时 history=1（无泄漏）→ 你叫什么名字 TurnCompleted 50 字 ✓。另：用户反馈的「卡面板默认弹出」是调试 ai_cmd 遗留状态（冷启动实测默认关闭），非代码问题
- **对话字幕聊天记录 + 拖拽 FAB 重排 + 挪动人物 T-pose bug 修复（2026-10-04，用户需求「显示用户说的文字在虚拟人上面/字幕超四行要能下拉并实时贴底/左下角挪人按钮挪到视角上面换图标/挪动只有 T-pose 能用的 bug」；真机 62fabe84，单测 +14 全绿：app 74/corelib 19/adapter 54/orchestrator 108 debug 变体）**：
  - **聊天记录面板**（需求 1/2）：旧字幕=当前轮 replyText 单条 Text（maxLines=4 省略号截断）；新=`ChatTranscript` 纯逻辑（app/ChatTranscript.kt）+ `mutableStateListOf<ChatLine>`——用户行（青色「我：」前缀）在虚拟人行（白色）上方，面板 `heightIn(max=168dp)+verticalScroll`，`snapshotFlow{scrollState.maxValue}` 内容长高即 `animateScrollTo(maxValue)` 贴底（替代省略号截断）；AVATAR 句开播（SentenceStarted）并入最后一条 AVATAR 行（同回合逐句拼整段），USER 恒新起一行（自由说话连发=两次独立发言）；四条发送路径（打字 onSend/按住 autoSend/自由说话 utterance/ai_cmd send_chat）统一 `pushUserLine`；上下文切换清空；上限 60 行裁旧
  - **拖拽 FAB 重排**（需求 3a）：从左下角独立 FAB 挪进右下 FAB 列、置于「视」上方第一位；样式与「视/卡」一致改文字 FAB「移」（原 Build 扳手语义不对；material-icons-core 无 OpenWith、不为一个图标引 extended——debug 不削减包体积）；激活态仍 Close 图标
  - **挪动人物 T-pose bug**（需求 3b，A.1 第 36 条）：拖拽语义=平移 humanoid hips 骨骼（three-vrm mouse.html，弹簧骨骼感知真实身体运动），但 VRMA 待机/动作的 hips 平移轨道每帧 `applyTranslation` 整体重写 hips 局部变换、`restoreRestPose` 停播时也重写——触摸时一次性 setTransform 下一帧就被冲掉，只有 T-pose（无动画）能拖（默认待机常开=实际永远拖不动）。修法：触摸只累加 `hipsDragOffset`（VRM0 X 翻转在累加时处理），每帧动画分支后 `applyHipsDragOffset` 以「动画刚写出的平移」为基线重钉；判定抽纯函数 `HipsDragOffsetSolver`（corelib/internal/HipsDragOffsetSolver.kt）单测锁死四不变量：动画重写→新基线+重钉不累加/基线位移不变不重复落盘（不漂移）/帧间隙 restoreRestPose 后位移仍在/旋转轨道保留平移的帧不改写；覆写检测双信号=帧内 before/after 对比（覆盖内置 glTF 动画路径）+ 引擎 `consumeHipsRewritten` 标志（VrmaAnimationEngine，覆盖帧间隙 restoreRestPose）；换模型/`setDragMovesHips` 切换重置状态
  - **真机验证**：send_chat 一轮 → uiautomator bounds 确认用户行 y[1710-1758] 在虚拟人行 y[1764-1908] 上方、TurnCompleted subtitleLen=59；FAB 列「移」y[1119-1176] 在「视」y[1277-1334] 正上方（uiautomator+截图双确认）；拖拽模式开 30s 待机人物无漂移（求解器无误写）；拖拽手势本身 HyperOS 禁 shell 注入（老约束），待用户真手复验
- 工程底座：libs.versions.toml 加了 coroutines 1.9.0 / okhttp 4.12.0 / serialization-json 1.7.3 / kotlin-jvm / kotlin-serialization 插件；两新模块已入 settings.gradle.kts；视频模式再加 CameraX 1.4.2（core/camera2/lifecycle/view）与 `com.google.mlkit:face-detection:16.1.7`（**bundled 版**自带 BlazeFace 模型，不依赖 GMS，国产机可跑）

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

- **双服务商（2026-10-03 起）**：设置页全下拉选择，清单与默认值收口在 app 的 `AiProviders.kt`（改清单/默认值只动这一处 + `AiProvidersTest` 锁定的产品决策要同步改）。
  - **硅基流动** `https://api.siliconflow.cn/v1`（LLM/TTS/ASR 全链路实测）：LLM=`deepseek-ai/DeepSeek-V4-Flash`(默认)/`DeepSeek-V3`/`Qwen/Qwen3.8-27B`；TTS=`fnlp/MOSS-TTSD-v0.5`(默认)/`FunAudioLLM/CosyVoice2-0.5B`——**两模型共用 CosyVoice 音色引用** `FunAudioLLM/CosyVoice2-0.5B:anna`（MOSS 收跨模型引用实测 200），wav/pcm 16k 均可、**mp3 只收 32000/44100**；ASR=`Qwen/Qwen3-ASR-1.7B`（`/audio/transcriptions`，wav 稳、**mp3 偶发 HTTP 500 空 body**——ASR 探针用 wav）
  - **火山引擎**：LLM=方舟 Ark `https://ark.cn-beijing.volces.com/api/v3`（OpenAI 兼容 chat/completions；6 个模型 chat 实测 5 通——doubao-seed-2-0-mini(默认)/2-1-turbo/2-1-pro/deepseek-v4-flash-ga/seed-character 均回包，**seedream-5-0-pro 是图像模型 chat 报 RPM 限额**）；TTS=豆包语音 seed-tts-2.0（V3 WebSocket，`X-Api-Key` + `X-Api-Resource-Id: seed-tts-2.0`，音色 zh_female_vv_uranus_bigtts 默认等 6 个）；两把 key 分字段存储见 2.4 双服务商条目
  - 密钥明文：仓库根 `secrets.properties`（已 gitignore）：SILICONFLOW_API_KEY / VOLCANO_TTS_API_KEY / VOLCANO_ARK_API_KEY(待补)；设备侧经 run-as 写入 demo_settings（`ai_api_key_siliconflow`/`ai_api_key_volcano`）
- 填写入口：App ⚙️ 设置 →「AI 配置」→ 选服务商 + 填 Key 即可对话（模型/音色留空=默认）。
- 单测：`./gradlew :avatar-ai-adapter:test :avatar-orchestrator:testDebugUnitTest :app:testDebugUnitTest :corelib:testDebugUnitTest`（2026-10-04 起 app 74 / corelib 19 / adapter 54 / orchestrator 108，debug 变体全绿，视频模式起 app 也有纯 JVM 单测）
- 构建/安装：`./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 设备：`2c3769db`（本次双厂商验证机，adb input 可用）与 `62fabe84`（小米14/HyperOS，禁 shell input）；日志关注 `adb logcat -d -s AIDebug`（调试命令与事件）与 `adb logcat -d -s AndroidRuntime:E`（崩溃）与 UI 错误条（TurnFailed）。

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

### 任务 2.5：预置人物卡 grid + 人设提示词编辑（✅ 已完成，2026-10-04，见第二章 2.4 末条）

```
继续 AIAvatar-SDK 的 AI 层工作。先读 docs/ai-layer-handoff.md 第二章;本次做预置人物卡。
卡源结论: SillyTavern 官方全部角色卡只有 7 张(SillyTavern-Content 仓库 assets/character 6 张 + 主仓库 default_Seraphina),
可用的 5 张(Seraphina/Gloria/Sakana/Amy/Coding Sensei)已拷入 app/src/main/assets/cards/;
另按官方 V3 规范自创 13 张中文原创卡(PNG 内嵌 ccv3+chara tEXt,头像 seedream 生成)。本地 sillytavernassets 合集尺度不可用,勿再从那里挑卡。
要求:
1. CARDS 面板顶部加「预置角色」三列 grid:图(assets PNG 按 256px 采样解码)+名称;首位"无人物卡"空卡片=取消激活(仅当无激活卡才高亮);
   点预置卡=导入落盘+激活(PresetCardImport 去重映射 ai_preset_cards,重复点选复用,用户删卡后映射失效重导);「我的卡片」导入/删除保留。
2. 设置页加「人物卡」折叠区:只读展示当前卡人设(SystemPromptAssembler().assemble(card));编辑→OutlinedTextField→OK 保存覆盖
   (CardPromptOverrides 按 ai_card_prompt_overrides 持久化,session.systemPrompt 立即生效,session 重建重放);还原默认=清除覆盖。
3. 映射/覆盖用 kotlinx.serialization 纯 JSON(org.json 进 JVM 单测会 not-mocked 崩,A.1 第 33 条)。
验收: 点卡→人设生效+开场白朗读;设置页编辑提示词→按编辑后人设回答;还原默认→恢复。纯逻辑单测锁死(PresetCardsTest)。
```

### 任务 3：Room 会话存储 + 历史裁剪（✅ 已完成，2026-10-03，见第二章 2.4）

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

### 任务 4：语音输入（ASR 适配器）（✅ 已完成，2026-10-03，见第二章 2.4；demo 另做了三种互斥输入模式）

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

### 任务 5：视线系统（✅ 已完成，2026-10-03，见第二章 2.4）

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

### 任务 8：多模态行内标记协议（✅ 已完成，2026-10-03，真机 62fabe84，见第七节设计与 2.4 验证）

```
继续 AIAvatar-SDK 的 AI 层工作。先读 docs/ai-layer-handoff.md 第二章与第七节(任务8设计)。
任务: 让 LLM 单流输出同时驱动 文字(TTS/口型)+情绪(表情)+动作(VRMA)+镜头(CameraShot),行内标记法。
按第七节的分期 A→D 实施:
A. adapter: EmotionExtractor 泛化为 TagExtractor,TagCue sealed(Emotion/Action/Camera),
   InlineTagExtractor 统一解析 <emo:名:强度>/<act:名>/<cam:机位> 与老协议 <|emotion:..|>,
   跨delta缓冲/holdback/flush 语义照搬 MarkerEmotionExtractor,单测覆盖。
B. corelib: VrmaAnimationEngine 非循环播完自动 stop+restoreRestPose(现在冻结在末帧);
   controller 暴露 getVrmaAnimationDuration()。
C. orchestrator: AvatarSession 三路分派(动作/机位映射在 orchestrator 做,adapter 只出字符串 cue);
   GestureDriver(目录可注入,play=load+playVrmaAnimation(loop=false),interrupt 时 stop);
   SystemPromptAssembler 出 multimodalProtocolBlock(可用列表动态注入,空段省略),
   顺带修 assemble() 与 buildRequestMessages 双重追加协议块的既有问题;
   speak() 路径也走 extractor(开场白支持标签);AvatarEvent 加 ActionStarted/CameraChanged。
D. app: 内置策展动作目录(6个对话手势)+外置库关键词匹配回落内置;设置加"AI 可控镜头"开关;
   装配 actionCatalog;事件收集补新分支。
验收: 真机(62fabe84)——字幕无标签泄漏/三类标签各触发对应通道(截屏差分)/未知名静默/
打断后动作立即停/协议遵循率观察/老卡片 <|emotion|> 回归。提交代码,风格 TYPE: feat 中文描述。
```

## 七、多模态行内标记协议设计（任务 8，2026-10-03 定稿）

> 目标：LLM 单流输出同时携带文字（→TTS/口型）、情绪（→表情）、动作（→VRMA）、镜头（→CameraShot）。
> 定稿结论：**行内标记法**；解析基础设施扩展现有 `MarkerEmotionExtractor`；**不新建平行管线**——
> 现有 `LLM流 → extractor → chunker → pipeline` 两级流原样保留，只在分派处扩成三类。

### 7.1 协议定义（取值全部对齐项目实有资产）

| 标签 | 语法 | 取值 | 执行端 |
|---|---|---|---|
| 机位 | `<cam:shot>` | `close_up / medium_shot / full_shot / long_shot / over_shoulder`（= `CameraShot` 枚举小写） | `controller.setCameraShot()`（已有平滑 glide） |
| 动作 | `<act:tag>` | **ActCatalog 动态生成注入 prompt**（模型只见过真实名字） | 新 `GestureDriver` |
| 情绪 | `<emo:name:强度>` | `EmotionBlender.defs` 现有 7 键：happy/sad/angry/surprised/think/relaxed/neutral，强度 0.0~1.0 | `faceDriver.applyEmotion()`（现有路径） |
| 语音 | 无标签纯文本 | — | chunker → TTS（零改动） |

- **情绪名不采纳** sorrow/wink 等新词：向现有 defs 对齐（sorrow→sad、surprise→surprised）；wink 依赖模型 morph 参差，可作 V1.5 选项（defs 加一条 + `FaceDriver.send` 的 `availableExpressions` 门控自动兜底）。
- **机位不采纳** look_down/dynamic_orbit：项目不存在，不教模型不存在的参数。dynamic_orbit 可作 V2（`orbitCamera` 原语已在，需自建运镜循环）。
- **老协议兼容**：`<|emotion:名[:强度]|>` 继续识别（老卡片/旧提示词零成本过渡），但 prompt 只教新家族。

**协议块由 `SystemPromptAssembler.multimodalProtocolBlock()` 生成**，可用列表参数化、空段整体省略（headless 无 controller 时机位/动作段不出现在 prompt）。**注入节奏（2026-10-04 起，当晚修正合并细节）**：AvatarSession 每上下文只组装一次协议文本（`pinnedProtocol` 钉住，目录变更原地重钉），不逐轮重拼；**发送时与人设合并为一条 system 消息**（硅基流动对多条 system 直接 400，A.1 第 34 条）——请求前缀 `[人设+协议一条 system][历史]` 逐字节稳定（前缀缓存友好）；换 LLM 模型的新开上下文由 app 层签名轮换 contextId 实现（见 2.4 最新条目）。

### 7.2 ActCatalog（动作目录动态生成——防胡编的根治）

```kotlin
data class ActionEntry(val tag: String, val label: String, val assetPath: String? = null, val filePath: String? = null)
```

- **内置策展起步集**（assets/animations 25 个里过筛，舞蹈/搏击/运动类全部排除；策展标准：**上半身对话手势 + 首尾姿态接近 rest pose**）：

| tag | 文件 | 语义 |
|---|---|---|
| wave | Greeting While Standing.vrma | 挥手问候 |
| nod | Acknowledging Gesture.vrma | 点头认可 |
| thank | Being Thankful While Standing.vrma | 致谢 |
| celebrate | Celebrating After A Win.vrma | 庆祝 |
| dismiss | Dismissing With Back Hand.vrma | 摆手否定 |
| salute | Formal Military Salute.vrma | 敬礼 |

- **外置动画库**（445 个）开启时按文件名关键词匹配（greeting→wave、acknowledg→nod、thank→thank…），无匹配回落内置 assetPath（assets 始终在包内，手动面板隐藏不影响加载）。
- `session.actionCatalog` 是公开可注入属性——SDK 集成者可换自己的目录；目录为空时协议块不出动作段。
- prompt 里模型只见真实名字 + 客户端未知名静默丢弃 = 双保险。

### 7.3 协议细节决策（每条都影响可用性）

1. **跨 delta 缓冲**照搬现有实现：标签可能被 SSE 拦腰截断（`<emo:hap` + `py:0.8>`），尾部未闭合 `<` holdback + 64 字符上限（病态输入防卡死）语义原样继承。
2. **历史记录保留原始标签**（`replyBuffer` append 原始 delta）：模型在上下文里看到自己上一轮的标签用法 = 免费 few-shot 自我示范，协议遵循率显著更高。字幕 UI 拿的本来就是 clean text。**这是刻意设计，勿当 bug 修掉。**
3. **未知名静默丢弃**：情绪（EmotionBlender.apply 未知名 return）、动作（目录查不到不播）、机位（映射表查不到不动）——不发错误事件，LLM 偶尔编名字是常态。
4. **解析器对"正文裸 `<` 在真标签前"要防泄漏**：`a < b <emo:happy> c` 这种片段必须不能把真标签当普通文本透传（实现：段匹配失败时若段内还有下一个 `<`，只透传到该 `<` 并从那里重新评估）。
5. **密度兜底节流**（可选后置）：同名情绪 ~800ms 内重复忽略，防低质量模型每句刷标签。真机看遵循率再定。

### 7.4 解析与调度架构

```
LLM SSE delta
  └→ InlineTagExtractor (adapter·纯JVM，MarkerEmotionExtractor 泛化)
       ├→ TagCue.Emotion / .Action / .Camera    ← 标签即抽即发(带缓冲语义)
       └→ cleanText ─→ SentenceChunker ─→ SpeechPipeline   ←【整条不动】
  └→ AvatarSession 分派（adapter 严禁依赖 corelib，cue 只带字符串，映射在本层做）：
       Emotion → pendingEmotions →(挂到其后第一个句子的序号)→ 开播瞬间
                 faceDriver.applyEmotion(cue, holdMs=clip时长+1.2s)  (2026-10-04 起)
                 同名序列 <emo:x:1><emo:x:0> 开播后 200ms 步进补发（眨眼模式）
       Action  → gestureDriver.play(tag)        (即发即执行)
       Camera  → controller.setCameraShot(map)  (即发即执行；Options.enableLlmCamera 门控)
       同步 emit AvatarEvent(字幕/调试/UI 徽标；EmotionChanged 在开播时发)
```

各层改动：
- **adapter**：`api/TagCue.kt`（sealed）；`ExtractionResult.cues: List<TagCue>`；接口更名 `TagExtractor`；`MarkerEmotionExtractor` → `InlineTagExtractor`（一个扫描框架 + emo/act/cam/legacy 四条 matchEntire 正则，IGNORE_CASE）。
- **corelib**：`VrmaAnimationEngine.update()` 非循环 `elapsed >= duration` 时自动 `stop()`（现在**冻结在末帧**，LLM 动作播完会僵住）；`AvatarController.getVrmaAnimationDuration()`。
- **orchestrator**：`gesture/ActCatalog.kt` + `gesture/GestureDriver.kt`（`play(tag)` = load + `playVrmaAnimation(loop=false)`；`interrupt()` 联动 `stop()`；session 构造参数可注入 fake 供单测）；`AvatarSession.Options` 加 `enableLlmGestures/enableLlmCamera`；`AvatarEvent` 加 `ActionStarted(tag,label)/CameraChanged(shot)`；**顺带修既有 bug**：卡片激活时 `assemble()` 追加一次协议块、`buildRequestMessages` 又追加一次 = 双重注入——改为 `assemble()` 只拼人设、协议块只在 `buildRequestMessages` 追加一次；`speak()` 路径也走 extractor（开场白支持标签）。
- **app**：内置策展目录 + 外置关键词匹配；设置加「AI 可控镜头」开关（`ai_llm_camera`，默认开——给用户保住取景主权的后门）；produceState 装配 `actionCatalog`（useExternalAnimations 变化触发重装）；事件收集补新分支（CameraChanged 同步视角徽标）。

### 7.5 时序语义（刻意的取舍，2026-10-04 第三轮改版）

**Emotion 与 Action/Camera 分家**（7.11 详述）：
- **Emotion 挂到其后第一个句子上，开播瞬间才应用**——旧"生成时刻即执行"在 TTS 排队延迟下，表情在句子出声前就被 3 秒自动归零吃掉（真机整段回复表情零变化）。hold=clip 时长+1.2s 余量，长句中途不再归零；同一句内混多情绪取句尾情绪。
- **Action/Camera 保持即发即执行**——动作是长持续态、镜头是模式切换，早几秒无妨（AIRI 同款"反应快"语义）。
- 已知近似：**同一个 LLM delta 内**，句号后的标签会挂到**前一句**（cue 分派先于切句）；真实流式 delta 很小，标签几乎总落在句子开始前的独立 delta 里，实测无损。

### 7.6 动作生命周期与已知取舍

- corelib 引擎**单动画槽**（setAnimation 覆盖式）：连续 `<act>` 相互抢占，"最后指令赢"，与相机语义一致；对话中动作天然低频，V1 接受。
- "自动平滑过渡回 idle"：V1 的归位是 `restoreRestPose()` **瞬时切换**，不是淡出——所以策展标准要求首尾接近 rest pose（大多数手势类 VRMA 结尾在站立位，切换不可见）。真机若见跳变，V2 在引擎加 ~0.3s 末姿态→rest 交叉淡化。
- VRMA 驱动骨骼、表情驱动 morph，两套通道天然无冲突；全身大动作配特写观感差 → 策展只选手势类 + prompt 引导，不做技术限制。

### 7.7 已解决、勿重复建设的现状

| 设计里的点 | 项目现状 |
|---|---|
| 口型 vs 情绪下半脸冲突防护 | **max 混合**（2026-10-04 第三轮，替代旧"口型独占+blend-back"）：每 morph 取 max(口型, 表情)——口型目标(上限 0.49)通常强于表情 garnish 保住清晰度，表情嘴部动作说话中全程可见，句尾口型衰减后表情无级接管（无 pop，blend-back 机制已删）；眼区情绪压制眨眼（决策 2 + A.1 第 15 条） |
| 流式标签解析器 | MarkerEmotionExtractor 已验证全部边界（跨 delta/holdback/防泄漏/flush 丢悬尾） |
| `*动作*` 星号动作剥离 | SentenceChunker 已有（卡片常用语法），与新标签并行不悖 |
| 未知表情名门控 | FaceDriver.send 的 availableExpressions 门控 |

### 7.8 分期与真机验收清单

分期 A（adapter+单测）→ B（corelib，动画面板回归一次，SpringBone 是敏感区）→ C（orchestrator+单测）→ D（app+真机）。

真机（62fabe84，硅基流动 DeepSeek-V3）验收：
1. 字幕无任何标签泄漏（含裸 `<`、半截标签、老协议）；
2. 三类标签各触发对应通道：截屏差分（镜头构图变化/动作帧间差/表情变化）；
3. 未知名静默（字幕不泄漏、无错误条、无崩溃）；
4. 打断 ✕ 后动作立即停、镜头保持在当前机位；
5. 协议遵循率：连续 10 轮对话统计漏发/滥发/编名（归因分开：泄漏=解析器 bug，不发/乱发=prompt 问题）；
6. 老卡片（`<|emotion|>`）与人物卡开场白（speak 路径带标签）回归。

### 7.9 落地记录（2026-10-03，以代码为准的最终出入）

- Options 的协议开关定名 `protocolInstructions`；`GestureDriver` 为 open 类（测试注入 fake，session 构造参数 `gestureDriver` 可覆盖）。
- 解析器 flush 的悬尾判定用前缀正则 `<(emo|act|cam)\s*(:|$)` + `<|` 开头——容忍流切在冒号前（`<emo`），同时不误吞 "5 < 3"、"<emotional"。
- §7.3 第 4 条（裸 `<` 防吞段）已实现：段匹配失败时若段内还有下一个 `<`，只透传到该 `<` 并从那里重新评估。
- `speak()` 最终也走 extractor（设计时原判"开场白无标记"，实现升级为支持标签——卡片开场白可以挥手/微笑）。
- 老协议 `<|emotion|>` 保留识别；system prompt 只教新家族；历史 assistant 消息保留原始标签（§7.3 第 2 条）。
- 真机证据（62fabe84，两轮对话）：`chat:` 事件流（CameraChanged MEDIUM_SHOT→ActionStarted wave→EmotionChanged happy 0.8，中段 CLOSE_UP；第二轮 celebrate+4 情绪）、SoulLinkRenderer `Loaded VRMA 5.08s / 8.54s`、截屏（特写机位 + 干净字幕）。协议遵循率初判良好：两轮全部自发正确标签、未编造名字；密度遵循"转折处一个"。

### 7.10 第二轮迭代：动作库扩容 + 表情全量暴露 + IDLE 待机（2026-10-03）

**① 动作库**：外置库 9 类 309 个（跳过 02_行走跑步转向——位移类会把角色走出画面）拷入 `assets/animations/<分类>/`，共 334 个/47MB。目录 = `buildLlmActionCatalog` 全量扫描 assets 生成（tag=文件名转小写下划线、去重、分类=子文件夹名、`CATEGORY_ORDER` 按对话价值排序），外置动画模式追加外置库文件。协议块动作段按分类分组、置于提示词最末（量大防稀释）。

**② 表情全量暴露**：`<emo:>` 统一词表 = 7 标准情绪 + 模型全部可用表情原名（`FaceDriver.availableExpressions`，模型加载后捕获）。两条新链路：
- `EmotionBlender.applyInternal` 未知名回退：`Def(listOf(name to 1f), 0.25f)` 单 morph 直驱——同款 easeInOutCubic + 3s 自动回 neutral，眨眼保持/微表情都由此实现；
- `FaceDriver.resolveExpression`：大小写不敏感还原 morph 名（解析器统一小写 cue，morph 名大小写敏感——见 A.1 第 18 条）；`AvatarSession` 分派预过滤：规范情绪直通、可解析名直驱、其余静默丢弃。

**③ IDLE 待机**：`VrmaAnimationEngine` 加 idle 槽（`setIdleAnimation` + `idleTakeover/idleAnchor` 相位锚定）——一次性动作播完或 `stop()` 自动切 idle 循环；`consumeIdleSwap()` 供 renderer 重置弹簧骨骼；无 idle 回落 rest pose。API：`controller.setVrmaIdleAnimation(FromFile)/clearVrmaIdleAnimation`；orchestrator：`session.idleAction` → `GestureDriver.setIdle`；app：默认自动选（`IDLE_PREFERENCE`：Idle Stand Looking Around 优先）+ 动画面板长按换待机 + `ai_idle_animation` 持久化 + `ai_cmd set_idle/idle_off`。

**④ 提示词工程结论（真机 5 轮迭代）**：
1. few-shot 输出示例是格式遵循的最强杠杆，且放长提示词**最末尾**（近因效应）——示例前置时模型仍发括号演戏；
2. 示例名字必须动态取自真实清单：硬编码 `blink_l` 在 ARKit 模型（`blinkLeft`）上被门控静默丢弃，模型照抄示例名；
3. 情绪 + 直接表情合并为一个 `<emo>` 词表段，规则里点名"要求具体表情必须从列表选名发标签"；
4. 显式禁止"（括号）/*星号* 演戏"（DeepSeek-V3 默认爱这么干）；
5. LLM 温度 0.8→0.6；动作列表（数百 tag）放提示词最后。
已知取舍：系统提示词 ~14.5K chars（455 动作 + 68 表情），协议遵循率随 prompt 规模波动，靠示例+收尾强调兜底；若后续仍不稳，优先裁剪 prompt 内动作清单（库全量保留在本地）。

**观测点**：`AvatarSession` 的 `system prompt: N chars, cameras=…, actions=…, directExpr=…`（注入核对）与 `raw reply: …`（模型原始输出——"没发标签"与"发了被丢弃"靠它区分）；`GestureDriver` 的 `idle set to …`；`SoulLinkRenderer` 的 `Loaded idle VRMA: …`。

### 7.11 第三轮迭代：表情时序 + 微表情组合 + 口型/表情混合（2026-10-04，真机待验）

用户反馈三个症状（同一份带 6+ 个 `<emo:>` 标签的回复）：①表情基本无变化；②手动试 angry/happy"一帧"没有过渡；③说话时嘴部动作掩盖表情嘴部。三个独立根因三个修法：

**① 表情标签挂句开播（AvatarSession）**：旧语义 cue 在 LLM 吐标签瞬间就 `applyEmotion`，而 TTS 合成+排队把开播推迟数秒，3 秒自动归零在出声前就把表情吃掉——整段回复的表情全部提前衰减完。现在 `dispatchCues` 只把 cue 存进 `pendingEmotions`，`submitSentence` 挂到句序号（`emotionsBySequence`，ConcurrentHashMap——开播回调在音频线程），`onPlaybackStarted` 时 `applyEmotion(cue, holdMs=clip时长+1.2s余量)` 并在此时才 emit `EmotionChanged`；句尾残余标签在回合收尾 `flushTrailingEmotion` 立即应用；interrupt/新回合清空挂接表。**协议教的眨眼模式 `<emo:x:1><emo:x:0>` 依赖即发语义——挂接后用"同名序列"保留**：`splitEmotionRun`（纯函数+单测）判全部同名 → 首条 t=0 应用、其余按 200ms 步进补发（hold=-1 不重置归零计时）；混不同名 → 句尾情绪生效。已知近似：同一 delta 内句号后的标签挂到前一句（真实流式 delta 很小，无损）。**测试基建坑**：fake LLM 流必须在 Finish 后完成 flow（`transformWhile`），否则 `send()` 在 collect 之后的收尾段（含 flushTrailingEmotion）永远走不到。

**② 微表情组合（EmotionBlender.defs 重做）**：真机默认模型 SK_Sun 是完整 ARKit 52 morph 集，但 7 个旧 def 只引用 VRM 预设名——其中 `think` 预设**根本不存在**，整条 def 被 FaceDriver 门控静默丢弃（`<emo:think>` 完全无效）。现在 13 种标准情绪全部重做：VRM 预设打底 + ARKit 微表情叠层（happy=笑+eyeSquint+cheekSquint 的 Duchenne 笑；sad=AU1+AU4 悲伤眉+mouthFrown；angry=browDown+noseSneer+mouthPress；surprised=三段眉抬+jawOpen+eyeWide+oh；think/asymmetric brow 微表情拼装；smug/shy/worried/confused/sleepy/determined 六种新增），模型缺的 morph 按条降级不炸。`SystemPromptAssembler.emotionNames` 词表同步（带中文释义）+ 协议块教"标签放句首、每 1~2 句换一个"；**`SystemPromptAssemblerTest` 锁死"词表=defs 键集"不变量**（分叉是 think 式静默失效的根源）。

**③ 口型/表情 max 混合（FaceDriver）**：旧所有权规则"说话中口型独占 aa/ih/ou/ee/oh"把情绪 def 的嘴部 garnish（happy→aa、surprised→oh、sad→mouthFrown 叠加…）说话期间全部抹掉。改为每 morph 取 `max(口型, 表情)`（`blendMouth` 纯函数+单测）：口型目标（WINNER_CAP 0.7×GAIN 0.7≈0.49）通常强于 garnish 保住清晰度，表情嘴部动作说话中全程可见，句尾口型平滑衰减后表情无级接管——旧的 viseme blend-back 机制整个删除（表情嘴部值从未被清零，无 pop 可back）。

**手动表情通道（②的伴生修复）**："一帧闪过"的根因：FaceDriver.start() 把控制器切 instant 模式（缓动全在 blender 自绘），而面板/`ai_cmd set_expression` 直写 `controller.setExpression` → 无缓动。新增 `FaceDriver.applyManualExpression/clearManualExpression`（blender apply + `HOLD_NO_RESET`——缓动进场、保持到替换/清除），面板点击与 ai_cmd 有会话时走此通道（`AiChatDebugHooks.manualExpression/clearManualExpression` 两个 hook），无会话回退直写（此时控制器不在 instant 模式，仍有默认 300ms 过渡）。

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
17. **release() 打断停在 wait() 的写线程 → FATAL 闪退（任务 8 期间实录于旧构建）**：`AudioTrackPlaybackQueue.release()` 先置 released 再 `writer?.interrupt()`，而 `writeLoop` 的 `lock.wait()` 没有捕获 InterruptedException——写线程正空等队列时 close/rebuild 会话（设置逐字符改动即重建、`AiChatController.ensure` 换配置）会让 `avatar-playback` 线程带未捕获异常死亡（MIUI 上 app 闪退重启）。打断主路径反而正常（写线程多半在 write//drain 里而非 wait 里），所以任务 1/2 冒烟没暴露。已修：wait 包 try/catch，中断即静默退出（released 已先置位，语义就是关停）。单测：构造队列→writer 停稳→release→用默认 UncaughtExceptionHandler 断言无未捕获异常。
18. **标签名统一小写 vs 模型 morph 名大小写敏感（直接表情全灭的元凶，任务 8.1）**：`InlineTagExtractor` 沿用老协议把 cue 名 `lowercase()`，而 `AvatarState.Ready.expressions`/`getAvailableExpressions()` 返回的 morph 名是大小写敏感的（SK_Sun 是 ARKit 命名 `blinkLeft/eyeBlinkLeft/browInnerUp…`）——`<emo:blinkLeft:1>` 解析成 `blinkleft` 后被 availableExpressions 门控**静默丢弃**，事件都不发。最坑的是模型明明发了标签（`raw reply` 日志可见），表象却是"模型不遵守协议"。已修：`FaceDriver.resolveExpression` 大小写不敏感还原真实 morph 名（纯函数单测），分派时规范情绪走小写、直接表情走还原名。教训：**"模型不听话"先看 `AvatarSession` 的 `raw reply:` 日志再归因**——这次连试 4 轮提示词工程都无效，实际是客户端丢事件。
19. **调试钩子读 UI 状态快照会陈旧（任务 3）**：`AiChatDebugHooks` 若捕获 Compose 的列表状态（如设置页用的 `contextList`，只在打开设置页时刷新），`ai_cmd` 在任意时刻执行时读到的是旧值——真机首次验证 `contexts` 返回空列表，实际库里已有会话行（run-as 拉库证实）。已修：`contexts`/`select_context` 钩子改为执行时 `runBlocking + Dispatchers.IO` 实时查库（调试命令在主线程同步执行，几十行的小查询阻塞可忽略）。教训：**给 adb 代理用的查询命令一律实时读数据源，不读 UI 派生状态**。
20. **"待机优先级表"按文件名先过滤候选集会让不含关键字的条目永远落空（默认 idle 改 Arms Down 时踩）**：`resolveIdleAction` 原实现先 `filter { 文件名含 "idle" }` 再按 IDLE_PREFERENCE 精确匹配——首位换成 "Arms Down" 后它根本不在候选集里，静默落到第二优先级（logcat 里 `Loaded idle VRMA: 6.33s` 而非 0.042s 暴露）。修法：优先级表直接在库全量里精确匹配。**观测点：挂载的 idle 是否符合预期，看 `SoulLinkRenderer` 的 `Loaded idle VRMA: <时长>`——Arms Down 是单帧（0.042s），一眼可辨**。
21. **HyperOS 麦克风 runtime 权限 adb 三条路全堵（任务 4）**：`pm grant` 报 SecurityException（shell 无 GRANT_RUNTIME_PERMISSIONS）、`adb install -r -g` 后 dumpsys 仍 `granted=false`、`appops set` 包级 allow 但 **uid 级被系统管控恒 ignore**——MediaRecorder `setAudioSource` 直接抛 `setAudioSource failed`。对策：验证 ASR 链路**不需要麦克风**——`ai_cmd transcribe <文件>` 走 file→bytes→适配器，与按住说话完全同一 ASR 路径（host 用 TTS 合成语音 push 进去还能做 TTS→ASR 闭环自校验）；录音链路只能真手首按授权。**顺带的观测坑：`ai_cmd screenshot` 抓的是渲染帧（`controller.captureFrame`，纯 3D 无 Compose UI），验证按钮显隐/聊天条形态必须用 `adb exec-out screencap -p`**。
22. **双服务商改造期间的三个坑（2026-10-03）**：①MOSS-TTSD 的模型实名是 **`fnlp/MOSS-TTSD-v0.5`**（裸 `MOSS-TTSD-v0.5` 报 `Model does not exist`），且它**不接受裸短音色名**（`anna`→`Invalid voice`），必须用 CosyVoice 引用 `FunAudioLLM/CosyVoice2-0.5B:anna`（跨模型音色互认，实测 200）；MOSS 的 mp3 输出只收 sample_rate 32000/44100，wav/pcm 16k 不受限。②火山语音 WebSocket 的鉴权域按 Resource-Id 分家：`volc.service_type.10029`（老 1.0 大模型 TTS）只认 AppID+AccessToken（`X-Api-App-Key`+`X-Api-Access-Key`，API Key 进去 403/401/400 各样花式拒绝）；**API Key 域的资源号就是字面量 `seed-tts-2.0`**（`X-Api-Key` 头实测 200 出音频）。③火山帧解析 `parseFrame` 的 ptr 必须整体 `= headerSize*4` 定位——逐字节步进少算一个 reserved 字节（ptr 停在 3 而非 4），事件号读偏成垃圾值，MockWebServer 单测首跑即炸（`StringIndexOutOfBounds Range [11, 11+0x32000000)`），**协议解析必须先写帧级单测再上真机**。
23. **火山是两把钥匙（2026-10-03 实测，已按两字段落地）**：豆包语音控制台 key（`020b5acf-…`，TTS WebSocket `X-Api-Key` 可用）与方舟 Ark key（`ark-…`，chat/completions 可用）**互不通用**（互调 401/403——语音域按 Resource-Id 分鉴权域，Ark 域按 Bearer key 查表）。初版两把 key 共用一个「火山引擎 API Key」字段是设计缺陷：用户同时要火山 LLM+TTS 时必然断一头。已拆 `apiKeyVolcano`/`apiKeyVolcanoTts` 两字段，选火山 TTS 时豆包语音 Key 字段恒显（勾「同服务商」也要单填，下拉框旁的文案讲清楚）；旧单字段值按 `ark-` 前缀形态迁移（`splitLegacyVolcanoKey` 纯函数+单测）。另：`doubao-seedream-5-0-pro-260628` 是图像生成模型，chat/completions 返回 `ModelAccountRpmRateLimitExceeded`（账户对该模型 RPM 配额为 0 的表现），排查"火山某个模型报 RPM 超限"先想到这一条。
24. **视线系统的三个坑（任务 5，2026-10-03）**：①**脸方向不能假设骨骼轴**：头骨局部系因模型而异（10.vrm 脸朝头局部 +Z，SK_Sun 朝 −Y——`VrmLookAt: bound` 日志的 `faceLocal=` 可辨）。必须在绑定时刻用 `inv(头rest世界四元数)·(0,0,1)` 反推 faceLocalDir（VRM 模型静止面朝世界 +Z：1.0 天然如此，0.x 被 renderer 翻转 180° 后如此，故绑定必须放在翻转**之后**）；直接给头骨叠欧拉角必有一个模型头反着转。②**读世界变换前必须 `commitLocalTransformTransaction()`**：VRMA 每帧只写局部变换，不提交就读头骨世界位姿会拿到上一帧的陈旧值（写完偏移再 `updateBoneMatrices()` 传播蒙皮，渲染循环里 updateBoneMatrices 从两个动画分支收口成 gaze 之后的一次）。③**眼骨余量分层依赖增益和=1**：头 0.65+颈 0.35 平滑分量，眼骨补 `目标角−平滑角`——头颈收敛后眼自然回中；改增益时两处必须同步改，否则注视点永远差一截（无眼骨模型没有补足通道，落点天然略短，可接受）。
25. **骨骼叠加旋转必须"先剥再叠"，否则不在动画轨道里的骨骼逐帧累积（任务 5 用户实测眼珠转过头只剩眼白）**：头/颈每帧被 VRMA/idle 重写，偏移乘上去不会累积；但**眼骨不在 VRMA 轨道里**——上一帧写入的偏移一直留在局部变换里，下一帧再把新偏移乘在"当前局部"上就是连乘，头颈 ~300ms 收敛期内眼偏移被乘十几遍然后冻结在滚转位置（全模型必现，凡是"叠加旋转"的代码都适用此教训）。修法：每个骨骼记 `lastWritten`（上次写入的局部四元数）+`lastOffset`（对应的局部偏移 K）；本帧读到的局部若与 `lastWritten` 角度差 <0.01rad（=没被动画重写）就用 `inv(K)·当前` 剥回干净基座再叠新偏移，被动画重写过就直接以当前为基座。纯函数 `VrmLookAtEngine.stripPreviousWrite` + 5 条单测锁语义（`VrmLookAtStripTest`）；顺带修了"无任何动画时头/颈也累积"的暗坑（同一条剥逻辑覆盖）。**观测点：`quatAngle` 在 dot≈1−2.4e-7 时 acos 会放大成 6.9e-4，近 1 必须钳零**（GazeMath.quatAngle 已处理，自己写角度比对时注意）。
26. **"多模态模型"必须实测，API 收下 image_url ≠ 真看图（任务 9）**：`deepseek-v4-flash-ga`（火山）对 image_url 请求不报错但回答 "The image data appears incomplete"（同一张图 doubao-seed-2-0-mini 描述正确）——若把它标成视觉模型，用户在视频模式里会以为模型"看得见"实际全瞎。核验方法：64px 纯色 JPEG + "图中是什么颜色" chat 一次，答对=过、报 not a VLM=拒、答非所问/报图不完整=假视觉。硅基流动对**文本模型发数组形态 content** 会直接 400 `The model is not a VLM`——所以 adapter 里纯文本消息必须保持字符串 content，只有带图消息才用数组形态（否则闲聊轮也全灭）。
27. **CameraX+ML Kit 组装三坑（任务 9）**：①`ImageProxy.toBitmap()` 复制像素必须在 proxy close 前、ML Kit `fromMediaImage` 异步读 buffer——**proxy 的 close 必须挂在 Task 的 addOnCompleteListener**（默认主线程回调，close 线程安全），在 analyze 里提前 close 会检测全灭；②检测框坐标是**旋转后直立系**的（InputImage 带 rotationDegrees），前置摄像头原始帧**未镜像**：脸在屏幕右侧时落在画面左侧，世界对齐 nx 要翻转（-cx），竖直不镜像——首验看头像转向是否符合"看向我的脸"；③ML Kit bundled 版（`com.google.mlkit:face-detection`）模型在 AAR 里不依赖 GMS，国产机可用；别选 unbundled（play services 变体）。
28. **HyperOS 相机权限 adb 三条路全堵（任务 9，与第 21 条麦克风同源）**：`pm grant` 报 SecurityException（shell 无 GRANT_RUNTIME_PERMISSIONS）、`adb install -r -g` 后 dumpsys 仍 granted=false、`appops set` 包级 allow 但 **uid 级恒 ignore**（`appops get` 显示 `Uid mode: CAMERA: ignore`）——CameraX `bindToLifecycle` 前必须 runtime 权限到位，只能真手在系统框点一次"仅在使用中允许"。设计上的对冲：无权限时视频模式**降级不崩**（PiP 不渲染、追踪器 inactive、发送自动走纯文本），权限就绪（进程存活期间授权回调 / 重启后 checkSelfPermission）即自愈。
29. **视频模式二轮的两个真 bug（2026-10-04 用户实测，均已修+真机复验）**：①**PiP 黑屏=组合时序**：`AndroidView` 的 PreviewView 在容器拿到尺寸前不进组合，而 `LaunchedEffect` 首帧已 `start()`——bind 只有分析流没有 Preview，之后没人重绑，永远黑。凡"先绑后挂 UI 面"的组合都要在**挂载回调里检查补绑**（`attachPreview` 里 force 重绑），别假设 effect 晚于视图创建。②**注视点恒偏右下=坐标系错位**：ML Kit boundingBox 在**旋转后直立系**，ImageProxy width/height 是**传感器缓冲系**（竖屏前置为横置 640×480）——居中的脸被算出 nx=+0.25/ny=+0.33 恒定偏置（恰为纵横比错位量，症状是"追踪有效但恒定偏向一侧"，与脸位置无关）。归一化抽纯函数 `FaceFrameMath`（rotation 90/270 宽高互换）+ 居中→零的回归单测。判别技巧：偏置**恒定**找坐标系/分辨率错位，偏置**随位置镜像**才找翻转符号。③顺带：`String.format("%.0f", Int)` 抛 `f != java.lang.Integer`——%f 只吃浮点。
30. **第三方原生日志应用侧关不掉，只能治本+换 release 包（2026-10-04 用户反馈日志噪音）**：`FaceDetectorV2Jni`（ML Kit，每次检测一对 V 级）与 `SkJpegEncoder`（"skia-debug"，每次 Bitmap.compress JPEG 一串 I 级）都**不走 android.util.Log，发送端无法在应用代码里关**。实测结论（release 用 debug 签名对照）：①`SkJpegEncoder` 只在 **debuggable 应用**上出现——release 包完全消失（app 的 release 已挂 debug 签名可直接装）；②`FaceDetectorV2Jni` release 照在，唯一手段是**降低调用频次**：`UserCameraTracker` 加帧级门（`DETECT_INTERVAL_MS=80`，检测 30fps→≈12.5fps，snapshot-only 帧不进 ML Kit），实测 V 级日志 ~60 行/s→~20 行/s（-66%）且 CPU 同降，注视追踪靠 One-Euro 平滑无感（face age ≤80ms）。自己代码的教训：分析帧路径上的"每帧一条"日志(任何层级)在 30fps 下都是灾难，日志量按帧率乘出来。读日志侧的治标：`adb logcat -s <我们的tag>`（VideoTracker/FreeSpeech/LlmPrompt/AIDebug）。
31. **首句等待长的排查顺序（2026-10-04 用户"发送→说话间隔太久"实测归因）**：先按三分法分段（见下方观测点段），**大头几乎总在 LLM ttfb**。本轮实测拆解：REQUEST 27.96s → 首条 SentenceQueued 32.44s（**LLM 首句 4.48s**）→ SentenceStarted 33.69s（TTS 合成 1.24s），感知等待 5.72s。LLM 侧两个叠加因素：①**各家混合推理模型默认开思考**——思考 token 走 `reasoning_content` 流式返回而适配器只读 `delta.content`，思考时间全部变成首句前的纯等待；修法=`llmExtraBody()`（AiProviders.kt）按服务商方言分发：火山 doubao-seed 系 `thinking: {"type":"disabled"}`、**硅基流动 Qwen3 系 `enable_thinking: false`（2026-10-04 用户实测千问慢的根因，Qwen3 混合推理默认开思考；Qwen3-VL-Instruct 非思考模型带上是 no-op）**；deepseek 系两边都不认识对方参数，返回 null 不发防 400。REQUEST 头行 `thinking=off` 确认生效（两种参数名都认）；②前缀缓存是否命中此前无观测——LLM 适配器已带 `stream_options.include_usage`，usage（prompt/cached/completion tokens）解析进 `LlmStreamEvent.Finish.usage`，RESPONSE 头行 `tok: prompt=A cached=B completion=C`，**cached>0 = 钉住协议的前缀缓存命中**（首请求冷缓存数值小属正常，看同上下文第二请求起）。TTS 侧 1.24s 是结构性的：火山适配器每句新建 WS 连接（TLS 握手+StartConnection+StartSession 2-3 个 RTT）且收齐整段音频才返回（`TtsAdapter` 句级整段语义）——要再降需做"连接复用/首包即播"，是改 `PlaybackItem` 语义的中型工程，等 thinking-off 真机复测后再决定。

32. **表情三连坑（2026-10-04 用户"表情没变化/一帧/口型掩盖"实测归因，第三轮已修，见 7.11）**：①**标签即发即执行 × TTS 排队延迟 = 表情必然提前衰减**——`<emo:>` 吐出瞬间应用，句子开播晚数秒，3 秒归零在出声前把表情吃掉；凡"cue→视觉通道"的链路都要问一句"这个通道的持续态会不会被时间吃掉"，会就必须挂到播放时刻（act/cam 是长持续态/模式所以幸免）。②**def 引用模型不存在的预设 = 整条静默失效**——SK_Sun 无 `think` 预设，`<emo:think>` 一个 morph 都不落；组合表必须用模型实有 morph 叠层，且词表与 defs 键集要有单测锁死（`SystemPromptAssemblerTest`）。③**全局 instant 模式下任何直写控制器的路径都是一帧**——FaceDriver 为自绘缓动把控制器切 0ms，手动表情必须走 blender 通道（`HOLD_NO_RESET`）而不是 `controller.setExpression`。另：VRM 预设在 ARKit 模型上的 bind 极粗（angry=嘴角下压、sad=browDown、relaxed=browInnerUp），单预设当"全脸表情"用观感必然单薄；`normalizeBindWeights` 把每条预设的最强 bind 归一到 1.0，预设权重 0.5 → 实际幅度比老模型大，def 里的权重按 0.12~0.6 起。
33. **org.json 在 JVM 单测里是"not mocked"桩（2026-10-04 预置卡导入映射/提示词覆盖单测全挂的根因）**：app 模块里用 `JSONObject/JSONArray` 写的纯逻辑一旦进 `testDebugUnitTest` 就抛 `RuntimeException: Method put in org.json.JSONObject not mocked`——单元测试跑在 JVM 上，android.jar 只提供签名桩。对策二选一：①逻辑改用 kotlinx.serialization 的 `Json.parseToJsonElement`/`buildJsonObject` 纯 API（不需要序列化插件，orchestrator 全模块都是这么活的）；②`testOptions.unitTests.returnDefaultValues = true`（会掩盖其他未 mock 的 Android 调用，别开）。本次选 ①，app 模块补 `libs.kotlinx.serialization.json` 依赖。**顺带：`adb shell am start --es ai_cmd` 的命令与参数是两个 extra（`ai_cmd open_panel --es ai_arg cards`），写进同一个 extra 带空格不会拆分（"open_panel cards" 整体被当成命令名报 Unknown）。**

34. **硅基流动拒绝多条 system 消息（2026-10-04 用户选卡后对话 400 的根因）**：请求带两条 system（人设+钉住协议）直接 400 `{"code":20015,"message":"\"messages\" in request are illegal: System message must be at the beginning..."}`——**即使两条都在最开头**（curl 实测复现，合并成一条立即通过）；火山 Ark/OpenAI 官方都收多条。教训：OpenAI 兼容 ≠ 消息结构完全兼容，**system 消息永远只发一条**（多段指令在发送时拼接即可，各段文本仍可各自钉住缓存）；"真机待验"的请求结构改动必须覆盖每个已配服务商各跑一轮。修法：AvatarSession.buildRequestMessages 把 persona 与 pinnedProtocol 文本 join("\n\n") 进同一条 system（协议钉住/重钉语义不变，AvatarSessionProtocolPinTest 锁死单条不变量）。
35. **AI 未就绪时激活人物卡 = clearHistory 被跳过，旧对话泄漏进新角色（2026-10-04 真机踩）**：activateEntry 只有 session != null 才清历史；模型还在加载时切卡走 pendingGreetingFile 延迟分支，而 LaunchedEffect(session) 的补挂路径只重放人设+开场白不清历史——新会话的 RoomConversationStore 懒加载把上一个角色的对话整段带进新卡的请求（用户侧表现：换卡后第一轮就带着旧角色上下文）。修法：pendingGreetingFile 被消费（=延迟激活的真正生效时刻）时补 clearHistory；常规 session 重建（换音色等）不动历史。**同类教训：凡"激活动作依赖会话就绪"的副作用，延迟补执行的路径必须逐项核对**。另：LLM 适配器 OkHttpClient 的读超时从默认 10s 提到 60s（14K+ 字符钉住协议的首 token 排队+预填充会超 10s，真机 TurnFailed: timeout）。
36. **对动画驱动骨骼的"一次性写入"会被动画冲掉（2026-10-04 挪动人物 bug 根因）**：拖拽=平移 humanoid hips（three-vrm mouse.html 语义，弹簧骨骼要感知真实身体运动），但 VRMA 的 hips 平移轨道每帧把 hips 局部变换整个重写（`restoreRestPose` 停播时也重写）——触摸时一次 `setTransform` 的位移下一帧就被冲掉，用户侧表现=「只有 T-pose 能挪动」（默认待机常开，实际永远拖不动）。修法：触摸只累加偏移，每帧在动画分支之后以「动画刚写出的平移」为基线重钉（corelib `HipsDragOffsetSolver` 纯函数；帧内 before/after 对比 + 引擎 `consumeHipsRewritten` 信号双覆盖）。**通用教训：任何对动画驱动骨骼的持久修改要么挪到动画不写的节点，要么做成逐帧重钉的基线+偏移**。注意不能挪 asset root 替代：弹簧骨骼 Verlet 状态存 center 节点空间（本项目 center=Root），挪 root 等于挪 center，弹簧零反应。

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
| 表情太僵 | `EmotionBlender.defs` 主权重（AIRI 用 0.7~0.8 修过僵笑；ARKit 微表情叠层权重 0.12~0.6） |
| 情绪切太快/太慢 | `blendDuration`（0.15~0.6s）；自动回落时长=开播时 hold（clip 时长+1.2s，2026-10-04 起），无句子挂接的残余默认 3s |
| 表情全程无变化 | 先 `grep InfoStreamDectect` 看模型有没有发 `<emo:>`（RESPONSE raw）；发了→看 `EmotionChanged` 事件时间戳是否紧贴 `SentenceStarted`（挂接生效），再确认情绪名在协议词表里（A.1 第 32 条②） |
| 手动表情一帧闪过/没有过渡 | 面板/`ai_cmd set_expression` 应走 FaceDriver 手动通道（缓动+不归零）；看 `AIDebug` 返回行有无 "(eased, holds until cleared)"（无会话回退直写） |
| 说话时表情嘴部被口型盖住 | 2026-10-04 起是 max 混合不该再发生；仍盖=表情 garnish 权重低于口型，调 `EmotionBlender.defs` 里对应 viseme 名目条（aa/oh/ee）或查 `FaceDriver.blendMouth` 单测 |
| 口型完全不动 | 先看 logcat `FaceDriver`（2Hz 采样：t/volume/top）与 `AvatarSession`（句失败堆栈），再对照 A.1 第 1/2/3/4 条 |
| 模型不发标签/用（括号）演戏 | 先看 **`LlmPrompt` 的 RESPONSE raw 段**（`adb logcat -s LlmPrompt`）区分"没发"vs"发了被丢弃"（A.1 第 18 条）；REQUEST 段核对 protocol（协议三段清单）与 REQUEST user 行里的【当前镜头视角】前缀是否注入（视角行挂在末尾 user 消息上，2026-10-04 起）；协议只在每上下文首请求钉一次（`protocol pinned:` 行=重钉时机）；温度是否 ≤0.6 |
| 动作播完僵住/回到张开双臂 | 待机没挂上：看 `SoulLinkRenderer` 有无 `Loaded idle VRMA`、`GestureDriver` 有无 `idle set to`；`ai_cmd set_idle <文件名>` 手动挂 |
| 冷启动就是 T-pose | idle 挂载失败或被清：看 `SoulLinkRenderer` 有无 `Loaded idle VRMA`（demo 现在与 AI 会话解耦，模型加载即自动挂，默认 Arms Down）；挂上了仍 T-pose 则查引擎版本是否含"挂 idle 即接管"语义 |
| 直接表情（眨左眼等）不生效 | `list expressions` 核对模型真实 morph 名 → 协议块示例名是否动态生成（勿硬编码）→ A.1 第 18 条大小写解析 |
| 按住说话报"录音启动失败：setAudioSource failed" | 麦克风 runtime 权限未授（HyperOS 禁 adb 授权，见 A.1 第 21 条）；真手首按弹系统框允许一次即可 |
| 语音识别失败/识别为空 | 先 `ai_cmd transcribe <push 的音频>` 分离"录音问题"vs"ASR 问题"（TTS 合成一段语音 push 进去可闭环自校验）；识别空文本=离麦远/环境静音；ASR 模型确认：硅基流动默认 `Qwen/Qwen3-ASR-1.7B`（设置可显式覆盖）；**mp3 输入偶发 HTTP 500 空 body 是硅基流动 ASR 端问题，换 wav 重试** |
| 火山 TTS/LLM 报 401/403 | 看错误条/logcat 里的响应体：Ark `The API key doesn't exist` = 填的是语音 Key 不是方舟 Key（A.1 第 23 条）；TTS 403 = `X-Api-Key` 配了不匹配的 Resource-Id（必须 `seed-tts-2.0`，A.1 第 22 条） |
| 切服务商后模型/音色不对 | 正常防护路径：存储值不在新服务商清单一律落该服务商默认（`resolveLlmModel/resolveTtsModel/resolveVoice` 校验+默认）；`ai_cmd chat_state` 看"已解析生效"配置核对 |
| 视线不动/想验证视线 | `ai_cmd look_at`（无参）看 target/yaw/pitch（yaw 非零=在生效）；`look_at camera` 回注视用户、`look_at off` 关闭、`ai_x/ai_y/ai_z` 指向世界点看头是否转向限幅 |
| 视频模式进不去 | `chat_state` 看 `vision=`：false 就是当前模型不可收图——`set_llm_model Qwen/Qwen3.8-27B`（硅基流动）或 `set_provider volcano`（默认即视觉）；错误条文案里有原因 |
| 视频模式没有画面/抓拍 | 先看系统相机权限（A.1 第 28 条，真手授权一次）；`state` 看 `video: active=` 与 `ring=`；`video_snapshot` 探测缓存；logcat `VideoTracker` 的 `camera bound`(preview=true 才对)/`released` 与 face detect 失败行；**小窗黑屏但 state active=true** = Preview 没绑上（A.1 第 29 条①的守卫失效时查这里） |
| 注视点恒定偏向一侧（与脸位置无关） | 坐标系/分辨率错位而非翻转符号：查归一化用的是直立系还是缓冲系尺寸（A.1 第 29 条②，FaceFrameMath）；偏向随位置左右镜像才去翻 nx 符号 |
| 自由说话不触发/误触发 | 先 `chat_state` 看尾部 `freeTalk=true(listening)` 确认在听；不触发=门限高了（调 `SpeechVad` startAbsolute 0.055↓ 或 floorMultiplier 4↑）；切句太碎=hangoverMs 800↓；虚拟人说话被打断太勤=bargeAbsolute 0.14↑/bargeSustainMs 350↑；都不动则先看 logcat `FreeSpeech` 的 utterance 字节行分清"没录到"vs"识别空" |
| 视线转头太狠/太弱（视频模式） | `FacePointProjector` 构造参数 `maxLateralDegrees`（默认 22°）/`maxVerticalDegrees`（默认 15°）——脸贴画面边缘时的转角上限，角度语义与机位无关；只动这两个度数。"凑近画面低头太多"则调 `maxAlongFraction`（默认 0.2，前伸钳制比例）。saccade 抖动（±0.25 世界单位）与该幅度独立，嫌眼神飘另调 SaccadeEngine 的 jitterAmplitude |
| 首句等待太久（发送→出声间隔长） | `adb logcat -s LlmPrompt` 看 RESPONSE 头行 `ttfb=`（首 token 延迟）与 `tok: cached=`（>0=前缀缓存命中；冷上下文首请求小属正常）；REQUEST 头行核对 `thinking=off`（火山 doubao-seed 系/硅基流动 Qwen3 系默认都开思考必须关，参数方言见 `llmExtraBody`，A.1 第 31 条）；再按 `SentenceQueued→SentenceStarted` 看 TTS 合成段（火山每句新建 WS 是结构性 ~1s，连接复用是后续项） |
| 回复不提画面内容 | logcat 搜 `multimodal turn:`——没有=没带图（非视频模式/无相机权限/模型非视觉任一），有=带了图是模型理解问题（换 Qwen3-VL 或 doubao-pro 观察） |
| 头反着看/斜视 | 先看 `VrmLookAt: bound` 日志 `faceLocal=` 是否离谱（绑定过早/翻转顺序错，见 A.1 第 24 条①）；再查单测 GazeMathTest 的 yaw 符号约定是否被改 |
| 注视点太飘/太木 | saccade 抖动幅度= SaccadeEngine `jitterAmplitude`（默认 0.25 世界单位，AIRI 值）；头颈跟随速度= VrmLookAtEngine `HEAD_SMOOTH_RATE`（7≈300ms 收敛，调大更跟手） |

验证期临时加的观测点（保留）：`FaceDriver` debugTick（播放中 2Hz 采样日志）、`AvatarSession` 句失败堆栈与 `clip #N pcm=X.XXs` 时长日志（核对句尾是否被截断，见 A.1 第 13 条）、`AIDebug` 的 `chat:` 事件时序（SentenceQueued/Started/Ended/EmotionChanged/Turn*；**EmotionChanged 2026-10-04 起在句子开播时刻发射**（紧贴 SentenceStarted=挂接生效），不再是标签吐出时刻）与 `send_chat`/`interrupt_chat`/`chat_state` 调试命令（用法见 docs/ai-debug-intents.md）。**问答链路日志统一前缀 `[InfoStreamDectect] `（2026-10-04，用户排查等待时长用，拼写保留用户原样）**：覆盖 LlmPrompt 的 REQUEST/RESPONSE 全部行、AvatarSession 的 multimodal turn/prompt(persona+protocol 规模)/raw reply/clip/sentence failed 行、AIDebug 的 `chat:` 事件流、FreeSpeech 的 utterance/barge-in——`adb logcat | grep InfoStreamDectect` 即得完整时序（REQUEST 时间戳→首条 SentenceQueued=LLM 出句耗时，SentenceQueued→SentenceStarted=TTS 合成耗时，首句 Started−REQUEST=用户感知的等待下限）。**首响三分法之上 LLM 段已细化**（2026-10-04）：RESPONSE 头行 `ttfb=Xms`=请求到首个文本 delta（首句等待的大头，与 REQUEST 头行时间戳相减一致）、`stream=Yms`=整个流的时长、`tok: prompt/cached/completion`=服务商上报的 token 用量（`cached>0`=钉住协议的前缀缓存命中；`cached=-1`=服务商没报明细）；REQUEST 头行 `thinking=`=是否显式关思考。

## 附录 B：记忆索引（新会话自动加载）

- `airi-source-anchors` — AIRI file:line 锚点与对齐参数（本文档的上游依据）
- `sdk-four-layer-progress` — 本次落地的进度/坑清单精简版
- 其余 corelib 相关：`pbr-only-rendering`、`filament-1683-api-constraints`、`filament-light-rig-spot-only`、`filament-pcss-adreno-cliff`、`filament-camera-programmatic-control`、`vrm-springbone-center-semantics`、`vrma-anim-library`、`android-device-debug-workflow`
