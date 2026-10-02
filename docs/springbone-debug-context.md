# VRM 弹簧骨骼攻坚 — 上下文交接文档

> 更新:2026-10-02。供新会话继续排查"效果与 three-vrm 不完全一致"使用。
> 结论先行:**物理算法已通过与 three-vrm 官方库数值级一致性验证;当前未解决的是 App 真机表现仍有差异,原因尚未定位,但排查范围已大幅收窄。**

---

## 1. 涉及的代码(本项目 AIAvatar-SDK)

| 文件 | 作用 |
|---|---|
| `corelib/src/main/java/com/neethu/corelib/internal/VrmSpringBoneManager.kt` | 弹簧骨骼物理(本轮重写过 3 次,当前状态 = three-vrm 移植 + 4 项修复) |
| `corelib/src/main/java/com/neethu/corelib/internal/SoulLinkRenderer.kt` | 渲染器:帧循环(动画→表达式→弹簧→updateBoneMatrices→render)、拖拽处理、模型加载管线 |
| `corelib/src/main/java/com/neethu/corelib/internal/VrmaAnimationEngine.kt` | VRMA 动画重定向(只写 humanoid 骨骼,不碰 J_Sec 弹簧骨骼) |
| `corelib/src/main/java/com/neethu/corelib/internal/GlbBoneCuller.kt` | 皮肤关节 >256 时裁剪/合并(10.vrm 与 Twist_Sample 都未触发) |
| `corelib/src/main/java/com/neethu/corelib/AvatarController.kt` | 公共 API 层 |

**当前弹簧实现要点(全部经 three-vrm 官方源码逐行对照):**
- Verlet 惯性在 spring 的 `center` 节点空间积分(10.vrm 全部 center=Root;Twist_Sample 无 center=世界空间),刚度/重力/长度约束/碰撞在世界空间。
- 每帧骨长 = `|上一帧骨骼尾端世界位置 − 本帧头位置|`(骨骼尾端 = head + 最终方向 × rest 骨长,即 three-vrm 下一帧读 `child.matrixWorld` 的等价物);**不能用 verlet 尾巴位置**(会无界膨胀锁死)。
- 虚拟尾巴关节(无子节点)保持固定 7cm×世界缩放。
- VRM 1.0 链的最后一个 schema joint 不模拟、只作 tail(three-vrm v1 导入语义);VRM 0.x 子树全模拟。
- substep:每帧积分切成 ≤1/120s 的子步(最多 8 步),帧内 center 矩阵只算一次。
- 碰撞半径与 hitRadius 乘各自节点的世界缩放(`transformToUnitCube` 会把 asset.root 缩放 2/最大边长,Twist 模型 ≈1.238)。
- 动画/VRMA 起停时 `pendingSpringReset` 标志 → 第一帧新姿态应用后调 `reset()`。

**本轮新增 API:**
- `AvatarController.setDragMovesHips(Boolean)` — 拖拽模式移动 humanoid hips 骨骼(three-vrm mouse.html 语义,默认开)而非 asset.root;VRM 0.x 已做 180° 坐标补偿。
- `AvatarController.setSpringBoneDebugLog(Boolean)` — 每秒输出 logcat(tag `SpringBone`):前 4 条链的 `骨长/静止骨长` 与方向。**真机排查的第一工具。**

## 2. 已修复的 Bug(4 轮,按时间序)

1. **平移模型头发裙子方向错误**:实现忽略 `VRMC_springBone.springs[].center`,Verlet 状态存世界空间 → 整体平移时头发拖尾/甩飞。修复:tail 状态存 center 空间,惯性在 center 空间积分(见 `tools/springbone-sim/sim_center_basic.py`)。
2. **VRMA 动作时头发疯狂飞舞 + 针状卡死**:三个叠加因素——(a) 固定骨长无 slack,可变帧时长(真机卡顿)下约束猛拽;(b) VRM 1.0 末关节(7cm 虚拟尾巴)被模拟,对甩动/碰撞极敏感,被推过头后停在倒转平衡(回复力每帧仅 ~0.7%);(c) 起播姿态瞬移不 reset。修复:每帧骨长 + 跳过末关节 + pendingSpringReset。
3. **拖动方向锁死**:第 2 轮的"每帧骨长"最初用了 **verlet 尾巴**做基准 → 连续拖动时骨长无界膨胀(0.075→0.12m+),方向锁死不回弹。修复:改用骨骼尾端位置(第 1 节所述)。
4. **骨骼尾端基准 + 剧烈 VRMA 又 flail**:放开阻尼后需要 substep 压稳定性。修复:≤1/120s 子步。

修复效果(可变 dt 模拟真机抖动,三个 VRMA:Shuffling/Samba/Standing):flail 帧 200/75/0 → **1/0/0**。

## 3. 验证基础设施(tools/springbone-sim/)

