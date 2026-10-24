# 天空盒（360° 全景壁纸）

> 2026-10-24 实现并真机验收（Redmi vermeer / Adreno）。状态：**已落地，未提交**。
> 同日修复"天空盒非常模糊"：根因 = filament-utils GPU 通道硬编码 256×256/面，
> 改自研 CPU 转换（1024/面），真机复验通过。需求：把纯色灰背景换成可导入的
> 360° 全景壁纸（equirect 等距圆柱投影，2:1 的 JPG/PNG/WebP）。
> 同日晚修复"原图明亮、实机灰暗"：根因 = filament 天空盒被 IBL×曝光乘子衰减
> （坑 9），改 RGBA16F 预乘增益上传 + 亮度倍率旋钮，真机复验逐点对齐原图。

## 一、用户面

| 入口 | 用法 |
|---|---|
| SDK 配置 | `AvatarConfig(skyboxPath = "skybox/forest_grove.jpg")` 启动即载；`AvatarRenderSettings(skyboxBrightness = 1.3f)` 亮度倍率 |
| SDK 运行时 | `controller.setSkybox("skybox/beach.jpg")` / `setSkyboxFromFile(path)` / `setSkyboxYaw(deg)` / `setSkyboxBrightness(f)`（1.0=与原图一致，0.1–4）/ `clearSkybox()`；团播 `AvatarStage` 同面 API |
| demo 默认 | 启动自动加载 `assets/skybox/forest_grove.jpg`（Polyhaven CC0，4096×2048），demo 状态 `DemoUiState.selectedSkybox`（会话级不持久化） |
| ai_cmd | `skybox`（当前+列表+亮度）· `skybox <name>`（切换，可省扩展名）· `skybox off` · `skybox yaw <deg>` · `skybox gain <f>`（亮度倍率；多词参数注意 adb 内外双引号） |
| LLM 工具 | `set_skybox`（enum = assets/skybox 目录 + `off`；只跟 `agentTools` 总开关，无独立子开关——换背景低频良性，不同于特效刷屏风险） |

异步语义：`setSkybox`/`setYaw` 受理即返回 true，转换在后台（约 0.5-0.8s）完成后
下一帧生效；切换后立刻 `yaw` 也能正确作用于在途首图（pendingSource 机制）。

## 二、架构

```
SkyboxManager (corelib/internal, SoulLinkRenderer 与 Stage 共用)
  setPanorama(bitmap, yaw) ──► fitSource（ARGB_8888 + 宽≤4096 → 像素快照 IntArray，位图即回收）
                             ──► [后台串行线程] shiftPixelsHorizontally（yaw 循环横移，纯函数已单测）
                             ──► EquirectToCubemapConverter.createPyramid（box 金字塔）
                             ──► 六面并行 convertFace（专用 skybox-face 线程池×6，纯数组运算）
                             ──► [回主线程] RGBA16F 上传（CPU 线性化×增益，单层，SkyboxTexturePacker）
                             ──► Skybox.Builder().environment(cubemap) → scene.skybox（先挂新再毁旧）
  setColor(rgba)             纯色回退（原 setupSkybox 行为），释放全部全景资源
  setYaw(yaw)                从驻留像素数组重派生（零解码）；首图在途时随首图一起落位
  setBrightness(f)           亮度倍率（1.0=原图；变更触发重派生）
  setEnvironmentLuminance()  渲染端推送 IBL强度×相机曝光；增益实际变化才重派生
  destroy()                  渲染器 onDestroy 内、引擎 shutdown 前调用
```

