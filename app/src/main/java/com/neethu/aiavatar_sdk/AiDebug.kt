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
    |  load_model <arg>         Switch character (builtin assets/vrms or imported
    |                           filesDir/vrms, .glb/.vrm). Context is kept — use
    |                           import_model for the panel semantics (new context)
    |  import_model <arg>       Import a .vrm/.glb file (absolute, or relative to
    |                           the app's external files dir) into filesDir/vrms,
    |                           auto-select it and start a new context
    |  load_scene <arg>         Switch scene (file in assets/scene) or remove it (arg=none)
|  skybox <arg>             360° panorama skybox (image in assets/skybox): <name> |
|                           off (solid color) | list | yaw <deg> (rotate) |
|                           gain <f> (brightness, 1.0 = source image, 0.1–4)
    |  set_expression <arg>     Apply expression. arg = name, ai_weight = 0..1 (default 1)
    |  clear_expression         Reset face to neutral
    |  play_animation <arg>     Play .vrma from assets/animations. ai_loop = true/false
    |  stop_animation           Stop animation (returns to idle if one is set)
    |  accessory <sub>          Accessory family (P1.5): list / attach <id> [bone]
    |                           [dx dy dz rx ry rz scale colliderR] / detach|show|hide
    |                           <id> / bind|unbind <id> <animation path> / bindings /
    |                           import <glb path> / cal <id> | cal profile <id> (P1
    |                           offset-profile calibration: saves to the prop's
    |                           offsetProfile preset, all same-class props inherit)
    |                           / cal [show|clear] <id | profile:name>. Phone
    |                           animations auto-show the phone via the built-in
    |                           binding table (registered on model ready)
    |  fx <sub>                 VFX family (P1): list / play <preset> [ai_x ai_y
    |                           ai_z world meters] [anchor:<bone>] / stop <id|all> /
    |                           state. Catalog = built-ins + presets.json
    |                           (fx list): spark_burst | snow | ring_pulse | rain |
    |                           fire | confetti | petals | lightstick_spark
    |                           (default-anchors rightHand)
    |  set_idle <arg>           Set .vrma as looping idle (LLM gestures return to it).
    |                           arg = file name fragment; persist across restarts
    |  idle_off                 Clear idle; one-shots fall back to rest pose
    |Motion:
    |  move                     Move avatar in world units. ai_x/ai_y/ai_z = deltas
    |                           (+x right, +y up, +z toward camera)
    |  walk_to                  Walk to a world point (free locomotion, needs a
    |                           loaded scene — walk bounds come from its meta).
    |                           ai_x/ai_z = target meters; ai_arg = speed m/s
    |                           (default 1.0; ≥1.9 auto-switches to the run
    |                           gait when configured; gaits auto-configured on
    |                           first use); OR an anchor name: walk_to sofa
    |                           (table = `anchors`; links route via waypoints).
    |                           Pair with camera_shot follow
    |  walk_stop                Stop walking (fades back to idle)
    |  walk_state               Dump locomotion state (phase/pos/heading/target
    |                           + gait slots + fade/overlay/extract switches)
    |  walk_gait                Dump gait slots (walk/run/strafeL/strafeR)
    |  walk_strafe              Sidestep without turning (P2): ai_arg =
    |                           left|right, ai_x = meters (default 1.0),
    |                           ai_z = speed (default 1.0)
    |  walk_fade                Walk start/stop crossfade seconds (P2):
    |                           none=dump | 0..2 (0 = hard cut, P1 behavior)
    |  walk_overlay             Walk + gesture overlay toggle (P2 边走边比划):
    |                           on | off | status (off = stop-and-play, P1)
    |  walk_extract             方案C baked-motion extraction toggle (P2):
    |                           on | off | status (A/B on|off compares)
    |  anchors                  List the session's walk anchors (<move:> table;
    |                           ⇄ marks waypoint links)
    |  interactions             List the scene's interaction anchors (dream
    |                           bedroom furniture spots; interact_with table)
    |  interact <arg>           Position the avatar at an interaction anchor
    |                           (walk → turn → sit/lie contact alignment;
    |                           async — poll `pose_state`)
    |  pose_state               Contact pose + occlusion auto-hide diagnostics
    |  pose_clear               Stand up (leave the sit/lie pose)
    |  occlusion <arg>          Wall auto-hide toggle (on | off | state) —
    |                           hides walls blocking the camera sight line
    |  walk_transit             Walk transition clips (P1 tier-1): none=dump |
    |                           off | start <assets/*.vrma> | stop <assets/*.vrma>
    |                           (one-shot clips; program motion held while the
    |                           start clip plays; measured candidates in
    |                           docs/locomotion-feasibility.md §5.2)
    |  pad                      Gamepad hub commands (analog drive channel —
    |                           same state a physical controller writes,
    |                           docs/gamepad-control-feasibility.md):
    |                           move <左右-1..1> <前后-1..1> [秒] — latch walk
    |                           (camera-relative; +1 forward; seconds = auto-
    |                           release, else max 10s latch); look <yaw>
    |                           <pitch> [秒] — right stick; release — zero all;
    |                           run [on|off] — run gain (A button);
    |                           press a|b — inject button edge; state — dump
    |                           axes/latch/walk; on|off — master switch.
    |                           Numbers also via ai_x/ai_y/ai_z (positional).
    |                           Human priority: a real stick move overrides
    |                           the AI latch on that channel.
    |  scene_offset             Nudge the loaded scene: ai_x/ai_y/ai_z = meters.
    |                           Calibration probe — paste the echoed placement
    |                           json into assets/scene/<scene>.meta.json
    |  scene_rot <ai_x>         Rotate the loaded scene: ai_x = yaw degrees
    |                           (same meta workflow as scene_offset)
    |  zoom <ai_x>              Camera dolly. ai_x = pinch pixels, + zooms in / - zooms out
    |  pan                      Camera pan. ai_x/ai_y = screen pixels (two-finger equivalent)
    |  orbit                    Camera orbit. ai_x/ai_y = screen pixels (drag equivalent)
    |  reset_camera             Restore the initial camera pose
    |  camera_shot <arg>        Frame a preset shot with a smooth transition:
    |                           closeup | macro | medium | full | long | over
    |                           | follow (tracking shot for walking) | off
    |                           (release). Video mode defaults to closeup
    |                           on entry and returns to it after each turn
    |  set_drag_mode <arg>      on = touch drags the avatar instead of orbiting the camera
|  open_panel <arg>         Open a bottom panel: none | models | animations |
|                           expressions | scenes | cards | settings | guide |
|                           guide_cn | guide_intl (guide = the onboarding
|                           sheet shown while no API key is configured; the
|                           variant follows the app language — Chinese ->
|                           SiliconFlow, else -> OpenRouter — and guide_cn /
|                           guide_intl force one for A/B)
    |  spring_debug <arg>       on/off: 1 Hz spring bone dump to logcat (tag SpringBone)
    |  culling <arg>           on/off: avatar frustum culling. Default OFF (fix for the
    |                           disappearing-eyeballs bug: gltfio uses static bind-pose
    |                           culling boxes, so skinned meshes can be wrongly culled
    |                           at close range once the head turns). ON = old behavior.
    |  render_mode <arg>       pbr/mtoon: avatar shading style. MTOON swaps the model's
    |                           MToon materials to three-vrm cel shading + inverted-hull
    |                           outline and forces LinearToneMapping; reloads the model
    |                           (same path as the settings-page render-style toggle).
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
|Skills (docs/rps-skill-feasibility.md; needs a session. P0 voice path +
|                           wants video mode + a vision LLM for the judge turn;
|                           P2 camera path (MediaPipe) judges locally — no
|                           vision LLM needed):
|  skill_status             Dump the RPS skill state machine (state/round/
|                           last hand/user gesture/local verdict/pending frame)
|  skill_exit               Force-exit the active skill (restores the VAD
|                           sentence hangover)
|  rps_throw <arg>          Force a throw: rock | scissor | paper (omit ai_arg
|                           = status). The next utterance / send_chat becomes
|                           the judge turn (local gesture + snapshot already
|                           done) — real-device A/B entry point
|  rps_gesture <arg>        Simulate a camera-confirmed USER gesture: rock |
|                           scissor | paper (omit ai_arg = status). Runs the
|                           P2 local-judging path (local throw + instant
|                           spoken verdict) without a camera — the next
|                           utterance becomes the atmosphere turn
|  look_status              Dump the Look-Here skill state (state/rounds/
|                           score/last point direction/outcome/no-face streak)
|  look_throw <arg>         Force a point: up | down | left | right (omit
|                           ai_arg = status). Starts/restarts a round with a
|                           fixed direction — screen-direction calibration
|                           and no-mic A/B entry point (docs/lookhere-skill-
|                           feasibility.md §5)
|  face_pose                Dump the latest head pose from the FaceLandmarker
|                           lane (yaw/pitch degrees + age; needs the look-here
|                           skill active + video mode + front camera). Turn
|                           your head each way and lock the signs into
|                           LookHereTuning — the sign-calibration probe
|  mimic_status             Dump the Imitate-Me skill + render engine + camera
|                           lane state (docs/mimic-skill-feasibility.md)
|  mimic_pose               Dump the latest solved mirror directions (avatar-
|                           frame unit vectors + age) — the mimic sign-
|                           calibration probe (T-pose: user-left arm reads
|                           Lu=(1,0,0) and drives the avatar's RIGHT arm)
|  mimic_force <arg>        Inject a synthetic pose: tpose | left_up |
|                           right_up | both_up | forward | lean_left |
|                           lean_right | bow | turn_left | off (omit ai_arg
|                           = status). Runs the real solve path without a
|                           camera — watch which side the avatar raises /
|                           which way the torso leans (P2 torso calibration)
|  mimic_face <arg>         on | off (omit ai_arg = status): toggle the P2
|                           expression lane (52 ARKit blendshapes → model
|                           morphs via FaceDriver; real blinks replace auto-
|                           blink, mouth yields to lip-sync while speaking)
    |Agent tools (P0 tool calling, docs/agent-feasibility.md; needs a session):
    |  tools_list               Dump the LLM tool registry + tool-disabled models
    |  tools_call <arg>         Manually execute a tool (bypasses the LLM —
    |                           verifies the tool itself before A/B-ing the
    |                           model's tool choices). ai_arg = "<name> [json]"
    |                           with a space between, e.g. `get_time {}`
    |  activity <sub>           Activity family (P0 prop system, read-only):
    |                           list | state | last — catalog / current activity
    |                           / recent start-stop records. Drive the real chain
    |                           via `tools_call start_activity {"id":...}`
|  observe <arg>            = tools_call observe_camera (snapshot + vision
|                           describe end-to-end; optional ai_arg = the JSON
|                           args, default {}). Needs the camera-observe
|                           toggle + video mode + a vision LLM
|  agent_state              Dump agent tool state (registry / last tool calls /
|                           disabled models / round limit / timeout)
|Long-term memory (entries survive force-stop; panel = settings > Memory):
|  memory_list [arg]        List memory entries (optional content filter),
|                           newest first: "id [category] content (imp= src=)"
|  memory_stats             Totals / category distribution / embedding
|                           coverage / pending extraction window state
|  memory_forget <arg>      Delete one entry by unambiguous id prefix
|  memory_clear             Wipe ALL memories and the pending window
|  memory_config [t] [c]    View (no args) or set extraction thresholds
|                           (t turns OR c chars triggers a request)
|Providers (SiliconFlow + Volcano + OpenRouter + DeepSeek + Bailian; LLM/TTS/ASR dropdowns in ⚙️):
|  set_provider <arg>       Switch LLM provider: siliconflow | volcano |
|                           openrouter | deepseek | bailian (model list
|                           follows; empty model = vendor default)
|  set_tts_provider <arg>   Switch TTS provider independently (unchecks
|                           "same as LLM"): siliconflow | volcano |
|                           openrouter | bailian
|  set_llm_model <arg>      Switch the LLM model within the current provider's
|                           catalog (exact id, e.g. Qwen/Qwen3.8-27B; live
|                           /models ids work too once fetched)
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
|  set_language <arg>       Switch app language: system | zh | en (persisted; the
|                           AI session rebuilds so the prompts follow the language)
|  set_asr <arg>            Switch ASR engine: cloud (OpenAI-compatible, follows
|                           LLM provider, needs that provider's key) | system
|                           (built-in SpeechRecognizer, free no key — Google
|                           on GMS devices, vendor service on CN ROMs; pauses
|                           while the avatar speaks)
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
