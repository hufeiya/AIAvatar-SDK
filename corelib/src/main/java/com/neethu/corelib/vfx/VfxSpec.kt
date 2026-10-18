package com.neethu.corelib.vfx

/**
 * 特效库公开数据模型（docs/vfx-feasibility.md §4.2/§4.3）。全部字段带默认值，
 * 预设只写差异项；[EmitterParams]/[BehaviorParams] 是纯数据（无引擎类型），
 * 由 `vfx.sim` 层解释执行——JVM 单测覆盖。
 */

/** 特效混合档：决定进哪个批渲染（每档一个 draw call、一个材质）。 */
enum class VfxBlending { ADDITIVE, TRANSPARENT }

/**
 * 粒子渲染模式（画质升级 2026-10-17，docs/vfx-quality-upgrade.md §3.4）：
 *  - [BILLBOARD]：经典面向相机四边形（远处便宜，默认）；
 *  - [CROSS]：两片正交的世界系四边形（8 顶点/粒子）+ 三维翻滚——近景
 *    雪/花瓣等薄片粒子获得真实空间翻滚，任意视角都有体积感（游戏惯用的
 *    cross-quad 技法），不随视线转动。
 */
enum class VfxRenderMode { BILLBOARD, CROSS }

/** 粒子贴图来源。P0 仅程序化柔边圆点（零资产自证）；P1 增 assets 路径。 */
sealed interface VfxTexture {
    /** 程序化径向渐变圆点（64×64，运行时生成，零资产）。 */
    data object SoftDot : VfxTexture

    /** assets 路径 PNG（P1 资产包）。 */
    data class Asset(val path: String) : VfxTexture
}

/** 单特效粒子满时的策略。 */
enum class Overflow {
    /** 满了拒绝新粒子（爆发型默认）。 */
    REJECT,

    /** 覆盖剩余寿命最短的粒子（持续型：雨雪烟雾）。 */
    RECYCLE_OLDEST,
}

/** 轻量不可变三维向量（规格参数用；模拟层内部用 SoA 扁平数组）。 */
data class VfxVec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    companion object {
        val ZERO = VfxVec3(0f, 0f, 0f)
        val DOWN = VfxVec3(0f, -1f, 0f)
    }
}

/** 线性空间 RGBA 颜色（ColorOverLife 的键与粒子起始色）。 */
data class VfxColor(val r: Float, val g: Float, val b: Float, val a: Float) {
    companion object {
        val WHITE = VfxColor(1f, 1f, 1f, 1f)
    }
}

/** 颜色/尺寸曲线的键：t ∈ [0,1] 为生命进度（0=出生，1=消亡）。 */
data class ColorKey(val t: Float, val color: VfxColor)
data class ScalarKey(val t: Float, val value: Float)

/** 发射器形状（three.quarks shape/ 子集；P1 集齐 Point/Sphere/Box/Ring/Cone）。 */
enum class EmitterShape { POINT, SPHERE, BOX, RING, CONE }

/**
 * 发射器参数：形状 + 相对锚点的偏移域 + 初始速度域。
 *
 * - [shape]=SPHERE：体积内均匀采样，初速沿「中心→粒子」径向放射；
 * - [shape]=BOX：半尺寸盒内均匀采样，初速沿 [direction]；
 * - [shape]=RING：XZ 平面圆周采样，初速沿径向水平放射（ring_pulse）；
 * - [shape]=CONE：底面圆盘均匀采样（火焰底盘），初速 = [direction] 轴向 +
 *   径向展开（[radialFraction] 份额；篝火上升外扩）；
 * - [shape]=POINT：锚点单点，初速沿 [direction]。
 */
data class EmitterParams(
    val shape: EmitterShape = EmitterShape.POINT,
    /** 发射域中心（相对锚点，米）。 */
    val center: VfxVec3 = VfxVec3.ZERO,
    /** SPHERE 半径 / RING 半径 / CONE 底盘半径（米）。 */
    val radius: Float = 0.5f,
    /** BOX 半尺寸（米）。 */
    val halfExtents: VfxVec3 = VfxVec3(1f, 1f, 1f),
    /** POINT/BOX/CONE 的初始速度方向（SPHERE/RING 忽略，恒径向）。 */
    val direction: VfxVec3 = VfxVec3.DOWN,
    /** 初始速度域 [speedMin, speedMax]（米/秒，逐粒子采样）。 */
    val speedMin: Float = 0.5f,
    val speedMax: Float = 1.0f,
    /** CONE 径向展开份额（0=纯轴向，0.3=火苗外扩；乘在速度上）。 */
    val radialFraction: Float = 0f,
)

