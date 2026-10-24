package com.neethu.aiavatar_sdk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.neethu.aiavatar_sdk.AutonomyRig
import com.neethu.corelib.AvatarController
import com.neethu.corelib.ContactKind
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * 思考气泡覆盖层（docs/autonomy-feasibility.md §6.2）：2D Compose 覆盖层 +
 * Canvas 画经典思考云（主云朵 + 两颗递减小圆点尾巴指向头顶），不做 3D 贴片。
 *
 * 跟随：气泡显示期间才挂帧回调（[withFrameNanos]），每帧
 * `getHeadWorldPosition()` → 头顶 + 偏移 → `projectToScreen()` → 归一化坐标
 * → 像素（水平 clamp 进屏）；w≤0（背对相机）或出视锥 → 隐藏。坐/躺时头位
 * 随姿势变（投影源是头骨，天然正确）；躺姿头骨 y 低、气泡会贴脸——偏移取
 * 0.45m（站姿 0.25m，§10.5）。
 *
 * 帧预算：无气泡零开销（不挂帧回调），显示期每帧只做两次只读 API 调用。
 */
@Composable
internal fun ThoughtBubbleOverlay(
    controller: AvatarController,
    state: StateFlow<AutonomyRig.ThoughtBubble?>,
    modifier: Modifier = Modifier,
) {
    val bubble by state.collectAsState()
    var anchor by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    LaunchedEffect(bubble) {
        if (bubble == null) {
            anchor = null
            return@LaunchedEffect
        }
        // 躺姿头骨 y 低、气泡贴脸（§10.5）：抬高偏移在气泡开播时取一次
        // （6s 显示期内换姿罕见；每帧查姿势比偏移误差更贵）
        val lift = if (controller.getContactPoseState()?.kind == ContactKind.LIE) LIFT_LYING else LIFT_STANDING
        // 帧跟随（§6.2）：矩阵每帧现取，但投影结果没动就不写状态——静止
        // （坐/躺/站桩）时零重组，帧率不受气泡影响；走动时点位变化才重组
        // （§10.2「气泡更新不占渲染线程」的落地形态）。
        while (isActive) {
            withFrameNanos { }
            val next = headAnchorNormalized(controller, lift)
            val cur = anchor
            if (next != cur && (next == null || cur == null ||
                    kotlin.math.abs(next.first - cur.first) > MOVE_EPS ||
                    kotlin.math.abs(next.second - cur.second) > MOVE_EPS)
            ) {
                anchor = next
            }
        }
    }

    val current = bubble
    val uv = anchor
    if (current == null || uv == null) return
    val elapsed = android.os.SystemClock.elapsedRealtime() - current.startedAtMs
    if (elapsed > current.durationMs) return
    val alpha = fadeAlpha(elapsed, current.durationMs)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val bubbleW = minOf(wPx * 0.72f, with(density) { BUBBLE_WIDTH.toPx() })
        val bubbleH = with(density) { BUBBLE_HEIGHT.toPx() }
        val anchorX = uv.first * wPx
        val anchorY = uv.second * hPx
        val left = (anchorX - bubbleW / 2f).coerceIn(EDGE_GAP, wPx - bubbleW - EDGE_GAP)
        val top = (anchorY - TAIL_GAP * density.density - bubbleH).coerceAtLeast(EDGE_GAP)

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha },
        ) {
            drawThoughtCloud(left, top, bubbleW, bubbleH, anchorX, anchorY)
        }
        Text(
            text = current.text,
            color = TextColor,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .graphicsLayer { this.alpha = alpha }
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .width(with(density) { bubbleW.toDp() })
                .height(with(density) { bubbleH.toDp() })
                .wrapContentHeight(align = Alignment.CenterVertically),
        )
    }
}

/** 头顶世界坐标 → 归一化屏幕坐标（u,v；v 左上原点）；不可见返回 null。 */
private fun headAnchorNormalized(controller: AvatarController, lift: Float): Pair<Float, Float>? {
    val head = controller.getHeadWorldPosition() ?: return null
    val uv = controller.projectToScreen(head[0], head[1] + lift, head[2]) ?: return null
    return uv[0] to uv[1]
}

/** 经典思考云：基座椭圆铺满文本区（防文字溢出云外）+ 三圆叠出蓬松轮廓 + 两颗递减小圆点尾巴指向头顶锚点。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThoughtCloud(
    left: Float,
    top: Float,
    w: Float,
    h: Float,
    anchorX: Float,
    anchorY: Float,
) {
    val cy = top + h / 2f
    val cx = left + w / 2f
    // 基座：椭圆盖满整个文本矩形，文字永不露在云外
    drawOval(
        CloudColor,
        topLeft = Offset(left, top + h * 0.04f),
        size = androidx.compose.ui.geometry.Size(w, h * 0.92f),
    )
    // 蓬松轮廓：三圆叠在基座上下缘
    drawCircle(CloudColor, radius = h * 0.58f, center = Offset(left + w * 0.28f, cy))
    drawCircle(CloudColor, radius = h * 0.68f, center = Offset(cx, cy - h * 0.06f))
    drawCircle(CloudColor, radius = h * 0.52f, center = Offset(left + w * 0.74f, cy))
    // 云底小泡（云与尾巴的过渡）
    drawCircle(CloudColor, radius = h * 0.22f, center = Offset(cx - w * 0.06f, top + h * 0.92f))
    // 尾巴：两颗递减小圆点，沿云底→头顶锚点的连线
    val from = Offset(cx, top + h * 0.98f)
    val dx = anchorX - from.x
    val dy = anchorY - from.y
    drawCircle(CloudColor, radius = h * 0.16f, center = Offset(from.x + dx * 0.34f, from.y + dy * 0.34f))
    drawCircle(CloudColor, radius = h * 0.09f, center = Offset(from.x + dx * 0.68f, from.y + dy * 0.68f))
}

/** 淡入 260ms / 淡出 450ms。 */
private fun fadeAlpha(elapsedMs: Long, durationMs: Long): Float {
    val fadeIn = (elapsedMs / 260f).coerceAtMost(1f)
    val fadeOut = ((durationMs - elapsedMs) / 450f).coerceIn(0f, 1f)
    return fadeIn * fadeOut
}

private val CloudColor = Color(0xF2F8FAFF)
private val TextColor = Color(0xFF3A4A6B)
private val BUBBLE_WIDTH = 236.dp
private val BUBBLE_HEIGHT = 64.dp
private const val EDGE_GAP = 8f
private const val TAIL_GAP = 34f
private const val LIFT_STANDING = 0.25f
private const val LIFT_LYING = 0.45f

/** 归一化坐标死区：变化小于此不写状态（≈2px，静止时零重组）。 */
private const val MOVE_EPS = 0.0012f
