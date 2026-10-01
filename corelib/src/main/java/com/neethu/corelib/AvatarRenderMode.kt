package com.neethu.corelib

/**
 * Shading pipeline used to render the avatar model.
 *
 * Both modes render the same model; the difference is which material is
 * applied to every primitive. Switching at runtime swaps material instances
 * in place — the model itself, its animations and its expressions are
 * untouched, so the switch is instantaneous.
 *
 * @see AvatarController.setRenderMode
 */
enum class AvatarRenderMode {
    /** Filament's physically-based shading (the default glTF PBR ubershader). */
    PBR,

    /** MToon toon shading — the VRM standard anime-style look. */
    MTOON
}
