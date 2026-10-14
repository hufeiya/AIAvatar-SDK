# 多虚拟人团播（Dance Show）可行性调研与落地方案

> 用途：回答「同屏 N 个虚拟人团播——挨个上场跳舞、齐舞收场、走位运镜编排，导演脚本可用 adb intent 驱动」是否可行、要动哪里、分几期落地。
> 生成时间：2026-10-14。证据来源：corelib stage 包 / avatar-orchestrator roundtable 包 / app multiavatar 包三路源码核对（file:line 均已核对），配合 docs/multi-avatar-feasibility.md（P0~P3 已真机验收）与 docs/ai-debug-intents.md 的既有结论。
> 关联文档：docs/multi-avatar-feasibility.md（多虚拟人四期基座，本文的一切前提）、docs/ai-debug-intents.md（单人 adb 协议）、docs/locomotion-feasibility.md（走动=上场退场的基座）。
> 范围裁剪（按当前决策）：特效（风/雪/火/粒子）与真实直播推流**不在本期**；先做「确定性节目单团播」最小闭环。

---

## 一、结论速览

**总判定：可行，且是纯「编排层」组装。** 多虚拟人四期（渲染多实例 / 会话多实例+路由 / 导演制圆桌 / 空间智能）已经把团播需要的全部基座建完：每演员 VRMA 播放、播完自动回待机、走位/转向、GROUP_SHOT 运镜、注视接力、TTS 串场词、adb 调试通道——全部现成。本期唯一要写的新东西是一个**确定性的演出导演（ShowDirector）**：消费一张节目单，按事件+超时兜底推进幕次。逐项判定：

| 需求项 | 现状 | 差距 | 结论 |
|---|---|---|---|
| N 人同台各自独立 | Stage/AvatarRuntime 多实例已验收，双人 50~60fps@SD855（multi-avatar §十一） | 槽位在 demo 里硬编码 A/B（MultiAvatarActivity.kt:103） | **现成**；demo 小改支持第 3 人（可选） |
| 挨个独舞（一次性 VRMA） | `AvatarHandle.playVrmaAnimation(loop=false)` + 播完自动回待机（VrmaAnimationEngine.kt:241-268 接管语义） | **无「播完事件」**——`isOneShotActive()` 翻沿 `AvatarRuntime.postAnimation` 每帧已在算（AvatarRuntime.kt:405），只是没暴露 | **加 ~20 行回调**（本期唯一 corelib 增量） |
| 齐舞（全员同一曲目） | VRMA 舞台级缓存（VrmaClipCache.kt，62 行）：N 人同曲只解析一次；同帧 play 起播相位天然一致 | 无 | **现成** |
| 走位上场/退场 | walkTo/turnTo/walkFinished.avatarId/锚点表 stage_a·stage_b 全齐（P3 已验收） | 前排表演位锚点未定义 | 锚点 JSON 加 3 行 |
| 运镜编排 | CameraShot 目标参数化 + GROUP_SHOT 双人同框（P3） | 舞蹈大动作的取景调参未实测 | 现成，真机调参项 |
| 观众席应援（注视/鼓掌） | `h.lookAt(other)` 注视（AvatarHandle.kt:132-135）；04_交流手势 有 5 个鼓掌 VRMA | 无编排接线 | 现成，导演接线即可 |
| 串场词（TTS） | `RoundtableSession.say(slot, text)`（RoundtableSession.kt:176）全链现成（TTS→口型→字幕） | 无 | **现成**（P1 接入） |
| 背景音乐 | **全仓库零音乐播放**（grep MediaPlayer/SoundPool 无命中） | demo 加 MediaPlayer 封装 | 小件，demo 层（P1） |
| adb 导演 | STAGE_CMD 广播通道（MultiAvatarActivity.kt:608/649），play/gaze/walk/cam 命令已能**逐条手工导演** | 缺节目单级的批量下发与 in-app 时序 | 新增 `dance` 命令族 |
| AI 参与 | 圆桌导演两档（规则/LLM）已验收 | —— | **团播不需要每 avatar 挂 AI**（§4.1），P2 才用 LLM 做点播理解 |

**推荐架构一句话**：demo（`multiavatar` 包）新增 `ShowScript`（节目单 JSON，kotlinx.serialization）+ `ShowDirector`（纯 JVM 状态机：事件驱动、每步超时兜底、防卡场）+ `DanceShowController`（把幕次落到 AvatarHandle/RoundtableSession.say/MediaPlayer）；corelib 只补一个 `onVrmaFinished` 事件（镜像 walkFinished 的既有模式）；adb 走既有 STAGE_CMD 加 `dance` 命令族（`start/stop/next/status/load`）。**不动单人路径、不动 orchestrator、不新建仓库。**

**三期路线**：P0 确定性团播最小闭环（挨个独舞+齐舞+运镜编排+应援，~2~3 人日）→ P1 音乐+走位+串场词（~2~3 人日）→ P2 LLM 导播+弹幕点播模拟（~3~4 人日）。总量预估 **7~10 人日**。

---

## 二、目标场景定义（作为验收口径）

**必须支持（v1 目标 2~3 人，架构不设上限）：**

1. adb 一条命令跑完整场演出：`dance start` → 开场同框 → A 独舞（B/C 观众席注视+鼓掌）→ B 独舞 →（C 独舞，可选）→ 全员齐舞 → 谢幕鞠躬 → 结束回待机。
2. 节目单 = 一份 JSON（`ShowScript`）：声明每幕的表演者/曲目/机位/串场词/时长；内置一份默认节目单，零参数可跑。
3. 非表演者有「观众席行为」：注视当前表演者（头部+身体朝向），穿插鼓掌/应援一次性动作，不与表演者抢焦点。
4. 相机编排是节目单的一部分：独舞 FULL/LONG_SHOT 锚定表演者，转场与齐舞 GROUP_SHOT 同框，谢幕同框。
5. 演出与聊天/圆桌共存不冲突：演出期间圆桌停题、发言令牌清空（两套导演互斥）；单虚拟人路径（MainActivity/AiDebug）零改动。
6. P1：背景音乐随开演起、停演止；表演者从观众席走位上场、演完走回队列；幕间串场词由表演者自己 TTS 说出（口型/字幕全链复用）。
7. P2：LLM 导播（点播理解：文本含人名/舞名 → 动态插幕）+ 串场词润色；LLM 输出走白名单校验+失败降级固定台本。

