package com.neethu.corelib.internal

/**
 * 世界 → 屏幕归一化坐标的纯数学（思考气泡跟随，docs/autonomy-feasibility.md
 * §6.2）。输入 Filament camera 的 view/proj 矩阵（列主序 4×4，与
 * `Camera.getViewMatrix` / `Camera.getProjectionMatrix` 的布局一致），
 * 输出 [u, v]：
 * - `u ∈ [0,1]` 左 → 右，`v ∈ [0,1]` **上 → 下**（Compose 坐标约定，
 *   NDC 的 y 向上已在内部翻转）；
 * - 点在相机背后（clip w ≤ 0）或落在视锥外 → `null`（调用方隐藏气泡）。
 *
 * 独立成纯 JVM 对象是为了单测对拍：手搭 lookAt + perspective 矩阵验证
 * 中心/偏轴/背后/出锥四象限（docs/autonomy-feasibility.md §7 corelib 行）。
 */
internal object ScreenProjector {

    fun project(px: Float, py: Float, pz: Float, view: FloatArray, proj: FloatArray): FloatArray? {
        // eye = V · p（列主序：out[row] = Σ_col m[col·4+row]·v[col]）
        val ex = view[0] * px + view[4] * py + view[8] * pz + view[12]
        val ey = view[1] * px + view[5] * py + view[9] * pz + view[13]
        val ez = view[2] * px + view[6] * py + view[10] * pz + view[14]
        val ew = view[3] * px + view[7] * py + view[11] * pz + view[15]
        // clip = P · eye
        val cx = proj[0] * ex + proj[4] * ey + proj[8] * ez + proj[12] * ew
        val cy = proj[1] * ex + proj[5] * ey + proj[9] * ez + proj[13] * ew
        val cw = proj[3] * ex + proj[7] * ey + proj[11] * ez + proj[15] * ew
        if (cw <= W_EPS) return null // 背对相机（Filament 相机注视 -Z，前方 w>0）
        val ndcX = cx / cw
        val ndcY = cy / cw
        if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) return null // 视锥外
        return floatArrayOf((ndcX + 1f) * 0.5f, (1f - ndcY) * 0.5f)
    }

    private const val W_EPS = 1e-6f
}