/** 行为参数（sealed：模拟层映射到对应 Behavior 实现）。 */
sealed class BehaviorParams {
    /** 恒定加速度（米/秒²）。 */
    data class Gravity(val value: VfxVec3) : BehaviorParams()

    /** 速度指数衰减（每秒衰减系数；0=无，2.5=快速刹停的爆发感）。 */
    data class Drag(val coefficient: Float) : BehaviorParams()

    /** 生命进度 → 颜色曲线（[keys] 的 t 升序）。 */
    data class ColorOverLife(val keys: List<ColorKey>) : BehaviorParams()

    /** 生命进度 → 尺寸倍率曲线（乘在逐粒子基础尺寸上）。 */
    data class SizeOverLife(val keys: List<ScalarKey>) : BehaviorParams()

    /**
     * 横摆（雪的飘）：沿 [axis]（"x"/"y"/"z" 小写）直接改写速度分量为
     * `amplitude × sin(2π·frequency·粒子已活时长 + phase)`——速度目标式而非
     * 加速度累积，落速不被横摆抬走。粒子各活各的相位，天然错落。
     */
    data class Sway(val axis: String, val amplitude: Float, val frequency: Float, val phase: Float = 0f) : BehaviorParams()

    /**
     * 湍流（花瓣/烟/光尘的扰动）：力语义 = 加速度（米/秒²），apply 内乘 dt
     * （2026-10-17 修复：曾漏乘 dt 导致扰动力随帧率漂移）。
     *
     * - [curl]=false（默认）：简化三角伪流场——`force = strength ×
     *   (sin(py·k+t), sin(pz·k+t), sin(px·k+t))`（k 由 [scale] 导出，t 随
     *   粒子已活时长演化）。非真 curl（无散度约束），视觉够用且零开销。
     * - [curl]=true（P2 完整版）：从向量势 ψ 的解析旋度 ∇×ψ 取力——三个
     *   异构波向量的 sin 势，curl 数学上散度恒零（旋度场不会有「汇/源」
     *   聚集），涡卷感比伪流场真实；强度同样按 [strength] 米/秒² 标度。
     */
    data class Turbulence(
        val strength: Float,
        val scale: Float = 1f,
        val evolution: Float = 1f,
        val curl: Boolean = false,
    ) : BehaviorParams()

    /**
     * 地面碰撞（雪落地消融/雨丝到地即逝）：粒子 y 低于 [groundY] 且在下落时
     * 钉回地面、竖向速度清零、水平速度按 [friction] 指数衰减，剩余寿命钳到
     * [fadeSeconds]（落地后短促淡出；0 = 立即消亡）。
     */
    data class GroundCollision(val groundY: Float, val fadeSeconds: Float, val friction: Float = 3f) : BehaviorParams()
}

/** flipbook 序列帧参数（[VfxSpec.flipbook]）：贴图按列×行网格切子矩形。 */
data class FlipbookParams(
    val columns: Int,
    val rows: Int,
) {
    val totalFrames: Int get() = columns.coerceAtLeast(1) * rows.coerceAtLeast(1)
    init {
        require(columns >= 1 && rows >= 1) { "flipbook columns/rows must be >= 1" }
    }
}

/**
 * 风场参数（画质升级：雪/花瓣/烟共享同一阵风，docs/vfx-quality-upgrade.md
 * §3.4）。风是速度目标（米/秒），模拟层按 [responsiveness] 逐帧把水平速度
 * 拉向风采样值——帧率无关；竖向速度不受风影响（落速归重力管）。
 *
 * 同一 [VfxManager] 下所有带风特效共享全局风时钟：阵风相位一致，「雪与
 * 花瓣受同一阵风」才成立。头发弹簧骨骼的外力馈送也取同一采样。
 */