**明确不做（非目标，写进边界防发散）：**

- 真实直播推流/弹幕平台接入（OBS/直播伴侣/弹幕 SDK）——团播 demo 与推流完全解耦，弹幕用 `dance dm <文本>` 模拟；
- 动作与音乐的 BPM 级对齐——动作库不是按 BPM 制作（时长 4~15s 混杂），音乐定位为氛围轨，「挨个跳」本来就是团播的视觉主体；
- 粒子/天气/灯光特效——另立后续期（候选形态见 §9）；
- >3 人同台——性能（弹簧骨 N 倍）与手机竖屏取景注意力先成为瓶颈（同 multi-avatar §8 风险 8 的结论）；
- 动作与音乐卡点、多人边跳边唱混音。

---

## 三、现状盘点（逐项证据）

### 3.1 表演能力：corelib 已齐，只缺一个「播完事件」

- **每演员 VRMA 全套 API**（AvatarHandle.kt:99-115）：`loadVrmaAnimation` → `playVrmaAnimation(loop, speed, fade)` → `stopVrmaAnimation(fade)` → `getVrmaAnimationDuration()` → `setVrmaIdleAnimation`。独舞=one-shot（loop=false），齐舞=loop + 定长 stop，都在现有 API 面内。
- **播完自动回待机已内建**：`VrmaAnimationEngine.setIdleAnimation` 的接管语义（VrmaAnimationEngine.kt:241-268）——one-shot 播完自然回到 idle 循环，不会停在尾帧或 T-pose；`play()` 置 `idleTakeover=false`（:325-330），`isOneShotActive()` = `isPlaying && !idleTakeover && base != null`（:387）。**这正是「独舞结束」的可观测信号，翻沿检测的输入现成**。
- **缺口（唯一 corelib 增量）**：翻沿目前只被 `AvatarRuntime.postAnimation` 内部消费（:400-410，给模仿引擎让路用），没有对外事件。补法完全镜像既有 `onWalkFinished`（AvatarRuntime.kt:134 回调字段 → AvatarHandle.kt:53-68 SharedFlow 暴露）的模式，~20 行。
- **齐舞免费**：`VrmaClipCache`（corelib/stage/internal/VrmaClipCache.kt）已让 N 个 runtime 共享同一份解析产物；N 个引擎各自持有播放头，同帧 `playVrmaAnimation` 起播即相位对齐。
- **变速抓手**：`setPlaybackSpeed`（VrmaAnimationEngine.kt:394-396）可运行时改 base 槽速度——P2 若要「卡拍微调」有现成缝（本期不动）。
- **动作资产充足**：`assets/animations/08_舞蹈与表演/` 82 个（breakdance/hiphop/salsa/samba/机械舞/乐器表演…，含 `- Loop` 循环变体），全库 318 个分 11 类；`assets/vrms/` 3 个模型（10.vrm=VRM1.0+MToon、47058…vrm=VRM0.x+MToon v0、FreeTestCharacterAsuna_1024.vrm=轻量未验证，见 §6）。
- **鼓掌素材**：04_交流手势 有 Sitting Ethusiastic Clap 等 5 个鼓掌变体（多为坐姿起手，站姿应援动作需实测挑选，见 §8 风险 5）。

### 3.2 空间与运镜：P3 已全部就位

- 走位：`walkTo`（锚点 BFS 多段腿）/`startWalk`（坐标）/`turnTo`（原地转向）/`stopWalk`，`walkFinished` 带 `avatarId`（AvatarHandle.kt:204-216）；锚点表 `home.anchors.json` 已有 `stage_a(-0.8,-4)/stage_b(0.8,-4)` 圆桌席位——团播加「前排表演位」锚点即可复用全链。
- 运镜：`stage.setCameraShot(shot, target)` 目标参数化 + `GROUP_SHOT`（pivot=头部均值、距离按最远头距 ×3.2，Stage.kt:845-869）已验收；`cam group` 已是 STAGE_CMD 现成命令（MultiAvatarActivity.kt:883）。
- 站位编排数学：`StageFormation`（orchestrator roundtable 包，纯 JVM+单测）是「开题走到对谈位」的实现——团播的「上场/归队」比它简单（单点 walkTo），不需要新数学。

### 3.3 声音：TTS 串场词免费，音乐是唯一空白

- 串场词：`RoundtableSession.say(slotId, text): Boolean`（RoundtableSession.kt:176）不进历史、直接走 TTS→断句→口型全链；app 侧 `StageChatRig.sayAsync`（StageChat.kt:453-458）已封装为异步入口。串场完成的信号=该槽 `AvatarEvent.TurnCompleted`（AvatarEvent.kt:39）。
- 字幕/说话人高亮：舞台事件合流 `StageEvent(slotId, event)` 已在 MultiAvatarActivity 接了字幕分道（P1 记录）——串场词自动带字幕。
- 音乐：**全仓库无任何音乐播放代码**（四模块 grep MediaPlayer/SoundPool/ExoPlayer 零命中）。Android `MediaPlayer` 从 assets/raw 播 MP3 是几十行的 demo 小件；与 TTS 的并存靠系统混音器天然支持（AudioTrack 多路并行，multi-avatar §3.2 已验证）。
- 音频焦点：全仓库零处理（既有现状）——团播是自家 app 前台播放，v1 不引入焦点管理。

