package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The documents people send you — filed, classified and findable.
 *
 * SlyOS knows about two kinds of document and not the third. [SlyFolder] holds what it GENERATED;
 * DocsScreen holds what was SCANNED with the camera. Everything that arrives by email — every bill,
 * invoice, insurance policy, contract, bank statement, ticket and tax form — was passing through
 * untouched. The expense parser reads a receipt email for its total and throws the PDF away.
 *
 * So the most consequential paperwork in someone's life was the one category the app could not
 * answer a question about. "Where's my Verizon bill", "do I have the lease", "what did the insurance
 * renewal say" — all unanswerable, while the attachments sat in Gmail two taps away and completely
 * unindexed.
 *
 * Classification is deterministic, from the filename, the subject and the sender. Not a model call:
 * the words that identify a bill are the word "bill", and paying for an inference to discover that
 * would be absurd — as well as slower, and wrong occasionally rather than never. A model gets
 * involved only where the words genuinely do not say, and even then it only picks from this list.
 *
 * Nothing is downloaded during a scan. An attachment is fetched when it is opened, because filing a
 * hundred PDFs onto the phone to make them searchable would cost storage for a benefit the index
 * already provides.
 */
object ReceivedDocs {

    private const val PREFS = "slyos_received"
    private const val KEY = "docs"

    /** The categories paperwork actually falls into, in the order they matter when overdue. */
    enum class Kind { BILL, INVOICE, RECEIPT, STATEMENT, INSURANCE, CONTRACT, TAX, TICKET, ID, OTHER }

    data class Doc(
        val msgId: String,
        val attId: String,
        val name: String,
        val mime: String,
        val from: String,
        val subject: String,
        val ts: Long,
        val kind: Kind,
        /** An amount, when the subject or filename states one — never inferred. */
        val amount: String = ""
    ) {
        /** Who it is really from: a company name beats a no-reply address. */
        val sender: String get() {
            val n = from.substringBefore('<').trim().trim('"')
            if (n.isNotBlank() && !n.contains('@')) return n
            val dom = from.substringAfter('@', "").substringBefore('>').substringBefore('.')
            return dom.replaceFirstChar { it.uppercase() }.ifBlank { from }
        }
    }

    fun label(k: Kind): String = when (k) {
        Kind.BILL -> "Bill"; Kind.INVOICE -> "Invoice"; Kind.RECEIPT -> "Receipt"
        Kind.STATEMENT -> "Statement"; Kind.INSURANCE -> "Insurance"; Kind.CONTRACT -> "Contract"
        Kind.TAX -> "Tax"; Kind.TICKET -> "Ticket"; Kind.ID -> "ID"; Kind.OTHER -> "Document"
    }

    // MARK: - Classification

    /**
     * What kind of paperwork this is, from the words on it.
     *
     * Ordered by specificity rather than alphabetically, because the categories overlap: an
     * "insurance invoice" is insurance, and a "tax statement" is tax. The first rule to match wins,
     * so the more specific tests come first — get that order wrong and every insurance document
     * files itself as an invoice.
     */
    fun classify(name: String, subject: String, from: String): Kind {
        val hay = "$name $subject $from".lowercase()
        // WORD BOUNDARIES, BECAUSE SUBSTRINGS LIE.
        //
        // A real indexed document: "Agenda — test" filed as a CONTRACT, because the keyword "nda"
        // matches inside "Age-NDA". That is the third time today the same root cause has produced a
        // confident wrong answer — "round" matched "G-round" in the connection search, and before
        // that a `LIKE` matched surnames. A contains() test on a keyword list is a trap every time.
        fun has(vararg w: String) = w.any { term ->
            if (term.contains(' ')) hay.contains(term)      // multi-word terms are already specific
            else Regex("\\b" + Regex.escape(term) + "\\b").containsMatchIn(hay)
        }
        return when {
            has("insurance", "versicherung", "policy", "coverage", "assurance") -> Kind.INSURANCE
            has("tax", "steuer", "1099", "w-2", "w2 ", "irs", "hmrc", "vat return") -> Kind.TAX
            has("contract", "agreement", "vertrag", "nda", "terms of service", "lease",
                "tenancy", "employment offer") -> Kind.CONTRACT
            has("boarding", "ticket", "itinerary", "reservation", "e-ticket", "booking confirm",
                "flight") -> Kind.TICKET
            has("passport", "driver", "licence", "license", "id card", "visa application",
                "residence permit") -> Kind.ID
            // Bill before invoice: "your bill is ready" is a bill even when the file says invoice.
            has("bill", "rechnung", "utility", "electricity", "gas bill", "water bill", "broadband",
                "mobile bill", "past due", "amount due", "payment due") -> Kind.BILL
            has("invoice", "faktura", "pro forma") -> Kind.INVOICE
            has("statement", "kontoauszug", "account summary", "balance") -> Kind.STATEMENT
            has("receipt", "order confirm", "your order", "purchase", "paid", "quittung") -> Kind.RECEIPT
            else -> Kind.OTHER
        }
    }

    /** An amount only if it is written down. A guessed figure on a bill is worse than none. */
    fun amountIn(name: String, subject: String): String =
        Regex("([€$£]\\s?\\d[\\d.,]{0,9})|(\\d[\\d.,]{0,9}\\s?(EUR|USD|GBP))")
            .find("$subject $name")?.value?.trim().orEmpty()

