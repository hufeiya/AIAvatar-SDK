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
 * 人脸框归一化（视频模式，纯函数）。
 *
 * ⚠️ 坐标系：ImageProxy 的 width/height 是**传感器缓冲系**（竖屏手机上前置
 * 摄像头为横置的 640×480），而 ML Kit 在 InputImage 带 rotation 后给出的
 * boundingBox 是**旋转后直立系**的（480×640）——归一化必须用直立系尺寸，
 * 用缓冲系会把居中的脸算出恒定偏置（真机实录：注视点恒偏右下，正好等于
 * 480↔640 纵横比错位的量）。前置摄像头原始帧未镜像：脸在屏幕右侧时落在
 * 画面左侧，世界对齐 nx 要翻转（+1=屏幕右）；竖直方向不镜像。
 */
object FaceFrameMath {

    data class NormalizedFace(val nx: Float, val ny: Float, val area: Float)

    /** 旋转后直立系的画面尺寸（90°/270° 时宽高互换）。 */
    fun uprightSize(bufferW: Int, bufferH: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees == 90 || rotationDegrees == 270) bufferH to bufferW
        else bufferW to bufferH

    /**
     * [centerX]/[centerY]/[boxW]/[boxH] 取自直立系的 ML Kit boundingBox；
     * [bufferW]/[bufferH] 是 ImageProxy 缓冲尺寸（仅用于推直立尺寸）。
     */
    fun normalize(
        centerX: Float,
        centerY: Float,
        boxW: Float,
        boxH: Float,
        bufferW: Int,
        bufferH: Int,
        rotationDegrees: Int,
        frontCamera: Boolean,
    ): NormalizedFace {
        val (w, h) = uprightSize(bufferW, bufferH, rotationDegrees)
        val cx = (centerX / w) * 2f - 1f
        val cy = (centerY / h) * 2f - 1f
        return NormalizedFace(
            nx = (if (frontCamera) -cx else cx).coerceIn(-1f, 1f),
            ny = cy.coerceIn(-1f, 1f),
            area = ((boxW * boxH) / (w.toFloat() * h)).coerceIn(1e-4f, 1f),
        )
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
    /** 中文口语关键词（默认；既有行为不变）。 */
    val ZH: List<String> = listOf(
        "看", "瞧", "这个", "那个", "什么", "谁", "衣服", "颜色", "图片", "图像",
        "东西", "好看", "漂亮", "颜值", "背后", "后面", "旁边", "穿", "戴", "头发",
    )

    /** 英文口语关键词（多语言支持；英文模式下识别文本走这套）。 */
    val EN: List<String> = listOf(
        "look", "see", "this", "that", "what", "who", "wearing", "outfit", "color",
        "picture", "photo", "nice", "pretty", "beautiful", "behind", "beside",
        "next to", "hair", "background", "show me",
    )

    /** 兼容别名（SDK 集成者按自家语言接）。 */
    val DEFAULT: List<String> get() = ZH

    /** 按语言取词表。 */
    fun forLang(lang: com.neethu.corelib.Lang): List<String> =
        if (lang == com.neethu.corelib.Lang.EN) EN else ZH

    /** 任一关键词出现即命中（子串匹配，无分词——中文口语够用；英文按词命中）。 */
    fun matches(text: String, keywords: List<String> = DEFAULT): Boolean =
        keywords.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) }
}
