# 道具系统(Prop/Activity):从"被动配饰"到"自主使用道具"落地设计

日期:2026-10-25 · 代码基线:`c5b2ce7`(自主生命体 P0 验收通过) · 地位:**P0 已落地:2026-10-25 真机验收 6/6 通过(落地记录见 §7);P1/P2 未开发**
一句话:让大模型和自主状态机能"决定做一件事"——「我看会书」一句话(或 FSM 作息自发)完成:走到阅读椅、坐下、手里变出一本书、翻开看 20 分钟;道具不再只是"给用户戴上看的挂件",而是**行为叙事的一部分**。

---

## 0. TL;DR

1. **渲染层几乎零新代码。** 配饰系统 P0~P2 已把最难的部分做完了:GLB 挂骨骼、动画绑定表自动显隐(`VrmaStarted`→出道具/`VrmaFinished`→收道具)、三层规格合并、per-model 校准、LLM `wear/remove_accessory` 工具。本设计**不重建任何渲染机制**,道具显隐继续走既有绑定表。
2. 缺的是**语义层**:现在"玩手机动画→出手机"是动画驱动的被动联动,大模型无法表达"我打算看会书"这种**意图**。新增一个**活动目录(Activity Catalog)**——每条活动 = 道具×动画×场景锚点×可选 VFX×可选台词的"行为配方",配一个编排执行器把配方落到既有积木上(就位链/动画链/绑定表/speak/VFX 全部现成)。
3. 两个自主来源共用同一套活动目录:**FSM 作息**(零 LLM,`AutonomyDirector` 选点时带出活动,「闲着没事看会书」)+ **LLM 决策**(新工具 `start_activity(id)`,用户说「你看会书吧别玩手机」或模型自己想)。
4. 场景大表 70 个(§5),其中 **15 个零采购、2 个有替代跑法**(现有道具×现有动画直接组合,P0 验收编排器);道具 11→45+ 件的采购/生成清单(§6);动画缺口 ~20 个且**优先从本地 445 个 VRMA 源库补**(§6.3)。
5. 工作量 ≈ **14 人日**:P0 编排器+零采购场景 4 天,P1 资产扩充第一波 5 天,P2 深度联动(VFX 组合技/场景道具/节日包/古风包)5 天。
6. **P0 已落地(2026-10-25,真机验收 6/6 通过)**:活动目录 16 条(15 零采购+看书替代跑法)上线,`start_activity/stop_activity` 与 FSM `DoActivity` 真机全过——发送到道具 7.3s≤8s、25min 放置窗 2 次自主带道具活动、对话打断道具不消失、stop 干净收场、穿戴叠加共存、fps 60 零崩溃;全仓 1354 单测全绿。明细与新坑见 §7 落地记录。

---

## 1. 现状与差距

### 1.1 已有的(全部真机验收过)

| 积木 | 位置 | 与道具系统的关系 |
| --- | --- | --- |
| 配饰挂载(挂骨骼/跟随动画/校准/碰撞体) | corelib `accessory/` 包(docs/accessory-feasibility.md §七) | **渲染层原样复用**,不改 |
| 动画绑定表(播动画自动出/收道具,loop 支持) | `assets/accessories/bindings.json` + `AccessoryAnimationBinder` | 道具显隐的**唯一通道**,只加条目 |
| LLM 工具 `wear/remove_accessory` | `AgentToolCatalog.kt:775/827`(id enum 白名单) | 保留(纯穿戴场景),新增活动工具与之并列 |
| VFX 五件套 `play/stop_effect` | `AgentToolCatalog.kt:75/148`,presets 12 个(magic_circle/confetti/rain/spark_burst…) | 活动的可选联动层 |
| 自主 FSM(5 态+作息表+仲裁) | orchestrator `autonomy/`(AutonomyDirector/AutonomyTypes),app `AutonomyRig.kt:109` | **道具自主决策的挂点**(§3) |
| 就位执行链(走过去→转向→坐/躺→起身) | app `InteractionCoordinator.kt:88` `runInteraction` | 活动"先走到地方"直接调它 |
| 场景交互点(dream_bedroom×3,16 点/场景) | `assets/scene/*.interactions.json` | 活动的位置锚点来源 |
| 免 LLM 开口 `speak()` + `<emo:>/<act:>/<cam:>` 标签 | `AvatarSession.kt:870` | 活动"边做边说"复用 |
| 资产管线 | Kenney CC0 已下 137 件(`tools/accessory-samples/`)、程序化生成器 `make-prop-glb.js`(麦克风/应援棒先例)、`validate-glb.js` 校验器 | 新道具零新工具链 |
| 动画库 | 内置 334 个/9 类;**repo 外还有 445 个 VRMA 源库未内置** | 场景表标注每个活动用哪个动画 |

### 1.2 差距(本设计要补的三样)

1. **意图层缺失**:绑定表的方向是「动画→道具」(播了什么动画,出什么道具);没有「意图→(动画+道具+位置)」的反向表达。大模型想表达"我要看书",只能说 `<act:>` 播个动画,不知道有书、更不会安排"坐到阅读椅上再看"。
2. **FSM 不产出道具行为**:自主状态机只有 IDLE_STAND/WANDER/LOOKING/SITTING/LYING 五个"姿势态",坐着就是干坐,没有"坐着干什么"的词汇。
3. **道具太少且无语义**:11 件资产没有分类、没有别名、没有"什么时候想用它"的标注;LLM 的世界里只有 11 个裸 id,无从决策。

### 1.3 概念升级:配饰 → 道具

