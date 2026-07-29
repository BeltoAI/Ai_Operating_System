package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * What each step produced, so the next step can use it.
 *
 * [ToolRouter.executeActions] loops over every action and survives a failure in any one of them —
 * that part works. What was missing is a channel *between* the steps: every argument is written by
 * the planner before anything runs, so step 2 refers to a thing that does not exist yet.
 *
 * "Make a one-pager on the pilot and email it to Carlos" therefore created a document, sent Carlos
 * an email with nothing attached, and reported both as done. The document was real, the email was
 * real, and the sentence joining them was false. Same for "set up a call with a meet link and text
 * her the link" — the text went out promising a link that was never in it.
 *
 * That last one is the worst version, because the recipient sees the failure before the owner does.
 *
 * Two jobs here:
 *  - **carry** what a step produced forward, read from the store the step actually wrote to rather
 *    than from the sentence it returned; and
 *  - **stop** a step whose subject never came into existence, instead of running it empty.
 *
 * Deliberately narrow. It only joins steps within a single turn, and only where the join is
 * unambiguous — a document made moments ago followed by an email is one request, not two.
 */
object ActionChain {

    /** Something a step brought into the world, in the terms a later step might need. */
    data class Produced(
        val kind: String,               // "doc" | "event"
        val title: String = "",
        /** A Meet or Calendar link — the thing people ask to be sent. */
        val url: String = "",
        /** Where the file lives, for attaching. */
        val uri: String = ""
    )

    // MARK: - Capture

    /**
     * What this step produced, or null if it produced nothing usable.
     *
     * Documents are read back from [DocForge.library], which is the authoritative record, rather
     * than parsed out of the reply. Links are taken from the reply because that is where the
     * Calendar API's own response ends up, and it is our own sentence, not the model's.
     */
    fun capture(ctx: Context, type: String, arg: String, message: String): Produced? = try {
        when (type) {
            "create_document", "refine_document", "make_doc", "document" -> {
                // A failure reply must not become a bus entry, or the next step attaches a stale
                // file from last week and nobody notices it is the wrong one.
                if (Regex("(?i)couldn'?t|what format").containsMatchIn(message)) null
                else DocForge.library(ctx).firstOrNull()?.let {
                    Produced("doc", title = it.name, uri = it.uri)
                }
            }
            "add_event" -> {
                val link = URL.find(message)?.value.orEmpty()
                val title = try { JSONObject(arg).optString("title") } catch (e: Exception) { "" }
                // No link and no confirmation means nothing was created — an ambiguity question
                // ("Which Anna?") comes back through the same return value.
                if (link.isBlank() && !Regex("(?i)\\b(created|added|booked|invit)").containsMatchIn(message)) null
                else Produced("event", title = title, url = link)
            }
            else -> null
        }
    } catch (e: Exception) { null }

    private val URL = Regex("https?://[^\\s)\"']+")

    // MARK: - Across the confirmation card

    /**
     * What the last batch produced, for the steps that run *after* the owner taps Confirm.
     *
     * Sending an email is confirmable, so it does not run in the same [ToolRouter.executeActions]
     * call as the document it is meant to carry — the document is executed immediately, the email
     * waits on a card, and by the time it runs the in-memory bus has gone. Without this the
     * attachment would be resolved for exactly the requests that do not need confirming, and lost
     * for every one that does, which is all of the ones involving another person.
     *
     * Time-boxed. A document made half an hour ago must never quietly attach itself to an unrelated
     * email — the join is only obvious while the request is still the one being worked on.
     */
    @Volatile private var recent: List<Produced> = emptyList()
    @Volatile private var recentAt: Long = 0L
    private const val RECENT_MS = 5 * 60_000L

    fun publishBatch(produced: List<Produced>) {
        if (produced.isEmpty()) return
        recent = produced
        recentAt = System.currentTimeMillis()
    }

    private fun recentOrEmpty(): List<Produced> =
        if (System.currentTimeMillis() - recentAt < RECENT_MS) recent else emptyList()

    // MARK: - Resolve

