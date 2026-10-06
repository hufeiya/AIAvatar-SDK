# AI 原生调试接口（Intent 控制协议）

本应用支持通过 Android Intent 直接驱动 MainActivity 中原有的按钮功能（切换场景 /
切换人物 / 切换表情 / 切换动作 / 移动人物 / 相机缩放平移），供大模型以 adb 方式
调试，替代「adb 截图 + adb input 模拟点击」的原始手段。

- 命令通过 `am start` 的 extras 下发，应用内排队执行（冷启动、运行中均可）。
- 每条命令的执行结果（成功 / 失败原因 / 状态查询输出）都打印到 logcat 标签
  **`AIDebug`**，用 `adb logcat -d -s AIDebug` 读回。

## Intent extras

| extra        | 类型   | 说明 |
|--------------|--------|------|
| `ai_cmd`     | string | 命令名（必填，不区分大小写） |
| `ai_arg`     | string | 字符串参数：文件名 / 表情名 / on、off / none |
| `ai_x` `ai_y` `ai_z` | float | 数值参数：移动量（世界坐标，米）或相机手势距离（屏幕像素） |
| `ai_weight`  | float  | 表情权重 0~1，默认 1 |
| `ai_loop`    | boolean | `play_animation` 是否循环，默认 true |

命令兼容 LLM 常见手误：数值 extra 传成字符串（如 `--es ai_x 200`）也能解析；
`ai_loop` 接受 `true/false`、`on/off`、`1/0`。

## 命令一览