| | 配饰(现状) | 道具(升级后) |
| --- | --- | --- |
| 本质 | 视觉挂件 | 行为的组成部分 |
| 触发 | 用户指令(`wear_accessory`)/动画绑定(被动) | **意图驱动**:LLM 工具或 FSM 作息 |
| 生命周期 | 戴上直到摘下/动画播完 | 随活动开始出现、活动结束(或被打断)收起 |
| 组合 | 单件 | 活动 = 多道具+动画+位置+VFX+台词的配方 |
| 语义 | 无(裸 id) | 分类/别名/描述/适用时段,供决策 |

**命名约定**:渲染层沿用 `accessory/` 包与 `accessories/` 资产目录不动(避免迁移);语义层新概念叫 **prop(道具)** 与 **activity(活动)**,新目录 `assets/activities/`。

---

## 2. 核心设计:活动目录(Activity Catalog)

### 2.1 schema(`assets/activities/activities.json`)

```json
{
  "schemaVersion": 1,
  "activities": [
    {
      "id": "desk_typing",
      "name": "用电脑",
      "nameEn": "typing at the desk",
      "desc": "坐到书桌前用笔记本电脑打字干活",
      "category": "work",
      "anchor": "desk_seat",
      "animation": "07_日常互动/Sitting At A Computer And Typing.vrma",
      "props": ["laptop"],
      "bindingOffset": { "position": [0, -0.25, 0.05], "note": "坐姿放腿上,挂右手但下移" },
      "vfx": null,
      "loop": true,
      "minDwellMs": 180000,
      "hourRange": [9, 23],
      "says": ["这个需求今天能写完。", "嗯…这里得重构一下。"]
    }
  ]
}
```

字段说明:

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | ✓ | 工具白名单 enum 与 FSM 引用键;全局唯一 |
| `name`/`nameEn`/`desc` | ✓ | 注入 LLM prompt 的一句话描述(决策依据) |
| `category` | ✓ | `daily/work/food/play/sport/social/fantasy/festival`,用于 prompt 分层与作息表 |
| `anchor` | – | 交互点 id(在 `*.interactions.json` 内);缺省=原地做(拉伸/打电话) |
| `animation` | ✓ | VRMA 类别相对路径(绑定表同款键,binder 已统一剥 `animations/` 前缀) |
| `props` | – | 道具 id 列表;**道具显隐仍走 bindings.json**(animation→accessory 映射),此处仅声明语义归属与校验 |
| `bindingOffset` | – | 可选:本动画下的绑定偏移覆盖(见 §7 风险 3) |
| `vfx` | – | 可选:VFX preset id,活动开始时 `playEffect` |
| `loop` | ✓ | true=循环持续态(看书/打字,直到 stop 或被仲裁打断);false=一次性(举杯/施法) |
| `minDwellMs` | – | FSM 进入该活动的最短停留 |
| `hourRange` | – | 作息窗口(作息表之外 FSM 不自主选它,LLM/用户指令不受限) |
| `says` | – | 可选台词池:活动开始/期间由 `speak()` 随机低频说一句(可带 `<emo:>/<act:>` 标签) |

### 2.2 执行链(全部复用,唯一新代码是编排器)

```
start_activity("desk_typing")
  └─ ActivityExecutor(app 层,与 AutonomyRig 同侧,过同一把执行互斥锁)
       1. anchor? ──▶ InteractionCoordinator.runInteraction(走到接近点→转向→坐下→起身链现成)
       2. animation ▶ 现有播放链(同 play_animation 路径)
       3.            ▶ VrmaStarted → AccessoryAnimationBinder 查 bindings.json → 道具自动出现 ★零新代码
       4. vfx?    ──▶ playEffect(preset id)
       5. says?   ──▶ speak()(不进对话历史,带标签可边说边做)
       6. FSM     ──▶ AutonomyDirector 记入"外部活动态",锚点+活动为当前状态(不回弹,对齐
                      autonomy-feasibility.md §4.3 优先级 3:外部指令=状态转移)
stop_activity()
  └─ 停动画(若 loop)→ VrmaFinished/顶替路径 → 道具自动收起 ★零新代码
  └─ 坐/躺中先起身(1.2s 现成)→ FSM 回 IDLE_STAND
```

关键正交性:**活动目录不管道具显隐,绑定表管**。活动只负责"播哪个动画、去哪个位置、说什么、配什么特效";道具作为动画的影子自动出没——这与配饰 P1 的设计哲学一脉相承(LLM 无需知道配饰存在),升级为「编排器无需知道道具存在」。

### 2.3 LLM 工具(对齐 `wearAccessory` 形态)

```kotlin
// AgentToolCatalog 新增
fun startActivity(id: String)     // id enum 白名单:注册时由活动目录生成+执行时重验(双保险,同 wearAccessory)
fun stopActivity()                // 无参;不在活动中时返回"本来就没在做"非 error(同 removeAccessory 惯例)
// 缝失败枚举 ActivityFailure { UNKNOWN_ACTIVITY, BUSY, NO_MODEL, FAILED }
```

- prompt 注入:活动目录摘要(id+name+desc+category,66 条 ≈1.5k tok)随工具 schema 注入,模式同 `interact_with` 注入交互点表(「orchestrator 不感知场景表」惯例:目录由 app 层投影注入,orchestrator 只见 enum)。P2 若 token 吃紧再分层(高频 12 条全量+其余按 category 摘要)。
- 与 `wear_accessory` 的分工:**活动=做事**(自带编排),**穿戴=装扮**(纯显隐)。「戴上帽子」走 wear;「窝在椅子上看会儿书」走 start_activity。两者可叠加(活动执行中仍可 wear 帽子)。

