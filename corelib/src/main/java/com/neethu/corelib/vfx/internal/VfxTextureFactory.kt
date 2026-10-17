package com.neethu.corelib.vfx.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.neethu.corelib.vfx.VfxTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/**
 * 特效贴图工厂（docs/vfx-feasibility.md §4.2 第三层承接件）：P0 程序化柔边
 * 圆点（零资产自证）；P1 起接 assets PNG（spark/streak/petal/confetti/flame
 * ——tools/vfx-samples/gen_textures.py 程序化生成，§五）。上传纪律照抄
 * MToonApplier.decodeAndUpload——逐张 flushAndWait 排空（像素缓冲下一帧
 * beginFrame 才消费，同时存活顶爆 Java 堆，坑 11）。
 */
internal object VfxTextureFactory {

    private const val TAG = "VfxTexture"

    /** 程序化贴图边长（像素）；64 够柔边圆点用，省带宽。 */
    private const val PROC_SIZE = 64

    /**
     * 按 [VfxTexture] 描述建 Texture。[readBytes] 供 assets 路径取字节
     * （host 缝注入）；读取/解码失败回退柔边圆点（不炸主流程，§4.2）。
     */
    fun create(engine: Engine, texture: VfxTexture, readBytes: (String) -> ByteArray?): Texture =
        when (texture) {
            is VfxTexture.SoftDot -> softDot(engine)
            is VfxTexture.Asset -> {
                val bytes = readBytes(texture.path)
                val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                if (bitmap == null) {
                    Log.w(TAG, "asset texture load failed: '${texture.path}'; falling back to soft dot")
                    softDot(engine)
                } else {
                    // 非 ARGB_8888（罕见）先转码再走统一上传通道
                    if (bitmap.config != Bitmap.Config.ARGB_8888) {
                        val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        bitmap.recycle()
                        upload(engine, converted, srgb = true).also { converted.recycle() }
                    } else {
                        upload(engine, bitmap, srgb = true).also { bitmap.recycle() }
                    }
                }
            }
        }

    /** 程序化径向渐变圆点：高斯衰减 alpha（边缘 ~1%），白色 RGB。 */
    fun softDot(engine: Engine): Texture {
        val bitmap = Bitmap.createBitmap(PROC_SIZE, PROC_SIZE, Bitmap.Config.ARGB_8888)
        // 直通 alpha（Android 位图默认 premultiplied；shader 侧还要 ×alpha，
        // 预乘贴图会变成 a² 二次衰减）
        bitmap.setPremultiplied(false)
        val pixels = IntArray(PROC_SIZE * PROC_SIZE)
        val half = (PROC_SIZE - 1) / 2f
        for (y in 0 until PROC_SIZE) {
            for (x in 0 until PROC_SIZE) {
                val dx = (x - half) / half
                val dy = (y - half) / half
                val r2 = dx * dx + dy * dy
                val a = if (r2 >= 1f) 0f else exp(-4.5f * r2).coerceIn(0f, 1f)
                val ai = (a * 255f + 0.5f).toInt()
                // ARGB int（直通 alpha）：白 RGB + 计算出的 A
                pixels[y * PROC_SIZE + x] = (ai shl 24) or 0x00FFFFFF
            }
        }
        bitmap.setPixels(pixels, 0, PROC_SIZE, 0, 0, PROC_SIZE, PROC_SIZE)
        return upload(engine, bitmap, srgb = true).also { bitmap.recycle() }
    }

    /** Bitmap → Texture（SRGB8_A8 + mipmap + 逐张 flushAndWait）。 */
    private fun upload(engine: Engine, bitmap: Bitmap, srgb: Boolean): Texture {
        val w = bitmap.width
        val h = bitmap.height
        val levels = max(1.0, floor(ln(max(w, h).toDouble()) / ln(2.0)) + 1.0).toInt()
        val tex = Texture.Builder()
            .width(w).height(h).levels(levels)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .usage(
                Texture.Usage.SAMPLEABLE or Texture.Usage.GEN_MIPMAPPABLE or Texture.Usage.UPLOADABLE,
            )
            .build(engine)
        val px = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.LITTLE_ENDIAN)
        bitmap.copyPixelsToBuffer(px)
        px.rewind()
        tex.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(px, Texture.Format.RGBA, Texture.Type.UBYTE),
        )
        tex.generateMipmaps(engine)
        // 与 MToonApplier 同款：上传在下一帧 beginFrame 才执行，逐张排空
        // 把在途像素缓冲压到 1 个（坑 11）
        engine.flushAndWait()
        return tex
    }
}
