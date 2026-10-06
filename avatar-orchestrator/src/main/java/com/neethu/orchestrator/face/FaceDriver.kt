package com.neethu.orchestrator.face

import android.util.Log
import android.view.Choreographer
import com.neethu.aiadapter.api.EmotionCue
import com.neethu.aiadapter.api.VisemeTimeline
import com.neethu.aiadapter.lipsync.VowelDriver
import com.neethu.corelib.AvatarController
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

/**
 * Per-frame face mixer — the SDK equivalent of AIRI's VRM render loop ordering
 * (VRMModel.vue `bindManagedVrmInstanceRenderLoop`):
 *
 *  1. lip-sync visemes (from the playing clip's precomputed [VisemeTimeline],
 *     smoothed by [VowelDriver] with AIRI's exact constants)
 *  2. emotion morphs ([EmotionBlender], easeInOutCubic, 3 s auto-neutral)
 *  3. blink ([MicroMotionEngine], suppressed when the emotion owns the eye area)
 *  4. gaze ([SaccadeEngine], AIRI eye-motions port): jitter the fixation point
 *     around the tracking target (camera eye = the user's face) and write it
 *     into corelib — the head/neck/eye bone overlay and its smoothing live
 *     there
 *  5. merge with AIRI's ownership rules and push into [AvatarController]:
 *     the mouth channel blends lip-sync and emotion *per morph by max* —
 *     visemes keep articulation (their targets are the stronger ones) while
 *     the emotion's mouth garnish (happy→aa, surprised→oh, sad→mouth frown
 *     garnish…) stays visible underneath instead of being erased while
 *     speaking; the emotion re-claims the mouth on its own as the viseme
 *     smoothing decays after speech ends. Once everything the driver owns is
 *     at rest (no playback, emotion decayed to neutral) it goes quiet after
 *     one final zero write — manual expressions set from the app UI then own
 *     the face again, because the controller treats `setExpression(name, 0)`
 *     as a removal, so re-asserting zeros every frame would erase them.
 *
 * The controller is switched to instant mode (`transitionDuration = 0`) — all
 * easing lives here, mirroring three-vrm's per-frame `setValue` semantics.
 * Manual expressions from the app UI route through [applyManualExpression]
 * (the blender's easing, no auto-reset) so they blend in instead of snapping
 * in one frame.
 */