### 2.4 持续态与打断语义(核心细节)

- **loop 活动 = base 槽循环剪辑**:看书 20 分钟 = 循环剪辑一直播。用户突然说话 → FSM 冻结但**活动不中断**(她被你叫住,书还在手里)——比"收起道具愣住"自然得多。仲裁表照 autonomy-feasibility §4.3,唯一细化:**冻结只停移动与决策,不停 loop 动画与道具**。
- **说话手势与 loop 共存**:`<act:>` 手势与坐姿循环的关系沿用坐躺定姿的既有行为(坐姿循环期间说话手势的上半身表现,autonomy P0 真机已验收过);若手势以 base 顶替导致 loop 短暂让位,binder 的「同配饰不摘不戴+250ms 延迟隐藏」已兜住闪烁(P1 连播不闪烁实测)。**真机验证点**,见 §8 风险 3。
- **一次性活动**(举杯/施法/递花):one-shot 播完 → `VrmaFinished` → 道具自动收,与现有玩手机语义完全一致,零新语义。
- **stop 与打断**:stop_activity/技能/舞蹈顶替都走 `VrmaFinished`(edge-trigger 现成)→ 道具消失;loop 被顶替不发 Finished 的边界 P1 已在 binder 单测锁死(以"当前激活剪辑"为准)。

---

## 3. 与自主生命体 FSM 的集成

### 3.1 设计:活动附着在"点"上,FSM 零新增状态

不新增 `ACTIVITY` 状态(五态动词集保持干净);**`AutonomySpot` 加一个可选 `activityId` 字段**,由 app 侧组装 spots 时从活动目录 join 注入(orchestrator 的 `AutonomyTypes.kt:31` 只加一个可空字段,FSM 决策逻辑不动):

```kotlin
data class AutonomySpot(
    ..., 
    val activityId: String? = null,   // 新增:该点可附带的活动(reading_seat→"reading")
)
```

- **决策流**:FSM 依作息表选中 SITTING → `spotsFor(SITTING)` 里带 activity 的点自然参与加权随机 → 选中 `reading_seat` 时 directive 从 `GoTo` 变为 `GoTo+DoActivity(spot.activityId)`(`AutonomyDirective` 加一个子类)→ app 执行器先 `runInteraction` 就位再启动活动配方。
- **作息表扩展**:`autonomy.schedule.json` 现有状态权重不动,新增可选 `activityWeights`(category→倍率):深夜 food/daily×0.2、上午 work×2、晚间 play×1.5;`hourRange` 在选点层先行过滤。
- **效果**:「自主生命体」从"在房间里移动的姿势状态机"升级为"**有作息生活的行为体**"——上午坐书桌打字、午后阅读椅看书、晚上窝地毯抱枕看电视、深夜躺床上(有时刷手机)。全部零 LLM 成本。

### 3.2 两级自主的分工

| | FSM(默认,零 LLM) | LLM(对话驱动) |
| --- | --- | --- |
| 触发 | 作息表+加权随机 | 用户话语或模型自发决策 |
| 输入 | 时段/位置/禁忌(不连同点/距离衰减) | 对话上下文+记忆+活动目录描述 |
| 通道 | `GoTo+DoActivity` directive | `start_activity` 工具调用 |
| 例子 | 14:00 加权落在 reading_seat | 「你最近看的什么书?」→ 边回答边 start_activity("reading") |
| 开关 | autonomy 总开关(现成) | accessoryTools 同款独立开关(默认开) |

LLM 自发决策(没人说话也想换活动)走 autonomy-feasibility §4.2 的"可选 LLM 增强"缝,默认关,P2 评估。

---

## 4. 新增/改动清单(全量)

| 层 | 改动 | 规模 |
| --- | --- | --- |
| corelib | **零改动**。绑定表/binder/挂载/校准全部现成;唯一候选:bindings.json 条目加可选 `offset` 覆盖字段(解析处 ~10 行) | ~10 行 |
| orchestrator | `ActivityCatalog`(解析 activities.json+enum 生成)、`startActivity/stopActivity` 工具工厂、`AutonomySpot.activityId` 字段+`DoActivity` directive、活动失败枚举 | ~350 行,目录解析/白名单/枚举全 JVM 可测 |
| app | `ActivityExecutor`(编排:就位→动画→vfx→speak→FSM 同步,~150 行)、spots 组装 join activityId、执行互斥排队接入、设置开关(activityTools)、ai_cmd 观测(`activity list/state`) | ~300 行 |
| assets | `activities/activities.json`(70 条)、bindings.json 补条目、道具 GLB 11→45+(§6)、`LICENSES.md` | 资产为主 |

---

## 5. 场景大表(66 个)

标注约定:道具列 ✓=已有资产,🆕=需新增(§6);动画列 ✓=内置库现成(给路径),🆕=待补采(§6.3);期=P0(零采购)/P1/P2。**P0 行加粗**——现有资产直接组合,验收编排器用。

### A. 卧室日常(FSM 作息主场景,dream_bedroom 系列)

