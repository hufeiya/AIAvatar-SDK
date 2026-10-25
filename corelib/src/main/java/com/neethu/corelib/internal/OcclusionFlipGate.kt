package com.neethu.corelib.internal

/**
 * 防遮挡翻转闸门（P2 真机验收修复，docs/prop-system-feasibility.md §7-P2 修复记录）：
 * 墙体 hide/show 翻转（RenderableManager layerMask/castShadows 位写）在「角色移动 +
 * 相机移动」窗口内会形成连续翻转风暴，而翻转是 P2 真机验收 3/3 崩溃（FEngine::loop
 * 线程 Filament PostconditionPanic→abort，签名指向 handle 生命周期检查）的唯一新增
 * 渲染突变——此前的所有验收（无翻转）零崩溃。本闸门在身体或相机运动期间冻结翻转，
 * 运动停止后再稳定 [settleSeconds] 才放行：翻转只发生在视线几何完全静止的稳态，
 * 观感上等价（稳态机位才是剖切功能的服务对象），运动中的穿墙一闪不再发生。
 *
 * 纯状态机，JVM 可测；dt 由调用方（渲染帧回调）喂入。
 */
internal class OcclusionFlipGate(private val settleSeconds: Float = DEFAULT_SETTLE_SECONDS) {

    companion object {
        /** 运动停止后到放行翻转的稳定窗（与 unhide 消抖同量级）。 */
        const val DEFAULT_SETTLE_SECONDS = 0.25f
    }

    /** 已连续静止的秒数；初始 = 已稳定（冷启动静态机位立即可用）。 */
    private var settledForSeconds: Float = Float.MAX_VALUE

    /** 当前是否放行翻转。 */
    val flipsAllowed: Boolean get() = settledForSeconds >= settleSeconds

    /**
     * 每帧推进。[bodyMoving]=角色在动（走动/接触定姿过渡）；[cameraGliding]=相机
     * 过渡在飞（camera_shot glide steering）。任一为真即回到未稳定态。
     */
    fun update(bodyMoving: Boolean, cameraGliding: Boolean, dtSeconds: Float) {
        settledForSeconds = if (bodyMoving || cameraGliding) {
            0f
        } else {
            (settledForSeconds + dtSeconds.coerceAtLeast(0f)).coerceAtMost(settleSeconds)
        }
    }

    /** 显式回到未稳定态（遮挡开关/场景切换等状态突变后调用）。 */
    fun reset() {
        settledForSeconds = 0f
    }
}
