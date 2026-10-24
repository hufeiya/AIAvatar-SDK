# 物理碰撞与垂直移动可行性:防穿模、上楼梯、上下床

> 2026-10-24 预研。回答三个问题:①Filament 有没有碰撞检测,现在为什么穿模;②碰撞怎么加(手柄/LLM/ai_cmd/未来自主 FSM 四路全防);③进一步,虚拟人怎么优雅上楼梯、爬上床下床。
> 关联文档:`docs/locomotion-feasibility.md`(走动系统全史)、`docs/dream-bedroom-integration.md`(场景 sidecar/交互点/接触定姿)、`docs/gamepad-control-feasibility.md`(模拟量通道)、`docs/autonomy-feasibility.md`(自主 FSM 是碰撞的第五个消费者)。
> **范围钉死**:本文的"物理"=运动学角色控制器(位移过滤+墙滑+登阶),**不含刚体动力学**(不推箱子、不布娃娃、不撞飞家具——见 §12 明确不做)。

---

## 0. 结论速览

1. **Filament 没有碰撞检测**。它是纯渲染引擎,官方定位就是"渲染器,不是游戏引擎":没有物理系统、没有 CPU 网格射线、没有角色控制器。能蹭的只有三样现成数据——`RenderableManager.getAxisAlignedBoundingBox`(节点局部 AABB)、世界变换矩阵、`View.pick`(GPU 单像素拾取,用于点击选中,不能当逐帧碰撞用)。社区共识是渲染引擎 + 外挂物理库(Bullet/Jolt)或自研几何(§2)。
2. **穿模根因一句话**:四条运动通道(手柄模拟量/LLM walkTo/ai_cmd/接触定姿外的程序位移)全部汇入 `LocomotionEngine.startWalk`,而防穿模手段只有 meta.json 的**矩形 bounds clamp**(外墙近似),家具、内墙、床体零表达。`FrameMotion.dy` 恒 0(`LocomotionEngine.kt:287-293`),y 方向全靠 spawn 手标和定姿吸附——没有"地面"概念(§3)。
3. **不引入物理引擎(Jolt/Bullet),自研轻量 CharacterController**。决定性理由:仓库已经把位移收敛到**单一写入点** `SoulLinkRenderer.updateLocomotion`(`SoulLinkRenderer.kt:3075-3079`),且引擎"root 位置每帧权威回读"(`LocomotionEngine.kt:180-188`)——外部修正位移**天然无 desync**,碰撞层插在写入点即可全通道生效;房间场景用"竖直棱柱碰撞体 + XZ 圆形扫掠 + 墙滑 + 地面高度场"就能完备表达,几百行纯数学、可 JVM 对拍(仓库传统),零 NDK 依赖(§5 §6)。
4. **上楼梯不需要新动画、不需要新碰撞数学**:楼梯 = 一串逐级抬高的低矮棱柱,`stepHeight`(0.25m)登阶 + 地面高度吸附让现有步态直接"走上去";优雅感来自限速策略与吸附平滑,不动步态素材(§8)。**上床/下床 80% 已就位**:bed_sit/bed_lie 交互链(走 approach 点→过渡剪辑→hips 吸附床面)已经覆盖"坐上床沿""躺下",缺的只是下床落点(`dismount` 字段)与可选的"掀腿上床"过渡素材——素材库无攀爬类 VRMA,需从 repo 外 1510 存量 fbx2vrma 检索或 Blender 手 K(§9)。
5. 分期:**P0 防穿模核心 4~5 人日**(碰撞体 sidecar + 写入点拦截 + 墙滑 + blocked 归因上报,直接解决"穿模"主诉);P1 地面高度场+登阶+网格寻路 3~4 人日;P1.5 多人镜像+角色分离 1~1.5 人日;P2 上下床优雅化 2~3 人日+素材 1~2 人日。合计 ≈11~14 人日(§11)。

---

## 1. 问题定义:穿模发生在哪

