package com.neethu.aiavatar_sdk

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.neethu.corelib.AvatarController
import com.neethu.corelib.DriveMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 手柄输入枢纽（docs/gamepad-control-feasibility.md）：硬件手柄事件与
 * ai_cmd `pad` 命令族写**同一份轴状态**，统一推给 corelib 模拟量通道
 * ([AvatarController.setDriveAxis]/[AvatarController.setLookAxis])——
 * 人和 AI 操纵的是同一个摇杆，`pad state` 回读的也是同一份世界。
 *
 * 人机仲裁：某通道硬件摇杆越过死区 → 立即清该通道的 AI 锁存（人 > AI，
 * 粘性到下一次 AI 命令）；AI 锁存带最长持续时间（无刷新自动归零，防泄漏
 * 走位）。全部方法主线程调用（事件分发/executor 同线程）。
 *
 * P0 范围：双摇杆（走动+视角）+ A（跑步）/B（停）两键 + pad 命令族；
 * dpad/扳机/肩键映射见文档 P1。
 *
 * ⚠进程级单例（[get]）：MIUI 频繁 pause/resume 闪断（真机实锤每次 am start
 * 都闪一次）会重建 Activity——`by lazy` 挂 Activity 会丢 run/轴状态并重复
 * 注册 InputManager 监听。枢纽挂在 applicationContext 上与进程同寿。
 */
