package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * ONE DOCUMENT PIPELINE, EVERY FORMAT.
 *
 * Before this, output was split and inconsistent: Google Workspace made plain Docs/Sheets/Slides, and a
 * separate HTML path made the good-looking stuff. Now a single call produces html · pdf · docx · pptx ·
 * xlsx from the same designed content, so the format is the user's choice rather than a quality trade-off.
 *
 * Every produced file follows the same route:
 *      design/build → save into the SlyOS folder (visible in Files/Downloads) → index into the brain
 * so it is recallable ("send me that deck", "what was in the Q3 one-pager") and can be fetched back out
 * and re-shared later. Nothing is generated that the brain doesn't know about.
 */
object DocForge {
    private const val TAG = "SlyOS-DocForge"

    val FORMATS = listOf("pdf", "docx", "pptx", "xlsx", "html")

    data class Made(val ok: Boolean, val format: String = "", val name: String = "",
                    val uri: Uri? = null, val path: String = "", val error: String = "")

    private fun mimeFor(fmt: String) = when (fmt) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "text/html"
    }





    /**
     * Which format did the user actually ask for? Returns "" when they didn't say — the caller should
     * then ASK rather than silently guessing, since format is a real preference.
     */
    fun formatFrom(text: String): String {
        val t = text.lowercase()
        return when {
            Regex("\\bpptx?\\b|powerpoint|slide deck|\\bdeck\\b|presentation|slides").containsMatchIn(t) -> "pptx"
            Regex("\\bdocx?\\b|word doc|word file|\\bword\\b").containsMatchIn(t) -> "docx"
            Regex("\\bxlsx?\\b|excel|spreadsheet|\\bsheet\\b|tracker|budget").containsMatchIn(t) -> "xlsx"
            Regex("\\bpdf\\b").containsMatchIn(t) -> "pdf"
            Regex("\\bhtml\\b|web ?page|landing").containsMatchIn(t) -> "html"
            else -> ""
        }
    }

    /** Is this a deck-shaped request (drives slide splitting + landscape)? */
    private fun isDeck(kind: String, title: String) =
        Regex("(?i)deck|slide|present|pitch").containsMatchIn(kind + " " + title)

    /**
     * The MODEL designs the look for THIS document — palette, type, weight — rather than everything
     * inheriting one baked-in house style. A children's workshop flyer, a restructuring memo and a
     * fintech pitch should each arrive with their own visual register; hardcoding one accent would make
     * every output look identical regardless of subject.
     *
     * Falls back to a neutral theme only if the model is unavailable or returns something unusable.
     */
    fun designTheme(title: String, brief: String, kind: String): Ooxml.Theme {
        val sys = "You are an art director choosing a visual identity for ONE document. Consider its subject, " +
            "audience and tone, then pick a palette and typography that genuinely suit it — be decisive and " +
            "distinctive, not generic corporate blue. Output ONLY compact JSON, no prose:\n" +
            "{\"accent\":\"RRGGBB\",\"ink\":\"RRGGBB\",\"muted\":\"RRGGBB\",\"deckBg\":\"RRGGBB\"," +
            "\"deckInk\":\"RRGGBB\",\"font\":\"<a font that exists on Windows/Mac/Android: Arial, Helvetica, " +
            "Georgia, Times New Roman, Verdana, Tahoma, Trebuchet MS, Courier New, Palatino, Garamond>\"," +
            "\"titleSize\":<24-44>,\"bodySize\":<13-20>}\n" +
            "Rules: hex WITHOUT '#'. 'ink' is body text on white — must be dark and highly readable. " +
            "'deckBg' is the slide background with 'deckInk' as its text — they MUST have strong contrast " +
            "(either a dark bg with light ink, or a light bg with dark ink). 'accent' must be legible against " +
            "BOTH white and deckBg. Serif fonts for editorial/formal, sans for modern/technical."
        return try {
            val (code, raw) = AgentClient.chat("Document: \"$title\" ($kind)\nBrief: $brief", sys, emptyList())
            if (code != 200) return Ooxml.Theme()
            val o = org.json.JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            fun hex(k: String, d: String): String {
                val v = o.optString(k).trim().removePrefix("#").uppercase()
                return if (Regex("^[0-9A-F]{6}$").matches(v)) v else d
            }
            val def = Ooxml.Theme()
            Ooxml.Theme(
                accent = hex("accent", def.accent), ink = hex("ink", def.ink), muted = hex("muted", def.muted),
                deckBg = hex("deckBg", def.deckBg), deckInk = hex("deckInk", def.deckInk),
                font = o.optString("font").trim().ifBlank { def.font },
                titleSize = o.optInt("titleSize", def.titleSize).coerceIn(20, 48),
                bodySize = o.optInt("bodySize", def.bodySize).coerceIn(11, 22))
        } catch (e: Exception) { Log.w(TAG, "theme: ${e.message}"); Ooxml.Theme() }
    }

    /**
     * Build the document. [brief] is what the user asked for; the brain is pulled in for grounding so the
     * content is about THEM, not generic filler.
     */
    fun create(ctx: Context, title: String, brief: String, format: String, kind: String = ""): Made {
        val fmt = format.lowercase().ifBlank { "pdf" }
        val k = kind.ifBlank { if (isDeck(fmt + brief, title)) "deck" else "onepager" }
        if (fmt !in FORMATS) return Made(false, error = "Unknown format \"$format\".")
        return try {
            // Generate the SOURCE content first and keep it, then build the file from it — so a later
            // "make it shorter" edits this content rather than regenerating a different document.
            val content = when (fmt) {
                "xlsx" -> tableContent(ctx, title, brief).joinToString("\n") { it.joinToString(",") }
                "pptx" -> deckContent(ctx, title, brief)
                else -> writtenContent(ctx, title, brief, k)
            }
            if (content.isBlank()) return Made(false, error = "couldn't generate the content")
            val made = buildFrom(ctx, title, brief, fmt, k, content)
            if (made.ok) {
                remember(ctx, title, brief, fmt, k, content)
                indexIntoBrain(ctx, made, title, brief)
            }
            made
        } catch (e: Exception) {
            Log.w(TAG, "create: ${e.message}")
            Made(false, error = e.message ?: "couldn't build the document")
        }
    }

    // ── content generation (grounded in the brain) ───────────────────────────────────────────────
    private fun writtenContent(ctx: Context, title: String, brief: String, kind: String): String {
        val brain = try { BrainContext.build(ctx, "$title $brief").take(2000) } catch (e: Exception) { "" }
        val sys = "You write the BODY of a polished business document. Output PLAIN TEXT with light markdown " +
            "only: '# ' for section headings, '## ' for sub-headings, '- ' for bullets, '> ' for a pull-quote. " +
            "No HTML, no code fences, no preamble. Ruthless clarity — active voice, zero filler, every line " +
            "earns its place. Structure it the way a top consultant would: lead with the point, then support it. " +
            (if (brain.isNotBlank()) "Ground it in what you know about the user (do not copy verbatim): $brain" else "")
        val (code, text) = AgentClient.chat("Document: \"$title\" ($kind).\nBrief: $brief", sys, emptyList())
        return if (code == 200 && text.isNotBlank()) text else brief
    }

    private fun deckContent(ctx: Context, title: String, brief: String): String {
        val brain = try { BrainContext.build(ctx, "$title $brief").take(2000) } catch (e: Exception) { "" }
        val sys = "You write SLIDE DECK content. Separate every slide with a line containing only ===. The FIRST " +
            "line of each block is that slide's title; the rest are its bullets, each starting with '- '. " +
            "6-12 slides, ONE idea per slide, 3-5 short bullets max — never paragraphs. Slide 1 is a cover " +
            "(title + one-line subtitle). Ruthless clarity, active voice, zero filler. No markdown besides the bullets. " +
            (if (brain.isNotBlank()) "Ground it in what you know about the user: $brain" else "")
        val (code, text) = AgentClient.chat("Deck: \"$title\".\nBrief: $brief", sys, emptyList())
        return if (code == 200 && text.isNotBlank()) text else "$title\n- $brief"
    }

    private fun tableContent(ctx: Context, title: String, brief: String): List<List<String>> {
        val sys = "You produce SPREADSHEET data. Output ONLY CSV — the first row is the header, every later row " +
            "is data. No prose, no code fences, no commentary. Numbers as bare numbers (no currency symbols or " +
            "thousands separators) so they stay computable. Choose sensible columns for the request."
        val (code, text) = AgentClient.chat("Spreadsheet: \"$title\".\nBrief: $brief", sys, emptyList())
        if (code != 200 || text.isBlank()) return emptyList()
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
            .map { line -> splitCsv(line) }
            .filter { it.isNotEmpty() }
    }

    /** CSV split that respects quoted fields (so "Smith, John" stays one cell). */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false
        for (c in line) when {
            c == '"' -> q = !q
            c == ',' && !q -> { out.add(cur.toString().trim()); cur.setLength(0) }
            else -> cur.append(c)
        }
        out.add(cur.toString().trim())
        return out
    }

    /**
     * Feed the finished document back INTO the brain: its text becomes searchable, it lands in the outbox,
     * and the SlyOS-folder index knows about it — so it can be recalled and re-sent later.
     */
    private fun indexIntoBrain(ctx: Context, made: Made, title: String, brief: String) {
        try { DocText.add(ctx, made.name, "slyos", "$title\n\n$brief") } catch (e: Exception) {}
        try {
            MessageStore.insertOne(ctx, "Documents", "SlyOS", "system", "system",
                "Created ${made.format.uppercase()} “${made.name}” — $brief")
        } catch (e: Exception) {}
        try { MemoryLog.add(ctx, "action", "Created ${made.name}", brief.take(300), "Documents") } catch (e: Exception) {}
    }

    // ── memory of the last document, so "make it shorter" refines instead of starting over ────────
    private const val PREFS = "slyos_docforge"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keep the SOURCE content, not just the file — refinement needs something to edit. */
    private fun remember(ctx: Context, title: String, brief: String, format: String, kind: String, content: String) {
        p(ctx).edit().putString("title", title).putString("brief", brief).putString("format", format)
            .putString("kind", kind).putString("content", content.take(60000))
            .putLong("at", System.currentTimeMillis()).apply()
    }

    fun lastTitle(ctx: Context): String = p(ctx).getString("title", "") ?: ""
    fun hasDraft(ctx: Context): Boolean = (p(ctx).getString("content", "") ?: "").isNotBlank()

    /**
     * Refine the document just made — "make it shorter", "add a slide on pricing", "more formal".
     * Edits the EXISTING content rather than regenerating from the brief, so earlier work isn't lost,
     * then rebuilds the file (optionally into a different format).
     */
    fun refine(ctx: Context, instruction: String, newFormat: String = ""): Made {
        val pr = p(ctx)
        val title = pr.getString("title", "") ?: ""
        val brief = pr.getString("brief", "") ?: ""
        val kind = pr.getString("kind", "onepager") ?: "onepager"
        val content = pr.getString("content", "") ?: ""
        val fmt = newFormat.ifBlank { pr.getString("format", "pdf") ?: "pdf" }
        if (content.isBlank()) return Made(false, error = "There's no document to refine yet — ask me to make one first.")

        val sys = "You revise an existing document. Apply the user's instruction to the content below and " +
            "return the FULL revised content in EXACTLY the same format you received it (same markdown / same " +
            "=== slide separators / same CSV shape). Change only what the instruction asks for; preserve " +
            "everything else verbatim. Output ONLY the revised content — no commentary, no code fences."
        val (code, revised) = AgentClient.chat(
            "INSTRUCTION: $instruction\n\nCURRENT CONTENT:\n$content", sys, emptyList())
        if (code != 200 || revised.isBlank()) return Made(false, error = "couldn't revise it just now")

        val made = buildFrom(ctx, title, brief, fmt, kind, revised)
        if (made.ok) {
            remember(ctx, title, brief, fmt, kind, revised)
            indexIntoBrain(ctx, made, title, "refined: $instruction")
        }
        return made
    }

    /** Build a file from ALREADY-GENERATED content (used by refine, and by create after generation). */
    private fun buildFrom(ctx: Context, title: String, brief: String, fmt: String, kind: String, content: String): Made {
        val th = designTheme(title, brief, kind)
        return when (fmt) {
            "docx" -> {
                val uri = SlyFolder.file(ctx, "$title.docx", mimeFor("docx"), Ooxml.docx(title, content, th), "documents", brief.take(180))
                Made(uri != null, "docx", "$title.docx", uri, error = if (uri == null) "couldn't save" else "")
            }
            "pptx" -> {
                val slides = content.split(Regex("(?m)^===+\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }.map { blk ->
                    val l = blk.lines(); l.first().trim().removePrefix("#").trim() to l.drop(1).joinToString("\n").trim()
                }
                if (slides.isEmpty()) return Made(false, error = "no slides in the revised content")
                val uri = SlyFolder.file(ctx, "$title.pptx", mimeFor("pptx"), Ooxml.pptx(slides, th), "documents", brief.take(180))
                Made(uri != null, "pptx", "$title.pptx", uri, error = if (uri == null) "couldn't save" else "")
            }
            "xlsx" -> {
                val rows = content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }.map { splitCsv(it) }
                if (rows.isEmpty()) return Made(false, error = "no rows in the revised content")
                val uri = SlyFolder.file(ctx, "$title.xlsx", mimeFor("xlsx"), Ooxml.xlsx(rows, th), "documents", brief.take(180))
                Made(uri != null, "xlsx", "$title.xlsx", uri, error = if (uri == null) "couldn't save" else "")
            }
            else -> {
                // html / pdf — re-run the designer over the revised content so the layout is rebuilt too.
                val html = AgentClient.designHtml(kind, title, content, "", "")
                if (html.length < 120) return Made(false, error = "the designer came back empty")
                if (fmt == "html") {
                    val uri = SlyFolder.file(ctx, "$title.html", "text/html", html.toByteArray(), "documents", brief.take(180))
                    return Made(uri != null, "html", "$title.html", uri, error = if (uri == null) "couldn't save" else "")
                }
                val f = HtmlPdf.render(ctx, html, title, landscape = isDeck(kind, title))
                    ?: return Made(false, error = "couldn't render the PDF")
                val uri = SlyFolder.file(ctx, "$title.pdf", "application/pdf", f.readBytes(), "documents", brief.take(180))
                Made(uri != null, "pdf", "$title.pdf", uri, f.absolutePath, if (uri == null) "couldn't save" else "")
            }
        }
    }

    /** Open a produced document in whatever app handles it. */
    fun open(ctx: Context, uriStr: String, name: String): Boolean = try {
        val mime = mimeFor(name.substringAfterLast('.', "pdf").lowercase())
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriStr), mime)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        true
    } catch (e: Exception) { Log.w(TAG, "open: ${e.message}"); false }

    /** Hand a produced document to any app that can send it (mail, WhatsApp, Drive…). */
    fun share(ctx: Context, uriStr: String, name: String, note: String = ""): Boolean = try {
        val mime = mimeFor(name.substringAfterLast('.', "pdf").lowercase())
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, Uri.parse(uriStr))
            if (note.isNotBlank()) putExtra(android.content.Intent.EXTRA_TEXT, note)
            putExtra(android.content.Intent.EXTRA_SUBJECT, name)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(send, "Send $name")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) { Log.w(TAG, "share: ${e.message}"); false }

    /** Everything SlyOS has made, newest first — the "fetch it back out of the folder" side. */
    fun library(ctx: Context): List<SlyFolder.Doc> = try {
        SlyFolder.index(ctx).filter { d ->
            FORMATS.any { d.name.endsWith(".$it", true) }
        }.sortedByDescending { it.ts }
    } catch (e: Exception) { emptyList() }

    /** Find a previously made document by loose name ("the Q3 deck"). */
    fun find(ctx: Context, query: String): SlyFolder.Doc? {
        val q = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (q.isEmpty()) return null
        return library(ctx).maxByOrNull { d -> q.count { d.name.lowercase().contains(it) } }
            ?.takeIf { d -> q.any { d.name.lowercase().contains(it) } }
    }
}
