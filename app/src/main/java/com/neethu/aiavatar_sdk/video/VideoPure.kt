package com.neethu.aiavatar_sdk.video

/**
 * 视频模式的纯逻辑件（无 Android 依赖，JVM 单测直测）：
 *  - [SnapshotRingBuffer] 抓拍环形缓存（最近 3 帧，发送时取最清晰一帧）
 *  - [FrameQuality] 拉普拉斯方差清晰度（廉价、无解码依赖的锐度度量）
 *  - [VisionKeywords] 视觉意图关键词（"意图拦截"判定）
 */

/** 一帧已编码的抓拍：data URL（data:image/jpeg;base64,…）+ 元信息。 */
data class SnapshotFrame(
    val dataUrl: String,
    val byteCount: Int,
    val sharpness: Float,
    val atMs: Long,
)

/**
 * 抓拍环形缓存：分析管线按 [capacity] 帧深度持续覆盖写入；发送时刻由调用方
 * 取 [best]（缓存窗内清晰度最高的一帧，并列取最新）——"最新且清晰度最高"
 * 的取帧策略。窗深 3 × 抓拍间隔 ~0.5s ≈ 覆盖最近 1.5s。
 * 线程安全：分析线程写、主线程读。
 */
class SnapshotRingBuffer(private val capacity: Int = 3) {

    private val frames = ArrayDeque<SnapshotFrame>(capacity)

    /** 丢最旧压入新一帧；容量上限 [capacity]。 */
    @Synchronized
    fun push(frame: SnapshotFrame) {
        if (capacity <= 0) return
        while (frames.size >= capacity) frames.removeFirst()
        frames.addLast(frame)
    }

    /** 缓存窗内最清晰的一帧（并列取最新）；空缓存返回 null。 */
    @Synchronized
    fun best(): SnapshotFrame? = frames.maxWithOrNull(compareBy({ it.sharpness }, { it.atMs }))

    /** 全部缓存帧（旧→新），仅测试/观测用。 */
    @Synchronized
    fun framesAll(): List<SnapshotFrame> = frames.toList()

    /** 最新一帧（不管清晰度）。 */
    @Synchronized
    fun newest(): SnapshotFrame? = frames.lastOrNull()

    @Synchronized
    fun size(): Int = frames.size

    @Synchronized
    fun clear() = frames.clear()
}

/**
 * 帧清晰度：4 邻域拉普拉斯的方差。画面平坦（糊/暗）≈0，边缘丰富（清晰）
 * 显著更大。输入是缩小图（64×64）的行主序亮度阵——抓拍线程上的廉价计算。
 */
object FrameQuality {

    fun laplacianVariance(luma: IntArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3 || luma.size < w * h) return 0f
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = luma[i - 1] + luma[i + 1] + luma[i - w] + luma[i + w] - 4 * luma[i]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return ((sumSq / n) - mean * mean).coerceAtLeast(0.0).toFloat()
    }
}

/**
 * 视觉意图关键词（"模式 B 意图拦截"）：识别文本命中 → 该轮语义上需要看图。
 *
 * ⚠️ demo 现状：抓拍帧只存在于视频模式（相机管线随模式启停），且视频模式
 * 每轮都附帧（用户显式进入的"视频通话"语境，漏判"看这个"比多传一张
 * 512×512 JPEG 伤得多——后者约 1-2K vision token）。所以当前发送路径
 * 【不】经过这里。关键词留给两个未来场景：①非视频模式的常驻低帧率相机
 * （帧存在了才有"拦截"的意义）；②SDK 集成者按自家带宽策略接 `send(text,
 * images)` 时复用判定。
 */
object VisionKeywords {
    val DEFAULT: List<String> = listOf(
        "看", "瞧", "这个", "那个", "什么", "谁", "衣服", "颜色", "图片", "图像",
        "东西", "好看", "漂亮", "颜值", "背后", "后面", "旁边", "穿", "戴", "头发",
    )

    /** 任一关键词出现即命中（子串匹配，无分词——中文口语够用）。 */
    fun matches(text: String, keywords: List<String> = DEFAULT): Boolean =
        keywords.any { it.isNotEmpty() && text.contains(it) }
}
