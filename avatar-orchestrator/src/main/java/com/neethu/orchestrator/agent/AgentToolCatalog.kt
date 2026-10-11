package com.neethu.orchestrator.agent

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.ToolSpec
import com.neethu.corelib.CameraShot
import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.PromptTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * P0 内置工具目录（docs/agent-feasibility.md §4.4）：工厂函数各返回一个
 * (ToolSpec, ToolExecutor) 对，注册方决定挂进哪个 ToolRegistry。
 *
 * - [getDeviceTime] / [getCurrentView]：纯本地、无隐私面，AvatarSession 默认注册。
 * - [observeCamera]：依赖相机帧与视觉模型，隐私敏感，由集成方（demo app）
 *   按设置开关 + 视频模式可用性注册/反注册。
 *
 * 全部纯 JVM 可测（时钟/机位/抓帧都是注入的 lambda）。
 */
object AgentToolCatalog {

    private val noArgSchema: kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        put("required", buildJsonArray { })
    }

    /**
     * `get_device_time`：设备墙钟时间。**刻意不走 SkillHost.nowMs()**——那根缝
     * 的语义是开机以来的单调时长（SystemClock.elapsedRealtime，技能节拍用），
     * 不是墙钟；这里直接取 epoch ms，单测注入 [nowMs] 假时钟。
     */
    fun getDeviceTime(
        texts: PromptTexts,
        nowMs: () -> Long = { System.currentTimeMillis() },
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "get_device_time",
            description = texts.toolGetDeviceTimeDescription,
            parametersSchema = noArgSchema,
            systemHint = texts.toolGetDeviceTimeHint,
        )
        val executor = ToolExecutor { _ -> ToolResult(formatDeviceTime(nowMs(), zone, texts.lang)) }
        return spec to executor
    }

    /** `get_current_view`：当前机位（与每轮视角行同源）。 */
    fun getCurrentView(
        texts: PromptTexts,
        activeShot: () -> CameraShot?,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "get_current_view",
            description = texts.toolGetCurrentViewDescription,
            parametersSchema = noArgSchema,
            systemHint = texts.toolGetCurrentViewHint,
        )
        val executor = ToolExecutor { _ ->
            val shot = activeShot()
            ToolResult(shot?.let { "${it.label(texts.lang)} (${it.name})" } ?: texts.toolGetCurrentViewFree)
        }
        return spec to executor
    }

    /**
     * `observe_camera`：抓一帧当前画面 → 用同一 [LlmAdapter] 发一个**独立的**视觉
     * 描述请求（方案 A，docs/agent-feasibility.md §4.4）：provider 无关（tool
     * result 带图的数组形态只有部分端点支持）、成本可控、复用视觉模型门控。
     * [visionConfig] 返回 null = 当前无法看（错误文案回灌，模型口头告知用户）。
     *
     * 取帧缝 [snapshot] 由实现方决定取帧策略（2026-10-11 用户需求「任何模式都能
     * 按需拍照」）：视频模式常驻环直接取帧；其他模式**按需开相机拍一张、用完即
     * 关**（隐私：系统状态栏有相机使用指示，不留常驻）。[lens] 是模型带的可选
     * 偏好（"front"/"back"），实现方自行取舍（如视频模式下不抢用户的机位）。
     */
    fun observeCamera(
        llm: LlmAdapter,
        visionConfig: () -> LlmConfig?,
        snapshot: suspend (lens: String?) -> String?,
        texts: PromptTexts,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "observe_camera",
            description = texts.toolObserveCameraDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "description",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "想从画面里确认什么（一句话）")
                            },
                        )
                        put(
                            "question",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "补充关注点（可选）")
                            },
                        )
                        put(
                            "lens",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "拍摄镜头：front=拍自己（默认）；back=拍周围环境/房间")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("description")) })
            },
            systemHint = texts.toolObserveCameraHint,
        )
        val executor = ToolExecutor { args ->
            val cfg = visionConfig() ?: return@ToolExecutor ToolResult(texts.toolObserveNoVision, isError = true)
            val frame = snapshot(stringArg(args, "lens"))
                ?: return@ToolExecutor ToolResult(texts.toolObserveNoFrame, isError = true)
            val question = stringArg(args, "question") ?: stringArg(args, "description")
            val reply = StringBuilder()
            try {
                llm.streamChat(
                    listOf(
                        ChatMessage(ChatRole.SYSTEM, texts.toolObserveCameraSystem),
                        ChatMessage(ChatRole.USER, texts.toolObserveCameraUser(question), images = listOf(frame)),
                    ),
                    cfg,
                ).collect { ev ->
                    when (ev) {
                        is LlmStreamEvent.TextDelta -> reply.append(ev.text)
                        is LlmStreamEvent.Error -> throw ev.throwable
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@ToolExecutor ToolResult(texts.toolObserveFailed(e.message), isError = true)
            }
            ToolResult(reply.toString().trim().ifEmpty { texts.toolObserveFailed("empty reply") })
        }
        return spec to executor
    }

    /** "2026-10-11 21:34 周日 (UTC+8)" 形态；非整时区带分钟（UTC+5:30）。 */
    internal fun formatDeviceTime(epochMs: Long, zone: ZoneId, lang: Lang): String {
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
        val locale = if (lang == Lang.EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
        val dow = at.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        val offset = at.offset
        val totalMinutes = offset.totalSeconds / 60
        val sign = if (totalMinutes >= 0) "+" else "-"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val offsetText = "UTC$sign$hours" + if (minutes == 0) "" else ":${kotlin.math.abs(minutes)}"
        return "%04d-%02d-%02d %02d:%02d %s (%s)".format(
            locale, at.year, at.monthValue, at.dayOfMonth, at.hour, at.minute, dow, offsetText,
        )
    }

    /** Top-level string field out of an arguments object; null/absent/blank → null. */
    private fun stringArg(argumentsJson: String, key: String): String? = try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as? kotlinx.serialization.json.JsonObject
        (obj?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.contentOrNull()
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        try { content } catch (_: Exception) { null }
}
