package com.neethu.corelib.vfx.internal

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.VertexBuffer
import com.neethu.corelib.vfx.StageLightParams
import com.neethu.corelib.vfx.VfxMath
import com.neethu.corelib.vfx.StageLightType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 舞台灯架（画质升级 2026-10-17，docs/vfx-quality-upgrade.md §3.2，drei
 * SpotLight 的 Filament 移植）：每盏灯三件套——
 *  1. 真 Filament SPOT/POINT：照亮地板与 PBR 场景（MToon 走材质参数近似，
 *     由 VfxManager 挑贡献最大的一盏经 host 推送）；
 *  2. 可见光束锥 mesh（vfx_beam，OBJECT 域静态几何 + 实体变换逐帧摆动）：
 *     UV 烘焙径向/长度渐隐，任意视角软光柱；
 *  3. 地面光斑 quad（vfx_additive + 柔边圆点贴图）：束底亮斑收束视觉。
 *
 * 扫摆 = position/target 绕锚点竖轴同步旋转（束保持指向）；闪烁 = 强度
 * 多正弦调制（火焰照明）。光束方向约定：几何沿局部 +Z，旋转矩阵把 +Z 对
 * 到 aim；Filament 灯方向与实体变换的关系（builder.direction 持久 vs 变换
 * -Z 跟随）以真机目验定号——若灯斑与光束错位，翻 [AIM_CONVENTION_FLIP]。
 *
 * 零 GC：update 路径全部 scratch 复用；destroy 幂等（坑 13 顺序纪律）。
 * 线程约定：主线程（与 VfxManager 同）。
 */
