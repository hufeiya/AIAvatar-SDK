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
}
