# 森林梦境卧室 · Forest Reverie

原生 Blender 建模的 8 × 7 × 3.4 米卧室，室内面积约 56 平方米。绿色墙面、木地板、双面森林全景窗、窗边书桌、双人床、阅读椅、中央编织地毯、电视、游戏机与手柄均包含在场景中。家具及交互点在 GLB 内保留独立命名节点，可通过节点世界矩阵定位。

## 交付文件

| 文件 | 用途 |
| --- | --- |
| `dream_bedroom.glb` | 可独立导入的场景，贴图已内嵌 |
| `dream_bedroom.blend` | Blender 源文件，含预览相机与灯光 |
| `interactions.json` | 15 个家具/空间节点、18 个交互点、朝向、接触语义、导航图和家具包围盒 |
| `dream_bedroom.cutaway.json` | 四面墙与屋顶的分组、实际网格节点名单、平面位置、外法线及包围盒 |
| `dream_bedroom.meta.json` | 当前 AIAvatar-SDK 的场景平移及可走矩形范围 |
| `dream_bedroom.anchors.json` | 当前 SDK `MoveAnchor` 解析器可读的 12 个行走点、11 条双向通道 |
| `floorplan.png` / `floorplan.svg` | 标注家具位置、交互点和朝向的平面图 |
| `interaction_map.png` / `interaction_map.svg` | 交互点、导航连线与朝向说明图 |
| `bedroom_preview.png` / `media_preview.png` | 主视角及电视区的 Blender 渲染预览 |
| `cutaway_preview.png` | 隐藏南墙、东墙及屋顶后的远景示例，地板和独立家具保留 |
| `validation.json` / `validation.txt` | GLB 实际节点、几何包围盒及导航一致性检查 |
| `gltf_validation.json` | Khronos glTF Validator 格式检查报告 |
| `wall_split_validation.json` | 拆墙前后家具、交互点世界变换及分组结构核对 |
| `textures/` | 原始材质贴图；GLB 运行时无需此目录 |

## 分墙显隐

四面墙、天花板及地板现在分别位于以下 GLB 节点。五个可隐藏组的实际网格后代互不重叠，地板不属于任何可隐藏组。

| 分组节点 | 位置 / 内容 | 外法线（GLB） |
| --- | --- | --- |
| `WALL_NORTH` | 北墙、全景窗玻璃/窗框/窗台、窗帘、灯串与月亮装饰；室内边界 Z=-3.5 | `(0,0,-1)` |
| `WALL_SOUTH` | 南墙、入口门框/门槛、壁挂电视、对应顶线；室内边界 Z=3.5 | `(0,0,1)` |
| `WALL_EAST` | 东墙、侧窗玻璃/窗框/窗台、踢脚线；室内边界 X=4 | `(1,0,0)` |
| `WALL_WEST` | 西墙、护墙板、挂画、踢脚线；室内边界 X=-4 | `(-1,0,0)` |
| `CEILING` | 天花板和吊灯；室内顶界 Y=3.4 | `(0,1,0)` |
| `FLOOR` | 地板和底板，保持显示 | 不参与隐藏 |

这些分组都是空变换节点，**隐藏父实体本身不会自动隐藏 Filament 中的子网格**。宿主应递归控制该组所有可渲染后代，或按 `dream_bedroom.cutaway.json.groups[].renderableNodes` 中的实际网格节点名操作。同一信息也位于 `interactions.json.rendering.cutawayGroups`；`alwaysVisibleNodes` 指定保留 `FLOOR`。玻璃、壁挂电视仍保留原语义父节点名及世界坐标，但已成为相应墙组的后代。

例如从东南侧远距离看房间时，隐藏 `WALL_SOUTH`、`WALL_EAST` 和 `CEILING` 的全部网格后代；床、桌椅、地毯、电视柜及游戏机仍显示。`cutaway_preview.png` 展示这一组合。墙上的电视随南墙隐藏，避免露出挡视线的电视背板；需要单独显示电视时可对其网格应用例外规则。

旧的 `CUTAWAY_FRONT_WALL`、`CEILING_OPTIONAL` 节点保留在新组内以方便迁移。完整的按墙控制应使用上述新分组，不应隐藏整个 `ARCHITECTURE`。墙的平面位置和外法线以未应用场景 placement 的 GLB 世界坐标给出；检测前须与相机、角色转换到同一坐标空间。

**本次完成模型拆分和重新导出，所有墙默认仍显示。** GLB 不会自行执行相机遮挡检测，自动剖切需由宿主 SDK 控制；本次没有修改 SDK 相机逻辑。场景文件及其 cutaway 元数据已同步到 `app/src/main/assets/scene/`。家具及 18 个交互点的世界位置、朝向保持不变。

