package com.agentos.shell.screens

import android.annotation.SuppressLint
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.KnowledgeStore
import com.agentos.shell.tools.MemoryLog
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.PaperStore
import com.agentos.shell.tools.UsageLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CAP = 6
private const val EXPAND_CAP = 80   // expansions are append-only chapters; allow many so papers can grow huge

// A clean, A4-proportioned print/screen stylesheet injected into every paper so content sits in a
// readable column with real margins and never breaks across pages mid-element.
private const val PAPER_CSS = """
<style id="slyos-print">
@page { size: A4; margin: 22mm 18mm; }
* { box-sizing: border-box; }
html, body { background:#fff !important; }
body { font-family: Georgia,'Times New Roman',serif !important; font-size:11.5pt !important;
  line-height:1.55 !important; color:#1A1714 !important; max-width:170mm !important;
  margin:0 auto !important; padding:14mm 6mm !important; text-rendering:optimizeLegibility;
  -webkit-print-color-adjust:exact; }
h1 { font-size:20pt; text-align:center; line-height:1.25; margin:0 0 6pt; }
h2 { font-size:14.5pt; margin:20pt 0 7pt; break-after:avoid; page-break-after:avoid; }
h3 { font-size:12pt; margin:13pt 0 4pt; break-after:avoid; page-break-after:avoid; }
h1,h2,h3,h4 { break-inside:avoid; page-break-inside:avoid; }
p { margin:0 0 9pt; text-align:justify; orphans:3; widows:3; }
ul,ol { margin:0 0 9pt 18pt; }
li { margin:0 0 4pt; }
pre,table,figure,blockquote { break-inside:avoid; page-break-inside:avoid; max-width:100%; overflow-x:auto; }
img { max-width:100%; height:auto; }
table { width:100%; border-collapse:collapse; font-size:10.5pt; }
th,td { border:1px solid #d8d0c2; padding:5px 8px; text-align:left; }
mjx-container { overflow-x:auto; max-width:100%; }
.references li, .references p { font-size:10.5pt; }
</style>
"""