    /**
     * Fill this step's argument from what earlier steps produced.
     *
     * Handles explicit placeholders when the planner emits them, and back-fills the obvious join
     * when it does not — which is most of the time, because a planner that could reliably reference
     * a not-yet-existing file would not have needed this file written.
     */
    fun resolve(ctx: Context, type: String, arg: String, bus: List<Produced>): String {
        // Fall back to what the previous batch produced, so a confirmed email still finds the
        // document that was created moments before the card appeared.
        val pool = if (bus.isNotEmpty()) bus else recentOrEmpty()
        if (pool.isEmpty()) return arg
        val doc = pool.lastOrNull { it.kind == "doc" }
        val event = pool.lastOrNull { it.kind == "event" && it.url.isNotBlank() }

        // Placeholders first — if the planner said what it wanted, honour exactly that.
        var out = arg
        if (out.contains("$")) {
            doc?.let { out = out.replace("\$prev.file", it.uri).replace("\$last_doc", it.title) }
            event?.let { out = out.replace("\$prev.url", it.url).replace("\$last_event", it.url) }
        }

        return when (type) {
            "send_email", "email" -> {
                val o = try { JSONObject(out) } catch (e: Exception) { return out }
                // ATTACH THE THING THAT WAS JUST MADE.
                // A document created seconds earlier, followed by an email, is one request. The
                // owner said "email it" — "it" is on the bus.
                // The name travels with the uri: a MediaStore uri ends in a row id, so deriving a
                // filename from it would attach "1000004821" instead of "Pilot one-pager.pdf".
                if (doc != null && o.optString("attach").isBlank()) {
                    o.put("attach", doc.uri).put("attach_name", doc.title)
                }
                if (event != null) o.put("body", withLink(o.optString("body"), event.url))
                o.toString()
            }
            "send_sms", "message" -> {
                val o = try { JSONObject(out) } catch (e: Exception) {
                    // A bare-string SMS argument still deserves the link.
                    return if (event != null) withLink(out, event.url) else out
                }
                if (event != null) {
                    val key = if (o.has("text")) "text" else "body"
                    o.put(key, withLink(o.optString(key), event.url))
                }
                o.toString()
            }
            else -> out
        }
    }

    /**
     * A message that promises a link, given the link.
     *
     * If a URL is already in there the model found one somewhere — leave it alone rather than
     * appending a second, because two links in one message is its own kind of wrong.
     */
    private fun withLink(body: String, url: String): String {
        if (url.isBlank()) return body
        if (URL.containsMatchIn(body)) return body
        val b = body.trim()
        return if (b.isBlank()) url else "$b\n\n$url"
    }

    // MARK: - Block

    /**
     * Why this step must not run, or null if it may.
     *
     * THE POINT OF THE WHOLE FILE. When the document was never created, sending the email anyway
     * produces a message to a real person with the important part missing, and a report that says
     * both steps succeeded. Not sending it, and saying why, is the only honest outcome — the owner
     * can then decide, which they could not do when they were told it had worked.
     */
    fun blockedBy(type: String, arg: String, bus: List<Produced>, failedKinds: Set<String>): String? {
        if (failedKinds.isEmpty()) return null
        // send_document is unconditional: sending a document is the ONLY thing it does, so with no
        // document there is nothing left of the step.
        if ("doc" in failedKinds && bus.none { it.kind == "doc" } &&
            type in setOf("send_document", "send_doc"))
            return "**Nothing was sent** — the document was never created."

        val wantsDoc = "doc" in failedKinds && bus.none { it.kind == "doc" } &&
            (type == "send_email" || type == "email") &&
            Regex("(?i)\\b(attach|attached|the (doc|document|deck|file|sheet|report|one.?pager)|" +
                "\\bit\\b)\\b").containsMatchIn(arg)
        if (wantsDoc) return "**No email went out** — the document it was meant to carry was never created."

        val wantsLink = "event" in failedKinds && bus.none { it.kind == "event" } &&
            type in setOf("send_sms", "message", "send_email", "email") &&
            Regex("(?i)\\b(link|invite|invitation|meet|joining|calendar)\\b").containsMatchIn(arg)
        if (wantsLink) return "**Nothing was sent** — there's no invite link, because the event was never created."

        return null
    }

    // MARK: - The missing second step

