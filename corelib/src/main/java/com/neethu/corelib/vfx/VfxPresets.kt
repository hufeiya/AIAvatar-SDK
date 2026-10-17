package com.neethu.corelib.vfx

/**
 * 内置常量预设表（画质升级 2026-10-17 扩到 10 配方，docs/vfx-quality-upgrade.md）：
 * SDK 开箱即用；app 的 `assets/vfx/presets.json` sidecar 经 VfxCatalog 三层
 * 合并，app 条目覆盖同名内置项（真机调参以 sidecar 为准）。
 *
 * 坐标口径：世界单位=米，锚点 (0,0,0)=模型脚底（transformToUnitCube 后）。
 * 画质升级要点：fire/snow 复合层化（火焰=火苗+辉光+火星+烟+照明灯，雪=远景
 * Billboard+近景 CROSS 翻滚）；petals CROSS 空间翻滚；rain/雪落地碰撞；
 * 新增舞台灯组（stage_lights 扫摆彩光 / spotlight 追光）。
 *
 * 湍流自 2026-10-17 起为加速度量纲（米/秒²，apply 内乘 dt——修复帧率漂移），
 * 强度均已按新量纲标定。
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

    /**
     * 漫天飞雪（画质升级复合版）：远景 300 Billboard 柔点 + 近景 48 CROSS
     * 雪晶空间翻滚落地消融；两层共享同一风场（阵风同相，头发同摆）。
     */
    val snow = VfxSpec(
        id = "snow",
        label = "漫天飞雪",
        layers = listOf(
            // 远景：便宜 Billboard，宽域铺满取景框
            VfxSpec(
                id = "snow_far",
                blending = VfxBlending.TRANSPARENT,
                texture = VfxTexture.SoftDot,
                maxParticles = 300,
                overflow = Overflow.RECYCLE_OLDEST,
                rate = 50f,
                lifetimeMin = 5f,
                lifetimeMax = 7.5f,
                sizeMin = 0.02f,
                sizeMax = 0.038f,
                emitter = EmitterParams(
                    shape = EmitterShape.BOX,
                    center = VfxVec3(0f, 2.6f, 0f),
                    halfExtents = VfxVec3(1.7f, 0.25f, 1.7f),
                    direction = VfxVec3.DOWN,
                    speedMin = 0.25f,
                    speedMax = 0.5f,
                ),
                behaviors = listOf(
                    BehaviorParams.Gravity(VfxVec3(0f, -0.55f, 0f)),
                    BehaviorParams.ColorOverLife(
                        listOf(
                            ColorKey(0.00f, VfxColor(1f, 1f, 1f, 0f)),
                            ColorKey(0.15f, VfxColor(1f, 1f, 1f, 0.9f)),
                            ColorKey(1.00f, VfxColor(1f, 1f, 1f, 0f)),
                        ),
                    ),
                    BehaviorParams.SizeOverLife(
                        listOf(ScalarKey(0f, 0.6f), ScalarKey(1f, 1f)),
                    ),
                ),
                wind = WindParams(
                    direction = VfxVec3(0.7f, 0f, 0.3f),
                    strength = 0.55f,
                    gust = 0.5f,
                    gustFrequency = 0.13f,
                    swirl = 0.30f,
                    swirlScale = 1.4f,
                    responsiveness = 1.2f,
                ),
            ),
            // 近景：CROSS 雪晶三维翻滚，落地消融
            VfxSpec(
                id = "snow_near",
                blending = VfxBlending.TRANSPARENT,
                texture = VfxTexture.Asset("vfx/textures/snowflake.png"),
                maxParticles = 48,
                overflow = Overflow.RECYCLE_OLDEST,
                rate = 8f,
                lifetimeMin = 4f,
                lifetimeMax = 6f,
                sizeMin = 0.05f,
                sizeMax = 0.085f,
                emitter = EmitterParams(
                    shape = EmitterShape.BOX,
                    center = VfxVec3(0f, 2.3f, 0f),
                    halfExtents = VfxVec3(1.7f, 0.25f, 1.7f),
                    direction = VfxVec3.DOWN,
                    speedMin = 0.25f,
                    speedMax = 0.5f,
                ),
                behaviors = listOf(
                    BehaviorParams.Gravity(VfxVec3(0f, -0.5f, 0f)),
                    BehaviorParams.GroundCollision(groundY = 0f, fadeSeconds = 0.7f, friction = 3f),
                    BehaviorParams.ColorOverLife(
                        listOf(
                            ColorKey(0.00f, VfxColor(1f, 1f, 1f, 0f)),
                            ColorKey(0.12f, VfxColor(1f, 1f, 1f, 0.95f)),
                            ColorKey(1.00f, VfxColor(1f, 1f, 1f, 0f)),
                        ),
                    ),
                ),
                wind = WindParams(
                    direction = VfxVec3(0.7f, 0f, 0.3f),
                    strength = 0.55f,
                    gust = 0.5f,
                    gustFrequency = 0.13f,
                    swirl = 0.22f,
                    swirlScale = 1.4f,
                    responsiveness = 1.2f,
                ),
                renderMode = VfxRenderMode.CROSS,
                tumbleMin = 60f,
                tumbleMax = 170f,
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

    /** 阵雨（画质升级）：雨丝落地即逝（GroundCollision 到地短促淡出）。 */
    val rain = VfxSpec(
        id = "rain",
        label = "阵雨",
        blending = VfxBlending.TRANSPARENT,
        texture = VfxTexture.Asset("vfx/textures/streak.png"),
        maxParticles = 220,
        overflow = Overflow.RECYCLE_OLDEST,
        rate = 150f,
        lifetimeMin = 1.0f,
        lifetimeMax = 1.5f,
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
            BehaviorParams.GroundCollision(groundY = 0f, fadeSeconds = 0.10f, friction = 5f),
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

    /**
     * 篝火/舞台火焰（画质升级复合版）：火苗 64 帧高密度 flipbook + 底部辉光
     * + 火星迸溅 + 分形烟 + 闪烁暖光（真灯照亮地板与角色）。
     */
    val fire = VfxSpec(
        id = "fire",
        label = "篝火",
        texture = VfxTexture.Asset("vfx/textures/flame_8x8.png"),
        layers = listOf(
            // 火苗主体：64 帧 flipbook（8×8, 128px/帧），寿命即动画周期
            VfxSpec(
                id = "fire_flame",
                blending = VfxBlending.ADDITIVE,
                texture = VfxTexture.Asset("vfx/textures/flame_8x8.png"),
                maxParticles = 64,
                overflow = Overflow.RECYCLE_OLDEST,
                rate = 34f,
                lifetimeMin = 0.75f,
                lifetimeMax = 1.0f,
                sizeMin = 0.26f,
                sizeMax = 0.42f,
                emitter = EmitterParams(
                    shape = EmitterShape.CONE,
                    center = VfxVec3(0f, 0.02f, 0f),
                    radius = 0.15f,
                    direction = VfxVec3(0f, 1f, 0f),
                    speedMin = 0.8f,
                    speedMax = 1.3f,
                    radialFraction = 0.3f,
                ),
                behaviors = listOf(
                    BehaviorParams.Drag(coefficient = 0.9f),
                    BehaviorParams.Turbulence(strength = 1.1f, scale = 2.0f, evolution = 1.1f),
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
                flipbook = FlipbookParams(columns = 8, rows = 8),
            ),
            // 底部辉光：大而淡的暖光晕（热度感）
            VfxSpec(
                id = "fire_glow",
                blending = VfxBlending.ADDITIVE,
                texture = VfxTexture.SoftDot,
                maxParticles = 20,
                overflow = Overflow.RECYCLE_OLDEST,
                rate = 11f,
                lifetimeMin = 0.9f,
                lifetimeMax = 1.3f,
                sizeMin = 0.40f,
                sizeMax = 0.60f,
                emitter = EmitterParams(
                    shape = EmitterShape.CONE,
                    center = VfxVec3(0f, 0.08f, 0f),
                    radius = 0.12f,
                    direction = VfxVec3(0f, 1f, 0f),
                    speedMin = 0.25f,
                    speedMax = 0.45f,
                ),
                behaviors = listOf(
                    BehaviorParams.Drag(coefficient = 0.6f),
                    BehaviorParams.ColorOverLife(
                        listOf(
                            ColorKey(0.00f, VfxColor(1.0f, 0.55f, 0.18f, 0.0f)),
                            ColorKey(0.25f, VfxColor(1.0f, 0.50f, 0.15f, 0.5f)),
                            ColorKey(1.00f, VfxColor(0.8f, 0.25f, 0.05f, 0.0f)),
                        ),
                    ),
                    BehaviorParams.SizeOverLife(
                        listOf(ScalarKey(0f, 0.7f), ScalarKey(0.5f, 1.15f), ScalarKey(1f, 0.9f)),
                    ),
                ),
            ),
            // 火星：小亮点迸溅 + 重力回落
            VfxSpec(
                id = "fire_sparks",
                blending = VfxBlending.ADDITIVE,
                texture = VfxTexture.Asset("vfx/textures/spark.png"),
                maxParticles = 48,
                overflow = Overflow.RECYCLE_OLDEST,
                rate = 13f,
                lifetimeMin = 0.5f,
                lifetimeMax = 1.1f,
                sizeMin = 0.006f,
                sizeMax = 0.018f,
                emitter = EmitterParams(
                    shape = EmitterShape.CONE,
                    center = VfxVec3(0f, 0.10f, 0f),
                    radius = 0.06f,
                    direction = VfxVec3(0f, 1f, 0f),
                    speedMin = 1.1f,
                    speedMax = 2.2f,
                    radialFraction = 0.5f,
                ),
                behaviors = listOf(
                    BehaviorParams.Drag(coefficient = 1.2f),
                    BehaviorParams.Gravity(VfxVec3(0f, -2.2f, 0f)),
                    BehaviorParams.Turbulence(strength = 1.6f, scale = 2.6f, evolution = 1.6f),
                    BehaviorParams.ColorOverLife(
                        listOf(
                            ColorKey(0.00f, VfxColor(1.0f, 0.95f, 0.70f, 1.0f)),
                            ColorKey(0.45f, VfxColor(1.0f, 0.55f, 0.15f, 0.9f)),
                            ColorKey(1.00f, VfxColor(0.9f, 0.20f, 0.03f, 0.0f)),
                        ),
                    ),
                    BehaviorParams.SizeOverLife(
                        listOf(ScalarKey(0f, 1f), ScalarKey(1f, 0.3f)),
                    ),
                ),
            ),
            // 烟：分形烟团慢速上升（淡灰，透明档）
            VfxSpec(
                id = "fire_smoke",
                blending = VfxBlending.TRANSPARENT,
                texture = VfxTexture.Asset("vfx/textures/smoke.png"),
                maxParticles = 40,
                overflow = Overflow.RECYCLE_OLDEST,
                rate = 9f,
                lifetimeMin = 1.6f,
                lifetimeMax = 2.4f,
                sizeMin = 0.10f,
                sizeMax = 0.16f,
                startColor = VfxColor(0.30f, 0.29f, 0.28f, 1f),
                emitter = EmitterParams(
                    shape = EmitterShape.CONE,
                    center = VfxVec3(0f, 0.28f, 0f),
                    radius = 0.10f,
                    direction = VfxVec3(0f, 1f, 0f),
                    speedMin = 0.45f,
                    speedMax = 0.75f,
                    radialFraction = 0.35f,
                ),
                behaviors = listOf(
                    BehaviorParams.Drag(coefficient = 0.5f),
                    BehaviorParams.Turbulence(strength = 1.4f, scale = 1.8f, evolution = 0.9f),
                    BehaviorParams.ColorOverLife(
                        listOf(
                            ColorKey(0.00f, VfxColor(1f, 1f, 1f, 0f)),
                            ColorKey(0.25f, VfxColor(1f, 1f, 1f, 0.16f)),
                            ColorKey(0.70f, VfxColor(1f, 1f, 1f, 0.14f)),
                            ColorKey(1.00f, VfxColor(1f, 1f, 1f, 0f)),
                        ),
                    ),
                    BehaviorParams.SizeOverLife(
                        listOf(ScalarKey(0f, 0.5f), ScalarKey(1f, 1.6f)),
                    ),
                ),
                spinMin = -30f,
                spinMax = 30f,
            ),
        ),
        // 火焰照明：闪烁暖光点灯（照亮地板/场景；MToon 角色走 featured 推送）
        lights = listOf(
            StageLightParams(
                type = StageLightType.POINT,
                color = VfxColor(1.0f, 0.55f, 0.20f, 1f),
                intensityCandela = 26_000f,
                position = VfxVec3(0f, 0.55f, 0f),
                rangeMeters = 7f,
                beamVisible = false,
                flickerStrength = 0.5f,
                flickerFrequency = 8.5f,
            ),
        ),
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
            // 湍流已改加速度量纲（×dt）：原 0.18（帧踢量）→ ~8（米/秒²）
            BehaviorParams.Turbulence(strength = 8f, scale = 1.6f, evolution = 0.7f),
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

    /**
     * 风吹花瓣（画质升级）：CROSS 空间翻滚 + WindField 风送（替换原 Sway+
     * Turbulence 组合，湍流改加速度量纲）+ 落地消融。
     */
    val petals = VfxSpec(
        id = "petals",
        label = "风吹花瓣",
        blending = VfxBlending.TRANSPARENT,
        texture = VfxTexture.Asset("vfx/textures/petal.png"),
        maxParticles = 150,
        overflow = Overflow.RECYCLE_OLDEST,
        rate = 24f,
        lifetimeMin = 3.5f,
        lifetimeMax = 5.5f,
        sizeMin = 0.035f,
        sizeMax = 0.06f,
        emitter = EmitterParams(
            shape = EmitterShape.BOX,
            // 侧方来风：从角色左侧（-X）半空吹向右侧
            center = VfxVec3(-1.3f, 1.5f, 0f),
            halfExtents = VfxVec3(0.3f, 1.1f, 1.6f),
            direction = VfxVec3(1f, -0.1f, 0f),
            speedMin = 0.5f,
            speedMax = 0.9f,
        ),
        behaviors = listOf(
            BehaviorParams.Gravity(VfxVec3(0f, -0.2f, 0f)),
            BehaviorParams.GroundCollision(groundY = 0f, fadeSeconds = 0.6f, friction = 3f),
            BehaviorParams.ColorOverLife(
                listOf(
                    ColorKey(0.00f, VfxColor(1f, 1f, 1f, 0f)),
                    ColorKey(0.10f, VfxColor(1f, 1f, 1f, 0.95f)),
                    ColorKey(0.85f, VfxColor(1f, 1f, 1f, 0.95f)),
                    ColorKey(1.00f, VfxColor(1f, 1f, 1f, 0f)),
                ),
            ),
        ),
        wind = WindParams(
            direction = VfxVec3(1f, 0f, 0.15f),
            strength = 0.5f,
            gust = 0.45f,
            gustFrequency = 0.16f,
            swirl = 0.35f,
            swirlScale = 1.1f,
            responsiveness = 1.4f,
        ),
        renderMode = VfxRenderMode.CROSS,
        tumbleMin = 80f,
        tumbleMax = 220f,
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

    /**
     * 舞台扫光（画质升级新增，能力线 A）：三束彩色光锥绕角色扫摆 + 地面
     * 光斑；真 SPOT 灯照亮地板与 PBR 场景，MToon 角色吃 featured 推送。
     */
    val stageLights = VfxSpec(
        id = "stage_lights",
        label = "舞台扫光",
        lights = listOf(
            StageLightParams(
                color = VfxColor(1.0f, 0.25f, 0.55f, 1f),          // 品红
                intensityCandela = 150_000f,
                position = VfxVec3(1.9f, 2.7f, 0.7f),
                target = VfxVec3(-0.25f, 0.8f, 0f),
                coneInnerDeg = 5f,
                coneOuterDeg = 9f,
                rangeMeters = 10f,
                sweepSpeedDegPerSec = 22f,
                sweepAmplitudeDeg = 42f,
                sweepPhaseDeg = 0f,
            ),
            StageLightParams(
                color = VfxColor(0.20f, 0.75f, 1.0f, 1f),          // 青
                intensityCandela = 150_000f,
                position = VfxVec3(-1.9f, 2.7f, 0.7f),
                target = VfxVec3(0.25f, 0.8f, 0f),
                coneInnerDeg = 5f,
                coneOuterDeg = 9f,
                rangeMeters = 10f,
                sweepSpeedDegPerSec = 18f,
                sweepAmplitudeDeg = 36f,
                sweepPhaseDeg = 120f,
                realLight = false,   // 真灯前向成本 ≈−10fps/盏：B/C 纯光束+光斑
            ),
            StageLightParams(
                color = VfxColor(1.0f, 0.75f, 0.25f, 1f),          // 琥珀
                intensityCandela = 120_000f,
                position = VfxVec3(0.4f, 2.9f, -1.3f),
                target = VfxVec3(-0.1f, 0.9f, 0.4f),
                coneInnerDeg = 4f,
                coneOuterDeg = 8f,
                rangeMeters = 10f,
                sweepSpeedDegPerSec = 15f,
                sweepAmplitudeDeg = 30f,
                sweepPhaseDeg = 240f,
                realLight = false,
            ),
        ),
    )

    /** 追光（画质升级新增）：单束冷白光从上前方针定角色，微呼吸闪烁。 */
    val spotlight = VfxSpec(
        id = "spotlight",
        label = "追光",
        lights = listOf(
            StageLightParams(
                color = VfxColor(0.95f, 0.97f, 1.0f, 1f),
                intensityCandela = 260_000f,
                position = VfxVec3(0.7f, 2.8f, 1.1f),
                target = VfxVec3(0f, 0.95f, 0f),
                coneInnerDeg = 5f,
                coneOuterDeg = 9f,
                rangeMeters = 16f,
                flickerStrength = 0.05f,
                flickerFrequency = 0.6f,
            ),
        ),
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
        stageLights.id to stageLights,
        spotlight.id to spotlight,
    )

    /** 按 id 查找（大小写不敏感）。 */
    fun find(id: String): VfxSpec? = all[id.lowercase()]
}
