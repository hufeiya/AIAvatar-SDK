package com.neethu.corelib

/**
 * Light rig used to light the avatar. The rig is built on top of the IBL:
 *
 *  - [KEY_ONLY] — a single warm key light with shadows plus IBL. Cheapest option.
 *  - [KEY_FILL] — key light + a cool dim fill light from the opposite side.
 *  - [STUDIO]   — full photographic three-point setup: key + fill + a strong
 *    cool rim/back light that outlines hair and shoulders.
 */
enum class LightingRig {
    KEY_ONLY,
    KEY_FILL,
    STUDIO,
}

/**
 * Screen-space ambient occlusion level.
 *
 * [STANDARD] uses Filament's SAO algorithm at medium sample counts,
 * [HIGH] uses ground-truth AO (GTAO) at high quality with a bilateral filter,
 * which is what produces the contact darkening in eye sockets, nostrils and
 * cloth creases that reads as "not floating".
 */
enum class AmbientOcclusionQuality {
    OFF,
    STANDARD,
    HIGH,
}

/**
 * Tone mapping operator applied by the post-processed color grading pass.
 *
 * [ACES] is the film-industry standard (Unreal default) with a gentle
 * highlight roll-off. [FILMIC] is punchier; [LINEAR] is unclamped and will
 * blow out highlights on metallic/eye highlights.
 */
enum class ToneMappingMode {
    LINEAR,
    FILMIC,
    ACES,
}

/**
 * Anti-aliasing strategy. TAA is the highest quality option (it also removes
 * high-frequency shimmer from hair and normal maps) but may smear during fast
 * motion; FXAA is the cheap fallback; MSAA_4X keeps geometry edges crisp.
 */
enum class AntiAliasingMode {
    NONE,
    FXAA,
    MSAA_4X,
    TAA,
}

/**
 * Material/shading model used to render the avatar model itself.
 *
 * [PBR] is the default gltfio metallic-roughness path (studio-lit realism).
 * [MTOON] swaps every MToon-flagged material of the model (VRM 0.x
 * `VRM/MToon` shaders and VRM 1.0 `VRMC_materials_mtoon` extensions) to the
 * three-vrm-compatible MToon cel-shading: toony two-tone diffuse, parametric
 * rim, matcap, and an inverted-hull outline drawn as duplicated primitives.
 * Non-MToon materials of the same model keep their PBR look.
 *
 * Switching modes reloads the model (material replacement happens at load).
 */
enum class RenderMode {
    PBR,
    MTOON,
}

/**
 * Tunable PBR rendering settings for the avatar view.
 *
 * Pass an instance to [AvatarConfig.renderSettings] for the initial state and
 * push updates at runtime via [AvatarController.updateRenderSettings] — every
 * field is hot-swappable without reloading the model, except
 * [enhanceMaterials] which is applied when a model loads (turn it off and
 * reload the model to fully revert material tweaks).
 *
 * @property iblIntensity Intensity of the IBL environment in lux (0–50 000).
 * @property iblRotationDegrees Rotation of the IBL environment around Y, in degrees.
 * @property lightingRig Studio light rig level, see [LightingRig].
 * @property shadowMapSize Shadow map resolution of the key light (1024/2048/4096).
 *   1024 holds 60 FPS on mid-range mobile GPUs; 2048 already costs ~25% FPS on a
 *   2019 flagship (Adreno 640) because the 3-point rig keeps a real shadow-casting
 *   key light in every rig level (see [softShadows] for the hard cliff).
 * @property softShadows Use PCSS soft shadows instead of hard PCF. Opt-in only:
 *   Filament's PCSS fragment path is catastrophically slow on Adreno GPUs
 *   (measured 1–5 FPS on an Adreno 640 flagship, independent of shadow map
 *   size), so this defaults to off and the HIGH tier ships PCF as well.
 * @property contactShadows Screen-space contact shadows on the key light for
 *   micro shadows (eyelashes, nose wings). Costs a small amount of GPU bandwidth.
 * @property ambientOcclusion SSAO/GTAO level, see [AmbientOcclusionQuality].
 * @property toneMapping Post-processing tone mapper, see [ToneMappingMode].
 * @property bloomEnabled Enable the bloom pass.
 * @property bloomStrength Bloom strength (0.0–0.5).
 * @property antiAliasing Anti-aliasing strategy, see [AntiAliasingMode].
 * @property depthOfFieldEnabled Enable depth of field focused on the model
 *   (close-up "macro lens" look; disable for full-body shots to save GPU time).
 * @property enhanceMaterials Best-effort material upgrades: clear coat on
 *   eye-named materials and lower roughness on skin/hair-named materials.
 *   Takes full effect only on models whose materials declare the matching
 *   features (e.g. KHR_materials_clearcoat); subsurface skin and anisotropic
 *   hair cannot be switched onto gltfio ubershader materials at runtime.
 * @property renderMode Avatar material/shading model, see [RenderMode].
 *   Unlike the other fields this one is NOT hot-swappable: switching reloads
 *   the model (see [AvatarController.updateRenderSettings]).
 * @property showFps Whether the consuming UI should show a live FPS counter.
 *   The renderer always measures frame rate and reports it via
 *   [AvatarController.fps]; this flag only controls whether the overlay is
 *   displayed (consumed by the demo/UI layer, ignored by the renderer).
 */
