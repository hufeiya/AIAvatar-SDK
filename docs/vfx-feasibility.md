# 3D 特效库（VFX）可行性调研与落地方案

> 生成时间：2026-10-16。对应代码状态：`main@3741d25`（配饰 P1.5+P2 之后）。**P0 渲染内核已于 2026-10-17 开发完成并通过真机验收（实录见 §六 P0）；P1 预设资产包+锚定发射+团播接入已于 2026-10-17 开发完成并通过真机验收（实录见 §六 P1），P2 未开发**。
> 起点：与 Gemini 的外部方案讨论（three.quarks 移植 + Billboard 批渲染 + 万能材质三层架构），本文结合仓库现状逐条校准——采纳什么、修正什么、否决什么（§二）。
> 回答四个问题：①特效库怎么分层、哪部分能下沉 JVM 单测？②Filament 侧承接件（动态网格/材质/贴图）在本仓库的落点与先例？③怎么接进单人渲染/团播舞台/AI 工具三条业务线？④P0/P1/P2 各做什么、多少工作量、真机怎么验？

---

## 一、结论速览

**可行，且本仓库的先例密度比预想高。** 核心机制一句话：**粒子模拟是纯数学（SoA 扁平数组 + 状态机，零 Filament 依赖，JVM 单测锁死）；Filament 侧只需要一个动态网格批渲染器 + 两三个运行时编译的 unlit 材质**。这两个「承接件」都有现成先例——MToon 已用 filamat 运行时 MaterialBuilder 编译过 7 个材质变体（`MToonMaterialFactory`），渲染循环每帧挂点、PNG 贴图上传、Bloom 全部就绪。唯一的全新领域是**动态 VertexBuffer（全仓库零先例）**，已用 javap 实证 Filament 1.68.3 的关键 API 全部存在（§3.3）。

| 问题 | 结论 |
|---|---|
| 架构 | 三层：纯 Kotlin 模拟层（发射器/行为/积分，~70% 代码量，JVM 可测）→ 零 GC 批渲染桥 → 运行时编译材质层 |
| 移植蓝本 | three.quarks（MIT）的 `shape/`+`behaviors/` 纯数学目录 1:1 转 Kotlin；Billboard 四角数学自研（几十行） |
| 材质编译 | **不用 matc 离线编译**（对 Gemini 方案的修正）：照 MToon 先例走 filamat-android 运行时 MaterialBuilder，`vertexDomain(WORLD)`/`BlendingMode.ADD` 已 javap 实证存在 |
| Billboard | CPU 端逐粒子写 4 顶点；相机基向量从既有 `cameraManipulator.getLookAt(eye,target,up)` 三元组叉积导出，不动相机系统 |
| Effekseer | **否决出本期**：C++ 运行时 + JNI 桥引入成本 ≫ 收益，且与「纯 Kotlin 可单测」的仓库纪律相悖；留作远期美术驱动路线（§六远期） |
| 资产 | `assets/vfx/`：presets.json sidecar 目录（照 accessory.json 模式）+ PNG 贴图（程序化生成先例 icon-gen.py + Kenney CC0 粒子包）；P0 用运行时程序化纹理零资产自证 |
| 单位口径 | 米（真机实证口径：walk-calib 1.22m/s、配饰局部 TRS 直接米）：粒子尺寸 0.02~0.5m、雪域 6×6m |
| 工作量 | P0 渲染内核 2.5~3.5 人日，P1 资产包+锚定+团播 3~4 人日，P2 AI 通道+高级材质 3~4 人日，合计 **9~11 人日** |
| 主要风险 | ADD/TRANSPARENT 混合的 alpha 预乘语义、自有 PNG 的 UV 方向（两个 P0 首验项）；GPU 资源 destroy 顺序（坑 13 前科）；透明粒子 fill-rate（Adreno） |

## 二、对 Gemini 方案的逐条校准

| # | Gemini 方案点 | 裁决 | 依据 |
|---|---|---|---|
| 1 | 移植 three.quarks `shape/`+`behaviors/`（锥/球/盒发射器、重力/湍流/颜色衰减/序列帧） | ✅ 采纳 | 纯三维向量数学、无渲染耦合、MIT 协议。落在 `corelib/vfx/sim/`，遵守坑 21（纯数学类不带 `android.util.Log`，日志放渲染器层） |
| 2 | 否决 `PrimitiveType.POINTS`、必须 Billboard Quad（1 粒子=4 顶点=2 三角） | ✅ 采纳 | 点精灵有硬件像素上限且不支持旋转/拉伸；本项目运镜有 MACRO 微距机位，Billboard 是唯一正确解 |
| 3 | 用 `matc` 离线编译 `.mat` → `.filamat` 放 assets | ⚠️ **改道** | 仓库既有惯例是 **filamat-android 运行时编译**——`MToonMaterialFactory` 已编译 7 个材质变体，注释原话「与引擎同版本保证 .filamat 兼容」。运行时编译省掉整套离线工具链和版本对齐负担；且已 javap 实证 `MaterialBuilder.vertexDomain(WORLD)`、`blending(ADD)`、`shading(UNLIT)`、`require(COLOR/UV0)` 全部可用（§3.3）。材质代码从 `.mat` 文件原样翻译成 builder 链式调用即可 |
| 4 | 从 `camera.getModelMatrix()` 前三列取 Right/Up 构建 Billboard | ✅ 可用，**换主路** | API 已实证存在；但本仓库相机语言是自建 Manipulator 的 `getLookAt(eye, target, up)` 三元组（「ModelViewer 无公开相机 API」历史决策，`SoulLinkRenderer.kt:86-99`），基向量直接叉积导出（§4.7），零新依赖、两条渲染线（单人/团播）通用。`getModelMatrix` 留作备选 |
| 5 | `setGeometryAt` 动态索引数 + DirectByteBuffer 覆写 | ✅ 采纳 | 已实证 1.68.3 存在 `setGeometryAt(inst, prim, type, vb, ib, offset, count)` 与 `setBufferAt` **五参重载（byteOffset+sizeBytes 部分上传）** |
| 6 | Effekseer（C++ 运行时 + .efk 资产 + JNI 桥） | ❌ **否决出本期** | 引入 JNI 桥、C++ 构建链、新资产格式的成本远超收益；quarks 级粒子 + 材质三件套已覆盖 §七配方表全部场景，且全程 Kotlin 可单测。远期若需要「美术在 PC 编辑器里做复杂特效」再评估 |
| 7 | Shadertoy 三件套（UV 流动/噪声溶解/Fresnel 边缘光）贴进材质 | ✅ 收编进 P2 | filamat 运行时编译对 fragment 自定义代码无限制（MToon 移植了整个 three-vrm mtoon.frag）；作为第三个材质 `vfx_decal` 承接地面法阵/护盾（§六 P2） |
| 8 | Gemini 未提、但仓库必须管的四条 | ➕ 补充 | ①GPU 资源 **destroy 顺序**（坑 13：MaterialInstance 仍被 renderable 持有时 destroy 直接 SIGABRT）；②**零 GC 纪律**（弹簧骨骼每子步分配 FloatArray 的技术债教训，特效必须预分配）；③与 **MToon LINEAR 色调映射并存**的观感标定；④fps 验收只认 `ai_cmd state` 的 `fps=` 字段（坑 14，gfxinfo 恒 0） |

## 三、现状盘点（引擎侧逐项证据）

