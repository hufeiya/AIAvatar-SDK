# 双 LLM 独立工作模式可行性分析 + 落地方案(参考 open-annie)

> 生成时间:2026-10-11。对应代码状态:`a89ac06`(SDK 0.1.4 / APK v1.2)。
> 外部调研对象:`github.com/rehan-remade/open-annie`(main 分支);其 `broker/UPSTREAM.md` 的上游事实核验于 2026-09-27,本文引用时标注「实测/作者核验/第三方」。
> **结论先行:可行,且侵入面比预期小得多。** 核心洞察:open-annie 的双模型不是炫技,是被全双工语音模型的形态**逼**出来的(音频流里塞不下动作标签);我们的单模型行内标签在文本 LLM 场景完全成立,应该保留为默认。双模型模式作为**可选拓扑**叠加:决策模型=第二个 `LlmAdapter` 实例(零新 adapter 代码),决策输出的落点=现有 `TagCue` 三条分发通路(几乎零新分发代码)。真正要新写的只有一个 `decision/` 包(~400 行含测试)+设置面板一组。

---

## 0. 结论速览

| 问题 | 答案 |
|---|---|
| open-annie 的架构 | GPT-Live-1(OpenAI 全双工语音模型,WebRTC,$0.05/分钟)**只管说话**;Jev(TypeSafe 决策模型)读实时转录,**~108ms** 决定表情/手势/身体;两者经薄 broker(fal serverless)中转,低置信度时本地规则地板兜底 |
| Jev 是什么 | 「决策模型」新品类(System One):**不做对话**,只回答题型化问题——noul(是/否)/choice(≤255 选项)/score(2-10 档),进=state+questions,出=每题概率+置信度;$0.042/M input、**输出免费**、64k ctx、p50 108ms(作者实测 n=20) |
| Q1:OpenRouter 有没有 Jev | **没有本体**(官方 API 实测 458 个模型,2026-10-11)。只有 `typesafe/jev-router`——用 Jev 给每次请求挑 chat 模型的路由器,不是决策模型,别混淆。真 Jev 两个入口:TypeSafe 自家 API(专用端点,非 OpenAI 兼容)、Cloudflare Workers AI(`typesafe/jev`,REST) |
| Q2:硅基流动有国产替代吗 | 「决策模型」品类国产云**都没有**;但超便宜小模型+JSON 协议完全够格:GLM-5.3-Flash(¥0.8/M in、缓存 ¥0.23)、Ling-mini-2.0(¥0.5/M)、Qwen3.5-35B-A3B(短 ctx ¥0.4/M)等;决策调用每回合几 K token,月成本可忽略。SDK 现有 `OpenAiCompatibleLlmAdapter` 零新代码接入 |
| Q3:他的手势哪来的 | **不是 VRMA**。63 个 clip 全部 headless Blender 手工 K 帧(IK 控制 rig)→ 烘焙成自研 clip JSON(VRM1.0 humanoid 局部空间旋转增量)+ pack.json 清单,CC0-1.0 |
| VRMA 能复用吗 | 直接搬=不行(要写 JSON→VRMA 转换脚本,技术上可行);**实际上没必要**——我们已有 445 个 VRMA 源库、309 个内置,语义手势已覆盖;他们真正稀缺的是 talk rests(说话体态)与 g_* 微节拍短语两类,按需用 Mixamo→VRMA 既有管线补,不整体搬。**要抄的是决策→动作的调度语义(闸门/节流/一回合一个动作),不是动作文件** |
| 我们的侵入面 | adapter **零改动**;corelib **零改动**;orchestrator 新增 `decision/` 包 + `AvatarSession` 构造参数/`send()` 一处并行协程;主 prompt 瘦身用现成开关 `Options.protocolInstructions=false`(AvatarSession.kt:144);app 设置面板一组 + ai_cmd |
| 成本 | 决策走小模型:每次问 ~500 token、每回合 ≤4 次 → ¥0.001~0.01/回合量级;真 Jev 更便宜(输出免费)但要专用 adapter(P2) |
| 语音流水影响 | **无**。决策协程独立、不 await、不阻塞 send() 主链(同 agent-feasibility「语音流水不打断」原则);TTS/口型/帧率链路无感 |

---

## 1. open-annie 是怎么做的

