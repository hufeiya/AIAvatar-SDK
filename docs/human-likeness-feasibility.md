# 虚拟人「更像真人」开放命题探讨：从固定目录到任意表情、任意动作

> 起因：开放命题——「现在有呼吸、有视线闪变、有固定的表情和动作，但还是不能做出任意表情和任意动作，怎么更像真人？」。本文不改代码，做一次系统性探讨与可行性收敛，作为后续立项依据。
> 基线：commit `6808513`（2026-10-23）。
> 配套阅读：`docs/avatar-realism-feasibility.md`（2026-10-04 六项生理层方案——**本文是它的上一层续篇**，旧文档 P1 尾巴并入本文 P0）、`docs/inline-tag-reliability.md`（协议可靠性的硬边界，任何协议扩展前必读）、`docs/agent-feasibility.md`（主动性方案与「不做心理状态机」的既有决策）、`docs/ai-layer-handoff.md`。

---

## 0. 结论速览

**核心判断：「像真人」不是资产数量问题，而是「表情/动作的组合自由度 × 与语音的时序耦合 × 跨频道一致性 × 行为的历史惯性」四件事。当前系统的短板不在「会的不够多」（346 个动画 + 52 个 ARKit morph 已经是一个很大的基），而在「只能在基上单选」和「选了之后与语音各干各的」。**

| 层 | 内容 | 现状 | 差距级别 |
|---|---|---|---|
| L0 生理层 | 呼吸、眨眼、saccade、弹簧骨骼 | 呼吸✅ saccade✅ 眨眼基本✅（`VrmBreathEngine` / `SaccadeEngine` / `MicroMotionEngine`） | 小（旧文档 P1 尾巴：非对称眨眼、微抖层、事件联动） |
| L1 情绪表达层 | 连续面孔空间、表情轨迹、层叠与惯性 | 13 命名情绪单状态机 + 原生 morph 直驱已进协议 | **中——本文主战场之一** |
| L2 话语耦合层 | 非语言行为与语音韵律/句法/话轮的相位对齐 | emo 挂句开播、act 每轮一个、走动+手势 mask | **中——性价比最高的盲区** |
| L3 人格层 | 行为风格参数、情绪惯性（mood） | persona 纯 prompt 概念，零数值化 | 大 |
| L4 交互层 | 倾听反馈、话轮信号、backchannel | phase/导演制基础设施在，表现层全缺 | 大（旧文档 #6 仍未做） |

「任意表情」的解法：**把 13 个命名情绪当成连续面孔空间（morph 基上的向量）的 13 个关键点，做插值、轨迹与层叠**——底座（`VrmExpressionManager` 连续权重 + 多表情叠加）已经在代码里，缺的只是空间数学和输入协议。
「任意动作」的解法：**四级火箭**——参数化重放（346×修饰维度≈数千可辨动作）→ 行为组合（BML-lite 多频道并发）→ 程序化（IK/additive 微动）→ 生成式（云端离线当内容工厂，不当运行时依赖）。

---

## 1. 问题建模：「像真人」到底是什么

### 1.1 一个反直觉的起点：一致性 > 自由度

多模态交互研究的反复发现（也是恐怖谷的工程面）：**不一致的丰富比一致的简单更显假**。脸在笑、声音平、身体僵——三个频道互相打架，观众立刻判定「不是活的」。反之，一个只会六个表情但每个都与语音重音、句法停顿精确对齐的虚拟人，会被感知为「活的」。

推论：**先做耦合与一致性，再做自由度**。这也是为什么路线图把「韵律对齐」「话轮目光」放在「生成式动作」之前。

### 1.2 第二条：时序 > 内容

非语言行为的意义大半在相位关系，不在内容本身。同一个挥手：
- 落在重音上 → 「他在强调」；
- 随机时机 → 「它在播放动画」。

人类的感知系统对 100~300ms 级的相位误差极其敏感（对口的 McGurk 效应、话轮交接的 200ms 间隙研究都是证据）。当前系统恰好是「内容有、相位无」：emo 挂句开播是句级对齐（已比大多数开源项目好），但句内没有任何韵律级对齐。

