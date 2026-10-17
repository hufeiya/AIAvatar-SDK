package com.neethu.corelib.stage.internal

import android.util.Log
import com.google.android.filament.EntityManager
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.FilamentAsset
import com.neethu.corelib.BreathInfo
import com.neethu.corelib.CameraShot
import com.neethu.corelib.LookAtInfo
import com.neethu.corelib.MimicInfo
import com.neethu.corelib.MimicPose
import com.neethu.corelib.RenderMode
import com.neethu.corelib.WalkFinished
import com.neethu.corelib.WalkStartResult
import com.neethu.corelib.WalkSnapshot
import com.neethu.corelib.accessory.AccessoryManager
import com.neethu.corelib.internal.HipsDragOffsetSolver
import com.neethu.corelib.internal.LocomotionEngine
import com.neethu.corelib.internal.LocomotionExtractor
import com.neethu.corelib.internal.MToonApplier
import com.neethu.corelib.internal.VrmBreathEngine
import com.neethu.corelib.internal.VrmExpressionManager
import com.neethu.corelib.internal.VrmLookAtEngine
import com.neethu.corelib.internal.VrmPoseMimicEngine
import com.neethu.corelib.internal.VrmSpringBoneManager
import com.neethu.corelib.internal.VrmaAnimationEngine
import com.neethu.corelib.internal.WalkGait
import com.neethu.corelib.internal.WalkGaitKind

/**
 * 舞台上的一个「演员」：一个 VRM 模型的全部 per-model 状态与逐帧驱动
 * （docs/multi-avatar-feasibility.md §4.1 拆分表的右列）。
 *
 * 与单虚拟人渲染器（SoulLinkRenderer）的对应关系：这里承载的是它原来绑在
 * 「当前唯一模型」上的全部字段（六引擎 / hips 拖拽 / 骨锚 / 走动状态机 /
 * MToon applier），按演员各持一份；引擎本体零改动复用（探索确认全部无
 * 单模型假设）。帧泵由 [Stage] 统一驱动：N 个 [preAnimation] → 1 次共享
 * commit → N 个 [postAnimation] → N 个 [skinAndPhysics]（§8 风险 7 的
 * 「N runtime → 1 commit」纪律）。
 *
 * 生命周期：[bind] 在主线程执行（createAsset + 引擎绑定），销毁走 [destroy]
 * （in-use 顺序与单虚拟人一致：先毁 asset 再毁 MToon 实例）。
 */
