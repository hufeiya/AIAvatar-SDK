package com.neethu.aiavatar_sdk.video

import com.neethu.aiavatar_sdk.video.PoseMimicMath.LEFT_EAR
import com.neethu.aiavatar_sdk.video.PoseMimicMath.LEFT_ELBOW
import com.neethu.aiavatar_sdk.video.PoseMimicMath.LEFT_EYE
import com.neethu.aiavatar_sdk.video.PoseMimicMath.LEFT_SHOULDER
import com.neethu.aiavatar_sdk.video.PoseMimicMath.LEFT_WRIST
import com.neethu.aiavatar_sdk.video.PoseMimicMath.NOSE
import com.neethu.aiavatar_sdk.video.PoseMimicMath.RIGHT_EAR
import com.neethu.aiavatar_sdk.video.PoseMimicMath.RIGHT_ELBOW
import com.neethu.aiavatar_sdk.video.PoseMimicMath.RIGHT_EYE
import com.neethu.aiavatar_sdk.video.PoseMimicMath.RIGHT_SHOULDER
import com.neethu.aiavatar_sdk.video.PoseMimicMath.RIGHT_WRIST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模仿我」方向解算纯函数层的已知答案单测：**合成关键点 → 断言每根方向分量
 * 精确值**——坐标系符号表全在这里锁死（docs/mimic-skill-feasibility.md §5）：
 * Pose 世界系（x右/y下/z朝相机，[PoseMimicMath.mirrorFrame]）与 FaceLandmarker
 * 矩阵系（x左/y上/z远，[PoseMimicMath.matrixFrame]，经头部矩阵测试锁死）两套
 * 转换分开锁。真机标定若发现某轴反了，改对应转换函数 + 同步改本文件，不动
 * 其他逻辑。
 */
class PoseMimicMathTest {

    private fun pts(vararg pairs: Pair<Int, PLandmark>): List<PLandmark> {
        val all = MutableList(33) { PLandmark(0f, 0f, 0f, 0f) }
        for ((i, p) in pairs) all[i] = p
        return all
    }

    private fun pl(x: Float, y: Float, z: Float = 0f, vis: Float = 0.9f) = PLandmark(x, y, z, vis)

    /** 方向断言（逐分量带容差——contentEquals 按 float 位比较,−0.0f 会假失败）。 */
    private fun assertDir(actual: FloatArray?, x: Float, y: Float, z: Float) {
        assertNotNull(actual)
        assertEquals(x, actual!![0], 1e-4f)
        assertEquals(y, actual[1], 1e-4f)
        assertEquals(z, actual[2], 1e-4f)
    }

    /** 头部关键点（正对镜头）：鼻尖略近相机（Pose 系 z+ = 朝相机）、双耳对称。 */
    private fun head() = listOf(
        NOSE to pl(0f, -0.62f, 0.08f),
        LEFT_EYE to pl(0.03f, -0.65f, 0.08f),
        RIGHT_EYE to pl(-0.03f, -0.65f, 0.08f),
        LEFT_EAR to pl(0.08f, -0.62f, 0f),
        RIGHT_EAR to pl(-0.08f, -0.62f, 0f),
    )

    // ── 镜像语义（默认）：用户左 → 虚拟人右 ────────────────────────────────

    @Test
    fun `tpose - user left arm drives avatar RIGHT arm with mirrored x`() {
        // MP 世界系：x+ = 画面右 = 用户左侧；用户左臂水平指向他自己的左 = (+1,0,0)_mp
        val p = PoseMimicMath.mimicPoseFromLandmarks(
            pts(
                *head().toTypedArray(),
                LEFT_SHOULDER to pl(0.2f, -0.35f), LEFT_ELBOW to pl(0.45f, -0.35f),
                LEFT_WRIST to pl(0.7f, -0.35f),
                RIGHT_SHOULDER to pl(-0.2f, -0.35f), RIGHT_ELBOW to pl(-0.45f, -0.35f),
                RIGHT_WRIST to pl(-0.7f, -0.35f),
            ),
            timestampMs = 1000,
        )!!
        // 镜像 = (−x,−y,−z)：(+1,0,0)_mp → (−1,0,0)_A = 虚拟人右臂 T-pose 自然指向
        assertDir(p.rightUpperArm, -1f, 0f, 0f)
        assertDir(p.rightLowerArm, -1f, 0f, 0f)
        assertDir(p.leftUpperArm, 1f, 0f, 0f)
        assertDir(p.leftLowerArm, 1f, 0f, 0f)
        assertTrue(p.visible)
    }

