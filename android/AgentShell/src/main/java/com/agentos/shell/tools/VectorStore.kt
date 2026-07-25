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
    /** Newest-first vectors scanned per search, and the wall-clock ceiling for that scan. Together these keep
     *  semantic recall useful while guaranteeing it can never dominate response time (it once cost 21s). */
    // SEARCH THE WHOLE BRAIN, NOT ITS NEWEST SLIVER.
    // 6,000 was a sane cap when the brain was small; against 67,511 vectors it searched 9% of memory, so a
    // full overnight re-embed changed nothing for anything older than the last import. Measured failure:
    // "who told me it was a hard period to find investors" returned "that conversation isn't stored here",
    // while Anna Gong's "Dec can be a tough month to raise" sat embedded and unreachable outside the window.
    // Newest-first ordering made it worse, not better: imported Instagram rows all carry the IMPORT time, so
    // "newest" is dominated by one bulk import rather than by anything genuinely recent.
    // Measured cost is ~19µs/vector, so a full 67k scan is ~1.3s — the budget below is the real guard, and
    // the cap is now high enough that it stops being the thing that decides what the brain can remember.
    private const val SCAN_CAP = 250_000
    private const val SCAN_BUDGET_MS = 1500L

    data class Hit(val contact: String, val role: String, val body: String, val score: Float)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_vec.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS vmem(contact TEXT, role TEXT, body TEXT, provider TEXT, dim INTEGER, v BLOB, ts INTEGER)")
            // int8 copy of v — a quarter the bytes, which is what decides how much of the brain a search
            // can actually reach. See migrateQ8/cosineQ8. Guarded: exists already on newer installs.
            try { db.execSQL("ALTER TABLE vmem ADD COLUMN q8 BLOB") } catch (e: Exception) {}
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending ON vmem(dim)")
            // Without this the per-insert duplicate check is a full table scan.
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_dedupe ON vmem(contact, body)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON vmem(ts)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = onCreate(db)  // never drop the brain
        // onCreate runs ONLY for a brand-new database and onUpgrade only when the version number moves, so
        // an existing install at the current version runs neither — a column added in onCreate would never
        // appear on the very devices that already hold a brain. Adding it here means every open reconciles
        // the schema. Fully guarded, so a duplicate-column error on later opens is a no-op.
        override fun onOpen(db: SQLiteDatabase) {
            try { db.execSQL("ALTER TABLE vmem ADD COLUMN q8 BLOB") } catch (e: Exception) {}
        }
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

    /**
     * int8 copy of a vector: v[i] / max|v| * 127, one byte per dimension instead of four.
     *
     * Semantic search is I/O-bound, not compute-bound — measured 29,184 vectors in 1.66s, which is ~88MB of
     * float32 BLOBs off SQLite at ~55MB/s. Optimising the arithmetic changed nothing (1730ms → 1658ms). The
     * only way to search the whole brain inside a latency budget is to read fewer bytes, so this stores a
     * quarter-size copy and the scan reads that instead.
     *
     * No scale needs storing: cosine is dot/sqrt(|q|²·|d|²), and a per-vector scale factor s appears once in
     * the numerator and once (via sqrt(s²)) in the denominator, so it cancels exactly. Quantisation costs
     * ~1% of cosine precision, which is far below the gap between a relevant memory and an irrelevant one.
     */
    private fun toQ8(v: FloatArray): ByteArray {
        var max = 0f
        for (x in v) { val a = if (x < 0) -x else x; if (a > max) max = a }
        val out = ByteArray(v.size)
        if (max == 0f) return out
        val k = 127f / max
        for (i in v.indices) {
            val q = Math.round(v[i] * k)
            out[i] = (if (q > 127) 127 else if (q < -127) -127 else q).toByte()
        }
        return out
    }

    /** Cosine between a float query and an int8-quantised stored vector. Scale cancels — see [toQ8]. */
    private fun cosineQ8(q: FloatArray, qNorm2: Float, b: ByteArray): Float {
        if (b.size != q.size) return -1f
        var dot = 0f; var nb = 0f
        for (i in q.indices) {
            val v = b[i].toFloat()          // signed byte, already the quantised magnitude
            dot += q[i] * v; nb += v * v
        }
        val d = Math.sqrt(qNorm2.toDouble() * nb.toDouble()).toFloat()
        return if (d == 0f) -1f else dot / d
    }

    /**
     * Fill in the int8 copies for vectors embedded before this existed, in bounded chunks so it never holds
     * memory or blocks for long. Returns how many were converted; 0 means the brain is fully migrated.
     * Driven by EmbedWorker, which already has a time budget and runs periodically.
     */
    fun migrateQ8(ctx: Context, limit: Int = 2000): Int {
        return try {
            val d = db(ctx)
            val ids = ArrayList<Long>(limit); val blobs = ArrayList<ByteArray>(limit)
            d.rawQuery("SELECT rowid, v FROM vmem WHERE q8 IS NULL AND v IS NOT NULL LIMIT $limit", null).use { c ->
                while (c.moveToNext()) { ids.add(c.getLong(0)); blobs.add(c.getBlob(1)) }
            }
            if (ids.isEmpty()) return 0
            d.beginTransaction()
            try {
                val st = d.compileStatement("UPDATE vmem SET q8=? WHERE rowid=?")
                for (i in ids.indices) {
                    st.clearBindings()
                    st.bindBlob(1, toQ8(toVec(blobs[i]))); st.bindLong(2, ids[i])
                    st.executeUpdateDelete()
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
            ids.size
        } catch (e: Exception) { 0 }
    }

    /** How many vectors still lack their int8 copy — i.e. are reachable only by the slow path. */
    fun unquantizedCount(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT count(*) FROM vmem WHERE q8 IS NULL AND v IS NOT NULL", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }
    private fun toVec(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { b.getFloat() }
    }

    /**
     * Cosine straight off the stored little-endian float32 blob — the scan's hot loop, run once per vector
     * in the brain.
     *
     * The old path did two wasteful things on EVERY row: materialised a fresh FloatArray (a 3KB allocation
     * per vector, ~200MB of garbage across a 67k-vector brain), and recomputed the QUERY's own sum of
     * squares inside cosine() — 768 redundant multiply-adds, 67,000 times over, for a value that cannot
     * change during a scan. Measured 59µs/vector, which is why searching the whole brain took ~4s and the
     * cap had been dialled down to 6,000 rows instead — trading away 91% of the brain's recall for latency.
     *
     * [qNorm2] is the query's sum of squares, computed once by the caller.
     */
    private fun cosineBlob(q: FloatArray, qNorm2: Float, bytes: ByteArray): Float {
        if (bytes.size != q.size * 4) return -1f
        var dot = 0f; var nb = 0f
        var i = 0; var j = 0
        while (i < q.size) {
            val bits = (bytes[j].toInt() and 0xFF) or
                ((bytes[j + 1].toInt() and 0xFF) shl 8) or
                ((bytes[j + 2].toInt() and 0xFF) shl 16) or
                ((bytes[j + 3].toInt() and 0xFF) shl 24)
            val v = Float.fromBits(bits)
            dot += q[i] * v; nb += v * v
            i++; j += 4
        }
        val d = Math.sqrt((qNorm2.toDouble()) * nb.toDouble()).toFloat()
        return if (d == 0f) -1f else dot / d
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
        // 1200, not 4000: the stored body is only for embedding + a short recall snippet (display clips to ~280).
        // Storing 4000 chars/row is what bloated the vector DB to 200MB and made every recall read hundreds of MB.
        val clipped = t.take(1200)
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
        // Research papers — your OWN writing. Previously keyword-only, so asking your brain about an idea
        // in your paper using different words found nothing. Chunk each paper so long ones stay findable.
        try {
            PaperStore.list(ctx).forEach { p ->
                val text = PaperStore.plainText(ctx, p.id)
                if (text.length >= 40) text.chunked(1200).take(8).forEach { chunk ->
                    add("Paper: ${p.title}", "paper", chunk)
                }
            }
        } catch (e: Exception) {}
        // Filed documents (email attachments, scans, receipts, contracts) — the structured summary +
        // extracted fields, so even a doc whose full body didn't reach DocText is still recallable.
        try {
            DocStore.list(ctx).take(perSource).forEach { d ->
                val fields = try {
                    val o = org.json.JSONObject(d.fieldsJson)
                    o.keys().asSequence().joinToString(" · ") { k -> "$k: ${o.optString(k)}" }
                } catch (e: Exception) { "" }
                add("Filed: ${d.title}", "filed",
                    listOfNotNull(d.title, d.category.ifBlank { null }, d.summary.ifBlank { null },
                        fields.ifBlank { null }).joinToString(" · "))
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

    /**
     * ONE-TIME COMPACTION — shrink an already-bloated index. Older rows stored up to 4000 chars of body each,
     * which grew the DB to ~200MB and made every recall read hundreds of MB. Truncate those bodies to 1200 and
     * VACUUM to reclaim the space. Guarded to run once; background-only (VACUUM rewrites the file).
     */
    fun compactOnce(ctx: Context) {
        val meta = ctx.getSharedPreferences("slyos_vec_meta", Context.MODE_PRIVATE)
        if (meta.getBoolean("compacted_v1", false)) return
        try {
            val d = db(ctx)
            d.execSQL("UPDATE vmem SET body = substr(body, 1, 1200) WHERE length(body) > 1200")
            d.execSQL("VACUUM")
            meta.edit().putBoolean("compacted_v1", true).apply()
            HealthStore.note("vec_compact", true, "truncated bodies + vacuumed the vector index")
        } catch (e: Exception) { HealthStore.note("vec_compact", false, e.message ?: "failed") }
    }

    /** Embed up to [cap] queued rows, in batches. Safe to call on app start (off the main thread). */
    fun backfill(ctx: Context, cap: Int = 1000) {
        // SELF-HEAL: if the cloud embedder is sidelined (quota/auth) and the free on-device model isn't
        // downloaded yet, fetch it (~6MB) so semantic memory keeps working instead of staying dead.
        if (EmbeddingClient.provider(ctx) == null || EmbeddingClient.unhealthyReason(ctx).isNotBlank()) {
            if (!OnDeviceEmbedder.ready(ctx)) {
                android.util.Log.i("SlyOS-Perf", "cloud embedder unavailable (${EmbeddingClient.unhealthyReason(ctx)}) — downloading on-device embedder")
                OnDeviceEmbedder.download(ctx)
            }
        }
        val provider = EmbeddingClient.provider(ctx) ?: return
        // If the active provider's dimension differs from what's stored (e.g. we failed over from Gemini's
        // 768-dim to the on-device 100-dim), those old vectors can never match a query again — search filters
        // on dim. Clear them so the loop below re-embeds them in the CURRENT space.
        try {
            // Any provider switch invalidates stored vectors — search filters on dim, so 768-dim vectors are
            // invisible to a 100-dim query. Clear whatever no longer matches the ACTIVE space, not just local.
            val want = if (provider == "local") OnDeviceEmbedder.DIM else 0
            if (want > 0) {
                val stale = db(ctx).compileStatement("SELECT count(*) FROM vmem WHERE v IS NOT NULL AND dim<>$want").simpleQueryForLong()
                if (stale > 0) {
                    android.util.Log.i("SlyOS-Perf", "re-embedding $stale vectors into the $provider space (dim $want)")
                    db(ctx).execSQL("UPDATE vmem SET v=NULL, dim=0 WHERE v IS NOT NULL AND dim<>$want")
                }
            }
        } catch (e: Exception) {}
        compactOnce(ctx)    // one-time: shrink the over-bloated index so recall isn't reading hundreds of MB
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
                            // Write the int8 copy at the same time, so newly embedded memories are on the
                            // fast search path immediately and never need migrating.
                            put("q8", toQ8(vecs[i]))
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
        // SPEED: 2.5s cap (was 8s). A warm embed is well under 1s; a cold/throttled one now fails FAST to
        // keyword recall instead of freezing every reply for 8s. The profile (booking link, about, etc.) is
        // always in context regardless, so this rarely changes the answer — it just stops the hang.
        val tEmbed = System.currentTimeMillis()
        val qv = embedBounded(ctx, query, 2500L) ?: run {
            Fail.log(ctx, "Brain", "semantic recall for \"${query.take(40)}\"",
                "query embedding >2.5s — fell back to keyword search only", "warn")
            android.util.Log.i("SlyOS-Perf", "embed TIMEOUT ${System.currentTimeMillis() - tEmbed}ms for \"${query.take(30)}\"")
            return emptyList()
        }
        android.util.Log.i("SlyOS-Perf", "embed ok ${System.currentTimeMillis() - tEmbed}ms")
        try {
            // HARD LATENCY BUDGET. Measured on-device: scanning 50k vectors meant reading ~150MB of BLOBs from
            // SQLite and cost 21 SECONDS on every single message — it, not the model, was the wait. We scan
            // newest-first and stop at whichever comes first: SCAN_CAP rows or SCAN_BUDGET_MS. Newest-first
            // means we always rank the memories most likely to matter, and recall degrades gracefully (a few
            // of the very oldest may be skipped on a slow device) instead of freezing the whole app.
            val tScan = System.currentTimeMillis()
            val scored = ArrayList<Pair<Long, Float>>(1024)
            var seen = 0
            // The query's own sum of squares is constant for the whole scan — hoist it out of the loop.
            var qNorm2 = 0f
            for (x in qv) qNorm2 += x * x
            // FAST PASS: int8 copies only. Selecting q8 WITHOUT v is the entire point — pulling both columns
            // would read the float32 blob anyway and save nothing.
            db(ctx).rawQuery(
                "SELECT rowid, q8 FROM vmem WHERE q8 IS NOT NULL AND dim=? ORDER BY ts DESC LIMIT $SCAN_CAP",
                arrayOf(qv.size.toString())).use { c ->
                while (c.moveToNext()) {
                    val score = cosineQ8(qv, qNorm2, c.getBlob(1))
                    if (score > 0.20f) scored.add(c.getLong(0) to score)
                    // Check the clock every 512 rows (cheap) so a slow device can't blow the budget.
                    if (++seen and 511 == 0 && System.currentTimeMillis() - tScan > SCAN_BUDGET_MS) break
                }
            }
            // SLOW PASS: anything not yet migrated, with whatever budget is left. This shrinks to nothing as
            // migrateQ8 completes, but until then these memories stay reachable rather than silently absent.
            if (System.currentTimeMillis() - tScan < SCAN_BUDGET_MS) {
                db(ctx).rawQuery(
                    "SELECT rowid, v FROM vmem WHERE q8 IS NULL AND v IS NOT NULL AND dim=? ORDER BY ts DESC LIMIT $SCAN_CAP",
                    arrayOf(qv.size.toString())).use { c ->
                    while (c.moveToNext()) {
                        val score = cosineBlob(qv, qNorm2, c.getBlob(1))
                        if (score > 0.20f) scored.add(c.getLong(0) to score)
                        if (++seen and 511 == 0 && System.currentTimeMillis() - tScan > SCAN_BUDGET_MS) break
                    }
                }
            }
            android.util.Log.i("SlyOS-Perf", "vector scan ${System.currentTimeMillis() - tScan}ms over $seen vectors")
            if (scored.isEmpty()) {
                Fail.log(ctx, "Brain", "semantic recall for \"${query.take(40)}\"",
                    "nothing scored above 0.20 across ${embeddedCount(ctx)} vectors", "warn")
                return emptyList()
            }
            val top = scored.sortedByDescending { it.second }.take(k)
            val scoreById = top.associate { it.first to it.second }
            val ids = top.joinToString(",") { it.first.toString() }
            val hits = ArrayList<Hit>(top.size)
            db(ctx).rawQuery("SELECT rowid, contact, role, body FROM vmem WHERE rowid IN ($ids)", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    hits.add(Hit(c.getString(1), c.getString(2), c.getString(3), scoreById[id] ?: 0f))
                }
            }
            return hits.sortedByDescending { it.score }
        } catch (e: Exception) {
            Fail.log(ctx, "Brain", "semantic search", e.message ?: "query failed")
            return emptyList()
        }
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
