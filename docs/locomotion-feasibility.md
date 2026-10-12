# 虚拟人自由走动(Locomotion)可行性分析

> 2026-10-11 预研。回答四个问题:①three-vrm 的"非原地动画"是怎么实现的;②非原地之后,动画之间怎么衔接过渡;③走动时相机怎么运镜;④AI 自由在房间走动的落地路径。
> 关联文档:`docs/agent-feasibility.md`(P0 工具调用,本文的 AI 决策通道复用它)、`docs/mimic-skill-feasibility.md`(车道/仲裁模板,本文照抄其生命周期)、`docs/ai-layer-handoff.md`(坑清单,本文新增条目会并入)。
> **状态(2026-10-13):P0+P1+P2 均已实现,P2 于 2026-10-13 真机验收通过。**P0(程序走动核心)2026-10-11 验收;P1(AI 通道)2026-10-12 验收(锚点踩点/`<move:>` 全链自然触发/越界 clamp/mimic 互斥/双模型,详见 ai-layer-handoff.md 第二节+坑 25/26/27)。**P2(2026-10-13)五项全落地**:双槽 crossfade(引擎 base+overlay 槽,起步/停步/one-shot→idle 全部淡入淡出,`walk_fade` 0.25s 默认开)、上半身 mask 边走边比划(走动中 one-shot 手势自动以 UPPER_BODY_BONES 掩码 overlay 叠加,播完自动淡出,`walk_overlay` 开关)、方案 C 位移提取(LocomotionExtractor 纯类分析 hips 轨道前向分量,引擎剥离+渲染层回灌 root,`Female Normal Walk` 实测带 1.49m/循环烘焙位移——"原地"是误认——提取模式脚步与位移按构造严格同步;run/strafe 素材 baseSpeed 由提取自动标定)、waypoint 图(links 无向边+BFS 路由,home.anchors.json 配 links,多段腿 driver 自动续走)、跑步/侧移步态(Running Forward + Walking Strafe Left/Right 内置,speed≥1.9 自动跑步,`walk_strafe left|right` 朝向锁死横移)。真机验收(2c3769db,VRM0+VRM1 双模型):起步/停步 crossfade 无 T-pose 闪帧、边走边出拳(上半身出拳+腿部步态同帧)、两段腿 bed→carpet→window 自动续走、全程 60FPS 零崩溃。验收中排掉两个真坑:**bindToModel 陈旧世界读数**(TransformManager 惰性求值, hips 高度错 7×,已 commit 后读;见坑 28)+**session 重建僵尸 collector**(续腿 relay 挂 init 被旧实例收走,改惰性幂等注册;见坑 29)。单测 832 全绿。**

---

## 0. 结论速览

1. **three-vrm 的"非原地"没有任何黑魔法**。VRM Animation 规范只允许 hips 一根骨带 translation 通道;非原地素材把前向位移逐帧烘焙进 hips 平移,three-vrm 播放时把它直接写进 hips 节点的 position——hips 是人形骨骼链的根,改它等于搬动整个角色。three-vrm v3.5.1 全仓库 grep `locomotion|rootMotion|crossfade|blend` 为 0 命中:**没有状态机、没有混合、没有导航,locomotion 全部留给用户代码**(§2)。
2. **咱们 SDK 的同款机制已经就绪**:`VrmaParser` 解析 hips translation(`VrmaParser.kt:331`),`VrmaAnimationEngine` 每帧把"相对 rest 的 delta × 身高比 + rest 本地平移"写进 hips 节点(`VrmaAnimationEngine.kt:260-287`)。**今天喂一个非原地 VRMA,真机就会走**——现在的动作全是原地,只是因为 343 个素材策展时全选了原地(§2.5)。
3. 但"自由走动"主路线**不推荐靠非原地素材**:位移写死在关键帧里,终点不可控、循环播放每圈跳回起点、一次性播完 idle 接管时身体瞬移回原点。主路线选**原地步态素材 + 程序化根位移**(合成 root motion),`moveAvatar` root 平移入口现成(`SoulLinkRenderer.kt:1748`);非原地素材用"运行时位移提取"(方案 C)在 P2 兜底接入(§4)。
4. **过渡衔接有一条被忽视的捷径**:素材库 `09_状态过渡/` 分类里已经有 Idle To Walk / Walk Forward To Idle / Walk 180 Turn 等过渡剪辑(Mixamo 状态过渡系)。P0/P1 用"状态机 + 过渡素材"就能自然衔接,**不需要先建 crossfade 混合引擎**;双槽 crossfade(仿 three.js `AnimationMixer.crossFadeTo`)作为 P2 基建(§5)。
5. **相机跟随的改动极小**:`updateCameraShot`(`SoulLinkRenderer.kt:1869-1949`)已经是"逐帧测误差 → grab/orbit/scroll 原语指数收敛"的闭环,只是到位后清了 `shotSteering` 标志(`:1946-1948`)。FOLLOW 模式 = 到位不清标志 + pivot 换 hips 动态世界锚点,闭环代码一行不用重写(§6)。
6. **AI 决策两条通道都通**:V1 用标签 `<move:sofa>`(与 `<cam:xxx>` 同族,异步不阻塞说话),P1 用工具 `move_to`(P0 工具调用刚落地,与 `observe_camera` 同构,可阻塞等待到达后让 LLM 接着说)(§7.3)。
7. 分期:P0 程序走动核心 + ai_cmd 手动驱动 + FOLLOW 相机 + home.glb 对位;P1 标签通道 + 锚点表 + 转向;P2 crossfade / mask 分层 / 位移提取 / waypoint 图。P0 约 2-3 天(§11)。

---

## 1. 问题定义:一次"自由走动"拆成五个子问题

### 1.1 目标体验

用户对 AI 说"你去窗边看看" → 虚拟人从原地转身 → 自然起步走向窗边(脚步与位移同步,不滑步)→ 相机平稳跟拍 → 到达窗边停下 → 转身面向用户继续说话。全程可以边走边说,TTS 不断流。

### 1.2 五个子问题

