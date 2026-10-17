package com.neethu.corelib.vfx

/**
 * 内置常量预设表（P1 扩到 §七配方表全集，docs/vfx-feasibility.md §4.3/§七）：
 * SDK 开箱即用；app 的 `assets/vfx/presets.json` sidecar 经 VfxCatalog 三层
 * 合并，app 条目覆盖同名内置项（真机调参以 sidecar 为准）。
 *
 * 坐标口径：世界单位=米，锚点 (0,0,0)=模型脚底（transformToUnitCube 后）。
 * 贴图：P0 三预设保持程序化柔边圆点（零资产兜底）；P1 新增五配方引用
 * assets PNG——纯 SDK（无资产）消费时 VfxTextureFactory 自动回退柔边圆点，
 * 观感降级但功能无损。
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

    /** 漫天飞雪（§七配方）：顶部窄域持续飘落 + 横摆 + 淡入淡出。 */
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
            BehaviorParams.Sway(axis = "x", amplitude = 0.25f, frequency = 0.6f),
            BehaviorParams.Sway(axis = "z", amplitude = 0.18f, frequency = 0.8f, phase = 2.1f),
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

    /** 环形脉冲：地面圆周径向扩散的光环，拖拽减速 + 青白淡出。 */
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

    /** 阵雨（§七配方）：顶部盒域快速下落 + 速度拉伸成雨丝 + 高回收率。 */
    val rain = VfxSpec(
        id = "rain",
        label = "阵雨",
        blending = VfxBlending.TRANSPARENT,
        texture = VfxTexture.Asset("vfx/textures/streak.png"),
        maxParticles = 220,
        overflow = Overflow.RECYCLE_OLDEST,
        rate = 140f,
        lifetimeMin = 0.8f,
        lifetimeMax = 1.2f,
        sizeMin = 0.012f,
        sizeMax = 0.02f,
        emitter = EmitterParams(
            shape = EmitterShape.BOX,
            // 透明档过绘是第一预算项（P0 实录）：域收紧 + 细长粒子
            center = VfxVec3(0f, 2.4f, 0f),
            halfExtents = VfxVec3(1.3f, 0.1f, 1.3f),
            direction = VfxVec3.DOWN,
            speedMin = 2.0f,
            speedMax = 3.2f,
        ),
        behaviors = listOf(
            BehaviorParams.Gravity(VfxVec3(0f, -9f, 0f)),
            BehaviorParams.ColorOverLife(
                listOf(
                    ColorKey(0.00f, VfxColor(0.75f, 0.85f, 1.0f, 0f)),
                    ColorKey(0.10f, VfxColor(0.75f, 0.85f, 1.0f, 0.55f)),
                    ColorKey(1.00f, VfxColor(0.75f, 0.85f, 1.0f, 0f)),
                ),
            ),
        ),
        stretchByVelocity = 7f,
    )

    /** 篝火/舞台火焰（§七配方）：锥底盘上升 + flipbook 序列帧 + 亮黄→橙红→暗。 */
    val fire = VfxSpec(
        id = "fire",
        label = "篝火",
        blending = VfxBlending.ADDITIVE,
        texture = VfxTexture.Asset("vfx/textures/flame_4x4.png"),
        maxParticles = 96,
        overflow = Overflow.RECYCLE_OLDEST,
        rate = 42f,
        lifetimeMin = 0.7f,
        lifetimeMax = 1.0f,
        sizeMin = 0.10f,
        sizeMax = 0.17f,
        emitter = EmitterParams(
            shape = EmitterShape.CONE,
            center = VfxVec3(0f, 0.02f, 0f),
            radius = 0.16f,
            direction = VfxVec3(0f, 1f, 0f),
            speedMin = 0.7f,
            speedMax = 1.2f,
            radialFraction = 0.25f,
        ),
        behaviors = listOf(
            BehaviorParams.Drag(coefficient = 0.8f),
            BehaviorParams.ColorOverLife(
                listOf(
                    ColorKey(0.00f, VfxColor(1.0f, 0.92f, 0.55f, 0.0f)),
                    ColorKey(0.15f, VfxColor(1.0f, 0.85f, 0.40f, 0.95f)),
                    ColorKey(0.55f, VfxColor(1.0f, 0.45f, 0.10f, 0.75f)),
                    ColorKey(1.00f, VfxColor(0.45f, 0.08f, 0.02f, 0.0f)),
                ),
            ),
            BehaviorParams.SizeOverLife(
                listOf(ScalarKey(0f, 0.55f), ScalarKey(0.35f, 1f), ScalarKey(1f, 0.6f)),
            ),
        ),
        flipbook = FlipbookParams(columns = 4, rows = 4),
    )

    /** 团播开演彩带（§七配方）：顶部盒域撒彩色纸屑 + 自转 + 慢落。 */
    val confetti = VfxSpec(
        id = "confetti",
        label = "开演彩带",
        blending = VfxBlending.TRANSPARENT,
        texture = VfxTexture.Asset("vfx/textures/confetti.png"),
        maxParticles = 300,
        overflow = Overflow.RECYCLE_OLDEST,
        rate = 80f,
        lifetimeMin = 2.5f,
        lifetimeMax = 4f,
        sizeMin = 0.03f,
        sizeMax = 0.055f,
        emitter = EmitterParams(
            shape = EmitterShape.BOX,
            center = VfxVec3(0f, 2.2f, 0f),
            halfExtents = VfxVec3(1.4f, 0.15f, 1.4f),
            direction = VfxVec3.DOWN,
            speedMin = 0.15f,
            speedMax = 0.4f,
        ),
        behaviors = listOf(
            BehaviorParams.Gravity(VfxVec3(0f, -0.8f, 0f)),
            BehaviorParams.Sway(axis = "x", amplitude = 0.3f, frequency = 0.9f),
            BehaviorParams.Turbulence(strength = 0.18f, scale = 1.6f, evolution = 0.7f),
            BehaviorParams.ColorOverLife(
                listOf(
                    ColorKey(0.00f, VfxColor(1f, 1f, 1f, 0f)),
                    ColorKey(0.08f, VfxColor(1f, 1f, 1f, 1f)),
                    ColorKey(0.85f, VfxColor(1f, 1f, 1f, 1f)),
                    ColorKey(1.00f, VfxColor(1f, 1f, 1f, 0f)),
                ),
            ),
        ),
        palette = listOf(
            VfxColor(1.00f, 0.30f, 0.35f, 1f),
            VfxColor(1.00f, 0.72f, 0.15f, 1f),
            VfxColor(0.30f, 0.85f, 0.45f, 1f),
            VfxColor(0.25f, 0.60f, 1.00f, 1f),
            VfxColor(0.80f, 0.40f, 1.00f, 1f),
            VfxColor(1.00f, 1.00f, 1.00f, 1f),
        ),
        spinMin = -240f,
        spinMax = 240f,
    )

    /** 风吹花瓣（§七配方）：侧方盒域风送 + 自转 + 湍流扰动（P1 湍流验收载体）。 */
    val petals = VfxSpec(
        id = "petals",
        label = "风吹花瓣",
        blending = VfxBlending.TRANSPARENT,
        texture = VfxTexture.Asset("vfx/textures/petal.png"),
        maxParticles = 160,
        overflow = Overflow.RECYCLE_OLDEST,
        rate = 26f,
        lifetimeMin = 3f,
        lifetimeMax = 5f,
        sizeMin = 0.035f,
        sizeMax = 0.06f,
        emitter = EmitterParams(
            shape = EmitterShape.BOX,
            // 侧方来风：从角色左侧（-X）半空吹向右侧
            center = VfxVec3(-1.2f, 1.4f, 0f),
            halfExtents = VfxVec3(0.3f, 1.0f, 1.5f),
            direction = VfxVec3(1f, -0.12f, 0f),
            speedMin = 0.45f,
            speedMax = 0.85f,
        ),
        behaviors = listOf(
            BehaviorParams.Gravity(VfxVec3(0f, -0.22f, 0f)),
            BehaviorParams.Turbulence(strength = 0.3f, scale = 2.2f, evolution = 0.9f),
            BehaviorParams.Sway(axis = "y", amplitude = 0.22f, frequency = 1.3f),
            BehaviorParams.ColorOverLife(
                listOf(
                    ColorKey(0.00f, VfxColor(1f, 1f, 1f, 0f)),
                    ColorKey(0.10f, VfxColor(1f, 1f, 1f, 0.95f)),
                    ColorKey(0.85f, VfxColor(1f, 1f, 1f, 0.95f)),
                    ColorKey(1.00f, VfxColor(1f, 1f, 1f, 0f)),
                ),
            ),
        ),
        spinMin = -200f,
        spinMax = 200f,
    )

    /** 应援棒流光（§七配方）：Point 高频小粒子 + 手骨锚定（应援模式组合件）。 */
    val lightstickSpark = VfxSpec(
        id = "lightstick_spark",
        label = "应援星光",
        blending = VfxBlending.ADDITIVE,
        texture = VfxTexture.Asset("vfx/textures/spark.png"),
        maxParticles = 64,
        overflow = Overflow.RECYCLE_OLDEST,
        burst = 12,
        rate = 36f,
        lifetimeMin = 0.4f,
        lifetimeMax = 0.8f,
        sizeMin = 0.02f,
        sizeMax = 0.045f,
        emitter = EmitterParams(
            shape = EmitterShape.SPHERE,
            center = VfxVec3.ZERO,
            radius = 0.06f,
            speedMin = 0.08f,
            speedMax = 0.25f,
        ),
        behaviors = listOf(
            BehaviorParams.Drag(coefficient = 2.5f),
            BehaviorParams.Gravity(VfxVec3(0f, -0.15f, 0f)),
            BehaviorParams.ColorOverLife(
                listOf(
                    ColorKey(0.00f, VfxColor(1.0f, 0.97f, 0.80f, 1.0f)),
                    ColorKey(0.45f, VfxColor(0.55f, 0.85f, 1.0f, 0.85f)),
                    ColorKey(1.00f, VfxColor(0.25f, 0.45f, 1.0f, 0.0f)),
                ),
            ),
            BehaviorParams.SizeOverLife(
                listOf(ScalarKey(0f, 1f), ScalarKey(1f, 0.2f)),
            ),
        ),
        // 应援模式缺省锚定：调用方不给锚点 → 右手骨骼跟随（配饰 lightstick 同款挂点）
        defaultBone = "rightHand",
    )

    /** 内置目录（id → spec，保留声明序；app sidecar 可覆盖同名项）。 */
    val all: Map<String, VfxSpec> = linkedMapOf(
        sparkBurst.id to sparkBurst,
        snow.id to snow,
        ringPulse.id to ringPulse,
        rain.id to rain,
        fire.id to fire,
        confetti.id to confetti,
        petals.id to petals,
        lightstickSpark.id to lightstickSpark,
    )

    /** 按 id 查找（大小写不敏感）。 */
    fun find(id: String): VfxSpec? = all[id.lowercase()]
}