## 坐标和方向

**所有数值单位为米。** GLB 使用右手系，`+Y` 向上，地面 `Y=0`，房间中心为原点。室内范围为 `X=-4..4`、`Z=-3.5..3.5`；北侧森林窗在 `Z=-3.48`，电视墙在正 Z 方向。

`yawDeg=0` 面朝 `+Z`，`90` 面朝 `+X`，`180` 面朝 `-Z`，`-90` 面朝 `-X`。身体前向由以下公式给出：

```text
forward = [sin(yaw), 0, cos(yaw)]
quaternionXYZW = [0, sin(yaw / 2), 0, cos(yaw / 2)]
```

公式中的角度先换为弧度。Blender 坐标 `(x,y,z)` 导出后为 GLB `(x,z,-y)`；GLB 已经完成坐标轴转换，导入后无需再旋转 90°。

SDK 配置对整个场景应用以下平移，保留场景原始米制尺寸：

```text
sdkWorld = gltfPosition + [0, -1, -4]
```

因此 SDK 中地面 `Y=-1`，房间中心为 `(0,-1,-4)`。本包 `meta` 的 yaw 为 0，因此位置转换不改变朝向。`gltfPosition / gltfOrigin / gltfBounds` 都属于未偏移的 GLB 世界坐标；`sdkWorldPosition / sdkWorldOrigin` 及 walking sidecar 的 `x/z` 已加上此平移。**不能给 `sdkWorldPosition` 再加一次 meta 偏移。** 如果导入器已把 meta 应用到场景父节点，直接读取节点最终世界矩阵即可。

## 家具定位

下表是 GLB 中家具父节点的原点和前向，完整实际几何 AABB 在 `interactions.json.objects[].gltfBounds`。父节点原点用于整体摆放和查找，并不一定等于网格中心或座面中心。

| 名称 | GLB 节点 | 原点 `(X,Y,Z)` | yaw° |
| --- | --- | --- | ---: |
| 双人床 | `FURN_BED` | `(-2.35,0,-1.1)` | 0 |
| 床头柜 | `FURN_NIGHTSTAND` | `(-0.8,0,-2.12)` | 0 |
| 弧形书桌 | `FURN_DESK` | `(1,0,-2.62)` | 0 |
| 书桌椅 | `FURN_CHAIR_DESK` | `(1,0,-1.55)` | 180 |
| 中央地毯 | `FURN_RUG` | `(-0.1,0,0.65)` | 0 |
| 阅读椅 | `FURN_CHAIR_READING` | `(2.83,0,0.12)` | -65 |
| 阅读边几 | `FURN_SIDE_TABLE` | `(3.15,0,1.3)` | 0 |
| 北侧玻璃 | `GLASS_PANORAMA_NORTH` | `(0,0.7,-3.48)` | 0 |
| 东侧玻璃 | `GLASS_PANORAMA_EAST` | `(3.98,0.7,-1.18)` | -90 |
| 电视 | `FURN_TV` | `(0.3,1.55,3.36)` | 180 |
| 电视矮柜 | `FURN_MEDIA_CONSOLE` | `(0.3,0,2.99)` | 180 |
| 游戏机 | `FURN_GAME_CONSOLE` | `(1.1,0.575,2.96)` | 180 |
| 游戏手柄 | `PROP_CONTROLLER` | `(0.55,0.6,2.94)` | 180 |
| 书架 | `FURN_BOOKCASE` | `(-3.64,0,1.66)` | 90 |
| 入口 | `PORTAL_ENTRY` | `(-2.75,0,3.5)` | 180 |

玻璃朝向表示朝室内的法线；床的前向指向床尾；椅子的前向是坐下后身体面向的方向。地毯等没有天然正面的对象仍提供稳定的局部坐标方向。

## 虚拟人交互点

节点命名为 `ANCHOR_<ID>`；每个点都配有 `FACING_<ID>`，位于目标前方 0.5 米，便于用两点计算朝向。部分点另有 `LOOK_<ID>`，表示视线目标；视线目标和身体朝向不要求完全重合。节点自定义属性已导出为 glTF `extras`，包括 `anchorId / label / kind / contact / yawDeg / navigable`。

读取节点时应累乘父节点变换得到**世界矩阵**，不要把节点的局部 `translation` 直接视为最终世界坐标。