internal class VfxStageLightRig(
    private val engine: Engine,
    private val scene: Scene,
    private val beamMaterial: Material,
    private val glowMaterial: Material,
    private val glowTexture: Texture,
    lights: List<StageLightParams>,
) {

    private class Lamp(val params: StageLightParams) {
        var lightEntity = 0
        var lightInstance = 0
        var beamEntity = 0
        var beamInstance = 0
        var beamVertexBuffer: VertexBuffer? = null
        var beamIndexBuffer: IndexBuffer? = null
        var beamMaterialInstance: MaterialInstance? = null
        var glowEntity = 0
        var glowInstance = 0
        var glowMaterialInstance: MaterialInstance? = null
        var beamLength = 1f
        var beamRadius = 0.3f
        // 每帧缓存（featured-light 挑选数据源）
        var worldPos = FloatArray(3)
        var aim = FloatArray(3)
        var curIntensity = 0f
    }

    private val lamps: List<Lamp> = lights.map { Lamp(it) }
    private var destroyed = false

    // 每帧 scratch（零 GC）
    private val mat = FloatArray(16)
    private val beamMat = FloatArray(16)
    private val glowMat = FloatArray(16)

    init {
        for (lamp in lamps) {
            buildLight(lamp)
            if (lamp.params.beamVisible && lamp.params.type == StageLightType.SPOT) {
                buildBeam(lamp)
                buildGlow(lamp)
            }
        }
    }

    // ── 构建 ─────────────────────────────────────────────────────────

    private fun buildLight(lamp: Lamp) {
        val p = lamp.params
        if (!p.realLight) return   // 纯光束灯：无实体，MToon 推送照常
        val entity = EntityManager.get().create()
        val builder = when (p.type) {
            StageLightType.SPOT -> {
                val aim = aimOf(p)
                LightManager.Builder(LightManager.Type.SPOT)
                    .color(p.color.r, p.color.g, p.color.b)
                    .intensity(p.intensityCandela)
                    .direction(aim[0], aim[1], aim[2])
                    .spotLightCone(
                        degToRad(p.coneInnerDeg),
                        degToRad(p.coneOuterDeg),
                    )
                    .falloff(p.rangeMeters.coerceAtLeast(0.5f))
                    .castShadows(false)   // 舞台灯不进阴影管线（PCSS 悬崖零牵连）
            }

            StageLightType.POINT -> LightManager.Builder(LightManager.Type.POINT)
                .color(p.color.r, p.color.g, p.color.b)
                .intensity(p.intensityCandela)
                .falloff(p.rangeMeters.coerceAtLeast(0.5f))
        }
        builder.build(engine, entity)
        val tm = engine.transformManager
        lamp.lightInstance = tm.create(entity)
        scene.addEntity(entity)
        lamp.lightEntity = entity
        lamp.aim = aimOf(p)
    }

    /** 光束锥几何：3 环 × 20 段，局部 +Z 轴向，UV=(径向占比, 长度进度)。 */
    private fun buildBeam(lamp: Lamp) {
        val p = lamp.params
        val length = p.beamLengthMeters.takeIf { it > 0.01f }
            ?: run {
                val dx = p.target.x - p.position.x
                val dy = p.target.y - p.position.y
                val dz = p.target.z - p.position.z
                sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.3f)
            }
        lamp.beamLength = length
        val radius = length * tan(degToRad(p.coneOuterDeg)).coerceAtLeast(0.02f)
        lamp.beamRadius = radius

        val segments = BEAM_SEGMENTS
        val ringT = floatArrayOf(0f, 0.55f, 1f)
        val vertexCount = ringT.size * (segments + 1)
        val floats = FloatArray(vertexCount * VfxMath.FLOATS_PER_VERTEX)
        var f = 0
        for (t in ringT) {
            val r = radius * (0.035f + 0.965f * t)
            val z = length * t
            for (s in 0..segments) {
                val ang = 2.0 * Math.PI * s / segments
                floats[f++] = (r * cos(ang)).toFloat()
                floats[f++] = (r * sin(ang)).toFloat()
                floats[f++] = z
                // UV：x=径向占比（r/radius）、y=长度进度
                floats[f++] = 0.035f + 0.965f * t
                floats[f++] = t
                // COLOR 槽占位（beam 材质不读颜色，布局对齐无害）
                floats[f++] = 1f; floats[f++] = 1f; floats[f++] = 1f; floats[f++] = 1f
            }
        }
        val indices = ShortArray((ringT.size - 1) * segments * 6)
        var ii = 0
        for (ring in 0 until ringT.size - 1) {
            for (s in 0 until segments) {
                val a = (ring * (segments + 1) + s).toShort()
                val b = (a + 1).toShort()
                val c = (a + segments + 1).toShort()
                val d = (c + 1).toShort()
                indices[ii++] = a; indices[ii++] = c; indices[ii++] = b
                indices[ii++] = b; indices[ii++] = c; indices[ii++] = d
            }
        }

        val vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, VfxMath.STRIDE_BYTES)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, VfxMath.UV_OFFSET * 4, VfxMath.STRIDE_BYTES)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, VfxMath.COLOR_OFFSET * 4, VfxMath.STRIDE_BYTES)
            .build(engine)
        val fb = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        fb.asFloatBuffer().put(floats)
        vb.setBufferAt(engine, 0, fb)

        val ib = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        val bb = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(indices)
        ib.setBuffer(engine, bb)

        val mi = beamMaterial.createInstance()
        mi.setParameter("color", p.color.r, p.color.g, p.color.b)
        mi.setParameter("intensity", BEAM_INTENSITY)
        // 只画外表面：径向渐隐在外表面完整，背面过绘减半（透明档第一预算）
        mi.setCullingMode(com.google.android.filament.Material.CullingMode.BACK)

        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            // builder 不从顶点数据算 AABB（空盒 = PreconditionPanic，真机首验
            // 23:49 SIGABRT 根因）——必须手动 boundingBox
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .boundingBox(Box(0f, 0f, 0f, 1e4f, 1e4f, 1e4f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, indices.size)
            .material(0, mi)
            .build(engine, entity)
        engine.transformManager.create(entity)
        scene.addEntity(entity)
        lamp.beamEntity = entity
        lamp.beamInstance = engine.transformManager.getInstance(entity)
        lamp.beamVertexBuffer = vb
        lamp.beamIndexBuffer = ib
        lamp.beamMaterialInstance = mi
    }

    /** 地面光斑：单位水平 quad（XZ 平面，中心原点），变换做位置/缩放。 */
    private fun buildGlow(lamp: Lamp) {
        val p = lamp.params
        val half = 0.5f
        val quad = floatArrayOf(
            // 4 顶点：pos(3) uv(2) color(4) —— 顶点色=灯色×基准亮度
            -half, 0f, -half, 0f, 0f, p.color.r, p.color.g, p.color.b, GLOW_ALPHA,
            half, 0f, -half, 1f, 0f, p.color.r, p.color.g, p.color.b, GLOW_ALPHA,
            half, 0f, half, 1f, 1f, p.color.r, p.color.g, p.color.b, GLOW_ALPHA,
            -half, 0f, half, 0f, 1f, p.color.r, p.color.g, p.color.b, GLOW_ALPHA,
        )
        val idx = shortArrayOf(0, 2, 1, 1, 2, 3)

        val vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, VfxMath.STRIDE_BYTES)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, VfxMath.UV_OFFSET * 4, VfxMath.STRIDE_BYTES)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, VfxMath.COLOR_OFFSET * 4, VfxMath.STRIDE_BYTES)
            .build(engine)
        val fb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        fb.asFloatBuffer().put(quad)
        vb.setBufferAt(engine, 0, fb)

        val ib = IndexBuffer.Builder()
            .indexCount(idx.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        val bb = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(idx)
        ib.setBuffer(engine, bb)

        val mi = glowMaterial.createInstance()
        mi.setParameter("mainTexture", glowTexture, GLOW_SAMPLER)
        mi.setParameter("intensity", 1.0f)
        mi.setCullingMode(com.google.android.filament.Material.CullingMode.BACK)

        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .culling(false)   // 同上：builder 空 AABB panic，关剔除
            .castShadows(false)
            .receiveShadows(false)
            .boundingBox(Box(0f, 0f, 0f, 1e4f, 1e4f, 1e4f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, idx.size)
            .material(0, mi)
            .build(engine, entity)
        engine.transformManager.create(entity)
        scene.addEntity(entity)
        lamp.glowEntity = entity
        lamp.glowInstance = engine.transformManager.getInstance(entity)
        lamp.glowMaterialInstance = mi
    }

    // ── 每帧 ─────────────────────────────────────────────────────────

    /**
     * 推进一帧：扫摆角 → 灯位/束/光斑变换与灯方向，闪烁 → 光强。[tSec] 为
     * 灯架存活秒数（VfxManager 传入的特效 age）。
     */
    fun update(tSec: Float, anchorX: Float, anchorY: Float, anchorZ: Float) {
        if (destroyed) return
        for (lamp in lamps) {
            val p = lamp.params
            // 摆动模式（幅度>0）保持灯在锚点前方扇区内；整圆模式 = 连续旋转
            val sweepDeg = if (p.sweepAmplitudeDeg > 0f) {
                p.sweepPhaseDeg + p.sweepAmplitudeDeg *
                    sin((p.sweepSpeedDegPerSec * tSec * Math.PI / 180.0)).toFloat()
            } else {
                p.sweepPhaseDeg + p.sweepSpeedDegPerSec * tSec
            }
            val sweep = degToRad(sweepDeg)
            val cs = cos(sweep); val sn = sin(sweep)
            // position/target 绕锚点竖轴同步旋转（束指向随扫摆保持偏移）
            val px0 = p.position.x; val pz0 = p.position.z
            val tx0 = p.target.x; val tz0 = p.target.z
            val lampX = anchorX + px0 * cs + pz0 * sn
            val lampY = anchorY + p.position.y
            val lampZ = anchorZ - px0 * sn + pz0 * cs
            val tgtX = anchorX + tx0 * cs + tz0 * sn
            val tgtY = anchorY + p.target.y
            val tgtZ = anchorZ - tx0 * sn + tz0 * cs

            // 闪烁强度
            var m = 1f
            if (p.flickerStrength > 0f) {
                val ft = tSec * p.flickerFrequency
                m = 1f - p.flickerStrength * (0.5f + 0.5f * sin(ft * 2f.toFloat() * Math.PI.toFloat() + 0.7f).toFloat()) *
                    (0.75f + 0.25f * sin(ft * 5.3f + 2.1f).toFloat())
            }
            lamp.curIntensity = p.intensityCandela * m

            val aim = aimFromTo(lampX, lampY, lampZ, tgtX, tgtY, tgtZ)
            lamp.worldPos[0] = lampX; lamp.worldPos[1] = lampY; lamp.worldPos[2] = lampZ
            lamp.aim[0] = aim[0]; lamp.aim[1] = aim[1]; lamp.aim[2] = aim[2]

            // 灯实体变换：平移 + 旋转。Filament SPOT 方向与实体变换的约定
            // （builder.direction 持久 vs 变换 ±Z 跟随）以真机目验定号，见类
            // 注释 AIM_CONVENTION_FLIP；光束 mesh 恒用 +Z→aim。
            // ⚠ instance 0 = TransformManager 根：对纯光束灯（无实体）的
            // lightInstance=0 setTransform 会脏整棵层级（真机 30fps 元凶）
            if (lamp.lightInstance != 0) {
                aimBasis(aim, mat, FLIP_Z)
                mat[12] = lampX; mat[13] = lampY; mat[14] = lampZ
                engine.transformManager.setTransform(lamp.lightInstance, mat)
            }

            // 光束变换：同平移同指向，+Z→aim（几何约定不翻转）
            if (lamp.beamEntity != 0) {
                aimBasis(aim, beamMat, 1f)
                beamMat[12] = lampX; beamMat[13] = lampY; beamMat[14] = lampZ
                engine.transformManager.setTransform(lamp.beamInstance, beamMat)
                lamp.beamMaterialInstance?.setParameter("intensity", BEAM_INTENSITY * m)
            }

            // 地面光斑：锚点地面（rel y=0）+ 束径比例缩放——target.y 是照射
            // 高度不是地面，光斑挂 target 会浮空（真机首验 23:51）
            if (lamp.glowEntity != 0) {
                val scale = lamp.beamRadius * 1.8f
                for (k in 0..15) glowMat[k] = 0f
                glowMat[0] = scale; glowMat[5] = scale; glowMat[10] = scale; glowMat[15] = 1f
                glowMat[12] = tgtX; glowMat[13] = anchorY + 0.02f; glowMat[14] = tgtZ
                engine.transformManager.setTransform(lamp.glowInstance, glowMat)
            }

            // 真灯光强闪烁（LightManager 逐实例可写；纯光束灯无实体跳过）
            if (lamp.lightInstance != 0) {
                engine.lightManager.setIntensity(lamp.lightInstance, lamp.curIntensity)
            }
        }
    }

    /**
     * 本架「对角色贡献最大」的一盏（MToon 近似单灯推送的挑选依据）：
     * 贡献 = 光强 × 锥角衰减 × 距离衰减，评估点 = 特效锚点（角色脚底代理）。
     * @return false = 架内无灯。
     */
    fun featuredLight(out: LampOut): Boolean {
        var best: Lamp? = null
        var bestScore = -1f
        for (lamp in lamps) {
            val p = lamp.params
            var dx = out.evalX - lamp.worldPos[0]
            var dy = out.evalY - lamp.worldPos[1]
            var dz = out.evalZ - lamp.worldPos[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.05f)
            dx /= d; dy /= d; dz /= d
            val cosAngle = -(dx * lamp.aim[0] + dy * lamp.aim[1] + dz * lamp.aim[2])
            val coneFall = smoothstep(cosDegToRad(p.coneOuterDeg), cosDegToRad(p.coneInnerDeg), cosAngle)
            val distFall = (1f - d / p.rangeMeters.coerceAtLeast(0.5f)).coerceIn(0f, 1f)
            val score = lamp.curIntensity * coneFall * distFall * distFall
            if (score > bestScore) {
                bestScore = score
                best = lamp
            }
        }
        best ?: return false
        // 折算线性 RGB：illum = I/d² 相对 key 灯基准（74k lux）的比例，×π 对齐
        // MToon lightColor 量纲（defaultLightColor = color×π）
        var dx = out.evalX - best.worldPos[0]
        var dy = out.evalY - best.worldPos[1]
        var dz = out.evalZ - best.worldPos[2]
        val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.05f)
        val illumScale = (best.curIntensity / (d * d) / 74_000f).coerceIn(0f, 3f)
        out.colorR = best.params.color.r * illumScale
        out.colorG = best.params.color.g * illumScale
        out.colorB = best.params.color.b * illumScale
        out.posX = best.worldPos[0]; out.posY = best.worldPos[1]; out.posZ = best.worldPos[2]
        out.dirX = best.aim[0]; out.dirY = best.aim[1]; out.dirZ = best.aim[2]
        out.cosInner = cosDegToRad(best.params.coneInnerDeg)
        out.cosOuter = cosDegToRad(best.params.coneOuterDeg)
        out.range = best.params.rangeMeters
        return true
    }

    /** 幂等销毁：摘实体/灯/束/光斑（材质与贴图归 VfxManager 统一后置）。 */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        for (lamp in lamps) {
            if (lamp.lightEntity != 0) {
                scene.removeEntity(lamp.lightEntity)
                engine.destroyEntity(lamp.lightEntity)
                EntityManager.get().destroy(lamp.lightEntity)
                lamp.lightEntity = 0
            }
            if (lamp.beamEntity != 0) {
                scene.removeEntity(lamp.beamEntity)
                engine.destroyEntity(lamp.beamEntity)
                EntityManager.get().destroy(lamp.beamEntity)
                lamp.beamEntity = 0
            }
            if (lamp.glowEntity != 0) {
                scene.removeEntity(lamp.glowEntity)
                engine.destroyEntity(lamp.glowEntity)
                EntityManager.get().destroy(lamp.glowEntity)
                lamp.glowEntity = 0
            }
            lamp.beamVertexBuffer?.let { engine.destroyVertexBuffer(it) }
            lamp.beamIndexBuffer?.let { engine.destroyIndexBuffer(it) }
            lamp.beamMaterialInstance?.let { engine.destroyMaterialInstance(it) }
            lamp.glowMaterialInstance?.let { engine.destroyMaterialInstance(it) }
            lamp.beamVertexBuffer = null
            lamp.beamIndexBuffer = null
            lamp.beamMaterialInstance = null
            lamp.glowMaterialInstance = null
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────

    private fun aimOf(p: StageLightParams): FloatArray =
        aimFromTo(p.position.x, p.position.y, p.position.z, p.target.x, p.target.y, p.target.z)

    /** MToon featured-light 输出（VfxManager 复用，零分配）。 */
    class LampOut {
        var evalX = 0f; var evalY = 0f; var evalZ = 0f
        var posX = 0f; var posY = 0f; var posZ = 0f
        var dirX = 0f; var dirY = 0f; var dirZ = 0f
        var colorR = 0f; var colorG = 0f; var colorB = 0f
        var cosInner = 0f; var cosOuter = 1f
        var range = 0f
    }

    companion object {
        private const val TAG = "VfxStageLightRig"

        /** 光束锥分段/环数与亮度基准（加色叠两面，强度按此标定）。 */
        private const val BEAM_SEGMENTS = 20
        private const val BEAM_INTENSITY = 0.85f
        private const val GLOW_ALPHA = 0.30f

        /**
         * 灯方向约定开关：true = Filament SPOT 方向跟随实体变换 −Z（旋基 z
         * 列翻转到 −aim）；false = 方向跟随 +Z。光束 mesh 恒 +Z→aim，不受
         * 此开关影响。真机首验定号项——灯斑与光束错位时翻转。
         */
        private const val AIM_CONVENTION_FLIP = false
        private val FLIP_Z: Float get() = if (AIM_CONVENTION_FLIP) -1f else 1f

        private val GLOW_SAMPLER = com.google.android.filament.TextureSampler(
            com.google.android.filament.TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
            com.google.android.filament.TextureSampler.MagFilter.LINEAR,
            com.google.android.filament.TextureSampler.WrapMode.CLAMP_TO_EDGE,
        )

        private fun degToRad(d: Float): Float = (d * Math.PI / 180.0).toFloat()
        private fun cosDegToRad(d: Float): Float = cos(degToRad(d))

        private fun aimFromTo(
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
        ): FloatArray {
            var dx = x1 - x0; var dy = y1 - y0; var dz = z1 - z0
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-8f) return floatArrayOf(0f, -1f, 0f)
            dx /= len; dy /= len; dz /= len
            return floatArrayOf(dx, dy, dz)
        }

        /**
         * 旋基写进 [out] 前 12 元素（列主序 filament mat4）：z 列 = aim×
         * [zFlip]（灯实体可按约定翻转、光束恒 1），x 列 = normalize(up×aim)，
         * y 列 = aim×x。aim≈±Y 退化时借世界 X 兜底。
         */
        private fun aimBasis(aim: FloatArray, out: FloatArray, zFlip: Float) {
            // up = (0,1,0)：x = up × aim = (aim.z, 0, -aim.x)
            val xx = aim[2]; val xz = -aim[0]
            val xl = sqrt(xx * xx + xz * xz)
            val x0: Float; val x1: Float; val x2: Float
            if (xl < 1e-6f) {
                // aim ≈ ±Y：借世界 X
                x0 = 1f; x1 = 0f; x2 = 0f
            } else {
                x0 = xx / xl; x1 = 0f; x2 = xz / xl
            }
            // y = aim × x
            val y0 = aim[1] * x2 - aim[2] * x1
            val y1 = aim[2] * x0 - aim[0] * x2
            val y2 = aim[0] * x1 - aim[1] * x0
            out[0] = x0; out[1] = x1; out[2] = x2; out[3] = 0f
            out[4] = y0; out[5] = y1; out[6] = y2; out[7] = 0f
            out[8] = aim[0] * zFlip; out[9] = aim[1] * zFlip; out[10] = aim[2] * zFlip; out[11] = 0f
            out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
        }

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
