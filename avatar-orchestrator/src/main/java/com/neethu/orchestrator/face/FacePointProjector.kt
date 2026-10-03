package com.neethu.orchestrator.face

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 1D One-Euro filter (Casiez et al. 2012) — the adaptive low-pass used for
 * jittery interactive signals. Slow movements get heavy smoothing (cutoff
 * [minCutoff] Hz); fast movements open the cutoff proportionally to speed
 * (slope [beta]), so intent appears instantly instead of lagging.
 *
 * 纯 JVM 可测；无状态持久化除 x̂/dx̂ 两个滑动量。
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.2f,
    private val beta: Float = 0.06f,
    private val dCutoff: Float = 1.0f,
) {
    private var hatXPrev = Float.NaN
    private var hatDxPrev = Float.NaN

    /** Feed one sample; [deltaSeconds] ≤ 0 resets the filter (first frame). */
    fun filter(x: Float, deltaSeconds: Float): Float {
        if (deltaSeconds <= 0f || hatXPrev.isNaN()) {
            hatXPrev = x
            hatDxPrev = 0f
            return x
        }
        val dt = deltaSeconds.coerceIn(1f / 240f, 0.5f)
        val dx = (x - hatXPrev) / dt
        val hatDx = lowpass(dx, hatDxPrev, alpha(dCutoff, dt))
        val cutoff = minCutoff + beta * abs(hatDx)
        val hatX = lowpass(x, hatXPrev, alpha(cutoff, dt))
        hatXPrev = hatX
        hatDxPrev = hatDx
        return hatX
    }

    fun reset() {
        hatXPrev = Float.NaN
        hatDxPrev = Float.NaN
    }

    private fun lowpass(x: Float, prev: Float, a: Float): Float = a * x + (1 - a) * prev

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

/**
 * 用户摄像头人脸位置 → 世界坐标注视点的投影器（视频模式，任务 5 预留的
 * POINT 注入缝：结果喂 `FaceDriver.setGazePoint` + `GazeMode.POINT`）。
 *
 * 输入约定（由采集端归一化，见 app 侧 UserCameraTracker）：
 *  - [nx] ∈ [-1,1]，**世界对齐**：+1 = 用户（脸）在屏幕右边缘——前置摄像头
 *    原始帧是镜像前的（脸在屏幕右侧时落在画面左侧），采集端必须翻转 x，
 *    本投影器不再翻转；
 *  - [ny] ∈ [-1,1]，+1 = 脸在画面下缘（图像 y 向下），竖直方向不镜像；
 *  - [area] = 人脸包围框面积 / 全画面面积 ∈ (0,1]，用于估相对深度。
 *
 * 输出：世界系注视点 = 相机眼位 + 朝向模型方向的前后偏移 + 相机系横向/
 * 纵向偏移。参考深度 [refDepth] 对应用户「正常持机距离」，面积越大（越近）
 * 注视点越向模型方向收；横向/纵向幅度随深度放大（归一化坐标是角度量）。
 * One-Euro 平滑在 nx/ny/depth 三路上内置，滤掉检测框抖动。
 *
 * 所有量纲是 demo 世界尺度（相机距模型 ~2-4 世界单位）下的调参常量，
 * 真机观感不对先调 [lateralRange]/[verticalRange]/[refDepth]/[areaRef]。
 */
class FacePointProjector(
    /** nx=±1 时的横向偏移（世界单位，参考深度处）。 */
    private val lateralRange: Float = 1.4f,
    /** ny=±1 时的纵向偏移。 */
    private val verticalRange: Float = 1.0f,
    /** 参考深度（世界单位）：area == [areaRef] 时认为用户在这个距离。 */
    private val refDepth: Float = 1.2f,
    /** 参考面积占比：典型持机距离/取景下人脸框占全画面的比例。 */
    private val areaRef: Float = 0.10f,
    /** 深度钳制（世界单位），防面积异常时注视点飞出。 */
    private val minDepth: Float = 0.5f,
    private val maxDepth: Float = 3.0f,
) {
    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()
    private val fd = OneEuroFilter(minCutoff = 0.6f, beta = 0.02f)

    /** 最近一次投影结果（face lost 时保持，供调用方持续写入同一目标）。 */
    var lastPoint: FloatArray = FloatArray(3)
        private set

    /**
     * 投影并平滑一个观测。[eye]/[target]/[up] 来自
     * `AvatarController.getCameraLookAt()`（eye=用户侧机位=注视用户的名义位置，
     * target=模型，up=相机上方向）。任一轴过滤异常输入时安全退化到 eye 本身。
     */
    fun project(
        nx: Float,
        ny: Float,
        area: Float,
        eye: FloatArray,
        target: FloatArray,
        up: FloatArray,
        deltaSeconds: Float,
    ): FloatArray {
        // 相机正交基：forward=eye→target, right=forward×up, camUp=right×forward
        val f = normalize3(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2])
            ?: floatArrayOf(0f, 0f, -1f)
        val r = normalize3(
            f[1] * up[2] - f[2] * up[1],
            f[2] * up[0] - f[0] * up[2],
            f[0] * up[1] - f[1] * up[0],
        ) ?: floatArrayOf(1f, 0f, 0f)
        val u = floatArrayOf(
            r[1] * f[2] - r[2] * f[1],
            r[2] * f[0] - r[0] * f[2],
            r[0] * f[1] - r[1] * f[0],
        )

        val snx = fx.filter(nx.coerceIn(-1f, 1f), deltaSeconds)
        val sny = fy.filter(ny.coerceIn(-1f, 1f), deltaSeconds)
        val safeArea = if (area > 1e-4f) area else 1e-4f
        val depth = fd.filter((refDepth * sqrt(areaRef / safeArea)), deltaSeconds)
            .coerceIn(minDepth, maxDepth)

        val along = refDepth - depth // 越近(深度小)越朝模型方向(+forward)前伸
        val lateral = snx * lateralRange * (depth / refDepth)
        val vertical = -sny * verticalRange * (depth / refDepth)

        lastPoint = floatArrayOf(
            eye[0] + f[0] * along + r[0] * lateral + u[0] * vertical,
            eye[1] + f[1] * along + r[1] * lateral + u[1] * vertical,
            eye[2] + f[2] * along + r[2] * lateral + u[2] * vertical,
        )
        return lastPoint
    }

    /** 切模式/丢脸恢复时清平滑状态，避免旧轨迹拖尾。 */
    fun reset() {
        fx.reset(); fy.reset(); fd.reset()
    }

    companion object {
        private fun normalize3(x: Float, y: Float, z: Float): FloatArray? {
            val len = sqrt(x * x + y * y + z * z)
            if (len < 1e-6f) return null
            return floatArrayOf(x / len, y / len, z / len)
        }

        /** 探测向量：绕 +Y 旋 [angleDeg] 的单位向量（单测构造斜交基用）。 */
        internal fun yawProbe(angleDeg: Float): FloatArray =
            floatArrayOf(sin(Math.toRadians(angleDeg.toDouble())).toFloat(), 0f, cos(Math.toRadians(angleDeg.toDouble())).toFloat())
    }
}
