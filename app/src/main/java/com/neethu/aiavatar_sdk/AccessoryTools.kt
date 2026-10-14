package com.neethu.aiavatar_sdk

import android.content.Context
import android.util.Log
import com.neethu.aiavatar_sdk.multiavatar.AccessoryCalibrationStore
import com.neethu.aiavatar_sdk.multiavatar.AccessoryCatalog
import com.neethu.aiavatar_sdk.multiavatar.ImportedAccessoryLibrary
import com.neethu.corelib.AvatarController
import com.neethu.corelib.accessory.AccessorySource
import com.neethu.corelib.accessory.AccessorySpec
import java.io.File

/**
 * 单人路径配饰工具（P1.5 桥接的 app 层，docs/accessory-feasibility.md §5.7）：
 * [wiring] 把 sidecar+校准的规格解析器注入 [AvatarController]、
 * [registerBuiltinBindings] 在模型就绪后注册内置动画绑定表（等价团播路径的
 * SlotBootstrap），[command] 是 `ai_cmd accessory` 命令族的处理体（语义与
 * 团播 STAGE_CMD 的 `acc` 逐条对齐，少掉 slot 维度）。
 */
internal object AccessoryTools {

    private const val TAG = "AccessoryTools"

    /** 最近一次 attach 成功的规格（`accessory cal` 的数据源，同 acc cal）。 */
    private val lastAttachSpec = LinkedHashMap<String, AccessorySpec>()

    /**
     * 注入规格解析器（动画联动自动挂载用；幂等，模型加载 effect 每次就绪
     * 都可调）。modelId 取 [AvatarController.currentModelId]（invoke 时读，
     * 跨模型换校准 preset 自动跟随）。
     */
    fun wiring(context: Context, controller: AvatarController) {
        controller.accessorySpecResolver = { accId ->
            val modelId = controller.currentModelId
            val entry = if (modelId == null) null else AccessoryCatalog.find(context, accId)
            if (modelId == null || entry == null) {
                null
            } else {
                AccessoryCatalog.specFor(context, modelId, entry)
            }
        }
    }

    /** 注册内置动画绑定表（bindings.json）；模型重载后需重挂（管理器重建）。 */
    fun registerBuiltinBindings(context: Context, controller: AvatarController): Int {
        var bound = 0
        for (b in AccessoryCatalog.loadBindings(context)) {
            if (controller.bindAccessoryToAnimation(b.accessory, b.animation)) bound++
        }
        Log.i(TAG, "accessory bindings registered: $bound")
        return bound
    }