    @Test
    fun `left hand raised - avatar right arm goes up`() {
        // 用户左肘在左肩正上方：dir (0,−0.25,0)_mp → (0,1,0)_A
        val p = PoseMimicMath.mimicPoseFromLandmarks(
            pts(
                *head().toTypedArray(),
                LEFT_SHOULDER to pl(0.2f, -0.35f), LEFT_ELBOW to pl(0.2f, -0.60f),
                LEFT_WRIST to pl(0.2f, -0.85f),
                RIGHT_SHOULDER to pl(-0.2f, -0.35f), RIGHT_ELBOW to pl(-0.2f, -0.10f),
                RIGHT_WRIST to pl(-0.2f, 0.12f),
            ),
            timestampMs = 1000,
        )!!
        assertDir(p.rightUpperArm, 0f, 1f, 0f)
        assertDir(p.rightLowerArm, 0f, 1f, 0f)
        // 用户右臂垂着（向下）→ 虚拟人左臂向下
        assertDir(p.leftUpperArm, 0f, -1f, 0f)
    }

    @Test
    fun `arm toward camera - avatar points at the viewer (mirror z flip)`() {
        // 用户左臂伸向手机（+z_mp，Pose 系 z+ = 朝相机）→ (0,0,+1)_A = 指向观察者
        val p = PoseMimicMath.mimicPoseFromLandmarks(
            pts(
                *head().toTypedArray(),
                LEFT_SHOULDER to pl(0.2f, -0.35f), LEFT_ELBOW to pl(0.2f, -0.35f, 0.25f),
                LEFT_WRIST to pl(0.2f, -0.35f, 0.45f),
                RIGHT_SHOULDER to pl(-0.2f, -0.35f), RIGHT_ELBOW to pl(-0.2f, -0.10f),
                RIGHT_WRIST to pl(-0.2f, 0.12f),
            ),
            timestampMs = 1000,
        )!!
        assertDir(p.rightUpperArm, 0f, 0f, 1f)
        assertDir(p.rightLowerArm, 0f, 0f, 1f)
    }

    @Test
    fun `head forward mirrors - user turns left, avatar turns to its right`() {
        // 用户头转向他自己的左 30°：鼻尖相对双耳中点 = (sin30, 0, cos30)·k 在
        // MP 系（x_mp+ = 用户左侧；z_mp+ = 朝相机，鼻尖前突）≈ (0.04, 0, 0.07)；
        // 镜像后 x<0（转向虚拟人右侧=画面左）且 z>0（朝向用户）
        val p = PoseMimicMath.mimicPoseFromLandmarks(
            pts(
                NOSE to pl(0.12f, -0.62f, 0.07f),
                LEFT_EYE to pl(0.11f, -0.65f, 0.07f),
                RIGHT_EYE to pl(0.05f, -0.65f, 0.07f),
                LEFT_EAR to pl(0.16f, -0.62f, 0f),
                RIGHT_EAR to pl(0.0f, -0.62f, 0f),
                LEFT_SHOULDER to pl(0.2f, -0.35f), LEFT_ELBOW to pl(0.45f, -0.35f),
                LEFT_WRIST to pl(0.7f, -0.35f),
                RIGHT_SHOULDER to pl(-0.2f, -0.35f), RIGHT_ELBOW to pl(-0.45f, -0.35f),
                RIGHT_WRIST to pl(-0.7f, -0.35f),
            ),
            timestampMs = 1000,
        )!!
        val h = p.headForward!!
        assertTrue("head x should be negative, got ${h[0]}", h[0] < 0f)
        assertTrue("head z should be positive, got ${h[2]}", h[2] > 0f)
        assertEquals(0f, h[1], 1e-4f)
    }