### 1.1 总体架构:说话的模型和动的模型彻底分离

```
浏览器(three.js + VRM 渲染)
   │ getUserMedia 麦克风 ──WebRTC(音频)──► GPT-Live-1(OpenAI 全双工语音模型)
   │                                          │  transcript.delta(实时转录外流)
   │                                          ▼
   │                                     broker(fal serverless 薄代理)
   │                                          │ state+questions
   │                                          ▼
   │                                     Jev(TypeSafe 决策模型,~108ms)
   │                                          │ {face, clip, reaction, energy}+概率
   ▼                                          ▼
   └────────────── ◄── 驱动表情/手势/身体(带概率闸门+本地规则地板)──┘
```

关键事实(来自 README / docs/contracts.md / broker/UPSTREAM.md,上游数据作者核验于 2026-09-27):

- **GPT-Live-1 只管说话**:`v1/live/sessions` WebRTC 全双工,音频+文本进出,**没有动作/情绪输出通道**。转录增量(transcript.delta)是 Jev 的唯一输入源。没有 end-of-turn 标记,断句全靠本地 RMS/VAD 启发式。
- **Jev 只管动**:读转录、答题型问题、给概率;**不生成任何话**。
- **broker 的存在原因**:Jev 无 CORS(浏览器直连被 400 挡)、GPT-Live-1 无浏览器临时密钥(不在 client_secrets 枚举里),必须服务端中转。
- **本地规则地板**:Jev 超时/429/低置信度时,规则兜底,角色不会僵住。

与我们的形态差异(为什么他们被迫双模型):

| | open-annie | 我们(AIAvatar-SDK) |
|---|---|---|
| 说话模型形态 | 全双工语音(WebRTC 音频流) | 文本 LLM → 断句 → TTS |
| 动作指令能否内嵌在说话输出里 | **不能**(音频流没地方放标签) | **能**(`<emo:/<act:/<cam:` 行内标签,已跑通) |
| 表情/动作的反应时机 | 独立通道,~108ms,说话说到哪跟到哪 | 跟随标签流,粒度=模型自己排版 |
| 主模型要求 | 无(不用懂任何协议) | 要学会标签词表(免费/小模型偶尔出格,靠 EmotionAliases/静默丢弃兜) |

结论:两条路线各自成立。我们做双模式的动机不是补短板,而是**给三个新选项**:①主模型彻底免协议(更小/更便宜/更稳的模型都能当主模型);②听相表情(主模型思考的 3~8s 静默期,现在脸是呆的,决策通道可以在这期间给「聆听表情」);③为将来接真 Jev 级时延的决策模型预留通道。

### 1.2 Jev:一个「决策模型」品类,不是 chat 模型

API 契约(UPSTREAM.md,作者逐条核验):

- 端点:`POST https://api.typesafe.ai/v1/systemone`,`Bearer` 认证,body=`{model:"jev-latest", state, questions}`;**问题 key 不发给模型**(只发 criteria)。
- 三种题型(都带 `instructions`):
  - `noul`:是/否,可选 `criteria:{"true":"…","false":"…"}`
  - `choice`:`criteria` 必填(选项→描述),最多 255 选项
  - `score`:`criteria` 必填(有序档位描述),2~10 档
- 响应:`{model, answers, usage}`,每题如 `{type:"choice", choice, probabilities:{…}, confidence}` / `{type:"noul", noul:0.87}`。
- 价格与限额:**$0.042/M input,输出免费**;64k ctx(state 32k);25 万 tok/s、1200 rpm。
- 时延(作者实测 n=20,~404 input tokens/次):**p50 108ms / p90 149ms**。
- 入口:**TypeSafe 自家 API**(需注册充值,专用端点)与 **Cloudflare Workers AI**(`env.AI.run('typesafe/jev',…)` 或 REST `POST …/ai/run`,32k ctx,同价,零数据保留;REST 是否包 `{result,…}` 信封**作者也未验证**,接入前先 curl)。
- 第三方评测的冷水(采纳时留意):有独立评测称 Jev「比 chat LLM 快 20 倍、便宜 153 倍,**但准确率明显更低**」。对粗粒度情绪/动作决策够用(有概率闸门+规则地板托底),别指望它做精细分类。

open-annie 给 Annie 配的题(翻译自 packages/core/src/instinct.js,值得整段抄的调度语义):

