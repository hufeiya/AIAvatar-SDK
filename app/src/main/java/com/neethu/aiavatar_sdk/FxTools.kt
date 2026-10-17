package com.neethu.aiavatar_sdk

import android.content.Context
import com.neethu.aiavatar_sdk.multiavatar.VfxCatalog
import com.neethu.corelib.AvatarController
import com.neethu.corelib.vfx.VfxAnchor
import com.neethu.corelib.vfx.VfxSpec

/**
 * 单人路径特效工具（docs/vfx-feasibility.md §六）：`ai_cmd fx` 命令族的
 * 处理体——`fx list` / `fx play <preset> [ai_x ai_y ai_z] [anchor:<骨名>]` /
 * `fx stop <id|all>` / `fx state`。
 *
 * 锚点优先级（P1）：显式 anchor:<骨名> > 显式 ai_x/ai_y/ai_z > 预设
 * defaultBone（应援模式）> 角色脚底实时位（P0 首验教训：锚原点会生成在
 * 取景框外）。目录走 VfxCatalog 三层合并（内置常量 + presets.json 覆盖）。
 */
internal object FxTools {

    /** `ai_cmd fx <list|play|stop|state> ...`。 */
    fun command(
        context: Context,
        controller: AvatarController,
        arg: String?,
        x: Float?,
        y: Float?,
        z: Float?,
    ): String {
        val t = (arg ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (t.getOrNull(0)?.lowercase()) {
            "list", null -> listLine(context, controller)
            "play" -> play(context, controller, t, x, y, z)
            "stop" -> stop(controller, t)
            "state" -> stateLine(controller)
            else -> usage()
        }
    }

    private fun usage() =
        "usage: fx <list|play <preset> [ai_x ai_y ai_z] [anchor:<骨名>]|stop <id|all>|state>"

    private fun listLine(context: Context, controller: AvatarController): String {
        val presets = VfxCatalog.all(context)
        val catalog = presets.values.joinToString(", ") { p ->
            "${p.id}(${p.blending.name.lowercase()}, max=${p.maxParticles}, " +
                (if (p.burst > 0) "burst=${p.burst}" else "rate=${p.rate}") +
                (p.defaultBone?.let { ", bone=$it" } ?: "") + ")"
        }
        val active = controller.effects.value.joinToString(", ") { "${it.id}:${it.presetId}×${it.activeParticles}" }
        return "presets(${presets.size}): $catalog\nactive: ${active.ifEmpty { "(none)" }}"
    }

    private fun play(
        context: Context,
        controller: AvatarController,
        t: List<String>,
        x: Float?,
        y: Float?,
        z: Float?,
    ): String {
        val presetId = t.getOrNull(1)
            ?: return "usage: fx play <preset> (try: fx list)"
        val preset: VfxSpec = VfxCatalog.find(context, presetId)
            ?: return "unknown preset '$presetId' (try: fx list)"
        // anchor:<骨名> 显式骨锚（含 avatarId 冒号二段：anchor:A:rightHand 形态留团播）
        val boneArg = t.drop(2).firstOrNull {
            it.startsWith("anchor:", true) || it.startsWith("bone:", true)
        }?.substringAfter(':')?.takeIf { it.isNotBlank() }
        val anchor = when {
            boneArg != null -> VfxAnchor.Bone(boneArg)
            x != null || y != null || z != null -> {
                // 显式坐标按轴覆盖角色实时位（未给的轴用角色当前值，P0 口径）
                val pos = FloatArray(3)
                val have = controller.avatarWorldPos(pos)
                VfxAnchor.World(
                    x ?: (if (have) pos[0] else 0f),
                    y ?: (if (have) pos[1] else 0f),
                    z ?: (if (have) pos[2] else 0f),
                )
            }
            else -> defaultAnchor(controller, preset)
        }
        val handle = controller.playVfx(preset, anchor)
        return when {
            handle != null -> "fx play ${preset.id} ok as ${handle.id} @ ${describeAnchor(anchor)}"
            else -> "fx play rejected (renderer not ready or batch full)"
        }
    }

    /**
     * 缺省锚点（设置界面点播与 ai_cmd fx play 共用，P1 口径）：预设带缺省骨
     * → 骨骼锚定（发射器跟随、粒子留世界系）；否则角色脚底实时位（自由走动
     * 后角色不在原点，锚 (0,0,0) 全部生成在取景框外——P0 首验教训）。
     */
    fun defaultAnchor(controller: AvatarController, preset: VfxSpec): VfxAnchor {
        preset.defaultBone?.let { return VfxAnchor.Bone(it) }
        val pos = FloatArray(3)
        val have = controller.avatarWorldPos(pos)
        return VfxAnchor.World(
            if (have) pos[0] else 0f,
            if (have) pos[1] else 0f,
            if (have) pos[2] else 0f,
        )
    }

    private fun describeAnchor(anchor: VfxAnchor): String = when (anchor) {
        is VfxAnchor.World -> "world(%.1f,%.1f,%.1f)".format(anchor.x, anchor.y, anchor.z)
        is VfxAnchor.Bone -> "bone:${anchor.boneName}"
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
                "particles=${info.activeParticles} age=${"%.1f".format(info.ageSeconds)}s" +
                (info.anchor.takeIf { it.isNotEmpty() }?.let { " @$it" } ?: "")
        }
    }
}