data class WindParams(
    /** 主风向（世界系；内部投影到水平面并归一）。 */
    val direction: VfxVec3 = VfxVec3(1f, 0f, 0f),
    /** 基础风速（米/秒）。 */
    val strength: Float = 0.8f,
    /** 阵风幅度（0~1，慢正弦包络调制强度）。 */
    val gust: Float = 0.35f,
    /** 阵风基频（Hz；叠加 2 个失谐正弦营造有机起伏）。 */
    val gustFrequency: Float = 0.15f,
    /** 局部扰动幅度（米/秒；位置相关的伪流场）。 */
    val swirl: Float = 0.25f,
    /** 扰动空间尺度（米）。 */
    val swirlScale: Float = 1.2f,
    /** 速度趋向风场的响应率（每秒；2 = ~0.5s 跟上）。 */
    val responsiveness: Float = 1.6f,
)

/** 舞台灯类型。 */
enum class StageLightType { SPOT, POINT }

/**
 * 舞台灯参数（画质升级能力线 A，docs/vfx-quality-upgrade.md §3.2）：一盏
 * 真 Filament 聚光/点光（照亮地板与 PBR 场景）+ 可见光束锥 mesh（加色渐隐
 * 体积近似）+ 地面光斑。位置/目标均为相对特效锚点的偏移（米）。
 *
 * - [sweepSpeedDegPerSec]：绕锚点竖轴扫摆（°/秒），position/target 同步旋转；
 * - [flickerStrength]/[flickerFrequency]：强度闪烁（火焰照明/灯泡嗡闪）；
 * - MToon 角色不自动吃场景灯：VfxManager 每帧挑「对角色贡献最大」的一盏
 *   经 host 推给 MToon 材质（近似单盏局部光，全量多灯响应留后续）。
 */
data class StageLightParams(
    val type: StageLightType = StageLightType.SPOT,
    /** 灯色（RGB 线性；alpha 不用）。 */
    val color: VfxColor = VfxColor(1f, 1f, 1f, 1f),
    /** 光强（坎德拉；本仓库 key 灯 2.25M cd @ ~5.5m 的量级参照）。 */
    val intensityCandela: Float = 120_000f,
    /** 灯位（相对锚点，米）。 */
    val position: VfxVec3 = VfxVec3(0f, 2.6f, 0.8f),
    /** 照射目标点（相对锚点；POINT 忽略）。 */
    val target: VfxVec3 = VfxVec3(0f, 0.9f, 0f),
    /** 聚光内/外锥半角（度）。 */
    val coneInnerDeg: Float = 7f,
    val coneOuterDeg: Float = 13f,
    /** 距离衰减半径（米）。 */
    val rangeMeters: Float = 14f,
    /** 是否渲染可见光束锥 + 地面光斑（POINT 默认无束）。 */
    val beamVisible: Boolean = true,
    /**
     * 是否挂真 Filament 灯（照亮 PBR 场景/地板）。false = 纯光束+地面光斑+
     * MToon 推送（角色响应不依赖真灯）。真灯是前向渲染逐片元成本（真机
     * 实测每灯 ≈−10fps@K20Pro 全屏 home.glb），多灯编队须限量。
     */
    val realLight: Boolean = true,
    /** 光束长度（米；0 = 自动 = position→target 距离）。 */
    val beamLengthMeters: Float = 0f,
    /** 扫摆角速度（°/秒，绕锚点竖轴；0 = 固定）。 */
    val sweepSpeedDegPerSec: Float = 0f,
    /**
     * 扫摆幅度（度；0 = 连续整圆旋转，>0 = 在 ±幅度内正弦摆动——
     * 整圆会把灯甩到相机背后/墙内，舞台样片用摆动模式）。
     */
    val sweepAmplitudeDeg: Float = 0f,
    /** 扫摆初始相位（度）。 */
    val sweepPhaseDeg: Float = 0f,
    /** 强度闪烁幅度（0~1；0.5 = ±50% 锯齿闪烁）。 */
    val flickerStrength: Float = 0f,
    /** 闪烁频率（Hz 量级）。 */
    val flickerFrequency: Float = 8f,
)

