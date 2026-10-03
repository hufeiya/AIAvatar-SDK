package com.neethu.orchestrator.audio

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsResult
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Turns [TtsResult] bytes into mono 16-bit PCM.
 *
 * - WAV: parsed directly (16-bit PCM / 8-bit unsigned / 32-bit float; stereo downmixed)
 * - MP3/OGG/unknown containers: decoded through [MediaExtractor] + [MediaCodec]
 * - RAW_PCM_16LE: byte swap only
 */
object PcmDecoder {

    class DecodedPcm(val pcm: ShortArray, val sampleRateHz: Int)

    fun decode(result: TtsResult): DecodedPcm = when (result.format) {
        TtsAudioFormat.RAW_PCM_16LE -> DecodedPcm(bytesToShorts(result.audio), result.rawPcmSampleRate)
        TtsAudioFormat.WAV -> decodeWav(result.audio)
        else -> decodeWithMediaCodec(result.audio)
    }

    // ── WAV ───────────────────────────────────────────────────────────────

    fun decodeWav(bytes: ByteArray): DecodedPcm {
        val buf = ByteReader(bytes)
        require(buf.readString(4) == "RIFF") { "Not a RIFF file" }
        buf.skip(4)
        require(buf.readString(4) == "WAVE") { "Not a WAVE file" }

        var channels = 1
        var sampleRate = 24000
        var bitsPerSample = 16
        var formatTag = 1
        var data: ByteArray? = null

        while (buf.remaining() >= 8) {
            val id = buf.readString(4)
            val size = buf.readIntLe()
            when (id) {
                "fmt " -> {
                    formatTag = buf.readShortLe()
                    channels = buf.readShortLe()
                    sampleRate = buf.readIntLe()
                    buf.skip(4) // byte rate
                    buf.skip(2) // block align
                    bitsPerSample = buf.readShortLe()
                    if (size > 16) buf.skip(size - 16)
                }
                "data" -> {
                    data = bytes.copyOfRange(buf.position, min(buf.position + size, bytes.size))
                    buf.skip(size)
                }
                else -> buf.skip(size)
            }
            if (size % 2 == 1) buf.skip(1) // chunks are word-aligned
        }

        val raw = requireNotNull(data) { "WAV has no data chunk" }
        val pcm = when {
            formatTag == 1 && bitsPerSample == 16 -> bytesToShorts(raw)
            formatTag == 1 && bitsPerSample == 8 -> {
                ShortArray(raw.size) { i -> (((raw[i].toInt() and 0xFF) - 128) shl 8).toShort() }
            }
            formatTag == 3 && bitsPerSample == 32 -> {
                val n = raw.size / 4
                ShortArray(n) { i ->
                    val bits = (raw[i * 4].toInt() and 0xFF) or
                        ((raw[i * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((raw[i * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((raw[i * 4 + 3].toInt() and 0xFF) shl 24)
                    (java.lang.Float.intBitsToFloat(bits).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                }
            }
            else -> error("Unsupported WAV format tag=$formatTag bits=$bitsPerSample")
        }
        return DecodedPcm(downmixToMono(pcm, channels), sampleRate)
    }

    // ── MediaCodec (MP3 / OGG / others) ───────────────────────────────────

    private fun decodeWithMediaCodec(bytes: ByteArray): DecodedPcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(BytesMediaDataSource(bytes))
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            require(audioTrackIndex >= 0) { "No audio track found" }
            extractor.selectTrack(audioTrackIndex)

            val mediaFormat = format!!
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(mediaFormat, null, null, 0)
                codec.start()

                val out = ArrayList<ShortArray>()
                var totalFrames = 0
                var outSampleRate = 24000
                var outChannels = 1
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            val chunk = extractor.readSampleData(inBuf, 0)
                            if (chunk < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, chunk, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            outSampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outIndex >= 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            val shorts = readPcmShorts(outBuf, info.offset, info.size)
                            out += shorts
                            totalFrames += shorts.size / outChannels
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }

                val merged = ShortArray(totalFrames * outChannels)
                var pos = 0
                for (chunk in out) {
                    System.arraycopy(chunk, 0, merged, pos, chunk.size)
                    pos += chunk.size
                }
                return DecodedPcm(downmixToMono(merged, outChannels), outSampleRate)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private class BytesMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = min(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    /** Read little-endian 16-bit PCM directly from a (possibly direct) ByteBuffer. */
    private fun readPcmShorts(buffer: java.nio.ByteBuffer, offset: Int, size: Int): ShortArray {
        val n = size / 2
        val out = ShortArray(n)
        buffer.position(offset)
        buffer.limit(offset + size)
        for (i in 0 until n) {
            val lo = buffer.get().toInt() and 0xFF
            val hi = buffer.get().toInt() and 0xFF
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    // ── shared helpers ────────────────────────────────────────────────────

    fun bytesToShorts(bytes: ByteArray): ShortArray = bytesToShorts(bytes, 0, bytes.size)

    fun bytesToShorts(bytes: ByteArray, from: Int, to: Int): ShortArray {
        val n = (to - from) / 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = ((bytes[from + i * 2].toInt() and 0xFF) or ((bytes[from + i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        return out
    }

    fun downmixToMono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val frames = pcm.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += pcm[f * channels + c]
            out[f] = (acc / channels).toShort()
        }
        return out
    }

    private class ByteReader(private val bytes: ByteArray) {
        var position = 0
            private set

        fun remaining(): Int = bytes.size - position

        fun skip(n: Int) {
            position = min(position + n, bytes.size)
        }

        fun readString(n: Int): String {
            val s = String(bytes, position, min(n, remaining()), Charsets.US_ASCII)
            position += n
            return s
        }

        fun readIntLe(): Int {
            val v = java.nio.ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.LITTLE_ENDIAN).int
            position += 4
            return v
        }

        fun readShortLe(): Int {
            val v = java.nio.ByteBuffer.wrap(bytes, position, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            position += 2
            return v
        }
    }
}
