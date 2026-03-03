package com.neethu.corelib

/**
 * Represents the current state of the avatar rendering pipeline.
 *
 * Observe this via [AvatarController.state] to react to loading progress,
 * readiness, or errors.
 *
 * ```kotlin
 * val controller = rememberAvatarController()
 * val state by controller.state.collectAsState()
 *
 * when (state) {
 *     is AvatarState.Idle -> { /* show placeholder */ }
 *     is AvatarState.Loading -> { /* show spinner */ }
 *     is AvatarState.Ready -> { /* avatar is visible */ }
 *     is AvatarState.Error -> { /* show error message */ }
 * }
 * ```
 */
sealed class AvatarState {
    /** Initial state — no model loaded yet. */
    data object Idle : AvatarState()

    /** A model is currently being loaded. */
    data class Loading(val modelPath: String) : AvatarState()

    /**
     * The model has been loaded successfully and is rendering.
     * @param animationCount Number of animations available in the model.
     */
    data class Ready(
        val animationCount: Int,
        val expressions: List<String> = emptyList(),
        val scenePath: String? = null
    ) : AvatarState()

    /** An error occurred during model loading or rendering setup. */
    data class Error(val message: String, val cause: Throwable? = null) : AvatarState()
}
