# 虚拟人任意动作生成:可行性分析与落地方案

> 生成时间:2026-10-23。对应代码状态:`main@6808513`(辩论赛升级后工作区)。
> 关联文档:`docs/locomotion-feasibility.md`(方案C 提取/双槽位)、`docs/mimic-skill-feasibility.md`(端上直接写骨骼先例)、`docs/multi-dance-feasibility.md`(点播路由)、`docs/project-guide.md`(§5.2 技术债)。

---

## 0. 结论速览

| 问题 | 答案 |
|---|---|
| 能不能成立 | **能,且不需要赌任何单一路线**。"任意动作"拆成三层需求(长尾覆盖 / 用户即时定义 / 实时连续),每层都有成熟工程路径,且**共享同一条主干**:一切来源汇聚成 `.vrma` → 现有播放链路零改动消费 |
| 一句话主路线 | **四条腿接力**:A 资产工厂扩容(量大管饱)→ B 程序化姿态 DSL(端上实时任意姿势)→ C 视频动捕(所见即所得入库)→ D 文本生成(长尾点播)。A 是地基(工厂与质检先立起来,B/C/D 的产物才有归宿) |
| 关键捷径 | ①FBX→VRMA 转换器+筛选脚本已在手(`fbx2vrma-converter/` + `curate_vrma.py`,现库 346 个全是它产的);②端上"直接写骨骼"有三个生产级先例(视线/呼吸/模仿),程序化路线零框架改动;③目录全量扫描自动注册,新动作文件进包即进 LLM 词表(`LlmActionCatalog.kt:57`);④外置动画开关+`getExternalFilesDir` 通道还在(`MainActivity.kt:798`),生成结果 adb push 即生效,免重打包 |
| 瓶颈不在哪 | **不在播放器**(双槽位+mask+提取已是分层合成器)、**不在 LLM 链路**(`<act:>` 协议+语义候选+点播白名单全通)、**不在格式转换**(fbx2vrma 已量产 309 个)。瓶颈在"内容从哪来"和"目录怎么不爆炸" |
| 最大风险 | ①文本生成模型权重普遍由 HumanML3D(CC-BY-NC)系数据训练,**商用分发受限**;②动作目录 346→1000+ 与提示词空间的矛盾,需要目录治理先行 |
| 本篇判死项 | 端上实时神经动作生成(模型百 MB 级+移动端生态缺失+首延迟秒级,性价比被 B 路线碾压);物理仿真驱动全身(工程黑洞,无必要) |

---

## 1. 问题定义:"任意动作"到底指什么

用户原话是"固定动作太少了,想让虚拟人做出任意动作"。拆成三层需求,各对应不同技术路线,不要混为一谈:

| 层 | 场景示例 | 内容何时存在 | 对应路线 |
|---|---|---|---|
| **L1 长尾覆盖** | "他做了个机械舞""扶了扶眼镜" | 请求前就存在,只是库没收录 | A(资产工厂) |
| **L2 用户即时定义** | "照我这个视频跳一遍""做一个后空翻" | 请求时才产生,内容个性化 | C(视频动捕)、D(文本生成) |
| **L3 实时连续任意** | 聊天中随语义即兴做出指向/摊手/耸肩组合 | 无法枚举,逐轮不同 | B(程序化姿态)+ D 远期(语音驱动手势) |

**范围钉死(本篇不做)**:手指精细动作(数据源稀缺,收益低);多人接触动作(拥抱/搏斗,单骨架模型表达不了);人-物交互操持(端茶搬椅,需要物体重定向,另立项);物理仿真驱动(布料/受击反应);口型与表情(已有独立系统,`VrmExpressionManager` + `<emo:>` 协议,不在本篇范围)。

---

## 2. 现状盘点(证据)

### 2.1 播放运行时:已经是"分层合成器",不是"单文件播放器"

这是整个计划可行的底层前提——任何来源的动作,只要变成 VRMA,立刻获得既有的一切能力:

- **双槽位+crossfade+上半身 mask**:基座槽(idle/步态)+叠加槽带 `boneMask` 正交混合(`VrmaAnimationEngine.kt:50`,mask 集合 :67,`play(loop, speed, fadeSeconds, boneMask, extractor)` :287,混合写入 :582-647)。**意味着新手势可以叠在走路/待机上**。
- **hips 位移语义**:delta-from-rest 平移+身高比缩放(`writeTranslationSample` :694-734);方案C 提取器剥离烘焙前向位移由引擎积分(`LocomotionExtractor.kt:34`,引擎 `consumeExtractedForwardDelta` :403,`AvatarRuntime.kt:870` 集成)——**动作库扩容时位移类素材有现成的驯化手段**。
- **idle 槽自动接管**(:252):一次性动作播完无缝回待机,新入库动作天然获得"播完不僵住"。
- **解析缓存**:`stage/internal/VrmaClipCache.kt:33` 源路径级记忆化+负缓存,多虚拟人共享。
- **表情轨道已解析未消费**:`VrmaParser.kt:350-354` 建了 `ExpressionTrack`,但引擎从不读它(project-guide.md:341 已记为技术债)——**修复后,带表情的动捕/生成产物即可完整还原**。
- **程序化叠层先例( B 路线的可行性背书)**:`VrmLookAtEngine`(视线)、`VrmBreathEngine`(呼吸)、`VrmPoseMimicEngine`+`PoseMimicSolver`(摄像头模仿,**绝对驱动幂等、one-shot 期间让位、退场缓动**——这套范式就是 B 要复用的骨架)。choreoCallback 固定栈序"动画→hipsDrag→呼吸→视线→commit"(`SoulLinkRenderer` :280-379),新引擎照位插入即可。

### 2.2 目录与 LLM 链路:新文件自动获得"被 AI 挑选"的能力

- 目录**全量扫描生成**,零硬编码:`buildLlmActionCatalog`(`LlmActionCatalog.kt:57`)递归扫 `assets/animations`(app/MainActivity.kt:811),文件名→snake_case tag(`sanitizeActionTag` :19),中文别名表 `CHINESE_NAME_TAGS` :31,分类=子文件夹名。
- **行内协议** `<act:tag[:intensity]>`(`TagCue.kt:26`),按轮语义候选收敛(`ActionCandidateSelector`),未知 tag 客户端拒绝并回报——量大了不会失控。
- **技能通道可绕过目录播任意文件**:`GestureDriver.playFile`(`GestureDriver.kt:63`)——D 路线的点播入口现成。
- **点播路由先例**:规则→LLM→双向白名单校验→拒绝降级(`DanceRequestRouter.kt:63`,白名单 :86-117)——generated 动作的白名单治理照抄这个模式。
- **外置动画通道仍在**:`useExternalAnimations` 开关(MainActivity.kt:236/:418)+ `externalAnimationsRoot = getExternalFilesDir(null)`(:798)。**adb push 进该目录,开关一开即进目录,不重打包**。

### 2.3 资产与工具链:转换管线是量产过的,不是纸上设计

