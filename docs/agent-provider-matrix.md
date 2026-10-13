# Provider 工具调用兼容矩阵(P0 agent 化,A 任务实测)

> 实测时间:2026-10-11,密钥来自仓库 `secrets.properties`,curl 直连各家 OpenAI 兼容端点。
> 对应设计:docs/agent-feasibility.md §10。**结论:五组(四家)全部支持原生流式工具调用**,P0 无需禁用任何模型的工具。
> 复验命令模板见 agent-feasibility.md §10(单工具 `get_time` + `tool_choice:"auto"`,观察 SSE 原始行)。

## 矩阵

| Provider | 模型 | 工具调用 | 分片形态(实测) | arguments 分片 | finish_reason | assistant content="" | 关思考共存 | 备注 |
|---|---|---|---|---|---|---|---|---|
| 硅基流动 | `deepseek-ai/DeepSeek-V3` | ✅ | 首片带 `index+id+function.name`,后续片显式发 `"id":null,"name":""` | ✅ 2 片增量 | `tool_calls` | ✅ 200 | —(不发参数) | 每片都带 usage 块 |
| 硅基流动 | `Qwen/Qwen3.8-27B` | ✅ | 同上 | ✅ 3 片增量 | `tool_calls` | ✅ 200 | ✅ `enable_thinking:false` 同发无冲突 | — |
| 火山方舟 | `doubao-seed-2-0-mini-260428` | ✅ | 首片带全字段(键序不同:`name` 在 `id` 前),后续片**省略** id/name | ✅ | `tool_calls` | ✅ 200 | ✅ `thinking:{"type":"disabled"}` 同发无冲突 | tool 轮未见 reasoning_content 泄漏进 content |
| 火山方舟 | `deepseek-v4-flash-ga-260731` | ✅ | 同 doubao | ✅ | `tool_calls` | ✅ 200 | — | 该模型思考 token 走 `reasoning_content`(已被适配器忽略),「假视觉」前科不影响工具 |
| OpenRouter | `openrouter/free`(免费路由,实际落 `apodex/apodex-1.1-mini:free` 等) | ✅ | 首片带 id+name,后续片省略 name | ✅ | `tool_calls` | ✅ 200 | reasoning 字段自适应(无需发参数) | 每次路由可能换上游模型 |

## 落进实现的协议要点

1. **累积器必须把 `null` 与 `""` 都当「缺失」**:硅基流动后续分片显式发 `"id":null, "name":""`,火山/OpenRouter 直接省略字段——`ToolCallAccumulator`(`OpenAiCompatibleLlmAdapter.kt` 同包)只在非空时覆写 id/name,否则首片之后的空串会把名字清掉。
2. **并行多工具 = 一个 delta 数组里的多个 `index` 槽**:按 `sortedMapOf<index>` 分槽累积,`Finish.toolCalls` 按 index 序输出。
3. **`finish_reason` 不作判据**:`Finish.toolCalls` 非空即工具轮(防御某些 provider 用 `stop` 收尾仍带累积调用,与 xsAI 行为一致)。
4. **第二轮回灌形态全五家 200**:`[...原请求, {"role":"assistant","content":"","tool_calls":[…]}, {"role":"tool","tool_call_id":…,"content":…}]`——`content` 恒传空串不传 null(DeepSeek 系对非字符串 content 400 的教训)。
5. **关思考方言与 tools 同发无冲突**(Qwen `enable_thinking:false` / doubao `thinking:{type:disabled}`),风险 §9.2 出清,`llmExtraBody` 无需按工具开关让位。

## 工具调用「可靠性」列(2026-10-11 真机+curl 补测,同一提示下重复发起原生调用的成功率)

| 模型 | 思考配置 | 可靠性 | 结论 |
|---|---|---|---|
| 硅基流动 `DeepSeek-V4-Flash`(默认) | 无思考参数 | **3/3** | 可靠 |
| 硅基流动 `Qwen3-VL-32B-Instruct` | enable_thinking:false(对它是 no-op) | **3/3** | 可靠 |
| 火山 `doubao-seed-2-0-mini` | thinking:disabled | **3/3** | 可靠 |
| 硅基流动 `Qwen/Qwen3.8-27B` | **enable_thinking:false** | **~4/10** | ⚠ **不可靠** |
| 硅基流动 `Qwen/Qwen3.8-27B` | 思考开(不发参数) | 4/4 | 可靠但闲聊轮 21s(首响治理回退,不可接受) |