### 1.3 第三条：「任意」= 组合空间 × 动力学，不是资产无限堆

「任意表情」的物理上限是模型的 morph 基（SK_Sun：52 个 ARKit 命名 custom + 18 个 VRM 预设，`avatar-realism-feasibility.md` §1.3 实测）。在这个基上：
- **任意静态组合**已经存在——`directExpressions` 让 LLM 可以 `<emo:原生名:权重>` 直驱任意 morph（AvatarSession.kt:1232 进协议块）；
- 缺的是**任意动力学**：组合的插值轨迹、多表情层叠、随时间的连续演化。

「任意动作」同理：346 个 clip 是基，缺的是**参数维度**（镜像/幅度/速度）与**组合语法**（多频道并发、时间偏移）。「任意」的工程解=把基上的组合空间撑到数万级，生成式只补真正的长尾。

### 1.4 第四条：LLM 编排，运行时实现

LLM 输出「做什么、什么强度、和哪句话对齐」（语义与结构），逐帧数值永远由端侧确定性代码生成。这与 `inline-tag-reliability` 的结论同源：结构化、白名单、词表锁死的协议才可靠；让 LLM 直出骨骼数值既不可靠也无必要。

---

## 2. 现状底牌盘点（被低估的资产）

探讨「怎么补」之前，先盘点「已有但没用满」的底牌——**这个仓库的底牌比想象中好**：

| # | 资产 | 锚点 | 现状 | 未释放的价值 |
|---|---|---|---|---|
| 1 | morph 连续权重 + 多表情叠加 clamp | `VrmExpressionManager.kt:315-427`（target/current 双表逐帧 lerp，多表情权重累加） | 被 EmotionBlender 当「离散情绪的渲染器」用 | **它本身就是一个连续面孔空间的插值底座** |
| 2 | 原生 morph 直驱协议 | `AvatarSession.kt:1232`、`PromptTexts.kt:332-336` | LLM 可 `<emo:原生名:权重>` | 任意静态组合的入口已存在，缺层叠与轨迹 |
| 3 | 64ms 音素帧自带 volume | `api/LipSyncProcessor.kt:10-14`（PhonemeFrame: timeSeconds + volume RMS） | 只用来取 viseme | **免费的韵律包络**——重音检测、头部 beat、表情强度调制的现成信号源 |
| 4 | 双槽 crossfade + 45 骨上半身 mask + 运行时变速 | `VrmaAnimationEngine.kt:27-40/:67-81/:395-397`、`AvatarRuntime.kt:963-978` | 走动专用 | 组合化引擎的半成品，mask 目前只在走路 overlay 自动启用 |
| 5 | VRMA expressionTracks | `VrmaParser.kt:284` 解析、update 不消费（project-guide §5.2 记账） | **蒸发的表情轨道** | 若有带表情轨道的素材，驱动它=零新增资产的「说话带表情」 |
| 6 | 检索面 vs 库容 | `ActionCandidateSelector.kt:8-22`（22 白名单+关键词 top-12）vs 346 库 | LLM 实际只看得到 ~34 个 | 「任意动作」的第一块短板其实是**检索面**，不是库容 |
| 7 | 先剥再叠 + 双信号范式 | `BreathBaseResolver` / `VrmLookAtEngine.stripPreviousWrite` | 呼吸/视线/模仿三处验证 | 任何新程序化层（点头、IK、姿态）照抄即可，不用发明第四种写法 |
| 8 | 多 avatar 导演制 + 圆桌 | `stage/`（memory: multi-avatar-feasibility） | 辩论场景在用 | 监听者表现（听别人说话时的 backchannel）有现成舞台 |

**对旧文档（2026-10-04）的两处修正**（旧文档 §1.1/§2.3 写「引擎单动画槽、无遮罩无混合、无 crossfade」）：
- 双槽 crossfade 与上半身 mask 已随 locomotion P2 落地（base+overlay 两槽，one-shot→idle 淡入 0.25s）；
- 运行时变速已有（`setPlaybackSpeed` 0~3）。
- 仍缺的：镜像、幅度缩放、任意骨骼集 mask 的公开 API、真 additive（delta）混合——见 §4。

---

