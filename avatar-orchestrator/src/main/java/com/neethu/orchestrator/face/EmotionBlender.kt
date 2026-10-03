package com.neethu.orchestrator.face

import com.neethu.aiadapter.api.EmotionCue
import com.neethu.aiadapter.lipsync.VowelDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Emotion state machine — port of AIRI's `useVRMEmote`
 * (packages/stage-ui-three/src/composables/vrm/expression.ts):
 *
 *  - each emotion expands to a fixed morph-target combo + blend duration
 *  - transitions ease with `easeInOutCubic` starting from the *currently
 *    displayed* values (no zero-then-pop)
 *  - morphs not carried over into the new emotion ease back to 0
 *  - after [autoResetDelayMs] the face returns to `neutral` (AIRI's
 *    `setEmotionWithResetAfter(…, 3000)`)
 *
 * Main-confined: [apply] hops to the main dispatcher, [tick] is called from
 * the Choreographer loop.
 */
class EmotionBlender(
    scope: CoroutineScope,
    private val autoResetDelayMs: Long = 3_000,
) {

    data class Def(val targets: List<Pair<String, Float>>, val blendDuration: Float)

    /** AIRI's emotionStates table (weights ≤0.8 fix the "stiff smile" issue #590). */
    val defs: Map<String, Def> = mapOf(
        "happy" to Def(listOf("happy" to 0.7f, "aa" to 0.2f), 0.4f),
        "sad" to Def(listOf("sad" to 0.7f, "oh" to 0.15f), 0.4f),
        "angry" to Def(listOf("angry" to 0.7f, "ee" to 0.3f), 0.3f),
        "surprised" to Def(listOf("surprised" to 0.8f, "oh" to 0.4f), 0.15f),
        "neutral" to Def(listOf("neutral" to 1.0f), 0.6f),
        "think" to Def(listOf("think" to 0.7f), 0.5f),
        "relaxed" to Def(listOf("relaxed" to 0.7f), 0.4f),
    )

    private val mainScope = CoroutineScope(scope.coroutineContext + Job()) // dispatch context preserved
    private val main = Dispatchers.Main.immediate

    private var owned = setOf<String>()
    private var startValues = mapOf<String, Float>()
    private var targets = mapOf<String, Float>()
    private var progress = 1f
    private var duration = 0.4f
    private var resetJob: Job? = null

    /** True while the current emotion owns non-viseme (eye area) morphs → suppresses blink. */
    val eyeAreaActive: Boolean
        get() = targets.any { (name, value) ->
            name !in VISEME_SET && value > 0.01f
        }

    /** Blend duration of the active emotion (used for the viseme blend-back pass). */
    val currentBlendDuration: Float get() = duration

    /** Current viseme-name targets of the active emotion (for the blend-back pass). */
    fun visemeTargets(): Map<String, Float> =
        targets.filterKeys { it in VISEME_SET }

    /** Apply an emotion cue (cancels any pending auto-reset). */
    fun apply(cue: EmotionCue) {
        mainScope.launch(main) { applyInternal(cue) }
    }

    private fun applyInternal(cue: EmotionCue) {
        val def = defs[cue.name] ?: return
        resetJob?.cancel()
        resetJob = null

        startValues = owned.associateWith { currentValue(it) }
        val intensity = cue.intensity.coerceIn(0f, 1f)
        val newTargets = def.targets.associate { (name, value) -> name to value * intensity }
        owned = owned + newTargets.keys
        targets = owned.associateWith { newTargets[it] ?: 0f }
        duration = def.blendDuration
        progress = 0f

        if (cue.name != "neutral") {
            resetJob = mainScope.launch(main) {
                delay(autoResetDelayMs)
                applyInternal(EmotionCue("neutral", 1f))
            }
        }
    }

    /** Advance the transition; returns current display values for all owned morphs. */
    fun tick(deltaSeconds: Float): Map<String, Float> {
        progress = min(progress + deltaSeconds / duration, 1f)
        val e = easeInOutCubic(progress)
        val values = HashMap<String, Float>(owned.size)
        for (name in owned) {
            val from = startValues[name] ?: 0f
            val to = targets[name] ?: 0f
            values[name] = from + (to - from) * e
        }
        return values
    }

    private fun currentValue(name: String): Float {
        // Mid-transition snapshots must keep easing from where we actually are.
        val e = easeInOutCubic(progress)
        val from = startValues[name] ?: 0f
        val to = targets[name] ?: 0f
        return from + (to - from) * e
    }

    companion object {
        val VISEME_SET: Set<String> = VowelDriver.VOWEL_NAMES.toSet()

        fun easeInOutCubic(t: Float): Float =
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

        private fun Float.pow(n: Int): Float = Math.pow(this.toDouble(), n.toDouble()).toFloat()
    }
}
