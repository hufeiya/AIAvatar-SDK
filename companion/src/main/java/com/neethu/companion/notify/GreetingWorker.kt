package com.neethu.companion.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neethu.companion.CompanionActivity
import com.neethu.companion.GreetingNotifyPrefs
import com.neethu.companion.R
import com.neethu.companion.companionPrefs
import com.neethu.orchestrator.autonomy.ThoughtComposer
import com.neethu.corelib.Lang
import com.neethu.orchestrator.memory.AgentMemory
import com.neethu.orchestrator.memory.MemoryCategories
import com.neethu.orchestrator.memory.RoomMemoryStore
import com.neethu.orchestrator.memory.RoomRelationshipStore
import com.neethu.orchestrator.memory.RelationshipTracker
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * G10 问候后台推送（酒馆 P3）：WorkManager ~30min 周期唤醒 → [GreetingPlanner]
 * 门控 → 本地模板 + 记忆事实/关系数据合成问候 → 通知落地；点通知进 app 由
 * 虚拟人开口说出（深链 extra → CompanionActivity 消费）。
 *
 * 设计约束：worker 里**不起 AvatarSession**（无渲染上下文、TTS 后台播不可靠），
 * 内容零 LLM（ThoughtComposer 本地模板，成本恒零）；问候正文随通知展示，
 * 「开口说」延迟到用户点进来的时刻——也正是陪伴语义的正确时序。
 */
internal class GreetingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = companionPrefs(context)
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val decision = GreetingPlanner.decide(
            GreetingPlanner.Input(
                nowMs = now,
                hourOfDay = hour,
                enabled = GreetingNotifyPrefs.enabled(prefs),
                dailyCount = GreetingNotifyPrefs.dayCount(prefs, now),
                lastNotifyMs = prefs.getLong(GreetingNotifyPrefs.KEY_LAST_NOTIFY_MS, -1).takeIf { it > 0 },
                lastForegroundMs = prefs.getLong(GreetingNotifyPrefs.KEY_LAST_FOREGROUND_MS, -1).takeIf { it > 0 },
            ),
        )
        Log.i(TAG, "greeting worker: $decision")
        val fire = decision as? GreetingPlanner.Decision.Fire ?: return Result.success()

        val text = composeGreeting(context, hour) ?: return Result.success().also {
            Log.i(TAG, "greeting worker: no content composed, skip")
        }
        GreetingNotifyPrefs.recordNotified(prefs, now)
        // 回执进长期记忆（与前台问候同语义，airi 教训：主动发言要有回执）——
        // 用户下次对话可被自然引用；不写对话历史。
        runCatching {
            val now = System.currentTimeMillis()
            RoomMemoryStore.from(context).add(
                AgentMemory(
                    id = "greeting-notify-$now",
                    content = "我趁你不注意发过一条问候：「${text.take(60)}」",
                    category = MemoryCategories.EVENT,
                    importance = 4,
                    source = "greeting",
                    createdAt = now,
                    updatedAt = now,
                    lastAccessedAt = now,
                ),
            )
        }.onFailure { Log.w(TAG, "greeting receipt failed", it) }
        notify(context, text)
        return Result.success()
    }

    /** 通知内容：记忆事实 / 纪念日 / 称呼进模板；无素材走时段通用池。 */
    private suspend fun composeGreeting(context: Context, hour: Int): String? {
        val composer = ThoughtComposer(Lang.ZH, Random.Default)
        val memoryFact = runCatching {
            RoomMemoryStore.from(context).all()
                .filter { it.content.length >= 6 }
                .sortedByDescending { it.updatedAt }
                .firstOrNull { it.category in FACT_CATEGORIES }?.content
                ?: RoomMemoryStore.from(context).all()
                    .filter { it.content.length >= 6 }
                    .maxByOrNull { it.updatedAt }?.content
        }.getOrNull()?.take(24)
        val relationship = runCatching {
            val store = RoomRelationshipStore.from(context)
            RelationshipTracker(
                store = store,
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
                llmProvider = { null },
                llmConfigProvider = { null },
            ).apply { refresh() }
        }.getOrNull()?.snapshots?.value
        val anniversaryYears = relationship?.takeIf { it.isAnniversaryToday }?.let { it.daysTogether / 365 } ?: 0
        return composer.greetingText(
            hour,
            memoryFact,
            nickname = relationship?.record?.nickname,
            anniversaryYears = anniversaryYears,
        )
    }

    private suspend fun notify(context: Context, text: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.greeting_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.greeting_channel_desc) },
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted, drop greeting")
            return
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, CompanionActivity::class.java).apply {
                putExtra(CompanionActivity.EXTRA_PENDING_GREETING, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_greeting)
            .setContentTitle(context.getString(R.string.greeting_notification_title, cardName(context) ?: context.getString(R.string.app_name)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }

    /** 通知标题带角色名（激活卡名；无卡用应用名）。 */
    private suspend fun cardName(context: Context): String? = runCatching {
        val prefs = companionPrefs(context)
        val index = prefs.getString("ai_cards", null) ?: return@runCatching null
        val active = prefs.getString("ai_active_card", null) ?: return@runCatching null
        // 卡名从卡库解析（CharacterCardStore 落盘 PNG/JSON 原样，读一次 name）
        val store = com.neethu.orchestrator.card.CharacterCardStore(
            java.io.File(context.filesDir, "cards"),
        )
        store.read(active)?.name?.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private const val TAG = "GreetingNotify"
        private const val CHANNEL_ID = "greetings"
        private const val NOTIFICATION_ID = 4001
        private const val WORK_NAME = "greeting-notify"

        /** 记忆事实素材的类目（autonomy 同款）。 */
        private val FACT_CATEGORIES = setOf(
            MemoryCategories.EVENT, MemoryCategories.COMMITMENT, MemoryCategories.PREFERENCE,
        )

        /** 挂载/卸载周期任务（开关切换与冷启动时调用；KEEP 防重复调度）。 */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GreetingWorker>(30, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}
