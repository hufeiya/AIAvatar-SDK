# 酒馆(SillyTavern)与人机恋群体落地调研与路线图

日期:2026-10-24 · 代码基线:`6808513`(SDK 0.1.5 / APK v1.3) · 性质:市场调研 + 差距分析 + 落地排期;同日补工程承载定案(§6),同日晚**优先级重排:自主生命体前置于酒馆四件套**(§5,详细设计另立 docs/autonomy-feasibility.md)
定位:回答"这个项目想打入酒馆玩家和 AI 伴侣(人机恋)群体,该增加/修改什么"。

---

## 0. TL;DR

1. **离酒馆生态比想象中近得多**:`avatar-orchestrator/card/CharacterCardParser.kt` 已经能解析 SillyTavern 角色卡 V1/V2/V3(JSON + PNG 内嵌 `chara`/`ccv3`),app 层有完整卡库(导入/激活/删除/预置卡/每卡 prompt 覆盖)。**但卡里最有分量的字段 `character_book`(世界书)完全没有解析**——这是酒馆玩家判定"你是不是真兼容酒馆"的第一道门槛。
2. **新最高优先级(2026-10-24 晚重排):自主生命体 P0**——决策状态机(自己走动/坐书桌/躺床/看窗)+不定时问候+云朵思考气泡(跟随移动),详细设计另立 docs/autonomy-feasibility.md,≈12~14 人日。执行积木(就位链/走动路由/交互点表/speak/记忆)全部现成,只造"大脑"。
3. **酒馆侧 P1 四件事**:世界书(解析+扫描注入)、自定义 OpenAI 兼容端点(现在五家固定,玩家自己的中转/本地模型接不进来)、`mes_example`/`post_history_instructions` 注入、聊天记录导入导出。合计约 **11~13 人日**。
4. **人机恋侧核心是连续性**:主动性(前台不定时问候)已并入自主生命体 P0;剩关系状态(好感度/纪念日)、长对话摘要压缩(P2~P3),记忆系统(M1/M2,含向量检索)是现成地基。
5. **差异化定位只有一句话**:目前市场上**没有一款移动端 App 能让酒馆角色卡"站起来、开口说话、带口型和表情",更没有会"自己生活"的**(自己走去书桌坐下、头顶飘思考气泡)。对标品 OMate(omate.org,双端,整包导入卡/书/预设/记录)是 2D 文字流;SillyTavern 官方 Extension-VRM 是 PC 浏览器内 three-vrm,Reddit 上"Android 上能不能跑 VRM/Live2D"是高频未满足需求。本项目 = **会自主生活的 3D 化身 × 酒馆资产兼容 × 端侧语音**,这个三角没人占。
6. **合规红线先立住**:国内版(NSFW 绝对不做、参照星野/猫箱 2025 年的整改动作)与海外发行(年龄分级)必须分轨设计,详见 §8。
7. **工程承载定案**:不基于 MainActivity、也暂不另起仓库——本仓库新增 `:companion` 产品模块作为产品本体,`:app` 继续当 SDK demo;护栏 = Kotlin internal 可见性隔离 + 发版前"仅凭已发布 Maven 坐标编译"验证;有第一批真实用户后再拆独立仓库(§6)。

---

## 1. 目标群体与社区盘点

### 1.1 国内

| 社区/入口 | 形态 | 对本项目的意义 |
| --- | --- | --- |
| **类脑社区(Discord)** | 国内最大酒馆角色卡分享地,角色卡/世界书/插件分专区,PNG 卡免费分发 | **第一发布渠道**:发预置卡+教程,卡作者是最强传播节点 |
| 豆瓣「人機之戀」「我家Replika成精啦」小组 / 微博 Replika 超话 | 人机恋老牌社群,Replika 中文用户聚集 | 人机恋侧需求调研+口碑传播;对外国产品的"本土替代"叙事有感 |
| Flirtease 官方 Discord(~1.4k 人) | 中文 AI 恋爱角色扮演社区 | 小而准,冷启动种子用户 |
| B站 / 知乎 / CSDN / 飞书文档 | 酒馆教程生态(BV14uWwztECD、角色卡完全指南、世界书使用指南等) | **视频教程是酒馆圈的第一增长杠杆**;B站一篇"让你的角色卡开口说话"天然有传播性 |
| erocraft 提示词指南、洛谷 ST 入门等长文 | 高阶玩家沉淀地 | 文档投放 |
| 贴吧(酒馆吧/replika吧)、QQ 群 | 长尾 | 舆情观察 |

