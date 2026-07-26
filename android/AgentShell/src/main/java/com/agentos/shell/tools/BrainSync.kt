package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device brain sync against the Supabase `brain_items` table (see ACCOUNT_AND_SYNC.md). This first
 * slice syncs the user PROFILE — the highest-value, smallest cross-device payload (your "About you" text +
 * owner name). It uses the documented last-write-wins rule on `updated_at` (UTC millis). More record kinds
 * (chats, papers, memories) plug into the same push/pull shape later.
 *
 * All calls are blocking; run them on a background thread.
 */
object BrainSync {
    private const val TAG = "SlyOS-Sync"
    private const val PREF = "slyos"
    private const val K_PROFILE_TS = "sync_profile_ts"   // updated_at we last pushed/applied for the profile
    private const val K_LAST_OK = "sync_last_ok"          // wall-clock of the last SUCCESSFUL sync
    private const val K_MSG_TS = "sync_msg_ts"            // ts of the newest message pushed so far
    private const val TABLE = "brain_items"

    /**
     * How many messages one sync run pushes.
     *
     * A brain holding tens of thousands of them cannot go up in a single request — it times out, and
     * a failure halfway leaves nothing to resume from. Capping each run and advancing a watermark
     * means the whole history converges over several syncs rather than never completing once.
     */
    private const val MSG_BATCH = 400
    private const val MSG_CHUNK = 100                     // rows per HTTP upsert

    fun lastOkMs(ctx: Context): Long = prefs(ctx).getLong(K_LAST_OK, 0L)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    data class Result(val ok: Boolean, val message: String)

