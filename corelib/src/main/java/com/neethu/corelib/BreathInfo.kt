package com.neethu.corelib

/**
 * Snapshot of the breathing system — the diagnostics surface behind
 * [AvatarController.getBreathInfo]. All values reflect the last rendered
 * frame's state.
 */
class BreathInfo(
    /** Whether the breath overlay is currently enabled. */
    val enabled: Boolean,
    /** Whether the avatar is currently marked as speaking (shallower/faster breath). */
    val speaking: Boolean,
    /** Breath phase in [0,1): 0 = exhale bottom, [com.neethu.corelib.internal.BreathWave.INHALE_FRACTION] = inhale peak. */
    val phase: Float,
    /** Current breathing rate in breaths per minute (rest or speaking rate). */
    val breathsPerMinute: Float,
    /** Current normalized breath amplitude in [0,1] (speaking modulation included). */
    val amplitude: Float,
    /** Number of spine/shoulder bones actually bound on the loaded model. */
    val bonesBound: Int,
) {
    override fun toString(): String =
        "enabled=$enabled speaking=$speaking bpm=%.1f amp=%.2f phase=%.2f bones=$bonesBound"
            .format(breathsPerMinute, amplitude, phase)
}
