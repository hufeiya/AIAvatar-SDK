# 自主生命体 P0 真机验收记录

日期:2026-10-25 凌晨(真机时段 02:46~03:01,深夜恰好是作息表的严苛用例) · 代码:本提交 · 设备:无线 adb · 场景:dream_bedroom(森林梦境卧室,16 交互点)

## 验收结果(对照 docs/autonomy-feasibility.md §9)

### 1. 自主行为 ✅

放置不说话,FSM 自主决策链完整发生(autonomy_log 实录):

```
02:46:50 IDLE_STAND → LOOKING @rug_watch_tv (dwell)   ← 走到地毯看电视
02:47:29 LOOKING → LYING @bed_lie (dwell)             ← 走到床躺下
02:55:03 LYING → SITTING @desk_seat (external)        ← 外部 interact 后认领
02:57:57 SITTING → LYING @bed_lie (dwell)             ← 再次自主躺床
02:58:00 LYING → IDLE_STAND (frozen-stop)             ← 用户说话,停步让路
02:58:07 IDLE_STAND → LYING @bed_lie (unfroze)        ← 对话结束,解冻即活
```

- 走动/坐/躺/看四类状态全部出现,无穿模、无卡死(approach 链全部到达,字幕多条「已到达」)。
- **深夜作息生效**:凌晨 2 点时段 LYING 权重 ×5,两次自主决策都落床;躺卧 dwell 773s/1700s 落在深夜 ×1.5 加长区间(白天基线 8~20min);思考气泡文案全是深夜模板(「窗外是星星吗」「月亮好亮啊」「困了…再睡一会儿」)。
- 防抽搐上限:actionsLastHour 计数 1~3/12,频率克制。

### 2. 冲突仲裁 ✅

- **说话打断走动**:自主走动中 `send_chat` → 转移日志 `frozen-stop`,字幕面板完整留痕(「我:等等,先别走」→「🚶 走动已停止」);对话期间零新转移、坐/躺不强制起身。
- **解冻续跑**:回合结束 `unfroze` 即刻重新决策,不等 dwell。
- **外部指令续跑不回弹**:`walk_to desk_approach` + `interact desk_seat` 后,FSM 以 `LYING → SITTING @desk_seat (external)` 认领新位置,按坐姿 dwell 续跑,不弹回原状态。

### 3. 不定时问候 ✅

- **安静时段硬门**:凌晨 2:50 自然问候到点被拦(poll 重排,dueIn 持续后推)——23:00~08:00 静默成立;`autonomy_greet` 显式人令豁免硬门(深夜验收入口,设计如此)。
- **完整链路**:前 1.5s 思考气泡(内心版)→ `<emo:joy><act:wave>` 挥手微笑说出口(截图:眨眼+张嘴笑+挥手姿态,TTS「说话中」)→ 记忆回执落库:`0d0b7c1089e4 [事件] 我主动问候了用户:「刚才想起来你说过周末去猫咪咖啡馆时由用户请喝奶茶,后来怎么样了?」 (imp=5 src=greeting)`——内容含真实记忆事实(memoryStore 真机数据),不进对话历史。
- **冷却重置**:用户发消息后问候 dueIn 从 2422s 跳到 5148s(`onUserActivity` 生效);泊松调度 dueIn≈40min;每日上限 3。

### 4. 思考气泡 ✅

- **跟随**:站/坐/躺三姿势全部正确贴合头顶(躺姿 0.45m 抬高,不贴脸);尾巴两颗递减小圆点指向头部;人物背对相机时气泡仍跟头位(投影点在视锥内——期望行为;点出锥/背后由 ScreenProjector 返回 null 隐藏,JVM 对拍单测覆盖)。
- **内容**:状态转移触发(躺下→「困了…再睡一会儿」),记忆槽模板填充真机记忆事实(「想起ta说过周末去猫咪咖啡馆…」)。
- **帧率**:气泡显示中 FPS 60(初版每帧无条件重组拖到 24fps,修复:投影结果不动不写状态,静止零重组);无气泡零开销(不挂帧回调)。

### 5. 可观测 ✅

`ai_cmd autonomy_state`(状态/锚点/nextIn 倒计时/actionsLastHour/pending/问候调度/mounted)与 `autonomy_log`(最近 20 条转移+原因)输出完整决策轨迹,本记录全部数据来自这两条命令。另有 `autonomy on|off`、`autonomy_greet`、`autonomy_think` 驱动入口。

### 6. 回归 ✅

全仓单测 **1219 全绿**(新增 28:FSM 决策/仲裁/禁忌/限速/作息解析/问候调度/模板池 27 + 投影对拍 6 中并入计数);无会话(纯模型+场景)时自主行为照常(coordinator 直线走位兜底),多人对话等旧路径不受影响(自主默认只在交互点表非空的场景激活)。

## 实现落点(与设计文档 §7 对照)

| 层 | 交付 |
| --- | --- |
| corelib | `AvatarController.getHeadWorldPosition()` + `projectToScreen()`(SoulLinkRenderer 经 Filament camera 每帧现取 view/proj 矩阵)+ `ScreenProjector` 纯 JVM 对拍 |
| orchestrator | 新包 `autonomy/`:AutonomyDirector(FSM+作息+仲裁+禁忌+限速)、GreetingScheduler(泊松+硬门+日上限)、ThoughtComposer(zh/en 模板池)、`AutonomySchedule` 解析;AvatarSession.`writeAutonomyReceipt()` 回执;MemorySources.GREETING |
| app | AutonomyRig(装配+每拍仲裁+气泡流)、ThoughtBubbleOverlay(云朵 Canvas+帧跟随+死区跳重组)、InteractionCoordinator.`cancelChain()`(冻结让路)、设置三开关(自主/问候/气泡)、ai_cmd 命令族五条、`assets/autonomy/autonomy.schedule.json` |

## 偏差与决策记录

1. **forceDue 豁免安静门**:设计文档没写;真机验收在深夜做不了问候链,改为「自然问候硬门不动,显式 ai_cmd 人令豁免」——与「沉默是默认」不冲突(人令即用户在场)。
2. **cap 归位保留锚点**:超限站桩时人还站在原地,锚点保留(禁忌继续生效);坐/躺起身才清空。
3. **问候素材**:用 memoryStore 最近事实(event/commitment/preference 优先)替代 MemoryRetriever 语义检索——P0 零成本,LLM 增强默认关,与设计一致。
4. **气泡文本可能被顶栏按钮遮挡**:头像贴屏幕上缘的边缘情况,垂直钳位把气泡顶进按钮区——后续可加「顶钳时下移到头下方」,本期不做。
