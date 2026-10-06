package com.neethu.orchestrator.face

/**
 * MediaPipe FaceLandmarker 的 52 ARKit blendshapes → 当前模型 morph 名的映射
 * （「模仿我」P2 表情通道的纯函数层，JVM 可测）。
 *
 * 匹配策略（按序）：
 *  1. **归一化直配**——小写、去 `_`/空格 后与模型 morph 名相等（ARKit 命名的
 *     模型如 SK_Sun 的 52 morph 全走这条，逐字对应零损失）；
 *  2. **ARKit → VRM 预设别名表**——VRM 预设命名（aa/ih/…/blinkLeft/joy/angry…）
 *     的模型按语义对应表降级映射（jawOpen→aa、mouthSmile→joy、browDown→angry
 *     等，近似但让非 ARKit 模型也有表情模仿）；
 *  3. 都没有 → 丢弃（模型没有的 morph 硬写无意义，FaceDriver 的 send 还会
 *     再门控一次）。
 *
 * 多个 ARKit 源映射到同一个 morph 时取 max（左右对称 morph 汇成一个 VRM 预设
 * 的自然语义）。`neutral` 由调用方过滤（权重恒 ~1，写了等于锁死中性脸）。
 */
object MimicFaceMapper {

    /** 归一化：小写、去下划线/空格/连字符。 */
    private fun norm(name: String): String = buildString {
        for (c in name) if (c != '_' && c != ' ' && c != '-') append(c.lowercaseChar())
    }

    /**
     * ARKit 名 → VRM 预设名（近似语义）。键=ARKit 归一化名，值=按优先级尝试的
     * VRM 预设归一化名列表。只收「模型没有 ARKit 直配」时的降级路径。
     */
    private val VRM_ALIASES: Map<String, List<String>> = mapOf(
        "jawopen" to listOf("aa", "a"),
        "mouthfun" to listOf("fun"),
        "mouthpucker" to listOf("ou", "u"),
        "mouthsmileleft" to listOf("joy"),
        "mouthsmileright" to listOf("joy"),
        "mouthfrownleft" to listOf("sorrow"),
        "mouthfrownright" to listOf("sorrow"),
        "browdownleft" to listOf("angry"),
        "browdownright" to listOf("angry"),
        "browinnerup" to listOf("surprised", "angry"),
        "browouterupleft" to listOf("surprised"),
        "browouterupright" to listOf("surprised"),
        "eyewideleft" to listOf("surprised"),
        "eyewideright" to listOf("surprised"),
        "eyesquintleft" to listOf("fun"),
        "eyesquintright" to listOf("fun"),
        "eyeblinkleft" to listOf("blinkleft", "blink_l", "blink"),
        "eyeblinkright" to listOf("blinkright", "blink_r", "blink"),
    )

    /**
     * 映射一帧 blendshapes。返回 `morph 名 → 权重`（已过滤低于 [deadzone] 的
     * 噪声、多个源取 max）；无可用映射返回空表。
     */
    fun map(
        blendshapes: Map<String, Float>,
        availableMorphs: Collection<String>,
        deadzone: Float = 0.04f,
    ): Map<String, Float> {
        if (blendshapes.isEmpty() || availableMorphs.isEmpty()) return emptyMap()
        val byNorm = HashMap<String, String>(availableMorphs.size)
        for (m in availableMorphs) byNorm[norm(m)] = m
        val out = HashMap<String, Float>()
        for ((name, weight) in blendshapes) {
            if (weight <= deadzone) continue
            val n = norm(name)
            if (n == "neutral") continue
            val target = byNorm[n]?.let { direct(it, weight) }
                ?: VRM_ALIASES[n]?.firstNotNullOfOrNull { alias -> byNorm[norm(alias)]?.let { direct(it, weight) } }
            if (target != null) {
                val existing = out[target.first]
                out[target.first] = maxOf(existing ?: 0f, target.second)
            }
        }
        return out
    }

    private fun direct(morph: String, weight: Float): Pair<String, Float> = morph to weight.coerceIn(0f, 1f)
}
