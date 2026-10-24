# 自然语言动作控制成功率：DeepSeek-flash 基准实测与修复实录

> 2026-10-25（凌晨）。任务：以 DeepSeek 官方 `deepseek-flash`（V4.1-Flash）为基准，
> 真机实测「大模型控制虚拟人动作」的自然语言成功率（走到椅子前坐下/走到床前躺下等），
> 按失败形态逐轮优化。测试设备 2c3769db（Redmi K20 Pro），场景 dream_bedroom_3
> （桃隐仙居，5 个坐/躺交互点 + 12 锚点），模型 4705（VRM0），Edge-TTS。

## 一、测试方法

固定 12 条自然语言指令（`run_suite.sh`，每条独立 new_context，等 TurnCompleted 后
采样 pose_state/walk_state + 全量日志）：

| 组 | 指令 | 正解通道 |
| --- | --- | --- |
| A1~A5 | 去床边坐下 / 上床躺下 / 去书桌前坐下 / 到茶席坐下 / 去修炼台打坐 | `interact_with(bed_sit/bed_lie/desk_seat/tea_seat/meditation_seat)` → SIT/LIE HOLDING |
| B1~B3 | 走到窗边 / 走到剑架前 / 回房间中央 | `move_to(window_view/sword_approach/spawn)` 或 `<move:>` 标签，WalkArrived |
| C1 | 站起来活动一下 | `stand_up`（需在坐姿后执行才有判定意义） |
| D1~D3 | 开心挥手 / 跳支舞 / 害羞低头+全身镜头 | `<act:>``<emo:>``<cam:>` 标签，无发明词 |
| E1 | 先去窗边看看，然后坐到床沿上 | move_to → interact_with 两轮工具 |

判定=工具/标签正确触发 **且** 物理终态正确（pose HOLDING / WalkArrived 到锚点）。

## 二、基线（修复前）：5/12

第一轮实测只有 **A1/A2/D1/D2/D3** 干净通过。表层失败全是 LLM 侧
（A5 move_to 误用、B3 标签撞 BUSY、E1 链中止），但根因在**渲染层走动引擎**：

### 2.1 走动蛇形绕行 → 极限环冻结（引擎核心 bug）

- 症状演进：腿速 0.19m/s（应 1.0）→ 逐秒采样发现**每秒位移 0.6~1.1m 全速但路径
  蛇形 3~5 倍** → waypoint 多段腿中途冻结 0.8m 永不到达（A3 第一现场）。
- 冻结会**污染跨测试全场**：`LocomotionDriver.walkTo` 的 ALREADY_WALKING 判定读
  引擎相位，冻结的 WALKING 让后续所有 move_to/interact_with/`<move:>` 全部
  静默被拒（A4/B1/B2/B3/E1 连环假失败）。
- **根因 1（朝向脱钩）**：`LocomotionEngine.headingRad` 是内部状态，靠
  `resyncHeadingRad` 手动同步；方案 C（提取回灌）下位移沿**真实 root 朝向**回灌、
  朝向修正却按**陈旧内部状态**计算——追 bearing 逐帧反向过冲 = 蛇形，极端成
  永不到达的极限环。
- **修复**：`update()` 新增 `rootHeadingRad` 参数（root 矩阵读出的 belly heading，
  带 VRM0 翻转符号），每帧对齐——与「位置以矩阵为权威」同一哲学，内部状态降级为
  缓存。单测：`root朝向读数是权威 外部旋转后自动对齐直线收敛` +
  `方案C背对目标起步 朝向同步后收敛不蛇形`（路径长 ≤ 直线 1.5×）。
- **根因 2（提取链断供）**：遥测（看门狗日志挂 `vrma[debugState()]`）抓到
  `baseExtractor=false`——走动中 base 槽被**不含提取器的剪辑**（手势/待机/停步
  淡入时序）顶掉，提取位移恒 0 而状态机照常 WALKING = 原地冻结。
- **修复**：①每帧自愈——`external && phase!=IDLE && !isExtractionHealthy()`
  即同帧重载步态（fade=0 直切；起步 crossfade 的 0.25s 由
  `isExtractionHealthy()` 放行步态 overlay 淡入不算断供）；②停滞看门狗兜底
  ——5s 净位移 <0.15m 强制重载步态+引擎重起步（2 次），仍停滞 force stop
  （交互链 30s awaitStandstill 不再整链静默 abort）。看门狗重试**必须直载步态**
  ——startWalk 的 sameGaitPlaying 短路 + stop 后 idle 自动续播会掩护死链
  （真机实锤：停滞 15s 跨两次引擎级重发无效）。

### 2.2 提示词矛盾（LLM 侧）

- toolsetBlock 写「行内标签是表演层；**工具是你获取信息用的**」——与
  move_to/interact_with 动作工具直接冲突 → A5「打坐」选了 move_to。
- `<move:锚点>` 段写「自己想去**或用户让你去**都用标签」——与 move_to 工具双通道
  打架 → B1 标签+工具同回合双发。
- 修复（zh+en 同步）：toolsetBlock 明确「位置和姿态同样必须函数调用，台词说
  『坐下了』身体不会动」；move 段改为「标签只用于自己想换位的即兴移动，用户让
  去/要坐躺一律用工具」；interact_with hint 补「打坐/跪/伏」词例 +「不调用就宣称
  已坐下是错的」；move_to hint 补「不调用就宣称已到达是错的」。
- 效果：A5 打坐此后四轮全对；B1 双发消失。

## 三、迭代修复（每轮失败→归因→修）

