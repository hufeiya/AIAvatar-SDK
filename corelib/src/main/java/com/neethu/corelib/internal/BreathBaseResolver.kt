package com.neethu.corelib.internal

import kotlin.math.abs

/**
 * 程序化叠加层的基座判定纯函数（JVM 可测）——「先剥再叠」范式的双信号修正版，
 * 呼吸层（VrmBreathEngine）与视线层（VrmLookAtEngine）共用。
 *
 * 旧范式（VrmLookAtEngine.stripPreviousWrite 单信号）用「当前局部 ≈ 上次写入值」
 * 推断「动画没重写，需要剥掉我的旧偏移」。该推断对视线（偏移要么 0 要远大于
 * 阈值）成立，但对呼吸这类**从 0 缓慢爬升的连续小角度层**会产生吸收态陷阱：
 * 动画每帧把骨骼重置回基座 B 后，strip 把 B 误判为「我的写入」（|offset|<0.57°
 * 时永远成立），base 被错误地算成 inv(LO_prev)·B，渲染出来的永远是
 * LO_new·inv(LO_prev)·B ≈ B + 单帧增量——**永久锁死**（真机症状=
 * 99% 静止+每周期一两次微抽动；Python 仿真 tools/breath_sim 复现并验证修复）。
 *
 * 修复 = HipsDragOffsetSolver 同款双信号（A.1 第 36 条）：渲染循环在动画分支
 * **之前**快照骨骼局部（capturePreAnimation），本函数用「快照 vs 当前」精确
 * 判定动画是否重写过：
 *  - 重写过（diff > [REWRITE_EPS_RAD]）→ base = 当前值（动画刚写的干净基座，
 *    绝不 strip——旧偏移已不存在，减它就是减了个错误姿态）；
 *  - 没重写（rest pose 定格，骨骼一直是我上次写的）→ 才走 strip 剥旧偏移。
 *
 * 视线层接入的收益同理：头/颈被呼吸推着让 |offset| 在 STRIP_EPS 两侧周期性
 * 穿越，单信号下渲染在「全量↔被吃掉」间跳变（头部顿挫）；双信号后头/颈恒
 * 全量写、眼骨（不被动画重写）保留 strip。
 */
internal object BreathBaseResolver {

    /**
     * 重写判定阈值（rad）。float32 下同值两次读取 diff 恒为 0；重写后的 diff
     * 至少是上一帧的写入偏移（呼气谷底也 ≥ 写入 eps 量级），1e-4（0.0057°）
     * 两界皆安全。
     */
    const val REWRITE_EPS_RAD = 1e-4f

    /**
     * @param current      本帧动画写完后、叠加前读到的骨骼局部四元数
     * @param preAnimation 动画分支前的快照（null=本帧没拍到，退回单信号 strip）
     * @return (base, stripped)：base=叠加新偏移的基座；stripped=是否真的剥了东西
     *   （false 时调用方无需写盘）
     */
    fun resolve(
        current: FloatArray,
        preAnimation: FloatArray?,
        lastWritten: FloatArray?,
        lastOffset: FloatArray?,
    ): Pair<FloatArray, Boolean> {
        if (preAnimation != null &&
            abs(GazeMath.quatAngle(current, preAnimation)) > REWRITE_EPS_RAD
        ) {
            return current to false
        }
        return VrmLookAtEngine.stripPreviousWrite(current, lastWritten, lastOffset)
    }
}