### 3.4 adb 调试通道：逐条手工导演已经可行

STAGE_CMD 是 `am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "<命令>"` 的字符串命令路由（MultiAvatarActivity.kt:649 `StageCommandRouter`，:674 起巨型 when）。现有命令已能**外部脚本逐条驱动**一场团播：

```bash
adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "cam group"
adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "play A animations/08_舞蹈与表演/Bboy Hip Hop Variation One.vrma"
adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "gaze B A"   # B 注视 A
adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "cam full A"
```

这是**零代码的 P0 前置预演路径**（先真机手工串一遍验证素材与运镜），但有硬伤：adb 往返 100~300ms 抖动、PC 必须在场、时序靠人肉 sleep。**正式方案是节目单一次下发、in-app 导演按事件推进**（§4.5）；两者共用同一套底层命令语义。

### 3.5 demo 层的既有形态与待改点

- `MultiAvatarActivity`（977 行）：`Slots` 双槽硬编码（:103-143）、开场装配双人+场景+共享 idle（:94-100, :166-183, :254）、StageCommandRouter（:649）。团播作为该 Activity 的新节目形态**就地扩展**（不新建 Activity）：加 `dance` 命令族 + 极简 DancePanel。
- 第 3 人：`Slots` 改 Map<String, AvatarHandle?>（保留 A/B 语义不变），C 用 `load C <模型>` 按需加载（默认双人开演，C 可选——性能见 §6）。
- 与圆桌的互斥：圆桌 ACTIVE 时 `dance start` 应拒绝并提示先 `stop`；开演时主动 `stopTopic()` + interrupt 在途发言（P2 圆桌已有「开题先打断」的先例可镜像）。
- 单人路径（MainActivity + AiDebugExecutor）：零接触。

---

## 四、关键设计决策

### 4.1 每 avatar 都要接 AI 吗？——不，确定性导演是正解

团播的「挨个跳舞」是**固定程序**，LLM 在链路里只会引入三样东西：时延（1~3s/次）、不确定性（幻觉动作 id）、成本。与圆桌（P2 ConversationDirector）的本质区别：圆桌的难点是「下一句谁说」（需要语义判断），团播的难点是「下一个节目是什么」（节目单已写死）。因此：

- **P0/P1：零 LLM。** 导演是纯状态机，行为 100% 确定、可单测、可复演。
- **P2：LLM 只做两个窄口**——①点播理解（文本→{表演者, 曲目}，输出白名单校验）；②串场词润色（固定台本的措辞变体）。失败一律降级固定台本，观众无感（防失控三件套的思路沿用：白名单 + 校验失败降级 + 轮数/时长硬上限）。
- 虚拟人的「人格感」来自既有资产：不同 idle、不同串场词文案、表情系统，不需要活 LLM。

### 4.2 ShowDirector 放哪：demo（app），不进 lib

- 节目单/幕次/谢幕是**产品语义**不是渲染语义——corelib 的定位是「舞台与演员的原语」（Stage/AvatarHandle/运镜/走动），orchestrator 的定位是「AI 会话编排」。演出编排两头都不沾，先落 demo。
- 仓库先例：AiDebug（app 内纯 Kotlin + JVM 单测）——「app 内纯逻辑下沉单测」是被验证的模式。
- ShowDirector 写成**不依赖 Android 的纯 Kotlin 状态机**（输入=节目单+事件，输出=动作指令列表），指令由 demo 侧的 Controller 落地到 AvatarHandle——这样状态机全分支可 JVM 单测（复演、超时、中途 stop、事件乱序）。
- 抽出条件（什么情况下进 lib）：出现第二个消费方（例如未来把「演出编排」作为 SDK 能力发布，或特效期想复用幕次模型）。见 §9。

### 4.3 corelib 唯一增量：`onVrmaFinished` 事件

```kotlin
// AvatarRuntime（镜像 onWalkFinished，AvatarRuntime.kt:134 同款）
var onVrmaFinished: ((avatarId: String) -> Unit)? = null
// postAnimation（:400-410 处已有 oneShotActive 局部量）：
//   prevOneShotActive=true && oneShotActive=false 时触发（初始化 prev=false 不误触发）

// AvatarHandle（镜像 walkFinished 的 SharedFlow 模式，AvatarHandle.kt:53-68）
val vrmaFinished: SharedFlow<VrmaFinished>   // data class VrmaFinished(avatarId: String)
```

- 淡出到 idle 的过渡期 `isOneShotActive()` 仍为 true（引擎注释明确），所以事件在「视觉上回到 idle」的瞬间发出，语义正确。
- 单人路径 `AvatarController` 不暴露它（枚举零扩面）；walk/mimic/表情全链零接触。
- 导演侧仍配**超时兜底**（1.2×duration+1s），事件丢了也能推进——防卡场纪律（§4.6）。

### 4.4 节目单协议（ShowScript）

JSON + kotlinx.serialization（app 已依赖，app/build.gradle.kts:104）。解析失败=整单拒绝+原因行（调试通道回显），**不做容错续跑**——节目单是开发者手写的，早失败优于演一半。

```json
{
  "version": 1,
  "title": "开业团播",
  "music": "music/show.mp3",
  "acts": [
    { "type": "camera", "shot": "group" },
    { "type": "say",    "performer": "A", "text": "欢迎来到今晚的团播现场！" },
    { "type": "dance",  "performer": "A", "clip": "08_舞蹈与表演/Bboy Hip Hop Variation One", "camera": "full", "fans": ["clap"] },
    { "type": "dance",  "performer": "B", "clip": "08_舞蹈与表演/Female Hip Hop 'Running Man' Dancing", "camera": "full" },
    { "type": "wait",   "seconds": 1.5 },
    { "type": "group_dance", "clip": "08_舞蹈与表演/Doing The Shuffling Dance", "seconds": 12, "camera": "group" },
    { "type": "bow",    "camera": "group" }
  ]
}
```