- **亮度补偿（坑 9 的修复）**：filament 天空盒着色器采样后乘 `iblLuminance =
  IBL强度 × 相机曝光`（`materials/skybox.mat` + `View.cpp`/`ColorPassDescriptorSet.cpp`；
  纯色分支不乘——这是纯色背景从未发暗的原因）。本项目 13 000 lux × 晴天16默认曝光
  2.6e-5 ≈ **0.34**，LDR 全景被压暗约 1.6 档。修复 = 上传前预乘
  `gain = brightness / envLuminance`（`SkyboxTexturePacker`），格式换 **RGBA16F**
  （gain≈2.9 时线性值超 1.0，8-bit sRGB 装不下）。补偿只作用于天空盒纹理，
  **不动相机曝光/IBL 强度**（那是全场景共用的乘子，动了毁灯光调校）。渲染器在
  `applyIblSettings` 后推送实时亮度 → 档位切换自动重派生；`brightness` 旋钮
  （0.1–4）在补偿之上再乘，1.0 = 与原图逐像素一致。ACES 色调映射档（PBR 模型）
  仍会对补偿后的线性值做曲线压缩（与头像同链路，属预期）；MToon/LINEAR 档下
  天空盒与原图逐像素一致。
- **为什么不用 filament-utils 的 `IBLPrefilterContext.EquirectangularToCubemap`**：
  其原生实现（libs/iblprefilter IBLPrefilterContext.cpp）把输出 cubemap 硬编码为
  **256×256/面**，Java 绑定无自定义尺寸入口——4096 宽的源被压到 256/面再拉伸到
  整屏，必然模糊（修复前的用户报障）。自研 CPU 转换按源宽 1/4 输出（上限
  1024/面），与源像素密度精确对齐（4096 宽 = 11.4 px/°，cubemap 1024/面同密度）。
- **方向约定与 GPU 通道逐像素一致**（观感不变，只有清晰度差异）：面像素→方向 =
  filament libs/ibl `Cubemap.h getDirectionFor`（cmgen 同表 = GL 硬件采样约定）；
  方向→equirect UV = libs/iblprefilter `equirectToCube.mat toEquirect`。两式互为
  逆映射，JVM 单测逐像素锁住（`EquirectToCubemapConverterTest`）。
- **采样**：源图 box 金字塔 + 按每像素水平足迹选层（equirect 极区方位角压缩，
  对应 GPU 的 trilinear+各向异性 16），层内双线性；水平环绕（=GL REPEAT）、垂直
  钳制。滤波在 sRGB 空间（GPU 通道线性空间，自然图像差异不可感知），输出保持
  sRGB 编码由 SRGB8_A8 硬件解码，shader 得到的线性值与 GPU 通道等价。层深封顶
  在密度点+2 层：极区水平足迹→∞，再往下取就是全图均值（色调漂移），封顶后极区
  ≈顶部条带均值。
- **线程模型**：公开方法主线程调；位图后处理在主线程（几十 ms），转换在后台
  （转换线程串行 + 六面专用池并行，全为纯数组运算不碰引擎），落盘回主线程。
  epoch 计数使换新/纯色/销毁后到达的旧转换结果在触引擎前被丢弃。
- 光照不动：仍走 default_env.ktx IBL + 三灯 rig，天空盒**只做背景**（SpecularFilter/
  irradiance SH 派生 IBL 留作后续——调色不对会毁观感，收益有限）。
- 生命周期与引擎一致：引擎不随模型加载重建（只有最终 teardown），skybox/cubemap/
  驻留像素数组跨模型加载存活，仅 destroy 一次。

## 三、资产规格

- **等距圆柱投影（equirect）、宽高比 ≈ 2:1**。偏离 2:1（<1.7 / >2.3）只告警不拒绝，
  画面会拉伸/畸变。
- 源宽 >4096 自动降采样；**清晰度上限 = 素材密度**：cubemap 每面 = 宽/4，与源
  px/° 精确对齐。要更锐（如 1080p 屏近水平视场 ≈ 18 px/°）需换 8192 宽素材，
  同时调高 `MAX_FACE_SIZE`（2048/面 RGBA16F 单层 ≈ 200MB 显存，旗舰可行）。
- 无最小尺寸要求（旧 GPU 通道的"最长边 ≥2048"是它的 12 级 mip 断言所致，本通道
  不需要；极小图自动按 128/面 保底）。