/**
 * decal 几何（P2，docs/vfx-feasibility.md §六 P2 + 画质升级 §7.4 拆分修正：
 * 地面平面与穹顶不是同一种几何，分两态）。非粒子的静态网格贴花——法阵地面
 * 圆盘 / 护盾穹顶；舞台光柱由舞台灯架（StageLightParams 光束锥）覆盖不在此列。
 */
enum class DecalShape {
    /** 地面圆盘：锚点水平面 XZ 上的圆形贴花（魔法阵），绕竖轴自转。 */
    GROUND_CIRCLE,

    /** 穹顶：锚点上方半球壳（护盾/光环），CPU 逐顶点 Fresnel 边缘光。 */
    DOME,
}

/**
 * 贴花参数（[VfxSpec.decals]）：一张静态网格 + vfx_decal 材质实例，挂独立
 * 实体走实体变换（自转/跟随锚点），Fresnel 经 CPU 顶点色逐帧更新（穹顶）。
 * 颜色走顶点色（灰度 Fresnel 调制），呼吸脉动走 intensity（材质实例热改）。
 */
data class DecalParams(
    val shape: DecalShape = DecalShape.GROUND_CIRCLE,
    /** 贴图（assets 路径；程序化柔边圆点兜底）。 */
    val texture: VfxTexture = VfxTexture.SoftDot,
    /** 半径（米）。 */
    val radiusMeters: Float = 1.2f,
    /** 相对锚点的高度偏移（米；地面贴花抬高防 z-fight，穹顶 0=贴地起）。 */
    val heightOffsetMeters: Float = 0.02f,
    /** 颜色（线性 RGB；顶点色 = 本色 × Fresnel 明度）。 */
    val color: VfxColor = VfxColor(0.55f, 0.75f, 1.0f, 1f),
    /** 基准亮度（呼吸脉动围绕它调制）。 */
    val intensity: Float = 1.0f,
    /** 自转角速度（°/秒，绕竖轴；地面贴花转贴图，穹顶慢转 UV 花纹）。 */
    val spinSpeedDegPerSec: Float = 30f,
    /** 穹顶 Fresnel 边缘光强度（0=无边缘光；GROUND_CIRCLE 忽略）。 */
    val rimStrength: Float = 1.6f,
    /** 呼吸脉动幅度（0~1；0=恒定亮度）。 */
    val pulseStrength: Float = 0.25f,
    /** 呼吸脉动频率（Hz 量级）。 */
    val pulseFrequency: Float = 0.8f,
)

/**
 * 特效规格：一次播放的完整参数表。
 *
 * 发射模型：[burst]（起播一次性数量）+ [rate]（每秒持续发射，0=无）。
 * spark_burst = burst 一次性；snow = 纯 rate 持续；两者可组合。
 */
