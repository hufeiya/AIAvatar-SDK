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
| `state` | — | 输出当前状态：模型 / 场景 / 动作 / 表情 / 相机位姿 / FPS / 可用表情列表 |
| `list` | `ai_arg`= models \| scenes \| animations \| expressions \| cards | 列出可用资源（调用其他命令前先查有效取值） |
| `load_model` | `ai_arg`=文件名 | 切换人物（assets/vrms 下的 .glb/.vrm），自动重置表情 |
| `load_scene` | `ai_arg`=文件名 \| none | 切换场景（assets/scene 下的 .glb）；`none` 移除场景 |
| `set_expression` | `ai_arg`=表情名，可选 `ai_weight` | 设置表情；应用前会清空旧表情 |
| `clear_expression` | — | 清空全部表情，恢复中性表情 |
| `play_animation` | `ai_arg`=文件名，可选 `ai_loop` | 播放 assets/animations 下的 .vrma 动作 |
| `stop_animation` | — | 停止动作，恢复待机姿态 |
| `move` | `ai_x` `ai_y` `ai_z` | 平移人物（世界坐标米）：+x 右、+y 上、+z 朝相机 |
| `zoom` | `ai_x` | 相机推拉，取值为「双指张开距离」像素：正数放大、负数缩小 |
| `pan` | `ai_x` `ai_y` | 相机平移（屏幕像素，等效双指拖动） |
| `orbit` | `ai_x` `ai_y` | 相机环绕（屏幕像素，等效单指拖动） |
| `reset_camera` | — | 相机恢复初始机位 |
| `camera_shot` | `ai_arg`= closeup \| medium \| full \| long \| over \| off | 平滑运镜到预设机位：closeup 面部特写、medium 中景半身、full 全景全身、long 远景（大动作舞蹈用）、over 反应侧景；`off` 释放回自由相机 |
| `set_drag_mode` | `ai_arg`=on \| off | 拖拽模式：on 时手指拖动人物而非旋转相机 |
| `open_panel` | `ai_arg`= none \| models \| animations \| expressions \| scenes \| cards \| settings | 打开/关闭对应底部面板（MIUI 禁止 shell 注入点击，用此命令驱动 UI 面板） |
| `spring_debug` | `ai_arg`=on \| off | 弹簧骨骼诊断：on 时每秒向 logcat（tag `SpringBone`）输出各弹簧链根/梢关节的骨长 len=当前/静止 与方向 dir，用于真机物理排查 |
| `screenshot` | — | 渲染一张 PNG 到应用外部目录，绝对路径打印到 logcat（可直接 `adb pull`） |
| `send_chat` | `ai_arg`=消息文本 | 发送一条用户消息（等价于聊天条发送），走 LLM 流式→断句→TTS→口型全链路；断句/情绪/回合事件时序打印到 `AIDebug`（`chat:` 前缀） |
| `interrupt_chat` | — | 打断当前 AI 回合（等价于聊天条 ✕），立即静音并回到 IDLE |
| `chat_state` | — | 输出 AI 对话状态：phase（IDLE/THINKING/SPEAKING）/ 字幕长度 / 最近错误 |
| `import_card` | `ai_arg`=文件路径 | 导入并**自动激活**一张酒馆人物卡（SillyTavern PNG/JSON）。arg 为绝对路径或相对应用外部目录（`/sdcard/Android/data/<pkg>/files/`，先 `adb push` 到这里，无需权限）的相对路径；激活即重写系统提示词，已配 TTS 时自动朗读开场白（含 `{{char}}/{{user}}` 宏替换） |
| `active_card` | — | 输出当前激活卡片：文件名 / name / spec / version / 开场白长度 / 系统提示词长度与前 100 字符（卡片未激活输出 `no active card`） |

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

# 截图并拉取
adb shell am start -n $PKG/.MainActivity --es ai_cmd screenshot
adb logcat -d -s AIDebug | grep screenshot      # 取绝对路径
adb pull /storage/emulated/0/Android/data/$PKG/files/ai_debug/screenshot_xxx.png

# AI 对话（需先在 ⚙️ 设置里配置 AI 服务）；事件时序（断句/情绪/回合）看 AIDebug 的 chat: 行
adb shell am start -n $PKG/.MainActivity --es ai_cmd send_chat --es ai_arg "'你好，请用三句话介绍你自己'"
adb shell am start -n $PKG/.MainActivity --es ai_cmd chat_state
adb shell am start -n $PKG/.MainActivity --es ai_cmd interrupt_chat

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