| act 类型 | 字段 | 语义 |
|---|---|---|
| `dance` | performer, clip, camera?, fans?, line? | 独舞：line 串场（P1）→ 全员 gaze 表演者 → 表演者 one-shot 播放 → vrmaFinished/超时 → 观众席复位 |
| `group_dance` | clip, seconds, camera? | 齐舞：全员同帧 loop 播放，seconds 到点统一 stop 回 idle |
| `bow` | camera? | 谢幕：全员播一次性鞠躬（03_打招呼与礼仪/Quick Informal Bow）回 idle |
| `say` | performer, text | 串场词（P1 接 TTS；P0 打 log） |
| `move` | targets（锚点名/坐标）, camera? | 走位（P1）：walkFinished 驱动，15s 超时兜底（P3 既有纪律） |
| `camera` | shot, target? | 切机位（closeup/medium/full/long/over/follow/group） |
| `music` | action=start\|stop\|duck | 音乐（P1） |
| `wait` | seconds | 停顿 |

- `clip` 写类别目录下的文件名（可省 `.vrma`），运行时拼 `animations/<clip>` 并先过**曲目白名单**（扫描 08 目录生成，`dance list` 可查）——防手误也防 P2 的 LLM 幻觉路径名。
- `fans`（观众席动作）：`gaze`（默认恒开）/`clap`（随机挑 1~2 人播鼓掌 one-shot）/`sway`（观众席换轻快 idle 变体）。P0 只做 gaze+clap。
- 目录约定：节目单放 `filesDir/shows/<name>.json`（adb push 进来）或 assets `shows/`（内置默认单）。

### 4.5 adb 导演通道：STAGE_CMD 加 `dance` 命令族

沿用 STAGE_CMD 的「一条命令一行回显（OK/FAIL+原因）到 logcat `AIDebug`」协议：

| 命令 | 作用 |
|---|---|
| `dance` / `dance status` | 快照：state(IDLE/RUNNING/DONE/STOPPED)/第几幕/当前 act/各演员状态/剩余时长 |
| `dance start [名]` | 开演（缺省=内置默认节目单；圆桌 ACTIVE 时拒绝并提示） |
| `dance load <file>` | 从 filesDir/shows/ 读 JSON 并校验（回显幕数与总时长估计） |
| `dance stop` | 停演三清：音乐停/说话 interrupt/走动停/一次性动作停/观众席复位 |
| `dance next` | 跳过当前幕（导演直接推进到下一 act） |
| `dance list` | 可用曲目白名单（08 目录扫描）+ 已加载节目单的幕次列表 |
| `dance dm <文本>` | P2：模拟一条弹幕进点播路由 |

intent 形态与转义纪律（含空格的中文路径必须内层引号，multi-avatar P1 的坑）：

```bash
PKG=com.neethu.aiavatar_sdk
# 整条引号包法：外层双引号给本地 shell，内层单引号给设备 shell
adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD \
    --es cmd "'dance start default'"
# 自定义节目单：先 push 再加载
adb push myshow.json /storage/emulated/0/Android/data/$PKG/files/shows/
adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "dance load myshow.json"
adb logcat -d -s AIDebug     # 读回 OK/FAIL
```

### 4.6 时序模型：事件驱动 + 每步超时兜底

导演跑在 rig scope（Main dispatcher），幕次推进是一个顺序协程；每个「等事件」都包一层 `withTimeout`：

| 等什么 | 事件源 | 兜底超时 |
|---|---|---|
| 独舞结束 | `vrmaFinished(performer)` | 1.2×duration+1s（duration=load 后 `getVrmaAnimationDuration()`） |
| 齐舞到点 | `delay(seconds)` | —（本身就是时间驱动） |
| 串场词说完 | 该槽 `TurnCompleted` | 8s |
| 走位到位（P1） | `walkFinished(arrived=true)` | 15s（P3 编排同款；超时就地开演） |
| 鼓掌动作 | 不等（并行发射，观众席行为不阻塞幕次） | — |

- 超时不是异常是**推进信号**：打 `AIDebug` 警告行后继续下一幕——演出宁可瑕疵不可卡死。
- `dance stop`/Activity 销毁：协程取消 + 状态复位（引擎 one-shot `stopVrmaAnimation(fade)` 回 idle、观众席 lookAtCamera、音乐 stop）。
- 单测注意（既有坑）：导演的 `delay` 兜底会被 `advanceUntilIdle` 推过——超时分支用例用 `runCurrent`（multi-avatar P3 单测教训）。

### 4.7 舞蹈位移漂移：P0 就要面对的真问题

动作库实测有净位移（记忆 vrma-hips-displacement：步态剪辑带 1.49m/循环烘焙位移；「看似原地」的素材也未必净位移为零）。独舞 one-shot 是**原始播放**（不走位移提取），位移会真实累积到 root。对策三层：

1. **曲目白名单人工筛**：默认节目单只收真机实测净位移小的曲目（P0 验收时逐个 `screenshot` 前后对比定名单）——这就是白名单存在的产品理由；
2. **幕间回位**：独舞结束后表演者 `turnTo`+`moveAvatar` snap 回自己的队列 mark（P0 最简）；P1 升级为 `walkTo` 走回（走位本身成为演出语言）；
3. **齐舞选 loop 变体**：`- Loop` 命名的剪辑按循环设计，净位移天然小。

### 4.8 与圆桌/聊天共存的仲裁

- 两导演互斥：`dance start` 前置检查 `RoundtableState`，ACTIVE 则拒；`topic` 开题前检查 `showState==RUNNING` 则拒。互斥点收在 Controller 一处，不散落。
- 演出中聊天输入照常可用（点播 P2 才接进导演；v1 打字走原路由，仅当串场词在说时打断语义与既有一致）。
- 串场词与音乐并存：系统混音器天然多路，无需 ducking（v1）；若实测串场词被音乐盖过，加 `mediaPlayer.setVolume(0.3f)` 的 duck act（P1 备选，一行）。
- 运镜权：演出期间导演独占机位（LLM `<cam:>` 标签本就「最后说话者赢」，演出中无人发言，天然无争抢；圆桌停题后无导演插话）。