    // MARK: - Store

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Doc> = try {
        val arr = JSONArray(p(ctx).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                Doc(o.optString("m"), o.optString("a"), o.optString("n"), o.optString("t"),
                    o.optString("f"), o.optString("s"), o.optLong("ts"),
                    runCatching { Kind.valueOf(o.optString("k")) }.getOrDefault(Kind.OTHER),
                    o.optString("amt"))
            }
        }.sortedByDescending { it.ts }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, docs: List<Doc>) {
        val arr = JSONArray()
        // Bounded and newest-first, so the cap drops the oldest rather than whatever came last.
        docs.sortedByDescending { it.ts }.take(300).forEach { d ->
            arr.put(JSONObject().put("m", d.msgId).put("a", d.attId).put("n", d.name)
                .put("t", d.mime).put("f", d.from).put("s", d.subject).put("ts", d.ts)
                .put("k", d.kind.name).put("amt", d.amount))
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Index whatever has arrived.
     *
     * Keyed on message id plus filename, NOT on Gmail's attachmentId — that is regenerated on every
     * request, so keying on it would re-file the same document endlessly. (The same trap Inbox
     * already documents having fallen into.)
     */
    fun scan(ctx: Context, limit: Int = 60): Int {
        if (!GoogleAuth.isConnected(ctx)) return 0
        val existing = all(ctx)
        val seen = existing.map { it.msgId + "|" + it.name }.toHashSet()
        val fresh = ArrayList<Doc>()
        try {
            GmailClient.recentAttachmentsCached(ctx, limit).forEach { a ->
                val key = a.msgId + "|" + a.name
                if (key in seen) return@forEach
                // Inline images and signature logos are not paperwork.
                if (a.mime.startsWith("image/") && a.name.length < 12) return@forEach
                // Nor are calendar invites, which arrive as an .ics on every accept and decline and
                // filled the index with "Declined: smooch" twice over. The calendar owns those.
                if (a.name.endsWith(".ics", true) || a.mime.contains("calendar", true) ||
                    a.name.equals("invite.ics", true)) return@forEach
                fresh.add(Doc(a.msgId, a.attId, a.name, a.mime, a.from, a.subject, a.ts,
                    classify(a.name, a.subject, a.from), amountIn(a.name, a.subject)))
            }
        } catch (e: Exception) { return 0 }
        if (fresh.isEmpty()) return 0
        save(ctx, existing + fresh)
        // Into the brain, so "have I had the insurance renewal yet" is answerable from anywhere
        // rather than only from whatever screen ends up showing this.
        fresh.take(20).forEach { d ->
            try {
                Brain.remember(ctx, "note", "${label(d.kind)} from ${d.sender}",
                    "${d.name} — ${d.subject}" + (if (d.amount.isNotBlank()) " (${d.amount})" else ""),
                    role = "system")
            } catch (e: Exception) {}
        }
        return fresh.size
    }

    fun byKind(ctx: Context, kind: Kind): List<Doc> = all(ctx).filter { it.kind == kind }

    /** Free-text over sender, subject, filename and category — the four things people remember. */
    fun search(ctx: Context, q: String): List<Doc> {
        val t = q.trim()
        if (t.length < 2) return all(ctx)
        return all(ctx).filter {
            it.name.contains(t, true) || it.subject.contains(t, true) ||
                it.from.contains(t, true) || label(it.kind).contains(t, true)
        }
    }

    /** Fetch and open one — downloaded on demand, never during a scan. */
    fun open(ctx: Context, d: Doc): Boolean = try {
        val att = GmailClient.MailAttachment(d.msgId, d.attId, d.name, d.mime, d.from, d.subject, d.ts)
        val uri = GmailClient.downloadAttachment(ctx, att)
        if (uri != null) DocForge.open(ctx, uri.toString(), d.name) else false
    } catch (e: Exception) { false }

    // MARK: - The brain

    fun isDocQuestion(q: String): Boolean = Regex(
        "(?i)\\b(bill|invoice|receipt|statement|insurance|policy|contract|lease|agreement|tax|" +
        "boarding pass|ticket|attachment|attached|pdf|document|paperwork|" +
        "did i (get|receive)|have i (got|received)|where('s| is) my|send me the)\\b"
    ).containsMatchIn(q)

    /**
     * What has arrived, for a question about paperwork.
     *
     * Matched against the question first so a specific ask gets the specific document, and the
     * recent set otherwise — because "what bills came in" and "where's my Verizon bill" want
     * different answers and both are common.
     */
    fun contextFor(ctx: Context, q: String): String {
        val docs = all(ctx)
        if (docs.isEmpty()) return ""
        val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }
        val hits = docs.filter { d ->
            words.any { w ->
                d.sender.contains(w, true) || d.subject.contains(w, true) ||
                    d.name.contains(w, true) || label(d.kind).contains(w, true)
            }
        }
        val show = if (hits.isNotEmpty()) hits.take(10) else docs.take(10)
        return buildString {
            append(if (hits.isNotEmpty()) "DOCUMENTS YOU'VE BEEN SENT that match this:\n"
                   else "DOCUMENTS YOU'VE BEEN SENT recently:\n")
            val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
            show.forEach { d ->
                append("· ").append(label(d.kind)).append(" from ").append(d.sender)
                    .append(" — ").append(d.name)
                if (d.amount.isNotBlank()) append(" (").append(d.amount).append(")")
                append(", ").append(fmt.format(java.util.Date(d.ts))).append("\n")
            }
        }.take(1600)
    }
}