### 3.1 已就绪的承接件

| # | 事实 | 证据 | 对特效库的意义 |
|---|---|---|---|
| 1 | 每帧更新挂点现成 | 单人 `SoulLinkRenderer.choreoCallback`（`internal/SoulLinkRenderer.kt:427-552`，10 步管线表见 project-guide §1.3）；团播 `Stage.choreoCallback`（`stage/internal/Stage.kt:328-425`） | 特效 update 插在 `updateCameraShot` 之后、`render` 之前（§4.4），仿 `updateLocomotion` 的接入方式 |
| 2 | filamat 运行时编译先例 | `internal/MToonMaterialFactory.kt:60-88`（7 变体 init 编译）、`compile()` :106-111、`MaterialBuilder.init()` :164/:183 | VfxMaterialFactory 照抄生命周期；材质与引擎版本天然兼容 |
| 3 | PNG→Texture 上传先例 | `internal/MToonApplier.kt:299-343` `decodeAndUpload`（BitmapFactory → `Texture.Builder` SRGB8_A8 → `copyPixelsToBuffer` → setImage → generateMipmaps → **逐张 `flushAndWait` 排空**） | 抽成 `VfxTextureFactory`；必须保留逐张 flushAndWait（坑 11：像素缓冲下一帧 beginFrame 才消费，同时存活顶爆 Java 堆） |
| 4 | Bloom 已实现 | `applyViewSettings`（`SoulLinkRenderer.kt:840-846`，strength 钳 0–0.5 + MEDIUM；`AvatarRenderSettings.bloomStrength` :98；`hdrColorBuffer=HIGH` :888-890 是 bloom 前提） | 加色特效直接吃辉光，零新增代码；观感标定项见 §八 |
| 5 | 相机基向量可取 | `cameraManipulator.getLookAt(eye, target, up)` 先例：`SoulLinkRenderer.kt:2652-2658`、`Stage.kt:859-864` | Billboard 基向量来源（§4.7）；两条渲染线同一姿势 |
| 6 | 单位=米 | walk-calib 实测 baseSpeed 1.22 m/s；配饰 P0 结论「父链自动传播根缩放，局部 TRS 直接米」 | 粒子尺寸/发射域直接按米配参，无需模型比例换算 |
| 7 | 事件缝与中继模式 | `VrmaStarted/Finished`（`AvatarRuntime.kt:118/:180`）→ `AvatarHandle` SharedFlow 中继（`AvatarHandle.kt:65-79`） | 「动画开播自动触发特效」类联动的现成挂钩（P1 团播用） |
| 8 | 资产包 sidecar 模式 | `assets/accessories/accessory.json`（id/name/file/license/默认位姿）+ `LICENSES.md` + 解析 `AccessoryCatalog.kt:76-133` + resolver 注入缝（`AvatarController.accessorySpecResolver` :392） | `assets/vfx/presets.json` 照抄；app 层解析经 resolver 缝注入，corelib 零 JSON 依赖 |
| 9 | 程序化资产先例 | `tools/icon-gen.py`（cairosvg 生成 PNG）/ `tools/accessory-samples/make-phone-glb.js`（零依赖 Node 生成 GLB） | 贴图用 Python 脚本程序化生成（§五），柔边圆点/星芒/雨丝都不需要美术 |
| 10 | ai_cmd 命令族模式 | `acc` 族：`AiDebugExecutor.kt:180` 委托 `AccessoryTools.kt:55-71` 二级 when；团播 `StageCommandRouter`（`MultiAvatarActivity.kt:779`，acc 分支 :951） | `fx` 命令族同构 |
| 11 | LLM 工具模式 | `AgentToolCatalog.wearAccessory`（`agent/AgentToolCatalog.kt:501-546`，enum 白名单 + 失败归因枚举）→ app `AgentAccessoryTools.kt:25-57` 幂等注册 → `MainActivity.kt:1472` 接线 → `AiChat.accessoryTools` 开关（`AiChat.kt:93`） | `play_effect` 工具五步落点同构（§六 P2） |
| 12 | 双渲染线的解耦缝先例 | `AccessoryHost`（internal 接口，抽象 Stage 与 SoulLinkRenderer 两条路径供 Manager 共用） | `VfxHost` 照抄这个模式（§4.2） |

### 3.2 空白区（新领域 = P0 首验对象）

- **动态几何零先例**：全仓库无 `VertexBuffer`/`IndexBuffer`/`RenderableManager.Builder` 使用（唯一一处 Builder 出现在注释里，`SoulLinkRenderer.kt:1960`）。所有几何都来自 GLB 经 gltfio。→ 粒子批渲染是本仓库第一块程序化网格，API 已实证但**行为语义（部分上传/位置指针/剔除盒）必须 P0 真机首验**，态度对齐配饰 P0 对 `setParent` 的首验。
- **程序化纹理无公开工具**：MToonApplier 的解码上传是 private 且数据源是 GLB BIN chunk。需抽一个从 assets PNG / 内存 Bitmap 建 Texture 的 `VfxTextureFactory`。

### 3.3 Filament 1.68.3 API 实证（javap 于 Gradle 缓存 AAR，2026-10-16）

| API | 签名（实证存在） | 用途 |
|---|---|---|
| `VertexBuffer.setBufferAt` | `(Engine, int, Buffer)` 三参 **+** `(Engine, int, Buffer, int byteOffset, int sizeBytes)` 五参重载 | 每帧部分上传活跃粒子前缀，不用整缓冲重传 |
| `RenderableManager.setGeometryAt` | `(int instance, int primitiveIndex, PrimitiveType, VertexBuffer, IndexBuffer, int offset, int count)` | 动态指定本帧索引数（=活跃粒子数×6），零粒子时画 0 个 |
| `VertexBuffer.Builder` | `attribute(VertexAttribute, int bufferIndex, AttributeType, int offset, int stride)` + `normalized(VertexAttribute)` | 交错顶点布局；`POSITION/UV0/COLOR` 及 `CUSTOM0-7` 枚举在册 |
| `IndexBuffer.Builder` | `indexCount(int)` + `bufferType(IndexType.USHORT)` | 静态索引一次建终身不改 |
| `Camera` | `getModelMatrix(float[])` / `getViewMatrix(float[])` / `getPosition(float[])` | Billboard 基向量的备选来源 |
| `MaterialBuilder`（`com.google.android.filament.filamat`） | `vertexDomain(VertexDomain.WORLD)` ✓、`blending(BlendingMode.ADD/TRANSPARENT/SCREEN/MULTIPLY)` ✓、`shading(Shading.UNLIT)` ✓、`require(VertexAttribute.COLOR/UV0)` ✓、`samplerParameter(SAMPLER_2D, FLOAT, name)` ✓、`uniformParameter(FLOAT, name)` ✓、`depthWrite/culling/flipUV/platform(MOBILE)` ✓ | §4.6 材质层全部就绪；**无需离线 matc** |

> 注意坑 10 前科：**filament MaterialInstance 设置未声明的 uniform 是 SIGABRT**——材质参数表（mainTexture/intensity）唯一事实源收在 VfxMaterialFactory，别在调用侧多设一个名字。

## 四、三层架构设计

### 4.1 分层与单测边界