## 3. 任意表情：从 13 个命名状态到连续面孔空间

### 3.1 差距分析（四点）

现状：`EmotionBlender`（face/EmotionBlender.kt:26）13 个 canonical 情绪，每个是 ARKit morph 组合（如 sad = sad×0.3 + browInnerUp×0.55 + mouthFrown×0.35×2…），ease 从当前显示值起步，hold 后 3s 自动回 neutral。

1. **单状态机**：一次只有一个激活情绪，后 apply 顶掉前面（旧文档 §2.6 明确记账）。做不出「嘴角在笑但眉头担忧」的混合态——而这恰是人类表情的常态（苦笑、强颜欢笑、惊喜=-surprised+happy）。
2. **无轨迹**：只有进场 ease + 定时回零。人类表情是连续演化的——酝酿（~500ms 潜伏）→ 展开→ 峰值→ 衰退，且相邻表情之间有 co-articulation（过渡路径本身携带信息，如「笑意慢慢收起来变成尴尬」）。
3. **无惯性**：3s 自动归零意味着角色没有情绪记忆。真人被骂了一句，接下来两分钟的神态都带着那句话。
4. **非情绪类面部行为缺位**：眉闪（话轮交接信号）、思考时皱眉自语、被光晃到眯眼、说话中途的吞咽——这些是「面部手势」，不是情绪状态，当前体系没有承载它们的通道。

### 3.2 方案：面孔空间（Face Space）

**数学**：把 morph 基张成的空间当作面孔空间 V（SK_Sun 上 dim≈70）。每个 canonical 情绪定义是 V 上的一个向量 dᵢ。现有的一切不变，只加三件事：

1. **关键点坐标表**：给 13 个 canonical 各标 (valence, arousal) 坐标（效价-唤醒二维环状模型，Russell 1980 传统）。建议初值（真机调）：

   | 情绪 | valence | arousal | | 情绪 | valence | arousal |
   |---|---|---|---|---|---|---|
   | happy | +0.8 | +0.5 | | sad | -0.6 | -0.4 |
   | angry | -0.6 | +0.7 | | surprised | +0.1 | +0.8 |
   | relaxed | +0.5 | -0.5 | | sleepy | +0.1 | -0.8 |
   | smug | +0.4 | +0.2 | | shy | +0.1 | +0.3 |
   | worried | -0.5 | -0.1 | | confused | -0.2 | +0.2 |
   | think | 0 | -0.2 | | determined | +0.2 | +0.6 |

2. **混合律**：`EmotionBlender.applySet(Set<(def, weight)>)`——多个 keypoint 的 morph 向量做凸组合（权重归一后逐 morph 累加，VrmExpressionManager 本来就 clamp，安全）。「惊喜」= surprised×0.6 + happy×0.4，一句话就出来了。**这是消灭「单状态机」的最小改动**。
3. **轨迹生成器**：attack-sustain-decay 三段包络替代单一 ease；相邻 cue 之间不瞬跳目标，而是沿 VA 空间走短弧（插值两帧目标 morph 向量即可，不需要真样条）。纯函数 `AffectTrajectory`，JVM 单测锁形状（峰位/单调段/时长界）。

**输入协议**：两种形态二选一或并存（§7 详述可靠性边界）：
- 并发 emo 标签：`<emo:surprised:0.6><emo:happy:0.4>`（extractor 已支持多标签，卡点只在 EmotionBlender 单状态机）；
- 或 VA 标签：`<emo:va:0.7:0.6:0.8>`（valence:arousal:intensity），运行时找最近 keypoint 的邻域插值——词表更小，对 LLM 更稳定，且天然支持「强度渐变」叙事。

### 3.3 微表情与面部手势层

- **不对称噪声层**：人类面部天然左右不对称。在 FaceDriver 合并后对眉/颊/嘴 morph 加 ±5~10% 的低频不对称扰动（每侧独立 1/f 类噪声，换模型重播种）。成本≈零，去「对称脸塑料感」效果显著。
- **眉闪（eyebrow flash）**：browInnerUp 80~150ms 脉冲，挂话轮交接（用户说完→虚拟人接话前 300ms）——最强的「我听到你了」信号之一。
- **眯眼/眨眼升级**：旧文档 §2.5 的非对称曲线（闭 0.08s 开 0.12s）+ 左右分离 + `triggerBlink` 事件 API + saccade 联动，原样并入本文 P0。