| 命令 | 参数 | 作用 |
|------|------|------|
| `help` | — | 打印命令表到 logcat |
| `state` | — | 输出当前状态：模型 / 场景 / 动作 / 表情 / 相机位姿 / 视线（lookAt） / FPS / 可用表情列表 |
| `list` | `ai_arg`= models \| scenes \| animations \| expressions \| cards | 列出可用资源（调用其他命令前先查有效取值） |
| `load_model` | `ai_arg`=文件名 | 切换人物（内置 assets/vrms 或已导入 filesDir/vrms 下的 .glb/.vrm，`list models` 合并列出），自动重置表情；**保持当前上下文**（方便不破坏会话地 A/B 模型；面板切换才会轮换上下文） |
| `import_model` | `ai_arg`=文件路径 | 导入一个 .vrm/.glb 到应用 data 文件夹（filesDir/vrms）并**自动选中 + 新建上下文**（与面板导入同语义）。arg 为绝对路径或相对应用外部目录（先 `adb push` 到 `/sdcard/Android/data/<pkg>/files/`，无需权限）的相对路径；重名自动加 `_1` 序号后缀（绝不遮蔽内置模型）；非 GLB 文件直接报错 |
| `load_scene` | `ai_arg`=文件名 \| none | 切换场景（assets/scene 下的 .glb）；`none` 移除场景 |
| `set_expression` | `ai_arg`=表情名，可选 `ai_weight` | 设置表情；应用前会清空旧表情 |
| `clear_expression` | — | 清空全部表情，恢复中性表情 |
| `play_animation` | `ai_arg`=文件名，可选 `ai_loop` | 播放 assets/animations 下的 .vrma 动作 |
| `stop_animation` | — | 停止动作（设了待机时回到待机循环，否则回 rest pose） |
| `set_idle` | `ai_arg`=文件名片段 | 把一个 .vrma 设为循环待机（LLM 手势播完/手动停止后回到它），持久化到 demo_settings；不设则用内置优先级自动选（Idle Stand Looking Around 优先） |
| `idle_off` | — | 清除待机，一次性动作播完回落 rest pose |
| `move` | `ai_x` `ai_y` `ai_z` | 平移人物（世界坐标米）：+x 右、+y 上、+z 朝相机 |
| `zoom` | `ai_x` | 相机推拉，取值为「双指张开距离」像素：正数放大、负数缩小 |
| `pan` | `ai_x` `ai_y` | 相机平移（屏幕像素，等效双指拖动） |
| `orbit` | `ai_x` `ai_y` | 相机环绕（屏幕像素，等效单指拖动） |
| `reset_camera` | — | 相机恢复初始机位 |
| `camera_shot` | `ai_arg`= closeup \| medium \| full \| long \| over \| off | 平滑运镜到预设机位：closeup 面部特写、medium 中景半身、full 全景全身、long 远景（大动作舞蹈用）、over 反应侧景；`off` 释放回自由相机 |
| `look_at` | `ai_arg`= camera \| off，或 `ai_x` `ai_y` `ai_z`；省略 `ai_arg` = 查状态 | 视线控制（任务 5）：默认 camera（注视镜头=注视用户，由 FaceDriver 的 SaccadeEngine 每帧加注视抖动）；`ai_x/ai_y/ai_z`=世界坐标注视点（切 POINT 模式，未来人脸追踪同入口，**无 AI 会话也可用**——直接驱动 corelib 视线叠加）；`off`=关闭（头颈眼回动画自身姿态）；无参数=输出当前状态（目标/已施加 yaw/pitch/骨骼绑定），验证明看 yaw 是否非零 |
| `set_drag_mode` | `ai_arg`=on \| off | 拖拽模式：on 时手指拖动人物而非旋转相机 |
| `open_panel` | `ai_arg`= none \| models \| animations \| expressions \| scenes \| cards \| settings | 打开/关闭对应底部面板（MIUI 禁止 shell 注入点击，用此命令驱动 UI 面板） |
| `spring_debug` | `ai_arg`=on \| off | 弹簧骨骼诊断：on 时每秒向 logcat（tag `SpringBone`）输出各弹簧链根/梢关节的骨长 len=当前/静止 与方向 dir，用于真机物理排查 |
| `spring` | `ai_arg`=on \| off | 弹簧物理开关（头发/胸部的 sway 总闸）：off 时发骨冻结在当前姿势。用于排查「头发抽搐」类问题——off 后消失=抽搐源在弹簧响应；配合呼吸开关可区分激励源 |
| `culling` | `ai_arg`=on \| off | 角色视锥剔除开关（默认 off=修复态）：gltfio 给蒙皮 mesh 的剔除盒是绑定姿态静态盒，特写+转头时眼球这类小 mesh 会被错误剔除（眼球丢失 bug）；on=旧行为（bug 兼容，A/B 复现用），off=蒙皮 mesh 永远绘制 |
| `breath` | `ai_arg`=on \| off（省略=查状态） | 程序化呼吸叠加（脊柱俯仰 ~2.6°+双肩微耸，静息 15 次/分；说话时自动变浅变快，默认开）。省略 `ai_arg` 输出 enabled/speaking/bpm/amp/phase/绑定骨数；`off` 作为真机 A/B 的「无呼吸基线」（截图差分看肩胸区 ~4s 周期像素起伏） |
| `screenshot` | — | 渲染一张 PNG 到应用外部目录，绝对路径打印到 logcat（可直接 `adb pull`） |
| `send_chat` | `ai_arg`=消息文本 | 发送一条用户消息（等价于聊天条发送），走 LLM 流式→断句→TTS→口型全链路；断句/情绪/回合事件时序打印到 `AIDebug`（`chat:` 前缀） |
| `interrupt_chat` | — | 打断当前 AI 回合（等价于聊天条 ✕），立即静音并回到 IDLE |
| `chat_state` | — | 输出 AI 对话状态：phase（IDLE/THINKING/SPEAKING）/ 字幕长度 / 最近错误 / 已解析生效的双厂商配置（llmProvider+llmModel、ttsProvider(same=勾选态)+ttsModel+voice）/ 当前模型是否支持图片输入（vision=）；视频模式再附 video=[相机/人脸/抓拍缓存状态] |
| `contexts` | — | 列出全部对话上下文（Room 持久化，按最近使用排序）：id 前 8 位（卡片名/消息数/最近使用时间）+ 当前上下文标记 |
| `new_context` | — | 新建一个上下文并切换（随机 UUID）；旧上下文历史保留在库里，可随时切回 |
| `select_context` | `ai_arg`=上下文 id 前缀 | 切换到指定上下文（前缀匹配，不区分大小写），历史从 Room 恢复；无匹配报错。切换会重建会话，正在播放的回合会被打断 |
| `import_card` | `ai_arg`=文件路径 | 导入并**自动激活**一张酒馆人物卡（SillyTavern PNG/JSON）。arg 为绝对路径或相对应用外部目录（`/sdcard/Android/data/<pkg>/files/`，先 `adb push` 到这里，无需权限）的相对路径；激活即重写系统提示词，已配 TTS 时自动朗读开场白（含 `{{char}}/{{user}}` 宏替换） |
| `active_card` | — | 输出当前激活卡片：文件名 / name / spec / version / 开场白长度 / 系统提示词长度与前 100 字符（卡片未激活输出 `no active card`） |
| `transcribe` | `ai_arg`=音频文件路径 | 语音识别（任务 4）：把音频文件走与「按住说话」完全相同的 ASR 链路（OpenAI 兼容 `/audio/transcriptions`，模型按设置推断：硅基流动默认 `Qwen/Qwen3-ASR-1.7B`，OpenRouter 默认 `openai/whisper-large-v3`（OpenRouter 音频端点要求账户 ≥$0.50 余额，不足时 402 错误原样透出）），识别文本打印到 logcat。arg 为绝对路径或相对应用外部目录的相对路径（先 `adb push`）。**不需要麦克风权限**（不走 MediaRecorder），适合无手环境验证 ASR |
| `voice_record` | `ai_arg`=秒数(1\|30) | 用与「按住说话」相同的 MediaRecorder 路径（AAC/m4a/16kHz）真录音 N 秒后自动转写，输出文件大小与识别文本；开始录音前会先打断正在播的回复（半双工）。**需要麦克风权限**：HyperOS 禁 adb 授权（`pm grant`/`install -g`/appops 均无效），首次须真手按住说话弹系统框授权；无权限时报 `录音启动失败：setAudioSource failed` |
| `voice_free` | `ai_arg`= on \| off（省略=翻转） | 按住说话 ⇄ 自由说话切换（持久化，语音/视频模式聊天条左侧同款按钮）：自由态=连续聆听，软件 VAD 自动断句（静默 800ms 判句尾），说完一句自动 ASR+发送（视频模式自动附抓拍帧，freeTalk 下 autoSend 设置不生效）；虚拟人说话时**大声**开口=打断当前回复（barge-in，高门限+持续 350ms+600ms 宽限防扬声器残留误触）。`chat_state` 尾部显示 freeTalk=(listening/hearing) 状态 |
| `set_mode` | `ai_arg`= manual \| text \| voice \| video | 切换输入模式（持久化）：manual=手动点击（完整 UI，全部按钮可见）/ text=打字输入（进入时自动隐藏所有界面按钮）/ voice=语音模式（同左，按住说话）/ video=视频模式（语音模式的一切 + 用户相机 PiP 小窗 + 人脸注视追踪 + 每轮发送附相机抓拍）。**video 有准入门控**：只有多模态（可收图）大模型才能进入，否则 FAIL 并带原因（切模型用 `set_llm_model`）。MIUI 禁触摸注入，用此命令切换后配合 `adb exec-out screencap -p` 验证 UI；首次进入视频模式会弹系统相机权限框，**须真手点允许**（HyperOS 禁 adb 授权，同麦克风） |
| `video_camera` | `ai_arg`= front \| back（省略=翻转） | 视频模式前后摄切换（立即重绑相机）；相机未启动报错 |
| `video_snapshot` | — | 探测视频模式的抓拍环形缓存（每 0.5s 一帧 512×512 JPEG(80)，深 3 帧）：输出最清晰一帧的字节数/清晰度/年龄与缓存深度；空=相机刚起，等 1s 再试。只探测不发送——发送路径由 `send_chat`/按住说话自动附帧 |
| `show_buttons` | `ai_arg`= on \| off（省略=翻转） | 显示/隐藏所有悬浮按钮：打字/语音模式进入时按钮自动隐藏，需要换模型/开设置时用它临时显示；不持久化，切模式/重启回到该模式默认（manual=显示） |
| `set_provider` | `ai_arg`= siliconflow \| volcano \| openrouter | 切换大模型服务商（持久化，设置页下拉框同款语义）：模型清单随服务商切换，存储的模型/音色不在新服务商清单时自动落回该服务商默认（防跨服务商残留）；火山 LLM 需方舟 Ark API Key（与豆包语音的 API Key 是两把钥匙）；OpenRouter 一把 `sk-or-v1-…` key 通吃大模型/TTS/ASR |
| `set_tts_provider` | `ai_arg`= siliconflow \| volcano \| openrouter | TTS 独立服务商切换：自动取消「TTS 与大模型同服务商」勾选并切到目标服务商（模型/音色同样按清单校验回落）；硅基流动 TTS 两模型共用 CosyVoice 音色引用，火山只有 seed-tts-2.0，OpenRouter 只有 voxtral-mini-tts（输出恒 MP3 22.05kHz） |
| `set_asr` | `ai_arg`= cloud \| system | 切换语音识别引擎（持久化）：cloud=OpenAI 兼容云端识别（跟随大模型服务商，key 共用；OpenRouter 音频端点要求 ≥$0.50 余额）；system=系统内置 `SpeechRecognizer`（免费无 Key：GMS 设备走 Google，国产 ROM 走厂商服务如小米 mibrain）。系统识别首用会弹「允许使用语音识别」授权框——等待期间会话保持轮询（用户点允许后自动续上）；虚拟人 SPEAKING 期自动暂停聆听（防 TTS 自回声）。自由说话开着时切换即时生效 |
| `set_language` | `ai_arg`= system \| zh \| en | 切换界面语言（持久化，设置页「语言 (Language)」下拉同款）：system=跟随系统（中文系统→简体中文，其余→英文）；会话身份含语言 → 自动重建会话，身份前言/协议块/视角行/猜拳指令全部按新语言重发（上下文不轮换，历史保留）；系统内置识别的识别语言与 Edge-TTS 默认音色随之切换（英文模式默认 Emma 多语种音色）。生效语言回显 `appLang=… (effective=…)` |
| `set_llm_model` | `ai_arg`=清单内模型 id | 当前服务商下切换大模型（精确 id，清单见 `set_provider` 后的回落默认或代码 AiProviders.kt）：硅基流动视觉模型=`Qwen/Qwen3.8-27B`/`Qwen/Qwen3-VL-32B-Instruct`，火山 4 个 doubao 系全是视觉模型（默认 mini 即可）——视频模式验证用 |
| `skill_status` | — | 猜拳技能状态机快照：state（IDLE/INVITED/ARMED/THROWN/JUDGING）/局数/上次出的手/用户最近手势/最近本地判定/是否已缓存抓拍帧（技能框架 docs/rps-skill-feasibility.md；激活走语音「玩猜拳」或含关键词的 send_chat） |
| `skill_exit` | — | 强制退场激活中的技能（=对用户说「不玩了」的效果）：恢复用户设置的 VAD 句尾悬停，回 IDLE |
| `rps_throw` | `ai_arg`= rock \| scissor \| paper（省略=查状态） | **强制出拳**（须 ARMED 态）：本地随机改为指定手势，播手势 VRMA + 抓帧，state→THROWN；此后下一句话（语音或 `send_chat "三二一"`）即成为裁判回合（带帧+技能指令发给大模型）——真机 A/B 与不开麦验证玩法的关键入口 |
| `rps_gesture` | `ai_arg`= rock \| scissor \| paper（省略=查状态） | **模拟相机确认的用户手势**（P2 本地判定路径，免摄像头/免真手势）：ARMED 收到即本地随机出拳+本地判胜负+`speak()` 即时宣判（无 LLM 往返）；THROWN 收到=纯手势连局开下一拳；下一句话（语音或 `send_chat`）成为气氛组回合（指令带本地判定结果，模型只反应不重判）。验证 P2 链路时大模型无需 vision——判定是本地确定值 |
| `look_status` | — | 「看这边」技能状态机快照：state（IDLE/INTRO/POINTING/ANNOUNCING）/已判定局数/比分（虚拟人:用户）/上次指向方向/本局结论（dodged/caught/frozen）/连续无脸局数/是否存有判负抓拍帧（技能 docs/lookhere-skill-feasibility.md；激活走语音「看这边」或含关键词的 send_chat） |
| `look_throw` | `ai_arg`= up \| down \| left \| right（省略=查状态） | **强制朝指定方向指**：跳过 RNG 立即开/重开一局（播指向 VRMA+喊"看这边!"+判定窗）。两个用途：①屏幕方向标定——逐个执行，人眼看虚拟人实际指向画面的哪一侧，与文件名不符就改 `LookHereSkill.screenToAssetDir` 映射表；②免麦 A/B——强制指向后立刻转头，观察宣判是否正确 |
| `face_pose` | — | 最近一次头部姿态观测：yaw/pitch（度）+ 数据年龄。**符号标定入口**：在「看这边」激活态（视频模式+前摄）依次向自己左/右/上/下转头，记录四组符号，与 `LookHereTuning` 的 `yawPositiveIsScreenLeft`/`pitchPositiveIsScreenUp` 对照，错了改布尔并用已知答案单测锁死。无数据时提示前置条件（技能激活+视频模式+前摄+脸在画面内） |
| `mimic_status` | — | 「模仿我」三段汇总：技能状态机（state=IDLE/INTRO/ACTIVE/BANTER、催促计数、是否见过人）+ 渲染引擎（engaged/restoring/姿态年龄/平滑头部角）+ 相机车道（最新 MimicPose 年龄/可见性）（技能 docs/mimic-skill-feasibility.md；激活走语音「模仿我」或含关键词的 send_chat，激活即自动切视频模式） |
| `mimic_pose` | — | 最近解算的镜像方向集（虚拟人世界系单位向量 + 数据年龄）。**符号标定探针**：在模仿激活态做 T-pose——用户左臂应读出 `Lu=(1,0,0)`（驱动虚拟人**右**臂）、用户右臂 `Ru=(-1,0,0)`；双臂前伸 z=+1（指观察者）。任一轴与推导不符改 `PoseMimicMath.mirrorFrame` + 已知答案单测。无数据时提示前置条件（可用 `mimic_force` 替代探路） |
| `mimic_force` | `ai_arg`= tpose \| left_up \| right_up \| both_up \| forward \| lean_left \| lean_right \| bow \| turn_left \| off（省略=查状态） | **注入合成姿态**（在 MediaPipe 坐标里搭骨架后走与真实检测**完全相同**的解算路径）：免相机/免技能激活直接驱动渲染引擎。用途：①镜像标定——`left_up` 后人眼看虚拟人抬的是哪只手（应抬它自己的右手）；②P2 躯干标定——`lean_left`/`lean_right`/`bow` 看躯干倾斜方向、`turn_left` 看转身方向，方向错了改 `PoseMimicMath.mirrorFrame`；③引擎 A/B（退场缓动/单发 VRMA 让路/与呼吸视线共存）。`off` 撤销注入、引擎缓动回待机 |
| `mimic_face` | `ai_arg`= on \| off（省略=查状态） | P2 表情车道开关（默认 on）：52 ARKit blendshapes → 模型 morph 名映射（`MimicFaceMapper`，ARKit 命名模型直配、VRM 预设模型走别名降级）→ FaceDriver 表情通道。**所有权规则**：说话期嘴部让给口型（jawOpen 等归零）、用户的真实眨眼替代自动眨眼、情绪通道整体让位（blender 状态保留、断供即接回）。off=只模仿身体，表情留在情绪通道——排查表情 vs 身体问题的隔离开关 |

