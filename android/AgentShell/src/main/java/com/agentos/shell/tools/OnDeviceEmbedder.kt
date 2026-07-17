package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device semantic embeddings via MediaPipe's Text Embedder (Universal Sentence Encoder). This makes
 * recall-by-meaning FREE, unlimited, private and rate-limit-proof, and works no matter which chat provider
 * the user has (Groq-only users get semantic memory too). It drains VectorStore's backfill queue with no
 * quota to throttle it.
 *
 * SAFETY: everything is reflection-based and wrapped — if the MediaPipe class/method names differ, the model
 * isn't downloaded, or anything throws, embed() returns null and the caller falls back to the cloud embedder
 * (or keyword recall). It can never crash the app or block a reply. It's also OPT-IN (embed provider "local"),
 * so default behavior is unchanged until the user picks it.
 */
object OnDeviceEmbedder {
    private const val TAG = "SlyOS-Embed"
    // Universal Sentence Encoder text embedder (100-dim), hosted by MediaPipe. ~6MB.
    private const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite"
    private const val FILE = "use_embedder.tflite"
    const val DIM = 100

    private fun file(ctx: Context): File = File(File(ctx.filesDir, "models").apply { mkdirs() }, FILE)
    fun isDownloaded(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 100_000L }
    fun ready(ctx: Context): Boolean = isDownloaded(ctx)

    /** Download the embedder model (call off the main thread). Returns true on success. */
    fun download(ctx: Context, onProgress: (Int) -> Unit = {}): Boolean {
        val out = file(ctx)
        return try {
            val c = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 60000
            }
            val total = c.contentLength.coerceAtLeast(1)
            var read = 0L
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(65536); var n: Int
                    while (inp.read(buf).also { n = it } > 0) {
                        o.write(buf, 0, n); read += n
                        onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            c.disconnect()
            isDownloaded(ctx)
        } catch (e: Exception) { Log.w(TAG, "download failed: ${e.message}"); HealthStore.note("embed_download", false, e.message ?: "fail"); false }
    }

    fun delete(ctx: Context) { try { file(ctx).delete() } catch (e: Exception) {}; embedder = null }

    @Volatile private var embedder: Any? = null
    @Volatile private var teClass: Class<*>? = null

    /** Build the MediaPipe TextEmbedder once, via reflection so a missing dep degrades to null (not a crash). */
    private fun ensure(ctx: Context): Any? {
        embedder?.let { return it }
        if (!isDownloaded(ctx)) return null
        return try {
            val path = file(ctx).absolutePath
            val baseOptClass = Class.forName("com.google.mediapipe.tasks.core.BaseOptions")
            val baseBuilder = baseOptClass.getMethod("builder").invoke(null)
            baseBuilder.javaClass.getMethod("setModelAssetPath", String::class.java).invoke(baseBuilder, path)
            val baseOptions = baseBuilder.javaClass.getMethod("build").invoke(baseBuilder)

            val optClass = Class.forName("com.google.mediapipe.tasks.text.textembedder.TextEmbedder\$TextEmbedderOptions")
            val optBuilder = optClass.getMethod("builder").invoke(null)
            optBuilder.javaClass.getMethod("setBaseOptions", baseOptClass).invoke(optBuilder, baseOptions)
            val options = optBuilder.javaClass.getMethod("build").invoke(optBuilder)

            val te = Class.forName("com.google.mediapipe.tasks.text.textembedder.TextEmbedder")
            teClass = te
            te.getMethod("createFromOptions", Context::class.java, optClass).invoke(null, ctx, options)
                .also { embedder = it; HealthStore.note("embed_local_init", true, "ready") }
        } catch (e: Throwable) {
            Log.w(TAG, "init failed: ${e.message}"); HealthStore.note("embed_local_init", false, e.message ?: "init fail"); null
        }
    }

    /** Embed a batch locally. Returns null on any failure (→ caller falls back to cloud / keyword). */
    fun embed(ctx: Context, texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val e = ensure(ctx) ?: return null
        val te = teClass ?: return null
        return try {
            val embedM = te.getMethod("embed", String::class.java)
            val out = ArrayList<FloatArray>(texts.size)
            for (t in texts) {
                val result = embedM.invoke(e, t.take(4000))
                val er = result.javaClass.getMethod("embeddingResult").invoke(result)
                val embs = er.javaClass.getMethod("embeddings").invoke(er) as List<*>
                val emb0 = embs.firstOrNull() ?: return null
                val vec = emb0.javaClass.getMethod("floatEmbedding").invoke(emb0) as FloatArray
                out.add(vec)
            }
            HealthStore.note("embed_local", true, "${out.size} vecs")
            out
        } catch (e2: Throwable) {
            Log.w(TAG, "embed failed: ${e2.message}"); HealthStore.note("embed_local", false, e2.message ?: "embed fail"); null
        }
    }
}
