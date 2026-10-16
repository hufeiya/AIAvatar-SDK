package com.neethu.aiavatar_sdk

import com.neethu.corelib.AvatarController
import com.neethu.corelib.vfx.VfxAnchor
import com.neethu.corelib.vfx.VfxPresets

/**
 * 单人路径特效工具（P0，docs/vfx-feasibility.md §六）：`ai_cmd fx` 命令族的
 * 处理体——`fx list` / `fx play <preset> [ai_x ai_y ai_z]` / `fx stop <id|all>` /
 * `fx state`。位置用 ai_x/ai_y/ai_z extras（move/walk_to 先例；缺省 = 模型
 * 脚底原点）。P1 扩锚定与团播路由，P2 接 LLM 工具。
 */
internal object FxTools {

    /** `ai_cmd fx <list|play|stop|state> ...`。 */
    fun command(controller: AvatarController, arg: String?, x: Float?, y: Float?, z: Float?): String {
        val t = (arg ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (t.getOrNull(0)?.lowercase()) {
            "list", null -> listLine(controller)
            "play" -> play(controller, t, x, y, z)
            "stop" -> stop(controller, t)
            "state" -> stateLine(controller)
            else -> usage()
        }
    }

    private fun usage() = "usage: fx <list|play <preset> [ai_x ai_y ai_z]|stop <id|all>|state>"

    private fun listLine(controller: AvatarController): String {
        val catalog = VfxPresets.all.values.joinToString(", ") { p ->
            "${p.id}(${p.blending.name.lowercase()}, max=${p.maxParticles}, " +
                (if (p.burst > 0) "burst=${p.burst}" else "rate=${p.rate}") + ")"
        }
        val active = controller.effects.value.joinToString(", ") { "${it.id}:${it.presetId}×${it.activeParticles}" }
        return "presets(${VfxPresets.all.size}): $catalog\nactive: ${active.ifEmpty { "(none)" }}"
    }

    private fun play(
        controller: AvatarController,
        t: List<String>,
        x: Float?,
        y: Float?,
        z: Float?,
    ): String {
        val presetId = t.getOrNull(1)
            ?: return "usage: fx play <preset> (try: fx list)"
        val preset = VfxPresets.find(presetId)
            ?: return "unknown preset '$presetId' (try: fx list)"
        // 缺省锚点 = 角色脚底（asset.root 世界变换实时位置；自由走动后角色
        // 不在原点，锚原点 = 生成在取景框外——2026-10-17 真机首验教训）
        val pos = FloatArray(3)
        val have = controller.avatarWorldPos(pos)
        val ax = x ?: (if (have) pos[0] else 0f)
        val ay = y ?: (if (have) pos[1] else 0f)
        val az = z ?: (if (have) pos[2] else 0f)
        val anchor = VfxAnchor.World(ax, ay, az)
        return controller.playVfx(preset, anchor)?.let { handle ->
            "fx play ${preset.id} ok as ${handle.id} @ ($ax, $ay, $az)"
        } ?: "fx play rejected (renderer not ready or batch full)"
    }

    private fun stop(controller: AvatarController, t: List<String>): String {
        val id = t.getOrNull(1) ?: return "usage: fx stop <id|all>"
        if (id.equals("all", ignoreCase = true)) {
            val n = controller.effects.value.size
            controller.stopAllVfx()
            return "fx stop all ok ($n active)"
        }
        return if (controller.stopVfx(id)) "fx stop $id ok"
        else "no fx $id (try: fx state)"
    }

    private fun stateLine(controller: AvatarController): String {
        val live = controller.vfxSnapshot()
        if (live.isEmpty()) return "fx: (none active)"
        return live.joinToString("\n") { info ->
            "fx ${info.id}: ${info.presetId} ${info.blending.name.lowercase()} " +
                "particles=${info.activeParticles} age=${"%.1f".format(info.ageSeconds)}s"
        }
    }
}
