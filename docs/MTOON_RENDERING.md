# MToon 渲染架构与开发指南

> 本文档记录 AIAvatar-SDK 中 MToon(VRM 卡通着色)的实现方式、项目结构、关键约束与验收流程。
> 当前实现是 **pixiv/three-vrm 官方着色器的移植**(2026-10 重写),后续开发新功能或修 Bug 前请先通读本文。

---

## 1. 项目结构

```
AIAvatar-SDK/
├── app/                                  # Demo 应用 (applicationId: com.neethu.aiavatar_sdk)
│   └── src/main/
│       ├── java/com/neethu/aiavatar_sdk/MainActivity.kt   # Compose Demo UI(模型/动画/表情/场景面板)
│       └── assets/
│           ├── vrms/                     # 测试模型(VRM 0.x / VRM 1.0 / GLB 混合)
│           ├── animations/               # .vrma 动画
│           ├── scene/                    # 场景 GLB(如 home.glb)
│           └── default_env.ktx           # IBL 环境光(Demo 中 5000 lux)
├── corelib/                              # SDK 本体 (namespace: com.neethu.corelib)
│   ├── build.gradle.kts                  # 含 matc 材质编译任务(见 §5)
│   └── src/main/
│       ├── materials/*.mat               # Filament 材质源文件(着色器在这里!)
│       ├── assets/materials/*.filamat    # matc 编译产物(运行时加载)
│       ├── cpp/                          # 占位 JNI(NativeLib.stringFromJNI),渲染全在 Kotlin
│       └── java/com/neethu/corelib/
│           ├── AvatarView.kt             # Compose 入口(AndroidView 包装 SurfaceView)
│           ├── AvatarController.kt       # 公开 API:loadModel/playAnimation/setRenderMode...
│           ├── AvatarConfig.kt           # 初始化配置(iblPath/enableMToon/enableSpringBone...)
│           ├── AvatarRenderMode.kt       # 渲染模式枚举(PBR / MTOON)
│           ├── AvatarState.kt            # UI 状态流
│           ├── AvatarBehavior.kt         # 交互行为
│           └── internal/                 # 内部实现(不对外)
│               ├── SoulLinkRenderer.kt   # 渲染引擎核心:灯光/场景/加载流程/帧循环
│               ├── MToonMaterialHelper.kt# MToon 材质加载与参数绑定 ★
│               ├── VrmGlbParser.kt       # VRM/GLB JSON 解析:纹理解码 + MToon 参数提取 ★
│               ├── GlbBoneCuller.kt      # 预处理:裁剪未用骨骼以适配 Filament 256 骨骼上限
│               ├── VrmExpressionManager.kt # 表情(morph target)管理
│               ├── VrmSpringBoneManager.kt # 弹簧骨骼物理(头发/裙摆)
│               ├── VrmaParser.kt / VrmaAnimationEngine.kt # .vrma 动画
│               └── NativeLib.kt
└── local.properties                      # sdk.dir;可加 matc.path
```

标 ★ 的两个文件 + `materials/*.mat` 是 MToon 开发的主战场。

## 2. 技术栈与版本匹配(重要)

| 组件 | 版本 | 备注 |
|---|---|---|
| filament-android / gltfio-android / filament-utils-android | **1.68.3** | Maven 依赖,见 corelib/build.gradle.kts |
| matc(材质编译器) | `/usr/local/bin/matc`,输出 "68" | **必须与运行时大版本一致** |
| Filament 源码(本机参考) | `/home/neethu/projects/filament`(main 分支) | 查 1.68.3 API 用 `git -C ~/projects/filament show 8e0f0c92c:<path>`,`8e0f0c92c` = "Release Filament 1.68.3" |
| three-vrm 参考实现 | `/home/neethu/projects/three-vrm` | 着色器:`packages/three-vrm-materials-mtoon/src/shaders/mtoon.{frag,vert}`;加载器:`MToonMaterialLoaderPlugin.ts` |
| minSdk / compileSdk | 29 / 36 | |

