# 桃隐仙居 · Peachveil Sanctuary

参考附图中暖木梁架、粉色纱帐、花灯与桃花的室内氛围，并沿用 `assets/dream_bedroom/README.md` 的坐标、家具语义、交互点及分墙规范，使用 Blender 原生建模的新中式修仙小屋。室内 **8 × 7 米，约 56 平方米**；檐高 3.25 米、结构屋脊 4.65 米，脊瓦装饰最高约 4.82 米。粉纱架子床、矮茶桌与蒲团、书案、书架、香案、剑架及莲台打坐区形成完整室内布置。

北侧与东侧为木柱、花格和敞开栏杆，南侧入口两旁及西侧为暖色灰泥木框墙，开口借景项目已有天空盒。**没有屋外地形、山水、云海、建筑、外景树木、天空球、天空贴图背景板或其他屋外场景**。室内桃花为装饰；Blender 预览的世界颜色、相机和灯光不导出到 GLB。

本次只新增 `assets/dream_bedroom_3/` 和对应制作工具，没有覆盖原卧室，没有复制到 `app/src/main/assets/scene/`，没有修改 SDK 或应用代码。

## 交付文件

| 文件 | 用途 |
| --- | --- |
| `dream_bedroom_3.blend` | 可继续编辑的 Blender 源文件，含预览相机和灯光 |
| `dream_bedroom_3.glb` | 米制、+Y 向上的独立室内资产，贴图内嵌 |
| `interactions.json` | 14 个家具/入口语义节点、18 个交互点、朝向、接触语义、实际网格 AABB 与导航图 |
| `dream_bedroom_3.cutaway.json` | 四面墙和屋顶独立隐藏组、网格后代名单、边界平面与法线 |
| `dream_bedroom_3.meta.json` | 当前 SDK 的场景平移及可走矩形 |
| `dream_bedroom_3.anchors.json` | `MoveAnchor` 可读的 12 个 XZ 行走点、11 条双向通道 |
| `floorplan.svg` / `floorplan.png` | 米制平面图，标明家具原点、方向与屋顶剖面 |
| `interaction_map.svg` / `interaction_map.png` | 交互点、接触语义、朝向及静态导航路径 |
| 预览图 | `bedroom_preview.png`、`cutaway_preview.png`、`tea_preview.png` |
| `validation.json` / `validation.txt` | 最终 GLB 节点、坐标、几何边界、接触面与导航校验 |
| `gltf_validation.json` | Khronos glTF 格式验证报告 |
| `textures/` | 原始程序化材质贴图；运行 GLB 不需要外部加载 |
| `SHA256SUMS.txt` | 交付文件完整性校验值（若已生成） |

## 分墙和屋顶显隐

| 分组节点 | 内容 | 外法线（未偏移 GLB） |
| --- | --- | --- |
| `WALL_NORTH` | 北侧木柱、栏杆、花格与附属装饰 | `(0,0,-1)` |
| `WALL_SOUTH` | 南侧入口、灰泥隔墙、木框与附属装饰 | `(0,0,1)` |
| `WALL_EAST` | 东侧木柱、栏杆、花格与附属装饰 | `(1,0,0)` |
| `WALL_WEST` | 西侧灰泥墙、木框护墙板及月纹装饰 | `(-1,0,0)` |
| `CEILING` | 中式曲面屋顶、梁架及悬灯装饰 | 36 段屋面参考法线，见 JSON |
| `FLOOR` | 地板与底板 | 始终保留 |

**所有墙组和屋顶在交付文件中默认显示**。这些组都是空变换节点，Filament 中只隐藏父实体不会自动隐藏子网格；宿主须递归控制全部可渲染后代，或按 `cutawayGroups[].renderableNodes` 名单逐一操作。五个可隐藏组的网格后代互不重叠，地板不属于隐藏组。床、茶席、书案与独立家具保留各自语义节点。`cutaway_preview.png` 展示隐藏 `WALL_SOUTH`、`WALL_EAST` 和 `CEILING` 后的房间，地板与家具保留；渲染结束后源文件恢复全部组可见。

