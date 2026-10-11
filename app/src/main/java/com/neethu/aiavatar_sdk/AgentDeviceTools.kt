package com.neethu.aiavatar_sdk

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.neethu.corelib.AmbiencePreset
import com.neethu.corelib.AvatarController
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.orchestrator.agent.AgentToolCatalog
import com.neethu.orchestrator.agent.DeviceVitals
import com.neethu.orchestrator.agent.HapticFailure
import com.neethu.orchestrator.agent.TorchFailure
import com.neethu.orchestrator.i18n.PromptTexts
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P0.5 设备工具的 app 侧接线（docs/agent-tools-expansion.md §2/§3.1）：六个
 * 工具（get_device_status / set_flashlight / send_haptic / read_clipboard /
 * set_ambience / take_photo）的 Android 实现全部收在这里，MainActivity 只剩
 * 一个 LaunchedEffect 调 [registerAll]——MainActivity 3744 行，不再给它加接线。
 *
 * 缝模式（getDeviceTime 同款）：orchestrator 定义 Schema/参数校验/文案，
 * 这里注入设备实现；所有 Android API 异常都归因成缝的失败枚举，由
 * orchestrator 的 PromptTexts 双语文案回灌——工具失败永不炸回合。
 *
 * 隐私分级（本批定价先例）：五个无隐私面工具跟随 `agentTools` 总开关；
 * `read_clipboard` 读取用户数据，独立开关默认关（[AiChatPrefs.readClipboard]，
 * 开关切换经 [registerAll] 重跑即注册/反注册，协议块按指纹自动重钉）。
 *
 * 责任清理：手电筒挂 [DefaultLifecycleObserver]，页面 onStop/onDestroy 强制
 * 关灯——模型开了灯用户走人、手电筒亮到天荒地老是产品事故。总开关关闭时
 * 连 P0 内置的 get_device_time / get_current_view 也一并摘掉（设置页承诺
 * 「关闭后回到纯对话」，此前 P0 只摘了 observe_camera，这里补齐）。
 *
 * [context] 允许 null：JVM 单测/无界面集成传 null，注册照常、执行时统一降级
 * isError 回灌（注册路径从不触碰 context，全部延迟到 executor 里）。
 */
object AgentDeviceTools {

    private const val BUILTIN_TIME = "get_device_time"
    private const val BUILTIN_VIEW = "get_current_view"
    private const val STATUS = "get_device_status"
    private const val FLASHLIGHT = "set_flashlight"
    private const val HAPTIC = "send_haptic"
    private const val CLIPBOARD = "read_clipboard"
    private const val AMBIENCE = "set_ambience"
    private const val PHOTO = "take_photo"

    // ── 手电筒状态（lifecycle 清理用；object 级：跨会话重建仍有效）──
    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var torchOn = false
    private var observerAttachedLifecycle: Lifecycle? = null