| # | 场景 | 触发语境 | 道具 | 挂点 | 动画 | 期 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **窝阅读椅看书** | FSM 午后加权;「看会儿书吧」 | 单本书 🆕(P0 替代:一摞书 books ✓) | leftHand | 坐姿阅读 🆕(P0 替代:06/Seated Idle With Hands On A Table ✓ 捧书) | **P0**/P1 |
| 2 | **书桌打字** | FSM 上午;「去忙你的吧」 | laptop ✓ | rightHand(腿上偏移) | 07/Sitting At A Computer And Typing ✓ | **P0** |
| 3 | **书桌写日记** | 「写会儿日记」 | 钢笔 🆕 | rightHand | 07/Male Writing While Seated ✓(P0 可空手写) | **P0** |
| 4 | **躺床上刷手机** | FSM 深夜;「睡前刷会儿手机」 | phone ✓ | rightHand | 躺姿玩手机 🆕 | P1 |
| 5 | **坐床边喝饮料** | 「喝点东西歇会」 | 易拉罐 🆕 | rightHand | 07/Having A Seat And Drinking A Canned Beverage ✓ | P1 |
| 6 | **站着喝水** | 「有点渴」 | 水瓶 🆕 | rightHand | 07/Male Drinking ✓ | P1 |
| 7 | **地毯抱枕看电视** | FSM 晚间;「陪我看电视」 | pillow ✓ | leftHand | 01/Holding An Object Idle ✓ @rug_watch_tv | **P0** |
| 8 | **床边抱泰迪熊** | 情绪低落/夜间 | bear ✓ | leftHand | 01/Holding An Object Idle ✓ @bed_sit | **P0** |
| 9 | **窗边端热饮看风景** | FSM 清晨;「看看窗外」 | 马克杯 🆕 | rightHand | 01/Holding An Object Idle ✓ @window_view | P1 |
| 10 | **撸泰迪熊** | 「撸撸你的熊」 | bear ✓ | 场景摆放 | 07/Petting An Animal ✓ | **P0** |
| 11 | **游戏机前打手柄** | 「打会儿游戏」 | 手柄 🆕 | 双手 | 打手柄 🆕(P0 先站 game_approach 看) | P1 |
| 12 | **看电视嗑瓜子** | FSM 晚间 | 零食碗 🆕 | leftHand | 嗑瓜子 🆕 | P2 |
| 13 | **戴眼罩睡觉** | FSM 深夜 LYING 增强 | 眼罩 🆕 | head | bed_lie 躺姿剪辑 ✓(场景自带) | P1 |
| 14 | **抱纸箱收拾房间** | 「把箱子搬过去」 | box ✓ | 双手 | 07/Carrying A Box ✓、Holding An Object While Walking ✓ | **P0** |
| 15 | **镜前梳妆** | 早晨作息 | 梳子 🆕 | rightHand | 梳头 🆕 | P2 |

### B. 饮食

| # | 场景 | 触发语境 | 道具 | 挂点 | 动画 | 期 |
| --- | --- | --- | --- | --- | --- | --- |
| 16 | **吃泡面** | 「饿了来碗面」 | 泡面杯🆕+叉🆕 | 双手 | 吃面 🆕 | P2 |
| 17 | **喝奶茶** | 「点杯奶茶」 | 奶茶杯 🆕 | rightHand | 07/Holding An Object While Walking ✓ + Male Drinking ✓ 组合 | P1 |
| 18 | 吃冰淇淋 | 夏天话题 | 冰淇淋 🆕 | rightHand | 舔冰淇淋 🆕 | P2 |
| 19 | 吃苹果 | 健康话题 | 苹果 🆕 | rightHand | 吃苹果 🆕 | P2 |
| 20 | **倒饮料待客** | 「给我倒杯喝的」 | 水壶🆕+杯🆕 | 双手 | 07/Pouring And Serving Drinks ✓ | P1 |
| 21 | 举杯庆贺 | 庆祝对话 | 酒杯 🆕 | rightHand | 05/Pumping A Fist ✓(举手变体) | P1 |
| 22 | 茶几摆茶具 | 待客氛围 | 茶具组 🆕 | **世界坐标**(场景道具) | 无(静置) | P2 |
| 23 | 爆米花看电影 | 追剧话题 | 爆米花桶 🆕 | leftHand | 抓取吃 🆕 | P2 |
| 24 | 端盘子当服务生 | 角色扮演 | 托盘🆕+杯🆕 | leftHand | Holding An Object While Walking ✓ | P2 |

### C. 工作学习

| # | 场景 | 触发语境 | 道具 | 挂点 | 动画 | 期 |
| --- | --- | --- | --- | --- | --- | --- |
| 25 | **站着打电话** | 「接个电话」 | phone ✓ | rightHand | 07/Male Talking On The Cell Phone ✓(已绑定) | **P0** |
| 26 | **站着玩手机/走路刷机** | 「别老玩手机」 | phone ✓ | rightHand | 07/Standing Texting On Phone ✓(已绑定) | **P0** |
| 27 | **平板阅读** | 「用平板看看」 | phone ✓(先复用) | rightHand | 07/Female Using Touchscreen Tablet ✓(已绑定);平板 🆕 后换 | **P0** |
| 28 | 拿文件检查 | 「帮我看看这个」 | 写字板 🆕 | leftHand | 07/Pick Up Object And Examine ✓、Taking An Item And Examining It ✓ | P1 |
| 29 | 翻包找东西 | 「我钥匙呢」 | 手提包 🆕 | leftHand | 07/Reaching To Rummage Through Items ✓ | P1 |
| 30 | 翻抽屉找文件 | 办公扮演 | (场景抽屉) | – | 07/Searching The Top Drawer Of A File Cabinet ✓ | P2 |
| 31 | 白板前讲解 | 教学扮演 | 指示棒 🆕 | rightHand | 04/Pointing With Arm Bent ✓ | P2 |
| 32 | 夹板站立记录 | 秘书扮演 | 夹板🆕+笔🆕 | leftHand | 站立书写 🆕 | P2 |