| # | 场景 | 现状 | 穿模形态 |
|---|------|------|---------|
| 1 | 手柄推摇杆 | 每帧 `startWalk(pos+dir×lookahead)` retarget(`SoulLinkRenderer.kt:2935-2939`) | 直线冲进床/书桌/墙角,无滑动无停步 |
| 2 | LLM `move_to`/`<move:>` | waypoint 图 BFS 路由多段腿(`LocomotionDriver.kt:89-104`),末腿直线 | 图节点间连线穿家具;无图回退直线全裸奔 |
| 3 | ai_cmd `walk_to x z` | 直线 + bounds clamp | 同上,坐标随便给随便穿 |
| 4 | 未来自主 FSM WANDER | 复用 walkTo(autonomy-feasibility §2) | 继承 2/3 的全部问题 |
| 5 | 多人走位/换位 | StageFormation 摆位有 1.6m 间距(`StageFloor.kt:54`),**走动路径零避让** | 两人对穿 |
| 6 | 垂直方向 | y 恒由 spawn 写死,无地面查询 | (还穿不起来,但也没法上台阶/床) |

唯一已有的"几何"防线:`WalkBounds` 矩形 clamp(`LocomotionEngine.kt:119-126`)。它是 2026-10-11 locomotion P0 的"边界用几何 clamp"决策(原文 §1.3 明确把物理排除),当时房间只有 home.glb 一个空壳,如今 dream_bedroom 已有 101 个实体、16 个交互点,家具密度够了,防线该升级了。

---

## 2. Filament 能力边界:有什么、没什么

| 能力 | 有无 | 说明 |
|------|------|------|
| 物理引擎/碰撞系统 | ❌ | 官方定位纯渲染;GitHub Discussion #5998 官方回复:碰撞/raycast 自己组合物理库或手写射线-三角形 |
| CPU 网格射线检测 | ❌ | 没有 raycast-against-mesh API;#5998 给的方案是手写射线-三角形,逐帧成本不可接受 |
| `View.pick` GPU 拾取 | ✅ | 单像素深度回读拿实体 id,一帧一次的点击选择可以,**逐帧碰撞不行**(回读延迟+功耗) |
| `RenderableManager.getAxisAlignedBoundingBox` | ✅ | 节点局部 AABB;SceneOcclusionController 已在用(`SceneOcclusionController.kt:209`)——**自动推导碰撞体的现成机器** |
| 数学库(vec/mat/quat) | ✅ | 自研几何的砖块 |
| Frustum culling 用 AABB | ✅ | 说明引擎内部也只有盒级几何,连它自己都不做三角级碰撞 |

类比(View 体系视角):Filament 像 `Canvas`,只负责画;碰撞是 `onTouchEvent` 命中检测层,得自己写在更高的层。Android 里你不会因为用了 Canvas 就引入一套物理引擎来处理滑动冲突——同理,自研几何就够。

**Filament 内不会长出碰撞,方向只有两个:外挂物理库 or 自研运动学控制器**(对比见 §5)。

---

## 3. 现状盘点:为什么插一层就能全防

### 3.1 咽喉点唯一(这是本方案可行的全部底气)

```
手柄轴(GamepadHub.kt:208)──┐
LLM move_to(AgentToolCatalog.kt:573)──┤
ai_cmd walk_to(AiDebugExecutor.kt:195)──┼─▶ startWalk ──▶ LocomotionEngine.update
waypoint 续腿(LocomotionDriver.kt:107)─┘                    │ FrameMotion(dx,dz,dHeading)
                                                            ▼
              SoulLinkRenderer.updateLocomotion(:3042)
                updateDriveAxes(:3071)  ← 手柄轴消费(同帧 retarget)
                locomotionEngine.update(:3073)  ← root 位置权威回读
                mat[12]+=dx, mat[13]+=dy, mat[14]+=dz (:3076-3078)  ★写入点①
                tm.setTransform(:3079)
                提取回灌 mat[12]/[14]+=… (:3101-3102)  ★写入点②(方案 C 步态)
```

两个关键设计红利:

- **引擎不积分位置**,每帧从 root 矩阵回读当前坐标(`LocomotionEngine.kt:180-188` 注释原话:"the engine never integrates position itself, so drags and programmatic moves can't desync it")。碰撞层在写入点①②改写位移后,下一帧引擎自动以修正后的位置为基准继续算——**无状态漂移、无 desync、无需改引擎内部**。
- 手柄通道走 `updateDriveAxes → startWalk` 进引擎,提取回灌走写入点②绕过引擎——**两处都要过滤**(坑 3)。

### 3.2 已有的可复用零件

| 零件 | 位置 | 碰撞系统怎么用 |
|------|------|--------------|
| GLB 节点世界 AABB 机器 | `SceneOcclusionController.computeGroupAabb`(:192-229)+ `OcclusionMath.transformAabb/expand`(OcclusionMath.kt:58/81) | autogen 碰撞体直接复用:全场景扫渲染实体 → 世界 AABB → 过滤 → 碰撞体候选 |
| sidecar 挂接模式 | `loadScene` 挂 occlusion(:1960-1965),`applyScenePlacement` 失效重算(:2025) | colliders.json 同款:attach 读、placement 变更失效 |
| 纯数学层 + JVM 对拍传统 | OcclusionMath/ContactPoseMath/DriveMath + SceneInteractionMathTest(17 例) | SceneCollisionMath 照抄,先单测后真机 |
| blocked 归因上报模板 | move_to/interact_with 失败归因回灌(AgentToolCatalog.kt:573-743)+ `WalkInterrupted` 事件 | 碰撞挡路 → `WalkInterrupted(BLOCKED)` → 既有文案机制报给 LLM |
| 竖直棱柱之外的场景几何 | meta.json bounds/spawn、anchors.json 图、interactions.json approach 点 | bounds 保留当外墙兜底;colliders 补家具内墙 |
| 垂直移动的 y 写入缝 | `FrameMotion.dy` 字段已存在(`LocomotionEngine.kt:56`),只是恒 0;spawn 的 `setAvatarWorldPosition`(:2979-3000)证明 y 可安全直写 | 地面高度吸附产出 dy,同点写入 |

### 3.3 交互链与定姿不是穿模点,是"合法瞬移"

`ContactPoseController` 的 hips 吸附(:231-261)和 spawn 落点直接写 root,绕过位移层——这是设计使然(定姿本来就要把人"放"到床面上)。碰撞层对它们**挂起豁免**(POSE_ACTIVE 期间不 resolve),但落点来源(交互点表)是人工标定的,天然落在合法位置。唯一要防的是:定姿吸附后的 root 不应被夹在碰撞体里(床体碰撞体 top 要低于床面接触点,或对"已定姿实体"登记豁免,见坑 4)。

---

## 4. 碰撞模型设计(核心 §)

### 4.1 数据:`<场景名>.colliders.json`(第五件套之外的新 sidecar)

```jsonc
{
  "floorY": -0.97,          // 场景地板世界 y(dream_bedroom 即 offsetY -1 校正值,显式化)
  "stepHeight": 0.25,       // 自动登阶阈值(默认 0.25m,场景可覆盖)
  "colliders": [
    // 竖直棱柱:XZ 截面(可 yaw 旋转)+ [y, y+h] 垂直区间。y=底面世界高。
    {"id":"wall_north","kind":"wall","x":0,"y":-0.97,"z":-7.15,"hx":3.65,"hz":0.12,"h":2.6,"yawDeg":0},
    {"id":"bed","kind":"furniture","x":-2.5,"y":-0.97,"z":-5.0,"hx":1.1,"hz":0.62,"h":0.55,"yawDeg":0},
    {"id":"desk","kind":"furniture","x":2.2,"y":-0.97,"z":-6.2,"hx":0.7,"hz":0.35,"h":0.75,"yawDeg":0},
    {"id":"stairs_s1","kind":"step","x":3.0,"y":-0.97,"z":-2.0,"hx":0.5,"hz":0.28,"h":0.16,"yawDeg":0}
    // kind: wall|furniture|step|prop —— 只影响调试可视化与豁免策略,算法不区分
  ]
}
```

