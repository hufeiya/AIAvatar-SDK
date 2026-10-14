package com.neethu.corelib

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.neethu.corelib.internal.SoulLinkRenderer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
class AvatarController : AvatarActor {

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

    private val _detectedRenderMode = MutableStateFlow<RenderMode?>(null)

    /** 已挂载配饰的稳定观测流（渲染器回调中继；模型重载清空）。 */
    private val _accessories =
        MutableStateFlow<List<com.neethu.corelib.accessory.AccessoryState>>(emptyList())

    /** Walk-finished events relayed from the renderer (see [walkFinished]). */
    private val _walkFinished = MutableSharedFlow<WalkFinished>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Render mode auto-detected from the currently loaded model's materials,
     * or `null` before the first auto-detected load.
     *
     * Fresh model switches ([loadModel] / [loadModelFromFile] without
     * [forceReload]) follow the model: a model with MToon materials loads as
     * [RenderMode.MTOON], a model without as [RenderMode.PBR] — regardless of
     * the mode pushed via [updateRenderSettings] (a manual override applies to
     * the current model only; the next switch re-detects from materials).
     * Collect this to keep a settings UI / persistence layer in sync with what
     * the renderer actually did.
     */
    val detectedRenderMode: StateFlow<RenderMode?> = _detectedRenderMode.asStateFlow()

    // ── Internal Renderer Binding ────────────────────────────────────────

    internal var renderer: SoulLinkRenderer? = null
        private set

    /** Tracks the path currently loaded (or being loaded) to avoid re-loading. */
    private var currentModelPath: String? = null

    /** How [currentModelPath] was loaded (assets vs filesystem), for mode-switch reloads. */
    private var currentModelIsFile: Boolean = false

    /** Render mode of the currently loaded model (drives the reload on switch). */
    private var appliedRenderMode: RenderMode = RenderMode.PBR

    /**
     * 最近一次经 [updateRenderSettings] 推入的渲染设置;null = 尚未推送过。
     * 供「基于当前设置做增量变更」的调用方(如氛围预设 set_ambience)取正本
     * copy——updateRenderSettings 只进不出,没有它就只能全量构造,把用户调过
     * 的字段打回默认。
     */
    var currentRenderSettings: AvatarRenderSettings? = null
        private set

    /**
     * Attach the renderer. Called internally by [AvatarView] during factory.
     */
    internal fun attach(renderer: SoulLinkRenderer) {
        this.renderer = renderer
        renderer.onFpsUpdated = { value -> _fps.value = value }
        renderer.onWalkFinished = { finished -> _walkFinished.tryEmit(finished) }
        renderer.onAccessoriesUpdated = { list -> _accessories.value = list }
        // A material-driven mode switch happens mid-load (the model is already
        // rendered in the detected mode), so bookkeeping only: keep the reload
        // trigger in sync and expose the value — no reload, no settings push.
        renderer.onRenderModeAutoDetected = { mode ->
            appliedRenderMode = mode
            _detectedRenderMode.value = mode
        }
    }

