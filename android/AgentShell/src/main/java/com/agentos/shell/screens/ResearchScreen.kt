package com.agentos.shell.screens

import android.annotation.SuppressLint
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    var selectedChap by remember { mutableStateOf(-1) }   // -1 = whole paper
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
            val id = PaperStore.create(ctx, titleOf(out, prompt), out)
            papers = PaperStore.list(ctx); remaining = UsageLimiter.remaining(ctx, "paper", CAP)
            currentId = id; html = out; prompt = ""; mode = "editor"; busy = false; status = ""
        }
    }
    fun revise() {
        if (editPrompt.isBlank() || busy) return
        if (UsageLimiter.remaining(ctx, "paper", CAP) <= 0) { status = "Daily limit reached ($CAP/$CAP)."; return }
        val instr = editPrompt; busy = true; status = "Revising…"
        scope.launch {
            val mem = MemoryStore.about(ctx)
            val out = withContext(Dispatchers.IO) { AgentClient.revisePaper(html, instr, web, mem) }
            if (out.startsWith("ERR::")) { status = errText(out); busy = false; return@launch }
            UsageLimiter.use(ctx, "paper", CAP)
            html = out; PaperStore.save(ctx, currentId, html, titleOf(html, ""))
            editPrompt = ""; remaining = UsageLimiter.remaining(ctx, "paper", CAP); busy = false; status = ""
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

    // Revise just the selected chapter in place.
    fun editChapter() {
        if (editPrompt.isBlank() || busy) return
        val chaps = chapters(html)
        if (selectedChap !in chaps.indices) { status = "Pick a chapter to edit first."; return }
        if (UsageLimiter.remaining(ctx, "expand", EXPAND_CAP) <= 0) { status = "Daily limit reached ($EXPAND_CAP)."; return }
        val instr = editPrompt; val chap = chaps[selectedChap]; busy = true; status = "Revising “${chap.heading}”…"
        scope.launch {
            val mem = MemoryStore.about(ctx); val title = titleOf(html, "")
            val frag = withContext(Dispatchers.IO) { AgentClient.reviseChapter(title, chap.html, instr, web, mem) }
            if (frag.startsWith("ERR::")) { status = errText(frag); busy = false; return@launch }
            UsageLimiter.use(ctx, "expand", EXPAND_CAP)
            html = html.substring(0, chap.start) + frag.trim() + "\n" + html.substring(chap.end)
            PaperStore.save(ctx, currentId, html)
            editPrompt = ""; busy = false; status = "“${chap.heading}” updated ✓"
        }
    }

    // Expand = append a brand-new chapter (never a full rewrite), so the paper can grow without limit.
    fun expand() {
        if (editPrompt.isBlank() || busy) return
        if (UsageLimiter.remaining(ctx, "expand", EXPAND_CAP) <= 0) { status = "Daily expand limit reached ($EXPAND_CAP)."; return }
        val instr = editPrompt; busy = true; status = "Opus is writing a new chapter…"
        scope.launch {
            val mem = MemoryStore.about(ctx)
            val title = titleOf(html, "")
            val outline = outlineOf(html)
            val frag = withContext(Dispatchers.IO) { AgentClient.expandPaper(title, outline, instr, web, mem) }
            if (frag.startsWith("ERR::")) { status = errText(frag); busy = false; return@launch }
            UsageLimiter.use(ctx, "expand", EXPAND_CAP)
            html = insertBeforeBodyEnd(html, frag)
            PaperStore.save(ctx, currentId, html)
            editPrompt = ""; busy = false; status = "Chapter added ✓"
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

    fun sharePdf() {
        val wv = webRef ?: return
        try {
            val density = ctx.resources.displayMetrics.density
            val w = if (wv.width > 0) wv.width else 1080
            val h = (wv.contentHeight * density).toInt().coerceIn(wv.height.coerceAtLeast(1), 20000)
            val doc = android.graphics.pdf.PdfDocument()
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, 1).create())
            wv.draw(page.canvas)
            doc.finishPage(page)
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
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)),
                    factory = { c -> WebView(c).apply { settings.javaScriptEnabled = true; webRef = this
                        loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null) } },
                    update = { wv -> if (wv.tag != html) { wv.tag = html; wv.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null) } }
                )
                Spacer(Modifier.height(8.dp))
                if (showSource) {
                    BasicTextField(value = html, onValueChange = { html = it; PaperStore.save(ctx, currentId, it) },
                        textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 200.dp)
                            .clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(10.dp))
                    Spacer(Modifier.height(8.dp))
                }
                // Chapter picker — choose what "Edit" targets: the whole paper or one chapter.
                val chaps = remember(html) { chapters(html) }
                if (chaps.isNotEmpty()) {
                    if (selectedChap >= chaps.size) selectedChap = -1
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically) {
                        val chip = @Composable { label: String, sel: Boolean, onClick: () -> Unit ->
                            Text(label, fontSize = T.caption, color = if (sel) T.bgElevated else T.inkSoft,
                                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(999.dp))
                                    .background(if (sel) T.accent else T.hairline).clickable { onClick() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        chip("Whole paper", selectedChap == -1) { selectedChap = -1 }
                        chaps.forEachIndexed { i, c ->
                            chip(c.heading.take(22).ifBlank { "Ch ${i + 1}" }, selectedChap == i) { selectedChap = i }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val editingChapter = selectedChap >= 0 && chaps.isNotEmpty()
                    BasicTextField(value = editPrompt, onValueChange = { editPrompt = it }, singleLine = true,
                        textStyle = TextStyle(color = T.ink, fontSize = T.small),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(10.dp),
                        decorationBox = { inner ->
                            if (editPrompt.isEmpty()) Text(
                                if (editingChapter) "how should this chapter change?" else "edit the paper, or describe a chapter to add…",
                                fontSize = T.small, color = T.inkFaint); inner() })
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "…" else "Edit", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.ink)
                            .clickable(enabled = !busy && editPrompt.isNotBlank()) {
                                if (editingChapter) editChapter() else revise()
                            }.padding(horizontal = 13.dp, vertical = 9.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "…" else "＋ Expand", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable(enabled = !busy && editPrompt.isNotBlank()) { expand() }.padding(horizontal = 13.dp, vertical = 9.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text("Pick a chapter to edit just that part · “Whole paper” rewrites all · Expand appends a new chapter",
                    fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Share", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable { sharePdf() }.padding(horizontal = 16.dp, vertical = 9.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Print/Save", fontSize = T.small, color = T.ink,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                            .clickable { exportPdf() }.padding(horizontal = 14.dp, vertical = 9.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (showSource) "Source" else "Source", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { showSource = !showSource }.padding(vertical = 9.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Library", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { papers = PaperStore.list(ctx); mode = "library" }.padding(vertical = 9.dp))
                }
            }
        }
        if (status.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(status, fontSize = T.small, color = T.accent) }
    }
}