材质 API 文档(1.68.3):`git -C ~/projects/filament show 8e0f0c92c:docs/Materials.md.html`(去掉 HTML 标签后可读),`customSurfaceShading`、`LightData`/`ShadingData` 结构、`specularFactor` 等均以此为准。

## 3. 模型加载与 MToon 应用流程

`SoulLinkRenderer.loadModel(assetsPath)` 的关键顺序(**顺序有语义,改动需谨慎**):

```
assets 读字节
  → VrmGlbParser.parse()               # 解码全部图片为 Filament Texture(SRGB8_A8)
  → getMaterialInfos()                 # 每个 glTF material → MaterialInfo(MToon 参数)
  → getPrimitiveMaterialMapping()      # mesh/primitive → materialIndex
  → GlbMorphPatcher.injectMorphDefaultWeights()  # 给带 morph target 的 mesh 注入默认 weights
  → GlbBoneCuller.cullUnusedBones()    # 骨骼裁剪(避免 >256 崩溃)
  → modelViewer.loadModelGlb()         # gltfio 建实体(默认 ubershader PBR 材质)
  → transformToUnitCube()
  → VRMA/表情/弹簧骨骼初始化
  → MToonMaterialHelper.applyToAssetWithTextures()   # 逐 primitive 换成 MToon 材质
  → loadEnvironment(config.iblPath)    # IBL(5000 lux)+ ACES tonemapping
```

> **为什么需要 GlbMorphPatcher**:gltfio 只在 `mesh.weights_count > 0` **且材质不是 unlit**(`!prim.material->unlit`)时才为 morph target 计算并上传法线(tangent frame,ResourceLoader.cpp 的 TangentsJob 门控);VRM 导出模型普遍不写默认 `weights`,且 MToon 导出会给所有材质打 `KHR_materials_unlit`,导致 MorphTargetBuffer 的法线纹理层是未初始化内存。一旦表情权重非零,Filament 顶点态 `morphNormal()` 会把垃圾数据加进顶点法线——matcap 材质出现乱纹(法线乱→matcap UV 乱),无 matcap 的材质明暗全坏/全黑。注入全零 `weights` + 剥离 `KHR_materials_unlit` 即可让 gltfio 走正常上传路径(本 SDK 的材质判定只看 VRMC_materials_mtoon/VRM 0.x materialProperties,不受影响),中性表情外观不变。

材质变体选择逻辑(`MToonMaterialHelper`):
- 解析出的 `MaterialInfo.isMToon == false` → **unlit 材质**;
- 是 MToon → 按 gltfio 已解析的 `originalMi.material.blendingMode` 选择:
  - `OPAQUE` → vrm_mtoon_opaque(不透明)
  - `MASKED` → vrm_mtoon_masked(alpha test,threshold 0.5)
  - `FADE`/`TRANSPARENT` → vrm_mtoon_transparent

> **关键事实**:gltfio 把 glTF `alphaMode: BLEND` 映射为 `BlendingMode.FADE`(直通 alpha,见 filament 源码 `libs/gltfio/src/UbershaderProvider.cpp`)。所以 transparent 变体必须用 `blending: fade`;若用 `blending: transparent`(预乘语义)眼睛/眉毛会出现白边光晕。

### 3.1 运行时切换 PBR / MToon

公开 API:`AvatarController.setRenderMode(AvatarRenderMode)` / `getRenderMode()`(枚举在 `AvatarRenderMode.kt`),内部走 `SoulLinkRenderer.setRenderMode()`。切换只换 MaterialInstance,不重载模型,动画/表情/弹簧骨骼全部不受影响。机制:

