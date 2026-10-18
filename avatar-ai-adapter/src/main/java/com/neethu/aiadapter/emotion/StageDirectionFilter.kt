package com.neethu.aiadapter.emotion

/** Suppresses only a finite list of exact stage directions, preserving ordinary parentheses. */
internal class StageDirectionFilter {
    private val pending = StringBuilder()

    fun feed(text: String): String = buildString {
        for (char in text) {
            pending.append(char)
            while (pending.isNotEmpty()) {
                val value = pending.toString()
                if (value in directions) {
                    pending.setLength(0)
                    break
                }
                if (directions.any { it.startsWith(value) }) break
                append(pending[0])
                pending.deleteCharAt(0)
            }
        }
    }

    fun flush(): String = pending.toString().also { reset() }
    fun reset() { pending.setLength(0) }

    companion object {
        private val directions = listOf(
            "低头", "挥手", "点头", "摇头", "鞠躬", "眨眼", "微笑", "害羞地低头", "轻轻点头",
            "waves", "nods", "bows", "blinks", "smiles",
        ).flatMap { listOf("（$it）", "($it)", "*$it*") }.toSet()
    }
}