```
┌────────────────────────────────────────────────────────────────┐
│ 第一层 模拟层（纯 Kotlin，零 Filament/Android 依赖 → JVM 单测）    │
│  ParticlePool(SoA) · Emitter sealed(Point/Sphere/Cone/Box/Ring) │
│  · Behavior sealed(Gravity/Drag/Turbulence/Color/Size/Frame)    │
│  · ParticleSystem.update(dt, camBasis, emitterPose, out顶点数组) │
│  依赖注入：确定性种子 Random → 同 dt 序列结果可断言               │
└──────────────────────────────┬─────────────────────────────────┘
                    │ 每帧输出：活跃粒子顶点字节（写进预分配 FloatArray）
                    ▼
┌────────────────────────────────────────────────────────────────┐
│ 第二层 批渲染桥（internal，Filament——本仓库新领域，P0 首验）       │
│  VfxBatchRenderer：静态 IndexBuffer + 动态 VertexBuffer          │
│  · 部分上传（五参 setBufferAt）+ 动态索引数（setGeometryAt）      │
│  · 零 GC：DirectByteBuffer/FloatArray 全部预分配                 │
│  · 每种混合档一个批（additive 批 + transparent 批），特效共享      │
└──────────────────────────────┬─────────────────────────────────┘
                    │ 单次 DrawCall 渲染整批（全屏特效合计 2 个 draw call）
                    ▼
┌────────────────────────────────────────────────────────────────┐
│ 第三层 材质层（filamat 运行时编译，MToonMaterialFactory 同路）     │
│  vfx_additive（加色：火/星光/魔法/激光）· vfx_transparent（雨雪烟）│
│  · P2 增第三材质 vfx_decal（UV scroll + Fresnel，法阵/护盾）      │
└────────────────────────────────────────────────────────────────┘
```

### 4.2 包与文件清单（`corelib/src/main/java/com/neethu/corelib/vfx/`，仿 accessory 六件套）

| 文件 | 层 | 职责 |
|---|---|---|
| `VfxSpec.kt` | 公开数据 | 预设参数表（材质档/贴图引用/maxParticles/overflow 策略/emitter/behaviors/默认锚点）+ `VfxAnchor` sealed（`World(pos)` / `Bone(avatarId, boneName)`）+ `VfxInfo` 状态快照 |
| `VfxMath.kt` | 纯函数 | 相机基向量（getLookAt→right/up）、Billboard 四角、速度拉伸轴、曲线采样——全静态纯函数，JVM 单测 |
| `sim/ParticlePool.kt` | 纯数学 | SoA 扁平数组（pos/vel/life/maxLife/size/angle/angVel/color/flipbook phase），swap-with-last 回收，预分配零 GC |
| `sim/Emitter.kt` | 纯数学 | sealed：Point/Sphere(Surface)/Cone/Box/Ring——采样出初始 pos+vel（three.quarks `shape/` 移植） |
| `sim/Behavior.kt` | 纯数学 | sealed：GravityForce/DragForce/TurbulenceForce(P1)/ColorOverLife/SizeOverLife/FrameOverLife——`apply(pool, i, dt)` |
| `sim/ParticleSystem.kt` | 纯数学 | 编排器：发射预算累积 → 积分 → 行为 → **直接写出 Billboard 顶点**到调用方给的 FloatArray；无任何引擎类型 |
| `internal/VfxMaterialFactory.kt` | Filament | 2+1 材质运行时编译（§4.6）；照 MToonMaterialFactory 的 init/build 生命周期 |
| `internal/VfxTextureFactory.kt` | Filament | 程序化兜底纹理（径向渐变 Bitmap，P0 零资产）+ assets PNG 加载（MToonApplier.decodeAndUpload 同款纪律：逐张 flushAndWait） |
| `internal/VfxBatchRenderer.kt` | Filament | 每混合档一个批：静态索引 + 动态顶点 + Renderable（§4.5）；销毁顺序守卫 |
| `internal/VfxHost.kt` | 解耦缝 | internal 接口（仿 AccessoryHost）：`engine()/cameraBasis(out)/resolveBone(avatarId,name):entity?/containerEntity()`——SoulLinkRenderer 与 Stage 各一实现 |
| `internal/VfxManager.kt` | 编排 | 多特效生命周期（play/stop/stopAll）、每帧泵（对各 ParticleSystem update → 各批 flush）、Bone 锚定解析、幂等 destroy |
| 公开 API | 门面 | `AvatarController` / `AvatarStage` 增：`playVfx(spec, anchor): VfxHandle` / `stopVfx(id)` / `stopAllVfx()` / `effects: StateFlow<List<VfxInfo>>`；转发到 renderer 的 VfxManager |

### 4.3 数据模型与预设供给

```kotlin
// VfxSpec.kt 草案（全部字段带默认值，预设只写差异项）
data class VfxSpec(
    val id: String,                      // "snow" / "fire_flipbook"
    val label: String = id,
    val blending: VfxBlending = ADDITIVE,          // → 用哪个批/材质
    val texture: VfxTexture = Procedural.SOFT_DOT, // P0 程序化 / P1 assets 路径
    val maxParticles: Int = 256,                   // 单特效上限；批总上限另设
    val overflow: Overflow = REJECT,               // 满了拒绝 | RECYCLE_OLDEST（持续型）
    val emitter: EmitterParams,                    // shape + rate + 初始速度域
    val behaviors: List<BehaviorParams> = emptyList(),
    val stretchByVelocity: Float = 0f,             // >0 = 雨类拉伸
    val flipbook: FlipbookParams? = null,          // 列×行×fps
)
```

供给分两步（对齐配饰的「内置 + resolver 缝」模式）：

- **P0**：corelib 内置一张 **Kotlin 常量预设表**（3 个预设，零 JSON 解析零资产）——SDK 开箱即用的哲学与「内置默认环境光零资产即亮」同源。
- **P1**：`app/src/main/assets/vfx/presets.json` sidecar 目录 + 贴图，**app 层解析**（kotlinx.serialization，`SceneMetaParser` 惯例；org.json 在 JVM 单测 not-mocked 禁用），经 `AvatarController.vfxSpecResolver` 注入缝合并进目录——corelib 保持零 JSON 依赖，`AccessoryCatalog.kt:76`（builtin/all/specFor 三层合并）是现成模板。

### 4.4 每帧时序与挂点

```
SoulLinkRenderer.choreoCallback:  …springBone(:532) → updateCameraShot(:541) → populateSceneEntities(:547)
                                  → 【vfxManager.update(frameTimeNanos)】 → modelViewer.render(:549)
Stage.choreoCallback:             …updateCameraShot(:388) → asyncUpdateLoad(:393) → populateRenderables(:396)
                                  → 【vfxManager.update】 → render(:404)
```

挂点选在 `updateCameraShot` **之后**的两个理由：

1. **同帧相机基向量**：运镜 steering 本帧刚写入 Manipulator，此时 `getLookAt` 拿到的就是本帧 render 将用的位姿，Billboard 无一帧滞后；
2. **骨骼已提交**：`commitLocalTransformTransaction` 在步骤 7（:515）已跑，Bone 锚定读世界变换不会撞 TransformManager 惰性求值的陈旧读数（坑 40 前科）。

线程纪律：全部引擎操作在主线程（§3 既有事实），粒子 sim 也放主线程 doFrame 内——512 粒子 × 力学积分 + 顶点写出的 CPU 预算 **<0.3ms**（纯数组运算），不值得为它引入跨线程同步。弹簧骨骼的「每子步分配 FloatArray(16)」GC 前科不允许在特效层重演：** ParticlePool 与顶点缓冲全部预分配，update/flush 路径零 new**。

