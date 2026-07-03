package com.agentos.shell.tools

import android.content.Context

/**
 * P5.2 — per-CONTACT trust tiers, an axis on top of the per-app mode. Even inside a full-auto app, a
 * first-time / unknown sender should be DRAFTED, not auto-sent, until you trust them. Known contacts you
 * mark trusted get full auto. Stored on-device.
 *
 * Tiers: "full" (auto-send), "draft" (pre-write, wait for tap), "off" (do nothing). Unset contacts fall
 * back to [defaultFor]: someone already in your brain/contacts = inherit the app mode; a brand-new unknown
 * sender = draft.
 */
object TrustStore {
    private const val PREF = "slyos_trust"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun key(contact: String) = "t_" + contact.lowercase().trim()

    fun tier(ctx: Context, contact: String): String? = prefs(ctx).getString(key(contact), null)
    fun setTier(ctx: Context, contact: String, tier: String) = prefs(ctx).edit().putString(key(contact), tier).apply()
    fun clear(ctx: Context, contact: String) = prefs(ctx).edit().remove(key(contact)).apply()

    /** True if we've ever seen this contact before (in the brain DB or phone contacts) — i.e. not a stranger. */
    private fun isKnown(ctx: Context, contact: String): Boolean {
        if (contact.isBlank()) return false
        return try {
            MessageStore.threadFor(ctx, contact, 1).isNotEmpty() ||
                (ContactsTool.canRead(ctx) && ContactsTool.resolve(ctx, contact) is ContactsTool.Resolution.Found)
        } catch (e: Exception) { false }
    }

    /**
     * Resolve the effective tier for [contact] given the app's [appMode]. Explicit per-contact tier wins.
     * Otherwise: a KNOWN contact inherits the app mode; an UNKNOWN/new sender is capped at "draft" even in
     * a full-auto app — never auto-send to a stranger on the first contact.
     */
    fun effectiveTier(ctx: Context, contact: String, appMode: String): String {
        tier(ctx, contact)?.let { return it }
        if (appMode == "full" && !isKnown(ctx, contact)) return "draft"
        return appMode
    }
}