### 3.4 情绪惯性：mood 慢通道

一个标量对（v_mood, a_mood），对每句表情做一阶低通积分（时间常数分钟级），**不参与 LLM 决策，只做表现偏置**：
- EmotionBlender 的 neutral 基线偏移（心情差时 rest 脸也垮一点：mouthFrown 基线 +0.08 之类）；
- idle 动作选择权重（memory: idle 行为调度器落地时直接消费）；
- 呼吸频率/幅度调制（`VrmBreathEngine.setEmotion`，兴奋=快而浅）；
- 未来 TTS 音色/语速选择的输入。

**边界声明**：`agent-feasibility.md` §3.4 明确决策过「不做 mood/energy/affection 心理状态机」——那是指**不把心理状态喂给 LLM 决策**（避免不可控的隐式人格漂移）。本提案的 mood 是纯表现层慢通道，LLM 永远看不到它，两者不冲突。这个边界要在实现时写进代码注释，防止后人误接。

### 3.5 上限与模型侧建议

- 表情自由度的硬上限=morph 基。SK_Sun（52 ARKit + 18 预设）是优秀基线；**4705（VRM 0.x）等旧模型基更小**——所有混合/直驱都要走已有的缺 morph 降级链（EmotionBlender 已内建逐条降级，直接复用）。
- 模型验收清单（选型/入库时跑）：ARKit 52 是否齐、blinkLeft/blinkRight 是否分离、是否有多余 custom 表情槽（进 directExpressions 协议即白赚）。
- 瞳孔缩放（强情绪信号）VRM 生态基本没有 morph 支持，不做。

---

## 4. 任意动作：四级火箭

### 4.0 现状约束清单

- act 每轮限 1 个（第二个 BUSY，AvatarSession dispatchCues）；单 overlay 槽 last-cue-wins；
- 无镜像、无幅度缩放（全仓无 VRMA mirror；幅度参数只有口型/呼吸有）；变速已有；
- 无词级时间戳（只有 64ms 音素帧 + 句级播放事件）；
- 检索面：LLM 只见 22 白名单 + 12 动态候选（`ActionCandidateSelector`），库 346；
- 引擎无真 additive（delta）混合（旧文档 §2.3 阶段 C，仍不建议做——Mixamo 转换件无标定 delta）。

### 4.1 第一级：参数化重放（组合爆炸，性价比之王）

给 `playVrmaAnimation` 加修饰维度：

| 维度 | 实现 | 难度 |
|---|---|---|
| 镜像 | 骨名左右互换表（Mixamo 命名规整，可自动生成，含 32 手指骨）+ 四元数 Y 轴翻转（左手挥=右手挥镜像） | 中 |
| 幅度 | 骨骼旋转角缩放：`pose' = slerp(rest, pose, k)`，k∈0.5~1.5——比逐四元数缩放稳 | 低 |
| 速度 | ✅ 已有 | — |
| 起始相位裁剪 | 跳过前 N% 帧（很多 Mixamo clip 前 0.5s 是预备姿势） | 低 |

协议形态：`<act:wave:fast:mirror>`（InlineTagExtractor 的标签语法已支持多段冒号）。**组合账**：346 clip × (镜像×2 × 幅度 3 档 × 速度 3 档) ≈ **6 万+ 可辨动作实例**——「任意动作」的工程近似第一仗就在这，全部端侧确定性代码，零新资产。

### 4.2 第二级：行为组合 BML-lite（多频道并发）

关键架构事实（§2 表 4/7）：**运行时本来就是多频道并发的**——morph（FaceDriver）、骨骼 clip（base/overlay）、程序化头颈（LookAtEngine 通道）、呼吸（BreathEngine）、走位（LocomotionEngine）五路写层互不抢占。缺的只是**一个协调者和一份组合语法**。

设计（对齐虚拟人领域的 BML/SAIB 思路与 Cassell 的 BEAT 传统，但极简化）：