    /**
     * The delivery step the planner left out, or null when the request had no second half.
     *
     * Observed on a device: *"make a one-pager pdf about the SlyOS pilot and email it to
     * eshir010@ucr.edu"* produced exactly one action — `create_document`. The email was not planned
     * at all, so there was no second step for a result bus to feed. The document appeared and
     * nobody was told the sending half had silently gone missing.
     *
     * This is the same shape as [CalendarIntent] and [ScreenIntent]: the two halves of a plainly
     * two-part request must not depend on the model remembering the second one. The address or name
     * is read straight out of the sentence, and nothing is invented — no recipient, no action.
     */
    fun missingDelivery(prompt: String, actions: List<AgentAction>): AgentAction? {
        // Only when something was made and nothing is being sent.
        if (actions.none { producesKind(it.type) == "doc" }) return null
        if (actions.any { it.type in setOf("send_email", "email", "send_doc", "send_document") }) return null

        // "and email it to X", "then send it to X", "email that to X".
        // The recipient is an address OR a name. An address must be matched as a whole — a plain
        // "everything up to punctuation" capture stops at the dot in ucr.edu and hands the sender
        // "eshir010@ucr", which is refused downstream as not being an address at all.
        val m = Regex("(?i)\\b(?:and |then |, )?\\b(e-?mail|send)\\b\\s+(?:it|that|the \\w+)?\\s*" +
            "\\bto\\b\\s+([^\\s,;]+@[^\\s,;]+|[A-Za-z][\\w' -]{1,40})").find(prompt) ?: return null
        val whoRaw = m.groupValues[2].trim().trimEnd('.', '!', '?', ',')
        if (whoRaw.isBlank() || whoRaw.length > 60) return null
        // "send it to the printer", "email it to myself later" — a recipient has to look like one.
        if (Regex("(?i)^(the |a |an )?(printer|cloud|drive|folder|trash)\\b").containsMatchIn(whoRaw)) return null

        // What the document is about, for a subject line that isn't "(no subject)".
        // "about X" and "on X" both name the subject — "a short pdf ON on-device latency" is as
        // common as "about", and missing it fell back to "The document you asked for", which tells
        // the recipient nothing.
        val about = Regex("(?i)\\b(?:about|on|covering|explaining)\\s+([^,.;]+?)(?:\\s+and\\b|$)")
            .find(prompt)?.groupValues?.get(1)?.trim().orEmpty()
        val subject = about.ifBlank { "The document you asked for" }
            .replaceFirstChar { it.uppercase() }.take(70)

        return AgentAction("send_email", JSONObject()
            .put("to", whoRaw)
            .put("subject", subject)
            // A body is required downstream, and an empty one would be refused. The attachment is
            // the point of this email, so the body says so rather than padding it out.
            .put("body", "Here's the one you asked for — it's attached.")
            .toString())
    }

    /**
     * The request with its delivery clause removed.
     *
     * The document half of a two-part request gets the WHOLE sentence as its brief, so the title
     * derived from it came out as *"make a one-pager pdf about edge inference and email it to
     * es.pdf"* — a filename carrying the recipient's address, which then goes out as the subject
     * line and the attachment name. Who it is for is not part of what it is about.
     */
    fun stripDelivery(text: String): String {
        val cut = Regex("(?i)\\s*(?:,|\\band\\b|\\bthen\\b)\\s*(e-?mail|send|share|text)\\b" +
            "[^.]{0,60}?\\bto\\b\\s+\\S+.*$").replace(text, "")
        return cut.trim().trimEnd(',', ';', '-').ifBlank { text }
    }

    /** Which bus kind an action type is supposed to produce, for tracking what failed. */
    fun producesKind(type: String): String? = when (type) {
        "create_document", "refine_document", "make_doc", "document" -> "doc"
        "add_event" -> "event"
        else -> null
    }

    /** A content:// uri read into a real file, because Gmail attaches files, not uris. */
    fun asFile(ctx: Context, uri: String, name: String): java.io.File? = try {
        val out = java.io.File(ctx.cacheDir, name.ifBlank { "attachment" })
        ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        if (out.length() > 0) out else null
    } catch (e: Exception) { null }
}
