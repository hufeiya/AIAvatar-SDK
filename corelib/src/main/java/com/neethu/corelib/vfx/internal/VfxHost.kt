package com.neethu.corelib.vfx.internal

import com.google.android.filament.Engine
import com.google.android.filament.Scene

/**
 * 特效渲染宿主缝（docs/vfx-feasibility.md §4.2，仿 AccessoryHost）：抽象
 * 渲染路径差异供 [VfxManager] 共用——P0 单人 = SoulLinkRenderer 实现，
 * P1 团播 = Stage 第二实现。全部方法主线程调用（与 VfxManager 同线程约定）。
 */
internal interface VfxHost {

    val vfxEngine: Engine

    val vfxScene: Scene

    /**
     * 当前相机位姿三元组（本帧 render 将用的位姿；out 参数零分配）。
     * Billboard 基向量从此导出（VfxMath.basisFromLookAt）。
     */
    fun vfxCameraLookAt(outEye: FloatArray, outTarget: FloatArray, outUp: FloatArray)
}
