package com.agentos.shell.tools

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Semantic memory: a local vector index over everything in the brain. Writing is instant (rows are
 * queued with no vector); a background pass embeds the backlog in capped batches so it never blocks
 * ingest or burns through rate limits. Search embeds the query once and ranks by cosine similarity —
 * so the agent recalls by MEANING. Fully degrades to the keyword path if embeddings are unavailable.
 */
object VectorStore {
    data class Hit(val contact: String, val role: String, val body: String, val score: Float)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_vec.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS vmem(contact TEXT, role TEXT, body TEXT, provider TEXT, dim INTEGER, v BLOB, ts INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending ON vmem(dim)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = onCreate(db)  // never drop the brain
    }

    @Volatile private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Helper(ctx).also { helper = it } }
        return h.writableDatabase
    }

    private fun toBlob(v: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }; return b.array()
    }
    private fun toVec(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { b.getFloat() }
    }

    /** Queue text for later embedding — instant, no network. Called whenever the brain gains a message. */
    fun enqueue(ctx: Context, contact: String, role: String, body: String) {
        val t = body.trim(); if (t.length < 3) return
        try {
            db(ctx).execSQL("INSERT INTO vmem(contact,role,body,provider,dim,v,ts) VALUES(?,?,?,'',0,NULL,?)",
                arrayOf(contact, role, t.take(4000), System.currentTimeMillis()))
        } catch (e: Exception) {}
    }

    /** One-time seed: pull EVERY message already in the brain into the queue (existing imported history
     *  pre-dates the live hook, so without this the index only sees new messages). Runs once. */
    fun ensureSeeded(ctx: Context) {
        val meta = ctx.getSharedPreferences("slyos_vec_meta", Context.MODE_PRIVATE)
        if (meta.getBoolean("seeded", false)) return
        try {
            val rows = MessageStore.allRows(ctx, 20000)
            val d = db(ctx); d.beginTransaction()
            try {
                val stmt = d.compileStatement("INSERT INTO vmem(contact,role,body,provider,dim,v,ts) VALUES(?,?,?,'',0,NULL,?)")
                val now = System.currentTimeMillis()
                for (r in rows) {
                    val t = r.body.trim(); if (t.length < 3) continue
                    stmt.clearBindings()
                    stmt.bindString(1, r.contact); stmt.bindString(2, r.role); stmt.bindString(3, t.take(4000)); stmt.bindLong(4, now)
                    stmt.executeInsert()
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
            meta.edit().putBoolean("seeded", true).apply()
        } catch (e: Exception) {}
    }

    fun pendingCount(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT count(*) FROM vmem WHERE v IS NULL", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    fun embeddedCount(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT count(*) FROM vmem WHERE v IS NOT NULL", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    /** Embed up to [cap] queued rows, in batches. Safe to call on app start (off the main thread). */
    fun backfill(ctx: Context, cap: Int = 200) {
        val provider = EmbeddingClient.provider(ctx) ?: return
        ensureSeeded(ctx)   // make sure existing history is queued before we start embedding
        var processed = 0
        try {
            while (processed < cap) {
                val ids = ArrayList<Long>(); val bodies = ArrayList<String>()
                db(ctx).rawQuery("SELECT rowid, body FROM vmem WHERE v IS NULL LIMIT 32", null).use { c ->
                    while (c.moveToNext()) { ids.add(c.getLong(0)); bodies.add(c.getString(1)) }
                }
                if (ids.isEmpty()) break
                // Free-tier embedding is rate-limited; if a batch is throttled, wait once and retry
                // before giving up, and pace successful batches so a run gets through more.
                var vecs = EmbeddingClient.embed(ctx, bodies)
                if (vecs == null) { try { Thread.sleep(5000) } catch (e: Exception) {}; vecs = EmbeddingClient.embed(ctx, bodies) }
                if (vecs == null) break
                if (vecs.size != ids.size) break
                val d = db(ctx); d.beginTransaction()
                try {
                    for (i in ids.indices) {
                        val cv = android.content.ContentValues().apply {
                            put("provider", provider); put("dim", vecs[i].size); put("v", toBlob(vecs[i]))
                        }
                        d.update("vmem", cv, "rowid=?", arrayOf(ids[i].toString()))
                    }
                    d.setTransactionSuccessful()
                } finally { d.endTransaction() }
                processed += ids.size
                try { Thread.sleep(400) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    /** Semantic search: embed the query, rank embedded rows by cosine, return the top [k]. */
    fun search(ctx: Context, query: String, k: Int = 6): List<Hit> {
        if (query.isBlank()) return emptyList()
        val provider = EmbeddingClient.provider(ctx) ?: return emptyList()
        val qv = EmbeddingClient.embed(ctx, listOf(query), "RETRIEVAL_QUERY")?.firstOrNull() ?: return emptyList()
        val hits = ArrayList<Hit>()
        try {
            db(ctx).rawQuery("SELECT contact, role, body, v, dim FROM vmem WHERE v IS NOT NULL AND provider=? AND dim=?",
                arrayOf(provider, qv.size.toString())).use { c ->
                while (c.moveToNext()) {
                    val v = toVec(c.getBlob(3))
                    val score = EmbeddingClient.cosine(qv, v)
                    if (score > 0.55f) hits.add(Hit(c.getString(0), c.getString(1), c.getString(2), score))
                }
            }
        } catch (e: Exception) { return emptyList() }
        return hits.sortedByDescending { it.score }.take(k)
    }

    fun clear(ctx: Context) {
        try { db(ctx).execSQL("DELETE FROM vmem") } catch (e: Exception) {}
        // Reset the one-time seed flag so a re-index (e.g. after switching embedding provider) pulls
        // the whole brain back into the queue instead of finding it empty.
        try { ctx.getSharedPreferences("slyos_vec_meta", Context.MODE_PRIVATE).edit().putBoolean("seeded", false).apply() } catch (e: Exception) {}
    }
}