data class VfxSpec(
    val id: String,
    val label: String = id,
    val blending: VfxBlending = VfxBlending.ADDITIVE,
    val texture: VfxTexture = VfxTexture.SoftDot,
    /** 单特效粒子上限（也是它在共享批里占的写段容量）。 */
    val maxParticles: Int = 256,
    val overflow: Overflow = Overflow.REJECT,
    val emitter: EmitterParams = EmitterParams(),
    val behaviors: List<BehaviorParams> = emptyList(),
    /** 一次性发射数量（起播瞬间）。0 = 无爆发。 */
    val burst: Int = 0,
    /** 持续发射速率（粒子/秒）。0 = 纯一次性。 */
    val rate: Float = 0f,
    /** 逐粒子寿命域（秒，均匀采样）。 */
    val lifetimeMin: Float = 1f,
    val lifetimeMax: Float = 2f,
    /** 逐粒子基础尺寸域（米；Billboard 半宽）。 */
    val sizeMin: Float = 0.05f,
    val sizeMax: Float = 0.10f,
    /** 无 ColorOverLife 时的恒定颜色。 */
    val startColor: VfxColor = VfxColor.WHITE,
    /** 逐粒子随机取色表（confetti 彩色纸屑；null = 恒用 [startColor]）。 */
    val palette: List<VfxColor>? = null,
    /** 自转角速度域（度/秒，绕屏幕轴；仅 BILLBOARD 生效；0 = 不转）。 */
    val spinMin: Float = 0f,
    val spinMax: Float = 0f,
    /** >0 = 按速度方向拉伸粒子（雨丝；拉伸倍率）。 */
    val stretchByVelocity: Float = 0f,
    /** 序列帧（null = 整张贴图；火焰 flipbook）。 */
    val flipbook: FlipbookParams? = null,
    /**
     * 缺省锚定骨骼（P1 应援模式：lightstick_spark 缺省锚 rightHand——调用方
     * 不给锚点时按骨骼锚定发射，骨骼跟随、已存活粒子留世界系；null = 世界点）。
     */
    val defaultBone: String? = null,
    /** 英文显示名（presets.json 的 nameEn；null = 回落 [label]）。 */
    val labelEn: String? = null,
    /** 渲染模式（默认 Billboard；近景薄片粒子用 CROSS 获得空间翻滚）。 */
    val renderMode: VfxRenderMode = VfxRenderMode.BILLBOARD,
    /** 三维翻滚角速度域（度/秒/轴，逐粒子逐轴随机正负；仅 CROSS 生效）。 */
    val tumbleMin: Float = 0f,
    val tumbleMax: Float = 0f,
    /** 环境风场（null = 无风；带风特效共享全局风时钟，阵风同相）。 */
    val wind: WindParams? = null,
    /**
     * 复合层（画质升级：一个预设 = 多个子发射器，如 火焰=火苗+辉光+火星+
     * 烟+照明灯）。非空时本 spec 其余发射参数被忽略，逐层实例化；共享锚点
     * 与生命周期（全部层播完才自停，stop 一次全停）。
     */
    val layers: List<VfxSpec>? = null,
    /** 舞台灯组（非空时 VfxManager 建灯架：真灯 + 可见光束 + 地面光斑）。 */
    val lights: List<StageLightParams> = emptyList(),
    /**
     * 贴花组（P2：法阵/护盾类静态网格贴花，非粒子通道）。非空时 VfxManager
     * 建 decal rig（独立实体 + 实体变换），纯贴花特效与纯灯特效同款永不自停。
     */
    val decals: List<DecalParams> = emptyList(),
)

/** 特效锚点（P1 集齐世界点 + 骨骼锚定，docs/vfx-feasibility.md §4.8）。 */
sealed interface VfxAnchor {
    /** 固定世界点（米；模型经 transformToUnitCube 后原点在脚底）。 */
    data class World(val x: Float, val y: Float, val z: Float) : VfxAnchor

    /**
     * 骨骼锚定：发射器原点每帧跟随骨骼世界位置（发射跟随、已存活粒子留在
     * 世界系——Unity world-simulation 语义，走路时手部流光拖尾自然）。
     * [avatarId] 仅团播有意义（null = 单人当前角色）；解析失败（模型重载/
     * 演员离场后实体失效）→ 特效安静自停，不持陈旧 entity（§4.8）。
     */
    data class Bone(val boneName: String, val avatarId: String? = null) : VfxAnchor

    /**
     * 镜头前（P2 play_effect 的 camera_front 语义）：每帧取相机 eye+forward
     * 解析「眼前 [distanceMeters] 米」的悬浮点（特效跟着镜头走，不依赖角色；
     * VfxManager 每帧已取相机三元组，app 层零相机 API 依赖）。
     */
    data class CameraFront(val distanceMeters: Float = 1.2f) : VfxAnchor
}

/** 一次播放的句柄（[VfxManager] 生成 id，`stopVfx` 凭它停止）。 */
class VfxHandle internal constructor(val id: String) {
    override fun toString(): String = "VfxHandle($id)"
}

/** 特效状态快照（[AvatarController.effects] 列表项与 ai_cmd `fx state` 数据源）。 */
data class VfxInfo(
    val id: String,
    val presetId: String,
    val blending: VfxBlending,
    val activeParticles: Int,
    val ageSeconds: Float,
    /** 锚点描述（P1 调试目验骨锚跟随用；如 "world(0.0,0.0,-3.9)" / "bone:rightHand"）。 */
    val anchor: String = "",
)