屋顶沿 Z 方向起脊，采用两侧各 18 段的弯曲屋面。轮廓公式为 `Y = 3.25 + 1.40 × (1 − abs(X)/4.20)^1.30`，檐口 X=±4.2 米。`CEILING.planePointGltf` 是结构屋脊参考点，不能当成整个屋顶的水平面；`boundaryPlanesGltf` 给出原始屋面网格 36 个分段平面、端点及法线，不包含板厚、筒瓦和脊瓦装饰。组的实际 AABB 包含梁架、瓦片和悬挂装饰，最高脊瓦约 4.82 米。四个墙平面表示室内边界，开口不会被本元数据填成实墙。

所有平面、法线和家具边界均使用未应用 placement 的 GLB 世界坐标。做相机遮挡判断前须转换到与相机、角色相同的空间。GLB 本身不包含自动剖切或显隐逻辑。

## 坐标和方向

所有数值单位为米。GLB 使用右手系，+Y 向上，地板上表面为 Y=0，房间中心为原点。室内 X 为 `-4..4`、Z 为 `-3.5..3.5`，北侧为负 Z。Blender `(x,y,z)` 导出为 GLB `(x,z,-y)`，导入后不用再旋转 90°。

```text
yaw 0° = +Z，90° = +X，180° = -Z，-90° = -X
forward = [sin(yaw), 0, cos(yaw)]
quaternionXYZW = [0, sin(yaw/2), 0, cos(yaw/2)]
sdkWorld = gltfPosition + [0, -1, -4]
```

三角函数先将角度换为弧度。SDK 中地板 Y=-1、房间中心 `(0,-1,-4)`，保留原始米制尺寸。`gltfPosition/gltfOrigin/gltfBounds` 未偏移；`sdkWorldPosition/sdkWorldOrigin` 以及 walking sidecar 的 x/z 已经应用平移，不能重复叠加 meta 偏移。若场景父节点已应用 placement，应读取各节点最终世界矩阵。

## 家具定位

下表为家具父节点原点和前向。实际 AABB 从最终 GLB 的 POSITION 顶点与累积世界矩阵计算，见 `objects[].gltfBounds`；家具原点不一定是网格中心或座面中心。`PORTAL_ENTRY` 是无网格的入口语义点，boundsKind 为 semantic_point，min/max 都等于它的原点，不表示实体门洞包围盒。

| 名称 | GLB 节点 | 原点 (X,Y,Z) | yaw° |
| --- | --- | --- | ---: |
| 桃华流霞架子床 | `FURN_BED` | `(2.05, 0, -1.05)` | 0 |
| 鎏金床头小柜 | `FURN_NIGHTSTAND` | `(3.6, 0, -1.9)` | 0 |
| 听雨书案 | `FURN_DESK` | `(-2.65, 0, -2.75)` | 0 |
| 如意圈背书椅 | `FURN_CHAIR_DESK` | `(-2.65, 0, -1.65)` | 180 |
| 花间矮茶案 | `FURN_TEA_TABLE` | `(-2, 0, 0.75)` | 0 |
| 茶席北蒲团 | `FURN_TEA_CUSHION_NORTH` | `(-2, 0, -0.15)` | 0 |
| 茶席南蒲团 | `FURN_TEA_CUSHION_SOUTH` | `(-2, 0, 1.65)` | 180 |
| 桃花织锦地毯 | `FURN_RUG` | `(-1.5, 0, 0.75)` | 0 |
| 凝神白玉莲台 | `FURN_MEDITATION` | `(2.35, 0, 2.15)` | 0 |
| 藏经小书阁 | `FURN_BOOKCASE` | `(-3.65, 0, 0.2)` | 0 |
| 凝香供案 | `FURN_ALTAR` | `(-2.65, 0, 2.9)` | 0 |
| 青霄灵剑架 | `FURN_SWORD_STAND` | `(3.45, 0, 0.95)` | 0 |
| 入户门槛 | `PORTAL_ENTRY` | `(0.15, 0, 3.5)` | 180 |
| 青瓷桃枝花器 | `FURN_PEACH_VASE` | `(-3.55, 0, -2)` | 0 |

床前向指向床尾；椅子和蒲团前向表示坐下后身体面向。床的纱帐、床架与软装同属于床语义组；步行障碍检查采用包含它们的保守整体 AABB。

## 虚拟人交互

