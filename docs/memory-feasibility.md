# 长期记忆可行性分析 + 落地方案(自动提炼式记忆)

> **实现状态(2026-10-13):M1+M2 已全部实现,单测 +60 全量 832 全绿(app181/corelib114/adapter108/orchestrator429 debug 变体)。**
> 与本文的对照:§4 全部按定稿落地——存储(MemoryStore+Room `agent_memory.db`+待提炼窗口持久化)/检索(MemoryRetriever,公式+常驻槽+关键词降级)/提炼(MemoryExtractor+Parser+MemoryCoordinator 攒批触发+静默门+快照语义)/三工具(remember/recall/forget)/注入缝(末尾 user 槽 viewLine→memoryLine→skillLine)/运行时开关(AvatarSession.setMemoryEnabled/setMemoryAutoExtract)/MemoryTexts 双语/设置页两开关+隐私文案/管理面板(MemoryPanel)/ai_cmd 五命令(memory_list/memory_stats/memory_forget/memory_clear/memory_config)。
> 差异与补充:①AvatarSession 构造缝多一个 `embeddingConfig: (() -> EmbeddingConfig?)?` lambda(配置每次现取,换 Key 不重建会话,AIAvatarSdk.embeddingConfigProvider 转发);②MemoryParser 返回 null(垃圾输出,保留窗口)与空列表(合法空 ops,成功清窗)两种语义,比 §4.3 草案更细;③AIAvatarSdk 公开 `memoryStore` 只读属性(管理面板/调试命令在会话未配置时也可查删)。
> **真机验收(2026-10-13):§10 curl 矩阵 + §7 九场景全部通过**(2c3769db,硅基 DeepSeek-V4-Flash 主链 + 火山 doubao 对照,结果详见 `agent-provider-matrix.md`「记忆提炼」节与 `ai-layer-handoff.md` 第二节)。要点:三家主路由提炼 JSON 单行合规 + 双事实命中全 5/5(**OpenRouter free 仅 1/5,免费路由按预案别开自动提炼**);九场景含 force-stop 持久化、跨上下文纯向量检索、`memory_config 1 0` 运行时热更新、纯通道 B 写入(src=auto 分类正确)、0.92 去重同 id 原地升级(imp 5→8)、无 Key 关键词降级、开关 tools 13↔10、30 轮寒暄恰 6 次提炼仅 +1 条、管理面板单条删除+清空恢复「不认识」。**两个模型侧发现(非代码缺陷)**:①DeepSeek 调 remember 常不传可选 category(入库全「其他」),doubao 传对——分类质量随模型浮动;②弱祈使「记一下」偶发「宣布记住零调用」(DeepSeek 已知形态),自动提炼数秒内兜住不丢事实。剩 M3(整理/独立提炼模型/开场白个性化)按需另立。

> 生成时间:2026-10-11。对应代码状态:P0 工具调用已实现并真机验收通过(`docs/agent-feasibility.md`)。
> 外部调研对象:①**claude-code 源码**(`/home/neethu/projects/claude-code`,file:line 锚点已核实)——其 auto-memory 是「LLM 自动提炼对话 → 存本地 → 后续会话召回」的成熟工业实现,与本需求形态一致;②**airi** 的 telegram 记忆闭环(向量检索路线,已在 `agent-feasibility.md` §1.4 盘点)。
> 与既有文档的关系:**本文接管 `agent-feasibility.md` §6(P2 长期记忆草图)并扩展**——§6.1 数据模型 / §6.2 EmbeddingAdapter / §6.4 检索公式三处定案原样保留;新增的核心是 **claude-code 式的「回合结束自动提炼」通道**(用户本需求的主通道)、常驻身份槽、时间衰减豁免、互斥规则与完整的工程落点。
>
> **结论先行:可行,且基础设施几乎全部现成。** P0 工具循环给了工具写入的完整通路;Room 持久化、钉住协议、末尾 user 前缀槽、双语文案目录、ai_cmd 惯例全部就位。真正要新写的只有 `memory/` 包(存储+提炼+检索,~750 行含测试)+ EmbeddingAdapter(~80 行)+ 设置/管理面板(~250 行)。提炼走**跟随主模型的独立请求**(纯 JSON 文本输出,不碰工具调用,规避 Qwen3.8 关思考工具调用不可靠的矩阵结论),且**攒批触发而非逐轮提炼**——累计轮次或字符数达到阈值(均可在 `AvatarSession.Options` 配置)才发一次提炼请求,后台执行零语音流水影响。

---

## 0. 结论速览

| 问题 | 答案 |
|---|---|
| 需求形态 | 大模型把每轮对话提炼成「用户身份 / 偏好 / 发生的事」存本地,之后随时召回——即 **claude-code auto-memory 的聊天版** |
| 参照系怎么选 | claude-code = 提炼式记忆(写什么由 LLM 判断,存摘要不存原文);airi = 原文入库+向量检索(不提炼,靠检索时当场"想起")。**本方案主通道取 claude-code 的提炼式,检索公式取 airi 的混合打分**——提炼式对聊天虚拟人更优:记忆条数可控(几十条 vs 几万条原文)、注入 token 恒定、隐私面小(不存全量对话副本) |
| 写入双通道 | 通道 A = 主回合 `remember` 工具(用户说「记住…」时模型主动写,**即时生效**);通道 B = **攒批自动提炼**(本需求主通道)。窗口内 A 写过**不再整窗跳过**(攒批后跳过会连坐丢掉其余轮次的信号)——重叠靠「A 的结果即时进记忆清单 + 机械去重」兜住(§4.3) |
| 提炼时机 | **攒批触发,不逐轮**(聊天短句多,逐轮提炼既费 token 又逼模型硬挤垃圾):每个完整回合收尾后把本回合追加进「待提炼窗口」,**累计轮次 ≥ N 或累计字符数 ≥ M** 才发一次提炼请求(两阈值或关系,`Options.memoryExtractEveryTurns=5` / `memoryExtractMinChars=600` 可配)。输入=窗口内多回合 user+assistant 干净文本;窗口持久化,force-stop 不丢、跨会话连续计数;被打断/失败/`speak()` 一律不进窗口 |
| 提炼用什么模型 | **跟随主模型**(复用同一 LlmAdapter + 当前 LlmConfig,零新配置面):提炼是普通文本补全 + JSON 输出,不依赖工具调用,Qwen3.8 关思考照样可靠;成本 ~3K input token/请求、默认 5 回合才发一次,清单大头被摊薄,单位回合 <¥0.002。P2 可加「独立提炼模型」设置(便宜小模型降本,双模型文档 §Q2 的价格表直接适用) |
| 存什么 | Room 独立库 `agent_memory.db`(§6.1 schema 保留,+source/updatedAt 字段):一条记忆=一句第三人称原子事实 + 分类 + 重要度 + 嵌入向量。**跨上下文全局共享**(new_context 换历史不换记忆) |
| 怎么召回 | 每轮请求前按本轮用户输入检索:向量主路(硅基流动 bge-m3,复用 ASR 的「硅基流动 Key」缝)+ 关键词降级(2-gram LIKE);打分公式照 airi(`1.2×cosine + 0.2×时间新鲜度`),**身份/关系类豁免时间衰减**;另有**常驻槽**(重要度≥8 的身份/关系事实恒注入) |
| 注入在哪 | **末尾 user 前缀槽**(视角行/技能行同缝,AvatarSession.kt:976-989)——逐轮变化、不进 store、不碰钉住协议块,A.1 第 34 条与前缀缓存全不破 |
| 隐私与开关 | 总开关+自动提炼子开关(均默认开,**刻意不进 ChatConfig**——observeCamera 先例,切开关不重建会话不轮换上下文);提炼把对话文本发给主模型服务商(与主链路同隐私面,无新增);嵌入发送到硅基流动(设置页文案明示);管理面板列表/搜索/删除/清空 |
| 工作量 | M1 存储与检索注入 ≈4-5 天(单测 +30);M2 工具化+管理面板 ≈2-3 天;合计 ≈1.5 周(与 §8 路线图 P2 估时一致) |

