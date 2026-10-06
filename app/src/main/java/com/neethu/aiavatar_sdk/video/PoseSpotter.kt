package com.neethu.aiavatar_sdk.video

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * MediaPipe PoseLandmarker 薄封装（「模仿我」判定件，
 * docs/mimic-skill-feasibility.md §3）。
 *
 * bundled 模型 `assets/pose_landmarker.task`（lite float16 ~5.5MB）+ IMAGE 运行
 * 模式：调用方（UserCameraTracker 分析线程）给一帧直立位图，同步返回
 * [PoseLandmarkerResult]——消费 `worldLandmarks()`（米制世界坐标 33 点，原点
 * 髋部中点，自带 visibility）。P1 只用上半身关键点 0-16（鼻/眼/耳/肩/肘/腕）；
 * 单帧 CPU 15-30ms，80ms 帧级门下与另两条 MediaPipe 车道互斥（同一分析线程
 * 不并跑），只在技能激活+前摄时跑（关=零开销不建引擎）。
 *
 * 方向解算/镜像映射不在这里——[PoseMimicMath] 纯函数层负责（JVM 单测主场），
 * 本类只做「位图进、原始关键点出」。创建/识别/释放全部约束在分析线程串行
 * ——MediaPipe 原生实例不允许跨线程并发使用（FaceSpotter/GestureSpotter 同款约束）。
 */
class PoseLandmarkerEngine(context: Context) {

    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .build(),
    )

    /** 识别一帧直立位图；无姿态/推理失败返回 null（车道静默降级）。 */
    fun detect(upright: Bitmap): PoseLandmarkerResult? {
        return landmarker.detect(BitmapImageBuilder(upright).build())
    }

    fun close() = landmarker.close()

    companion object {
        const val MODEL_ASSET = "pose_landmarker.task"
    }
}
