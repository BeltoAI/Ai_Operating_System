package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ON-DEVICE VOICE CLONING — free, private, offline, in the OWNER'S voice. No ElevenLabs, no per-user API cost.
 *
 * Same UX as before: the user records a sample once (VoiceSampleStore) and taps "Create my cloned voice"; from
 * then on every spoken reply (Home, hold-brain, camera, and later phone calls) plays in their voice. This is
 * the local backend for the SAME button and the SAME speak() surfaces.
 *
 * Architecture (mirrors LocalLlm — runtime model download + reflection so it compiles with or without the
 * native runtime present):
 *   1) MODEL — a small zero-shot TTS/voice-conversion model downloaded once to files/voice/ (a few MB → a
 *      couple hundred, per the chosen model). No giant asset baked into the APK.
 *   2) CLONE — [createFromSample] runs the speaker encoder over the recorded sample once and stores the
 *      owner's timbre embedding (files/voice/profile.bin). This is the actual "clone" step.
 *   3) SPEAK — [synthesize] runs base TTS + applies the stored timbre → a WAV/MP3 the existing speak() plays.
 *
 * Runtime: ONNX Runtime Mobile (Maven `com.microsoft.onnxruntime:onnxruntime-android`, native libs bundled).
 * Called by REFLECTION so this file compiles before the dependency + model pipeline are finalized on-device;
 * every entry point degrades gracefully (returns false/null) until the engine is wired and the model present,
 * so callers safely fall back to ElevenLabs / system TTS. The model + inference are finalized with on-device
 * testing (build → install → listen → tune) — that's the iterative part this foundation sets up for.
 */
object LocalVoice {
    private const val TAG = "SlyOS-LocalVoice"

    // The downloadable engine model(s). URL/filename finalized when the model is chosen + converted to ONNX.
    // Kept as data so swapping models later is a one-line change (same as LocalLlm.MODELS).
    data class VoiceModel(val id: String, val name: String, val fileMb: Int, val url: String, val fileName: String)

    // Placeholder catalog — the concrete model (e.g. an OpenVoice-class converter + base TTS, or a fine-tunable
    // VITS) is pinned during the on-device integration pass. Left empty so nothing tries to download a bad URL.
    val MODELS: List<VoiceModel> = emptyList()

    private fun dir(ctx: Context): File = File(ctx.filesDir, "voice").apply { mkdirs() }
    private fun modelFile(ctx: Context, m: VoiceModel): File = File(dir(ctx), m.fileName)
    private fun profileFile(ctx: Context): File = File(dir(ctx), "profile.bin")

    /** True once the engine model(s) are downloaded. */
    fun modelReady(ctx: Context): Boolean = MODELS.isNotEmpty() && MODELS.all { modelFile(ctx, it).let { f -> f.exists() && f.length() > 10_000L } }

    /** True once the owner's voice has been cloned (timbre embedding extracted from their sample). */
    fun hasProfile(ctx: Context): Boolean = profileFile(ctx).let { it.exists() && it.length() > 16L }

    /** The one check callers use — a cloned local voice is ready to speak. Mirrors ElevenLabs.available(). */
    fun available(ctx: Context): Boolean = modelReady(ctx) && hasProfile(ctx) && enginePresent()

    /** Whether the ONNX Runtime native engine is actually on the classpath (added via Gradle in the model pass). */
    private fun enginePresent(): Boolean =
        try { Class.forName("ai.onnxruntime.OrtEnvironment"); true } catch (e: Throwable) { false }

    /** Download the engine model(s) once (background). Returns true when everything's present. */
    fun downloadModel(ctx: Context): Boolean {
        if (MODELS.isEmpty()) return false
        return try {
            for (m in MODELS) {
                val f = modelFile(ctx, m)
                if (f.exists() && f.length() > 10_000L) continue
                val c = (URL(m.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 120000
                }
                if (c.responseCode !in 200..299) { c.errorStream?.close(); return false }
                c.inputStream.use { input -> f.outputStream().use { input.copyTo(it, 64 * 1024) } }  // streamed, no OOM
            }
            modelReady(ctx)
        } catch (t: Throwable) { Log.w(TAG, "downloadModel: ${t.message}"); false }
    }

    /**
     * THE CLONE STEP (behind the same "Create my cloned voice" button). Runs the speaker encoder over the
     * recorded sample and stores the owner's timbre so [synthesize] can speak as them. Returns a result the
     * UI can show. Requires the model downloaded + a recorded sample.
     */
    data class Result(val ok: Boolean, val error: String = "")
    fun createFromSample(ctx: Context): Result {
        if (!VoiceSampleStore.hasSample(ctx)) return Result(false, "Record your voice sample first.")
        if (!enginePresent()) return Result(false, "The on-device voice engine isn't installed in this build yet.")
        if (!modelReady(ctx) && !downloadModel(ctx)) return Result(false, "Couldn't download the voice model.")
        return try {
            // MODEL-INTEGRATION POINT: run the speaker encoder over VoiceSampleStore.sampleFile via ONNX Runtime
            // and write the embedding to profileFile(ctx). Finalized during the on-device tuning pass.
            val emb = VoiceEngine.extractSpeaker(ctx, VoiceSampleStore.sampleFile(ctx), MODELS)
                ?: return Result(false, "Couldn't read your voice from the sample.")
            profileFile(ctx).writeBytes(emb)
            Result(true)
        } catch (t: Throwable) { Result(false, t.message ?: "clone failed") }
    }

    /** Speak [text] in the owner's cloned voice → an audio file the existing speak() plays (MediaPlayer). Null
     *  on any failure so callers fall back to ElevenLabs / system TTS. */
    fun synthesize(ctx: Context, text: String): File? {
        if (!available(ctx) || text.isBlank()) return null
        return try {
            VoiceEngine.synthesize(ctx, text.take(1200), profileFile(ctx), MODELS, File(ctx.cacheDir, "lv_${System.currentTimeMillis()}.wav"))
        } catch (t: Throwable) { Log.w(TAG, "synthesize: ${t.message}"); null }
    }
}
