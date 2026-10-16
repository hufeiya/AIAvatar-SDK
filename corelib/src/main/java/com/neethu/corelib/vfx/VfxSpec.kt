package com.neethu.corelib.vfx

/**
 * 特效库公开数据模型（docs/vfx-feasibility.md §4.2/§4.3）。全部字段带默认值，
 * 预设只写差异项；[EmitterParams]/[BehaviorParams] 是纯数据（无引擎类型），
 * 由 `vfx.sim` 层解释执行——JVM 单测覆盖。
 */

/** 特效混合档：决定进哪个批渲染（每档一个 draw call、一个材质）。 */
enum class VfxBlending { ADDITIVE, TRANSPARENT }

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

/** 发射器形状（three.quarks shape/ 子集；P0 = Point/Sphere/Box/Ring）。 */
enum class EmitterShape { POINT, SPHERE, BOX, RING }

/**
 * 发射器参数：形状 + 相对锚点的偏移域 + 初始速度域。
 *
 * - [shape]=SPHERE：体积内均匀采样，初速沿「中心→粒子」径向放射；
 * - [shape]=BOX：半尺寸盒内均匀采样，初速沿 [direction]；
 * - [shape]=RING：XZ 平面圆周采样，初速沿径向水平放射（ring_pulse）；
 * - [shape]=POINT：锚点单点，初速沿 [direction]。
 */
data class EmitterParams(
    val shape: EmitterShape = EmitterShape.POINT,
    /** 发射域中心（相对锚点，米）。 */
    val center: VfxVec3 = VfxVec3.ZERO,
    /** SPHERE 半径 / RING 半径（米）。 */
    val radius: Float = 0.5f,
    /** BOX 半尺寸（米）。 */
    val halfExtents: VfxVec3 = VfxVec3(1f, 1f, 1f),
    /** POINT/BOX 的初始速度方向（SPHERE/RING 忽略，恒径向）。 */
    val direction: VfxVec3 = VfxVec3.DOWN,
    /** 初始速度域 [speedMin, speedMax]（米/秒，逐粒子采样）。 */
    val speedMin: Float = 0.5f,
    val speedMax: Float = 1.0f,
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
}

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
    /** 自转角速度域（度/秒，绕屏幕轴；0 = 不转）。 */
    val spinMin: Float = 0f,
    val spinMax: Float = 0f,
    /** >0 = 按速度方向拉伸粒子（雨丝；拉伸倍率）。 */
    val stretchByVelocity: Float = 0f,
)

/** 特效锚点。P0 仅世界点；P1 增骨骼锚定（发射器跟随、已存活粒子留世界系）。 */
sealed interface VfxAnchor {
    /** 固定世界点（米；模型经 transformToUnitCube 后原点在脚底）。 */
    data class World(val x: Float, val y: Float, val z: Float) : VfxAnchor
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
)
