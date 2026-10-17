package com.neethu.aiavatar_sdk.multiavatar

import android.content.Context
import android.util.Log
import com.neethu.corelib.vfx.BehaviorParams
import com.neethu.corelib.vfx.ColorKey
import com.neethu.corelib.vfx.EmitterParams
import com.neethu.corelib.vfx.EmitterShape
import com.neethu.corelib.vfx.FlipbookParams
import com.neethu.corelib.vfx.Overflow
import com.neethu.corelib.vfx.ScalarKey
import com.neethu.corelib.vfx.VfxBlending
import com.neethu.corelib.vfx.VfxColor
import com.neethu.corelib.vfx.VfxPresets
import com.neethu.corelib.vfx.VfxSpec
import com.neethu.corelib.vfx.VfxTexture
import com.neethu.corelib.vfx.VfxVec3
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 内置特效目录（P1 docs/vfx-feasibility.md §4.3/§5.2）：`assets/vfx/
 * presets.json` sidecar 目录为权威调参表（8 配方 + 贴图引用），corelib
 * [VfxPresets] 常量打底、本文件条目覆盖同名内置项——三层合并（SDK 常量 →
 * sidecar 调参 → 未来命令行显式参数），越靠外优先级越高。
 *
 * 解析纪律（AccessoryCatalog 同款）：脏条目整条丢弃 + 字段钳制，解析失败
 * = 回落纯内置目录不崩；corelib 保持零 JSON 依赖（解析只在 app 层）。
 */
internal object VfxCatalog {

    // ── sidecar DTO（只描述 JSON 形状；全部可缺省）───────────────────────

    @Serializable
    data class SidecarEmitter(
        val shape: String = "point",
        val center: List<Float>? = null,
        val radius: Float = 0.5f,
        val halfExtents: List<Float>? = null,
        val direction: List<Float>? = null,
        val speed: List<Float>? = null,
        val radialFraction: Float = 0f,
    )

    @Serializable
    data class SidecarFlipbook(val columns: Int = 1, val rows: Int = 1)

    @Serializable
    data class SidecarBehavior(
        val type: String,
        val value: List<Float>? = null,
        val coefficient: Float? = null,
        val axis: String = "x",
        val amplitude: Float? = null,
        val frequency: Float? = null,
        val phase: Float = 0f,
        val strength: Float? = null,
        val scale: Float = 1f,
        val evolution: Float = 1f,
        val keys: List<List<Float>>? = null,
    )

    @Serializable
    data class SidecarEntry(
        val id: String,
        val name: String = id,
        val nameEn: String? = null,
        val texture: String? = null,
        val blending: String = "additive",
        val maxParticles: Int = 256,
        val overflow: String = "reject",
        val burst: Int = 0,
        val rate: Float = 0f,
        val lifetime: List<Float>? = null,
        val size: List<Float>? = null,
        val emitter: SidecarEmitter = SidecarEmitter(),
        val behaviors: List<SidecarBehavior> = emptyList(),
        val startColor: List<Float>? = null,
        val palette: List<List<Float>>? = null,
        val spin: List<Float>? = null,
        val stretchByVelocity: Float = 0f,
        val flipbook: SidecarFlipbook? = null,
        val defaultBone: String? = null,
        val license: String = "CC0",
    )

    @Serializable
    private data class SidecarFile(val presets: List<SidecarEntry>)

    private val json = Json { ignoreUnknownKeys = true }

    /** 贴图路径归一：sidecar 写 vfx/ 下相对路径（textures/x.png）→ assets 全路径。 */
    private fun assetTexture(rel: String?): VfxTexture? {
        val path = rel?.trim()?.removePrefix("/") ?: return null
        if (path.contains("..")) return null // 防目录穿越
        return VfxTexture.Asset("vfx/$path")
    }

    private fun vec3(v: List<Float>?, fallback: VfxVec3): VfxVec3 =
        if (v != null && v.size >= 3) VfxVec3(v[0], v[1], v[2]) else fallback