---

## 五、总体架构与数据流

```
adb shell am broadcast STAGE_CMD            adb push shows/*.json
        │ cmd "dance start/default"                 │
        ▼                                           ▼
┌─────────────────────────────────────────────────────────────┐
│  StageCommandRouter (app, 既有)  ──►  DanceShowController    │
│                                         │ 装配/落地层         │
│                              ┌──────────┴──────────┐         │
│                              ▼                     ▼         │
│                   ShowDirector (纯 JVM)     MusicPlayer(demo) │
│                   ShowScript 节目单模型      MediaPlayer 封装  │
│                   状态机+超时兜底                             │
│                              │ 指令: playDance/gaze/cam/say…  │
└──────────────────────────────┼───────────────────────────────┘
                               ▼
   ┌──────────────────────────────────────────────────────────┐
   │ AvatarStage (corelib, 零改动): 相机/场景/VRMA 缓存/帧泵      │
   │ AvatarHandle ×N (零改动+vrmaFinished): 表演/注视/走动       │
   │ RoundtableSession.say (零改动): 串场词 TTS 全链             │
   └──────────────────────────────────────────────────────────┘
```

**一场默认演出的时序（双人版，C 可选扩三幕）：**

```
dance start
 ① camera GROUP                    → 开场全员同框 (camera_shot 立即生效)
 ② dance A: B gaze A / B clap(随机) → cam FULL A → A 播 Bboy Hip Hop(one-shot)
    → vrmaFinished(A) 或超时 → A 回 idle(引擎自动) → B 回 gaze camera
 ③ dance B: 镜像 ②
 ④ wait 1.5s
 ⑤ group_dance: cam GROUP → 全员同帧 play Shuffling(loop) → 12s → 全员 stop 回 idle
 ⑥ bow: 全员 Quick Informal Bow(one-shot) → 回 idle
 ⑦ camera GROUP → DONE（状态留 DONE，dance status 可查，dance start 可重演）
```

**API 草图（签名级，供评审；不是终稿）：**

```kotlin
// app multiavatar/show/（纯 Kotlin，无 Android import，JVM 可测）
@Serializable data class ShowScript(val version: Int, val title: String,
    val music: String? = null, val acts: List<ShowAct>)
@Serializable data class ShowAct(val type: String, val performer: String? = null,
    val clip: String? = null, val seconds: Float? = null, val camera: String? = null,
    val target: String? = null, val text: String? = null, val fans: List<String>? = null)

class ShowDirector {                       // 状态机本体：事件进、指令出
    val state: StateFlow<ShowState>        // IDLE/RUNNING/DONE/STOPPED
    val progress: StateFlow<ActProgress>   // 第几幕/什么 act/阶段(announce/perform/return)
    fun start(script: ShowScript, out: (DirectorCmd) -> Unit)
    fun onVrmaFinished(id: String); fun onWalkFinished(id: String)
    fun onSayCompleted(id: String); fun stop(); fun skip()
}

// app 侧落地：DirectorCmd密封类 → AvatarHandle/RoundtableSession/MediaPlayer 调用
```

---

## 六、性能与预算

**渲染**（基线=Redmi K20 Pro / SD855）：

- 双人 50~60fps 已验收（multi-avatar §十一，弹簧骨 stride=2 为多演员默认）。第三人 = +1 份 VRM 纹理/顶点 + 弹簧骨链，且 stride 交错已是 3 人兼容设计。**待实测**：Asuna_1024 若为轻量模型（名字含 1024 贴图规格）可能比现有两个重模型便宜——P0 先单人加载验证其 VRM 版本/材质路径（0.x 走 Y-180 翻转），再决定是否入默认节目单。
- 3 人开演若 <30fps：降档顺序=①C 退出默认节目单（双人照演）②观众席弹簧骨隔双帧（stride=4，仅非表演者）③非焦点 runtime 降频（multi-avatar 遗留优化项，本期不做）。
- 大动作舞蹈（breakdance 翻腾）对 GPU 无额外压力（同 mesh 同材质），压力在弹簧骨 substep——dt 峰值已被引擎 clamp（0.25s 上限）。

**内存/成本**：VRMA 缓存共享（齐舞零增量）；音乐解码常驻 ~5MB 级；P0/P1 零 LLM 调用零网络成本；P2 点播一次非流式调用百 token 级（复用圆桌导演的便宜模型结论：Qwen3.8 关思考/doubao-mini 结构化输出 5/5 可靠）。

**时延体感**：幕间切换 <100ms（本机事件+协程，无网络）；串场词 TTS 首包 1~2s（既有链路，幕间观众鼓掌正好填感知空隙——串场词与鼓掌并行发射的设计理由）。

---

## 七、分期落地计划

> 遵循仓库惯例：纯逻辑下沉 JVM 单测 + STAGE_CMD 真机验收；每期独立可验收、单人路径零回归。

### P0 确定性团播最小闭环——约 2~3 人日

**动刀：**

1. corelib（唯一增量，~20 行）：`AvatarRuntime.onVrmaFinished`（postAnimation 翻沿，:400-410 处的 oneShotActive 已现成）→ `AvatarHandle.vrmaFinished: SharedFlow<VrmaFinished>`；`AvatarController` 单人路径不暴露。
2. demo `multiavatar/show/` 新包（纯 Kotlin）：`ShowScript`（+kotlinx.serialization 解析与校验）、`ShowDirector`（状态机：act 队列推进/事件等待+超时兜底/stop/skip）、全分支 JVM 单测（推进/超时/事件丢失/stop 中断/skip/重复 start；注意 `runCurrent` 坑）。
3. demo 接线：`DanceShowController`（DirectorCmd → AvatarHandle 调用；白名单=启动时扫描 animations/08 目录）；`Slots` 2→Map（A/B 语义不变，`load C <模型>` 按需）；STAGE_CMD `dance` 命令族（§4.5 表）；内置默认节目单（双人版；fans 只做 gaze+clap）；开演前 `stopTopic()`+interrupt 互斥。
4. 幕间回位：独舞结束 `turnTo`+snap 回 mark（§4.7 最简版）。