设计取舍:

- **只做竖直棱柱,不做任意凸包/三角网格**。房间场景的墙、家具、台阶用棱柱表达完备;人形代理也降成"XZ 圆(r≈0.20m)+ 垂直区间 [feetY, feetY+身高]"——于是全部碰撞判定退化为 **2D 圆 vs 旋转矩形(OBB)+ 区间相交**,几十行数学,O(n) n≈20~40 每帧微秒级。
- 不放天花板(身高够不着);不放大背景板(已删,且大 AABB 罩全房的教训见 dream-bedroom §二坑 3——autogen 的启发式过滤必须继承这条豁免规则)。

### 4.2 来源:Blender 导出为主,autogen 扫描兜底

| 路线 | 做法 | 定位 |
|------|------|------|
| **A. 建模期导出(权威)** | `tools/blender/export_dream_bedroom_N.py` 仿 `cutaway_records`(:138-151)新增 colliders 导出——墙/家具在 Blender 里本来就是独立节点,框选打组标注即可;`integrate_scene.py` files 表(:96)与拷贝循环(:226-228)各加一件;`validate_*.py` 加一致性校验 | 新场景全走这条,零运行时成本 |
| **B. autogen 扫描(兜底)** | 复用 occlusion 的 AABB 机器全场景扫渲染实体 → 启发式过滤(地板/天花板=超大薄板剔除、大背景板豁免、体积<阈值忽略)→ `ai_cmd colliders_scan` 导出候选 → 人工修成 4.1 格式 | 存量 3 个场景快速起量;调试工具常驻 |

解析落在 `SceneMetaParser.kt` 旁新增纯 JVM 类;corelib 新 `internal/SceneCollisionWorld.kt`(纯数学,注入 collider 列表+floorY,不感知 Filament),挂接照 occlusion 模式(`loadScene` 读 sidecar → renderer 持有;`applyScenePlacement` 后失效重算)。

### 4.3 逐帧算法(全部可 JVM 对拍)

```
每帧(写入点①②之后、setTransform 之前):
  proposed = root + (dx, dy, dz)                    // 引擎产出 or 提取回灌
  1) 水平推离+墙滑:
     对每个 collider:垂直区间 [y, y+h] 与 [feetY+stepHeight, feetY+身高] 相交才参与
       → XZ 圆 vs OBB 推离:穿透深度沿 OBB 最近面法线推出,剩余位移投影到切向(墙滑)
       → 圆-圆(其他 avatar):推开至半径和
  2) 地面高度(横移后才采样,采样点=修正后 XZ):
     ground = max{ top(c) : c 足迹含点 且 top(c) ≤ feetY+stepHeight }  else floorY
     3a) 上升:ground - feetY ∈ (0, stepHeight] → 登阶,y 吸附(平滑,坑 10)
     3b) 下降:feetY - ground ≤ snapDown(0.35m) → 落下吸附;否则= 悬崖边缘,水平方向该分量归零
  3) 写回 root,mark blocked:
     实际位移/指令位移 连续 N 帧 < 0.1 → WalkInterrupted(BLOCKED) + stopWalk
```

要点:

- **stepHeight 语义一个参数同时解决"台阶能上、床不能走上去"**:床 top≈0.55m > 0.25m → 参与阻挡(上床必须走交互链,正确);台阶 0.16m ≤ 0.25m → 不阻挡 + 抬升地面(自动登阶,优雅)。
- **blocked 检测是 LLM 通道的命脉**:`WalkInterrupted(BLOCKED)` 走既有事件流,`move_to` 的失败归因文案机制直接复用("被家具挡住,已停在附近");`InteractionCoordinator.awaitStandstill`(:171-183)轮询 walkState IDLE,收到停步即自然返回——**顺手修掉"长多段腿静默 abort 30s"的既有坑**(见 §10 坑 8)。
- bounds 矩形 clamp 保留(双保险:防 LLM 给出房间外坐标),colliders 管室内精细阻挡。

---

## 5. 选型对比:为什么不引入物理引擎