    private fun rgba(v: List<Float>?, fallback: VfxColor): VfxColor =
        if (v != null && v.size >= 4) VfxColor(v[0], v[1], v[2], v[3]) else fallback

    /** 行为 DTO → BehaviorParams；脏行为（缺参数/未知类型）经 [onError] 上报
     *  后丢弃（坑 21：JVM 单测可达的纯路径不带 android.util.Log，运行时诊断
     *  由 [sidecar] 注入回调）。 */
    private fun behavior(b: SidecarBehavior, onError: (String) -> Unit): BehaviorParams? {
        fun dirty(): BehaviorParams? {
            onError("drop dirty behavior '${b.type}'")
            return null
        }
        return when (b.type.lowercase()) {
            "gravity" -> b.value?.takeIf { it.size >= 3 }?.let {
                BehaviorParams.Gravity(VfxVec3(it[0], it[1], it[2]))
            } ?: dirty()

            "drag" -> b.coefficient?.let { BehaviorParams.Drag(it) } ?: dirty()

            "sway" -> if (b.amplitude == null || b.frequency == null) dirty()
            else BehaviorParams.Sway(b.axis.lowercase(), b.amplitude, b.frequency, b.phase)

            "turbulence" -> if (b.strength == null) dirty()
            else BehaviorParams.Turbulence(b.strength, b.scale, b.evolution)

            "color_over_life" -> {
                val keys = b.keys?.takeIf { it.isNotEmpty() }
                    ?.mapNotNull { k -> if (k.size >= 5) ColorKey(k[0], VfxColor(k[1], k[2], k[3], k[4])) else null }
                    .orEmpty()
                if (keys.isEmpty()) dirty() else BehaviorParams.ColorOverLife(keys)
            }

            "size_over_life" -> {
                val keys = b.keys?.takeIf { it.isNotEmpty() }
                    ?.mapNotNull { k -> if (k.size >= 2) ScalarKey(k[0], k[1]) else null }
                    .orEmpty()
                if (keys.isEmpty()) dirty() else BehaviorParams.SizeOverLife(keys)
            }

            else -> dirty()
        }
    }