### 4.5 批渲染层设计（新领域，逐项给依据）

**顶点格式**（每顶点 9 float = 36 字节，交错）：

| 属性 | 类型 | 偏移 | 说明 |
|---|---|---|---|
| POSITION | FLOAT3 | 0 | **世界坐标**（CPU 端已做 Billboard 展开 + 锚定变换） |
| UV0 | FLOAT2 | 12 | 已按 flipbook 帧重映射的子矩形 UV |
| COLOR | FLOAT4 | 20 | RGBA（ColorOverLife 逐粒子输出） |

v1 刻意用 FLOAT4 颜色而非 Gemini 方案的 UBYTE4：省掉浮点数组与字节缓冲混写的游标算术（纯 sim 层输出更干净），512 粒子 × 4 顶点 × 36B ≈ **74KB/帧上传，可忽略**；将来要省带宽再切 UBYTE4+normalized（`Builder.normalized(COLOR)` 已实证，留作优化不做预告）。

**索引缓冲**：USHORT 静态，`(0,1,2, 2,3,0)` 循环一次建终身不改；512 粒子上限 = 2048 顶点 < 65536 无溢出风险（批上限超 16384 粒子才需 UINT，远超移动端预算）。

**Renderable**：单 primitive TRIANGLES；`culling(false)`（粒子云的世界包围盒逐帧变化，剔除盒难以便宜地保守，直接关——配饰/角色对动态几何关剔除是本仓库既有做法）；`receiveShadows(false)`/`castShadows(false)`（不进阴影管线，规避 PCSS Adreno 悬崖的一切牵连）；挂在 **独立 root entity** 上（不挂 avatar/scene 的变换与生命周期——模型重载、场景切换不影响世界系特效）。

**每帧 flush**（零 GC）：

```kotlin
fun flush(activeParticles: Int) {
    if (activeParticles == 0) { rm.setGeometryAt(inst, 0, TRIANGLES, vb, ib, 0, 0); return }
    val byteCount = activeParticles * 4 * STRIDE_BYTES
    directBuffer.position(0)                       // 复用，不重新分配
    rm.setGeometryAt(inst, 0, TRIANGLES, vb, ib, 0, activeParticles * 6)
    vb.setBufferAt(engine, 0, directBuffer, 0, byteCount)   // 五参部分上传（已实证）
}
```

> P0 首验注：NIO position 指针与五参 byteOffset 的精确语义（filament JNI 读 position+offset 还是裸 offset）以真机首个版本为准记录结论——两个候选写法都在首验清单里，一行改动的事。

**多特效合并**：同混合档的所有特效共用一个批（写段各自独立，写入顺序=特效创建序），全屏任意多特效合计 **2 个 draw call**（P2 加 decal 材质后 3 个）。批容量按最活跃配预算（默认 additive 512 + transparent 512），批满按各特效 maxParticles 独立 REJECT，不互相挤占。

**销毁顺序**（坑 13 + 抽屉跳转二次 onDestroy 双护栏前科，必须写成幂等状态机）：

```
VfxManager.destroy()（幂等，可重入）：
  ① setGeometryAt(inst,0,…,0,0) 清绘制引用
  ② RenderableManager.destroy(entity)          ← 先摘 renderable 对 MaterialInstance 的持有
  ③ destroyVertexBuffer / destroyIndexBuffer
  ④ destroyMaterialInstance ×N → destroyMaterial ×N
  ⑤ destroyTexture ×N                          ← 纹理无 in-use 扫描，顺序保持实例先于纹理（坑 13 尾注）
```

### 4.6 材质层（运行时编译，完整配置）

```kotlin
// VfxMaterialFactory.kt —— 照 MToonMaterialFactory 的 init()/compile() 生命周期（:106-111/:164/:183）
private fun buildAdditive(): MaterialPackage = MaterialBuilder()
    .name("vfx_additive")
    .platform(MaterialBuilder.Platform.MOBILE)            // MToon 同款
    .shading(MaterialBuilder.Shading.UNLIT)               // 不受灯光 rig 影响（三灯全 SPOT 的现状零牵连）
    .vertexDomain(MaterialBuilder.VertexDomain.WORLD)     // 顶点已是世界坐标，免模型矩阵
    .blending(MaterialBuilder.BlendingMode.ADD)           // transparent 档换 TRANSPARENT
    .depthWrite(false)                                    // 透明边缘互切黑框防线
    .culling(MaterialBuilder.CullingMode.NONE)
    .flipUV(false)                                        // MToon 同款（:198）
    .require(MaterialBuilder.VertexAttribute.UV0)
    .require(MaterialBuilder.VertexAttribute.COLOR)
    .samplerParameter(SamplerType.SAMPLER_2D, SamplerFormat.FLOAT, "mainTexture")
    .uniformParameter(UniformType.FLOAT, "intensity")
    .material(SHADER_SRC)                                  // 见下
    .build()!!
```

```glsl
void material(inout MaterialInputs material) {
    prepareMaterial(material);
    vec4 tex = texture(materialParams_mainTexture, getUV0()); // UV 已在 CPU 端做好 flipbook 子矩形
    vec4 c = tex * getColor();                                // 顶点色 = ColorOverLife 输出
    // ADD 档：filament 加色是 ONE/ONE、不参与 alpha 混合——淡出必须在源端预乘 rgb
    material.baseColor = vec4(c.rgb * c.a * materialParams_intensity, 1.0);
}
// TRANSPARENT 档（雨雪烟雾）：filament 的 transparent 默认 premultiplied
// （ONE / ONE_MINUS_SRC_ALPHA），同样预乘：
//   material.baseColor = vec4(c.rgb * c.a * intensity, c.a);
```

> **P0 首验项**：ADD 的 alpha 语义与 TRANSPARENT 的 premultiplied 约定按上述写死，黑白纯背景（`load_scene none`）下各截一张图确认淡出/边缘行为；若观感不符再按常规 NON-premultiplied 调整——结论记录进本文档 §八。

### 4.7 Billboard 数学（VfxMath.kt，全纯函数）

相机基向量从 Manipulator 的 getLookAt 三元组导出（`Stage.kt:859-864` 同款取法）：

```
forward = normalize(target - eye)
right   = normalize(cross(forward, up))      // up 是 getLookAt 的第三参
camUp   = cross(right, forward)

粒子中心 P、半宽 s、（可选）自转角 θ（绕 camUp 旋转 right/camUp 后再合成）：
V0 = P - right·s - camUp·s   （左下）   V1 = P + right·s - camUp·s   （右下）
V2 = P + right·s + camUp·s   （右上）   V3 = P - right·s + camUp·s   （左上）
UV：V0=(0,0) V1=(1,0) V2=(1,1) V3=(0,1)（方向若上下颠倒 = §八 UV 首验项，翻 V 一行修）
```

**速度拉伸（雨）**：把 `camUp·s` 换成 `normalize(velocity)·s·stretch`、`right·s` 换成 `normalize(cross(velocityAxis, viewDir))·s`——雨丝自动沿下落方向拉长，仍是 4 顶点。

### 4.8 锚定发射（P1）

`VfxAnchor` 两态：