| # | 子问题 | 本文章节 |
|---|--------|---------|
| 1 | 位移从哪来(非原地动画的本质) | §2 §4 |
| 2 | 步态与位移怎么衔接过渡(起步/停步/转向/手势) | §5 |
| 3 | 相机怎么跟拍 | §6 |
| 4 | 房间在哪、怎么知道能走到哪 | §7.1-7.2 |
| 5 | AI 怎么决定走(标签 / 工具) | §7.3-7.4 |

### 1.3 范围钉死(本篇不做)

- **任意动作生成**(文生动作/视频动捕):那是"动作资产从哪来"的另一单生意,本文只解决"走"这一类动作;现有来源(Mixamo 爬取/DeepMotion/Pico 录制)继续适用。
- **物理引擎与刚体碰撞**:走动不做物理,边界用几何 clamp。
- **AR 平面检测**:虚拟场景内走动,不涉及真实空间理解。
- **障碍物动态避让**:V1 靠锚点摆放保证通路,V2 用 waypoint 图。

---

## 2. three-vrm 源码解读:非原地动画的本质(问题①)

源码:`/home/neethu/projects/mtoon/three-vrm`(pixiv/three-vrm,v3.5.1,与项目 model-viewer vendor 的版本一致)。

### 2.1 VRMA 规范:humanoid 平移只有 hips 一根骨

- `VRMAnimation` 的轨道结构里,平移轨道的键类型被硬编码为字面量 `'hips'`(`packages/three-vrm-animation/src/VRMAnimation.ts:15`)——这不是实现偷懒,是规范约束:`VRMC_vrm_animation-1.0` 只允许 hips 有 translation 通道。
- loader 对非 hips 的 translation 通道警告并丢弃(`VRMAnimationLoaderPlugin.ts`)。
- 规范语义(vrm-c/vrm-specification):humanoid 节点动画以 T-pose 为基准,T-pose 时刻节点应处于 rest 变换;hips 的 translation 即相对该基准的逐帧位移。**原地素材的 hips 平移只有呼吸般的上下浮动;非原地素材的 hips 平移带有持续的前向分量——"原地/非原地"纯粹是素材烘焙方式的区别,规范和加载器层面两者一视同仁。**

### 2.2 加载链路:hips 平移如何被换算与保留

`VRMAnimationLoaderPlugin`(解析 `VRMC_vrm_animation` 扩展):

1. 在 VRMA 文件自己的 T-pose 节点层级上取各骨骼 `matrixWorld`,并单独缓存 hips **父节点**的世界矩阵(`:150-178`,`worldMatrixMap.set('hipsParent', …)`);
2. translation 通道逐帧乘上 `hipsParentWorldMatrix`,把"相对 VRMA 内 hips 父节点的局部位移"换算成 VRMA 场景的**世界坐标绝对位置(含 T-pose 站高)**,keyframe 全保留、无抽取压缩(`:212-228`);
3. 没有任何 Z-up 归一化(git 全历史无 `_needsZupNormalize`,v3.x 已移除);VRM0 兼容只剩两处:x/z 取反 + 四元数翻转(`createVRMAnimationClip.ts:27-31,44`)。

### 2.3 播放链路:写 hips position = 搬动全身

`createVRMAnimationClip.ts:36-52`:

```ts
const scale = humanoidY / animationY;            // 按模型站高比缩放,适配不同身高模型
track.values = track.values.map((v, i) => (metaVersion === '0' && i % 3 !== 1 ? -v : v) * scale);
track.name = `${nodeName}.position`;             // 目标 = 归一化骨架的 Normalized_hips
```

- 轨道直接写 `Normalized_hips.position`(归一化骨架根挂在场景原点,`VRMHumanoidRig.ts:70-79`);
- `vrm.update(dt)` → `VRMHumanoidRig.update()`(`VRMHumanoidRig.ts:112-137`)把归一化骨架 hips 的世界位置反算回真实 hips 节点的 `node.position`;
- **hips 是人形骨骼链的根,改它的 position 就带动整条骨骼链和 skinned mesh 在场景中位移——这就是全部"root motion 效果"。没有单独的 root node、没有位移/旋转分离逻辑。**`restHipsPosition`(`VRMAnimation.ts:12`)是官方留给用户做二次处理(如位移剥离)的唯一钩子。

### 2.4 结论:非原地是素材属性,不是引擎能力

- three-vrm 本体没有 locomotion 状态机、没有 blend tree、没有 crossfade 工具、没有 root motion 提取器(grep 全仓库 0 命中,仅两处注释提及 `AnimationMixer`)。
- 社区通行做法:非原地直接信任素材位移;混合用 three.js `AnimationMixer` 的 `fadeIn/fadeOut/crossFadeTo` + `timeScale` 自己写;程序移动则推 `vrm.scene` 根节点。**airi 也没有 locomotion 层。**
- 自带的两个示例(`loader-plugin.html` / `dnd.html`)均为"加载→建 clip→mixer 播放"三步,无任何移动逻辑。

### 2.5 对照:咱们引擎的同款机制(已就绪)

Android 端走的是同一条数学路,锚点:

| 环节 | three-vrm | 本仓库 |
|------|-----------|--------|
| 解析 hips 平移 | `VRMAnimationLoaderPlugin.ts:212-228` | `VrmaParser.kt:331`(只解析 hips;`:334-343` 用 hipsParent 世界旋转归正) |
| rest 基准 | `restHipsPosition` | `VrmaParser.kt:242-267` |
| 身高比缩放 | `createVRmaAnimationClip.ts:40-42` | `VrmaAnimationEngine.kt:264-267`(`targetHipsY/animHipsY`) |
| VRM0 兼容 | x/z 取反 | `VrmaAnimationEngine.kt:276-279` |
| 写入 | 写 `Normalized_hips.position` | `applyTranslation` 直接写 TransformManager(`VrmaAnimationEngine.kt:321-329`) |