---

## 1. 外部参照:两套记忆架构的实勘

### 1.1 claude-code auto-memory(提炼式,本方案主参照)

核心架构 = **markdown 文件 + system prompt 行为指令 + 回合结束后台提炼 agent + 无向量检索**。没有独立的 "Memory" 工具,写入复用普通文件 IO。

- **存储格式**:分项目目录(`~/.claude/projects/<slug>/memory/`,`src/memdir/paths.ts:223`)下,`MEMORY.md` 索引(非记忆本身,单行条目,硬上限 200 行/25KB,`memdir.ts:34-38`)+ 每条记忆一个文件,frontmatter 为 `name/description/type` 四类封闭分类(user/feedback/project/reference,`memoryTypes.ts:261-271`)。**description 字段就是为召回时零成本筛选设计的**。
- **写入双通道 + 互斥**:
  - 通道 A:system prompt 注入完整记忆指令(`memdir.ts:419` loadMemoryPrompt),主 agent 可随时写文件,写记忆目录免权限确认(`utils/permissions/filesystem.ts:1572`);
  - 通道 B:**每次完整回合结束自动跑**(stop hook,`query/stopHooks.ts:145-152`)——fork 一个共享 prompt cache 的子 agent,带工具白名单(只读全局 + 仅记忆目录可写),分析本轮新消息并直接增删改记忆文件(节奏可配 `tengu_bramble_lintel`:默认 1=每轮,可设每 N 轮——**聊天场景把这个 N 调大正是本方案对它的第一个修正**,见 §2.1);
  - **互斥**:主 agent 本轮写过记忆 → 后台提炼直接跳过并推进游标(`extractMemories.ts:121-148`,"main agent's prompt has full save instructions — when it writes memories, the forked extraction is redundant")。
- **提炼 prompt 的关键约束**(原文锚点 `services/extractMemories/prompts.ts:35-43`):只允许用最近 N 条消息提炼,禁止花回合去验证内容;**预先注入现有记忆清单**(manifest,`extractMemories.ts:396-400`)省得 agent 先 ls;教学提示「先查清单,能更新就更新,不要写重复记忆」。
- **不存什么**(WHAT_NOT_TO_SAVE,`memoryTypes.ts:183-195`):可从当前项目状态推导的内容、单次性寒暄、用户没确认的猜测——这条负面清单比正面清单更有工程价值,本文 §4.3 整段移植改写。
- **召回**:纯索引 + 小模型选择器,**没有向量库**——扫全部 frontmatter 出 manifest(`memoryScan.ts:35`),Sonnet 级小模型按「当前查询 + 描述清单」选 ≤5 条(`findRelevantMemories.ts:18`),JSON schema 约束输出,异步预取不阻塞、会话级预算 60KB(`attachments.ts:269-290`)。另有定期整合(autoDream,`autoDream/consolidationPrompt.ts:15`):合并近似重复、解决矛盾、删陈旧指针。
- **防陈旧**:召回时附「这条记忆已 N 天,是写入当时的观察,不是现状」提示(`memoryAge.ts:33`)。

### 1.2 airi 的记忆(向量检索式,检索公式参照)

主战场 telegram-bot,已闭环的链路:每条消息到达即**原文入库 + 实时 embed**(pgvector HNSW,`models/chat-message.ts:17`);召回 = `score = 1.2×cosine + 0.2×时间新鲜度(30 天线性衰减)`、相似度 >0.5、top-3、每条命中再取前后 5 条邻域(`models/chat-message.ts:87-245`,打分在 :111-112);注入姿态两层——最近 30 条常驻 + agent 调 `read_unread_messages` 时向量检索结果以模板注入(`prompts/action-read-messages.velin.md:12-19`)。

教训(直读源码确认):精心设计的 5 张记忆表(`memory_fragments/memory_tags/memory_episodic/memory_long_term_goals/memory_short_term_ideas`,`db/schema.ts:110-206`)**只有 schema 没有任何读写代码**;prompt 里定义了 `come_up_ideas` 提炼动作但 dispatchAction 没有对应 case——LLM 产出的长期记忆想法直接被丢弃;core-agent 的对话压缩框架(`packages/core-agent/src/messages/compaction.ts:101`)导出了但全仓库无调用方。**设计超前于接线,AIRI 的记忆「看起来有」实际上只有一个原文+向量检索的最小闭环在转。**

### 1.3 可移植结论(本设计的公理)