| 方案 | 引入成本 | 能拿到什么 | 缺什么 | 判定 |
|------|---------|-----------|--------|------|
| **自研运动学 CharacterController(选它)** | ~500 行纯数学 + 挂接,零依赖 | 墙滑/登阶/地面吸附/圆分离,确定性可单测 | 无刚体动力学(本就不做) | ✅ P0~P2 全覆盖 |
| Jolt(jolt-jni,stephengold 维护的 JVM 绑定) | NDK 交叉编译 4 个 ABI + ~2-4MB so + JNI 学习曲线 | CharacterVirtual 现成角色控制器、连续碰撞、将来可加推箱子/ragdoll | 99% 能力用不上;调参黑盒;真机排难成本高 | ❌ 留作 P3+ 若做物理道具再评估 |
| jBullet(纯 Java) | 零 NDK | 老牌 API | 项目 2018 年停更;单线程;精度怪癖;同样 99% 用不上 | ❌ |
| recast4j(纯 Java Recast/Detour 导航网格) | 中 | 真导航网格烘焙+寻路 | 8×7m 手摆场景用占据栅格 A*(200 行)足够;烘焙管线是另一个工程 | ❌ 留作场景复杂化后的升级路径(§7.3) |
| Filament View.pick 当碰撞 | 零 | — | GPU 回读延迟/功耗,一帧一次的语义,逐帧碰撞不可行 | ❌ |

一句话:我们要的是"不穿模 + 会绕路 + 能上台阶",这是**运动学约束**不是**动力学模拟**——Unity 的 CharacterController 也从不是刚体,是胶囊扫掠 + 算法化登阶,我们造的就是这个小一号的同款。

---

## 6. 分通道接入(改哪里)

| 层 | 改动 | 规模 |
|----|------|------|
| corelib | 新 `internal/SceneCollisionMath.kt`(圆-OBB 推离/墙滑/地面高度,纯数学)+ `internal/SceneCollisionWorld.kt`(collider 列表+缓存失效,照 occlusion 模式);`SoulLinkRenderer.updateLocomotion` 写入点①②后插入 resolve(≈40 行);blocked 事件接 `WalkInterrupted` 既有流 | 数学 ~250 行 + 挂接 ~100 行 |
| app | `SceneMetaParser` 扩展 colliders.json 解析;`AiDebugExecutor` 新命令族:`colliders`(列表/开关)、`colliders_scan`(autogen 导出)、`colliders_debug`(头顶/脚下显示命中调试,可选画 ASCII 地图) | ~200 行 |
| app(Blender 侧) | export 脚本加 colliders 导出;validate 加一致性检查;add-scene SKILL.md 流程加一步 | 脚本 ~100 行 |
| orchestrator | 无新工具——`move_to`/`interact_with` 语义不变,blocked 走既有 WalkInterrupted 归因;PromptTexts 补一条"被挡住"文案(zh/en) | 文案 2 键 |
| 单测 | SceneCollisionMathTest(推离/滑移/登阶/吸附/blocked 判定 ~20 例)+ colliders.json 解析 + bounds 兼容 | ~20 例 |

线程语义:resolve 在 choreoCallback 渲染线程内联(与 updateLocomotion 同帧同线程),无锁;A* 寻路(P1)只在路径请求时算、跑在调用方协程,渲染线程只查结果(坑 7)。

---

## 7. 寻路:AI 从"撞上去"到"绕过去"(P1)

碰撞层只保证不穿,LLM/FSM 的目标点若被家具隔开,需要绕行路径,否则就是"贴墙卡住 → blocked 上报"的体验降级。

### 7.1 占据栅格 + A*(推荐)

- 场景 bounds 内铺 0.15m 栅格(~49×42 for dream_bedroom,毫秒级),格子可走 = 不与任何 collider 足迹(膨胀 avatar 半径)重叠;楼梯区按"该处地面高度可达"放行(P1 后)。
- A* + 视线拉直(string-pulling,格网 raycast),输出折线 → 喂给**既有多段腿机制**(`LocomotionDriver.pendingLegs`,`:129-139` 续腿 relay 已存在)——寻路器只是个"更聪明的 WaypointRouter",调度层零改动。
- 与既有 waypoint 图的关系:**图保留为语义层**(锚点间"惯常路线"),栅格负责最后一条腿的障碍绕行与无图兜底。两者可互换:有图走图,无图走栅格。