一处实现差异值得注意:咱们写的是 **delta 语义**——`pos = rest本地 + (采样值 − 素材rest hips) × 身高比`(`VrmaAnimationEngine.kt:268-286`),角色始终以模型自身 rest 为基准,只叠加相对位移。这意味着:非原地素材的一次性播放,身体会真实前移,但 **hips 局部平移不含根位移,root 不动;素材播完 idle 接管时 hips 回到 rest,身体瞬移回原点**。这是方案 A 的致命伤之一(§4.1),也是方案 C"位移提取"要解决的问题。

---

## 3. SDK 现状盘点:自由走动要买的五样东西,仓库已有什么

### 3.1 位移:root 平移入口现成

- `SoulLinkRenderer.moveAvatar(dx,dy,dz)`(`SoulLinkRenderer.kt:1748-1759`):root 矩阵 `mat[12..14]` 直接累加,注释锁死坐标语义(+X 右 / +Y 上 / +Z 朝相机);`AvatarController.moveAvatar`(`AvatarController.kt:571-573`)已透传;ai_cmd `move` 命令已有(`AiDebugExecutor.kt:162,610`)。
- hips 层与 root 层的叠加路径已被拖拽功能打通:VRMA 每帧写 hips 局部 → `hipsRewritten` 标记(`VrmaAnimationEngine.kt:330`)→ 渲染器消费并重钉拖拽偏移(`SoulLinkRenderer.kt:1711-1736`,`HipsDragOffsetSolver`)。root 位移与 hips 动画位移天然正交(父子关系),互不覆盖。

### 3.2 过渡:单槽硬切 + 一批现成的"过渡剪辑"

- 引擎是**单动画槽**:`currentAnimation` + `idleAnimation`(`VrmaAnimationEngine.kt:36,60`),last-cue-wins,硬切靠策展掩盖("候选 clip 尾帧接近 rest pose",`:291-295` 注释);无多剪辑、无权重、无 crossfade、无 playbackSpeed(全 corelib grep 无 timeScale)。
- 一次性播完自动接管 idle(`:296-307`),idle 接管用 `idleAnchor` 做相位归零(`:226-228`)。
- **素材侧的意外收获**:`09_状态过渡/` 分类共 8 个状态过渡剪辑——`Idle To Walk 180`、`Walk Forward To Idle`、`Walk 180 Turn`(Male/Female 各一套)。这是 Mixamo 状态过渡系素材,天然就是起步/停步/转身的"缝合剪辑",配合状态机即可做到无需混合引擎的自然过渡(§5.2 档 1)。
- 步态素材:根目录 `Female Normal Walk.vrma`、`Catwalk Walk.vrma`,另有走路变体 6 个(打电话走/边走边看手机/搬箱走等)。全库 343 个 VRMA。

### 3.3 相机:闭环运镜现成,只缺"跟随"语义

- `CameraShot` 6 档(`CameraShot.kt:14-56`):CLOSE_UP / MACRO / MEDIUM_SHOT / FULL_SHOT / LONG_SHOT / OVER_SHOULDER。
- `updateCameraShot`(`SoulLinkRenderer.kt:1869-1949`):每帧算目标位姿 `shotPose`(pivot 挂 head/chest/hips 骨骼世界坐标,`:1957-2005`),误差按 `step = 1-exp(-dt·RATE)` 指数收敛,三原语依次修正:strafe-pan(`grabBegin/Update/End strafe=true`)→ orbit → scroll dolly;到位判定通过后 `shotSteering = false` 相机静止(`:1946-1948`)。
- 相机 target 由 Manipulator 内部维护(`getLookAt`,`:1807-1813`),不绑定任何模型节点——**没有"跟踪移动目标"的钩子,但闭环本身每帧重读骨骼锚点,天然支持移动 pivot**。
- 用户触摸 `ACTION_DOWN` 会 `cancelShotTransition()`(`:480-484`),程序与手势共存机制已有。

### 3.4 场景:home.glb 已内置但无对位,模型悬空

- 默认无地面,只有 Skybox + IBL(`SoulLinkRenderer.kt:825-831,1195-1214`)。
- `app/src/main/assets/scene/home.glb`(30MB)已内置,`loadScene`(`SoulLinkRenderer.kt:1495-1530`)原样加载、**无任何变换/对位代码**;demo 接线 `MainActivity.kt:954-957`,ai_cmd `load_scene` 可拉起。
- 模型经 `transformToUnitCube()` 中心落在 `(0,0,-4)`、最大边缩放为 2m,脚约在 `y=-1`(`MODEL_CENTER`,`SoulLinkRenderer.kt:217-219`)——**悬空无地平面**。走动前必须做一次场景对位标定(§7.1)。
- 模型朝向惯例:VRM0.x root 加载后乘 Y-180 翻转(`:968-999`),面朝 +Z(朝相机);VRM1.0 不翻。位移/转向实现必须兼容两种(§10 坑 5)。

### 3.5 决策通道:标签与工具两条路都通

- **标签链路**:`InlineTagExtractor.feed` → `TagCue`(adapter `TagCue.kt:15-33`,Emotion/Action/Camera 三族)→ `dispatchCues`(`AvatarSession.kt:818-866`)→ `gestureDriver.play` / `dispatchCamera`(`:874-883`)。相机标签就是"名字查表映射"(`CAMERA_SHOTS` 表,`:1204-1212`),`<move:xxx>` 可以完全同构。
- **工具链路**:P0 工具调用已真机验收。`AgentToolCatalog` 工厂返回 `Pair<ToolSpec, ToolExecutor>`(`AgentToolCatalog.kt:48,64,92` 三个内置工具),`AvatarSession.init` 默认注册(`AvatarSession.kt:309-314`),systemHint 自动聚合进钉住协议块(`:1083-1088`),`ToolRegistry.invoke` 自带参数预校验/超时/协作取消(`ToolRegistry.kt:84-108`)。**加一个 move_to 工具 = 一个工厂函数 + 两条 PromptTexts 文案**,与 observe_camera 同构。

### 3.6 每帧挂点与写入者仲裁(照抄 mimic 车道模板)