1. **提炼式主通道**:虚拟人场景每轮都提炼,记忆条数收敛在几十条量级——claude-code 的「后台提炼 + 现有清单防重」架构直接平移;airi 的「写入即向量化原文」路线条数会随使用无限膨胀,注入预算失控,不做主通道。
2. **双通道 + 互斥**:`remember` 工具(A)与自动提炼(B)并存,B 在 A 写过时跳过——claude-code 已验证的分工:显式要求走 A(即时、模型自证),其余走 B(兜底、覆盖用户没意识到该记的)。
3. **检索公式照抄 airi**、**去重靠「清单进提炼 prompt + 机械 cosine 阈值」双层**(claude-code prompt 层 + 本项目 §6.3 已定的 0.92 阈值),**不引入向量库选型**(sqlite-vec/ObjectBox)——几百条记忆全量载入内存线性算 cosine <10ms,向量库是 P3 之后的事。
4. **每打通一步就闭环一步**(AIRI 死表教训):先做存储→检索→注入的最小闭环,再叠提炼,再叠工具与管理面;任何一步的表结构/schema 变更都立刻有读写代码背书。
5. **防陈旧与防遗忘并重**:召回带新鲜度语义(事件类衰减),但身份/关系类**豁免衰减**——airi 的均匀时间衰减公式会把「用户叫小明」30 天后衰减到查无此人的优先级,这是照抄公式时必须修的坑(§4.5)。

---

## 2. 可行性分析:三个关键问题

### 2.1 提炼谁来做、何时触发、输入是什么

**谁**:独立请求,不是主回合的工具轮。理由:①提炼要在回合结束后跑,那时主回合的请求上下文已组装完,再追加一轮工具调用会拉长用户可感知的回合;②提炼输出的消费者是记忆库不是 TTS,独立后台请求天然隔离;③协议上用**纯 JSON 文本输出**(宽松解析,DecisionParser 同款),**刻意不走 tools 通道**——`docs/agent-provider-matrix.md` 实测 Qwen3.8 关思考下工具调用 ~4/10 不可靠,而小模型/关思考模型的普通 JSON 文本输出没有这个坑。

**何时(攒批触发,不逐轮)**:聊天里用户的话大都非常简短(「嗯嗯」「哈哈」「真的吗」),逐轮提炼一是浪费 token——清单是提炼请求的常驻大头,逐轮重发;二是模型在没东西可记时被要求产出,容易讨好式输出垃圾记忆。因此采用**窗口累积 + 双阈值触发**:每个完整回合收尾后,把 (userText, assistantCleanText) 追加进「待提炼窗口」;**累计轮次 ≥ `memoryExtractEveryTurns`(默认 5)或累计字符数 ≥ `memoryExtractMinChars`(默认 600)** 即触发一次提炼,成功后窗口清空。两阈值是「或」关系、都在 `AvatarSession.Options` 上可配(SDK 公开 API;demo 不上设置页——工程向参数给集成方,真机调试用 `ai_cmd memory_config` 运行时调)。两个条件互补:字符阈值让「内容密集的少轮次对话」(用户贴一大段话)也能及时提炼,轮次阈值让「零散寒暄」不烧请求。

- 打断(CancellationException)/失败(TurnFailed)的回合不进窗口——半截对话提炼出来的事实不可信(claude-code 同款语义:只在完整最终回复后提炼);`speak()` 开场白/主动发言同样不进(没有用户输入,无可提炼)。
- **窗口持久化**:待提炼窗口存 `agent_memory.db` 单行表(§4.1),force-stop 不丢、跨会话连续计数——上次会话攒了 3 轮没到阈值,这次接着攒;恢复时若已超阈值,本回合收尾即触发。claude-code 用持久化游标(`lastMemoryMessageUuid`,extractMemories.ts:307)解决的正是同一问题。
- 窗口只增不减的退化情形(连续寒暄永远凑不出值得记的内容):见下面的本地静默门。

**输入**:**窗口内多回合的 user 原文 + assistant 全量干净文本**(cleanBuffer,不含标签),按对话顺序拼接。不需要 claude-code 式的跨回合游标——它的游标是因为提炼挂在 stop hook 层、只能从消息流里切增量;我们在回合收尾点直接拿到全文,追加进窗口即可。多工具轮的中间 assistant 文本(「让我看看」类衔接句)与工具结果不进窗口(工具结果是临时数据,且 observe_camera 的视觉描述不落库已是定案——视觉帧内容同样不进记忆,隐私面与记忆噪音双顾虑)。

**本地静默门(防垃圾信息的结构化兜底)**:触发条件满足但窗口累计 <12 字符(连续「嗯嗯」「哈哈」把轮次阈值凑满的情形)→ **不烧请求,直接清空窗口**——这些轮次已被本地判定为无可提炼内容。≥12 字符则照常请求,**要不要记交给模型判断**,配合提炼 prompt 的反讨好条款(§4.3:「大多数片段只有 0~2 条,输出空 ops 是完全正常且常见的结局」)——阈值触发保证了每次请求的输入都有 5 轮/600 字的上下文量,「确实没东西记」成为模型可以合理得出的结论,而不是被逐轮逼着产出。

### 2.2 存什么、怎么去重、怎么防失控

**一条记忆 = 一句第三人称原子事实**,五类分类(比 claude-code 的 user/feedback/project/reference 更贴陪伴场景,比 airi 死表的 8 字段更收敛):

| category | 含义 | 例 | 时间衰减 |
|---|---|---|---|
| profile | 用户身份 | 「用户叫小明,是 Android 工程师」 | **豁免** |
| relationship | 重要的人/宠物 | 「用户养了只叫煤球的黑猫」 | **豁免** |
| preference | 偏好/口味/习惯 | 「用户不喜欢香菜」「睡前爱听故事」 | 豁免 |
| event | 发生的事/计划 | 「用户下周三要考驾照科目二」 | 正常衰减 |
| commitment | 答应/约定 | 「答应明天提醒用户带伞」 | 正常衰减 |

**去重双层**:①prompt 层——提炼 prompt 预注入现有记忆清单(claude-code manifest 同款),指令「能更新就更新,不要写重复」;②机械层——add 落库前对库内记忆算 cosine,>0.92 视为重复 → 覆盖 content 保 accessCount 不新增(§6.3 已定)。无嵌入配置时机械层退化为「内容逐字相等」判定。

**防失控三件套**:提炼 prompt 硬顶「一次最多 5 条」+ 解析层再 cap;检索注入层 topK=3 + 常驻槽 ≤3(注入 token 恒定,与库大小无关);管理面板显示总数,超过 200 条提示整理(M3 的 consolidator,claude-code autoDream 对应物,见 §5 分期)。

### 2.3 怎么召回、注入的时延与成本