internal class GamepadHub private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: GamepadHub? = null

        /** 取进程级枢纽（幂等；首调以 [context.applicationContext] 构造）。 */
        fun get(context: Context): GamepadHub =
            instance ?: synchronized(this) {
                instance ?: GamepadHub(context.applicationContext).also { instance = it }
            }

        /** 死区外判定用的摇杆噪声容差（右摇杆 Z/RZ vs RX/RY 探测）。 */
        private const val STICK_EPS = 0.02f

        /** AI 锁存最长持续时间（秒）：泄漏的 pad move 不能无限走。 */
        private const val AI_LATCH_MAX_S = 10f
    }

    /** 二轴模拟量（drive：x=右+，y=前+；look：x=yaw+，y=pitch+）。 */
    data class Axes(val x: Float, val y: Float) {
        val isZero: Boolean get() = x == 0f && y == 0f
    }

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val handler = Handler(Looper.getMainLooper())

    private val _deviceName = MutableStateFlow<String?>(null)

    /** 当前手柄设备名（null = 未连接）；HUD chip 与 pad_state 共用。 */
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private var controllerRef: AvatarController? = null
    private var hwDrive = Axes(0f, 0f)
    private var hwLook = Axes(0f, 0f)
    private var aiDrive: Axes? = null
    private var aiLook: Axes? = null
    private var run = false
    private var enabled = true

    // ── 生命周期 ─────────────────────────────────────────────────────────

    /** DemoScreen 组装后绑定控制器（幂等；重绑时把已存轴同步过去）。 */
    fun attach(controller: AvatarController) {
        controllerRef = controller
        refreshConnectedDevice()
        push()
    }

    /** 后台化时调用：归零全部轴（防回前台惊喜走位），AI 锁存一并清。 */
    fun onAppPaused() {
        if (hwDrive.isZero && hwLook.isZero && aiDrive == null && aiLook == null) return
        Log.i(AI_LOG_TAG, "gamepad axes cleared on pause")
        releaseAxes()
    }

    // ── 热插拔 ───────────────────────────────────────────────────────────

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshConnectedDevice()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshConnectedDevice()
        override fun onInputDeviceChanged(deviceId: Int) = refreshConnectedDevice()
    }

    init {
        inputManager.registerInputDeviceListener(deviceListener, handler)
        refreshConnectedDevice()
    }

    private fun refreshConnectedDevice() {
        var pad: InputDevice? = null
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id)
            if (device != null && isGamepad(device)) {
                pad = device
                break
            }
        }
        val name = pad?.name
        if (name != _deviceName.value) {
            _deviceName.value = name
            Log.i(AI_LOG_TAG, "gamepad ${name ?: "disconnected"}")
        }
    }

    private fun isGamepad(device: InputDevice): Boolean {
        if (device.isVirtual) return false
        val sources = device.sources
        // 触摸屏也会声明 JOYSTICK 类位（真机实锤：goodix_ts）——显式排除
        if (sources and InputDevice.SOURCE_TOUCHSCREEN != 0) return false
        // ⚠SOURCE_GAMEPAD(0x401) 含 CLASS_BUTTON 位，`!= 0` 会把电源键
        // （qpnp_pon，0x301）都判成手柄——必须整模式匹配
        if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) return true
        if (sources and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false
        // 真摇杆必有位移轴（X/Y 双摇杆或 HAT 十字键）
        return device.motionRanges.any {
            it.axis == MotionEvent.AXIS_X || it.axis == MotionEvent.AXIS_Y ||
                it.axis == MotionEvent.AXIS_HAT_X
        }
    }

    // ── 硬件事件（MainActivity.dispatchKeyEvent / onGenericMotionEvent 转发）──

    /**
     * Joystick 类 MotionEvent：左摇杆 AXIS_X/Y（上=−1）、右摇杆 Z/RZ
     * （杂牌走 RX/RY，Z/RZ 全零时回退探测）。返回 true = 已消费。
     */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!enabled) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false
        event.device?.let { if (isGamepad(it)) _deviceName.value = it.name }
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        var rx = event.getAxisValue(MotionEvent.AXIS_Z)
        var ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        if (abs(rx) < STICK_EPS && abs(ry) < STICK_EPS) {
            rx = event.getAxisValue(MotionEvent.AXIS_RX)
            ry = event.getAxisValue(MotionEvent.AXIS_RY)
        }
        // 径向死区（硬件伪影，AI 值不过）；前=−Y（摇杆上推为负）
        val drive = DriveMath.applyDeadzone(lx, -ly).let { (x, y) -> Axes(x, y) }
        val look = DriveMath.applyDeadzone(rx, ry).let { (x, y) -> Axes(x, y) }
        if (drive != hwDrive) {
            hwDrive = drive
            if (!drive.isZero) clearAiLatch(driveTimeoutRunnable)
        }
        if (look != hwLook) {
            hwLook = look
            if (!look.isZero) clearAiLatch(lookTimeoutRunnable)
        }
        push()
        return true
    }

    /**
     * 手柄按键（P0：A=跑步增益 toggle，B=停走+停转）。keyRepeat 抑制——
     * 长按会连发 ACTION_DOWN。返回 true = 已消费（不进 IME/焦点链）。
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        if (!KeyEvent.isGamepadButton(event.keyCode)) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> toggleRun()
                KeyEvent.KEYCODE_BUTTON_B -> releaseAxes()
                else -> {} // P1：dpad/扳机/肩键/R3
            }
        }
        return true
    }

    // ── 轴状态推进 ───────────────────────────────────────────────────────

    private val driveTimeoutRunnable = Runnable {
        aiDrive = null
        push()
    }
    private val lookTimeoutRunnable = Runnable {
        aiLook = null
        push()
    }

    private fun clearAiLatch(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }

    private fun armLatch(runnable: Runnable, seconds: Float) {
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, (seconds * 1000).toLong())
    }

    /** 当前生效轴 = AI 锁存优先（它就是最后写入者），否则硬件轴。 */
    private fun push() {
        val controller = controllerRef ?: return
        val drive = aiDrive ?: hwDrive
        val look = aiLook ?: hwLook
        controller.setDriveAxis(forward = drive.y, strafe = drive.x)
        controller.setLookAxis(yaw = look.x, pitch = look.y)
    }

    private fun toggleRun() {
        run = !run
        controllerRef?.setDriveRun(run)
        Log.i(AI_LOG_TAG, "gamepad run=$run")
    }

    /** 全轴归零（硬件+AI，= 松开手柄），取消 AI 锁存定时。 */
    private fun releaseAxes() {
        aiDrive = null
        aiLook = null
        hwDrive = Axes(0f, 0f)
        hwLook = Axes(0f, 0f)
        handler.removeCallbacks(driveTimeoutRunnable)
        handler.removeCallbacks(lookTimeoutRunnable)
        push()
    }

    // ── ai_cmd pad 命令族 ────────────────────────────────────────────────

    /**
     * 执行 `pad` 命令（解析见 [parsePadCommand]）。AI 写轴带锁存：无时长 =
     * 最长 [AI_LATCH_MAX_S] 秒（防泄漏走位），带时长 = 到时自动归零
     * （过程指令「往前走 2 秒」）。返回结果行（pad state 快照）。
     */
    fun executeCommand(command: AiDebugCommand): String {
        val pad = parsePadCommand(command.arg, command)
        return when (pad) {
            is PadCommand.Move -> {
                require(enabled) { "gamepad drive is off — send 'pad on' first" }
                aiDrive = Axes(pad.x.coerceIn(-1f, 1f), pad.y.coerceIn(-1f, 1f))
                if (aiDrive!!.isZero) clearAiLatch(driveTimeoutRunnable)
                else armLatch(driveTimeoutRunnable, pad.seconds ?: AI_LATCH_MAX_S)
                push()
                stateLine()
            }
            is PadCommand.Look -> {
                require(enabled) { "gamepad drive is off — send 'pad on' first" }
                aiLook = Axes(pad.x.coerceIn(-1f, 1f), pad.y.coerceIn(-1f, 1f))
                if (aiLook!!.isZero) clearAiLatch(lookTimeoutRunnable)
                else armLatch(lookTimeoutRunnable, pad.seconds ?: AI_LATCH_MAX_S)
                push()
                stateLine()
            }
            PadCommand.Release -> {
                releaseAxes()
                "axes released\n${stateLine()}"
            }
            is PadCommand.Run -> {
                run = pad.on ?: !run
                controllerRef?.setDriveRun(run)
                "run=$run"
            }
            is PadCommand.Press -> when (pad.button) {
                "a" -> { toggleRun(); stateLine() }
                "b" -> { releaseAxes(); "released via B\n${stateLine()}" }
                else -> throw IllegalArgumentException(
                    "pad press supports a|b (P0) — dpad/triggers land in P1",
                )
            }
            PadCommand.State -> stateLine()
            is PadCommand.Enabled -> {
                enabled = pad.on
                if (!enabled) releaseAxes()
                "gamepad enabled=$enabled"
            }
        }
    }

    /** pad_state 快照行：设备/使能/轴值/AI 锁存源/跑步/走动相位。 */
    fun stateLine(): String {
        val drive = aiDrive ?: hwDrive
        val look = aiLook ?: hwLook
        val walk = controllerRef?.getWalkState()
        fun fmt(a: Axes) = "(%.2f,%.2f)".format(a.x, a.y)
        return "dev=${_deviceName.value ?: "none"} enabled=$enabled run=$run " +
            "drive=${fmt(drive)} src=${if (aiDrive != null) "AI" else "HW"} " +
            "look=${fmt(look)} srcLook=${if (aiLook != null) "AI" else "HW"} " +
            "walking=${controllerRef?.isDriveWalking() ?: false} " +
            (walk?.let { "walk=${it.phase} pos=(%.2f,%.2f) v=%.2f".format(it.x, it.z, it.speed) } ?: "walk=n/a")
    }
}