| ID | 用途 / 接触位置 | GLB `(X,Y,Z)` | yaw° | 可行走 |
| --- | --- | --- | ---: | :---: |
| `SPAWN` | 地毯中央，脚底 | `(-0.1,0.03,0.65)` | 180 | 是 |
| `RUG_WATCH_TV` | 地毯看电视，脚底 | `(0,0.03,1.1)` | 0 | 是 |
| `BED_APPROACH` | 床侧接近点，脚底 | `(-0.65,0.03,-0.65)` | -90 | 是 |
| `BED_SIT` | 床沿，臀部接触面 | `(-1.4,0.69765,-0.8)` | 90 | 否 |
| `BED_LIE` | 床上，仰卧髋背接触面 | `(-2.86,0.712404,-1.08)` | 180 | 否 |
| `BED_HEAD` | 枕区，头部接触参考 | `(-2.86,0.912,-1.98)` | 180 | 否 |
| `DESK_APPROACH` | 书桌椅前，脚底 | `(1,0,-0.73)` | 180 | 是 |
| `DESK_SEAT` | 书桌椅座面，臀部 | `(1,0.551499,-1.565)` | 180 | 否 |
| `READING_APPROACH` | 阅读椅前，脚底 | `(1.65,0,0.65)` | 115 | 是 |
| `READING_SEAT` | 阅读椅座面，臀部 | `(2.802811,0.562,0.132679)` | -65 | 否 |
| `WINDOW_VIEW` | 窗前看森林，脚底 | `(3.35,0,-1.25)` | 90 | 是 |
| `TV_APPROACH` | 电视柜前，脚底 | `(0.25,0,2.28)` | 0 | 是 |
| `GAME_APPROACH` | 游戏机前，脚底 | `(1.12,0,2.28)` | 0 | 是 |
| `GAME_PICKUP` | 手柄抓取，手部 IK 参考 | `(0.55,0.635,2.94)` | 0 | 否 |
| `ENTRY` | 门内入口，脚底 | `(-2.75,0,2.85)` | 180 | 是 |
| `AISLE_SOUTH` | 床尾通道，脚底 | `(-1.8,0,1.65)` | 90 | 是 |
| `AISLE_EAST_LOWER` | 东侧通道转折，脚底 | `(1.8,0,-0.7)` | 180 | 是 |
| `AISLE_EAST_UPPER` | 东侧窗边通道，脚底 | `(1.8,0,-1.2)` | 90 | 是 |

上述坐姿、躺姿和枕区参考点的 Y 值，是在给定 XZ 位置对相关家具的实际网格进行垂直 BVH 射线求交，取最高表面后加 2 毫米获得的。`BED_LIE` 和 `BED_HEAD` 共用左侧枕头中心线 `X=-2.86`；`BED_SIT` 位于床侧、避开床尾较厚的盖毯。坐标在 JSON 中保留到小数点后 6 位。

**交互点不是通用的虚拟人 root 坐标。** 不同 VRM 的模型原点、归一化比例、髋骨位置、体型和动作素材都不同。当前 SDK 将虚拟人的包围盒最长边归一为 2 米，并把包围盒中心放到 `(0,0,-4)`；`AvatarSpawn` 是叠加在归一化根矩阵上的平移偏移。不能仅把上表坐标传给 root，就宣称角色已坐准或躺准。

建议的接触对齐顺序：

1. 行走到相应 `*_APPROACH` 点，并按交互点的 yaw 调整朝向。
2. 播放实际坐下、坐姿、躺下或躺姿动画；采样最终骨骼世界变换。
3. 用模型的骨骼到身体表面的局部偏移计算当前接触点，再按下式修正根平移。
4. 平滑混合修正量，检查脚底、膝盖、扶手、头部及床面的关系，必要时添加脚部/手部 IK。

```text
currentContactWorld = boneWorldMatrix * modelSpecificContactOffset
rootTranslation += targetContactWorld - currentContactWorld
```

坐姿的 `buttock_surface` 是座面接触点；若直接使用 hips 骨骼，目标髋骨还需加上臀部厚度。`BED_LIE` 的 yaw 表示**从脚指向头的身体长轴**，yaw 本身不会把站立姿势变成躺姿。`BED_HEAD` 是辅助校准参考；是否能同时贴合髋背和枕头取决于模型身长、躺姿和头颈 IK。`GAME_PICKUP` 只是手部定位参考，不包含手柄绑定和抓握动画。

地毯主体高约 2.8 厘米，织纹最高约 3.9 厘米。walking sidecar 只控制 XZ，地面与地毯之间的 Y 高度调整需由宿主按接触点或地表采样处理。

## 接入 AIAvatar-SDK

本次仅制作资产及文档，没有修改应用代码或默认场景。

场景显示：把 `dream_bedroom.glb`、`dream_bedroom.meta.json` 放入 `app/src/main/assets/scene/` 后重新打包，主界面场景列表会列出 GLB；`MainActivity` 按同名 `.meta.json` 加载平移。代码接入可直接调用：

