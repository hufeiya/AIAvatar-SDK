package com.neethu.corelib

/**
 * Public types for scene interaction poses (contact alignment) — the avatar
 * walks to an interaction anchor's approach point, then this layer aligns the
 * body to a furniture contact point (sit on a chair/bed edge, lie on a bed).
 *
 * The asset contract (per-scene `interactions.json`) supplies contact points
 * in SDK world meters; the alignment samples the actual seated/lying pose and
 * corrects the root translation so the hips land on the contact surface
 * (rootTranslation += targetContactWorld − measuredContactWorld).
 */

/** How the body meets the furniture at the contact point. */
enum class ContactKind {
    /** Seated: hips land on [ContactPoseRequest.y] + [ContactPoseRequest.hipsLiftMeters]. */
    SIT,

    /**
     * Supine: the standing pose is rotated flat (face up, head along the
     * feet→head axis) — there is no lying VRMA in the built-in library, so
     * the body keeps the idle loop while horizontal. [ContactPoseRequest.yawDeg]
     * describes the feet→head axis (head points along `forward(yawDeg)`).
     */
    LIE,
}

/**
 * A contact alignment request in SDK world coordinates (meters).
 *
 * @param x/y/z Contact point (seat surface / mattress back surface), NOT the
 *   avatar root — the root is corrected from the measured hips bone position.
 * @param yawDeg Body facing at the contact (SIT) or feet→head body axis (LIE);
 *   0 = +Z, positive turns +Z toward +X (scene asset convention).
 * @param sitInClipAssetPath Optional one-shot "stand → sit" transition VRMA
 *   (played before alignment); omit to crossfade straight into the loop clip.
 * @param sitLoopClipAssetPath Looping seated idle VRMA held while sitting
 *   (SIT only); omit to keep the current looping animation.
 * @param standUpClipAssetPath Optional one-shot "sit → stand" VRMA played
 *   while the root returns to the captured stand matrix on [AvatarController.clearContactPose].
 * @param hipsLiftMeters Vertical offset from the contact surface to the hips
 *   bone center (buttock/back thickness); small positive value.
 */
data class ContactPoseRequest(
    val kind: ContactKind,
    val x: Float,
    val y: Float,
    val z: Float,
    val yawDeg: Float,
    val sitInClipAssetPath: String? = null,
    val sitLoopClipAssetPath: String? = null,
    val standUpClipAssetPath: String? = null,
    val hipsLiftMeters: Float = 0.09f,
)

/** Result of [AvatarController.applyContactPose]. */
enum class ContactPoseResult {
    /** Pose engaged (transition playing; alignment follows automatically). */
    STARTED,

    /** No model loaded. */
    NO_MODEL,

    /** A contact pose is already engaged (or being exited) — clear it first. */
    BUSY,

    /** The locomotion layer is walking — wait for arrival before posing. */
    WALKING,

    /** Contact point is NaN/out of range or the target is degenerate. */
    INVALID_TARGET,

    /** The transition/loop clip failed to load/parse. */
    LOAD_FAILED,
}

/** Contact pose state machine phase. */
enum class ContactPosePhase {
    /** No contact pose (standing free). */
    NONE,

    /** Playing the sit transition / rotating into the supine orientation. */
    ENTERING,

    /** Sliding the root so the hips land on the contact surface. */
    ALIGNING,

    /** Aligned and holding (seated/lying idle). */
    HOLDING,

    /** Returning to the captured stand matrix (stand-up / roll upright). */
    EXITING,
}

/** One-shot snapshot of the contact pose state (for UI / ai_cmd dumps). */
data class ContactPoseSnapshot(
    val phase: ContactPosePhase,
    val kind: ContactKind?,
    /** Whether the hips have landed on the contact surface (HOLDING). */
    val aligned: Boolean,
)
