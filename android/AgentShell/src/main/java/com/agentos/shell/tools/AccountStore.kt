package com.agentos.shell.tools

import android.content.Context

/**
 * Local SlyOS account session — persists the Supabase tokens + identity on-device so the user stays signed
 * in. The account is the anchor for cross-device sync (see ACCOUNT_AND_SYNC.md). Sign-in/up run through
 * [SupabaseClient]; this just holds the result and exposes a fresh access token (auto-refreshing on 401).
 */
object AccountStore {
    private const val PREF = "slyos"
    private const val K_AT = "acct_access"
    private const val K_RT = "acct_refresh"
    private const val K_UID = "acct_uid"
    private const val K_EMAIL = "acct_email"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun signedIn(ctx: Context): Boolean = prefs(ctx).getString(K_UID, "").orEmpty().isNotBlank()
    fun email(ctx: Context): String = prefs(ctx).getString(K_EMAIL, "").orEmpty()
    fun userId(ctx: Context): String = prefs(ctx).getString(K_UID, "").orEmpty()
    fun accessToken(ctx: Context): String = prefs(ctx).getString(K_AT, "").orEmpty()
    fun refreshToken(ctx: Context): String = prefs(ctx).getString(K_RT, "").orEmpty()

    fun save(ctx: Context, s: SupabaseClient.Session) {
        prefs(ctx).edit().putString(K_AT, s.accessToken).putString(K_RT, s.refreshToken)
            .putString(K_UID, s.userId).putString(K_EMAIL, s.email).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(K_AT).remove(K_RT).remove(K_UID).remove(K_EMAIL).apply()
    }

    /** Sign up; on success (with a session) persist it. Returns (ok, message). */
    fun signUp(ctx: Context, email: String, password: String): Pair<Boolean, String> {
        val r = SupabaseClient.signUp(email.trim(), password)
        if (!r.ok) return false to r.error
        if (r.session != null) { save(ctx, r.session); return true to "Account created." }
        return true to "Check your email to confirm, then sign in."
    }

    fun signIn(ctx: Context, email: String, password: String): Pair<Boolean, String> {
        val r = SupabaseClient.signIn(email.trim(), password)
        if (!r.ok || r.session == null) return false to r.error
        save(ctx, r.session)
        return true to "Signed in."
    }

    fun signOut(ctx: Context) {
        val at = accessToken(ctx)
        if (at.isNotBlank()) SupabaseClient.signOut(at)
        clear(ctx)
    }

    /** A usable access token, refreshing it first if we have a refresh token. Returns "" if not signed in. */
    fun freshAccessToken(ctx: Context): String {
        val rt = refreshToken(ctx)
        if (rt.isNotBlank()) {
            val s = SupabaseClient.refresh(rt)
            if (s != null) { save(ctx, s); return s.accessToken }
        }
        return accessToken(ctx)
    }
}
