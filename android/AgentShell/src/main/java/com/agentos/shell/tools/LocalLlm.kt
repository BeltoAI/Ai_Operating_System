package com.agentos.shell.tools

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device LLM — a free, private, offline endpoint that plugs into the SAME orchestration as the cloud
 * providers: SlyOS assembles the identical brain/persona context and passes it through, so memory, visual
 * outputs and every screen behave the same. Small local models are weaker and can't browse the web, so
 * SlyOS keeps using cloud models for web/vision tasks (and can use the local model to pre-process cheap
 * steps to save API cost).
 *
 * The actual inference runs through Google's MediaPipe LLM Inference engine, called by REFLECTION so this
 * file compiles with or without the native library present. Add the dependency to turn it on:
 *   implementation("com.google.mediapipe:tasks-genai:0.10.24")
 */
object LocalLlm {
    private const val TAG = "SlyOS-LLM"
    private const val PREF = "slyos"
    private const val K_ENABLED = "local_enabled"
    private const val K_MODEL = "local_model"
    private const val K_PREPROCESS = "local_preprocess"
    private const val K_VERIFIED = "local_verified"

    /** A downloadable on-device model. [ramGbMin] is the phone RAM we recommend to run it comfortably.
     *  [ctxTokens] is the model's FIXED context window (the ekvNNNN baked into the .task file). We must
     *  never feed more than this many input+output tokens or MediaPipe crashes the app natively. */
    data class LmModel(
        val id: String, val name: String, val family: String, val params: String, val quant: String,
        val fileMb: Int, val ramGbMin: Double, val ctxTokens: Int, val url: String, val fileName: String, val note: String
    )

    // A small, curated set that actually runs on a phone. URLs point at MediaPipe .task bundles hosted by
    // Google's litert-community on HuggingFace. Each has a fixed ekv context window encoded in its filename.
    // IMPORTANT: only OPEN (non-gated, apache-2.0/MIT) repos — gated ones (Gemma, Meta Llama) need a
    // HuggingFace login and silently fail to download, so we never list them here.
    val MODELS: List<LmModel> = listOf(
        LmModel("qwen2.5-0.5b-int8", "Qwen 2.5 · 0.5B", "Alibaba Qwen", "0.5B", "int8", 546, 3.0, 1280,
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true",
            "qwen2.5-0.5b-int8.task", "Fastest & smallest. Great for quick replies, notes, drafting. Runs on most phones."),
        LmModel("qwen2.5-1.5b-int8", "Qwen 2.5 · 1.5B", "Alibaba Qwen", "1.5B", "int8", 1650, 4.0, 1280,
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true",
            "qwen2.5-1.5b-int8.task", "Balanced — sharper reasoning + multilingual. Good on newer phones (4GB+ RAM)."),
        LmModel("phi4-mini-int8", "Phi-4 mini", "Microsoft Phi", "3.8B", "int8", 3944, 6.0, 1280,
            "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task?download=true",
            "phi4-mini-int8.task", "Best quality on-device, but a big download & heavy — only high-end phones (6GB+ RAM).")
    )

    fun modelById(id: String): LmModel? = MODELS.firstOrNull { it.id == id }

    // ---- Device capability ------------------------------------------------------------------------
    enum class Fit { GREAT, OK, RISKY, NO }
    data class Verdict(val fit: Fit, val plain: String)