文件名参数不必带扩展名：`load_model AvatarDone` 等价于 `load_model AvatarDone.glb`；
表情名不区分大小写。

## 使用示例

```bash
PKG=com.neethu.aiavatar_sdk

# 查询当前状态（应用未启动时也会先启动再执行）
adb shell am start -n $PKG/.MainActivity --es ai_cmd state
adb logcat -d -s AIDebug        # 读回结果

# 切换人物
adb shell am start -n $PKG/.MainActivity --es ai_cmd load_model --es ai_arg AvatarDone.glb

# 切换场景 / 移除场景
adb shell am start -n $PKG/.MainActivity --es ai_cmd load_scene --es ai_arg living_room.glb
adb shell am start -n $PKG/.MainActivity --es ai_cmd load_scene --es ai_arg none

# 表情（名称用 list expressions 查询）
adb shell am start -n $PKG/.MainActivity \
    --es ai_cmd set_expression --es ai_arg happy --ef ai_weight 0.8

# 播放动作（文件名用 list animations 查询）
adb shell am start -n $PKG/.MainActivity \
    --es ai_cmd play_animation --es ai_arg Angry.vrma --ez ai_loop true

# 移动人物：向右 0.2m、向下 0.1m
adb shell am start -n $PKG/.MainActivity --es ai_cmd move --ef ai_x 0.2 --ef ai_y -0.1

# 相机：放大（等效双指张开 300px）、平移、环绕、复位
adb shell am start -n $PKG/.MainActivity --es ai_cmd zoom --ef ai_x 300
adb shell am start -n $PKG/.MainActivity --es ai_cmd pan --ef ai_x -150 --ef ai_y 50
adb shell am start -n $PKG/.MainActivity --es ai_cmd orbit --ef ai_x 200 --ef ai_y -100
adb shell am start -n $PKG/.MainActivity --es ai_cmd reset_camera
adb shell am start -n $PKG/.MainActivity --es ai_cmd camera_shot --es ai_arg closeup

# 视线（任务 5）：查状态 / 指向世界坐标点 / 回到注视用户 / 关闭
adb shell am start -n $PKG/.MainActivity --es ai_cmd look_at
adb shell am start -n $PKG/.MainActivity --es ai_cmd look_at --ef ai_x 2.5 --ef ai_y 1.0 --ef ai_z -3.5
adb shell am start -n $PKG/.MainActivity --es ai_cmd look_at --es ai_arg camera
adb shell am start -n $PKG/.MainActivity --es ai_cmd look_at --es ai_arg off

# 视频模式（需视觉模型；首次弹相机权限框要真手点允许）
adb shell am start -n $PKG/.MainActivity --es ai_cmd chat_state        # 看 vision= 确认模型收图
adb shell am start -n $PKG/.MainActivity --es ai_cmd set_mode --es ai_arg video
adb shell am start -n $PKG/.MainActivity --es ai_cmd video_snapshot    # 探测抓拍缓存
adb shell am start -n $PKG/.MainActivity --es ai_cmd video_camera --es ai_arg back
adb shell am start -n $PKG/.MainActivity --es ai_cmd send_chat --es ai_arg "'描述你现在通过摄像头看到的画面'"
# ↑ 视频模式下 send_chat 自动附缓存窗内最清晰一帧（logcat 看 AvatarSession 的
#   "multimodal turn: N image(s)" 行）；state 命令输出 video: 相机/人脸/缓存行

# 截图并拉取
adb shell am start -n $PKG/.MainActivity --es ai_cmd screenshot
adb logcat -d -s AIDebug | grep screenshot      # 取绝对路径
adb pull /storage/emulated/0/Android/data/$PKG/files/ai_debug/screenshot_xxx.png

# AI 对话（需先在 ⚙️ 设置里配置 AI 服务）；事件时序（断句/情绪/回合）看 AIDebug 的 chat: 行
adb shell am start -n $PKG/.MainActivity --es ai_cmd send_chat --es ai_arg "'你好，请用三句话介绍你自己'"
adb shell am start -n $PKG/.MainActivity --es ai_cmd chat_state
adb shell am start -n $PKG/.MainActivity --es ai_cmd interrupt_chat

# 对话上下文（历史 Room 落库，杀进程不丢）：新建 / 列出 / 按 id 前缀切回
adb shell am start -n $PKG/.MainActivity --es ai_cmd contexts
adb shell am start -n $PKG/.MainActivity --es ai_cmd new_context
adb shell am start -n $PKG/.MainActivity --es ai_cmd select_context --es ai_arg 299c0080

# 人物卡：把酒馆卡推到应用外部目录后导入（导入即激活，激活即朗读开场白）
adb push card.png /sdcard/Android/data/$PKG/files/
adb shell am start -n $PKG/.MainActivity --es ai_cmd import_card --es ai_arg card.png
adb shell am start -n $PKG/.MainActivity --es ai_cmd list --es ai_arg cards
adb shell am start -n $PKG/.MainActivity --es ai_cmd active_card

# 打开底部面板（MIUI 禁止 shell input tap 时驱动 UI 用）
adb shell am start -n $PKG/.MainActivity --es ai_cmd open_panel --es ai_arg cards

# 不知道有哪些命令时
adb shell am start -n $PKG/.MainActivity --es ai_cmd help
```