    // ── 人偶模式（mirror=false）：同侧跟随，x 不取反；前后轴恒翻 ───────────

    @Test
    fun `puppet mode keeps sides and still flips z`() {
        val p = PoseMimicMath.mimicPoseFromLandmarks(
            pts(
                *head().toTypedArray(),
                LEFT_SHOULDER to pl(0.2f, -0.35f), LEFT_ELBOW to pl(0.45f, -0.35f),
                LEFT_WRIST to pl(0.7f, -0.35f),
                RIGHT_SHOULDER to pl(-0.2f, -0.35f), RIGHT_ELBOW to pl(-0.2f, -0.35f, 0.25f),
                RIGHT_WRIST to pl(-0.2f, -0.35f, 0.45f),
            ),
            timestampMs = 1000,
            mirror = false,
        )!!
        // 用户左臂 (+1,0,0)_mp → 人偶=(+x,−y,+z)=(1,0,0)_A 填虚拟人**左**臂（同侧，
        // 指向虚拟人自己的左侧）
        assertDir(p.leftUpperArm, 1f, 0f, 0f)
        // 用户右臂前伸 (0,0,+1)_mp → 人偶=(0,0,+1)_A（前伸仍前伸——前后轴恒翻）
        assertDir(p.rightUpperArm, 0f, 0f, 1f)
    }

    // ── 可见性门控与塌缩保护 ──────────────────────────────────────────────

    @Test
    fun `low visibility parts are dropped per-bone`() {
        val p = PoseMimicMath.mimicPoseFromLandmarks(
            pts(
                *head().toTypedArray(),
                LEFT_SHOULDER to pl(0.2f, -0.35f), LEFT_ELBOW to pl(0.45f, -0.35f),
                LEFT_WRIST to pl(0.7f, -0.35f),
                RIGHT_SHOULDER to pl(-0.2f, -0.35f, vis = 0.3f), // 右肩不可见 → 用户右大臂失效
                RIGHT_ELBOW to pl(-0.45f, -0.35f), RIGHT_WRIST to pl(-0.7f, -0.35f),
            ),
            timestampMs = 1000,
        )!!
        // 用户左臂全可见 → 虚拟人右臂（镜像换侧）全在
        assertNotNull(p.rightUpperArm)
        assertNotNull(p.rightLowerArm)
        // 用户右肩不可见 → 虚拟人左大臂失效；但小臂（肘→腕）不经过肩，仍在
        assertNull(p.leftUpperArm)
        assertNotNull(p.leftLowerArm)
        assertTrue(p.visible)
    }

    @Test
    fun `all parts invisible yields a liveness ping with visible=false`() {
        val p = PoseMimicMath.mimicPoseFromLandmarks(
            pts(
                NOSE to pl(0f, -0.62f, 0.08f, vis = 0.1f),
                LEFT_EAR to pl(0.08f, -0.62f, 0f, vis = 0.1f),
                RIGHT_EAR to pl(-0.08f, -0.62f, 0f, vis = 0.1f),
                LEFT_SHOULDER to pl(0.2f, -0.35f, vis = 0.1f),
                LEFT_ELBOW to pl(0.45f, -0.35f, vis = 0.1f),
                LEFT_WRIST to pl(0.7f, -0.35f, vis = 0.1f),
            ),
            timestampMs = 1000,
        )!!
        assertFalse(p.visible)
        assertNull(p.headForward)
        assertNull(p.rightUpperArm)
    }

    @Test
    fun `collapsed segment (elbow equals wrist) drops the lower arm only`() {
        val p = PoseMimicMath.mimicPoseFromLandmarks(
            pts(
                *head().toTypedArray(),
                LEFT_SHOULDER to pl(0.2f, -0.35f), LEFT_ELBOW to pl(0.45f, -0.35f),
                LEFT_WRIST to pl(0.45f, -0.35f), // 腕==肘：塌缩
                RIGHT_SHOULDER to pl(-0.2f, -0.35f), RIGHT_ELBOW to pl(-0.45f, -0.35f),
                RIGHT_WRIST to pl(-0.7f, -0.35f),
            ),
            timestampMs = 1000,
        )!!
        assertNotNull(p.rightUpperArm) // 用户左大臂正常（镜像后填虚拟人右臂）
        assertNull(p.rightLowerArm)    // 用户左小臂塌缩 → 虚拟人右小臂不更新
        assertNotNull(p.leftUpperArm)  // 用户右臂正常 → 虚拟人左臂
        assertNotNull(p.leftLowerArm)
    }

