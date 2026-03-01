package com.neethu.corelib

/**
 * A single behavior command to apply to the avatar.
 *
 * Behaviors represent transient actions such as playing an animation or
 * adjusting blend shapes. Pass them to [AvatarController.applyBehavior].
 *
 * ```kotlin
 * // Play a wave animation
 * controller.applyBehavior(
 *     AvatarBehavior(animationIndex = 2, isLoop = false)
 * )
 *
 * // Set blend shapes for an expression
 * controller.applyBehavior(
 *     AvatarBehavior(blendShapes = mapOf("happy" to 0.8f, "blink" to 1.0f))
 * )
 * ```
 *
 * @property animationIndex Index of the animation to play (from [AvatarState.Ready.animationCount]).
 * @property transitionDuration Duration in seconds for animation crossfade.
 * @property isLoop Whether the animation should loop.
 * @property blendShapes Map of blend shape names to intensity values (0.0–1.0).
 */
data class AvatarBehavior(
    val animationIndex: Int? = null,
    val transitionDuration: Float = 0.2f,
    val isLoop: Boolean = true,
    val blendShapes: Map<String, Float>? = null,
)
