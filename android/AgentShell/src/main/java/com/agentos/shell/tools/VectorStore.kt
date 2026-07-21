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

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_vec.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS vmem(contact TEXT, role TEXT, body TEXT, provider TEXT, dim INTEGER, v BLOB, ts INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending ON vmem(dim)")
            // Without this the per-insert duplicate check is a full table scan.
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_dedupe ON vmem(contact, body)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON vmem(ts)")
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

    /**
     * Queue text for later embedding — instant, no network.
     *
     * DEDUPE: there was no uniqueness check, so the same text could be embedded many times over. On a real
     * device that produced 72,422 vectors for 41,807 messages (173%) — wasted embedding calls, wasted
     * storage, and duplicate hits crowding out genuinely different memories at retrieval time.
     */
    fun enqueue(ctx: Context, contact: String, role: String, body: String): Boolean {
        val t = body.trim(); if (t.length < 3) return false
        val clipped = t.take(4000)
        return try {
            val d = db(ctx)
            val dup = d.rawQuery("SELECT 1 FROM vmem WHERE contact=? AND body=? LIMIT 1",
                arrayOf(contact, clipped)).use { it.moveToFirst() }
            if (dup) return false
            d.execSQL("INSERT INTO vmem(contact,role,body,provider,dim,v,ts) VALUES(?,?,?,'',0,NULL,?)",
                arrayOf(contact, role, clipped, System.currentTimeMillis()))
            true
        } catch (e: Exception) { false }
    }

    /**
     * Ingest EVERY kind of memory, not just chat messages.
     *
     * The gap this closes: only MessageStore ever fed the vector index. Documents, photo captions/OCR,
     * on-screen recall, CRM contacts and the LinkedIn network were NEVER embedded — so semantic search
     * could not find anything from them, no matter how relevant. That is the bulk of the brain, and it
     * is why recall felt useless: it was searching a fraction of what the user believes is stored.
     *
     * Idempotent (enqueue dedupes), so it is safe to run on every launch.
     */
    fun ingestAllSources(ctx: Context, perSource: Int = 400): Int {
        var added = 0
        // NOTE: enqueue() reports whether it actually inserted. Counting the table before/after each row
        // would mean two full COUNT(*) scans per memory — thousands of scans on a real brain.
        fun add(contact: String, role: String, body: String) {
            if (body.isBlank()) return
            if (enqueue(ctx, contact, role, body)) added++
        }
        // Documents the user has filed or fed in — their actual content, chunked so long docs are findable.
        try {
            DocText.recent(ctx, 200).forEach { (title, body) ->
                body.chunked(1200).take(6).forEach { chunk -> add("Document: $title", "doc", chunk) }
            }
        } catch (e: Exception) {}
        // Photo understanding: captions, labels and OCR text are real memories of what the user saw.
        try { PhotoIndex.searchableText(ctx, perSource).forEach { (name, text) -> add("Photo: $name", "photo", text) } }
        catch (e: Exception) {}
        // Total Recall — what was actually on screen. The single richest source and it was never indexed.
        try {
            InteractionStore.search(ctx, "", perSource).forEach { e ->
                add("Seen in ${e.app}", "screen", e.text)
            }
        } catch (e: Exception) {}
        // People: CRM + network, so "who did I meet at X" is answerable semantically.
        try {
            LeadStore.all(ctx).take(perSource).forEach { l ->
                add("Contact: ${l.name}", "crm",
                    listOfNotNull(l.name, l.role.ifBlank { null }, l.company.ifBlank { null },
                        l.email.ifBlank { null }, l.notes.ifBlank { null }).joinToString(" · "))
            }
        } catch (e: Exception) {}
        try {
            ConnectionStore.recent(ctx, perSource).forEach { c ->
                add("Network: ${c.name}", "network",
                    listOfNotNull(c.name, c.role.ifBlank { null }, c.company.ifBlank { null }).joinToString(" · "))
            }
        } catch (e: Exception) {}
        if (added > 0) HealthStore.note("vec_ingest", true, "queued $added new memories from all sources")
        return added
    }

    /** Remove exact-duplicate rows left behind by the old no-dedupe insert. Returns rows deleted. */
    fun purgeDuplicates(ctx: Context): Int = try {
        val d = db(ctx)
        val before = d.rawQuery("SELECT count(*) FROM vmem", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        d.execSQL("DELETE FROM vmem WHERE rowid NOT IN (SELECT MIN(rowid) FROM vmem GROUP BY contact, body)")
        val after = d.rawQuery("SELECT count(*) FROM vmem", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val removed = before - after
        if (removed > 0) HealthStore.note("vec_dedupe", true, "removed $removed duplicate vectors")
        removed
    } catch (e: Exception) { 0 }

    /** One-time seed: pull EVERY message already in the brain into the queue (existing imported history
     *  pre-dates the live hook, so without this the index only sees new messages). Runs once. */
    fun ensureSeeded(ctx: Context) {
        val meta = ctx.getSharedPreferences("slyos_vec_meta", Context.MODE_PRIVATE)
        if (meta.getBoolean("seeded", false)) return
        try {
            // Seed the value-ranked set (your own writing first). P2.2: raised 15k→50k now that batching
            // is ~12× more request-efficient, so far more of the brain becomes semantically searchable.
            val rows = MessageStore.valueRows(ctx, 50000)
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
    fun backfill(ctx: Context, cap: Int = 1000) {
        val provider = EmbeddingClient.provider(ctx) ?: return
        ensureSeeded(ctx)   // make sure existing history is queued before we start embedding
        var processed = 0
        try {
            while (processed < cap) {
                val ids = ArrayList<Long>(); val bodies = ArrayList<String>()
                // P2.2: Gemini's batchEmbedContents accepts up to 100 inputs per request — batching ~100
                // (instead of 8) spends the scarce free-tier REQUEST quota ~12× more efficiently, so a
                // large brain indexes in far fewer sessions. Token throughput is generous; requests are the cap.
                db(ctx).rawQuery("SELECT rowid, body FROM vmem WHERE v IS NULL LIMIT 100", null).use { c ->
                    while (c.moveToNext()) { ids.add(c.getLong(0)); bodies.add(c.getString(1)) }
                }
                if (ids.isEmpty()) break
                // Free-tier embedding is tightly rate-limited; keep batches small, back off hard on a
                // throttle, and pace so a run trickles under the cap instead of slamming into it.
                var vecs = EmbeddingClient.embed(ctx, bodies)
                if (vecs == null) { try { Thread.sleep(20000) } catch (e: Exception) {}; vecs = EmbeddingClient.embed(ctx, bodies) }
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
                try { Thread.sleep(2500) } catch (e: Exception) {}   // pace under the free-tier cap
            }
        } catch (e: Exception) {}
    }

    /** Run the query embedding on a worker thread and wait at most [ms]; null on timeout (thread finishes
     *  in the background, harmless). Keeps a slow embed provider from ever blocking a reply. */
    private fun embedBounded(ctx: Context, query: String, ms: Long): FloatArray? {
        val ref = java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
        val t = Thread { try { ref.set(EmbeddingClient.embed(ctx, listOf(query), "RETRIEVAL_QUERY")?.firstOrNull()) } catch (e: Exception) {} }
        t.isDaemon = true; t.start(); t.join(ms)
        return ref.get()
    }

    /** Semantic search: embed the query, rank embedded rows by cosine, return the top [k]. */
    fun search(ctx: Context, query: String, k: Int = 6): List<Hit> {
        if (query.isBlank()) return emptyList()
        val provider = EmbeddingClient.provider(ctx) ?: return emptyList()
        // P3: HARD-CAP the query embedding (a network call) at ~4s. On a Gemini throttle/stall this returns
        // null and the caller degrades to keyword recall — semantic recall can NEVER block a reply.
        // 8s, not 4s: on a cold Gemini call 4s frequently timed out, silently returning NO semantic recall
        // at all — the user experiences that as "my brain forgot everything".
        val qv = embedBounded(ctx, query, 8000L) ?: run {
            Fail.log(ctx, "Brain", "semantic recall for \"${query.take(40)}\"",
                "query embedding timed out — fell back to keyword search only", "warn")
            return emptyList()
        }
        val hits = ArrayList<Hit>()
        try {
            // Match on DIMENSION only, not provider. Vectors written under an older provider label (or after
            // a model heal) were previously invisible forever — the index looked full while returning nothing.
            // Newest first, and capped: cosine over every row in Kotlin does not scale past a few tens of
            // thousands, and a slow search is indistinguishable from a broken one.
            db(ctx).rawQuery(
                "SELECT contact, role, body, v FROM vmem WHERE v IS NOT NULL AND dim=? ORDER BY ts DESC LIMIT 30000",
                arrayOf(qv.size.toString())).use { c ->
                while (c.moveToNext()) {
                    val v = toVec(c.getBlob(3))
                    val score = EmbeddingClient.cosine(qv, v)
                    // NO hard cutoff. A fixed 0.55 floor meant a query whose best match scored 0.54 returned
                    // absolutely nothing. Collect everything, rank, and let the caller take the best k.
                    if (score > 0.20f) hits.add(Hit(c.getString(0), c.getString(1), c.getString(2), score))
                }
            }
        } catch (e: Exception) {
            Fail.log(ctx, "Brain", "semantic search", e.message ?: "query failed")
            return emptyList()
        }
        if (hits.isEmpty()) Fail.log(ctx, "Brain", "semantic recall for \"${query.take(40)}\"",
            "nothing scored above 0.20 across ${embeddedCount(ctx)} vectors", "warn")
        return hits.sortedByDescending { it.score }.take(k)
    }

    /** P1.6: remove one person's vectors so forgotten content can't resurface in semantic search. */
    fun deleteContact(ctx: Context, contact: String) {
        if (contact.isBlank()) return
        try { db(ctx).execSQL("DELETE FROM vmem WHERE contact=?", arrayOf(contact)) } catch (e: Exception) {}
    }

    fun clear(ctx: Context) {
        try { db(ctx).execSQL("DELETE FROM vmem") } catch (e: Exception) {}
        // Reset the one-time seed flag so a re-index (e.g. after switching embedding provider) pulls
        // the whole brain back into the queue instead of finding it empty.
        try { ctx.getSharedPreferences("slyos_vec_meta", Context.MODE_PRIVATE).edit().putBoolean("seeded", false).apply() } catch (e: Exception) {}
    }
}