| 阶段 | 题目 | 类型 | 概率闸门 |
|---|---|---|---|
| 听(用户说话时) | face:此刻该是什么表情 | choice(全部表情 when 描述) | p≥0.6 才应用,intensity 固定 0.8 |
| 说(她说话时) | performs:她现在这句话**是不是真的在做**肢体动作(假设/引用/过去式/提议都不算) | noul | 动作类 clip 要求 p≥0.5(final)/0.6(部分句) |
| 说 | what:哪个身体 clip 最贴合她正在说的话 | choice(每个 clip 一条 when 描述+none) | 表达类 p≥0.8、动作类 p≥0.85;**一条 partial 链只出一个动作** |
| 说 | reaction:对谁、什么反应(agree/disagree/empathize/celebrate/recoil/ponder/bashful/scoff/none) | choice | 仅最终文本、p≥0.75、冷却期内不重复 |
| 说 | face:什么表情 | choice | p≥0.6,intensity 1 |
| 说 | energy:表演能量(gentle/moderate/high) | choice | 直通下游 |

Pacer 节流(同样值得抄):说相每回合 **最多问 4 次**、两次间隔 **≥450ms**、新增字数权重 **≥8**(≥16 才开始问);听相字数权重 ≥8、间隔 ≥900ms;「没有答案 ≠ 不动作,只是这次不能动」;每回合 reset。

### 1.3 手势动作从哪来:Blender 手工 K 的 CC0 动作包

motion/README.md 的事实:

- **63 个 clip**:3 个 idle、7 个 fidget(摸头发/拽袖子/搓手/交握/瞟/伸懒腰/扭胯)、2 个 listen、12 个语义手势(wave/clap/shrug/think/laugh/sad_slump/surprised_recoil/bow/point_self/excited_bounce 等)、1 个 dance(120bpm)、8 个 talk rests(talk_calm_1..3 / talk_animated_1..3 / talk_excited_1..2)、**32 个 g_\* 节拍短语**(beat/deictic/metaphoric/iconic/emblem 五类,如 g_beats3_r/l、g_list3_l、g_tap_r/l/both、g_flick_small_r)。
- **制作方式**:headless Blender 在 VRM 骨架上建 IK 控制 rig,关键帧以 Python 数据文件保存(poses.py/clips.py/phrases.py),碰撞检查后烘焙导出;CC0-1.0。
- **格式**:自研 clip JSON + pack.json 清单——每根 humanoid 骨存**相对 rest 姿态的世界旋转增量 `D = R_pose·R_rest^T`**,换算到 VRM humanoid 层级局部空间;hips 存位置偏移。**不是 VRMA 文件**,但同是 VRM1.0 humanoid 空间的旋转数据。
- 运行时质量大头不在模型,在**动调设计**:重音音节对齐手势时机、身体碰撞守卫、布料弹簧骨、talk 层与 body 层分池、phrase 停在 hold 点再 time-warp。

---

## 2. 三个问题直答

### Q1:OpenRouter 有没有 Jev 这类决策大模型直接调用?

**没有本体。** 2026-10-11 用官方 `/api/v1/models` 实测(458 个模型),与 typesafe 相关的只有一条:

- `typesafe/jev-router`——「Jev Router:为每次请求挑最合适的 chat 模型与推理力度」。这是**用 Jev 做模型路由**的产品,不是决策模型;定价字段为 -1(按用量另计)。网上营销站宣称的 `typesafe/jev-1.13` 上 OpenRouter,**实测 404,不存在**。

要直连真 Jev,只有两条路,都需要专用 adapter(非 OpenAI 兼容 chat):

1. TypeSafe 自家 API(注册、充值、`/v1/systemone` 专用端点);
2. Cloudflare Workers AI `typesafe/jev`(REST `/ai/run`,需先验证响应信封)。

**但这不挡路**:决策模型不需要是 Jev——OpenAI 兼容 chat 的小模型 + JSON 输出协议,在我们 P0 设计里就能扮演同一角色(§3、§4)。Jev 的不可替代点只有一个:**108ms 时延**,要等 P1 逐句同步/P2 真 Jev 接入才吃得到。

### Q2:硅基流动(国内)有没有 Jev 的国产替代?

