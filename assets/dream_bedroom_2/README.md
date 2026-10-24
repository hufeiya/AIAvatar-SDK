# 星潮玻璃小屋 · Starlit Tide

参考提供的玻璃屋顶梦幻小屋图片与 `assets/dream_bedroom/README.md` 的资产约定，使用 Blender 原生建模的新室内场景。8 × 7 米，室内 56 平方米；檐高 3.15 米、屋脊 4.85 米。暖木船形床、蓝白织物、长书架、垂藤、黄铜望远镜、玻璃浮球、渔网灯串与小灯笼构成航海观星主题。

**天花板为真实的双坡透明玻璃**，并非星空贴图或背景板。房间包含北侧山形全景窗与东侧玻璃窗；**没有屋外地形、海面、云、树木、天空球、星空或月亮背景**，窗外和玻璃屋顶外应由项目已有天空盒显示。Blender 预览使用的世界颜色和灯光不导出到 GLB。

本次仅新增 `assets/dream_bedroom_2/` 和对应制作工具，未覆盖原卧室，也未修改应用默认场景或 SDK 行为。

## 交付文件

| 文件 | 用途 |
| --- | --- |
| `dream_bedroom_2.blend` | Blender 源文件，含材质、相机与预览灯光 |
| `dream_bedroom_2.glb` | 米制 +Y 向上、可独立导入的室内资产 |
| `interactions.json` | 19 个家具/玻璃语义节点、20 个交互点、方向与接触语义、导航图及实际几何 AABB |
| `dream_bedroom_2.cutaway.json` | 四面墙与玻璃屋顶的独立隐藏组，实际网格后代名单及平面 |
| `dream_bedroom_2.meta.json` | 当前 SDK 的场景平移、可走矩形范围 |
| `dream_bedroom_2.anchors.json` | 当前 `MoveAnchor` 解析器可读的 13 个 XZ 行走点、12 条双向通道 |
| `floorplan.svg` / `floorplan.png` | 家具位置、原点、方向及玻璃屋顶横剖面 |
| `interaction_map.svg` / `interaction_map.png` | 交互点、接触语义、朝向与导航关系 |
| `bedroom_preview.png` / `glass_roof_preview.png` / `cutaway_preview.png` | Blender 预览，具体以目录内文件为准 |
| `validation.json` / `validation.txt` | 最终 GLB 节点/坐标/AABB/导航与玻璃屋顶检查 |
| `gltf_validation.json` | Khronos glTF 格式验证报告（若已生成） |

## 分墙与玻璃屋顶显隐

| 分组 | 内容 | 外法线（未偏移 GLB） |
| --- | --- | --- |
| `WALL_NORTH` | 北墙、山形全景玻璃与窗框 | `(0,0,-1)` |
| `WALL_SOUTH` | 南墙、入口与壁挂电视 | `(0,0,1)` |
| `WALL_EAST` | 东墙、侧面玻璃与窗框 | `(1,0,0)` |
| `WALL_WEST` | 西墙、护墙板与墙上书架 | `(-1,0,0)` |
| `CEILING` | 双坡玻璃、屋顶木梁、灯网和悬挂装饰 | 两个向上的斜面法线，见 JSON |
| `FLOOR` | 木地板 | 始终保留 |

所有墙与屋顶默认可见。`GLASS_ROOF` 是 `CEILING` 的后代，隐藏天花板组时玻璃和顶上装饰会一起隐藏。玻璃的金属粗糙度 PBR 材质使用 alpha 混合；宿主需正确开启透明混合并提供天空盒/IBL。

各组为空变换节点。**Filament 中仅隐藏父实体不会自动隐藏子网格**；须递归控制可渲染后代，或按 `cutawayGroups[].renderableNodes` 的实际节点名单操作。五组的可渲染后代互不重叠，`FLOOR` 不属于隐藏组。床、阅读椅、地毯及独立家具不随墙隐藏；壁挂电视和墙上书架随所属墙隐藏。

屋脊沿 Z 轴，屋顶内界为 `Y = 4.85 - 0.425 × abs(X)`。`CEILING.planePointGltf` 是屋脊参考点；实际斜面使用 `boundaryPlanesGltf` 两个平面，不能用一个水平面代替。组的 `gltfBounds` 包括木梁、灯网与悬挂装饰。所有平面和法线都在未应用 placement 的 GLB 世界坐标中，检测前应与相机、角色转换到同一坐标空间。GLB 不包含相机遮挡或自动显隐逻辑。

