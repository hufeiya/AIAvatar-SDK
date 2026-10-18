# 多模态行内标签可靠性方案与真机排查记录

日期：2026-10-18（Asia/Shanghai）  
代码基线：`9f20483`  
范围：先提供诊断和实施方案；本次没有修改业务代码或安装新的 APK。已使用现有真机完成 4 次 LLM→标签解析→TTS 链路测试。

> 实施状态（2026-10-18）：P0 已由用户完成真机验证；P1 代码与自动化回归已完成；随后完成真机验收，结论为**部分通过**（仍有动作漏发与空台词），详见第 10、11 节。第 1–9 节保留原始排查与设计背景，其中“当前代码”“尚未实现”等描述指排查基线。

## 1. 结论与推荐路线

**当前最主要的问题是：第二轮起，请求里已经没有完整白名单，而不是模型看到了白名单后单纯不听话。** 当前 `/chat/completions` 调用没有服务端会话引用，第一轮发过的系统消息不会自动出现在下一轮上下文中。

另一个直接放大错误的因素是：原始回复，包括被拒绝的标签，仍会写入历史；提示词却要求模型“只使用你历史回复中出现过的标签名”。真机上已经观察到模型连续复用同一组错误标签。

建议分三步实施：

1. **P0：每个请求都带有效协议和词表，清理回放给模型的错误标签。** 保留当前单 LLM、单流、行内标签架构；先修复信息缺失。
2. **P1：缩小模型需要选择的动作集合，加固解析与执行校验。** 用稳定的常用语义动作和按需候选覆盖几百个动画资产，减少长文件名诱发的拼写错误。
3. **P2：如果要求模型输出也严格限制在枚举中，采用服务端约束解码。** 可以仍然只有一个 LLM 请求和一条流；客户端白名单校验继续保留。

需要分别定义两个目标：

- **生成合规**：模型少写或不写词表外标签。提示词只能提高概率；支持严格枚举的约束解码能进一步限制生成空间。
- **执行合规**：词表外控制指令永远不执行。这个目标必须由客户端确定性校验实现，不能交给模型承诺。

“不发标签”虽然不会出现非法标签，但也会让虚拟人不动。因此验收必须同时看非法标签率和用户要求的动作是否完成。

## 2. 本次真机证据

### 2.1 环境

| 项目 | 实测值 |
| --- | --- |
| 设备 | Redmi K20 Pro，adb 在线 |
| 应用 | `com.neethu.aiavatar_sdk`，versionName `1.2`，versionCode `3`，debuggable |
| LLM | 百炼 `qwen3.8-flash`，请求日志显示 `thinking=off` |
| TTS | 百炼 `qwen3-tts-flash`，音色 `Cherry` |
| 人物卡 | 未激活，`persona=0ch` |
| 当前协议块 | 13,113 字符，含标签目录和工具说明，不等同于 token 数 |
| 运行时目录 | 327 个动作、8 个镜头、68 个原生表情；另有 13 个标准情绪、10 个移动锚点 |
| 工具 | 请求携带 15 个工具，本次 4 次回复均 `toolRounds=0` |
| 原上下文 | `8a7022fd`，10 条消息，测试后已切回 |
| 测试上下文 | A=`dbd597c6`，B=`efe80a2d`，保留以便复查 |

README 的“334 个动画”不能当作当轮标签数量：本地当前有 346 个 `.vrma` 文件，而真正注入的目录还经过技能排除、名称转换、去重等处理。**权限依据应始终是当前运行时注册表。** 本次未逐一核对已安装 APK 与工作区的全部资产差异。

### 2.2 四次请求

| 样本 / 时间 | 用户请求 | system 状态 | 模型输出与事件 |
| --- | --- | --- | --- |
| A1 / 15:11:57 | 请开心地向我挥挥手打个招呼，镜头给面部特写。只说一句简短的中文。 | `full`，`protocol=13113ch` | `close_up`、`happy`、`aj_waving_gesture` 有效；有对应镜头、表情和动作事件 |
| A2 / 15:12:38 | 现在请害羞地低头，再轻轻点头表示同意，镜头拉到全身。只说一句简短的中文。 | `brief`，`protocol=omitted` | 自造 `full_body`、`aj_bowing_head`，均产生 `UnknownTag`；`shy` 有效 |
| A3 / 15:13:07 | 再做一次刚才的点头动作，镜头仍然保持全身。只说一句简短的中文。 | `brief`，`protocol=omitted` | 再次复用 `full_body`、`aj_bowing_head`，再次被拒绝 |
| B1 / 15:13:38 | 与 A2 完全相同，换新上下文 | `full`，`protocol=13113ch` | `full_shot`、`shy`、`agreeing_yes` 有效；但输出了“（低头）”，进入 TTS 队列 |

