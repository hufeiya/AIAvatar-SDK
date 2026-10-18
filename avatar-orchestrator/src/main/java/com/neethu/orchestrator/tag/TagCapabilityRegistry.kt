package com.neethu.orchestrator.tag

import com.neethu.aiadapter.api.TagCue
import com.neethu.corelib.CameraShot
import com.neethu.orchestrator.face.EmotionAliases
import com.neethu.orchestrator.gesture.ActionEntry

/** One immutable capability snapshot shared by prompt, history and execution. */
class TagCapabilityRegistry(
    val emotions: Set<String>,
    val directExpressions: List<String>,
    val selection: ActionCandidateSelector.Selection,
    val allActions: List<ActionEntry>,
    val cameras: List<Pair<String, String>>,
    val moves: List<Pair<String, String>>,
    val gesturesEnabled: Boolean,
    val cameraEnabled: Boolean,
    val movesEnabled: Boolean,
    val gesturesAvailable: Boolean = true,
    val cameraAvailable: Boolean = true,
    val movesAvailable: Boolean = true,
) {
    enum class Reason { UNKNOWN_NAME, NOT_ALLOWED_THIS_TURN, DISABLED, UNAVAILABLE, RESOURCE_LOAD_FAILED, BUSY, MALFORMED }
    data class Resolution(val cue: TagCue?, val reason: Reason? = null, val aliased: Boolean = false)

    val actions get() = if (gesturesEnabled && gesturesAvailable) selection.actions else emptyMap()
    val version: String get() = listOf(emotions.sorted(), directExpressions, actions.toSortedMap(), cameras, moves,
        gesturesEnabled, cameraEnabled, movesEnabled, gesturesAvailable, cameraAvailable, movesAvailable).toString().hashCode().toUInt().toString(16)

    private fun emotion(name: String): String? {
        val canonical = EmotionAliases.resolve(name)
        if (canonical in emotions) return canonical
        directExpressions.firstOrNull { it.equals(name, true) }?.let { return it }
        return EmotionAliases.resolveEmotion(name)?.takeIf { it in emotions }
    }

    fun resolve(cue: TagCue): Resolution {
        val normalized: TagCue? = when (cue) {
            is TagCue.Emotion -> emotion(cue.name)?.let { cue.copy(name = it) }
            is TagCue.Action -> {
                val name = when {
                    cue.name in actions -> cue.name
                    else -> actions.entries.firstOrNull { it.value.tag == cue.name }?.key
                }
                when {
                    name != null -> cue.copy(name = name)
                    allActions.any { it.tag == cue.name } || cue.name in selection.actions || cue.name in ActionCandidateSelector.semanticActions &&
                        allActions.any { it.tag == ActionCandidateSelector.semanticActions[cue.name] } ->
                        return Resolution(null, when {
                            !gesturesEnabled -> Reason.DISABLED
                            !gesturesAvailable -> Reason.UNAVAILABLE
                            else -> Reason.NOT_ALLOWED_THIS_TURN
                        })
                    // Explicit legacy cross-namespace compatibility only, never fuzzy action repair.
                    else -> EmotionAliases.resolveEmotion(cue.name)?.let { emotion(it) }
                        ?.let { TagCue.Emotion(it, 1f, cue.raw) }
                }
            }
            is TagCue.Camera -> {
                val name = cameraAliases[cue.name] ?: cue.name
                if (name in cameraShots && !cameraEnabled) return Resolution(null, Reason.DISABLED)
                if (name in cameraShots && !cameraAvailable) return Resolution(null, Reason.UNAVAILABLE)
                name.takeIf { n -> cameras.any { it.first == n } }?.let { cue.copy(name = it) }
            }
            is TagCue.Move -> {
                if (moves.any { it.first == cue.name } && !movesEnabled) return Resolution(null, Reason.DISABLED)
                if (moves.any { it.first == cue.name } && !movesAvailable) return Resolution(null, Reason.UNAVAILABLE)
                cue.takeIf { movesEnabled && movesAvailable && moves.any { it.first == cue.name } }
            }
            is TagCue.InvalidControl, is TagCue.ToolCallText -> return Resolution(null, Reason.MALFORMED)
        }
        val canonicalName = when (normalized) {
            is TagCue.Emotion -> normalized.name
            is TagCue.Action -> normalized.name
            is TagCue.Camera -> normalized.name
            is TagCue.Move -> normalized.name
            else -> null
        }
        val raw = when (cue) {
            is TagCue.Emotion -> cue.raw.ifEmpty { "<emo:${cue.name}>" }
            is TagCue.Action -> cue.raw.ifEmpty { "<act:${cue.name}>" }
            is TagCue.Camera -> cue.raw.ifEmpty { "<cam:${cue.name}>" }
            is TagCue.Move -> cue.raw.ifEmpty { "<move:${cue.name}>" }
            else -> ""
        }
        // The extractor lowercases native morphs. Compare the original spelling so an
        // already-correct blinkLeft is not incorrectly counted as an alias repair.
        val rawName = raw.substringAfter(':').substringBefore(':').substringBefore('>').trim().removeSuffix("|").trim()
        val crossNamespace = cue is TagCue.Action && normalized is TagCue.Emotion
        val extraIntensity = cue !is TagCue.Emotion && raw.count { it == ':' } > 1
        return Resolution(normalized, if (normalized == null) Reason.UNKNOWN_NAME else null,
            normalized != null && (rawName != canonicalName || crossNamespace || extraIntensity))
    }

    fun canonicalTag(cue: TagCue): String? = when (val resolved = resolve(cue).cue) {
        is TagCue.Emotion -> "<emo:${resolved.name}:${resolved.intensity}>"
        is TagCue.Action -> "<act:${resolved.name}>"
        is TagCue.Camera -> "<cam:${resolved.name}>"
        is TagCue.Move -> "<move:${resolved.name}>"
        else -> null
    }

    companion object {
        val cameraAliases = mapOf("closeup" to "close_up")
        val cameraShots = mapOf(
            "close_up" to CameraShot.CLOSE_UP, "macro" to CameraShot.MACRO,
            "medium_shot" to CameraShot.MEDIUM_SHOT, "full_shot" to CameraShot.FULL_SHOT,
            "long_shot" to CameraShot.LONG_SHOT, "over_shoulder" to CameraShot.OVER_SHOULDER,
            "follow" to CameraShot.FOLLOW, "group" to CameraShot.GROUP_SHOT,
        )
    }
}
