package com.neethu.aiavatar_sdk.multiavatar

/**
 * 内置配饰清单（docs/accessory-feasibility.md P0：phone 起步；P1 换
 * `accessories/accessory.json` sidecar 目录扫描 + per-model 校准 preset）。
 *
 * 只收 CC0（许可证集中记录见 tools/accessory-samples/README.md）；
 * 偏移为骨骼局部系出厂默认（米/度），真机 `acc attach` 微调参数可覆盖。
 */
internal object AccessoryCatalog {

    data class Entry(
        val id: String,
        val assetPath: String,
        val license: String,
        val defaultBone: String,
        val position: FloatArray,
        val rotationEulerYXZ: FloatArray,
        val scale: Float,
    )

    val all = listOf(
        Entry(
            id = "phone",
            assetPath = "accessories/phone.glb",
            license = "CC0(自制,make-phone-glb.js)",
            defaultBone = "rightHand",
            position = floatArrayOf(0f, 0.02f, 0.03f),
            rotationEulerYXZ = floatArrayOf(0f, 0f, 0f),
            scale = 1f,
        ),
    )

    fun find(id: String): Entry? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }
}