A2 的关键日志摘录，省略进程等无关字段：

```text
REQUEST model=qwen3.8-flash ... sys=brief persona=omitted protocol=omitted history=3 sent=3
UnknownTag[cam] <cam:full_body>
UnknownTag[act] <act:aj_bowing_head>
RESPONSE raw: <cam:full_body><emo:shy:0.8><act:aj_bowing_head>嗯……好的呀。
RESPONSE clean: 嗯……好的呀。
```

A3 的原始输出：

```text
<cam:full_body><emo:happy:0.6><act:aj_bowing_head>收到，这次看得清吧？
```

B1 的原始输出及 TTS 队列证据：

```text
<cam:full_shot><emo:shy:0.8>（低头）嗯……<act:agreeing_yes>我答应你。
CameraChanged FULL_SHOT
ActionStarted agreeing_yes (Agreeing Yes)
SentenceQueued #0 "（低头）嗯…"
```

这说明两件事：缺词表时出现了真实的自造标签；补回词表后，其他格式约束仍可能失守。这里的“动作有效”指客户端产生执行事件，不表示已经人工观看并确认动画完整满足“低头再轻轻点头”的全部语义。

### 2.3 缓存与 token

| 样本 | prompt tokens | cached tokens | completion tokens | 首 token 延迟 |
| --- | ---: | ---: | ---: | ---: |
| A1，全量 | 5,966 | 0 | 28 | 1,317 ms |
| A2，精简 | 1,967 | 1,024 | 27 | 896 ms |
| A3，精简 | 2,026 | 1,024 | 28 | 969 ms |
| B1，全量 | 5,966 | 5,376 | 31 | 882 ms |

**缓存命中不等于被省略的词表仍在上下文里。** A2/A3 明明有缓存命中，仍然写错标签。B1 在新建本地上下文后仍有大量缓存命中，也说明本地 contextId 与服务端前缀缓存不是一回事。

这是 4 次排查样本，不是统计评测。A2 与 B1 的历史不同，也有随机采样因素，不能据此宣称补回词表能达到某个准确率，或全量一定比精简更快。缓存数量不提供具体缓存内容的逐段归属。

### 2.4 本次测试的边界

- 没有修改业务代码、模型配置、API Key，也没有重装 APK。
- 使用新上下文发送测试消息；结束后切回原上下文，并将镜头恢复到首条测试请求记录的中景机位。
- 通过请求、原始回复、解析后文本和执行事件判断链路行为，未作视觉动作质量或实际听感评测。
- 两个测试上下文保留，原上下文消息数仍为 10。

## 3. 代码中的原因与已有保护

以下行号以本次基线为准，后续改动应以函数名定位。

| 位置 | 现状 | 影响 |
| --- | --- | --- |
| [AvatarSession.kt](../avatar-orchestrator/src/main/java/com/neethu/orchestrator/session/AvatarSession.kt)，`buildRequestMessages`，约 1227–1259 行 | `includeIdentity` 只在首轮或身份块变化时成立；其余轮只发 `missionText` | 词表和人物设定从后续请求消失；历史只含 user/assistant，无法补回原 system |
| [OpenAiCompatibleLlmAdapter.kt](../avatar-ai-adapter/src/main/java/com/neethu/aiadapter/openai/OpenAiCompatibleLlmAdapter.kt)，约 51–87 行 | 每次 POST `/chat/completions`，发送本次 `messages`；当前路径无服务端会话引用 | 不能把本地“已发送”当作服务端“持续记住” |
| [PromptTexts.kt](../avatar-orchestrator/src/main/java/com/neethu/orchestrator/i18n/PromptTexts.kt)，`protocolReminder`，约 275、680 行 | 中英文均要求使用历史出现过的名字 | 首轮没有历史；后续只能复用少数已用动作；错误历史也成为示范 |
| [AvatarSession.kt](../avatar-orchestrator/src/main/java/com/neethu/orchestrator/session/AvatarSession.kt)，约 842 行 | `store.appendAssistant(replyBuffer.toString())` 写入原始回复 | 已被 `UnknownTag` 拒绝的控制串仍会进入下一轮输入；同回合工具循环也回放 raw 片段 |
| [LlmActionCatalog.kt](../app/src/main/java/com/neethu/aiavatar_sdk/LlmActionCatalog.kt)，`buildLlmActionCatalog` | 从文件名派生几百个英文下划线标签 | 模型要记住较长的资产名称，容易根据语义自行改写或套用前缀 |
| [AvatarSession.kt](../avatar-orchestrator/src/main/java/com/neethu/orchestrator/session/AvatarSession.kt)，`dispatchCues` / `dispatchCamera` | 动作查目录，镜头查映射，表情有别名与原生 morph 解析 | 已有一定白名单保护，A2/A3 的非法动作和镜头没有执行 |