- `World(Vec3)`：固定世界点（ai_cmd 给参即此态）；
- `Bone(avatarId, boneName)`：**每帧**经 `VfxHost.resolveBone`（复用配饰的 resolveBone 缝语义）解析骨骼实体世界位置作为发射器原点——发射器跟随、**已存活粒子留在世界系**（Unity world-simulation 语义，走路时手部流光拖尾自然）。解析失败（模型重载/换装后实体失效）→ 特效安静自停，不持陈旧 entity（`destroyModel` 后的悬空 entity 是危险引用）。

## 五、资产包设计

### 5.1 目录树（P1 落地，对齐 accessories/ 模式）

```
app/src/main/assets/vfx/
├── presets.json          # sidecar 目录清单（schema 见 5.2）
├── textures/
│   ├── soft_dot.png      # 柔边圆点（雪/烟/星云底版）
│   ├── spark.png         # 四芒星（星光/烟花）
│   ├── streak.png        # 竖向渐变细条（雨）
│   ├── petal.png         # 花瓣剪影（风吹花瓣）
│   ├── confetti.png      # 纸屑色块（团播开演）
│   └── flame_4x4.png     # 火焰序列帧（flipbook）
└── LICENSES.md
```

生成脚本 `tools/vfx-samples/gen_textures.py`：cairosvg/Pillow 程序化生成上述 6 张（径向渐变、星芒函数、线性渐变都是基础图形学），先例 = `tools/icon-gen.py`；备选渠道 Kenney Particle Pack（**CC0**，60+ 粒子贴图）。许可证纪律同配饰：内置只收 CC0+自制，集中记录 LICENSES.md。

### 5.2 presets.json schema（照 accessory.json 的 sidecar 惯例）

```json
{
  "presets": [
    {
      "id": "snow",
      "name": "漫天飞雪", "nameEn": "Snowfall",
      "texture": "textures/soft_dot.png",
      "blending": "transparent",
      "maxParticles": 400, "overflow": "recycle_oldest",
      "rate": 60, "lifetime": [4, 7], "size": [0.025, 0.05],
      "emitter": { "shape": "box", "center": [0, 2.2, 0], "halfExtents": [1.6, 0.2, 1.6], "direction": [0, -1, 0], "speed": [0.25, 0.5] },
      "behaviors": [
        { "type": "gravity", "value": [0, -0.6, 0] },
        { "type": "sway", "axis": "x", "amplitude": 0.3, "frequency": 0.8 },
        { "type": "color_over_life", "keys": [[0, 1, 1, 1, 0], [0.15, 1, 1, 1, 0.9], [1, 1, 1, 1, 0]] },
        { "type": "size_over_life", "keys": [[0, 0.02], [1, 0.045]] }
      ],
      "license": "CC0 (self-made)"
    }
  ]
}
```

> 曲线 keys 为扁平数组：color_over_life 每键 `[t, r, g, b, a]`、size_over_life
> 每键 `[t, v]`（P1 落地定稿——调研稿的 `[t, [r,g,b,a]]` 异构嵌套无法映射
> `List<List<Float>>`，已扁平化）。

解析在 app 层（kotlinx.serialization），脏条目丢弃 + 字段钳制（配饰 `AccessoryCatalog` 同款纪律），经 `vfxSpecResolver` 缝注入 corelib；corelib 内置 3 个代码常量预设做 SDK 兜底。

## 六、分期计划

### P0 —— 渲染内核打通（预估 2.5~3.5 人日）

**目标**：一条粒子从「CPU 出生 → Billboard 顶点 → 动态网格 → 辉光上屏」的全链真机可见；全部新领域 API 完成首验。

1. `VfxMath` + `sim/`（ParticlePool/Point+SphereEmitter/Gravity/ColorOverLife/SizeOverLife/ParticleSystem）纯 Kotlin，JVM 单测锁死；
2. `VfxMaterialFactory`：additive + transparent 两材质运行时编译（§4.6）；
3. `VfxTextureFactory`：程序化径向渐变兜底纹理（**P0 零资产**）；
4. `VfxBatchRenderer`：静态索引 + 动态顶点 + 部分上传 + 动态索引数 + 幂等 destroy（§4.5）；
5. `VfxHost` 缝 + SoulLinkRenderer 实现 + choreoCallback 挂点（§4.4）；
6. `AvatarController.playVfx/stopVfx/stopAllVfx/effects` 公开 API + 内置 3 常量预设（spark_burst 加色 / snow 透明 / ring_pulse）；
7. ai_cmd `fx` 命令族：`fx list` / `fx play <preset> [x y z]`（位置用 ai_x/ai_y/ai_z extras，walk_to 先例）/ `fx stop <id|all>` / `fx state`；
8. 单测：`ParticlePoolTest`（回收换位/发射预算/溢出策略/dt 钳制）、`ParticleSystemTest`（确定性种子下逐帧积分断言）、`VfxMathTest`（基向量正交性/四角坐标/拉伸轴）。

**真机验收清单**（对齐 ai-debug-intents.md 惯例）：

```bash
adb shell "am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd 'fx play spark_burst'"
ai_cmd state → fps ≥ 55（512 粒子、中景机位；Adreno K20 Pro 口径）
fx stop → 粒子当帧清空；fx play/stop ×20 循环 + load_model 重载 ×5 → 零 FATAL、PSS 稳定（配饰 ×20 先例）
load_scene none 纯色底 → additive/transparent 各一截图：ADD 预乘淡出、TRANSPARENT premultiplied、UV 方向三个首验项记录结论
抽屉跳 SimpleDemo 往返 ×5 → 无二次 onDestroy 崩溃回归（双护栏前科）
MToon 模型（10.vrm）+ PBR 模型各验一遍 → LINEAR/ACES 两种色调映射下特效观感标定
```

#### P0 验收实录（2026-10-17 真机，raphael/K20 Pro · MIUI 12.5 · debug 包）

| 项 | 结果 |
|---|---|
| 三预设目验 | spark_burst 金色放射爆发（亮黄→橙红衰减清晰）/ snow 满屏柔边雪花 / ring_pulse 脚下青白光环径向扩散，双混合档全上屏 |
| fps（ai_cmd state 口径） | additive（ring/spark）60fps；snow 400 粒 TRANSPARENT 收紧域后 60fps（首版 6×6m 域 + 三参全量上传实测 30fps，域收紧至 3.2×3.2m + 五参部分上传后回到 60——过绘是透明档第一预算项，风险 #7 实锤） |
| fx stop | stop 后 0.5s 截图零残留（当帧清空 ✓）；ai_cmd state fps=60 |
| ×20 play/stop + load_model ×5 | 零 FATAL/SIGABRT；PSS 1.28GB→1.10GB（降，无泄漏）；进程全程存活 |
| 抽屉跳 SimpleDemo 往返 | ×5+1 次（末次显式 focus 校验进/出），零崩溃——VFX 批/材质/纹理随 onDestroy 幂等销毁路径与二次 onDestroy 双护栏兼容 |
| 双色调映射 | MToon(10.vrm)+LINEAR 与 PBR(47058…)+ACES 两档下 additive/transparent 观感均正常（纯灰底截图在档） |
| 单测 | VFX 新增 29 条（Pool 8/System 11/Math 10），全模块 1609 条全绿 |

**首验项结论（回填 §八）**：

