# 工具目录扩展:候选判定与落地设计(P0.5)

> 生成时间:2026-10-11。前置:`docs/agent-feasibility.md`(P0 已实现+真机验收通过)、`docs/agent-provider-matrix.md`(五家协议矩阵)。
> **落地状态(2026-10-11 当日实现并真机验收):本批 6 工具全部完成,单测 +28 全量 681 全绿(app177/corelib85/adapter95/orchestrator324)。真机(2c3769db)工具层 A/B 6/6 全过且全部有系统级硬件证据(dumpsys torch/vibrator/相册 PNG/prefs/亮度差分,详见交接文档 P0.5 验收条目);LLM 自然触发 5/12 方向组合通过,两种模型侧失败形态(DeepSeek 关灯/恢复/拍照/击掌口头演;doubao set_ambience 确定性文本吐 XML 进 TTS)已定档在 docs/agent-provider-matrix.md「P0.5 节」,机械兜底列后续任务。设计逐条照做,偏差仅三处——①set_ambience 未做 iblIntensity 渐变 polish(第一版接受硬切,如 §2.5 所列);②总开关关闭时连 P0 内置 get_device_time/get_current_view 也一并反注册(§2.0 语义补齐,此前只摘 observe_camera);③验后提示词加固 2 处(flashlight hint+toolsetBlock 状态改变行)。**
> 背景:与 Gemini 头脑风暴出 14 个候选工具(Android 硬件类 / Filament 舞台类 / 系统实用类 / 表达类),本文逐个判定可行性,并给出本批(P0.5)的落地设计。
> 结论先行:**6 个本批实现**(零新依赖,全部走现有 ToolRegistry/工厂缝)、**3 个列 P2 候选**(卡资产或需独立主题)、**4 个明确不做**(与行内标签重复/与系统助手同质化/渲染器工程量过大)。工作量 ≈1 周,单测 +35 左右,设置页只加一个开关(剪贴板,默认关)。

---

## 0. 判定总表

| # | 候选工具(Gemini 命名) | 本判定 | 判定依据 | 实用性 | 难度 |
|---|---|---|---|---|---|
| 1 | control_flashlight | ✅ 本批 `set_flashlight` | CameraManager.setTorchMode,几十行;⚠相机被自家占用时的行为要兜(§2.2) | ★★★★★ | 极低 |
| 2 | get_device_vitals | ✅ 本批,并入 `get_device_status` | BatteryManager 粘性广播;与音频路由合并成一个「本体感受」工具(§2.1) | ★★★★ | 极低 |
| 3 | get_connected_devices | ✅ 本批,并入 `get_device_status` | AudioManager.getDevices,几十行;单独成工具太薄 | ★★★ | 极低 |
| 4 | trigger_haptic_pulse | ✅ 本批 `send_haptic` | Vibrator+VibrationEffect,预设波形表;触觉是全新交互维度 | ★★★★ | 极低 |
| 5 | read_clipboard | ✅ 本批 `read_clipboard` | ClipboardManager 前台可读(Android 10+ 焦点限制对本场景无碍);**唯一新增开关** | ★★★★☆ | 极低 |
| 6 | set_scene_ambience | ✅ 本批 `set_ambience` | `updateRenderSettings` 已整体热切换(AvatarController.kt:541),只做现有参数的组合预设,不改 applyLighting | ★★★★☆ | 低 |
| 7 | take_avatar_selfie | ✅ 本批 `take_photo` | **`AvatarController.captureFrame` 已存在**(AvatarController.kt:665,ai_cmd screenshot 在用);砍掉「真实相机合成」变体(§2.6) | ★★★★ | 低-中 |
| 8 | play_ambient_sound | ⏸ P2 候选 | 实现不难,**卡在资产**(CC0 白噪音音源+APK 增量);等音源定案(§5) | ★★★ | 中 |
| 9 | sense_spatial_environment | ⏸ P2 候选 | 传感器可用性碎片化(光照传感器缺失率不低),启发式运动分类有误判率;单独立批做校准 | ★★☆ | 中 |
| 10 | pop_thought_bubble | ⏸ P2 候选 | 属「表演层」而非「世界状态」(违反 §1 公理 1),且引入不经 TTS 的第二文本通道;归入下一轮「表达升级」主题一起设计 | ★★★☆ | 中 |
| 11 | switch_avatar_style | ❌ 不做 | 内置 VRM 无分体服装资产,渲染器无部件可见性 API;前置条件是资产管线,非本批可解 | ★★ | 高 |
| 12 | spawn_3d_prop | ❌ 不做 | 渲染器无道具挂载系统(骨骼 socket attach+独立 gltf 加载+淡入淡出),数周级渲染器工程,触碰 springbone/MToon 敏感区 | ★★ | 高 |
| 13 | focus_camera_shot | ❌ 不做 | 6 机位+平滑过渡已存在,且 `<cam:macro>` 行内标签**已暴露给 LLM**——工具版是纯重复,稀释 hint 聚焦度(§1 公理 1) | ★ | — |
| 14 | dispatch_quick_intent | ❌ 不做 | 与小爱/Siri 同质化,拿自己的高延迟打别人的系统级生态;差异化定案见 §1 公理 2 | ★ | — |

