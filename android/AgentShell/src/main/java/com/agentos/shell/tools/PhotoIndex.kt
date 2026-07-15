package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log

/**
 * PHOTOS IN THE BRAIN — a real, growing local RAG for your pictures.
 *
 * Every photo is described once by the vision model, and the description + metadata live in SQLite (so it
 * scales to your whole gallery and is included in brain backups). You can then ask for pictures by MEANING —
 * "send a cute selfie", "the beach photo", "a picture of my dog". Retrieval is keyword-scored over the
 * captions today (no embedding key needed); embeddings can layer on later for fuzzier matches.
 *
 * Indexing is incremental and bounded (a handful of the newest un-described photos per run) so it never
 * spikes cost, and it keeps growing every time the app opens.
 */
object PhotoIndex {
    private const val TAG = "SlyOS-PhotoIdx"

    data class Entry(val uri: String, val name: String, val caption: String, val bucket: String, val ts: Long)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_photos.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS photos(uri TEXT PRIMARY KEY, name TEXT, caption TEXT, bucket TEXT, ts INTEGER, " +
                "labels TEXT DEFAULT '', kind TEXT DEFAULT '', faces INTEGER DEFAULT 0)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_ts ON photos(ts)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            if (o < 2) {
                try { db.execSQL("ALTER TABLE photos ADD COLUMN labels TEXT DEFAULT ''") } catch (e: Exception) {}
                try { db.execSQL("ALTER TABLE photos ADD COLUMN kind TEXT DEFAULT ''") } catch (e: Exception) {}
                try { db.execSQL("ALTER TABLE photos ADD COLUMN faces INTEGER DEFAULT 0") } catch (e: Exception) {}
            }
        }
    }

    @Volatile private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Helper(ctx).also { helper = it } }
        return h.writableDatabase
    }

    fun count(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT count(*) FROM photos", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    fun indexedUris(ctx: Context): Set<String> = try {
        val out = HashSet<String>()
        db(ctx).rawQuery("SELECT uri FROM photos", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
        out
    } catch (e: Exception) { emptySet() }

    fun add(ctx: Context, uri: String, name: String, caption: String, bucket: String, ts: Long) {
        if (caption.isBlank()) return
        try {
            db(ctx).insertWithOnConflict("photos", null, ContentValues().apply {
                put("uri", uri); put("name", name); put("caption", caption); put("bucket", bucket); put("ts", ts)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) { Log.w(TAG, "add: ${e.message}") }
    }

    fun clear(ctx: Context) = try { db(ctx).execSQL("DELETE FROM photos") } catch (e: Exception) {}

    /** Store the FREE on-device analysis (labels + kind + faces). Upserts by uri; no caption needed. */
    fun putVision(ctx: Context, uri: String, name: String, bucket: String, ts: Long, labels: List<String>, kind: String, faces: Int) {
        try {
            db(ctx).insertWithOnConflict("photos", null, ContentValues().apply {
                put("uri", uri); put("name", name); put("bucket", bucket); put("ts", ts)
                put("labels", labels.joinToString(",")); put("kind", kind); put("faces", faces)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            // If the row already existed (caption present), just add the vision fields without wiping the caption.
            db(ctx).execSQL("UPDATE photos SET labels=?, kind=?, faces=? WHERE uri=?",
                arrayOf(labels.joinToString(","), kind, faces, uri))
        } catch (e: Exception) { Log.w(TAG, "putVision: ${e.message}") }
    }

    /** Uris that already have on-device analysis (a non-empty kind). */
    fun analyzedUris(ctx: Context): Set<String> = try {
        val out = HashSet<String>()
        db(ctx).rawQuery("SELECT uri FROM photos WHERE kind IS NOT NULL AND kind != ''", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
        out
    } catch (e: Exception) { emptySet() }

    /**
     * FREE local retrieval: photos matching any of [kinds] (selfie/portrait/fullbody/group/person/scene) and,
     * if given, scored up by [terms] appearing in labels/caption. This is what makes "a full-body photo of me"
     * answerable across a whole gallery with zero API cost — the cloud model only confirms identity afterwards.
     */
    fun findLocal(ctx: Context, kinds: List<String>, terms: List<String>, limit: Int = 30): List<FileResolver.Found> {
        return try {
            val out = mutableListOf<FileResolver.Found>()
            val kindSet = kinds.toSet()
            db(ctx).rawQuery("SELECT uri, name, kind, labels, caption FROM photos ORDER BY ts DESC LIMIT 400", null).use { c ->
                while (c.moveToNext()) {
                    val uri = c.getString(0); val name = c.getString(1) ?: "photo"
                    val kind = c.getString(2) ?: ""; val labels = (c.getString(3) ?: "").lowercase()
                    val cap = (c.getString(4) ?: "").lowercase()
                    if (kindSet.isNotEmpty() && kind !in kindSet) continue
                    val hay = "$labels $cap $name".lowercase()
                    val termScore = terms.count { hay.contains(it) }
                    // rank: exact-kind photos first, then term matches
                    val score = (if (kind in kindSet) 3 else 0) + termScore
                    out.add(FileResolver.Found(Uri.parse(uri), name, kind.ifBlank { "gallery" }, score))
                }
            }
            out.sortedByDescending { it.score }.take(limit)
        } catch (e: Exception) { Log.w(TAG, "findLocal: ${e.message}"); emptyList() }
    }

    /** Run the FREE on-device analyzer over the newest un-analyzed photos (bounded per call). */
    fun analyzeRecent(ctx: Context, max: Int = 60): Int {
        return try {
            val done = analyzedUris(ctx)
            val recents = FileResolver.recentPhotos(ctx, 500)
            var added = 0
            for (f in recents) {
                if (added >= max) break
                val key = f.uri.toString()
                if (key in done) continue
                if (f.where.contains("screenshot", true) || f.name.contains("screenshot", true)) {
                    putVision(ctx, key, f.name, f.where, System.currentTimeMillis(), listOf("screenshot"), "screenshot", 0); added++; continue
                }
                val r = PhotoVision.analyze(ctx, f.uri) ?: continue
                putVision(ctx, key, f.name, f.where, System.currentTimeMillis(), r.labels, r.kind, r.faces)
                added++
            }
            if (added > 0) Log.i(TAG, "on-device analyzed $added photos")
            added
        } catch (e: Exception) { Log.w(TAG, "analyzeRecent: ${e.message}"); 0 }
    }

    /**
     * Describe the newest un-indexed photos (bounded). Uses the vision model already available via the Claude
     * key — no extra setup. Returns how many it added this run. Grows the RAG a little each time it's called.
     */
    fun indexRecent(ctx: Context, max: Int = 6): Int {
        return try {
            val done = indexedUris(ctx)
            val recents = FileResolver.recentPhotos(ctx, 80)
            var added = 0
            for (f in recents) {
                if (added >= max) break
                if (f.uri.toString() in done) continue
                val b64 = ImageUtil.encode(ctx, f.uri) ?: continue
                val cap = AgentClient.captionImage(b64)
                if (cap.isNotBlank() && !cap.startsWith("Couldn't")) {
                    add(ctx, f.uri.toString(), f.name, cap, f.where, System.currentTimeMillis())
                    added++
                }
            }
            if (added > 0) Log.i(TAG, "described $added new photos (total ${count(ctx)})")
            added
        } catch (e: Exception) { Log.w(TAG, "indexRecent: ${e.message}"); 0 }
    }

    private val STOP = setOf("a", "an", "the", "my", "of", "me", "send", "to", "photo", "picture", "pic", "image", "one", "some")

    /** Best photo matches for a meaning query ("cute selfie") — keyword-scored over the descriptions. */
    fun search(ctx: Context, query: String, limit: Int = 6): List<FileResolver.Found> {
        val toks = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOP }.distinct()
        if (toks.isEmpty()) return emptyList()
        return try {
            val where = toks.joinToString(" OR ") { "caption LIKE ? OR name LIKE ?" }
            val args = toks.flatMap { listOf("%$it%", "%$it%") }.toTypedArray()
            val hits = mutableListOf<FileResolver.Found>()
            db(ctx).rawQuery("SELECT uri, name, caption FROM photos WHERE $where ORDER BY ts DESC LIMIT 60", args).use { c ->
                while (c.moveToNext()) {
                    val uri = c.getString(0); val name = c.getString(1) ?: "photo"; val cap = (c.getString(2) ?: "").lowercase()
                    val hay = "$cap $name".lowercase()
                    val score = toks.count { hay.contains(it) } * 2
                    if (score > 0) hits.add(FileResolver.Found(Uri.parse(uri), name.ifBlank { "photo" }, "gallery", score))
                }
            }
            hits.sortedByDescending { it.score }.take(limit)
        } catch (e: Exception) { Log.w(TAG, "search: ${e.message}"); emptyList() }
    }
}
