# VFX 画质升级：GitHub 移植候选与 Filament 接入方案

> 调研日期：2026-10-17。源码基线：`988581d`，Filament Android `1.68.3`。
> 本文是 P0/P1 完成后的重新选型，补充并修正 [原可行性文档](vfx-feasibility.md) 的画质假设。已阅读实现、核对上游源码，并在连接的真机上复现现有 fire/snow；候选项目尚未移植或测得性能。
>
> **2026-10-18 落地实录**：§8 起三线样片（舞台灯/火焰/雪风）+ 渲染层升级已实现并通过真机验收——实施范围见 §8；Effekseer 桥（§3.1/阶段 C）与体积火焰（§3.3 B 对照组）**未实施**，理由与退路见 §8 末尾。

## 1. 建议结论

保留 Filament、现有特效生命周期和骨骼锚定。将目标拆成三条能力线：

1. **舞台灯光**：参考 `pmndrs/drei` 的 SpotLight，把可见光束与真实聚光灯配对；补齐 MToon 对局部灯光的响应。
2. **复杂特效制作**：将 `effekseer/Effekseer` 提升为首选验证对象，参考 `EffekseerForGodot4` 制作 Filament 渲染适配器。价值是编辑器、复合发射器、模型/拖尾/环等完整制作流程。
3. **天气与自然现象**：保留轻量 Kotlin 模拟，按需移植 `three.quarks` 的三维旋转、拖尾和行为。火焰采用高质量烘焙资产与局部体积渲染两种质量档，微风必须联动头发、衣物或场景物体。

**建议首先交付三个可绕视角检验的样片：扫动灯束照到角色、带烟与照明的火焰、雪与头发受同一阵风影响。** 再决定扩展编辑器兼容范围。原 P2 的 AI 工具接入与这些视觉能力无关，应排在视觉验收之后。

这是基于当前架构的工程判断；开源项目的功能列表不能直接视为本 SDK 已具备的能力，也不能据此承诺端侧达到桌面 3A 的全部效果。

## 2. 当前“像 2D”的具体原因

| 现状与证据 | 对观感的影响 |
|---|---|
| `vfx/sim/ParticleSystem.kt:159` 起的顶点输出统一生成 Billboard；`VfxBatchRenderer` 每粒子四顶点，只有位置、UV、颜色 | 位置在三维空间，但没有网格粒子、空间翻滚、曲面或真正的拖尾几何 |
| `VfxMaterialFactory.kt:55` 使用 `UNLIT`，fragment 只是纹理乘顶点色 | 雪、花瓣、烟不随舞台照明变化；火焰发光本身也不等于照亮周围物体 |
| `tools/vfx-samples/gen_textures.py:159` 的火图总计 256×256，4×4 格，每帧 64×64；火苗由椭圆堆叠后模糊 | 这是功能验证资产，缺少自然燃烧的细碎火舌、涡卷和连续演化 |
| `ParticleSystem.kt` 只选一帧 UV，没有相邻帧混合 | 序列帧跳变；提高粒子数量无法补回资产信息 |
| `VfxManager` 按混合方式与纹理分批；粒子没有视深排序；`ParticlePool.killAt` 使用 swap-with-last | 透明混合可能乱序。原文“出生序近似排序”不代表当前实现能持续维持出生顺序 |
| 目前无场景深度采样、软交界、场景碰撞或共享风场 | 雪与地面、烟与身体、风与角色之间缺少联系 |
| `stage/internal/Stage.kt:592` 与 `MToonMaterialFactory.kt:308`：MToon 使用手动传入的一盏主光参数 | 新增 Filament SPOT/POINT 灯不会自动在 MToon 角色上产生局部照明，舞台实现必须单独处理 |

现有特效已经进入 Filament 的三维 Scene，不能说是二维 UI 覆盖层。`depthWrite(false)` 也不代表关闭深度测试：已有的不透明物体遮挡，与尚未实现的透明排序/软交界是不同问题。

