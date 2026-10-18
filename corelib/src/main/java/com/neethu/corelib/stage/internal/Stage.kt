package com.neethu.corelib.stage.internal

import android.graphics.Bitmap
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.neethu.corelib.stage.StageProp
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.neethu.corelib.AmbientOcclusionQuality
import com.neethu.corelib.AntiAliasingMode
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.CameraShot
import com.neethu.corelib.LightingRig
import com.neethu.corelib.RenderMode
import com.neethu.corelib.ScenePlacement
import com.neethu.corelib.ToneMappingMode
import com.neethu.corelib.internal.MToonMaterialFactory
import com.neethu.corelib.vfx.internal.VfxManager
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 多演员舞台（docs/multi-avatar-feasibility.md §4.1 拆分表的左列）：
 * 一个 [ModelViewer] 宿主（Engine/Scene/View/SwapChain/相机/手势）+
 * 三灯 rig + IBL + Skybox + ColorGrading + 渲染设置 + 场景 GLB + 共享
 * MToon 材质库 + VRMA 只读缓存 + 统一帧泵。
 *
 * 与单虚拟人渲染器的分工：这里持有全部「舞台级」状态（单例语义的共享
 * 资源，§4.8 清单），per-model 状态都在 [AvatarRuntime]；两班代码平行
 * 演进——SoulLinkRenderer（单人路径）**零改动**，本类只服务多演员入口，
 * 单人功能零回归风险。
 *
 * 帧泵（N 演员 → 1 commit 纪律，§8 风险 7）：
 * ```
 * for (runtime in runtimes) runtime.preAnimation(frame, dt)   // 姿势层
 * commitLocalTransformTransaction()                            // 共享一次
 * for (runtime in runtimes) runtime.postAnimation(now, dt)     // 覆盖层
 * for (runtime in runtimes) runtime.skinAndPhysics(frame, dt)  // 蒙皮+物理
 * updateCameraShot() → fps → 渐进进场 → modelViewer.render()
 * ```
 */