    fun deviceRamGb(ctx: Context): Double = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        (mi.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0))
    } catch (e: Exception) { 0.0 }

    /** Plain-English "can my phone run this?" — no jargon, so non-technical users get it. */
    fun canRun(ctx: Context, m: LmModel): Verdict {
        val ram = deviceRamGb(ctx)
        if (ram <= 0.1) return Verdict(Fit.OK, "We couldn't read your phone's memory — it may still work; try it.")
        val gb = "%.0f".format(ram)
        return when {
            ram >= m.ramGbMin + 1.5 -> Verdict(Fit.GREAT, "Runs great on your phone ($gb GB RAM).")
            ram >= m.ramGbMin -> Verdict(Fit.OK, "Should run on your phone ($gb GB RAM) — may be a little slow.")
            ram >= m.ramGbMin - 1.0 -> Verdict(Fit.RISKY, "Might be tight on your phone ($gb GB RAM) — could lag or crash. A smaller model is safer.")
            else -> Verdict(Fit.NO, "Your phone ($gb GB RAM) likely can't run this one. Pick a smaller model.")
        }
    }

    // ---- Settings ---------------------------------------------------------------------------------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_ENABLED, on).apply()
    fun preprocess(ctx: Context): Boolean = prefs(ctx).getBoolean(K_PREPROCESS, false)
    fun setPreprocess(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_PREPROCESS, on).apply()
    fun selectedId(ctx: Context): String = prefs(ctx).getString(K_MODEL, "").orEmpty()
    fun setSelectedId(ctx: Context, id: String) { prefs(ctx).edit().putString(K_MODEL, id).putBoolean(K_VERIFIED, false).apply(); unload() }
    // A model is only trusted for real prompts AFTER a successful user-run test — small model files can be
    // incompatible with the engine and crash natively (uncatchable), so we never auto-load one unproven.
    fun verified(ctx: Context): Boolean = prefs(ctx).getBoolean(K_VERIFIED, false)
    fun setVerified(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_VERIFIED, on).apply()
    fun selectedModel(ctx: Context): LmModel? = modelById(selectedId(ctx))

    // ---- Model files + download -------------------------------------------------------------------
    private fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun modelFile(ctx: Context, m: LmModel): File = File(dir(ctx), m.fileName)
    fun isDownloaded(ctx: Context, m: LmModel): Boolean = modelFile(ctx, m).let { it.exists() && it.length() > 1_000_000L }

    /** Download [m] to the device, reporting 0..100 progress. Returns true on success. Blocking. */
    fun download(ctx: Context, m: LmModel, onProgress: (Int) -> Unit): Boolean {
        val out = modelFile(ctx, m)
        val tmp = File(out.absolutePath + ".part")
        return try {
            val c = (URL(m.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
            }
            if (c.responseCode !in 200..299) { Log.e(TAG, "download ${c.responseCode}"); return false }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: (m.fileMb * 1_000_000L)
            c.inputStream.use { inp ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16); var read = 0L; var n: Int; var lastPct = -1
                    while (inp.read(buf).also { n = it } >= 0) {
                        o.write(buf, 0, n); read += n
                        val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
            if (out.exists()) out.delete()
            tmp.renameTo(out)
            true
        } catch (e: Exception) { Log.e(TAG, "download failed", e); try { tmp.delete() } catch (x: Exception) {}; false }
    }

    fun delete(ctx: Context, m: LmModel) { try { modelFile(ctx, m).delete() } catch (e: Exception) {}; if (selectedId(ctx) == m.id) { setVerified(ctx, false); unload() } }

    // ---- Inference (MediaPipe via reflection) -----------------------------------------------------
    @Volatile private var engine: Any? = null
    @Volatile private var loadedPath: String = ""
    // MediaPipe's engine is NOT thread-safe and a second call while one is running crashes the app
    // natively. Everything that touches the engine runs under this single lock, so calls are serialized.
    private val engineLock = Any()
    // Very rough chars→tokens factor. English is ~4 chars/token; we deliberately UNDER-estimate at 3.3 so
    // our token budget is conservative and we never accidentally overflow the model's fixed window.
    private const val CHARS_PER_TOKEN = 3.3

    /** True only when the local model is enabled, downloaded, the engine is present, AND it passed the
     *  user's test — so a prompt is NEVER auto-routed to an unproven model that could crash the phone. */
    fun ready(ctx: Context): Boolean {
        if (!enabled(ctx) || !verified(ctx)) return false
        val m = selectedModel(ctx) ?: return false
        if (!isDownloaded(ctx, m)) return false
        return engineClass() != null
    }

    /** A one-off, user-initiated test generation. Uses a FULL-SIZED system prompt (like a real request) so
     *  it exercises the whole context window — the most memory/heat-intensive path. If it survives this, it
     *  survives real prompts (which generate() caps to the same budget). Sets verified on success. This is
     *  the ONLY place an unproven model is loaded. */
    fun testRun(ctx: Context): Pair<Int, String> {
        val m = selectedModel(ctx) ?: return -1 to "No on-device model selected."
        // Build a realistic-length system prompt to fill most of the window, mirroring a real agent call.
        val filler = ("You are SlyOS, the user's on-phone assistant. You are helpful, concise and natural. ")
            .repeat((m.ctxTokens / 6).coerceAtLeast(1))
        val msgs = JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "Say hello in one short sentence."))
        val r = generate(ctx, filler, msgs, 48)
        if (r.first == 200) setVerified(ctx, true)
        return r
    }

    private fun engineClass(): Class<*>? = try {
        Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
    } catch (e: Throwable) { null }

    fun unload() { try { engine?.let { it.javaClass.getMethod("close").invoke(it) } } catch (e: Throwable) {}; engine = null; loadedPath = "" }

    private fun ensureLoaded(ctx: Context, m: LmModel): Boolean {
        val path = modelFile(ctx, m).absolutePath
        if (engine != null && loadedPath == path) return true
        unload()
        return try {
            val infClass = engineClass() ?: return false
            val optClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
            val builder = optClass.getMethod("builder").invoke(null)
            val bClass = builder.javaClass
            bClass.getMethod("setModelPath", String::class.java).invoke(builder, path)
            // Set the engine's max sequence length to the model's ACTUAL context window (the ekvNNNN baked
            // into the file). The old hardcoded 1024 was smaller than a real system prompt, so any real
            // prompt overflowed it and crashed natively — this was THE crash. Now we match the file.
            try { bClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType).invoke(builder, m.ctxTokens) } catch (e: Throwable) {}
            val options = bClass.getMethod("build").invoke(builder)
            engine = infClass.getMethod("createFromOptions", Context::class.java, optClass)
                .invoke(null, ctx.applicationContext, options)
            loadedPath = path
            engine != null
        } catch (e: Throwable) { Log.e(TAG, "engine load failed", e); false }
    }

    /**
     * Generate a reply from the flattened prompt. Returns (200, text) on success, or an error pair the
     * router treats like any other provider failure — so it transparently falls back to a cloud model.
     */
    fun generate(ctx: Context, system: String, messages: JSONArray, maxTokens: Int): Pair<Int, String> {
        val m = selectedModel(ctx) ?: return -1 to "No on-device model selected."
        if (!isDownloaded(ctx, m)) return -1 to "On-device model not downloaded yet."
        if (engineClass() == null) return -1 to "On-device engine not installed in this build."
        // Serialize: MediaPipe crashes natively on concurrent calls, so only one generate runs at a time.
        synchronized(engineLock) {
            if (!ensureLoaded(ctx, m)) return -1 to "Couldn't load the on-device model."
            return try {
                // Keep replies SHORT — a small on-device model runs cooler/faster and rambles less. Then
                // budget the input so input + output can NEVER exceed the model's fixed window (overflow =
                // native crash on the phone).
                val outTok = maxTokens.coerceIn(16, minOf(m.ctxTokens / 3, 220))
                val inputTokBudget = (m.ctxTokens - outTok - 48).coerceAtLeast(64)
                val inputCharBudget = (inputTokBudget * CHARS_PER_TOKEN).toInt()
                val prompt = buildLocalPrompt(system, messages, inputCharBudget)
                val resp = engine!!.javaClass.getMethod("generateResponse", String::class.java).invoke(engine, prompt) as? String
                if (resp.isNullOrBlank()) -1 to "The on-device model returned nothing." else 200 to cleanOutput(resp)
            } catch (e: Throwable) { Log.e(TAG, "generate failed", e); -1 to "On-device generation failed." }
        }
    }

    /**
     * Build a compact, small-model-friendly prompt. Two things matter for quality:
     *  1) The useful facts about the user sit at the END of the caller's system prompt (the "About the
     *     user" block), so when we must trim we keep the TAIL of the system text — that's what lets the
     *     model answer "what's my name". (The long tool protocol at the head is useless to a local model.)
     *  2) We only include the LAST user turn, not the whole history, to stay light and cool.
     */
    private fun buildLocalPrompt(system: String, messages: JSONArray, maxChars: Int): String {
        // Extract the most recent user message text.
        var lastUser = ""
        for (i in messages.length() - 1 downTo 0) {
            val o = messages.optJSONObject(i) ?: continue
            if (o.optString("role") == "user") {
                val c = o.opt("content")
                lastUser = when (c) {
                    is String -> c
                    is JSONArray -> (0 until c.length()).joinToString(" ") { c.optJSONObject(it)?.optString("text").orEmpty() }
                    else -> c?.toString().orEmpty()
                }
                break
            }
        }
        val header = "You are SlyOS, the user's helpful, concise on-phone assistant. Answer in one or two short " +
            "sentences using the facts below. If you don't know, say so briefly.\n\nContext about the user:\n"
        val footer = "\n\nUser: " + lastUser.trim() + "\nAssistant:"
        val room = (maxChars - header.length - footer.length).coerceAtLeast(0)
        val ctxText = if (system.length <= room) system else system.takeLast(room)  // keep the TAIL (facts)
        return header + ctxText + footer
    }

    /** Small instruct models often echo the chat template ("User:", "Assistant:") or start a fake next turn.
     *  Strip any of that so the user sees a clean reply. */
    private fun cleanOutput(raw: String): String {
        var s = raw.trim()
        // Cut anything from the point the model tries to start a new turn.
        for (marker in listOf("\nUser:", "\nAssistant:", "\nuser:", "\nassistant:", "\n<", "User:")) {
            val idx = s.indexOf(marker)
            if (idx > 0) s = s.substring(0, idx)
        }
        // Drop a leading role label if present.
        s = s.removePrefix("Assistant:").removePrefix("assistant:").trim()
        return s.ifBlank { "…" }
    }
}