    /**
     * Detach the renderer. Called internally by [AvatarView] during release.
     */
    internal fun detach() {
        renderer?.onFpsUpdated = null
        renderer?.onWalkFinished = null
        renderer?.onAccessoriesUpdated = null
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
     * Unless [forceReload] is set (explicit reloads keep the pushed render
     * mode), the render mode is decided by the model's own materials — see
     * [detectedRenderMode].
     *
     * @param assetPath Relative path inside `assets/`, e.g. `"models/avatar.vrm"`.
     * @param forceReload If `true`, re-load even if the same path is already loaded.
     */
    fun loadModel(assetPath: String, forceReload: Boolean = false) {
        currentModelIsFile = false
        loadModelInternal(assetPath, forceReload) {
            it.loadModel(assetPath, autoRenderMode = !forceReload)
        }
    }

    /**
     * Load a VRM/GLB model from a file on local storage — for models imported
     * at runtime (app data dir) instead of shipped in `assets/`. Same state
     * transitions and idempotency rules as [loadModel]; the idempotency key is
     * the absolute file path. Render-mode auto-detection behaves like in
     * [loadModel] (file loads via [forceReload] keep the pushed mode).
     *
     * @param filePath Absolute path to the `.vrm`/`.glb` file on the filesystem.
     * @param forceReload If `true`, re-load even if the same path is already loaded.
     */
    fun loadModelFromFile(filePath: String, forceReload: Boolean = false) {
        currentModelIsFile = true
        loadModelInternal(filePath, forceReload) {
            it.loadModelFromFile(filePath, autoRenderMode = !forceReload)
        }
    }

    private fun loadModelInternal(
        pathKey: String,
        forceReload: Boolean,
        load: (SoulLinkRenderer) -> Unit,
    ) {
        if (!forceReload && pathKey == currentModelPath && _state.value is AvatarState.Ready) {
            return // already loaded
        }

        val r = renderer
        if (r == null) {
            _state.value = AvatarState.Error("Renderer not attached. Is AvatarView in the composition?")
            return
        }

        currentModelPath = pathKey
        _state.value = AvatarState.Loading(pathKey)

        try {
            load(r)
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
    override fun setExpression(name: String, weight: Float) {
        renderer?.setExpression(name, weight)
    }

    /**
     * Clear all active expressions, returning the model to a neutral face.
     */
    override fun clearAllExpressions() {
        renderer?.clearAllExpressions()
    }

    /**
     * Get the list of available expression names from the currently loaded model.
     */
    override fun getAvailableExpressions(): List<String> {
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
    override fun setExpressionTransitionDuration(durationMs: Long) {
        renderer?.setExpressionTransitionDuration(durationMs)
    }

    // ── Public API: VRMA Animation ───────────────────────────────────────

    /**
     * Load a VRMA animation file from the app's `assets/` directory.
     *
     * @param assetPath Relative path inside `assets/`, e.g. `"animations/Angry.vrma"`.
     * @return `true` if the animation was loaded successfully.
     */
    override fun loadVrmaAnimation(assetPath: String): Boolean {
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
    override fun loadVrmaAnimationFromFile(path: String): Boolean {
        return renderer?.loadVrmaAnimationFromFile(path) ?: false
    }

    /**
     * Start playing the previously loaded VRMA animation.
     * This stops any built-in animation that is currently playing.
     *
     * @param loop Whether the animation should loop. Defaults to `true`.
     * @param speed Clip clock scale (walk gait passes groundSpeed/baseSpeed).
     * @param fadeSeconds P2 crossfade: > 0 blends from whatever is currently
     *   playing over this many seconds (three.js `crossFadeTo` semantics).
     *   While walking, a one-shot gesture automatically becomes an
     *   upper-body-masked overlay (walk + gesture at the same time).
     */
    override fun playVrmaAnimation(loop: Boolean, speed: Float, fadeSeconds: Float) {
        renderer?.playVrmaAnimation(loop, speed, fadeSeconds)
    }

    /**
     * Stop the VRMA animation and restore the model's rest pose (or the idle
     * loop when configured). [fadeSeconds] > 0 fades out instead of cutting.
     */
    override fun stopVrmaAnimation(fadeSeconds: Float) {
        renderer?.stopVrmaAnimation(fadeSeconds)
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
    override fun setVrmaIdleAnimation(assetPath: String): Boolean {
        return renderer?.setVrmaIdleAnimation(assetPath) ?: false
    }

    /** Same as [setVrmaIdleAnimation] but from an absolute file path. */
    override fun setVrmaIdleAnimationFromFile(path: String): Boolean {
        return renderer?.setVrmaIdleAnimationFromFile(path) ?: false
    }

    /** Drop the idle; one-shots and stops return to the rest pose again. */
    override fun clearVrmaIdleAnimation() {
        renderer?.clearVrmaIdleAnimation()
    }

    // ── Public API: Accessories（P1.5 单人路径桥接）───────────────────────

    /**
     * 当前模型文件名（per-model 校准 preset 的 key；未加载 = null）。与团播
     * AvatarRuntime.modelKey 同语义。
     */
    val currentModelId: String?
        get() = currentModelPath?.substringAfterLast('/')

    /**
     * 配饰挂载规格解析器（动画联动自动挂载用）：配饰 ID → spec。app 层注入
     * sidecar 默认 + per-model 校准的合并结果（demo 见 AiDebugExecutor 的
     * accessory 命令族装配）；未注入时绑定命中只显隐已挂载件。
     */
    var accessorySpecResolver: ((accessoryId: String) -> com.neethu.corelib.accessory.AccessorySpec?)?
        get() = renderer?.accessorySpecResolver
        set(value) {
            renderer?.accessorySpecResolver = value
        }

    /** 当前已挂载配饰（挂/卸/显隐都会发布；模型重载清空）。 */
    val accessories: StateFlow<List<com.neethu.corelib.accessory.AccessoryState>> =
        _accessories.asStateFlow()

    /**
     * 挂载配饰到语义骨骼（加载 GLB → setParent 骨骼实体 → 入场景；挂载后
     * 自动跟随骨骼动画，零每帧代码）。同一 id 重复挂载返回失败。
     */
    fun attachAccessory(spec: com.neethu.corelib.accessory.AccessorySpec): Result<Unit> {
        val r = renderer
            ?: return Result.failure(IllegalStateException("no renderer attached"))
        return r.attachAccessory(spec)
    }

    /** 卸载并销毁（true = 确有此配饰）。 */
    fun detachAccessory(id: String): Boolean = renderer?.detachAccessory(id) ?: false

    /** 不卸载只显隐（true = 确有此配饰）。 */
    fun setAccessoryVisible(id: String, visible: Boolean): Boolean =
        renderer?.setAccessoryVisible(id, visible) ?: false

    /**
     * 动画联动：播 [animationClipPath] 期间 [accessoryId] 自动在场（显式
     * 白名单，路径剥 `animations/` 前缀归一）。命中 VrmaStarted 自动挂载
     * （规格走 [accessorySpecResolver]）并显示；播完/被打断/被顶替经 0.25s
     * crossfade 窗口隐藏。
     */
    fun bindAccessoryToAnimation(accessoryId: String, animationClipPath: String): Boolean =
        renderer?.bindAccessoryToAnimation(accessoryId, animationClipPath) ?: false

    /** 解除动画绑定（剪辑与配饰都匹配才解除）。 */
    fun unbindAccessoryFromAnimation(accessoryId: String, animationClipPath: String): Boolean =
        renderer?.unbindAccessoryFromAnimation(accessoryId, animationClipPath) ?: false

    /** 当前动画绑定表快照（归一化 clipPath → accessoryId）。 */
    val accessoryAnimationBindings: Map<String, String>
        get() = renderer?.accessoryAnimationBindings ?: emptyMap()

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
    override fun setLookAtTarget(x: Float, y: Float, z: Float) {
        renderer?.setLookAtTarget(x, y, z)
    }

    /** Stop gaze tracking; the head returns to its animated pose. */
    override fun clearLookAtTarget() {
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
    override fun setBreathEnabled(enabled: Boolean) {
        renderer?.setBreathEnabled(enabled)
    }

    /** 呼吸幅度倍率（0~3，1 = 设计幅度），实时生效、跨模型重载保留。 */
    override fun setBreathAmplitudeScale(scale: Float) {
        renderer?.setBreathAmplitudeScale(scale)
    }

    /** 呼吸频率（次/分），实时生效、跨模型重载保留；≤0 恢复引擎默认。 */
    override fun setBreathRateBpm(bpm: Float) {
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
    override fun setBreathSpeaking(speaking: Boolean) {
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
     * [placement] is an optional one-time rigid alignment (Y rotation + offset)
     * for the room, plus the walkable bounds used by [startWalk] — typically
     * parsed from `assets/scene/<scene>.meta.json` after a one-time
     * calibration (see the `scene_offset` / `scene_rot` ai_cmd probes).
     * `null` keeps the previous behavior (scene loaded as-is).
     *
     * @param assetPath Relative path inside `assets/`, e.g. `"scene/living_room.glb"`.
     */
    fun loadScene(assetPath: String, placement: ScenePlacement? = null) {
        val r = renderer ?: return
        r.loadScene(assetPath, placement)
        // Update state to include scene path
        val current = _state.value
        if (current is AvatarState.Ready) {
            _state.value = current.copy(scenePath = assetPath)
        }
    }

    /**
     * Live-adjust the loaded scene's placement (calibration probes) without
     * reloading it; re-applies from the placement captured at load time.
     */
    fun setScenePlacement(placement: ScenePlacement?) {
        renderer?.setScenePlacement(placement)
    }

    /** The current scene placement, or `null` when none was set. */
    fun getScenePlacement(): ScenePlacement? = renderer?.getScenePlacement()

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

    // ── Public API: Locomotion（自由走动）────────────────────────────────

    /**
     * Configure the looping walk gait used by [startWalk]. Must succeed once
     * before walking; re-calling it swaps the gait (also mid-walk, taking
     * effect on the next [startWalk]).
     *
     * @param assetPath In-place walk VRMA inside `assets/` — the travel comes
     *   from the locomotion layer itself (synthetic root motion), so any
     *   in-place clip from the animation library works.
     * @param baseSpeed The gait's natural ground speed in m/s at playback
     *   speed 1 (calibrate with `tools/walk-calib`). Walking at [speed] m/s
     *   plays the clip at speed/baseSpeed so footsteps match the actual
     *   travel and the feet don't slide.
     * @return `false` when the clip can't be read/parsed (renderer missing).
     */
    fun setWalkGait(assetPath: String, baseSpeed: Float): Boolean {
        return renderer?.setWalkGait(assetPath, baseSpeed) ?: false
    }

    /** Whether a walk gait is configured (see [setWalkGait]). */
    fun isWalkGaitConfigured(): Boolean = renderer?.hasWalkGait() ?: false

    /**
     * P2 跑步步态：[startWalk] 的 speed ≥ 1.9 m/s 且配置了跑步步态时自动
     * 选用。[baseSpeed] ≤ 0 且素材带烘焙位移时自动标定（方案 C）。
     */
    fun setRunGait(assetPath: String, baseSpeed: Float = 0f): Boolean {
        return renderer?.setRunGait(assetPath, baseSpeed) ?: false
    }

    /**
     * P2 侧移步态对（左/右）：[startStrafe] 按方向选用。[baseSpeed] 同上。
     */
    fun setStrafeGaits(leftAssetPath: String, rightAssetPath: String, baseSpeed: Float = 0f): Boolean {
        return renderer?.setStrafeGaits(leftAssetPath, rightAssetPath, baseSpeed) ?: false
    }

    /**
     * P2 侧移：朝向不变横步挪 [meters] 米（[left]=true 向角色左手边）。
     * 返回值语义同 [startWalk]（NO_GAIT = 侧移步态未配置）。
     */
    fun startStrafe(left: Boolean, meters: Float, speed: Float = 1.0f): WalkStartResult {
        return renderer?.startStrafe(left, meters, speed) ?: WalkStartResult.NO_RENDERER
    }

    /** P2 走动起步/停步 crossfade 时长（秒）；0 = 关（硬切）。 */
    fun setWalkFadeSeconds(seconds: Float) {
        renderer?.setWalkFadeSeconds(seconds)
    }

    fun getWalkFadeSeconds(): Float = renderer?.getWalkFadeSeconds() ?: 0f

    /** P2 走动中手势 overlay（边走边比划）开关。 */
    fun setGestureOverlayEnabled(enabled: Boolean) {
        renderer?.setGestureOverlayEnabled(enabled)
    }

    fun isGestureOverlayEnabled(): Boolean = renderer?.isGestureOverlayEnabled() ?: false

    /** P2 方案 C 位移提取总开关（A/B 对拍用；默认开）。 */
    fun setExtractionEnabled(enabled: Boolean) {
        renderer?.setExtractionEnabled(enabled)
    }

    fun isExtractionEnabled(): Boolean = renderer?.isExtractionEnabled() ?: true

    /** 走动步态配置一览（诊断/ai_cmd）。 */
    fun walkGaitsSummary(): String = renderer?.walkGaitsSummary() ?: "no renderer"

    /**
     * Configure optional walk transition clips (P1 档 1, docs/locomotion-feasibility.md
     * §5.2): a one-shot [startAssetPath] plays at walk start (program motion
     * held while it runs), a one-shot [stopAssetPath] plays on arrival/stop
     * before the idle loop resumes. `null` disables a slot; both-off by
     * default. Re-calling swaps the config (also mid-walk, next walk).
     *
     * @return `false` when any provided path fails to load/parse (config kept).
     */
    fun setWalkTransitions(startAssetPath: String?, stopAssetPath: String?): Boolean {
        return renderer?.setWalkTransitions(startAssetPath, stopAssetPath) ?: false
    }

    /** Whether transition clips are configured (see [setWalkTransitions]). */
    fun hasWalkTransitions(): Boolean = renderer?.hasWalkTransitions() ?: false

    /** Current transition clip config (asset paths, null = off) — diagnostics/ai_cmd dump. */
    fun getWalkTransitions(): Pair<String?, String?> =
        renderer?.getWalkTransitions() ?: (null to null)

    /**
     * One event per finished walk: [WalkFinished.arrived]=true when the state
     * machine reached the target, false when cancelled ([stopWalk]). Collect
     * to surface arrival in UI or feed dialogue logic (AvatarSession emits
     * [com.neethu.orchestrator.session.AvatarEvent.WalkArrived] from this).
     */
    override val walkFinished: SharedFlow<WalkFinished> = _walkFinished.asSharedFlow()

    /**
     * Walk toward a world-space XZ point (meters): plays the configured gait
     * on loop while the locomotion layer turns the avatar toward the target
     * (in place first, ~20° gate) and advances along its facing direction,
     * clamping the target into the loaded scene's [ScenePlacement.bounds].
     * Arrival (≤6 cm) hard-stops and returns to the idle loop; [stopWalk]
     * interrupts at any time. Refused while the mimic engine owns the bones.
     *
     * ```kotlin
     * controller.setWalkGait("animations/Female Normal Walk.vrma", baseSpeed = 1.1f)
     * controller.setCameraShot(CameraShot.FOLLOW) // optional tracking camera
     * controller.startWalk(targetX = 1.5f, targetZ = -3f, speed = 1.0f)
     * ```
     */
    override fun startWalk(targetX: Float, targetZ: Float, speed: Float): WalkStartResult {
        return renderer?.startWalk(targetX, targetZ, speed) ?: WalkStartResult.NO_RENDERER
    }

    /** Stop walking immediately and return to the idle loop. */
    override fun stopWalk() {
        renderer?.stopWalk()
    }

    /**
     * Locomotion state snapshot (phase / position / heading / target / speed),
     * for diagnostics and UI.
     */
    override fun getWalkState(): WalkSnapshot? = renderer?.walkSnapshot()

    // ── Public API: Render Settings ──────────────────────────────────────

    /**
     * Apply PBR render settings to the avatar view at runtime.
     *
     * Every part of [AvatarRenderSettings] is hot-swappable — lighting rig,
     * shadows, SSAO/GTAO, tone mapping, bloom, anti-aliasing and depth of
     * field take effect immediately without reloading the model — except
     * [AvatarRenderSettings.renderMode]: switching between [RenderMode.PBR]
     * and [RenderMode.MTOON] reloads the currently loaded model, because the
     * MToon outline pass needs its duplicated GLB primitives and the material
     * swap happens at load time.
     *
     * A mode pushed here is a manual override: it applies to the currently
     * loaded model, and the next fresh model switch re-detects the mode from
     * the new model's materials (see [detectedRenderMode]).
     *
     * [QualityPreset] offers one-click bundles:
     *
     * ```kotlin
     * controller.updateRenderSettings(QualityPreset.ULTRA.toRenderSettings())
     * ```
     */
    fun updateRenderSettings(settings: AvatarRenderSettings) {
        currentRenderSettings = settings
        renderer?.applyRenderSettings(settings)
        val modeChanged = settings.renderMode != appliedRenderMode
        appliedRenderMode = settings.renderMode
        val path = currentModelPath
        if (modeChanged && path != null) {
            if (currentModelIsFile) {
                loadModelFromFile(path, forceReload = true)
            } else {
                loadModel(path, forceReload = true)
            }
        }
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
    override fun getCameraLookAt(): Triple<FloatArray, FloatArray, FloatArray>? {
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
    override fun setCameraShot(shot: CameraShot) {
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
    override fun getActiveCameraShot(): CameraShot? {
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