- 渲染主循环 `choreoCallback`(`SoulLinkRenderer.kt:306-420`)的骨骼写入顺序固定:VRMA → hipsDrag 重钉 → breath → lookAt → mimic → commit → spring bone。**locomotion 层的挂点:VRMA 分支之后、breath 之前**(与 hipsDrag 同级,写 root/hips 平移,不碰 rotation)。
- orchestrator/app 侧没有逐帧 tick,决策层只发指令不逐帧参与——mimic 已验证这套"volatile 原子换手 + 断供自愈 + one-shot 让路"的生命周期模板(`VrmPoseMimicEngine.kt:123,192-235`),locomotion 照抄。

---

## 4. 核心选型:位移从哪来(问题①的落地方案)

### 4.1 方案 A:非原地素材直接播(不推荐做主路线)

把带 root motion 的 VRMA(如 Mixamo 不勾 In Place 转出来的)直接喂给现有引擎。

- ✅ 零代码,今天的引擎就能"走"。
- ❌ **终点不可控**:位移幅度写死在 keyframes,LLM 说"走到沙发"无法精确落地。
- ❌ **循环跳变**:hips 每圈从起点重新开始,loop 播放 = 走一步瞬移回原点。
- ❌ **播完回弹**:delta 语义下 idle 接管时身体瞬移回原点(§2.5)。
- ❌ 转向、速度全部锁死在素材里。
- 定位:作为**风格化点缀**保留(走上台/踱步/滑步舞这类"演一段"的场景),不做自由走动主路线。

### 4.2 方案 B:原地步态 + 程序化根位移(★主路线)

原地 walk VRMA 继续管下半身姿态和步频,新增 LocomotionController 每帧推 root:

```
每帧:root += forward(heading) × speed × dt      // moveAvatar 同路径写 root
      转向:root 3×3 旋转块左乘 Ry(Δheading)     // 平移列不动,原地转体
```

- ✅ 复用现有 334 库,walk 素材已有,零新增素材。
- ✅ 终点、速度、转向全是运行时参数,LLM 可精确控制。
- ✅ 位移与 hips 动画正交(父子关系),拖拽重钉机制已验证叠加路径(§3.1)。
- ✅ 无循环跳变、无回弹(root 位移只进不出,停步即定格)。
- 速度标定(防滑步的关键):步态速度 = 步幅 × 步频。步幅用脚本对 VRMA 做前向运动学算踝关节世界轨迹(新建 `tools/walk-calib/`,Node 读 GLB,`tools/springbone-sim/` 有先例);真机再用 ai_cmd 微调。需要给引擎加 `playbackSpeed`(timeScale)来微调步频匹配位移速度,否则只能位移迁就步频。
- 转向实现(照写进设计的细节):root 矩阵 `mat[0..10]` 的 3×3 块**左乘** `Ry(Δ)`,`mat[12..14]` 平移列不动——等效于绕 root 原点原地转体,`transformToUnitCube` 烘进矩阵的缩放不受影响。前向向量与 VRM0 翻转复合的符号,真机标定一次后锁死(参照 mimic 两轴符号三轮标定的教训)。

### 4.3 方案 C:运行时位移提取(A+B 合体,P2 增强)

每帧把非原地素材的 hips 平移**拆两半**:水平前向分量抽出来积分进 root 位移,竖直分量留在 hips(保上下起伏)。效果:任意非原地素材变成可循环、可控速、不回弹的步态,VrmaParser 的 delta 计算现成(`VrmaParser.kt:331-345`),只需在 `VrmaAnimationEngine` 的 translation 分支加一个"分量分流"开关。这是把 Mixamo 全库(含不勾 In Place 的)和 Pico 录制(天然带位移)变成 locomotion 资产池的钥匙,但 P0/P1 用不到。

### 4.4 选型结论

**B 为主路线,C 为 P2 增强,A 作风格点缀。**理由:B 的所有组件(步态素材/root 入口/正交性验证)今天已就绪,新增代码集中在一个控制器里;A 的三个致命伤都无解;C 是锦上添花且有实现风险(素材位移轨迹不干净时会抖)。

---

## 5. 状态机与过渡设计(问题②)

### 5.1 状态机(LocomotionEngine,corelib 内部)

```
IDLE ──startWalk(target)──▶ STARTING ──▶ WALKING ──arrived──▶ STOPPING ──▶ IDLE
                                 │              │
                                 └──turn(dir)───┘   (WALKING 内 heading 连续插值)
任何状态 ──pause(for gesture)──▶ PARKED ──resume──▶ 原状态
```

- STARTING/STOPPING 播 `09_状态过渡/` 的起步/停步剪辑(档 1,见下);WALKING 循环播放 walk 素材 + root 匀速(或缓动)位移。
- 到达判定:距离目标 < 阈值(0.05m);P1 升级为提前减速(制动距离 = v²/2a)。
- 所有参数 volatile 换手注入(仿 mimic),决策层不持有引擎引用。

### 5.2 过渡三档(成本递增,P0 不建混合引擎)

| 档 | 机制 | 成本 | 效果 |
|----|------|------|------|
| 档 0(现状) | 硬切 + 策展(尾帧≈rest) | 0 | idle↔act 可用,起步/停步会跳 |
| 档 1(P0/P1) | **过渡素材**:起步播 `Idle To Walk`、停步播 `Walk Forward To Idle`、转身播 `Walk 180 Turn`;配合速度 ease-in/out | 状态机自带 | 自然衔接,零引擎改动 |
| 档 2(P2) | **双槽 crossfade**:`VrmaAnimationEngine` 加第二动画槽 + 全局混合权重,每骨四元数 slerp/ hips 位置 lerp,0.2-0.3s 淡入淡出;three.js `AnimationMixer.crossFadeTo` 同款语义 | 中(采样器双实例 + 混合写) | 任意两剪辑平滑互转,手势/舞蹈间过渡也受益 |