1. **ADD 预乘**：shader 内 `rgb *= a`、输出 alpha=1 的写法正确——纯灰底目验平滑淡出、无黑框硬边、辉光自然；
2. **TRANSPARENT premultiplied**：`vec4(rgb*a, a)` 正确——白雪高斯边缘干净无暗边；
3. **UV 方向**：柔边圆点径向对称无法判定上下；**非对称贴图 P1 首验**（CPU 端翻 V 一行预案保留）；
4. **setBufferAt 五参语义**：JNI 从 buffer position 起读 sizeBytes 字节；`position(0)+limit(byteCount)` 下三参全量与五参部分真机 A/B 目验等价——按 §4.5 五参部分上传为正路（74KB→活跃前缀）；
5. **新坑 A（filamat uniform 语法）**：shader 里 uniform 不能用 `materialParams_xxx` define 语法——编译静默失败（`isValid=false`、无错误信息，只能真机二分）；**必须用 `materialParams.xxx` 结构体语法**；sampler 才有 define（`materialParams_mainTexture` 合法）。MToonMaterialFactory 早就全用结构体语法，正是这一差异；
6. **新坑 B（世界系锚点）**：角色不在世界原点（unit-cube 后脚底 y=−1、场景放置后 z≈−3.94，`MODEL_CENTER=(0,0,−4)`）——世界系特效缺省锚点必须跟角色根实体世界变换（新增 `avatarWorldPos()`），锚 (0,0,0) = 全部生成在取景框外（本 P0 最大弯路：排查「隐身」半天，实为锚点错位 + 下述坑 C 叠加）；
7. **坑 10 复发实锤**：对未声明 uniform 的材质实例调 `setParameter` = PreconditionPanic SIGABRT（诊断期临时材质漏声明 intensity 当场炸）——「材质参数表唯一事实源收在 Factory」纪律再次验证。

**对 P0 清单的两处小扩项（均在纯数学层，带单测）**：Emitter 补 Box+Ring（snow/ring_pulse 配方必需，§4.1 全集内）；Behavior 补 Drag（spark_burst 配方点名）。预设参数两处真机校准：雪域 6×6→3.2×3.2m、发射高度 4.5→2.2m（默认机位取景框适配，见坑 B 的相机口径）。

### P1 —— 预设资产包 + 锚定发射 + 团播接入（预估 3~4 人日）

1. 发射器补全：Cone/Box/Ring（three.quarks `shape/` 移植）；行为补全：DragForce/Sway/TurbulenceForce（简化 curl noise）/FrameOverLife（flipbook）；速度拉伸（雨）；
2. `assets/vfx/` 资产包：presets.json + 6 张程序化贴图 + LICENSES.md + app 层 `VfxCatalog`（三层合并）+ resolver 缝；内置预设扩到 **8~10 个**（§七配方表）；
3. 锚定发射：`VfxAnchor.Bone` + VfxHost.resolveBone + 模型重载自停（§4.8）；ai_cmd `fx play <preset> anchor:<bone名>`；
4. 团播接入：`VfxHost` 第二实现挂 `Stage.choreoCallback`；节目单 act 新类型 `fx`（`shows/default.json` 扩展，特效插幕不打断舞蹈——对齐 enqueueAct 语义）；`StageCommandRouter` 加 `fx` 分支（ai_cmd 团播路径）；`DanceRequestRouter` 白名单扩展预研（LLM 点播特效走导播链）;
5. 应援棒联动：lightstick 配饰（已有资产）+ 手骨锚定星光的组合预设「应援模式」；
6. 单测：新发射器/行为逐个（确定性断言）+ `VfxCatalogTest`（**对照 assets/vfx 目录树断言路径**——坑 25 教训）+ 节目单 fx act 解析测试。

**真机验收清单**：雨/雪/火/花瓣/彩带五配方逐个 `fx play` 目验；手骨星光锚定后 `walk_to` 走动全程跟随不脱钩、VrmaStarted/Finished 切换期间无残留；团播节目单插 `fx` act 不打断编舞（对照 multi-dance 验收口径）；三特效同屏（雪+彩带+火）`ai_cmd state` fps ≥ 50；fps 预算表记档。

#### P1 验收实录（2026-10-17 真机，raphael/K20 Pro · MIUI 12.5 · debug 包）

| 项 | 结果 |
|---|---|
| 五配方目验（单人路径） | snow 满屏雪花横摆飘落 / rain 细长雨丝速度拉伸下落 / fire 脚底 flipbook 火苗橙黄→暗红加色燃烧 / petals 左侧风送粉色花瓣自转飘入 / confetti 六色调色板彩纸旋转飘落，全部 60fps（HUD 口径） |
| 彩带调色板 bug（当场抓改） | 首版 confetti 全白——`ColorOverLife` 曾**覆写**粒子色把 spawn 采样的 palette 配色洗掉；语义改为**曲线 × 起始色**（ParticlePool 增 baseRGBA 四数组），重验六色齐出；白色起始配方（spark_burst 等）数学不变，单测 `color curve multiplies base color keeping palette hue` 锁死 |
| 骨骼锚定 | `fx play lightstick_spark` 缺省锚 rightHand；`fx state` 显 `@bone:rightHand`；walk_to 走动 + Waving 挥手动画切换全程特效存活不脱钩（age 25s+，解析失败即自停的机制下「存活」即「解析持续成功」）；spark_burst 显式 `anchor:rightHand` 爆发位置随手部 |
| 团播 fx 命令族 | `fx list`（合并目录 8 预设）/ `fx play confetti`（舞台中心）/ `fx play fire B bone:rightHand`（B 右手火焰目验清晰）/ `fx state` / `fx stop all` 全通 |
| 节目单 fx 幕 | 默认单 17 幕装载（fx 幕预设校验过目录）；开演 confetti 幕 3s hold 收口后 say/move/dance 无缝推进；谢幕前 spark_burst 幕 burst 自然播完 StopFx 空转；`show done` 全程 fps=60——插幕不打断编舞 ✓ |
| 三特效同屏 | 单人（snow+confetti+fire）fps=60；团播（snow 328 + confetti 258 + fire 36 = 622 粒）fps=60——验收线 ≥50 大幅富余 |
| fps 预算表（ai_cmd state 口径，K20 Pro/Adreno） | snow 400 粒 TRANSPARENT=60 · rain 220 粒（域 2.6×0.2×2.6m 细长粒）=60 · fire 96 粒 flipbook ADD=60 · confetti 300 粒=60 · petals 160 粒=60 · 三特效 622 粒同屏=60；透明档预算规则延续 P0 结论：域收紧 + 粒子屏幕占比小是第一纪律 |
| ×20 play/stop + load_model ×3 | 双路径（单人 am start ai_cmd + 团播 STAGE_CMD broadcast）循环零 FATAL/SIGABRT；PSS 1.394GB→1.348GB（降，无泄漏）；进程全程存活 |
| 单测 | VFX P1 新增 20 条（corelib sim 6：cone/sway/turbulence/flipbook/palette/曲线×起始色；app 14：VfxCatalog 7 含对照 assets 树、ShowScript fx 幕 4、ShowDirector fx 幕 3），全模块 **2026 条全绿** |

**P1 实施差异与首验项结论（回填 §八）**：