### 7.2 各通道的寻路策略

| 通道 | 策略 |
|------|------|
| 手柄 | 不寻路,纯墙滑(玩家就要"顶着墙走"的手感) |
| LLM move_to / <move:> | 目标锚点→栅格 A*→多段腿;不可达→blocked 归因 |
| ai_cmd walk_to 坐标 | 同上 |
| 自主 FSM WANDER(P0 依赖) | 同 LLM 通道 |
| 多人走位(P1.5) | 各自寻路 + 圆-圆分离 |

### 7.3 升级路径

场景复杂到手摆栅格不够(多层、动态门)再上 recast4j(纯 Java Recast,可烘焙 navmesh)。当前规模明确不做。

---

## 8. 上楼梯(垂直移动 P1)

### 8.1 关键认知:楼梯不需要爬的动画

登阶的本质是**地面高度函数变了,不是动作变了**。现有步态(Female Normal Walk,方案 C 提取带 1.49m/循环烘焙位移)照常播放,hips 上下起伏来自素材本身;root y 只需跟随 groundHeight(x,z) 吸附——人在画面上就是"走上一级台阶"。游戏业界(CharacterController stepOffset、Minecraft 步高)同款思路。

楼梯的作者端表达 = N 个 `kind:"step"` 低矮棱柱逐级排开(Blender 里一个 Array modifier 的事),**零新碰撞数学、零新动画资产**。库内本就没有 stair/step 类 VRMA(全目录排查,最近的是 10_跳跃 7 个跳/落素材),这条路线完美绕开了素材缺口。

### 8.2 优雅感的三个来源(实现项)

1. **登阶吸附平滑**:y 不瞬跳,临界阻尼跟随(≈8-10Hz 起调,真机眼验),脚感=级差被"迈过去"而不是"瞬移上去"(坑 10);
2. **楼梯限速策略**:地面斜率(采样两点高度差)> 阈值时把 speed cap 到 walk 档、禁跑步——跑步上楼会滑步露馅;
3. **落脚下蹲不处理**:VRMA 步态自带膝盖起伏,台阶级差 ≤0.2m 时视觉完全可信,不必逐级触发 special 步。

### 8.3 下楼梯与边界

- 下行吸附窗 snapDown=0.35m(≈两级),超出=悬崖边缘阻挡(不做坠落/摔跤——没有对应动画,且卧室场景无悬崖);
- 顶层/底层由 colliders 围栏表达;spawn 落点支持任意 y(已有)。
- 现有三场景无楼梯;验收需 Blender 造一个 3 级台阶测试台(走 add-scene 流程顺带验证 colliders 导出管线,一石二鸟)。

---

## 9. 上床下床(P2 优雅化)

### 9.1 现状先说清楚:80% 已经通了

dream_bedroom 真机验收(dream-bedroom §五)已通过的全链:走到 `bed_approach`(-0.65,-4.65,床边)→ `Standing To Sitting Transition` 坐下过渡 → hips 吸附床面接触点(bed_sit y=-0.30,比地板 y=-0.97 高约 0.67m,hipsLift 0.09)→ `Sit With Fidgeting Feet` 循环;`bed_lie` 走根旋转平躺方案。**"坐上床沿、躺下、起身"三段都已达标**——床面比地板高 0.67m ≫ stepHeight 0.25m,walk 通道本来就**不该**能走上床(碰撞层上线后这从"巧合正确"变成"设计正确")。

真正的缺口只有两个:

