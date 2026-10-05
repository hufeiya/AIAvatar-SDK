package com.neethu.aiavatar_sdk

/**
 * AI-native debug protocol.
 *
 * LLM agents driving the app through `adb shell am start` can attach the
 * extras below to an Intent for [MainActivity]. Commands are queued and
 * executed once the UI is ready, and every command logs a result line under
 * the [AI_LOG_TAG] logcat tag, which the agent reads back with
 * `adb logcat -d -s AIDebug`.
 *
 * Example:
 * ```
 * adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity \
 *     --es ai_cmd load_model --es ai_arg AvatarDone.glb
 * adb logcat -d -s AIDebug
 * ```
 *
 * Full command reference: `docs/ai-debug-intents.md` (also printable via the
 * `help` command).
 */
const val AI_LOG_TAG = "AIDebug"

const val EXTRA_AI_CMD = "ai_cmd"
const val EXTRA_AI_ARG = "ai_arg"
const val EXTRA_AI_X = "ai_x"
const val EXTRA_AI_Y = "ai_y"
const val EXTRA_AI_Z = "ai_z"
const val EXTRA_AI_WEIGHT = "ai_weight"
const val EXTRA_AI_LOOP = "ai_loop"

/** All [EXTRA_AI_*] keys, used to detect (and reject) malformed ai intents. */
val AI_EXTRA_KEYS = listOf(
    EXTRA_AI_CMD, EXTRA_AI_ARG, EXTRA_AI_X, EXTRA_AI_Y, EXTRA_AI_Z,
    EXTRA_AI_WEIGHT, EXTRA_AI_LOOP,
)

/**
 * One parsed debug command.
 *
 * @param name  Command name (`ai_cmd`), case-insensitive.
 * @param arg   String parameter (`ai_arg`): file name, expression name, on/off, ...
 * @param x/y/z Numeric parameters (`ai_x`/`ai_y`/`ai_z`): movement deltas in
 *   world units, or camera gesture distances in screen pixels.
 * @param weight Expression weight 0..1 (`ai_weight`), default 1.
 * @param loop  Whether `play_animation` loops (`ai_loop`), default true.
 */
data class AiDebugCommand(
    val name: String,
    val arg: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val z: Float? = null,
    val weight: Float? = null,
    val loop: Boolean = true,
) {
    /** `true` when at least one numeric parameter was provided. */
    val hasCoordinates: Boolean get() = x != null || y != null || z != null
}

/**
 * Parse a debug command from Intent extras (as a plain map). Returns `null`
 * when no valid [EXTRA_AI_CMD] string is present.
 *
 * Tolerant of LLM mistakes: numeric extras may arrive as strings, booleans
 * for [EXTRA_AI_LOOP] may arrive as "on"/"off"/"true"/"false".
 */
fun parseAiDebugCommand(extras: Map<String, Any?>): AiDebugCommand? {
    val name = (extras[EXTRA_AI_CMD] as? String)?.trim()?.lowercase().orEmpty()
    if (name.isEmpty()) return null
    return AiDebugCommand(
        name = name,
        arg = (extras[EXTRA_AI_ARG] as? String)?.trim()?.ifEmpty { null },
        x = extras[EXTRA_AI_X].asFloat(),
        y = extras[EXTRA_AI_Y].asFloat(),
        z = extras[EXTRA_AI_Z].asFloat(),
        weight = extras[EXTRA_AI_WEIGHT].asFloat()?.coerceIn(0f, 1f),
        loop = extras[EXTRA_AI_LOOP].asBoolean() ?: true,
    )
}

private fun Any?.asFloat(): Float? = when (this) {
    null -> null
    is Number -> toFloat()
    is String -> toFloatOrNull()
    else -> null
}

private fun Any?.asBoolean(): Boolean? = when (this) {
    null -> null
    is Boolean -> this
    is String -> when (lowercase()) {
        "true", "on", "1" -> true
        "false", "off", "0" -> false
        else -> null
    }
    else -> null
}

