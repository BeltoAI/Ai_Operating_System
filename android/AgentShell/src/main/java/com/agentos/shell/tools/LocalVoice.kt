package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ON-DEVICE VOICE CLONING — free, private, offline, in the OWNER'S voice. No ElevenLabs, no per-user API cost.
 *
 * Engine: sherpa-onnx + ZipVoice (zero-shot). Same UX as before — the owner records the fixed script once
 * (VoiceSampleStore) and taps "Create my cloned voice"; from then on every spoken reply (Home, hold-brain,
 * camera, and later phone calls) plays in their voice, via the shared [VoiceOut] path.
 *
 * How it works:
 *   • MODEL (~110 MB) is downloaded ONCE at runtime (not in the APK) to files/voice/zipvoice/. The .tar.bz2 is
 *     extracted on-device; the 24 kHz vocoder is fetched alongside it.
 *   • CLONE = decode the recorded sample to PCM ([AudioDecode]) and cache it as the reference. ZipVoice is
 *     zero-shot, so cloning is instant — no training. The reference TEXT is the known training script, which
 *     is exactly what ZipVoice needs (reference audio + its transcript).
 *   • SPEAK = [VoiceEngine.synthesize] runs ZipVoice with the cached reference → a WAV the normal speak() plays.
 *
 * Everything fails soft: if the native lib, the model, or the sample is missing, calls return false/null and
 * callers fall back to ElevenLabs / system TTS.
 */
object LocalVoice {
    private const val TAG = "SlyOS-LocalVoice"

    // Real artifacts (pinned). Model archive + separate 24 kHz vocoder, both from sherpa-onnx releases.
    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2"
    private const val VOCODER_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx"

    /** Resolved paths to the model files ZipVoice needs, once downloaded + extracted. */
    data class ModelPaths(val tokens: File, val encoder: File, val decoder: File, val vocoder: File,
                          val dataDir: File, val lexicon: File)

    private fun root(ctx: Context): File = File(ctx.filesDir, "voice").apply { mkdirs() }
    private fun modelDir(ctx: Context): File = File(root(ctx), "zipvoice").apply { mkdirs() }
    private fun refFile(ctx: Context): File = File(root(ctx), "reference.f32")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("slyos_localvoice", Context.MODE_PRIVATE)

    private fun paths(ctx: Context): ModelPaths {
        val d = modelDir(ctx)
        return ModelPaths(
            tokens = File(d, "tokens.txt"),
            encoder = File(d, "encoder.int8.onnx"),
            decoder = File(d, "decoder.int8.onnx"),
            vocoder = File(d, "vocos_24khz.onnx"),
            dataDir = File(d, "espeak-ng-data"),
            lexicon = File(d, "lexicon.txt")
        )
    }

    /** True once the ZipVoice model + vocoder are downloaded and extracted. */
    fun modelReady(ctx: Context): Boolean {
        val p = paths(ctx)
        return p.tokens.exists() && p.encoder.exists() && p.decoder.exists() &&
            p.vocoder.exists() && p.dataDir.isDirectory && p.lexicon.exists()
    }

    /** True once the owner's voice has been cloned (their reference clip is cached). */
    fun hasProfile(ctx: Context): Boolean = refFile(ctx).exists() && refFile(ctx).length() > 1000

    /** Whether the sherpa native engine is actually present in this build (AAR + .so for this ABI). */
    fun enginePresent(): Boolean = VoiceEngine.runtimeAvailable()

    /** The one check callers use — a cloned local voice is ready to speak. Mirrors ElevenLabs.available(). */
    fun available(ctx: Context): Boolean = enginePresent() && modelReady(ctx) && hasProfile(ctx)

    // ── Model download ────────────────────────────────────────────────────────────────────────────

