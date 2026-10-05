package com.neethu.aiavatar_sdk.video

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/**
 * MediaPipe 预置手势 → [com.neethu.orchestrator.skill.AvatarSkill.onUserGesture]
 * 的手势码（0=无,1=石头,2=剪刀,3=布，缝的文档约定）。
 *
 * GestureRecognizer 的 6 个 canned 类别里 Closed_Fist/Open_Palm/Victory 恰好
 * 一一对应石头/布/剪刀——零训练零标注（docs/rps-skill-feasibility.md §5）；
 * 其余类别（Thumb_Up/Thumb_Down/Pointing_Up/None）对猜拳无意义，一律折算成
 * 0（无），不进技能状态机。
 */
object PresetGestures {
    const val NONE = 0
    const val ROCK = 1
    const val SCISSORS = 2
    const val PAPER = 3

    fun codeFor(categoryName: String?): Int = when (categoryName) {
        "Closed_Fist" -> ROCK
        "Victory" -> SCISSORS
        "Open_Palm" -> PAPER
        else -> NONE
    }
}

/**
 * 手势确认的稳定性门控（纯逻辑，JVM 单测）：原始识别逐帧抖动/单帧误检很常见，
 * 直接把每帧结果喂给技能会连环误触发出拳。确认一个手势需要：
 *  1. **连续 [stableFrames] 帧同一手势**（80ms/帧 ≈ 240ms 保持）；
 *  2. 距上次触发超过 [refireGapMs]（连局的物理下限：虚拟人出手势+宣判总要看完）；
 *  3. 上次触发的手势已「放手」——连续 [RESET_FRAMES] 帧不同手势（含收手=0）
 *     才重新武装，防「握着拳头不放」被误检抖动拆成两拳。
 *
 * 触发后同样的手势想再次触发必须先经过一次放手/换手；[reset] 供相机重绑时
 * 清态（否则上次会话遗留的未武装状态会吞掉本局第一个手势）。
 */
class GestureStabilityGate(
    private val stableFrames: Int = DEFAULT_STABLE_FRAMES,
    private val refireGapMs: Long = DEFAULT_REFIRE_GAP_MS,
) {
    private var streakGesture = PresetGestures.NONE
    private var streakCount = 0
    private var lastFiredGesture = PresetGestures.NONE
    private var lastFiredMs = Long.MIN_VALUE
    private var differentStreak = 0
    private var rearmed = true

    /**
     * 喂一帧识别结果，返回本帧确认出的手势码（0=没有新确认）。只在达到确认
     * 条件的那一帧返回非 0，后续同手势帧不再重复返回。
     */
    fun onDetection(gesture: Int, nowMs: Long): Int {
        if (gesture == streakGesture) streakCount++ else {
            streakGesture = gesture
            streakCount = 1
        }
        if (gesture != lastFiredGesture) differentStreak++ else differentStreak = 0
        if (!rearmed && differentStreak >= RESET_FRAMES) rearmed = true

        if (gesture == PresetGestures.NONE || streakCount < stableFrames) return PresetGestures.NONE
        // 未映射码（上游映射表之外的类别）永远不触发，防上游扩展漏改这里
        if (gesture !in PresetGestures.ROCK..PresetGestures.PAPER) return PresetGestures.NONE
        // 「从未触发」以 lastFiredGesture 判定——lastFiredMs 若用 Long.MIN_VALUE
        // 哨兵，nowMs-lastFiredMs 会溢出成负数把首触发改成永远不过
        if (lastFiredGesture != PresetGestures.NONE && nowMs - lastFiredMs < refireGapMs) {
            return PresetGestures.NONE
        }
        if (!rearmed) return PresetGestures.NONE

        lastFiredGesture = gesture
        lastFiredMs = nowMs
        rearmed = false
        differentStreak = 0
        streakCount = 0
        return gesture
    }

    /** 相机重绑/技能退场时清态。 */
    fun reset() {
        streakGesture = PresetGestures.NONE
        streakCount = 0
        lastFiredGesture = PresetGestures.NONE
        lastFiredMs = Long.MIN_VALUE
        differentStreak = 0
        rearmed = true
    }

    companion object {
        /** 连续命中帧数（80ms 分析节奏下 ≈240ms 手势保持）。 */
        const val DEFAULT_STABLE_FRAMES = 3

        /** 两次触发的最小间隔（一拳的观感下限，也兜误检抖动）。 */
        const val DEFAULT_REFIRE_GAP_MS = 1_500L

        /** 重新武装所需的连续不同手势帧数（≈160ms 放手/换手）。 */
        const val RESET_FRAMES = 2
    }
}

/**
 * MediaPipe GestureRecognizer 的薄封装（猜拳 P2，docs/rps-skill-feasibility.md §5）。
 *
 * bundled 模型 `assets/gesture_recognizer.task`（float16 ~8MB）+ IMAGE 运行模式：
 * 调用方（UserCameraTracker 分析线程）给一帧直立位图，同步返回手势码。单帧
 * CPU 10-20ms，在 80ms 帧级门下与 ML Kit 人脸检测（异步 Task）错峰，无压力。
 * 不依赖 GMS，国产无服务设备可跑（与 ML Kit bundled 同选型理由）。
 *
 * 创建/识别/释放全部约束在分析线程串行执行（UserCameraTracker 负责）——
 * MediaPipe 原生实例不允许跨线程并发使用。
 */
class HandGestureRecognizer(context: Context) {

    private val recognizer: GestureRecognizer = GestureRecognizer.createFromOptions(
        context,
        GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(1)
            .build(),
    )

    /** 识别一帧直立位图，返回手势码（识别失败/无手返回 [PresetGestures.NONE]）。 */
    fun recognize(upright: Bitmap): Int {
        val result = recognizer.recognize(BitmapImageBuilder(upright).build())
        val top = result.gestures().firstOrNull()?.firstOrNull() ?: return PresetGestures.NONE
        return PresetGestures.codeFor(top.categoryName())
    }

    fun close() = recognizer.close()

    companion object {
        const val MODEL_ASSET = "gesture_recognizer.task"
    }
}
