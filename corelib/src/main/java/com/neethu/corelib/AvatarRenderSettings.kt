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
 * @property softShadows Use PCSS soft shadows instead of hard PCF.
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
 * @property showFps Whether the consuming UI should show a live FPS counter.
 *   The renderer always measures frame rate and reports it via
 *   [AvatarController.fps]; this flag only controls whether the overlay is
 *   displayed (consumed by the demo/UI layer, ignored by the renderer).
 */
data class AvatarRenderSettings(
    val iblIntensity: Float = 5_000f,
    val iblRotationDegrees: Float = 0f,
    val lightingRig: LightingRig = LightingRig.STUDIO,
    val shadowMapSize: Int = 2048,
    val softShadows: Boolean = true,
    val contactShadows: Boolean = false,
    val ambientOcclusion: AmbientOcclusionQuality = AmbientOcclusionQuality.HIGH,
    val toneMapping: ToneMappingMode = ToneMappingMode.ACES,
    val bloomEnabled: Boolean = false,
    val bloomStrength: Float = 0.15f,
    val antiAliasing: AntiAliasingMode = AntiAliasingMode.FXAA,
    val depthOfFieldEnabled: Boolean = false,
    val enhanceMaterials: Boolean = false,
    val showFps: Boolean = true,
)

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

    /** Mainstream: key + fill, 2048 soft shadows, SSAO, ACES + light bloom. */
    MEDIUM,

    /** High: full three-point rig, GTAO, 2048 soft shadows, ACES + bloom. */
    HIGH,

    /** Next-gen: 4096 shadows + contact shadows, GTAO, TAA, bloom, DoF, material upgrades. */
    ULTRA,
    ;

    /**
     * Build the preset's settings. Like [AvatarRenderSettings.iblIntensity]
     * and [AvatarRenderSettings.iblRotationDegrees], [showFps] is passed
     * through so applying a preset does not reset user-tuned values.
     */
    fun toRenderSettings(
        iblIntensity: Float = 5_000f,
        iblRotationDegrees: Float = 0f,
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
            showFps = showFps,
        )
        MEDIUM -> AvatarRenderSettings(
            iblIntensity = iblIntensity,
            iblRotationDegrees = iblRotationDegrees,
            lightingRig = LightingRig.KEY_FILL,
            shadowMapSize = 2048,
            softShadows = true,
            contactShadows = false,
            ambientOcclusion = AmbientOcclusionQuality.STANDARD,
            toneMapping = ToneMappingMode.ACES,
            bloomEnabled = true,
            bloomStrength = 0.15f,
            antiAliasing = AntiAliasingMode.FXAA,
            depthOfFieldEnabled = false,
            enhanceMaterials = false,
            showFps = showFps,
        )
        HIGH -> AvatarRenderSettings(
            iblIntensity = iblIntensity,
            iblRotationDegrees = iblRotationDegrees,
            lightingRig = LightingRig.STUDIO,
            shadowMapSize = 2048,
            softShadows = true,
            contactShadows = false,
            ambientOcclusion = AmbientOcclusionQuality.HIGH,
            toneMapping = ToneMappingMode.ACES,
            bloomEnabled = true,
            bloomStrength = 0.20f,
            antiAliasing = AntiAliasingMode.FXAA,
            depthOfFieldEnabled = false,
            enhanceMaterials = false,
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
            showFps = showFps,
        )
    }
}