- **加载时**:`loadModelGlb` 之后立刻 `captureOriginalMaterials()` 把 gltfio 的 PBR ubershader 实例按 (entity, primitiveIndex) 存进 `originalMaterialSlots`;随后若当前模式是 MToon 才 `applyMToonToAsset()`。
- **切到 MToon**:对当前 asset 重新跑一遍 §3 的 MToon 应用流程(变体选择按原始 ubershader 材质的 blendingMode,恢复后仍有效)。
- **切到 PBR**:按 slot 恢复原始实例,然后 `MToonMaterialHelper.releaseInstances()` 销毁不再被引用的 MToon 实例(**顺序有语义**:先恢复再销毁,否则下一帧引用已销毁实例会崩)。
- **VRM 解析是懒加载**(`ensureVrmParsed`):只有第一次真正需要 MToon 时才解码纹理并缓存到 `parsedVrm`,纯 PBR 用法零纹理开销;纹理缓存跨切换复用,**换模型时**(`loadModelGlb` 已销毁旧 asset 与 renderable)先 `releaseInstances()` + `releaseParsedVrm()` 再重建。在 MToon 实例仍被引用时销毁纹理同样会崩,顺序同理。
- `enableMToon` 配置项语义变为**初始模式**;MToon helper 现在总是创建(若 .filamat 加载失败,`setRenderMode(MTOON)` 返回 false 并保持原模式)。

Demo 的设置入口:右下角 FAB 列最上方的齿轮(约 tap 985 1060,1080×2340),设置界面是带遮罩的底部抽屉,Rendering 区单选 PBR/MToon 即时生效。

## 4. 着色器设计(three-vrm → Filament 映射)

三个 MToon `.mat` 共享同一份着色器主体(仅 material 块头部不同),逐条对应 three-vrm 的 `mtoon.frag`:

| three-vrm 概念 | Filament 实现方式 |
|---|---|
| `RE_Direct_MToon` 逐灯 toon 漫反射 | `surfaceShading()`(每灯调用一次,天然等价) |
| `getShading()`:dotNL+shift → toony ramp → ×shadow | `getShading()`,`linearstep(-1+toony, 1-toony, dotNL+shift)`(分母 +1e-5 防 toony=1 除零) |
| `getDiffuse()`:`lightColor * BRDF_Lambert(mix(shade, lit, shading))` | `lightColor * mix(...)/PI` |
| `V0_COMPAT_SHADE`:min(col, diffuseColor) | **在乘以灯光颜色之后** clamp(顺序与 three-vrm 一致) |
| rim + matcap 在光照循环后加一次 | 放进 `material.emissive`(Filament 在所有灯光之后加 emissive);双面法线翻转发生在 `material()` 之前,故 `getWorldNormalVector()` 已翻转 |
| `rimMix = mix(白, directSpecular累计, rimLightingMix)` | directSpecular 无法跨灯累计 → 由 app 提供 uniform `lightIrradianceSum`(见 §6 灯光耦合) |
| `RE_IndirectDiffuse_MToon`:irradiance × Lambert(diffuseColor) | `material.specularFactor = 0` 关掉 IBL 高光,IBL 只剩 Lambert 漫反射。**1.68.3 的 customSurfaceShading 不接管 IBL**,IBL 永远走标准 PBR 路径,这是唯一的驯服手段 |
| matcap UV(视空间法线球面投影) | `mat3(getViewFromWorldMatrix())` 变换 normal/view 后按 three-vrm 公式计算;CLAMP_TO_EDGE 采样 |
| 灯光强度单位 | three-vrm 方向光强度 ≈1.0;Filament 用 lux。`lightIntensityScale`(app 设为 1/30000)归一化 |

**没有实现的 three-vrm 特性**(按需求优先级):
1. **轮廓线(outline)**:inverted-hull 需要为每个 renderable 复制几何体/实体;而本项目的蒙皮(skinning)、morph 表情、弹簧骨骼更新全部绑定在原实体上,复制实体需要镜像所有骨骼/权重更新,侵入性大。属大特性,需单独规划。
2. UV 滚动/旋转动画(`uvAnimationMaskTexture` 等)。
3. normalMap(`getTangentFrame` 移植,需 tangent 属性)。
4. `giEqualizationFactor`:three-vrm 的 GLSL 与节点路径都**声明但未使用**(死参数),移植版同样只解析不使用。
5. `transparentWithZWrite` 深度写入切换。

## 5. 材质参数清单(着色器 ↔ Kotlin 必须严格对应)