    @Test
    fun `too few landmarks returns null`() {
        // MediaPipe 万一给短列表（防御性守卫）
        assertNull(PoseMimicMath.mimicPoseFromLandmarks(List(10) { pl(0f, 0f, 0f, 0f) }, 1000))
    }

    // ── 合成调试姿态（mimic_force）：与真实解算同路径 ──────────────────────

    @Test
    fun `forced presets flow through the same solver`() {
        val tpose = PoseMimicMath.forcedPreset("tpose", 1000)!!
        assertDir(tpose.rightUpperArm, -1f, 0f, 0f)
        assertDir(tpose.leftUpperArm, 1f, 0f, 0f)

        val leftUp = PoseMimicMath.forcedPreset("left_up", 1000)!!
        assertTrue(leftUp.rightUpperArm!![1] > 0.99f)  // 用户左手举 → 虚拟人右臂上抬
        assertTrue(leftUp.leftUpperArm!![1] < -0.99f)  // 右臂保持下垂

        val fwd = PoseMimicMath.forcedPreset("forward", 1000)!!
        assertDir(fwd.rightUpperArm, 0f, 0f, 1f)

        assertNull(PoseMimicMath.forcedPreset("off", 1000))
        assertNull(PoseMimicMath.forcedPreset("bogus", 1000))
    }

    // ── 方向滤波：One-Euro + z 阻滞 + 死区 ────────────────────────────────

    @Test
    fun `filter converges to a constant stream`() {
        val f = MimicDirectionFilter()
        var out: com.neethu.corelib.MimicPose? = null
        for (i in 0 until 60) {
            out = f.filter(PoseMimicMath.mimicPoseFromLandmarks(tposePts(), i * 80L)!!)
        }
        assertEquals(-1f, out!!.rightUpperArm!![0], 0.01f)
        assertEquals(0f, out.rightUpperArm!![1], 0.01f)
    }

    @Test
    fun `filter deadzone suppresses micro jitter`() {
        val f = MimicDirectionFilter(deadzoneRad = 0.05f)
        val base = f.filter(PoseMimicMath.mimicPoseFromLandmarks(tposePts(), 0)!!)
        val first = base.rightUpperArm!!.copyOf()
        // 微小扰动（肘点挪 5mm）：方向变化远低于死区 → 输出保持原值
        val jittered = f.filter(
            PoseMimicMath.mimicPoseFromLandmarks(
                pts(
                    *head().toTypedArray(),
                    LEFT_SHOULDER to pl(0.2f, -0.35f), LEFT_ELBOW to pl(0.455f, -0.352f),
                    LEFT_WRIST to pl(0.7f, -0.35f),
                    RIGHT_SHOULDER to pl(-0.2f, -0.35f), RIGHT_ELBOW to pl(-0.45f, -0.35f),
                    RIGHT_WRIST to pl(-0.7f, -0.35f),
                ),
                80,
            )!!,
        )
        assertTrue(jittered.rightUpperArm!!.contentEquals(first))
    }

    @Test
    fun `filter passes ping frames through untouched`() {
        val f = MimicDirectionFilter()
        val ping = com.neethu.corelib.MimicPose(
            0L, null, null, null, null, null, null, null, visible = false,
        )
        assertEquals(ping, f.filter(ping))
    }

    @Test
    fun `filter reset clears state - next first frame snaps through`() {
        val f = MimicDirectionFilter()
        f.filter(PoseMimicMath.mimicPoseFromLandmarks(tposePts(), 0)!!)
        f.reset()
        val out = f.filter(PoseMimicMath.mimicPoseFromLandmarks(tposePts(), 0)!!)
        assertDir(out.rightUpperArm, -1f, 0f, 0f)
    }