## 米制坐标与 SDK 平移

GLB 为右手坐标系，`+Y` 向上，地面 `Y=0`；室内 X 为 `-4..4`、Z 为 `-3.5..3.5`。北窗在负 Z，电视在正 Z。Blender `(x,y,z)` 导出为 GLB `(x,z,-y)`，导入后无需再旋转 90°。

```text
yaw 0° = +Z，90° = +X，180° = -Z，-90° = -X
forward = [sin(yaw), 0, cos(yaw)]
quaternionXYZW = [0, sin(yaw/2), 0, cos(yaw/2)]
sdkWorld = gltfPosition + [0, -1, -4]
```

三角函数先把角度转为弧度。SDK 中地面 Y=-1、房间中心 `(0,-1,-4)`；不缩放原始米制尺寸。`gltfPosition/gltfOrigin/gltfBounds` 未偏移；`sdkWorldPosition/sdkWorldOrigin` 和 walking sidecar 的 x/z 已加平移，**不能再加一次 meta 偏移**。如果导入器已把 placement 加在场景父节点，应直接读取最终世界矩阵。

## 家具定位

下表为语义父节点原点。实际几何边界由最终 GLB POSITION 顶点和累积世界矩阵计算，见 `objects[].gltfBounds`。父节点原点不等于座面或家具几何中心。无独立网格的入口节点仅表示通道位置，其 boundsKind 为 semantic_point，min/max 都是原点，不表示实体边界。

| 名称 | GLB 节点 | 原点 (X,Y,Z)，米 | yaw° |
| --- | --- | --- | ---: |
| 橡木船形双人床 | `FURN_BED` | `(-2.15, 0, -1.05)` | 0 |
| 床头小柜 | `FURN_NIGHTSTAND` | `(-0.45, 0, -2.15)` | 0 |
| 窗边书桌 | `FURN_DESK` | `(3.35, 0, -0.55)` | 90 |
| 书桌椅 | `FURN_CHAIR_DESK` | `(2.42, 0, -0.55)` | 90 |
| 蓝白潮汐编织地毯 | `FURN_RUG` | `(0, 0, 0.7)` | 0 |
| 蓝色阅读扶手椅 | `FURN_CHAIR_READING` | `(2.7, 0, 1.55)` | -90 |
| 黄铜束带木桶边几 | `FURN_SIDE_TABLE` | `(3, 0, 2.65)` | 0 |
| 黄铜天文望远镜 | `FURN_TELESCOPE` | `(2.35, 0, -2.4)` | -25 |
| 窗下长书柜 | `FURN_WINDOW_BENCH` | `(1.8, 0, -3.1)` | 0 |
| 床头悬挂藏书架 | `FURN_BOOKCASE` | `(-3.93, 1.3, -1.4)` | 90 |
| 航海黄铜收纳箱 | `FURN_CHEST` | `(-2.65, 0, 2.65)` | 0 |
| 南侧影音矮柜 | `FURN_MEDIA_CONSOLE` | `(1.65, 0, 3.12)` | 180 |
| 壁挂显示屏 | `FURN_TV` | `(1.65, 1.7, 3.34)` | 180 |
| 游戏主机 | `FURN_GAME_CONSOLE` | `(2.1, 0.58, 3.1)` | 180 |
| 游戏手柄 | `PROP_CONTROLLER` | `(1.5, 0.635, 3.05)` | 180 |
| 入口 | `PORTAL_ENTRY` | `(-0.9, 0, 3.5)` | 180 |
| 北侧山墙玻璃 | `GLASS_PANORAMA_NORTH` | `(0, 0.7, -3.49)` | 0 |
| 东侧全景玻璃 | `GLASS_PANORAMA_EAST` | `(3.99, 0.7, 0)` | -90 |
| 全景双坡玻璃屋顶 | `GLASS_ROOF` | `(0, 0, 0)` | 0 |

玻璃朝向为室内法线，床朝向指向床尾，椅子朝向表示坐下后身体前方。玻璃屋顶不具有单一室内法线，实际屋顶法线在 cutaway 平面中给出。

## 站立、坐姿和躺姿交互