**Qwen3.8 关思考的失败形态与修复**(真机 2c3769db + curl A/B 实证):
- 失败形态 ①:只说衔接句(「让我看看……」)不发起调用就收流(~50%);② 把调用写成正文(「[调用工具 xx]: {…}」自造格式/`<tool_call><function=…>` 模板文本);③ 用 `get_current_view` 顶替意图。
- **根因**:enable_thinking:false 时 SiliconFlow 服务层不解析 Qwen3.8 的模板工具调用——`tool_choice:"required"` 下模型明明输出了正确的 `<tool_call><function=observe_camera><parameter=…>` 文本(2/2),却进不了原生 tool_calls 字段;开思考则服务端正常解析(4/4)。是**服务端解析问题**,非模型能力问题。
- **三层修复**(已实现):①工具说明改祈使句并禁文本式调用(PromptTexts.toolsetBlock,提示词 A/B 迭代:示例反而教会模型模仿文字形态,已删);②会话层「悬空衔接句」检测(`agent/TemplateToolCalls.isDanglingBridgeReply`——≤24 字含看/查动词且无问号)→ `tool_choice:"required"` 静默重试一轮(文字不进 TTS);③`parseTemplateToolCalls` 解析 Qwen 模板文本形态兜底(原生为空时)。修复后真机:间接问法原生调用成功,模型正确转告「打开视频模式我才能看到」。
- **残余**(已知,低频):长错误回答(「让我看看你那边——嗯……画面里好像…」,超 24 字不判悬空)与自造文本格式仍接不住;用户显式说「用工具看看」100% 触发。**选型建议**:视觉+工具场景优先 `Qwen3-VL-32B-Instruct`(可靠+非思考模型快)或火山 doubao 系;Qwen3.8 用户遇到不调工具时显式说「用工具看看」。

### P0.5 设备工具自然触发的两种新失败形态(2026-10-11 晚真机 2c3769db,DeepSeek-V4-Flash / doubao-seed-2-0-mini)

P0.5 六工具真机验收中发现(P0 的 curl A/B 只测「能不能调用」,自然触发按**意图方向**分解后暴露两类新形态):

1. **DeepSeek-V4-Flash 的「方向不对称」**:信息类(get_device_status)与「开启/切换到」类(开灯/set_ambience dim)稳定原生调用;**「关闭/恢复/表达类」动作(关灯、氛围恢复、拍照、击掌)倾向口头演**——模型在台词里宣称「已关掉/拍好啦」但零函数调用,连干净上下文、显式「用手电筒工具」话术、per-tool hint 与 toolsetBlock 公共尾「必须发起函数调用」提示词两轮加重都压不住(doubao 同场景关灯原生调用成功→有模型差异)。**残余缓解**:悬空衔接句检测只接「单句 ≤24 字就停」形态,整轮叙述接不住(已知残余的推广);后续候选=回合级「宣布了动作但零调用」的 required 重试(成本控制:仅当轮内出现动作动词+工具名相关词时)。**用户侧缓解**:关灯/拍照类话术显式点名工具或换 doubao。
2. **doubao-seed-2-0-mini 对 set_ambience 确定性文本吐**(3/3):模型输出 `<set_ambience><parameter name="preset">dim</parameter></set_ambience>` 作为**正文文本**——不是 Qwen 的 `<tool_call><function=>` 模板形态(无 wrapper、parameter 用 name 属性),`parseTemplateToolCalls` 不识别→工具意图丢失,且**原文进了断句→TTS 被朗读**(比 Qwen 形态多一层危害)。同模型 set_flashlight 关灯原生调用正常→工具相关。后续=parser 扩展识别该形态+流式侧抑制(在 InlineTagExtractor 层吞掉,防 TTS 泄漏)。

### P1 走动通道的自然触发形态(2026-10-12 真机 2c3769db,DeepSeek-V4-Flash,锚点已踩点标定)

「你走到 X」类请求 3 走 1 成,成功的形态是标准标签 `<move:window>`/`<move:desk>`/`<move:carpet>`(还会自发配 `<cam:follow>` 编排运镜,异步起步+跨走动 TTS 不断流全链通过)。失败三种(与 P0.5 doubao set_ambience 同族——**文本吐工具调用**):

1. **伪工具调用文本**:`<move_to carpet>`/`<move_to anchor="desk">`(自然措辞与显式「用工具 move_to」都会触发)——不是原生 tool_calls、不是任何协议标签,整段进断句→TTS 朗读且动作不发生;`<stop_moving>` 同形态(走动中叫停,模型只用文本形态回应,原生工具一次没调过)。
2. **发明标签形状**:`<move:window→carpet>`(箭头复合目标),严格 moveTag 名字段不认→原样漏进 TTS。
3. 附带:**未调用就口头宣称到达**(「到啦,已经站在地毯中间停稳了」其实没走)——P0.5「方向不对称」的走动版,公理 3 形态。

