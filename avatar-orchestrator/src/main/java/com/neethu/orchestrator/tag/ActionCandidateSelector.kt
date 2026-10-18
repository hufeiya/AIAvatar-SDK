package com.neethu.orchestrator.tag

import com.neethu.orchestrator.gesture.ActionEntry

/** Local, deterministic retrieval. Asset names are never repaired by fuzzy matching. */
object ActionCandidateSelector {
    // Exact asset identities only. Integrators can supply their own short semantic tags.
    val semanticActions: Map<String, String> = linkedMapOf(
        "wave" to "aj_waving_gesture", "nod" to "nodding_head_yes",
        "bow" to "quick_informal_bow", "greet" to "greeting_while_standing",
        "salute" to "formal_military_salute", "blow_kiss" to "blowing_a_kiss",
        "thank" to "being_thankful_while_standing", "shake_head" to "shaking_head_no_thoughtfully",
        "shrug" to "shoulder_shrug", "ask" to "asking_a_question",
        "whisper" to "telling_a_secret", "think" to "thinking_while_standing",
        "bashful" to "being_bashful_while_standing", "celebrate" to "celebrating_after_a_win",
        "fist_pump" to "pumping_a_fist", "pout" to "standing_pout_gesture",
        "look_right" to "quickly_looking_to_the_right", "look_around" to "look_around_nervously",
        "stretch_arms" to "stretching_arms_by_pushing_the_elbows",
        "stretch_neck" to "stretching_neck_rolling_side_to_side",
        "jump" to "jump_in_place", "point" to "pointing_with_arm_bent",
        "pat_back" to "patting_on_the_back", "wipe_sweat" to "wiping_sweat_idle",
    )

    data class Selection(val common: Map<String, ActionEntry>, val candidates: Map<String, ActionEntry>) {
        val actions: Map<String, ActionEntry> get() = common + candidates
    }

    private val intents = listOf(
        listOf("跳舞", "舞蹈", "街舞", "舞", "dance", "dancing", "hip hop", "hip-hop", "rumba", "salsa", "探戈") to
            listOf("dance", "dancing", "hip_hop", "rumba", "salsa", "舞蹈"),
        listOf("坐", "躺", "卧", "起身", "站起来", "sit", "seat", "stand up") to
            listOf("sitting", "seated", "reclined", "坐姿"),
        listOf("演奏", "弹琴", "吉他", "钢琴", "小提琴", "guitar", "piano", "violin", "sing", "唱歌") to
            listOf("guitar", "piano", "violin", "singing"),
        listOf("运动", "打拳", "踢", "拳", "球", "kick", "punch", "sport", "golf", "boxing") to
            listOf("kick", "punch", "golf", "boxing", "ball"),
    )

    // Explicit bilingual intent metadata. These rank existing identities; they never create aliases.
    private val specificIntents = listOf(
        listOf("街舞", "嘻哈", "hip hop", "hip-hop") to listOf("hip_hop", "bboy"),
        listOf("伦巴", "rumba") to listOf("rumba"),
        listOf("桑巴", "samba") to listOf("samba"),
        listOf("萨尔萨", "salsa") to listOf("salsa"),
        listOf("地板", "地上", "floor") to listOf("sitting_on_the_floor"),
        listOf("起身", "站起来", "stand up") to listOf("sitting_to_standing", "standing_up"),
        listOf("躺", "卧", "lie down", "recline") to listOf("reclined"),
        listOf("棒球", "挥棒", "baseball") to listOf("baseball"),
        listOf("吉他", "guitar") to listOf("guitar"),
        listOf("钢琴", "piano") to listOf("piano"),
        listOf("小提琴", "violin") to listOf("violin"),
        listOf("鼓掌", "拍手", "clap") to listOf("clap"),
    )

    fun select(catalog: List<ActionEntry>, input: String, previous: String? = null): Selection {
        val byTag = catalog.associateBy { it.tag }
        val common = linkedMapOf<String, ActionEntry>()
        for ((semantic, asset) in semanticActions) {
            (byTag[semantic] ?: byTag[asset])?.let { common[semantic] = it }
        }
        // Small application-defined vocabularies remain usable without keyword metadata.
        if (catalog.size <= 40) catalog.forEach { entry ->
            if (entry !in common.values) common[entry.tag] = entry
        }
        val query = input.lowercase()
        val ranked = linkedMapOf<String, ActionEntry>()
        fun add(entry: ActionEntry?) {
            if (entry != null && entry !in common.values) ranked.putIfAbsent(entry.tag, entry)
        }
        // Carry the last legal action for “again”, even if history was trimmed.
        add(byTag[previous] ?: common[previous])
        val keywords = intents.filter { (words, _) -> words.any { it in query } }.flatMap { it.second }
        val specific = specificIntents.filter { (words, _) -> words.any { it in query } }.flatMap { it.second }
        val queryWords = Regex("[a-z0-9]+|[\\p{IsHan}]{2,}").findAll(query).map { it.value }
            .filter { it.length >= 3 && it !in setOf("please", "again", "the", "can", "you", "and") }.toList()
        val scored = catalog.map { entry ->
            val description = "${entry.tag} ${entry.label} ${entry.category}".lowercase()
            val exactTag = Regex("(?<![a-z0-9_])" + Regex.escape(entry.tag) + "(?![a-z0-9_])").containsMatchIn(query)
            val label = entry.label.lowercase()
            val exactLabel = label.length >= 2 && Regex("(?<![a-z0-9_])" + Regex.escape(label) + "(?![a-z0-9_])").containsMatchIn(query)
            val exact = if (exactTag || exactLabel) 100 else 0
            val score = exact + specific.count { it in description } * 30 +
                keywords.count { it in description } * 10 + queryWords.count { it in description }
            entry to score
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<ActionEntry, Int>> { it.second }.thenBy { it.first.tag })
        scored.forEach { add(it.first) }
        return Selection(common, ranked.entries.take(12).associate { it.toPair() })
    }
}