### D. 娱乐表演

| # | 场景 | 触发语境 | 道具 | 挂点 | 动画 | 期 |
| --- | --- | --- | --- | --- | --- | --- |
| 33 | **唱歌/说唱** | 「唱一个」 | microphone ✓ | rightHand | 08/MC Rapping ✓、Singing While Walking ✓(已绑定) | **P0** |
| 34 | **抱吉他弹唱** | 「来段吉他」 | 吉他 🆕 | 双手 | 08/Playing A Guitar ✓ | P1 |
| 35 | 拉小提琴 | 音乐话题 | 小提琴🆕+弓🆕 | 双手 | 08/Playing A Short Piece On The Violin ✓ | P2 |
| 36 | 弹钢琴 | 音乐话题 | 钢琴(场景道具) | 世界坐标+坐姿 | 08/Playing Multiple Runs On A Piano ✓ | P2 |
| 37 | 弹贝斯 | 乐队话题 | 贝斯 🆕 | 双手 | 08/Playing The Bass Guitar ✓ | P2 |
| 38 | **戴耳机听歌摇摆** | 「戴上耳机听听歌」 | 耳机 🆕 | head | 08 舞蹈类摇摆 ✓(Shuffling 等) | P1 |
| 39 | **打碟放歌** | 「放点音乐」 | radio ✓ | rightHand | 08 任意舞蹈 ✓(道具静持+舞) | **P0** |
| 40 | 吹泡泡 | 童心话题 | 泡泡棒 🆕 | rightHand | 挥手类 ✓+泡泡 VFX 🆕 | P2 |
| 41 | 玩魔方 | 益智话题 | 魔方 🆕 | 双手 | 玩魔方 🆕 | P2 |
| 42 | 画画写生 | 文艺话题 | 画架🆕+笔🆕 | 场景+手 | 绘画 🆕 | P2 |
| 43 | 举灯牌应援 | 团播/应援 | lightstick ✓ | rightHand | 04/交流手势 ✓+lightstick_spark VFX ✓(已验收) | **P0** |

### E. 运动健身

| # | 场景 | 触发语境 | 道具 | 挂点 | 动画 | 期 |
| --- | --- | --- | --- | --- | --- | --- |
| 44 | **原地拉伸/伸懒腰** | FSM 久坐起身;「活动一下」 | 无 | – | 07/Stretching Arms ✓、Stretching Neck ✓ | **P0** |
| 45 | **瑜伽** | 「做做瑜伽」 | 瑜伽垫 🆕 | **世界地面** | 07/Reaching Hands Towards Toes ✓ | P1 |
| 46 | 哑铃举铁 | 健身话题 | 哑铃 🆕 | 双手 | 09/Transition From Idle To Fitness Idle ✓(循环) | P1 |
| 47 | 跳绳 | 有氧话题 | 跳绳 🆕 | 双手 | 跳绳 🆕 | P2 |
| 48 | 羽毛球 | 运动话题 | 球拍 🆕 | rightHand | 挥拍 🆕 | P2 |
| 49 | 呼啦圈 | 童趣话题 | 呼啦圈 🆕 | 腰(hips) | 呼啦圈 🆕 | P2 |
| 50 | 拍篮球 | 运动话题 | 篮球 🆕 | rightHand | 运球 🆕 | P2 |

### F. 情感社交

| # | 场景 | 触发语境 | 道具 | 挂点 | 动画 | 期 |
| --- | --- | --- | --- | --- | --- | --- |
| 51 | **鼓掌喝彩** | 用户分享好消息 | 无 | – | 04/Standing Clap 系列 ✓(坐姿 3 款也有) | **P0** |
| 52 | 送花 | 表白/感谢对话 | 花束 🆕 | rightHand | 递花 🆕(P1 先用 Taking An Item And Examining It ✓)+petals VFX ✓ | P1 |
| 53 | 送礼物盒 | 生日/节日 | 礼物盒 🆕 | 双手 | 递物 🆕(同上兜底) | P1 |
| 54 | 仙女棒 | 夜晚氛围 | 仙女棒 🆕 | rightHand | 静持+spark_burst VFX ✓ | P1 |
| 55 | 手拿气球 | 游园话题 | 气球 🆕 | rightHand(弹簧摇摆) | 01/Holding An Object Idle ✓ | P1 |
| 56 | **雨天撑伞** | 天气话题 | 伞 🆕 | rightHand | Holding An Object While Walking ✓+rain VFX ✓ | P1 |
| 57 | 擦眼泪安慰 | 悲伤对话 | 纸巾 🆕 | rightHand | 05/Wiping Sweat Idle ✓(变体) | P2 |
| 58 | 比心/飞吻 | 撒娇对话 | 无 | – | 04 手势类 ✓ | **P0** |

### G. 奇幻仙侠(dream_bedroom_3 桃隐仙居主题)

| # | 场景 | 触发语境 | 道具 | 挂点 | 动画 | 期 |
| --- | --- | --- | --- | --- | --- | --- |
| 59 | **持杖施法** | 「给我变个魔法」 | 法杖 🆕 | rightHand | 04/Pointing ✓+**magic_circle VFX ✓(组合技旗舰)** | P1 |
| 60 | **打坐修炼** | 「修炼一会儿」 | 无 | – | 06/Sitting Cross Legged ✓(MEDITATION_SEAT 已接)+ring_pulse VFX ✓ | P1 |
| 61 | 饮酒葫芦 | 酒仙扮演 | 葫芦 🆕 | rightHand | 07/Male Drinking ✓(动画复用) | P1 |
| 62 | 御剑/持剑 | 仙侠扮演 | 剑 🆕 | rightHand(背挂鞘 🆕@back) | 拔剑/持剑 🆕 | P2 |
| 63 | 抚琴(古筝) | 古风雅集 | 古筝 🆕 | 腿上大件 | 弹琴 🆕 | P2 |
| 64 | 折扇 | 江南才子 | 折扇 🆕 | rightHand | 开扇耍扇 🆕 | P2 |
| 65 | 油纸伞 | 雨巷意境 | 油纸伞 🆕 | rightHand | Holding An Object While Walking ✓+rain VFX ✓ | P2 |

