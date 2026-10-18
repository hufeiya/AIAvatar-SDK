package com.neethu.orchestrator.agent

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.ToolSpec
import com.neethu.corelib.AmbiencePreset
import com.neethu.corelib.CameraShot
import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.PromptTexts
import com.neethu.orchestrator.locomotion.LocomotionDriver
import com.neethu.orchestrator.locomotion.MoveAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 内置工具目录（P0：docs/agent-feasibility.md §4.4；P0.5 设备六件：
 * docs/agent-tools-expansion.md §2）：工厂函数各返回一个
 * (ToolSpec, ToolExecutor) 对，注册方决定挂进哪个 ToolRegistry。
 *
 * - [getDeviceTime] / [getCurrentView]：纯本地、无隐私面，AvatarSession 默认注册。
 * - [observeCamera]：依赖相机帧与视觉模型，隐私敏感，由集成方（demo app）
 *   按设置开关 + 视频模式可用性注册/反注册。
 * - P0.5 设备工具（[getDeviceStatus]/[setFlashlight]/[sendHaptic]/
 *   [readClipboard]/[setAmbience]/[takePhoto]）：executor 全依赖 Android API，
 *   由 demo 的 AgentDeviceTools 统一注册；orchestrator 保持纯 JVM 可测
 *   （读数/手电/震动/氛围/拍照全是注入的 lambda）。
 *
 * 全部纯 JVM 可测。schema 的参数 description 沿用 observe_camera 惯例
 * （中文硬编码，弱约束信号）；用户可见文案一律走 [PromptTexts] 双语键。
 */
object AgentToolCatalog {

