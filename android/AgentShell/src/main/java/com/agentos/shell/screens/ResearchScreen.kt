package com.agentos.shell.screens

import android.annotation.SuppressLint
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.KnowledgeStore
import com.agentos.shell.tools.MemoryLog
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MetricsStore
import com.agentos.shell.tools.PaperStore
import com.agentos.shell.tools.UsageLimiter
import com.agentos.shell.tools.ZenodoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CAP = 6   // daily Opus paper operations (new paper + each suggestion)

// A clean, A4-proportioned print/screen stylesheet injected into every paper so content sits in a
// readable column with real margins and never breaks across pages mid-element.
private const val PAPER_CSS = """
<style id="slyos-print">
@page { size: A4; margin: 24mm 20mm; }
* { box-sizing: border-box; }
html, body { background:#fff !important; }
/* LaTeX 'article'-like typesetting: Computer-Modern-ish serif, justified, indented paragraphs,
   tight leading, centered scholarly title block. Falls back gracefully where CM isn't installed. */
body { font-family:'Latin Modern Roman','CMU Serif','Latin Modern','Nimbus Roman',
  'TeX Gyre Termes','Times New Roman',Georgia,serif !important; font-size:10.8pt !important;
  line-height:1.34 !important; color:#111 !important; max-width:165mm !important;
  margin:0 auto !important; padding:12mm 4mm !important; text-rendering:optimizeLegibility;
  font-feature-settings:"liga","kern"; -webkit-print-color-adjust:exact; }
h1 { font-size:19pt; font-weight:700; text-align:center; line-height:1.2; margin:0 0 4pt;
  letter-spacing:.1px; }
/* author / affiliation line right under the title */
h1 + p, .author, .affil { text-align:center; }
h2 { font-size:13pt; font-weight:700; margin:17pt 0 5pt; break-after:avoid; page-break-after:avoid; }
h3 { font-size:11.3pt; font-weight:700; font-style:italic; margin:11pt 0 3pt; break-after:avoid; page-break-after:avoid; }
h1,h2,h3,h4 { break-inside:avoid; page-break-inside:avoid; }
/* Justified body with first-line indent; no indent on the first paragraph after a heading (LaTeX rule). */
p { margin:0 0 2pt; text-align:justify; text-indent:1.4em; orphans:3; widows:3; hyphens:auto; }
h1+p, h2+p, h3+p, h4+p, blockquote p, li p, .abstract p:first-of-type { text-indent:0; }
/* Abstract: narrower measure, smaller, like a real paper. */
.abstract, #abstract { font-size:10pt; margin:10pt 7mm 14pt; }
ul,ol { margin:3pt 0 6pt 20pt; }
li { margin:0 0 2pt; }
pre,table,figure,blockquote { break-inside:avoid; page-break-inside:avoid; max-width:100%; overflow-x:auto; }
blockquote { margin:6pt 7mm; font-size:10.2pt; }
img { max-width:100%; height:auto; }
table { width:100%; border-collapse:collapse; font-size:9.6pt; margin:6pt 0; }
th,td { border:1px solid #bbb; padding:4px 7px; text-align:left; }
caption, figcaption { font-size:9.4pt; font-style:italic; text-align:center; margin-top:3pt; }
a { color:#111; text-decoration:none; }
mjx-container { overflow-x:auto; max-width:100%; }
/* Table of contents: compact, boxed-off, never split across a page. */
nav.toc { break-inside:avoid; page-break-inside:avoid; margin:10pt 0 14pt; padding:6pt 0; border-top:.5pt solid #ccc; border-bottom:.5pt solid #ccc; }
nav.toc h2 { font-size:12pt; margin:0 0 4pt; text-align:left; }
nav.toc ol { margin:0 0 0 20pt; font-size:10pt; }
nav.toc li { margin:0 0 1pt; }
/* References: hanging indent, smaller, with the URL shown beneath each entry. */
.references, #references { font-size:9.8pt; }
.references li, #references li { margin:0 0 4pt; padding-left:1.4em; text-indent:-1.4em; }
.references a, #references a { color:#1a3e6e; word-break:break-all; }
</style>
"""

/** Briefly highlight + scroll to the freshly-added text (the #slyosnew marker), then fade it out. */
private fun injectHighlight(html: String): String {
    val inject = """
<style>#slyosnew{background:#FCE7DA;border-left:3px solid #E8642C;padding:6px 0 6px 12px;border-radius:6px;
  transition:background 1.4s ease,border-color 1.4s ease;}</style>
<script>setTimeout(function(){var e=document.getElementById('slyosnew');if(e){
  e.scrollIntoView({behavior:'smooth',block:'center'});
  setTimeout(function(){e.style.background='transparent';e.style.borderColor='transparent';},3800);}},450);</script>
"""
    val idx = html.lastIndexOf("</body>", ignoreCase = true)
    return if (idx >= 0) html.substring(0, idx) + inject + html.substring(idx) else html + inject
}

/** Make sure the paper's <head> carries our print CSS (idempotent — replaces any prior copy). */
private fun ensureStyle(html: String): String {
    val h = Regex("(?is)<style id=\"slyos-print\">.*?</style>").replace(html, "")
    val headClose = Regex("(?i)</head>").find(h)
    if (headClose != null) return h.substring(0, headClose.range.first) + PAPER_CSS + h.substring(headClose.range.first)
    val bodyOpen = Regex("(?i)<body[^>]*>").find(h)
    if (bodyOpen != null) return h.substring(0, bodyOpen.range.last + 1) + PAPER_CSS + h.substring(bodyOpen.range.last + 1)
    return PAPER_CSS + h
}

