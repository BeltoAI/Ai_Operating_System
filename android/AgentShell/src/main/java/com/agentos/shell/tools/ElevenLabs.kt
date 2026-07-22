package com.agentos.shell.tools

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * P6 (paid add-on) — cloned-voice text-to-speech via the user's OWN ElevenLabs key. This is the opt-in
 * "call your agent in YOUR voice" path; the free default stays on the device's generic on-device TTS.
 * No key is ever shipped — [available] is false until the user pastes their own key + voice id in Settings.
 */
object ElevenLabs {
    fun available(ctx: Context): Boolean =
        MemoryStore.elevenKey(ctx).isNotBlank() && MemoryStore.elevenVoiceId(ctx).isNotBlank()

    data class CloneResult(val ok: Boolean, val voiceId: String = "", val error: String = "")

    /**
     * ACTUALLY CREATE THE CLONE. Uploads the user's recorded voice sample to THEIR ElevenLabs account via
     * Instant Voice Cloning (/v1/voices/add), saves the returned voice id, and from then on every spoken
     * reply (Home, hold-brain, camera, calls) plays in their real voice. The sample is STREAMED (never held
     * whole in memory). Requires the user's own ElevenLabs key. Returns a clear result for the UI.
     *
     * Before this, the recorded sample went nowhere and the "cloned voice" never existed — everything fell
     * back to the generic system voice.
     */
    fun createVoiceFromSample(ctx: Context, name: String = "My SlyOS voice"): CloneResult {
        val key = MemoryStore.elevenKey(ctx)
        if (key.isBlank()) return CloneResult(false, error = "Add your ElevenLabs API key first (Brain → API keys).")
        val sample = VoiceSampleStore.sampleFile(ctx)
        if (!sample.exists() || sample.length() < 2000) return CloneResult(false, error = "Record your voice sample first.")
        return try {
            val boundary = "slyosVoice" + System.currentTimeMillis()
            val nameField = ("--$boundary\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n$name\r\n").toByteArray(Charsets.UTF_8)
            val fileHead = ("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"voice.m4a\"\r\n" +
                "Content-Type: audio/mp4\r\n\r\n").toByteArray(Charsets.UTF_8)
            val tail = ("\r\n--$boundary--\r\n").toByteArray(Charsets.UTF_8)
            val total = nameField.size.toLong() + fileHead.size + sample.length() + tail.size
            val c = (URL("https://api.elevenlabs.io/v1/voices/add").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 120000
                setRequestProperty("xi-api-key", key)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setFixedLengthStreamingMode(total)
            }
            c.outputStream.use { os ->
                os.write(nameField); os.write(fileHead)
                sample.inputStream().use { it.copyTo(os, 64 * 1024) }
                os.write(tail); os.flush()
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val msg = try { org.json.JSONObject(err).optJSONObject("detail")?.optString("message") } catch (e: Exception) { null }
                return CloneResult(false, error = (msg ?: "").ifBlank {
                    if (code == 401) "ElevenLabs rejected the key." else "Couldn't create the voice (HTTP $code). Instant cloning needs a paid ElevenLabs plan."
                })
            }
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            val id = org.json.JSONObject(resp).optString("voice_id")
            if (id.isNotBlank()) { MemoryStore.setElevenVoiceId(ctx, id); CloneResult(true, voiceId = id) }
            else CloneResult(false, error = "ElevenLabs didn't return a voice id.")
        } catch (t: Throwable) { CloneResult(false, error = t.message ?: "network error") }
    }

    /** Synthesize [text] to a temp MP3 file; returns the file (to play with MediaPlayer) or null on failure. */
    fun synthesize(ctx: Context, text: String): File? {
        val key = MemoryStore.elevenKey(ctx); val voice = MemoryStore.elevenVoiceId(ctx)
        if (key.isBlank() || voice.isBlank() || text.isBlank()) return null
        return try {
            val body = org.json.JSONObject()
                .put("text", text.take(1200))
                .put("model_id", "eleven_turbo_v2_5")   // low-latency model, good for a live call
                .toString()
            val c = (URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("xi-api-key", key)
                setRequestProperty("content-type", "application/json")
                setRequestProperty("accept", "audio/mpeg")
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) { c.errorStream?.close(); return null }
            val f = File(ctx.cacheDir, "sly_voice_${System.currentTimeMillis()}.mp3")
            c.inputStream.use { input -> java.io.FileOutputStream(f).use { input.copyTo(it) } }
            f
        } catch (e: Exception) { null }
    }
}
