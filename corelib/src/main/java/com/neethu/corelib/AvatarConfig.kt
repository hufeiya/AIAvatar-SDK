package com.neethu.corelib

/**
 * Configuration for the avatar rendering environment.
 *
 * Pass this to [AvatarView] to control lighting, materials, and interaction.
 * These are "set once" initialization parameters — for transient actions,
 * use [AvatarBehavior] instead.
 *
 * ```kotlin
 * AvatarView(
 *     controller = controller,
 *     config = AvatarConfig(
 *         iblPath = "studio_ibl.ktx",
 *         enableMToon = true,
 *         backgroundColor = floatArrayOf(0.1f, 0.1f, 0.15f, 1.0f)
 *     )
 * )
 * ```
 *
 * @property iblPath Path to an IBL KTX file in assets for environment lighting.
 * @property enableMToon Whether to use the MToon toon shader for VRM models.
 *   When `false`, Filament's default PBR pipeline is used.
 * @property backgroundColor RGBA background color (range 0.0–1.0 each).
 * @property enableTouch Whether the user can rotate/pan the model by touch.
 * @property enableSpringBone Whether to enable spring bone physics for hair/clothing dynamics.
 */
data class AvatarConfig(
    val iblPath: String? = null,
    val enableMToon: Boolean = true,
    val backgroundColor: FloatArray = floatArrayOf(0.2f, 0.2f, 0.25f, 1.0f),
    val enableTouch: Boolean = true,
    val enableSpringBone: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AvatarConfig) return false
        return iblPath == other.iblPath &&
                enableMToon == other.enableMToon &&
                backgroundColor.contentEquals(other.backgroundColor) &&
                enableSpringBone == other.enableSpringBone &&
                enableTouch == other.enableTouch
    }

    override fun hashCode(): Int {
        var result = iblPath?.hashCode() ?: 0
        result = 31 * result + enableMToon.hashCode()
        result = 31 * result + backgroundColor.contentHashCode()
        result = 31 * result + enableTouch.hashCode()
        result = 31 * result + enableSpringBone.hashCode()
        return result
    }
}
