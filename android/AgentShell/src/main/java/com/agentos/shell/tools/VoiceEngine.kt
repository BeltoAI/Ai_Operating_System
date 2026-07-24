package com.agentos.shell.tools

import android.content.Context
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
 * voice, so there's no separate "train" step. Output is 24 kHz mono, saved as a WAV the normal speak() plays.
 *
 * The OfflineTts handle is heavy to build (loads the ONNX models), so it's created once and reused. All access
 * is synchronized — the engine isn't reentrant.
 */
internal object VoiceEngine {
    private const val TAG = "SlyOS-VoiceEngine"

    // Tunables surfaced here so on-device tuning is a one-line change:
    //  numSteps — flow-matching steps; more = higher quality but slower (8 is a good phone default).
    //  speed    — 1.0 = natural pace.
    private const val NUM_STEPS = 8
    private const val SPEED = 1.0f

    @Volatile private var tts: OfflineTts? = null
    private val lock = Any()

    /** True if the sherpa native library actually loaded (AAR present + .so for this ABI). */
    fun runtimeAvailable(): Boolean =
        try { System.loadLibrary("sherpa-onnx-jni"); true } catch (t: Throwable) { false }

    private fun engine(m: LocalVoice.ModelPaths): OfflineTts? {
        tts?.let { return it }
        return synchronized(lock) {
            tts ?: try {
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
                        numThreads = 2,
                        provider = "cpu"
                    )
                )
                OfflineTts(assetManager = null, config = cfg).also { tts = it }
            } catch (t: Throwable) { Log.w(TAG, "engine build failed: ${t.message}"); null }
        }
    }

    /** Speak [text] in the owner's voice → write a 24 kHz WAV to [out]; return it, or null on failure. */
    fun synthesize(ctx: Context, text: String, refSamples: FloatArray, refSampleRate: Int, refText: String,
                   m: LocalVoice.ModelPaths, out: File): File? {
        val e = engine(m) ?: return null
        return try {
            synchronized(lock) {
                val gen = GenerationConfig(
                    speed = SPEED,
                    referenceAudio = refSamples,
                    referenceSampleRate = refSampleRate,
                    referenceText = refText,
                    numSteps = NUM_STEPS
                )
                val audio = e.generateWithConfig(text, gen)
                if (audio.samples.isEmpty()) return null
                audio.save(out.absolutePath)   // sherpa writes a valid WAV
            }
            if (out.exists() && out.length() > 44) out else null
        } catch (t: Throwable) { Log.w(TAG, "synthesize failed: ${t.message}"); null }
    }

    /** Drop the cached engine (e.g. after a re-clone) so the next synth rebuilds it. */
    fun reset() { synchronized(lock) { try { tts?.release() } catch (e: Exception) {}; tts = null } }
}