```
cue = { channel: face | head | gaze | gesture | breath
        unit:    白名单名（face=13情绪+原生morph；head=nod/shake/tilt；gesture=346库）
        intensity, speed, mirror
        onset:   @sentence_start | @accent_K（句内第K个重音） | @turn_end …
        dur/hold }
```

- **BehaviorRealizer**（orchestrator 新增，纯 JVM 可单测）：把 cue 流排程到五路频道；冲突规则=现有让位仲裁的延伸（口型>情绪嘴部、mimic>情绪、走路>手势 mask）。
- 每轮 act 上限从 1 放宽为「gesture≤1 + head/gaze/face 不限」（它们不同频道，天然无冲突）。
- **与 inline-tag-reliability 的张力**：自由 JSON 会回退可靠性（该文档的全部教训）。约束：unit 走白名单、onset 走有限枚举、语法仍用行内标签或工具调用（tool call 通道输出结构化 JSON 更稳，且工具循环基建已全）。**词表锁 + i18n 双份 + 可靠性回归三件套是硬前置**。

### 4.3 第三级：程序化（无限参数化的小动作）

- **additive 点头/摇头/歪头**：倾听反馈（旧文档 §2.6）同款——LookAtEngine 加 pitch/yaw/roll 振荡通道，先剥再叠。用于：重音微点头（§5）、倾听反馈、话轮信号。**不要用 VRMA 点头动画**（顶掉 base，旧文档已有此判断）。
- **IK 指向/摊手**：手臂两骨 aim 数学可大量复用 GazeMath（`quatFromTo` 一族）；「指向锚点/指向镜头/掌心向上」三五个原语就够覆盖交流手势的大头。Mixamo 命名的肩-肘-腕链规整，bind 一次即可。
- **姿态转移**：重心左右换脚——库里已有 `Weight Shift Idle` 等素材，随 idle 行为调度器（旧 #2）轮换即可，不必程序化。
- 吞咽/微小耸肩等生理 micro act：低优先，凑趣项。

### 4.4 第四级：生成式（当内容工厂，不当运行时依赖）

学术界现状（名称级索引）：文本到动作（MDM / MLD / MotionGPT / T2M-GPT）、语音驱动手势（BEAT / ZeroEGGS / DiffSHEG / EMOTE）、语音驱动面部（NVIDIA Audio2Face 一类）。工程判断：

1. **端侧运行时不可行**：扩散/自回归动作模型在 Adreno 上无戏；延迟与能耗不可接受。
2. **正确形态=离线内容工厂**：`prompt → 云端 API 或本地 ComfyUI/Blender 管线 → BVH/FBX → 既有 Mixamo→VRMA 转换链 → assets/animations/ 自动编目`。仓库 `tools/blender/` 已有 Blender 工作流先例，管线形态成立。生成结果是**资产**，走既有全链路（编目、白名单、LLM 目录、crossfade），风险隔离。
3. **版权红线**：主流动作数据集（HumanML3D、AMASS 系）多为研究许可，商用生成的合规要逐模型查；生成服务条款同理。立项前先过法务关。
4. **恐怖谷风险**：劣质的「生成但不协调」动作观感**差于**优质的固定目录动作（§1.1 原则）。生成式必须过 A/B 盲测才准上线。
5. 运行时流式（语音→手势实时生成）列为远期观察项，等云端低延迟动作 API 出现再评估。

### 4.5 明确不做清单

- active ragdoll / 全身物理控制器（研究级，Adreno 无预算，且与 VRMA 资产体系冲突）；
- 端侧训练任何动作/表情模型；
- 神经渲染面部（换掉 VRM 管线，等于换项目）；
- 把云端动作生成做成运行时阻塞依赖（无网即残废，违背纯客户端卖点）。

---

## 5. 时序耦合：最便宜的真人感（当前最大的空白）

这一节全部是低成本高感知项，多数只动 orchestrator：

