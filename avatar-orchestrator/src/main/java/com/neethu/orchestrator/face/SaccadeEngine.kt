package com.neethu.orchestrator.face

import kotlin.random.Random

/**
 * AIRI `useIdleEyeSaccades` + `randomSaccadeInterval`（stage-ui-three
 * composables/vrm/animation.ts + utils/eye-motions.ts）的精确移植。
 *
 * 生理行为：人不盯一个点——注视点每 0.8–4.8 s 跳一次（分段均匀分布：
 * 0.4 s 一档，首档概率 0.075，长注视概率递减——上游注释"模拟 65 cm 外
 * 27 寸显示器上的随机内容"），跳点时在【基准目标】周围 ±0.25 世界单位
 * 抖动（仅 x/y，z 不动）。跳出的注视点写进 corelib 后由骨骼解算平滑
 * （眼球先到、头颈跟进），本引擎不做任何平滑。
 *
 * 基准目标由 trackingMode 决定（AIRI camera/mouse/none → 见
 * [GazeMode]）：camera = 相机眼位（用户脸的位置，未来视频系统换成真实
 * 人脸坐标即可，接口不变）。基准目标移动时调用 [snap]（AIRI
 * `watch(focusPos) → instantUpdate`）：注视点精确贴住新目标，不动计时器。
 *
 * 上游 update() 里 `else if (!fixationTarget)` 是死分支（Vector3 对象恒
 * 真值），未移植。
 */
class SaccadeEngine(
    private val random: Random = Random,
    /** 注视点抖动幅度（世界单位，x/y 各自独立 ±）。 */
    private val jitterAmplitude: Float = 0.25f,
) {

    /** 当前注视点（世界坐标，含抖动）。[tick]/[snap] 原地更新，读同一数组零分配。 */
    val fixation = FloatArray(3)

    // -1 = 立即（上游初始 nextSaccadeAfter = -1：0 >= -1 首帧即取点）
    private var nextSaccadeIn = -1f
    private var timeSinceSaccade = 0f

    /**
     * 推进时钟；到点换注视点（基准 + 抖动）。返回本帧是否刷新了注视点。
     * 基准目标在两次 saccade 之间移动【不】跟随（上游同语义，跟随交给
     * [snap]）。
     */
    fun tick(deltaSeconds: Float, baseX: Float, baseY: Float, baseZ: Float): Boolean {
        var refreshed = false
        if (timeSinceSaccade >= nextSaccadeIn) {
            fixation[0] = baseX + rand(-jitterAmplitude, jitterAmplitude)
            fixation[1] = baseY + rand(-jitterAmplitude, jitterAmplitude)
            fixation[2] = baseZ
            timeSinceSaccade = 0f
            nextSaccadeIn = nextIntervalSeconds()
            refreshed = true
        }
        timeSinceSaccade += deltaSeconds
        return refreshed
    }

    /** 基准目标变了：注视点精确贴住新目标（无抖动，等同 AIRI instantUpdate）。 */
    fun snap(baseX: Float, baseY: Float, baseZ: Float) {
        fixation[0] = baseX
        fixation[1] = baseY
        fixation[2] = baseZ
    }

    private fun rand(from: Float, to: Float): Float = from + random.nextFloat() * (to - from)

    private fun nextIntervalSeconds(): Float {
        val r = random.nextFloat()
        for (i in CUMULATIVE.indices) {
            if (r <= CUMULATIVE[i]) {
                return (BASE_MS[i] + random.nextFloat() * STEP_MS) / 1000f
            }
        }
        return (BASE_MS.last() + random.nextFloat() * STEP_MS) / 1000f
    }

    companion object {
        private const val STEP_MS = 400f

        // eye-motions.ts EYE_SACCADE_INT_P：逐档累加后的概率上沿与档位下沿(ms)。
        // r ≤ 0.075 → 800ms 档、≤ 0.185 → 1200ms 档 …… ≤ 1.0 → 4400ms 档。
        private val CUMULATIVE = floatArrayOf(
            0.075f, 0.185f, 0.31f, 0.45f, 0.575f, 0.625f, 0.665f, 0.695f, 0.715f, 1.0f,
        )
        private val BASE_MS = floatArrayOf(
            800f, 1200f, 1600f, 2000f, 2400f, 2800f, 3200f, 3600f, 4000f, 4400f,
        )
    }
}

/**
 * 视线追踪目标来源（对齐 AIRI 的 trackingMode：camera / mouse / none）。
 *
 * - [CAMERA]：看相机眼位 = 看镜头后的用户（demo 默认）。未来「用户视频
 *   系统」上线后，真实人脸在相机系里的坐标经换算喂给
 *   [FaceDriver.setGazePoint] 并切 [POINT] 即可，链路零改动。
 * - [POINT]：看指定世界坐标点（调试命令 / 外部追踪源）。
 * - [NONE]：关闭（头颈眼回到动画自身姿态）。
 */
enum class GazeMode { CAMERA, POINT, NONE }
