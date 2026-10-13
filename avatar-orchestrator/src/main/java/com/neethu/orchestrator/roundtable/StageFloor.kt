package com.neethu.orchestrator.roundtable

/**
 * 舞台地面的能力缝（多虚拟人 P3 站位编排，docs/multi-avatar-feasibility.md
 * §4.7）：让 [RoundtableSession] 在开题时把各槽位走到对谈位并互相对着。
 * app 用 [com.neethu.corelib.stage.AvatarHandle] + 各槽会话的 LocomotionDriver
 * 实现（走动复用 `<move:锚点>` 全链：防抖/waypoint 图/bounds clamp）；
 * JVM 单测用 fake。
 */
interface StageFloorRelay {

    /**
     * 槽位的对谈锚点（名字 + 世界坐标）。锚点表含 `stage_<slot>` 时返回
     * （app 侧约定），null = 用 [StageFormation] 推导落点。
     */
    fun conversationAnchor(slotId: String): Pair<String, Pair<Float, Float>>?

    /** 走向锚点（复用 `<move:锚点>` 全链）。false = 没起步（没在走动/没场景/没步态）。 */
    fun walkToAnchor(slotId: String, anchorName: String): Boolean

    /** 直线走向坐标（锚点缺席时的推导落点；bounds clamp 在引擎侧兜底）。 */
    fun walkToPoint(slotId: String, x: Float, z: Float): Boolean

    /** 原地转向 (x,z)（对谈位互相对着；走动引擎的纯转模式）。 */
    fun faceTowards(slotId: String, x: Float, z: Float): Boolean

    /** 停步（编排走位被防抖拒绝时的让路手段：开题是显式指令，走位让路）。 */
    fun stopWalking(slotId: String)

    /** 槽位当前脚底世界坐标 (x,z)；模型未就绪 = null（该槽跳过编排）。 */
    fun positionOf(slotId: String): Pair<Float, Float>?

    /** 相机/用户的世界坐标 (x,z)——对谈连线取其垂直方向，「用户在侧」。 */
    fun cameraXZ(): Pair<Float, Float>?

    /** 槽位是否仍在途中（走动中或有 waypoint 续腿排队）——多段腿的到达要过滤。 */
    fun isEnRoute(slotId: String): Boolean
}

/**
 * 对谈位编排的纯数学（P3，JVM 可测）：给定各槽当前位置与相机位置，推导
 * 「互相对着、用户在侧」的落点与互指目标。
 *
 * - 两人：以当前位置质心为中心，沿「相机→质心」的**垂直**方向排开
 *   （[spacing] 间距）——相机看到的是并肩侧影，两人面对面；落点按总走路
 *   距离最小化分配（少走岔路）。
 * - 三人以上：围绕质心的正多边形（半径由间距反解），全部面朝中心。
 * - 锚点优先：调用方把锚点坐标并进 [anchors]，推导只负责没锚点的槽；
 *   互指目标一律按**最终落点**算（含锚点槽）。
 */
object StageFormation {

    /** 两人对谈位的间距（米）：约一臂半，特写不顶牛、同框装得下。 */
    const val DEFAULT_SPACING = 1.6f

    /** 一个槽位的编排结果：走向 (x,z)，到位后面向 (faceX,faceZ)。 */
    data class Plan(
        val slotId: String,
        val x: Float,
        val z: Float,
        val faceX: Float,
        val faceZ: Float,
    )

    /**
     * [positions] = 槽位 → 当前脚底坐标（至少 2 个才成局）；
     * [anchors] = 槽位 → 对谈锚点坐标（可缺省，缺的用推导落点）；
     * [camera] = 用户/相机 (x,z)，null = 按「相机在 +Z 正前」处理。
     */
    fun plan(
        positions: Map<String, Pair<Float, Float>>,
        anchors: Map<String, Pair<Float, Float>> = emptyMap(),
        camera: Pair<Float, Float>? = null,
        spacing: Float = DEFAULT_SPACING,
    ): List<Plan> {
        if (positions.size < 2) return emptyList()
        val ids = positions.keys.toList()
        val cx = positions.values.map { it.first }.average().toFloat()
        val cz = positions.values.map { it.second }.average().toFloat()
        val derived = deriveSpots(positions, cx, cz, camera, spacing)
        val target = ids.associateWith { anchors[it] ?: derived.getValue(it) }

        return ids.map { id ->
            val (x, z) = target.getValue(id)
            // 互指：两人=对方落点；多人=其余人落点的均值（圆桌朝向圆心等效）
            val others = ids.filter { it != id }
            val fx = others.map { target.getValue(it).first }.average().toFloat()
            val fz = others.map { target.getValue(it).second }.average().toFloat()
            Plan(id, x, z, fx, fz)
        }
    }

    /**
     * 推导落点（无锚点槽的兜底）：沿「相机→质心」垂直轴对称排开；两人按
     * 总走路距离最小择向，多人正多边形环布。
     */
    private fun deriveSpots(
        positions: Map<String, Pair<Float, Float>>,
        cx: Float,
        cz: Float,
        camera: Pair<Float, Float>?,
        spacing: Float,
    ): Map<String, Pair<Float, Float>> {
        val ids = positions.keys.toList()
        // 相机→质心的单位轴；退化（相机与质心重合/未给）按正前——相机在 +Z 看向 -Z
        val cam = camera ?: Pair(cx, cz + 6f)
        var ax = cx - cam.first
        var az = cz - cam.second
        val len = kotlin.math.sqrt(ax * ax + az * az)
        ax = if (len < 1e-4f) 0f else ax / len
        az = if (len < 1e-4f) -1f else az / len
        // 垂直方向（左法线）
        val px = -az
        val pz = ax

        return if (ids.size == 2) {
            val half = spacing / 2f
            val spotA = Pair(cx - px * half, cz - pz * half)
            val spotB = Pair(cx + px * half, cz + pz * half)
            val (a, b) = ids
            val keep = dist(positions.getValue(a), spotA) + dist(positions.getValue(b), spotB)
            val swap = dist(positions.getValue(a), spotB) + dist(positions.getValue(b), spotA)
            if (keep <= swap) mapOf(a to spotA, b to spotB) else mapOf(a to spotB, b to spotA)
        } else {
            // 正 n 边形：半径 = spacing / (2 sin(π/n))，相位取垂直轴方向对齐相机侧
            val n = ids.size
            val radius = (spacing / (2f * kotlin.math.sin(Math.PI.toFloat() / n)))
            val base = kotlin.math.atan2(pz, px)
            ids.indices.associate { i ->
                val ang = base + (2f * Math.PI.toFloat()) * i / n
                ids[i] to Pair(cx + radius * kotlin.math.cos(ang), cz + radius * kotlin.math.sin(ang))
            }
        }
    }

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = a.first - b.first
        val dz = a.second - b.second
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }
}
