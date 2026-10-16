package com.neethu.corelib.vfx

/**
 * 内置常量预设表（P0 零 JSON 零资产，docs/vfx-feasibility.md §4.3）：SDK
 * 开箱即用；P1 的 `assets/vfx/presets.json` sidecar 经 resolver 缝合并，
 * app 条目覆盖同名内置项。
 *
 * 坐标口径：世界单位=米，锚点 (0,0,0)=模型脚底（transformToUnitCube 后）。
 */
object VfxPresets {

    /** 星尘爆发（§七配方）：球心一次性放射 + 拖拽刹停 + 亮黄→橙红衰减。 */
    val sparkBurst = VfxSpec(
        id = "spark_burst",
        label = "星尘爆发",
        blending = VfxBlending.ADDITIVE,
        texture = VfxTexture.SoftDot,
        maxParticles = 96,
        overflow = Overflow.REJECT,
        burst = 96,
        rate = 0f,
        lifetimeMin = 0.5f,
        lifetimeMax = 1.1f,
        sizeMin = 0.02f,
        sizeMax = 0.055f,
        emitter = EmitterParams(
            shape = EmitterShape.SPHERE,
            center = VfxVec3(0f, 1.2f, 0f),
            radius = 0.08f,
            speedMin = 1.2f,
            speedMax = 2.8f,
        ),
        behaviors = listOf(
            BehaviorParams.Drag(coefficient = 2.2f),
            BehaviorParams.Gravity(VfxVec3(0f, -0.6f, 0f)),
            BehaviorParams.ColorOverLife(
                listOf(
                    ColorKey(0.00f, VfxColor(1.0f, 0.95f, 0.72f, 1.0f)),
                    ColorKey(0.35f, VfxColor(1.0f, 0.62f, 0.22f, 0.9f)),
                    ColorKey(1.00f, VfxColor(0.75f, 0.18f, 0.04f, 0.0f)),
                ),
            ),
            BehaviorParams.SizeOverLife(
                listOf(ScalarKey(0f, 1f), ScalarKey(1f, 0.35f)),
            ),
        ),
    )

    /** 漫天飞雪（§七配方）：顶部 6×6m 盒域持续飘落 + 淡入淡出。 */
    val snow = VfxSpec(
        id = "snow",
        label = "漫天飞雪",
        blending = VfxBlending.TRANSPARENT,
        texture = VfxTexture.SoftDot,
        maxParticles = 400,
        overflow = Overflow.RECYCLE_OLDEST,
        burst = 0,
        rate = 60f,
        lifetimeMin = 4f,
        lifetimeMax = 7f,
        sizeMin = 0.025f,
        sizeMax = 0.05f,
        emitter = EmitterParams(
            shape = EmitterShape.BOX,
            // 默认机位（眼高 ~0、距角色 ~5m）取景框顶 ~1.7m：发射高度须在框内，
            // 4~7s 寿命 × 落速 ~0.5m/s 恰好落到地面附近消亡（过高落不进画面）
            center = VfxVec3(0f, 2.2f, 0f),
            halfExtents = VfxVec3(1.6f, 0.2f, 1.6f),
            direction = VfxVec3.DOWN,
            speedMin = 0.25f,
            speedMax = 0.5f,
        ),
        behaviors = listOf(
            BehaviorParams.Gravity(VfxVec3(0f, -0.6f, 0f)),
            BehaviorParams.ColorOverLife(
                listOf(
                    ColorKey(0.00f, VfxColor(1f, 1f, 1f, 0f)),
                    ColorKey(0.15f, VfxColor(1f, 1f, 1f, 0.9f)),
                    ColorKey(1.00f, VfxColor(1f, 1f, 1f, 0f)),
                ),
            ),
            BehaviorParams.SizeOverLife(
                listOf(ScalarKey(0f, 0.5f), ScalarKey(1f, 1f)),
            ),
        ),
    )

    /** 环形脉冲（P0 内置）：地面圆周径向扩散的光环，拖拽减速 + 青白淡出。 */
    val ringPulse = VfxSpec(
        id = "ring_pulse",
        label = "环形脉冲",
        blending = VfxBlending.ADDITIVE,
        texture = VfxTexture.SoftDot,
        maxParticles = 64,
        overflow = Overflow.REJECT,
        burst = 64,
        rate = 0f,
        lifetimeMin = 0.7f,
        lifetimeMax = 1.0f,
        sizeMin = 0.035f,
        sizeMax = 0.06f,
        emitter = EmitterParams(
            shape = EmitterShape.RING,
            center = VfxVec3(0f, 0.05f, 0f),
            radius = 0.15f,
            speedMin = 1.4f,
            speedMax = 2.0f,
        ),
        behaviors = listOf(
            BehaviorParams.Drag(coefficient = 1.8f),
            BehaviorParams.ColorOverLife(
                listOf(
                    ColorKey(0.00f, VfxColor(0.55f, 0.90f, 1.0f, 1.0f)),
                    ColorKey(1.00f, VfxColor(0.20f, 0.45f, 0.9f, 0.0f)),
                ),
            ),
            BehaviorParams.SizeOverLife(
                listOf(ScalarKey(0f, 0.8f), ScalarKey(1f, 2.2f)),
            ),
        ),
    )

    /** 内置目录（id → spec，保留声明序）。 */
    val all: Map<String, VfxSpec> = linkedMapOf(
        sparkBurst.id to sparkBurst,
        snow.id to snow,
        ringPulse.id to ringPulse,
    )

    /** 按 id 查找（大小写不敏感）。 */
    fun find(id: String): VfxSpec? = all[id.lowercase()]
}
