package com.agentos.shell.tools

import android.content.Context
import org.json.JSONObject

/**
 * THE OPEN DOCUMENT.
 *
 * When you attach something, it does not evaporate after one answer. It stays open — its text, where it
 * came from, and who sent it — and it is injected into EVERY AI in SlyOS (Home, Chat, Research). That is
 * why "reply to that email" works two turns later: the brain still has the document AND the sender.
 *
 * Cleared when you say so, or when you attach something else.
 */
object AttachContext {
    private const val PREFS = "slyos_attach"

    data class Open(
        val uri: String,
        val name: String,
        val text: String = "",        // extracted contents (PDFs), trimmed
        val fromName: String = "",    // "Anna"
        val fromEmail: String = "",   // "anna@x.com"  → lets the AI actually reply
        val subject: String = "",
        val msgId: String = "",
        val ts: Long = System.currentTimeMillis()
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun set(ctx: Context, o: Open) {
        val j = JSONObject()
            .put("uri", o.uri).put("name", o.name).put("text", o.text.take(20000))
            .put("fromName", o.fromName).put("fromEmail", o.fromEmail)
            .put("subject", o.subject).put("msgId", o.msgId).put("ts", o.ts)
        prefs(ctx).edit().putString("open", j.toString()).apply()
    }

    fun get(ctx: Context): Open? = try {
        val s = prefs(ctx).getString("open", "") ?: ""
        if (s.isBlank()) null else JSONObject(s).let {
            Open(
                it.optString("uri"), it.optString("name"), it.optString("text"),
                it.optString("fromName"), it.optString("fromEmail"),
                it.optString("subject"), it.optString("msgId"), it.optLong("ts")
            )
        }
    } catch (e: Exception) { null }

    fun clear(ctx: Context) = prefs(ctx).edit().remove("open").apply()

    /** Update just the extracted text (after we read a PDF), keeping the sender info. */
    fun setText(ctx: Context, text: String) { get(ctx)?.let { set(ctx, it.copy(text = text)) } }

    /**
     * What every AI is told. Plain, factual, and it explicitly hands over the reply address so the model
     * can act on it instead of claiming it "doesn't have the email".
     */
    fun brief(ctx: Context): String {
        val o = get(ctx) ?: return ""
        return buildString {
            append("OPEN DOCUMENT — the user has \"").append(o.name).append("\" attached right now. ")
            if (o.fromEmail.isNotBlank())
                append("It was emailed to them by ").append(o.fromName.ifBlank { o.fromEmail })
                    .append(" <").append(o.fromEmail).append(">")
                    .append(if (o.subject.isNotBlank()) ", subject \"${o.subject}\"" else "").append(". ")
                    .append("If the user asks you to reply/respond to that email, send it to ")
                    .append(o.fromEmail).append(" — you already have the address, never say you don't. ")
            if (o.text.isNotBlank())
                append("Its contents:\n---\n").append(o.text.take(12000)).append("\n---\n")
            append("Refer to this document naturally when the user says \"it\", \"this\", \"the document\" or \"that email\". ")
        }
    }
}