「决策模型」作为品类,国产云(硅基流动/火山方舟/阿里百炼等)**目前都没有**对应产品——这是 TypeSafe 2026-09 造的新词。替代思路:超便宜小模型 + 结构化 JSON 提示词,硅基流动上候选(siliconflow.cn/pricing,2026-10-11 抓取;注:老一代 Qwen2.5-7B/GLM-4-9B 已从价格页下架):

| 模型 | 价格(输入/输出,每 M token) | 备注 |
|---|---|---|
| **zai-org/GLM-5.3-Flash** | ¥0.80 / ¥2.80(缓存 ¥0.23) | 决策模型首选:前缀缓存便宜(state 部分基本命中缓存) |
| inclusionAI/Ling-mini-2.0 | ¥0.50 / ¥2.00 | 最便宜档 |
| Qwen3.5-35B-A3B(短上下文) | ¥0.40 / ¥3.20 | MoE A3B,吞吐高 |
| stepfun-ai/Step-3.5-Flash | ¥0.70 / ¥2.10 | 备选 |
| tencent/Hunyuan-MT-7B | 免费 | 翻译向,决策不一定行,先验证 |
| XingChenAGI/Xing4.0-29B | 免费 | 免费兜底 |

成本核算:决策单次 state ~500 token、每回合 Pacer 硬顶 4 次 → ~2K input token/回合,用 GLM-5.3-Flash ≈ **¥0.0016/回合**,聊一小时(300 回合)不到 ¥0.5,**可以忽略**。SDK 已有 `OpenAiCompatibleLlmAdapter`,新增一个实例即接入,零新 adapter 代码。

### Q3:他的手势动作哪来的?有 VRMA 文件可以复用吗?

来源 = **Blender 手工 K 帧的 CC0 动作包**(§1.3),格式是自研 clip JSON,**不是 VRMA**,仓库里没有 .vrma 文件。

复用可行性分三档:

1. **直接搬:不行。** 格式不同(旋转增量 JSON vs VRMA/glTF 扩展),要写转换脚本。技术上可行——两边都是 VRM1.0 humanoid 骨骼的旋转数据,写个 `clip.json → VRMA(VRMC_vrm_animation) ` 的离线脚本工作量不大(参考我们已有的 VrmaParser 反向推导轨道结构)。
2. **实际没必要整体搬。** 我们已有 445 个 VRMA 源库、309 个已内置化(assets animations/ 334 个文件/47MB)+ IDLE 待机系统,wave/nod/clap/bow 等语义手势都有覆盖。他们包里我们**真正缺的品类**是:talk rests(说话体态循环,8 个)和 g_\* 微节拍短语(32 个)——建议按需用既有 Mixamo→VRMA 管线补这两类,而不是整包移植。
3. **该抄的是调度语义,不是文件**:概率闸门(0.5~0.85 分档)、「一条 partial 链只出一个动作」、reaction 冷却、Pacer 节流、本地规则地板——这些才是 open-annie 动作系统「看起来活」的原因(§1.2)。

---

## 3. 双 LLM 模式设计(默认仍是单模型行内标签)

### 3.1 模式定义与开关

```kotlin
// AvatarSession.Options 新增
enum class LlmTopology { SINGLE, DUAL }   // SINGLE=现状默认,行为零变化
val llmTopology: LlmTopology = LlmTopology.SINGLE

// AvatarSession 构造参数新增(均可空)
private val decisionLlm: LlmAdapter? = null,
private val decisionConfig: LlmConfig? = null,
```

- **SINGLE(默认)**:现状全保留——主 LLM 输出行内标签,`InlineTagExtractor` 抽取,`dispatchCues` 分发,13 情绪挂句开播、动作/镜头即时执行。
- **DUAL**:主 LLM 用现成开关 `protocolInstructions=false` 瘦身(AvatarSession.kt:144/252/786)——**主提示词里不再出现 `<emo:/<act:/<cam:` 词表块**,主模型只管说话:prompt 更短、前缀缓存更友好、免费/小模型也能当主模型、TTS 拿到的是纯净文本。决策 LLM 是第二个 `LlmAdapter` 实例 + 独立 `LlmConfig`(ChatTypes.kt:29,baseUrl/apiKey/model 全独立,可与主模型同服务商不同型号、也可跨服务商)。
- **降级语义**:DUAL 但 decisionLlm 缺席/报错/超时 → 决策通道静默无动作,对话完全不受影响(open-annie「缺失的答案 ≠ 不动作」同款);主模型残留标签仍走现有 dispatchCues,无害。

