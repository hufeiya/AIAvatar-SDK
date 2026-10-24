package com.neethu.corelib

/**
 * Public types for avatar locomotion (free walking), P0 of
 * docs/locomotion-feasibility.md.
 *
 * The movement model: the walk VRMA keeps playing in place while a
 * programmatic root layer translates/rotates the avatar each frame
 * (synthetic root motion). Targets are world-space XZ points; the room
 * geometry is described by the loaded scene's [ScenePlacement.bounds].
 */

/** Rectangular walkable area in world XZ (meters) — targets are clamped into it. */
data class WalkBounds(
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float,
)

/**
 * Placement of the loaded scene (environment GLB) relative to the avatar's
 * world: a rigid Y rotation + translation applied on top of the scene's own
 * root transform, plus the walkable bounds calibrated for the room.
 *
 * Values come from `assets/scene/<scene>.meta.json` (one-time calibration via
 * the `scene_offset` / `scene_rot` ai_cmd probes) or from SDK code.
 */
data class ScenePlacement(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,
    val yawDeg: Float = 0f,
    val bounds: WalkBounds? = null,
)

/** Locomotion state machine phase. */
enum class WalkPhase {
    /** No walk in progress (also the arrival/stop state). */
    IDLE,

    /** Rotating in place toward the target before advancing. */
    TURNING,

    /** Advancing while heading corrections keep tracking the target. */
    WALKING,
}

/** One-shot snapshot of the locomotion state (for UI / ai_cmd dumps). */
data class WalkSnapshot(
    val phase: WalkPhase,
    val x: Float,
    val z: Float,
    /** World yaw of the facing direction in degrees (0 = +Z, toward the camera). */
    val headingDeg: Float,
    val targetX: Float,
    val targetZ: Float,
    /** Requested ground speed in m/s (the gait timeScale derives from it). */
    val speed: Float,
)

/** Result of [AvatarController.startWalk]. */
enum class WalkStartResult {
    /** Walk started (gait clip playing, locomotion state machine engaged). */
    STARTED,

    /** No view/renderer attached. */
    NO_RENDERER,

    /** No model loaded. */
    NO_MODEL,

    /**
     * No scene loaded — walking without a room means "walking on air" (坑 4:
     * 不加载 home.glb 就走 = 虚空行走). Load a scene first; its placement
     * also carries the walkable bounds.
     */
    NO_SCENE,

    /** [AvatarController.setWalkGait] was never called (or failed). */
    NO_GAIT,

    /** The mimic ("imitate me") engine owns the bones — stop it first. */
    MIMIC_ACTIVE,

    /**
     * A scene contact pose (sit/lie) is engaged — clear it first
     * ([AvatarController.clearContactPose]) so the root animation lanes
     * don't fight over the root matrix.
     */
    POSE_ACTIVE,

    /** The gait clip failed to load/parse. */
    LOAD_FAILED,
}

/**
 * A walk ended — emitted on [AvatarController.walkFinished] once per walk.
 * [arrived]=true means the state machine reached the target; false means the
 * walk was cancelled (user/LLM stop, interrupt). Coordinates are the final
 * root position (meters, world XZ).
 *
 * [avatarId]（多虚拟人 P3）：舞台路径（[com.neethu.corelib.stage.AvatarRuntime]）
 * 填演员 id（"A"/"B"），单人路径为空串——合流消费方（站位编排/调试通道）凭它
 * 归属事件，不靠「哪条流」猜。
 */
data class WalkFinished(
    val arrived: Boolean,
    val x: Float,
    val z: Float,
    val avatarId: String = "",
)