- 推荐来源：[Polyhaven](https://polyhaven.com) HDRI 的 Tonemapped JPG（CC0），下载后
  本地缩到 4096×2048、q88 ≈ 3-4MB。

## 四、坑（真机实证）

1. **filament-utils GPU 通道输出 256×256/面（本功能模糊根因）**：
   `IBLPrefilterContext.EquirectangularToCubemap.run()` 原生实现硬编码
   `.width(256).height(256)`，Java 绑定不暴露 outCube 参数。任何走该通道的
   全景背景都会糊，自研 CPU 转换绕开。
2. **旧通道的两条 SIGABRT 断言**（自研通道已不存在，留档）：
   equirect 输入必须恰好 12 级 mip（"equirect must have 12 mipmap levels
   allocated"）；`generateMipmaps` 需要 GEN_MIPMAPPABLE usage。
3. **pexels-amar-35483494.jpg 不是全景**（已从目录移除，留档）：小行星立体投影
   （tiny planet，stereographic），硬塞天空盒会环向拉伸 + 左右边缘硬接缝
   （真机 aspect 1.02 告警 + 截图实证）。选素材认准 2:1 的 equirect。
4. **各向同性金字塔在极区会取到"全图均值"**：极区水平足迹→∞，金字塔不封层深
   一路下钻，底层均值被边缘列/行权重带偏（真机模拟：天顶色 179 vs 全图均
   值 127.5）。封顶在密度点+2 层后极区≈顶部条带均值。另：奇数尺寸降采样
   必须 ceil 分块（floor 丢边、单 texel 块超权都会色调漂移），Level 尺寸标注
   与数组必须同一出处（曾错位导致索引串行）。
5. **转换线程池必须专用**：ForkJoinPool.commonPool 会被应用其他子系统（模型
   加载期步态提取等）抢占，vermeer 真机观测到六面被拖成串行、单次 22s；换
   专用 fixed pool×6 后 0.47-0.53s（SD855）。`invokeAll` 逐面 Log 埋点可定位。
6. **yaw = 像素循环横移**（转换线程内做，主线程零成本），正角 = 原画面右侧内容
   转到正前方；重派生不重解码，靠管理器驻留降采样像素数组（≈32MB，全景期间
   常驻）。
7. 换新失败（路径/解码/GPU 上传异常）保留旧背景不黑屏；先 scene.skybox = 新再
   毁旧，避免一帧采样已毁纹理。上传异常时销毁半成品 cubemap。
8. ai_cmd 多词参数（`yaw 90`）必须 adb 内外双引号 `--es ai_arg '"yaw 90"'`（老坑
   重现）；参数走 `ai_arg`，`ai_cmd` 只放命令名。
9. **原图明亮、实机灰暗（IBL×曝光乘子耦合，2026-10-24 晚修复）**：filament 把
   天空盒当"场景辐亮度"处理，着色器采样后乘 `iblLuminance = IBL强度 × 相机曝光`
   （`materials/skybox.mat` 的 `sky.rgb *= frameUniforms.iblLuminance`；乘子来源
   `View.cpp` → `ColorPassDescriptorSet.cpp: s.iblLuminance = intensity * exposure`）。
   本项目无 `setExposure`（filament 默认 f/16、1/125s、ISO 100，`details/Camera.h`，
   曝光 2.6e-5）+ IBL 默认 13000 lux → 乘积 ≈ 0.34，LDR 全景被压暗约 1.6 档；
   真机实测吻合预测（雪面 p90 原图 0.729 → 显示 0.43）。**指纹**：切灯光档位
   天空盒亮度跟着变 5~6 倍（DIM 3500 → 乘子 0.09 近乎全黑）。修复 = RGBA16F
   预乘增益上传（`SkyboxTexturePacker`）+ 渲染器推送实时 envLuminance +
   `brightness` 用户旋钮。注意：`Skybox.Builder().intensity()` 在有 IBL 时被
   IBL 强度顶替（Java doc 明示），且无 IBL 时天空盒强度默认 0（黑）——本项目
   恒有 IBL，前半句是耦合本身，后半句是 IBL_NONE 配置的既有边界。

## 五、验收记录（2026-10-24 真机）

- 模糊修复复验（Redmi raphael / SD855 / 60Hz）：窗外树干、枝桠、叶隙光斑清晰
  可辨（修复前为无结构色斑，纯天空盒区拉普拉斯方差 9.7 → 窗内 190-307）；
  fps=60（该机屏上限）无回落；vermeer 修复前实测 112-118。
- 性能：4096×2048 → 1024/面 全程 0.7s（转换 0.45-0.53s + 上传 0.23-0.26s），
  小行星素材 2169×2119 → 543/面 0.16s+0.07s；连续 10 次快速切换零失败零崩溃、
  耗时无衰减（无泄漏）；TOTAL PSS 656MB 平稳。
- ai_cmd 全链：status / off / `pexels-amar-35483494`（切+aspect 告警）/ `yaw 90`
  （含 -90 回退）/ 切回 forest_grove 全 OK；yaw 在首图在途期间到达也能正确落位。
- 「天」按钮（MainActivity FAB 列，「切换场景」上方）：循环 assets/skybox 全部
  图片，末张回绕首张。素材换为 3 张后真机验证：forest_grove → pexels-1516822895
  （14400×7200 2:1）→ pexels-4sjpixel（12000×6000 2:1）→ 回绕，零 aspect 告警；
  8K+ 源经 inSampleSize 解码降到 ≤4096 宽，链路无感。
- 单测：`EquirectToCubemapConverterTest`（7：面中心方向约定/侧四面逐像素 GL
  约定/环绕缝/极区金字塔/不透明/分面一致性/faceSizeFor）+ `SkyboxShiftTest`（4）
  全绿；全仓三模块单测通过。

### 亮度补偿修复（2026-10-24 晚，坑 9）

- 单测新增 `SkyboxTexturePackerTest`（8：half 位型/往返精度/次正规与下溢/向偶
  舍入/LUT 端点与单调/打包字节序与 alpha/增益语义端到端（brightness=1 时
  shader 乘回后 = 原图 sRGB）/曝光公式对照）；全仓三模块单测通过。
- 真机（raphael / SD855）：雪面 p90 **0.735 vs 原图 0.729 逐点对齐**（修复前
  0.430）；`skybox gain 1.5` → swap 日志 gain=3.62（=1.5×2.41 组合正确）、p90
  0.820 可见提亮；`gain 1.0` 复原 2.41；`off`→重进全景往返后亮度不变；连续
  4 次重派生零崩溃零泄漏，fps=60 无回落。gain=2.41 反推设备实际 IBL ≈ 15900
  lux（用户调亮过，非 13000 默认）——证明补偿读的是运行时真实值。
- 1024/面上传（F16 8B/px + 256 项 half LUT）457ms，与旧 sRGB 路径同量级。
- ai_cmd 全链：`skybox`（状态行含 brightness）/ `gain 1.5` / `gain 0.7`（夹取
  范围内）/ `gain 1.0` 全 OK。

## 六、后续候选

- 全景派生 IBL（SpecularFilter + irradiance SH）——天空盒参与照明，日落全景暖光房间。
  注意：当前 cubemap 是单层（天空盒 shader 只采样 LOD 0）且含亮度增益，派生 IBL
  需另起带 mip 的通道并剥掉 gain（用驻留源像素重转）。
- `.hdr` 输入（filament-utils HDRLoader 现成，Polyhaven 原生格式）。
- 团播 StageChat 挂 `set_skybox` 工具（API 已在，仅 demo 接线缺）。
- 小行星素材的 stereographic→equirect 重映射（CPU 一次预处理）。
- 8K 素材支持（调高 MAX_FACE_SIZE + 显存评估，见资产规格）。
- vermeer 上 22s 转换的根因复查（专用池已隔离，待该机重新连上后复测）。