**验收（真机 SD855）：**

- `dance start` 一条命令跑完整场：`dance status` 全程可查，DONE 收尾；`dance stop` 中途停演三清（动作回 idle/注视复位/无残留协程）；`dance next` 跳幕。
- 截帧序列（每幕 `shot` 命令）核对：独舞 FULL 锚定表演者、齐舞 GROUP 同框、谢幕全员入画。
- 独舞播完自动回 idle 无 T-pose 无尾帧冻结；位移漂移实测（8~10 个候选曲目前后 screenshot 对比）产出默认白名单。
- 双人全程 fps ≥45；双人既有聊天/圆桌回归零变化；单人 MainActivity 零接触零回归；×2 force-stop 重启零崩溃。
- 单测：ShowDirector 全分支 + ShowScript 解析校验（坏 JSON/未知 act/缺字段拒绝）。

### P1 音乐+走位+串场词——约 2~3 人日

**动刀：**

1. `MusicPlayer`（demo 小件：assets/music/ 播放、start/stop/音量 duck、onError 兜底行）；`music` act 接入；内置默认节目单配乐（CC0/自备，版权注意）。
2. 走位：`home.anchors.json` 加前排表演位 `stage_front_a/b/c`（词表同步可见）；`move` act 接入（walkTo 锚点 → walkFinished → 就位开演；15s 超时就地开演，P3 纪律）；独舞结束从 snap 升级为 `walkTo` 走回队列。
3. 串场词：`say` act / `dance.line` 接 `sayAsync`（该槽无会话时跳过+警告行——团播可以纯渲染不带 AI 跑）；完成信号 `TurnCompleted`+8s 兜底；串场期间观众席鼓掌并行（填 TTS 首包空隙）。
4. 极简 DancePanel（开始/停止/下一幕/状态行，复用 SmallButton 既有件）。

**验收：** 带音乐完整场（开演起乐、停演停乐）；走位上场到位才开舞、退场走回队列；串场词口型/字幕正常且不与音乐抢主旋律（音量体感）；演出中打字聊天不炸演出；fps 复测；单测：move/say 的超时推进分支。

### P2 LLM 导播+点播互动——约 3~4 人日

**动刀：**

1. 点播路由（先规则后 LLM）：`dance dm <文本>` 与聊天框文本 → 规则档（人名/曲目名精确匹配直接插幕）→ LLM 档（非流式便宜模型，输入=在场者+曲目白名单+文本，输出 `{"performer":"A","clip":"...","line":"..."}` 截首平衡 JSON 解析）；**白名单双向校验**（人不在场/曲不在单→拒绝降级规则档）；插幕位置=当前幕结束后（不打断进行中的独舞）。
2. 串场词润色：LLM 按表演者人设一句话变体（可选开关，默认关——固定串场已够演出）；失败降级原文。
3. 赛后圆桌点评（可选彩蛋）：演出 DONE 后 `topic` 开题「点评刚才的演出」——圆桌导演与团播共享同一舞台的两套节目形态合流演示（宣传点：同一 SDK 两种节目）。
4. `dance dm`/`dance auto on|off`（LLM 导播总开关，缺 Key 静默规则档——圆桌既有模式）。

**验收：** 「让A跳街舞」命中插幕；「让C跳」C 不在场→降级提示；LLM 拒答/超时/垃圾输出→固定台本无缝续演（3/3 稳定）；导播决策全落 `director_log` 风格日志可复盘；成本记录（每场点播调用次数/token）。
**单测**：路由两档全分支+白名单校验+降级链（LLM 挂起 fake 注意无参 Unconfined 坑）。

### 总预估与顺序依赖

7~10 人日；P0 是唯一动 corelib 的期，P1/P2 纯 demo 层；P1 依赖 P0 的导演骨架，P2 依赖 P1 的串场词缝。发布策略：P0 一版（「adb 一条命令团播」已是完整演示视频素材），P1 一版（带音乐的完整演出=README 头图视频），P2 一版（点播互动=直播形态前站）。

---

## 八、风险与开放问题

| # | 风险 | 定性 | 缓解 |
|---|---|---|---|
| 1 | 舞蹈素材净位移漂移（独舞越跳离队列越远/走出取景） | 高确定性会遇到 | §4.7 三层：白名单人工筛（P0 验收产出）+ 幕间回位 + 齐舞选 Loop 变体 |
| 2 | 第三人性能（SD855 掉帧） | 中 | C 按需加载不进默认单；降档顺序 §6；双人保底 50~60fps 已验收 |
| 3 | GROUP_SHOT 取景在齐舞/大跳时过远或切头（×3.2 span 按头距算，包不住翻腾半径） | 中（调参） | P0 真机调参；独舞用 FULL/LONG 锚人；必要时 GroupShotMath 加「动作幅度系数」参数（纯数学+单测，改动小） |
| 4 | 事件丢失卡场（vrmaFinished/TurnCompleted 未达） | 低-中 | 每步超时兜底纪律（§4.6）；`dance next` 人工救场；导演决策落日志可复盘 |
| 5 | 应援素材观感（鼓掌多为坐姿起手；鞠躬后回 idle 的 crossfade 生硬） | 低（调参） | P0 实测挑选站姿应援曲目；`walk_fade`/`stopVrmaAnimation(fade)` 调 crossfade 时长 |
| 6 | 舞蹈 VRMA 的表情轨道未驱动（SDK 已知限制）——跳舞面无表情 | 低（既有） | 视觉可接受（团播看身体）；根治=expressionTracks 驱动，另立项不在本期 |
| 7 | 音乐版权（P1 配乐） | 低 | 默认单用 CC0/自创作；README 注明素材来源 |
| 8 | open 问题：齐舞相位漂移（N 引擎各自积分 playhead，长时间 loop 后是否同步） | 搁置到 P0 实测 | 同帧起播+同速积分理论上同步；实测 30s 齐舞截帧对比；偏了就在 group_dance 每 N 秒重对齐（stop/play 重入） |
| 9 | open 问题：演出中来电/切后台的暂停语义 | 搁置 | v1 不处理（与既有 TTS 行为一致）；P1 的 MusicPlayer 至少 onStop 暂停音乐 |

