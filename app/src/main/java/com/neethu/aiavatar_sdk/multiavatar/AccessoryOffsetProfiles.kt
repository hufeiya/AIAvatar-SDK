package com.neethu.aiavatar_sdk.multiavatar

/**
 * 校准偏移模板层（P1 道具系统 §6.4，docs/prop-system-feasibility.md）：
 * sidecar 条目可引用「挂点×道具形状类别」模板（`offsetProfile`），模板出厂
 * 偏移 + **每模型只校准一次**的模板级 preset 替代逐件手填 defaultOffset——
 * 道具扩到 45+ 件后校准次数从 模型×道具（2×45=90 次）降到 模板×模型
 * （9×2=18 次，P1 场景层加面戴件模板后 9 个），新道具引用模板即近似正确，
 * 个别再 `accessory cal` 微调。
 *
 * 合并层级（低 → 高，越靠外优先级越高）：
 * 1. sidecar 条目出厂默认（无模板时的既有行为，11 件旧配饰保持不动）；
 * 2. 模板出厂默认（条目引用模板时整体接管 bone/pos/rot/scale）；
 * 3. 模板级 per-model 校准（`calibration/<modelId>/offsetProfiles.json`，
 *    `accessory cal profile <id>` 落盘，只覆盖标定维度）；
 * 4. 条目级 per-model 校准（既有 `calibration/<modelId>/<accessoryId>.json`，
 *    个别道具微调）；
 * 5. 命令显式参数（`acc attach` 的偏移参数，不变）。
 *
 * 模板出厂值取 P0/P1 真机验证过的出厂偏移族（phone [0,0.02,0.03]@rightHand
 * 在 10.vrm 零校准可用；books [0,0.02,0.04]@leftHand 同族）。VRM0.x（4705）
 * 右手骨局部系差 ~180° Y 的差异由每模型一次的模板级校准吸收——这正是把 90
 * 次校准降为 16 次的机制。
 */
internal object AccessoryOffsetProfiles {

    /** 一个模板的出厂默认挂载值。 */
    data class Profile(
        val bone: String,
        val position: FloatArray,
        val rotationEulerYXZ: FloatArray,
        val scale: Float,
    )

    /** 杯类短圆柱：马克杯 / 奶茶杯 / 水杯 / 酒杯。 */
    val holdCup = Profile("rightHand", floatArrayOf(0f, 0.01f, 0.03f), floatArrayOf(0f, 0f, 0f), 1f)

    /** 瓶罐类长圆柱：水瓶 / 易拉罐。 */
    val holdBottle = Profile("rightHand", floatArrayOf(0f, 0.02f, 0.03f), floatArrayOf(0f, 0f, 0f), 1f)

    /** 书本 / 平板类（leftHand 捧持，books 先例）。 */
    val holdBook = Profile("leftHand", floatArrayOf(0f, 0.02f, 0.04f), floatArrayOf(0f, 0f, 0f), 1f)

    /** 细长棒类（原点=握点）：钢笔 / 仙女棒 / 法杖 / 气球线 / 伞柄。 */
    val holdWand = Profile("rightHand", floatArrayOf(0f, 0f, 0.04f), floatArrayOf(0f, 0f, 0f), 1f)

    /** 泛手持轻物（递出示手）：花束 / 礼物盒 / 手柄 / 水壶。 */
    val holdObject = Profile("rightHand", floatArrayOf(0f, 0.03f, 0.05f), floatArrayOf(0f, 0f, 0f), 1f)

    /** 头戴件：耳机 / 眼罩。 */
    val wearHead = Profile("head", floatArrayOf(0f, 0.02f, 0f), floatArrayOf(0f, 0f, 0f), 1f)

    /**
     * 面戴件（P1 §5-#13 戴眼罩睡觉）：贴脸部件（眼罩/面具）——与 [wearHead]
     * 头箍类分开模板,避免耳机类的模板级校准 preset 被面罩继承（同理反向）。
     * 出厂值 = 头骨前侧眼线位（P1 场景层新增的第 9 个模板）。
     */
    val wearFace = Profile("head", floatArrayOf(0f, 0.035f, 0.10f), floatArrayOf(0f, 0f, 0f), 1f)

    /** 双手大件（挂单骨 + 动画握姿约束）：吉他。 */
    val holdBig = Profile("rightHand", floatArrayOf(0f, 0.05f, 0.08f), floatArrayOf(0f, 0f, 0f), 1f)

    /**
     * 场景摆件（世界坐标挂载，§6.2 P1 尾落地）：瑜伽垫 / 茶具组。入库先占位
     * ——出厂默认不追求手持正确，world mount 上线后由锚点接管。
     */
    val worldProp = Profile("rightHand", floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), 1f)

    /** 模板名 → 出厂默认（sidecar `offsetProfile` 的合法取值域）。 */
    val byName: Map<String, Profile> = mapOf(
        "holdCup" to holdCup,
        "holdBottle" to holdBottle,
        "holdBook" to holdBook,
        "holdWand" to holdWand,
        "holdObject" to holdObject,
        "wearHead" to wearHead,
        "wearFace" to wearFace,
        "holdBig" to holdBig,
        "worldProp" to worldProp,
    )

    fun find(name: String?): Profile? = name?.let { byName[it] }

    /** 挂载值（合并链的载体；[AccessoryCatalog.Entry] 的静态面即此形态）。 */
    data class Mount(
        val bone: String,
        val position: FloatArray,
        val rotationEulerYXZ: FloatArray,
        val scale: Float,
    )

    /**
     * 纯函数合并（JVM 单测直测）：无模板 = 既有行为（条目默认 + 条目级 preset）；
     * 有模板 = 模板出厂值打底，模板级 preset → 条目级 preset 逐维覆盖
     * （[AccessoryCalibrationStore.Preset] 各字段可空 = 未标定维度沿用底层）。
     * 未知模板名防御性回退条目默认（资产测试会拦住 sidecar 手误）。
     */
    fun resolve(
        entryDefault: Mount,
        profileName: String?,
        profilePreset: AccessoryCalibrationStore.Preset?,
        accessoryPreset: AccessoryCalibrationStore.Preset?,
    ): Mount {
        var m = find(profileName)?.let {
            Mount(it.bone, it.position, it.rotationEulerYXZ, it.scale)
        } ?: entryDefault
        for (p in listOfNotNull(profilePreset, accessoryPreset)) {
            m = Mount(
                bone = p.bone ?: m.bone,
                position = p.position?.toFloatArray() ?: m.position,
                rotationEulerYXZ = p.rotationEulerYXZ?.toFloatArray() ?: m.rotationEulerYXZ,
                scale = p.scale ?: m.scale,
            )
        }
        return m
    }
}