    private val torchLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = forceTorchOff()
        override fun onDestroy(owner: LifecycleOwner) = forceTorchOff()
    }

    /**
     * 按当前偏好注册/反注册全部设备工具。幂等：同名工具已在注册表里就跳过
     * （不白白推高 registry revision 触发协议块重钉）；会话重建（换上下文/
     * 换语言）后注册表为空，全量补回。effect 依赖 session + agentTools +
     * readClipboard + lang，任一变化重跑一次即可。
     *
     * @param currentRenderSettings 当前渲染设置正本（氛围预设基于它 copy）。
     * @param onRenderSettingsChange 氛围切换的回写缝（demo 传设置页同款更新
     *   路径：状态 + 持久化 + controller 推送；内部已保证主线程回调）。
     */
    fun registerAll(
        session: AvatarSession,
        controller: AvatarController,
        context: Context?,
        texts: PromptTexts,
        prefs: AiChatPrefs,
        lifecycle: Lifecycle?,
        currentRenderSettings: () -> AvatarRenderSettings,
        onRenderSettingsChange: (AvatarRenderSettings) -> Unit,
    ) {
        // 清理观察者只挂一次（重复 registerAll 幂等）；换 lifecycle 重新挂。
        if (lifecycle != null && observerAttachedLifecycle !== lifecycle) {
            observerAttachedLifecycle?.removeObserver(torchLifecycleObserver)
            lifecycle.addObserver(torchLifecycleObserver)
            observerAttachedLifecycle = lifecycle
        }
        if (!prefs.agentTools) {
            listOf(STATUS, FLASHLIGHT, HAPTIC, CLIPBOARD, AMBIENCE, PHOTO, BUILTIN_TIME, BUILTIN_VIEW)
                .forEach { session.tools.unregister(it) }
            return
        }
        // 内置两件可能被上一次总开关关闭摘掉，补回（与会话 init 注册的执行体同源）。
        if (session.tools.find(BUILTIN_TIME) == null) {
            AgentToolCatalog.getDeviceTime(texts).let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        if (session.tools.find(BUILTIN_VIEW) == null) {
            AgentToolCatalog.getCurrentView(texts) { controller.getActiveCameraShot() }
                .let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        if (session.tools.find(STATUS) == null) {
            AgentToolCatalog.getDeviceStatus(texts) { readVitals(context) }
                .let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        if (session.tools.find(FLASHLIGHT) == null) {
            AgentToolCatalog.setFlashlight(texts) { enabled -> setTorch(context, enabled) }
                .let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        if (session.tools.find(HAPTIC) == null) {
            AgentToolCatalog.sendHaptic(texts) { pattern -> vibrate(context, pattern) }
                .let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        if (session.tools.find(AMBIENCE) == null) {
            AgentToolCatalog.setAmbience(texts) { preset ->
                val updated = preset.applyTo(currentRenderSettings())
                withContext(Dispatchers.Main) { onRenderSettingsChange(updated) }
                true
            }.let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        if (session.tools.find(PHOTO) == null) {
            AgentToolCatalog.takePhoto(texts) { captureAndSave(context, controller) }
                .let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        // 剪贴板：唯一独立开关（默认关）。开关切换 = 本函数整体重跑，缺则挂、
        // 多则摘，其余工具因 find 守卫不动。
        val clipboardRegistered = session.tools.find(CLIPBOARD) != null
        if (prefs.readClipboard && !clipboardRegistered) {
            AgentToolCatalog.readClipboard(texts) { readClipboardText(context) }
                .let { (spec, exec) -> session.tools.register(spec, exec) }
        } else if (!prefs.readClipboard && clipboardRegistered) {
            session.tools.unregister(CLIPBOARD)
        }
    }

    // ── set_flashlight ──────────────────────────────────────────────────────

    /**
     * `CameraManager.setTorchMode`。返回 null = 成功。⚠相机被自家占用（视频
     * 模式/按需拍照）时部分机型抛 CAMERA_IN_USE——按「错误回灌」归因，让模型
     * 口头转告，绝不吞掉当成功。
     */
    private fun setTorch(context: Context?, enabled: Boolean): TorchFailure? {
        if (context == null) return TorchFailure.FAILED
        val manager = cameraManager
            ?: context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return TorchFailure.FAILED
        cameraManager = manager
        val id = torchCameraId ?: findFlashCameraId(context, manager) ?: return TorchFailure.NO_FLASH
        return try {
            manager.setTorchMode(id, enabled)
            torchCameraId = id
            torchOn = enabled
            null
        } catch (e: CameraAccessException) {
            if (e.reason == CameraAccessException.CAMERA_IN_USE) TorchFailure.CAMERA_IN_USE else TorchFailure.FAILED
        } catch (_: Exception) {
            TorchFailure.FAILED
        }
    }

    private fun findFlashCameraId(context: Context, manager: CameraManager): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) return null
        return try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 页面退后台/销毁时强制关灯（责任清理，§2.2 坑 2）。 */
    private fun forceTorchOff() {
        if (!torchOn) return
        val id = torchCameraId
        val manager = cameraManager
        if (id != null && manager != null) {
            try {
                manager.setTorchMode(id, false)
            } catch (_: Exception) {
                // 关不掉也只能算了：设备可能已被别的应用抢走相机
            }
        }
        torchOn = false
    }

    // ── send_haptic ─────────────────────────────────────────────────────────

    private fun vibrate(context: Context?, pattern: String): HapticFailure? {
        if (context == null) return HapticFailure.FAILED
        val vibrator = vibratorOf(context) ?: return HapticFailure.NO_VIBRATOR
        if (!vibrator.hasVibrator()) return HapticFailure.NO_VIBRATOR
        val effect = hapticEffect(pattern) ?: return HapticFailure.FAILED
        return try {
            vibrator.vibrate(effect)
            null
        } catch (_: Exception) {
            HapticFailure.FAILED
        }
    }

    private fun vibratorOf(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** 四档预设波形（§2.3）：模型不设计波形，预设表锁单测。 */
    private fun hapticEffect(pattern: String): VibrationEffect? = when (pattern) {
        // 65bpm lub-dub（强 100ms + 歇 200ms + 弱 60ms + 歇 560ms ≈ 0.92s/圈）×6 ≈ 5.5s
        "heartbeat" -> {
            val cycleT = longArrayOf(0, 100, 200, 60, 560)
            val cycleA = intArrayOf(0, 180, 0, 110, 0)
            val timings = LongArray(cycleT.size * 6)
            val amplitudes = IntArray(cycleA.size * 6)
            repeat(6) { i ->
                cycleT.forEachIndexed { j, t -> timings[i * cycleT.size + j] = t }
                cycleA.forEachIndexed { j, a -> amplitudes[i * cycleA.size + j] = a }
            }
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        }
        "high_five" -> VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
        // 低幅高频 ~3s：40ms 交替 90/0（猫呼噜的颤感）
        "purr" -> VibrationEffect.createWaveform(
            LongArray(75) { 40 },
            IntArray(75) { if (it % 2 == 0) 90 else 0 },
            -1,
        )
        "gentle_tap" -> VibrationEffect.createWaveform(longArrayOf(0, 15, 120, 15), intArrayOf(0, 120, 0, 120), -1)
        else -> null
    }

    // ── read_clipboard ──────────────────────────────────────────────────────

    /** 主线程读剪贴板（Android 10+ 焦点限制对本场景无碍：用户切回本 app 才说话）。 */
    private suspend fun readClipboardText(context: Context?): String? {
        if (context == null) return null
        return withContext(Dispatchers.Main) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@withContext null
            val clip = cm.primaryClip ?: return@withContext null
            // 多形态归一为文本（coerceToText 非文本形态返回空串）
            (0 until clip.itemCount).asSequence()
                .mapNotNull { clip.getItemAt(it)?.coerceToText(context)?.toString() }
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
        }
    }

    // ── set_ambience（缝体在 registerAll 里：preset.applyTo + 回写缝）────────

    // ── take_photo ──────────────────────────────────────────────────────────

    /** 抓下一帧渲染画面 → 存 PNG 到相册 Pictures/AIAvatar，返回文件名。 */
    private suspend fun captureAndSave(context: Context?, controller: AvatarController): String? {
        if (context == null) return null
        val bitmap = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
                val resumed = AtomicBoolean(false)
                fun resumeOnce(bmp: android.graphics.Bitmap?) {
                    if (resumed.compareAndSet(false, true)) cont.resume(bmp, onCancellation = null)
                }
                val started = try {
                    controller.captureFrame { bmp -> resumeOnce(bmp) }
                } catch (_: Exception) {
                    false
                }
                if (!started) resumeOnce(null)
            }
        } ?: return null
        return withContext(Dispatchers.IO) { saveBitmapToGallery(context, bitmap) }
    }

    /**
     * MediaStore 相册写入（API 29+ 对自有贡献零权限，与 minSdk 29 对齐）；
     * 失败时清掉 IS_PENDING 残留条目，返回 null = 保存失败。
     */
    private fun saveBitmapToGallery(context: Context, bitmap: android.graphics.Bitmap): String? {
        val name = "avatar_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIAvatar")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        } catch (_: Exception) {
            return null
        }
        val ok = try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        // 部分相册不认 IS_PENDING 翻转，补一次媒体扫描兜底（失败不影响结果）
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(uri.path ?: ""), arrayOf("image/png"), null) }
        return name
    }

    // ── get_device_status ───────────────────────────────────────────────────

    /** 电量/温度/充电（粘性广播）+ 音频路由，一次拿全。 */
    private fun readVitals(context: Context?): DeviceVitals? {
        if (context == null) return null
        return try {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = if (status >= 0) {
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            } else {
                null
            }
            // ⚠部分 OEM 粘性广播 temperature 恒 0 → null（文案降级「未知」，不编数）
            val tempRaw = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val tempC = if (tempRaw > 0) tempRaw / 10f else null
            DeviceVitals(percent, charging, tempC, audioRouteKey(context))
        } catch (_: Exception) {
            null
        }
    }

    /** 输出设备归一：蓝牙 > 有线 > 扬声器 > 听筒（多数机型后两者同时在列）。 */
    private fun audioRouteKey(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "unknown"
        val types = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        } catch (_: Exception) {
            return "unknown"
        }
        fun has(vararg candidates: Int) = candidates.any(types::contains)
        return when {
            has(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) -> "bluetooth"
            has(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET) -> "wired"
            has(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) -> "speaker"
            has(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) -> "earpiece"
            else -> "unknown"
        }
    }
}