---

## 九、架构归宿总表（lib / demo / 新 lib）

| 组件 | 归宿 | 理由 | 抽出条件 |
|---|---|---|---|
| `onVrmaFinished`（AvatarRuntime+AvatarHandle） | **corelib**（本期唯一增量） | 通用能力：任何「播完做某事」的编排都需要（技能链/彩蛋/特效触发）；镜像 walkFinished 既有模式 | —— |
| `ShowScript`（节目单模型+解析校验） | **app** `multiavatar/show/` | 产品语义非渲染语义；AiDebug「app 内纯 Kotlin+单测」先例 | 「演出编排」要作为 SDK 能力发布 / 出现第二个消费方 |
| `ShowDirector`（状态机） | **app**（纯 JVM） | 同上；且 P2 LLM 导播成熟后若与圆桌 ConversationDirector 抽象合流，去处是 orchestrator 而非 corelib | 与圆桌导演合流出通用「Director」抽象时 |
| `DanceShowController` / `MusicPlayer` / DancePanel | **app** | demo 装配层与 Android 小件；不值得单独模块 | —— |
| 默认节目单 JSON / music 资产 | **app assets** | 内容资产非代码 | —— |
| STAGE_CMD `dance` 命令族 | **app** | 调试通道本就在 app（StageCommandRouter） | —— |
| 前排表演位锚点 | **app assets**（home.anchors.json） | 场景数据，P3 同款 | —— |
| 特效（风/雪/火/粒子/Bloom/Fog） | **未来新模块 effectlib**（同仓库新 Gradle 模块，非新仓库） | 与虚拟人正交的场景能力，塞 corelib 会让 SDK 漂移成微型引擎；按需 `implementation` 引入 | 特效期单独立项时再建（本期明确不做） |
| **不新建仓库** | —— | Star/流量/Issue 必须聚合一处；demo 是售楼处；多仓库=双份 CI+版本同步 | —— |

**一句话**：本期 95% 代码落在 app demo 层，corelib 只收一个 20 行的通用事件；「另起炉灶」与「现在就抽 effectlib/show 模块」都被明确否决——先让 demo 跑起来惊艳，再谈沉淀。

---

## 十、探索证据核心索引

- 表演 API：AvatarHandle.kt:99-115（VRMA 全套）/ :53-68（walkFinished SharedFlow 模式=vrmaFinished 的模板）/ :120-135（lookAt 系）/ :204-216（walk/turnTo）。
- 引擎语义：VrmaAnimationEngine.kt:241-268（idle 接管=播完自动回待机）/ :287-330（play 置 idleTakeover=false）/ :387（isOneShotActive）/ :392（getDuration）/ :394-396（setPlaybackSpeed）。
- 翻沿用现成量：AvatarRuntime.kt:400-410（postAnimation 已算 oneShotActive）/ :134（onWalkFinished 回调字段）。
- 舞台：Stage.kt:56-59（帧泵三段纪律）/ :297-323（Choreographer 泵）/ :845-869（GROUP_SHOT+setGroupShot）；VrmaClipCache.kt（62 行共享缓存）。
- 编排层：RoundtableSession.kt:176（say 串场）；AvatarEvent.kt:39（TurnCompleted）；StageFormation（P3 对谈位数学）。
- app：MultiAvatarActivity.kt:94-100（默认常量）/ :103-143（Slots 双槽）/ :254（共享 idle 挂接）/ :608/:649（STAGE_CMD 通道+路由器）/ :827-836（play 命令含空格路径拼回）/ :839-853（gaze）/ :871-889（cam）；StageChat.kt:119/:453-458（rig+sayAsync）；app/build.gradle.kts:104（kotlinx.serialization 已依赖）。
- 资产：animations/08_舞蹈与表演=82 曲目（含 `- Loop` 变体）；04_交流手势=5 鼓掌；03_打招呼与礼仪=8（鞠躬/挥手）；vrms=3 模型（Asuna_1024 未验证）。
- 既有验收基线：multi-avatar §十一~十四（双人 50~60fps/双人 60fps 圆桌 12 轮/对谈位 heading 精确/46-58fps）；ai-debug-intents.md（adb 协议与转义纪律）。

---

## 十一、实施与验收实录（P0/P1/P2 全部完成）

> 三期全部真机（Redmi K20 Pro / SD855，2c3769db）验收通过；本节是实施后的回填记录，
> 进度状态以此节为准（上文 §1~§十 是实施前的规划稿，保留原貌）。

### P0 确定性团播最小闭环（2026-10-14 提交 3ba5fc5）

- 落点与规划一致：corelib 只加 `onVrmaFinished`（AvatarRuntime 翻沿 → AvatarHandle
  SharedFlow，镜像 walkFinished）；`ShowScript`/`ShowDirector`（纯 JVM 状态机）/
  `DanceShowController`/STAGE_CMD `dance` 命令族落 app `multiavatar/show/`；
  `Slots` 改 Map 支持 `load C` 第三人；幕间回位 P0 用 snap。