> 档 1 的关键标定项:过渡素材里是否带 root motion(位移)——若带,与 LocomotionController 的程序位移会叠加,需要在过渡剪辑播放期间暂停程序位移或提取剥离(方案 C 的开关在这里提前用上)。真机逐个验。
>
> **标定项已有数据(2026-10-11,walk-calib FK 测 hips 平移范围)**:库内"起步"类候选全部带 0.8~1.7m 前向位移(`Twist Left Idle To Walk 180` 系,XZ 范围 1.2~1.7m)——直接接会双重位移+播完回弹,不能用;唯一干净的停步候选 `Female Walk Forward To Twist Left Idle`(1.25s,XZ 6cm≈原地)但尾姿态是 twist-left idle,与常规 idle 循环之间仍有一次硬切。**结论:机制先行、默认关**——`setWalkTransitions(start, stop)`(起步 hold 冻结程序位移+播完切步态循环;停步 one-shot 播完自动接管 idle)已落地,ai_cmd `walk_transit start|stop <path>|off` 真机 A/B,哪个剪辑能过"无跳变"眼验再定默认。转身维持 P0 的连续插值原地转(180° 定角剪辑无法参数化任意转角,强行套会为 30° 修正演一整圈)。**停步剪辑 A/B 已做(2026-10-12 真机)**:挂载后急停有 1.25s 收步动作、无瞬移,机制可用;但尾姿态硬切依旧+起步候选全带位移不可用,**默认维持关**。

### 5.3 走动中的 `<act>` 手势:先"停步比划",后"分层 mask"

- **V1(P0/P1)**:走路中收到 `<act:xxx>` → 状态机进 PARKED(减速停步)→ 手势播完 → resume 继续走。符合直觉("停下来比划一下再走"),零引擎改动——单槽抢占机制现成。
- **P2**:上半身 mask——walk 剪辑只写下半身骨骼列表,act 剪辑只写上半身列表,边走边比划。实现 = `VrmaAnimationEngine` 支持 boneMask 过滤写集(采样照旧,写入时跳过不在 mask 的骨),双槽 crossfade 是它的前置。mimic 车道已证明多写入者仲裁可行,但这个放 P2,不进 P0 承诺。

### 5.4 与既有写入者的仲裁

| 写入者 | 层 | 冲突处理 |
|--------|----|---------|
| LocomotionEngine | root 平移/旋转 + hips 层动画(步态) | 起步时清零拖拽偏移(残留会让走直线歪) |
| 拖拽(hipsDrag) | hips 平移 | 走动开始时重置;拖拽中禁止起步 |
| mimic | rotation 车道 | mimic 激活期间 locomotion 挂起(仿 mimic 的 one-shot 让路语义,`VrmPoseMimicEngine.kt:212-215`) |
| breath/lookAt | rotation | 照常叠加;**走动中 lookAt 是否改看前方 = 真机标定项**(身体移动而眼球锁定镜头可能怪) |
| spring bone | 物理后处理 | 位移不影响(纯旋转驱动);急转弯甩头发/裙摆是表现力不是 bug |

---

## 6. 相机运镜:FOLLOW 跟随模式(问题③)

### 6.1 现有闭环为什么天然适合跟随

`updateCameraShot` 的本质是"目标位姿 → 每帧测误差 → 原语收敛",而 `shotPose` 每帧重读 hips/head **世界坐标**。唯一阻碍跟随的是到位后 `shotSteering=false`(`:1946-1948`)。所以 FOLLOW = 一个语义开关,不是新系统。

### 6.2 FOLLOW 设计

- `CameraShot` 新增 `FOLLOW`(跟拍,复用 MEDIUM_SHOT 的景别参数):`shotPose` 的 pivot 换成 hips 世界坐标的**动态锚点**,距离比中景稍远(1.3-1.5×,给运动留画框余量)。
- FOLLOW 激活期间**不清 `shotSteering`**:角色走,误差持续存在,指数趋近天然输出平滑跟拍;`SHOT_APPROACH_RATE` 在 FOLLOW 下调小(更软的阻尼,防高频抖动)。
- 到达停步后,FOLLOW 自动降级为 MEDIUM_SHOT(一次到位收敛后正常静止),或由 LLM 发 `<cam:close_up>` 收尾。
- 运镜序列示例:起步时 LLM 发 `<cam:long_shot>`(大景别跟拍,全身入画)→ 行进中 FOLLOW 保持 → 到达 `<cam:close_up>`(特写对话)。全部走现有 `dispatchCamera` 链路,LLM 自主编排,引擎零新增协议。

### 6.3 用户接管

- 用户手指触摸 = 临时退出跟随(现有 `cancelShotTransition()` 挂点扩展:额外清 follow 标志);N 秒无操作或下一条 `<cam:` 指令恢复。
- 约束重申(来自 `docs/` 与记忆):Java 端无 `setFrame`、Bookmark 不可自建,**原语闭环是唯一运镜路径**——恰好现有闭环已满足,不需要新原语。

---

## 7. 自由走动方案:房间、导航、AI 决策(问题④⑤)

### 7.1 房间对位(home.glb)

现状:`loadScene` 无任何变换,模型悬空脚在 y≈-1。对位是 P0 的前置小任务:

1. `loadScene` 增加一次性变换参数(rotation/scale/offset,默认恒等 = 现行为零变化);
2. ai_cmd 新增 `scene_offset dx dy dz`(调 home.glb 与 `scene_rot yaw`),真机对着 screencap 标定一次;
3. 标定结果写 `assets/scene/home.meta.json`(地面高度、房间矩形边界、缩放),运行时读取;
4. 兜底:不加载 home.glb 时不响应走动指令(ai_cmd 提示先 `load_scene`),防止"虚空行走"。

### 7.2 锚点表与导航分层

- **锚点表** `assets/scene/home.anchors.json`:`{name, x, z, yaw, label, labelEn}`——语义点(沙发/窗边/门口/书架),label 供协议块展示。踩点方式:ai_cmd `walk_to x z` 手动走到满意位置 → 记录坐标写表(比视觉估算可靠得多)。
- **V1 导航 = 两点直线 + 房间矩形 clamp**:锚点由自己摆放,保证锚点间连线不穿墙(户型自己控制,这是虚拟场景最大的红利);越界直接 clamp。
- **V2 导航 = waypoint 图 + BFS**:锚点升级为图节点,几十个节点规模 BFS 足够,无 navmesh 烘焙。
- **P2 可选(视觉标定路线)**:observe_camera 拍全景 → 视觉 LLM 估锚点坐标候选 → 人工确认入库。与工具生态呼应,但视觉模型估 3D 坐标误差大,只作候选生成,不做权威。