```kotlin
controller.loadScene(
    "scene/dream_bedroom.glb",
    ScenePlacement(
        offsetX = 0f, offsetY = -1f, offsetZ = -4f, yawDeg = 0f,
        bounds = WalkBounds(-3.65f, 3.65f, -7.15f, -0.85f),
    ),
)
```

自动行走：当前 `SceneMetaParser.kt` 中的 `loadWalkAnchors` 固定读取 `scene/home.anchors.json`，`MainActivity` 的 `remember` 也没有以当前场景为键重新加载。因此**仅复制新 `.anchors.json` 或切换场景，不会自动启用这套卧室导航**。集成时应按场景选择加载对应表，通过 `parseMoveAnchors` 解析后注入当前会话的 `locomotionAnchors`，并在换场景时更新会话。避免把其他房间的旧锚点用于本场景。

`MoveAnchor` 只读取以下字段，文件中的坐标已经是 SDK 世界坐标：

```json
{
  "anchors": [
    {
      "name": "spawn",
      "x": -0.1,
      "z": -3.35,
      "label": "中央出生/站立点",
      "labelEn": "spawn",
      "links": ["aisle_south", "bed_approach", "desk_approach", "reading_approach", "rug_watch_tv"]
    }
  ]
}
```

到达朝向、Y 高度、坐姿、躺姿、抓取和视线目标属于 `interactions.json` 的完整约定，当前行走解析器不会自动执行。当前 SDK 也没有自动读取本包 GLB 交互节点并进行髋骨吸附的桥接逻辑。

## 导航与验证

`navigation.edges` 共 11 条双向边，连接 12 个站立/接近点。图中仅收录可走点，座面、床面、枕区和手柄点不会成为行走目标。`bounds` 只是房间范围的矩形钳制，不能阻止穿床、穿桌或动态碰撞。

验证脚本直接读取 GLB 的实际 POSITION 顶点、父子关系及累积世界矩阵，不依赖 Blender 的未求值曲线包围盒。它检查 18 个交互点和 15 个家具节点的位置、朝向、FACING/LOOK 节点、SDK 平移、walking 点和双向连通关系；8 个主要家具的实际 AABB 在 XZ 上各向扩张 0.22 米后，11 条路径均无相交。地毯视为可走面。

这是静态路径几何检查，不是运行时碰撞系统：未覆盖任意起点接入图的第一段路径、角色摆臂、不同体型、额外道具、动态避障或动画接触。角色半径大于 0.22 米时应重新验证。静态小摆件、窗边盆栽、吊灯及墙面并未作为完整导航碰撞体生成。

在仓库根目录复验：

```bash
python tools/blender/validate_dream_bedroom.py
```

重导 GLB 后如需用最终网格刷新 JSON 中的家具及障碍包围盒：

```bash
python tools/blender/validate_dream_bedroom.py --update-bounds
```

检查容差为 `0.00002m`，这是 JSON 与导出几何的一致性容差，不代表动作接触或运行时定位达到了这个精度。校验也覆盖五个隐藏组、实际网格名单、地板隔离以及 cutaway sidecar 的一致性。Khronos glTF Validator 的当前结果为 **0 错误、0 警告**；160 条提示为未使用的 UV 等信息项。没有进行 Android/Filament 真机渲染验证或特定 VRM 的坐姿、躺姿贴合验证。

## 渲染与规模

GLB 为 **13,730,080 字节，约 13.09 MiB（13.73 MB）**，包含 **245,128 三角形、284,483 顶点、27 个 mesh、129 个 primitive、28 个材质、6 张内嵌的 512 × 512 贴图**。网格只在各墙、地板或家具组内合并，独立家具父节点和交互空节点保留。场景无骨骼、无动画、无外部资源引用。

采用 glTF metallic-roughness PBR；玻璃是 alpha 混合材质，灯带、灯泡和屏幕包含自发光。材质使用 `KHR_materials_emissive_strength` 和 `KHR_materials_specular` 扩展。Blender 预览的面光源、相机、世界环境未导出，GLB 的发光网格也不会自动替代宿主灯光；运行时需配置 IBL、光源、阴影及透明排序，实际亮度和反射可能与预览不同。

`FOREST_BACKDROP` 可独立控制窗外森林；四墙和屋顶请使用上文分组。隐藏森林后应由项目提供窗外背景。本包未附低模 LOD，移动端性能以实际目标设备测试为准。

重建脚本位于 `tools/blender/build_dream_bedroom.py`，由它按顺序执行家具、装饰与导出脚本；`render_dream_bedroom.py` 渲染完整房间和剖面示例，结束后恢复全墙显示。原始建模、拆墙和导出通过 Blender MCP 执行。
