package com.agentos.shell.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File

/**
 * The neural engine behind [LocalVoice] — sherpa-onnx running the ZipVoice zero-shot cloner. ZipVoice clones
 * at SYNTHESIS time: every call takes the owner's reference clip + its transcript and speaks new text in that
 * voice, so there's no separate "train" step.
 *
 * LATENCY: the engine STREAMS. [speakStreaming] plays audio through an AudioTrack as each chunk is generated,
 * so speech starts in ~1s instead of after the whole clip renders. The OfflineTts handle is heavy to build
 * (loads the ONNX models), so it's created once, reused, and can be [warm]ed ahead of first use. Access is
 * synchronized — the engine isn't reentrant.
 */
internal object VoiceEngine {
    private const val TAG = "SlyOS-VoiceEngine"

    // Tunables (on-device tuning is a one-line change here):
    //  NUM_STEPS — flow-matching steps. 8 = the value that produced clean audio in the first working build
    //  (4 may have been too few and yielded silence); tune down later once output is confirmed good.
    //  SPEED     — 1.0 = natural pace.
    private const val NUM_STEPS = 8
    private const val SPEED = 1.0f

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    private val lock = Any()

    /** True if the sherpa native library actually loaded (AAR present + .so for this ABI). */
    fun runtimeAvailable(): Boolean =
        try { System.loadLibrary("sherpa-onnx-jni"); true } catch (t: Throwable) { false }

    private fun engine(m: LocalVoice.ModelPaths): OfflineTts? {
        tts?.let { return it }
        return synchronized(lock) {
            tts ?: try {
                val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val cfg = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                            tokens = m.tokens.absolutePath,
                            encoder = m.encoder.absolutePath,
                            decoder = m.decoder.absolutePath,
                            vocoder = m.vocoder.absolutePath,
                            dataDir = m.dataDir.absolutePath,
                            lexicon = m.lexicon.absolutePath
                        ),
                        numThreads = cores,
                        provider = "cpu"
                    )
                )
                OfflineTts(assetManager = null, config = cfg).also { tts = it }
            } catch (t: Throwable) { Log.w(TAG, "engine build failed: ${t.message}"); null }
        }
    }

    /** Preload the ONNX models so the FIRST spoken reply doesn't pay the model-load cost. Safe to call early. */
    fun warm(m: LocalVoice.ModelPaths) { try { engine(m) } catch (t: Throwable) {} }

    /** Stop whatever is currently speaking (e.g. the user talks again). */
    fun stop() { synchronized(lock) { try { track?.pause(); track?.flush(); track?.stop() } catch (e: Exception) {} } }

    /** 16-bit PCM AudioTrack (far more universally supported than PCM_FLOAT). Null if it won't initialize. */
    private fun newTrack(sampleRate: Int): AudioTrack? {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = if (min > 0) maxOf(min, sampleRate * 2) else sampleRate * 2   // ~1s of 16-bit mono
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) { Log.w(TAG, "AudioTrack build failed: ${t.message}"); return null }
        if (t.state != AudioTrack.STATE_INITIALIZED) { Log.w(TAG, "AudioTrack not initialized (state=${t.state})"); try { t.release() } catch (e: Exception) {}; return null }
        return t
    }

    /**
     * Speak [text] in the owner's voice, STREAMING 16-bit audio to the speaker as it's generated (low latency).
     * Blocks until playback finishes; call it off the main thread. Returns true only if audio actually played.
     */
    fun speakStreaming(text: String, refSamples: FloatArray, refSampleRate: Int, refText: String,
                       m: LocalVoice.ModelPaths): Boolean {
        val e = engine(m) ?: run { Log.w(TAG, "no engine"); return false }
        val started = System.currentTimeMillis()
        return try {
            stop(); try { track?.release() } catch (ex: Exception) {}
            val sr = try { e.sampleRate() } catch (ex: Exception) { 24000 }
            val t = newTrack(sr) ?: return false     // caller falls back (WAV clone → system TTS)
            track = t; t.play()
            var frames = 0L
            synchronized(lock) {
                val gen = GenerationConfig(speed = SPEED, referenceAudio = refSamples,
                    referenceSampleRate = refSampleRate, referenceText = refText, numSteps = NUM_STEPS)
                e.generateWithConfigAndCallback(text, gen) { chunk ->
                    if (chunk.isNotEmpty()) {
                        val pcm = ShortArray(chunk.size) { i ->
                            (chunk[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                        try { val w = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING); if (w > 0) frames += w } catch (ex: Exception) {}
                    }
                    1   // keep going
                }
            }
            try { t.stop() } catch (ex: Exception) {}
            try { t.release() } catch (ex: Exception) {}
            if (track === t) track = null
            Log.i(TAG, "streamed $frames frames in ${System.currentTimeMillis() - started}ms")
            frames > 0   // false → nothing came out → caller falls back to the WAV clone
        } catch (t: Throwable) { Log.w(TAG, "speakStreaming failed: ${t.message}"); false }
    }

    /** Non-streaming: synth [text] to a WAV file (kept for callers that need a file, e.g. saving). */
    fun synthesize(text: String, refSamples: FloatArray, refSampleRate: Int, refText: String,
                   m: LocalVoice.ModelPaths, out: File): File? {
        val e = engine(m) ?: return null
        return try {
            synchronized(lock) {
                val gen = GenerationConfig(speed = SPEED, referenceAudio = refSamples,
                    referenceSampleRate = refSampleRate, referenceText = refText, numSteps = NUM_STEPS)
                val audio = e.generateWithConfig(text, gen)
                if (audio.samples.isEmpty()) return null
                audio.save(out.absolutePath)
            }
            if (out.exists() && out.length() > 44) out else null
        } catch (t: Throwable) { Log.w(TAG, "synthesize failed: ${t.message}"); null }
    }

    /** Drop the cached engine (e.g. after a re-clone) so the next synth rebuilds it. */
    fun reset() { synchronized(lock) { stop(); try { tts?.release() } catch (e: Exception) {}; tts = null } }
}