| 缺口 | 现状体验 | 落地方案 |
|------|---------|---------|
| **下床落点** | `EXITING` 回到"定姿开始时抓拍的 standMatrix"(ContactPoseController :264-276)——从床**中间**躺下起身,人原地出现在床垫上方再吸附地面,观感"弹"回 | interactions.json 交互点新增 `dismount` 字段(床边地板落点):EXITING 目标改用 dismount 点(平移 lerp 目标替换),可选接 `Landing From Jump.vrma`(库内现成)做落地缓冲 |
| **上床过程感** | 站在床边→坐姿过渡→hips 滑到床面,坐姿链自然;但**躺**是从坐姿根旋转放平,没有"抬腿上床"的过程 | `mount` 过渡 one-shot(可选,非阻塞项):素材三选一——①repo 外 1510 存量 fbx2vrma 检索 climb/sit-and-roll 类;②Blender 手 K 2s"坐床沿摆腿后仰"(fbx2vrma 管线现成);③先用坐姿链+根旋转顶一段,素材到位再挂 `mountInClip` 字段(与 sitInClip 同构,就位链加一拍) |

### 9.2 配套:地面上下文

HOLDING 期间(人在床上)走动本来就被 `POSE_ACTIVE` 拒绝(`SoulLinkRenderer.kt:2960`),无需新机制;stand_up 起身动画播完落回 dismount 点后,地面高度上下文自然回到 floorY。唯一要保证:床体 collider 的 top(0.55m)与床面接触点(-0.30,即高度 0.67m)不冲突——collider top 表示"床垫可踩面"时取床垫实际面高,登阶越不过它(>stepHeight),交互链合法到达,两条路径互不干扰(坑 4 详)。

---

## 10. 坑清单(预判,实现时并入 ai-layer-handoff)

1. **`RenderableManager` @EntityInstance 收 instance 非 entity**——autogen 扫 AABB 必踩,照抄 `SceneOcclusionController` :206-208 的注释写法,不重蹈。
2. **场景 asset.getName 恒 null**(AssetLoader 无 NameManager)——autogen 输出用 entity id 关联,人类可读名靠 sidecar/人工修。
3. **两个位移写入点都要过滤**:引擎产出(:3076-3078)与方案 C 提取回灌(:3101-3102)。只滤①的话提取步态模式(跑步/默认步态就是提取模式!)照样穿模——这是最容易漏的一处。
4. **定姿/拖拽/spawn 是合法瞬移**:碰撞 resolve 必须 POSE_ACTIVE、hips 拖拽中、spawn 落点时挂起,否则会把"躺平在床上的人"推下床;床 collider top 与床面接触点的关系要专门验(§9.2)。
5. **手柄 lookahead 目标点可落在墙内**:`startWalk(pos+dir×lookahead)` 的目标 clamp 进 bounds 后仍可能在床里——引擎朝不可达点持续推进,靠 blocked 检测停步兜底;贴墙手感(死区/制动)要真机 A/B,DriveMath 的 lookahead=v²/2a 制动逻辑不受影响(gamepad 文档已知特性)。
6. **y 语义**:dream_bedroom 地板不在 y=0(地板 y≈-0.97,spawn y=-0.97)。floorY 显式进 colliders.json(§4.1),别从 offset 推算——场景将来可能抬高。
7. **A* 不进渲染线程**:寻路在调用方协程算(毫秒级但没必要占帧),渲染线程只读结果;栅格缓存随场景 attach 重建(照 occlusion AABB 缓存失效模式)。
8. **blocked 必须发 WalkInterrupted**:否则就位链 awaitStandstill 等 30s 静默 abort 的既有坑(autonomy/interact 链已记录)在碰撞场景下会高频复发——碰撞层顺手把它修成"提前退出+归因"。
9. **bounds 与 colliders 双重约束的优先级**:bounds clamp 仍在 startWalk 入口(引擎层),墙滑在写入点(渲染层)——两层都留,测试用例里锁死"bounds 内必可达(barring colliders)"的语义,防 LLM 通道出现"目标被 clamp 进家具内部"的死角。
10. **登阶吸附平滑频率别太高**:台阶级差小、吸附过快=视觉高频抖动;8-10Hz 临界阻尼起步,真机调。
11. **VRM0/VRM1 兼容**:碰撞全在世界系,root 的 Y-180 翻转烘在矩阵里不影响圆-OBB 数学;唯 heading 提取已带 isVrm0 符号(rootHeadingRad),blocked 时回传的朝向信息走同一出口。
12. **多人 AvatarRuntime 镜像**:stage 侧 updateLocomotion(:412/:830)是单人的镜像实现,碰撞过滤要抽成两个 renderer 共用的组件,别复制粘贴第二份(坑 41"session 重建僵尸 collector"同族教训)。

