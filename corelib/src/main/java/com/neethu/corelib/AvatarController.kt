package com.neethu.corelib

import android.graphics.Bitmap
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

    private val _fps = MutableStateFlow(0)

    /**
     * Live frame rate measured by the renderer, updated twice a second.
     * `0` while no view is attached or rendering has not started yet.
     * Whether the UI displays it is controlled by
     * [AvatarRenderSettings.showFps].
     */
    val fps: StateFlow<Int> = _fps.asStateFlow()

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
        renderer.onFpsUpdated = { value -> _fps.value = value }
    }

    /**
     * Detach the renderer. Called internally by [AvatarView] during release.
     */
    internal fun detach() {
        renderer?.onFpsUpdated = null
        this.renderer = null
        _fps.value = 0
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
            val expressionNames = r.getAvailableExpressions()
            _state.value = AvatarState.Ready(
                animationCount = animCount,
                expressions = expressionNames
            )
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

    // ── Public API: Expression (Blend Shape) ─────────────────────────────

    /**
     * Set expression weight on the current model.
     *
     * @param name Expression name (e.g. "happy", "sad", "blink").
     * @param weight Weight value 0.0–1.0. Defaults to 1.0 (full expression).
     */
    fun setExpression(name: String, weight: Float = 1.0f) {
        renderer?.setExpression(name, weight)
    }

    /**
     * Clear all active expressions, returning the model to a neutral face.
     */
    fun clearAllExpressions() {
        renderer?.clearAllExpressions()
    }

    /**
     * Get the list of available expression names from the currently loaded model.
     */
    fun getAvailableExpressions(): List<String> {
        return renderer?.getAvailableExpressions() ?: emptyList()
    }

    /**
     * Set the transition duration for expression blending.
     *
     * When switching expressions, the morph weights will smoothly interpolate
     * over this duration instead of changing instantly.
     *
     * @param durationMs Duration in milliseconds. Default is 300ms. Use 0 for instant.
     */
    fun setExpressionTransitionDuration(durationMs: Long) {
        renderer?.setExpressionTransitionDuration(durationMs)
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
     * Load a VRMA animation file from local storage (e.g. the app's external
     * files dir), for animation libraries downloaded or pushed at runtime
     * instead of being packaged in the APK.
     *
     * @param path Absolute path to the `.vrma` file on the filesystem.
     * @return `true` if the animation was loaded successfully.
     */
    fun loadVrmaAnimationFromFile(path: String): Boolean {
        return renderer?.loadVrmaAnimationFromFile(path) ?: false
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

    /**
     * Duration of the currently loaded VRMA animation in seconds
     * (0 when none is loaded). Useful for progress UI and tests.
     */
    fun getVrmaAnimationDuration(): Float {
        return renderer?.getVrmaDuration() ?: 0f
    }

    // ── Public API: VRMA Idle ─────────────────────────────────────────────

    /**
     * Set the looping idle animation (from assets) the model returns to after
     * a one-shot VRMA or a manual stop. Without an idle the model falls back
     * to its rest pose. Does not interrupt current playback.
     *
     * @return `true` if the animation was loaded successfully.
     */
    fun setVrmaIdleAnimation(assetPath: String): Boolean {
        return renderer?.setVrmaIdleAnimation(assetPath) ?: false
    }

    /** Same as [setVrmaIdleAnimation] but from an absolute file path. */
    fun setVrmaIdleAnimationFromFile(path: String): Boolean {
        return renderer?.setVrmaIdleAnimationFromFile(path) ?: false
    }

    /** Drop the idle; one-shots and stops return to the rest pose again. */
    fun clearVrmaIdleAnimation() {
        renderer?.clearVrmaIdleAnimation()
    }

    // ── Public API: Gaze / Look-At ───────────────────────────────────────

    /**
     * Track a world-space gaze target. The avatar turns its head/neck (smooth,
     * clamped to a natural range) and darts its eyes toward [x,y,z] on top of
     * whatever animation is playing — feed it the camera eye to have the
     * avatar look at the user, or a face position from a camera tracker.
     *
     * ```kotlin
     * val (eye, _, _) = controller.getCameraLookAt()!!
     * controller.setLookAtTarget(eye[0], eye[1], eye[2])
     * ```
     */
    fun setLookAtTarget(x: Float, y: Float, z: Float) {
        renderer?.setLookAtTarget(x, y, z)
    }

    /** Stop gaze tracking; the head returns to its animated pose. */
    fun clearLookAtTarget() {
        renderer?.clearLookAtTarget()
    }

    /**
     * Last-frame gaze state (target, applied yaw/pitch offsets, bound bones),
     * or `null` when no view is attached. Diagnostics surface.
     */
    fun getLookAtInfo(): LookAtInfo? {
        return renderer?.lookAtInfo()
    }

    // ── Public API: Mimic（「模仿我」）────────────────────────────────────

    /**
     * Feed one frame of mimic pose targets — the "imitate me" pipeline (user's
     * body from the camera → avatar's bones, mirror semantics baked in by the
     * app lane). All directions are unit vectors in avatar world space
     * (+X screen right, +Y up, +Z toward the viewer). Thread-safe: call from
     * any thread; the render thread consumes the latest frame each tick.
     *
     * Pass `null` to ease the avatar back to its animated pose (a frame older
     * than ~600 ms triggers the same restore automatically — the renderer
     * self-heals when the camera lane stops feeding).
     */
    fun setMimicPose(pose: MimicPose?) {
        renderer?.setMimicPose(pose)
    }

    /** Stop mimicking: ease back to the animated pose over ~300 ms. */
    fun clearMimicPose() {
        renderer?.setMimicPose(null)
    }

    /**
     * Last-frame mimic state (engaged/restoring, pose age, smoothed head
     * angles), or `null` when no view is attached. Diagnostics surface.
     */
    fun getMimicInfo(): MimicInfo? {
        return renderer?.mimicInfo()
    }

    // ── Public API: Breath ───────────────────────────────────────────────

    /**
     * Enable or disable the procedural breath overlay — a subtle chest rise
     * + shoulder shrug driven on the spine/shoulder bones on top of whatever
     * animation plays (the default idle is a static single-frame pose, so
     * this is what keeps the avatar from looking frozen). On by default; the
     * setting survives model switches.
     */
    fun setBreathEnabled(enabled: Boolean) {
        renderer?.setBreathEnabled(enabled)
    }

    /** 呼吸幅度倍率（0~3，1 = 设计幅度），实时生效、跨模型重载保留。 */
    fun setBreathAmplitudeScale(scale: Float) {
        renderer?.setBreathAmplitudeScale(scale)
    }

    /** 呼吸频率（次/分），实时生效、跨模型重载保留；≤0 恢复引擎默认。 */
    fun setBreathRateBpm(bpm: Float) {
        renderer?.setBreathRateBpm(bpm)
    }

    /**
     * Tell the breath system whether the avatar is currently speaking: while
     * talking, breath turns shallower (×0.6 amplitude) and slightly faster
     * (×1.15 rate), easing over ~0.5 s. One-shot flags — call on playback
     * start/end, not per frame. The session's FaceDriver feeds this
     * automatically; SDK integrators only need this when driving playback
     * themselves.
     */
    fun setBreathSpeaking(speaking: Boolean) {
        renderer?.setBreathSpeaking(speaking)
    }

    /**
     * Last-frame breath state (rate/amplitude/phase, bound bones), or `null`
     * when no view is attached. Diagnostics surface.
     */
    fun getBreathInfo(): BreathInfo? {
        return renderer?.breathInfo()
    }

    // ── Public API: Spring Bone ──────────────────────────────────────────

    /**
     * Enable or disable spring bone physics simulation.
     * When disabled, hair and clothing will remain static.
     *
     * @param enabled `true` to enable spring bone simulation, `false` to disable.
     */
    fun setSpringBoneEnabled(enabled: Boolean) {
        renderer?.setSpringBoneEnabled(enabled)
    }

    /**
     * When true (default), drag mode moves the humanoid hips bone (three-vrm
     * mouse.html semantics) so spring bones react with full swings. When false,
     * drag mode moves the asset root instead.
     */
    fun setDragMovesHips(enabled: Boolean) {
        renderer?.setDragMovesHips(enabled)
    }

    /** Enables once-per-second spring bone diagnostics in logcat (tag "SpringBone"). */
    fun setSpringBoneDebugLog(enabled: Boolean) {
        renderer?.springBoneManager?.setDebugLogEnabled(enabled)
    }

    /**
     * Toggle frustum culling for the avatar's own renderables. Default is OFF
     * (see SoulLinkRenderer — gltfio's static bind-pose culling boxes make
     * skinned meshes like the eyeballs vanish at close range once the head
     * turns); turn ON only for debugging.
     */
    fun setAvatarCulling(enabled: Boolean) {
        renderer?.setAvatarCulling(enabled)
    }

    // ── Public API: Scene (Environment/Background) ───────────────────────

    /**
     * Load a GLB scene (environment/background) from the app's `assets/` directory.
     *
     * The scene is rendered alongside the current model in the same Filament scene.
     * Loading a new scene automatically removes the previously loaded scene.
     *
     * @param assetPath Relative path inside `assets/`, e.g. `"scene/living_room.glb"`.
     */
    fun loadScene(assetPath: String) {
        val r = renderer ?: return
        r.loadScene(assetPath)
        // Update state to include scene path
        val current = _state.value
        if (current is AvatarState.Ready) {
            _state.value = current.copy(scenePath = assetPath)
        }
    }

    /**
     * Remove the currently loaded scene (environment/background).
     */
    fun removeScene() {
        val r = renderer ?: return
        r.removeScene()
        // Update state to clear scene path
        val current = _state.value
        if (current is AvatarState.Ready) {
            _state.value = current.copy(scenePath = null)
        }
    }

    // ── Public API: Render Settings ──────────────────────────────────────

    /**
     * Apply PBR render settings to the avatar view at runtime.
     *
     * Every part of [AvatarRenderSettings] is hot-swappable — lighting rig,
     * shadows, SSAO/GTAO, tone mapping, bloom, anti-aliasing and depth of
     * field take effect immediately without reloading the model.
     * [QualityPreset] offers one-click bundles:
     *
     * ```kotlin
     * controller.updateRenderSettings(QualityPreset.ULTRA.toRenderSettings())
     * ```
     */
    fun updateRenderSettings(settings: AvatarRenderSettings) {
        renderer?.applyRenderSettings(settings)
    }

    // ── Public API: Interaction ──────────────────────────────────────────

    /**
     * Enable or disable drag mode. When enabled, touching the screen translates
     * the avatar in world space instead of rotating the camera.
     */
    fun setDragMode(enabled: Boolean) {
        renderer?.isDragMode = enabled
    }

    // ── Public API: Programmatic Avatar & Camera Control ─────────────────

    /**
     * Translate the avatar root in world space (meters) without touch input.
     * +X right, +Y up, +Z toward the camera. Mirrors drag-mode translation.
     */
    fun moveAvatar(dx: Float, dy: Float, dz: Float = 0f) {
        renderer?.moveAvatar(dx, dy, dz)
    }

    /**
     * Dolly the camera toward/away from the model.
     * [spreadPx] uses pinch semantics: positive = spread fingers = zoom in,
     * negative = pinch together = zoom out.
     */
    fun zoomCamera(spreadPx: Float) {
        renderer?.zoomCamera(spreadPx)
    }

    /**
     * Pan the camera laterally, equivalent to a two-finger drag of
     * [dxPx]/[dyPx] screen pixels.
     */
    fun panCamera(dxPx: Float, dyPx: Float) {
        renderer?.panCamera(dxPx, dyPx)
    }

    /**
     * Orbit the camera around the model, equivalent to a single-finger drag of
     * [yawPx]/[pitchPx] screen pixels.
     */
    fun orbitCamera(yawPx: Float, pitchPx: Float) {
        renderer?.orbitCamera(yawPx, pitchPx)
    }

    /**
     * Restore the camera to its initial pose (as when the model was loaded).
     */
    fun resetCamera() {
        renderer?.resetCamera()
    }

    /**
     * Current camera pose: `Triple(eye, target, up)`, each a 3-element
     * array, or `null` while no view is attached.
     */
    fun getCameraLookAt(): Triple<FloatArray, FloatArray, FloatArray>? {
        return renderer?.cameraLookAt()
    }

    // ── Public API: Camera Shots (preset framing) ────────────────────────

    /**
     * Glide the camera to a preset [shot] with a smooth transition — never a
     * hard cut. Available shots:
     *
     *  - [CameraShot.CLOSE_UP] — 面部特写, chest-up to head (look-at on the head bone)
     *  - [CameraShot.MACRO] — 面部微距, face-filling close-up (~0.6× of close-up distance)
     *  - [CameraShot.MEDIUM_SHOT] — 中景半身, waist-up (standard streamer framing)
     *  - [CameraShot.FULL_SHOT] — 全景全身, head to feet
     *  - [CameraShot.LONG_SHOT] — 远景, full body with wide margin for big dance moves
     *  - [CameraShot.OVER_SHOULDER] — 反应侧景, half-body from ~38° side-front
     *
     * Framing is derived from the model's humanoid bones at call time, so it
     * adapts to any model and animation pose. The shot stays as the camera
     * "mode": switching models re-frames the new character with the same
     * shot, until [clearCameraShot] or another [setCameraShot]. Manual camera
     * input (touch or [zoomCamera]/[panCamera]/[orbitCamera]/[resetCamera])
     * cancels only the in-flight glide.
     *
     * ```kotlin
     * controller.setCameraShot(CameraShot.MEDIUM_SHOT)
     * ```
     */
    fun setCameraShot(shot: CameraShot) {
        renderer?.setCameraShot(shot)
    }

    /**
     * Release the camera-shot mode. The camera stays where it is; gestures
     * behave exactly as before.
     */
    fun clearCameraShot() {
        renderer?.clearCameraShot()
    }

    /**
     * The last requested [CameraShot] (camera mode), or `null` when released
     * or no view is attached. Note: manual camera control cancels the glide
     * but keeps the mode.
     */
    fun getActiveCameraShot(): CameraShot? {
        return renderer?.getActiveCameraShot()
    }

    /**
     * Capture the next rendered frame as a [android.graphics.Bitmap] and pass
     * it to [onCaptured] (on the main thread). Fails (returns `false`) when no
     * view is attached or rendering is paused.
     */
    fun captureFrame(onCaptured: (Bitmap?) -> Unit): Boolean {
        val r = renderer ?: return false
        return r.captureNextFrame(onCaptured)
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