### 3.2 数据流(决策通道与主链并行)

```
send(text)
  ├─ 主 LLM 流(无标签协议) ──SentenceChunker──► SpeechPipeline ──► TTS ──► 播放   〔全链现状,零改动〕
  │
  └─ scope.launch  DecisionCoordinator(独立协程,不 await)
       │
       ├─ listen 相(发送瞬间):state=userText → 问一次 → 表情
       │    〔趁主模型 THINKING 的 3~8s 静默期给「聆听表情」,现阶段的空白窗口〕
       │
       └─ reply 相(Pacer 节流):cleanText 每攒 ≥8 字符且距上次 ≥450ms、每回合 ≤4 次
             state=userText + 已说出部分(部分句,同 open-annie partial 语义)
             → 一行 JSON:{"emo":"happy","intensity":0.7,"act":"wave","cam":null}
             │  kotlinx 宽松解析(截第一个 {...}、容忍出格),失败静默丢
             ▼
        List<TagCue>   ←复用现有类型,未知名过 EmotionAliases/catalog 过滤(与现有 dispatchCues 同语义)
             ▼
        emo → FaceDriver 立即应用(不走挂句)+ emit EmotionChanged
        act → GestureDriver.play(tag)  〔单槽 last-cue-wins 现成〕
        cam → dispatchCamera(name)     〔现成〕
```

设计要点:

1. **决策输出与行内标签同构**(都是 `TagCue`),分发代码几乎零新写;唯一差异是表情从「挂句开播」改为「到达即应用」——决策通道不知道 TTS 排队时序,`FaceDriver` 的 hold/归零机制兜住时长(open-annie 同款:Jev 也不知道 TTS 何时开播)。P1 再加「到达时若下一句尚未开播 → 借 `emotionsBySequence` 挂句」的同步优化。
2. **不碰语音流水**:决策协程独立 collect、不 join、不阻塞 send() 主链;回合结束 `resetTurn()`(Pacer 计数/冷却/`partialActed` 清零,抄 instinct.js)。
3. **单写者原则**:DUAL 模式主模型无词表 → 理论上不再产标签;决策通道是表情/动作/镜头的唯一权威,与技能车道的关系见 §3.4。

### 3.3 改动清单(预计 ~400 行含测试)

| 文件 | 动作 | 内容 |
|---|---|---|
| `avatar-orchestrator/…/decision/DecisionCoordinator.kt` | 新增 | Pacer(纯 JVM 可单测:4 次/回合、≥450ms、字数权重、一回合一个 act、reaction 冷却、回合 reset)+ 听/说两相编排 + 超时(单次问 ≤3s) |
| `avatar-orchestrator/…/decision/DecisionParser.kt` | 新增 | 宽松 JSON 解析 → `List<TagCue>`;未知 emo 走 `EmotionAliases.resolve`,未知 act 查 catalog 不中即丢(与 AvatarSession.kt:590 dispatchCues 同语义) |
| `avatar-orchestrator/…/i18n/DecisionTexts.kt` | 新增 | 决策 system prompt 双语(zh/en,跟 PromptTexts 同惯例):角色一句话 + 表情词表(复用现有 emotionNames/morph 直驱名)+ 动作目录的 **when 描述**(学 open-annie 的 clip `when`)+ 镜头清单 + JSON 格式约定;**沉默一等公民**(全 null 合法且常见) |
| `avatar-orchestrator/…/session/AvatarSession.kt` | 修改 | 构造参数 +decisionLlm/+decisionConfig;Options +llmTopology;send() 内 launch 决策协程;新 `dispatchDecision(cues)`(emo 立即/act/cam 复用现有函数);close()/interrupt() 顺带取消 |
| `app/…/ui/SettingsScreen.kt` + `AiProviders.kt` | 修改 | 「动作决策」分组:开关 + 服务商/模型选择(key 复用现有 provider 凭据存储) |
| `app/…/AiDebugExecutor.kt` | 修改 | `ai_cmd decision on/off/state`(state 报最近一次决策内容与时延,便于真机排查) |
| adapter / corelib / TagCue / GestureDriver / FaceDriver / SpeechPipeline | **零改动** | — |

