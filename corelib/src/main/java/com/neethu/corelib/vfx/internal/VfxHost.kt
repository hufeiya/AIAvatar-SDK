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

    /**
     * 骨骼锚点解析（P1 §4.8）：把 [boneName]（语义名，大小写不敏感）的当前
     * 世界位置写进 [out]（out 3 float）。true = 解析成功；false = 无模型/
     * 演员不在场/语义骨缺失/实体已失效——调用方据此安静自停特效（不持
     * 陈旧 entity，模型重载前科）。[avatarId] 仅团播有意义（null = 单人
     * 当前角色）。
     */
    fun vfxResolveBone(avatarId: String?, boneName: String, out: FloatArray): Boolean

    /** assets 字节读取（P1 资产贴图；null = 不存在/读取失败，回退兜底纹理）。 */
    fun vfxReadAssetBytes(path: String): ByteArray?

    /**
     * MToon 舞台灯推送（画质升级 2026-10-17）：MToon 数学不吃场景灯，每帧把
     * 「对角色贡献最大」的一盏局部灯以材质参数近似推给 MToon 表面材质。
     * [pos]/[dir]（世界系，dir 已归一）、[colorRGB]（已按距离/强度折算的
     * 线性 RGB）、[cosInner]/[cosOuter]（锥角余弦）、[rangeM]（衰减半径）。
     * rangeM ≤ 0 = 关闭（推零）。默认空实现（宿主无 MToon 演员时不设）。
     */
    fun vfxPushMToonStageLight(
        pos: FloatArray,
        dir: FloatArray,
        colorRGB: FloatArray,
        cosInner: Float,
        cosOuter: Float,
        rangeM: Float,
    ) {
    }
}