    /** sidecar 条目 → VfxSpec（字段钳制；null = 条目脏，经 [onError] 上报后整条丢弃）。 */
    fun toSpec(e: SidecarEntry, onError: (String) -> Unit = {}): VfxSpec? {
        val id = e.id.trim()
        if (id.isEmpty()) return null
        val shape = when (e.emitter.shape.lowercase()) {
            "point" -> EmitterShape.POINT
            "sphere" -> EmitterShape.SPHERE
            "box" -> EmitterShape.BOX
            "ring" -> EmitterShape.RING
            "cone" -> EmitterShape.CONE
            else -> return null
        }
        val behaviors = e.behaviors.mapNotNull { b -> behavior(b, onError) }
        val lifetime = e.lifetime?.takeIf { it.size >= 2 } ?: listOf(1f, 2f)
        val size = e.size?.takeIf { it.size >= 2 } ?: listOf(0.05f, 0.10f)
        val speed = e.emitter.speed?.takeIf { it.size >= 2 } ?: listOf(0.5f, 1f)
        val spin = e.spin?.takeIf { it.size >= 2 } ?: listOf(0f, 0f)
        return VfxSpec(
            id = id,
            label = e.name,
            blending = if (e.blending.equals("transparent", true)) VfxBlending.TRANSPARENT else VfxBlending.ADDITIVE,
            texture = assetTexture(e.texture) ?: VfxTexture.SoftDot,
            maxParticles = e.maxParticles.coerceIn(1, 2048),
            overflow = if (e.overflow.equals("recycle_oldest", true)) Overflow.RECYCLE_OLDEST else Overflow.REJECT,
            emitter = EmitterParams(
                shape = shape,
                center = vec3(e.emitter.center, VfxVec3.ZERO),
                radius = e.emitter.radius.coerceAtLeast(0f),
                halfExtents = vec3(e.emitter.halfExtents, VfxVec3(1f, 1f, 1f)).let {
                    VfxVec3(it.x.coerceAtLeast(0f), it.y.coerceAtLeast(0f), it.z.coerceAtLeast(0f))
                },
                direction = vec3(e.emitter.direction, VfxVec3.DOWN),
                speedMin = speed[0].coerceAtLeast(0f),
                speedMax = speed[1].coerceAtLeast(speed[0].coerceAtLeast(0f)),
                radialFraction = e.emitter.radialFraction.coerceIn(0f, 2f),
            ),
            behaviors = behaviors,
            burst = e.burst.coerceAtLeast(0),
            rate = e.rate.coerceAtLeast(0f),
            lifetimeMin = lifetime[0].coerceAtLeast(0.01f),
            lifetimeMax = lifetime[1].coerceAtLeast(lifetime[0].coerceAtLeast(0.01f)),
            sizeMin = size[0].coerceAtLeast(0.001f),
            sizeMax = size[1].coerceAtLeast(size[0].coerceAtLeast(0.001f)),
            startColor = rgba(e.startColor, VfxColor.WHITE),
            palette = e.palette?.mapNotNull { if (it.size >= 4) VfxColor(it[0], it[1], it[2], it[3]) else null }
                ?.takeIf { it.isNotEmpty() },
            spinMin = spin[0],
            spinMax = spin[1],
            stretchByVelocity = e.stretchByVelocity.coerceAtLeast(0f),
            flipbook = e.flipbook?.let {
                runCatching { FlipbookParams(it.columns, it.rows) }.getOrNull()
            },
            defaultBone = e.defaultBone?.trim()?.takeIf { it.isNotEmpty() },
            labelEn = e.nameEn?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    // ── 三层合并目录 ─────────────────────────────────────────────────────

    /** sidecar 全量（解析失败 = 空表回落内置，不崩；诊断走 logcat）。 */
    fun sidecar(context: Context): List<VfxSpec> = try {
        val text = context.assets.open("vfx/presets.json")
            .use { it.readBytes().toString(Charsets.UTF_8) }
        parse(text) { reason -> Log.w(TAG, reason) }
    } catch (e: Exception) {
        Log.w(TAG, "presets.json parse failed: ${e.message}")
        emptyList()
    }

    /**
     * JSON 文本 → 预设列表（脏条目丢弃；独立出来供 JVM 单测直读 assets 树——
     * 纯路径零 android.util.Log，坑 21；诊断经 [onError] 回调注入）。
     */
    fun parse(text: String, onError: (String) -> Unit = {}): List<VfxSpec> = try {
        json.decodeFromString<SidecarFile>(text).presets.mapNotNull { e ->
            toSpec(e, onError).also { if (it == null) onError("drop dirty sidecar preset '${e.id}'") }
        }
    } catch (e: Exception) {
        onError("presets.json parse failed: ${e.message}")
        emptyList()
    }

    /**
     * 合并目录 = 内置常量打底 + sidecar 覆盖同名项（保留内置声明序在前、
     * sidecar 新增项在后）。
     */
    fun all(context: Context): Map<String, VfxSpec> = merge(VfxPresets.all, sidecar(context))

    /** 合并纯函数（JVM 单测直测：内置打底、sidecar 覆盖同名、新增追加）。 */
    fun merge(builtin: Map<String, VfxSpec>, sidecar: List<VfxSpec>): Map<String, VfxSpec> {
        val merged = linkedMapOf<String, VfxSpec>()
        builtin.forEach { (id, spec) -> merged[id.lowercase()] = spec }
        sidecar.forEach { spec -> merged[spec.id.lowercase()] = spec }
        return merged
    }

    /** 按 id 查找（大小写不敏感）。 */
    fun find(context: Context, id: String): VfxSpec? = all(context)[id.lowercase()]

    private const val TAG = "VfxCatalog"
}