### 7.3 AI 决策通道:标签先行,工具跟进

**V1:标签 `<move:sofa>`(推荐先做)**

- adapter `TagCue` 新增 `Move(name)` 族,`InlineTagExtractor` 加 `<move:名字>` 正则(命名空间独立,见坑 8);
- orchestrator 新增 `LocomotionDriver`(与 `GestureDriver` 完全对称,`gesture/GestureDriver.kt:16-86`):锚点目录由 app 注入(同 `LlmActionCatalog` 注入 `ActCatalog` 的模式),`dispatchCues` 加 Move 分支 → 解析锚点 → `controller.startWalk(anchor)`;未知名走既有 `UnknownTag` 上报(过滤不朗读,层 2 兜底直接复用);
- 协议块(PromptTexts zh/en 同步)追加 move 说明行 + 锚点清单(锚点表会话内不变,不破协议块钉住与 A.1 第 34 条 system 恒单条约束);
- **核心优势:异步**——`startWalk` 即返回,LLM 继续说话,TTS 流水不断,走路不占对话轮。

**P1:工具 `move_to` / `stop_moving`(组合玩法)——已实现,异步语义**

- `AgentToolCatalog` 加工厂,与 `observe_camera` 同构:app 注入锚点表和 controller;`move_to(anchor)` **起步即返回确认**("已经在走向窗边,请继续对话"),**不阻塞等到达**——工具层超时上限(默认 10s)下正常步速走 2m+ 必超时,阻塞语义等于给模型埋一个必炸的错误结果;到达经 `WalkArrived` 事件给 UI,模型拿到起步确认后自然继续说话,与"边走边说"设计对齐(§12 开放问题 4 的默认值也倾向不触发 LLM 说话);
- `stop_moving` 无参停步,与 move_to 配对注册;`speed` 可选参数(0.2~3.0 钳制,坏值忽略走默认);
- 失败归因全回灌:无锚点配置/没加载场景(NO_SCENE 新守卫)/没加载模型/步态未配/mimic 占用/执行缝失败,各走 PromptTexts 双语文案,工具失败不炸回合;
- systemHint 自动进协议块,零额外协议工作。

### 7.4 事件流与"边走边说"

- `AvatarEvent` 新增:`WalkStarted(anchor)` / `WalkArrived(anchor)` / `WalkInterrupted(reason)`(`AvatarEvent.kt:7-61` 处追加);聊天面板信息行(灰字,同 UnknownTag 样式)+ 事件流日志,复用 `[InfoStream]` 通道。
- TTS/断句流水与走动完全解耦(决策层只发指令),"边走边说"天然成立。
- 防抖:WALKING 期间收到新 `<move:>` → 忽略或替换目标(P0 选"忽略 + UnknownTag 不报错",提示词写明"正在走动时不要连续下移动指令")。

---

## 8. 架构落点(依赖方向与文件清单)

严格四层架构,依赖只向下:

| 层 | 新增 | 职责 |
|----|------|------|
| corelib(internal) | `LocomotionEngine.kt`(新) | 状态机/位移积分/heading/速度标定参数;挂 `choreoCallback` VRMA 分支后;`SoulLinkRenderer` 转发 |
| corelib(public) | `AvatarController.startWalk/stopWalk/walkState` | volatile 换手 API(仿 `setMimicPose`,`AvatarController.kt:389-391`) |
| orchestrator | `LocomotionDriver`(新,`gesture/` 旁)、`dispatchCues` Move 分支、`AgentToolCatalog.move_to`(P1)、`AvatarEvent` 三事件 | 锚点解析、协议块文案、事件 |
| adapter | `TagCue.Move` 族 + `InlineTagExtractor` move 正则 | 标签抽取 |
| app | 锚点/场景 meta 加载、设置开关(仿 agentTools)、`ai_cmd` walk_to/walk_stop/anchors/scene_offset、`AiChatPrefs` | demo 接线与调试 |

已知债不新增:`MainActivity` 已 3744 行,新接线尽量走既有 effect/AiDebugExecutor 模式,不再膨胀主类。

---

## 9. 测试计划与真机验收清单

- **JVM 单测**(纯函数注入时钟/坐标,仿 orchestrator 现有风格):状态机全迁移边、位移积分(帧率无关性 dt 抽查)、到达判定、减速制动距离、heading 复合旋转数学、锚点 JSON 解析与 clamp、`<move:>` 抽取与未知名上报、协议块 move 行(中英)。
- **标定工具**:`tools/walk-calib/`(Node,读 VRMA GLB 做 FK,输出步幅/步频/周期建议速度)。
- **ai_cmd**:`walk_to x z [speed]`、`walk_stop`、`anchors`、`scene_offset`、`scene_rot`。
- **真机验收**(对照 §1.1 目标体验):
  1. 直线走 2m 不滑步、步频与位移速度肉眼同步;
  2. 起步/停步用过渡素材,无姿态跳变、无瞬移回弹;
  3. `<move:sofa>` 全链(语音模式+视频模式):标签→走路→到达事件→UI 信息行;
  4. FOLLOW 相机跟拍不掉队、不振荡;用户拖动可接管;
  5. 走动中持续说话,TTS 不断流;走动中 `<act>` → 停步比划 → 续走;
  6. VRM0 + VRM1 两模型各过一遍(翻转差异);
  7. 边界 clamp 生效,`walk_to` 越界坐标不穿墙;
  8. mimic 激活时走路指令挂起,断供自愈不受影响;
  9. 拖拽模型后立刻走动,无残留偏移;
  10. 单测全绿基线不破(当前 653)。

---