/** Full command reference, printed to logcat by the `help` command. */
fun aiDebugHelp(): String = """
    |AI debug interface — intent extras: ai_cmd, ai_arg, ai_x, ai_y, ai_z, ai_weight, ai_loop
    |
|Query:
|  help                     Print this help
|  state                    Dump avatar/scene/animation/expression/camera state
|  list <arg>               List assets: models | scenes | animations | expressions | cards
    |Assets:
    |  load_model <arg>         Switch character. arg = file in assets/vrms (.glb/.vrm)
    |  load_scene <arg>         Switch scene (file in assets/scene) or remove it (arg=none)
    |  set_expression <arg>     Apply expression. arg = name, ai_weight = 0..1 (default 1)
    |  clear_expression         Reset face to neutral
    |  play_animation <arg>     Play .vrma from assets/animations. ai_loop = true/false
    |  stop_animation           Stop animation (returns to idle if one is set)
    |  set_idle <arg>           Set .vrma as looping idle (LLM gestures return to it).
    |                           arg = file name fragment; persist across restarts
    |  idle_off                 Clear idle; one-shots fall back to rest pose
    |Motion:
    |  move                     Move avatar in world units. ai_x/ai_y/ai_z = deltas
    |                           (+x right, +y up, +z toward camera)
    |  zoom <ai_x>              Camera dolly. ai_x = pinch pixels, + zooms in / - zooms out
    |  pan                      Camera pan. ai_x/ai_y = screen pixels (two-finger equivalent)
    |  orbit                    Camera orbit. ai_x/ai_y = screen pixels (drag equivalent)
    |  reset_camera             Restore the initial camera pose
    |  camera_shot <arg>        Frame a preset shot with a smooth transition:
    |                           closeup | macro | medium | full | long | over
    |                           | off (release). Video mode defaults to closeup
    |                           on entry and returns to it after each turn
    |  set_drag_mode <arg>      on = touch drags the avatar instead of orbiting the camera
|  open_panel <arg>         Open a bottom panel: none | models | animations |
|                           expressions | scenes | cards | settings
    |  spring_debug <arg>       on/off: 1 Hz spring bone dump to logcat (tag SpringBone)
    |  culling <arg>           on/off: avatar frustum culling. Default OFF (fix for the
    |                           disappearing-eyeballs bug: gltfio uses static bind-pose
    |                           culling boxes, so skinned meshes can be wrongly culled
    |                           at close range once the head turns). ON = old behavior.
    |  breath <arg>            on | off (omit ai_arg = status): procedural breath
    |                           overlay (chest rise + shoulder shrug on the spine/
    |                           shoulder bones, ~15/min). Default ON; speaking
    |                           automatically turns it shallower/faster. A/B use:
    |                           breath off = pre-breath baseline
    |Chat (requires the AI service configured in ⚙️ settings):
    |  send_chat <arg>          Send ai_arg as the user's chat message; the reply
    |                           streams into the subtitle + AIDebug event logs
    |  interrupt_chat           Interrupt the current turn (same as ✕ button)
|  chat_state               Dump chat phase / subtitle length / last error /
|                           resolved provider/model/voice config
|Providers (dual-vendor: SiliconFlow + Volcano; LLM/TTS/ASR dropdowns in ⚙️):
|  set_provider <arg>       Switch LLM provider: siliconflow | volcano
|                           (model list follows; empty model = vendor default)
|  set_tts_provider <arg>   Switch TTS provider independently (unchecks
|                           "same as LLM"): siliconflow | volcano
|  set_llm_model <arg>      Switch the LLM model within the current provider's
|                           catalog (exact id, e.g. Qwen/Qwen3.8-27B)
|Gaze (look-at; default = camera, the avatar watches the user):
|  look_at                  No ai_arg = dump gaze state (target + applied yaw/pitch)
|  look_at <arg>            ai_arg = camera (watch the user/lens) | off (release);
|                           or ai_x/ai_y/ai_z = world-space target point
|                           (works with or without an AI session)
|Contexts (Room-persisted conversation history; needs the AI service configured):
    |  contexts                 List contexts (id prefix, card, message count) + active
    |  new_context              Start a fresh context; previous ones stay selectable
    |  select_context <arg>     Switch to a context by id prefix (see contexts)
|Cards (no AI service needed to import/activate):
|  import_card <arg>        Import + activate a card (SillyTavern PNG/JSON).
|                           arg = absolute path, or path under the app's
|                           external files dir (adb push there first).
|                           Activating rewrites the system prompt and speaks
|                           the greeting when a TTS service is configured.
|  active_card              Dump active card name/spec/system-prompt head
|Voice input (ASR; requires the AI service configured):
|  transcribe <arg>         Transcribe an audio file (arg = absolute path, or
|                           path under the app's external files dir; adb push
|                           there first). Same chain as push-to-talk.
|  voice_record <arg>       Record ai_arg seconds (1..30) of mic with the same
|                           MediaRecorder path as push-to-talk, then transcribe
|  voice_free <arg>         on | off (omit ai_arg to toggle): free-talking
|                           (continuous listening, VAD auto-segments and sends
|                           each sentence) vs push-to-talk; works in voice and
|                           video modes, barge-in interrupts the avatar
|Modes:
    |  set_mode <arg>           Switch input mode: manual | text | voice | video
    |                           (video = camera call: needs a vision-capable LLM,
    |                           rejected with the reason otherwise; enables face
    |                           gaze tracking, draggable PiP preview and per-turn
    |                           camera snapshots attached to messages)
|  show_buttons <arg>       Show/hide all floating buttons: on | off
|                           (omit ai_arg to toggle). Reveals the manual
|                           buttons while staying in text/voice/video mode
|Video call (video mode):
    |  video_camera <arg>       front | back (omit ai_arg to toggle)
    |  video_snapshot           Probe the snapshot ring buffer (bytes/sharpness/
    |                           age) — no request is sent; empty = camera just
    |                           started, wait ~1s
|Other:
    |  screenshot               Save a PNG of the current frame (path is logged here)
    |
    |Example:
    |  adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd state
    |  adb logcat -d -s AIDebug
""".trimMargin()