/** `pad` 子命令解析结果（纯数据，JVM 单测）。 */
internal sealed interface PadCommand {
    /** 左摇杆：x=左右(右+)，y=前后(前+)；seconds=null=锁存到 release/超时。 */
    data class Move(val x: Float, val y: Float, val seconds: Float?) : PadCommand

    /** 右摇杆：x=yaw(右+)，y=pitch(下+)；seconds 同上。 */
    data class Look(val x: Float, val y: Float, val seconds: Float?) : PadCommand

    /** 双轴归零（= 松开手柄）。 */
    data object Release : PadCommand

    /** 跑步增益；on=null = 翻转。 */
    data class Run(val on: Boolean?) : PadCommand

    /** 单发按键（沿触发）。 */
    data class Press(val button: String) : PadCommand

    /** 状态快照。 */
    data object State : PadCommand

    /** 手柄控制总开关（off 时硬件事件直通不消费）。 */
    data class Enabled(val on: Boolean) : PadCommand
}

/**
 * 解析 `pad` 子命令。数字参数双形态：extras 优先（ai_x/ai_y/ai_z 按位：
 * 第 1/2/3 个数；move 与 look 同构——move: 左右/前后/秒，look: yaw/pitch/秒），
 * arg 内联 token 兜底（`pad move 0 1 2`）。裸 `pad` = 查状态（LLM 友好）。
 */
internal fun parsePadCommand(arg: String?, cmd: AiDebugCommand): PadCommand {
    val tokens = arg?.trim()?.split(Regex("\\s+"))
        ?.filter { it.isNotEmpty() }
        ?.map { it.lowercase() }
        .orEmpty()
    val sub = tokens.firstOrNull() ?: return PadCommand.State
    val rest = tokens.drop(1)

    // 数字三源：extras（typed）→ 内联 token → 缺省
    fun nums(defaultX: Float, defaultY: Float): Triple<Float, Float, Float?> {
        val x = cmd.x ?: rest.getOrNull(0)?.toFloatOrNull() ?: defaultX
        val y = cmd.y ?: rest.getOrNull(1)?.toFloatOrNull() ?: defaultY
        val seconds = cmd.z ?: rest.getOrNull(2)?.toFloatOrNull()
        return Triple(x, y, seconds?.takeIf { it > 0f })
    }

    return when (sub) {
        "move", "drive" -> {
            val (x, y, seconds) = nums(defaultX = 0f, defaultY = 0f)
            PadCommand.Move(x, y, seconds)
        }
        "look", "camera" -> {
            val (x, y, seconds) = nums(defaultX = 0f, defaultY = 0f)
            PadCommand.Look(x, y, seconds)
        }
        "release", "stop" -> PadCommand.Release
        "run" -> PadCommand.Run(
            when (rest.firstOrNull()) {
                "on", "true", "1" -> true
                "off", "false", "0" -> false
                null -> null
                else -> throw IllegalArgumentException("pad run expects on|off, got '${rest.first()}'")
            },
        )
        "press" -> PadCommand.Press(
            rest.firstOrNull()
                ?: throw IllegalArgumentException("pad press expects a|b"),
        )
        "state" -> PadCommand.State
        "on" -> PadCommand.Enabled(true)
        "off" -> PadCommand.Enabled(false)
        else -> throw IllegalArgumentException(
            "unknown pad subcommand '$sub' — move|look|release|run|press|state|on|off",
        )
    }
}