百炼官方明确说明 Chat Completions / DashScope 多轮接口是无状态的，需要每次传入所需历史。该说明与当前代码及真机日志一致：[多轮对话文档](https://www.alibabacloud.com/help/en/model-studio/multi-round-conversation)。

### 3.1 名称需要统一

当前介绍文案、调试命令别名和 LLM 协议名字并不完全一致：

| 常见写法 | 当前实际情况 | 建议 |
| --- | --- | --- |
| `<cam:closeup>` | LLM 镜头映射使用 `close_up`；debug 命令可接受 `closeup`，不代表行内标签也接受 | 生成用标准名 `close_up`；若要兼容旧写法，显式登记别名 |
| `<act:wave>` | 默认资产目录有 `waving`、`aj_waving_gesture` 等；`wave` 不能仅凭 README 示例视为存在 | 从运行时注册表生成示例；或新增明确的语义别名映射 |
| `<emo:joy>` | 有英文情绪别名回退；协议主要公布 `happy` 等标准情绪 | 生成优先使用标准名，兼容层单独保留 |

当前完整协议的动作示例已经从目录选取，这一点应保留；但首个目录项未必总与示例意图最匹配，后续应选择已验证的问候动作。

### 3.2 当前过滤还不能保证所有控制串都不进入语音

已使用现有编译产物，在主机 JVM 上直接调用 `InlineTagExtractor.feed/flush` 做补充探测；不是手机 LLM 生成结果：

| 输入 | 解析后正文 | 结果 |
| --- | --- | --- |
| `<cam:full_body>你好` | `你好` | 能提取，再由下游拒绝未知名 |
| `<mood:happy>你好` | `<mood:happy>你好` | 新命名空间未识别，残留正文 |
| `<act:>你好` | `<act:>你好` | 空参数未识别，残留正文 |
| 分块输入 `<act:` + 70 个 `x`，再输入 `>你好` | 完整错误串残留正文 | 未闭合缓冲超过 64 字符后原样释放 |
| `5 < 3` | `5 < 3` | 普通比较表达式正确保留 |

这些残留正文按现有管线可能进入 TTS。不能直接删除所有尖括号内容，因为比较式、代码说明等也可能是正常文本。应区分“控制候选串”与普通正文。

## 4. P0：先修复请求与历史，保留行内单流

### 4.1 完整协议只构建一次，但每次请求都引用它

推荐请求结构：

```text
system：固定前言 + 人物设定 + 当前完整协议/词表 + 工具说明
user/assistant：经过标签清理的历史窗口
user：本轮视角/状态等动态信息 + 用户本轮输入
```

具体修改原则：

1. `pinnedProtocolText()` 继续承担本地文本缓存；它决定是否重新构建协议，不决定是否发送。
2. `/chat/completions` 的每次请求，都附带当前有效的完整 system；同一回合的工具后续请求也保留。
3. 保持单条 system，沿用项目已有的厂商兼容方式。
4. 模型、场景、能力开关和工具变化时更新目录；裁剪历史时始终保留协议。
5. 人设也应随请求保留，否则修复标签后仍可能出现角色漂移。
6. 中英文提醒删除“只在开头提供过”“只使用历史中出现过的名字”，改成“以本次请求的允许清单为准”。

示意代码仅表达策略，不是本次已提交的实现：

```kotlin
val systemText = listOf(missionText, systemPrompt.trim(), pinnedProtocolText().orEmpty())
    .filter { it.isNotBlank() }
    .joinToString("\n\n")
// 每个无状态请求都放入这条 system；只裁剪随后追加的对话历史。
```

### 4.2 用前缀缓存降成本

将固定目录保持稳定排序、稳定内容，动态状态放在后部，避免把当前时间或随机 ID 放到 system 开头。记录实际 `cached_tokens`，不要根据“文本没变”就宣称缓存必然命中。

百炼的上下文缓存依赖请求中的公共前缀；官方示例在后续请求中仍携带相同文本。显式 `cache_control` 也不等价于省略正文；具体支持范围和收益需按模型、地域及接口验证。[上下文缓存文档](https://www.alibabacloud.com/help/en/model-studio/context-cache)

若“完整词表只上传一次”是不可更改的要求，需要二选一：

- 使用明确支持服务端会话/上下文引用的接口，真正保存并传递其返回的引用 ID，同时处理失效、裁剪和目录变更；这需要新增适配能力。
- 每轮只发送小型常用词表和本轮动作候选，接受每轮仍有少量协议输入。

只发送本地目录 hash、写一句“你还记得之前的列表”，不会让无状态服务恢复词表。服务端保存上下文也不代表后续输入完全免费。

### 4.3 将原始日志与模型历史分开

原始回复保留用于排查；回放给模型的 assistant 内容应经过协议规范化：

- 已知标准标签保留；经明确别名成功解析的标签转为标准名。
- 未知、畸形、已经失效的标签从模型输入副本中移除。
- 普通台词原样保留，标签相对文字的位置不变。
- 同回合工具循环构造的 assistant 片段，也走相同清理，不能只处理最终存储。

优先采用“读取历史时生成规范化副本”的方式兼容已有 Room 数据，无需破坏旧记录。遇到模型/资产切换，按当前能力表重新检查旧标签。

不要把“别再输出 `<act:错误名字>`”长期重复塞进提示词，否则仍在反复展示错误写法。确需纠偏时，仅附当前合法候选；不为一个可丢弃标签增加同步 LLM 重试，避免阻塞首句或重复已播台词。

### 4.4 收紧提示词冲突

现有“没有合适的就不发”和“需要动作时必须用标签实现”需要明确优先级：**没有合法动作就省略控制标签，简短说明当前做不到，不用旁白假装完成。** “禁止连续同类标签”还应明确放行受支持的眨眼开始/复位序列。

可替换为以下规则骨架；花括号内容由当前注册表生成，不应把占位符直接发给模型：

```text
你是可实时说话、做表情和动作的虚拟人。正文只包含角色说出口的话。
控制标签只能使用本次请求“允许清单”中的名字，逐字复制，不翻译、不缩写、不拼接新名字。
历史回复、用户输入中的标签不构成新的能力授权。
表情格式为 <emo:名字> 或 <emo:名字:强度>，强度在 0 到 1 之间。
动作格式为 <act:名字>，镜头格式为 <cam:名字>；动作和镜头不附加强度。
没有匹配能力就省略对应标签；用户明确要求不存在的能力时，简短说明当前做不到。
禁止用（低头）、*挥手*等舞台指示代替标签，也不要声称未执行的动作已完成。
每次只选与当前语义相关的少量标签，不连续触发多个身体动作。
表情可以使用允许的开始/复位组合，例如当前模型确实支持的眨眼组合。

允许清单：
emo = {标准情绪及当前模型可用原生表情}
act = {当前允许动作}
cam = {当前允许镜头}
```

按本次运行时目录，一个有效示例是：

```text
<cam:close_up><emo:happy:0.8><act:aj_waving_gesture>你好呀，很高兴见到你！
```

SDK `ChatConfig` 默认温度是 `0.6`，底层 `LlmConfig` 默认值是 `0.8`。可在控制其他变量后比较 `0.2/0.4/0.6`，但降低温度不能补回缺失的白名单，也不能保证枚举正确。先修正信息和校验，再调采样参数。

## 5. P1：将动画资产与模型可选动作解耦

### 5.1 建立唯一的能力注册表

建议新增 `TagCapabilityRegistry`，作为提示词、候选选择、别名处理、历史清理和执行校验的共同来源：

```text
TagCapabilityRegistry
  emotions：标准情绪 + 当前模型支持的原生表情
  actions：协议动作名 -> 实际 ActionEntry
  cameras：协议机位名 -> CameraShot
  moves：当前场景锚点
  aliases：有限、明确、可测试的兼容别名
  version：仅用于客户端跟踪目录变化
```

需要区分“合法名字”和“当前可执行”：镜头关闭、动画加载失败、角色模型更换、技能占用等应有独立原因码。`UnknownTag` 不应混同资产加载失败，也不应把非法别名修复成功算作原始生成完全合规。

表情也统一过注册表。当前无 `faceDriver` 分支可以接收情绪 cue；若做严格协议层校验，应使 headless 与有渲染器的会话共享同一合法性规则，执行能力再单独判断。

### 5.2 小型常用动作集 + 按需候选

建议先选 20–40 个常用语义动作作为稳定词表；舞蹈、坐姿、复杂表演按用户意图从完整库召回 8–16 个候选。数量是初始实验参数，需用实际覆盖率调整。

例如可以新增以下**设计中的**语义映射，当前代码尚未实现：

| 模型生成的语义名 | 可选底层资产 tag | 意义 |
| --- | --- | --- |
| `wave` | `aj_waving_gesture` | 问候挥手 |
| `nod` | `nodding_head_yes` | 点头 |
| `bow` | `quick_informal_bow` | 简短鞠躬 |

映射目标需实际观看验证，尤其要区分轻点头、强烈点头、鞠躬。不能用任意相似资产“修复”用户意图。

候选选择可以先用本地中英文关键词和人工分类，不增加第二次 LLM 调用；候选不足时省略动作或扩展候选集。上一轮合法动作应作为候选来源，以支持“再做一次”。完整动画库仍可用于手动播放和检索。

动态候选可作为每轮 system 固定块后的能力段，并保持只有一条 system；把它放在稳定前缀之后。客户端必须执行同一份候选范围，防止“提示词只允许 10 个，执行层却放行全部”形成语义不一致。动态部分的缓存收益单独测量。

### 5.3 有限别名，不无限猜测

保留现有中英文情绪别名，但记录 `raw → canonical`。可对明确的兼容写法登记 `closeup → close_up` 等映射，前提是目标仍在本轮允许范围内。

不要将任意未知动作通过编辑距离或模糊语义直接映射为“最像的动画”。相似名称可能有方向、幅度和动作类型差异。未知动作默认不执行，后台日志记录原因；正文继续流转。

### 5.4 加固流式控制串隔离

推荐状态机：`TEXT → CONTROL_CANDIDATE → VALIDATE → EMIT/DROP`。

1. 正文只将完整确认的文本片段交给断句器。
2. 已识别控制前缀进入有界缓冲；超长时进入丢弃状态，而不是原样流入 TTS。丢弃状态需要明确的结束和恢复边界，避免后续正文无限丢失。
3. 空值、错误参数、新的 `name:value` 式控制候选均记录协议错误；不自动执行未知命名空间。
4. 正常比较式如 `5 < 3` 应保留。若产品允许讲解标签或代码，需要独立的转义/展示规则，不能用“删除所有尖括号”处理。
5. 断流、取消、flush、跨任意 chunk 切分都应得到一致结果，下一回合必须清空解析状态。
6. 对“（低头）”等舞台指示使用有限规则或独立文本审查策略；不要全删括号内容，正常口语也可能包含括号。严格分离对白和控制的结构化协议是更长期的方案。

这些校验只需要本地处理，不增加模型往返。它们保证控制语法不会误入执行或语音通道，但无法阻止模型在普通台词里作出错误描述，后者仍需质量评测。

## 6. P2：需要更强保证时，仍可保留单 LLM、单流

### 6.1 托管模型：严格 JSON Schema + 枚举

仅要求“输出 JSON”，或使用 `json_object`，只解决 JSON 格式，不保证动作值来自白名单。必须把允许值写入 `enum`，并使用目标接口真正支持的严格 schema 模式。

一种可选的新协议形态：

```json
{
  "segments": [
    {
      "text": "你好呀！",
      "emotion": "happy",
      "intensity": 0.8,
      "action": "aj_waving_gesture",
      "camera": "close_up"
    }
  ]
}
```

Schema 的属性片段示意，完整实现还需补齐根对象、数组、必填项等：

```json
{
  "action": {
    "type": ["string", "null"],
    "enum": [null, "aj_waving_gesture", "agreeing_yes"]
  },
  "camera": {
    "type": ["string", "null"],
    "enum": [null, "close_up", "full_shot"]
  }
}
```

关键要求：

- 枚举从本轮注册表生成；`null` 表示不触发。标准情绪、原生表情、强度也应有约束。
- 结构流不能直接喂给现有 TTS。需要增量 JSON 解析，收到一个完整、校验通过的 segment 后，按序派发 cue 和台词；不用等待整篇响应结束。
- `text` 字段仍可能包含旁白或标签字符串，枚举约束不会自动保证台词质量，仍需正文清理。
- 处理拒绝、截断、无完整 segment、网络中断等情况。已播部分不能通过整段重试再次播放。
- 验证结构化输出与现有工具调用、流式传输的组合兼容性。不能在不改解析链路时直接加 `response_format`。

百炼官方文档区分 JSON Object 与 JSON Schema，并列出 `enum` 支持；其具体 JSON Schema 支持清单包含 Qwen3.8-Flash 系列，但同页概览表仍有较旧的限制描述。应对实际地域、模型 ID 和端点做能力探测，本次未发送 schema 探测请求。[结构化输出文档](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output)

### 6.2 必须保留原始行内格式：约束语法

若自部署推理服务支持 grammar/regex 约束，可以由注册表编译合法标签语法，同时允许普通对白。正文分支必须排除未经转义的控制起始符，不能使用一个可以吞掉任意 `<act:任意名字>` 的宽泛分支，否则等于绕开约束。

vLLM 官方提供结构化输出、正则和语法约束能力，可作为自部署方向参考；是否能承载完整的对白与标签混合语法仍需原型验证。[vLLM Structured Outputs](https://docs.vllm.ai/en/latest/features/structured_outputs/)

不要把简单 `logit_bias` 当作标签枚举约束：动作名通常跨多个 token，所需限制还取决于当前是否正在生成标签。

## 7. 建议实施顺序与改动位置

| 优先级 | 改动 | 主要位置 | 完成条件 |
| --- | --- | --- | --- |
| P0-1 | 无状态请求每轮携带协议与人设 | `AvatarSession.buildRequestMessages`；`PromptTexts` 中英文提醒 | 首轮、后续轮、工具后续轮都能读到有效目录 |
| P0-2 | 规范化历史回放 | `AvatarSession` 请求构建及工具循环；历史投影辅助类 | 错误标签保留在审计数据中，但不再喂给模型 |
| P0-3 | 修订测试与说明 | `AvatarSessionProtocolPinTest`、i18n 测试、README 与调试说明 | 不再把“后续省略词表”当作正确行为 |
| P1-1 | 统一能力注册表与事件原因 | `SystemPromptAssembler`、`dispatchCues`、`dispatchCamera`、`GestureDriver` | 公布、兼容、校验、执行共享同源数据 |
| P1-2 | 加固控制串解析 | `InlineTagExtractor` / `TagExtractor` | 畸形控制串不流入 TTS，普通文本不被误删 |
| P1-3 | 常用语义动作与候选选择 | `LlmActionCatalog`、请求能力段 | 更短的词表仍覆盖用户动作需求 |
| P2 | 可选受约束输出模式 | `LlmConfig`、LLM adapter、增量解码层 | 单流结构化片段可校验、可顺序播放，失败可控 |

先落地 P0 并用相同测试集比较，再引入 P1；如果业务要求严格生成枚举，P2 可单独做能力验证。为本次问题直接引入第二个“纠错 LLM”会增加时延与复杂度，目前没有必要。

## 8. 验证与验收

### 8.1 已执行的现有测试

```bash
./gradlew :avatar-ai-adapter:test --tests '*InlineTagExtractorTest' \
  :avatar-orchestrator:testDebugUnitTest \
  --tests '*AvatarSessionProtocolPinTest' \
  --tests '*AvatarSessionTagsTest' \
  --tests '*SystemPromptAssemblerTest'
```

结果：`BUILD SUCCESSFUL`，共 **64 项通过，0 失败、0 错误、0 跳过**。

| 测试类 | 数量 |
| --- | ---: |
| `InlineTagExtractorTest` | 33 |
| `AvatarSessionProtocolPinTest` | 9 |
| `AvatarSessionTagsTest` | 16 |
| `SystemPromptAssemblerTest` | 6 |

测试通过不能证明当前设计正确。例如 `later turns carry only the preamble` 正在断言第二轮没有人设和目录；实施 P0 时应改成验证每轮都包含当前协议，而不是简单删除此项覆盖。

### 8.2 修改后需要补充的回归

- 第 2、10、30 轮，以及历史被裁剪后，仍能选择首轮没有用过的合法动作。
- 原始回复含 `full_body`、`aj_bowing_head` 时，下一请求的 assistant 历史中没有这两条控制串。
- 切换 VRM、关闭镜头、移除动作、改变场景锚点后，提示词、校验和历史回放同步更新。
- 已知标准名、有限别名、未知名、禁用能力、资源失败分别有可观察结果。
- 复用本次解析探测案例，增加每个字符位置的流式切分、异常截断、空参数、超长参数、正常比较式。
- 眨眼开始/复位不被节流误伤；连续动作不会相互抢占到全部只播最后一个。
- 正常对白、舞台指示、标签讲解文本分别验收；不靠一律删括号让测试通过。

### 8.3 真机评测建议

至少准备 100 条固定输入，覆盖问候、点头、害羞、舞蹈、全身镜头、原生表情、无此能力、重复上次动作；另做跨历史窗口的长对话。对同一模型、采样参数、资产集、工具开关，比较“现状 / P0 / P0+P1”，每组多次独立运行。

| 指标 | 定义或用途 |
| --- | --- |
| 原始非法标签率 | 原始候选中不符合标准清单的比例；别名修复前计数 |
| 别名修复率 | 经有限兼容映射恢复的比例，独立于原始合规率 |
| 非法执行数 | 非法 cue 实际进入执行器的数量，工程目标为 0 |
| 控制串泄漏数 | 控制串进入断句/TTS 的数量，定义好的控制语法测试集目标为 0 |
| 动作满足率 | 明确要求动作时，是否触发语义合适的动作，防止全部省略标签 |
| 执行失败率 | 合法标签但资源、开关、运行状态阻止执行的比例 |
| 延迟 | 首 token、首句开始播放的 p50/p95，分开统计 |
| 成本 | 实际 input/cached/output tokens；按实际厂商计费口径估算 |

上述目标是待验收标准，不是本次四条样本已经达到的结果。

## 9. 真机复现命令

应用已自带 Intent 调试入口，无需模拟点击。应在新测试上下文执行，等待每条回复出现 `TurnCompleted` 后再发下一条。

```bash
# 先记录原上下文和模型配置。
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd contexts
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd chat_state

# 新建测试上下文。
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd new_context

adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity \
  --es ai_cmd send_chat \
  --es ai_arg '请开心地向我挥挥手打个招呼，镜头给面部特写。只说一句简短的中文。'

# 等上一条 TurnCompleted 后再执行。
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity \
  --es ai_cmd send_chat \
  --es ai_arg '现在请害羞地低头，再轻轻点头表示同意，镜头拉到全身。只说一句简短的中文。'

adb logcat -d -s AIDebug AvatarSession LlmPrompt GestureDriver
```

本次完整筛选日志及临时 JVM 探测文件保存在 `/tmp/aiavatar-tag-audit-20261018/`，不加入仓库；关键证据已摘录在本文中。临时目录可能被系统清理。本文可独立作为后续实施与验收依据。


## 10. P1 实施记录（2026-10-18）

### 10.1 能力注册表与执行结果

新增 `avatar-orchestrator/.../tag/TagCapabilityRegistry.kt`，供协议生成、历史投影、有限别名和流式执行共用。注册表包括标准情绪、当前原生表情、语义动作到 `ActionEntry` 的映射、镜头、场景锚点及客户端版本。标准情绪在 headless 会话中仍先做相同合法性校验，实际无面部驱动时另报不可执行。

新增可观察事件：

- `TagAliasResolved(kind, raw, canonical)`：记录有限兼容映射，含原资产 tag→语义短名、`closeup→close_up`、既有情绪别名和误附的非表情强度参数。
- `TagRejected(kind, raw, reason)`：原因包括 `UNKNOWN_NAME`、`NOT_ALLOWED_THIS_TURN`、`DISABLED`、`UNAVAILABLE`、`RESOURCE_LOAD_FAILED`、`BUSY`、`MALFORMED`。
- 原有 `UnknownTag` 保留，仅对未知名/畸形控制串兼容上报；资源失败、候选越界和忙碌不计为未知名。Demo 的 `AIDebug` 日志已接入新事件。

一轮最多启动一个行内身体动作，后续动作报告 `BUSY`，避免连续控制串使前一个动作立即被抢占。眨眼开始/复位仍走表情车道，不受此限制。技能可通过 `AvatarSkill.occupiesBody` 声明身体车道占用，猜拳和模仿技能已接入。

### 10.2 24 个常用语义动作与最多 12 个候选

`ActionCandidateSelector` 对完整资产目录进行本地选择，不增加 LLM 请求。内置 24 个常用语义映射（只有目标资产存在才公布），例如：

| 生成名 | 精确资产 tag |
| --- | --- |
| `wave` | `aj_waving_gesture` |
| `nod` | `nodding_head_yes` |
| `bow` | `quick_informal_bow` |
| `shake_head` | `shaking_head_no_thoughtfully` |
| `shrug` | `shoulder_shrug` |

舞蹈、坐姿/起身、演奏/唱歌、运动通过中英文关键词分类召回，另支持英文资产关键词检索，按分数和 tag 稳定排序；额外候选最多 12 个，无匹配则不填充任意动作。上一条**成功执行**的动作可进入下一轮候选，以支持“再做一次”，清空会话时重置；原始审计历史中的失败动作不会因此获得权限。小型自定义目录（≤40 项）保留其显式名字，完整库仍供手动/技能使用。

动态候选位于常用协议和工具说明之后，保持单条 system。每轮开始冻结公布范围；流中新增资产等待下一轮公布，移除/替换资产立即撤销旧映射。执行层只允许本轮公布的动作及明确登记的兼容名字。没有编辑距离或模糊语义修复。

已用 APK 内动画文件验证 24 个映射的目标身份全部存在；这不代表已人工观看确认每段动画的幅度、姿态和质量。特别是轻点头与鞠躬，仍应真机观看验收。

### 10.3 控制串隔离与有限舞台指示过滤

在 P0 已有的有界缓冲/丢弃状态上，移除畸形参数的宽容执行回退。空参数、错误参数和未知 `name:value` 命名空间只产生 `InvalidControl`；超长控制串不会原样释放到 TTS，恢复边界为 `>`、换行或新 `<`。已知名字的未知值继续由注册表验证。

保留既有明确兼容：合法数值的非表情尾部强度可被丢弃，但作为兼容事件单独记录。普通比较式 `5 < 3`、`<hello>` 和普通括号台词保留。若需要原样展示控制标签，显示内容应使用转义形式（如 `&lt;act:wave&gt;`），直接书写控制串仍属于控制通道。

新增 `StageDirectionFilter`，仅移除固定表中完整、精确匹配的括号或星号舞台指示，例如 `（低头）`、`*挥手*`、`(nods)`；`（真的）` 和其他正常台词不受影响。它有独立的短前缀缓冲，支持任意 chunk 切分、flush 与 reset。这是有限规则，不保证识别所有自然语言旁白，也不判断“我已经做完了”等普通台词是否属实。

### 10.4 自动化验证与产物

执行：

```bash
./gradlew :avatar-ai-adapter:test :avatar-orchestrator:testDebugUnitTest \
  :app:testDebugUnitTest :app:assembleDebug --console=plain
```

结果：**BUILD SUCCESSFUL；967 项通过，0 失败、0 错误、0 跳过**（adapter 134、orchestrator 539、app 294）。覆盖每个字符位置切分、畸形参数、超长控制恢复、有限舞台指示、原生表情变化、候选越界、别名规范化、资源失败、连续动作、运行中增删资产、重复动作和内置映射目标存在性。`git diff --check` 通过。

Debug APK：`app/build/outputs/apk/debug/AIAvatar-v1.2-debug.apk`。本次未安装 APK、未发送真机对话、未改模型/API Key；100 条固定输入的统计评测、动作视觉满足率和真实延迟仍按第 8 节真机验收。


## 11. P1 真机验收补记（2026-10-18）

已在 Redmi K20 Pro 安装并验证修复包。100 条固定请求全部收尾，112 个生成标签中未观察到非法标签或非法执行，控制串进入语音队列为 0；但明确动作/眨眼事件满足仅 **48/72**，其中不同场景固定集 **16/40**、后续连续动作 **32/32**。另有 **43 个回合没有台词**、**3 个 TTS 片段超时**，故不能判定整体交互验收通过。

验收期间修复中文舞种/完整资产名召回、混合情绪与眨眼序列、原生表情大小写别名统计。修复后 **970 项 JVM 回归 + 5 项 Android 真机确定性测试全部通过**，并用录像确认街舞、闪身步与左眼闭合/复位。VRM 切换后原生目录从 68 更新为 14，随后恢复原模型。最终恢复原上下文、模型、场景与长期记忆设置。

详见 [P1 真机验收记录](p1-device-acceptance-20261018.md)，其中包含逐条输入、原始/解析后回复、统计口径、失败样本、APK 指纹和恢复记录。第 10.4 节“未安装 APK”是先前实现阶段的记录，由本节追加后续真机结果，不代表当前状态。