---

## 1. 定位公理:什么该做工具,什么不该

三条公理从与 Gemini 的讨论定案沉淀而来,本批及后续所有工具立项都按此裁决:

1. **行内标签=表演层,工具=世界状态**。`<emo/act/cam>` 管说话时的即时表演(表情/动作/镜头,与 TTS 时间轴同步);工具管「获取信息」和「改变持久状态」(查时间/开灯/换氛围)。已经有一个通道的能力不再开第二个工具入口(focus_camera_shot 被砍的直接原因),工具数量本身就是成本——每个工具的 hint 都占据钉住协议块的注意力预算。
2. **不做系统遥控器**。导航/播歌/拉应用是小爱与 Siri 的主场(系统级生态+零延迟),虚拟人用高延迟的 LLM 循环去抢是扬短避长。差异化在四维:动机反转(角色为什么调工具)、具身联动(结果回灌到 3D 身体)、记忆沉淀(P2 记忆约束参数)、边界感(可以拒绝与沉默)。
3. **hint 写角色动机,不写 API 文档**;工具结果第一人称演绎。`systemHint` 描述的是「什么样的处境想用这个能力」而非功能罗列;协议块公共尾部(PromptTexts.toolsetBlock)补一行:设备状态类工具的返回是「你的身体感受」,回答时以第一人称演绎(「我摸了下额头,有点烫」),不许播报数值——这是「具身联动」在工程上唯一的落点,而且零代码(一行提示词)。

---

## 2. 本批六工具详细设计

### 2.0 通用形态与分层

全部沿用 P0 已验证的形态,**零架构新增**:

```
:corelib                (仅两个小改,§3.2):AmbiencePreset enum + currentRenderSettings getter
:avatar-orchestrator    AgentToolCatalog 增 5 个工厂函数(纯 JVM,设备能力全是注入的 suspend lambda)
:app                    新 AgentDeviceTools.kt 接线对象:实现各缝(Android API)+ 一次性注册
```

- 缝模式 = `getDeviceTime(nowMs: () -> Long)` 同款:orchestrator 定义 Schema/解析/hint,app 注入设备实现。orchestrator 保持纯 JVM 可测,不 import android。
- 默认注册:`get_device_time`/`get_current_view` 在 AvatarSession.kt:310-311 注册;本批六个的 executor 全依赖 Android API,**统一由 app 的 AgentDeviceTools 在会话构造后注册**(接线点与 MainActivity.kt:1284 observe_camera 的 LaunchedEffect 同层,但收敛到一个对象,给 MainActivity 减负)。
- 设置面:六个工具中五个**不设独立开关**,跟随 `agentTools` 总开关(无隐私面/用户可即时感知可撤销);`read_clipboard` **独立开关默认关**(唯一读取用户数据类)。设置页只加一行。
- ai_cmd:零新增,全部用现有 `tools_call <name> [json]` 真机 A/B(惯例同 rps_throw)。
- 失败语义:全部走 ToolResult(isError) 回灌,模型口头转告原因,永不炸回合(ToolRegistry.kt:84 既有守卫)。