`vrm_mtoon_*.mat` parameters(三个变体相同)与 `MToonMaterialHelper.applyMtoonParameters()` 一一对应:

| 参数 | 类型 | 来源(VRM 1.0 扩展字段) | VRM 0.x 字段 |
|---|---|---|---|
| baseColorFactor | float4 | pbrMetallicRoughness.baseColorFactor | _Color(gamma→linear, alpha 保持) |
| mainTexture | sampler2d | baseColorTexture | _MainTex |
| shadeColorFactor | float3 | shadeColorFactor | _ShadeColor(gamma→linear) |
| shadeMultiplyTexture | sampler2d | shadeMultiplyTexture | _ShadeTexture |
| hasShadeMultiplyTexture | bool | — | — |
| shadingShiftFactor | float | shadingShiftFactor | V0→V1 换算公式(见 parser 注释) |
| shadingToonyFactor | float | shadingToonyFactor | 同上 |
| shadingShiftTexture + Scale | sampler2d/float | shadingShiftTexture{index,scale} | 无 |
| hasShadingShiftTexture | bool | — | — |
| parametricRimColorFactor | float3 | parametricRimColorFactor | _RimColor(gamma→linear) |
| parametricRimFresnelPowerFactor / parametricRimLiftFactor / rimLightingMixFactor | float | 同名 | _RimFresnelPower/_RimLift/_RimLightingMix |
| rimMultiplyTexture | sampler2d | rimMultiplyTexture | _RimTexture |
| hasRimMultiplyTexture | bool | — | — |
| matcapFactor | float3 | matcapFactor | V0 固定 [1,1,1] |
| matcapTexture | sampler2d | matcapTexture | _SphereAdd |
| hasMatcapTexture | bool | — | — |
| emissiveFactor | float3 | glTF emissiveFactor | _EmissionColor(gamma→linear) |
| emissiveTexture | sampler2d | emissiveTexture | _EmissionMap |
| hasEmissiveTexture | bool | — | — |
| v0CompatShade | bool | false | true(整个模型按 meta version 判定) |
| flipV | bool | 恒 true(glTF 与 Filament 的 V 翻转约定) | 同 |
| lightIntensityScale | float | app 注入 = 1/REFERENCE_LIGHT_LUX | 同 |
| lightIrradianceSum | float3 | app 注入(见 §6) | 同 |

unlit 材质参数:`baseColorFactor`、`mainTexture`、`flipV`。

纹理颜色空间:全部按 sRGB 处理(SRGB8_A8,`VrmGlbParser.createTextureFromBitmap`)——与 three-vrm 一致(shade/matcap/rim/shadingShift 都是 sRGB color texture);因子(factor)保持线性,不做 sRGB→linear 转换。matcap 用 CLAMP 采样,其余 REPEAT。

默认值(three-vrm MToonMaterial 默认):shadeColor=黑、toony=0.9、shift=0、rimColor=黑、rimPower=5、rimLift=0、rimLightingMix=1、matcapFactor=白。

## 6. 灯光 rig 耦合(改灯光必读)

`SoulLinkRenderer.companion` 定义了场景灯常量,**两处必须同步**:

```kotlin
SUN_COLOR = [1.0, 0.98, 0.95], SUN_LUX = 90_000   // 主光,castShadows
FILL_COLOR = [0.8, 0.85, 1.0], FILL_LUX = 30_000  // 补光
```

- `setupLighting()` 用它们创建 Filament 方向光;
- `setupMToonMaterial()` 调用 `mtoonHelper.setLightRig(SUN_COLOR, SUN_LUX, FILL_COLOR, FILL_LUX)`,helper 计算:
  - `lightIntensityScale = 1 / 30_000`(REFERENCE_LIGHT_LUX)→ 主光在着色器中等效强度 ≈0.955,补光 ≈0.318;
  - `lightIrradianceSum = Σ color × lux × scale` → 供 `rimLightingMixFactor` 混合(three-vrm 的 rim 是 mix(白, 全部灯非遮挡辐照度, mixFactor),与阴影无关)。