### H. 节日主题(活动包=道具+台词+VFX 预设组合,按日历自动加权)

| # | 场景 | 触发语境 | 道具 | 动画/VFX | 期 |
| --- | --- | --- | --- | --- | --- |
| 66 | 圣诞送礼 | 12 月加权 | 圣诞帽 🆕@head+礼物盒 🆕 | confetti ✓ | P2 |
| 67 | 万圣南瓜 | 10 月加权 | 南瓜灯 🆕 | fire ✓ | P2 |
| 68 | 新年灯笼 | 春节加权 | 灯笼 🆕 | spark_burst ✓ | P2 |
| 69 | 生日蛋糕 | 用户生日(记忆 M2 有生日事实) | 蛋糕 🆕 | confetti ✓ | P2 |
| 70 | 中秋月饼 | 中秋加权 | 月饼 🆕 | petals ✓ | P2 |

**统计**:P0 零采购 **15 个**(2/3/7/8/10/14/25/26/27/33/39/43/44/51/58)+ 1 个替代跑法(1 看书:捧 books+坐姿 idle);P1 约 19 个,P2 约 35 个;涉及新道具 ~40 件、新动画 ~20 个。

---

## 6. 资产扩充计划

### 6.1 新道具采购/生成清单(~40 件)

渠道优先级(承 accessory-feasibility §3.3 结论):**Kenney(CC0 原生 GLB)→ 程序化生成(make-prop-glb.js,简单几何体 30 分钟级)→ Quaternius/Poly Pizza(CC0/CC-BY)→ AI 生成(Meshy/Tripo,长尾)**。单件 <2MB、无骨骼动画、禁 Draco/KTX2,`validate-glb.js` 入库校验。

| 类别 | 道具(挂点) | 首选来源 | 备注 |
| --- | --- | --- | --- |
| 日常 | 单本书(leftHand)、钢笔(rH)、马克杯(rH)、水瓶(rH)、易拉罐(rH)、手提包(lH)、写字板(lH)、梳子(rH)、纸巾(rH)、眼罩(head)、平板(rH)、魔方(双手) | Kenney furniture/海洋包+程序化 | 书/杯/笔类几何简单,程序化生成最快且尺度可控 |
| 食物 | 泡面杯、奶茶杯、冰淇淋、苹果、水壶+杯、酒杯、爆米花桶、零食碗、托盘、蛋糕、月饼 | Quaternius food pack(CC0)+Kenney food kit | 食物质感 PBR 观感 P0 已验证无发白 |
| 娱乐 | 吉他、贝斯、小提琴+弓、耳机(head)、泡泡棒、画架(场景) | Kenney music/fantasy+Poly Pizza | 乐器大件走双手持,开 springCollider 防穿身 |
| 运动 | 瑜伽垫(地面)、哑铃、跳绳、球拍、呼啦圈、篮球 | Kenney sports+程序化 | 瑜伽垫=**首个地面道具**(见 6.2) |
| 情感 | 花束、礼物盒、仙女棒、气球、伞、圣诞帽(head)、南瓜灯、灯笼 | Kenney fantasy+程序化+AI 生成(花束) | 气球挂手+弹簧摇摆(外部碰撞体现成) |
| 古风 | 法杖、剑(+背挂鞘)、葫芦、折扇、油纸伞、古筝(场景) | Kenney fantasy(剑/杖)+程序化(扇/伞/葫芦)+AI 生成(古筝) | 仙侠包与 dream_bedroom_3 联动,差异化卖点 |
| 场景道具 | 茶具组、钢琴 | 程序化+Poly Pizza | **新挂载类别:世界坐标锚点**(6.2) |

### 6.2 新挂载类别:地面/场景道具(唯一渲染层小增量)

瑜伽垫铺地上、茶具摆桌上、钢琴立墙边——**不挂骨骼,放世界坐标**。实现:复用挂载管线,把 `setParent` 的目标从骨骼实体换成场景锚点实体(home.glb 场景加载先例,`Stage.kt:700-731` 同套管线),或直接挂 avatar 场景 root+世界 TRS。activity schema 的 `props` 项支持 `{"id": "yoga_mat", "mount": "world", "anchor": "yoga_spot"}`。预算 +1 人日(含 ai_cmd 校准命令扩展),排 P1 尾。

### 6.3 动画缺口补采(~20 个,渠道顺序)

1. **本地 445 个 VRMA 源库先查**(repo 外,9 类 309 个已内置的同一来源)——看书/吃面/玩手机(躺)这类常见动作源库里很可能已有,零外部依赖,`fbx2vrma` 管线现成。
2. Mixamo(需转 VRMA,注意许可条款)/其他 CC0 动画库。
3. 实在没有的(吃面/梳头/呼啦圈等细分动作)降级:P0 用"万能手持 idle(Holding An Object Idle)+道具"近似,或砍场景。