data class AvatarRenderSettings(
    val iblIntensity: Float = 13_000f,
    val iblRotationDegrees: Float = 0f,
    val lightingRig: LightingRig = LightingRig.STUDIO,
    val shadowMapSize: Int = 1024,
    val softShadows: Boolean = false,
    val contactShadows: Boolean = false,
    val ambientOcclusion: AmbientOcclusionQuality = AmbientOcclusionQuality.HIGH,
    val toneMapping: ToneMappingMode = ToneMappingMode.ACES,
    val bloomEnabled: Boolean = false,
    val bloomStrength: Float = 0.15f,
    val antiAliasing: AntiAliasingMode = AntiAliasingMode.FXAA,
    val depthOfFieldEnabled: Boolean = false,
    val enhanceMaterials: Boolean = false,
    val renderMode: RenderMode = RenderMode.PBR,
    val showFps: Boolean = true,
)

/**
 * 舞台氛围预设(P0.5 智能体工具 `set_ambience`,docs/agent-tools-expansion.md §2.5):
 * 现有渲染参数的组合预置,零渲染器改动——只动 [AvatarRenderSettings.lightingRig]/
 * [AvatarRenderSettings.iblIntensity]/[AvatarRenderSettings.bloomEnabled] 三个字段,
 * 阴影/AA/色调映射/renderMode 一概不碰(PCSS 软阴影在 Adreno 上是 1-5 FPS 悬崖,
 * 永不进预设)。
 *
 * ⚠预设名按真实效果命名:当前渲染系统没有色温参数(色调映射只有模式选择,IBL
 * rotation 不等价于暖光),「暖色/赛博」做不到,不承诺。
 *
 * 应用方式必须是「当前设置 copy 变换」([applyTo]),不许全量构造——否则用户在
 * 设置页调过的阴影/AA/renderMode 会被打回默认。
 */
enum class AmbiencePreset {
    /** 摄影棚基准光(现状默认):三灯 STUDIO + 13000 lux,无 bloom。 */
    STUDIO,

    /** 明亮日光:三灯 STUDIO + 22000 lux,无 bloom。 */
    BRIGHT,

    /** 昏暗夜话:key + 补光 + 3500 lux,无 bloom。 */
    DIM,

    /** 单灯聚光舞台感:仅主灯 + 5000 lux,无 bloom。 */
    SPOTLIGHT,

    /** 暗夜朦胧:仅主灯 + 2500 lux + 轻 bloom(0.25)。 */
    DREAMY,
    ;

    /**
     * 在 [settings] 的副本上只改氛围三字段,其余(阴影/AA/色调映射/renderMode/
     * enhanceMaterials 等用户调过的值)原样保留。
     */
    fun applyTo(settings: AvatarRenderSettings): AvatarRenderSettings = when (this) {
        STUDIO -> settings.copy(lightingRig = LightingRig.STUDIO, iblIntensity = 13_000f, bloomEnabled = false)
        BRIGHT -> settings.copy(lightingRig = LightingRig.STUDIO, iblIntensity = 22_000f, bloomEnabled = false)
        DIM -> settings.copy(lightingRig = LightingRig.KEY_FILL, iblIntensity = 3_500f, bloomEnabled = false)
        SPOTLIGHT -> settings.copy(lightingRig = LightingRig.KEY_ONLY, iblIntensity = 5_000f, bloomEnabled = false)
        DREAMY -> settings.copy(
            lightingRig = LightingRig.KEY_ONLY,
            iblIntensity = 2_500f,
            bloomEnabled = true,
            bloomStrength = 0.25f,
        )
    }
}

