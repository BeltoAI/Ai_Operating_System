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

    /** A downloadable on-device model. [ramGbMin] is the phone RAM we recommend to run it comfortably. */
    data class LmModel(
        val id: String, val name: String, val family: String, val params: String, val quant: String,
        val fileMb: Int, val ramGbMin: Double, val url: String, val fileName: String, val note: String
    )

    // A small, curated set that actually runs on a phone. URLs point at MediaPipe .task bundles.
    val MODELS: List<LmModel> = listOf(
        LmModel("gemma3-1b-int4", "Gemma 3 · 1B", "Google Gemma", "1B", "int4", 560, 3.0,
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            "gemma3-1b-int4.task", "Fastest. Great for quick replies, notes, drafting. Best on most phones."),
        LmModel("qwen2.5-1.5b-int8", "Qwen 2.5 · 1.5B", "Alibaba Qwen", "1.5B", "int8", 1600, 4.0,
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "qwen2.5-1.5b-int8.task", "Sharper reasoning + multilingual. Needs a newer phone with more RAM."),
        LmModel("llama3.2-3b-int4", "Llama 3.2 · 3B", "Meta Llama", "3B", "int4", 1900, 6.0,
            "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct/resolve/main/Llama-3.2-3B-Instruct_multi-prefill-seq_q4_ekv1280.task",
            "llama3.2-3b-int4.task", "Best quality on-device, but heavy — only for high-end phones (6GB+ RAM).")
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
    fun setSelectedId(ctx: Context, id: String) { prefs(ctx).edit().putString(K_MODEL, id).apply(); unload() }
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

    fun delete(ctx: Context, m: LmModel) { try { modelFile(ctx, m).delete() } catch (e: Exception) {}; if (selectedId(ctx) == m.id) unload() }

    // ---- Inference (MediaPipe via reflection) -----------------------------------------------------
    @Volatile private var engine: Any? = null
    @Volatile private var loadedPath: String = ""

    /** True when a model is selected, downloaded, and the on-device engine library is present. */
    fun ready(ctx: Context): Boolean {
        if (!enabled(ctx)) return false
        val m = selectedModel(ctx) ?: return false
        if (!isDownloaded(ctx, m)) return false
        return engineClass() != null
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
            try { bClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType).invoke(builder, 1024) } catch (e: Throwable) {}
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
        if (!ensureLoaded(ctx, m)) return -1 to "Couldn't load the on-device model."
        return try {
            val prompt = flatten(system, messages)
            val resp = engine!!.javaClass.getMethod("generateResponse", String::class.java).invoke(engine, prompt) as? String
            if (resp.isNullOrBlank()) -1 to "The on-device model returned nothing." else 200 to resp.trim()
        } catch (e: Throwable) { Log.e(TAG, "generate failed", e); -1 to "On-device generation failed." }
    }

    /** Flatten the system prompt + chat turns into one prompt string for a text-in/text-out local model. */
    private fun flatten(system: String, messages: JSONArray): String = buildString {
        if (system.isNotBlank()) append(system).append("\n\n")
        for (i in 0 until messages.length()) {
            val o = messages.optJSONObject(i) ?: continue
            val role = o.optString("role")
            val content = o.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).joinToString(" ") { content.optJSONObject(it)?.optString("text").orEmpty() }
                else -> content?.toString().orEmpty()
            }
            if (text.isNotBlank()) append(if (role == "assistant") "Assistant: " else "User: ").append(text).append("\n")
        }
        append("Assistant: ")
    }
}
