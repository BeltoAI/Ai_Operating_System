package com.agentos.shell.tools

import android.content.Context
import com.agentos.shell.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Privacy-safe product analytics: anonymous usage events → your Supabase, so you can see how many people use
 * SlyOS, what they actually do with it, and how effective it is. No names, no content — just a random per-install
 * id + an event name + a light prop. Fire-and-forget on a background thread; never blocks or crashes the app.
 * Owners can opt out (Analytics.setEnabled false). Query aggregates from your Supabase dashboard.
 */
object Analytics {
    private fun p(ctx: Context) = ctx.getSharedPreferences("slyos_analytics", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()

    /** Stable, anonymous per-install id (a random UUID — not tied to the person or device hardware). */
    private fun deviceId(ctx: Context): String {
        val cur = p(ctx).getString("did", "").orEmpty()
        if (cur.isNotBlank()) return cur
        val id = java.util.UUID.randomUUID().toString()
        p(ctx).edit().putString("did", id).apply()
        return id
    }

    /** Record an event, e.g. track(ctx, "site_published"). [props] is an optional short tag, never PII. */
    fun track(ctx: Context, event: String, props: String = "") {
        if (!enabled(ctx)) return
        val base = try { BuildConfig.SUPABASE_URL.trimEnd('/') } catch (e: Exception) { "" }
        val key = try { BuildConfig.SUPABASE_ANON_KEY } catch (e: Exception) { "" }
        if (base.isBlank() || key.isBlank()) return
        val did = deviceId(ctx)
        Thread {
            try {
                val body = JSONObject().put("device", did).put("event", event.take(60)).put("props", props.take(120)).toString()
                val conn = (URL("$base/rest/v1/usage_events").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 12000
                    setRequestProperty("apikey", key)
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Prefer", "return=minimal")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }.start()
    }
}