### 3.4 与技能车道、待机系统的仲裁

现有体系已经是多来源动作的成熟仲裁,决策通道直接搭车:

- **动作**:GestureDriver 本就单槽抢占(last-cue-wins)+ 播完自动回待机/IDLE;`<act:>` 标签、技能 `playFile`(AvatarSession.kt:487,防协议泄漏的旁路)、决策 LLM 三者天然同语义。
- **优先级**:技能直驱 > 决策 LLM > IDLE 待机(现状即如此);**mimic 技能运行中决策通道 yield**(加一个 MimicActive 判断跳过 act——模仿我时表情动作归 PoseLandmarker 车 道)。
- **表情**:FaceDriver hold/归零兜底;决策频率被 Pacer 压住(≤4 问/回合),不会刷屏。

### 3.5 与 agent-feasibility.md 的关系:三条通道正交

| 通道 | 解决什么 | 状态 |
|---|---|---|
| 单模型行内标签 | 说话模型自带多模态表达(默认,已跑通) | 已上线 |
| 双模型决策(本方案) | 表达的反应速度与主模型解耦;主模型免协议 | 本文档 |
| 工具调用(agent-feasibility P0) | 能力扩展(看/查/记/定闹钟),回合内循环 | 已有设计,独立推进 |

三者可叠加:工具调用的结果(如查到天气)进入对话后,决策通道照样据此调表情;双模型模式不依赖原生 tool-calls,所以**免费/小模型路由(新手引导 INTL 版的 openrouter/free)也能享受完整多模态表达**——这是它相对工具调用路线的独立价值。

### 3.6 决策提示词骨架(P0,chat 模型版)

```text
system: 你是虚拟人「{名字}」的肢体语言导演。根据对话判断她此刻应有的表情、
动作和镜头,只输出一行 JSON,不要输出其他任何内容:
{"emo":"表情名或null","intensity":0.0~1.0,"act":"动作tag或null","cam":"镜头名或null"}
没有合适的就全 null;不要编造词表外的名字;一回合最多一个动作。
可用表情:{13 情绪名 + 模型原生 morph 名}
可用动作:{tag——when 描述}(如 "wave——挥手打招呼,用于迎接/告别/引起注意")
可用镜头:{CAMERA_SHOTS 名单}
user: [用户说]{userText}
      [她说到]{partialAssistantText}
```

chat 模型没有 Jev 那样的真概率,自报 confidence 不可靠 → P0 用**结构性闸门**替代:Pacer 硬顶 + 一回合一个动作 + 同名表情冷却 + 未知名静默丢。P2 若接真 Jev,再把上面的单问翻译回 noul/choice 题型,照抄 gateReply 的 0.6/0.85 阈值。

---

## 4. 决策模型选型

| 路线 | 模型 | 价格 | 接入 | 建议 |
|---|---|---|---|---|
| 硅基流动(默认推荐) | GLM-5.3-Flash / Ling-mini-2.0 | ¥0.5~0.8/M in | 现有 OpenAiCompatibleLlmAdapter,零新代码 | 中文决策稳、state 前缀命中缓存后更便宜 |
| OpenRouter | 小 Flash 系(openrouter/free 免费路由) | 0~低 | 同上 | 海外引导包(INTL)场景适用;免费路由质量参差,靠闸门兜 |
| Cloudflare Workers AI | `typesafe/jev` | $0.042/M in,输出免费 | **P2**:专用 adapter(REST 非 OpenAI 兼容;先 curl 验证响应信封) | 108ms 真时延,P1 逐句同步后才吃得到 |
| TypeSafe 直连 | `jev-latest` | 同上 | P2,同上+充值 | 与 Cloudflare 二选一即可 |
| 本地规则 | 规则地板/小分类器 | 0 | 可选远期 | open-annie 的 local rule floor 同款;我们呼吸/眨眼/IDLE 系统已是天然地板 |

---

## 5. 分期落地

