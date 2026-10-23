# 辩论大厅

Blender 原生建模，无外部素材或贴图依赖。正反方各四席、主持一席，每席包含无扶手软包椅、独立桌、桌麦、纸张和席位编号。大厅包含舞台、台阶、背景墙、侧墙吸音板及灯带。前方和顶部开放，方便自动镜头取景。

## 文件

- 运行时 GLB：`app/src/main/assets/scene/debate_hall.glb`
- Blender 源文件：本目录 `debate_hall.blend`
- 席位世界坐标及朝向：本目录 `seat_anchors.json`
- 可用布局：`app/src/main/assets/debate/layout_debate_hall_v1.json`
- 重建脚本：`tools/blender/build_debate_hall.py`（在 Blender Python 中执行；ROOT 指向仓库目录）

短赛程、全赛程的内建配置均已引用新布局。椅子已经包含在大厅内，因此布局的 `chair.enabled` 保持 `false`，避免重复生成。角色坐标与旧布局一致，无需修改相机代码。

## 定位节点

每个席位 ID 为 `HOST`、`PRO1`–`PRO4`、`CON1`–`CON4`。节点真实保留在 GLB 中，不是仅 Blender 可见的辅助线；自定义属性导出为 glTF `extras`。

| 节点名 | 含义 |
| --- | --- |
| `CHAIR_PRO1` | 整套桌椅及定位点的父节点 |
| `AVATAR_ROOT_PRO1` | 当前 demo 的角色根节点世界位置，Y=0 |
| `SEAT_PRO1` | 座面中心，世界 Y=-0.48 |
| `FLOOR_PRO1` | 椅子下方地面投影，世界 Y=-1 |
| `FACING_PRO1` | 座面正前方 0.6 米的朝向目标 |

其他席位替换后缀即可。节点是空节点，没有可见网格；地面和桌前另有可见席位编号。读取子节点时使用其**世界矩阵**，不能直接使用局部 translation。

单位为米。GLB/运行时为右手系、+Y 向上，人物面朝 +Z，朝向角绕 +Y；正方 +12°，反方 -12°，主持 0°。Blender 中对应 `(x, -z, y)`；导出时已转换，运行时不要再次旋转 90°。舞台地面 Y=-1，座高 0.52 米、桌高 0.84 米。

例如正方一辩：角色根位置 `(-0.7, 0, -1.7)`、座面中心 `(-0.7, -0.48, -1.7)`、yaw=12°。传入现有 `AvatarSpawn` 的值为 `(-0.7, 0, 2.3, 12)`，因为 SDK 的 z 参数是相对 `(0,0,-4)` 的偏移。

## 人物坐准椅子

根节点坐标与座面中心是两个不同基准。先按 `AVATAR_ROOT_*` 和朝向安置角色，再播放实际坐姿动画，计算髋骨在世界坐标下的位置。用座面节点作为目标，按角色髋部厚度补偿后，将位置差加到角色根变换上：

```
targetHip = seatWorldPosition + worldUp * hipContactOffset
rootTranslation += targetHip - currentHipWorldPosition
```

`hipContactOffset` 是模型髋骨到臀部表面的距离，需要逐模型校准；同时观察脚掌是否落地。若不同身材模型仍有脚掌悬空，应配合脚部 IK 或调整模型/椅子尺寸。站起、坐下时需要平滑切换坐姿补偿，避免跳变。

当前 P0 只读取布局的角色根坐标，没有实现读取 GLB 席位节点、髋骨吸附或 `seatHeightOverride` 应用逻辑。本次提供可精确读取的定位基准，未声称不同角色和动画已完成真机贴合验证。

## 渲染

Blender 文件包含预览相机与棚灯；GLB 不导出相机/灯光，运行时使用 SDK 的现有环境照明。PBR 材质和自发光灯带已导出，预览中的反射、阴影和亮度会随运行时照明变化。

从主界面抽屉进入辩论赛会直接读取 APK assets 的默认赛程；页面内两个载入按钮也直接读 assets。外部 Intent 的 `load` 仍默认优先读取 `filesDir/debate`，支持自定义调试配置；加 `--es config_source assets` 可强制使用 APK 内建配置，布局也随同从 assets 读取。
