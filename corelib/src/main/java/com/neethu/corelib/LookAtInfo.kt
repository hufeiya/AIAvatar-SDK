package com.neethu.corelib

/**
 * Snapshot of the gaze (look-at) system — the diagnostics surface behind
 * [AvatarController.getLookAtInfo]. All values reflect the last rendered
 * frame's state.
 */
class LookAtInfo(
    /** World-space gaze target currently being tracked, or `null` when off. */
    val target: FloatArray?,
    /** Smoothed head+neck yaw offset currently applied, in degrees (+ = model turning toward its left as seen from the camera… sign follows the world +Y convention). */
    val yawDegrees: Float,
    /** Smoothed head+neck pitch offset currently applied, in degrees (+ = looking up). */
    val pitchDegrees: Float,
    /** `true` when the model has a head bone to drive. */
    val headBoneBound: Boolean,
    /** `true` when left/right eye bones were found (eyes carry the un-smoothed gaze remainder). */
    val eyeBonesBound: Boolean,
) {
    override fun toString(): String =
        "target=" + (target?.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) } ?: "off") +
            " yaw=%+.1f° pitch=%+.1f°".format(yawDegrees, pitchDegrees) +
            " head=$headBoneBound eyes=$eyeBonesBound"
}
