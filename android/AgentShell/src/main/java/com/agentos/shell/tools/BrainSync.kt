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
    private const val TABLE = "brain_items"

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

        // 2) Pull the server's rows; apply profile if newer, and reconstruct any chat threads locally.
        var applied = false
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
            }
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