1. **UV 方向（§八 #3）**：petal/confetti/spark 非对称贴图真机目验——本轮贴图均为弱方向性设计（花瓣中脉、四芒星、方块折痕），且花瓣/彩带配方自带自转，无可辨「上下颠倒」观感，风险降级为低；CPU 端翻 V 预案保留在 `VfxMath.writeParticle` 注释，未来引入带文字/强方向贴图时一行修。
2. **批按（混合档 × 贴图）键控**（§4.5「全屏 2 draw call」的 P1 修正）：材质实例一张 mainTexture，异贴图特效不能共批——批键加贴图维度，全屏 draw call 数 = 在用（档×贴图）组合数（个位数），换仿真实成本可忽略。
3. **贴图缓存**：VfxManager 按 `VfxTexture` 键缓存 GPU 纹理（同贴图多预设共享一张）；Asset 加载失败回退柔边圆点并缓存，`destroy` 走 distinct 去重（防同纹理双毁）。
4. **预设供给两层**：corelib `VfxPresets` 扩到 8 配方（§七表，SDK 零资产兜底可跑）；app `VfxCatalog` 三层合并（内置打底 → presets.json 覆盖同名 → 未来显式参数），presets.json 为真机调参权威表（两者同源，`VfxCatalogTest` 防漂移断言）。**与文档 §4.3 的一处偏差**：未加 `AvatarController.vfxSpecResolver` 注入缝——配饰的 resolver 缝有 corelib 内部消费者（动画联动状态机），VFX 的全部消费者（FxTools/StageCommandRouter/节目单）都在 app 层可直接调 VfxCatalog，加缝只会造 write-only API；P2 LLM 工具同为 app 层，届时仍无 corelib 消费者。
5. **DanceRequestRouter 白名单扩展预研结论**（P1 清单第 4 条末项）：LLM 点播特效走导播链的扩展点已就绪——router 输出 JSON 增可选 `"fx"` 字段、`VfxCatalog.find` 双向校验（同 clip 白名单模式）、命中即组装 `ShowAct(type="fx")` 走既有 enqueueAct 插幕；校验/拒绝降级链零新增。留 P2 与单人 `play_effect` LLM 工具（§六 P2 第 1 条）一并落地，本期限于「点播特效无 LLM 工具通道时不可达」未提前实现。
6. **新坑 C（ColorOverLife 覆写语义）**：曲线行为曾整体覆写粒子色——palette 配色被（白色）alpha 包络洗成纯白，真机 confetti 当场暴露。已改为曲线 × 起始色（起始白 = 纯曲线，palette 色 = 本色过包络）；任何新增「逐粒子属性 + 曲线行为」组合都须先定义乘法还是覆写语义。
7. **flipbook 帧推进语义**：帧号随**生命进度**（出生=第 0 帧、消亡=末帧）而非独立 fps——寿命即动画周期，fire 配方 16 帧/0.7~1.0s 自然得到 16~23fps 播放，省一个参数；单测选址须避开 `frac×totalFrames` 整数边界（浮点噪声翻帧，`ParticleSystemTest.flipbook` 注释有记录）。

### P2 —— AI 通道 + 高级材质（预估 3~4 人日）

1. **LLM 工具**：`play_effect(effect: enum 白名单, at: enum {around_me, stage_center, camera_front} 可选, position 可选)` + `stop_effect`——照 wearAccessory 五步落点（`AgentToolCatalog` 工厂 + `PromptTexts` 双语 description/hint/结果文案 + app `AgentEffectTools` 幂等注册 + `AiChat.fxTools` 开关默认关 + `MainActivity` 接线一行）；失败归因枚举（UnknownEffect/NotAvailable）全回灌不炸回合；**为什么用工具不用行内标签**：`<fx:>` 会成为第四个标签命名空间（协议块膨胀 + InlineTagExtractor 正则面扩大 + 模型滥用刷屏风险），而特效是场景级事件不是逐句修饰，工具的 enum 白名单 + 失败回灌链路（坑 26 的兜底体系）全部现成；
2. **vfx_decal 第三材质**：UV 流动 + Fresnel 边缘光 + 噪声溶解（Shadertoy 三件套），世界平面 Quad（非粒子，直接静态网格走同一 VfxBatchRenderer 通道）——地面魔法阵、护盾、舞台光柱三配方；
3. TurbulenceForce 完整版（curl noise）+ SphereSurfaceEmitter（按需）；
4. 单测：工具 catalog（schema 锁/白名单/失败文案，`AgentDeviceToolsCatalogTest` 模板）+ PromptTexts 双语键（EN 无中文断言）+ decal UV 函数纯数学部分。

**真机验收清单**：「来点舞台特效」→ `play_effect` 原生调用命中预设（wear_accessory 验收法）；胡编特效名 → 拒绝文案回灌 + UnknownTag 三层兜底不炸回合；工具开关关 → `tools_list` 无 play_effect 且请求不带 tools 键；法阵/护盾/光柱三配方目验（含与 `set_ambience SPOTLIGHT` 氛围预设叠加）；`agentSnapshot` 记录协议块 token 增量。

### 远期（本期不做，防 scope 膨胀）

| 项 | 不做的理由 |
|---|---|
| Effekseer（C++ 运行时 + .efk + JNI 桥） | 引入成本 ≫ 收益（§二 #5）；美术驱动复杂特效需求出现时再评估 |
| `<fx:>` 行内标签协议 | 工具通道已覆盖；第四命名空间的协议膨胀不值（§六 P2 #1） |
| GPU 粒子（compute/storage buffer） | Filament Android 侧支持面窄；CPU 侧 512~2000 粒子在移动端预算内 |
| `vfx import` 外置导入 | 配饰 acc import 的特效版（用户自带贴图+JSON），等有真实需求再做 |
| 情绪驱动微特效（`<emo:joy>` → 星星） | 有意思但耦合表情仲裁层，先看 P2 后的实际观感收益 |

## 七、效果配方表（结合项目场景）

| 想要的效果 | 预设 id | 材质档 | 贴图 | 发射器 | 行为要点 | 项目场景挂钩 |
|---|---|---|---|---|---|---|
| 漫天飞雪 | `snow` | transparent | soft_dot | Box 顶部 6×0.4×6m | 重力 -0.6 + 横摆 sway + 落地消亡 | home.glb 客厅场景氛围 |
| 阵雨 | `rain` | transparent | streak | Box 顶部 5×0.2×5m | 重力 -9 + **速度拉伸** + 高回收率 | 同上；显存友好 recycle_oldest |
| 篝火/舞台火焰 | `fire` | additive | flame_4x4 | Cone 底部 0.3m 上升 | flipbook + ColorOverLife 亮黄→橙红→暗 | 场景道具互动 |
| 应援棒流光 | `lightstick_spark` | additive | spark | Point 锚定 **hand骨** | 小粒子高频 + 拖尾 | **配饰 lightstick 组合**，团播应援 |
| 团播开演彩带 | `confetti` | transparent | confetti | Box 顶部 4×0.3×4m | 自转角速度 + 重力 + 色彩随机 | 节目单 `fx` act 开演插幕 |
| 舞台光柱 | `stage_beam` | additive | 梯形渐变 | 静态锥面（非粒子，decal 通道） | UV scroll + 缓慢摆动 | 与 `set_ambience SPOTLIGHT` 叠加标定 |
| 地面魔法阵 | `magic_circle` | additive | 法阵贴图 | 平面 Quad decal | UV 旋转流光 + Fresnel | P2 decal 材质首配方；舞蹈高潮插幕 |
| 护盾/光环 | `shield` | additive | 环形渐变 | 球面 Quad decal | Fresnel 边缘光 + 溶解噪声 | P2；演示向 |
| 星尘爆发 | `spark_burst` | additive | spark | Sphere burst 一次性 | 初速放射 + Drag + 快衰减 | P0 内置预设；TurnCompleted 庆祝钩子（远期） |
| 风吹花瓣 | `petals` | transparent | petal | Box 侧方 4×2×4m | 风向平移 + 自转 + 湍流扰动 | P1 湍流行为验收载体 |