/** Make sure the paper's <head> carries our print CSS (idempotent — replaces any prior copy). */
private fun ensureStyle(html: String): String {
    val h = Regex("(?is)<style id=\"slyos-print\">.*?</style>").replace(html, "")
    val headClose = Regex("(?i)</head>").find(h)
    if (headClose != null) return h.substring(0, headClose.range.first) + PAPER_CSS + h.substring(headClose.range.first)
    val bodyOpen = Regex("(?i)<body[^>]*>").find(h)
    if (bodyOpen != null) return h.substring(0, bodyOpen.range.last + 1) + PAPER_CSS + h.substring(bodyOpen.range.last + 1)
    return PAPER_CSS + h
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ResearchScreen(modifier: Modifier = Modifier, initialTopic: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(if (initialTopic.isNotBlank()) "compose" else "library") }
    var papers by remember { mutableStateOf(PaperStore.list(ctx)) }
    var currentId by remember { mutableStateOf(0L) }
    var html by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf(initialTopic) }
    var editPrompt by remember { mutableStateOf("") }
    var lastInstr by remember { mutableStateOf("") }
    var proposal by remember { mutableStateOf("") }     // suggested fragment, not yet added
    var propKind by remember { mutableStateOf("") }     // add | edit | intro
    var propLabel by remember { mutableStateOf("") }
    var propStart by remember { mutableStateOf(0) }
    var propEnd by remember { mutableStateOf(0) }
    var showPaper by remember { mutableStateOf(false) } // full paper hidden by default
    var web by remember { mutableStateOf(false) }
    var useDoc by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf(UsageLimiter.remaining(ctx, "paper", CAP)) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    fun titleOf(h: String, fallback: String): String =
        Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() } ?: fallback.take(60).ifBlank { "Untitled" }

    fun openPaper(id: Long) { currentId = id; html = PaperStore.html(ctx, id); mode = "editor"; status = "" }

    fun errText(out: String) = "Failed (" + out.removePrefix("ERR::").replace("::", "): ")

    fun generate() {
        if (prompt.isBlank() || busy) return
        if (UsageLimiter.remaining(ctx, "paper", CAP) <= 0) { status = "Daily limit reached ($CAP/$CAP). Resets tomorrow."; return }
        busy = true; status = if (web) "Opus is researching the web + writing…" else "Opus is writing…"
        scope.launch {
            val source = if (useDoc && KnowledgeStore.hasDoc(ctx)) KnowledgeStore.retrieve(ctx, prompt, 12000) else ""
            val mem = MemoryStore.about(ctx)
            val out = withContext(Dispatchers.IO) { AgentClient.writePaper(prompt, source, web, mem) }
            if (out.startsWith("ERR::")) { status = errText(out); busy = false; return@launch }
            UsageLimiter.use(ctx, "paper", CAP)
            val newTitle = titleOf(out, prompt)
            val id = PaperStore.create(ctx, newTitle, out)
            MemoryLog.add(ctx, "response", "Paper: $newTitle", "Wrote a research paper: $newTitle", "Research")
            papers = PaperStore.list(ctx); remaining = UsageLimiter.remaining(ctx, "paper", CAP)
            currentId = id; html = out; prompt = ""; mode = "editor"; busy = false; status = ""
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

    fun frontMatterEnd(h: String): Int {
        val firstH2 = Regex("(?is)<h2[^>]*>").find(h)?.range?.first
        if (firstH2 != null) return firstH2
        val b = h.lastIndexOf("</body>", ignoreCase = true)
        return if (b > 0) b else h.length
    }

    // Render a fragment on its own (same clean style) so it can be previewed before it's added.
    fun wrapFragment(frag: String): String =
        "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<script src='https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js'></script>" +
        PAPER_CSS + "</head><body>" + frag + "</body></html>"

    // Step 1: ask Opus what to add/change. It SUGGESTS a fragment; nothing is committed yet.
    fun propose() {
        if (editPrompt.isBlank() || busy) return
        if (UsageLimiter.remaining(ctx, "expand", EXPAND_CAP) <= 0) { status = "Daily limit reached ($EXPAND_CAP)."; return }
        val instr = editPrompt; lastInstr = instr; busy = true; status = "Thinking it through…"; proposal = ""
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
            when {
                action == "edit" && target.equals("INTRO", true) -> {
                    val head = html.substring(0, frontMatterEnd(html))
                    val frag = withContext(Dispatchers.IO) { AgentClient.reviseFrontMatter(head, instr, mem) }
                    if (frag.startsWith("ERR::")) { status = errText(frag); busy = false; return@launch }
                    proposal = frag; propKind = "intro"; propLabel = "Title / abstract / intro"
                }
                chap != null -> {
                    val frag = withContext(Dispatchers.IO) { AgentClient.reviseChapter(title, chap.html, instr, web, mem) }
                    if (frag.startsWith("ERR::")) { status = errText(frag); busy = false; return@launch }
                    proposal = frag; propKind = "edit"; propLabel = chap.heading; propStart = chap.start; propEnd = chap.end
                }
                else -> {
                    val frag = withContext(Dispatchers.IO) { AgentClient.expandPaper(title, outline, instr, web, mem) }
                    if (frag.startsWith("ERR::")) { status = errText(frag); busy = false; return@launch }
                    proposal = frag; propKind = "add"; propLabel = "New chapter"
                }
            }
            busy = false; status = "Here's a suggestion — add it, or ask for changes."
        }
    }

    // Step 2: you approve → splice it into the paper (in place, nothing else touched).
    fun commit() {
        if (proposal.isBlank() || busy) return
        val frag = proposal
        when (propKind) {
            "intro" -> { val end = frontMatterEnd(html); html = frag.trim() + "\n" + html.substring(end) }
            "edit" -> { html = html.substring(0, propStart) + frag.trim() + "\n" + html.substring(propEnd) }
            else -> { html = insertBeforeBodyEnd(html, frag) }
        }
        UsageLimiter.use(ctx, "expand", EXPAND_CAP)
        PaperStore.save(ctx, currentId, html)
        MemoryLog.add(ctx, "response", "Paper: ${titleOf(html, "")}", "$propLabel — $lastInstr", "Research")
        papers = PaperStore.list(ctx)
        proposal = ""; editPrompt = ""; status = "Added ✓ — tap View paper to review."
    }
    fun discard() { proposal = ""; status = "Discarded." }
    fun exportPdf() {
        val wv = webRef ?: return
        try {
            val pm = ctx.getSystemService(android.content.Context.PRINT_SERVICE) as PrintManager
            pm.print("SlyOS Paper", wv.createPrintDocumentAdapter("SlyOS Paper"),
                PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        } catch (e: Exception) { status = "Couldn't open PDF export." }
    }

    fun sharePdf() {
        val wv = webRef ?: return
        try {
            val density = ctx.resources.displayMetrics.density
            val w = if (wv.width > 0) wv.width else 1080
            val totalH = (wv.contentHeight * density).toInt().coerceIn(1, 200000)
            // Slice the rendered paper into real A4-proportioned pages with a numbered footer.
            val pageH = (w * 842f / 595f).toInt()
            val footer = (pageH * 0.045f).toInt()
            val usableH = (pageH - footer).coerceAtLeast(1)
            val pageCount = ((totalH + usableH - 1) / usableH).coerceAtLeast(1)
            val full = android.graphics.Bitmap.createBitmap(w, totalH, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(full).apply { drawColor(android.graphics.Color.WHITE); wv.draw(this) }
            val foot = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#8A8076"); textSize = w * 0.020f
                textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
            }
            val doc = android.graphics.pdf.PdfDocument()
            for (i in 0 until pageCount) {
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, pageH, i + 1).create())
                val canvas = page.canvas; canvas.drawColor(android.graphics.Color.WHITE)
                val top = i * usableH
                val sliceH = minOf(usableH, totalH - top)
                if (sliceH > 0) {
                    val src = android.graphics.Rect(0, top, w, top + sliceH)
                    val dst = android.graphics.Rect(0, 0, w, sliceH)
                    canvas.drawBitmap(full, src, dst, null)
                }
                canvas.drawText("Page ${i + 1} of $pageCount", w / 2f, pageH - footer / 2f, foot)
                doc.finishPage(page)
            }
            full.recycle()
            val file = java.io.File(ctx.cacheDir, "paper_$currentId.pdf")
            java.io.FileOutputStream(file).use { doc.writeTo(it) }; doc.close()
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
        } catch (e: Exception) { status = "Couldn't build PDF: ${e.message}" }
    }

    Column(modifier) {
        ScreenHeader(if (mode == "editor") "Paper" else "Research") {
            if (mode == "library") onBack() else { PaperStore.list(ctx); papers = PaperStore.list(ctx); mode = "library" }
        }
        Spacer(Modifier.height(4.dp))
        Text("Opus papers · $remaining/$CAP left today", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(10.dp))

        when (mode) {
            "library" -> {
                Text("＋ New paper", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { prompt = ""; mode = "compose" }.padding(horizontal = 18.dp, vertical = 10.dp))
                Spacer(Modifier.height(14.dp))
                if (papers.isEmpty())
                    Text("No papers yet. Tap New paper to write one.", fontSize = T.small, color = T.inkFaint)
                LazyColumn(Modifier.weight(1f)) {
                    items(papers, key = { it.id }) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { openPaper(p.id) }.padding(vertical = 12.dp)) {
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { web = !web }) {
                    Text(if (web) "☑" else "☐", fontSize = T.body, color = T.accent)
                    Spacer(Modifier.width(8.dp)); Text("Research the web & cite sources", fontSize = T.small, color = T.inkSoft)
                }
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
                    val styled = remember(html) { ensureStyle(html) }
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)),
                        factory = { c -> WebView(c).apply { settings.javaScriptEnabled = true; webRef = this
                            loadDataWithBaseURL("https://localhost/", styled, "text/html", "utf-8", null) } },
                        update = { wv -> if (wv.tag != styled) { wv.tag = styled; wv.loadDataWithBaseURL("https://localhost/", styled, "text/html", "utf-8", null) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    if (showSource) {
                        BasicTextField(value = html, onValueChange = { html = it; PaperStore.save(ctx, currentId, it) },
                            textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 200.dp)
                                .clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(10.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Share", fontSize = T.small, color = T.bgElevated,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                                .clickable { sharePdf() }.padding(horizontal = 16.dp, vertical = 9.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Print/Save", fontSize = T.small, color = T.ink,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                                .clickable { exportPdf() }.padding(horizontal = 14.dp, vertical = 9.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Source", fontSize = T.small, color = T.inkSoft,
                            modifier = Modifier.clickable { showSource = !showSource }.padding(vertical = 9.dp))
                    }
                } else if (proposal.isNotEmpty()) {
                    // A suggestion is on the table — preview it, then approve or refine.
                    Text("Suggestion · $propLabel", fontSize = T.caption, color = T.accent)
                    Spacer(Modifier.height(6.dp))
                    val preview = remember(proposal) { if (propKind == "intro") proposal else wrapFragment(proposal) }
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)),
                        factory = { c -> WebView(c).apply { settings.javaScriptEnabled = true
                            loadDataWithBaseURL("https://localhost/", preview, "text/html", "utf-8", null) } },
                        update = { wv -> if (wv.tag != preview) { wv.tag = preview; wv.loadDataWithBaseURL("https://localhost/", preview, "text/html", "utf-8", null) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (busy) "…" else "✓ Add to paper", fontSize = T.small, color = T.bgElevated,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                                .clickable(enabled = !busy) { commit() }.padding(horizontal = 16.dp, vertical = 9.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("↻ Redo", fontSize = T.small, color = T.ink,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                                .clickable(enabled = !busy) { editPrompt = lastInstr; propose() }.padding(horizontal = 14.dp, vertical = 9.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("✕ Discard", fontSize = T.small, color = T.inkSoft,
                            modifier = Modifier.clickable(enabled = !busy) { discard() }.padding(vertical = 9.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(value = editPrompt, onValueChange = { editPrompt = it },
                            textStyle = TextStyle(color = T.ink, fontSize = T.small),
                            modifier = Modifier.weight(1f).heightIn(min = 20.dp).clip(RoundedCornerShape(10.dp))
                                .background(T.bgElevated).padding(10.dp),
                            decorationBox = { inner -> if (editPrompt.isEmpty()) Text("ask for changes, or something new…", fontSize = T.small, color = T.inkFaint); inner() })
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "…" else "→", fontSize = T.body, color = T.bgElevated,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.ink)
                                .clickable(enabled = !busy && editPrompt.isNotBlank()) { propose() }.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                } else {
                    // Empty conversational state — just ask.
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (busy) "Thinking it through…"
                             else "What should we add or change?\n\nI'll suggest it → you approve → it goes into the paper.\nThen tap View paper to review.",
                            fontSize = T.small, color = T.inkFaint)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(value = editPrompt, onValueChange = { editPrompt = it },
                            textStyle = TextStyle(color = T.ink, fontSize = T.small),
                            modifier = Modifier.weight(1f).heightIn(min = 20.dp).clip(RoundedCornerShape(10.dp))
                                .background(T.bgElevated).padding(10.dp),
                            decorationBox = { inner -> if (editPrompt.isEmpty()) Text("e.g. add a chapter on…, expand section 3, sharpen the abstract…", fontSize = T.small, color = T.inkFaint); inner() })
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "…" else "→", fontSize = T.body, color = T.bgElevated,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                                .clickable(enabled = !busy && editPrompt.isNotBlank()) { propose() }.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                }
            }
        }
        if (status.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(status, fontSize = T.small, color = T.accent) }
    }
}