| ID | 用途 / 身体接触语义 | GLB (X,Y,Z) | yaw° | 可走 |
| --- | --- | --- | ---: | :---: |
| `SPAWN` | 中央出生/站立 / `feet` | `(0, 0.015, 1.1)` | 180 | 是 |
| `ENTRY` | 门内入口 / `feet` | `(0.15, 0.002, 2.95)` | 180 | 是 |
| `AISLE_CENTER` | 中央行走通道 / `feet` | `(0, 0.015, 0)` | 180 | 是 |
| `BED_APPROACH` | 纱帐床侧接近 / `feet` | `(0.15, 0.002, -0.4)` | 90 | 是 |
| `BED_SIT` | 床沿坐姿接触 / `buttock_surface` | `(1.05, 0.787269, -0.45)` | -90 | 否 |
| `BED_LIE` | 床上仰卧髋背接触 / `back_hip_surface` | `(1.5, 0.805269, -1.05)` | 180 | 否 |
| `BED_HEAD` | 枕区头部校准 / `head_surface` | `(1.5, 1.0115, -2.1)` | 180 | 否 |
| `DESK_APPROACH` | 书案椅前接近 / `feet` | `(-2.65, 0.033, -0.85)` | 180 | 是 |
| `DESK_SEAT` | 书案椅座面接触 / `buttock_surface` | `(-2.65, 0.502, -1.65)` | 180 | 否 |
| `NORTH_AISLE` | 北侧行走通道 / `feet` | `(-0.25, 0.002, -1.3)` | 180 | 是 |
| `WINDOW_VIEW` | 北侧栏前观景 / `feet` | `(-0.2, 0.002, -2.85)` | 180 | 是 |
| `TEA_APPROACH` | 茶席接近 / `feet` | `(-0.85, 0.028, 1.65)` | -90 | 是 |
| `TEA_SEAT` | 南侧蒲团坐姿接触 / `buttock_surface` | `(-2, 0.221999, 1.65)` | 180 | 否 |
| `MEDITATION_APPROACH` | 修炼台侧接近 / `feet` | `(1.05, 0.002, 2.15)` | 90 | 是 |
| `MEDITATION_SEAT` | 莲台打坐接触 / `buttock_surface` | `(2.35, 0.1965, 2.15)` | 180 | 否 |
| `ALTAR_APPROACH` | 香案侧接近 / `feet` | `(-1.15, 0.002, 2.4)` | -90 | 是 |
| `EAST_CROSSING` | 床尾修炼台间通道 / `feet` | `(1.05, 0.002, 0.98)` | 90 | 是 |
| `SWORD_APPROACH` | 剑架前赏剑 / `feet` | `(2.6, 0.002, 0.98)` | 90 | 是 |

每个 `ANCHOR_<ID>` 都有前方 0.5 米的 `FACING_<ID>`，部分另有 `LOOK_<ID>` 视线目标。`anchorId/label/kind/contact/yawDeg/navigable/relatedNode` 随 glTF extras 导出。读取节点位置时必须累乘父变换，不能直接把局部 translation 当世界坐标。

坐姿、躺姿、枕区点由 Blender 对指定支撑网格垂直 BVH 射线求交，取接触面再加 2 毫米；独立验证脚本从导出的真实三角形复查。脚底站立点按地板/地毯的实际表面采样。地毯可走，完整 Y 高度见本表；walking sidecar 只提供 XZ。

这些点是**身体表面接触目标，不是通用虚拟人 root 坐标**。应先走到对应 approach 点，播放实际坐下、躺下或打坐动作，采样骨骼世界变换，并以模型专属的骨骼到身体表面偏移修正根平移：

```text
currentContactWorld = boneWorldMatrix * modelSpecificContactOffset
rootTranslation += targetContactWorld - currentContactWorld
```

当前 SDK 会把模型最长边归一为 2 米；模型原点、髋骨位置、体型与动作仍不同。坐姿需考虑臀部厚度；仰卧需实际躺姿动画。`BED_LIE` 的 yaw 表示从脚指向头的身体长轴，不会让站姿自动躺下；`BED_HEAD` 只是辅助头部校准。莲台为打坐接触参考，不包含角色盘腿动画。宿主还需检查膝盖、脚底、床栏与纱帐关系并平滑过渡，必要时应用 IK。

## SDK 接入

