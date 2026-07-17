package com.agentos.shell.tools

import android.content.Context
import com.agentos.shell.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Product analytics — rich but privacy-safe. Every meaningful action, win, and loss is logged to your Supabase
 * with an anonymous per-install id AND (when signed in) the account, plus a WHAT-FOR intent category inferred
 * from the request — never the content itself. So you can later answer "how many users, what do they use SlyOS
 * for, what works, what fails, which cohort does what." Fire-and-forget; never blocks or crashes. Opt-out-able.
 */
object Analytics {
    private fun p(ctx: Context) = ctx.getSharedPreferences("slyos_analytics", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()

    private fun deviceId(ctx: Context): String {
        val cur = p(ctx).getString("did", "").orEmpty()
        if (cur.isNotBlank()) return cur
        val id = java.util.UUID.randomUUID().toString()
        p(ctx).edit().putString("did", id).apply()
        return id
    }

    /** Infer WHAT the user is trying to do (never store the text — just the bucket). This is the "what for". */
    fun intent(q: String): String {
        val t = q.lowercase()
        return when {
            Regex("\\bremind|remember|note|don'?t forget|save (this|that)|keep track\\b").containsMatchIn(t) -> "remember"
            Regex("\\bjob|hiring|apply|resume|cv|career|recruit|linkedin\\b").containsMatchIn(t) -> "find_job"
            Regex("\\bscreen ?time|distract|focus|less phone|reduce|productiv\\b").containsMatchIn(t) -> "reduce_screentime"
            Regex("\\bemail|inbox|reply|mail|message|text|whatsapp|\\bdm\\b|telegram\\b").containsMatchIn(t) -> "communicate"
            Regex("\\bcalendar|schedule|meeting|appointment|alarm|timer|wake|tomorrow|book a\\b").containsMatchIn(t) -> "schedule"
            Regex("\\bbuild|website|\\bsite\\b|\\bapp\\b|deck|deploy|design|logo|marketplace|landing\\b").containsMatchIn(t) -> "create"
            Regex("\\bexpense|receipt|invoice|budget|money|cost|spend|stock|invest|market|price\\b").containsMatchIn(t) -> "finance"
            Regex("\\bphoto|picture|image|gallery|selfie\\b").containsMatchIn(t) -> "photos"
            Regex("\\bsong|music|play|pause|shazam\\b").containsMatchIn(t) -> "music"
            Regex("\\bnews|research|look up|find out|what is|who is|how (do|to)|explain|why|search\\b").containsMatchIn(t) -> "research"
            Regex("\\bflashlight|torch|call|dial|navigate|maps|directions|open \\w+\\b").containsMatchIn(t) -> "device_control"
            Regex("\\btranslate|language|in (spanish|french|german|russian|chinese)\\b").containsMatchIn(t) -> "translate"
            Regex("\\bagent|team|hire|employee|assistant\\b").containsMatchIn(t) -> "team"
            else -> "other"
        }
    }

    /** Record an event. [category] = the what-for bucket (use intent() for free-text). [props] a short tag. */
    fun track(ctx: Context, event: String, props: String = "", category: String = "") {
        if (!enabled(ctx)) return
        val base = try { BuildConfig.SUPABASE_URL.trimEnd('/') } catch (e: Exception) { "" }
        val key = try { BuildConfig.SUPABASE_ANON_KEY } catch (e: Exception) { "" }
        if (base.isBlank() || key.isBlank()) return
        val did = deviceId(ctx)
        val account = try { if (AccountStore.signedIn(ctx)) AccountStore.email(ctx) else "" } catch (e: Exception) { "" }
        Thread {
            try {
                val body = JSONObject()
                    .put("device", did).put("account", account)
                    .put("event", event.take(60)).put("category", category.take(40)).put("props", props.take(160))
                    .toString()
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