@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ResearchScreen(modifier: Modifier = Modifier, initialTopic: String = "", onWorkspace: () -> Unit = {}, onChat: () -> Unit = {}, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(if (com.agentos.shell.tools.TeamInbox.openEmpId != null) "team" else if (initialTopic.isNotBlank()) "compose" else "library") }
    var papers by remember { mutableStateOf(PaperStore.list(ctx)) }
    var paperSearch by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(0L) }
    var renameText by remember { mutableStateOf("") }
    var currentId by remember { mutableStateOf(0L) }
    var html by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf(initialTopic) }
    var chatInput by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf<List<PaperStore.Chat>>(emptyList()) }
    var showPaper by remember { mutableStateOf(false) } // full paper hidden by default
    var pendingHighlight by remember { mutableStateOf(false) }
    val web = true   // Opus ALWAYS researches the web + cites sources
    var useDoc by remember { mutableStateOf(false) }
    var docType by remember { mutableStateOf("paper") }   // paper | whitepaper | memo
    var thesis by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var showPublish by remember { mutableStateOf(false) }
    var zToken by remember { mutableStateOf(MemoryStore.zenodoToken(ctx)) }
    var publishing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf(UsageLimiter.remaining(ctx, "paper", CAP)) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    fun titleOf(h: String, fallback: String): String =
        Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() } ?: fallback.take(60).ifBlank { "Untitled" }

    fun openPaper(id: Long) {
        currentId = id; html = PaperStore.html(ctx, id)
        docType = PaperStore.docType(ctx, id); thesis = PaperStore.thesis(ctx, id)
        chat = PaperStore.chatLog(ctx, id)
        mode = "editor"; showPaper = false; status = ""
    }

    fun errText(out: String) = "Failed (" + out.removePrefix("ERR::").replace("::", "): ")

    fun generate() {
        if (prompt.isBlank() || busy) return
        if (UsageLimiter.remaining(ctx, "paper", CAP) <= 0) { status = "Daily limit reached ($CAP/$CAP). Resets tomorrow."; return }
        busy = true; status = "Opus is researching the web + writing…"
        scope.launch {
            val source = if (useDoc && KnowledgeStore.hasDoc(ctx)) KnowledgeStore.retrieve(ctx, prompt, 12000) else ""
            val mem = MemoryStore.about(ctx)
            val lib = withContext(Dispatchers.IO) { PaperStore.libraryContext(ctx, 0L, prompt) }
            val dt = docType; val th = thesis
            val out = withContext(Dispatchers.IO) { AgentClient.writePaper(prompt, source, web, mem, lib, dt, th) }
            if (out.startsWith("ERR::")) { status = errText(out); busy = false; return@launch }
            UsageLimiter.use(ctx, "paper", CAP)
            val newTitle = titleOf(out, prompt)
            val id = PaperStore.create(ctx, newTitle, out, dt)
            if (th.isNotBlank()) PaperStore.setThesis(ctx, id, th)
            PaperStore.snapshot(ctx, id, "Created")
            MemoryLog.add(ctx, "response", "Paper: $newTitle", "Wrote a research paper: $newTitle", "Research")
            MetricsStore.record(ctx, MetricsStore.secondsFor("paper_write"))
            // Seed the conversation: your request + a real reply telling you what was drafted + sources.
            val askedFor = prompt
            PaperStore.addChat(ctx, id, "you", askedFor)
            val sources0 = Regex("https?://[^\\s\"'<>)\\]]+").findAll(out).map { it.value }.distinct().take(8).toList()
            val plain0 = out.replace(Regex("(?is)<style.*?</style>"), " ").replace(Regex("(?is)<script.*?</script>"), " ")
                .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            val chapN = Regex("(?is)<h2[^>]*>").findAll(out).count()
            var note0 = withContext(Dispatchers.IO) { AgentClient.researchNote("Write this paper: $askedFor", newTitle, plain0, sources0) }
            if (AgentClient.looksLikeError(note0)) note0 = ""   // drop error placeholder; the summary below still stands
            note0 += "\n\n📄 Drafted “$newTitle” — $chapN sections."
            note0 += if (sources0.isNotEmpty()) "\n🌐 Sources cited (${sources0.size}): " + sources0.joinToString("  ·  ")
                     else "\n⚠️ No source URLs came back — I couldn't web-verify citations this time."
            note0 += "\nTap “View paper” to read it. Then just message me to expand or edit any part."
            PaperStore.addChat(ctx, id, "ai", note0)
            papers = PaperStore.list(ctx); remaining = UsageLimiter.remaining(ctx, "paper", CAP)
            currentId = id; html = out; chat = PaperStore.chatLog(ctx, id)
            prompt = ""; mode = "editor"; showPaper = false; busy = false; status = ""
        }
    }
    // Headings already in the paper, so Opus continues the numbering and doesn't repeat itself.
    fun outlineOf(h: String): String =
        Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").findAll(h)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.isNotBlank() }.joinToString("\n")

    fun insertBeforeBodyEnd(h: String, frag: String): String {
        val idx = h.lastIndexOf("</body>", ignoreCase = true)
        return if (idx >= 0) h.substring(0, idx) + "\n" + frag + "\n" + h.substring(idx) else h + "\n" + frag
    }

    // Split the paper into chapters by <h2> so we can edit just one (works on huge papers).
    data class Chap(val heading: String, val html: String, val start: Int, val end: Int)
    fun chapters(h: String): List<Chap> {
        val ms = Regex("(?is)<h2[^>]*>(.*?)</h2>").findAll(h).toList()
        if (ms.isEmpty()) return emptyList()
        val out = ArrayList<Chap>()
        for (i in ms.indices) {
            val start = ms[i].range.first
            val end = if (i + 1 < ms.size) ms[i + 1].range.first
                      else h.lastIndexOf("</body>", ignoreCase = true).let { if (it > start) it else h.length }
            val heading = ms[i].groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            out.add(Chap(heading, h.substring(start, end), start, end))
        }
        return out
    }

    // Deterministic structural cleanup (no AI, never truncates): removes meta/placeholder lines,
    // forces a white background, pulls EVERY scattered reference into one References section at the
    // end, rebuilds the table of contents from the real headings, and renumbers chapters. Safe —
    // it only moves/renumbers existing content, it never rewrites prose.
    // PURE structural normalizer — the APP guarantees structure so the model doesn't have to: ONE clean
    // table of contents, chapters renumbered in document order, and References ALWAYS last. It never
    // rewrites prose and never strips highlight markers, so it's safe to run automatically every turn.
    fun restructure(src: String): Pair<String, Int> {
        var h = src
        // Strip every existing table of contents (any form) — we'll rebuild one.
        h = Regex("(?is)<nav[^>]*>.*?</nav>").replace(h) { if (it.value.lowercase().contains("content")) "" else it.value }
        h = Regex("(?is)<(div|ul|ol)[^>]*class=\"[^\"]*toc[^\"]*\"[^>]*>.*?</\\1>").replace(h, "")
        h = Regex("(?is)<h[1-3][^>]*>\\s*(?:table of\\s+)?contents\\s*</h[1-3]>\\s*(?:<(ul|ol)\\b[^>]*>.*?</\\1>)?").replace(h, "")
        // Gather all reference lists (de-duped), remove them + their headings.
        val refLis = StringBuilder(); val seen = HashSet<String>()
        val refOl = Regex("(?is)<ol[^>]*class=\"[^\"]*references[^\"]*\"[^>]*>(.*?)</ol>")
        refOl.findAll(h).forEach { m ->
            Regex("(?is)<li[^>]*>.*?</li>").findAll(m.groupValues[1]).forEach { li ->
                val key = li.value.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().lowercase()
                if (key.length > 4 && seen.add(key)) refLis.append(li.value).append("\n")
            }
        }
        h = refOl.replace(h, "")
        h = Regex("(?is)<h2[^>]*>\\s*\\d*\\.?\\s*references\\s*</h2>").replace(h, "")
        // Renumber chapters in document order (skip front/back-matter headings).
        var n = 0
        h = Regex("(?is)(<h2[^>]*>)(.*?)(</h2>)").replace(h) { m ->
            val open = m.groupValues[1]; var txt = m.groupValues[2].trim(); val close = m.groupValues[3]
            val plain = txt.replace(Regex("<[^>]+>"), "").trim().lowercase()
            val skip = listOf("reference", "appendix", "acknowledg", "abstract", "bibliography", "contents").any { plain.startsWith(it) }
            if (skip) "$open$txt$close" else { n++; txt = txt.replace(Regex("^\\s*\\d+[.).]?\\s+"), ""); "$open$n. $txt$close" }
        }
        // Rebuild the TOC from the real chapter headings, before the first chapter.
        val heads = Regex("(?is)<h2[^>]*>(.*?)</h2>").findAll(h)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { val l = it.lowercase(); it.isNotBlank() && !l.startsWith("contents") && !l.startsWith("table of contents") && !l.startsWith("reference") }
            .toList()
        if (heads.size >= 3) {
            val toc = "<nav class=\"toc\"><h2>Contents</h2><ol>" + heads.joinToString("") { "<li>$it</li>" } + "</ol></nav>"
            val firstH2 = Regex("(?is)<h2[^>]*>").find(h)?.range?.first
            if (firstH2 != null) h = h.substring(0, firstH2) + toc + "\n" + h.substring(firstH2)
        }
        // References LAST, always.
        if (refLis.isNotEmpty()) {
            val block = "<h2>References</h2>\n<ol class=\"references\">\n$refLis</ol>"
            val idx = h.lastIndexOf("</body>", ignoreCase = true)
            h = if (idx >= 0) h.substring(0, idx) + block + "\n" + h.substring(idx) else h + block
        }
        return h to seen.size
    }

    // Full clean-up (button / "clean up the paper"): strips placeholder lines + forces white background,
    // then runs the structural normalizer.
    fun cleanup(): String {
        var h = html.replace("id=\"slyosnew\"", "").replace("id=\"slyosold\"", "")
        val ph = "i'?ll research|i will research|in this section i will|i'?m going to|let me research|here'?s what i"
        h = Regex("(?is)<p[^>]*>\\s*($ph)[^<]*</p>").replace(h, "")
        h = Regex("(?is)<li[^>]*>\\s*($ph)[^<]*</li>").replace(h, "")
        h = Regex("(?is)<h[1-4][^>]*>\\s*($ph)[^<]*</h[1-4]>").replace(h, "")
        h = Regex("(?i)background(-color)?\\s*:\\s*(?!#fff|#ffffff|white|transparent|none)[^;\"}]+;?").replace(h, "")
        val (structured, refCount) = restructure(h)
        html = structured
        PaperStore.save(ctx, currentId, html)
        PaperStore.snapshot(ctx, currentId, "Cleaned up & structured")
        val parts = mutableListOf("ordered & renumbered the chapters", "rebuilt the table of contents", "moved references to the end")
        if (refCount > 0) parts.add("merged $refCount references")
        return "Cleaned up: " + parts.joinToString(", ") + " ✓"
    }
    fun finalize() { status = cleanup() }

    fun frontMatterEnd(h: String): Int {
        // The front matter runs up to the first REAL chapter — skip headings that are themselves part
        // of the front matter (a "Contents" TOC heading, "Abstract", "Keywords") so an intro edit can
        // actually reach and rewrite the title/abstract/table-of-contents.
        val skip = listOf("contents", "table of contents", "abstract", "keywords")
        Regex("(?is)<h2[^>]*>(.*?)</h2>").findAll(h).forEach { m ->
            val plain = m.groupValues[1].replace(Regex("<[^>]+>"), "").trim().lowercase()
            if (skip.none { plain.startsWith(it) }) return m.range.first
        }
        val b = h.lastIndexOf("</body>", ignoreCase = true)
        return if (b > 0) b else h.length
    }

    // ONE conversational turn (like the Claude web app): you message → it routes, writes/edits the
    // paper in place, then REPLIES in the chat telling you what it did + which web sources it used.
    // Nothing chatty ever lands in the document; the doc just updates and the change is highlighted.
    fun send() {
        val instr = chatInput.trim()
        if (instr.isBlank() || busy) return
        if (UsageLimiter.remaining(ctx, "paper", CAP) <= 0) {
            PaperStore.addChat(ctx, currentId, "ai", "You've hit today's Opus limit ($CAP/$CAP). It resets tomorrow.")
            chat = PaperStore.chatLog(ctx, currentId); chatInput = ""; return
        }
        PaperStore.addChat(ctx, currentId, "you", instr); chat = PaperStore.chatLog(ctx, currentId)
        chatInput = ""; busy = true; status = ""
        scope.launch {
            val mem = MemoryStore.about(ctx)
            val title = titleOf(html, "")
            val outline = outlineOf(html)
            val chaps = chapters(html)
            val (action, target) = withContext(Dispatchers.IO) { AgentClient.routePaperEdit(title, outline, instr) }
            val tnorm = target.lowercase().trim()
            val chap = if (action == "edit" && target.isNotBlank() && !target.equals("INTRO", true))
                chaps.firstOrNull { it.heading.equals(target, true) }
                    ?: chaps.firstOrNull { it.heading.lowercase().contains(tnorm) || tnorm.contains(it.heading.lowercase()) }
            else null
            var frag: String; var kind: String; var label: String; var start = 0; var end = 0
            when {
                action == "edit" && target.equals("INTRO", true) -> {
                    val head = html.substring(0, frontMatterEnd(html))
                    frag = withContext(Dispatchers.IO) { AgentClient.reviseFrontMatter(head, instr, mem) }
                    kind = "intro"; label = "Title / abstract / intro"
                }
                chap != null -> {
                    frag = withContext(Dispatchers.IO) { AgentClient.reviseChapter(title, chap.html, instr, web, mem, docType, thesis) }
                    kind = "edit"; label = chap.heading; start = chap.start; end = chap.end
                }
                else -> {
                    val lib = withContext(Dispatchers.IO) { PaperStore.libraryContext(ctx, currentId, instr) }
                    frag = withContext(Dispatchers.IO) { AgentClient.expandPaper(title, outline, instr, web, mem, lib, docType, thesis) }
                    kind = "add"; label = "New chapter"
                }
            }
            if (frag.startsWith("REFUSAL::")) {
                // The model replied conversationally instead of writing content — show it in chat,
                // never splice it into the paper, and don't burn a daily credit.
                PaperStore.addChat(ctx, currentId, "ai", frag.removePrefix("REFUSAL::").trim() +
                    "\n\n(Tip: for structural fixes — references, table of contents, background — just say " +
                    "“clean up the paper” and I'll do it directly.)")
                chat = PaperStore.chatLog(ctx, currentId); busy = false; return@launch
            }
            if (frag.startsWith("ERR::")) {
                PaperStore.addChat(ctx, currentId, "ai", "I couldn't complete that — ${errText(frag)}. Try rephrasing, or send it again.")
                chat = PaperStore.chatLog(ctx, currentId); busy = false; return@launch
            }
            // Splice into the document (in place; nothing else touched). Demote old highlight first.
            html = html.replace("id=\"slyosnew\"", "id=\"slyosold\"")
            when (kind) {
                "intro" -> {
                    val e = frontMatterEnd(html)
                    // SAFETY: never let a front-matter edit silently wipe the abstract/intro. If the
                    // rewrite is drastically shorter than what it replaces, reject it and keep the original.
                    val oldLen = html.substring(0, e).replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().length
                    val newLen = frag.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().length
                    if (oldLen > 220 && newLen < oldLen * 0.55) {
                        PaperStore.addChat(ctx, currentId, "ai", "I held off on that — it would have wiped a lot of your existing front matter (title/abstract/intro). Give me a more specific instruction, or tap “Clean up” for structure only.")
                        chat = PaperStore.chatLog(ctx, currentId); busy = false; return@launch
                    }
                    html = frag + "\n" + html.substring(e)
                }
                "edit" -> { html = html.substring(0, start) + "<div id=\"slyosnew\">$frag</div>\n" + html.substring(end) }
                else -> { html = insertBeforeBodyEnd(html, "<div id=\"slyosnew\">$frag</div>") }
            }
            // The APP, not the model, guarantees structure: after every change, re-normalize so chapters
            // stay in order + numbered, the TOC is rebuilt, and References stays LAST. Fixes the mess
            // where new chapters landed after the sources and the TOC went stale.
            html = restructure(html).first
            PaperStore.save(ctx, currentId, html)
            PaperStore.snapshot(ctx, currentId, "$label — ${instr.take(40)}")
            UsageLimiter.use(ctx, "paper", CAP)
            MetricsStore.record(ctx, MetricsStore.secondsFor(if (kind == "add") "paper_expand" else "paper_edit"))
            MemoryLog.add(ctx, "response", "Paper: ${titleOf(html, "")}", "$label — $instr", "Research")
            // Build the conversational reply + show sources so you know if the web was used.
            val sources = Regex("https?://[^\\s\"'<>)\\]]+").findAll(frag).map { it.value }.distinct().take(8).toList()
            val plain = frag.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            val words = plain.split(" ").count { it.isNotBlank() }
            var note = withContext(Dispatchers.IO) { AgentClient.researchNote(instr, label, plain, sources) }
            if (AgentClient.looksLikeError(note)) note = ""
            note += "\n\n📄 " + (if (kind == "add") "Added “$label”" else "Updated “$label”") + " (~$words words)."
            note += if (sources.isNotEmpty()) "\n🌐 Sources cited (${sources.size}): " + sources.joinToString("  ·  ")
                    else "\n⚠️ No source URLs came back — this section isn't web-verified."
            note += "\nTap “View paper” to see it highlighted."
            PaperStore.addChat(ctx, currentId, "ai", note); chat = PaperStore.chatLog(ctx, currentId)
            papers = PaperStore.list(ctx); remaining = UsageLimiter.remaining(ctx, "paper", CAP)
            pendingHighlight = (kind != "intro"); busy = false
        }
    }
    // Pull the user's hand-edits back out of the live (contenteditable) WebView and save them. We
    // keep the original <head> and swap in the edited <body> so MathJax source and styling survive.
    fun saveEdits() {
        val wv = webRef ?: run { editMode = false; return }
        wv.evaluateJavascript("(function(){return document.body.innerHTML;})();") { res ->
            try {
                val bodyInner = org.json.JSONTokener(res).nextValue() as? String
                if (bodyInner.isNullOrBlank()) { status = "Nothing to save."; editMode = false; return@evaluateJavascript }
                val openM = Regex("(?i)<body[^>]*>").find(html)
                val closeIdx = html.lastIndexOf("</body>", ignoreCase = true)
                var newHtml = if (openM != null && closeIdx > openM.range.last)
                    html.substring(0, openM.range.last + 1) + "\n" + bodyInner + "\n" + html.substring(closeIdx)
                else html
                // Strip artifacts that may have ridden along from the editable render.
                newHtml = newHtml.replace(Regex("(?is)<style id=\"slyos-print\">.*?</style>"), "")
                    .replace(" contenteditable=\"true\"", "")
                PaperStore.snapshot(ctx, currentId, "Before manual edit")
                html = newHtml
                PaperStore.save(ctx, currentId, html)
                editMode = false
                status = "Saved your edits ✓"
            } catch (e: Exception) { status = "Couldn't save edits."; editMode = false }
        }
    }
    fun exportPdf() {
        val wv = webRef ?: return
        try {
            val pm = ctx.getSystemService(android.content.Context.PRINT_SERVICE) as PrintManager
            pm.print("SlyOS Paper", wv.createPrintDocumentAdapter("SlyOS Paper"),
                PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        } catch (e: Exception) { status = "Couldn't open PDF export." }
    }

    // Render the on-screen paper into a real A4 PDF using Android's NATIVE print pipeline (the same
    // engine as the Print button). This produces a properly paginated, full-length, selectable-text
    // PDF — unlike rasterizing the WebView, which left long papers blank past the first screen.
    // Async: the print adapter works via callbacks; result is delivered to [onDone] on the main thread.
    fun buildPdf(onDone: (java.io.File?) -> Unit) {
        val wv = webRef ?: return onDone(null)
        try {
            val file = java.io.File(ctx.cacheDir, "paper_$currentId.pdf")
            val adapter = wv.createPrintDocumentAdapter("SlyOS Paper")
            val attrs = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            android.print.PdfPrint(attrs).print(adapter, file, onDone)
        } catch (e: Exception) { onDone(null) }
    }

    fun sharePdf() {
        status = "Building PDF…"
        buildPdf { file ->
            if (file == null) { status = "Couldn't build PDF."; return@buildPdf }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.agentos.shell.fileprovider", file)
                ctx.startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/pdf")
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Share paper"
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                status = "Sharing PDF…"
            } catch (e: Exception) { status = "Couldn't share PDF: ${e.message}" }
        }
    }

    // One-tap publish to Zenodo: build the PDF, generate rich metadata, create+upload+publish → DOI.
    fun publishZenodo() {
        if (publishing) return
        val token = zToken.trim()
        if (token.isBlank()) { status = "Paste your Zenodo token first."; return }
        MemoryStore.setZenodoToken(ctx, token)
        showPublish = false; publishing = true
        status = "Building PDF…"
        val titleNow = titleOf(html, "Untitled")
        val author = MemoryStore.ownerName(ctx).ifBlank { "Anonymous" }
        val pubType = when (docType) { "whitepaper" -> "report"; "memo" -> "report"; else -> "preprint" }
        val plain = html.replace(Regex("(?is)<style.*?</style>"), " ").replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        buildPdf { file ->
          if (file == null) { publishing = false; status = "Couldn't build the PDF — try again."; return@buildPdf }
          val priorDep = PaperStore.zenodoId(ctx, currentId)
          status = if (priorDep > 0) "Publishing new version to Zenodo…" else "Publishing to Zenodo…"
          scope.launch {
            val (descAi, kws) = withContext(Dispatchers.IO) { AgentClient.zenodoMeta(titleNow, plain) }
            // This description becomes the PUBLIC abstract of a permanent DOI — never let a model error
            // string ("[couldn't…") mint into it; fall back to the paper's own text.
            val desc = if (AgentClient.looksLikeError(descAi)) plain.take(1200) else descAi.ifBlank { plain.take(1200) }
            val res = withContext(Dispatchers.IO) {
                ZenodoClient.publish(token, file, titleNow, author, "Belto", desc, pubType, kws, true, priorDep)
            }
            publishing = false
            if (res.ok) {
                if (res.depId > 0) PaperStore.setZenodoId(ctx, currentId, res.depId)
                val verb = if (priorDep > 0) "Published new version" else "Published to Zenodo"
                val msg = "✅ $verb!\nDOI: ${res.doi}\n${res.url}\n" +
                    (if (kws.isNotEmpty()) "Keywords: ${kws.joinToString(", ")}\n" else "") +
                    "Open-access · CC-BY-4.0 · listed as $pubType."
                PaperStore.addChat(ctx, currentId, "ai", msg); chat = PaperStore.chatLog(ctx, currentId)
                MemoryLog.add(ctx, "response", "Published: $titleNow", "Zenodo DOI ${res.doi}", "Research")
                status = "$verb ✓ DOI: ${res.doi}"
            } else {
                if (res.depId > 0) PaperStore.setZenodoId(ctx, currentId, res.depId)
                PaperStore.addChat(ctx, currentId, "ai", "Zenodo publish failed: ${res.error}. Check the token has deposit:write + deposit:actions scopes.")
                chat = PaperStore.chatLog(ctx, currentId)
                status = "Publish failed: ${res.error}"
            }
          }
        }
    }

    Column(modifier) {
        // Team mode is a full-screen office — no header, so it fills the whole panel (only the nav shows).
        if (mode != "team") {
            ScreenHeader(if (mode == "editor") "Paper" else "Research") {
                if (mode == "library") onBack() else { PaperStore.list(ctx); papers = PaperStore.list(ctx); mode = "library" }
            }
            Spacer(Modifier.height(4.dp))
            Text("Opus · $remaining/$CAP left today (new paper + each suggestion)", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(10.dp))
        }

        when (mode) {
            "library" -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New paper", fontSize = T.small, color = T.bgElevated, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable { prompt = ""; mode = "compose" }.padding(vertical = 10.dp))
                    Text("Chat", fontSize = T.small, color = T.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(T.hairline)
                            .clickable { onChat() }.padding(vertical = 10.dp))
                    Text("Cowork", fontSize = T.small, color = T.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(T.hairline)
                            .clickable { onWorkspace() }.padding(vertical = 10.dp))
                    Text("Team", fontSize = T.small, color = T.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(T.hairline)
                            .clickable { mode = "team" }.padding(vertical = 10.dp))
                }
                Spacer(Modifier.height(14.dp))
                BasicTextField(value = paperSearch, onValueChange = { paperSearch = it }, singleLine = true,
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner -> if (paperSearch.isEmpty()) Text("Search papers…", fontSize = T.small, color = T.inkFaint); inner() })
                Spacer(Modifier.height(12.dp))
                if (papers.isEmpty())
                    Text("No papers yet. Tap New paper to write one.", fontSize = T.small, color = T.inkFaint)
                val shownPapers = papers.filter { paperSearch.isBlank() || it.title.contains(paperSearch, true) }
                if (papers.isNotEmpty() && shownPapers.isEmpty())
                    Text("No papers match “$paperSearch”.", fontSize = T.small, color = T.inkFaint)
                LazyColumn(Modifier.weight(1f)) {
                    items(shownPapers, key = { it.id }) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { openPaper(p.id) },
                                    onLongClick = { renaming = p.id; renameText = p.title })
                                .padding(vertical = 12.dp)) {
                            Text("◆", color = T.accent, fontSize = T.small)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, fontSize = T.body, color = T.ink)
                                Text(fmt.format(Date(p.updated)), fontSize = T.caption, color = T.inkFaint)
                            }
                            Text("✕", fontSize = T.small, color = T.inkFaint,
                                modifier = Modifier.clickable { PaperStore.delete(ctx, p.id); papers = PaperStore.list(ctx) }
                                    .padding(start = 10.dp))
                        }
                        Hairline()
                    }
                }
            }
            "compose" -> {
                BasicTextField(
                    value = prompt, onValueChange = { prompt = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.body),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                        .clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
                    decorationBox = { inner -> if (prompt.isEmpty()) Text("What should the paper be about? Topic, angle, scope…", fontSize = T.small, color = T.inkFaint); inner() }
                )
                Spacer(Modifier.height(10.dp))
                // Document type — locks genre + voice for the whole document.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("paper" to "Academic", "whitepaper" to "White paper", "memo" to "Investor memo").forEach { (id, label) ->
                        val sel = docType == id
                        Text(label, fontSize = T.caption, color = if (sel) T.bgElevated else T.inkSoft,
                            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(999.dp))
                                .background(if (sel) T.accent else T.hairline).clickable { docType = id }
                                .padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = thesis, onValueChange = { thesis = it }, singleLine = true,
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
                    decorationBox = { inner -> if (thesis.isEmpty()) Text("Core thesis to stay anchored to (optional) — e.g. onboard data triage on the payload they already fly", fontSize = T.small, color = T.inkFaint); inner() }
                )
                Spacer(Modifier.height(8.dp))
                Text("🌐 Always researches the web & cites real sources with links · draws on your other papers via the brain",
                    fontSize = T.caption, color = T.inkFaint)
                if (KnowledgeStore.hasDoc(ctx)) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useDoc = !useDoc }) {
                        Text(if (useDoc) "☑" else "☐", fontSize = T.body, color = T.accent)
                        Spacer(Modifier.width(8.dp)); Text("Use my loaded PDF as source", fontSize = T.small, color = T.inkSoft)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(if (busy) "Writing…" else "Write paper", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (busy || prompt.isBlank()) T.hairline else T.accent)
                        .clickable(enabled = !busy && prompt.isNotBlank()) { generate() }
                        .padding(horizontal = 20.dp, vertical = 11.dp))
            }
            "team" -> TeamPanel(onExit = { mode = "library" })
            "editor" -> {
                // Top bar: switch between the conversational writing flow and the full paper view.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showPaper) "✎ Keep writing" else "▢ View paper", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable { showPaper = !showPaper }.padding(horizontal = 16.dp, vertical = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Text("Library", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { papers = PaperStore.list(ctx); mode = "library" }.padding(vertical = 8.dp))
                }
                Spacer(Modifier.height(10.dp))

                if (showPaper) {
                    // Full paper preview + export tools (styled with the clean print CSS).
                    val styled = remember(html, pendingHighlight) {
                        val s = ensureStyle(html); if (pendingHighlight) injectHighlight(s) else s
                    }
                    // Editable render: MathJax stripped (so \( \) stays literal, not rendered) + the
                    // whole body made directly tap-to-edit.
                    val editableStyled = remember(html, editMode) {
                        if (!editMode) "" else {
                            var s = ensureStyle(html)
                            s = Regex("(?is)<script[^>]*(mathjax|tex-mml)[^>]*>\\s*</script>").replace(s, "")
                            s = s.replaceFirst(Regex("(?i)<body"), "<body contenteditable=\"true\" style=\"caret-color:#E8642C\" ")
                            s
                        }
                    }
                    val shown = if (editMode) editableStyled else styled
                    // Highlight glows briefly, then stop re-triggering on later renders.
                    LaunchedEffect(pendingHighlight) {
                        if (pendingHighlight) { kotlinx.coroutines.delay(5000); pendingHighlight = false }
                    }
                    if (editMode) {
                        Text("✎ Tap any text to edit it directly. Math shows as code (\\(…\\)) while editing. Hit Save when done.",
                            fontSize = T.caption, color = T.accent)
                        Spacer(Modifier.height(6.dp))
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)),
                        factory = { c -> WebView(c).apply { settings.javaScriptEnabled = true; webRef = this
                            loadDataWithBaseURL("https://localhost/", shown, "text/html", "utf-8", null) } },
                        update = { wv -> if (wv.tag != shown) { wv.tag = shown; wv.loadDataWithBaseURL("https://localhost/", shown, "text/html", "utf-8", null) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    if (showSource) {
                        BasicTextField(value = html, onValueChange = { html = it; PaperStore.save(ctx, currentId, it) },
                            textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 200.dp)
                                .clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(10.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                      if (editMode) {
                        Text("✓ Save edits", fontSize = T.small, color = T.bgElevated,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                                .clickable { saveEdits() }.padding(horizontal = 16.dp, vertical = 9.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel", fontSize = T.small, color = T.inkSoft,
                            modifier = Modifier.clickable { editMode = false; status = "" }.padding(horizontal = 10.dp, vertical = 9.dp))
                      } else {
                        Text("✎ Edit", fontSize = T.small, color = T.ink,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                                .clickable { showSource = false; status = ""; editMode = true }.padding(horizontal = 14.dp, vertical = 9.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (publishing) "Publishing…" else "↑ Zenodo", fontSize = T.small, color = T.bgElevated,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (publishing) T.hairline else T.accent)
                                .clickable(enabled = !publishing) { zToken = MemoryStore.zenodoToken(ctx); showPublish = true }
                                .padding(horizontal = 16.dp, vertical = 9.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share", fontSize = T.small, color = T.ink,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                                .clickable { sharePdf() }.padding(horizontal = 12.dp, vertical = 9.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Print", fontSize = T.small, color = T.ink,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                                .clickable { exportPdf() }.padding(horizontal = 12.dp, vertical = 9.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clean up", fontSize = T.small, color = T.ink,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                                .clickable { finalize() }.padding(horizontal = 12.dp, vertical = 9.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("History", fontSize = T.small, color = T.ink,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                                .clickable { showHistory = true }.padding(horizontal = 12.dp, vertical = 9.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Source", fontSize = T.small, color = T.inkSoft,
                            modifier = Modifier.clickable { showSource = !showSource }.padding(vertical = 9.dp))
                      }
                    }
                } else {
                    // Conversational writing — like chatting with Claude about the paper. You get a real
                    // reply each turn (what was written + sources used); the document updates beside it.
                    val listState = rememberLazyListState()
                    LaunchedEffect(chat.size, busy) {
                        val n = chat.size + (if (busy) 1 else 0)
                        if (n > 0) listState.animateScrollToItem(n - 1)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                        if (chat.isEmpty()) item {
                            Text("Message me to write or change anything — “add a chapter on thermal limits”, “expand section 3 with current sources”, “sharpen the abstract”. I'll write it, reply with what I did and the sources I used, and update the paper. Tap View paper anytime to read it.",
                                fontSize = T.small, color = T.inkFaint, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        items(chat) { m ->
                            val you = m.role == "you"
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = if (you) Arrangement.End else Arrangement.Start) {
                                Text(m.text, fontSize = T.small, color = if (you) T.bgElevated else T.ink,
                                    modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                                        .background(if (you) T.accent else T.hairline).padding(horizontal = 12.dp, vertical = 9.dp))
                            }
                        }
                        if (busy) item {
                            Text("✍️  writing & researching…", fontSize = T.small, color = T.accent,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(value = chatInput, onValueChange = { chatInput = it },
                            textStyle = TextStyle(color = T.ink, fontSize = T.small),
                            modifier = Modifier.weight(1f).heightIn(min = 20.dp).clip(RoundedCornerShape(10.dp))
                                .background(T.bgElevated).padding(10.dp),
                            decorationBox = { inner -> if (chatInput.isEmpty()) Text("Message your paper…", fontSize = T.small, color = T.inkFaint); inner() })
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "…" else "→", fontSize = T.body, color = T.bgElevated,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (busy || chatInput.isBlank()) T.hairline else T.accent)
                                .clickable(enabled = !busy && chatInput.isNotBlank()) { send() }.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                }
            }
        }
        if (status.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(status, fontSize = T.small, color = T.accent) }
    }

    if (showHistory) {
        val vers = remember(showHistory, html) { PaperStore.versions(ctx, currentId) }
        Dialog(onDismissRequest = { showHistory = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(18.dp))
                .background(T.bgElevated).padding(16.dp)) {
                Text("Version history", fontSize = T.body, color = T.ink)
                Text("Tap any version to restore it. Your current version is saved first, so nothing is lost.",
                    fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(10.dp))
                if (vers.isEmpty()) Text("No versions yet — they're saved on each change.", fontSize = T.small, color = T.inkFaint)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(vers, key = { it.ts }) { v ->
                        Column(Modifier.fillMaxWidth().clickable {
                            PaperStore.snapshot(ctx, currentId, "Before restore")
                            val old = PaperStore.versionHtml(ctx, currentId, v.ts)
                            if (old.isNotBlank()) { html = old; PaperStore.save(ctx, currentId, html); status = "Restored: ${v.label}" }
                            showHistory = false
                        }.padding(vertical = 10.dp)) {
                            Text(v.label.ifBlank { "snapshot" }, fontSize = T.small, color = T.ink)
                            Text(fmt.format(Date(v.ts)), fontSize = T.caption, color = T.inkFaint)
                        }
                        Hairline()
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Close", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { showHistory = false })
            }
        }
    }

    if (showPublish) {
        Dialog(onDismissRequest = { showPublish = false }) {
            val alreadyPublished = PaperStore.zenodoId(ctx, currentId) > 0
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Text(if (alreadyPublished) "Publish new version to Zenodo" else "Publish to Zenodo", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(6.dp))
                Text(if (alreadyPublished)
                        "This paper is already on Zenodo. Publishing again creates a NEW VERSION of the same record " +
                        "(shared concept-DOI, with a new version DOI) and replaces the file. Permanent and public."
                     else
                        "This uploads the PDF and PUBLISHES it on zenodo.org — minting a permanent, public DOI. " +
                        "I'll auto-fill an abstract, keywords, author, open-access & CC-BY-4.0 for visibility.",
                    fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(12.dp))
                Text("Your Zenodo access token (stored only on this phone)", fontSize = T.caption, color = T.inkSoft)
                Spacer(Modifier.height(4.dp))
                BasicTextField(value = zToken, onValueChange = { zToken = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.hairline).padding(10.dp),
                    decorationBox = { inner -> if (zToken.isEmpty()) Text("paste token (needs deposit:write + deposit:actions)", fontSize = T.caption, color = T.inkFaint); inner() })
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Publish now", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (zToken.isBlank()) T.hairline else T.accent)
                            .clickable(enabled = zToken.isNotBlank()) { publishZenodo() }
                            .padding(horizontal = 18.dp, vertical = 10.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Cancel", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { showPublish = false }.padding(vertical = 10.dp))
                }
            }
        }
    }

    if (renaming != 0L) {
        Dialog(onDismissRequest = { renaming = 0L }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Rename", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = renameText, onValueChange = { renameText = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.body), singleLine = true,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.hairline).padding(12.dp))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Save", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable {
                                PaperStore.rename(ctx, renaming, renameText)
                                papers = PaperStore.list(ctx); renaming = 0L
                            }.padding(horizontal = 18.dp, vertical = 10.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Cancel", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { renaming = 0L }.padding(vertical = 10.dp))
                }
            }
        }
    }
}
