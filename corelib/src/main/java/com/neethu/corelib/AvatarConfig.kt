package com.neethu.corelib

/**
 * Configuration for the avatar rendering environment.
 *
 * Pass this to [AvatarView] (Compose) or [AvatarSurfaceView] (classic View)
 * to control lighting, materials, and interaction. These are "set once"
 * initialization parameters — for transient actions, use [AvatarBehavior]
 * instead.
 *
 * ```kotlin
 * AvatarView(
 *     controller = controller,
 *     config = AvatarConfig(
 *         iblPath = "studio_ibl.ktx",
 *         backgroundColor = floatArrayOf(0.1f, 0.1f, 0.15f, 1.0f)
 *     )
 * )
 * ```
 *
 * Zero-asset quick start: `AvatarConfig()` ships with a built-in IBL, so the
 * model is lit out of the box without any asset files. Pass your own KTX to
 * [iblPath] for a custom look, or [IBL_NONE] to disable environment lighting
 * entirely (three-light rig only).
 *
 * @property iblPath Path to an IBL KTX file in assets; `null` (default) loads
 *   the SDK's built-in environment light, [IBL_NONE] disables IBL. The built-in
 *   and custom KTX come from Google Filament's env generator (Apache-2.0).
 * @property backgroundColor RGBA background color (range 0.0–1.0 each).
 * @property enableTouch Whether the user can rotate/pan the model by touch.
 * @property enableSpringBone Whether to enable spring bone physics for hair/clothing dynamics.
 * @property renderSettings Initial PBR render quality settings. Later changes
 *   should go through [AvatarController.updateRenderSettings] since this config
 *   is only read once when the view is created.
 */
data class AvatarConfig(
    val iblPath: String? = null,
    val backgroundColor: FloatArray = floatArrayOf(0.2f, 0.2f, 0.25f, 1.0f),
    val enableTouch: Boolean = true,
    val enableSpringBone: Boolean = true,
    val renderSettings: AvatarRenderSettings = AvatarRenderSettings(),
) {
    companion object {
        /**
         * 内置默认 IBL 的 assets 路径（随库打包，`AvatarConfig()` 零资产开箱
         * 即得环境光）。来自 Google Filament 仓库的环境贴图生成器（Apache-2.0）。
         */
        const val BUILT_IN_IBL = "corelib/default_env.ktx"

        /**
         * 显式禁用环境光的哨兵值：`AvatarConfig(iblPath = AvatarConfig.IBL_NONE)`
         * 只保留三灯光 rig，任何间接光（反射/漫射环境项）都不加载。
         */
        const val IBL_NONE = "__avatar_no_ibl__"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AvatarConfig) return false
        return iblPath == other.iblPath &&
                backgroundColor.contentEquals(other.backgroundColor) &&
                enableSpringBone == other.enableSpringBone &&
                enableTouch == other.enableTouch &&
                renderSettings == other.renderSettings
    }

    override fun hashCode(): Int {
        var result = iblPath?.hashCode() ?: 0
        result = 31 * result + backgroundColor.contentHashCode()
        result = 31 * result + enableTouch.hashCode()
        result = 31 * result + enableSpringBone.hashCode()
        result = 31 * result + renderSettings.hashCode()
        return result
    }
}