- 真机：`dance start` 一条命令全场（独舞+齐舞+谢幕+运镜+应援），`dance status`
  全程可查、`dance stop` 三清、`dance next` 跳幕；位移漂移实测筛出默认白名单。

### P1 音乐+走位+串场词（2026-10-14 提交 b60222d）

- `MusicPlayer`（MediaPlayer 封装，基线 0.65/duck 0.35、onError 兜底行、页面
  onStop 暂停 onStart 恢复）；`music` 幕四动作（start/stop/duck/full）。
- 锚点表加前排表演位 `stage_front_a/b/c`；`move` 幕走位到位才开舞（15s 兜底
  就地开演）；独舞收尾从 snap 升级为 walk 回**开演位**（退场走回队列）。
- `say` 幕与 `dance.line` 接圆桌 say 全链（TTS→口型→字幕），完成信号
  TurnCompleted + 8s 兜底，无会话秒跳过（纯渲染可跑）；应援鼓掌与串场并行。
- 真机：带乐完整场 54~76s、fps 50~58、演出中聊天不炸场、切后台音乐自动暂停；
  单测 39 个全过。
- 实施坑（规划外）：①mark 曾被 move 幕更新成表演位导致退场留在台前——改为
  **mark 固定为开演位**（演出中不变）；②切后台走位停摆靠 15s 兜底推进（引擎
  后台停帧，符合「宁可瑕疵不可卡死」）；③TTS 时长波动 5~18s，8s 兜底偶尔提前
  推进（下一幕起舞与串场词尾音重叠，可接受）；④自定义节目单要 `adb push` 进
  **内部** filesDir（`run-as` 法），外部存储不可读。

### P2 LLM 导播+点播互动（2026-10-14 实现，真机验收通过）

**动刀落点**（与 §7-P2 规划一致，全部 app 层，corelib/orchestrator 零改动）：

1. `DanceRequestRouter`（新，纯 JVM）：点播路由两档。规则档=演员名+曲目名都
   精确命中才直接插幕（单字母槽位 id 用**词边界**匹配防「bad」误中「a」；曲目名
   归一化小写去空白后子串匹配、取最长命中、<6 字符短名不参与）；未中走 LLM 档
   （圆桌导演同款：硅基流动便宜模型+温度 0.2+关思考，非流式收集 TextDelta，
   `MemoryParser.firstJsonObject` 同款截首平衡 JSON）；LLM 输出**双向白名单校验**
   （performer 解析回在场 id、clip 归一后必须在白名单内），超时 8s/垃圾输出/
   校验失败→Reject（附原始输出截断），演出照常。串场词长度硬上限 30 字。
   成本记录：调用数/失败数/最近时延/累计 token（`dance` 状态行与 `dance log`）。
2. `ShowDirector.enqueueAct`：插幕队列（当前幕收尾后作为**下一幕**执行，不打断
   进行中的独舞；多次插播 FIFO；进度总幕数随之增长；stop/换代即清）。
3. `DanceShowController`：`handleDm`（`dance dm` 与演出中聊天框共用入口，LLM
   调用异步跑不阻塞幕次）；`dance auto on|off`（LLM 导播总开关，prefs 持久化，
   缺硅基流动 Key 静默落规则档）；`dance polish on|off`（串场词润色，默认关，
   失败降级原文，润色期间停演则迟到的串场词不播）；`dance log`（导播决策日志
   ring 20 条，dm/polish/插幕全程可复盘）；`StageChatRig.danceDirectorLlm()` 缝
   （与圆桌导演同 Key 同模型、档位独立）。
4. 演出中聊天框输入自动切点播路由（§4.8 的 P2 语义）；DONE 后提示可 `topic`
   圆桌点评（彩蛋零代码，互斥守卫天然放行）。

**单测**：show 包 61 个全绿（路由 17：两档全分支/词边界/白名单双向拒绝/垃圾/
超时/网络失败/缺 Key/line 截断/润色降级/名册与清单 prompt 形态；导演 23：插幕
在当前幕后/不打断/进度总数/非演出拒收/停演清空不泄漏/FIFO），app+orchestrator
全量套件零回归。LLM fake 记调用数锁死「规则档命中零调用」。

**真机验收**（SD855，硅基流动 Key 在位 → auto 默认 llm 档）：

- 「让A跳一段街舞」→ 4.2s 路由，LLM 选真实曲目 `Female Hip Hop 'Arm Wave'
  Dancing`（过白名单）+自拟串场词「小柔来段街舞，手臂波浪秀起来」，插幕在当前
  走位幕收尾后执行，独舞完走回队列——插幕时序与规划一致；
- 「让C来一段芭蕾」→ C 不在场，LLM 如实输出“无合适曲目”→拒绝提示、固定台本
  无缝续演到 DONE（3 处插幕/拒绝全程演出不中断）；
- 「小刚 Doing The Shuffling Dance」→ 规则档精确命中，**零 LLM 调用**，插幕在
  谢幕后作为收场幕执行；
- `dance auto off` → 点播未命中即拒绝（附“LLM 导播未开启”提示）；`auto on` 恢复
  llm 档（决策计数随换代清零）；演出中 `dance start` 被互斥守卫正确拒绝；
- 润色：固定串场词被改写为「嗨大家好，我是小柔，今晚的团播开始啦～」（约 0.9s，
  日志带（润色）标记，`dance log` 记录变体对照）；
- 成本：单次点播 prompt≈4.6k tok（全曲目白名单入 prompt）/completion≈35 tok，
  状态行 `dm(llm调用2次(败1) 最近2376ms tok=9304+70)` 全程可见；
- fps 52~60（≥45 达标）、零 FATAL；超时/垃圾输出降级分支由单测锁死（真机不便
  确定性构造）。

**实施坑（规划外）**：白名单全量入 prompt 约 4.6k tok/次——点播是低频动作可接
受，若要降本可按类别裁剪（08 类优先已在 system prompt 引导）。
