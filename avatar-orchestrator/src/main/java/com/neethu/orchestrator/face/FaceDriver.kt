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
 *     lip-sync owns the mouth (`aa/ih/ou/ee/oh`) while speaking; after speech
 *     ends the emotion re-asserts its mouth targets with a blend-back pass.
 *     Once everything the driver owns is at rest (no playback, no blend-back,
 *     emotion decayed to neutral) it goes quiet after one final zero write —
 *     manual expressions set from the app UI then own the face again, because
 *     the controller treats `setExpression(name, 0)` as a removal, so
 *     re-asserting zeros every frame would erase them.
 *
 * The controller is switched to instant mode (`transitionDuration = 0`) — all
 * easing lives here, mirroring three-vrm's per-frame `setValue` semantics.
 */
class FaceDriver(
    private val controller: AvatarController,
    parentScope: CoroutineScope,
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

    /** Viseme blend-back state: after speech, emotion mouth targets fade in from 0. */
    private var visemeReturnTargets: Map<String, Float>? = null
    private var visemeReturnProgress = 0f
    private var visemeReturnDuration = 0.4f

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

    /** Start driving. Call once the model is loaded (expression list available). */
    fun start() {
        if (running) return
        controller.setExpressionTransitionDuration(0L)
        supportedExpressions = controller.getAvailableExpressions().toSet()
        vowelDriver.setPhonemeGroups(VowelDriver.defaultLayoutFor(DEFAULT_PHONEMES))
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // ── Playback wiring (called from the session, any thread) ─────────────

    fun onPlaybackStarted(item: PlaybackItem, playback: ActivePlayback) {
        activePlayback = playback
    }

    fun onPlaybackEnded() {
        activePlayback = null
    }

    fun onPlaybackInterrupted() {
        activePlayback = null
    }

    /** Notify the driver when the processor's phoneme layout is known. */
    fun setPhonemeLayout(groups: Array<IntArray>) {
        vowelDriver.setPhonemeGroups(groups)
    }

    /** Switch what the avatar looks at (see [GazeMode]). */
    fun setGazeMode(mode: GazeMode) {
        gazeMode = mode
    }

    /**
     * World-space gaze point for [GazeMode.POINT] — the injection seam for a
     * future camera-based face tracker (feed the user's face position here).
     */
    fun setGazePoint(x: Float, y: Float, z: Float) {
        gazePoint[0] = x; gazePoint[1] = y; gazePoint[2] = z
    }

    fun applyEmotion(cue: EmotionCue) {
        blender.apply(cue)
    }

    fun clearEmotion() {
        blender.apply(EmotionCue("neutral", 1f))
    }

    // ── Per-frame mix ─────────────────────────────────────────────────────

    fun tick(deltaSeconds: Float) {
        driverTime += deltaSeconds

        // 1. visemes from the active clip
        val wasLipSyncActive = lipSyncActive
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

        // viseme blend-back: emotion mouth targets ease in from zero once speech stops
        if (wasLipSyncActive && !lipSyncActive && visemeReturnTargets == null) {
            visemeReturnTargets = blender.visemeTargets()
            visemeReturnProgress = 0f
            visemeReturnDuration = blender.currentBlendDuration
        } else if (lipSyncActive) {
            visemeReturnTargets = null
        }

        // 3. blink (suppressed by eye-area emotions)
        var blink = microMotion.tickBlink(deltaSeconds)
        if (blender.eyeAreaActive) blink = 0f

        // 3.5 gaze + saccade (AIRI eye-motions): after blink, before the
        // emotion merge — write the fixation point into corelib; head/neck/eye
        // bone solving and its smoothing all live there.
        updateGaze(deltaSeconds)

        // 4. merge + send
        val visemeReturn = visemeReturnTargets
        if (visemeReturn != null) {
            visemeReturnProgress += deltaSeconds / visemeReturnDuration.coerceAtLeast(0.01f)
        }
        val returnEase = EmotionBlender.easeInOutCubic(visemeReturnProgress.coerceIn(0f, 1f))
        for (v in 0 until VowelDriver.VOWEL_COUNT) {
            val name = VowelDriver.VOWEL_NAMES[v]
            val value = when {
                lipSyncActive -> viseme[v]
                visemeReturn != null -> (visemeReturn[name] ?: 0f) * returnEase
                else -> emotionValues[name] ?: 0f
            }
            send(name, value)
        }
        for ((name, value) in emotionValues) {
            if (name in EmotionBlender.VISEME_SET) continue // owned by the viseme channel above
            send(name, value)
        }
        send(BLINK, blink)

        if (visemeReturn != null && visemeReturnProgress >= 1f) visemeReturnTargets = null
    }

    private var debugAccum = 0f

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
        // Dedup guard including zeros: at rest (no playback, no blend-back, emotion
        // decayed to 0) the driver must go QUIET after its one final zero write.
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
