package com.neethu.orchestrator.face

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
 *  4. merge with AIRI's ownership rules and push into [AvatarController]:
 *     lip-sync owns the mouth (`aa/ih/ou/ee/oh`) while speaking; after speech
 *     ends the emotion re-asserts its mouth targets with a blend-back pass.
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

    @Volatile private var activePlayback: ActivePlayback? = null
    private var driverTime = 0f
    private var lipSyncActive = false

    /** Viseme blend-back state: after speech, emotion mouth targets fade in from 0. */
    private var visemeReturnTargets: Map<String, Float>? = null
    private var visemeReturnProgress = 0f
    private var visemeReturnDuration = 0.4f

    private var availableExpressions: Set<String> = emptySet()
    private val sent = HashMap<String, Float>()
    private var running = false
    private var lastFrameNanos = 0L

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
        availableExpressions = controller.getAvailableExpressions().toSet()
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

    private fun send(name: String, value: Float) {
        if (availableExpressions.isNotEmpty() && name !in availableExpressions) return
        val previous = sent[name]
        if (previous != null && abs(previous - value) < SEND_EPSILON && value != 0f) return
        if (previous == null && value == 0f) return
        controller.setExpression(name, value)
        sent[name] = value
    }

    companion object {
        private const val BLINK = "blink"
        private const val SEND_EPSILON = 0.004f
        private val EMPTY_SCORES = FloatArray(0)

        /** Default wLipSync phoneme order used when no explicit layout is bound. */
        private val DEFAULT_PHONEMES = listOf("A", "I", "U", "E", "O", "S")
    }
}