    private fun download(url: String, to: File): Boolean = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20000; readTimeout = 180000
        }
        if (c.responseCode !in 200..299) { c.errorStream?.close(); false }
        else { c.inputStream.use { input -> to.outputStream().use { input.copyTo(it, 128 * 1024) } }; to.length() > 1000 }
    } catch (t: Throwable) { Log.w(TAG, "download ${url.takeLast(40)}: ${t.message}"); false }

    /**
     * Download + extract the engine model once. The archive's top-level folder is stripped so files land
     * directly in files/voice/zipvoice/. Returns true when everything's present. Safe on a background thread.
     */
    fun downloadModel(ctx: Context): Boolean {
        if (modelReady(ctx)) return true
        return try {
            val d = modelDir(ctx)
            // 1) the ZipVoice archive (encoder/decoder/tokens/lexicon/espeak-ng-data)
            if (!File(d, "encoder.int8.onnx").exists()) {
                val tar = File(root(ctx), "zipvoice.tar.bz2")
                if (!download(MODEL_URL, tar)) return false
                extractTarBz2(tar, d)
                tar.delete()
            }
            // 2) the 24 kHz vocoder (separate release asset)
            val voc = File(d, "vocos_24khz.onnx")
            if (!voc.exists() && !download(VOCODER_URL, voc)) return false
            modelReady(ctx)
        } catch (t: Throwable) { Log.w(TAG, "downloadModel: ${t.message}"); false }
    }

    /** Extract a .tar.bz2 into [dest], stripping the single top-level directory the archive is wrapped in. */
    private fun extractTarBz2(archive: File, dest: File) {
        archive.inputStream().buffered().use { fin ->
            BZip2CompressorInputStream(fin).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var e = tar.nextTarEntry
                    while (e != null) {
                        val rel = e.name.substringAfter('/', "")   // strip leading "model-name/"
                        if (rel.isNotBlank()) {
                            val outFile = File(dest, rel)
                            if (e.isDirectory) outFile.mkdirs()
                            else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { tar.copyTo(it, 128 * 1024) }
                            }
                        }
                        e = tar.nextTarEntry
                    }
                }
            }
        }
    }

    // ── Clone + synthesize ────────────────────────────────────────────────────────────────────────

    data class Result(val ok: Boolean, val error: String = "")

    /**
     * THE CLONE STEP (behind the same "Create my cloned voice" button). Ensures the model is present, decodes
     * the recorded sample to PCM, and caches it as the reference. Zero-shot, so this is fast — no training.
     */
    fun createFromSample(ctx: Context): Result {
        if (!VoiceSampleStore.hasSample(ctx)) return Result(false, "Record your voice sample first.")
        if (!enginePresent()) return Result(false, "The on-device voice engine isn't installed in this build.")
        if (!modelReady(ctx) && !downloadModel(ctx)) return Result(false, "Couldn't download the voice model. Check your connection and try again.")
        return try {
            val pcm = AudioDecode.toMonoFloat(VoiceSampleStore.sampleFile(ctx))
                ?: return Result(false, "Couldn't read your recording — try re-recording it.")
            if (pcm.samples.size < pcm.sampleRate) return Result(false, "That clip was too short — record ~15–20 seconds.")
            AudioDecode.writeFloats(refFile(ctx), pcm.samples)
            prefs(ctx).edit()
                .putInt("ref_sr", pcm.sampleRate)
                .putString("ref_text", VoiceSampleStore.TRAINING_SCRIPT)
                .apply()
            VoiceEngine.reset()
            Result(true)
        } catch (t: Throwable) { Result(false, t.message ?: "clone failed") }
    }

    /** Speak [text] in the owner's cloned voice → a WAV the existing speak() plays. Null on any failure. */
    fun synthesize(ctx: Context, text: String): File? {
        if (!available(ctx) || text.isBlank()) return null
        return try {
            val ref = AudioDecode.readFloats(refFile(ctx)) ?: return null
            val sr = prefs(ctx).getInt("ref_sr", 0); if (sr <= 0) return null
            val refText = prefs(ctx).getString("ref_text", VoiceSampleStore.TRAINING_SCRIPT) ?: ""
            val out = File(ctx.cacheDir, "lv_${System.currentTimeMillis()}.wav")
            VoiceEngine.synthesize(ctx, text.take(1000), ref, sr, refText, paths(ctx), out)
        } catch (t: Throwable) { Log.w(TAG, "synthesize: ${t.message}"); null }
    }
}