**SDK 侧兜底已落地(2026-10-12,层 0 原则「形状是标签就不进语音」)**:InlineTagExtractor 三层扩展——①snake_case 伪工具形状(name 含下划线+可选自由参数)→新 `TagCue.ToolCallText` 只抑制+UnknownTag[tool] 上报,**不执行**(文本不是工具通道);②四命名空间宽容回退 `<move:[^>]{1,40}>` 等,离谱名字下游按未知名过滤+上报;③flush 前缀表补 `move_` 截断形态。误伤防线=要求名字含下划线,散文 `<see this>`/`<hello>` 永不命中。单测 +8(adapter 7/orchestrator 1)。**provider 侧正经修复仍是 P0.5 遗留**:回合级「宣布动作零调用」required 重试(走动动词「去/走/到」加入悬空检测候选集,误伤面要单独评估)+move_to 文本形态的模板解析扩展。**选型建议**:走动方向 DeepSeek 触发不稳定(3 走 1),doubao 未测;结合 P0.5 结论,走动+工具全都要时优先火山 doubao 验证后再定。

## 服务商瞬态故障与韧性(2026-10-11 晚实测)

硅基流动当晚多模型间歇抖动(Qwen3-VL 一度 50507 4/5、Qwen3.8 空回复/60s 挂起、半小时后自愈),两类故障不再杀死回合:
- **adapter 瞬态静默重试**(`OpenAiCompatibleLlmAdapter`,airi transientRetry 同思路):HTTP 5xx / 快速 IO 失败(<15s:连接拒绝/DNS/秒级超时)在**未吐出任何事件**前重发一次(1.5s 退避);已吐字后绝不重试(防复读),慢超时(60s 级)不重试(防二次挂起)。MockWebServer 4 用例锁行为。
- **会话空回复重发**(`AvatarSession` 工具循环收尾):整回合零可见输出且无待执行工具时原样重发一次(completion 全进思考通道的 200 空响应形态,工具轮之后的续答轮同样覆盖)。

**observe_camera 于当晚升级为「任何模式按需拍照」**(取帧缝注入+lens 参数,视频模式取常驻环/其他模式开相机拍完即关),语音模式下火山 doubao 复验:原生调用带 lens=back→按需拍照+描述 2.6s 全对。

**P0 真机验收(2026-10-11 18:26,2c3769db,视频模式+后摄+Qwen3.8-27B)**:用户「用工具帮我看看房间」→ 模型衔接句「好呀,我来看看~」→ 原生 observe_camera(同时用了 description+question 两参数)→ 抓帧+视觉描述(棕色桌面/白色数据线/黑色网格笔筒/粉色玩偶/白色机箱/白墙,isError=false)→ 模型照描述回答并追问互动,toolRounds=1,ttfb=1.3s。**验收标准达成**。

## 未覆盖项(真机验收时补)

- `tool_choice:"required"` 的真实行为(实现已支持三种字符串形态,矩阵只实测了 auto 语义的缺省)。
- 工具相关 400 的真实文案样本:五家都通过了,没采到降级正则的实战样本——`isToolRelatedError` 正则表沿用 airi 实战积累(`ToolErrors.kt`),真机遇到新文案再补。
- 视觉描述请求(observe_camera 的独立 VLM 调用)走的是已验证的图片链路(视频模式同款),无新增协议面。

## 记忆提炼 JSON 可靠性(2026-10-13,memory-feasibility.md §10)

提炼不走 tools 通道(纯 JSON 文本输出),关注点与工具矩阵不同。用 `MemoryTextsZh.extractSystem` 逐字一致的 prompt + 含 2 个可记事实(考驾照科目二/橘猫叫团团)的 6 轮样例对话,temperature 0.2 非流式,每组 5 次:

| Provider | 模型 | 关思考参数 | HTTP | 单行 JSON | 合法 ops | 双事实命中 | 结论 |
|---|---|---|---|---|---|---|---|
| 硅基流动 | `deepseek-ai/DeepSeek-V4-Flash` | — | 5/5 | 5/5 | 5/5 | 5/5 | ✅ 可靠 |
| 硅基流动 | `Qwen/Qwen3.8-27B` | `enable_thinking:false` | 5/5 | 5/5 | 5/5 | 5/5 | ✅ 可靠——印证「提炼不走工具通道,关思考无坑」预判 |
| 火山 | `doubao-seed-2-0-mini-260428` | `thinking:disabled` | 5/5 | 5/5 | 5/5 | 5/5 | ✅ 可靠,~1s 全场最快 |
| OpenRouter | `openrouter/free` | — | 5/5 | 2/5 | **1/5** | 1/5 | ❌ 3 次空回/输出「User Safety: safe」杂语、1 次 JSON 截断——**免费路由下别开自动提炼**(§8 风险 1 预案成立) |

真机验收(同日)侧证:DeepSeek-V4-Flash 与 doubao-mini 双主模型对照跑九场景,提炼请求全部一次成功(无解析失败重试形态),攒批 5 轮触发节奏精确(30 轮寒暄恰 6 次「window drained by 5」)。
