package com.neethu.orchestrator.gesture

import android.util.Log
import com.neethu.corelib.AvatarController

/**
 * Plays LLM-requested gestures (`<act:tag>`) from a configurable
 * [ActionEntry] catalog (docs/ai-layer-handoff.md §7.6). One-shot semantics:
 * each play loads the clip and starts it non-looping; the engine restores the
 * rest pose automatically once the clip has run its full duration.
 *
 * The engine holds a single animation slot, so consecutive tags preempt each
 * other — "last cue wins", the same semantics as camera shots. [open] for
 * test fakes.
 */
open class GestureDriver(private val controller: AvatarController) {

    private var catalog: Map<String, ActionEntry> = emptyMap()

    /** Tag most recently started, or null after a stop / failed play. */
    @Volatile
    var currentTag: String? = null
        private set

    fun setCatalog(entries: List<ActionEntry>) {
        catalog = entries.associateBy { it.tag }
    }

    /** Load and start the clip for [tag]; false when unknown or unloadable. */
    open fun play(tag: String): Boolean {
        val entry = catalog[tag] ?: return false
        val loaded = when {
            entry.assetPath != null -> controller.loadVrmaAnimation(entry.assetPath)
            else -> entry.filePath?.let { controller.loadVrmaAnimationFromFile(it) } ?: false
        }
        if (!loaded) {
            Log.w(TAG, "gesture '$tag' failed to load (${entry.assetPath ?: entry.filePath})")
            return false
        }
        currentTag = tag
        controller.playVrmaAnimation(loop = false)
        return true
    }

    /** Cut the playing gesture and return to the rest pose (session interrupt). */
    open fun stop() {
        currentTag = null
        controller.stopVrmaAnimation()
    }

    companion object {
        private const val TAG = "GestureDriver"
    }
}