| # | 项 | 信号源（都已有） | 实现 | 预期感知 |
|---|---|---|---|---|
| 1 | **重音驱动头部 beat** | 64ms PhonemeFrame 的 volume（`LipSyncProcessor.kt:10-14`） | 句内取 top-K 能量峰→峰点 ±120ms 内 additive 微点头（2~3° pitch）+ browInnerUp 脉冲 | 「说话在节奏上」 |
| 2 | **句首换气** | SentenceStarted 播放事件（AudioTrackPlaybackQueue 回调） | 呼吸相位快进至吸气起点，句尾自然呼气 | 「气息跟着句子走」 |
| 3 | **表情先行** | emo cue 在句队列时即已知 | 句排队期低强度预显（0.3×）+ 开播全强度（挂句机制保留，防 3s 归零坑复发） | 人类表情先于言语 200~500ms |
| 4 | **眨眼-认知耦合** | 重音峰/话轮交接事件 | 高强调帧与话轮交接 ±500ms 抑制自动眨眼（话轮持有者眨眼减少是稳定的人类数据） | 去随机感 |
| 5 | **声音→脸自洽** | 同一 volume 包络 | 表情强度 ×(0.8~1.2) 随句能量浮动 | 频道一致性原则的免费实现 |
| 6 | **思考视线偏移** | phase==THINKING | 视线目标偏移镜头 20~30°（上/侧随机），SPEAKING 回镜头——`setLookAtTarget` 零 corelib 改动 | 强人类信号，半天工作量 |

**已知缺口**：无词级时间戳。短期用 volume 包络近似重音（对中文 TTS 的效果要真机验证）；中期看 provider 能力（部分 TTS 有 word timestamps）或音素-字对齐。**这是全篇唯一需要新数据的项**，其余全部复用现有信号。

---

## 6. 交互与人格

### 6.1 倾听反馈与 backchannel（旧 #6 升级版）

- 信号桥：`FaceDriver.setUserListening(Boolean)`（VAD `onHearingChanged` 已回调到 app，只差这最后一跳）；
- 表现三件套：歪头 roll（LookAtEngine 加 roll 通道）+ 周期性半点头 + 倾听挑眉（brow 独立叠加项，走口型 max 同款机制，不抢情绪通道）；
- **非语音 backchannel 优先**：用户说话期间每 2~5s 轻点头/挑眉。**不要**让虚拟人发「嗯」「对」语音——系统 ASR 无 AEC（memory: system-asr-quirks），自己出声会打断识别；点头+表情是零声学冲突的 backchannel。语音 backchannel 只在按住说话（无回采）模式下可开。

### 6.2 人格风格参数（behavioral fingerprint）

`CharacterCard.personality` 目前是纯 prompt 文案。数值化路径：
- 每卡一组**风格旋钮**：手势幅度系数、手势频率、眨眼率、呼吸变异性、idle 能量（小动作频率）、默认 micro-表情不对称度；
- 大五人格到行为维度的映射在心理学文献有方向性共识（外向性↑手势率/幅度、神经质↑微动频率等），**具体数值一律本项目真机标定**，文献只给方向；
- 18 张预置卡各配一个行为指纹，同一段话不同角色不同神态——这是「多角色同台」（辩论/团播）场景的直接增益。
- 与 mood 慢通道（§3.4）正交组合：人格=常数先验，mood=慢变状态。

### 6.3 主动性

`agent-feasibility.md` P1（stay_silent/三重打扰门/定时搭话）设计完备未实现，是 L4 层的最大单项，此处不重复，仅指出：**主动性落地后，「沉默时做什么」变得和「说话时做什么」同等重要**——恰好消费 idle 行为调度器和倾听反馈的同一批基建。

---

## 7. 架构对接与协议演进纪律

| 改动 | 层 | 落点 |
|---|---|---|
| 表情混合/轨迹/mood | orchestrator | `face/EmotionBlender` +新 `face/AffectTrajectory`、`face/MoodIntegrator`（纯函数单测） |
| 微表情/眉闪/眨眼升级 | orchestrator | `face/MicroMotionEngine`、FaceDriver 合并段 |
| 重音 beat/句首换气/表情先行 | orchestrator | FaceDriver tick + SpeechPipeline/PlaybackQueue 事件钩子 |
| 思考视线/倾听 roll | orchestrator→corelib | GazeMode 切换零 corelib；roll 通道=VrmLookAtEngine 新增（先剥再叠照抄） |
| act 参数化（镜像/幅度） | corelib | `VrmaAnimationEngine`（镜像骨名表抽纯函数单测；幅度=slerp(rest,pose,k)） |
| BML-lite | orchestrator | 新 `behavior/`（Realizer 纯 JVM）+ `tag/TagCapabilityRegistry` 扩 channel |
| 检索面扩大 | app/orchestrator | ActionCandidateSelector 候选扩容或语义检索 |
| IK 原语 | corelib | 新 `VrmArmAimEngine`（GazeMath 复用） |