> **注意：文件名含空格时必须用内层引号**。`adb shell` 会在设备上再分一次词，
> `--es ai_arg "A Hook Punch.vrma"` 只会把 `A Hook Punch.vrma` 的第一个词传进应用
> （报 `No animations file matches 'A'`）。正确写法是再加一层引号：
> ```bash
> adb shell am start -n $PKG/.MainActivity \
>     --es ai_cmd play_animation --es ai_arg "'Greeting While Standing.vrma'"
> ```

运行中的重复 `am start` 由 `launchMode="singleTask"` 保证路由到已存在的
MainActivity（触发 `onNewIntent`），不会重建界面。

## 推荐的调试循环

1. `ai_cmd=state` 了解当前状态；
2. `ai_cmd=list` 获取合法取值，避免无效文件名；
3. 下发操作命令；
4. `adb logcat -d -s AIDebug` 确认 `OK`/`FAIL` 与参数回显；
5. 需要视觉确认时用 `ai_cmd=screenshot` + `adb pull`（或 `adb exec-out screencap -p`）。

## 实现说明

- 协议解析：`app/.../AiDebug.kt`（纯 Kotlin，含单元测试）。
- 命令执行：`app/.../AiDebugExecutor.kt`，逐条执行并写 `AIDebug` 日志。
- 相机控制：`SoulLinkRenderer` 持有自建的 Filament `Manipulator`（与
  `ModelViewer` 默认构造完全一致），`zoom/pan/orbit/reset` 调用的是手势检测
  （`GestureDetector`）驱动真实触摸时所用的同一组 Manipulator API，因此行为
  与手指操作一致；其中缩放比例按 GestureDetector 的 pinch 系数（0.1/像素）换算。
- 人物移动：直接对模型根节点变换的平移列做增量，与拖拽模式同一代码路径。
