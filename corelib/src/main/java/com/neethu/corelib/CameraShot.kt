package com.neethu.corelib

/**
 * Preset camera shots for framing the avatar, applied with a smooth
 * transition (see [AvatarController.setCameraShot]).
 *
 * Framing is derived from the model's humanoid bones at call time — the
 * Close-Up look-at tracks the head, the other shots track the chest/hips —
 * so every model (and any animation pose) is framed correctly.
 *
 * @property label Human-readable label, usable directly in UI.
 */
enum class CameraShot(val label: String) {

    /**
     * 面部特写 — chest-up to head (bust shot). The look-at point is bound
     * to the model's head bone.
     */
    CLOSE_UP("面部特写 CU"),

    /**
     * 中景半身 — waist-up to head, the standard streamer framing.
     * Look-at is bound between head and hips.
     */
    MEDIUM_SHOT("中景半身 MS"),

    /**
     * 全景全身 — head to feet with some ground below.
     * Look-at is bound around the hips (body center).
     */
    FULL_SHOT("全景全身 FS"),

    /**
     * 远景 — the full-body shot pulled further back, leaving margin above
     * the head and below the feet so large dance movements stay in frame.
     * Look-at is bound to the hips (body center).
     */
    LONG_SHOT("远景 LS"),

    /**
     * 反应侧景 — half-body view from ~38° to the side-front, as if seen
     * over a conversation partner's shoulder.
     */
    OVER_SHOULDER("反应侧景 OS");
}