## 10. 风险与坑清单(预判,实现时并入 ai-layer-handoff 坑清单)

1. **hips 动画位移 × root 位移叠加**:拖拽偏移残留会让起步走歪——起步清零 drag offset;hipsRewritten 重钉逻辑走动期间要确认不与 locomotion 打架。
2. **单槽硬切**:过渡素材覆盖前,起步/停步有姿态跳变——靠档 1 素材 + 速度 ease 掩盖,别指望现状。
3. **无 playbackSpeed**:P0 位移速度迁就步频(标定步幅为准);加 timeScale 时注意 `idleAnchor` 相位、时长事件、断句同步都要跟着缩放。
4. **模型悬空**:不加载 home.glb 就走 = 虚空行走;对位标定是 P0 硬前置。
5. **VRM0 Y-180 翻转**:转向的 3×3 左乘与前向向量符号必须两代模型各标定一次(mimic 两轴三轮标定的教训:先真机标定再锁死,不凭推导)。
6. **相机常量钉死**:`MANIP_FOV_TAN/ZOOM_SPEED/ORBIT_SPEED` 是全局常量(`SoulLinkRenderer.kt:229-243`),FOLLOW 的阻尼/距离单独调参,别动全局值(影响既有 6 档 shot)。
7. **lookAt 走动中锁定镜头**:标定项;若怪,走动中 lookAt 目标临时改 heading 前方。
8. **标签命名空间碰撞**:act 标签 = 343 个文件名 sanitize,理论上可能存在名为 "sofa" 的动作文件;`<move:>` 独立解析在 extractor 层就分流,不与 act 撞 regex。
9. **LLM 疯狂 move**:提示词约束 + WALKING 期间忽略新指令 + 边界 clamp 三重保险;不许"边走边改目标"的.queue 语义进 P0。
10. **过渡素材可能自带位移**:播放期间程序位移暂停或提取剥离(§5.2 标定项),否则双重位移。
11. **方案 C 的脏数据**:非原地素材 hips 轨迹含侧向/晃动分量时,直接水平剥离会抖——启用前逐素材质检。
12. **home.glb 30MB 内存**:按需加载已有(`selectedScene`),对位参数写 meta.json,别运行时算包围盒。

---

## 11. 分期路线图与工作量

| 期 | 内容 | 工作量 | 验收 |
|----|------|--------|------|
| **P0 最小闭环** ✅ | LocomotionEngine(直线走+急停+heading 插值转向)+ root 位移挂点 + `playbackSpeed` + walk-calib 标定工具 + ai_cmd walk_to/walk_stop + home.glb 对位(loadScene 变换参数 + scene_offset)+ `CameraShot.FOLLOW` + 单测 | 2-3 天 | ai_cmd 手动驱动真机走直线,跟随相机跟拍,不滑步不穿墙 |
| **P1 AI 通道** ✅(2026-10-12 真机验收通过) | `<move:>` 标签全链(TagCue/extractor/LocomotionDriver/协议块 zh/en)+ 锚点表 + WalkStarted/Arrived/Interrupted 事件 + UI 信息行 + 过渡素材接入(机制:起步 hold/停步 one-shot/`walk_transit` ai_cmd;候选位移测量见 §5.2,**默认关**)+ 提前制动减速(§5.1 升级)+ 设置开关 llmMove + move_to/stop_moving 工具(§7.3) | 2 天 | 对 AI 说"去窗边看看"全链真机验收 |
| **P2 增强** ✅(2026-10-13 真机验收通过) | 五项一次落地:①双槽 crossfade——`VrmaAnimationEngine` 重构为 base+overlay 双槽(`play/stop` 带 fadeSeconds,three.js crossFadeTo 同语义),起步/停步/one-shot→idle 接管全部淡入淡出,`walk_fade` 默认 0.25s;②上半身 mask——`UPPER_BODY_BONES` 掩码,走动中 one-shot 手势(公开 `playVrmaAnimation(loop=false)`)自动以 overlay 叠加(淡入 0.2s,播完自动淡出回纯步态,腿不停),`walk_overlay` 开关;③方案 C——`LocomotionExtractor`(纯 JVM)分析 hips 轨道净前向/方向,引擎单槽写入时剥离前向分量、渲染层把逐帧差值经 root 旋转×renderScale 回灌世界(剥离与回灌严格抵消=脚步零滑步),带烘焙位移步态 baseSpeed 自动标定;④waypoint 图——`MoveAnchor.links` 无向边+`WaypointRouter` BFS(不可达/无图回退直线),`LocomotionDriver` 多段腿(到达自动续走/中断清队),`home.anchors.json` 配 links;⑤跑步/侧移——`Running Forward`/`Walking Strafe To The (L|R)` 内置,`setRunGait`/`setStrafeGaits`,speed≥1.9 自动跑步,`startStrafe`(headingLocked 模式,朝向不变横移) | 1.5 天 | 真机 2c3769db:crossfade 无闪帧/边走边出拳/两段腿自动续走/跑步侧移步态切换/双模型,零崩溃(§11 末 P2 实现落点) |

P1 实现落点(2026-10-11,未提交):adapter `TagCue.Move`+extractor `move` 正则(命名空间独立+强度段容忍);orchestrator `locomotion/`(MoveAnchor+parseMoveAnchors 纯 JVM 解析/LocomotionDriver 与 GestureDriver 对称:防抖走动中忽略+Outcome 归因)、`AvatarSession`(Options.enableLlmMove/driver 注入缝/dispatchMove/协议块 moves 段/walkFinished 流→WalkArrived/Interrupted)、`AgentToolCatalog.moveTo/stopMoving`(异步起步语义+anchor enum 白名单+失败归因回灌)、`AIAvatarSdk.ChatConfig.enableLlmMove`;corelib 引擎(BRAKE_DECEL=2.2 提前减速 v=sqrt(2ad)/startHoldSeconds 起步冻结+`WalkFinished` 事件流+startWalk NO_SCENE 守卫)、renderer(setWalkTransitions+起步剪辑墙钟切步态循环+到达/停步播 stop 剪辑+早停跳过 stop 剪辑)、AvatarController(setWalkTransitions/walkFinished SharedFlow);app(`home.anchors.json`+`home.meta.json` 脚手架/设置开关+Strings/UI 灰字信息行/ai_cmd anchors、walk_transit、walk_to <锚点名>)。

