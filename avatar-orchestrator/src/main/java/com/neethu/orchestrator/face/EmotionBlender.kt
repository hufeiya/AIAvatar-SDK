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
 *  - after the hold delay (default [autoResetDelayMs], per-apply overridable)
 *    the face returns to `neutral` (AIRI's `setEmotionWithResetAfter(…, 3000)`)
 *
 * Main-confined: [apply] hops to the main dispatcher, [tick] is called from
 * the Choreographer loop.
 */
class EmotionBlender(
    scope: CoroutineScope,
    private val autoResetDelayMs: Long = 3_000,
) {

    data class Def(val targets: List<Pair<String, Float>>, val blendDuration: Float)

    /**
     * Micro-expression combos (§7.10). Each entry layer ARKit detail morphs on
     * top of the VRM preset base, so the canonical seven read as full faces
     * instead of single mouth/brow pops on ARKit-set models (真机默认模型
     * SK_Sun：`think` 预设不存在 → 旧单 morph def 整条被 FaceDriver 门控丢弃，
     * `<emo:think>` 完全无效；现在 think 用 brow/eye/mouth 微表情拼出来)。
     * Names the loaded model doesn't have are dropped per-morph by
     * [FaceDriver.send]'s gate — combos degrade gracefully on smaller sets.
     * Blink is deliberately never used (auto-blink owns that morph); droopy
     * eyes use eyeSquint instead.
     */
    val defs: Map<String, Def> = mapOf(
        "happy" to Def(
            listOf(
                "happy" to 0.7f,
                "eyeSquintLeft" to 0.35f, "eyeSquintRight" to 0.35f,
                "cheekSquintLeft" to 0.2f, "cheekSquintRight" to 0.2f,
                "aa" to 0.12f,
            ),
            0.4f,
        ),
        "sad" to Def(
            listOf(
                "sad" to 0.3f,             // 本模型=眉中下压；与 browInnerUp 叠成 AU1+AU4 悲伤眉
                "browInnerUp" to 0.55f,
                "mouthFrownLeft" to 0.35f, "mouthFrownRight" to 0.35f,
                "eyeSquintLeft" to 0.12f, "eyeSquintRight" to 0.12f,
            ),
            0.5f,
        ),
        "angry" to Def(
            listOf(
                "angry" to 0.55f,          // 本模型=嘴角下压
                "browDownLeft" to 0.6f, "browDownRight" to 0.6f,
                "noseSneerLeft" to 0.22f, "noseSneerRight" to 0.22f,
                "mouthPressLeft" to 0.3f, "mouthPressRight" to 0.3f,
            ),
            0.3f,
        ),
        "surprised" to Def(
            listOf(
                "surprised" to 0.8f,       // 本模型=三段眉抬+jawOpen
                "eyeWideLeft" to 0.55f, "eyeWideRight" to 0.55f,
                "oh" to 0.25f,             // O 形嘴（mouthPucker+jawOpen），口型通道 max 混合下仍可见
            ),
            0.15f,
        ),
        "neutral" to Def(listOf("neutral" to 1.0f), 0.6f),
        "think" to Def(
            listOf(
                "browDownLeft" to 0.4f, "browDownRight" to 0.15f,   // 单侧皱眉=审视
                "browInnerUp" to 0.2f,
                "eyeSquintLeft" to 0.3f, "eyeSquintRight" to 0.15f, // 跟随同侧眯眼
                "mouthPressLeft" to 0.3f, "mouthPressRight" to 0.3f,
            ),
            0.5f,
        ),
        "relaxed" to Def(
            listOf(
                "relaxed" to 0.6f,         // 本模型=browInnerUp
                "eyeSquintLeft" to 0.25f, "eyeSquintRight" to 0.25f,
                "happy" to 0.2f,           // 淡淡的笑
            ),
            0.4f,
        ),
        // ── 扩充标准情绪（协议块词表同步，SystemPromptAssemblerTest 锁一致） ──
        "smug" to Def(
            listOf(
                "mouthSmileLeft" to 0.55f, "mouthSmileRight" to 0.2f, // 单侧上翘=坏笑
                "eyeSquintLeft" to 0.35f, "eyeSquintRight" to 0.1f,
                "browDownLeft" to 0.2f,
            ),
            0.35f,
        ),
        "shy" to Def(
            listOf(
                "happy" to 0.35f,
                "browInnerUp" to 0.4f,
                "eyeSquintLeft" to 0.35f, "eyeSquintRight" to 0.35f, // 羞怯低眼
                "cheekSquintLeft" to 0.2f, "cheekSquintRight" to 0.2f,
            ),
            0.5f,
        ),
        "worried" to Def(
            listOf(
                "browInnerUp" to 0.6f,
                "eyeWideLeft" to 0.3f, "eyeWideRight" to 0.3f,
                "mouthFrownLeft" to 0.3f, "mouthFrownRight" to 0.3f,
                "sad" to 0.2f,
            ),
            0.4f,
        ),
        "confused" to Def(
            listOf(
                "browInnerUp" to 0.5f, "browDownRight" to 0.25f,     // 一边眉挑一边压
                "eyeSquintLeft" to 0.3f,
                "mouthPressLeft" to 0.2f,
                "mouthShrugLower" to 0.35f, "mouthShrugUpper" to 0.2f, // 嘴唇撇缩"huh?"
            ),
            0.4f,
        ),
        "sleepy" to Def(
            listOf(
                "relaxed" to 0.4f,
                "eyeSquintLeft" to 0.45f, "eyeSquintRight" to 0.45f, // 眼皮沉重
                "browDownLeft" to 0.2f, "browDownRight" to 0.2f,
                "aa" to 0.12f,             // 微张嘴（哈欠感）
            ),
            0.6f,
        ),
        "determined" to Def(
            listOf(
                "browDownLeft" to 0.5f, "browDownRight" to 0.5f,
                "mouthPressLeft" to 0.4f, "mouthPressRight" to 0.4f,
                "eyeSquintLeft" to 0.15f, "eyeSquintRight" to 0.15f,
            ),
            0.3f,
        ),
    )

    private val mainScope = CoroutineScope(scope.coroutineContext + Job()) // dispatch context preserved
    private val main = Dispatchers.Main.immediate

    private var owned = setOf<String>()
    private var startValues = mapOf<String, Float>()
    private var targets = mapOf<String, Float>()
    private var progress = 1f
    private var duration = 0.4f
    private var resetJob: Job? = null
    private var currentEmotion: String? = null

    /**
     * True while the current emotion owns non-viseme (eye area) morphs →
     * suppresses blink. AIRI parity (`isEmoteActive`): `neutral` is not an
     * active emotion, so after the 3 s auto-reset blinking must resume; during
     * the blend-back transition suppression persists only while the captured
     * pre-transition eye weights are still fading out. Forgetting the neutral
     * check latches blink off forever after the first emotion (seen on
     * device, task-1 verification).
     */
    val eyeAreaActive: Boolean
        get() {
            val emotion = currentEmotion
            if (emotion != null && emotion != "neutral") {
                return targets.any { (name, value) -> name !in VISEME_SET && value > 0.001f }
            }
            if (progress < 1f) { // still blending back to neutral
                return startValues.any { (name, value) -> name !in VISEME_SET && value > 0.001f }
            }
            return false
        }

    /** Apply an emotion cue (cancels any pending auto-reset). */
    fun apply(cue: EmotionCue) {
        apply(cue, autoResetDelayMs)
    }

    /**
     * Apply with an explicit hold: [holdMs] ms at full expression before the
     * auto-neutral blend (sentence-attached emotions pass the clip duration so
     * a long sentence isn't cut to neutral mid-speech), `HOLD_NO_RESET` to
     * stay until the next apply (manual expressions from the app UI).
     */
    fun apply(cue: EmotionCue, holdMs: Long) {
        mainScope.launch(main) { applyInternal(cue, holdMs) }
    }

    private fun applyInternal(cue: EmotionCue, holdMs: Long = autoResetDelayMs) {
        // Unknown names become direct single-morph expressions (§7.10): the
        // loaded model's own presets/ARKit morphs (blink_l, browInnerUp, aa…)
        // ride the same easing + 3 s auto-reset machinery as the canonical
        // emotions. Canonical defs take precedence; FaceDriver's expression
        // gating drops morphs the model doesn't actually have. Def stores 1.0
        // — applyInternal scales by the cue intensity exactly once.
        val intensity = cue.intensity.coerceIn(0f, 1f)
        val def = defs[cue.name] ?: Def(listOf(cue.name to 1f), 0.25f)
        resetJob?.cancel()
        resetJob = null
        currentEmotion = cue.name

        startValues = owned.associateWith { currentValue(it) }
        val newTargets = def.targets.associate { (name, value) -> name to value * intensity }
        owned = owned + newTargets.keys
        targets = owned.associateWith { newTargets[it] ?: 0f }
        duration = def.blendDuration
        progress = 0f

        if (cue.name != "neutral" && holdMs >= 0) {
            resetJob = mainScope.launch(main) {
                delay(holdMs)
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
        /** [apply] hold sentinel: never auto-reset (manual expressions hold until replaced). */
        const val HOLD_NO_RESET = -1L

        val VISEME_SET: Set<String> = VowelDriver.VOWEL_NAMES.toSet()

        fun easeInOutCubic(t: Float): Float =
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

        private fun Float.pow(n: Int): Float = Math.pow(this.toDouble(), n.toDouble()).toFloat()
    }
}