---

## 11. 分期路线图

| 期 | 内容 | 人日 | 验收核心 |
|----|------|------|---------|
| **P0 防穿模核心** | colliders.json schema+解析、SceneCollisionMath/World(推离/墙滑/区间过滤/blocked)、双写入点拦截、Blender colliders 导出+autogen `colliders_scan` 工具、ai_cmd `colliders` 调试族、WalkInterrupted(BLOCKED) 归因、单测 ~20 例、dream_bedroom 全套碰撞体标定 | **4~5** | 手柄撞床/桌/墙零穿模;walk_to 穿桌被挡+归因;跑步 4.2m/4s 速度回归不变 |
| **P1 楼梯+寻路** | 地面高度场+登阶/吸附、楼梯限速、占据栅格+A*+视线拉直接多段腿、3 级台阶测试场景(顺带验导出管线) | **3~4** | 台阶上下往返无滑步无抖动;LLM「去窗边」被书桌挡时自动绕行 |
| **P1.5 多人** | AvatarRuntime 镜像共用过滤组件、圆-圆角色分离、走位寻路 | **1~1.5** | 双人对走互不穿越;辩论/团播回归不破 |
| **P2 上下床优雅化** | `dismount` 下床落点+落地缓冲剪辑、`mountInClip` 上床过渡(素材先行:fbx2vrma 检索或 Blender 手 K)、PromptTexts | **2~3**(+素材 1~2) | 躺下→起身落在床边地板;上床有"过程感"(素材到位后) |
| 合计 | | **≈11~14** | |

顺序建议:P0 → P2 的 dismount 落点(便宜且观感收益大,1 人日内)→ P1 → P1.5 → mount 素材。P0 单独交付即解决"穿模"主诉。

## 12. 明确不做(本篇范围外)

- 刚体动力学:推箱子、家具被撞飞、ragdoll 摔倒——物理引擎路线(§5)的适用场景,等有"可交互道具"需求再立项;
- 连续碰撞检测(CCD):最大位移每帧 <10cm,离散检测无隧穿风险;
- 动态障碍(移动的椅子/门):colliders 静态假设;真需要时按"重新 attach"处理;
- 三角网格级精确碰撞:棱柱包络的 ~10cm 级误差在第三人称相机下不可见;
- GPU pick 碰撞、navmesh 烘焙(场景规模不需要,§5/§7.3)。

---

## 13. 与其他文档的关系

- 依赖/复用:locomotion-feasibility(写入点/多段腿/提取回灌)、dream-bedroom-integration(sidecar 模式/交互链/定姿)、gamepad-control-feasibility(模拟量通道)、autonomy-feasibility(第五个运动消费者,WANDER 直接受益于 P0/P1);
- 实现后同步义务(惯例三件套):`ai-layer-handoff.md`(条目+坑 42+)、`project-guide.md`(模块表加 SceneCollisionWorld 行)、`ai-debug-intents.md`(colliders 命令族);
- 后续延伸(P3+):物理道具(Jolt 重评估)、多层场景 bounds、动态门/门帘。

## 附:参考

- Filament 官方讨论"CPU Picking (low-latency raycast)"(碰撞需自建,官方无计划):github.com/google/filament/discussions/5998
- Filament 仅渲染定位与 View.pick 语义:github.com/google/filament(#8605 等)
- Jolt JVM 绑定(留档备用):github.com/stephengold/jolt-jni;Jolt Character/CharacterVirtual:jrouwe.github.io/JoltPhysics
- Unity CharacterController stepOffset 登阶语义(业界同款思路的参照)