**如果改灯的数量/强度/颜色,必须同步调 `setLightRig`**,否则 MToon 明暗与 rim 会错。初始化顺序:`init { setupLighting(); setupMToonMaterial() }` —— helper 创建时灯光已存在,setLightRig 会被后续创建的每个 MaterialInstance 继承(`applyLightRig`)。

View 设置:ACES tonemapping、IBL 5000 lux。无 IBL 时(IBL diffuse=0)模型暗部只由补光的 toon ramp 提供,属正常现象。

## 7. 工具链:matc 编译材质

`.mat` 改动后必须重新编译到 assets,否则运行时加载的是旧着色器:

```bash
cd corelib/src/main
for m in vrm_mtoon_opaque vrm_mtoon_masked vrm_mtoon_transparent vrm_unlit; do
  matc -p mobile -a opengl -o assets/materials/$m.filamat materials/$m.mat
done
```

或 `./gradlew :corelib:compileMaterials`(任务在 corelib/build.gradle.kts,读 local.properties 的 matc.path 或 PATH)。

**崩溃陷阱(踩过)**:`MaterialInstance.setParameter(name, ...)` 对不存在的 uniform 直接 **SIGABRT**(Filament precondition)。matc 会剔除着色器中未使用的 uniform/参数,所以 Kotlin 设置的每个参数名必须在**所有会被设置该参数的材质变体**中真实存在且被着色器使用——包括 vrm_unlit.mat(曾因 MToon 参数改名 `baseColorFactor` 而 unlit 仍叫 `baseColor`,加载含非 MToon 材质的模型即崩溃)。改名/删参数时全局搜索 helper 中的 `setParameter(` 逐一核对。

## 8. 真机验收流程

设备:Redmi K20 Pro(adb id `2c3769db`,1080×2340,density 440)。

```bash
./gradlew :app:installDebug
adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity
adb exec-out screencap -p > /tmp/shot.png
adb logcat -d -s MToonMaterialHelper:* VrmGlbParser:* SoulLinkRenderer:*   # 材质应用/解析日志
adb logcat -d | grep -E "Fatal signal|Precondition"                        # Filament 崩溃排查
```

UI 换模型:右下角第四个 FAB(列表图标,约 tap 985 2154)→ 面板内点选;列表滚动 `adb shell input swipe 460 2120 460 1500 600`。

验收要点:
- VRM 0.x(如 HatsuneMikuNT.vrm)与 VRM 1.0(如 1.1.vrm、model.glb)都要测;
- 眼睛/眉毛(FADE)无白边光晕;头发(MASKED)无破碎;
- 明暗交界有 toon 阶梯感;rim 不重复发光;matcap 只出现在带 `_SphereAdd`/matcapTexture 的材质;
- 地面有模型投影(visibility 通路正常)。

## 9. 常见 Bug 排查速查

| 症状 | 首查 |
|---|---|
| 切模型崩溃 SIGABRT,logcat 有 `uniform named "..." not found` | §7 参数与材质变体不匹配 |
| 表情一激活明暗/阴影错乱、脸变黑或 matcap 乱纹(morph 位置正常) | §3 GlbMorphPatcher:morph 法线未上传(weights_count 与 KHR_materials_unlit 双门控) |
| 模型过亮/过暗但关系正确 | 灯光 rig 与 setLightRig 不同步(§6);REFERENCE_LIGHT_LUX |
| 暗部全黑 | 没加载 IBL 且补光太弱(§6);或 specularFactor 未生效导致 IBL 被夺能 |
| 透明件白边 | transparent 变体混入预乘语义(应为 `blending: fade`,§3) |
| rim 变亮一倍 | rim 混进了逐灯循环(应在 material.emissive,§4) |
| matcap 随视角异常 | 视空间矩阵/CLAMP 采样(§4);确认用的是 `getViewFromWorldMatrix()` |
| 新解析的参数不生效 | 材质里没加对应 parameter / 没重新 matc / helper 没绑定(§5、§7) |
