package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * INCOMING — the things other people sent you, already here.
 *
 * Two honest sources:
 *   • Email attachments (Gmail) — contracts, invoices, forms. Exact sender, exact time.
 *   • Recent images on the phone (WhatsApp, Telegram, screenshots, camera) — Android exposes these
 *     through the media store; it does NOT reliably say who sent them, so we show where they came from
 *     (the folder) rather than inventing a sender.
 *
 * Documents inside other chat apps are sandboxed by Android and can't be listed — "Browse" (the system
 * file picker) reaches those in one tap. We never pretend otherwise.
 */
object Inbox {
    private const val TAG = "SlyOS-Inbox"
    private const val PREFS = "slyos_inbox"

    data class Item(
        val uri: Uri?,
        val name: String,
        val who: String,          // "Anna" (email) or "WhatsApp" / "Screenshots" (image folder)
        val source: String,       // "email" | "photo"
        val ts: Long,
        val isPdf: Boolean = false,
        val mail: GmailClient.MailAttachment? = null
    ) {
        val key: String get() = mail?.let { "m:${it.msgId}:${it.attId}" } ?: "u:${uri.toString()}"
    }

    /** Photos that arrived recently from anywhere (WhatsApp, Telegram, screenshots, camera). */
    fun recentImages(ctx: Context, limit: Int = 12): List<Item> {
        val out = mutableListOf<Item>()
        try {
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $limit"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "photo"
                    val bucket = c.getString(2) ?: "Photos"
                    val ts = c.getLong(3) * 1000L
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    out.add(Item(uri, name, bucket, "photo", ts))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "recentImages: ${e.message}") }   // no media permission yet
        return out
    }

    /** Attachments people emailed you (needs Google connected). */
    fun emailAttachments(ctx: Context, limit: Int = 8): List<Item> = try {
        GmailClient.recentAttachments(ctx, limit).map {
            Item(null, it.name, it.sender, "email", it.ts, it.isPdf, it)
        }
    } catch (e: Exception) { emptyList() }

    /** Everything that came in, newest first. Email is fetched over the network — call off the main thread. */
    fun recent(ctx: Context, withEmail: Boolean = true): List<Item> {
        val items = mutableListOf<Item>()
        if (withEmail) items += emailAttachments(ctx)
        items += recentImages(ctx)
        return items.sortedByDescending { it.ts }
    }

    /** Resolve an item to something we can actually open (downloads email attachments on demand). */
    fun resolve(ctx: Context, item: Item): Uri? =
        item.uri ?: item.mail?.let { GmailClient.downloadAttachment(ctx, it) }

    // ── The rare, quiet nudge ─────────────────────────────────────────────────────────────────────
    // Only speaks up when it's genuinely worth it: a document someone emailed you in the last 3 days
    // that you haven't dealt with. Never for ordinary photos. Dismissed once = never shown again.

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun dismiss(ctx: Context, key: String) = prefs(ctx).edit().putBoolean("seen_$key", true).apply()
    private fun seen(ctx: Context, key: String) = prefs(ctx).getBoolean("seen_$key", false)

    /** The one thing worth mentioning right now — or null, which is the normal case. */
    fun nudge(ctx: Context): Item? = nudges(ctx, 1).firstOrNull()

    /** Recent documents people emailed you that you haven't dealt with — up to [max], newest first. */
    fun nudges(ctx: Context, max: Int = 3): List<Item> = try {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        emailAttachments(ctx, 12)
            .filter { it.ts > cutoff && !seen(ctx, it.key) }
            .take(max)
    } catch (e: Exception) { emptyList() }

    /** Plain-language line for the nudge — no jargon, no nagging. */
    fun nudgeLine(item: Item): String {
        val doc = item.name.substringBeforeLast('.').replace('_', ' ').take(36)
        return "${item.who} sent you \"$doc\" — want me to open it?"
    }
}
