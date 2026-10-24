# 森林梦境卧室（dream_bedroom）接入实录

> 2026-10-24。资产包 `assets/dream_bedroom/`（用户经 Blender MCP 制作+拆墙重导）
> 接入 SDK：默认场景替换、墙体自动剖切（防遮挡）、家具交互点接触定姿。
> 资产规格以包内 README/interactions.json 为准；本文记 SDK 侧实现与真机结论。

## 一、交付内容

| 层 | 文件 | 内容 |
| --- | --- | --- |
| 资产 | `app/src/main/assets/scene/dream_bedroom.glb` | 8×7m 闭室卧室，墙/地板/天花板/玻璃独立节点（用户重导） |
| 资产 | `scene/dream_bedroom.meta.json` | 场景平移 (0,-1,-4) + 走界 + **spawn 出生点**（SPAWN 交互点） |
| 资产 | `scene/dream_bedroom.anchors.json` | 12 个走位锚点 + 11 条双向边（MoveAnchor 格式，waypoint 图） |
| 资产 | `scene/dream_bedroom.interactions.json` | 16 个交互点（tools/gen_dream_interactions.py 从资产包 interactions.json 投影：SDK 世界坐标 + approach 映射 + 坐姿剪辑 + 视线目标） |
| 资产 | `scene/dream_bedroom.cutaway.json` | 剖切组声明（用户随 GLB 导出：4 墙 + 天花板，renderableNodes 显式列成员） |
| corelib | `internal/SceneOcclusionController.kt` | 防遮挡：相机→身体检测线 × 剖切组世界 AABB，命中 setLayerMask(0xFF,0x00)+关阴影，0.25s 无遮挡回显 |
| corelib | `internal/OcclusionMath.kt` | 纯数学：线段-AABB slab 相交、AABB 变换/扩张（JVM 单测） |
| corelib | `internal/ContactPoseController.kt` + `ContactPoseMath.kt` | 接触定姿状态机：NONE→ENTERING（转身+坐姿过渡剪辑）→ALIGNING（hips 实测吸附）→HOLDING→EXITING（回起姿矩阵+heading 重同步）；LIE=根旋转平躺方案 |
| corelib | `internal/ContactPose.kt`（公共类型） | ContactKind/ContactPoseRequest/Result/Phase/Snapshot |
| corelib | `AvatarController` 新 API | applyContactPose/clearContactPose/getContactPoseState/setAvatarWorldPosition/turnTo/setOcclusionEnabled/isOcclusionEnabled/getOcclusionDebugInfo；WalkStartResult 增 `POSE_ACTIVE` |
| corelib | `LocomotionEngine.resyncHeadingRad` | 外部 root 旋转后引擎 heading 重同步缝 |
| orchestrator | `AgentToolCatalog.interactWith/standUp` + `LocomotionDriver.Outcome.POSE_ACTIVE` + PromptTexts 双语 ~10 键 | interact_with（异步起步语义同 move_to）/ stand_up 两工具 |
| app | `InteractionAnchors.kt` + `InteractionCoordinator.kt` | 交互点解析（kotlinx）+ 就位链编排（走位→转身→定姿→视线，到达判定=轮询去抖非事件） |
| app | MainActivity | 默认场景=dream_bedroom（存档缺失时）；spawn 按场景应用；anchors/interactions 按场景重注入；move_to 包一层自动先起身；interact_with/stand_up 注册 |
| app | AiDebugExecutor | ai_cmd 新命令 `interactions`/`interact`/`pose_state`/`pose_clear`/`occlusion` |

## 二、墙体自动剖切（防遮挡）

- **数据源**：`<场景名>.cutaway.json`（与 GLB 同名）。`groups[].node` + `renderableNodes`
  按组隐藏；无 sidecar 回退节点名前缀（WALL_/GLASS_/CUTAWAY_/CEILING）。
- **判定**：每帧从相机眼到头/胸/髋三条线段，与组内 renderable 世界 AABB 并集
  （margin 0.06m）做 slab 相交；任一命中即整组隐藏（layerMask bit0 清零 +
  castShadows 关，阴影同步消失避免「墙没了影子还在」）。移开后持续 0.25s 才恢复（防沿墙扫视闪烁）。
- **开关**：`AvatarController.setOcclusionEnabled`（ai_cmd `occlusion on|off|state`），默认开。
- **世界 AABB 缓存**：attach 时算一次，`setScenePlacement` 后失效重算。计算必须在每帧
  TransformManager commit 之后（choreoCallback 中 updateLocomotion 之后）。

### 踩过的三个坑（都进了代码注释）

1. **`RenderableManager` 的 `@EntityInstance` 参数收的是 `getInstance(entity)` 的
   instance，不是 entity 本体**——传 entity = 调用静默落空（遮挡报告"已隐藏"但画面
   不变）。与弹簧骨骼 `getParent` entity/instance 混用同类坑。
2. **场景 AssetLoader 不带 NameManager，`asset.getName(entity)` 恒 null**——前缀
   发现路径不能用名字去重/识别，用实体 id；sidecar 路径的 `getFirstEntityByName`
   走另一张 native 名字表不受影响。