本任务没有把文件复制进应用。需要接入时，将 GLB、同名 meta、anchors 和 cutaway sidecar 放入 `app/src/main/assets/scene/` 并重新打包，或使用仓库的场景接入工作流。完整 `interactions.json` 还需转换成应用解析的 `<场景名>.interactions.json` 简化字段结构，不能直接把完整表改名复制。当前 `SceneMetaParser.loadWalkAnchors(context, sceneFile)` 会优先读取场景同名 anchors，缺失时退回 home；`MainActivity` 已按 selectedScene 更新锚点集合。

```kotlin
controller.loadScene(
    "scene/dream_bedroom_3.glb",
    ScenePlacement(
        offsetX = 0f, offsetY = -1f, offsetZ = -4f, yawDeg = 0f,
        bounds = WalkBounds(-3.65f, 3.65f, -7.15f, -0.85f),
    ),
)
```

`MoveAnchor` 只读取 name/x/z/label/labelEn/links；它不会自动执行 yaw、Y、视线、坐躺动画或接触对齐。`interactions.json` 保存完整约定。应用已提供同名简化交互表加载和坐躺接触流程，但本包未生成或注入应用侧交互投影；新增茶席/打坐点还需选择并校准适合的动画。矩形 bounds 只钳制房间范围，不能防止穿床或穿桌。

## 导航、验证和规模

最终 GLB：**11,328,188 字节（10.80 MiB）**；**183,608 三角形、20 个 mesh、123 个 primitive、27 个材质、8 张内嵌贴图**。

本包共 11 条双向静态路径，连接 12 个行走点；坐姿、床面和头部点不进入步行图。12 个主要家具按最终网格 AABB 在 XZ 各向扩张 0.22 米，校验全部目标及连线。通往书案经北侧通道绕过茶席，赏剑经 `EAST_CROSSING` 绕过床尾与修炼台；东侧通道较窄，应沿给定路径行走。地毯视为可走面。

几何校验状态：**PASS**。`validate_dream_bedroom_3.py` 检查实际节点、世界坐标、方向、FACING/LOOK、SDK 平移、家具 AABB、座面接触、walking sidecar、双向连通、分墙网格后代、地板隔离和无外部资源引用。JSON/GLB 一致性容差为 `0.00002m`，不代表运行时动作对齐达到该精度。Khronos glTF Validator：**0 错误、0 警告**，完整报告见 `gltf_validation.json`。

这是静态图和保守几何检查，未覆盖任意起点接入导航图的首段、不同体型、摆臂、额外道具、动态避障或动作接触。半径大于 0.22 米的角色需要重新验证。未做 Android/Filament 真机渲染、移动端性能或特定 VRM 坐躺/打坐姿态适配。

GLB 使用 glTF metallic-roughness PBR，无骨骼、无动画。灯笼发光网格不会自动照亮宿主场景；运行时由项目提供天空盒、IBL、光源、阴影和透明排序，亮度与 Blender 预览可能不同。纱帐有半透明混合材质。未附 LOD。

## 重建与预览

工具在独立 Blender 进程中制作该资产，不会覆盖旧卧室。可用本机 Blender 可执行文件代替命令中的 blender。

```bash
blender --background --factory-startup --python tools/blender/build_dream_bedroom_3.py
python tools/blender/dream3_metadata.py
python tools/blender/validate_dream_bedroom_3.py
blender --background assets/dream_bedroom_3/dream_bedroom_3.blend --python tools/blender/render_dream_bedroom_3.py
python tools/blender/dream3_metadata.py --docs-only
```

重建入口依次执行 `dream3_furniture.py`、`dream3_decor.py` 和 `export_dream_bedroom_3.py`；导出复用 `dream2_optimize.py` 的按语义组网格合并函数，保留家具及交互空节点。`dream3_metadata.py` 从最终 GLB 生成 JSON、SVG/PNG 图及本说明。`validate_dream_bedroom_3.py --update-bounds` 可刷新 JSON 中的实际边界；通常重跑 metadata 即可。Khronos 检查入口 `validate_dream_bedroom_3_gltf.cjs` 使用官方 gltf-validator npm 包，可通过 `GLTF_VALIDATOR_MODULE` 指向仓库外的包目录，无须改动项目 npm 依赖。渲染脚本结束后恢复四墙与屋顶全部可见。