**召回主路**(§6.4 公式原样):query=本轮用户输入 → embed → 与库内向量算 cosine → `score = 1.2×cosine + 0.2×max(0,1−ageDays/30)` → minCosine 0.35 剔除 → top-3。**两处修正**:①recency 项只对 event/commitment 生效(§2.2 衰减表,防「用户叫小明」被时间冲淡);②加常驻槽(profile/relationship 且 importance≥8,按重要度取 ≤3,与检索结果按 id 去重)——claude-code 的 MEMORY.md「索引恒在上下文」扮演的就是这个角色。

**注入位置**:末尾 user 前缀槽(AvatarSession.kt:976-989,`listOfNotNull(viewLine, memoryLine, skillLine)`),块格式照 §6.4:

```
[记忆参考]
- (身份) 用户叫小明,是 Android 工程师
- (偏好) 用户不喜欢香菜
```

- 该槽逐轮变化、只改本轮请求副本、不进 store——前缀缓存、A.1 第 34 条、recentTurnLimit、历史存储四者零影响(与视角行完全同性质)。
- **时延**:空库短路(不发 embed 请求,0ms);非空时一次 bge-m3 embed(~20 token)<300ms,加在 LLM 请求前——TTFT 本身 1-3s,占比可接受;**embed 超时 800ms 即放弃走关键词降级**(关键词是本地 LIKE,0ms)。
- **降级链**:无嵌入配置(没配硅基流动 Key)→ 纯关键词检索(2-gram LIKE + importance/recency 融合排序);embed 失败/超时 → 同上;检索异常 → 整块省略,**永不影响主回合**(与「工具失败永不炸回合」同哲学)。
- **成本**:检索 = 1 次 embed(~20 token,bge-m3 极廉价);提炼 = ~(清单 1.5K + 窗口 1.5K) input + ~150 output token/**每请求**,默认 5 回合才发一次——清单大头被 5 个回合摊薄,单位回合 ≈¥0.0005~0.002,聊 300 回合(60 次提炼)不到 ¥0.5。调大批次更省、调小批次更及时,集成方按产品形态权衡;若配独立小模型(Ling-mini-2.0 ¥0.5/M,双模型文档 §Q2 价格表)可再降一个量级。

---

## 3. 总体设计

### 3.1 数据流

```
send(text)
  ├─ 检索:memoryLineFor(text)            〔空库短路 0ms;embed≤800ms 超时→关键词〕
  │    └─ [记忆参考] 块 ──┐
  ├─ 主 LLM 回合(现状全链,零改动)◄────┘ 挂末尾 user 前缀槽
  │    └─ remember/recall/forget 工具随 ToolRegistry 注册(用户显式「记住…」走这里,即时落库)
  │
  └─ 回合收尾(TurnCompleted,AvatarSession.kt:620-624)
       └─ MemoryCoordinator.onTurnCompleted(userText, assistantCleanText)
            ├─ 开关关/被打断/失败回合 → 不进窗口
            ├─ 追加进待提炼窗口(持久化到 agent_memory.db,force-stop 不丢)
            ├─ 触发判定:窗口轮次≥N 或 字符≥M(Options 可配)→ scope.launch 提炼
            │    ├─ 静默门:窗口<12 字 → 清空窗口,不烧请求
            │    ├─ LLM 提炼请求(复用主 LlmAdapter,JSON ops 输出,宽松解析)
            │    ├─ 机械去重:cosine>0.92 → 覆盖不新增
            │    └─ Room agent_memory.db 落库(embed 失败→embedding=null)+ 成功后清空窗口
            │       〔失败保留窗口下次重试,硬顶 20 轮丢最旧;永不影响对话〕
            └─ 〔fire-and-forget,失败只 Log,永不影响对话〕
```

### 3.2 分层落点(四层架构不变)

| 层 | 动作 | 内容 |
|---|---|---|
| `:avatar-ai-adapter` | 新增 | `embedding/OpenAiCompatibleEmbeddingAdapter.kt` + `EmbeddingAdapter` 接口(§6.2 原样)。**聊天链路零改动** |
| `:avatar-orchestrator` | 新增 `memory/` 包 | MemoryStore(接口+内存 fake)/ MemoryDatabase(Room `agent_memory.db`,照 ConversationDatabase.kt:70 单例惯例)/ RoomMemoryStore / MemoryExtractor / MemoryRetriever / MemoryTools / MemoryCoordinator / i18n/MemoryTexts.kt |
| `:avatar-orchestrator` | 修改 | `AvatarSession`:构造参数 +memoryStore/+embeddingAdapter;Options +memory 开关组;buildRequestMessages 挂 memoryLine;回合收尾触发提炼;init 注册记忆工具 |
| `:avatar-orchestrator` | **零改动** | ChatConfig/钉住协议机制/工具循环/SpeechPipeline/store 接口全不动 |
| `:app` | 新增 | MemoryPanel.kt(管理面板)+ AgentMemoryPrefs 接线(AiChatPrefs +2 开关) |
| `:app` | 修改 | SettingsScreen「AI 配置」区 +2 行;ai_cmd +4 命令 |

### 3.3 明确不做的事

- **不做 markdown 文件制**(claude-code 原样):移动端 Room + 管理面板是同能力的更优形态,文件制只对「用户拿编辑器直接改」有优势;导出 JSON 留作 P3。
- **不引入向量库**(sqlite-vec/ObjectBox/pgvector 平移):几百条 × 1024 维 float 全量内存线性扫描 <10ms;等记忆上千条再做。
- **不搬 airi 的 5 张记忆表**:死表教训(§1.2);episodic/goals/ideas 的表达力等 P3 整理功能立项时按需加列。
- **v1 不做对话历史摘要压缩**(compaction):`recentTurnLimit=40` 裁剪现状够用;摘要进记忆库与「长期记忆」耦合后语义混乱,独立立项。
- **不给模型无检索的删除权**:`forget` 只认精确 id(id 从 recall 结果来),管理面板才是删意场的唯一权威——防止小模型误删。

---

## 4. 详细设计

### 4.1 数据模型(orchestrator `memory/MemoryDatabase.kt`,独立 Room 库 `agent_memory.db`)

§6.1 schema 保留,补 3 字段:

```kotlin
@Entity(tableName = "agent_memories")
data class AgentMemoryEntity(
    @PrimaryKey val id: String,          // uuid
    val content: String,                 // 一句第三人称原子事实,≤120 字(落库前截断)
    val category: String,               // profile/relationship/preference/event/commitment/other
    val importance: Int,                // 1-10,提炼缺省 5,clamp 1..10
    val source: String,                 // "tool"(remember 工具)| "auto"(自动提炼)| "manual"(面板)
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long,           // 检索命中即更新(未来衰减/整理的数据底座)
    val accessCount: Int = 1,
    val embedding: ByteArray?,          // FloatArray→ByteBuffer LE;bge-m3 1024 维;null=未嵌入
)
```

DAO:`upsert/delete(id)/clear/all()/allEmbedded()/search(关键词 LIKE 用 rawQuery 拼 2-gram OR)/count()`。同步风格照 ConversationMessageDao(单线程 IO scope 写入惯例不变)。

**待提炼窗口持久化**(§2.1):同库单行表 `pending_window(id=1, turnsJson TEXT, updatedAt)`,`turnsJson` 为 `[{"u":"用户句","a":"助手句"}]` 数组。Coordinator 启动时载入、每次追加/清空时回写;`memory_clear` 与总开关关闭时一并清空。这是对对话文本的第二份存储——但只存**未提炼的尾部几轮**(≤20 条),提炼完成即删,长期留存仍只有记忆条目本身;换来的是 force-stop 安全与跨会话连续计数。

**MemoryStore 接口**(orchestrator 纯 JVM 可测,Room 实现由 app 注入):

```kotlin
interface MemoryStore {
    suspend fun add(memory: AgentMemory): AgentMemory      // 含去重判定入口,返回实际落库条(新增或覆盖)
    suspend fun updateContent(id: String, content: String)
    suspend fun delete(id: String): Boolean
    suspend fun all(): List<AgentMemory>
    suspend fun count(): Int
    suspend fun clear()
    suspend fun touch(ids: List<String>)                    // 检索命中:accessCount+1/lastAccessedAt
}
```

**跨上下文全局**:记忆不挂 contextId——用户是同一个人,换上下文/换人设卡共享记忆。多用户/多角色隔离是 P3 议题(加 userId 列即可,不动架构)。

### 4.2 EmbeddingAdapter(§6.2 原样,补两句定案)

```kotlin
interface EmbeddingAdapter {
    suspend fun embed(texts: List<String>, config: EmbeddingConfig): List<FloatArray>
}
data class EmbeddingConfig(val baseUrl: String, val apiKey: String, val model: String)
```

- 端点 OpenAI 兼容 `POST {base}/embeddings`;**默认 `BAAI/bge-m3`(1024 维)走硅基流动**,Key 复用 AiChatPrefs 既有「硅基流动 Key(语音识别用)」同一位(该字段语义顺势扩为「语音识别与向量化用」,设置页文案同步)。火山方舟/OpenRouter 不接 embeddings 目录——它们只影响关键词降级路径,可接受。
- 错误带响应预览抛 IOException(A.1 第 11 条);**嵌入失败 ≠ 记忆丢失**:content 照存 embedding=null,检索走关键词。
- 批量:提炼一次最多 5 条 add,单次 embed 调用打包(1 次网络往返)。

### 4.3 自动提炼(MemoryExtractor + MemoryCoordinator,本方案主通道)

**触发**(AvatarSession.kt:620 `store.appendAssistant` 之后、emit TurnCompleted 前后均可,取其后):

```kotlin
memoryCoordinator?.onTurnCompleted(
    userText = text,                    // 本回合用户原文
    assistantText = cleanBuffer.toString(),  // 全量干净文本(无标签)
)
// 内部:窗口追加(持久化)→ 阈值判定;达到阈值才 scope.launch 提炼,fire-and-forget。
// 窗口/阈值的完整语义见 §2.1(双阈值或触发、静默门、持久化、失败保留硬顶 20 轮)。
```

**提炼请求**(复用主 `llm: LlmAdapter`,非流式 collect 全量):

- LlmConfig:复用当前 `llmConfig` 整体(model/baseUrl/key/extraBody——**关思考参数跟着走**,闲聊轮 21s 的教训对提炼同样适用),temperature 覆盖 0.2(提炼要稳)。
- system prompt(双语存 `MemoryTexts.kt`,zh 版草案——en 镜像;**反讨好条款是防「没东西硬挤」的关键,与攒批触发配套**):

```text
你在维护虚拟人关于用户的长期记忆库。阅读下面的对话片段(自上次整理以来的若干轮),
输出要做的记忆变更,只输出一行 JSON,不要输出任何其他内容:
{"ops":[{"action":"add","content":"第三人称原子事实","category":"...","importance":5}]}
category 取值:profile(身份)|relationship(重要的人/宠物)|preference(偏好/习惯)|
event(发生的事/计划)|commitment(答应用户的事)
规则:
- 只记与用户相关的、之后对话用得上的稳定事实:身份、偏好、生活事件与计划、
  约定、用户提到的重要的人和宠物。
- **大多数片段只有 0~2 条值得记;输出空 ops {"ops":[]} 是完全正常且常见的结局,
  不要为了产出而编造或把琐碎内容拔高成记忆。**
- 一条一个原子事实,写具体细节(名字/数字/日期),一次最多 5 条,宁缺毋滥。
- 这些不要记:寒暄与客套、你(虚拟人)自己说的话、用户一次性的情绪表达、
  记忆清单里已有的内容、用户没有明确确认的猜测、与画面/照片相关的临时内容。
- 需要更正或删除已有记忆时:{\"action\":\"update\",\"id\":\"...\",\"content\":\"...\"}
  或 {\"action\":\"delete\",\"id\":\"...\"}(id 见记忆清单;不确定就别删)。
```

- user 消息 = `[现有记忆清单]\n<listForPrompt(60)>\n\n[待提炼对话]\n用户: …\n{虚拟人名}: …\n\n用户: …`(窗口内多回合按对话顺序拼接)。清单行格式 `m_xxxx [偏好] 用户不喜欢香菜`(id 8 位短码,`updatedAt` 倒序,上限 60 条——清单是提炼请求的常驻大头,封顶防成本随库增长)。
- **解析**(MemoryParser,DecisionParser 同款宽松):截首个 `{...}` → kotlinx lenient + ignoreUnknownKeys;逐条:未知 category→other、importance clamp、content ≤120 字、ops ≤5 条;解析失败/空 ops → 静默结束(Log 一行),**窗口保留**下次触发重试。update/delete 未命中 id → 忽略。
- **apply 顺序**:delete → update → add(每条 add 先 embed 再机械去重)。成功后清空窗口;请求失败(网络/HTTP)**保留窗口**下次重试,窗口硬顶 20 轮(超顶丢最旧,防长故障下无限膨胀)。中途协程取消(会话 close)允许停在任意条——记忆行之间无事务依赖,可接受。

**与 remember 工具的重叠**(替代逐轮方案的「互斥」):窗口内某回合的 remember 写入**不再**触发「跳过提炼」——攒批窗口里整窗跳过会连坐丢掉其余轮次的信号。替代防线是既有的两层去重:remember 落库的事实立即出现在下一次提炼的清单里(prompt 负面清单「记忆清单里已有的内容不要记」),措辞变体的漏网由机械去重(cosine>0.92)兜住。claude-code 的「主 agent 写过→跳过后台提炼」(extractMemories.ts:121)是逐轮提炼的产物,batching 后该语义不再成立。

### 4.4 工具写入(通道 A,注册进既有 ToolRegistry)

init 块(AvatarSession.kt:307-312 同区)在 `memoryStore != null && options.agentToolsEnabled && options.memoryEnabled` 时注册三个工具(提示词进钉住协议块工具区,与既有工具同级,**每条 hint 一行**):

| 工具 | Schema | hint 要点(角色动机,不写 API 文档) |
|---|---|---|
| `remember` | `{content: string(required), category?: enum, importance?: int}` | 「用户明确请你记住/答应做什么事时,把要点记下来,一次一条」 |
| `recall` | `{query: string(required)}` | 「想回忆和用户的过往、确认用户是否提过某事时,先搜记忆再回答,别编」 |
| `forget` | `{id: string(required)}` | 「用户请你忘掉某事时,先用 recall 找到对应记忆的 id 再删」 |

- 结果文案由 MemoryTexts 拼(双语):remember 成功 → 「已记住:{content}」(模型第一人称转告);recall 空结果 → 「没有找到相关记忆」(**正常结果非 isError**,模型自然说「我还不了解这个」);forget 未命中 id → isError 附「先用 recall 查 id」。
- remember 落库走与提炼同一条 add 路径(含 embed+去重),importance 模型给值缺省 8(显式「记住」的事就该排常驻槽候选)。
- `AgentToolCatalog` 不动(它是 app 缝的设备工具工厂);记忆三工具的工厂放 `memory/MemoryTools.kt`(store 注入,orchestrator 自洽)。

### 4.5 检索与注入(MemoryRetriever)

```kotlin
class MemoryRetriever(store, embedder, config) {
    data class Config(
        val topK: Int = 3,
        val pinnedMax: Int = 3,          // 常驻槽上限
        val simWeight: Float = 1.2f,     // airi chat-message.ts:111-112 同款
        val recencyWeight: Float = 0.2f,
        val recencyWindowDays: Float = 30f,
        val minCosine: Float = 0.35f,
        val embedTimeoutMs: Long = 800L, // 超时→关键词降级
    )
    suspend fun lineFor(query: String, texts: MemoryTexts): String?   // null=本轮零注入
}
```

- **流程**:库空 → 立即 null(0 开销);否则 embed(query,带超时)→ 全量内存 cosine(revision 失效缓存,防每查询重复载入)→ 检索槽(topK)+ 常驻槽(profile/relationship ∧ importance≥8,按 importance/updatedAt 取 ≤3,与检索槽按 id 去重)→ 命中即 `touch(ids)` → 拼 `[记忆参考]` 块。embed 超时/无 embedder → 关键词路(2-gram LIKE,`命中 gram 占比 + 0.05×importance` 排序,topK 同)。
- **recency 修正**:recency 项只计入 event/commitment(profile/relationship/preference 恒 0.2 不衰减)——修掉 airi 公式「身份事实 30 天后沉底」的坑,规则按 category 查表,纯函数可测。
- **注入**(AvatarSession.kt:980-982 改一行):

```kotlin
val viewLine = currentViewLine()
val memoryLine = memoryCoordinator?.lineFor(userText)   // suspend,send 协程内已可挂起
val skillLine = skills.activeDirective()
val directivePrefix = listOfNotNull(viewLine, memoryLine, skillLine).joinToString("\n\n")
```

- buildRequestMessages 增加 suspend 语义或改为调用前取好传入(实现取后者,侵入更小:send() 协程内 line 461 前先取 memoryLine)。空块 = 请求与现状**逐字节一致**(既有空注册表 no-op 同款保证,单测锁)。

### 4.6 AvatarSession 侵入点清单(共 6 处)

1. 构造参数:`memoryStore: MemoryStore? = null`、`embeddingAdapter: EmbeddingAdapter? = null`(默认 null=**SDK 集成方默认无记忆**,与 `store` 参数同性质;demo 显式传入 Room 实现);
2. Options:`memoryEnabled: Boolean = true`(配合 memoryStore 非空生效)、`memoryAutoExtract: Boolean = true`、`memoryExtractEveryTurns: Int = 5`、`memoryExtractMinChars: Int = 600`(两阈值=提炼触发节奏的 SDK API,§2.1;turns 钳制 ≥1)、`memoryTopK: Int = 3`、`memoryPinnedMax: Int = 3`;
3. init 块:构造 MemoryCoordinator(持 llm/scope/store/embedder/texts,启动时从库载入待提炼窗口);按 §4.4 条件注册三工具;
4. buildRequestMessages 调用点(AvatarSession.kt:461)前取 memoryLine(§4.5);
5. 回合收尾(AvatarSession.kt:620 后):`memoryCoordinator?.onTurnCompleted(...)`(内部=窗口追加+阈值判定,达到阈值才发提炼请求)——注意放在 try 块内 TurnCompleted 之前,取消/异常路径天然跳过;
6. interrupt():**无记忆专用处理**——被打断的回合走取消路径不进窗口(§2.1),窗口状态由回合收尾路径唯一写入,不新增 interrupt 侵入。

**开关运行时切换**(不重建会话,observeCamera 先例):总开关=三工具 register/unregister + 协调器 enabled flag(revision 重钉自动发生);自动提炼子开关=协调器 flag。**AiChatPrefs +2 键(`ai_agent_memory` 默认 true / `ai_memory_auto_extract` 默认 true),刻意不进 ChatConfig**(AIAvatarSdk.kt:105 data class 等价=身份,agentTools 先例:开关不改变模型能力面,不轮换上下文)。

### 4.7 app 层

- **设置页**:「AI 配置」区 +2 开关行;「长期记忆」文案区一行(双语):「记忆内容保存在本机;提炼记忆会把对话发送给你的大模型服务商,向量化会把记忆文本发送到硅基流动。可在记忆管理中查看和删除。」
- **管理面板**(M2,`MemoryPanel.kt`):总数 + 列表(内容/分类徽标/重要度/来源/时间)+ 搜索 + 单条删除 + 清空(二次确认);入口放设置页「长期记忆」行。HyperOS 禁触摸注入的老问题照惯例靠 ai_cmd 验收。
- **ai_cmd**(docs/ai-debug-intents.md 惯例):`memory_list [query]` / `memory_stats`(总数/分类分布/嵌入覆盖率/**待提炼窗口状态**)/ `memory_forget <id前缀>` / `memory_clear` / `memory_config [turns] [chars]`(无参=查看当前提炼阈值;带参=运行时调整——真机调窗口节奏用,不重启会话)。

### 4.8 多语言

MemoryTexts.kt(zh/en 相邻,照 PromptTexts 惯例):提炼 system prompt、记忆清单标签行、`[记忆参考]` 块头、三工具的 description/hint/结果文案、设置页文案 ≈20 键。**虚拟人名**在提炼 prompt 的 assistant 行前缀取当前卡片名(无卡用「虚拟人」)。

---

## 5. 分期与工作量

| 期 | 内容 | 估时 | 单测 |
|---|---|---|---|
| **M1 存储与检索注入** | MemoryStore+Room 库 / EmbeddingAdapter / MemoryRetriever(公式+常驻槽+降级) / 注入缝 / Options+构造参数 / 设置 2 开关 / ai_cmd 3 命令(list/stats/clear) | 4-5 天 | +30 |
| **M2 自动提炼+工具化+管理** | MemoryCoordinator(窗口累积+持久化+双阈值触发) / MemoryExtractor+Parser / remember/recall/forget 三工具 / MemoryTexts 双语 / 管理面板 / memory_forget+memory_config / 隐私文案 / 提炼提示词真机调优 | 2-3 天 | +22 |
| M3(候选,做前另立) | consolidator 定期整理(合并/矛盾裁决/清单重建,claude-code autoDream 对应) / 独立提炼模型设置(双模型文档 §Q2 小模型表) / 记忆驱动的开场白个性化 / compaction 摘要进记忆 | 按需 | — |

工作量:M1 ≈ adapter 80 行 + orchestrator ~450 行 + app ~150 行;M2 ≈ orchestrator ~300 行 + app ~250 行。与 agent-feasibility §8 路线图 P2 估时(1.5-2 周)一致。

---

## 6. 测试计划(预计 +52 条)

- **MemoryRetriever(~14)**:公式边界(全零向量/同向量=1/新近度 30 天归零/minCosine 剔除/topK 截断)、recency 豁免表(event 衰减、profile 不衰减)、常驻槽入选与上限、两槽 id 去重、关键词 2-gram 切分与排序、embed 超时降级、空库 embedder 零调用、touch 更新。
- **MemoryExtractor/Parser/触发器(~16)**:正常 ops 全链落库、出格 JSON/空 ops/前后杂语、category 未知→other、importance clamp、content 截断、ops>5 截断、update/delete id 未命中、提炼失败不抛且窗口保留、窗口硬顶 20 轮丢最旧;触发判定(轮次阈值满足/字符阈值满足/两条件或关系/未达阈值不发请求/静默门 <12 字清空且不请求/窗口持久化与重启恢复/恢复即超阈值时首个回合收尾触发/空 ops 成功也清空窗口)。
- **MemoryStore/去重(~8)**:CRUD、cosine 0.92 覆盖不新增(保 accessCount)、无嵌入逐字去重、clear、fake DAO 惯例(不起 Robolectric,照 RoomConversationStoreTest)。
- **MemoryTools(~6)**:remember 参数校验/成功置互斥位、recall 空=非 isError、forget 未命中 isError、三 spec 快照锁(hint 防漂移)。
- **AvatarSession 集成(~6)**:空库请求逐字节不变、记忆行注入格式与槽位顺序、TurnCompleted 触发提炼参数正确、interrupt/失败路径不提炼、开关关=零记忆痕迹(tools 计数+请求体)、纯 TTS 会话(llm=null)不建协调器。
- **EmbeddingAdapter MockWebServer(~2)**:正常批量/400 带响应预览。

---

## 7. 真机验收标准

| # | 场景 | 通过标准 |
|---|---|---|
| 1 | 显式:「记住我叫小明,我讨厌香菜,下周三考科目二」 | remember 工具调用(字幕🔍灰字)→ `ai_cmd memory_list` 见 2-3 条分类正确;**force-stop 重启后仍在** |
| 2 | 跨上下文:`new_context` 后问「你知道我叫什么吗?我能吃香菜吗?」 | 两问都能答(常驻槽+检索注入生效;历史为空铁证记忆独立于上下文) |
| 3 | 隐式(走自动提炼):闲聊告知「我下周三要考科目二」→ **继续聊满 5 轮(默认阈值)** 后问「下周三我要干嘛?」 | 能答(event 类检索命中);`ai_cmd memory_config 1 0` 调成逐轮后,告知完下一轮即可答(阈值可调验证);窗口状态可在 memory_stats 查看 |
| 4 | 「我周五要开会,记一下」→「我周五要干嘛?」 | remember 工具调用 → 立即答对(显式路径不等攒批);memory_list source=tool、importance≥8 进常驻槽 |
| 5 | 「忘掉我不喜欢香菜吧」 | recall→forget 链路或模型引导用户去管理面板;库里条目消失 |
| 6 | 降级:不配硅基流动 Key(纯火山/OpenRouter) | 记忆照常写入(embedding=null);关键词检索仍能命中;无崩溃 |
| 7 | 开关:总开关关 | `chat_state` tools 计数 -3、请求无 [记忆参考]、无提炼请求、窗口清空;再开恢复且上下文不轮换 |
| 8 | 压力:连续 30 轮寒暄 | 提炼请求 ≈6 次(30/5)且多数产出空 ops;memory_stats 增长 ≤5 条(反讨好+去重生效);帧率/TTS 零回归;提炼全程后台无卡顿 |
| 9 | 隐私:管理面板 | 列表/搜索/删除/清空全链可用;清空后跨上下文问答恢复「不认识」 |

---

## 8. 风险清单

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| 1 | 小模型 JSON 出格(尤其 OpenRouter 免费路由当主模型时) | 中 | 宽松解析 + 截首个 {...} + 全部字段有默认/钳制;解析失败=本轮不记,零风险;开工前 A 任务(§10)先 curl 验证 |
| 2 | 记忆噪音(没东西硬挤/重复爆发) | 中 | **攒批触发**(每次请求输入足量,空 ops 是模型可合理得出的结论)+ prompt 反讨好条款 + ≤5 条硬顶 + 双层去重 + 12 字静默门;验收标准 8 兜底测量 |
| 3 | 「用户叫小明」被时间衰减埋没 | 中 | recency 按 category 豁免 + 常驻槽双保险(§4.5,airi 公式的已知坑) |
| 4 | 提炼成本随记忆库线性增长(清单越来越大) | 低 | 清单上限 60 条封顶;超限靠 M3 整理;跟随主模型本身便宜 |
| 5 | 隐私观感(对话自动上云提炼) | 中 | 提炼服务商=主对话服务商(隐私面无新增);嵌入独立文案明示;总开关+子开关+管理面板;默认每回合提炼但用户可一键关 |
| 6 | 记忆与事实冲突(用户改口味了) | 低 | 提炼 prompt 允许 update/delete;召回块带分类与来源,模型看到新旧两条时以对话现场为准;M3 整理做矛盾裁决 |
| 7 | 会话 close 时提炼协程被取消,半写状态 | 低 | 记忆行间无事务依赖;下一轮照常提炼;不引入「提炼事务」复杂度 |
| 8 | 嵌入模型切换后向量维度/分布不一致(新旧向量混库) | 低 | v1 模型锁死 bge-m3 写进文档;若开放配置,切换时全量重嵌入(留 P3) |
| 9 | 前缀缓存被 [记忆参考] 打掉 | 无 | 该槽在末尾 user 消息上,本就逐轮变化,钉住协议块零接触(视角行同缝已真机验证) |
| 10 | 攒批导致隐式记忆延迟可见(告知后马上问,还没提炼) | 中 | 显式记忆走 remember 工具即时路径;阈值可调(Options/`ai_cmd memory_config`);窗口持久化保证内容不丢;产品上「聊了几轮才想起来」也符合真人语感 |

---

## 9. 与既有约束的对照(实现时逐一核对)

1. **system 恒单条**(A.1 第 34 条):记忆块走末尾 user 前缀槽,不新增 system 条数;提炼是独立请求,与主回合 system 结构互不相干。
2. **few-shot/动态指令放请求最末**(§7.10 近因效应):记忆块与视角行/技能行同槽,排序 viewLine→memoryLine→skillLine(技能指令仍最靠近生成位置)。
3. **store 历史只存干净文本**:记忆块不进 store;提炼输入取 cleanBuffer(无标签),记忆内容天然无协议泄漏。
4. **recentTurnLimit**:记忆是「system/历史之外的第三种上下文」,不受裁剪、不占 40 条名额。
5. **ChatConfig 等价=会话身份**(AIAvatarSdk.kt:78):记忆开关与 store 均不进 ChatConfig(observeCamera/agentTools 先例),运行时切换走 register/unregister+重钉与协调器 flag。
6. **工具失败永不炸回合**(ToolRegistry.kt:84):三记忆工具全部 ToolResult(isError) 回灌;检索/提炼任何异常只 Log 不上抛。
7. **AI 未就绪路径**:llm=null(纯 TTS 会话)不构造 MemoryCoordinator;memoryStore 注入但 llm 缺失 = 工具注册(读写本地可用)但提炼停摆——语义自然,无特判。
8. **`speak()` 不广播回合结果、不进历史**:同样不触发提炼(开场白无用户输入)。
9. **多语言**:MemoryTexts zh/en 双份,lang 切换=重建会话时随 texts 一起换(既有惯例,零特殊处理)。
10. **HyperOS 禁 shell 触摸注入**:管理面板交互靠用户真手 + ai_cmd 等价命令验收(惯例)。
11. **adapter 错误带响应预览**(A.1 第 11 条):EmbeddingAdapter 同款;提炼 LLM 错误走 OpenAiCompatibleLlmAdapter 既有错误事件,collect 处转 Log。

---

## 10. 开工前 A 任务:提炼 JSON 可靠性 curl 矩阵(半天)

照 agent-feasibility §10 惯例,写 MemoryExtractor 前先验证「跟随主模型」的 JSON 输出可靠性(提炼不用工具,矩阵关注点不同):

| Provider | 模型 | 验证点 |
|---|---|---|
| 硅基流动 | DeepSeek-V4-Flash / Qwen3.8-27B(关思考) | 单行 JSON 合规 ≥5/5;中文原子事实质量;会不会输出 markdown 代码围栏 |
| 火山 | doubao-seed-2-0-mini(关思考) | 同上;thinking:disabled 与 JSON 输出共存 |
| OpenRouter | openrouter/free 路由 | 同上;免费路由 JSON 出格率(决定该路由下是否值得开自动提炼) |

验证 prompt 直接用 §4.3 草案 + 一段含 2 个可记事实的样例对话,跑 5 次统计。产出追加到 `docs/agent-provider-matrix.md` 新增「记忆提炼」节;不达标组合在 MemoryTexts/文档标注(免费路由默认关自动提炼或换固定小模型)。

---

## 11. 参考资料

- claude-code(本机 `/home/neethu/projects/claude-code`):`src/memdir/memdir.ts`(存储格式与主 prompt)/`memoryTypes.ts`(分类与负面清单)/`services/extractMemories/`(后台提炼 agent、互斥、manifest)/`memdir/findRelevantMemories.ts`(召回选择器)/`services/autoDream/`(定期整合)/`query/stopHooks.ts:145`(触发点)
- airi(本机 `/home/neethu/projects/airi`):`integrations/telegram-bot/src/models/chat-message.ts:87-245`(检索公式)/`src/db/schema.ts:110-206`(死表教训)/`bots/telegram/index.ts:225-238`(裁剪告知)
- 本仓库:`docs/agent-feasibility.md` §6(P2 草图,本文接管)/§1.4(airi 记忆盘点)/`docs/dual-llm-feasibility.md`(小模型价格表与独立 LlmAdapter 模式)/`docs/agent-provider-matrix.md`(工具调用可靠性矩阵)/`docs/ai-layer-handoff.md`(A.1 坑清单)
