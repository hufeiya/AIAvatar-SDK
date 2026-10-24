package com.neethu.corelib.internal

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.TransformManager
import com.google.android.filament.gltfio.FilamentAsset
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.cos

/**
 * Runtime engine that applies VRMA animation to a loaded VRM model.
 *
 * Handles the full retargeting pipeline following the VRM spec:
 * 1. Animations arrive as **normalized** rotations (from VrmaParser)
 * 2. For VRM 0.x: Flip x/z components (coordinate system difference)
 * 3. Convert from normalized to target bone space:
 *    `targetLocal = restLocal * inv(restWorld) * normalized * restWorld`
 *
 * Reference: pixiv/three-vrm createVRMAnimationClip.ts
 *
 * ## 双槽播放（P2，docs/locomotion-feasibility.md §5.2 档 2）
 *
 * base 槽常驻（idle 循环 / 走路步态），overlay 槽承载淡入淡出的第二个剪辑
 * （three.js `AnimationMixer.crossFadeTo` 同款语义）：
 * - **crossfade**：`play(fadeSeconds>0)` 时现播剪辑成为淡出的 base，新剪辑从 0
 *   淡入——起步/停步不再硬切，任意两剪辑平滑互转；
 * - **手势 overlay**：走动中 `<act:>` 以 [UPPER_BODY_BONES] 掩码淡入（上半身
 *   边走边比划，§5.3 P2），one-shot 播完自动淡出回纯步态；
 * - 一次性动作播完的 idle 接管也走淡入淡出（原来是硬切）。
 *
 * 混合在**归一化空间**做（slerp/lerp 后单次 retarget），共享骨骼按 overlay
 * 权重 w 混合，单侧独有的骨骼直接取拥有方的值（three.js 同款：未被上.clip
 * 绑定的 property 保留另一 clip 的值）。[Slot.boneMask] 控制槽位写集——被
 * 掩码挡掉的骨骼视为该槽不存在，让位给另一槽。
 *
 * ## 方案 C 位移提取（§4.3）
 *
 * 槽位可携带 [LocomotionExtractor]（非原地素材）：hips 平移轨道照常采样，
 * 但写入前减去前向投影（hips 只留起伏+侧向），前向分量以逐帧差值从
 * [consumeExtractedForwardDelta] 交给渲染层积分进 root——可循环、可控速、
 * 不回弹。混合期间暂停回灌（半权重的步态配全额位移=滑步），播放头照常
 * 推进保证恢复后无跳变。
 */