## 八、风险清单与首验项

| # | 风险/未知 | 等级 | 对策与退路 |
|---|---|---|---|
| 1 | **ADD 混合 alpha 语义**（filament ONE/ONE 不读目标 alpha，淡出需源端预乘） | ~~P0 首验~~ ✅已闭环 | shader 内 `rgb *= a` 预乘写死（§4.6）；纯灰底目验平滑淡出无黑框（§六 P0 实录 #1） |
| 2 | **TRANSPARENT 的 premultiplied 约定**（filament 默认 ONE/ONE_MINUS_SRC_ALPHA） | ~~P0 首验~~ ✅已闭环 | 同上预乘写法；白雪高斯边缘干净（§六 P0 实录 #2） |
| 3 | **自有 PNG 的 UV 方向**（Bitmap 行序 top-down vs GL v 轴向上；MToon 的 flipUV(false) 是 GLB 语境） | ~~P1 首验~~ ✅已闭环 | P1 非对称贴图（petal/spark/confetti）真机目验：弱方向性设计 + 自转配方下无可辨颠倒，风险降级为低；CPU 端翻 V 预案保留在 `VfxMath.writeParticle`（注释在案），引入强方向贴图时一行修（§六 P1 实录 #1） |
| 4 | setBufferAt 五参的 position/byteOffset 精确语义 | ~~P0 首验~~ ✅已闭环 | JNI 从 buffer position 起读 sizeBytes；position(0)+limit(n) 下三参/五参真机 A/B 等价（§六 P0 实录 #4），五参部分上传为正路 |
| 5 | GPU 资源 destroy 顺序（坑 13 SIGABRT 前科 + 二次 onDestroy 前科） | 高 | §4.5 幂等 destroy 状态机；抽屉往返 ×5 回归进 P0 验收清单 |
| 6 | 透明粒子互叠排序（depthWrite=false 下加色无序OK、透明档有序感） | 中 | transparent 粒子按出生序写入近似排序（新粒子后写=盖在上面，多数配方自然成立）；严重时 P2 按视深排序写入段 |
| 7 | fill-rate（透明大粒子 overdraw，Adreno 悬崖前科） | 中 | 粒子屏幕占比预算进配方表（雪点 ≤2% 屏高）；P1 出 fps 预算表；批上限默认 512×2 |
| 8 | 零 GC 纪律失守 | 中 | SoA 全预分配 + flush 复用 buffer；评审时 grep update/flush 路径的 new（弹簧骨骼前科） |
| 9 | MToon LINEAR vs PBR ACES 两种色调映射下特效观感漂移 | 低 | P0 验收双模型各验一遍；必要时 intensity 按渲染档位微调（材质参数热改） |
| 10 | LLM 通道模型侧失败形态（坑 26：吐伪工具调用/口头宣称已放特效） | 中 | enum 白名单 + isError 回灌 + InlineTagExtractor 三层兜底全部现成；P2 验收含胡编名拒绝链 |
| 11 | 锚定引用悬空（模型重载后 bone entity 失效） | 低 | 每帧经 host 重新解析 + 解析失败自停（§4.8），不持陈旧 entity |
| 12 | **filamat uniform 引用语法**（P0 实证）：shader 里 uniform 用 `materialParams_xxx` define 语法 = 编译静默失败（isValid=false 无错误信息）；必须 `materialParams.xxx` 结构体语法（sampler 才有 define） | 高（一票否决级） | VfxMaterialFactory 全部 uniform 走结构体语法；材质编译失败只能真机二分诊断，新增 uniform 后必真机回归 |
| 13 | **世界系锚点错位**（P0 实证）：角色 unit-cube 后脚底 y=−1、场景放置后 z≈−4，锚 (0,0,0) = 粒子全部在取景框外「隐身」 | 高 | fx play 缺省锚 = `avatarWorldPos()`（asset.root 世界变换）；显式坐标按轴覆盖 |

## 九、测试与验收策略

- **JVM 单测**（纯数学层）：ParticlePool（回收/预算/溢出/dt 钳制）、ParticleSystem（种子确定性积分序列）、VfxMath（基向量正交/四角/拉伸轴/flipbook UV）、Behavior 逐个、VfxCatalog（对照 assets 树，坑 25）、AgentEffectToolsCatalog（P2，schema 锁/白名单/双语）。基线：P1 交付后全模块 **2026 条全绿**（本轮 VFX 新增 20 条）。
- **真机口径**：fps 只认 `ai_cmd state` 的 `fps=` 字段（坑 14）；视觉差分先 `camera_shot closeup` 固定机位再 screencap；命令注入一律 ai_cmd（MIUI 禁 shell 触摸）。
- **回归清单**（特效层改动不得波及）：MToon 渲染/切角色、弹簧骨骼、走动（含 FOLLOW 跟拍）、配饰挂载、抽屉跳转往返、模型重载 ×5 泄漏。

## 十、file:line 锚点汇总

| 主题 | 锚点 |
|---|---|
| 单人帧循环/挂点 | `corelib/.../internal/SoulLinkRenderer.kt:427-552`（updateCameraShot :541、render :549、骨骼 commit :515） |
| 团播帧循环 | `corelib/.../stage/internal/Stage.kt:328-425`（:388/:396/:404） |
| 运行时材质编译先例 | `corelib/.../internal/MToonMaterialFactory.kt:60-88,106-111,164,183-198` |
| 贴图上传先例 | `corelib/.../internal/MToonApplier.kt:299-343`（flushAndWait :337） |
| Bloom/色调映射 | `SoulLinkRenderer.kt:840-846,888-890,902-919`；`AvatarRenderSettings.kt:38,96-98,123`；Stage 侧 `Stage.kt:623-628,679-694` |
| 相机基向量取法 | `SoulLinkRenderer.kt:2652-2658`、`Stage.kt:859-864`、Manipulator 构建 :86-99 |
| 解耦缝模板 | `corelib/.../accessory/AccessoryHost.kt`；resolver 缝 `AvatarController.kt:392`；事件中继 `stage/AvatarHandle.kt:65-79` |
| ai_cmd | `app/.../AiDebug.kt:101-329`（help）、`AiDebugExecutor.kt:153,180`、`AccessoryTools.kt:55-71`、`MultiAvatarActivity.kt:779,951` |
| LLM 工具五步 | `agent/AgentToolCatalog.kt:501-591,671-683`、`AgentAccessoryTools.kt:25-57`、`MainActivity.kt:1461-1472`、`AiChat.kt:93`、`i18n/PromptTexts.kt` |
| 资产包模板 | `app/src/main/assets/accessories/{accessory.json,bindings.json,LICENSES.md}`、`multiavatar/AccessoryCatalog.kt:76-133` |
| 程序化资产先例 | `tools/icon-gen.py`、`tools/accessory-samples/make-phone-glb.js` |
| Filament 依赖声明 | `corelib/build.gradle.kts:76-80`（filamat-android :80） |

---

*本文档为开发前调研。落地时按 P0 → P1 → P2 顺序推进，每期真机验收通过后回填「验收实录」到对应分期小节（accessory-feasibility.md 惯例）。*
