package com.agentos.shell.tools

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode the owner's recorded voice sample (AAC/.m4a) into mono PCM float samples for the on-device cloner.
 * ZipVoice needs the reference clip as a FloatArray + its sample rate; MediaRecorder gives us compressed AAC,
 * so we run it through MediaCodec once at clone time. Pure Android, no extra deps.
 */
object AudioDecode {
    private const val TAG = "SlyOS-AudioDecode"

    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    /** Decode [file] to mono float PCM in [-1, 1]. Returns null on any failure. */
    fun toMonoFloat(file: File): Pcm? {
        if (!file.exists() || file.length() < 1000) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ArrayList<Float>(sampleRate * 30)
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val total = info.size / 2
                        if (channels <= 1) {
                            for (i in 0 until total) out.add(shorts.get(i) / 32768f)
                        } else {
                            // Downmix interleaved channels to mono.
                            var i = 0
                            while (i + channels <= total) {
                                var acc = 0f
                                for (c in 0 until channels) acc += shorts.get(i + c) / 32768f
                                out.add(acc / channels); i += channels
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // sample rate/channels can restate here; keep the originals (mono downmix already handles it)
                }
            }
            if (out.isEmpty()) null else Pcm(out.toFloatArray(), sampleRate)
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${t.message}"); null
        } finally {
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    /** Persist float PCM as a raw little-endian float32 blob (fast to reload for synthesis). */
    fun writeFloats(file: File, samples: FloatArray) {
        file.outputStream().buffered().use { os ->
            val bb = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putFloat(s)
            os.write(bb.array())
        }
    }

    /** Reload a float32 blob written by [writeFloats]. */
    fun readFloats(file: File): FloatArray? {
        if (!file.exists() || file.length() < 4) return null
        return try {
            val bytes = file.readBytes()
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(fb.remaining()).also { fb.get(it) }
        } catch (e: Exception) { null }
    }
}