- 现库:**346 个 .vrma / 48MB**,9 类(`01_待机与站姿`(65)…`08_舞蹈与表演`(82))+`11/12` 技能目录,根目录另有 ~28 个 Mixamo 英文名件(`app/src/main/assets/animations/`)。
- **源头管线**:Mixamo FBX 1510 个 → `fbx2vrma-converter`(TK-256 的 Node.js 转换器,内含 FBX2glTF;**clone 在 repo 外 `/home/neethu/projects/fbx2vrma-converter/`,尚未收编进 tools/**)→ `curate_vrma.py` 白名单筛选(出用途/时长/根位移清单)→ `VRMA_Selected_Categorized/` 445 个 → 除位移类外 309 入包(归档 `ai-layer-handoff-2026-10.md:134`)。
- **质检先例**:`tools/walk-calib/calib.js` 对 VRMA 做 FK 脚底离地检测标定步速——**vrma_lint 的脚滑指标可以直接借这套 FK 数学**。
- 本地已有 1510 个 Mixamo 源件存量,不怕 Adobe 关停服务。

---

## 3. 路线全景与选型

| 路线 | 覆盖层 | 实时性 | 观感确定性 | 增量成本 | 法律风险 | 关键依赖 |
|---|---|---|---|---|---|---|
| **A 资产工厂扩容** | L1 | 播放即达 | 高(人工筛选) | 极低(管线已有) | 低(Mixamo 免费可用;CMU 公有领域) | 筛选人工时 |
| **B 程序化姿态 DSL** | L3 | **端上实时** | 高(参数限幅) | 低(复用 mimic 范式) | 无 | 无新依赖 |
| **C 视频→动捕→VRMA** | L2 | 离线分钟级 | 中(脚滑/穿模需质检) | 中(PC 管线) | 中(SMPL 模型 research license) | GPU 一次配置 |
| **D 文本→动作生成** | L1/L2 | 服务端 5~15s | 中低(脚滑普遍,需后处理) | 中高(部署服务) | **高**(HumanML3D 系 CC-BY-NC) | GPU 服务器 |
| E 语音驱动手势(远期) | L3 | 服务端流式 | 中 | 高 | 同 D | D 的基建 |

**选型结论:组合拳,四期接力,不是单选。** 理由:三层需求没有一条路线能全覆盖;而四条路线的产物汇聚到同一终点(VRMA 入库/入通道),**公共基建(A 期建的工厂+lint+目录治理)被 C/D 全额复用**,先建地基后盖楼。

---

## 4. 各路线详解

### 4.A 资产工厂扩容(P0)

**目标**:库从 346 → 800~1000+,并让"进一个新动作"变成 10 分钟的标准化操作。

- **存量筛选**:本地 1510 个 Mixamo FBX 已有,扩展 `curate_vrma.py` 的白名单与分类(当前只筛了 445 个),重点补:`07_日常互动`、`08_舞蹈与表演`、坐姿交互、情绪微动作。英文原名保留语义化 snake_case 重命名(**tag 即 prompt**,命名约定"动作英文名即其含义"必须守住)。
- **位移类驯化**:Mixamo 非 In-Place 件默认排除的教训(02 类走出画面,归档 :580)——改为**过 `LocomotionExtractor` 提取**后入"点播通道"(不进 `<act:>` 词表,避免聊天中走出画面),舞蹈类收益最大。
- **新增免费源**:CMU 图形学动捕库(公有领域,~2500 段 BVH,可转 FBX 汇入同一管线)补写实动作;其他来源一律走"FBX 汇聚点"。
- **入库规范**(写进 `tools/vrma-factory/README`):文件名=英文 snake_case 语义名;中文名登记 `CHINESE_NAME_TAGS`;分类子目录;时长/是否循环在 curate 清单里标注;静态短姿势(<0.5s)不入循环。
- **外置库通道**:生成/筛选产物优先走 `getExternalFilesDir` 外置通道(免重打包),稳定后择优入 assets;**实测外置目录规模上限与首启扫描耗时**。

### 4.B 程序化姿态 DSL(P1)——端上实时"任意姿势"

**表达力边界先说清**:能做任意*姿势*(静态)与简单*往复手势*(挥手/点头/鼓掌),做不了复杂时空轨迹(后空翻/舞蹈)——那是 C/D 的活。但它**零延迟、零依赖、白名单安全**,覆盖 L3 里 80% 的聊天手势。

- **设计原则:参数化模板优先,原始关节 JSON 只进 debug 通道**。理由:①token 成本(模板名 vs 数百字节 JSON);②观感可控(模板经过调参,LLM 手写关节角大概率抽搐);③安全(限幅表锁死);④协议先例(行内标签可靠性改造的"语义动作白名单+动态候选"同款哲学)。
- **模板库首批 ~10 个**:`point_left/right`(指向)、`arms_crossed`(抱臂)、`hands_on_hips`(叉腰)、`think`(托腮)、`bow`(鞠躬,深浅参数)、`shrug`(摊手耸肩)、`clap`(鼓掌,N 次)、`wave`(挥手参数化,与 VRMA 版互备)、`fist_pump`、`look_around`。每个模板 ≈ 若干关节目标四元数+缓动+时长,数据 ~30 行。
- **引擎** `VrmParametricPoseEngine`:镜像 `VrmPoseMimicEngine` 范式——绝对驱动幂等(无需 strip 记账)、入场 nlerp 缓动、`isOneShotActive()` 期间让位、退场回 idle、所有权仲裁"最后写入者赢"。新增的只是"目标来自模板表而不是摄像头"。**与上半身 mask 天然正交**:走路也能指向。
- **LLM 侧**:词表极小,追加进既有协议动作段(`<pose:bow:deep>` 同款语法,或复用 `<act:>` 挂虚拟 tag 由 GestureDriver 分流到模板引擎)。
- **顺带修 expressionTracks 消费**(`VrmaParser` 已解析,补引擎采样→`VrmExpressionManager`):C/D 产物自带表情轨道,不修则静默丢失。

### 4.C 视频→动捕→VRMA(P2)——"所见即所得"入库

**目标**:用户拍一段视频(自己跳舞/喜剧动作/体育动作),分钟级变成虚拟人能播的 VRMA。

- **开源链路**(PC 端,一次配好反复用):
  单目视频 → **GVHMR**(zju3dv,CVPR 2025,世界系重力对齐,单卡速度快)或 **WHAM**(MPI)→ SMPL 参数 → `smpl2bvh` → **Blender headless 烘 FBX**(bvh 导入→骨架→FBX 导出,`blender --background --python`)→ **既有 `fbx2vrma-converter`** → `vrma_lint` → 外置库。
  **所有格式汇聚到 FBX 是刻意设计**:复用已量产的转换器,不为 BVH 写第二条 VRMA 生成路径。
- **商用现成替代**(不想配环境时):Rokoko Vision(免费单视频)、DeepMotion、Plask、QuickMagic(直接出 FBX/BVH)——作为"手动通道"写进文档即可,不写代码。
- **采集规范**(给用户的 prompt 模板):全身入画、纯色背景、固定机位、单人、避免遮挡。
- **质检=vrma_lint(P0 建,C/D 共用)**:NaN/关节角越限/脚底滑移(FK 法,借 `walk-calib/calib.js` 数学)/hips 静置高度异常/T-pose 偏差/时长。产物过 lint 才允许入库,外加真机截图目检。
- **交付形态**:`tools/vrma-factory/` 脚本族 + 可选 Colab notebook(无 GPU 用户);**不做端上动捕**(现有 `VrmPoseMimicEngine` 已覆盖端上实时模仿玩法,资产化交给 PC)。
- **法律**:SMPL 模型文件本身是 research-only license——开源链路默认**限演示与个人使用**,商用需换商用动捕服务,§8 拍板。

### 4.D 文本→动作生成(P3)——长尾点播

**目标**:"给我做一个后空翻""来段机械舞" → 服务端生成 → 真机播放并入库。

- **模型选型**(2026-10 现状):
  - **MoMask**(CVPR 2024,~3.8k star,社区事实基线):masked modeling,质量稳,**内置 BVH 导出**,首选。
  - **MotionStreamer**(ICCV 2025,zju3dv):扩散自回归**流式**生成,面向 avatar 实时场景——本期只取其离线用法,流式是 §4.E 的技术储备。
  - **MMM**:0.081s/句,速度极快,备选。
- **两种用法,基建同一套**:
  1. **离线批量(优先做)**:用提示词表批量生成→lint→人工筛选→入库。**零运行时改动**,库规模再翻倍的另一条腿,还能定向补缺("查漏:库里有 X 吗?没有就生成 20 个变体")。
  2. **在线点播**:LLM 工具 `generate_motion(text)`(照 `AgentToolCatalog` 的 `move_to` 等先例)→ HTTP 服务(MoMask→BVH→FBX→VRMA 全链)→ 返回 job → 完成事件续播(复用 `enqueueAct` 插幕模式)+ 存入 generated 白名单目录。
- **服务形态**:本地 PC/局域网 GPU 机,Colab 兜底;**不做端上**(判死理由见 §0)。
- **时延预算**:生成 1~3s + BVH→VRMA 转换 1~2s + 推送 1~3s ≈ **端到端 5~15s**。适合"表演类请求",不适合聊天中随手手势(那是 B 的地盘)——工具描述里要写清引导。
- **质量预期管理**:生成动作普遍脚滑/重心漂移,lint+脚部锁定后处理保底;观感定位"能看、好玩",不是商用级动画。文档与工具描述里明示。
- **法律红线**:MoMask 等主流模型权重由 HumanML3D(CC-BY-NC 4.0)系数据训练,**生成物商用分发存疑**;默认仅演示/个人使用,商用走可商用数据训的模型或商用 API(DeepMotion 等)。§8 拍板。

### 4.E 远期观测(不在本计划内开工)

- **语音驱动手势**(EDGE/EMAGE/ZeroEGGS 系):让说话时的手势自动匹配语义与韵律——与 L3 最贴的终态,但依赖常驻生成服务与音频-动作对齐,等 D 的基建稳定后评估。
- **流式生成实时化**(MotionStreamer 方向):模型侧正朝"文本流→动作流"演进,保持半年一观测。

---

## 5. 公共基建(随期落地,全路线共用)

| 基建 | 期 | 内容 | 依据/先例 |
|---|---|---|---|
| **vrma_lint 质检器** | P0 | Python 读 glTF+`VRMC_vrm_animation`(复用 springbone-sim 的解析经验),检查 NaN/关节越限/脚滑(FK)/hips 静置高度/时长,输出报告表 | `tools/walk-calib/calib.js` FK 脚底检测 |
| **转换器收编** | P0 | `fbx2vrma-converter` 从 repo 外 clone 迁入 `tools/vrma-factory/`,固化 FBX2glTF 二进制版本 | 归档 :134 现状 |
| **目录规模治理** | P0 | 协议块只发"策展集(≤200 tag)+分类概览",全量走按轮语义候选(`ActionCandidateSelector` 已有)与点播白名单;**协议块预算 <20K chars**(现 346 个≈12K) | 归档 :580 目录置于提示词最末的教训 |
| **点播通道统一** | P1 | 通用工具 `play_animation(tag_or_path)`:白名单=外置库+generated 目录,复用 `DanceRequestRouter` 双向校验模式;`GestureDriver.playFile` 已具备底层 | `DanceRequestRouter.kt:63` |
| **expressionTracks 消费** | P1 | 引擎按关键帧采样表情轨道喂 `VrmExpressionManager`;注意与 `<emo:>` 情绪通道的仲裁(动画期让位规则同 mimic) | `VrmaParser.kt:350-354`;project-guide.md:341 |
| **命名与检索约定** | P0 | generated 产物用"生成提示词=文件名"约定,反向可检索;中文别名登记 `CHINESE_NAME_TAGS` | `LlmActionCatalog.kt:31` |

---

## 6. 分期路线图与工作量

### P0 资产工厂与通道(~2~3 人日)

内容:转换器收编入 tools/;curate 脚本扩展+存量 1510 个二次筛选入库(目标 800+);vrma_lint;位移类过 extractor 入点播通道;目录规模治理;入库规范文档。

验收:
- [ ] 新动作入库全流程(转换→lint→push→目录出现→LLM 能选中)≤10 分钟,断网不阻塞
- [ ] `ai_cmd` 列目录确认新 tag 可见;对话中"做个机械舞"命中新入库动作
- [ ] lint 拦截一个故意损坏的样例(NaN/越限各一)
- [ ] 协议块 chars 数不超预算(日志 `prompt: persona=Nch protocol=Nch`)
- [ ] 位移类动作经点播通道播放不出画(方案C 生效)

### P1 程序化姿态 DSL + 表情轨道(~2~3 人日)

内容:`VrmParametricPoseEngine`+限幅表;首批 10 个姿态模板;`<pose:>` 协议接入与候选收敛;expressionTracks 消费修复;`play_animation` 通用点播工具。

验收:
- [ ] 对话"抱臂想一下→指向左边"全程无 VRMA 播放、延迟不可感知,与走路/待机共存不抽搐
- [ ] one-shot VRMA 期间姿态请求让位,播完恢复
- [ ] 自带表情轨道的 VRMA 播放时表情同步(用 Blender 手造一个带表情轨道的样例)
- [ ] 单测:模板参数限幅/让位仲裁/协议提取
- [ ] 真机:10 模板逐个目检+限幅越界单测

### P2 视频动捕工厂(~3~5 人日)

内容:`tools/vrma-factory/video2vrma`(GVHMR/WHAM+smpl2bvh+Blender headless+fbx2vrma 全链脚本);采集规范文档;Colab notebook;lint 接入全链。

验收:
- [ ] 一段 30s 手机自拍舞蹈视频→lint 通过的 VRMA→真机可播,脚滑在可接受范围
- [ ] 全链端到端 ≤5 分钟(不含环境配置)
- [ ] 三种失败输入(多人/遮挡/剧烈运动)有明确报错不产坏文件

### P3 文本生成服务(~5~8 人日)

内容:MoMask 部署(本地 GPU)+生成→BVH→FBX→VRMA HTTP 服务;离线批量提示词表+首批批量入库;`generate_motion` 异步工具+job 事件+插幕续播+generated 白名单。

验收:
- [ ] 对话"来个后空翻"→≤15s 开始播放→动作入 generated 目录可复播
- [ ] 批量生成 100 个提示词→lint 报告→人工筛选入库率有数据
- [ ] 服务不可达时工具优雅降级(报错文案+回落库内相近动作)
- [ ] 法律标注:generated 目录与文档注明用途限制

**总计**:四期 ≈ 12~19 人日。P0+P1 纯端上+仓库内,零外部依赖,先做;P2/P3 依赖一台 GPU 机器,排后。

---

## 7. 风险与坑预判(实现时并入 ai-layer-handoff 坑清单)

1. **协议块膨胀**:346→1000+ 直接全发会顶爆小上下文模型。治理先行(P0):策展集+按轮候选+点播白名单三层。
2. **位移类走出画面**:02 类的历史教训(归档 :580)。generated/mocap 产物**默认带世界系 hips 位移**,必须过 extractor 或钳制,否则角色跑出镜头。
3. **转换器单点**:`fbx2vrma-converter` 目前只在 repo 外,机器一换即失传;FBX2glTF 二进制平台绑定,固化版本与平台说明。
4. **中文名/tag 语义**:`sanitizeActionTag` 只认 ASCII 时中文文件名会静默跳过(`LlmActionCatalog` 别名表先例);协议约定"动作英文名即其含义"必须守住,违例动作 LLM 挑不中。
5. **Mixamo 依赖**:服务随时可能关停——本地 1510 个存量是护城河,**先全量归档再谈筛选**。
6. **SMPL/HumanML3D license**:SMPL 模型文件 research-only;主流生成权重 CC-BY-NC。产物商用分发前必须过 §8 的拍板结论。
7. **生成/动捕质量**:"能动"≠"能看"。脚滑、重心漂、手指飘是三类通病;lint 指标+脚部锁定后处理+人工筛选三道闸,并在产品文案管理预期。
8. **目录噪声反噬**:量大会拉低 LLM 挑选准确率(相似动作互相干扰)。按轮语义候选 + 分类描述要精写,宁缺毋滥入策展集。
9. **外置库扫描**:首启递归扫描耗时随规模增长,注意别在主线程(既有 `listAssetsRecursive` 同款约束);`getExternalFilesDir` 在 Android 11+ 无需存储权限,但用户清理数据即清空——generated 产物要有"重新拉取/重新生成"路径。
10. **多写入者仲裁**:B 路线引擎新增第四个骨骼写入者,所有权仲裁必须复用"最后写入者赢+one-shot 让位"既有规则,别发明新框架(mimic 文档 §6 的教训)。

---

## 8. 开放问题(需拍板,均有默认值)

1. **商用分发底线?** 默认:generated/mocap 产物仅限演示与个人使用,目录与文档标注来源;assets 内 Mixamo 件维持现状(已在包内,先例既成)。
2. **P2/P3 的 GPU 机器?** 默认:本地 PC(需确认显卡),Colab 兜底;无 GPU 时 P3 降级为"离线批量在 Colab 跑,在线点播不做"。
3. **策展集规模上限?** 默认 ≤200 tag 进协议块,其余走候选/点播。
4. **B 首批模板清单确认?** 默认 §4.B 的 10 个,可按用户高频场景增删。

---

## 9. 参考资料

- MoMask(CVPR 2024,内置 BVH 导出):https://github.com/EricGuo5513/momask-codes
- MotionStreamer(ICCV 2025,流式动作生成):https://github.com/zju3dv/MotionStreamer
- awesome-text-to-motion(路线全集):https://github.com/Zilize/awesome-text-to-motion
- MMM(0.081s/句):https://www.ekkasit.com/MMM-page
- GVHMR(单目视频→世界系动捕):https://github.com/zju3dv/GVHMR
- WHAM:https://wham.is.tue.mpg.de
- EasyMocap:https://github.com/zju3dv/EasyMocap
- bvh2vrma-7lang(BVH→VRMA 网页版,手动通道备用):https://github.com/r800zz/bvh2vrma-7lang
- fbx2vrma-converter(本仓库现役转换器):https://github.com/TK-256/fbx2vrma-converter
- Rokoko Vision(免费单视频动捕):https://uthana.com/resources/best-ai-motion-capture-tools (对比文,含 Plask/QuickMagic)
- CMU 图形学动捕库(公有领域 BVH):http://mocap.cs.cmu.edu