| 轮 | 结果 | 新暴露问题 → 修复 |
| --- | --- | --- |
| 基线 | 5/12 | 引擎蛇形/冻结 + 提示词矛盾（§二） |
| R1 | 9/12 | A2 WalkInterrupted=A1 冻结级联（看门狗已兜住恢复管线）；E1 模型把 `<interact_with>` 写成正文 → **伪工具调用文本检测**：`isPseudoToolCallText`（raw 上查 `<工具名`）命中走 required 静默重试 |
| R2 | 10/12 | E1 续答轮的伪调用没接住（检查限 round0 且查了 clean 文本——MALFORMED 伪标签已被提取器剥掉）→ 改查 **raw、任意轮** |
| R3 | 9/12 | A1 超时=装完 APK 立即发消息的冷启动竞态（套件加就绪等待）；A3/B2 遥测抓到 `baseExtractor=false` → **每帧自愈 + 看门狗确定性重载**（§2.1 根因 2） |
| R4 | 10/12 | **B2 反噬**：`<move:sword_approach>` 标签已成功起步，配套台词「这就去看看」又被承诺句检测命中 → required 重试 → DeepSeek 400「Thinking mode does not support this tool_choice」→ 好回合炸成 TurnFailed。修复：①`isActionCommitmentReply` 与本轮 embodimentDispatched（move/act 标签已分派）**互斥**；②required 重试包异常保险丝 |
| R5 | 11/12 | E1 悬空重试在 DeepSeek 上 required **必 400**（思考模式）→ 保险丝保住回合但恢复没发生 → 降级**不带 tool_choice 的静默普通重试**再给模型一次采样机会 |
| R6 | 12/12 | 全绿（下表） |

## 四、最终结果（R6，全部修复就位）

**12/12**。要点：

- A1~A5 全部 interact_with 正确 + SIT/LIE HOLDING（多段腿 2~4 段全到位）。
- B2 剑架 6 段腿（绕行路线）全部到达——基线时代同类路线必冻结。
- E1 复合指令 move_to+interact_with 双 native 调用（toolRounds=2）→ SIT HOLDING。
- D3 标签纪律：无能力动作（低头）正确省略并演 bashful，零发明词。
- 走速恢复 1.0m/s 口径（腿界弧线是 external 模式无对齐门的固有形态，可接受）。
- 稳定性压力测试（10 次坐/躺/走混合 + 8 次）：零停滞、零 abort、零自愈触发。

五轮关键指标变化：基线 5/12 → R1 9/12 → R2 10/12 → R4 10/12（不同失败集）→ R5 11/12 → **R6 12/12**。

## 五、改动清单

| 文件 | 改动 |
| --- | --- |
| `corelib/.../LocomotionEngine.kt` | `update()` 新增 `rootHeadingRad` 权威读数参数（NaN=旧语义，测试兼容） |
| `corelib/.../VrmaAnimationEngine.kt` | `isExtractionHealthy()`（base 带提取器或步态 overlay 淡入中）+ `debugState()`（停滞诊断快照） |
| `corelib/.../SoulLinkRenderer.kt` | 每帧提取链自愈 + 走动停滞看门狗（5s×2 重载→force stop）+ updateLocomotion 传 root heading |
| `corelib/.../stage/internal/AvatarRuntime.kt` | 同款 root heading + 自愈（团播） |
| `app/.../InteractionCoordinator.kt` | awaitStandstill 30s→45s（多段腿+一次停滞自愈的组合会顶满 30s） |
| `avatar-orchestrator/.../agent/TemplateToolCalls.kt` | `COMMITMENT_CUES`（这就去/这就坐/去坐下…12 条完整短语）+ `isActionCommitmentReply`（可互斥版）+ `isPseudoToolCallText`（raw 查 `<工具名`） |
| `avatar-orchestrator/.../session/AvatarSession.kt` | 悬空重试三命中（悬空衔接句/动作承诺句〔与标签分派互斥〕/伪调用文本〔raw、任意轮〕）+ required 重试异常保险丝 + 降级普通静默重试 + `embodimentDispatchedThisRound` 追踪 |
| `avatar-orchestrator/.../i18n/PromptTexts.kt` | toolsetBlock 语义修正 + move 段双通道消歧 + interact_with/move_to hint 加警告（zh+en） |

测试：LocomotionEngineTest +2（朝向权威/方案C收敛）、TemplateToolCallsTest +2
（承诺句/伪调用）、AvatarSessionAgentTest +2（伪调用 round0/续答轮 required 重试），
全量单测绿。**未提交**。

## 六、已知残余（留档）

1. **DeepSeek 思考模式不支持 `tool_choice:"required"`**（400）——悬空补救在其上
   恒走普通静默重试路径（多一次请求、~1s）；Qwen/硅基流动仍走 required。
   建议后续把「required 不支持」按 modelKey 缓存（同 toolsDisabledModels 模式）。
2. **waypoint 图拓扑**：sword→window 要绕 6 段腿（east_crossing 没连 north_aisle
   的边）——BFS 行为正确但绕路；改图要动 `dream_bedroom_3.anchors.json`（资产侧）。
3. **external 走动腿界弧线**：无对齐门（设门会螺旋）是 P2 设计取舍，腿界转弯
   走弧线、净速度略低于 1.0；观感自然，不动。
4. **C1 起身测试**须排在坐姿测试后才有判定意义（本轮脚本顺序缺陷，结果按
   前置状态判读）。
5. 走动中 act 手势 overlay 完成后的槽位时序（baseExtractor=false 的成因链）只被
   自愈兜住，未做单一根因收敛——若后续发现自愈频繁触发（`extraction dead` 日志），
   应回头查 handleOneShotCompletion 与 startWalk crossfade 的竞争。