    /** 标准合成 T-pose（用户双臂平举）。 */
    private fun tposePts() = pts(
        *head().toTypedArray(),
        LEFT_SHOULDER to pl(0.2f, -0.35f), LEFT_ELBOW to pl(0.45f, -0.35f),
        LEFT_WRIST to pl(0.7f, -0.35f),
        RIGHT_SHOULDER to pl(-0.2f, -0.35f), RIGHT_ELBOW to pl(-0.45f, -0.35f),
        RIGHT_WRIST to pl(-0.7f, -0.35f),
    )
}

// ── P2：躯干轴/肩线解算 + 矩阵头部 + 躯干预设 ─────────────────────────────

class PoseMimicTorsoMathTest {

    private fun pts(vararg pairs: Pair<Int, PLandmark>): List<PLandmark> {
        val all = MutableList(33) { PLandmark(0f, 0f, 0f, 0f) }
        for ((i, p) in pairs) all[i] = p
        return all
    }

    private fun pl(x: Float, y: Float, z: Float = 0f, vis: Float = 0.9f) = PLandmark(x, y, z, vis)

    private fun head() = listOf(
        PoseMimicMath.NOSE to pl(0f, -0.62f, 0.08f),
        PoseMimicMath.LEFT_EAR to pl(0.08f, -0.62f, 0f),
        PoseMimicMath.RIGHT_EAR to pl(-0.08f, -0.62f, 0f),
    )

    /** 站立骨架：双肩水平、双髋在下（MP y 向下→肩 y 更小）。 */
    private fun standing() = pts(
        *head().toTypedArray(),
        PoseMimicMath.LEFT_SHOULDER to pl(0.2f, -0.35f),
        PoseMimicMath.RIGHT_SHOULDER to pl(-0.2f, -0.35f),
        PoseMimicMath.LEFT_HIP to pl(0.1f, 0.05f),
        PoseMimicMath.RIGHT_HIP to pl(-0.1f, 0.05f),
    )

    private fun assertVec(v: FloatArray?, x: Float, y: Float, z: Float) {
        assertNotNull(v)
        assertEquals(x, v!![0], 1e-3f)
        assertEquals(y, v[1], 1e-3f)
        assertEquals(z, v[2], 1e-3f)
    }

    @Test
    fun `standing torso axis is up and shoulder line points avatar-left`() {
        val p = PoseMimicMath.mimicPoseFromLandmarks(standing(), 1000)!!
        // 轴 = 肩中−髋中 = (0,−0.4,0)_mp → 镜像 (0,0.4,0) 归一 → (0,1,0)
        assertVec(p.torsoAxis, 0f, 1f, 0f)
        // 肩线 = 虚拟人左肩(用户右, −0.2) − 虚拟人右肩(用户左, +0.2) = (−0.4)_mp → 镜像 (0.4) → (1,0,0)
        assertVec(p.shoulderLine, 1f, 0f, 0f)
    }

    @Test
    fun `user leaning toward camera bows the avatar torso toward viewer`() {
        // 双肩整体前移（+z_mp = 朝相机，Pose 系 z+ 朝相机）并放低（y+）
        val leaning = pts(
            *head().toTypedArray(),
            PoseMimicMath.LEFT_SHOULDER to pl(0.2f, -0.30f, 0.25f),
            PoseMimicMath.RIGHT_SHOULDER to pl(-0.2f, -0.30f, 0.25f),
            PoseMimicMath.LEFT_HIP to pl(0.1f, 0.05f),
            PoseMimicMath.RIGHT_HIP to pl(-0.1f, 0.05f),
        )
        val p = PoseMimicMath.mimicPoseFromLandmarks(leaning, 1000)!!
        // 轴 z 分量：肩前移 → 轴 z>0（朝相机）→ 镜像 z 保号 → 正 = 顶端朝观察者
        assertTrue("torso z should be positive, got ${p.torsoAxis!![2]}", p.torsoAxis!![2] > 0.4f)
    }