internal class VrmaAnimationEngine(
    private val engine: Engine
) {
    companion object {
        private const val TAG = "VrmaAnimEngine"
        private const val CHUNK_TYPE_JSON = 0x4E4F534A

        /** 一次性动作播完 → idle 接管的淡入时长（秒）。 */
        const val FADE_TO_IDLE_SECONDS = 0.25f

        /** 手势 overlay 播完/被打断时的淡出时长（秒）。 */
        const val OVERLAY_FADE_OUT_SECONDS = 0.18f

        /**
         * 上半身骨骼集（VRM 标准人骨名，P2 §5.3 边走边比划的写集掩码）：
         * walk 步态保持全身，act overlay 只写这些骨骼——hips/腿/脚留给步态。
         */
        val UPPER_BODY_BONES: Set<String> = setOf(
            "spine", "chest", "upperChest", "neck", "head", "jaw", "leftEye", "rightEye",
            "leftShoulder", "leftUpperArm", "leftLowerArm", "leftHand",
            "rightShoulder", "rightUpperArm", "rightLowerArm", "rightHand",
            "leftThumbMetacarpal", "leftThumbProximal", "leftThumbDistal",
            "leftIndexProximal", "leftIndexIntermediate", "leftIndexDistal",
            "leftMiddleProximal", "leftMiddleIntermediate", "leftMiddleDistal",
            "leftRingProximal", "leftRingIntermediate", "leftRingDistal",
            "leftLittleProximal", "leftLittleIntermediate", "leftLittleDistal",
            "rightThumbMetacarpal", "rightThumbProximal", "rightThumbDistal",
            "rightIndexProximal", "rightIndexIntermediate", "rightIndexDistal",
            "rightMiddleProximal", "rightMiddleIntermediate", "rightMiddleDistal",
            "rightRingProximal", "rightRingIntermediate", "rightRingDistal",
            "rightLittleProximal", "rightLittleIntermediate", "rightLittleDistal",
        )
    }

    /** 单个播放槽：剪辑 + 播放头 + 变速 + 循环 + 写集掩码 + 位移提取器。 */
    private class Slot(
        val animation: VrmaParser.VrmaAnimation,
        var playheadSeconds: Float,
        var speed: Float,
        var looping: Boolean,
        /** null = 全部骨骼；非 null = 只写集合内的骨骼（P2 上半身 mask）。 */
        val boneMask: Set<String>?,
        /** 方案 C 提取器（非原地步态），null = 原地素材。 */
        val extractor: LocomotionExtractor?,
    ) {
        /** 该轨道是否落在本槽写集内（掩码语义：被挡=本槽没有这条轨道）。 */
        fun admits(track: VrmaParser.HumanoidTrack): Boolean =
            boneMask == null || track.boneName in boneMask
    }

    // Bone mapping: VRM bone name → Filament entity
    private var boneEntityMap: Map<String, Int> = emptyMap()

    // Rest pose quaternions (extracted from the target VRM model)
    private var boneRestLocalQuat: Map<String, FloatArray> = emptyMap() // boneName -> [x,y,z,w]
    private var boneRestWorldQuat: Map<String, FloatArray> = emptyMap() // boneName -> [x,y,z,w]
    private var boneRestTransforms: Map<String, FloatArray> = emptyMap() // boneName -> mat4x4

    // VRM version: "0" or "1"
    private var vrmMetaVersion: String = "1"

    private var targetHipsY: Float = 1.0f

    /** setAnimation 暂存的待播剪辑，[play] 消费。 */
    private var incomingAnimation: VrmaParser.VrmaAnimation? = null

    /** base 槽（常驻层）。null = 什么都没在播。 */
    private var base: Slot? = null

    /** overlay 槽（淡入/淡出中的第二剪辑）；null = 单槽。 */
    private var overlay: Slot? = null

    /** overlay 当前权重 0..1（共享骨骼的混合因子）。 */
    private var overlayWeight = 0f

    /** overlay 目标权重：1 = 淡入中（将成为 base），0 = 淡出中（将被丢弃）。 */
    private var overlayTarget = 1f

    /** 本次淡入/淡出的总时长（秒）。 */
    private var fadeSeconds = 0f

    /** 淡入期间冻结 base 播放头（一次性动作播完淡出到 idle 时用）。 */
    private var freezeBase = false

    /** 淡入目标是 idle（晋升时置 idleTakeover + 弹簧重置标记）。 */
    private var overlaySettlesIdle = false

    /** 淡入期间 base 槽的共享轨道索引（bone+path → 轨道），fade 建一次。 */
    private var baseTrackIndex: Map<String, VrmaParser.HumanoidTrack>? = null

    private var isPlaying = false

    /**
     * Playhead 积分：dt 只在动画真正被采样的帧累积（未被调用 = 暂停），
     * 渲染时钟被 vrmaStartTime 重置/回跳时 dt 取 0，变速不跳相位。
     */
    private var lastElapsedSeconds = 0f

    // Idle takeover: when set, the settled slot is the looping idle animation
    // instead of an action — the VRM rest pose is the A-pose, which reads as
    // "arms spread" (§7.10). During a fade that will settle on idle this stays
    // false so overlay layers (mimic) keep yielding until the promotion.
    private var idleAnimation: VrmaParser.VrmaAnimation? = null
    private var idleTakeover = false
    private var idleSwapPending = false

    /** 方案 C：上一帧的展开前向位移（提取差值的基准）。 */
    private var lastUnrolledForward = 0f

    /** 方案 C：已提取待渲染层消费的前向位移（模型尺度，米）。 */
    private var pendingExtracted = 0f

    private var hipsRewritten = false

    // ── Model Binding ────────────────────────────────────────────────────

    fun bindToModel(asset: FilamentAsset, vrmGlbBytes: ByteArray) {
        val tm = engine.transformManager

        // ⚠先 commit 再读世界变换：bindToModel 在 loadModel+transformToUnitCube
        // 同一帧内被调，TransformManager 惰性求值——不 commit 时 getWorldTransform
        // 返回的是**变换前**的陈旧矩阵，targetHipsY 会错一个数量级（真机
        // 2c3769db 实测 0.112 vs 真值 ~0.75， hips 缩放比连带错 7×，所有 VRMA
        // 平移轨道渲染只剩 13% 尺寸、提取步态速度同倍失真）。TransformManager
        // 惰性陷阱的又一处落点（choreoCallback 的 commit 注释同源）。
        tm.commitLocalTransformTransaction()

        // Detect VRM version
        vrmMetaVersion = detectVrmVersion(vrmGlbBytes)
        Log.i(TAG, "VRM meta version: $vrmMetaVersion")

        // Parse VRM bone name → glTF node name mapping
        val vrmBoneNodeNames = parseVrmBoneNodeNames(vrmGlbBytes)

        val entityMap = mutableMapOf<String, Int>()
        val restLocal = mutableMapOf<String, FloatArray>()
        val restWorld = mutableMapOf<String, FloatArray>()
        val restMats = mutableMapOf<String, FloatArray>()

        if (vrmBoneNodeNames.isNotEmpty()) {
            Log.i(TAG, "Using VRM extension bone mapping (${vrmBoneNodeNames.size} bones)")
            for ((boneName, nodeName) in vrmBoneNodeNames) {
                val entity = asset.getFirstEntityByName(nodeName)
                if (entity == 0) continue

                entityMap[boneName] = entity
                val instance = tm.getInstance(entity)
                if (instance == 0) continue

                // Save rest local transform
                val localMat = FloatArray(16)
                tm.getTransform(instance, localMat)
                restMats[boneName] = localMat.clone()
                restLocal[boneName] = matrixToQuaternion(localMat)

                // Save rest world transform
                val worldMat = FloatArray(16)
                tm.getWorldTransform(instance, worldMat)
                restWorld[boneName] = matrixToQuaternion(worldMat)
            }
        }

        boneEntityMap = entityMap
        boneRestLocalQuat = restLocal
        boneRestWorldQuat = restWorld
        boneRestTransforms = restMats

        // Hips Y for translation scaling —— **局部 rest** y（getTransform 直读，
        // 不经过世界合成）。⚠不能用世界 wm[13]：transformToUnitCube 把模型
        // **居中**后世界 y = 「hips 相对模型中心的高度」（hips 在中心略下方，
        // 真机 4705 实测 0.108），拿它当身高比会把所有 VRMA 平移轨道/步态
        // 提取位移压小 ~8×（0.857→0.104）=「走路慢 4~7 倍」的根因（walk_to/
        // <move:>/手柄全中招）。局部/素材两侧同为各自 rig 单位，比例即标准
        // 比例重定向；世界位移由反馈侧 renderScale（链路缩放积）补齐，对
        // unit-cube 大缩放模型同样成立。bind 时骨骼未被动画改写，局部矩阵
        // 即 rest；commit 在上（防惰性求值的旧读数）。
        val hipsEntity = entityMap["hips"]
        if (hipsEntity != null) {
            val inst = tm.getInstance(hipsEntity)
            if (inst != 0) {
                val lm = FloatArray(16)
                tm.getTransform(inst, lm)
                targetHipsY = if (lm[13] > 0.01f) lm[13] else 1.0f
            }
        }

        Log.i(TAG, "Bound ${entityMap.size} bones, hipsY=$targetHipsY, version=$vrmMetaVersion")
    }

    /** 模型 hips **局部 rest** y（bindToModel 时测得）——位移提取的尺度换算
     * 用；素材→模型的缩放比 = 此值 / 素材 restHipsPosition[1]（两侧同为各自
     * rig 的局部单位）。世界口径的 hips 高受 transformToUnitCube 居中平移
     * 污染，不可用（见 bindToModel 注释）。 */
    fun modelHipsHeight(): Float = targetHipsY

    /** hips 的 Filament entity（渲染层取世界/局部矩阵算链路渲染尺度用）。 */
    fun hipsEntity(): Int = boneEntityMap["hips"] ?: 0

    fun setAnimation(animation: VrmaParser.VrmaAnimation) { incomingAnimation = animation }

    /**
     * Set the looping idle the engine returns to after one-shots / stops
     * (null = rest pose).
     *
     * 接管语义：
     * - 挂上 idle 时没有任何动作在播（模型加载后/冷启动）→ **立即进入待机
     *   循环**，否则模型会一直停在绑定姿态（T-pose）；
     * - 正在展示旧 idle 时挂新 idle → 立即热切换并从第 0 帧起播；
     * - 正在展示 idle 时挂 null → 停止播放并回 rest pose；
     * - 正在播动作时不打断——播完或 [stop] 自然回到新 idle。
     */
    fun setIdleAnimation(animation: VrmaParser.VrmaAnimation?) {
        val showingIdle = isPlaying && idleTakeover && overlay == null
        idleAnimation = animation
        when {
            animation == null -> {
                if (showingIdle) {
                    isPlaying = false
                    base = null
                    idleTakeover = false
                    restoreRestPose()
                }
            }
            !isPlaying || showingIdle -> {
                base = Slot(animation, 0f, 1f, looping = true, boneMask = null, extractor = null)
                overlay = null
                overlayWeight = 0f
                isPlaying = true
                idleTakeover = true
                idleSwapPending = true
            }
            // else: 动作播放中——只更新槽位，播完/stop 后接管
        }
    }
    fun getIdleDuration(): Float = idleAnimation?.duration ?: 0f

    /**
     * Start playback of the loaded animation. [speed] scales the clip clock
     * (walk locomotion: groundSpeed / gait baseSpeed so footsteps match the
     * actual travel); the default 1 keeps gesture/idle behavior unchanged.
     *
     * P2 双槽：[fadeSeconds] > 0 且当前有剪辑在播时，现播内容成为淡出的
     * base、新剪辑淡入（crossfade）；无剪辑在播时忽略（无从混合，直接起播）。
     * [boneMask] 限定新剪辑写集（手势 overlay 传 [UPPER_BODY_BONES]）；
     * [extractor] 挂方案 C 位移提取（走动步态用）。
     */
    internal fun play(
        loop: Boolean = true,
        speed: Float = 1f,
        fadeSeconds: Float = 0f,
        boneMask: Set<String>? = null,
        extractor: LocomotionExtractor? = null,
    ) {
        val anim = incomingAnimation ?: return
        incomingAnimation = null
        val slot = Slot(anim, 0f, speed.coerceIn(0.25f, 3f), loop, boneMask, extractor)
        if (fadeSeconds > 0f && isActive()) {
            // 已有 fade 在跑：先把 overlay 原地晋升成 base（权重跳变可接受，
            // 连续 crossfade 是罕见路径），再对快照开新 fade。
            promoteOverlay()
            val current = base
            if (current != null) {
                base = current
                overlay = slot
                overlayWeight = 0f
                overlayTarget = 1f
                this.fadeSeconds = fadeSeconds.coerceIn(0.05f, 2f)
                freezeBase = false
                overlaySettlesIdle = false
                idleTakeover = false
                isPlaying = true
                baseTrackIndex = indexTracks(current)
                lastUnrolledForward = extractor?.forwardAt(0f) ?: 0f
                pendingExtracted = 0f
                return
            }
        }
        base = slot
        overlay = null
        overlayWeight = 0f
        isPlaying = true
        idleTakeover = false
        freezeBase = false
        overlaySettlesIdle = false
        baseTrackIndex = null
        lastUnrolledForward = extractor?.forwardAt(0f) ?: 0f
        pendingExtracted = 0f
    }

    /**
     * Stop playback. With an idle configured this returns to the idle loop;
     * without one it restores the rest pose.
     *
     * P2：[fadeSeconds] > 0 时淡入淡出——手势 overlay 在走动 base 上快速淡出
     * （走动继续）；单槽动作冻结当前姿态淡入 idle（急停不再硬切）。
     */
    fun stop(fadeSeconds: Float = 0f) {
        val idle = idleAnimation
        val baseSlot = base
        if (fadeSeconds > 0f && isPlaying && overlay != null && overlaySettlesIdle) {
            // 已在淡向 idle（重复 stop 幂等）：不动正在进行的过渡
            return
        }
        if (fadeSeconds > 0f && isPlaying && overlay != null &&
            baseSlot != null && baseSlot.looping && !overlaySettlesIdle
        ) {
            // 手势 overlay 收尾：淡出回纯 base（走动/idle 循环不中断）
            overlayTarget = 0f
            this.fadeSeconds = fadeSeconds.coerceIn(0.05f, OVERLAY_FADE_OUT_SECONDS * 2f)
            return
        }
        if (idle != null) {
            if (fadeSeconds > 0f && isPlaying && baseSlot != null && overlay == null) {
                // 单槽动作（步态/手势）淡入 idle：一次性冻结尾帧，循环照常播
                overlay = Slot(idle, 0f, 1f, looping = true, boneMask = null, extractor = null)
                overlayWeight = 0f
                overlayTarget = 1f
                this.fadeSeconds = fadeSeconds.coerceIn(0.05f, 2f)
                freezeBase = !baseSlot.looping
                overlaySettlesIdle = true
                baseTrackIndex = indexTracks(baseSlot)
                return
            }
            base = Slot(idle, 0f, 1f, looping = true, boneMask = null, extractor = null)
            overlay = null
            overlayWeight = 0f
            isPlaying = true
            idleTakeover = true
            idleSwapPending = true
        } else {
            isPlaying = false
            base = null
            overlay = null
            overlayWeight = 0f
            restoreRestPose()
        }
    }

    fun isActive(): Boolean = isPlaying && base != null

    /**
     * 是否正在播**一次性**动作（非 idle 接管）——骨骼叠加层（模仿引擎）的挂起
     * 判据：`<act:>` 手势/技能手势要完整播放，播完回 idle 后叠加层自动续上。
     * 循环播放的非 idle 手动动画也算（用户手动播动画时模仿让路是合理语义）。
     * 淡出到 idle 的过渡期仍算（动作还在半权重驱动骨骼）。
     */
    fun isOneShotActive(): Boolean = isPlaying && !idleTakeover && base != null

    fun getVrmMetaVersion(): String = vrmMetaVersion

    /** Duration of the settling animation in seconds (0 when none). */
    fun getDuration(): Float = settleTarget()?.animation?.duration ?: 0f

    /** 运行时改 base 槽变速（方案 C 走动的制动/ease 每帧调；playhead 积分下不跳相位）。 */
    fun setPlaybackSpeed(speed: Float) {
        base?.speed = speed.coerceIn(0f, 3f)
    }

    /**
     * 方案 C：取走自上次调用以来提取的前向位移（模型尺度，米，沿槽位
     * [LocomotionExtractor.forwardDir] 方向）。混合期间不累积（渲染层拿到 0）。
     */
    fun consumeExtractedForwardDelta(): Float {
        val pending = pendingExtracted
        pendingExtracted = 0f
        return pending
    }

    /**
     * 走动停滞诊断快照（看门狗触发时打一行，非热路径）：双槽/提取/播放头
     * 一览。方案 C 下位移全靠 base 槽的提取器逐帧回灌——base 无提取器
     * （被不含 extractor 的 play 顶掉）、isPlaying=false、或 fade 卡死不晋升
     * 都会让位移恒 0 而走动状态机照常 WALKING（= 原地冻结）。
     */
    fun debugState(): String {
        val b = base
        val ov = overlay
        return "playing=$isPlaying" +
            " base=" + (b != null) +
            " baseExtractor=" + (b?.extractor != null) +
            " baseSpeed=" + (b?.speed ?: 0f) +
            " loop=" + (b?.looping ?: false) +
            " playhead=" + (b?.playheadSeconds ?: 0f) +
            " freezeBase=$freezeBase" +
            " overlay=" + (ov != null) +
            " overlayWeight=$overlayWeight" +
            " fadeSeconds=$fadeSeconds" +
            " overlayMask=" + (ov?.boneMask != null) +
            " pendingExtracted=$pendingExtracted" +
            " idleTakeover=$idleTakeover"
    }

    /** 当前提取前向方向（模型空间 XZ 单位向量）；无提取器时 null。 */
    fun extractorForwardDir(): FloatArray? = base?.extractor?.forwardDir

    /**
     * 方案 C 提取链当前是否在产位移：base 带提取器，或步态正作为 overlay
     * 淡入（起步 crossfade 的 0.25s 内 base 还是 idle——合法窗口，不算断供）。
     * 走动停滞自愈的判据：步态槽被手势/待机/停步淡入时序顶掉（base 无提取器
     * 且没有步态 overlay 在淡入）= 提取位移恒 0 而走动状态机照常 WALKING，
     * 即原地冻结（2026-10-24 真机遥测实锤 baseExtractor=false）。
     */
    fun isExtractionHealthy(): Boolean {
        val b = base ?: return false
        if (b.extractor != null) return true
        val ov = overlay
        return ov != null && ov.extractor != null && overlayTarget == 1f
    }

    /** One-shot→idle transition happened since the last call (renderer resets spring bones). */
    fun consumeIdleSwap(): Boolean {
        val pending = idleSwapPending
        idleSwapPending = false
        return pending
    }

    /**
     * hips 局部平移被本引擎重写过（平移轨道写入或 [restoreRestPose]）——
     * 渲染器据此重新钉上持久化的拖拽位移（拖拽只写一次会被动画下一帧
     * 覆写，见 SoulLinkRenderer.applyHipsDragOffset）。
     */
    fun consumeHipsRewritten(): Boolean {
        val rewritten = hipsRewritten
        hipsRewritten = false
        return rewritten
    }

    // ── Frame Update ─────────────────────────────────────────────────────

    fun update(elapsedSeconds: Float) {
        // Playhead 积分：dt 只在动画真正被采样的帧累积（未被调用 = 暂停），
        // 渲染时钟被 vrmaStartTime 重置/回跳时 dt 取 0，变速不跳相位。
        val dt = (elapsedSeconds - lastElapsedSeconds).coerceIn(0f, 0.25f)
        lastElapsedSeconds = elapsedSeconds
        if (!isPlaying) return
        val baseSlot = base ?: return

        // 播放头推进：base 冻结（一次性播完淡出 idle）时只有 overlay 前进
        if (!(freezeBase && overlay != null)) {
            baseSlot.playheadSeconds += dt * baseSlot.speed
        }
        val overlaySlot = overlay
        if (overlaySlot != null) {
            overlaySlot.playheadSeconds += dt * overlaySlot.speed
            val dir = if (overlayTarget == 1f) 1f else -1f
            overlayWeight = (overlayWeight + dir * dt / fadeSeconds).coerceIn(0f, 1f)
            // 淡出到位：overlay 丢弃，base 独立续播
            if (overlayTarget == 0f && overlayWeight <= 0f) dropOverlay()
        }

        val tm = engine.transformManager

        val currentOverlay = overlay
        if (currentOverlay == null) {
            writeSlot(baseSlot, tm)
        } else {
            writeBlended(baseSlot, currentOverlay, tm)
        }

        // 方案 C 提取：base 带提取器且回灌通道干净时累积——纯单槽，或 overlay
        // 带上半身 mask（hips 归 base 独占，正交组合）；无 mask 的 crossfade
        // 期间播放头照常推进（基准持续更新，恢复后无跳变），差值丢弃
        // （半权重步态配全额位移=滑步）。
        val extractor = baseSlot.extractor
        if (extractor != null) {
            val unrolled = extractor.forwardAt(baseSlot.playheadSeconds)
            val ov = overlay
            if (ov == null || ov.boneMask != null) {
                pendingExtracted += unrolled - lastUnrolledForward
            }
            lastUnrolledForward = unrolled
        }

        // 一次性播完的接管（P2 双槽语义）：
        // - 淡入完成 → overlay 晋升为 base；目标是 idle 时置接管/弹簧标记；
        // - overlay 是一次性且播完、base 在循环（走动中的手势）→ 淡出回纯步态，
        //   **不晋升**（晋升会把一次性手势变成 base 杀掉走动）；
        // - overlay 一次性播完且 base 不循环 → 晋升后按单槽一次性接管（淡入 idle）；
        // - 纯单槽一次性到尾 → 淡入 idle（有 idle）或回 rest pose（没有）。
        if (currentOverlay != null) {
            if (overlayTarget == 1f && overlayWeight >= 1f) {
                promoteOverlay()
            } else if (overlayTarget == 1f && !currentOverlay.looping &&
                currentOverlay.playheadSeconds >= currentOverlay.animation.duration
            ) {
                if (baseSlot.looping) {
                    overlayTarget = 0f
                    fadeSeconds = OVERLAY_FADE_OUT_SECONDS
                } else {
                    promoteOverlay()
                    handleOneShotCompletion()
                }
            }
        } else if (!baseSlot.looping && baseSlot.playheadSeconds >= baseSlot.animation.duration) {
            handleOneShotCompletion()
        }
    }

    /** 单槽一次性动作到尾的接管（原硬切逻辑的淡入版判定在 [stop] 里实现）。 */
    private fun handleOneShotCompletion() {
        val idle = idleAnimation
        if (idle != null) {
            stop(FADE_TO_IDLE_SECONDS)
        } else {
            isPlaying = false
            base = null
            overlay = null
            overlayWeight = 0f
            restoreRestPose()
        }
    }

    /** 当前「结算目标」：淡入中的 overlay（将成为 base），否则 base。 */
    private fun settleTarget(): Slot? =
        overlay?.takeIf { overlayTarget == 1f } ?: base

    /** overlay 晋升为 base（淡入完成）。目标为 idle 时置接管/弹簧标记。 */
    private fun promoteOverlay() {
        val slot = overlay ?: return
        base = slot
        overlay = null
        overlayWeight = 0f
        baseTrackIndex = null
        if (overlaySettlesIdle) {
            idleTakeover = true
            idleSwapPending = true
        }
        overlaySettlesIdle = false
    }

    /** 丢弃淡出完成的 overlay。 */
    private fun dropOverlay() {
        overlay = null
        overlayWeight = 0f
    }

    /** fade 期间 base 轨道索引（key = bone\u0001path）。 */
    private fun indexTracks(slot: Slot): Map<String, VrmaParser.HumanoidTrack> {
        val map = mutableMapOf<String, VrmaParser.HumanoidTrack>()
        for (track in slot.animation.humanoidTracks) {
            if (slot.admits(track)) map[trackKey(track)] = track
        }
        return map
    }

    private fun trackKey(track: VrmaParser.HumanoidTrack) = "${track.boneName}/${track.path}"

    // ── Slot Writing ─────────────────────────────────────────────────────

    /** 单槽写入：每条轨道采样→（带提取器时剥离前向分量）→ retarget 应用。 */
    private fun writeSlot(slot: Slot, tm: TransformManager) {
        val anim = slot.animation
        if (anim.duration <= 0f) return
        val time = if (slot.looping) slot.playheadSeconds % anim.duration
                   else slot.playheadSeconds.coerceAtMost(anim.duration)
        // 提取的 f 与本帧采样同一时间基（单循环内插值），剥离的量与
        // consumeExtractedForwardDelta 累积的量在循环边界恰好互补。
        val forwardNow = slot.extractor?.forwardAt(time)

        for (track in anim.humanoidTracks) {
            if (!slot.admits(track)) continue
            val entity = boneEntityMap[track.boneName] ?: continue
            when (track.path) {
                "rotation" -> writeRotation(track, time, entity, tm, 0f, null)
                "translation" -> {
                    val pos = interpolateVector3(track.times, track.values, time)
                    writeTranslationSample(track, pos, anim, entity, tm, forwardNow)
                }
            }
        }
    }

    /**
     * 双槽混合写入：共享骨骼（双方都有且都放行）按 overlay 权重混合；
     * 单侧独有的骨骼取拥有方——mask 挡掉的轨道视同不存在（走动步态保全身、
     * 手势 overlay 只写上半身的正交组合就在这条规则里成立）。
     * 提取在混合期不生效（半权重步态配全额位移=滑步），hips 平移按普通
     * 插值处理。
     */
    private fun writeBlended(baseSlot: Slot, overlaySlot: Slot, tm: TransformManager) {
        val baseAnim = baseSlot.animation
        val overlayAnim = overlaySlot.animation
        val baseTime = if (baseAnim.duration <= 0f) 0f
            else if (baseSlot.looping) baseSlot.playheadSeconds % baseAnim.duration
            else baseSlot.playheadSeconds.coerceAtMost(baseAnim.duration)
        val overlayTime = if (overlayAnim.duration <= 0f) 0f
            else if (overlaySlot.looping) overlaySlot.playheadSeconds % overlayAnim.duration
            else overlaySlot.playheadSeconds.coerceAtMost(overlayAnim.duration)
        val w = overlayWeight

        val baseIndex = baseTrackIndex
        val covered = HashSet<String>()
        // Pass 1：overlay 轨道（共享→混合，独有→直接 overlay 值）
        for (track in overlayAnim.humanoidTracks) {
            if (!overlaySlot.admits(track)) continue
            val entity = boneEntityMap[track.boneName] ?: continue
            val key = trackKey(track)
            val baseTrack = baseIndex?.get(key)?.takeIf { baseSlot.admits(it) }
            when (track.path) {
                "rotation" -> {
                    if (baseTrack != null) {
                        covered.add(key)
                        val qa = interpolateQuaternion(baseTrack.times, baseTrack.values, baseTime)
                        val qb = interpolateQuaternion(track.times, track.values, overlayTime)
                        writeRotation(track, 0f, entity, tm, w, qa, qb)
                    } else {
                        writeRotation(track, overlayTime, entity, tm, 0f, null)
                    }
                }
                "translation" -> {
                    if (baseTrack != null) {
                        covered.add(key)
                        val va = interpolateVector3(baseTrack.times, baseTrack.values, baseTime)
                        val vb = interpolateVector3(track.times, track.values, overlayTime)
                        val blended = floatArrayOf(
                            va[0] + (vb[0] - va[0]) * w,
                            va[1] + (vb[1] - va[1]) * w,
                            va[2] + (vb[2] - va[2]) * w,
                        )
                        writeTranslationSample(track, blended, overlayAnim, entity, tm, null)
                    } else {
                        val pos = interpolateVector3(track.times, track.values, overlayTime)
                        writeTranslationSample(track, pos, overlayAnim, entity, tm, null)
                    }
                }
            }
        }
        // Pass 2：base 独有轨道（overlay 没绑或被 mask 挡掉）——保留 base 值。
        // base 带提取器（走动步态 + 上半身手势 overlay 的正交组合）时，hips
        // 平移照常剥离前向分量——overlay 挡不住 hips 归 base 独占的事实。
        val baseTracks = baseIndex?.values ?: baseAnim.humanoidTracks
        val baseForward = if (baseSlot.extractor != null) baseSlot.extractor!!.forwardAt(baseTime) else null
        for (track in baseTracks) {
            if (!baseSlot.admits(track)) continue
            if (trackKey(track) in covered) continue
            val entity = boneEntityMap[track.boneName] ?: continue
            when (track.path) {
                "rotation" -> writeRotation(track, baseTime, entity, tm, 0f, null)
                "translation" -> {
                    val pos = interpolateVector3(track.times, track.values, baseTime)
                    writeTranslationSample(track, pos, baseAnim, entity, tm, baseForward)
                }
            }
        }
    }

    /**
     * 采样并写入旋转轨道。[blendWith]/[sampleB] 非 null 时为双槽混合
     * （blendWeight = overlay 权重，两份归一化四元数 slerp 后单次 retarget），
     * 否则按 [time] 采样单槽值。
     */
    private fun writeRotation(
        track: VrmaParser.HumanoidTrack,
        time: Float,
        entity: Int,
        tm: TransformManager,
        blendWeight: Float,
        blendWith: FloatArray?,
        sampleB: FloatArray? = null,
    ) {
        var normalized = if (blendWith != null && sampleB != null) {
            slerp(blendWith, sampleB, blendWeight)
        } else {
            interpolateQuaternion(track.times, track.values, time)
        }

        // VRM 0.x coordinate flip: negate x and z
        // (three-vrm createVRMAnimationClip: i % 2 === 0 ? -v : v)
        if (vrmMetaVersion == "0") {
            normalized = floatArrayOf(-normalized[0], normalized[1], -normalized[2], normalized[3])
        }

        // Retarget from normalized space to target bone space:
        // targetLocal = restLocal * inv(restWorld) * normalized * restWorld
        val restL = boneRestLocalQuat[track.boneName] ?: QUAT_IDENTITY
        val restW = boneRestWorldQuat[track.boneName] ?: QUAT_IDENTITY
        val restWInv = quatInverse(restW)

        // Retarget: L * (W^-1 * (norm * W))
        val step1 = quatMultiply(normalized, restW)
        val step2 = quatMultiply(restWInv, step1)
        val final_ = quatMultiply(restL, step2)

        applyRotation(tm, entity, track.boneName, final_)
    }

    /**
     * 平移写入：delta 语义（相对素材 rest）+ 身高比缩放 + 可选前向分量剥离
     * （方案 C 单槽步态）。[restHips] 取**样本所属动画**的 rest（双槽混合时
     * 两个素材的 rest 各自正确）。
     */
    private fun writeTranslationSample(
        track: VrmaParser.HumanoidTrack,
        pos: FloatArray,
        ownerAnim: VrmaParser.VrmaAnimation,
        entity: Int,
        tm: TransformManager,
        subtractForwardAt: Float?,
    ) {
        // Scale by height ratio
        val animHipsY = ownerAnim.restHipsPosition[1]
        val scale = if (animHipsY > 0.01f) targetHipsY / animHipsY else 1.0f

        // Compute DELTA from animation's rest hips position, then add
        // to the model's actual rest local translation. This keeps the
        // character centered and only applies relative movement.
        val animRest = ownerAnim.restHipsPosition
        val restMat = boneRestTransforms[track.boneName]
        val restTx = restMat?.get(12) ?: 0f
        val restTy = restMat?.get(13) ?: 0f
        val restTz = restMat?.get(14) ?: 0f

        if (vrmMetaVersion == "0") {
            pos[0] = restTx + -(pos[0] - animRest[0]) * scale
            pos[1] = restTy +  (pos[1] - animRest[1]) * scale
            pos[2] = restTz + -(pos[2] - animRest[2]) * scale
        } else {
            pos[0] = restTx + (pos[0] - animRest[0]) * scale
            pos[1] = restTy + (pos[1] - animRest[1]) * scale
            pos[2] = restTz + (pos[2] - animRest[2]) * scale
        }

        // 方案 C：剥离烘焙的前向分量（渲染层经 consumeExtractedForwardDelta
        // 积分进 root，与这里的剥离在循环边界互补，视觉无净差）。
        val extractor = subtractForwardAt?.let { base?.extractor }
        if (extractor != null) {
            pos[0] -= extractor.forwardDir[0] * subtractForwardAt
            pos[2] -= extractor.forwardDir[2] * subtractForwardAt
        }

        applyTranslation(tm, entity, pos)
    }

    // ── Transform Application ────────────────────────────────────────────

    private fun applyRotation(tm: TransformManager, entity: Int, boneName: String, quat: FloatArray) {
        val instance = tm.getInstance(entity)
        if (instance == 0) return
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        val tx = mat[12]; val ty = mat[13]; val tz = mat[14]
        quaternionToMatrix(quat, mat)
        mat[12] = tx; mat[13] = ty; mat[14] = tz
        tm.setTransform(instance, mat)
    }

    private fun applyTranslation(tm: TransformManager, entity: Int, pos: FloatArray) {
        val instance = tm.getInstance(entity)
        if (instance == 0) return
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        mat[12] = pos[0]; mat[13] = pos[1]; mat[14] = pos[2]
        tm.setTransform(instance, mat)
        if (entity == boneEntityMap["hips"]) hipsRewritten = true
    }

    private fun restoreRestPose() {
        val tm = engine.transformManager
        for ((boneName, restMat) in boneRestTransforms) {
            val entity = boneEntityMap[boneName] ?: continue
            val inst = tm.getInstance(entity)
            if (inst != 0) tm.setTransform(inst, restMat)
            if (boneName == "hips") hipsRewritten = true
        }
    }

    // ── Interpolation ────────────────────────────────────────────────────

    private fun interpolateQuaternion(times: FloatArray, values: FloatArray, t: Float): FloatArray {
        if (times.isEmpty()) return QUAT_IDENTITY.clone()
        if (t <= times.first()) return floatArrayOf(values[0], values[1], values[2], values[3])
        if (t >= times.last()) {
            val i = (times.size - 1) * 4
            return floatArrayOf(values[i], values[i+1], values[i+2], values[i+3])
        }
        var idx = 0
        for (i in 0 until times.size - 1) { if (t >= times[i] && t < times[i+1]) { idx = i; break } }
        val alpha = if (times[idx+1] > times[idx]) (t - times[idx]) / (times[idx+1] - times[idx]) else 0f
        val i0 = idx * 4; val i1 = (idx+1) * 4
        return slerp(
            floatArrayOf(values[i0], values[i0+1], values[i0+2], values[i0+3]),
            floatArrayOf(values[i1], values[i1+1], values[i1+2], values[i1+3]),
            alpha
        )
    }

    private fun interpolateVector3(times: FloatArray, values: FloatArray, t: Float): FloatArray {
        if (times.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        if (t <= times.first()) return floatArrayOf(values[0], values[1], values[2])
        if (t >= times.last()) {
            val i = (times.size - 1) * 3
            return floatArrayOf(values[i], values[i+1], values[i+2])
        }
        var idx = 0
        for (i in 0 until times.size - 1) { if (t >= times[i] && t < times[i+1]) { idx = i; break } }
        val a = if (times[idx+1] > times[idx]) (t - times[idx]) / (times[idx+1] - times[idx]) else 0f
        val i0 = idx * 3; val i1 = (idx+1) * 3
        return floatArrayOf(
            values[i0] + (values[i1] - values[i0]) * a,
            values[i0+1] + (values[i1+1] - values[i0+1]) * a,
            values[i0+2] + (values[i1+2] - values[i0+2]) * a
        )
    }

    // ── Quaternion Math ──────────────────────────────────────────────────

    private val QUAT_IDENTITY = floatArrayOf(0f, 0f, 0f, 1f)

    private fun quatMultiply(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        return floatArrayOf(
            aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx,
            aw*bz + ax*by - ay*bx + az*bw,
            aw*bw - ax*bx - ay*by - az*bz
        )
    }

    private fun quatInverse(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    private fun slerp(q0: FloatArray, q1: FloatArray, t: Float): FloatArray {
        var dot = q0[0]*q1[0] + q0[1]*q1[1] + q0[2]*q1[2] + q0[3]*q1[3]
        val b = if (dot < 0f) { dot = -dot; floatArrayOf(-q1[0], -q1[1], -q1[2], -q1[3]) } else q1
        if (dot > 0.9995f) {
            val r = FloatArray(4) { q0[it] + (b[it] - q0[it]) * t }
            val len = sqrt((r[0]*r[0]+r[1]*r[1]+r[2]*r[2]+r[3]*r[3]).toDouble()).toFloat()
            if (len > 0f) { r[0]/=len; r[1]/=len; r[2]/=len; r[3]/=len }
            return r
        }
        val theta0 = acos(dot.toDouble().coerceIn(-1.0, 1.0))
        val theta = theta0 * t
        val s0 = (cos(theta) - dot * sin(theta) / sin(theta0)).toFloat()
        val s1 = (sin(theta) / sin(theta0)).toFloat()
        return floatArrayOf(s0*q0[0]+s1*b[0], s0*q0[1]+s1*b[1], s0*q0[2]+s1*b[2], s0*q0[3]+s1*b[3])
    }

    // ── Matrix ↔ Quaternion ──────────────────────────────────────────────

    private fun quaternionToMatrix(q: FloatArray, mat: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val x2 = x+x; val y2 = y+y; val z2 = z+z
        val xx = x*x2; val xy = x*y2; val xz = x*z2
        val yy = y*y2; val yz = y*z2; val zz = z*z2
        val wx = w*x2; val wy = w*y2; val wz = w*z2
        mat[0]=1f-(yy+zz); mat[1]=xy+wz;      mat[2]=xz-wy;      mat[3]=0f
        mat[4]=xy-wz;      mat[5]=1f-(xx+zz); mat[6]=yz+wx;      mat[7]=0f
        mat[8]=xz+wy;      mat[9]=yz-wx;      mat[10]=1f-(xx+yy); mat[11]=0f
        mat[15]=1f
    }

    /**
     * Extract quaternion [x,y,z,w] from column-major 4x4 rotation matrix.
     * Uses Shepperd's method for numerical stability.
     */
    private fun matrixToQuaternion(m: FloatArray): FloatArray {
        val m00 = m[0]; val m01 = m[4]; val m02 = m[8]
        val m10 = m[1]; val m11 = m[5]; val m12 = m[9]
        val m20 = m[2]; val m21 = m[6]; val m22 = m[10]
        val trace = m00 + m11 + m22

        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt((trace + 1f).toDouble()).toFloat() * 2f
            q[3] = s * 0.25f; q[0] = (m21-m12)/s; q[1] = (m02-m20)/s; q[2] = (m10-m01)/s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt((1f + m00 - m11 - m22).toDouble()).toFloat() * 2f
            q[3] = (m21-m12)/s; q[0] = s * 0.25f; q[1] = (m01+m10)/s; q[2] = (m02+m20)/s
        } else if (m11 > m22) {
            val s = sqrt((1f + m11 - m00 - m22).toDouble()).toFloat() * 2f
            q[3] = (m02-m20)/s; q[0] = (m01+m10)/s; q[1] = s * 0.25f; q[2] = (m12+m21)/s
        } else {
            val s = sqrt((1f + m22 - m00 - m11).toDouble()).toFloat() * 2f
            q[3] = (m10-m01)/s; q[0] = (m02+m20)/s; q[1] = (m12+m21)/s; q[2] = s * 0.25f
        }
        // Normalize
        val len = sqrt((q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]).toDouble()).toFloat()
        if (len > 0f) { q[0]/=len; q[1]/=len; q[2]/=len; q[3]/=len }
        return q
    }

    // ── VRM Extension Parsing ────────────────────────────────────────────

    private fun detectVrmVersion(glbBytes: ByteArray): String {
        val json = parseGlbJson(glbBytes) ?: return "1"
        val ext = json.getAsJsonObject("extensions") ?: return "1"
        if (ext.has("VRMC_vrm")) return "1"
        if (ext.has("VRM")) return "0"
        return "1"
    }

    private fun parseVrmBoneNodeNames(glbBytes: ByteArray): Map<String, String> {
        val json = parseGlbJson(glbBytes) ?: return emptyMap()
        val nodes = json.getAsJsonArray("nodes") ?: return emptyMap()
        fun nodeName(i: Int): String? = if (i < nodes.size()) nodes[i].asJsonObject.get("name")?.asString else null

        val result = mutableMapOf<String, String>()
        val ext = json.getAsJsonObject("extensions") ?: return emptyMap()

        // VRM 1.0
        ext.getAsJsonObject("VRMC_vrm")?.getAsJsonObject("humanoid")
            ?.getAsJsonObject("humanBones")?.entrySet()?.forEach { (bone, el) ->
                el?.asJsonObject?.get("node")?.asInt?.let { idx ->
                    nodeName(idx)?.let { result[bone] = it }
                }
            }
        if (result.isNotEmpty()) return result

        // VRM 0.x
        ext.getAsJsonObject("VRM")?.getAsJsonObject("humanoid")
            ?.getAsJsonArray("humanBones")?.forEach { el ->
                val obj = el.asJsonObject
                val bone = obj.get("bone")?.asString ?: return@forEach
                val idx = obj.get("node")?.asInt ?: return@forEach
                nodeName(idx)?.let { result[bone] = it }
            }
        return result
    }

    private fun parseGlbJson(glbBytes: ByteArray): JsonObject? {
        val buf = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) return null
        buf.int; buf.int; buf.int // magic, version, length
        if (buf.remaining() < 8) return null
        val chunkLen = buf.int; val chunkType = buf.int
        if (chunkType != CHUNK_TYPE_JSON) return null
        val jsonBytes = ByteArray(chunkLen); buf.get(jsonBytes)
        return Gson().fromJson(String(jsonBytes, Charsets.UTF_8), JsonObject::class.java)
    }
}