协议纪律（全部来自 inline-tag-reliability 的既成教训，不可让步）：
1. 新 unit 一律白名单+词表锁+未知拒绝上报；
2. PromptTexts Zh/En 同步改+i18n 测试；
3. 结构复杂 cue（BML-lite）优先走 tool call 通道（结构化输出可靠性 > 行内标签），行内只保留高频简单形态；
4. 每次协议变更跑既有可靠性回归（白名单双向校验、拒绝降级链）。

---

## 8. 路线图

> 原则重申：P0/P1 全部是耦合与一致性（§1.1 判断），自由度的大头在 P1 参数化，生成式只在 P3。每期验收除专项判据外，通用回归=旧文档 §4 清单（弹簧骨骼/眼球剔除/静置漂移/打断混合）。

### P0：表情动力学 + 韵律对齐 v1（~3 天）

| 项 | 内容 | 验收 |
|---|---|---|
| 眨眼升级（旧 P1 尾巴） | 非对称曲线+左右分离+triggerBlink+saccade 联动 | `blink_trigger` 命令；closeup 录屏曲线 |
| 微跳视层（旧 P1 尾巴） | 0.4~1.2s 高频微抖叠加 | 旧文档 §2.4 判据（epsilon 放行） |
| 表情组合 v1 | `applySet` 多 keypoint 凸组合 + 13 情绪 VA 坐标表 | 「惊喜」等 5 个混合态盲测可辨 |
| 思考视线偏移 | THINKING→偏移镜头，SPEAKING→回 | `ai_cmd` 模拟 phase 切换录屏 |
| 句首换气 | SentenceStarted→呼吸相位重置 | 宏观特写观察气息-句对齐 |
| 重音点头+眉强调 v1 | volume top-K 峰→additive 微点头 | 录屏逐帧核对峰-点头相位差 <120ms |

### P1：mood + 组合语法 + 倾听（~1.5 周）

| 项 | 内容 | 验收 |
|---|---|---|
| mood 慢通道 | 一阶积分+四处消费（基线/idle/呼吸/未来TTS） | 连续对话 5min 后 rest 脸可感知偏移 |
| 倾听反馈+非语音 backchannel | 信号桥+roll 通道+点头/挑眉 | 自由说话模式真人开口观察；`listen on/off` A/B |
| idle 行为调度器（旧 #2） | 泊松触发+白名单策展+phase 门控 | 静置 3min 动作自然、对话中不顶手势 |
| act 参数化 | mirror+幅度+起始裁剪，协议修饰符 | 同一 clip 4 形态盲测可辨；骨名表单测全绿 |
| BML-lite v1 | gesture+head+face 同句并发，onset 枚举 | 「重音上摊手+挑眉」类复合行为真机 |
| 声音→脸自洽 | volume 调制表情强度 | A/B 对比「平脸念稿」vs「能量跟随」 |

### P2：人格化 + 检索 + IK（~2 周）

| 项 | 内容 | 验收 |
|---|---|---|
| 人格风格旋钮 | CharacterCard→行为参数，预置卡配指纹 | 同一句台词两张卡神态可辨 |
| 检索面扩大 | 346 库全量语义触达（候选扩容或嵌入检索，记忆系统 bge-m3 可复用） | LLM 长尾动作请求命中率 |
| IK 原语 | 指向锚点/镜头/摊手三原语 | 走位+指路复合场景 |
| VRMA expressionTracks 驱动 | 消灭蒸发的表情轨道 | 带表情轨道素材验证 |
| emotion→TTS 联动 | mood/情绪映射音色与语速（按 provider 能力） | 愤怒台词声音+脸一致 |
| CameraDirector 规则档 | 旧任务 7.2 | phase→镜位映射 |