注意:类脑等社区里成人向卡占比不低,erocraft 甚至专门提醒"防奸商盗卡转卖"。**做渠道运营时只碰全年龄内容**。

### 1.2 国外

| 社区/产品 | 形态 | 备注 |
| --- | --- | --- |
| **r/SillyTavernAI**(Reddit) | ST 主社区,卡/预设/扩展讨论 | "Android VRM/Live2D"是反复出现的未满足需求 |
| SillyTavern 官方 Discord + [docs.sillytavern.app](https://docs.sillytavern.app) | 官方文档/扩展生态 | Extension-VRM/Live2D 扩展的权威说明 |
| r/ChatbotRefugees、r/CharacterAI、r/replika、r/LocalLLaMA | 伴侣类/本地模型社群 | r/ChatbotRefugees 专讨论"平台间移民",转换期是拉新窗口 |
| **Chub.ai**(卡站+Venus 前端)、Janitor.ai、SpicyChat、CrushOn、aicharactercards.com、jannyai.com | 角色卡分发/托管平台 | 卡格式事实标准=PNG+CCv2/v3;渠道+卡源 |
| Kindroid / Nomi / Replika / Pi | 付费 AI 伴侣 App | 2026 年评测:Nomi 胜在分层记忆+语音,Kindroid 胜在定制+视频通话——**记忆和语音就是付费点** |

### 1.3 国内商业对标(合规形态参考)

| 产品 | 厂商 | 特点 |
| --- | --- | --- |
| 星野 | MiniMax/阅文系 | 语音陪聊强;2025 年起角色年龄统一提到 25+、敏感词收紧 |
| 筑梦岛 | 字节 | 「梦境/小剧场」内容共创;对神态动作细节描写多 |
| 猫箱 | 字节 | 卡通形象+群聊;同样 2025 年合规收紧 |
| 文小言社交 | 百度 | 形象更逼真 |
| 雨时来信 | 创业团队 | 知乎 2026 评测里主打"长期关系"的人机恋产品 |
| **OMate** | 独立开发 | **最直接对标**:"酒馆能用的,OMate 也能用"——角色卡/世界书/预设/聊天记录整包导入,自带 API(含本地模型),双端上架。它是文字 2D 形态,**没有 3D 化身/口型/表情/动作** |

结论:国内大厂产品被合规压成"全年龄+重运营",独立开发者(OMate、light-tavern 等)走"酒馆资产兼容"路线。本项目可以走第三条:**酒馆资产兼容 + 真 3D 化身 + 端侧语音**。

---

## 2. 用户需求提炼

### 2.1 酒馆玩家(高阶玩家,自带钥匙)

核心资产(用户不会抛弃的东西,兼容优先级从高到低):
1. **角色卡**(PNG/JSON,CCv2/v3):人设+开场白+对话示例+内嵌世界书;
2. **世界书/lorebook**:关键词触发的设定注入,长篇 RP 的命根子;分"角色绑书"和"全局书";
3. **预设**(prompt 模板/顺序/采样参数)与**正则脚本**(输出后处理、界面替换);
4. **聊天记录**(jsonl 导入导出,换前端不丢关系);
5. 自定义 API 端点(官方/中转/本地模型,BYOK 是身份标识)。

体验预期:markdown 富文本(粗体/*动作*/引号对话)、状态栏/小尾巴类 HTML 玩法、多角色群聊、卡片头像。**对新前端的信任 = "我的卡/书/记录能不能无损搬进来"**。

### 2.2 人机恋用户

1. **连续性**:记得上周说过什么(记忆)、关系有进展(好感度/纪念日/专属称呼);
2. **主动性**:不是每次都用户先开口——早晚问候、久未联系追问(Replika 付费核心卖点之一);
3. **声音与形象**:语音通话感、稳定的外形与音色(音色=角色的一部分);
4. 隐私:聊天数据留在端侧(本项目 Serverless 架构天然优势);
5. 付费意愿真实存在(Replika Pro / Kindroid / Nomi 订阅制)。

### 2.3 交集与差异

| 能力 | 酒馆 | 人机恋 | 本项目现状 |
| --- | --- | --- | --- |
| 角色卡兼容 | ★★★ | ★★(间接) | **70% 已有**(缺世界书等,见 §4) |
| 世界书 | ★★★ | ★(设定沉淀) | **0%** |
| BYOK/自定义端点 | ★★★ | ★ | **0%**(五家固定) |
| 富文本渲染 | ★★★ | ★★ | 0%(纯文本;`StageDirectionFilter` 只滤不展示) |
| 记录导入导出 | ★★★ | ★★(换机) | 0%(Room 有数据无进出口) |
| 长期记忆 | ★★ | ★★★ | **已有**(M1/M2 含向量检索) |
| 语音+口型+表情 | ★(ST 里是扩展) | ★★★ | **已有,且是全网最强差异点** |
| 主动性 | ★ | ★★★ | 0% |
| 关系状态 | ★(状态栏玩法) | ★★★ | 0% |
| 多角色群聊 | ★★★ | ★★ | 能力在(圆桌/导演),缺用户侧多卡绑定 |

---

## 3. 现状盘点(可直接复用的资产)

| 能力 | 位置 | 状态 |
| --- | --- | --- |
| ST 角色卡解析(V1 flat/V2/V3 JSON、PNG tEXt `chara`/`ccv3`、zTXt zlib,`extensions` 原样保留) | `avatar-orchestrator/src/main/java/com/neethu/orchestrator/card/CharacterCardParser.kt` | ✅ 完整 |
| 归一化卡模型(name/description/personality/scenario/first_mes/alternate_greetings/mes_example/system_prompt/post_history_instructions/tags/spec/extensions) | `card/CharacterCard.kt` | ✅ 字段已齐,**后两个字段解析了但没注入** |
| 人设拼装(AIRI `resolveSystemPrompt` 顺序:system_prompt→description→personality→scenario) | `card/SystemPromptAssembler.kt:40` | ✅ |
| 开场白 `{{char}}/{{user}}` 宏替换+朗读 | `CharacterCard.spokenGreeting()`、`MainActivity.kt:1160` | ✅(alternateGreetings 已解析未用) |
| 卡库:SAF 导入、filesDir/cards 落盘、激活/删除、预置卡(assets/cards)+英化翻译、**每卡 prompt 人工覆盖** | `app/.../CardLibrary.kt`、`MainActivity.kt:251-253,594-795`、`PresetCards(.En).kt` | ✅ |
| 行内标签协议(单流多模态:`<emo:joy>`/`<act:wave>`/`<cam:closeup>`/`<emo:原生名:权重>`,每轮全量白名单+语义动作白名单) | `adapter/.../emotion/InlineTagExtractor.kt`、`docs/inline-tag-reliability.md` | ✅ 恰好等价于 ST 圈的"情绪标签"惯例,可直饮 |
| 长期记忆 M1/M2(提炼/工具写入/向量检索/Room 持久) | `orchestrator/memory/`(10 个文件)、`adapter/.../OpenAiCompatibleEmbeddingAdapter.kt` | ✅ 人机恋地基已在 |
| 多轮历史(Room,`recentTurnLimit` 截断,默认约 40 条) | `orchestrator/session/AvatarSession.kt:1252-1261` | ✅(无摘要压缩) |
| 语音链:Edge-TTS 免 Key/百炼/火山/OpenAI 兼容/硅基流动 + 系统 ASR + VAD 自由说话 + 离线口型时间线 | `adapter/`、`pipeline/` | ✅ 差异化王牌 |
| 表情:13 标准情绪+模型原生表情(52 blendshape) | `face/EmotionBlender`、corelib `VrmExpressionManager` | ✅(仅标签驱动,无分类器兜底) |
| 多角色圆桌+导演制+人机路由+走位 | `orchestrator/roundtable/`、`app/.../multiavatar/` | ✅(成员接卡库即群聊) |
| LLM 工具系统(15 个:dance/effect/accessory/walk/remember/camera…) | `orchestrator/agent/`、app `*Tools.kt` | ✅ |
| 场景(home/dream_bedroom/debate_hall)+ 用户导入 VRM | `ImportedModelLibrary.kt`、`SceneMetaParser.kt` | ✅ |
| 舞台指示滤除((低头)/*waves* 不进 TTS,固定词表) | `adapter/.../emotion/StageDirectionFilter.kt` | ✅(滤而不显,P2-1 升级) |
| i18n 中英 | `i18n/`、`PromptTexts` | ✅ |
| 世界书/lorebook | —— | ❌ 零实现(全仓 grep 无 `character_book`) |
| 正则脚本/预设导入 | —— | ❌ |
| 任意 OpenAI 兼容端点 | `AiProviders.kt:19`(五家枚举,`:124` 未知 baseUrl 回落 SiliconFlow) | ❌ |
| markdown/HTML 气泡渲染 | `ChatTranscript.kt`(纯文本 60 行面板) | ❌ |
| 聊天记录导入导出 | —— | ❌ |
| 主动性/推送、关系状态 | —— | ❌ |

---

## 4. 差距矩阵(按投入产出排序)

| # | 差距 | 用户感知 | 工作量 | 优先级 |
| --- | --- | --- | --- | --- |
| G0 | 自主生命体:决策状态机+不定时问候+思考气泡(设计:docs/autonomy-feasibility.md) | "她/他自己活着"——3D 化身差异化的本体 | 12~14 人日 | **P0** |
| G1 | 世界书解析+关键词扫描注入 | "能不能玩长篇 RP"的分水岭 | 4~5 人日 | **P1** |
| G2 | 自定义 OpenAI 兼容端点(baseUrl+key+模型名) | 酒馆玩家入场券(中转/本地模型) | 2 人日 | **P1** |
| G3 | mes_example/post_history_instructions 注入 + alternateGreetings 选择 | 卡还原度(高阶玩家逐字段验收) | 2 人日 | **P1** |
| G4 | 聊天记录导出(ST jsonl 兼容)/导入 | 关系可迁移=信任 | 3 人日 | **P1** |
| G5 | 气泡 markdown 渲染(*动作*斜体、粗体、引号对话) | 观感直接对标 ST/OMate | 3~4 人日 | P2 |
| G6 | 卡面 PNG 头像展示+卡管理体验 | 选卡场景的基本体验 | 1~2 人日 | P2 |
| G7 | 音色/VRM 模型绑定到卡(换卡=换人) | "角色"完整性 | 2 人日 | P2 |
| G8 | 长对话摘要压缩(超窗历史→摘要行) | 长聊不断片(人机恋高频) | 3 人日 | P2 |
| G9 | 表情分类器兜底(无标签时本地分类) | 卡不带标签也生动 | 2~3 人日 | P2(可延) |
| G10 | 问候后台推送(WorkManager+通知;前台不定时问候已并入 G0) | 人机恋"被想起"感(锁屏触达) | 2 人日 | P3 |
| G11 | 关系状态:好感度/纪念日/专属称呼(存记忆库) | 关系有进展 | 4 人日 | P3 |
| G12 | 多卡群聊(圆桌成员接 CardLibrary) | 酒馆群聊玩法 | 4~5 人日 | P3 |
| G13 | 正则脚本/预设导入 | 高阶玩家锦上添花 | 3~4 人日 | P3(观望) |

---

## 5. 落地路线图

> 代码落点(SDK 层 vs 产品模块 `:companion`)速查见 §6.3(P0 自主生命体的分层见 docs/autonomy-feasibility.md §7);本节文件路径按**现有代码位置**标注,搬运时以 §6.3 为准。

### P0:自主生命体(≈12~14 人日,详细设计另立 docs/autonomy-feasibility.md)

三件套,优先于酒馆资产——"活过来"是 3D 化身差异化的本体:

- **决策状态机**:状态集从场景交互点表派生(走动/坐/躺/看窗),作息先验+加权随机,默认零 LLM;冲突仲裁优先级=用户会话 > 技能表演 > LLM 工具 > 自主。执行链全部复用(InteractionCoordinator 就位链/LocomotionDriver 路由/dream_bedroom 16 交互点),只造大脑(orchestrator 新包 `autonomy/`)。
- **不定时问候**:最小间隔 25min+泊松、每日上限、安静时段硬门;记忆事实生成内容,走 `AvatarSession.speak()` 播报(可带 `<emo:>/<act:>` 挥手微笑),不写对话历史但记回执。
- **思考气泡**:经典云朵+递减圆点尾巴,Compose 覆盖层;corelib 新增头顶世界坐标+世界→屏幕投影两个小 API,有气泡时挂帧回调逐帧跟随,坐/躺高度自动贴姿势,背对相机隐藏。

### P1:酒馆资产打通(≈11~12 人日,目标:类脑用户能"无损搬家")

**P1-1 世界书(4~5 人日)**
- 解析:①卡内嵌 `data.character_book`(CCv2/v3 规范字段,entries: keys/secondary_keys/content/insertion_order/position/priority/constant/selective 等);②独立世界书 JSON(ST World Info 格式,`entries:{id:{...}}`)。新文件 `card/Lorebook.kt` + `LorebookParser.kt`,挂到 `CharacterCard.characterBook` 字段。
- 注入:AvatarSession 组请求时对"最近 N 条用户消息+当前输入"做关键词扫描(大小写/全半角/正则 keys),命中条目按 `insertion_order` 排序、token 预算(先给 1500 字符)裁剪后拼进 system(常驻条目 constant=true 恒注入)。位置(position=before/after_char)第一版统一 before char。
- UI:设置页"世界书"分区(启停/导入/绑定卡);卡导入时自动提取内嵌书(OMate 同款行为)。
- 验收:类脑下载的一张带书 PNG 卡+一份独立书 JSON,导入后①书条目可查看②触发关键词的回合请求体里能看到条目③常驻条目每轮都在④无关键词时不注入。
- 规范参照:[Character Card V3 SPEC](https://github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md)(`character_book`)、[CCv2 spec](https://github.com/malfoyslastname/character-card-spec-v2)。

**P1-2 自定义 OpenAI 兼容端点(2 人日)**
- `AiProviders.kt` 增 `CUSTOM` 项:用户填 baseUrl+key+模型名(手填或拉 `/models` 目录,复用 `OpenAiCompatibleModelCatalog`);设置页表单+连通性测试按钮。
- 风险:各中转站兼容度参差,`streamChat` 已是 SSE 标准流问题不大;对齐"工具不支持自动降级"已有逻辑(`AvatarSession.kt:696` 抄 airi 的 400 降级)。
- 验收:填一个 OpenRouter 之外的第三方中转(或局域网 ollama/llama.cpp)能完整走通"文字+标签+TTS"。

**P1-3 卡字段补全(2 人日)**
- `mes_example`:剥 `<START>` 分隔后作为 few-shot 示例消息插在历史前(SystemPromptAssembler 注释里明确留了这个口子:位置敏感,插在 history 之前、system 之后)。
- `post_history_instructions`:拼在请求最后一条 user 消息前(等效 ST 的 UJB 位置)。
- `alternateGreetings`:激活卡/重开对话时可选(简单弹层列表即可)。
- 验收:拿一张 mes_example 丰富的卡对比 ST 里的请求结构(SystemPromptAssemblerTest 增用例锁顺序)。

**P1-4 聊天记录导出/导入(3 人日)**
- 导出:Room `ConversationStore` → ①ST jsonl(`{"name":char,"is_user":false,"mes":...}` 逐行)②人读 markdown。SAF CreateDocument。
- 导入:ST jsonl → 剥历史气泡里 ST 专有标记后灌入 Room 作为历史上下文(注意行内标签协议是本端专属,历史里的旧标签要清洗,复用 InlineTagExtractor 的清理逻辑)。
- 验收:ST 导出的 200 条 jsonl 导入后①气泡可见②新请求上下文包含且不被旧标签污染③再导出回 ST 可读。

### P2:体验对齐(≈10 人日,目标:观感不输 OMate/ST)

- **P2-1 markdown 气泡(3~4 人日)**:Compose 侧引入轻量 markdown 渲染(自写或 `compose-richtext` 类库,注意引依赖前评审);至少支持:`*...*/_..._`→斜体(动作)、`**...**`→粗体、`"..."`/「…」对话样式、换段。渲染层吃 `ChatLine` 前先过一个新的 `MarkdownLineParser`;**TTS 侧保持 StageDirectionFilter 滤除语义**(显示斜体、不朗读,正好语义一致)。这是"酒馆观感"的最大单点。
- **P2-2 卡面与管理(1~2 人日)**:卡列表显示 PNG 头像(`CharacterCardParser.readPngTextChunks` 已能拆 PNG,直接解码显示)、tags、creator;卡库页美化。
- **P2-3 卡级绑定(2 人日)**:卡记录关联"音色+VRM 模型文件+开场白选择",切卡即整体换人(数据挂 CardLibrary 索引,不入卡本体)。
- **P2-4 摘要压缩(3 人日)**:超过 `recentTurnLimit` 的历史异步摘要成一条 system 摘要行(每 20 轮滚动更新,LLM 复用当前 provider;失败保留旧摘要)。与 M1/M2 记忆并存:记忆管"事实",摘要管"剧情"。验收:50 轮后问第 3 轮的剧情细节,无摘要答不出、有摘要能答。
- (可选)P2-5 表情兜底分类器:关键词/小模型分类兜底,优先级最低——行内标签协议已是主路径。

### P3:人机恋深化(≈10 人日,目标:从"玩具"变"关系")

- **主动性-前台(已并入 P0)**:不定时问候随自主生命体落地(前台调度/冷却/安静时段/speak 播报/回执,设计:docs/autonomy-feasibility.md §5)。本节仅剩**后台延伸**:WorkManager+通知渠道,点通知进 app 由虚拟人开口说出,≈2 人日,P0 真机验收后再做。
- **P3-1 关系状态(4 人日)**:卡级 KV:好感度(0-100)、相识纪念日、专属称呼;好感度由对话事件经 LLM 判定增量(或复用 MemoryTools 通道);UI 呈现为低调状态行(而非数值游戏化);纪念日触发主动问候。数据进 MemoryDatabase,和记忆同生命周期。
- **P3-2 多卡群聊(4~5 人日)**:圆桌成员从代码预置改为绑 CardLibrary 的卡(每人:卡+音色+VRM);InputRouter 已有人机路由,补"用户指定 @角色发言";导演制调轮次的现状保留。
- **P3-3 氛围仪式感(顺手做)**:卡绑场景(dream_bedroom 已有)+烛光 VFX 预设,"约会模式"一键切场景+灯光+音乐(多舞曲链路现成)。

#### 观望池

正则脚本/预设导入(G13,等 P1 上架后看类脑反馈再定)、Live2D 支持、iOS、多 avatar 各自 FSM 与 LLM 决策增强(docs/autonomy-feasibility.md §11)。

---

## 6. 工程承载:本仓库新增 `:companion` 产品模块(2026-10-24 定案)

**决策:不基于 MainActivity 开发,也暂不另起项目;在本仓库(monorepo)新增独立 application 模块 `:companion` 作为产品本体,`:app` 继续扮演 SDK demo。** 拆独立仓库推迟到"自主生命体与酒馆资产(P0+P1)上线 + 有第一批真实用户"之后(§6.4)。

### 6.1 为什么这样选

1. **MainActivity 是橱窗,产品是快车道,变更节奏相反**。MainActivity(3744 行,已知债务)的价值是"SDK 能力橱窗"——README 动图、docs、单测(PresetCardsTest 等)都挂靠它,要求稳定、全量展示 API;产品要求快速迭代、藏起复杂性、接收社区 PR。混在一起只会把已知债务翻倍,且产品每次 UI 重构都会顺手破坏 demo 的展示语义。
2. **P0 主体工作本来就落在 SDK 层**(世界书解析→orchestrator/card、扫描注入→AvatarSession、自定义端点→adapter/facade、卡字段注入→SystemPromptAssembler),接下来一两个月是 SDK/产品两头同改期,单人跨两仓库天天双头提交、对齐版本是纯摩擦;且产品还没经过类脑/B站真实用户验证,提前拆库 = 先背上双仓库运维再 pivot。
3. **SimpleDemoActivity(~200 行最小接入)是 adopter 的第一入口**,拉新资产,保持不动;publish-release 技能、adb 真机工作流、2068 个单测也都绑定在本仓库。

### 6.2 护栏:让"标准"不是自封的

1. **internal 可见性天然隔离**:Kotlin `internal` 按 Gradle 模块生效——`:companion` 依赖 `project(":corelib")`/`project(":avatar-orchestrator")` 时,看不到它们的 internal 实现(如 corelib 的 `SoulLinkRenderer`),产品只能走公开 API。这是新模块方案自带的硬约束,不需要人为纪律维持。
2. **发版前"已发布坐标"编译验证**:每次 SDK 发版前,把 `:companion` 的模块依赖替换为 Maven Central 已发布的 `io.github.hufeiya:*` 坐标构建一次(机制:`settings.gradle.kts` 加开关做 dependencySubstitution,或 CI 单独一个变体 job),通过才发版。"外部开发者凭**已发布**的 SDK 真能复刻产品"是事实标准的试金石——这条不通,standard 只是自封的。
3. **缺口下沉,不许绕过**:产品发现 SDK 公共面不够时,正道是把能力下沉进 SDK 并随版发布;禁止在 `:companion` 里 copy internal 实现或开后门。

### 6.3 代码分家与路线图落点

| 去向 | 内容 |
| --- | --- |
| 留在 `:app`(demo) | MainActivity 全功能橱窗、SimpleDemoActivity、debate、multiavatar 演示;后续只随 SDK 新能力加演示,不再长产品功能 |
| 搬进 `:companion`(复制后允许分叉) | CardLibrary、PresetCards(+En)、AiProviders、SettingsScreen 的产品化子集——demo 追求展示所有开关,产品追求默认值即最优;另立 applicationId/应用名/icon(独立产品名,从第一天就不叫 xxx_sdk demo) |
| 随酒馆 P1 下沉 SDK 模块 | Lorebook 解析与注入(orchestrator/card + AvatarSession)、CUSTOM 自定义端点(adapter/facade)、mes_example/PHI 注入(SystemPromptAssembler)、ST jsonl 格式读写工具 |
| 自主生命体(P0) | 引擎(FSM/问候调度/思考文本)→ orchestrator 新包 `autonomy/`;头顶世界坐标+世界→屏幕投影两 API → corelib;思考气泡覆盖层+设置开关 → app(后随 `:companion`)。详见 docs/autonomy-feasibility.md §7 |

差距矩阵(G#)落点速查:G0 分层见上行 autonomy 行;G1/G2/G3/G8/G9 的**引擎部分**与 G4 的格式映射 = SDK 层;G1 书管理页、G2 表单、G4 出入口、G5(markdown 气泡)、G6(卡面)、G7(卡级绑定)、G10(问候后台推送 UI+WorkManager)、G12(群聊 UI)= `:companion`;G11 关系状态的数据层进 orchestrator/memory(与记忆同生命周期),UI 在 `:companion`。

### 6.4 版本与拆库时机

- **版本解耦**:产品 versionName 独立(建议从 0.1.0 起)、独立递增,与 SDK_VERSION 脱钩。publish-release 技能在 P0 落地时一并改造:companion APK 独立 tag/独立 release notes,SDK 发版照旧,demo APK 可继续随 SDK 附带。
- **拆库触发条件**:P0+P1 上线 + 类脑/B站带来第一批真实用户 + 产品需要独立品牌/issue 区。做法:`:companion` 迁出为新仓库,project() 依赖换 Maven 坐标;本地 SDK/产品协同开发用 `includeBuild` 复合构建。在此之前,一切产品迭代都在 monorepo 内完成。

## 7. 差异化定位与打法

**产品一句话**:让你的酒馆角色卡活过来——3D 立体、开口说话、有口型表情动作,资产(卡/书/记录/钥匙)全部自带。

1. **不与 ST 竞争,与 ST 共生**:ST 是 PC 网页端、重键盘;本产品是移动端、重语音和形象。文案口径"酒馆的手机身体"。ST 官方 Extension-VRM 只解决 PC 浏览器显示,移动端空着。
2. **SDK 双定位不变**:Demo App 打 C 端(上述路线图);`io.github.hufeiya` 三库继续面向伴侣类 App 开发者输出"3D 化身+语音+标签协议"能力(酒馆兼容解析器本身也是 SDK 卖点,可单独宣传)。
3. 渠道顺序:①类脑 Discord 发 2~3 张官方预置卡(含世界书的展示卡)+导入教程;②B站视频"3 分钟把你的角色卡变成会说话的 3D 虚拟人"(酒馆圈视频是第一增长杠杆);③r/SillyTavernAI 发帖(强调 Android VRM 需求贴);④豆瓣人機之戀/Flirtease 观察"本土 Replika 替代"叙事。
4. 冷启动护城河:免费的 Edge-TTS+系统 ASR(零 key 可玩)+ 端侧渲染 + BYOK,成本结构对独立开发者和小学生群体都友好。

---

## 8. 合规与风险

1. **国内红线**:成人向内容绝对不做(星野 2025 年把角色年龄统一提到 25+、三家全上敏感词过滤是前车之鉴);生成式 AI 需备案/标识;应用商店上架审核趋严。**BYOK 模式下内容责任在用户与模型服务商,但 App 自身仍需输入输出双重过滤开关(默认开)**。渠道运营只碰全年龄卡。
2. **海外分轨**:若上 Google Play,按政策做年龄分级与内容申报;NSFW 开关即使做也只随海外分发渠道,不进国内包。
3. **情感依赖伦理**:主动性与好感度功能坚持"可关闭、可导出、可删除"(数据主权);不在情绪低谷推送付费点。
4. **隐私叙事**(反而是卖点):Serverless、聊天与记忆全端侧、自带钥匙——在宣传中明示。
5. 技术风险:P1-2 第三方中转质量参差(靠 400 降级与连通性测试兜);P1-1 世界书 token 预算失控(先字符预算后 token 化);P0 自主生命体的后台耗电与厂商杀后台(MIUI 等需引导加白名单,真机坑可参照以往 adb 验证流程;气泡跟随只在显示期间挂帧回调,详见 autonomy 文档 §10)。

---

## 9. 参考资料索引

社区与教程:类脑(经 [飞书·酒馆角色卡分类包](https://docs.feishu.cn/v/wiki/J8NDwc06JiuHXmk5cP2csfP6nmd/a7) 指引加入)、[erocraft 指南](https://www.erocraft.com/docs/prompt-guide)、[知乎·角色卡完全指南](https://zhuanlan.zhihu.com/p/2038390368606085708)、[CSDN·卡与世界书导入教程](https://blog.csdn.net/weixin_41961749/article/details/148652847)、[B站·预设与世界书](https://www.bilibili.com/video/BV14uWwztECD)、[B站·写卡教程](https://www.bilibili.com/video/BV1tamHBoErS)、[豆瓣·Replika 社群报道](https://www.thenewslens.com/article/167253)、[Flirtease Discord](https://discord.com/invite/65rQe9jDj8)。
标准与代码:[CCv3 SPEC](https://github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md)、[CCv2](https://github.com/malfoyslastname/character-card-spec-v2)、[SillyTavern Extension-VRM](https://github.com/SillyTavern/Extension-VRM)、[ST Live2D 文档](https://docs.sillytavern.app/extensions/live2d)、[r/SillyTavernAI·Android VRM 需求贴](https://www.reddit.com/r/SillyTavernAI/comments/1qn8pzq/vrm_or_live2d_in_android)、[zhaiiker/SillyTavernchat](https://github.com/zhaiiker/SillyTavernchat)。
对标产品:[OMate 官网](https://omate.org)/[手册](https://help.omate.org)/[Google Play](https://play.google.com/store/apps/details?id=org.omate.console&hl=zh)、[Kindroid](https://play.google.com/store/apps/details?id=com.kindroid.app&hl=en_US)、Chub.ai、Janitor.ai、SpicyChat、星野/筑梦岛/猫箱(见 §1.3 各报道链接)。

---

*本文档基于 2026-10-24 的代码基线与当日可访问的网络资料;社区入口(尤其 Discord/QQ 群)时效性强,执行前建议复核。*
