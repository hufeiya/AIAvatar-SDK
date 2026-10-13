package com.neethu.corelib.stage

/**
 * 同屏多虚拟人的角色加载规格（docs/multi-avatar-feasibility.md P0）。
 *
 * `modelAssetPath` / `modelFilePath` 二选一：assets 相对路径（如
 * `"vrms/10.vrm"`）或本地文件绝对路径（运行时导入的模型），加载管线与
 * 单虚拟人完全一致（morph 补丁 / 骨骼裁剪 / MToon 换肤）。
 *
 * @property modelAssetPath 模型在 `assets/` 里的相对路径。
 * @property modelFilePath 模型在文件系统的绝对路径。
 * @property spawn 站位（世界坐标 + 朝向）。默认原点 = 与单虚拟人行为一致
 *   （所有人叠在世界原点；多人的站位由调用方错开）。
 */
data class AvatarSpec(
    val modelAssetPath: String? = null,
    val modelFilePath: String? = null,
    val spawn: AvatarSpawn = AvatarSpawn(),
) {
    init {
        require((modelAssetPath != null) xor (modelFilePath != null)) {
            "AvatarSpec needs exactly one of modelAssetPath / modelFilePath"
        }
    }

    /** 诊断显示名（路径末段）。 */
    val displayName: String
        get() = (modelAssetPath ?: modelFilePath ?: "?").substringAfterLast('/')
}

/**
 * 站位：世界坐标平移（米）+ 朝向（deg）。
 *
 * [yawDeg] 语义与 [com.neethu.corelib.WalkSnapshot.headingDeg] 一致：
 * 0 = 面向 +Z（镜头方向）。叠加顺序：unit-cube 归一 →（VRM 0.x 翻转）→
 * yaw → 平移；之后走动引擎在同一条 root 矩阵上继续写，互不冲突。
 */
data class AvatarSpawn(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val yawDeg: Float = 0f,
) {
    companion object {
        /** 常用站位：并排双人（左/右各偏 0.8 m，微微朝向中线；z=0 = 不动深度）。 */
        val LEFT = AvatarSpawn(x = -0.8f, yawDeg = 15f)
        val RIGHT = AvatarSpawn(x = 0.8f, yawDeg = -15f)
    }
}