### P3：生成式内容工厂（探索期，先过法务与盲测两道门）

云端/本地管线产 VRMA 入库；词级时间戳对接（依赖 provider）；audio-driven gesture 调研。

---

## 9. 怎么知道「更像了」：度量

1. **A/B 并排盲测**（主力）：同一话题脚本，新旧两版本各录屏，混杂顺序给 3~5 人选「哪个更像真人」；每期过 60% 选择线才算数。
2. **清单法**（自检）：固定 8~10 条陈述（「TA 在听我说话」「TA 的情绪和说的话一致」「TA 有自己的状态」），Likert 五级，A/B 各打。
3. **程序化指标**（免费、可进 CI 式回归）：
   - 对齐误差：重音峰-头部 beat 相位差分布（ms）；
   - 频道一致性：脸能量（morph 权重变化率）与声能量（volume 包络）的互相关峰值——**「一致性>自由度」的直接量化**；
   - 动作多样性：会话内 clip/修饰形态覆盖熵；
   - 表情转移密度：每分钟 morph 向量显著转向次数。
4. 主观量表用 Godspeed 系列（虚拟人研究标准量表，Bartneck 2009）做里程碑级测量，不做每期回归。

---

## 10. 风险与开放问题

| 风险 | 说明 | 缓解 |
|---|---|---|
| 恐怖谷反噬 | 自由度上去但协调没跟上，比固定目录更假 | §1.1 原则：耦合先行；生成式强制盲测门 |
| 协议膨胀回退可靠性 | BML-lite/修饰符加大 LLM 出错面 | §7 纪律四条；tool call 通道承载复杂结构 |
| 跨模型 morph 差异 | 4705（0.x）基小，混合表情降级后观感打折 | 降级链复用；模型验收清单（§3.5） |
| 性能预算 | 程序化层叠多了 CPU 涨；真灯 -10fps 的前车之鉴 | 每层 `ai_cmd state` fps 前后对比；FaceDriver 合并段 O(morph 数) 控制 |
| LLM token 成本 | 协议块变大 | 候选制（动态 top-K）已是既成模式，沿用 |
| 评估主观性 | 盲测样本小 | 清单法+程序化指标交叉 |
| 开放问题 | 词级时间戳依赖 provider；中文 volume 包络作重音代理的有效性；混合表情在卡通脸（MToon）上的可辨性 | P0 真机先验证再铺开 |

---

## 11. 参考脉络（名称级索引，落地前不引依赖）

- 恐怖谷：Mori（1970，uncanny valley）。
- 情绪结构：Russell 效价-唤醒环状模型（1980）；Mehrabian PAD；Ekman FACS/Action Units；分层情感模型 ALMA（Gebhard 2005，情绪/心境/人格三层——§3.4 mood 通道与 §6.2 人格旋钮即其表现层子集）。
- 非语言行为生成：Cassell 等的 BEAT（2001-2004，文本→节拍手势规划——§4.2 BML-lite 与 §5 重音 beat 的思想源头）；SAIB/BML 行为标记语言；SmartBody（USC ICT，行为实现器参照）；Gratch 系列 rapport agent（倾听/rapport——§6.1 依据）。
- 视线与认知：思考时视线偏移、话轮与注视方向的关系（对话分析/心理语言学文献共识，§5/§6 的依据）。
- 动画技术：Motion Graphs（Kovar 2002）；Motion Matching（Clavet，GDC 2016）——P2 检索面扩大的技术选项。
- 生成式动作：MDM（Tevet 2022）、MLD、MotionGPT、T2M-GPT；语音驱动手势 BEAT（Liu 2022）、ZeroEGGS、DiffSHEG、EMOTE；语音驱动面部 NVIDIA Audio2Face。仅作 P3 素材，本仓库不引实现。
- 评估：Godspeed 问卷（Bartneck 2009）。

---

*本文档 2026-10-23 由开放命题探讨生成（project-guide + 全仓代码锚点调研 + avatar-realism-feasibility 交叉核对）。与代码不符时以代码为准。*