    @Test
    fun `invisible hips drop the torso channel but keep arms`() {
        val p = PoseMimicMath.mimicPoseFromLandmarks(
            pts(
                *head().toTypedArray(),
                PoseMimicMath.LEFT_SHOULDER to pl(0.2f, -0.35f),
                PoseMimicMath.RIGHT_SHOULDER to pl(-0.2f, -0.35f),
                PoseMimicMath.LEFT_HIP to pl(0.1f, 0.05f, vis = 0.2f),
                PoseMimicMath.RIGHT_HIP to pl(-0.1f, 0.05f),
            ),
            1000,
        )!!
        assertNull(p.torsoAxis)
        assertNull(p.shoulderLine)
        assertTrue(p.visible) // 手臂/头仍可用
    }

    @Test
    fun `head forward from matrix extracts mirrored column direction`() {
        // 矩阵系（真机实证 2026-10-06）：x+ = 画面左、y+ = 上、z+ = 远离相机，
        // 规范脸 +X=脸右/+Y=上/+Z=脑后，FACE_FORWARD_LOCAL=−1 → forward = −Z' 列。
        // 构造「用户头转向他自己的左 30°」：脑后方向转向用户右（画面左 = +x_矩阵）
        // 且保持远离相机 → Z' = (sin30, 0, cos30) = (0.5, 0, 0.866)。
        // 首版误用 Pose 系 mirrorFrame 把此矩阵的 x/y 双反（用户实测「我向左转头
        // 虚拟人向右转」），本测试按精确分量锁死矩阵专用转换。
        val zc = floatArrayOf(0.5f, 0f, 0.866f)
        val yc = floatArrayOf(0f, 1f, 0f)
        val xc = floatArrayOf(
            yc[1] * zc[2] - yc[2] * zc[1],
            yc[2] * zc[0] - yc[0] * zc[2],
            yc[0] * zc[1] - yc[1] * zc[0],
        )
        val m = FloatArray(16).also {
            it[0] = xc[0]; it[1] = xc[1]; it[2] = xc[2]
            it[4] = yc[0]; it[5] = yc[1]; it[6] = yc[2]
            it[8] = zc[0]; it[9] = zc[1]; it[10] = zc[2]
            it[15] = 1f
        }
        val fwd = PoseMimicMath.headForwardFromMatrix(m, mirror = true)!!
        // forward_mp = −Z' 列 = (−0.5, 0, −0.866)（x<0 = 画面右 = 用户左 = 转头方向）；
        // 矩阵转换 mirror=(+x,+y,−z) → (−0.5, 0, +0.866)：x<0 转向虚拟人右侧（画面左），
        // z>0 朝用户——与 Pose 系鼻-耳路径同向（双源一致）
        assertVec(fwd, -0.5f, 0f, 0.866f)
    }

    @Test
    fun `head forward from matrix rest state faces the viewer`() {
        // 用户正对相机：脑后 = 远离相机 = +z_矩阵 → forward_mp = (0,0,−1)；
        // 转换后 = (0,0,+1)_A = 虚拟人面向用户（z 语义反射的唯一 rest 锚）。
        val m = FloatArray(16).also {
            it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
        }
        assertVec(PoseMimicMath.headForwardFromMatrix(m, mirror = true)!!, 0f, 0f, 1f)
    }

    @Test
    fun `torso presets flow through the same solver`() {
        val leanLeft = PoseMimicMath.forcedPreset("lean_left", 1000)!!
        // 用户向自己左倾 → 轴顶端 x_mp>0 → 镜像后 x<0（虚拟人向它的右侧倾）
        assertTrue(leanLeft.torsoAxis!!.contentEquals(leanLeft.torsoAxis)) // sanity: non-null
        assertTrue("lean_left axis x should be negative, got ${leanLeft.torsoAxis!![0]}", leanLeft.torsoAxis!![0] < 0f)

        val bow = PoseMimicMath.forcedPreset("bow", 1000)!!
        assertTrue("bow axis z should be positive, got ${bow.torsoAxis!![2]}", bow.torsoAxis!![2] > 0.3f)

        assertEquals(
            listOf(
                "tpose", "left_up", "right_up", "both_up", "forward",
                "lean_left", "lean_right", "bow", "turn_left", "off",
            ),
            PoseMimicMath.FORCED_PRESETS,
        )
    }
}