    private val noArgSchema: kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        put("required", buildJsonArray { })
    }

    /** [sendHaptic] 的白名单（与 schema enum 一致，锁单测）。 */
    internal val HAPTIC_PATTERNS = listOf("heartbeat", "high_five", "purr", "gentle_tap")

    /** [setAmbience] 的白名单（[AmbiencePreset] 枚举名的小写形态，锁单测）。 */
    internal val AMBIENCE_PRESET_NAMES = listOf("studio", "bright", "dim", "spotlight", "dreamy")

    /** [playEffect] 的 `at` 位置参数白名单（语义见 schema description，锁单测）。 */
    internal val EFFECT_AT_OPTIONS = listOf("around_me", "stage_center", "camera_front")

    /**
     * `play_effect`（P2 特效，docs/vfx-feasibility.md §六 P2）：放一段场景级
     * 特效（雪花/篝火/魔法阵/舞台灯等）。id 是 enum 白名单（目录定型，schema
     * 注册时生成一次、执行时再验——同 wear_accessory）。位置三选一：`at`
     * 枚举（我在身边/舞台中心/镜头前）、`position` 显式世界坐标（米，覆盖
     * at）、都不给 = 预设缺省锚（应援棒类自锚手骨）。**为什么是工具不是
     * `<fx:>` 行内标签**：特效是场景级事件不是逐句修饰，工具的 enum 白名单 +
     * 失败回灌链路（坑 26 兜底体系）全部现成，还省一个标签命名空间。
     * [play] 缝返回 null = 成功。
     */
    fun playEffect(
        texts: PromptTexts,
        effectIds: () -> List<String>,
        play: suspend (effect: String, at: String?, position: FloatArray?) -> EffectPlayFailure?,
    ): Pair<ToolSpec, ToolExecutor> {
        val ids = effectIds()
        val spec = ToolSpec(
            name = "play_effect",
            description = texts.toolPlayEffectDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "effect",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                                put("description", "要播放的特效 id（见列表，不要发明）")
                            },
                        )
                        put(
                            "at",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { EFFECT_AT_OPTIONS.forEach { add(JsonPrimitive(it)) } })
                                put("description", "可选位置语义:around_me=我身边,stage_center=舞台中心,camera_front=观众镜头前")
                            },
                        )
                        put(
                            "position",
                            buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "number") })
                                put("description", "可选显式世界坐标 [x,y,z](米,地面为 y=0);给了就覆盖 at")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("effect")) })
            },
            systemHint = texts.toolPlayEffectHint,
        )
        val executor = ToolExecutor { args ->
            val raw = stringArg(args, "effect")?.lowercase().orEmpty()
            val known = effectIds().any { it.equals(raw, ignoreCase = true) }
            val at = stringArg(args, "at")?.lowercase()
            val position = floatArrayArg(args, "position")?.takeIf { it.size >= 3 }?.copyOf(3)
            when {
                raw.isEmpty() || !known -> ToolResult(
                    texts.toolEnumArgInvalid("play_effect", raw, effectIds()),
                    isError = true,
                )
                at != null && at !in EFFECT_AT_OPTIONS -> ToolResult(
                    texts.toolEnumArgInvalid("play_effect.at", at, EFFECT_AT_OPTIONS),
                    isError = true,
                )
                else -> when (play(raw, at, position)) {
                    null -> ToolResult(texts.toolEffectPlayed(raw))
                    EffectPlayFailure.UNKNOWN_EFFECT -> ToolResult(texts.toolEffectUnknown, isError = true)
                    EffectPlayFailure.NOT_AVAILABLE -> ToolResult(texts.toolEffectNotAvailable, isError = true)
                }
            }
        }
        return spec to executor
    }

    /**
     * `stop_effect`（P2）：把当前在播的场景特效全部停掉（「把雪停了」「别放
     * 烟花了」）。无参数——停止是安全动作，不做白名单（停不存在的东西也是
     * 幂等成功）。
     */
    fun stopEffect(
        texts: PromptTexts,
        stop: suspend () -> Unit,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "stop_effect",
            description = texts.toolStopEffectDescription,
            parametersSchema = noArgSchema,
            systemHint = texts.toolStopEffectHint,
        )
        val executor = ToolExecutor { _ ->
            stop()
            ToolResult(texts.toolEffectStopped)
        }
        return spec to executor
    }

    /** Top-level number array field out of an arguments object; null/absent → null. */
    private fun floatArrayArg(argumentsJson: String, key: String): FloatArray? = try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as? kotlinx.serialization.json.JsonObject
        val arr = obj?.get(key) as? kotlinx.serialization.json.JsonArray ?: return null
        FloatArray(arr.size) { i ->
            (arr[i] as? JsonPrimitive)?.contentOrNull()?.toFloatOrNull() ?: Float.NaN
        }.takeIf { arr.isNotEmpty() && it.none { v -> v.isNaN() } }
    } catch (_: Exception) {
        null
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

    /**
     * `get_device_status`（P0.5，docs/agent-tools-expansion.md §2.1）：电量/温度/
     * 充电/音频路由合一的「本体感受」工具。合并为一个无参工具的理由：模型一次
     * 调用拿全（省一轮工具轮），且这组读数合起来才是完整的身体感受概念。
     * [status] 缝由 app 填系统读数，返回 null = 整体读不到（错误文案回灌）。
     * 返回值经 [PromptTexts.toolDeviceStatusResult] 拼成自然语句——模型直接据此
     * 组织口语，不需要二次解读。
     */
    fun getDeviceStatus(
        texts: PromptTexts,
        status: () -> DeviceVitals?,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "get_device_status",
            description = texts.toolGetDeviceStatusDescription,
            parametersSchema = noArgSchema,
            systemHint = texts.toolGetDeviceStatusHint,
        )
        val executor = ToolExecutor { _ ->
            val vitals = status()
                ?: return@ToolExecutor ToolResult(texts.toolDeviceStatusUnavailable, isError = true)
            ToolResult(
                texts.toolDeviceStatusResult(
                    batteryPercent = vitals.batteryPercent,
                    charging = vitals.charging,
                    temperatureC = vitals.temperatureC,
                    audioRoute = vitals.audioRoute,
                ),
            )
        }
        return spec to executor
    }

    /**
     * `set_flashlight`（§2.2）：一个工具带布尔参（不拆 on/off 两个——hint 聚焦、
     * Schema 面 ×0.5）。[torch] 缝返回 null = 成功；[TorchFailure] 由 app 层
     * 归因（无闪光硬件/相机被占用/其他失败），文案在这里统一回灌。
     */
    fun setFlashlight(
        texts: PromptTexts,
        torch: suspend (enabled: Boolean) -> TorchFailure?,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "set_flashlight",
            description = texts.toolSetFlashlightDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "enabled",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "true=打开手电筒,false=关闭")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("enabled")) })
            },
            systemHint = texts.toolSetFlashlightHint,
        )
        val executor = ToolExecutor { args ->
            val enabled = boolArg(args, "enabled")
                ?: return@ToolExecutor ToolResult(texts.toolFlashlightArgError, isError = true)
            when (torch(enabled)) {
                null -> ToolResult(texts.toolFlashlightDone(enabled))
                TorchFailure.NO_FLASH -> ToolResult(texts.toolFlashlightNoFlash, isError = true)
                TorchFailure.CAMERA_IN_USE -> ToolResult(texts.toolFlashlightCameraInUse, isError = true)
                TorchFailure.FAILED -> ToolResult(texts.toolFlashlightFailed, isError = true)
            }
        }
        return spec to executor
    }

    /**
     * `send_haptic`（§2.3）：四档预设震动（enum 而非自由波形参数——模型不该
     * 设计波形，预设够用且可锁单测）。[haptic] 缝返回 null = 成功。
     */
    fun sendHaptic(
        texts: PromptTexts,
        haptic: suspend (pattern: String) -> HapticFailure?,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "send_haptic",
            description = texts.toolSendHapticDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "pattern",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { HAPTIC_PATTERNS.forEach { add(JsonPrimitive(it)) } })
                                put("description", "heartbeat=心跳(安慰) high_five=击掌(庆祝) purr=呼噜(撒娇) gentle_tap=轻点(提醒)")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
            },
            systemHint = texts.toolSendHapticHint,
        )
        val executor = ToolExecutor { args ->
            val pattern = stringArg(args, "pattern")?.lowercase().orEmpty()
            if (pattern !in HAPTIC_PATTERNS) {
                return@ToolExecutor ToolResult(
                    texts.toolEnumArgInvalid("send_haptic", pattern, HAPTIC_PATTERNS),
                    isError = true,
                )
            }
            when (haptic(pattern)) {
                null -> ToolResult(texts.toolHapticDone(pattern))
                HapticFailure.NO_VIBRATOR -> ToolResult(texts.toolHapticNoVibrator, isError = true)
                HapticFailure.FAILED -> ToolResult(texts.toolHapticFailed, isError = true)
            }
        }
        return spec to executor
    }

    /**
     * `read_clipboard`（§2.4）：唯一「读取用户数据」类工具——注册与否由集成方
     * 的独立开关决定（demo 默认关）。[clipboard] 缝返回 null = 剪贴板空/非文本,
     * 是**正常结果**（模型自然转告），不是 isError。
     */
    fun readClipboard(
        texts: PromptTexts,
        clipboard: suspend () -> String?,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "read_clipboard",
            description = texts.toolReadClipboardDescription,
            parametersSchema = noArgSchema,
            systemHint = texts.toolReadClipboardHint,
        )
        val executor = ToolExecutor { _ -> ToolResult(clipboard() ?: texts.toolClipboardEmpty) }
        return spec to executor
    }

    /**
     * `set_ambience`（§2.5）：舞台氛围预设（enum 白名单校验在这里做，模型写
     * 了别的词回错误文案附合法值）。[ambience] 缝收到的是已校验的
     * [AmbiencePreset]，返回 false = 应用失败（如渲染未就绪）。
     */
    fun setAmbience(
        texts: PromptTexts,
        ambience: suspend (preset: AmbiencePreset) -> Boolean,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "set_ambience",
            description = texts.toolSetAmbienceDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "preset",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { AMBIENCE_PRESET_NAMES.forEach { add(JsonPrimitive(it)) } })
                                put("description", "studio=基准光 bright=明亮日光 dim=昏暗夜话 spotlight=单灯聚光 dreamy=暗夜朦胧")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("preset")) })
            },
            systemHint = texts.toolSetAmbienceHint,
        )
        val executor = ToolExecutor { args ->
            val raw = stringArg(args, "preset")?.lowercase().orEmpty()
            val preset = AmbiencePreset.entries.firstOrNull { it.name.lowercase() == raw }
            when {
                preset == null -> ToolResult(
                    texts.toolEnumArgInvalid("set_ambience", raw, AMBIENCE_PRESET_NAMES),
                    isError = true,
                )
                ambience(preset) -> ToolResult(texts.toolAmbienceApplied(preset.name))
                else -> ToolResult(texts.toolAmbienceFailed, isError = true)
            }
        }
        return spec to executor
    }

    /**
     * `take_photo`（§2.6）：拍「我」（3D 渲染画面）存进相册。核心 API
     * [AvatarController.captureFrame] 已存在；v1 不接摆姿势参数（模型可用既有
     * `<act:>` 标签在自己说话时摆动作，与拍照时序天然同步）。[shoot] 缝返回
     * 保存的文件名，null = 失败（画面没就绪/保存失败）。
     */
    fun takePhoto(
        texts: PromptTexts,
        shoot: suspend () -> String?,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "take_photo",
            description = texts.toolTakePhotoDescription,
            parametersSchema = noArgSchema,
            systemHint = texts.toolTakePhotoHint,
        )
        val executor = ToolExecutor { _ ->
            val name = shoot()
                ?: return@ToolExecutor ToolResult(texts.toolTakePhotoFailed, isError = true)
            ToolResult(texts.toolPhotoSaved(name))
        }
        return spec to executor
    }

    /**
     * `move_to`（P1 自由走动，docs/locomotion-feasibility.md §7.3）：走向一个
     * 锚点。**异步语义**（起步即返回"正在走"）而非阻塞等到达——工具层有超时
     * 上限（默认 10s），正常步速走 2m+ 必超时;到达经 WalkArrived 事件给 UI,
     * 模型拿到起步确认后自然继续说话("这就过去"),与"边走边说"设计对齐。
     * anchor 是 enum 白名单（锚点表定型,同 send_haptic 惯例）。[driver] 缝
     * 惰性取(锚点晚于注册注入时也拿得到);null = 功能未配置。
     */
    fun moveTo(
        texts: PromptTexts,
        driver: () -> LocomotionDriver?,
    ): Pair<ToolSpec, ToolExecutor> {
        val anchorNames = driver()?.anchors?.map { it.name } ?: emptyList()
        val spec = ToolSpec(
            name = "move_to",
            description = texts.toolMoveToDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "anchor",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { anchorNames.forEach { add(JsonPrimitive(it)) } })
                                put("description", "要走向的位置(锚点名,见工具说明)")
                            },
                        )
                        put(
                            "speed",
                            buildJsonObject {
                                put("type", "number")
                                put("description", "行走速度 m/s(可选,默认 1.0,0.2~3.0)")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("anchor")) })
            },
            systemHint = texts.toolMoveToHint,
        )
        val executor = ToolExecutor { args ->
            val d = driver() ?: return@ToolExecutor ToolResult(texts.toolMoveToNoAnchors, isError = true)
            val raw = stringArg(args, "anchor")?.lowercase().orEmpty()
            val anchor = d.find(raw)
            when {
                anchor == null -> ToolResult(
                    texts.toolEnumArgInvalid("move_to", raw, d.anchors.map { it.name }),
                    isError = true,
                )
                else -> {
                    // 可选速度:合法才应用,坏值忽略走默认(不为一格参数炸一轮)
                    stringArg(args, "speed")?.toFloatOrNull()?.let { d.speed = it.coerceIn(0.2f, 3.0f) }
                    when (val outcome = d.walkTo(anchor)) {
                        LocomotionDriver.Outcome.STARTED ->
                            ToolResult(texts.toolMoveToStarted(anchor.labelFor(texts.lang)))
                        LocomotionDriver.Outcome.ALREADY_WALKING ->
                            ToolResult(texts.toolMoveToAlreadyWalking)
                        LocomotionDriver.Outcome.NO_MODEL ->
                            ToolResult(texts.toolMoveToNoModel, isError = true)
                        LocomotionDriver.Outcome.NO_SCENE ->
                            ToolResult(texts.toolMoveToNoScene, isError = true)
                        LocomotionDriver.Outcome.NO_GAIT ->
                            ToolResult(texts.toolMoveToNoGait, isError = true)
                        LocomotionDriver.Outcome.MIMIC_ACTIVE ->
                            ToolResult(texts.toolMoveToMimicActive, isError = true)
                        LocomotionDriver.Outcome.UNKNOWN_ANCHOR ->
                            ToolResult(texts.toolEnumArgInvalid("move_to", raw, d.anchors.map { it.name }), isError = true)
                        LocomotionDriver.Outcome.NO_RENDERER, LocomotionDriver.Outcome.LOAD_FAILED ->
                            ToolResult(texts.toolMoveToFailed, isError = true)
                    }
                }
            }
        }
        return spec to executor
    }

    /**
     * `stop_moving`（P1）：无参停步。与 move_to 配对注册（走不动时停步工具
     * 也没有存在感）。
     */
    fun stopMoving(
        texts: PromptTexts,
        driver: () -> LocomotionDriver?,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "stop_moving",
            description = texts.toolStopMovingDescription,
            parametersSchema = noArgSchema,
            systemHint = texts.toolStopMovingHint,
        )
        val executor = ToolExecutor { _ ->
            val d = driver()
            if (d == null) {
                ToolResult(texts.toolMoveToNoAnchors, isError = true)
            } else {
                d.stopWalking()
                ToolResult(texts.toolStopMovingDone)
            }
        }
        return spec to executor
    }

    /**
     * `wear_accessory`（P2 配饰，docs/accessory-feasibility.md §5.6）：把一件
     * 配饰挂到语义骨骼（「戴上帽子」「拿个手机玩玩」）。id 是 enum 白名单
     * （目录定型，schema 注册时生成一次、执行时再验——注册晚于目录注入时
     * schema 可能是空表，执行时校验兜底）。[wear] 缝返回 null = 成功。
     */
    fun wearAccessory(
        texts: PromptTexts,
        catalogIds: () -> List<String>,
        wear: suspend (id: String) -> AccessoryWearFailure?,
    ): Pair<ToolSpec, ToolExecutor> {
        val ids = catalogIds()
        val spec = ToolSpec(
            name = "wear_accessory",
            description = texts.toolWearAccessoryDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "id",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                                put("description", "要穿戴的配饰 id（见列表，不要发明）")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("id")) })
            },
            systemHint = texts.toolWearAccessoryHint,
        )
        val executor = ToolExecutor { args ->
            val raw = stringArg(args, "id")?.lowercase().orEmpty()
            val known = catalogIds().any { it.equals(raw, ignoreCase = true) }
            when {
                raw.isEmpty() || !known -> ToolResult(
                    texts.toolEnumArgInvalid("wear_accessory", raw, catalogIds()),
                    isError = true,
                )
                else -> when (wear(raw)) {
                    null -> ToolResult(texts.toolAccessoryWorn(raw))
                    AccessoryWearFailure.ALREADY_WEARING -> ToolResult(texts.toolAccessoryAlreadyWearing)
                    AccessoryWearFailure.NO_MODEL -> ToolResult(texts.toolAccessoryNoModel, isError = true)
                    AccessoryWearFailure.FAILED -> ToolResult(texts.toolAccessoryFailed, isError = true)
                }
            }
        }
        return spec to executor
    }

    /**
     * `remove_accessory`（P2）：摘下一件正戴着的配饰。与 wear_accessory 配对
     * 注册；wornIds 供模型观测（executor 只认身上有的）。[remove] 缝返回
     * null = 成功。
     */
    fun removeAccessory(
        texts: PromptTexts,
        wornIds: () -> List<String>,
        remove: suspend (id: String) -> AccessoryRemoveFailure?,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = "remove_accessory",
            description = texts.toolRemoveAccessoryDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "要摘下的配饰 id（身上正戴着的）")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("id")) })
            },
            systemHint = texts.toolRemoveAccessoryHint,
        )
        val executor = ToolExecutor { args ->
            val raw = stringArg(args, "id")?.lowercase().orEmpty()
            val worn = wornIds().any { it.equals(raw, ignoreCase = true) }
            when {
                raw.isEmpty() || !worn -> ToolResult(texts.toolAccessoryNotWorn, isError = true)
                else -> when (remove(raw)) {
                    null -> ToolResult(texts.toolAccessoryRemoved(raw))
                    AccessoryRemoveFailure.FAILED -> ToolResult(texts.toolAccessoryFailed, isError = true)
                }
            }
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

    /** Top-level boolean field; accepts JSON boolean or "true"/"false" string, absent/null → null. */
    private fun boolArg(argumentsJson: String, key: String): Boolean? = try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as? kotlinx.serialization.json.JsonObject
        (obj?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
    } catch (_: Exception) {
        null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        try { content } catch (_: Exception) { null }
}

/**
 * [AgentToolCatalog.getDeviceStatus] 的缝数据：app 层读系统服务填充（纯 JVM
 * 可测），orchestrator 用 [PromptTexts.toolDeviceStatusResult] 拼成自然语句。
 * 字段允许 null = 该项读不到（跳过或降级「未知」，不编数——部分 OEM 粘性广播
 * temperature 恒 0）。
 */
data class DeviceVitals(
    /** 电量百分比 0-100；null = 粘性广播缺失/字段异常。 */
    val batteryPercent: Int?,
    /** 充电中（含充满）；null = 充电态读不到。 */
    val charging: Boolean?,
    /** 机身温度（摄氏度）；null = OEM 恒 0/读不到。 */
    val temperatureC: Float?,
    /** 归一音频路由键：speaker/earpiece/bluetooth/wired/unknown。 */
    val audioRoute: String,
)

/** [AgentToolCatalog.setFlashlight] 的缝失败归因；null = 成功。 */
enum class TorchFailure {
    /** 设备无闪光灯硬件。 */
    NO_FLASH,

    /** 相机被占用（视频模式/按需拍照期间）——「错误回灌不炸回合」的标准演出。 */
    CAMERA_IN_USE,

    /** 其他失败（CameraAccessException 等）。 */
    FAILED,
}

/** [AgentToolCatalog.sendHaptic] 的缝失败归因；null = 成功。 */
enum class HapticFailure {
    /** 无震动马达（平板常见）。 */
    NO_VIBRATOR,

    /** 其他失败（效果构造/驱动异常）。 */
    FAILED,
}

/** [AgentToolCatalog.wearAccessory] 的缝失败归因；null = 成功（未知名在工具层被白名单拦截）。 */
enum class AccessoryWearFailure {
    /** 该配饰已挂载（重复 wear）。 */
    ALREADY_WEARING,

    /** 没加载 3D 模型。 */
    NO_MODEL,

    /** 其他失败（加载/骨骼解析/渲染层缺失）。 */
    FAILED,
}

/** [AgentToolCatalog.removeAccessory] 的缝失败归因；null = 成功（未穿戴在工具层被拦截）。 */
enum class AccessoryRemoveFailure {
    /** 其他失败（渲染层缺失等）。 */
    FAILED,
}

/** [AgentToolCatalog.playEffect] 的缝失败归因；null = 成功（未知 id 在工具层被白名单拦截）。 */
enum class EffectPlayFailure {
    /** 目录里查无此 id（schema 与执行时目录漂移的兜底）。 */
    UNKNOWN_EFFECT,

    /** 渲染层未就绪（材质编译失败）或对应批容量已满。 */
    NOT_AVAILABLE,
}