缺口清单:坐姿看书、躺姿玩手机、打手柄、吃面、舔冰淇淋、吃苹果、嗑瓜子、梳头、递花/递物、站立书写、玩魔方、绘画、跳绳、挥拍、呼啦圈、运球、施法、耍扇、弹古筝、擦泪。**每个场景表行已标注兜底动画,P0/P1 不被缺口阻塞**。

### 6.4 校准成本控制:类别化默认偏移模板(关键降本设计)

配饰 P1 实测校准是 per-(模型×道具) 的(4705 右手系差 180°Y 那轮)。道具扩到 45+ 件后,2 模型×45 件=90 次校准不可接受。**解法:偏移模板按"挂点×道具形状类别"继承**——sidecar 的 `defaultOffset` 不再逐件手填,而是引用模板(`"offsetProfile": "holdCup"`/`"holdWand"`/`"holdBook"`/`"wearHead"`…),每模板×每模型只校准一次(~8 模板×2 模型=16 次),新道具引用模板即近似正确,个别再 `accessory cal` 微调。改动:三层合并(§accessory-feasibility 5.3)插入"模板层",~50 行+解析,排 P1 头。

---

## 7. 分期落地计划

### P0 编排器+零采购场景(≈4 人日)——15 个场景全用现有资产,1 个替代跑法

1. `activities.json` schema+解析(`ActivityCatalog`,orchestrator,~150 行)。
2. app `ActivityExecutor`:就位(可选)→播动画→vfx(可选)→says(可选)→FSM 状态同步;执行互斥排队(与 InteractionCoordinator 同锁)。
3. LLM 工具 `start_activity/stop_activity`(对齐 wearAccessory:enum 白名单双保险+失败枚举+双语 PromptTexts+独立设置开关)。
4. FSM:`AutonomySpot.activityId`+`DoActivity` directive+作息表 `activityWeights`。
5. 绑定表补条目:坐姿打字→laptop、写字→(空手)、抱枕→Holding An Object Idle、抱熊→同;per-binding offset 字段(若 laptop 腿上偏移需要)。
6. ai_cmd 观测:`activity list/state`。
7. **真机验收**:①「去看会儿书」→ 全链(走→坐→出道具→说话);②FSM 放置 10 分钟,作息表触发 ≥2 个带道具活动;③说话打断:loop 活动中对话,道具不消失、说完继续;④stop_activity 干净收场;⑤`start_activity` 期间再调 `wear_accessory` 可叠加;⑥fps 与崩溃扫描照旧口径。

> **落地记录(2026-10-25,P0 真机验收 6/6 通过,全仓 1354 单测全绿)**:做了——活动目录 16 条(15 零采购+看书替代跑法,`assets/activities/activities.json`)、`ActivityCatalog`(orchestrator,解析+enum 白名单)、app `ActivityExecutor`(就位→动画→vfx→says→FSM 同步,与就位链同一把互斥锁,反向 busyProbe 互让)、`start_activity/stop_activity` 工具(双语 PromptTexts+activityTools 独立开关默认开)、`AutonomySpot.activityId`+`DoActivity` directive+作息表 `activityWeights`、绑定表补 7 条、ai_cmd `activity list/state/last` 只读观测;companion 侧 DoActivity 记日志静默缺席(随 autonomy 搬迁是 P3,§10)。验收——①发送到道具 7.3s≤8s(LLM 4.6s+走坐链 2.7s);②25min 干净放置窗 2 次自主带道具活动(desk_typing 持续 9min+rug_pillow_tv settled 到收窗),上轮走动收敛回归修复实证:全窗零 walk 超时/断路器触发,FSM pending 全部正常清零;③loop 中对话道具不消失、回完继续;④stop 干净收场、空闲 stop 非 error、FSM 5s 内回 IDLE;⑤活动中 wear_accessory 叠加同屏;⑥fps 3 采样全 60、零 FATAL、进程无重启。新坑两处(活动系统都是"暴露器"而非根因)——其一,VRM1 中间父节点让 hips 只读局部 y 偏小 5.8×,步幅提取同倍失真(就位链 15-20s 的根因),修复=hips→root 祖先链 rest y 累积(`accumulateHeightY` 纯函数单测锁死);其二,步速校准后暴露走动收敛回归(r2 验收② 仅 1 次自主活动的真因):0.3-0.5m 短起步腿与转弯半径(约 0.4-0.5m)同量级→围目标振荡永不进 0.06m 到达半径,停滞看门狗只抓"原地不动"抓不到绕圈,FSM pending 拖 10-12min;三层修复=引擎转弯半径封顶(航向误差门控,对齐直行与摇杆全速保留)+driver 距首途经点 <0.6m 跳过 U 型首腿+渲染层 40s 非收敛断路器(arrived=false 清腿,自主循环 ≤40s 自愈),步态播放速比分母同步改提取口径校准(消除 ~25% 执行增益);新增 10 个收敛/跳腿单测,r2 实测坐标离散仿真复现机理(K=0.37 旧步速 1.7s 收敛/K≥1.0 永不收敛)。遗留:「别看了…停」这类不含工具名的措辞偶尔仍重命中 start_activity,话里明确带"停下手头活动"即中(既有 whimsy,非本期引入)。

### P1 资产扩充第一波(≈5 人日)——道具 11→32,场景 +18