internal class AvatarRuntime(
    private val stage: Stage,
    /** 舞台内唯一 ID（诊断/日志/UI 标识，如 "A"/"B"）。 */
    val id: String,
) {
    // ── 模型资产与动画 ───────────────────────────────────────────────────

    internal var asset: FilamentAsset? = null
        private set
    internal var animator: Animator? = null
        private set
    private var startTime = System.nanoTime()
    private var currentAnimationIndex: Int = -1
    private var isAnimationLooping: Boolean = true

    // ── 六大 per-model 引擎 ──────────────────────────────────────────────

    private var vrmaEngine: VrmaAnimationEngine? = null
    private var vrmaStartTime: Long = 0L

    /** 当前已挂载 idle 的来源（同源去重，防重组反复重解析——单虚拟人同款）。 */
    private var idleSource: String? = null

    private var expressionManager: VrmExpressionManager? = null
    private var lookAtEngine: VrmLookAtEngine? = null

    // 呼吸开关/参数跨模型重载保留（单虚拟人同款）
    private var breathEngine: VrmBreathEngine? = null
    private var breathEnabled = true
    private var breathSpeaking = false
    private var breathAmplitudeScale = 1f
    private var breathRateBpm = -1f

    private var mimicEngine: VrmPoseMimicEngine? = null

    internal var springBoneManager: VrmSpringBoneManager? = null
    private var pendingSpringReset: Boolean = false

    // ── 配饰（docs/accessory-feasibility.md P0/P1;每演员一份独立实例）────

    /** 模型文件名（per-model 校准 preset 的 key；loadAvatar 时由舞台写入）。 */
    var modelKey: String = ""

    /** 配饰控制器（懒建；挂点查询走 bind 时解析好的 [boneSemantics]）。 */
    val accessories: AccessoryManager by lazy {
        AccessoryManager(
            host = stage,
            ownerTag = "avatar $id",
            resolveBone = { semantic -> boneSemantics[semantic.lowercase()] ?: 0 },
            resolveSpec = { accId -> stage.accessorySpecResolver?.invoke(modelKey, accId) },
            // P2-3 帽子压头发实验：规格带碰撞体半径时在挂点骨骼上注册外部
            // 碰撞体（弹簧链被推出配饰外）；模型重载时配饰先 destroyAll，
            // 弹簧骨管理器随骨架重建，两侧生命周期对齐。
            springCollider = { spec, boneEntity, register ->
                val mgr = springBoneManager
                if (mgr != null && boneEntity != 0) {
                    if (register) {
                        mgr.addExternalCollider(boneEntity, spec.position, spec.springColliderRadius)
                    } else {
                        mgr.removeExternalCollider(boneEntity)
                    }
                }
            },
        )
    }

    /** 最近一次成功加载的 VRMA 剪辑路径 + loop 标志（VrmaStarted 载荷）。 */
    private var currentVrmaPath: String? = null
    private var currentVrmaLoop = false

    /**
     * VRMA 起播事件（含走动步态等内部播放），[AvatarHandle] 中继为 flow；
     * 配饰动画联动的「出道具」沿（同帧同步通知本演员的配饰状态机）。
     */
    var onVrmaStarted: ((String, String, Boolean) -> Unit)? = null

    // ── hips 拖拽（three-vrm mouse.html 语义）────────────────────────────

    private var dragMovesHips: Boolean = true
    private var hipsEntity: Int = 0
    private var isVrm0: Boolean = false
    private val hipsDragOffset = FloatArray(3)
    private var hipsDragState = HipsDragOffsetSolver.initial()

    // ── 运镜骨锚（0 = 未解析 → 比例兜底）────────────────────────────────

    internal var headEntity: Int = 0
        private set
    internal var chestEntity: Int = 0
        private set
    internal var hipsFallbackEntity: Int = 0
        private set

    // ── 走动（LocomotionEngine 纯数学，per-演员实例）────────────────────

    private val locomotionEngine = LocomotionEngine()
    private var walkGait: WalkGait? = null
    private var walkRunGait: WalkGait? = null
    private var strafeLeftGait: WalkGait? = null
    private var strafeRightGait: WalkGait? = null
    private var activeGait: WalkGait? = null
    private var walkFadeSeconds = 0.25f
    private var gestureOverlayEnabled = true
    private var extractionEnabled = true
    private var walkStartClip: WalkGait? = null
    private var walkStopClip: WalkGait? = null
    private var startClipDeadlineNanos = 0L
    private var walkSpeed = 1.0f

    /** 速度 ≥ 此值（m/s）且配置了跑步步态时自动选用跑步（P2 跑步）。 */
    private val runSpeedMps = 1.9f

    // ── 拖拽模式（触摸位移由 Stage 路由到 focused 演员）──────────────────

    var isDragMode: Boolean = false

    // ── MToon（材质库舞台级共享，applier per-演员）───────────────────────

    internal var mtoonApplier: MToonApplier? = null
        private set

    /** True 当本演员实际换上了 MToon 材质（舞台据此决定 LINEAR 色调映射）。 */
    internal var mtoonActive = false
        private set

    /** 检测出的渲染模式（fresh 加载按材质自动切换时更新，供 UI 同步）。 */
    var onRenderModeDetected: ((RenderMode) -> Unit)? = null

    /** 走动结束事件（arrived/cancelled），[AvatarHandle] 中继为 flow。 */
    var onWalkFinished: ((WalkFinished) -> Unit)? = null

    /**
     * 一次性动作播完事件（`isOneShotActive()` true→false 翻沿，淡出过渡结束、
     * 视觉回到 idle 的瞬间；stop 停播同样触发），[AvatarHandle] 中继为 flow。
     * 初值 prev=false 不误触发；演出编排（团播）等「播完做某事」消费。
     */
    var onVrmaFinished: ((String) -> Unit)? = null
    private var prevOneShotActive = false

    // ── 视锥剔除开关（默认关，眼球丢失 bug 的既有结论）───────────────────

    private var avatarCullingEnabled = false

    // ── 加载 ─────────────────────────────────────────────────────────────

    /**
     * 主线程绑定：createAsset + unit-cube + （VRM0 翻转）+ spawn + 六引擎
     * 绑定 + MToon 换肤。字节预处理已在后台完成（[PreparedModel]）。
     * [spec] 携带 spawn；[autoDetected] 非 null 时舞台切换渲染模式。
     *
     * @throws Exception 模型损坏 / Filament 加载失败（调用方转 Error 状态）。
     */
    fun bind(prepared: PreparedModel, spawn: com.neethu.corelib.stage.AvatarSpawn) {
        val engine = stage.engine
        stage.avatarAssetLoader?.let { loader ->
            // 先清旧（重载路径）：in-use 顺序见 [destroy]
            destroyAsset()

            val asset = loader.createAsset(prepared.finalBuffer)
                ?: throw IllegalStateException("createAsset failed for avatar $id")
            this.asset = asset
            // 纹理异步上传（ModelViewer.loadModelGlb 同款）：renderable 在
            // [populateRenderables] 里随资源就绪逐个进场
            stage.avatarResourceLoader?.asyncBeginLoad(asset)
            asset.releaseSourceData()

            // unit-cube 归一（per-asset 版 transformToUnitCube，见 SpawnMath）
            val tm = engine.transformManager
            val box = asset.boundingBox
            val rootInstance = tm.getInstance(asset.root)
            if (rootInstance != 0) {
                tm.setTransform(
                    rootInstance,
                    SpawnMath.unitCubeTransform(
                        box.center[0], box.center[1], box.center[2],
                        box.halfExtent[0], box.halfExtent[1], box.halfExtent[2],
                    )
                )
            }

            animator = asset.instance?.animator

            // 内置动画默认播第一个（有则播）
            if ((animator?.animationCount ?: 0) > 0) {
                playAnimation(0, loop = true)
            }

            // VRMA 引擎 + 绑定
            val vrmaEngine = VrmaAnimationEngine(engine)
            this.vrmaEngine = vrmaEngine
            // 模型重载 = 骨架/根矩阵重建：走动立即停（步态配置保留）
            activeGait = null
            locomotionEngine.stopWalk()

            vrmaEngine.bindToModel(asset, prepared.rawBytes)

            // VRM 0.x 朝向翻转（180° Y，同单虚拟人：翻转后角色面向 +Z/镜头）
            isVrm0 = prepared.rawBytes.let { ModelPreprocessor.parseVrmMetaVersion(it) } == "0"
            if (vrmaEngine.getVrmMetaVersion() == "0") {
                applyRootY180(rootInstance, tm)
            }

            // spawn 站位（unit-cube/翻转之后叠加，走动同款矩阵语义）
            if (rootInstance != 0) {
                val mat = FloatArray(16)
                tm.getTransform(rootInstance, mat)
                SpawnMath.applySpawn(mat, spawn.x, spawn.y, spawn.z, spawn.yawDeg)
                tm.setTransform(rootInstance, mat)
            }

            // 表情管理器
            expressionManager = VrmExpressionManager(engine).also { mgr ->
                mgr.parseFromGlb(prepared.rawBytes)
                mgr.bindToAsset(asset, prepared.rawBytes)
            }

            // 弹簧骨
            if (stage.config.enableSpringBone) {
                springBoneManager = VrmSpringBoneManager(engine).also { mgr ->
                    mgr.parseFromGlb(prepared.rawBytes)
                    mgr.bindToAsset(asset, prepared.rawBytes)
                }
            }

            // 全语义骨骼表（一次解析；既有绑定 + 配饰挂点共用，见
            // HumanoidBoneResolver——此前每个语义各解析一遍 JSON）
            boneSemantics = HumanoidBoneResolver.resolveAll(asset, prepared.rawBytes)
            fun bone(vararg semantics: String): Int =
                semantics.firstOrNull { boneSemantics.containsKey(it) }?.let { boneSemantics[it]!! } ?: 0

            // hips 拖拽 + 运镜骨锚（换角色丢弃旧拖拽位移，基线重捕）
            hipsEntity = bone("hips")
            hipsFallbackEntity = hipsEntity
            hipsDragOffset.fill(0f)
            hipsDragState = HipsDragOffsetSolver.initial()

            headEntity = bone("head")
            chestEntity = bone("upperChest", "chest", "spine")
            val spineEntity = bone("spine")
            val neckEntity = bone("neck")

            // 视线引擎（VRM0 翻转之后绑定——rest 朝向已面向 +Z）
            lookAtEngine = VrmLookAtEngine(engine).also { gaze ->
                gaze.bind(
                    headEntity,
                    neckEntity,
                    bone("leftEye"),
                    bone("rightEye"),
                )
            }

            // 呼吸引擎（翻转后绑定，世界轴偏移假设面向 +Z）；开关/参数重挂
            breathEngine = VrmBreathEngine(engine).also { breath ->
                breath.bind(
                    spineEntity,
                    bone("chest"),
                    bone("upperChest"),
                    bone("leftShoulder"),
                    bone("rightShoulder"),
                )
                breath.setEnabled(breathEnabled)
                breath.setSpeaking(breathSpeaking)
                breath.setAmplitudeScale(breathAmplitudeScale)
                if (breathRateBpm > 0) breath.setRateHz(breathRateBpm / 60f)
            }

            // 模仿引擎（翻转后绑定；缺骨优雅降级）
            mimicEngine = VrmPoseMimicEngine(engine).also { mimic ->
                mimic.bind(
                    neckEntity,
                    headEntity,
                    bone("leftUpperArm"),
                    bone("leftLowerArm"),
                    bone("leftHand"),
                    bone("rightUpperArm"),
                    bone("rightLowerArm"),
                    bone("rightHand"),
                    spineEntity,
                    bone("chest"),
                    bone("upperChest"),
                    bone("leftShoulder"),
                    bone("rightShoulder"),
                )
            }

            // 视锥剔除按当前开关重放（蒙皮顶点离开绑定姿态静态盒）
            applyAvatarCulling()

            // MToon 换肤（gltfio renderable 就位之后；材质库舞台级共享，懒建）
            prepared.mtoonParse?.let { parse ->
                val factory = stage.obtainMtoonFactory()
                if (factory != null) {
                    val applier = MToonApplier(engine, asset, prepared.rawBytes, parse, factory)
                    applier.apply()
                    mtoonApplier = applier
                    mtoonActive = true
                    pushMToonKeyLight()
                }
            }
            stage.onRuntimeMtoonChanged()
        }
        Log.i(TAG, "avatar $id bound (mtoon=$mtoonActive, vrm0=$isVrm0)")
    }

    /** VRM 0.x 的 180° Y 根翻转（矩阵左乘，与单虚拟人逐行同款）。 */
    private fun applyRootY180(rootInstance: Int, tm: com.google.android.filament.TransformManager) {
        if (rootInstance == 0) return
        val rootMat = FloatArray(16)
        tm.getTransform(rootInstance, rootMat)
        val rot180 = floatArrayOf(
            -1f, 0f, 0f, 0f,
             0f, 1f, 0f, 0f,
             0f, 0f, -1f, 0f,
             0f, 0f, 0f, 1f,
        )
        val result = FloatArray(16)
        for (row in 0..3) {
            for (col in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += rootMat[row + k * 4] * rot180[k + col * 4]
                result[row + col * 4] = sum
            }
        }
        tm.setTransform(rootInstance, result)
        Log.i(TAG, "avatar $id: applied 180° Y rotation for VRM 0.x")
    }

    /** VRM 1.0 = true（呼吸/翻转等分支用）。 */
    fun isVrm0Model(): Boolean = isVrm0

    // ── 骨骼解析（VRM1/VRM0 双语义，bind 时一次解析全语义表）─────────────

    /** 语义名（小写）→ 实体；bind 前为空表（配饰挂点/诊断查询用）。 */
    private var boneSemantics: Map<String, Int> = emptyMap()

    // ── 帧钩子（Stage 泵驱动；顺序=单虚拟人帧回调的逐行移植）─────────────

    /**
     * 姿势层（动画写入）：hips 基线快照 → 呼吸/视线预快照 → VRMA/内置动画
     * → hips 拖拽重钉 → 走动 root 位移。只写骨骼局部/root 变换，不提交。
     */
    fun preAnimation(frameTimeNanos: Long, dt: Float) {
        val hipsBefore = captureHipsTranslation()
        breathEngine?.capturePreAnimation()
        lookAtEngine?.capturePreAnimation()

        // VRMA 优先于内置动画
        val vrma = vrmaEngine
        if (vrma != null && vrma.isActive()) {
            if (vrma.consumeIdleSwap()) pendingSpringReset = true
            val elapsed = (frameTimeNanos - vrmaStartTime).toDouble() / 1_000_000_000.0
            vrma.update(elapsed.toFloat())
        } else {
            animator?.let { anim ->
                if (currentAnimationIndex >= 0 && currentAnimationIndex < anim.animationCount) {
                    val elapsedTimeSeconds = (frameTimeNanos - startTime).toDouble() / 1_000_000_000.0
                    val animDuration = anim.getAnimationDuration(currentAnimationIndex)
                    val time = if (isAnimationLooping) {
                        (elapsedTimeSeconds % animDuration).toFloat()
                    } else {
                        elapsedTimeSeconds.toFloat().coerceAtMost(animDuration)
                    }
                    anim.applyAnimation(currentAnimationIndex, time)
                }
            }
        }

        applyHipsDragOffset(hipsBefore)
        updateLocomotion(dt)
    }

    /**
     * 覆盖层（骑在动画之上）：呼吸 → 视线 → 模仿。引擎内部各自 commit
     * （引擎级全局提交，多演员交错无害），蒙皮传播之前全部落位。
     */
    fun postAnimation(nowElapsedMs: Long, dt: Float) {
        breathEngine?.update(dt)
        lookAtEngine?.update(dt)
        val animationWrote = (vrmaEngine != null && vrmaEngine!!.isActive()) ||
            (animator != null && currentAnimationIndex >= 0)
        val oneShotActive = (vrmaEngine?.isOneShotActive() == true) ||
            (animator != null && currentAnimationIndex >= 0 && !isAnimationLooping)
        mimicEngine?.update(dt, nowElapsedMs, animationWrote, oneShotActive)
        if (prevOneShotActive && !oneShotActive) {
            currentVrmaPath?.let { accessories.onVrmaFinished(it) }
            onVrmaFinished?.invoke(id)
        }
        prevOneShotActive = oneShotActive
    }

    /**
     * 蒙皮与物理：骨骼矩阵传播（Stage 已做一次共享 commit）→ 表情 morph
     * → 弹簧骨（重锚 + 步进 + 再次传播）。
     *
     * [springDt] 是弹簧骨的积分步长（秒）：Stage 按「隔帧步进」节拍发放
     * （多演员交错，单帧只步进一部分演员的弹簧骨，步长=累计 dt——Verlet
     * 积分一步等价两小步，视觉无损）；0 = 本帧轮空。≤0 且首帧时跳过全部
     * 弹簧工作（与单虚拟人 lastFrameTimeNanos 门一致）。
     */
    fun skinAndPhysics(frameTimeNanos: Long, springDt: Float) {
        animator?.updateBoneMatrices()
        expressionManager?.update(frameTimeNanos)
        if (pendingSpringReset && springBoneManager != null) {
            springBoneManager?.reset()
            pendingSpringReset = false
        }
        if (springDt > 0f) {
            springBoneManager?.update(springDt)
            if (springBoneManager != null) {
                animator?.updateBoneMatrices()
            }
        }
    }

    // ── hips 拖拽内部（单虚拟人逐行移植）─────────────────────────────────

    private fun captureHipsTranslation(): FloatArray? {
        if (!dragMovesHips || hipsEntity == 0) return null
        val tm = stage.engine.transformManager
        val instance = tm.getInstance(hipsEntity)
        if (instance == 0) return null
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        return floatArrayOf(mat[12], mat[13], mat[14])
    }

    private fun applyHipsDragOffset(hipsBefore: FloatArray?) {
        if (!dragMovesHips || hipsEntity == 0) {
            hipsDragState = HipsDragOffsetSolver.initial()
            return
        }
        val engineRewrote = vrmaEngine?.consumeHipsRewritten() == true
        val tm = stage.engine.transformManager
        val instance = tm.getInstance(hipsEntity)
        if (instance == 0) return
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        val (newState, target) = HipsDragOffsetSolver.solve(
            before = hipsBefore,
            current = floatArrayOf(mat[12], mat[13], mat[14]),
            rewritten = engineRewrote,
            offset = hipsDragOffset,
            state = hipsDragState,
        )
        hipsDragState = newState
        if (target != null) {
            mat[12] = target[0]
            mat[13] = target[1]
            mat[14] = target[2]
            tm.setTransform(instance, mat)
        }
    }

    /** Stage 触摸路由：拖拽位移增量（像素 → 世界由调用口径定，同单虚拟人）。 */
    fun addDragDelta(dx: Float, dy: Float) {
        if (dragMovesHips && hipsEntity != 0) {
            // VRM 0.x 的 180° Y 翻转：hips 局部系相对世界 X 镜像
            val sign = if (isVrm0) -1f else 1f
            hipsDragOffset[0] += dx * 0.005f * sign
            hipsDragOffset[1] -= dy * 0.005f
            hipsDragState = HipsDragOffsetSolver.State(hipsDragState.base, true)
        } else {
            val asset = asset ?: return
            val tm = stage.engine.transformManager
            val instance = tm.getInstance(asset.root)
            if (instance == 0) return
            val mat = FloatArray(16)
            tm.getTransform(instance, mat)
            mat[12] += dx * 0.005f
            mat[13] -= dy * 0.005f
            tm.setTransform(instance, mat)
        }
    }

    fun setDragMovesHips(enabled: Boolean) {
        if (dragMovesHips == enabled) return
        dragMovesHips = enabled
        clearHipsDragOffset()
    }

    private fun clearHipsDragOffset() {
        hipsDragOffset.fill(0f)
        hipsDragState = HipsDragOffsetSolver.initial()
    }

    // ── 走动（LocomotionEngine + 步态槽；单虚拟人逐行移植）────────────────

    private fun readGaitClip(source: String): com.neethu.corelib.internal.VrmaParser.VrmaAnimation? =
        stage.vrmaClip(source)

    private fun parseWalkGait(assetPath: String, baseSpeed: Float, kind: WalkGaitKind): WalkGait? {
        val animation = readGaitClip(assetPath)
            ?: run {
                Log.e(TAG, "Walk gait parse failed: $assetPath")
                return null
            }
        if (animation.duration <= 0f) {
            Log.e(TAG, "Walk gait parse failed: $assetPath")
            return null
        }
        val vrmFlip = vrmaEngine?.getVrmMetaVersion() == "0"
        val modelHipsY = vrmaEngine?.modelHipsHeight() ?: 1f
        val animHipsY = animation.restHipsPosition[1]
        val heightScale = if (animHipsY > 0.01f) modelHipsY / animHipsY else 1f
        val renderScale = hipsRenderScale()
        val hipsTrack = animation.humanoidTracks.firstOrNull {
            it.boneName == "hips" && it.path == "translation"
        }
        val extractor = LocomotionExtractor.analyze(
            hipsTrack, animation.restHipsPosition, vrmFlip, heightScale, animation.duration
        )
        val speed = if (baseSpeed > 0f) baseSpeed
            else extractor?.let { it.netForwardMeters * renderScale / it.duration } ?: 0f
        if (speed <= 0f) {
            Log.e(TAG, "Walk gait '$assetPath' needs an explicit baseSpeed (in-place clip, no calibration given)")
            return null
        }
        if (extractor != null) {
            Log.i(TAG, "Gait '$assetPath' has baked motion: net=%.2f units/loop (%.2f m/s world, renderScale=%.2f) → 方案C extraction on"
                .format(extractor.netForwardMeters, speed, renderScale))
        }
        return WalkGait(assetPath, animation.duration, speed, kind, extractor, renderScale)
    }

    /**
     * hips 局部 1 单位的世界渲染尺度（运行帧读取——bindToModel 时
     * TransformManager 未 commit、读数陈旧，已知陷阱坑 40）。
     */
    private fun hipsRenderScale(): Float {
        val hipsEntity = vrmaEngine?.hipsEntity() ?: return 1f
        val tm = stage.engine.transformManager
        val inst = tm.getInstance(hipsEntity)
        if (inst == 0) return 1f
        val world = FloatArray(16)
        val local = FloatArray(16)
        tm.getWorldTransform(inst, world)
        tm.getTransform(inst, local)
        fun len(m: FloatArray, c: Int) =
            kotlin.math.sqrt(m[c] * m[c] + m[c + 1] * m[c + 1] + m[c + 2] * m[c + 2])
        val wl = len(world, 0)
        val ll = len(local, 0)
        if (wl < 1e-6f || ll < 1e-6f) return 1f
        return wl / ll
    }

    fun setWalkGait(assetPath: String, baseSpeed: Float, kind: WalkGaitKind = WalkGaitKind.WALK): Boolean {
        return try {
            val gait = parseWalkGait(assetPath, baseSpeed, kind) ?: return false
            when (kind) {
                WalkGaitKind.WALK -> walkGait = gait
                WalkGaitKind.RUN -> walkRunGait = gait
                WalkGaitKind.STRAFE_LEFT -> strafeLeftGait = gait
                WalkGaitKind.STRAFE_RIGHT -> strafeRightGait = gait
            }
            Log.i(TAG, "avatar $id gait set [$kind]: ${gait.clipAssetPath} (${gait.durationSeconds}s, base=${gait.baseSpeed} m/s)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Walk gait load failed: $assetPath", e)
            false
        }
    }

    fun setStrafeGaits(leftAssetPath: String, rightAssetPath: String, baseSpeed: Float): Boolean {
        val left = try {
            parseWalkGait(leftAssetPath, baseSpeed, WalkGaitKind.STRAFE_LEFT)
        } catch (e: Exception) {
            Log.e(TAG, "Strafe gait load failed: $leftAssetPath", e); null
        }
        val right = try {
            parseWalkGait(rightAssetPath, baseSpeed, WalkGaitKind.STRAFE_RIGHT)
        } catch (e: Exception) {
            Log.e(TAG, "Strafe gait load failed: $rightAssetPath", e); null
        }
        if (left != null) strafeLeftGait = left
        if (right != null) strafeRightGait = right
        return left != null && right != null
    }

    fun hasWalkGait(): Boolean = walkGait != null

    fun setWalkFadeSeconds(seconds: Float) {
        walkFadeSeconds = seconds.coerceIn(0f, 2f)
    }

    fun getWalkFadeSeconds(): Float = walkFadeSeconds

    fun setGestureOverlayEnabled(enabled: Boolean) {
        gestureOverlayEnabled = enabled
    }

    fun isGestureOverlayEnabled(): Boolean = gestureOverlayEnabled

    fun setExtractionEnabled(enabled: Boolean) {
        extractionEnabled = enabled
    }

    fun isExtractionEnabled(): Boolean = extractionEnabled

    fun setWalkTransitions(startAssetPath: String?, stopAssetPath: String?): Boolean {
        fun parseOrNull(path: String?): WalkGait? {
            if (path == null) return null
            return try {
                parseWalkGait(path, baseSpeed = 1f, kind = WalkGaitKind.WALK)
            } catch (e: Exception) {
                Log.e(TAG, "Walk transition load failed: $path", e); null
            }
        }
        val start = parseOrNull(startAssetPath)
        if (startAssetPath != null && start == null) return false
        val stop = parseOrNull(stopAssetPath)
        if (stopAssetPath != null && stop == null) return false
        walkStartClip = start
        walkStopClip = stop
        return true
    }

    fun hasWalkTransitions(): Boolean = walkStartClip != null || walkStopClip != null

    fun walkGaitsSummary(): String {
        fun fmt(g: WalkGait?) = g?.let {
            "${it.clipAssetPath}(base=%.2f%s)".format(
                it.baseSpeed, if (it.extractor != null) ", baked" else ""
            )
        } ?: "off"
        return "walk=${fmt(walkGait)} run=${fmt(walkRunGait)} " +
            "strafeL=${fmt(strafeLeftGait)} strafeR=${fmt(strafeRightGait)}"
    }

    /** 走动内部分播入口（绕过手势 overlay 自动判定）。 */
    private fun playVrmaAnimationInternal(
        loop: Boolean,
        speed: Float,
        fadeSeconds: Float,
        extractor: LocomotionExtractor?,
        boneMask: Set<String>? = null,
    ) {
        currentAnimationIndex = -1
        vrmaStartTime = System.nanoTime()
        vrmaEngine?.play(loop, speed, fadeSeconds, boneMask, extractor)
        notifyVrmaStarted(loop)
        pendingSpringReset = true
    }

    /**
     * VrmaStarted 统一发射点（公开播放入口与走动内部播放共用）：引擎确有
     * 剪辑在播才发（load 失败后照调 play 的空转不发）；先同步喂本演员的
     * 配饰状态机（动画联动零延迟、同帧出道具），再对外回调/中继 flow。
     */
    private fun notifyVrmaStarted(loop: Boolean) {
        if (vrmaEngine?.isActive() != true) return
        val path = currentVrmaPath ?: return
        accessories.onVrmaStarted(path)
        onVrmaStarted?.invoke(id, path, loop)
    }

    fun startWalk(targetX: Float, targetZ: Float, speed: Float): WalkStartResult {
        if (asset == null) return WalkStartResult.NO_MODEL
        if (!stage.hasScene()) return WalkStartResult.NO_SCENE
        if (mimicInfo()?.engaged == true) return WalkStartResult.MIMIC_ACTIVE
        val gait = (if (speed >= runSpeedMps) walkRunGait else null) ?: walkGait
            ?: return WalkStartResult.NO_GAIT
        val prevGait = activeGait
        val prevSpeed = walkSpeed
        clearHipsDragOffset()
        walkSpeed = speed
        activeGait = gait
        val external = gait.extractor != null && extractionEnabled
        val startClip = walkStartClip?.takeIf {
            !external && locomotionEngine.snapshot().phase == com.neethu.corelib.WalkPhase.IDLE
        }
        val bounds = stage.activeScenePlacement?.bounds
        if (startClip != null && loadVrmaAnimation(startClip.clipAssetPath)) {
            playVrmaAnimationInternal(loop = false, speed = 1f, fadeSeconds = 0f,
                extractor = if (extractionEnabled) startClip.extractor else null)
            startClipDeadlineNanos = System.nanoTime() + (startClip.durationSeconds * 1_000_000_000L).toLong()
            locomotionEngine.startWalk(targetX, targetZ, speed, bounds, startClip.durationSeconds)
        } else {
            startClipDeadlineNanos = 0L
            val sameGaitPlaying = prevGait == gait && prevSpeed == speed &&
                locomotionEngine.snapshot().phase != com.neethu.corelib.WalkPhase.IDLE &&
                vrmaEngine?.isActive() == true
            if (!sameGaitPlaying) {
                if (!loadVrmaAnimation(gait.clipAssetPath)) return WalkStartResult.LOAD_FAILED
                playVrmaAnimationInternal(
                    loop = true,
                    speed = speed / gait.baseSpeed,
                    fadeSeconds = walkFadeSeconds,
                    extractor = if (extractionEnabled) gait.extractor else null,
                )
            }
            locomotionEngine.startWalk(targetX, targetZ, speed, bounds)
        }
        Log.i(TAG, "avatar $id startWalk target=(%.2f, %.2f) speed=%.2f gait=%s%s".format(
            targetX, targetZ, speed, gait.kind, if (external) "/extract" else ""))
        return WalkStartResult.STARTED
    }

    fun startStrafe(left: Boolean, meters: Float, speed: Float): WalkStartResult {
        if (asset == null) return WalkStartResult.NO_MODEL
        if (!stage.hasScene()) return WalkStartResult.NO_SCENE
        if (mimicInfo()?.engaged == true) return WalkStartResult.MIMIC_ACTIVE
        val gait = (if (left) strafeLeftGait else strafeRightGait) ?: return WalkStartResult.NO_GAIT
        clearHipsDragOffset()
        walkSpeed = speed
        activeGait = gait
        val external = gait.extractor != null && extractionEnabled

        val tm = stage.engine.transformManager
        val instance = tm.getInstance(asset!!.root)
        if (instance == 0) return WalkStartResult.NO_MODEL
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        var lx = mat[0]
        var lz = mat[2]
        val len = kotlin.math.sqrt(lx * lx + lz * lz)
        if (len < 1e-5f) {
            val h = locomotionEngine.snapshot().headingDeg.toDouble() * Math.PI / 180.0
            lx = kotlin.math.cos(h).toFloat()
            lz = -kotlin.math.sin(h).toFloat()
        } else {
            lx /= len
            lz /= len
        }
        val sign = if (left) -1f else 1f
        val distance = meters.coerceIn(0.2f, 5f)
        val targetX = mat[12] + sign * lx * distance
        val targetZ = mat[14] + sign * lz * distance

        if (!loadVrmaAnimation(gait.clipAssetPath)) return WalkStartResult.LOAD_FAILED
        playVrmaAnimationInternal(
            loop = true,
            speed = speed / gait.baseSpeed,
            fadeSeconds = walkFadeSeconds,
            extractor = if (extractionEnabled) gait.extractor else null,
        )
        locomotionEngine.startWalk(
            targetX, targetZ, speed, stage.activeScenePlacement?.bounds,
            startHoldSeconds = 0f, headingLocked = true,
        )
        Log.i(TAG, "avatar $id startStrafe %s %.1fm target=(%.2f, %.2f)".format(
            if (left) "left" else "right", distance, targetX, targetZ))
        return WalkStartResult.STARTED
    }

    fun stopWalk() {
        val s = locomotionEngine.snapshot()
        val wasWalking = s.phase != com.neethu.corelib.WalkPhase.IDLE
        val skippedStartClip = startClipDeadlineNanos != 0L
        startClipDeadlineNanos = 0L
        // 方案 C：先冻结步态步速再淡出（淡出期继续步进=原地滑步）
        if (activeGait?.extractor != null && extractionEnabled) {
            vrmaEngine?.setPlaybackSpeed(0f)
        }
        activeGait = null
        locomotionEngine.stopWalk()
        stopVrmaAnimation(walkFadeSeconds)
        val stopClip = walkStopClip
        if (wasWalking && !skippedStartClip && stopClip != null &&
            loadVrmaAnimation(stopClip.clipAssetPath)
        ) {
            playVrmaAnimationInternal(loop = false, speed = 1f, fadeSeconds = 0f, extractor = null)
        }
        if (wasWalking) {
            Log.i(TAG, "avatar $id stopWalk at (%.2f, %.2f)".format(s.x, s.z))
            onWalkFinished?.invoke(WalkFinished(arrived = false, x = s.x, z = s.z, avatarId = id))
        }
    }

    /**
     * 原地转向世界点（多虚拟人 P3 对谈位互相对着）：只转不挪，无步态要求；
     * 转到位照常发 [onWalkFinished]（arrived=true）。走动中/mimic 中拒绝
     * （防抖纪律同坑 9：改目标语义不进编排）。
     */
    fun turnTo(targetX: Float, targetZ: Float): Boolean {
        if (asset == null) return false
        if (mimicInfo()?.engaged == true) return false
        if (!locomotionEngine.startTurnTo(targetX, targetZ, stage.activeScenePlacement?.bounds)) {
            return false
        }
        Log.i(TAG, "avatar $id turnTo (%.2f, %.2f)".format(targetX, targetZ))
        return true
    }

    fun walkSnapshot(): WalkSnapshot = locomotionEngine.snapshot()

    /** 逐帧 root 位移（步态到期切换/提取回灌/相机自护/到达收尾）。 */
    private fun updateLocomotion(dtSeconds: Float) {
        val asset = asset ?: return
        val tm = stage.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return

        // 起步剪辑到期 → 切步态循环
        if (startClipDeadlineNanos != 0L) {
            val gait = activeGait
            if (System.nanoTime() >= startClipDeadlineNanos) {
                startClipDeadlineNanos = 0L
                if (gait != null &&
                    locomotionEngine.snapshot().phase != com.neethu.corelib.WalkPhase.IDLE &&
                    loadVrmaAnimation(gait.clipAssetPath)
                ) {
                    playVrmaAnimationInternal(
                        loop = true,
                        speed = walkSpeed / gait.baseSpeed,
                        fadeSeconds = walkFadeSeconds,
                        extractor = if (extractionEnabled) gait.extractor else null,
                    )
                }
            }
        }

        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        val external = activeGait?.extractor != null
        val motion = locomotionEngine.update(dtSeconds, mat[12], mat[13], mat[14], external)
        if (motion != null) {
            rotateRootAroundY(mat, motion.dHeadingRad)
            mat[12] += motion.dx
            mat[13] += motion.dy
            mat[14] += motion.dz
            tm.setTransform(instance, mat)
            if (external && startClipDeadlineNanos == 0L) {
                vrmaEngine?.setPlaybackSpeed(motion.commandedSpeedMps / (activeGait?.baseSpeed ?: 1f))
            }
        }
        // 方案 C 提取回灌（骨骼写入位移被 root→hips 链路放大，补回同倍数）
        val extracted = vrmaEngine?.consumeExtractedForwardDelta() ?: 0f
        if (extracted != 0f && extractionEnabled) {
            vrmaEngine?.extractorForwardDir()?.let { dir ->
                val scale = activeGait?.renderScale ?: 1f
                val step = extracted * scale
                val c0x = mat[0]; val c0z = mat[2]
                val c2x = mat[8]; val c2z = mat[10]
                val l0 = kotlin.math.sqrt(c0x * c0x + c0z * c0z).coerceAtLeast(1e-6f)
                val l2 = kotlin.math.sqrt(c2x * c2x + c2z * c2z).coerceAtLeast(1e-6f)
                val wx = (c0x / l0) * dir[0] * step + (c2x / l2) * dir[2] * step
                val wz = (c0z / l0) * dir[0] * step + (c2z / l2) * dir[2] * step
                mat[12] += wx
                mat[14] += wz
                tm.setTransform(instance, mat)
            }
        }
        // 相机自护：静态机位下走近镜头 → 切 FOLLOW（目标=自己）
        if (motion != null && stage.cameraApproachGuard(mat[12], mat[13], mat[14])) {
            stage.setCameraShot(CameraShot.FOLLOW, this)
        }
        if (locomotionEngine.consumeArrived()) {
            val s = locomotionEngine.snapshot()
            // wasWalking=false = 原地转向（turnTo）到达：没走过路，不播停步
            // 剪辑、不淡出当前动画（idle 还在上面）——只发到达事件
            val wasWalking = activeGait != null
            activeGait = null
            if (wasWalking) {
                val stopClip = walkStopClip
                if (stopClip != null && loadVrmaAnimation(stopClip.clipAssetPath)) {
                    playVrmaAnimationInternal(loop = false, speed = 1f, fadeSeconds = 0f, extractor = null)
                } else {
                    stopVrmaAnimation(walkFadeSeconds)
                }
            }
            onWalkFinished?.invoke(WalkFinished(arrived = true, x = s.x, z = s.z, avatarId = id))
        }
    }

    /** 世界 Y 旋转左乘到根矩阵（保留 unit-cube 缩放；单虚拟人同款）。 */
    private fun rotateRootAroundY(mat: FloatArray, theta: Float) {
        if (theta == 0f) return
        val c = kotlin.math.cos(theta)
        val s = kotlin.math.sin(theta)
        for (col in 0..2) {
            val i = col * 4
            val x = mat[i]
            val z = mat[i + 2]
            mat[i] = c * x + s * z
            mat[i + 2] = -s * x + c * z
        }
    }

    // ── 内置动画 API ─────────────────────────────────────────────────────

    fun playAnimation(index: Int, loop: Boolean = true) {
        currentAnimationIndex = index
        isAnimationLooping = loop
        startTime = System.nanoTime()
        pendingSpringReset = true
    }

    fun stopAnimation() {
        currentAnimationIndex = -1
    }

    fun getAnimationCount(): Int = animator?.animationCount ?: 0

    // ── VRMA API（缓存穿透到舞台）────────────────────────────────────────

    fun loadVrmaAnimation(assetsPath: String): Boolean {
        val animation = stage.vrmaClipOrNull(assetsPath) ?: return false
        vrmaEngine?.setAnimation(animation)
        currentVrmaPath = assetsPath
        return true
    }

    fun loadVrmaAnimationFromFile(path: String): Boolean = loadVrmaAnimation(path)

    fun setVrmaIdleAnimation(assetsPath: String): Boolean {
        if (idleSource == assetsPath) return true
        val animation = stage.vrmaClipOrNull(assetsPath) ?: return false
        vrmaEngine?.setIdleAnimation(animation)
        idleSource = assetsPath
        return true
    }

    fun setVrmaIdleAnimationFromFile(path: String): Boolean = setVrmaIdleAnimation(path)

    fun clearVrmaIdleAnimation() {
        idleSource = null
        vrmaEngine?.setIdleAnimation(null)
    }

    fun playVrmaAnimation(
        loop: Boolean = true,
        speed: Float = 1f,
        fadeSeconds: Float = 0f,
        boneMask: Set<String>? = null,
        allowWalkOverlay: Boolean = true,
    ) {
        currentAnimationIndex = -1
        vrmaStartTime = System.nanoTime()
        val overlayGesture = allowWalkOverlay && !loop && boneMask == null &&
            gestureOverlayEnabled &&
            locomotionEngine.snapshot().phase != com.neethu.corelib.WalkPhase.IDLE
        val mask = if (overlayGesture) VrmaAnimationEngine.UPPER_BODY_BONES else boneMask
        val fade = if (overlayGesture && fadeSeconds <= 0f) 0.2f else fadeSeconds
        vrmaEngine?.play(loop, speed, fade, mask, null)
        notifyVrmaStarted(loop)
        pendingSpringReset = true
    }

    fun stopVrmaAnimation(fadeSeconds: Float = 0f) {
        vrmaEngine?.stop(fadeSeconds)
        vrmaStartTime = System.nanoTime()
        pendingSpringReset = true
    }

    fun getVrmaDuration(): Float = vrmaEngine?.getDuration() ?: 0f

    // ── 表情 / 视线 / 模仿 / 呼吸 / 弹簧骨 / 剔除 API ────────────────────

    fun setExpression(name: String, weight: Float) {
        expressionManager?.setExpression(name, weight)
    }

    fun clearAllExpressions() {
        expressionManager?.clearAllExpressions()
    }

    fun getAvailableExpressions(): List<String> =
        expressionManager?.getAvailableExpressions() ?: emptyList()

    fun setExpressionTransitionDuration(durationMs: Long) {
        expressionManager?.setTransitionDuration(durationMs)
    }

    fun setLookAtTarget(x: Float, y: Float, z: Float) {
        lookAtEngine?.setTarget(x, y, z)
    }

    fun clearLookAtTarget() {
        lookAtEngine?.clearTarget()
    }

    fun lookAtInfo(): LookAtInfo? = lookAtEngine?.info()

    fun setMimicPose(pose: MimicPose?) {
        mimicEngine?.setPose(pose)
    }

    fun mimicInfo(): MimicInfo? = mimicEngine?.info()

    fun setBreathEnabled(enabled: Boolean) {
        breathEnabled = enabled
        breathEngine?.setEnabled(enabled)
    }

    fun setBreathSpeaking(speaking: Boolean) {
        breathSpeaking = speaking
        breathEngine?.setSpeaking(speaking)
    }

    fun setBreathAmplitudeScale(scale: Float) {
        breathAmplitudeScale = scale
        breathEngine?.setAmplitudeScale(scale)
    }

    fun setBreathRateBpm(bpm: Float) {
        breathRateBpm = bpm
        breathEngine?.setRateHz(bpm / 60f)
    }

    fun breathInfo(): BreathInfo? = breathEngine?.info()

    fun setSpringBoneEnabled(enabled: Boolean) {
        springBoneManager?.setEnabled(enabled)
    }

    fun setAvatarCulling(enabled: Boolean) {
        avatarCullingEnabled = enabled
        applyAvatarCulling()
    }

    private fun applyAvatarCulling() {
        val asset = asset ?: return
        val rm = stage.engine.renderableManager
        var toggled = 0
        for (entity in asset.entities) {
            val instance = rm.getInstance(entity)
            if (instance == 0) continue
            rm.setCulling(instance, avatarCullingEnabled)
            toggled++
        }
        Log.i(TAG, "avatar $id frustum culling ${if (avatarCullingEnabled) "enabled" else "disabled"} ($toggled renderables)")
    }

    // ── 程序化移动 / 骨锚 ────────────────────────────────────────────────

    fun moveAvatar(dx: Float, dy: Float, dz: Float) {
        val asset = asset ?: return
        val tm = stage.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        mat[12] += dx
        mat[13] += dy
        mat[14] += dz
        tm.setTransform(instance, mat)
    }

    /** 世界位置骨锚（运镜 pivot / 他人注视目标）。entity 未解析走比例兜底。 */
    fun boneAnchor(entity: Int, fallbackY: Float): FloatArray {
        if (entity != 0) {
            val tm = stage.engine.transformManager
            val instance = tm.getInstance(entity)
            if (instance != 0) {
                val m = FloatArray(16)
                tm.getWorldTransform(instance, m)
                return floatArrayOf(m[12], m[13], m[14])
            }
        }
        // 兜底：用 root 平移 + 比例高度（root 即当前世界位置，含 spawn/走动）
        val root = asset ?: return floatArrayOf(0f, fallbackY, -4f)
        val tm = stage.engine.transformManager
        val inst = tm.getInstance(root.root)
        val base = if (inst != 0) {
            val m = FloatArray(16)
            tm.getWorldTransform(inst, m)
            floatArrayOf(m[12], m[13], m[14])
        } else floatArrayOf(0f, 0f, -4f)
        return floatArrayOf(base[0], base[1] + fallbackY, base[2])
    }

    fun headWorldPosition(): FloatArray = boneAnchor(headEntity, STAGE_HEAD_FALLBACK_Y)

    // ── VFX 骨骼锚定（docs/vfx-feasibility.md §4.8）──────────────────────

    private val vfxBoneMat = FloatArray(16)

    /**
     * 语义骨世界位置（VfxAnchor.Bone 数据源）：boneSemantics 查实体 → 世界
     * 变换平移列。false = 演员未加载/语义骨缺失/实体已失效（destroyAsset 后
     * 陈旧实体 getInstance=0）——调用方（VfxManager）据此安静自停特效。
     */
    fun resolveBonePosition(boneName: String, out: FloatArray): Boolean {
        if (asset == null) return false
        val entity = boneSemantics[boneName.lowercase()] ?: return false
        if (entity == 0) return false
        val tm = stage.engine.transformManager
        val instance = tm.getInstance(entity)
        if (instance == 0) return false
        tm.getWorldTransform(instance, vfxBoneMat)
        out[0] = vfxBoneMat[12]
        out[1] = vfxBoneMat[13]
        out[2] = vfxBoneMat[14]
        return true
    }

    /** 角色脚底世界位置（root 世界变换平移列；fx 世界锚点的演员态数据源）。 */
    fun worldPos(out: FloatArray): Boolean {
        val asset = asset ?: return false
        val tm = stage.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return false
        val mat = FloatArray(16)
        tm.getWorldTransform(instance, mat)
        out[0] = mat[12]
        out[1] = mat[13]
        out[2] = mat[14]
        return true
    }

    // ── MToon 灯光参数（舞台 key 灯变化时全演员推送）──────────────────────

    fun pushMToonKeyLight() {
        val applier = mtoonApplier ?: return
        val dir = normalize(
            floatArrayOf(
                Stage.KEY_POSITION[0] - Stage.KEY_TARGET[0],
                Stage.KEY_POSITION[1] - Stage.KEY_TARGET[1],
                Stage.KEY_POSITION[2] - Stage.KEY_TARGET[2],
            )
        )
        applier.setKeyLight(dir, MToonApplier.defaultLightColor(Stage.KEY_COLOR))
    }

    private fun normalize(v: FloatArray): FloatArray {
        val length = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (length > 0f) floatArrayOf(v[0] / length, v[1] / length, v[2] / length) else v
    }

    // ── 材质增强（clear coat / roughness；单虚拟人同款）──────────────────

    fun applyMaterialEnhancements() {
        if (!stage.renderSettings.enhanceMaterials) return
        val asset = asset ?: return
        val instances = asset.getInstance()?.materialInstances ?: return
        instances.forEach { instance ->
            val name = instance.name?.lowercase() ?: return@forEach
            val material = instance.material
            when {
                STAGE_EYE_HINTS.any { name.contains(it) } -> {
                    if (material.hasParameter("clearCoat")) {
                        instance.setParameter("clearCoat", 1.0f)
                        if (material.hasParameter("clearCoatRoughness")) {
                            instance.setParameter("clearCoatRoughness", 0.08f)
                        }
                    }
                }
                STAGE_SKIN_HINTS.any { name.contains(it) } -> {
                    if (material.hasParameter("roughnessFactor")) {
                        instance.setParameter("roughnessFactor", 0.5f)
                    }
                }
                STAGE_HAIR_HINTS.any { name.contains(it) } -> {
                    if (material.hasParameter("roughnessFactor")) {
                        instance.setParameter("roughnessFactor", 0.4f)
                    }
                }
            }
        }
    }

    // ── 渐进入场（ResourceLoader 异步纹理就绪后逐个加 renderable）─────────

    /** 把已就绪的 renderable 加进 Scene；返回本帧新加的数量。 */
    fun populateRenderables(): Int {
        val asset = asset ?: return 0
        val loader = stage.avatarResourceLoader ?: return 0
        var added = 0
        var count: Int
        do {
            count = asset.popRenderables(stage.readyRenderables)
            if (count > 0) {
                stage.scene.addEntities(stage.readyRenderables.take(count).toIntArray())
                added += count
            }
        } while (count > 0)
        return added
    }

    // ── 销毁（in-use 顺序：先 asset 后 MToon 实例，SIGABRT 红线）──────────

    /** 从舞台移除本演员：Scene 摘 renderable → 毁 asset → 毁 MToon 实例。 */
    fun destroyAsset() {
        // 配饰先走：其 transform 父链引用本演员骨骼实体，先摘先毁
        accessories.destroyAll()
        val asset = asset ?: return
        val loader = stage.avatarAssetLoader
        stage.scene.removeEntities(asset.entities)
        if (loader != null) {
            loader.destroyAsset(asset)
        } else {
            Log.w(TAG, "avatar $id: asset loader already gone; leaking asset entities")
        }
        this.asset = null
        animator = null
        mtoonApplier?.destroy()
        mtoonApplier = null
        mtoonActive = false
        // 引擎字段随骨架一并失效
        vrmaEngine = null
        idleSource = null
        expressionManager = null
        lookAtEngine = null
        breathEngine = null
        mimicEngine = null
        springBoneManager = null
        activeGait = null
        locomotionEngine.stopWalk()
    }

    companion object {
        private const val TAG = "AvatarRuntime"

        // 比例兜底锚高（unit-cube 归一：脚 ≈ −1、头顶 ≈ +1）
        internal const val STAGE_HEAD_FALLBACK_Y = 0.8f
        internal const val STAGE_CHEST_FALLBACK_Y = 0.35f
        internal const val STAGE_HIPS_FALLBACK_Y = 0.0f

        // 材质名提示（VRM/glTF 惯例）
        internal val STAGE_EYE_HINTS = listOf("eye", "目", "瞳")
        internal val STAGE_SKIN_HINTS = listOf("skin", "face", "body", "顔", "脸", "肌")
        internal val STAGE_HAIR_HINTS = listOf("hair", "髪", "发")
    }
}