/**
 * One-click quality presets built on top of [AvatarRenderSettings].
 *
 * Presets deliberately leave the IBL intensity/rotation untouched so users can
 * tune the environment independently of the quality tier. Apply via
 * [toRenderSettings].
 */
enum class QualityPreset {
    /** Low-end devices: single key light, 1024 shadows, no AO, FXAA. Mobile-60fps target. */
    LOW,

    /** Mainstream: key + fill, 1024 shadows, SSAO, ACES + light bloom. */
    MEDIUM,

    /** High: full three-point rig, GTAO, 2048 shadows, ACES + bloom. */
    HIGH,

    /** Next-gen: 4096 shadows + contact shadows, GTAO, TAA, bloom, DoF, material upgrades. */
    ULTRA,
    ;

    /**
     * Build the preset's settings. Like [AvatarRenderSettings.iblIntensity],
     * [AvatarRenderSettings.iblRotationDegrees] and [AvatarRenderSettings.renderMode],
     * [showFps] is passed through so applying a preset does not reset
     * user-tuned values.
     */
    fun toRenderSettings(
        iblIntensity: Float = 13_000f,
        iblRotationDegrees: Float = 0f,
        renderMode: RenderMode = RenderMode.PBR,
        showFps: Boolean = true,
    ): AvatarRenderSettings = when (this) {
        LOW -> AvatarRenderSettings(
            iblIntensity = iblIntensity,
            iblRotationDegrees = iblRotationDegrees,
            lightingRig = LightingRig.KEY_ONLY,
            shadowMapSize = 1024,
            softShadows = false,
            contactShadows = false,
            ambientOcclusion = AmbientOcclusionQuality.OFF,
            toneMapping = ToneMappingMode.ACES,
            bloomEnabled = false,
            antiAliasing = AntiAliasingMode.FXAA,
            depthOfFieldEnabled = false,
            enhanceMaterials = false,
            renderMode = renderMode,
            showFps = showFps,
        )
        MEDIUM -> AvatarRenderSettings(
            iblIntensity = iblIntensity,
            iblRotationDegrees = iblRotationDegrees,
            lightingRig = LightingRig.KEY_FILL,
            shadowMapSize = 1024,
            softShadows = false,
            contactShadows = false,
            ambientOcclusion = AmbientOcclusionQuality.STANDARD,
            toneMapping = ToneMappingMode.ACES,
            bloomEnabled = true,
            bloomStrength = 0.15f,
            antiAliasing = AntiAliasingMode.FXAA,
            depthOfFieldEnabled = false,
            enhanceMaterials = false,
            renderMode = renderMode,
            showFps = showFps,
        )
        HIGH -> AvatarRenderSettings(
            iblIntensity = iblIntensity,
            iblRotationDegrees = iblRotationDegrees,
            lightingRig = LightingRig.STUDIO,
            shadowMapSize = 2048,
            softShadows = false,
            contactShadows = false,
            ambientOcclusion = AmbientOcclusionQuality.HIGH,
            toneMapping = ToneMappingMode.ACES,
            bloomEnabled = true,
            bloomStrength = 0.20f,
            antiAliasing = AntiAliasingMode.FXAA,
            depthOfFieldEnabled = false,
            enhanceMaterials = false,
            renderMode = renderMode,
            showFps = showFps,
        )
        ULTRA -> AvatarRenderSettings(
            iblIntensity = iblIntensity,
            iblRotationDegrees = iblRotationDegrees,
            lightingRig = LightingRig.STUDIO,
            shadowMapSize = 4096,
            softShadows = true,
            contactShadows = true,
            ambientOcclusion = AmbientOcclusionQuality.HIGH,
            toneMapping = ToneMappingMode.ACES,
            bloomEnabled = true,
            bloomStrength = 0.25f,
            antiAliasing = AntiAliasingMode.TAA,
            depthOfFieldEnabled = true,
            enhanceMaterials = true,
            renderMode = renderMode,
            showFps = showFps,
        )
    }
}
