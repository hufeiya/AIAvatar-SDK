package com.neethu.corelib.accessory

/**
 * 配饰来源（对齐 AvatarSpec 的 assets/文件双通道惯例）。
 */
sealed interface AccessorySource {
    /** assets/ 相对路径（内置资产，如 `accessories/phone.glb`）。 */
    data class Asset(val path: String) : AccessorySource

    /** 文件系统绝对路径（SAF 导入等外置资产）。 */
    data class File(val path: String) : AccessorySource
}

/**
 * 配饰挂载规格（docs/accessory-feasibility.md §5.5 P0 子集）。
 *
 * 单位约定：[position]/[rotationEulerYXZ] 为**目标骨骼局部系**的偏移，
 * 模型单位（VRM = 米、角度 = 度）。avatar 根上的 unit-cube 归一缩放经
 * 父链自动传播到配饰，因此这里不再做 renderScale 换算（§5.3 公式按
 * 「父链已带根缩放」修正——调研期公式的双重缩放在 P0 真机标定中证伪）；
 * [scale] 只承担逐资产校准系数（修正资产建模偏差）。
 *
 * FloatArray 字段按内容判等（data class 默认引用等对数组不适用）。
 */
data class AccessorySpec(
    /** 配饰 ID（同一演员内唯一；重复 attach 返回失败）。 */
    val id: String,
    val source: AccessorySource,
    /** 语义骨骼名：`rightHand`/`head`/`chest`…（VRM humanoid map 语义）。 */
    val bone: String,
    /** 骨骼局部系平移偏移（模型单位）。 */
    val position: FloatArray = FloatArray(3),
    /** 骨骼局部系旋转，YXZ 内在欧拉（度）。 */
    val rotationEulerYXZ: FloatArray = FloatArray(3),
    /** 逐资产校准系数（1 = 资产按真实米制建模）。 */
    val scale: Float = 1f,
    /** 阴影默认关（SPOT 阴影 + PCSS 在 Adreno 有真机悬崖）。 */
    val castShadows: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is AccessorySpec &&
            id == other.id && source == other.source && bone == other.bone &&
            position.contentEquals(other.position) &&
            rotationEulerYXZ.contentEquals(other.rotationEulerYXZ) &&
            scale == other.scale && castShadows == other.castShadows

    override fun hashCode(): Int = arrayOf(id, source, bone, scale, castShadows).contentHashCode()
}

/** 已挂载配饰的观测快照（调试命令 / LLM 观测用）。 */
data class AccessoryState(
    val id: String,
    val bone: String,
    val visible: Boolean,
)