    /** `ai_cmd accessory <list|attach|detach|show|hide|bind|unbind|bindings|import|cal> ...`。 */
    fun command(context: Context, controller: AvatarController, arg: String?): String {
        val t = (arg ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (t.getOrNull(0)?.lowercase()) {
            "list", null -> listLine(context, controller)
            "attach" -> attach(context, controller, t)
            "detach" -> detach(controller, t)
            "show", "hide" -> visible(controller, t, show = t[0].equals("show", true))
            "bind" -> bind(controller, t)
            "unbind" -> unbind(controller, t)
            "bindings" -> controller.accessoryAnimationBindings.entries
                .joinToString(", ") { (c, a) -> "$a←$c" }
                .ifEmpty { "bindings: (empty)" }
            "import" -> import(context, t)
            "cal" -> cal(context, controller, t)
            else -> usage()
        }
    }

    private fun usage() = "usage: accessory <list|attach <id> [bone] [dx dy dz rx ry rz scale [colliderR]]|" +
        "detach|show|hide <id>|bind|unbind <id> <动画路径>|bindings|import <glb路径>|" +
        "cal [show|clear] <id>>"

    private fun listLine(context: Context, controller: AvatarController): String {
        val all = AccessoryCatalog.all(context)
        val catalog = all.joinToString(", ") { e ->
            "${e.id}(${e.license}, bone=${e.defaultBone}" +
                (if (e.source is AccessorySource.File) ", imported" else "") + ")"
        }
        val bound = AccessoryCatalog.loadBindings(context)
        val worn = controller.accessories.value.joinToString(", ") {
            "${it.id}@${it.bone}" + if (it.visible) "" else "(hidden)"
        }
        return "catalog(${all.size}): $catalog\n" +
            "bindings(${bound.size}): " + bound.joinToString(", ") {
            "${it.accessory}←${it.animation.substringAfterLast('/')}"
        } + "\nworn: ${worn.ifEmpty { "(none)" }} model=${controller.currentModelId ?: "-"}"
    }

    private fun attach(context: Context, controller: AvatarController, t: List<String>): String {
        val id = t.getOrNull(1)
            ?: return "usage: accessory attach <id> [bone] [dx dy dz rx ry rz scale [colliderR]]"
        val entry = AccessoryCatalog.find(context, id)
            ?: return "unknown accessory '$id' (try: accessory list)"
        val modelId = controller.currentModelId
            ?: return "no model loaded"
        val nums = t.drop(2).mapNotNull { it.toFloatOrNull() }
        if (t.drop(2).count { it.toFloatOrNull() != null } != nums.size) {
            return "offset args must be numbers"
        }
        val boneOverride = t.drop(2).firstOrNull { it.toFloatOrNull() == null }
        // 9 个数字位 = 前 7 挂载偏移 + 第 8 弹簧碰撞体半径（P2-3 实验入口）
        val spec = AccessoryCatalog.specFor(
            context,
            modelId = modelId,
            entry = entry,
            boneOverride = boneOverride,
            positionOverride = nums.take(3).takeIf { it.size == 3 }?.toFloatArray(),
            rotationOverride = nums.drop(3).take(3).takeIf { it.size == 3 }?.toFloatArray(),
            scaleOverride = nums.getOrNull(6),
        ).copy(springColliderRadius = nums.getOrNull(7) ?: 0f)
        return controller.attachAccessory(spec).fold(
            onSuccess = {
                lastAttachSpec[entry.id] = spec
                "accessory attach ${entry.id}@${spec.bone} ok (pos=${spec.position.contentToString()} " +
                    "rot=${spec.rotationEulerYXZ.contentToString()} scale=${spec.scale}" +
                    (if (spec.springColliderRadius > 0f) " colliderR=${spec.springColliderRadius}" else "") + ")"
            },
            onFailure = { "accessory attach failed: ${it.message}" },
        )
    }

    private fun detach(controller: AvatarController, t: List<String>): String {
        val id = t.getOrNull(1) ?: return "usage: accessory detach <id>"
        return if (controller.detachAccessory(id)) "accessory detach $id ok"
        else "no accessory $id"
    }

    private fun visible(controller: AvatarController, t: List<String>, show: Boolean): String {
        val id = t.getOrNull(1) ?: return "usage: accessory show|hide <id>"
        return if (controller.setAccessoryVisible(id, show)) {
            "accessory $id ${if (show) "shown" else "hidden"}"
        } else {
            "no accessory $id"
        }
    }

    private fun bind(controller: AvatarController, t: List<String>): String {
        val id = t.getOrNull(1) ?: return "usage: accessory bind <id> <动画路径...>"
        val clip = t.drop(2).joinToString(" ").trim()
        if (clip.isEmpty()) return "usage: accessory bind <id> <动画路径...>"
        return if (controller.bindAccessoryToAnimation(id, clip)) "accessory bind $id ← $clip ok"
        else "bind rejected"
    }

    private fun unbind(controller: AvatarController, t: List<String>): String {
        val id = t.getOrNull(1) ?: return "usage: accessory unbind <id> <动画路径...>"
        val clip = t.drop(2).joinToString(" ").trim()
        return if (controller.unbindAccessoryFromAnimation(id, clip)) "accessory unbind $id ← $clip ok"
        else "no such binding"
    }

    private fun import(context: Context, t: List<String>): String {
        val raw = t.drop(1).joinToString(" ").trim()
        if (raw.isEmpty()) return "usage: accessory import <glb路径>（adb push 到 app 外部目录后按名引用）"
        val src = resolveExternalFile(context, raw)
            ?: return "no file at '$raw' (push it to ${context.getExternalFilesDir(null)?.absolutePath})"
        val name = ImportedAccessoryLibrary.importFile(context, src)
            ?: return "import failed: not a GLB or unwritable '$raw'"
        return "accessory import ok: $name (id=${name.removeSuffix(".glb")})"
    }

    private fun cal(context: Context, controller: AvatarController, t: List<String>): String {
        val modelId = controller.currentModelId ?: return "no model loaded"
        return when (t.getOrNull(1)?.lowercase()) {
            "clear" -> {
                val id = t.getOrNull(2) ?: return "usage: accessory cal clear <id>"
                val gone = AccessoryCalibrationStore.clear(context, modelId, id)
                "accessory cal clear $id@$modelId = $gone"
            }
            "show" -> {
                val id = t.getOrNull(2) ?: return "usage: accessory cal show <id>"
                val p = AccessoryCalibrationStore.load(context, modelId, id)
                "accessory cal $id@$modelId = ${p ?: "none"}"
            }
            else -> {
                val id = t.getOrNull(1)
                    ?: return "usage: accessory cal <id> [clear|show <id>]"
                val spec = lastAttachSpec[id]
                    ?: return "no attach record for $id (accessory attach first, then accessory cal)"
                AccessoryCalibrationStore.save(
                    context, modelId, id,
                    AccessoryCalibrationStore.Preset(
                        bone = spec.bone,
                        position = spec.position.toList(),
                        rotationEulerYXZ = spec.rotationEulerYXZ.toList(),
                        scale = spec.scale,
                    ),
                ).fold(
                    onSuccess = {
                        "accessory cal $id@$modelId saved: bone=${spec.bone} " +
                            "pos=${spec.position.contentToString()} " +
                            "rot=${spec.rotationEulerYXZ.contentToString()} scale=${spec.scale}"
                    },
                    onFailure = { "accessory cal save failed: ${it.message}" },
                )
            }
        }
    }

    private fun resolveExternalFile(context: Context, arg: String): File? {
        val absolute = File(arg)
        if (absolute.isFile) return absolute
        val external = context.getExternalFilesDir(null) ?: return null
        return File(external, arg.removePrefix("/")).takeIf { it.isFile }
    }
}
