package com.neethu.companion

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.neethu.companion.notify.GreetingWorker
import com.neethu.companion.ui.CompanionScreen
import com.neethu.companion.ui.theme.CompanionTheme

/**
 * 产品入口（§6.3 独立 applicationId/产品名）。职责：
 * 1. 挂 Compose 主屏；2. ai_cmd 调试通道（adb 验收,同 demo 惯例）；3. G10
 * 通知深链（`EXTRA_PENDING_GREETING` → 主屏等会话就绪后由虚拟人开口说出）；
 * 4. 问候通知周期任务的启动时机（开关在设置里,首次启动不自动开）。
 */
class CompanionActivity : ComponentActivity() {

    /** G10 通知深链带来的待播问候文本。 */
    private var pendingGreeting: String? = null

    /** ai_cmd 调试命令队列（adb am start extras）。 */
    private val aiCommands = java.util.concurrent.ConcurrentLinkedQueue<AiDebugCommand>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIntent(intent)
        // Android 13+ 通知权限:仅当用户开过问候通知(升级安装场景)才请求
        if (Build.VERSION.SDK_INT >= 33 &&
            GreetingNotifyPrefs.enabled(companionPrefs(this)) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4001)
        }
        // 冷启动兜底:开关开着但任务没挂上(卸载重装/强制停止后 WorkManager 状态丢失)
        if (GreetingNotifyPrefs.enabled(companionPrefs(this))) {
            GreetingWorker.schedule(this, enabled = true)
        }
        setContent {
            CompanionTheme {
                CompanionScreen(
                    pollAiCommand = { aiCommands.poll() },
                    pendingGreeting = pendingGreeting,
                    onPendingGreetingConsumed = { pendingGreeting = null },
                )
            }
        }
        // 前台时间戳(GreetingPlanner 的前台宽限:刚用过 app 就不推)
        companionPrefs(this).edit()
            .putLong(GreetingNotifyPrefs.KEY_LAST_FOREGROUND_MS, System.currentTimeMillis())
            .apply()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        companionPrefs(this).edit()
            .putLong(GreetingNotifyPrefs.KEY_LAST_FOREGROUND_MS, System.currentTimeMillis())
            .apply()
    }

    private fun consumeIntent(intent: Intent?) {
        intent ?: return
        pendingGreeting = intent.getStringExtra(EXTRA_PENDING_GREETING) ?: pendingGreeting
        com.neethu.companion.parseAiDebugCommand(intent.extras?.let { extras ->
            extras.keySet().associateWith { extras.get(it) }
        }.orEmpty())?.let { cmd ->
            aiCommands.add(cmd)
            Log.i(AI_LOG_TAG, "queued ai_cmd: ${cmd.name} ${cmd.arg.orEmpty()}")
        }
    }

    companion object {
        /** G10 通知深链 extra：待播问候正文。 */
        const val EXTRA_PENDING_GREETING = "pending_greeting"
    }
}
