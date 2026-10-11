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
