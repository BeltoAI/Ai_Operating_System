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