| ID | 用途 / 身体接触语义 | GLB (X,Y,Z)，米 | yaw° | 可走 |
| --- | --- | --- | ---: | :---: |
| `SPAWN` | 地毯中央出生/站立 / `feet` | `(0, 0.045999, 0.65)` | 180 | 是 |
| `RUG_WATCH_TV` | 地毯看电视 / `feet` | `(0.2, 0.046, 1.2)` | 0 | 是 |
| `BED_APPROACH` | 船床侧接近 / `feet` | `(-0.3, 0.045999, -0.2)` | -90 | 是 |
| `BED_SIT` | 船床侧沿坐姿接触 / `buttock_surface` | `(-1.1, 0.932, -0.3)` | 90 | 否 |
| `BED_LIE` | 船床仰卧髋背接触 / `back_hip_surface` | `(-2.77, 0.943868, -1.05)` | 180 | 否 |
| `BED_HEAD` | 枕区头部校准 / `head_surface` | `(-2.77, 1.14725, -2.2)` | 180 | 否 |
| `DESK_APPROACH` | 书桌椅前接近 / `feet` | `(1.55, 0.002, -0.55)` | 90 | 是 |
| `DESK_SEAT` | 书桌椅座面接触 / `buttock_surface` | `(2.42, 0.572, -0.55)` | 90 | 否 |
| `READING_APPROACH` | 阅读椅前接近 / `feet` | `(1.55, 0.002, 1.55)` | 90 | 是 |
| `READING_SEAT` | 阅读椅座面接触 / `buttock_surface` | `(2.7, 0.561999, 1.55)` | -90 | 否 |
| `NORTH_AISLE` | 北侧观星通道 / `feet` | `(0.5, 0.002, -1.25)` | 180 | 是 |
| `TELESCOPE_APPROACH` | 望远镜观星接近 / `feet` | `(1.55, 0.002, -1.55)` | 137 | 是 |
| `WINDOW_VIEW` | 北窗前站立观景 / `feet` | `(0.45, 0.002, -2.45)` | 180 | 是 |
| `WINDOW_APPROACH` | 窗边长凳前接近 / `feet` | `(0.9, 0.002, -2.45)` | 180 | 是 |
| `WINDOW_SEAT` | 窗边长凳座面接触 / `buttock_surface` | `(0.9, 0.622, -3.1)` | 0 | 否 |
| `TV_APPROACH` | 电视柜前接近 / `feet` | `(1.55, 0.002, 2.4)` | 0 | 是 |
| `GAME_APPROACH` | 游戏机前接近 / `feet` | `(2.1, 0.002, 2.4)` | 0 | 是 |
| `GAME_PICKUP` | 手柄抓握参考 / `hand_grip` | `(1.5, 0.69, 3.05)` | 0 | 否 |
| `ENTRY` | 门内入口站立 / `feet` | `(-0.9, 0.002, 2.9)` | 180 | 是 |
| `SOUTH_AISLE` | 船床尾通道 / `feet` | `(-0.4, 0.046001, 1.5)` | 90 | 是 |

每个 `ANCHOR_<ID>` 均带有前方 0.5 米的 `FACING_<ID>`；有视线目标的点另含 `LOOK_<ID>`。`anchorId/label/kind/contact/yawDeg/navigable/relatedNode` 自定义属性导出为 glTF `extras`。需累乘父变换读取世界位置，不能直接把局部 translation 当世界坐标。

坐姿、躺姿、枕区点的高度由 Blender 对相应家具指定接触网格求垂直 BVH 射线（排除船床边框与摆件），取实际表面再加 2 毫米。它们是**身体表面接触目标，不是通用虚拟人 root 坐标**。推荐先走到对应 approach 点，播放实际坐/躺动作、采样接触骨骼，再应用模型专属的骨骼到身体表面偏移：

```text
currentContactWorld = boneWorldMatrix * modelSpecificContactOffset
rootTranslation += targetContactWorld - currentContactWorld
```

当前 SDK 会按模型最长边归一为 2 米；原点、髋骨、体型及动作仍因 VRM 而异。坐姿需考虑臀部厚度，仰卧需使用实际躺姿动画。`BED_LIE` 的 yaw 只表示从脚指向头的身体长轴；`BED_HEAD` 为额外头部贴枕校准。`GAME_PICKUP` 仅是手部 IK/抓握参考，不包含手柄绑定。宿主还需处理脚底/膝盖/扶手接触、平滑过渡与视线。

## SDK 接入