### 3.1 Python 全链路模拟(无需 Android 环境)
忠实移植 Kotlin 实现(glTF 解析→变换层级→VRMA 解析/重定向→弹簧物理→碰撞),numpy 实现。**已验证与真实 three-vrm 运行时数值一致。**

| 脚本 | 用途 | 关键点 |
|---|---|---|
| `sim_vrma_pipeline.py` | **主力**:10.vrm + 真实 VRMA + 全部修复 + 可变 dt + flail/倒转指标 | 文件尾部有多个实验块,`FINAL` 块 = 当前 Kotlin 行为;整跑较慢(几分钟) |
| `sim_twist_drag.py` | Twist_Sample 拖动语义测试(root/hips、快/慢、回弹) | 场景 A/B/C + 释放回弹 |
| `sim_center_regression.py` | 10.vrm center 回归:静止/匀速平移/传送 | 期望:方向与骨长完全不变 |
| `sim_drag_collision.py` | 拖动+碰撞:肩部胶囊卡死分析 | 当前:0 卡肩 |
| `sim_settle_variants.py` | 静置对比(每帧骨长 vs 固定、有无 substep) | 三变体结果一致且正常 |
| `sim_center_basic.py` `sim_minimal_isolated.py` `sim_fullmodel_first.py` | 第一/二轮的历史验证(教学价值) | |

运行:`python3 <script>`(依赖 numpy;模型路径为绝对路径,指向 `app/src/main/assets/vrms/` 与 three-vrm 仓库)。

**方法论警告:固定 dt=1/60 的模拟不炸但真机会炸——必须用可变 dt(如 uniform(1/120, 0.05))才复现真机。**

### 3.2 Node 直接跑 three-vrm 官方源码(GROUND TRUTH)
`node_run_twist.ts` — 用 esbuild 把 three-vrm TS 源 + GLTFLoader 打包后在 Node 无头运行,`vrm.update(dt)` 完整可用。**已产出的基准数值(Twist_Sample,无 center,57 个模拟关节):**

```
settled:            Hair1_05:[0.08,-0.99,-0.13] Hair1_09:[-0.05,-0.99,-0.09] Hair1_12:[0.04,-0.98,0.21]
hips-drag 1.5m/30帧: [-0.66,-0.58] [-0.90,-0.44] [-0.95,-0.31]
root-drag 1.5m/30帧: 与 hips-drag 完全一致(无 center 模型二者的物理等价)
flick 2m/帧:         [-1.00,-0.04](水平甩满),释放 5 秒完美回弹
1.238× 根缩放后:     全部行为不变
```

Python 移植的同样场景数值与之吻合(静止 `[0.08,-0.99,-0.13]` vs `[0.076,-0.988,-0.132]`)。

无头运行的关键 shim(踩坑所得,顺序敏感):`self=globalThis`、`ProgressEvent` 类、fetch 拦截(**GLTFLoader 传的是 Request 对象,取 `.url`**)、`TextureLoader.load` 打桩返回空 Texture、模型路径必须 `file://`。运行方法见文件头注释。

### 3.3 已排除的嫌疑(不要重查)
- 算法数学(verlet/旋转恢复/碰撞/约束)——与官方库数值级一致。
- `inside` 碰撞体——两个模型都没有。
- `VRMC_node_constraint`——Twist_Sample 没有(名字唬人)。
- GlbBoneCuller——两个模型皮肤都 <256,未触发;且它不改节点层级。
- VRM 0.x 路径——Twist/10.vrm 都是 1.0。
- 1.238× transformToUnitCube 缩放——官方库在此缩放下行为不变。
- 加载字节流不一致——用户手上的 Twist 文件与官方 md5 相同;MorphPatcher/Culler 输出仍用原始字节解析扩展,节点索引/名字不变。
- VRMA 重定向公式——与 three-vrm `createVRMAnimationClip` 逐项一致,且身体动画视觉正常。
- `TransformManager.getWorldTransform` 惰性提交——读时会 commit 脏变换,JNI 直接调 C++ `getWorldTransform`,set/get 可交错。

## 4. 未解决的问题(新会话从这里开始)

**现象**:真机上 Twist_Sample 的效果"和 three-vrm 不完全一致"(用户描述:头发轻微摆动、会卡在衣服上、僵硬;three-vrm 官方示例则全方位甩动且顺滑)。算法已排除,以下是剩余假设,按优先级:

1. **真机是否在跑最新代码?**(首要)第 3 轮修复(骨骼尾端基准+substep)与本轮的调试日志都刚写完,用户上一张截图的症状与第 2 轮修复前的"锁死"完全吻合。→ 让用户开启 `setSpringBoneDebugLog(true)` 重测:**健康判据 = len≈rest(≈0.075×1.238≈0.093)且不增长;松手 2~3 秒方向回到 `[0.08,-0.99,-0.13]` 等 rest 值。** len 增长 = 锁死仍在;方向恒 rest 不动 = 弹簧没收到头部运动(查 hips 是否真被拖动)。
2. **拖拽输入映射与 mouse.html 的差异**:mouse.html 是绝对映射(鼠标→±2.68m,单帧 Δ 可达 0.2m+);App 是增量 `dx_px×0.005`(慢拖单帧 Δ 仅几 mm → 稳态倾角只有几度,看起来就是"轻微摆动")。**这不是 bug 而是映射标定差异**——可对比同 Δ 下的输出,或调大 `0.005` 系数/加惯性。
3. **Filament 变换数据流的残余差异**(次优先):
   - 拖拽在 UI 线程逐事件 `setTransform(asset.root/hips)`,一帧内可能多次写入;Choreographer 帧内弹簧只读一次(子步间不重读 center——center 帧内不变,但 **无 center 模型的 root 平移会连续变化,子步间 head 位置不变——与世界空间 verlet 的耦合是否与 three-vrm 的逐帧语义有差?**)——Node 端没有"帧内多次位移"的对应物。
   - `updateBoneMatrices()` 每帧调两次(动画分支 + 弹簧后);只影响蒙皮,不影响变换,但未实测。
4. **帧循环顺序**:动画分支在 `lastFrameTimeNanos>0` 判断之外,弹簧 dt clamp [0.001,0.05];极端掉帧时动画位移大而 dt 被钳制 → 拖尾形状与 three-vrm(不钳制)不同。可考虑放宽上钳或不钳。
5. **观感差异**:mouse.html 相机 FOV 30°/距离 10,App 相机不同;甩动幅度观感不同不代表物理不同 → 建议让用户录屏对比并报 logcat 数值。

**建议的新会话第一步**:让用户在真机上 `setSpringBoneDebugLog(true)`,执行"慢拖/快拖/松手"各 3 秒,把 logcat 贴出来——一次就能分辨"代码没更新 / 锁死仍存 / 拖拽增益太小 / 物理确实一致只是观感"。

## 5. 关键领域事实(免得重新踩坑)

- **VRM `center` 语义**:Verlet 状态存 center 节点空间;整体平移/旋转模型时头发刚性跟随零反应(10.vrm 全链 center=Root,这是作者意图)。想拖动时有反应 → 移动 hips(身体内部运动),App 已实现(默认)。
- **mouse.html**:mousemove → `humanoid.getNormalizedBoneNode('hips').position.set(px,py,0)`,屏幕映射 ±2.68m;相机固定不动。
- **three-vrm v1 导入**:只为 `joints[0..n-2]` 建模拟关节;VRM 0.x `root.traverse` 全子树,tail=第一层级子节点,叶子用 7cm 虚拟尾巴。
- **three-vrm 每帧骨长**:`_calcWorldSpaceBoneLength` 读 child 的 matrixWorld——依赖其更新顺序(父先于子更新),子矩阵天然是"上一帧末"——等价于缓存骨骼尾端位置。
- **UniVRM 的 7cm 虚拟尾巴**:无子节点时按 spec 用 0.07m,方向 = 父→本节点。
- **VRM 0.x 差异**:碰撞体 offset Z 取反;模型加载后根节点需 180° Y 旋转(App 已做)。
- **Filament**:列主序 float[16];`TransformManager.getWorldTransform` 读时惰性 commit 脏变换;`transformToUnitCube` 缩放 `2/maxExtent` 并把模型中心放到 `<0,0,-4>`。
- **10.vrm**:VRM 1.0,VRoid Studio 2.10.0,无内置动画;22 springs/28 colliders,全部 center=Root。头发 `J_Sec_Hair1_21` 是呆毛(bind 方向朝上 `[0.37,0.61,0.7]`,指天是正常的!)。

## 6. 对外承诺过的行为基线(回归清单)

1. 10.vrm:静止/拖动 root/传送 → 头发零反应、刚性跟随;VRMA 三舞种 → 无疯狂飞舞、无针状卡死(模拟 flail 1/0/0)。
2. Twist_Sample(无 center):拖动 → 全方位拖尾,幅度随速度,松手 ≤5s 完全回弹,骨长恒定。
3. 拖拽模式默认移动 hips;pan(相机)永远无反应(正确)。
4. 编译:`./gradlew :corelib:compileDebugKotlin :app:compileDebugKotlin` 当前通过。

## 7. three-vrm 参考资料(本地仓库 /home/neethu/projects/three-vrm)

- `packages/three-vrm-springbone/src/VRMSpringBoneJoint.ts` — update 主循环(verlet/骨长/旋转恢复)
- `VRMSpringBoneColliderShape{Sphere,Capsule}.ts` — 碰撞(含 inside 语义)
- `VRMSpringBoneLoaderPlugin.ts` — v1/v0 导入、center、tail 解析
- `VRMSpringBoneManager.ts` — 依赖排序更新
- `packages/three-vrm/examples/mouse.html` — hips 拖拽示例
