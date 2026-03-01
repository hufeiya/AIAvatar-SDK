package com.neethu.corelib

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.neethu.corelib.internal.SoulLinkRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Primary API surface for controlling a 3D avatar.
 *
 * `AvatarController` acts as the bridge between your UI layer and the
 * underlying Filament rendering engine. It exposes:
 * - **[state]** — an observable [StateFlow] of [AvatarState] for reactive UI.
 * - **[loadModel]** — to load a VRM/GLB model from assets.
 * - **[applyBehavior]** — to trigger animations and blend shapes.
 * - **[playAnimation]** / **[stopAnimation]** — convenience animation controls.
 *
 * ## Usage
 * ```kotlin
 * val controller = rememberAvatarController()
 * val state by controller.state.collectAsState()
 *
 * AvatarView(
 *     controller = controller,
 *     config = AvatarConfig(iblPath = "studio.ktx")
 * )
 *
 * // Load a model after the view is attached
 * LaunchedEffect(Unit) {
 *     controller.loadModel("avatar.vrm")
 * }
 *
 * // Later, play an animation
 * controller.playAnimation(index = 0, loop = true)
 * ```
 *
 * Obtain an instance via [rememberAvatarController] inside a Composable scope.
 */
class AvatarController {

    // ── Observable State ─────────────────────────────────────────────────

    private val _state = MutableStateFlow<AvatarState>(AvatarState.Idle)

    /**
     * The current state of the avatar.
     *
     * Collect this in Compose via `controller.state.collectAsState()` to
     * build reactive UI that responds to loading, ready, or error states.
     */
    val state: StateFlow<AvatarState> = _state.asStateFlow()

    // ── Internal Renderer Binding ────────────────────────────────────────

    internal var renderer: SoulLinkRenderer? = null
        private set

    /** Tracks the path currently loaded (or being loaded) to avoid re-loading. */
    private var currentModelPath: String? = null

    /**
     * Attach the renderer. Called internally by [AvatarView] during factory.
     */
    internal fun attach(renderer: SoulLinkRenderer) {
        this.renderer = renderer
    }

    /**
     * Detach the renderer. Called internally by [AvatarView] during release.
     */
    internal fun detach() {
        this.renderer = null
        // Don't reset state — the controller can outlive the view
    }

    // ── Public API: Model Loading ────────────────────────────────────────

    /**
     * Load a VRM or GLB model from the app's `assets/` directory.
     *
     * This is idempotent — calling with the same [assetPath] while the model
     * is already loaded (or loading) is a no-op.
     *
     * The [state] flow will transition:
     * `Idle → Loading → Ready` (or `Error`).
     *
     * @param assetPath Relative path inside `assets/`, e.g. `"models/avatar.vrm"`.
     * @param forceReload If `true`, re-load even if the same path is already loaded.
     */
    fun loadModel(assetPath: String, forceReload: Boolean = false) {
        if (!forceReload && assetPath == currentModelPath && _state.value is AvatarState.Ready) {
            return // already loaded
        }

        val r = renderer
        if (r == null) {
            _state.value = AvatarState.Error("Renderer not attached. Is AvatarView in the composition?")
            return
        }

        currentModelPath = assetPath
        _state.value = AvatarState.Loading(assetPath)

        try {
            r.loadModel(assetPath)
            val animCount = r.getAnimationCount()
            _state.value = AvatarState.Ready(animationCount = animCount)
        } catch (e: Exception) {
            _state.value = AvatarState.Error(
                message = "Failed to load model: ${e.message}",
                cause = e
            )
        }
    }

    // ── Public API: Behavior Commands ────────────────────────────────────

    /**
     * Apply a [AvatarBehavior] bundle to the avatar.
     *
     * This is the primary way to drive animations and expressions.
     *
     * @see AvatarBehavior
     */
    fun applyBehavior(behavior: AvatarBehavior) {
        val r = renderer ?: return

        behavior.animationIndex?.let { index ->
            r.playAnimation(index, behavior.isLoop)
        }

        // BlendShape support — future extension point
        // behavior.blendShapes?.forEach { (name, weight) ->
        //     r.setBlendShape(name, weight)
        // }
    }

    /**
     * Play the animation at [index].
     *
     * @param index Zero-based animation index (see [AvatarState.Ready.animationCount]).
     * @param loop Whether the animation should loop. Defaults to `true`.
     */
    fun playAnimation(index: Int, loop: Boolean = true) {
        renderer?.playAnimation(index, loop)
    }

    /**
     * Stop all animations and reset the avatar to its bind pose.
     */
    fun stopAnimation() {
        renderer?.stopAnimation()
    }

    // ── Public API: VRMA Animation ───────────────────────────────────────

    /**
     * Load a VRMA animation file from the app's `assets/` directory.
     *
     * @param assetPath Relative path inside `assets/`, e.g. `"animations/Angry.vrma"`.
     * @return `true` if the animation was loaded successfully.
     */
    fun loadVrmaAnimation(assetPath: String): Boolean {
        return renderer?.loadVrmaAnimation(assetPath) ?: false
    }

    /**
     * Start playing the previously loaded VRMA animation.
     * This stops any built-in animation that is currently playing.
     *
     * @param loop Whether the animation should loop. Defaults to `true`.
     */
    fun playVrmaAnimation(loop: Boolean = true) {
        renderer?.playVrmaAnimation(loop)
    }

    /**
     * Stop the VRMA animation and restore the model's rest pose.
     */
    fun stopVrmaAnimation() {
        renderer?.stopVrmaAnimation()
    }
}

/**
 * Create and remember an [AvatarController] across recompositions.
 *
 * ```kotlin
 * val controller = rememberAvatarController()
 * ```
 */
@Composable
fun rememberAvatarController(): AvatarController {
    return remember { AvatarController() }
}