将 GLB 与同名 meta 复制到 `app/src/main/assets/scene/` 后重打包，可由场景列表读取；本任务没有代替你进行这一步。

```kotlin
controller.loadScene(
    "scene/dream_bedroom_2.glb",
    ScenePlacement(
        offsetX = 0f, offsetY = -1f, offsetZ = -4f, yawDeg = 0f,
        bounds = WalkBounds(-3.65f, 3.65f, -7.15f, -0.85f),
    ),
)
```

`MoveAnchor` sidecar 仅控制 XZ 走位，yaw/Y 高度/视线/动作/坐躺接触来自完整 `interactions.json`。应按当前场景加载对应 anchors，换场景时更新会话导航集合；仅放入 JSON 不等于 SDK 已自动启用本资产交互。应用当前版本的默认 anchor 加载方式可能固定为另一场景，需在集成时核对，避免使用旧房间点位。

## 验证和规模

最终 GLB 为 **7,772,332 字节（7.41 MiB）**，包含 **135,774 三角形、24 个 mesh、109 个 primitive、21 个材质、6 张内嵌贴图**。

`validate_dream_bedroom_2.py` 直接读取最终 GLB POSITION 顶点、完整父子世界变换和 extras。它检查家具原点/AABB、交互点与 FACING/LOOK、正前方、SDK 平移、sidecar 对应关系、五个独立隐藏组、地板隔离、真实透明屋顶、无外景几何和导航连通性。10 个主要家具的世界 AABB 各向扩张 0.22 米，全部 12 条静态路径和所有可行走目标均接受障碍检查；地毯是可走面。

当前几何校验状态：**PASS**。一致性容差为 `0.00002m`，仅表示元数据与导出几何的一致程度。Khronos glTF Validator：0 个错误、0 个警告；详见 `gltf_validation.json`。

```bash
python tools/blender/dream2_metadata.py
python tools/blender/validate_dream_bedroom_2.py
# 重导 GLB 后刷新实际 AABB（通常直接重跑 metadata 即可）
python tools/blender/validate_dream_bedroom_2.py --update-bounds
```

这是静态几何/图检查，不是运行时碰撞系统；未覆盖任意起点接入图的首段、角色摆臂、不同体型、额外道具、动态避障或动作接触。半径大于 0.22 米时应重新验证。矩形 bounds 本身不能阻止穿家具。未做 Android/Filament 真机渲染或特定 VRM 的坐姿/躺姿拟合验证。

GLB 使用 metallic-roughness PBR，无骨骼、无动画、无外部文件引用。Blender 相机、灯光与世界环境仅供预览，不导出；发光网格不会自动替代宿主灯光。移动端实际亮度、玻璃排序和性能需要项目已有天空盒/IBL、光源与目标设备评估。未附 LOD。

木纹、蓝白织物与潮汐地毯贴图由本仓库脚本程序生成，源图位于 `textures/`；运行 GLB 不需要另行加载该目录。重建入口为 `tools/blender/build_dream_bedroom_2.py`，依次使用装饰和导出脚本；`render_dream_bedroom_2.py` 生成三张预览，结束后恢复全部墙面与玻璃屋顶可见。`dream2_metadata.py` 从最终 GLB 生成本包 JSON、图示及说明，`validate_dream_bedroom_2.py` 可在无 Blender 环境复验。

## 重建与预览

在仓库根目录执行，`blender` 可替换为本机 Blender 可执行文件路径。制作脚本用于独立进程，会清理该进程中的场景，原卧室资产文件不会被写入。

```bash
blender --background --factory-startup --python tools/blender/build_dream_bedroom_2.py
python tools/blender/dream2_metadata.py
python tools/blender/validate_dream_bedroom_2.py
blender --background assets/dream_bedroom_2/dream_bedroom_2.blend --python tools/blender/render_dream_bedroom_2.py
python tools/blender/dream2_metadata.py --docs-only
```

`dream2_optimize.py` 在家具、玻璃及墙体语义组内合并网格，保留交互空节点。完整屋顶和四墙默认显示。`SHA256SUMS.txt` 用于核对交付文件完整性。

Khronos 验证脚本为 `tools/blender/validate_dream_bedroom_2_gltf.cjs`，需安装官方 `gltf-validator` npm 包，可通过 `GLTF_VALIDATOR_MODULE` 指向仓库外的包路径；不要求改动项目 npm 依赖。