Billboard 本身不是低画质的同义词。游戏级烟雾也可用带方向光照信息的贴片，例如 Unity 官方介绍的 [six-way smoke lighting](https://discussions.unity.com/t/new-addition-to-vfx-graph-samples-smoke-portal/934103)。真正缺失的是资产、渲染模式、材质与环境互动的组合，GPU 模拟或“全部换成立体小模型”都不能单独解决。

另有一个天气升级前应修正的问题：`sim/Behavior.kt` 的 `Turbulence.apply` 每帧给速度累加扰动却未乘 `dt`，而调用方按实际帧时间更新。扰动力会随帧率改变；修正时需要重新标定已有预设，不能只替换公式后沿用旧强度。

## 3. GitHub 候选，按本项目价值排序

| 项目 | 可借用的能力 | 本项目采用方式 | 成本与判断 |
|---|---|---|---|
| [Effekseer](https://github.com/effekseer/Effekseer)；MIT | 可视化编排、多层粒子、Sprite/Ribbon/Ring/Track/Model 渲染接口 | C++ 模拟与资源加载 + 自建 Filament 后端 | 高；复杂游戏特效的长期首选验证对象，不能直接接现成 GL renderer 就算完成 |
| [EffekseerForGodot4](https://github.com/effekseer/EffekseerForGodot4)；MIT | 将 Effekseer 几何、材质和模型提交给宿主引擎；有 Android 构建说明 | 学习后端结构和能力映射，不引入 Godot | 最有价值的适配先例，详见下一节 |
| [pmndrs/drei](https://github.com/pmndrs/drei)；MIT | 聚光灯的可见锥体、视角/距离渐隐、可选深度交界 | 只移植 SpotLight 几何与 GLSL 数学，重写为 filamat 材质 | 低至中；优先做舞台样片。属于体积感近似，不是完整雾中光散射 |
| [mattatz/THREE.Fire](https://github.com/mattatz/THREE.Fire)；MIT | 在三维包围盒内沿视线采样的程序化火焰 | 移植 `FireShader.js` 到独立体积材质 | 中；真正体积火焰的 PoC，属于较早的演示代码，不是现成写实资产包 |
| [Alchemist0823/three.quarks](https://github.com/Alchemist0823/three.quarks)；MIT | Mesh、Trail、三维旋转、子发射器、行为和曲线 | 扩展现有 Kotlin 路线，渲染部分重写为 Filament | 中；天气与轻量特效适合继续复用，不能只移植 shape/behavior 就期待完整画质 |
| [godotengine/godot](https://github.com/godotengine/godot)；MIT | 粒子碰撞、吸引场、向量场等设计 | 提取算法；参考官方 [3D particles demo](https://github.com/godotengine/godot-demo-projects/tree/master/3d/particles) | 整体移植成本高；适合作为雪、风、碰撞设计参考 |
| [turanszkij/WickedEngine](https://github.com/turanszkij/WickedEngine)；MIT | GPU 粒子、soft particles、深度碰撞、体积光 | 用作高画质效果与渲染流程参考 | 很高；其 GPU 管线不能直接嵌进本项目的 Android Filament，当前不选作运行时依赖 |

上表许可证指仓库代码层面；具体纹理、模型和示例特效应逐项记录来源与授权，不能把引擎代码许可证自动套到所有素材上。

### 3.1 Effekseer：改为重点验证，但不要许诺“即插即用”

上游 [Manager 接口](https://github.com/effekseer/Effekseer/blob/170/Dev/Cpp/Effekseer/Effekseer/Effekseer.Manager.h) 提供五类渲染器的 setter。已核对 Godot 适配源码中的 `CreateSpriteRenderer`、`CreateRibbonRenderer`、`CreateRingRenderer`、`CreateModelRenderer`、`CreateTrackRenderer`，以及 `DrawSprites`：它获取顶点数据后交给 Godot mesh/material，而不是要求宿主直接使用 Effekseer 的 OpenGL 上下文。[源码](https://github.com/effekseer/EffekseerForGodot4/blob/8706d2917c2487efac3a4943c16a10dfcfc5b127/Dev/Cpp/src/RendererGodot/EffekseerGodot.Renderer.cpp)

这证明“宿主引擎负责最终渲染”的路线有先例，**不证明 Filament 后端已存在或适配简单**。建议新建如下桥接：

```text
Effekseer 编辑器 → 特效文件 + 纹理 + 模型
                         ↓
                C++ Runtime / Manager
                         ↓
        RendererCommon 几何生成 + 材质状态转换
                         ↓
          批级 JNI / DirectByteBuffer 数据交接
                         ↓
       Filament VB/IB + MaterialInstance + Renderable
                         ↓
          与角色、场景共享 Scene / Camera / View
```

首轮只支持明确的子集：普通 Sprite、Ribbon/Track、一个 Mesh 效果、基础透明与加色、颜色曲线与贴图动画。模型加载、索引布局、法线/切线、混合方式与绘制顺序都必须显式适配。复杂材质节点、场景扭曲、GPU particles、soft particles 分别设能力门槛；不支持的资产要报出具体原因。

现有批渲染器可复用上传与生命周期经验，但当前固定 Quad 布局不能无修改承担所有 Effekseer 几何。JNI 应按批传输，不能逐粒子跨语言调用；上游缓冲的有效期与 Filament 异步消费需要明确所有权或多缓冲策略。

避免初版采用第二个透明 Surface/WebView 覆盖角色：颜色混合不等于共享场景深度。直接调用 Effekseer GL/Vulkan 后端也涉及 Filament 的上下文、驱动线程、render pass 和资源所有权，不能因为两者支持同一图形 API 就认为能共用。

原文将“新增 C++ 构建链”视为否决条件过重：本仓库已有 `corelib/src/main/cpp/CMakeLists.txt` 和 Gradle `externalNativeBuild`，但目前只是很小的原生工程；新的 Runtime、JNI 和渲染适配仍是实质工作。纯 Kotlin 测试适用于模拟与控制层，不应成为拒绝成熟特效制作流程的理由。

落地时固定编辑器、Runtime、导出格式为兼容版本并锁定 commit/tag；上面的 `170` 接口证据和 Godot 当前源码不是已经验证可混合构建的版本组合。Effekseer 主仓库也明确提醒不要将开发分支直接用于生产。

### 3.2 Drei：舞台灯最直接的移植切口

具体文件：[SpotLight.tsx](https://github.com/pmndrs/drei/blob/master/src/core/SpotLight.tsx)、[SpotLightMaterial.tsx](https://github.com/pmndrs/drei/blob/master/src/materials/SpotLightMaterial.tsx)。代码有距离衰减、根据视角衰减锥面、可选的深度差渐隐；需要替换 Three.js 的 shader chunks 和输出色彩处理，不能整段原样塞进 filamat。

建议每个灯具包含两个同步组件：

- 一个 Filament `SPOT`，负责照亮地板和 PBR 场景；控制位置、方向、颜色、锥角与强度。
- 一个共享同样位姿与锥角的锥体 mesh，负责可见光束；增加噪声缓慢流动、近端/远端渐隐与相机贴近时的淡出。

MToon 另加少量局部灯近似，或独立的舞台材质变体，计算局部光方向、距离与锥角衰减。必须先在真实 VRM 上验证颜色与明暗移动，避免只有地板亮、人物完全不响应。局部阴影若需要，必须另评估采样与成本，不能声称与 PBR 灯完全等价。

第一版可用场景几何深度测试与已知地面边界实现有限效果；Drei 的深度渐隐部分需要宿主提供深度纹理。完整效果还要处理灯光被墙遮住后，墙后光束不应继续可见的问题；仅有锥体 mesh 的深度测试不等于光路遮挡或体积阴影。

### 3.3 THREE.Fire：回答“有没有真正的三维火焰”

有。该项目在盒体内进行视线步进采样，用三维噪声驱动火焰分布；不是单张朝向相机的火苗。[FireShader.js](https://github.com/mattatz/THREE.Fire/blob/master/FireShader.js) 的默认设置是 20 次步进、3 层噪声，这只是上游参数，不是本机可用的性能预算。

移植点是火焰密度、噪声和积分数学；由 Filament 的盒体 mesh 与材质承载，并传入相机位置、世界到局部变换、时间等参数。复用代码时保留其引用的噪声实现署名。

验收难点包括相机进入体积、非均匀缩放、体积与角色相交、背景深度裁剪、透明排序及近景屏幕占比。对 raymarch 火焰而言，只测试包围盒表面深度不能正确处理盒内遮挡，需要将积分终点限制到场景表面，或在受限场景明确规避相交。画质/性能需本机比较后决定是否保留此质量档。

另一个可比较的实现是 [yomotsu/VolumetricFire](https://github.com/yomotsu/VolumetricFire)：它公开了 slice spacing 控制的体积火焰路线，适合与 raymarch 做实验比较；本轮未完成其全部源码与资源许可审计，不列为已批准导入项。

“体积”也不自动等于“真实”。真实火焰通常还需要连续火舌、火星、烟、局部闪烁灯光与合适曝光。面向 K20 Pro，默认质量档更适合先验证**高质量烘焙序列 + 多层空间分布 + 烟/火星 + 一盏受控局部灯**，近景才实验体积版。

### 3.4 Quarks、Godot：把雪与微风做成环境

Quarks 本轮核对版本为 `de7c207f791c8b2260c43334a282976221688b1b`。优先研究这些文件，而不止原计划的发射形状：

- [SpriteBatch.ts](https://github.com/Alchemist0823/three.quarks/blob/de7c207f791c8b2260c43334a282976221688b1b/packages/three.quarks/src/SpriteBatch.ts) 与 [TrailBatch.ts](https://github.com/Alchemist0823/three.quarks/blob/de7c207f791c8b2260c43334a282976221688b1b/packages/three.quarks/src/TrailBatch.ts)：批绘制与拖尾结构。
- [Rotation3DOverLife.ts](https://github.com/Alchemist0823/three.quarks/blob/de7c207f791c8b2260c43334a282976221688b1b/packages/quarks.core/src/behaviors/Rotation3DOverLife.ts)：空间旋转。
- [Noise.ts](https://github.com/Alchemist0823/three.quarks/blob/de7c207f791c8b2260c43334a282976221688b1b/packages/quarks.core/src/behaviors/Noise.ts) 与 `EmitSubParticleSystem.ts`：扰动与子发射器参考。移植前仍要逐项核对语义和单测，不能默认上游行为就是需要的 curl noise。

Godot 的 [particles.glsl](https://github.com/godotengine/godot/blob/master/servers/rendering/renderer_rd/shaders/particles.glsl) 有向量场、球/盒/SDF/高度场碰撞结构，可作为算法参考；整段 compute shader 依赖 Godot 的 RenderingDevice、缓冲布局与生成管线，不可直接作为 Filament 材质运行。

本项目的实现建议：

- 雪按近、中、远分布；远处保留便宜 Billboard，近处少量使用能空间翻滚的薄片或小网格，按距离控制尺寸与密度。
- 对地面先做平面/高度场碰撞与落地淡出；积雪是另一套表面材质或沉积状态，不等于粒子碰撞完成。
- 微风用统一 `WindField.sample(worldPosition, time)` 驱动雪、花瓣、烟与角色弹簧骨骼；头发与已有衣物骨链响应按模型参数限幅。没有衣物骨链的模型不能因此自动获得布料模拟。
- `WindField` 组合主风向、阵风和低频扰动；若用 curl noise，需从向量势求旋度，不是给三个速度轴分别加任意噪声。
- [stegu/webgl-noise](https://github.com/stegu/webgl-noise) 可提供噪声函数参考，按实际 CPU/GPU 预算选择采样、低频更新或预计算。不要把微风主要表现成几条发光曲线。

如果 Effekseer 适配通过，不必再完整重写一遍 Quarks 的所有高级能力：Quarks 只补轻量天气与必要的通用数学，复杂复合特效交给编辑器路线。

## 4. 资产制作与不能直接移植的项目

当前程序化图集应保留为测试/降级素材，正式火焰需要新的制作输入。建议用 [Blender](https://github.com/blender/blender) 离线模拟与渲染火/烟，输出带透明度的连续序列、发光信息，按需要输出方向光照数据；SDK 只消费产物，不引入 Blender 运行时。Blender 的气体域支持火与烟，见 [官方说明](https://docs.blender.org/manual/id/3.6/physics/fluid/type/domain/settings.html)。

图集应检查帧间连续性、颜色空间、预乘约定、格子边缘留白与 mipmap 串格；加上相邻帧插值。先做一套火焰资产的参考画面，避免在低质量贴图上继续堆粒子与 Bloom。

[Unity VisualEffectGraph-Samples](https://github.com/Unity-Technologies/VisualEffectGraph-Samples) 适合观看效果和学习制作方法，但其 [LICENSE.md](https://github.com/Unity-Technologies/VisualEffectGraph-Samples/blob/master/LICENSE.md) 标注 Unity Companion License、面向 Unity-dependent projects，因此不把其中实现或素材默认列入本 Android SDK 的可直接移植清单。[SixWaySmokeTest](https://github.com/keijiro/SixWaySmokeTest) 本轮看到的仓库根目录也未提供明确许可证，不据此推定可自由复制。

同样，不把一段没有明确授权的 Shadertoy shader 当成素材来源。研发优先用本表中已核对的代码许可证，并对最终实际选用的素材单独建清单。

## 5. 与现有代码的落点

下列是建议的新增/重构职责，尚未创建运行时代码：

| 落点 | 责任 |
|---|---|
| 保留 `VfxManager`/`VfxHost`、公开 play/stop/handle 与骨锚 | 复用生命周期、业务命令、单人/团播入口 |
| 拆分 `ParticleSystem.update` 的模拟与几何输出 | 同一模拟结果能走 Billboard、Mesh 或 Ribbon renderer，而不是强制写 Quad |
| 新增 `StageLightRig`/`BeamRenderer` | 灯与可见锥体共用变换，材质参数与曝光可调 |
| 扩展 MToon 的舞台灯参数或材质变体 | 至少证明点/聚光灯能在角色表面移动并产生衰减 |
| 新增 `EffekseerRuntime` + native 渲染桥 | 资源加载、生命周期、特效参数、成批几何与渲染状态交接 |
| 新增 `VolumeFireRenderer` | 独立的局部体积材质与性能档位，不硬塞进 Quad 批 |
| 新增 `WindField`、简单碰撞采样 | 环境统一驱动力；骨骼层接入前阅读 springbone 调试文档 |
| 扩展材质与资源能力描述 | 普通透明、加色、lit mesh、flipbook 插值、受光烟雾分别管理；未支持的资产明确拒绝 |

深度是共同前置问题：普通不透明遮挡可继续用同一 View 的深度测试；**soft particles、热扰动与 raymarch 相交处理**需要分别验证场景 depth/color 如何提供给材质。当前实现没有这些输入。可探索额外 View/RenderTarget 或更深的 native 接入，但必须避免读取当前正在写入的 attachment，并计入额外渲染和带宽成本。本文没有确认 Filament 1.68.3 的现成零成本接口。

## 6. 真机复核记录与新的验收方式

本轮设备：`Redmi K20 Pro / raphael`，Android 11，`Adreno 640`，设备报告 OpenGL ES 3.2。应用安装版本为 `1.2 / versionCode=3`；本轮未重装 APK，未验证安装包与源码 HEAD 的逐字一致性。

在已有 `10.vrm + home.glb` 场景、默认相机下依次运行：

```bash
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd state
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd fx --es ai_arg "'play fire'"
# 稳定播放后查询 state，并截图
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd state
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd fx --es ai_arg "'stop all'"
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd fx --es ai_arg "'play snow'"
# 稳定播放后再查询 state
adb logcat -d -s AIDebug:I '*:S'
```

- 火焰命令成功，锚点 `world(0.0,-1.0,-3.9)`；22:45:04 的 `state` 为 `fps=60`。截图中脚边为明亮重叠光斑，连续火舌与烟层次不足。
- 雪景命令成功，同一锚点；22:47:01 的 `state` 为 `fps=60`。画面主要是大小不同的柔边白点。
- 原始截图在本次本机临时目录 `/tmp/vfx-before-fire.png`、`/tmp/vfx-before-snow.png`；未提交为长期资产。取证后已停止本轮播放的特效。
- 以上仅是现有安装包、固定视角的视觉和 FPS 抽样。不是新方案 benchmark，不代表 60fps 下尚余多少 GPU 时间，也没有完成热稳定、绕拍或团播压力测试。

新的分阶段验收建议：

| 阶段 | 样片与通过条件 | 失败后的决策 |
|---|---|---|
| A：舞台灯 | 少量彩色灯束扫动；地板和 MToon 人物均有响应；转动相机不露平面贴纸边缘；检查墙前/墙后与灯束相交 | 先解决角色受光和遮挡，不继续增加灯数 |
| B：火焰 A/B | 同一场景、曝光、大小，对比新烘焙复合火焰与 THREE.Fire 体积版；正面、侧面、绕拍、贴近均录屏；要求火舌连续且烟、火星、局部光协调 | 体积版超预算则只保留受限近景档；烘焙版仍需达到视觉目标 |
| C：Effekseer 桥 | 一个含 Sprite + Ribbon/Track + Mesh 的编辑器资产在同一 Filament Scene 正确遮挡；播放/停止、骨锚、切角色/场景正常 | 不可接受的格式/材质差异应明确列出，决定扩大后端或保留 Kotlin 路线 |
| D：雪与风 | 雪有前后景、落地消失；近景薄片翻滚；阵风同时影响雪、花瓣、头发；不同帧率运动规律基本一致 | 优先修正时间积分、风场和尺度，避免靠增大粒子数掩盖问题 |

所有阶段都在同样分辨率、曝光、角色数量下做有/无特效对照，记录帧时间分布、CPU/GPU 数据（可获得时）、连续运行后的 FPS 与温度、近景透明覆盖率。性能目标可先沿用单人接近 60fps、团播 ≥50fps 的旧门槛，但它们只是目标，不能作为本次已完成验收的数据。

## 7. 对原方案需要明确修正的结论

1. “Billboard 是唯一正确解”应改为按效果选择 Sprite/Mesh/Ribbon/Volume；四顶点只是一种渲染模式。
2. “两三个 unlit 材质覆盖全部场景”只适合基础验证，不能覆盖受光烟、局部照明与空间体积。
3. “纯 Kotlin 可测，所以 Effekseer 成本大于收益”不适合新的复杂特效目标；收益必须包含编辑器和资产生产效率。
4. “法阵、护盾、光柱走同一个平面 decal 通道”需要重新拆分：地面平面、球壳与锥体不是同一种几何。
5. P0/P1 的验收实录仍可保留，但说明的是功能可用与抽样帧率，不是新的游戏级视觉标准已满足。

本轮产出为选型与验证方案；没有实现上述新后端、灯束、体积火焰或新资产，也没有引用旧的 60fps 数据为它们背书。

## 8. 落地实录（2026-10-18 真机验收通过）

### 8.1 实施范围与三线对照

调研的三条能力线按「样片优先」推进，Effekseer 桥与体积火焰明确后置（§8.4）：

| 调研条目 | 落地状态 | 实现要点 |
|---|---|---|
| §3.2 Drei 舞台灯（线 1） | ✅ `VfxStageLightRig` | 每盏灯三件套：真 Filament SPOT/POINT + 可见光束锥 mesh（`vfx_beam` 材质，UV 烘焙径向/长度渐隐，任意视角软光柱）+ 地面光斑 quad；扫摆（整圆/±幅度正弦摆动两种模式）+ 强度闪烁；`stage_lights`（三束彩光）/`spotlight`（追光）两配方 |
| §3.2 MToon 局部灯响应 | ✅ 近似单灯 | MToonMaterialFactory 增 5 个 uniform（pos/dir/color/cone/range），surface shader 增局部光项（锥角 smoothstep + 距离平方衰减）；VfxManager 每帧挑「对角色贡献最大」的一盏经 `VfxHost.vfxPushMToonStageLight` 推送——**多灯全量响应留后续，featured 单灯近似已验证颜色随扫摆移动** |
| §3.3 火焰（线 2，烘焙路线） | ✅ 复合层化 | `fire` = 火苗（64 帧 1024² flipbook）+ 底部辉光 + 火星 + 分形烟 + 闪烁暖光点灯（照真灯照亮地板/PBR，MToon 走推送）；火舌为「形状势场−fBm 噪声」程序化烘焙（`gen_flame_8x8`），64 帧使播放速率 >60fps、免相邻帧混合 |
| §3.4 雪/风（线 3） | ✅ | `WindField`（主风×慢阵风包络×位置扰动，全局风时钟同源采样）+ 雪复合层（远景 300 Billboard + 近景 48 CROSS 雪晶三维翻滚 + GroundCollision 落地消融）+ 花瓣 CROSS 化 + **头发弹簧骨骼风耦合**（`VrmSpringBoneManager.setWindAccel`，风外力与重力同点叠加、限幅防 Verlet 失稳） |
| §2 表格逐项 | 部分闭环 | Turbulence 漏乘 dt 已修（加速度量纲，预设全量重标定）；flipbook 时序平滑用 64 帧密度替代相邻帧混合；粒子视深排序/软交界/场景深度采样**未做**（仍是无深度输入的透明过绘模型） |
| §3.1 Effekseer（线 2，编辑器路线） | ❌ 后置 | 见 §8.4 |
| §3.3 体积火焰（THREE.Fire 移植） | ❌ 后置 | 见 §8.4 |

渲染层结构升级：`VfxSpec` 增 `renderMode(BILLBOARD/CROSS)/wind/layers/lights/tumble`；批渲染键从（混合档×贴图）扩为（混合档×贴图×渲染模式，CROSS=8 顶点/12 索引每粒子）；`VfxManager` 复合层编排 + 全局风时钟 + featured 灯推送；`VfxTextureFactory` 不变。

### 8.2 真机验收实录（raphael/K20 Pro · MIUI 12.5 · debug 包）

| 项 | 结果 |
|---|---|
| 三样片目验 | ①舞台灯：三束彩色光锥扫摆、地板光斑、**MToon 角色腿部/裙摆随 featured 灯变色**（截图在档 `vfx-lights10`）；②火焰：火苗+辉光+火星+烟分层、暖光在腿上渐变（`vfx-fire4`）；③雪：远景柔点铺满取景框、近景雪花晶可见空间翻滚、落地消融（`vfx-final-combo3`） |
| fps（HUD uiautomator 读数，连采 5 次） | baseline 60×5；stage_lights/fire/snow/petals/rain/confetti/spotlight 全 60×5；**三样片同屏（fire+snow+stage_lights）58~60**；回归前科：`ai_cmd state` 的 fps= 读数被 am start 注入干扰（见新坑 F），以 HUD 为准 |
| 组合播放 | fire(70 粒)+snow(118 粒)+stage_lights 同屏，目录合并 `fx list` 显示 10 配方 |
| 骨锚回归 | `fx play lightstick_spark` @bone:rightHand 存活 9.9s+（walk 期间持续解析） |
| ×20 播停 + load_model ×3 + 抽屉往返 ×3 | 修复 rig 泄漏后**零 FATAL/SIGABRT**，PSS 1.02GB（先决条件修复前 SIGABRT 见新坑 D） |
| 单测 | 新增 `VfxUpgradeTest`（WindField 界/确定性、GroundCollision 落地钳制、eulerBasis 正交、CROSS 八顶点、**湍流帧率无关性回归锁**、风场速度趋向），全模块 968+ 条全绿（本轮 VFX 相关 ~40 条） |

### 8.3 新坑清单（本轮全部真机实锤）

1. **filament RenderableManager.Builder 空 AABB panic**：`culling(true)` 不给 `boundingBox` = "AABB can't be empty" PreconditionPanic（builder **不会**从顶点数据算包围盒）。灯架束/光斑已改 culling(false)+大盒（与粒子批同款纪律）。
2. **MaterialInstance.setCullingMode**：剔除档位在 MI 上不在 renderable 上（RenderableManager 无 setCullingMode）；光束 BACK 剔除（背面过绘减半）、光斑 BACK（只画朝上面）。
3. **flipbook 图集 mipmap 串格**：REPEAT 寻址下相邻格火焰在高 mip 互渗，真机目验火焰周围出现矩形亮边；6% 格内留白（缩入重采样）消除——调研 §4 预警项实锤。
4. **VfxManager.stop/stopAll 泄漏灯架**（SIGABRT 级）：升级前 stop 只需清列表；灯架引入后 stop 不销毁 rig → 光束 MI/灯实体跨次播放累积，最终 `destroyMaterial` 时 "N instances still alive" panic。**教训：给生命周期引入新资源时，全部移除路径（stop/stopAll/自停/锚失效/destroy）必须同步收口。**
5. **TransformManager instance 0 = 根**：对无实体灯（realLight=false）的 `lightInstance=0` 调 setTransform 会脏整棵变换层级（疑似 30fps 元凶之一，已加守卫；守卫前每帧 4 次根 setTransform）。
6. **`ai_cmd state` 的 fps= 读数偏差**：am start 注入命令本身在主线程执行，帧预算紧的场景（舞台灯 ~+5ms/帧）会把 0.5s 窗口打半 → state 读 30 而 HUD 同期 60。**帧预算紧的场景以 HUD uiautomator 连续读数为准**；state 快读仅作粗筛。
7. **真灯是前向渲染逐片元成本**：本场景每盏真灯 ≈−10fps@全屏 home.glb（60→3 灯 30）。`StageLightParams.realLight=false` = 纯光束+地面光斑+MToon 推送（角色响应不依赖真灯）；多灯编舞配额：真灯 ≤1~2 盏，其余光束化。

### 8.4 后置项与退路

- **Effekseer 桥（调研阶段 C）**：C++ Runtime + JNI 批交接 + Filament 后端是独立的人日级工程；本轮把「复杂特效的编排表达」先用**复合层（layers）+ 舞台灯组**覆盖了火焰/舞台两类高频需求，编辑器路线的增量收益要在现有复合配方表达不了时再兑付。退路：§3.1 的桥接分层结论仍然有效，首批子集建议（Sprite+Track+基础混合）不变。
- **体积火焰（THREE.Fire 移植）**：烘焙路线（64 帧+辉光+火星+烟+真灯）已达到「火舌连续、层次可分」的样片标准；raymarch 版的场景深度输入（§5 的共同前置问题）未解决，近景相交/背景裁剪需先验证深度通路。退路：`VolumeFireRenderer` 独立材质方案不变，等 soft particles/深度采样一起做。
- **粒子视深排序 / 软交界 / 场景深度采样**：调研 §5 的共同前置问题本轮未动，透明档仍按出生序近似排序；若后续做 soft particles，需先验证 Filament 1.68.3 的场景 depth 输入通路（额外 View/RenderTarget 或 native 接入）。

### 8.5 配方资产清单（本轮后）

`assets/vfx/textures/`：soft_dot / spark / streak / petal / confetti / **flame_8x8（1024² 64帧）** / **snowflake（64²）** / **smoke（128²）**，全部 `tools/vfx-samples/gen_textures.py` 程序化生成（确定性种子，可对拍）；旧 flame_4x4 退役（mipmap 串格 + 分辨率不足）。presets：10 配方（8 个既有升级 + stage_lights/spotlight 新增），sidecar 与 corelib `VfxPresets` 同源。