### 2.1 `get_device_status`(合并 vitals + 音频路由)

**体验**:「我感觉手机好卡」→「我摸了下自己的额头……都 41 度了,电量也只剩 9%,你快给我插上充电线,不然我要晕过去了」。戴上耳机→「检测到你连上耳机了,可以说点悄悄话啦」。

**Schema**:`{}` 无参。**合并为一个工具的理由**:两者都是无参设备感知、模型一次调用拿全(省一轮工具轮),且「电量+温度+充电+音频路由」合起来才是完整的「本体感受」概念——分开注册两个薄工具只稀释 hint。

**实现缝**:`status: () -> String?`(app 层):
- 电量/温度/充电:`registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))` 粘性广播(level/scale → %;EXTRA_STATUS 充电态;`BATTERY_PROPERTY_TEMPERATURE` → 0.1°C,返回 0 视为「未知」);可附 `PowerManager.getThermalStatus()` 档位名(none/light/.../critical)。
- 音频路由:`AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 判 BLE 头戴/A2DP/有线/扬声器/听筒,归一成 `耳机(蓝牙)/耳机(有线)/扬声器` 一类短语。
- 返回**中文自然语句**(由 PromptTexts 模板拼装,双语),如「电量 43%,未在充电,机身温度约 41.5°C,声音从扬声器播出」——模型直接以此组织口语,不需要二次解读。
- 隐私面:无(纯本地读数),hint 限定「当用户关切设备/手机状态,或你想表达自己的身体感受时」。

**⚠坑**:粘性广播在部分 OEM 上 temperature 字段恒 0 → 文案降级「机身温度未知」,不编数。

### 2.2 `set_flashlight`

**体验**:「床底下好黑,帮我照一下」→「别怕,我帮你照着,小心脚下」(开);「可以关了」(关)。

**Schema**:`{ "enabled": boolean (required) }`。**一个工具带布尔参**,不拆 on/off 两个工具(hint 聚焦、Schema 面×0.5)。不做 Android 13 的多级亮度(precise torch strength 端机型碎片化,v2 再说)。

**实现缝**:`torch: (enabled: Boolean) -> String`(app 层):`cameraManager.setTorchMode(cameraId, enabled)`;`hasSystemFeature(FEATURE_CAMERA_FLASH)` 不满足时返回固定错误文案(无闪光灯硬件)。

**⚠坑(真机必验)**:
1. **相机被自家占用**:视频模式/按需拍照期间 CameraX 持有相机,`setTorchMode` 可能抛 CAMERA_IN_USE → 捕获后返回 isError「视频模式占着相机,我开不了手电筒」,模型口头转告——这是「错误回灌不炸回合」哲学的标准演出,不许吞。
2. **责任清理**:`onStop`/会话销毁时**强制关灯**(模型开了灯用户走人,手电筒亮到天荒地老是产品事故)。AgentDeviceTools 挂 LifecycleObserver 统一清理。

### 2.3 `send_haptic`

**体验**:安慰场景「闭上眼睛,手指贴在手机上感受一下……我一直在呢」(心跳震动);猜拳获胜击掌一记清脆短震。

**Schema**:`{ "pattern": enum (required): heartbeat | high_five | purr | gentle_tap }`。enum 而非自由波形参数——模型不该设计波形,预设四档够用且可锁单测。

**实现缝**:`haptic: (pattern: String) -> String`(app 层):`Vibrator`(`VibratorManager` API 31+ 回退),`VibrationEffect.createWaveform` 预设表:
- heartbeat:65bpm 规律双击(长-短对,重复 ~6s)
- high_five:单次 40ms 硬震
- purr:低幅高频连续 ~3s
- gentle_tap:15ms 轻点 ×2
manifest 加 `VIBRATE` 权限(普通权限)。无震动硬件(平板常见)→ isError「这个设备没有马达」。

**hint 场景**:「当你想跨越屏幕触到用户时——安慰、庆祝击掌、调皮提醒」。⚠约束模型**不要**频繁用:hint 里写「偶尔为之才有惊喜」,防模型每轮都震。

### 2.4 `read_clipboard`

**体验**:用户在微信复制长文/快递单号/验证码,切回来说「帮我看看我刚复制的内容」→ 免粘贴直接答。

**Schema**:`{}` 无参。

**实现缝**:`clipboard: () -> String?`(app 层,主线程):`ClipboardManager.getPrimaryClip()` → `item.coerceToText(context)`(多形态归一为文本)→ 空/非文本返回「剪贴板是空的」**正常结果**(非 isError——模型自然转告即可)。超长文本由工具循环既有 4K 截断兜底。

**隐私分级(本批唯一开关)**:设置页「允许读取剪贴板」默认**关**,开启后注册、关闭即反注册(协议块自动重钉)。理由:这是六个里唯一「读取用户数据」的工具,分级要立住——「无隐私面跟总开关,读数据独立开关默认关」成为后续工具的定价先例。
**⚠平台行为**:Android 10+ 焦点限制(仅前台可读)对本场景无碍——用户切换回本 app 才说话,本 app 正持有焦点;Android 12+ 首次读取系统弹「已从剪贴板粘贴」toast,观感可接受,hint 里仍限定「仅当用户明确要求读取复制内容时调用」,不许偷看。

### 2.5 `set_ambience`

**体验**:「准备睡了,聊聊今天吧」→「好,那我把灯调成晚安模式」(舞台光线平滑变暗);「来点气氛!」→ 聚光灯+bloom 的舞台感。

**Schema**:`{ "preset": enum (required): studio | bright | dim | spotlight | dreamy }`。
**⚠刻意不用 Gemini 起的 cozy_night/cyberpunk 名**——当前渲染系统**没有色温参数**(色调映射只有模式选择,IBL rotation 不等价于暖光),承诺「暖色/赛博」做不到;预设名按真实效果命名。

**预设映射**(全部 = 现有 `AvatarRenderSettings` 字段组合,零渲染器改动):

| preset | lightingRig | iblIntensity | bloom | 语义 |
|---|---|---|---|---|
| studio(默认) | STUDIO | 13000 | off | 现状基准 |
| bright | STUDIO | 22000 | off | 明亮日光 |
| dim | KEY_FILL | 3500 | off | 昏暗夜话 |
| spotlight | KEY_ONLY | 5000 | off | 单主灯舞台感 |
| dreamy | KEY_ONLY | 2500 | on(0.25) | 暗夜朦胧 |

**实现**:
- corelib 新增 `enum class AmbiencePreset { STUDIO, BRIGHT, DIM, SPOTLIGHT, DREAMY }` + `fun applyTo(settings: AvatarRenderSettings): AvatarRenderSettings` 纯函数(copy 变换,可单测)。
- 缝:`ambience: (preset: String) -> String`(app 层):解析枚举 → `controller.updateRenderSettings(当前设置.copy(...) 经 applyTo)`。**必须基于当前设置 copy**,不许全量构造——否则用户在设置页调过的阴影/AA/renderMode 会被打回默认(接线坑,§3.2 的 getter 就为此)。
- 解析失败(模型写了别的词)→ isError 附合法值列表。
- 过渡渐变(300-500ms 的 iblIntensity 插值)列为 polish 项,第一版接受硬切(舞台灯「啪」地换光也自然);做的话在 app 缝里用帧循环插值,corelib 不动。

**边界**:只动 lightingRig/iblIntensity/bloom 三个字段;不碰阴影(⚠PCSS Adreno 悬崖,软阴影永不进预设)、不碰 renderMode/AA/toneMapping。

### 2.6 `take_photo`

**体验**:「跟我合个影/拍张照看看你现在的样子」→ 虚拟人摆个姿势 → 「拍好啦,已经存进你的相册了」。

**实现**(核心 API **已存在**):
- 缝:`shoot: suspend () -> String?`(app 层):`controller.captureFrame { bitmap -> ... }`(AvatarController.kt:665,ai_cmd screenshot 同链路)→ 异步存 PNG → 返回保存路径文本回灌(模型据此说「存好了」)。
- 保存位置:`MediaStore.Images` 相对路径 `Pictures/AIAvatar/`(API 29+ 零权限,与 README 的 minSdk 29 一致);文件名时间戳。
- 摆姿势:v1 **不接**(模型可用既有 `<act:>` 行内标签在自己说话时摆动作,与拍照时序天然同步——「(摆出剪刀手)拍好啦」这个体验用现有通道就能拼出来,零新代码);v2 再考虑 pose 参数映射内置姿势 VRMA。
- **砍掉 Gemini 版本的「真实相机帧+渲染帧合成合影」**:姿势不同步的合成图效果必然廉价,且是独立图像工程;只做「拍我(3D 渲染画面)」。写真集/头像导出是天然衍生场景(渲染帧就是干净的立绘)。

**隐私面**:纯虚拟画面,无;不设开关,hint 限定「用户要求拍照/合影/写真时」。

---

## 3. 工程落点清单

### 3.1 app:`AgentDeviceTools.kt`(新文件,MainActivity 减负)

```kotlin
// app/.../agent/AgentDeviceTools.kt(建议位置)
object AgentDeviceTools {
    fun registerAll(session: AvatarSession, controller: AvatarController, prefs: AiChatPrefs, lifecycle: Lifecycle)
    // 一次注册:set_flashlight / send_haptic / read_clipboard(仅开关开时)/ set_ambience / take_photo / get_device_status
    // 内部持有:手电筒强制关的 LifecycleObserver、Vibrator 引用
    fun unregisterClipboard(session: AvatarSession)   // 开关切换时单独反注册
}
```

MainActivity 现有 observe_camera 的 LaunchedEffect(MainActivity.kt:1284-1344)因开关依赖复杂(总开关×观察开关×视觉模型)保持原地;本批六工具统一收进 AgentDeviceTools,`DisposableEffect(session)` 里一次调用。**MainActivity 3744 行,不再给它加接线。**

### 3.2 corelib 两处小改(合计 ~60 行)

1. `AmbiencePreset` enum + `applyTo(AvatarRenderSettings)` 纯函数(§2.5)。
2. `AvatarController.currentRenderSettings: AvatarRenderSettings?` getter(§2.5 基于 copy 的前提;现 updateRenderSettings 只进不出)。

### 3.3 orchestrator:AgentToolCatalog 五个工厂 + PromptTexts

- 每个工厂 = ToolSpec(schema+description)+ 缝调用 + 参数校验(枚举白名单)+ 双语 PromptTexts 新键(~30 个,照 toolGetDeviceTime* 惯例,PromptTexts.kt:84-98 同区)。
- toolsetBlock 公共尾部追加一行(双语):「设备状态类工具返回的是你自己的身体感受,回答时第一人称演绎,不要播报数值」(§1 公理 3)。

### 3.4 注册时机与协议重钉

AgentDeviceTools 注册发生在会话构造后 → ToolRegistry revision 递增 → 协议块指纹变化自动重钉(AvatarSession 既有机制,零新代码)。本批工具集合**运行期不再变化**(剪贴板开关除外),前缀缓存稳定。

---

## 4. 测试计划(预计 +35 条)

- orchestrator(~20):每工厂的参数校验(枚举白名单/缺失必填/isError 文案)、schema 快照锁(防 hint 漂移)、get_device_status 缝返回 null 时的降级文案、set_ambience 未知 preset 的错误文案、PromptTexts 双语键完整性(照既有惯例)。
- corelib(~6):AmbiencePreset 五预设的字段映射锁(尤其 dim/dreamy 的 ibl 值)、applyTo 不触碰 shadow/AA/renderMode 字段。
- app(~9):AgentDeviceTools 注册计数与开关联动(剪贴板关→反注册+重钉)、flashlight 缝的 CAMERA_IN_USE 转文案、clipboard 空/非文本/超长三分支、haptic 无马达降级、take_photo captureFrame 失败转文案。

## 5. 真机验收标准(全走 `tools_call` 先 A/B,再过 LLM 自然触发)

| 工具 | LLM 自然触发话术 | 通过标准 |
|---|---|---|
| get_device_status | 「我感觉手机好烫/我没电了吗」 | 调用→第一人称演绎数值,不播报 |
| set_flashlight | 「帮我照一下,这好黑」 | 灯亮;「关了吧」灯灭;视频模式中调用→口头转告开不了 |
| send_haptic | 「击个掌!」(猜拳获胜后) | 一次短震;非每轮都震 |
| read_clipboard | (复制任意文本)「帮我看看我刚复制的内容」 | 开关开=正确复述;开关关=工具不存在,模型自然回应 |
| set_ambience | 「准备睡了,把灯调暗吧」 | 画面变暗;「恢复一下」回 studio |
| take_photo | 「给自己拍张照」 | 相册 Pictures/AIAvatar 出现 PNG;模型说出存储确认 |

## 6. P2 候选(触发条件,不排期)

| 工具 | 解锁条件 |
|---|---|
| play_ambient_sound | CC0 音源定案(4-5 段 loop,每段 ≤2MB,APK 增量可接受);实现 MediaPlayer 循环环+音量参数+与 TTS 混音验证(~200 行) |
| sense_environment | 单独立批:传感器可用性矩阵(光照传感器缺失率高)+ 运动状态启发式误判校准;一次性查询(2s 窗),不做持续监听(持续感知是 P1 主动性领域的注入流,不是工具) |
| pop_thought_bubble | 归入「表达升级」主题:双轨表达(嘴上说 A 气泡写 B)与字幕/历史的语义一并设计;实现本身不难(Compose 浮层),难在定位与防滥用 |

## 7. 明确不做(理由存档,防回头重提)

- **switch_avatar_style**:内置模型无分体服装资产+渲染器无部件可见性 API;等资产管线成熟再立项。
- **spawn_3d_prop**:道具挂载是数周级渲染器工程,触碰 springbone/MToon 敏感区;若未来做,先做「场景切换」(loadScene 已有接口)再谈手持道具。
- **focus_camera_shot**:`<cam:>` 六机位已暴露给 LLM 且带平滑过渡,工具版纯重复。
- **dispatch_quick_intent**:系统助手主场,同质化竞争;若未来做内容向,方向是「分享到虚拟人」(接收)而非「拉起外部应用」(发出)。

## 8. 与既有约束的对照

1. **system 恒单条**(A.1 第 34 条):六工具 hint 照旧聚合进钉住协议块末尾,不新增 system 条数。
2. **工具失败永不炸回合**:所有设备 API 异常转 isError 文案回灌(手电筒 CAMERA_IN_USE 是标准演出)。
3. **MAX_TOOL_RESULT_CHARS=4K / 超时 10s / 顺序执行**:clipboard 超长与 get_device_status 都在既有守卫内,零改动。
4. **身份签名不含工具开关**:剪贴板开关切换只走 register/unregister+重钉,不轮换上下文。
5. **提示注入面**:本批六工具结果全部来自本机系统 API,无外部内容源,P0 风险清单第 8 条不适用;play_ambient_sound(P2)同样无注入面。
6. **工作量**:orchestrator ~250 行 + corelib ~60 行 + app ~350 行 + PromptTexts ~60 行,单测 +35,真机半天;**合计 ≈1 周**(含真机排障缓冲,参照 P0 节奏)。