    /** Push local profile up, then pull the server's latest and apply if it's newer. Returns a status. */
    fun syncNow(ctx: Context): Result {
        if (!AccountStore.signedIn(ctx)) return Result(false, "Sign in first.")
        if (!SupabaseClient.configured()) return Result(false, "Account backend not configured.")
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return Result(false, "Session expired — sign in again.")

        // 1) Push local profile.
        val now = System.currentTimeMillis()
        val body = MemoryStore.about(ctx)
        val data = JSONObject().put("owner", MemoryStore.ownerName(ctx))
        val row = JSONObject()
            .put("user_id", uid).put("kind", "profile").put("client_id", "about")
            .put("title", "Profile").put("body", body).put("data", data)
            .put("updated_at", now).put("deleted", false)
        val pushed = SupabaseClient.upsert(TABLE, token, JSONArray().put(row), "user_id,kind,client_id")
        if (pushed) prefs(ctx).edit().putLong(K_PROFILE_TS, now).apply()

        // Push messages incrementally, oldest first, from wherever the last run stopped.
        //
        // This is what makes "one brain, both phones" true rather than aspirational: until now only
        // the profile, chats and vault crossed over, so a second device knew who its owner was and
        // nothing about anyone they had ever spoken to.
        try {
            val watermark = prefs(ctx).getLong(K_MSG_TS, 0L)
            val batch = MessageStore.since(ctx, watermark, MSG_BATCH)
            if (batch.isNotEmpty()) {
                var highest = watermark
                var sentAll = true
                batch.chunked(MSG_CHUNK).forEach { chunk ->
                    val rows = JSONArray()
                    chunk.forEach { m ->
                        rows.put(JSONObject()
                            .put("user_id", uid).put("kind", "message")
                            // The message hash is stable across devices, so the same message pushed
                            // from two phones lands on one row instead of two.
                            .put("client_id", "msg:" + m.hash)
                            .put("title", m.contact)
                            .put("body", m.body)
                            .put("data", JSONObject()
                                .put("platform", m.platform)
                                .put("role", m.role)
                                .put("ts", m.ts))
                            .put("updated_at", m.ts).put("deleted", false))
                    }
                    if (SupabaseClient.upsert(TABLE, token, rows, "user_id,kind,client_id")) {
                        highest = maxOf(highest, chunk.maxOf { it.ts })
                    } else sentAll = false
                }
                // Only advance the watermark past what actually landed — otherwise a failed chunk is
                // skipped forever and those messages never reach the other device.
                if (sentAll || highest > watermark) {
                    prefs(ctx).edit().putLong(K_MSG_TS, highest).apply()
                }
                Log.i(TAG, "pushed ${batch.size} messages, watermark now $highest")
            }
        } catch (e: Exception) { Log.w(TAG, "message push", e) }

        // Push learned facts — small, and the highest-value thing per byte that crosses over.
        try {
            val facts = MemoryStore.learnedFacts(ctx)
            if (facts.isNotEmpty()) {
                val rows = JSONArray()
                facts.take(400).forEachIndexed { i, f ->
                    rows.put(JSONObject()
                        .put("user_id", uid).put("kind", "fact")
                        .put("client_id", "fact:" + f.hashCode())
                        .put("title", "").put("body", f)
                        .put("updated_at", now).put("deleted", false))
                }
                SupabaseClient.upsert(TABLE, token, rows, "user_id,kind,client_id")
            }
        } catch (e: Exception) { Log.w(TAG, "fact push", e) }

        // Push chat threads up. Body = readable transcript; data.msgs = structured messages for exact
        // reconstruction when another device pulls the thread back down.
        try {
            val chatRows = JSONArray()
            ChatStore.threads(ctx).forEach { t ->
                val msgs = ChatStore.messages(ctx, t.id)
                val text = msgs.joinToString("\n") { (if (it.role == "you") "You: " else "SlyOS: ") + it.text }
                val msgsJson = JSONArray()
                msgs.forEach { m -> msgsJson.put(JSONObject().put("role", m.role).put("text", m.text).put("ts", m.ts)) }
                chatRows.put(JSONObject()
                    .put("user_id", uid).put("kind", "chat").put("client_id", "chat:${t.id}")
                    .put("title", t.title).put("body", text.take(20000))
                    .put("data", JSONObject().put("id", t.id).put("msgs", msgsJson))
                    .put("updated_at", t.updated).put("deleted", false))
            }
            if (chatRows.length() > 0) SupabaseClient.upsert(TABLE, token, chatRows, "user_id,kind,client_id")
        } catch (e: Exception) { Log.w(TAG, "chat push", e) }

        // Push the bank vault as CIPHERTEXT only (end-to-end encrypted; the PIN never leaves the device).
        try {
            if (BankVault.isConfigured(ctx)) {
                val vrow = JSONObject()
                    .put("user_id", uid).put("kind", "vault").put("client_id", "bank")
                    .put("title", "Bank vault").put("body", BankVault.cipherBlob(ctx))
                    .put("data", JSONObject().put("salt", BankVault.saltB64(ctx)))
                    .put("updated_at", BankVault.updatedAt(ctx)).put("deleted", false)
                SupabaseClient.upsert(TABLE, token, JSONArray().put(vrow), "user_id,kind,client_id")
            }
        } catch (e: Exception) { Log.w(TAG, "vault push", e) }

        // 2) Pull the server's rows; apply profile if newer, and reconstruct any chat threads locally.
        var applied = false
        // Collected and inserted in one batch: a per-row insert across a few thousand messages is
        // thousands of separate transactions.
        val pulledMessages = ArrayList<MessageStore.Row>()
        val remote = SupabaseClient.pull(TABLE, token, uid, 0L)
        for (i in 0 until remote.length()) {
            val o = remote.optJSONObject(i) ?: continue
            if (o.optBoolean("deleted")) continue
            val kind = o.optString("kind")
            if (kind == "profile" && o.optString("client_id") == "about") {
                val ts = o.optLong("updated_at")
                val localTs = prefs(ctx).getLong(K_PROFILE_TS, 0L)
                if (ts > localTs) {
                    val serverBody = o.optString("body")
                    if (serverBody.isNotBlank() && serverBody != body) { MemoryStore.setAbout(ctx, serverBody); applied = true }
                    prefs(ctx).edit().putLong(K_PROFILE_TS, ts).apply()
                }
            } else if (kind == "chat") {
                try {
                    val data = o.optJSONObject("data") ?: continue
                    val tid = data.optLong("id").takeIf { it != 0L } ?: continue
                    val msgsJson = data.optJSONArray("msgs") ?: JSONArray()
                    val msgs = (0 until msgsJson.length()).mapNotNull { j ->
                        val mo = msgsJson.optJSONObject(j) ?: return@mapNotNull null
                        ChatStore.Msg(mo.optString("role"), mo.optString("text"), mo.optLong("ts"))
                    }
                    ChatStore.importThread(ctx, tid, o.optString("title"), o.optLong("updated_at"), msgs)
                } catch (e: Exception) { Log.w(TAG, "chat pull row", e) }
            } else if (kind == "message") {
                // A message from another device. Deduped by the store's own content hash, so pulling
                // the same row twice — or pulling back something this phone pushed — adds nothing.
                try {
                    val data = o.optJSONObject("data")
                    val platform = data?.optString("platform").orEmpty().ifBlank { "Synced" }
                    val role = data?.optString("role").orEmpty()
                    val contact = o.optString("title")
                    val body = o.optString("body")
                    if (body.isNotBlank() && contact.isNotBlank()) {
                        pulledMessages.add(MessageStore.Row(
                            contact, platform, contact, role.ifBlank { "them" },
                            body, o.optLong("updated_at")))
                    }
                } catch (e: Exception) { Log.w(TAG, "message pull row", e) }
            } else if (kind == "fact") {
                try {
                    val f = o.optString("body")
                    if (f.isNotBlank()) MemoryStore.addLearnedFact(ctx, f)
                } catch (e: Exception) { Log.w(TAG, "fact pull row", e) }
            } else if (kind == "vault" && o.optString("client_id") == "bank") {
                try {
                    val salt = o.optJSONObject("data")?.optString("salt").orEmpty()
                    BankVault.importFromSync(ctx, salt, o.optString("body"), o.optLong("updated_at"))
                } catch (e: Exception) { Log.w(TAG, "vault pull", e) }
            }
        }
        // One batch, deduped by content hash — so anything this phone already has is skipped rather
        // than duplicated, including rows it pushed itself on an earlier run.
        if (pulledMessages.isNotEmpty()) {
            try {
                val added = MessageStore.insertBatchDedupe(ctx, pulledMessages)
                if (added > 0) { applied = true; Log.i(TAG, "pulled $added new messages") }
            } catch (e: Exception) { Log.w(TAG, "message pull insert", e) }
        }

        if (pushed || applied) prefs(ctx).edit().putLong(K_LAST_OK, System.currentTimeMillis()).apply()
        return when {
            !pushed && !applied -> Result(false, "Sync failed: " + SupabaseClient.lastError.ifBlank { "couldn't reach the server." })
            applied -> Result(true, "Synced — pulled newer data from another device.")
            else -> Result(true, "Synced ✓")
        }
    }

    /** Fire-and-forget background sync (e.g. right after sign-in). */
    fun syncInBackground(ctx: Context) {
        Thread { try { syncNow(ctx) } catch (e: Exception) { Log.w(TAG, "bg sync", e) } }.start()
    }
}