1. 校准偏移模板层(6.4,先做,后面全部受益)。
2. 道具批量入库:日常/食物/乐器/情感四类 ~21 件(Kenney+程序化为主),validate-glb 全检+LICENSES.md。
3. 动画补采第一波(6.3 渠道 1 本地源库优先):坐姿看书、躺姿玩手机、打手柄、递花、站立书写等 5~8 个。
4. 地面道具挂载(6.2,瑜伽垫+茶具验证)。
5. 场景上线:看书(P1 旗舰)、喝饮料×2、瑜伽、吉他弹唱、耳机听歌、送花、撑伞(天气联动)、施法(VFX 组合技旗舰)、打坐(VFX)、仙女棒、气球、平板换真平板等。
6. **真机验收**:每新道具出/收+校准模板命中抽查;吉他双手持大件穿模检查;施法=法杖+魔法阵时序(杖起→阵现)。

### P2 深度联动(≈5 人日)——场景 +34,生态位

1. 节日活动包(日历加权+记忆 M2 生日联动:「今天你生日」→ 蛋糕+confetti+唱生日歌)。
2. 古风包(剑/扇/伞/古筝/葫芦)与 dream_bedroom_3 联动宣传点。
3. LLM 自主决策增强评估(autonomy §4.2 的缝:状态转移时低频问小模型"现在想干嘛",白名单枚举输出)。
4. 多人延伸:团播活动(双人喝茶对坐)——ShowAct 加 `activity` 字段走导演确定性调度(对齐 props 字段先例)。
5. model-viewer 活动预览(点活动看道具+动画组合效果)。
6. 剩余动画补采与长尾场景按需。

**合计 ≈14 人日**。顺序建议:P0(编排器是唯一结构性新代码,先用零采购场景验证)→ P1 模板层→资产→场景 → P2 按宣传节奏挑着做(节日包/古风包各自独立可插拔)。

---

## 8. 风险与预埋的坑

| # | 风险/未知 | 等级 | 缓解 |
| --- | --- | --- | --- |
| 1 | **动画缺口是场景覆盖率的主瓶颈**(表内 ~20 个🆕) | 高 | 渠道 1=本地 445 源库(大概率覆盖一半);每场景已配兜底动画,P0/P1 不被阻塞;细碎动作直接砍 |
| 2 | 校准工作量随道具数线性膨胀 | 高 | §6.4 偏移模板层,90 次→16 次;新道具默认引用模板 |
| 3 | loop 活动被说话手势/`<act:>` 顶替时道具闪断 | 中 | binder「同配饰不摘不戴+250ms 延迟隐藏」已兜(P1 连播实测);P0 验收项③真机专验;若仍闪,给 loop 活动画像加"顶替白名单"(手势黑名单内不停绑定) |
| 4 | 大件道具(吉他/古筝/呼啦圈)穿身体 | 中 | springColliderRadius 外部碰撞体现成(P2-3 已验);逐件开启 |
| 5 | 双手持道具挂单骨的姿态漂移(吉他只挂右手会绕轴转) | 中 | 挂点仍单骨+动画握姿约束(与手机同原理);真机不行再评估双骨约束(预算外,先不做) |
| 6 | 同步多道具(水壶+杯)与动画握姿不匹配 | 中 | 动画决定握姿,道具逐件校准;Pouring 动画 P1 真机先验,不行降级单道具 |
| 7 | prompt 膨胀:66 活动全量注入 | 低 | ≈1.5k tok 可接受(对照 interact_with 16 点先例);P2 分层注入 |
| 8 | FSM 选点带活动后"抽搐感"(频繁起坐换活动) | 中 | 继承 autonomy 上限(≤12 次/时+minDwell);活动 minDwellMs 更长(3~10min);禁忌表加"不连做同活动" |
| 9 | 外置导入道具无语义(用户自导 GLB 没进活动目录) | 低 | 外置导入保持纯 wear 通道;活动目录是策展资产,不自动纳编 |
| 10 | 销毁顺序 SIGABRT/Draco 资产/尺度散布 | 低 | 全部继承 accessory-feasibility §八 既有纪律(校验器拦截+destroy 顺序) |

---

## 9. 验收标准总口径

1. **P0 门禁**:「去书桌前忙会儿吧」一次命中 `start_activity("desk_typing")`,全链(就位→坐→道具→可选台词)≤8s;tvm 说话打断道具保持;FSM 10 分钟 ≥2 次自主带道具活动;单测全绿(目录解析/白名单/directive)。
2. **P1 门禁**:新道具 21 件 `acc list` 全注册、校准模板命中抽查 ≥80% 免微调;施法组合技(杖+阵)与撑伞(伞+雨)两组联动真机录屏。
3. **P2 门禁**:节日包按系统日历自动加权生效;团播双人活动不互抢道具(per-runtime 隔离现成)。
4. **回归**:多人对话/辩论/团播/自主生命体既有验收不受影响(activityTools 默认开但 FSM 活动权重默认保守)。

---

## 10. 与其他文档的关系

- **上游能力**:docs/accessory-feasibility.md(渲染层与绑定表,本文档不重复其机制)、docs/autonomy-feasibility.md(FSM/作息/仲裁,本文档是其"姿势态→行为态"的扩展)、docs/vfx-feasibility.md(VFX preset 与工具)。
- **执行链背景**:docs/dream-bedroom-integration.md(交互点表/定姿)、docs/locomotion-feasibility.md(走动)、docs/nl-action-reliability.md(标签可靠性纪律:提示词提高概率、确定性校验在客户端——活动白名单同思路)。
- **资产渠道**:docs/arbitrary-motion-feasibility.md(445 源库与 fbx2vrma 管线,动画补采渠道 1 的依据)。
- **后续延伸(P3,不在本期)**:记忆驱动活动选择("她记得你推荐的 书"→看书的思考气泡提这本)、用户自定义活动包(外置 JSON,对齐 acc import 工作流)、:companion 模块化时随 autonomy 一起搬迁。