- **P0(≈2-3 天):双模式骨架**
  1. `Options.llmTopology` + 构造参数 + `protocolInstructions=false` 瘦身验证(SINGLE 路径回归);
  2. DecisionCoordinator(听/说两相 + Pacer)+ DecisionParser + DecisionTexts(双语);
  3. `dispatchDecision`(emo 立即/act/cam)+ 回合生命周期挂钩;
  4. 设置 UI「动作决策」分组 + `ai_cmd decision on/off/state`;
  5. 单测(JVM):Pacer 全闸门矩阵、Parser 宽松用例(出格 JSON/未知名/全 null)、DUAL 模式 prompt 无词表断言、SINGLE 回归 —— 预计 +25~35 条。
- **P1(≈2-3 天):同步与打磨**:表情到达时借 `emotionsBySequence` 挂句优化;mimic 让位;真机 A/B(单 vs 双:听相表情出现时延、动作合理性、成本日志加 `DECISION_TAG`);决策提示词按真机表现迭代。
- **P2(按需)**:JevAdapter(Cloudflare REST 先 curl 验证信封,再 TypeSafe)+ noul/choice/score 题型化 + 概率闸门;talk rests/g_* 类 VRMA 补库(Mixamo→VRMA 既有管线);(可选)annie-blender clip JSON→VRMA 离线转换脚本。

---

## 6. 风险与对策

1. **决策时延**:chat 小模型 0.5~2s ≫ Jev 的 108ms。听相落在 THINKING 静默期,体感无碍;说相靠 Pacer 早触发(≥16 字重即首问)+ FaceDriver hold;逐句同步 P1;真时延需求等 P2 Jev。
2. **小模型 JSON 出格**:宽松解析 + 未知名静默丢(现有 dispatchCues 同语义)+ 全 null 合法;最坏结果=没动作,对话零风险;OpenRouter 免费路由尤其要靠闸门。
3. **双写冲突**:DUAL 模式主模型已无词表,理论无标签;残留标签照常走 dispatchCues(无害冗余);与技能/mimic 冲突 → §3.4 yield 规则。
4. **Jev 准确率争议**:第三方评测「明显更低」——我们只做粗粒度表达决策且有闸门+地板,可接受;不用于精细分类任务。
5. **成本失控**:Pacer 硬顶 4 问/回合 × ~500 token,量级见 §Q2;`extraBody`(ChatTypes.kt:38)可关小模型的思考模式(OpenRouter doubao-seed 关思考的既有经验照搬)。
6. **Cloudflare REST 信封未验证**(open-annie 作者也没验证):P2 动工前先 curl,不确定就先走 TypeSafe 直连。

---

## 7. 验收清单(真机)

- `ai_cmd decision on`:说「你好呀」→ 主模型 THINKING 期间 1~2s 内出现聆听表情(现版是呆脸);
- 「跟我挥挥手打个招呼」→ 说话中出现 wave,**一回合 ≤1 个动作**,播完回待机;
- 连续 10 轮:DECISION_TAG 日志(每问 tokens/时延/结果)、无 TTS 卡顿、帧率 60fps 基线不破(同步说话掉帧已修的回归项);
- `ai_cmd decision off` 回 SINGLE:行内标签全功能回归(13 情绪挂句开播/口型/动作/镜头/眨眼协议);
- 决策模型 key 配错/断网:对话照常,只是没动作(降级语义);
- 单测全绿(现 588 + 新增)。

---

## 8. 参考资料

- open-annie:README、docs/contracts.md、broker/UPSTREAM.md(上游事实,作者核验 2026-09-27)、motion/README.md、packages/core/src/instinct.js
- Jev 第三方评测:[TypeSafe AI's Jev on Screen Classification(hashnode)](https://hashnode.com)、[AI That Doesn't Want to Chat: Cloudflare Clef, TypeSafe Jev, and the Rise of Decision Models](https://blog.melvinjonesrepol.com)、[Jev Architectural Deep Dive(rankllms.com)](https://rankllms.com)、[Experimenting with Jev on Cloudflare Workers AI(mattorb.com)](https://mattorb.com)——注意其中部分站点带营销水分,采纳数字以 open-annie UPSTREAM.md 的实测为准
- OpenRouter 模型清单:`https://openrouter.ai/api/v1/models`(2026-10-11 实测 458 个,typesafe 仅 jev-router)
- 硅基流动价格页:siliconflow.cn/pricing(2026-10-11)
- 本仓库:docs/agent-feasibility.md(工具调用路线,与本方案正交)、docs/ai-layer-handoff.md §7(行内标签协议)