class FaceDriver(
    private val controller: AvatarController,
    parentScope: CoroutineScope,
    /**
     * 表情名单来源（可注入，JVM 单测用）：默认读控制器（模型重载后集合随
     * [refreshExpressions] 更新，见 [start]）。生产路径不传。
     */
    private val expressionsProvider: () -> List<String> = { controller.getAvailableExpressions() },
) {

    private val vowelDriver = VowelDriver()
    private val microMotion = MicroMotionEngine()
    private val blender = EmotionBlender(parentScope)
    private val saccade = SaccadeEngine()

    @Volatile private var activePlayback: ActivePlayback? = null
    private var driverTime = 0f
    private var lipSyncActive = false

    // ── Gaze (look-at) state ─────────────────────────────────────────────
    // 默认 CAMERA：看着镜头 = 看着用户。未来用户视频系统拿到真实人脸坐标后
    // 切 POINT 喂 setGazePoint，链路其余不动（AIRI trackingMode 同构）。
    @Volatile private var gazeMode = GazeMode.CAMERA
    @Volatile private var gazePoint = FloatArray(3)
    private var lastGazeWrite: FloatArray? = null
    private var lastGazeBase: FloatArray? = null

    private var supportedExpressions: Set<String> = emptySet()
    private val sent = HashMap<String, Float>()
    private var running = false
    private var lastFrameNanos = 0L

    /** Expression names the loaded model supports (captured at [start]). */
    val availableExpressions: Set<String> get() = supportedExpressions

    /** Canonical emotion names with combo defs (take precedence over direct expressions). */
    val knownEmotionNames: Set<String> get() = blender.defs.keys

    /**
     * Resolve a (possibly mis-cased) expression name to the model's actual
     * morph name, or null when unsupported. The tag extractor lowercases cue
     * names, but morph names are case-sensitive (`blinkLeft` ≠ `blinkleft`) —
     * direct-expression cues must be re-cased before hitting the controller
     * (真机踩过：全被这里的大小写卡掉).
     */
    fun resolveExpression(rawName: String): String? =
        resolveExpressionName(supportedExpressions, rawName)

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 0.016f
            else ((frameTimeNanos - lastFrameNanos).coerceAtLeast(0)) / 1_000_000_000f
            lastFrameNanos = frameTimeNanos
            try {
                tick(dt.coerceAtMost(0.1f))
            } finally {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /**
     * Start driving. Call once the model is loaded (expression list available).
     * Calling again after a model reload is safe and expected: the expression
     * set is re-captured ([refreshExpressions]) so the NEW model's morph names
     * pass the [send] gate（导入/切换模型后旧集合会把新模型的表情静默丢弃）.
     */
    fun start() {
        if (running) {
            refreshExpressions()
            return
        }
        controller.setExpressionTransitionDuration(0L)
        refreshExpressions()
        vowelDriver.setPhonemeGroups(VowelDriver.defaultLayoutFor(DEFAULT_PHONEMES))
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Re-capture the supported-expression set from the controller and reset
     * the per-morph dedup bookkeeping. Must run after every model reload:
     * the controller cleared its weights alongside the old model, so stale
     * `sent` entries would suppress re-writes of the same values on the new
     * model, and morph names unique to the new model would fail the [send]
     * gate entirely.
     */
    fun refreshExpressions() {
        supportedExpressions = expressionsProvider().toSet()
        sent.clear()
    }

    fun stop() {
        // 未 start 过 = 没贴过帧回调：直接跳过 Choreographer（JVM 单测没有
        // Choreographer 实例，session.close() → stop() 不该在这里炸）
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // ── Playback wiring (called from the session, any thread) ─────────────

    fun onPlaybackStarted(item: PlaybackItem, playback: ActivePlayback) {
        activePlayback = playback
        // 呼吸说话调制（corelib 呼吸叠加层）：说话时呼吸变浅变快。开播/收播
        // 一次性置位即可（幅度系数在 corelib 侧一阶趋近，无逐帧调用、无突跳）。
        controller.setBreathSpeaking(true)
    }

    fun onPlaybackEnded() {
        activePlayback = null
        controller.setBreathSpeaking(false)
    }

    fun onPlaybackInterrupted() {
        activePlayback = null
        controller.setBreathSpeaking(false)
    }

    /** Notify the driver when the processor's phoneme layout is known. */
    fun setPhonemeLayout(groups: Array<IntArray>) {
        vowelDriver.setPhonemeGroups(groups)
    }

    /** Switch what the avatar looks at (see [GazeMode]). */
    fun setGazeMode(mode: GazeMode) {
        gazeMode = mode
    }

    /** 视线微动（saccade）开关：关闭=注视点锁定基准，无抖动无换点。 */
    fun setSaccadeEnabled(enabled: Boolean) {
        saccade.isEnabled = enabled
    }

    /** 视线抖动幅度（世界单位），实时生效。 */
    fun setSaccadeJitter(amplitude: Float) {
        saccade.jitterAmplitude = amplitude.coerceIn(0f, 1f)
    }

    /** 眨眼开关：关闭立即结束当前眨眼。 */
    fun setBlinkEnabled(enabled: Boolean) {
        microMotion.setBlinkEnabled(enabled)
    }

    /** 眨眼平均间隔（秒），实时生效。 */
    fun setBlinkIntervalMean(meanS: Float) {
        microMotion.setBlinkIntervalMean(meanS.coerceIn(0.5f, 12f))
    }

    /**
     * World-space gaze point for [GazeMode.POINT] — the injection seam for a
     * future camera-based face tracker (feed the user's face position here).
     */
    fun setGazePoint(x: Float, y: Float, z: Float) {
        gazePoint[0] = x; gazePoint[1] = y; gazePoint[2] = z
    }

    /**
     * Apply an emotion cue. [holdMs] = time at full expression before the
     * auto-neutral blend; the session passes the playing clip's duration so a
     * long sentence isn't cut to neutral mid-speech (default 3 s, AIRI's
     * `setEmotionWithResetAfter`).
     */
    fun applyEmotion(cue: EmotionCue, holdMs: Long = DEFAULT_EMOTION_HOLD_MS) {
        blender.apply(cue, holdMs)
    }

    /**
     * Manual expression from the app UI (expression panel, `ai_cmd
     * set_expression`): eases in like any emotion (the controller itself is
     * in instant mode) but never auto-resets — it holds until replaced or
     * [clearManualExpression] is called. Names resolve to the model's actual
     * morph casing; unknown names are still applied (per-morph gate drops
     * them at [send]).
     */
    fun applyManualExpression(name: String, weight: Float) {
        val resolved = resolveExpression(name) ?: name
        blender.apply(EmotionCue(resolved, weight.coerceIn(0f, 1f)), EmotionBlender.HOLD_NO_RESET)
    }

    // ── Face mimicry（「模仿我」P2 表情通道）──────────────────────────────

    /** 最近一帧映射好的 mimic 表情权重；null = 表情车道断供（情绪/眨眼接管）。 */
    @Volatile private var mimicFace: Map<String, Float>? = null
    private val activeMimicMorphs = HashSet<String>()

    /**
     * Feed one frame of mimic face weights (morph names ALREADY mapped to the
     * model's names by the app via [MimicFaceMapper]; the driver re-gates them
     * through `send`). `null` releases the face — the previously driven morphs
     * ease to zero once and emotion/blink reclaim their channels.
     */
    fun setMimicFace(weights: Map<String, Float>?) {
        mimicFace = weights
    }

    /** Release the manual expression, easing back to neutral. */
    fun clearManualExpression() {
        blender.apply(EmotionCue("neutral", 1f))
    }

    fun clearEmotion() {
        blender.apply(EmotionCue("neutral", 1f))
    }

    // ── Per-frame mix ─────────────────────────────────────────────────────

    private var debugAccum = 0f

    fun tick(deltaSeconds: Float) {
        driverTime += deltaSeconds

        // 1. visemes from the active clip
        var viseme = FloatArray(VowelDriver.VOWEL_COUNT)
        val playback = activePlayback
        val timeline = playback?.item?.timeline
        if (playback != null && timeline != null) {
            val t = playback.positionSeconds()
            val frame = timeline.sampleAt(t)
            if (frame != null) {
                viseme = vowelDriver.update(frame.volume, frame.phonemeScores, t, deltaSeconds)
            }
            lipSyncActive = viseme.any { it > VowelDriver.DEAD_ZONE }
            debugTick(deltaSeconds, t, frame?.volume ?: -1f, viseme)
        } else {
            // decay the smoothing state so the mouth closes naturally
            viseme = vowelDriver.update(0f, EMPTY_SCORES, driverTime, deltaSeconds)
            lipSyncActive = false
        }

        // 2. emotion morph values
        val emotionValues = blender.tick(deltaSeconds)

        // 3. blink (suppressed by eye-area emotions; P2 表情模仿供真实眨眼时
        // 也让位——用户的真实眨眼经 mimicFace 的 eyeBlink morph 直通)
        var blink = microMotion.tickBlink(deltaSeconds)
        if (blender.eyeAreaActive) blink = 0f

        // 3.5 gaze + saccade (AIRI eye-motions): after blink, before the
        // emotion merge — write the fixation point into corelib; head/neck/eye
        // bone solving and its smoothing all live there.
        updateGaze(deltaSeconds)

        // 4. merge + send. Mouth channel: per-morph max(viseme, emotion) —
        // visemes stay dominant for articulation while the emotion's mouth
        // garnish (happy→aa, surprised→oh…) remains visible under speech, and
        // as the viseme smoothing decays at clip end the emotion re-claims the
        // mouth on its own (no separate blend-back pass needed — the emotion
        // value never got zeroed).
        val mimic = mimicFace
        val mimicOwnsFace = mimic != null
        val mimicOwnsBlink = mimic?.keys?.any { it.contains("blink", ignoreCase = true) } == true
        if (mimicOwnsBlink) blink = 0f

        val visemeByName = HashMap<String, Float>(VowelDriver.VOWEL_COUNT)
        for (v in 0 until VowelDriver.VOWEL_COUNT) {
            visemeByName[VowelDriver.VOWEL_NAMES[v]] = viseme[v]
        }
        for ((name, value) in blendMouth(visemeByName, emotionValues)) {
            send(name, value)
        }
        if (mimicOwnsFace) {
            // 模仿期：情绪通道整体让位（mimic 逐帧绝对权重，混情绪会打架）；
            // blender 状态照常推进，断供即自然接回（值从未清零）。说话期嘴部
            // 所有权让给口型 max 通道：VISEME_SET 的 morph 完全跳过（上面刚写
            // 过），jawOpen 等其它下颌 morph 归零（否则卡在用户张嘴值上）。
            for ((name, weight) in mimic) {
                when {
                    lipSyncActive && name in EmotionBlender.VISEME_SET -> Unit
                    lipSyncActive && name in MOUTH_YIELD_SET -> send(name, 0f)
                    else -> send(name, weight)
                }
            }
            // 上一帧还驱着、这一帧掉出映射的 morph（如 eyeBlink 归零）补一发 0
            for (m in activeMimicMorphs) if (m !in mimic) send(m, 0f)
        } else {
            // 断供：mimic 上一帧驱着的 morph 一次性归零（后续被 dedup 静默），
            // 情绪/眨眼通道即刻接回（blender 值从未清零=自然 blend-back）
            for (m in activeMimicMorphs) send(m, 0f)
            for ((name, value) in emotionValues) {
                if (name in EmotionBlender.VISEME_SET) continue // owned by the mouth channel above
                send(name, value)
            }
        }
        // 记账无论激活与否都同步（断供→清空=下次激活从头写入）
        activeMimicMorphs.clear()
        if (mimic != null) activeMimicMorphs.addAll(mimic.keys)
        send(BLINK, blink)
    }

    /**
     * Gaze channel: pick the base target from [gazeMode], let [SaccadeEngine]
     * jitter a fixation point around it (AIRI eye-motions), and write it into
     * corelib when it moves. BASE_RETRACK_EPS²: the base (camera eye / tracked
     * face) moving beyond ~1 cm counts as "the user moved" — the fixation
     * snaps onto it exactly (AIRI watch(focusPos) → instantUpdate semantics)
     * and saccades re-jitter from there; smaller drift is ignored so a
     * handheld camera doesn't cancel every saccade.
     */
    private fun updateGaze(dt: Float) {
        val base: FloatArray? = when (gazeMode) {
            GazeMode.CAMERA -> controller.getCameraLookAt()?.first
            GazeMode.POINT -> gazePoint
            GazeMode.NONE -> null
        }
        if (base == null) {
            if (lastGazeWrite != null) {
                controller.clearLookAtTarget()
                lastGazeWrite = null
                lastGazeBase = null
            }
            return
        }
        val lb = lastGazeBase
        if (lb == null || distSq(lb, base) > BASE_RETRACK_EPS_SQ) {
            saccade.snap(base[0], base[1], base[2])
            lastGazeWrite = null
        }
        lastGazeBase = base.copyOf()
        saccade.tick(dt, base[0], base[1], base[2])
        val f = saccade.fixation
        val lw = lastGazeWrite
        if (lw == null || distSq(lw, f) > WRITE_EPS_SQ) {
            controller.setLookAtTarget(f[0], f[1], f[2])
            lastGazeWrite = f.copyOf()
        }
    }

    private fun distSq(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * 2 Hz trace of the viseme sampling path while a clip plays (tag
     * `FaceDriver`): `t` should sweep the clip duration, `volume` track the
     * audio envelope and `top` follow the spoken vowels. Primary tool for
     * tuning VowelDriver constants on a device (task-1 appendix in
     * docs/ai-layer-handoff.md).
     */
    private fun debugTick(dt: Float, t: Float, volume: Float, viseme: FloatArray) {
        debugAccum += dt
        if (debugAccum < 0.5f) return
        debugAccum = 0f
        val top = viseme.withIndex().maxByOrNull { it.value }
        val topStr = if (top == null || top.value <= VowelDriver.DEAD_ZONE) "none"
        else "${VowelDriver.VOWEL_NAMES[top.index]}=${top.value}"
        Log.d(TAG, "t=$t volume=$volume top=$topStr")
    }

    private fun send(name: String, value: Float) {
        if (supportedExpressions.isNotEmpty() && name !in supportedExpressions) return
        val previous = sent[name]
        // Dedup guard including zeros: at rest (no playback, emotion decayed
        // to 0) the driver must go QUIET after its one final zero write.
        // Re-asserting zeros every frame is destructive — corelib treats
        // setExpression(name, 0) as targetWeights.remove(name), so a resting
        // zero-storm silently erases any manually applied mouth expression
        // (aa/ih/ou/ee/oh) the frame after the user sets it.
        if (previous != null && abs(previous - value) < SEND_EPSILON) return
        if (previous == null && value == 0f) return
        controller.setExpression(name, value)
        sent[name] = value
    }

    companion object {
        private const val TAG = "FaceDriver"
        private const val BLINK = "blink"
        private const val SEND_EPSILON = 0.004f
        private val EMPTY_SCORES = FloatArray(0)

        /** Default hold for session-applied emotions when no clip duration is known. */
        const val DEFAULT_EMOTION_HOLD_MS = 3_000L

        /**
         * 模仿期说话时表情通道要**归零让位**的下颌/嘴形 morph（VISEME_SET 之外
         * 的张嘴系——ARKit 命名模型没有 aa/ih 这类预设 morph，口型只动 vowel
         * 预设，jawOpen 不归零会卡在用户的张嘴值上和语音打架）。VISEME_SET 本身
         * 是跳过（口型 max 通道拥有），这里只收额外项。
         */
        private val MOUTH_YIELD_SET = setOf(
            "jawOpen", "jawLeft", "jawRight", "jawForward", "mouthClose",
            "mouthShrugLower", "mouthShrugUpper",
        )

        /**
         * Pure mouth-channel merge (unit-testable without a renderer): every
         * viseme morph gets `max(viseme, emotion)` so neither source can erase
         * the other; emotion entries on non-viseme morphs pass through the
         * emotion loop untouched.
         */
        fun blendMouth(viseme: Map<String, Float>, emotion: Map<String, Float>): Map<String, Float> {
            val out = LinkedHashMap<String, Float>(viseme.size)
            for ((name, v) in viseme) {
                out[name] = maxOf(v, emotion[name] ?: 0f)
            }
            return out
        }

        /** Base-target movement beyond this (world units²) re-fixates exactly. */
        private const val BASE_RETRACK_EPS_SQ = 0.01f * 0.01f

        /** Fixation write threshold (world units²) — keep corelib writes rare. */
        private const val WRITE_EPS_SQ = 0.0001f * 0.0001f

        /** Default wLipSync phoneme order used when no explicit layout is bound. */
        private val DEFAULT_PHONEMES = listOf("A", "I", "U", "E", "O", "S")

        /** Pure lookup for [resolveExpression] (unit-testable without a renderer). */
        fun resolveExpressionName(supported: Set<String>, rawName: String): String? {
            if (rawName in supported) return rawName
            val byLowercase = supported.firstOrNull { it.equals(rawName, ignoreCase = true) }
            return byLowercase
        }
    }
}