internal class Stage(
    private val surfaceView: SurfaceView,
    val config: AvatarConfig,
) : DefaultLifecycleObserverBridge,
    com.neethu.corelib.accessory.AccessoryHost {

    // ── Filament 宿主（ModelViewer = Engine/Scene/View/Camera/渲染/手势）──

    private val cameraManipulator: Manipulator = Manipulator.Builder()
        .targetPosition(MODEL_CENTER[0], MODEL_CENTER[1], MODEL_CENTER[2])
        .zoomSpeed(MANIP_ZOOM_SPEED)
        .orbitSpeed(MANIP_ORBIT_SPEED, MANIP_ORBIT_SPEED)
        .fovDegrees(MANIP_FOV_DEGREES)
        .farPlane(MANIP_FAR_PLANE)
        .build(Manipulator.Mode.ORBIT)

    private val modelViewer: ModelViewer = ModelViewer(
        surfaceView,
        Engine.create(),
        UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK),
        cameraManipulator
    )

    val engine: Engine get() = modelViewer.engine
    val scene get() = modelViewer.scene

    // ── 配饰宿主缝（AccessoryHost；团播路径配饰与演员共用 loader）────────

    override val accEngine: Engine get() = engine
    override val accScene: Scene get() = scene
    override val accAssetLoader: AssetLoader? get() = avatarAssetLoader
    override val accResourceLoader: ResourceLoader? get() = accessoryResourceLoader
    override fun accReadSourceBytes(source: String): ByteArray = readSourceBytes(source)

    // ── VFX 特效宿主缝（P1 团播第二实现，docs/vfx-feasibility.md §六）────

    /**
     * 特效管理器：首次 playVfx 惰性创建（材质运行时编译，与单人路径同构）；
     * destroy 走 [onDestroy]（引擎仍在位时按坑 13 顺序收干净）。
     */
    private var vfxManager: VfxManager? = null

    /** 特效列表变化（play/stop/播完自停/骨锚失效自停）→ 门面 StateFlow 中继。 */
    var onEffectsUpdated: ((List<com.neethu.corelib.vfx.VfxInfo>) -> Unit)? = null

    /** 特效宿主缝：相机三元组来自舞台 Manipulator，骨锚/资产按演员解析。 */
    private val vfxHost = object : com.neethu.corelib.vfx.internal.VfxHost {
        override val vfxEngine: Engine get() = engine
        override val vfxScene: Scene get() = scene
        override fun vfxCameraLookAt(outEye: FloatArray, outTarget: FloatArray, outUp: FloatArray) {
            cameraManipulator.getLookAt(outEye, outTarget, outUp)
        }

        override fun vfxResolveBone(avatarId: String?, boneName: String, out: FloatArray): Boolean {
            val runtime = synchronized(runtimes) {
                when {
                    avatarId != null ->
                        runtimes.firstOrNull { it.id.equals(avatarId, ignoreCase = true) }
                    else -> focusedRuntime ?: runtimes.firstOrNull()
                }
            } ?: return false
            return runtime.resolveBonePosition(boneName, out)
        }

        override fun vfxReadAssetBytes(path: String): ByteArray? = runCatching {
            readSourceBytes(path)
        }.getOrNull()

        override fun vfxPushMToonStageLight(
            pos: FloatArray,
            dir: FloatArray,
            colorRGB: FloatArray,
            cosInner: Float,
            cosOuter: Float,
            rangeM: Float,
        ) {
            stageLightConeScratch[0] = cosInner
            stageLightConeScratch[1] = cosOuter
            synchronized(runtimes) {
                runtimes.forEach { it.pushMToonStageLight(pos, dir, colorRGB, stageLightConeScratch, rangeM) }
            }
        }
    }

    /** VfxHost → 各演员 MToonApplier.setStageLight 的 cone 打包 scratch（主线程唯一）。 */
    private val stageLightConeScratch = FloatArray(2)

    // ── 头发弹簧骨骼风场馈送（画质升级 2026-10-17）──────────────────────

    private val windSample = FloatArray(2)
    private val windPosScratch = FloatArray(3)

    /**
     * 环境风 → 全体演员弹簧骨骼外力：取 VfxManager 全局风时钟同源采样（与
     * 雪/花瓣阵风同相），限幅防 Verlet 失稳；无活跃风特效清零。
     */
    private fun feedSpringBoneWind() {
        var fed = false
        synchronized(runtimes) {
            for (rt in runtimes) {
                val mgr = rt.springBoneManager ?: continue
                if (rt.worldPos(windPosScratch) &&
                    vfxManager?.ambientWind(windPosScratch[0], windPosScratch[2], windSample) == true
                ) {
                    val k = SPRINGBONE_WIND_GAIN
                    mgr.setWindAccel(
                        (windSample[0] * k).coerceIn(-SPRINGBONE_WIND_MAX, SPRINGBONE_WIND_MAX),
                        0f,
                        (windSample[1] * k).coerceIn(-SPRINGBONE_WIND_MAX, SPRINGBONE_WIND_MAX),
                    )
                } else {
                    mgr.setWindAccel(0f, 0f, 0f)
                }
                fed = true
            }
        }
        if (!fed) return
    }

    // ── 演员与加载器 ─────────────────────────────────────────────────────

    private val runtimes = ArrayList<AvatarRuntime>()

    /** 演员加载器（全部演员共用一套；场景 GLB 有自己的第二套，单虚拟人同构）。 */
    var avatarAssetLoader: AssetLoader? = null
        private set
    var avatarResourceLoader: ResourceLoader? = null
        private set
    private var avatarMaterialProvider: UbershaderProvider? = null

    /**
     * 配饰资源加载器（同步 loadResources 专用；与演员异步管线隔离——
     * 配饰首次挂载用，docs/accessory-feasibility.md §5.2）。
     */
    var accessoryResourceLoader: ResourceLoader? = null
        private set

    /** popRenderables 复用缓冲（Runtime 渐进进场共用）。 */
    val readyRenderables = IntArray(128)

    /** 触摸拖拽命中的演员（P0：UI/命令显式指定；无点击拾取）。 */
    var focusedRuntime: AvatarRuntime? = null

    /**
     * 配饰挂载规格解析（动画联动自动挂载用，P1 docs/accessory-feasibility.md
     * §5.4）：入参（modelId, accessoryId）——modelId 为模型文件名（per-model
     * 校准 preset 的 key），app 层注入 sidecar 默认+校准合并的结果；null =
     * 未注入或查不到，绑定命中时无法自动挂载（手动 attach 的显隐联动不受影响）。
     */
    var accessorySpecResolver: ((
        modelId: String,
        accessoryId: String,
    ) -> com.neethu.corelib.accessory.AccessorySpec?)? = null

    // ── 共享 MToon 材质库（舞台级一份，多演员复用编译产物）───────────────

    var mtoonFactory: MToonMaterialFactory? = null
        private set

    // ── VRMA 只读缓存（parser 舞台一份，锁内串行）────────────────────────

    private val vrmaParser = com.neethu.corelib.internal.VrmaParser()
    private val vrmaCache = VrmaClipCache()

    /** 缓存取片：[source] = assets 相对路径或文件绝对路径；失败 = null。 */
    fun vrmaClipOrNull(source: String): com.neethu.corelib.internal.VrmaParser.VrmaAnimation? =
        vrmaCache.getOrParse(source) { src ->
            vrmaParser.parse(java.nio.ByteBuffer.wrap(readSourceBytes(src)))
        }

    fun vrmaClip(source: String): com.neethu.corelib.internal.VrmaParser.VrmaAnimation? = vrmaClipOrNull(source)

    /** 模型/动画字节读取：绝对路径走文件，其余走 assets。 */
    fun readSourceBytes(source: String): ByteArray {
        val file = java.io.File(source)
        if (file.isFile) return file.inputStream().use { it.readBytes() }
        return surfaceView.context.assets.open(source).use { it.readBytes() }
    }

    // ── 渲染设置（舞台级，全演员共享观感）────────────────────────────────

    var renderSettings: AvatarRenderSettings = config.renderSettings
        private set
    private var colorGrading: ColorGrading? = null
    private var appliedToneMapping: ToneMappingMode? = null

    private var keyLightEntity: Int = 0
    private var fillLightEntity: Int = 0
    private var rimLightEntity: Int = 0
    private var keyShadowSignature: ShadowSignature? = null

    /** 任一演员处于 MToon → 舞台色调映射强制 LINEAR（MToon 数学依赖；P0 从简，per-演员分级留待后续期）。 */
    private val anyMtoonActive: Boolean
        get() = synchronized(runtimes) { runtimes.any { it.mtoonActive } }

    // ── 场景（环境 GLB）＋对位 ───────────────────────────────────────────

    private var sceneAsset: FilamentAsset? = null
    private var sceneAssetLoader: AssetLoader? = null
    private var sceneResourceLoader: ResourceLoader? = null
    // 走动守卫读 bounds 用（internal；公开语义走 get/setScenePlacement 函数）
    var activeScenePlacement: ScenePlacement? = null
    private var sceneBaseMatrix: FloatArray? = null

    fun hasScene(): Boolean = sceneAsset != null

    // ── VFX 特效（P1 团播路径；与单人 SoulLinkRenderer 同构）─────────────

    /**
     * 播放一个特效。首次调用惰性初始化渲染器（材质编译 + 批缓冲）。
     * @return 句柄；null = 渲染未就绪或对应批容量已满。
     */
    fun playVfx(spec: com.neethu.corelib.vfx.VfxSpec, anchor: com.neethu.corelib.vfx.VfxAnchor): com.neethu.corelib.vfx.VfxHandle? {
        val mgr = vfxManager ?: VfxManager(vfxHost).also { m ->
            m.onChange = { onEffectsUpdated?.invoke(m.effects.value) }
            vfxManager = m
        }
        return mgr.play(spec, anchor)
    }

    /** 停止一个特效（凭 [com.neethu.corelib.vfx.VfxHandle.id]）。 */
    fun stopVfx(id: String): Boolean = vfxManager?.stop(id) ?: false

    /** 停止全部特效。 */
    fun stopAllVfx() {
        vfxManager?.stopAll()
    }

    /** 活跃特效实时快照（STAGE_CMD `fx state` 数据源）。 */
    fun vfxSnapshot(): List<com.neethu.corelib.vfx.VfxInfo> =
        vfxManager?.snapshot() ?: emptyList()

    fun getScenePlacement(): ScenePlacement? = activeScenePlacement

    // ── FPS / 截帧 ───────────────────────────────────────────────────────

    var onFpsUpdated: ((Int) -> Unit)? = null
    private var fpsFrameCount = 0
    private var fpsWindowStartNanos = 0L

    private var isRendering = false

    // ── 运镜（目标参数化：shot 跟谁）────────────────────────────────────

    private var activeShot: CameraShot? = null
    private var shotTarget: AvatarRuntime? = null

    /** GROUP_SHOT 是否走视锥拟合取景（false = 旧头距 × 常数路径）。 */
    private var groupFitFraming = false

    /** 世界静态道具（辩论座椅等布置；独立于场景 GLB 的第二资产通道）。 */
    private val props = mutableListOf<StageProp>()

    /** GROUP_SHOT 的同框名单（null = 非 group 机位；空表 = 无演员可取景）。 */
    private var shotTargets: List<AvatarRuntime>? = null
    private var shotSteering = false
    private var shotDeadlineNanos = 0L
    private var followYawPitch: Pair<Float, Float>? = null

    private val cameraApproachGuardMeters = 0.9f

    // ── 触摸（相机手势 / 拖拽路由 focused 演员）──────────────────────────

    var isDragMode: Boolean = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        setupSkybox()
        applyRenderSettings(config.renderSettings)
        if (config.enableTouch) {
            surfaceView.setOnTouchListener { _, event ->
                handleTouch(event)
            }
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        return if (isDragMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    focusedRuntime?.addDragDelta(dx, dy)
                }
            }
            true
        } else {
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                cancelShotTransition()
            }
            modelViewer.onTouchEvent(event)
            true
        }
    }

    // ── 演员管理 ─────────────────────────────────────────────────────────

    /** 创建演员槽位（字节管线在后台，[AvatarRuntime.bind] 完成进场）。 */
    fun createRuntime(id: String): AvatarRuntime {
        ensureAvatarLoaders()
        val runtime = AvatarRuntime(this, id)
        synchronized(runtimes) {
            runtimes.add(runtime)
            // 多演员默认隔帧步进（P3 降频杠杆的 P0 版）；单演员保持每帧
            if (runtimes.size > 1) springStride = 2L
        }
        if (focusedRuntime == null) focusedRuntime = runtime
        return runtime
    }

    fun removeRuntime(runtime: AvatarRuntime) {
        synchronized(runtimes) {
            runtimes.remove(runtime)
            if (runtimes.size <= 1) springStride = 1L
        }
        if (shotTarget === runtime) {
            shotTarget = null
            activeShot = null
            shotSteering = false
        }
        // 同框名单里走了一个：不再构成「双人同框」，放手让相机停住
        shotTargets?.let { list ->
            val rest = list.filter { it !== runtime }
            shotTargets = rest
            if (activeShot == CameraShot.GROUP_SHOT && rest.size < 2) {
                activeShot = null
                shotTargets = null
                shotSteering = false
            }
        }
        if (focusedRuntime === runtime) focusedRuntime = runtimes.firstOrNull()
        runtime.destroyAsset()
    }

    fun allRuntimes(): List<AvatarRuntime> = synchronized(runtimes) { runtimes.toList() }

    private fun ensureAvatarLoaders() {
        if (avatarAssetLoader == null) {
            avatarMaterialProvider = UbershaderProvider(engine)
            avatarAssetLoader = AssetLoader(engine, avatarMaterialProvider!!, EntityManager.get())
        }
        if (avatarResourceLoader == null) {
            avatarResourceLoader = ResourceLoader(engine, true)
        }
        if (accessoryResourceLoader == null) {
            accessoryResourceLoader = ResourceLoader(engine, true)
        }
    }

    /** MToon 材质库（懒建；任一演员需要换肤时创建，全舞台共享）。 */
    fun obtainMtoonFactory(): MToonMaterialFactory? {
        if (mtoonFactory == null) {
            mtoonFactory = MToonMaterialFactory.create(engine)
        }
        return mtoonFactory
    }

    /** 演员 MToon 状态变化后重推舞台观感（key 灯参数 + 色调映射）。 */
    fun onRuntimeMtoonChanged() {
        synchronized(runtimes) { runtimes.forEach { it.pushMToonKeyLight() } }
        applyViewSettings()
    }

    // ── 帧泵 ─────────────────────────────────────────────────────────────

    private var profilePreAnim = 0L
    private var profileSkin = 0L
    private var profilePostSkin = 0L
    private var profileTail = 0L
    private var profileFrames = 0L
    private var profileWindowStart = 0L

    private val choreoCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val probeT0 = if (STAGE_PROFILE) System.nanoTime() else 0L
            val dt = if (lastFrameTimeNanos == 0L) 0.016f
            else ((frameTimeNanos - lastFrameTimeNanos).coerceAtLeast(0L)) / 1_000_000_000.0f
            val dtClamped = dt.coerceIn(0.001f, 0.05f)

            val current = synchronized(runtimes) { runtimes.toList() }

            // ① 姿势层（动画写入；只写局部/root，不提交）
            for (runtime in current) {
                try {
                    runtime.preAnimation(frameTimeNanos, dtClamped)
                } catch (e: Exception) {
                    Log.e(TAG, "preAnimation failed for ${runtime.id}", e)
                }
            }

            val probeT1 = if (STAGE_PROFILE) System.nanoTime() else 0L
            // ② 共享提交（引擎级一次；N 演员全部骨骼写入后统一生效）
            engine.transformManager.commitLocalTransformTransaction()

            // ③ 覆盖层（呼吸/视线/模仿；引擎内部自提交，多演员交错无害）
            val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
            for (runtime in current) {
                try {
                    runtime.postAnimation(nowElapsedMs, dtClamped)
                } catch (e: Exception) {
                    Log.e(TAG, "postAnimation failed for ${runtime.id}", e)
                }
            }

            // ④ 蒙皮 + 物理。弹簧骨隔帧步进（stride，§6 杠杆提前落地）：
            // N 演员交错，单帧只步进 1/N 的弹簧骨（步长=累计 dt，Verlet 等价），
            // 重模型（10.vrm 的 center 空间弹簧链）×N 不再挤爆帧预算。
            // stride=1 = 每帧全步进（单演员与老路径逐位一致）。
            val stride = springStride.coerceAtLeast(1)
            springFrameSeq++
            for ((idx, runtime) in current.withIndex()) {
                val stepped = (springFrameSeq + idx) % stride == 0L
                // ⚠步长不乘 stride：跨帧累计的大步长会把引擎 substeps 顶到
                // 上限（8×substep=28ms），反而锁死低帧率。半速率小步长采样
                // （30Hz）对头发/裙摆物理视觉无损，且让 substeps 随帧率恢复
                // 自然回落（低帧→4 步→更快→dt 更小→逃出高成本吸引子）。
                val springDt = if (stepped) dtClamped else 0f
                try {
                    if (STAGE_PROFILE) {
                        val t0 = System.nanoTime()
                        runtime.skinAndPhysics(frameTimeNanos, springDt)
                        profileSkin += System.nanoTime() - t0
                    } else {
                        runtime.skinAndPhysics(frameTimeNanos, springDt)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "skinAndPhysics failed for ${runtime.id}", e)
                }
            }

            val probeT2 = if (STAGE_PROFILE) System.nanoTime() else 0L
            // ⑤ 运镜 → fps → 渐进进场 → 渲染
            updateCameraShot(frameTimeNanos)
            lastFrameTimeNanos = frameTimeNanos
            updateFps(frameTimeNanos)

            // 演员纹理与场景纹理异步就绪后逐个进场
            avatarResourceLoader?.asyncUpdateLoad()
            for (runtime in current) {
                try {
                    runtime.populateRenderables()
                } catch (e: Exception) {
                    Log.e(TAG, "populateRenderables failed for ${runtime.id}", e)
                }
            }
            populateSceneEntities()

            // VFX 泵：updateCameraShot 之后（同帧相机基向量）、render 之前
            //（docs/vfx-feasibility.md §4.4；演员骨骼已在步骤②提交）
            vfxManager?.update(frameTimeNanos)
            feedSpringBoneWind()

            val probeT3 = if (STAGE_PROFILE) System.nanoTime() else 0L
            modelViewer.render(frameTimeNanos)
            if (STAGE_PROFILE) {
                val now = System.nanoTime()
                profilePreAnim += probeT1 - probeT0
                profilePostSkin += probeT2 - probeT1
                profileTail += now - probeT3
                profileFrames++
                if (profileWindowStart == 0L) profileWindowStart = now
                if (now - profileWindowStart > 2_000_000_000L) {
                    val f = profileFrames.coerceAtLeast(1)
                    android.util.Log.i("StageProfile",
                        "pre=%.1fms post=%.1fms skin=%.1fms tail=%.1fms fps=%.0f".format(
                            profilePreAnim / f / 1e6, (profilePostSkin - profileSkin) / f / 1e6,
                            profileSkin / f / 1e6, profileTail / f / 1e6,
                            f * 1e9f / (now - profileWindowStart)))
                    profilePreAnim = 0; profilePostSkin = 0; profileTail = 0; profileSkin = 0
                    profileFrames = 0; profileWindowStart = now
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private var lastFrameTimeNanos = 0L

    /** 弹簧骨隔帧步进节拍（1=每帧；多演员默认 2，见泵内注释）。 */
    @Volatile var springStride: Long = 1L
    private var springFrameSeq = 0L

    private fun updateFps(frameTimeNanos: Long) {
        if (fpsWindowStartNanos == 0L) {
            fpsWindowStartNanos = frameTimeNanos
            fpsFrameCount = 0
        }
        fpsFrameCount++
        val elapsed = frameTimeNanos - fpsWindowStartNanos
        if (elapsed >= FPS_WINDOW_NANOS) {
            onFpsUpdated?.invoke(((fpsFrameCount * 1_000_000_000L) / elapsed).toInt())
            fpsFrameCount = 0
            fpsWindowStartNanos = frameTimeNanos
        }
    }

    // ── 渲染设置（单虚拟人逐行移植；灯光 rig 全 SPOT）────────────────────

    fun applyRenderSettings(settings: AvatarRenderSettings) {
        renderSettings = settings
        applyLighting()
        applyIblSettings()
        applyViewSettings()
        synchronized(runtimes) { runtimes.forEach { it.applyMaterialEnhancements() } }
    }

    /** 演员加载完成后的模式自动切换（fresh 加载跟随模型材质）。 */
    fun setDetectedRenderMode(mode: RenderMode): Boolean {
        if (renderSettings.renderMode == mode) return false
        renderSettings = renderSettings.copy(renderMode = mode)
        applyLighting()
        applyViewSettings()
        return true
    }

    private data class ShadowSignature(
        val shadowMapSize: Int,
        val contactShadows: Boolean,
    )

    private fun applyLighting() {
        val shadowSignature = ShadowSignature(
            shadowMapSize = renderSettings.shadowMapSize,
            contactShadows = renderSettings.contactShadows,
        )
        if (keyLightEntity == 0 || shadowSignature != keyShadowSignature) {
            keyShadowSignature = shadowSignature
            destroyLight(keyLightEntity)
            keyLightEntity = 0
            keyLightEntity = createSpotLight(
                color = KEY_COLOR,
                candela = KEY_CANDELA,
                position = KEY_POSITION,
                target = KEY_TARGET,
                coneInner = KEY_CONE_INNER,
                coneOuter = KEY_CONE_OUTER,
                castShadows = true,
                shadowMapSize = renderSettings.shadowMapSize,
                contactShadows = renderSettings.contactShadows,
            )
        }

        when (renderSettings.lightingRig) {
            LightingRig.KEY_ONLY -> {
                destroyLight(fillLightEntity); fillLightEntity = 0
                destroyLight(rimLightEntity); rimLightEntity = 0
            }
            LightingRig.KEY_FILL -> {
                destroyLight(rimLightEntity); rimLightEntity = 0
                if (fillLightEntity == 0) {
                    fillLightEntity = createSpotLight(
                        color = FILL_COLOR, candela = FILL_CANDELA,
                        position = FILL_POSITION, target = FILL_TARGET,
                        coneInner = FILL_CONE_INNER, coneOuter = FILL_CONE_OUTER,
                    )
                }
            }
            LightingRig.STUDIO -> {
                if (fillLightEntity == 0) {
                    fillLightEntity = createSpotLight(
                        color = FILL_COLOR, candela = FILL_CANDELA,
                        position = FILL_POSITION, target = FILL_TARGET,
                        coneInner = FILL_CONE_INNER, coneOuter = FILL_CONE_OUTER,
                    )
                }
                if (rimLightEntity == 0) {
                    rimLightEntity = createSpotLight(
                        color = RIM_COLOR, candela = RIM_CANDELA,
                        position = RIM_POSITION, target = RIM_TARGET,
                        coneInner = RIM_CONE_INNER, coneOuter = RIM_CONE_OUTER,
                    )
                }
            }
        }

        // MToon 数学不吃场景灯：key 灯作为材质参数推给每个演员
        synchronized(runtimes) { runtimes.forEach { it.pushMToonKeyLight() } }
    }

    private fun createLightEntity(builder: LightManager.Builder, position: FloatArray): Int {
        val eng = engine
        val entity = EntityManager.get().create()
        builder.build(eng, entity)
        val tm = eng.transformManager
        val instance = tm.create(entity)
        val transform = FloatArray(16).apply {
            this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
            this[12] = position[0]; this[13] = position[1]; this[14] = position[2]
        }
        tm.setTransform(instance, transform)
        scene.addEntity(entity)
        return entity
    }

    private fun createSpotLight(
        color: FloatArray,
        candela: Float,
        position: FloatArray,
        target: FloatArray,
        coneInner: Float,
        coneOuter: Float,
        castShadows: Boolean = false,
        shadowMapSize: Int = 1024,
        contactShadows: Boolean = false,
    ): Int {
        val aim = normalize(
            floatArrayOf(
                target[0] - position[0],
                target[1] - position[1],
                target[2] - position[2],
            )
        )
        val builder = LightManager.Builder(LightManager.Type.SPOT)
            .color(color[0], color[1], color[2])
            .intensity(candela)
            .direction(aim[0], aim[1], aim[2])
            .spotLightCone(coneInner, coneOuter)
            .falloff(SPOT_FALLOFF_METERS)
            .castShadows(castShadows)
        if (castShadows) {
            builder.shadowOptions(
                LightManager.ShadowOptions().apply {
                    mapSize = shadowMapSize
                    screenSpaceContactShadows = contactShadows
                    stepCount = CONTACT_SHADOW_STEPS
                }
            )
        }
        return createLightEntity(builder, position)
    }

    private fun destroyLight(entity: Int) {
        if (entity == 0) return
        scene.removeEntity(entity)
        engine.destroyEntity(entity)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (length > 0f) floatArrayOf(v[0] / length, v[1] / length, v[2] / length) else v
    }

    private fun applyIblSettings() {
        val ibl = scene.indirectLight ?: return
        ibl.setIntensity(renderSettings.iblIntensity)
        val radians = Math.toRadians(renderSettings.iblRotationDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        ibl.setRotation(floatArrayOf(c, 0f, -s, 0f, 1f, 0f, s, 0f, c))
    }

    private fun applyViewSettings() {
        val view = modelViewer.view
        val settings = renderSettings

        view.setAmbientOcclusionOptions(
            View.AmbientOcclusionOptions().apply {
                enabled = settings.ambientOcclusion != AmbientOcclusionQuality.OFF
                aoType = if (settings.ambientOcclusion == AmbientOcclusionQuality.STANDARD) {
                    View.AmbientOcclusionOptions.AmbientOcclusionType.SAO
                } else {
                    View.AmbientOcclusionOptions.AmbientOcclusionType.GTAO
                }
                quality = if (settings.ambientOcclusion == AmbientOcclusionQuality.HIGH) {
                    View.QualityLevel.HIGH
                } else {
                    View.QualityLevel.MEDIUM
                }
                radius = AO_RADIUS
            }
        )

        view.setBloomOptions(
            View.BloomOptions().apply {
                enabled = settings.bloomEnabled
                strength = settings.bloomStrength.coerceIn(0f, 0.5f)
                quality = View.QualityLevel.MEDIUM
            }
        )

        val msaa = View.MultiSampleAntiAliasingOptions()
        val taa = View.TemporalAntiAliasingOptions()
        when (settings.antiAliasing) {
            AntiAliasingMode.NONE -> view.antiAliasing = View.AntiAliasing.NONE
            AntiAliasingMode.FXAA -> view.antiAliasing = View.AntiAliasing.FXAA
            AntiAliasingMode.MSAA_4X -> {
                view.antiAliasing = View.AntiAliasing.NONE
                msaa.enabled = true
                msaa.sampleCount = 4
            }
            AntiAliasingMode.TAA -> {
                view.antiAliasing = View.AntiAliasing.NONE
                taa.enabled = true
                taa.sharpness = 0.25f
            }
        }
        view.setMultiSampleAntiAliasingOptions(msaa)
        view.setTemporalAntiAliasingOptions(taa)

        // ⚠PCSS Adreno 悬崖（filament-pcss-adreno-cliff）：软阴影档位保持 1024+PCF
        view.setShadowType(if (settings.softShadows) View.ShadowType.PCSS else View.ShadowType.PCF)

        view.setDepthOfFieldOptions(
            View.DepthOfFieldOptions().apply {
                enabled = settings.depthOfFieldEnabled
                maxApertureDiameter = 0.01f
            }
        )
        if (settings.depthOfFieldEnabled) {
            val eye = FloatArray(3)
            modelViewer.camera.getPosition(eye)
            val dx = eye[0] - MODEL_CENTER[0]
            val dy = eye[1] - MODEL_CENTER[1]
            val dz = eye[2] - MODEL_CENTER[2]
            modelViewer.camera.setFocusDistance(sqrt(dx * dx + dy * dy + dz * dz))
        }

        view.renderQuality = view.renderQuality.apply {
            hdrColorBuffer = View.QualityLevel.HIGH
        }
        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
            enabled = false
        }

        // 任一演员 MToon → LINEAR（P0 从简：per-演员分级留后续期）
        applyToneMapping(if (anyMtoonActive) ToneMappingMode.LINEAR else settings.toneMapping)
    }

    private fun applyToneMapping(mode: ToneMappingMode) {
        if (mode == appliedToneMapping && colorGrading != null) return
        appliedToneMapping = mode
        val eng = engine
        val toneMapper: ToneMapper = when (mode) {
            ToneMappingMode.LINEAR -> ToneMapper.Linear()
            ToneMappingMode.FILMIC -> ToneMapper.Filmic()
            ToneMappingMode.ACES -> ToneMapper.ACES()
        }
        val grading = ColorGrading.Builder()
            .quality(ColorGrading.QualityLevel.HIGH)
            .toneMapper(toneMapper)
            .build(eng)
        modelViewer.view.setColorGrading(grading)
        colorGrading?.let { eng.destroyColorGrading(it) }
        colorGrading = grading
    }

    private fun setupSkybox() {
        val bg = config.backgroundColor
        val skybox = Skybox.Builder()
            .color(bg[0], bg[1], bg[2], bg.getOrElse(3) { 1.0f })
            .build(engine)
        scene.skybox = skybox
    }

    /** 加载内置 IBL（舞台初始化一次；单虚拟人是每次加载模型重载——语义等价，舞台级配置无 per-model 差异）。 */
    private fun loadEnvironment(iblPath: String) {
        try {
            surfaceView.context.assets.open(iblPath).use { input ->
                val buffer = java.nio.ByteBuffer.wrap(input.readBytes())
                val iblBundle = KTX1Loader.createIndirectLight(engine, buffer)
                iblBundle.indirectLight?.let { scene.indirectLight = it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load IBL from $iblPath", e)
        }
        applyIblSettings()
        applyViewSettings()
    }

    /** 舞台初始化收尾（IBL）。init 块之后再跑一次 applyViewSettings 的镜像入口。 */
    fun startup() {
        when (config.iblPath) {
            AvatarConfig.IBL_NONE -> Unit
            null -> loadEnvironment(AvatarConfig.BUILT_IN_IBL)
            else -> loadEnvironment(config.iblPath)
        }
    }

    // ── 场景 GLB（单虚拟人逐行移植）──────────────────────────────────────

    fun loadScene(assetsPath: String, placement: ScenePlacement? = null) {
        removeScene()
        activeScenePlacement = placement
        sceneBaseMatrix = null

        if (sceneAssetLoader == null) {
            val materialProvider = UbershaderProvider(engine)
            sceneAssetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        }
        if (sceneResourceLoader == null) {
            sceneResourceLoader = ResourceLoader(engine, true)
        }

        try {
            surfaceView.context.assets.open(assetsPath).use { input ->
                val buffer = java.nio.ByteBuffer.wrap(input.readBytes())
                val asset = sceneAssetLoader?.createAsset(buffer)
                if (asset != null) {
                    sceneResourceLoader?.asyncBeginLoad(asset)
                    asset.releaseSourceData()
                    sceneAsset = asset
                    applyScenePlacement()
                    Log.i(TAG, "Scene loaded: $assetsPath (${asset.entities.size} entities)" +
                        (placement?.let { ", placement(yaw=${it.yawDeg}, off=${it.offsetX}/${it.offsetY}/${it.offsetZ})" } ?: ""))
                } else {
                    Log.e(TAG, "Failed to create scene asset: $assetsPath")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scene: $assetsPath", e)
        }
    }

    fun setScenePlacement(placement: ScenePlacement?) {
        activeScenePlacement = placement
        applyScenePlacement()
    }

    private fun applyScenePlacement() {
        val asset = sceneAsset ?: return
        val tm = engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return
        if (sceneBaseMatrix == null) {
            val base = FloatArray(16)
            tm.getTransform(instance, base)
            sceneBaseMatrix = base
        }
        val mat = sceneBaseMatrix!!.clone()
        activeScenePlacement?.let { p ->
            if (p.yawDeg != 0f) {
                val yaw = toRadians(p.yawDeg)
                val c = cos(yaw)
                val s = sin(yaw)
                for (col in 0..2) {
                    val i = col * 4
                    val x = mat[i]
                    val z = mat[i + 2]
                    mat[i] = c * x + s * z
                    mat[i + 2] = -s * x + c * z
                }
            }
            mat[12] += p.offsetX
            mat[13] += p.offsetY
            mat[14] += p.offsetZ
        }
        tm.setTransform(instance, mat)
    }

    fun removeScene() {
        sceneAsset?.let { asset ->
            sceneResourceLoader?.asyncCancelLoad()
            sceneResourceLoader?.evictResourceData()
            scene.removeEntities(asset.entities)
            sceneAssetLoader?.destroyAsset(asset)
            Log.i(TAG, "Scene removed")
        }
        sceneAsset = null
        sceneBaseMatrix = null
    }

    private fun populateSceneEntities() {
        sceneResourceLoader?.asyncUpdateLoad()
        sceneAsset?.let { asset -> popRenderables(asset) }
        synchronized(props) {
            for (prop in props) {
                if (prop.pending) popRenderables(prop.asset, onEmpty = { prop.pending = false })
            }
        }
    }

    private fun popRenderables(asset: FilamentAsset, onEmpty: (() -> Unit)? = null) {
        var count: Int
        var any = false
        do {
            count = asset.popRenderables(readyRenderables)
            if (count > 0) {
                scene.addEntities(readyRenderables.take(count).toIntArray())
                any = true
            }
        } while (count > 0)
        if (!any) onEmpty?.invoke()
    }

    // ── 世界静态道具（辩论赛 P0a：座椅布置；复用场景加载器，见 docs/debate-feasibility.md §7.1）──

    /**
     * 加载一个静态 GLB 摆进世界（无骨骼动画，仅 root 变换）。随帧渐进进场
     * （纹理未就绪时先隐藏，与场景/演员同策略）。null = 资产读取或创建失败。
     */
    fun loadProp(assetsPath: String, x: Float, y: Float, z: Float, yawDeg: Float = 0f, scale: Float = 1f): StageProp? {
        if (sceneAssetLoader == null) {
            val materialProvider = UbershaderProvider(engine)
            sceneAssetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        }
        if (sceneResourceLoader == null) {
            sceneResourceLoader = ResourceLoader(engine, true)
        }
        return try {
            val buffer = java.nio.ByteBuffer.wrap(surfaceView.context.assets.open(assetsPath).use { it.readBytes() })
            val asset = sceneAssetLoader?.createAsset(buffer) ?: run {
                Log.e(TAG, "Failed to create prop asset: $assetsPath")
                return null
            }
            // 顺序纪律（同 loadScene/演员 bind）：先挂异步加载再释放源数据——
            // 反过来 = 后台加载线程读到已释放的 cgltf 数据 = SIGSEGV
            sceneResourceLoader?.asyncBeginLoad(asset)
            asset.releaseSourceData()
            val tm = engine.transformManager
            val inst = tm.getInstance(asset.root)
            if (inst != 0) tm.setTransform(inst, propMatrix(x, y, z, yawDeg, scale))
            asset.boundingBox.let { box ->
                Log.i(TAG, "Prop bbox: center=(${box.center[0]},${box.center[1]},${box.center[2]}) half=(${box.halfExtent[0]},${box.halfExtent[1]},${box.halfExtent[2]})")
            }
            val prop = StageProp(asset)
            synchronized(props) { props += prop }
            Log.i(TAG, "Prop loaded: $assetsPath @($x,$y,$z) yaw=$yawDeg scale=$scale (${asset.entities.size} entities)")
            prop
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load prop: $assetsPath", e)
            null
        }
    }

    fun removeProp(prop: StageProp) {
        synchronized(props) { props.remove(prop) }
        scene.removeEntities(prop.asset.entities)
        sceneAssetLoader?.destroyAsset(prop.asset)
        Log.i(TAG, "Prop removed")
    }

    fun removeAllProps() {
        val snapshot = synchronized(props) { props.toList() }
        for (prop in snapshot) removeProp(prop)
    }

    private fun propMatrix(x: Float, y: Float, z: Float, yawDeg: Float, scale: Float): FloatArray {
        val yaw = toRadians(yawDeg)
        val c = cos(yaw) * scale
        val s = sin(yaw) * scale
        // 列主序：绕 Y 旋转（与 applyScenePlacement 同约定）+ 均匀缩放 + 平移
        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, scale, 0f, 0f,
            s, 0f, c, 0f,
            x, y, z, 1f,
        )
    }

    // ── 相机程序化控制（单虚拟人逐行移植）────────────────────────────────

    fun zoomCamera(spreadPx: Float) {
        cancelShotTransition()
        cameraManipulator.scroll(
            surfaceView.width / 2,
            surfaceView.height / 2,
            -spreadPx * ZOOM_SPEED_PER_PIXEL
        )
    }

    fun panCamera(dxPx: Float, dyPx: Float) {
        cancelShotTransition()
        grabCamera(dxPx, dyPx, strafe = true)
    }

    fun orbitCamera(yawPx: Float, pitchPx: Float) {
        cancelShotTransition()
        grabCamera(yawPx, pitchPx, strafe = false)
    }

    private fun grabCamera(dxPx: Float, dyPx: Float, strafe: Boolean) {
        val cx = surfaceView.width / 2
        val cy = surfaceView.height / 2
        cameraManipulator.grabBegin(cx, cy, strafe)
        cameraManipulator.grabUpdate(cx + dxPx.roundToInt(), cy + dyPx.roundToInt())
        cameraManipulator.grabEnd()
    }

    fun resetCamera() {
        cancelShotTransition()
        cameraManipulator.jumpToBookmark(cameraManipulator.homeBookmark)
    }

    fun cameraLookAt(): Triple<FloatArray, FloatArray, FloatArray> {
        val eye = FloatArray(3)
        val target = FloatArray(3)
        val up = FloatArray(3)
        cameraManipulator.getLookAt(eye, target, up)
        return Triple(eye, target, up)
    }

    // ── 运镜（目标参数化：本手术的新能力）────────────────────────────────

    /**
     * 滑向预设 [shot]，取景锚定 [target] 演员的人体骨（null = 当前聚焦演员
     * [focusedRuntime]）。FOLLOW 跟拍骑在目标的移动身体上。GROUP_SHOT 同框
     * 全体在台演员（[target] 忽略——单人点不了「双人同框」，显式名单走
     * [setGroupShot]）。多演员共享一台相机：镜头控制权在舞台。
     */
    fun setCameraShot(shot: CameraShot, target: AvatarRuntime? = null) {
        if (shot == CameraShot.GROUP_SHOT) {
            setGroupShot(synchronized(runtimes) { runtimes.toList() })
            return
        }
        activeShot = shot
        shotTarget = target ?: focusedRuntime ?: synchronized(runtimes) { runtimes.firstOrNull() }
        shotTargets = null
        shotSteering = true
        shotDeadlineNanos = System.nanoTime() + SHOT_TIMEOUT_NANOS
        followYawPitch = if (shot == CameraShot.FOLLOW) currentYawPitch() else null
    }

    /**
     * GROUP_SHOT 显式名单（多虚拟人 P3 双人同框）：pivot = 头部均值、距离随
     * 头距缩放（[GroupShotMath]）。与 FOLLOW 同为「永不到达」的跟踪机位，
     * 但 yaw/pitch 是固定常量——姿态只来自世界骨锚，不吃相机状态（坑 22
     * 自参照固化的多目标版纪律）。少于 2 人在名单里时拒绝（那是特写的活）。
     */
    fun setGroupShot(targets: List<AvatarRuntime>) {
        setGroupShotInternal(targets, fittedFraming = false)
    }

    /**
     * 视锥拟合组像（辩论赛 P0a，docs/debate-feasibility.md §8）：目标边界点
     * 投影 + 实时纵横比算距离，取代头距 × 常数——4v4 队伍与九人全景
     * （头距超旧 span 上限、横屏宽视场）都由本路径取景。
     */
    fun setGroupShotFitted(targets: List<AvatarRuntime>) {
        setGroupShotInternal(targets, fittedFraming = true)
    }

    private fun setGroupShotInternal(targets: List<AvatarRuntime>, fittedFraming: Boolean) {
        val cast = targets.filter { it.asset != null }
        val minTargets = if (fittedFraming) 1 else 2
        if (cast.size < minTargets) {
            Log.w(TAG, "shot needs ≥$minTargets ready avatars (got ${cast.size})")
            return
        }
        activeShot = CameraShot.GROUP_SHOT
        shotTarget = null
        shotTargets = cast
        groupFitFraming = fittedFraming
        shotSteering = true
        shotDeadlineNanos = 0L
        followYawPitch = null
    }

    fun clearCameraShot() {
        activeShot = null
        shotTarget = null
        shotTargets = null
        shotSteering = false
        followYawPitch = null
        groupFitFraming = false
    }

    fun getActiveCameraShot(): CameraShot? = activeShot

    /** 当前运镜目标（诊断）。 */
    fun getCameraShotTarget(): AvatarRuntime? = shotTarget

    private fun cancelShotTransition() {
        shotSteering = false
    }

    /** 旧路径：头均值 + 最远头距 × 3.2（双人/三人对谈档位）。 */
    private fun legacyGroupFrame(): GroupShotMath.Frame? = GroupShotMath.frame(
        shotTargets.orEmpty().mapNotNull { t ->
            if (t.asset == null) null
            else t.boneAnchor(t.headEntity, AvatarRuntime.STAGE_HEAD_FALLBACK_Y)
        },
    )

    /**
     * 拟合路径：每目标取 头/hips/双脚 世界锚点（缺语义骨按比例兜底），混合
     * 坐站都覆盖；竖直 FOV 恒 33°，水平按实时纵横比展开。
     */
    private fun fittedGroupFrame(): GroupShotMath.Frame? {
        val points = mutableListOf<FloatArray>()
        val tmp = FloatArray(3)
        for (t in shotTargets.orEmpty()) {
            if (t.asset == null) continue
            val head = t.boneAnchor(t.headEntity, AvatarRuntime.STAGE_HEAD_FALLBACK_Y)
            points += head
            if (shotTargets?.size == 1) {
                // 单人播报取头胸范围：全身镜头会退到前排辩手身后，
                // 让前景身体挡住后方主持人。头顶余量由拟合边距保证。
                points += t.boneAnchor(t.chestEntity, AvatarRuntime.STAGE_CHEST_FALLBACK_Y)
                continue
            }
            if (t.resolveBonePosition("hips", tmp)) points += tmp.clone() else points += floatArrayOf(head[0], head[1] - 0.5f, head[2])
            if (t.resolveBonePosition("leftfoot", tmp)) points += tmp.clone()
            if (t.resolveBonePosition("rightfoot", tmp)) points += tmp.clone()
        }
        val w = surfaceView.width.coerceAtLeast(1)
        val h = surfaceView.height.coerceAtLeast(1)
        return GroupFrameMath.frame(
            points = points,
            vFovDeg = MANIP_FOV_DEGREES,
            aspect = w.toFloat() / h.toFloat(),
            marginMeters = if (shotTargets?.size == 1) 0.25f else 0.4f,
            minDistance = if (shotTargets?.size == 1) 1.2f else 2.5f,
        )
    }

    /**
     * 走动相机自护（单虚拟人同款）：演员走近静态机位 → true（调用方切 FOLLOW）。
     */
    fun cameraApproachGuard(x: Float, y: Float, z: Float): Boolean {
        if (activeShot == null || activeShot == CameraShot.FOLLOW) return false
        cameraManipulator.getLookAt(shotEye, shotTarget3, shotUp3)
        val dx = x - shotEye[0]
        val dy = y - shotEye[1]
        val dz = z - shotEye[2]
        return dx * dx + dy * dy + dz * dz <
            cameraApproachGuardMeters * cameraApproachGuardMeters
    }

    private class ShotPose(
        val pivot: FloatArray,
        val distance: Float,
        val yaw: Float,
        val pitch: Float,
    )

    private fun updateCameraShot(nowNanos: Long) {
        if (!shotSteering) return
        val shot = activeShot ?: return
        val group = shot == CameraShot.GROUP_SHOT
        val follow = shot == CameraShot.FOLLOW
        val target = if (group) null else shotTarget ?: run { shotSteering = false; return }
        val pose = if (group) {
            // yaw/pitch 固定常量（正前方微俯）：姿态只来自世界骨锚，不吃相机
            // 状态——坑 22 自参照固化在输入层面就不成立
            val frame = if (groupFitFraming) fittedGroupFrame() else legacyGroupFrame()
            frame?.let {
                ShotPose(pivot = it.pivot, distance = it.distance, yaw = 0f, pitch = toRadians(8f))
            } ?: run { shotSteering = false; return }
        } else {
            shotPose(shot, target!!) ?: run { shotSteering = false; return }
        }
        // group 与 follow 同为跟踪机位（演员在走位，pivot 一直动，不到达）；
        // 静态机位 4s 转不到位视为失败放手
        if (!follow && !group && nowNanos > shotDeadlineNanos) {
            Log.w(TAG, "Camera shot transition timed out")
            shotSteering = false
            return
        }
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0) return
        val dt = ((nowNanos - lastFrameTimeNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        val step = 1f - exp(-dt * (if (follow || group) FOLLOW_APPROACH_RATE else SHOT_APPROACH_RATE))

        val goalDir = orbitDirection(pose.yaw, pose.pitch)
        val goalEye = floatArrayOf(
            pose.pivot[0] + goalDir[0] * pose.distance,
            pose.pivot[1] + goalDir[1] * pose.distance,
            pose.pivot[2] + goalDir[2] * pose.distance,
        )

        val eye = FloatArray(3)
        val target3 = FloatArray(3)
        val up = FloatArray(3)
        cameraManipulator.getLookAt(eye, target3, up)
        val gaze = normalize(floatArrayOf(target3[0] - eye[0], target3[1] - eye[1], target3[2] - eye[2]))

        // 1) strafe-pan
        val err = floatArrayOf(goalEye[0] - eye[0], goalEye[1] - eye[1], goalEye[2] - eye[2])
        val right = normalize(cross(gaze, WORLD_UP))
        val upward = cross(right, gaze)
        val panD = dist3(eye, pose.pivot).coerceIn(0.3f, 20f)
        val worldPerPixel = 2f * MANIP_FOV_TAN * panD / h
        val panX = -dot(err, right) * step / worldPerPixel
        val panY = -dot(err, upward) * step / worldPerPixel
        if (abs(panX) >= 1f || abs(panY) >= 1f) {
            cameraManipulator.grabBegin(w / 2, h / 2, true)
            cameraManipulator.grabUpdate(w / 2 + panX.roundToInt(), h / 2 + panY.roundToInt())
            cameraManipulator.grabEnd()
        }

        // 2) orbit
        val dTheta = wrapAngle(pose.yaw - atan2(-gaze[0], -gaze[2])) * step
        val dPhi = (pose.pitch - asin((-gaze[1]).coerceIn(-1f, 1f))) * step
        if (abs(dTheta) > 1e-4f || abs(dPhi) > 1e-4f) {
            cameraManipulator.grabBegin(w / 2, h / 2, false)
            cameraManipulator.grabUpdate(
                w / 2 - (dTheta / MANIP_ORBIT_SPEED).roundToInt(),
                h / 2 - (dPhi / MANIP_ORBIT_SPEED).roundToInt()
            )
            cameraManipulator.grabEnd()
        }

        // 3) scroll
        cameraManipulator.getLookAt(eye, target3, up)
        val newGaze = normalize(floatArrayOf(target3[0] - eye[0], target3[1] - eye[1], target3[2] - eye[2]))
        val s = dot(
            floatArrayOf(goalEye[0] - eye[0], goalEye[1] - eye[1], goalEye[2] - eye[2]),
            newGaze
        ) * step
        val delta = (-s / MANIP_ZOOM_SPEED).coerceIn(-500f, 500f)
        if (abs(delta) > 1e-3f) {
            cameraManipulator.scroll(w / 2, h / 2, delta)
        }

        cameraManipulator.getLookAt(eye, target3, up)
        val gazeDot = dot(normalize(floatArrayOf(target3[0] - eye[0], target3[1] - eye[1], target3[2] - eye[2])), goalDir)
        if (!follow && !group && dist3(eye, goalEye) < SHOT_ARRIVE_DIST && gazeDot > cos(SHOT_ARRIVE_ANGLE_RAD)) {
            shotSteering = false
        }
    }

    /** [shot] 的目标世界位姿（锚定 [target] 的骨骼；单虚拟人 shotPose 的参数化版）。 */
    private fun shotPose(shot: CameraShot, target: AvatarRuntime): ShotPose? {
        if (target.asset == null) return null
        val head = target.boneAnchor(target.headEntity, AvatarRuntime.STAGE_HEAD_FALLBACK_Y)
        val chest = target.boneAnchor(target.chestEntity, AvatarRuntime.STAGE_CHEST_FALLBACK_Y)
        val hips = target.boneAnchor(target.hipsFallbackEntity, AvatarRuntime.STAGE_HIPS_FALLBACK_Y)
        val span = dist3(head, hips).coerceIn(0.25f, 1.5f)
        return when (shot) {
            CameraShot.CLOSE_UP -> ShotPose(
                pivot = lerp3(head, chest, 0.22f),
                distance = 1.45f * span,
                yaw = 0f,
                pitch = toRadians(6f),
            )
            CameraShot.MACRO -> ShotPose(
                pivot = lerp3(head, chest, 0.18f),
                distance = 0.9f * span,
                yaw = 0f,
                pitch = toRadians(4f),
            )
            CameraShot.MEDIUM_SHOT -> ShotPose(
                pivot = lerp3(head, hips, 0.45f),
                distance = 1.85f * span,
                yaw = 0f,
                pitch = toRadians(4f),
            )
            CameraShot.FULL_SHOT -> ShotPose(
                pivot = lerp3(head, hips, 1.10f),
                distance = 3.9f * span,
                yaw = 0f,
                pitch = toRadians(5f),
            )
            CameraShot.LONG_SHOT -> ShotPose(
                pivot = lerp3(head, hips, 1.0f),
                distance = 6.0f * span,
                yaw = 0f,
                pitch = toRadians(6f),
            )
            CameraShot.OVER_SHOULDER -> ShotPose(
                pivot = lerp3(head, chest, 0.55f),
                distance = 2.1f * span,
                yaw = toRadians(38f),
                pitch = toRadians(6f),
            )
            CameraShot.FOLLOW -> {
                val pivot = lerp3(head, hips, 0.9f)
                val yawPitch = followYawPitch ?: currentYawPitch().also { followYawPitch = it }
                val currentDist = run {
                    val (eyeNow, targetNow, _) = cameraLookAt()
                    dist3(eyeNow, pivot)
                }
                ShotPose(
                    pivot = pivot,
                    distance = maxOf(currentDist, 2.5f * span),
                    yaw = yawPitch.first,
                    pitch = yawPitch.second.coerceIn(toRadians(-60f), toRadians(60f)),
                )
            }
            // GROUP_SHOT 不走单目标 pose（updateCameraShot 的 group 分支吃
            // [GroupShotMath]）；这里给 null 保 when 穷尽
            CameraShot.GROUP_SHOT -> null
        }
    }

    private fun currentYawPitch(): Pair<Float, Float> {
        val (eye, target, _) = cameraLookAt()
        val gaze = normalize(
            floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2])
        )
        return atan2(-gaze[0], -gaze[2]) to asin((-gaze[1]).coerceIn(-1f, 1f))
    }

    // 小向量助手（运镜驱动）
    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dist3(a: FloatArray, b: FloatArray): Float =
        sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    private fun lerp3(a: FloatArray, b: FloatArray, t: Float): FloatArray = floatArrayOf(
        a[0] + (b[0] - a[0]) * t,
        a[1] + (b[1] - a[1]) * t,
        a[2] + (b[2] - a[2]) * t,
    )

    private fun orbitDirection(theta: Float, phi: Float): FloatArray =
        floatArrayOf(sin(theta) * cos(phi), sin(phi), cos(theta) * cos(phi))

    private fun wrapAngle(x: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        return ((x + Math.PI.toFloat()) % twoPi + twoPi) % twoPi - Math.PI.toFloat()
    }

    private fun toRadians(deg: Float): Float = (deg * Math.PI / 180.0).toFloat()

    // 复用缓冲（相机自护 / 运镜闭环的每帧分配逃逸）
    private val shotEye = FloatArray(3)
    private val shotTarget3 = FloatArray(3)
    private val shotUp3 = FloatArray(3)

    // ── 截帧 ─────────────────────────────────────────────────────────────

    fun captureNextFrame(onCaptured: (Bitmap) -> Unit): Boolean {
        return if (!isRendering) false else {
            modelViewer.debugGetNextFrameCallback(onCaptured)
            true
        }
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────

    override fun onResume() {
        // 幂等（stage 可能同时被 view 转发与直接观察；重复 post 会双泵）
        if (isRendering) return
        isRendering = true
        Choreographer.getInstance().postFrameCallback(choreoCallback)
    }

    override fun onPause() {
        if (!isRendering) return
        isRendering = false
        Choreographer.getInstance().removeFrameCallback(choreoCallback)
        lastFrameTimeNanos = 0L
    }

    override fun onDestroy() {
        onPause()
        shotSteering = false
        activeShot = null
        shotTarget = null
        shotTargets = null

        // VFX 先收（renderable/缓冲/材质/纹理仍要引擎在位；坑 13 顺序）
        vfxManager?.destroy()
        vfxManager = null

        // 演员：in-use 顺序（asset 先于 MToon 实例，SIGABRT 红线）
        synchronized(runtimes) {
            runtimes.forEach { it.destroyAsset() }
            runtimes.clear()
        }
        focusedRuntime = null
        avatarResourceLoader?.asyncCancelLoad()
        avatarResourceLoader?.evictResourceData()
        avatarResourceLoader?.destroy()
        avatarResourceLoader = null
        avatarAssetLoader?.destroy()
        avatarAssetLoader = null
        avatarMaterialProvider = null
        // 配饰实体已随演员 destroyAsset 全部清理，这里只收加载器
        accessoryResourceLoader?.destroy()
        accessoryResourceLoader = null
        mtoonFactory?.destroy()
        mtoonFactory = null

        removeScene()
        removeAllProps()
        sceneResourceLoader?.destroy()
        sceneResourceLoader = null
        sceneAssetLoader?.destroy()
        sceneAssetLoader = null

        val eng = engine
        listOf(keyLightEntity, fillLightEntity, rimLightEntity)
            .filter { it != 0 }
            .forEach { entity ->
                scene.removeEntity(entity)
                eng.destroyEntity(entity)
            }
        keyLightEntity = 0
        fillLightEntity = 0
        rimLightEntity = 0
        keyShadowSignature = null
        colorGrading?.let { eng.destroyColorGrading(it) }
        colorGrading = null
        appliedToneMapping = null

        vrmaCache.clear()
        lastFrameTimeNanos = 0L
    }

    companion object {
        private const val TAG = "AvatarStage"

        /** 泵分段计时开关（真机性能归因用；StageProfile 日志，默认关）。 */
        internal const val STAGE_PROFILE = false

        init {
            com.google.android.filament.utils.Utils.init()
        }

        // ModelViewer 把模型放在 MODEL_CENTER，默认轨道机位从 +Z 看向它
        internal val MODEL_CENTER = floatArrayOf(0f, 0f, -4f)

        // camutils 常量钉死（运镜闭环换算依赖，与单虚拟人逐位一致）
        private const val MANIP_ZOOM_SPEED = 0.01f
        private const val MANIP_ORBIT_SPEED = 0.01f
        private const val MANIP_FOV_DEGREES = 33f
        private const val MANIP_FAR_PLANE = 5000f

        private const val SHOT_APPROACH_RATE = 7f
        private const val FOLLOW_APPROACH_RATE = 5f
        private const val SHOT_ARRIVE_DIST = 0.012f
        private const val SHOT_ARRIVE_ANGLE_RAD = 0.02f
        private const val SHOT_TIMEOUT_NANOS = 4_000_000_000L

        private val MANIP_FOV_TAN = tan(Math.toRadians((MANIP_FOV_DEGREES / 2f).toDouble())).toFloat()

        private val WORLD_UP = floatArrayOf(0f, 1f, 0f)

        // 三灯 rig（全 SPOT，含「太阳」——方向光方向在本项目被 Y-180 翻转，
        // 见 filament-light-rig-spot-only 结论；参数与单虚拟人逐位一致）
        internal val KEY_COLOR = floatArrayOf(1.0f, 0.98f, 0.95f)
        private const val KEY_CANDELA = 2_250_000f
        internal val KEY_POSITION = floatArrayOf(2.0f, 5.0f, -2.0f)
        internal val KEY_TARGET = floatArrayOf(0.0f, 0.9f, -4.0f)
        private const val KEY_CONE_INNER = 0.35f
        private const val KEY_CONE_OUTER = 0.7f

        private val FILL_COLOR = floatArrayOf(0.8f, 0.85f, 1.0f)
        private const val FILL_CANDELA = 300_000f
        private val FILL_POSITION = floatArrayOf(-2.0f, 2.6f, -2.0f)
        private val FILL_TARGET = floatArrayOf(0.0f, 1.1f, -4.0f)
        private const val FILL_CONE_INNER = 0.55f
        private const val FILL_CONE_OUTER = 1.0f

        private val RIM_COLOR = floatArrayOf(0.95f, 0.97f, 1.0f)
        private const val RIM_CANDELA = 350_000f
        private val RIM_POSITION = floatArrayOf(0.0f, 2.7f, -6.0f)
        private val RIM_TARGET = floatArrayOf(0.0f, 1.3f, -4.0f)
        private const val RIM_CONE_INNER = 0.5f
        private const val RIM_CONE_OUTER = 0.9f

        private const val SPOT_FALLOFF_METERS = 15f

        /** 头发风外力：风速→加速度增益与安全限幅（模型单位/秒²；Verlet 稳定优先）。 */
        private const val SPRINGBONE_WIND_GAIN = 1.1f
        private const val SPRINGBONE_WIND_MAX = 1.3f
        private const val CONTACT_SHADOW_STEPS = 8
        private const val AO_RADIUS = 0.15f
        private const val FPS_WINDOW_NANOS = 500_000_000L
        private const val ZOOM_SPEED_PER_PIXEL = 0.1f
    }
}

/** [Stage] 的生命周期桥（避免 corelib 对 lifecycle 的 api 依赖扩散到 internal 层）。 */
internal interface DefaultLifecycleObserverBridge {
    fun onResume()
    fun onPause()
    fun onDestroy()
}