P2 实现落点(2026-10-13):corelib 新 `internal/LocomotionExtractor.kt`(纯数学,analyze 输入 hips translation 轨道+rest+VRM0 翻转+heightScale+素材时长,输出 netForward/forwardDir/forwardAt 展开循环查询;非原地阈值 0.12m 按素材空间米判定——engine 单位随模型缩放浮动不可用);`VrmaAnimationEngine` 重构双槽(`Slot`: 剪辑+播放头+速度+循环+boneMask+extractor;base+overlay+overlayWeight,共享骨骼按 overlay 权重混合于**归一化空间**(slerp 后单次 retarget),单侧独有骨骼取拥有方=mask 正交组合的实现基座;one-shot 完成三分支:走动 base 上的手势播完→淡出 overlay 不晋升/idle 淡入完成→晋升置接管/单槽→淡入 idle;混合期提取差值丢弃但播放头基准持续更新保证恢复无跳变;上半身 mask overlay 期间提取照常累积——hips 归 base 独占);`LocomotionEngine` 增 `headingLocked`(侧移:不进 TURNING、不修正朝向、位移直指目标)与 `externalGait`(方案 C:平移恒 0、`FrameMotion.commandedSpeedMps` 交渲染层设步态变速、不设对齐门=转身体走弧线);`SoulLinkRenderer`:`parseWalkGait`(解析+提取分析+renderScale 链路缩放测量+baseSpeed 自动标定)、`setRunGait/setStrafeGaits/startStrafe`、updateLocomotion 增提取回灌(归一化 root 旋转×dir×consumed×renderScale,extractionEnabled 门控)、mode C 每帧 setPlaybackSpeed(commanded/baseSpeed)、相机自护(静态机位+角色逼近<0.9m 自动切 FOLLOW)、起步 crossfade(walkFadeSeconds)、retarget 同步态续走(不重播步态);`AvatarController` 透传(setRunGait/setStrafeGaits/startStrafe/setWalkFadeSeconds/setGestureOverlayEnabled/setExtractionEnabled/walkGaitsSummary)。orchestrator:`MoveAnchor.links`(无向边,解析归一/去自环)+`WaypointRouter`(BFS 最短,不可达/无图回退直线,nearestAnchor)+`LocomotionDriver` 多段腿(pendingLegs 队列,onWalkFinished 到达续走/中断清队,startLeg open 供测试)+`AvatarSession.ensureWalkLegRelay`(惰性幂等续腿 relay,锚点注入时注册——挂 init 会被僵尸 session 收走,坑 29);ai_cmd 锚点名形态改走 driver(与 LLM 通道同路,waypoint 生效)。app:内置 `Running Forward`/`Walking Strafe To The Left/Right` 三素材(hips 净位移实测 2.86/0.90/1.79m,baseSpeed 自动标定)、`ensureWalkGaits` 一次配齐 walk/run/strafe、ai_cmd `walk_gait/walk_strafe/walk_fade/walk_overlay/walk_extract` 五命令+walk_state 扩展+anchors 显示 links。**⚠重要实测发现:默认步态 Female Normal Walk 带 1.49m/循环烘焙位移(净速 1.27 m/s 与 walk-calib 脚步速度一致)——"原地"是误认;且该模型(4705834820915235427.vrm)的世界单位是模型比例单位(身高 0.22 单位),P0 程序位移 1.0 单位/s 实为 4.5 身高/s 的超速滑步,提取模式(0.5 身高/s)才是自然步速——坑 28。**

P0 不含 LLM 通道——用 ai_cmd 手动驱动即可完成走动核心的真机验收,对齐 agent-feasibility"P0 先行"的节奏。

---

## 12. 开放问题(需要拍板,均有默认值)

1. **V1 决策通道:标签 vs 工具?** 默认:标签先行(异步不占轮、全模式可用),工具 P1 跟进(组合观察)。
2. **walk 主素材选哪个?** 默认:`Female Normal Walk`(女款普通步,与内置模型匹配);标定工具产出数据后可换 `Catwalk Walk`(更有表演感)。
3. **home.glb 默认加载吗?** 默认:不改变现状(手动/引导加载),开启走动开关时若未加载场景,聊天面板提示。
4. **到达后要不要触发 LLM 说话?** 默认:不触发(P0/P1 只发事件给 UI);工具版阻塞到达天然解决"到了要说什么"。
5. **走路中手势策略?** 默认:停步比划再续走(V1);mask 分层等 P2。

---

## 附:与既有约束的对照(实现时必须尊重的既定事实)

- **A.1 第 34 条 system 恒单条**:move 说明与锚点清单拼进既有协议块(`composeProtocolText`),锚点表会话内不变 → 指纹不变 → 不触发重钉。
- **单动画槽现状**:P0/P1 所有过渡建立在状态机 + 过渡素材上,不承诺混合;crossfade 是 P2 独立立项。
- **相机原语闭环约束**(Java 无 setFrame、Bookmark 不可自建):FOLLOW 复用现有闭环,不新增运镜原语。
- **拖拽重钉机制**(`consumeHipsRewritten` → `applyHipsDragOffset`):locomotion 起步清零 drag offset,复用而不绕过。
- **mimic 车道模板**:volatile 换手 / 断供自愈 / one-shot 让路三件套直接照抄;mimic 激活期间 locomotion 挂起。
- **P0 工具调用**(`agent/` 包):move_to 工具走 `AgentToolCatalog` 工厂 + `AvatarSession.Options.tools` 注入或 demo 运行时注册,不碰工具循环本身。
- **文档同步义务**:实现后 `ai-layer-handoff.md`(第二节条目 + 坑清单)、`project-guide.md`(模块表加 LocomotionEngine 行)、`ai-debug-intents.md`(新命令)三处必须同步。