3. **大背景板（FOREST_BACKDROP）刻意不进候选**——弯曲地形网格的局部 AABB 达
   22×19m，世界盒罩住整个房间，相机恒「在盒内」→ 任何机位都误判遮挡 → 窗外永远
   变灰天空盒。剖切组以 sidecar 契约为准，别按前缀贪多。

## 三、家具交互点（接触定姿）

- **走位**：stand/approach/waypoint 点 → `driver.walkTo`（waypoint 图路由，多段腿）
  → `controller.turnTo(yawDeg)` 原地转向。sit/lie 点 → 先走到 `approach` 接近点。
- **定姿**（corelib ContactPoseController，per-frame 在 updateLocomotion 之后）：
  - SIT：转身到 anchor.yawDeg → 播 `sitInClip`（Standing To Sitting Transition，
    one-shot）→ 播完沿实测 hips 世界位差值平移 root（`hipsTarget = 接触面 +
    hipsLift 0.09m`）→ crossfade `sitLoopClip`（书桌椅=Seated Idle With Hands On A
    Table、阅读椅=Elbows On Legs Leaning Forward、床沿=Sit With Fidgeting Feet）。
  - LIE：内置库无躺卧 VRMA → **根旋转方案**：`R_target = Ry(yaw+180°)·Rx(−90°)·R_stand`
    （slerp 0.5s，保住 unit-cube 缩放与 VRM0 Y-180 翻转），hips 吸附同 SIT，身体保持
    idle 循环（呼吸仍在，读作安静平躺）。
  - EXITING：SIT 播 standUpClip（Standing Up From Seated Position），root 动画回
    「定姿开始时抓拍的 standMatrix」（旋转 slerp+平移 lerp），完成后
    `locomotionEngine.resyncHeadingRad` 重同步。
- **朝向约定**：交互点 yawDeg 0=+Z，SIT 的 yaw=身体面向；LIE 的 yaw=**脚→头长轴**。
  root 矩阵列 2 在 VRM1=belly 方向、VRM0 被烘焙翻转取负——heading 提取必须带
  `isVrm0` 符号（renderer.rootHeadingRad）。
- **互斥**：定姿期间 `startWalk/startStrafe` 返回 `WalkStartResult.POSE_ACTIVE`；
  app 层 move_to 工具包了一层「先 clearContactPose + 900ms 再走」。

## 四、场景切换与出生点

- 默认场景（存档缺失时）= `dream_bedroom.glb`；设置面板/`load_scene` 可切回 home/debate。
- **anchors/interactions 全部按场景名解析**（`scene/<名>.anchors.json`，无专属表退回
  home.anchors.json 兜底）；注入 effect 以场景为键重跑——修掉 README 点名的
  「换场景沿用旧词表」问题。
- meta 可选 `spawn: {x,y,z,yawDeg}`：模型 Ready 后 `setAvatarWorldPosition` 落点
  （闭室地板不在 y=0，不落点=悬空 1m）。按场景只应用一次。

## 五、真机验收（2026-10-24，2c3769db/Redmi K20 Pro，debug 变体，全程 60FPS 零 FATAL）

- 场景加载：101 实体、occluders=5（4 墙+天花板）、spawn 落点日志正确。
- 防遮挡：相机在房外四侧逐一绕转，对应墙组即时隐藏/0.25s 恢复（南→东→西北全走查，
  截图目验窗外森林不受影响）；`occlusion off` A/B 正常。
- 走位：`interact bed_sit/desk_seat/reading_seat/bed_lie` 全链通过——多段腿 waypoint
  路由（床边→地毯→书桌）、到达转身、坐姿三处 hips 吸附、躺姿根旋转（头朝枕区、
  弹簧骨骼垂落自然）、起身回起姿点。
- 工具通道：`tools_list` 17 工具含 interact_with/stand_up；`tools_call interact_with`
  全链（错误文案附合法值白名单）；anchors 词表按场景生效（`walk_to carpet` 正确报未知）。
- **已知缺口**：qwen3.8-flash（百炼）对「去坐下」自然语言两次零调用（toolRounds=0，
  宣布动作但不调工具）——agent-provider-matrix 已记录的同族可靠性问题，提示词注入
  正确（系统提示含完整 hint）；工具层/ai_cmd 通道不受影响。自然触发可靠性留待
  provider 侧迭代（required 重试对非悬空句型无效）。
- **已知取舍**：家具不参与自动隐藏（GPT 方案约定）——相机绕到书桌正后方会被桌面挡
  （物理正确）；坐/躺姿下 camera_shot 的 span=头髋距离被压缩，静态机位偏近且偶发
  「transition timed out」（运镜系统既有限制，非本次引入，follow 机位不受影响）。

## 六、复验入口

```bash
python tools/blender/validate_dream_bedroom.py     # GLB/锚点/导航一致性
python tools/gen_dream_interactions.py             # 重投影交互点 sidecar
./gradlew :app:testDebugUnitTest :corelib:testDebugUnitTest   # 2299 全绿
# 真机：
#   ai_cmd interactions / interact bed_sit / pose_state / pose_clear
#   ai_cmd occlusion on|off|state；camera_shot follow + orbit 绕视验收
```
