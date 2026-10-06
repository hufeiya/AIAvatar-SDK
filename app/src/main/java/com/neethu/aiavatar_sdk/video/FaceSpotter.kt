package com.neethu.aiavatar_sdk.video

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 列主序 4×4 变换矩阵 → 头部 (yawDeg, pitchDeg)（纯函数，JVM 单测锁死数学
 * 与符号约定）。取旋转部分 3×3 做 ZYX 分解：yaw=atan2(-m20,√(m00²+m10²))、
 * pitch=atan2(m21,m22)（roll 不折算进这两个角，头部 roll 本就有限）。
 *
 * ⚠ 列基向量必须先归一化再提角——矩阵含平移/缩放，直接提角有几度级偏差
 * （呼吸系统 matToQuat 的同款坑，A.1 第 40 条）。输出的正负号对应屏幕哪个
 * 方向由 MediaPipe 输出约定+前摄朝向决定，真机标定收在 LookHereTuning 的
 * 两个布尔里（docs/lookhere-skill-feasibility.md §5），本函数只保证数学。
 */
object HeadPoseMath {

    fun yawPitchDeg(columnMajor4x4: FloatArray): Pair<Float, Float> {
        require(columnMajor4x4.size >= 12) { "need a 4x4 matrix, got ${columnMajor4x4.size} floats" }
        fun at(r: Int, c: Int) = columnMajor4x4[c * 4 + r].toDouble()
        fun col(c: Int): DoubleArray {
            val x = at(0, c); val y = at(1, c); val z = at(2, c)
            val n = sqrt(x * x + y * y + z * z)
            return doubleArrayOf(x / n, y / n, z / n)
        }
        val c0 = col(0)
        val c1 = col(1)
        val c2 = col(2)
        val hyp = hypot(c0[0], c0[1])
        val yaw = if (hyp < 1e-9) 0.0 else atan2(-c0[2], hyp)
        val pitch = atan2(c1[2], c2[2])
        return Math.toDegrees(yaw).toFloat() to Math.toDegrees(pitch).toFloat()
    }
}

/**
 * MediaPipe FaceLandmarker 薄封装（「看这边」头部姿态判定件 + P2「模仿我」
 * 表情/精确头部判定件，docs/lookhere-skill-feasibility.md §3 与
 * docs/mimic-skill-feasibility.md P2）。
 *
 * bundled 模型 `assets/face_landmarker.task`（float16 ~3.7MB）+ IMAGE 运行
 * 模式：调用方（UserCameraTracker 分析线程）给一帧直立位图，同步返回。
 * `facialTransformationMatrixes` 直出头部旋转矩阵——yaw/pitch 同一来源同一
 * 噪声特性，比 ML Kit 的 eulerY（无 pitch）或关键点几何估算稳。单帧 CPU
 * 10-30ms，80ms 帧级门下与 ML Kit 人脸检测错峰；只在技能激活+前摄时跑
 * （关=零开销不建引擎）。不依赖 GMS，国产无服务设备可跑。
 *
 * **两个实例**：看这边车道（`blendshapes=false` 默认）只要矩阵；模仿车道
 * （`blendshapes=true`）额外要 52 ARKit blendshapes（表情模仿）。不共用一个
 * 实例——开 blendshapes 有逐帧成本，别让看这边的判定窗背上它；两车道本来
 * 就随单活跃收口互斥，内存多一份模型是可接受代价。
 *
 * 创建/识别/释放全部约束在分析线程串行执行——MediaPipe 原生实例不允许
 * 跨线程并发使用（手势/Pose 引擎同款约束）。
 */
class FaceLandmarkerEngine(
    context: Context,
    private val blendshapes: Boolean = false,
) {

    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(blendshapes)
            .setOutputFacialTransformationMatrixes(true)
            .build(),
    )

    /** 完整结果（矩阵 + 可选 blendshapes）；无脸返回 null。 */
    fun detectResult(upright: Bitmap): FaceLandmarkerResult? {
        return landmarker.detect(BitmapImageBuilder(upright).build())
    }

    /** 识别一帧直立位图，返回 (yawDeg, pitchDeg)；无脸/无矩阵返回 null。 */
    fun detect(upright: Bitmap): Pair<Float, Float>? {
        val matrix = detectResult(upright)?.facialTransformationMatrixes()?.orElse(null)
            ?.firstOrNull()
            ?: return null
        return HeadPoseMath.yawPitchDeg(matrix)
    }

    fun close() = landmarker.close()

    companion object {
        const val MODEL_ASSET = "face_landmarker.task"
    }
}
