package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.webkit.WebView
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.JobDoc
import com.agentos.shell.tools.JobStore
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The job hunt, made stupid-simple: connect LinkedIn, paste a link, Generate — then preview, edit,
 *  save, and email your résumé + cover letter. */
@Composable
fun JobScreen(modifier: Modifier = Modifier, initialTarget: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var link by remember { mutableStateOf(JobStore.posting(ctx)) }
    var resume by remember { mutableStateOf(JobStore.resume(ctx)) }
    var resumeHtml by remember { mutableStateOf("") }
    var coverHtml by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var resumeFile by remember { mutableStateOf<File?>(null) }
    var coverFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf("") }
    var liDone by remember { mutableStateOf(MemoryStore.positions(ctx).isNotBlank()) }
    var leads by remember { mutableStateOf<List<AgentClient.JobLead>>(emptyList()) }
    var findMsg by remember { mutableStateOf("") }
    var fullView by remember { mutableStateOf("") }   // html shown full-screen

    fun feed(note: String, s: Int) {
        com.agentos.shell.tools.MessageStore.insertOne(ctx, "Career", "Job hunt", "me", "me", note)
        com.agentos.shell.tools.MetricsStore.record(ctx, s)
    }
    suspend fun resolve(p: String): String {
        val t = p.trim(); if (!t.startsWith("http")) return p
        return kotlinx.coroutines.suspendCancellableCoroutine { c ->
            JobDoc.fetchPageText(ctx, t) { x -> if (c.isActive) c.resumeWith(Result.success(if (x.length > 80) x else p)) }
        }
    }

    val liPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = "li"
            withContext(Dispatchers.IO) { uris.forEach { com.agentos.shell.tools.ConnectionStore.importLinkedIn(ctx, it) } }
            liDone = true; busy = ""
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val txt = withContext(Dispatchers.IO) {
                try {
                    val b = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    if (b.size >= 4 && b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte()) {
                        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
                        com.tom_roush.pdfbox.pdmodel.PDDocument.load(b).use { com.tom_roush.pdfbox.text.PDFTextStripper().getText(it) }
                    } else String(b, Charsets.UTF_8)
                } catch (e: Exception) { "" }
            }
            if (txt.isNotBlank()) { resume = txt.trim(); JobStore.setResume(ctx, resume) }
        }
    }

    fun generate() {
        if (busy.isNotEmpty()) return
        busy = "gen"
        scope.launch {
            val posting = resolve(link.ifBlank { initialTarget })
            var r = resume
            if (r.isBlank()) {
                r = withContext(Dispatchers.IO) { AgentClient.jobResumeFromBrain(MemoryStore.fullProfile(ctx)) }
                if (r.length > 20 && !r.startsWith("[")) { resume = r; JobStore.setResume(ctx, r) }
            }
            val rh = withContext(Dispatchers.IO) { AgentClient.jobResumeHtmlDoc(r, posting) }
            if (rh.contains("<")) { resumeHtml = rh; resumeFile = null; JobDoc.htmlToPdf(ctx, rh, "resume") { f -> resumeFile = f } }
            val ch = withContext(Dispatchers.IO) { AgentClient.jobCoverHtmlDoc(r, posting, MemoryStore.fullProfile(ctx)) }
            if (ch.contains("<")) { coverHtml = ch; coverFile = null; JobDoc.htmlToPdf(ctx, ch, "cover") { f -> coverFile = f } }
            email = withContext(Dispatchers.IO) { AgentClient.jobEmail(r, posting, MemoryStore.fullProfile(ctx)) }
            feed("Drafted an application (résumé + cover + email)", 1500)
            busy = ""
        }
    }
    fun revise(which: String, instruction: String) {
        if (busy.isNotEmpty() || instruction.isBlank()) return
        busy = "rev"
        scope.launch {
            val src = if (which == "r") resumeHtml else coverHtml
            val h = withContext(Dispatchers.IO) { AgentClient.jobReviseHtmlDoc(src, instruction) }
            if (h.contains("<")) {
                if (which == "r") { resumeHtml = h; resumeFile = null; JobDoc.htmlToPdf(ctx, h, "resume") { f -> resumeFile = f } }
                else { coverHtml = h; coverFile = null; JobDoc.htmlToPdf(ctx, h, "cover") { f -> coverFile = f } }
            }
            busy = ""
        }
    }

    fun findJobs(q: String) {
        if (busy.isNotEmpty() || q.isBlank()) return
        busy = "find"; leads = emptyList(); findMsg = ""
        scope.launch {
            val res = withContext(Dispatchers.IO) { AgentClient.jobFindOpenings(q, MemoryStore.fullProfile(ctx)) }
            leads = res; busy = ""
            if (res.isNotEmpty()) feed("Found ${res.size} job openings for “$q”", 300)
            else findMsg = "No results yet — paste a job link below, or set replies to Claude in Settings so I can search the web."
        }
    }

    // "Find me a job at Goldman Sachs doing X" → search and show real openings to pick from.
    LaunchedEffect(Unit) {
        if (initialTarget.isNotBlank()) { if (link.isBlank()) link = initialTarget; findJobs(initialTarget) }
    }

    @Composable
    fun bigBtn(label: String, accent: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        Text(label, fontSize = T.body, color = if (accent) T.bgElevated else T.ink, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (accent) (if (enabled) T.accent else T.hairline) else T.bgElevated)
                .clickable(enabled = enabled) { onClick() }.padding(vertical = 14.dp))
    }

    @Composable
    fun docCard(title: String, html: String, file: File?, which: String) {
        if (html.isBlank()) return
        var editing by remember { mutableStateOf(false) }
        var rev by remember { mutableStateOf("") }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
            Text("View", fontSize = T.small, color = T.accent, modifier = Modifier.clickable { fullView = html }.padding(8.dp))
            Text("PDF", fontSize = T.small, color = T.accent, modifier = Modifier.clickable { JobDoc.printHtml(ctx, html, which) }.padding(8.dp))
            Text(if (editing) "Close" else "Edit", fontSize = T.small, color = T.accent,
                modifier = Modifier.clickable { editing = !editing }.padding(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        AndroidView(
            factory = { c -> WebView(c).apply { settings.javaScriptEnabled = false; setBackgroundColor(android.graphics.Color.WHITE) } },
            update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
            modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.height(4.dp))
        Text("Tap View for the full, scrollable page.", fontSize = T.caption, color = T.inkFaint)
        if (editing) {
            Spacer(Modifier.height(8.dp))
            BasicTextField(rev, { rev = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
                decorationBox = { inner -> if (rev.isEmpty()) Text("What should I change?", fontSize = T.small, color = T.inkFaint); inner() })
            Spacer(Modifier.height(6.dp))
            bigBtn(if (busy == "rev") "Editing…" else "Apply change", accent = true, enabled = busy.isEmpty()) { revise(which, rev); editing = false }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Find a job") { JobStore.setPosting(ctx, link); onBack() }
        Spacer(Modifier.height(14.dp))

        bigBtn(if (liDone) "LinkedIn connected ✓" else "Connect LinkedIn", accent = !liDone, enabled = busy != "li") {
            liPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
        }
        if (!liDone) {
            Spacer(Modifier.height(6.dp))
            Text("LinkedIn ▸ Settings ▸ Get a copy of your data ▸ import the CSVs.", fontSize = T.caption, color = T.inkFaint)
        }

        Spacer(Modifier.height(14.dp))
        BasicTextField(link, { link = it }, singleLine = false, textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
            decorationBox = { inner -> if (link.isEmpty()) Text("Paste a job link — or type what you want", fontSize = T.small, color = T.inkFaint); inner() })

        Spacer(Modifier.height(10.dp))
        bigBtn(if (busy == "find") "Searching…" else "Find jobs online", accent = false, enabled = busy.isEmpty()) { findJobs(link.ifBlank { initialTarget }) }
        if (findMsg.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(findMsg, fontSize = T.caption, color = T.inkFaint) }

        leads.forEach { lead ->
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated)
                .clickable { link = lead.url; JobStore.setPosting(ctx, lead.url); generate() }.padding(14.dp)) {
                Text(lead.title + (if (lead.company.isNotBlank()) " · " + lead.company else ""), fontSize = T.small, color = T.ink)
                Text((if (lead.location.isNotBlank()) lead.location + "  ·  " else "") + "tap to generate", fontSize = T.caption, color = T.accent)
            }
        }

        Spacer(Modifier.height(14.dp))
        bigBtn(if (busy == "gen") "Working…" else "Generate from link", accent = true, enabled = busy.isEmpty()) { generate() }

        docCard("Résumé", resumeHtml, resumeFile, "r")
        docCard("Cover letter", coverHtml, coverFile, "c")

        if (email.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Email", fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
                Text("Copy", fontSize = T.small, color = T.accent, modifier = Modifier.clickable { clip.setText(AnnotatedString(email)) }.padding(8.dp))
            }
            Spacer(Modifier.height(6.dp))
            BasicTextField(email, { email = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp))
        }

        if (resumeFile != null || coverFile != null) {
            Spacer(Modifier.height(16.dp))
            bigBtn("Email with attachments", accent = true) {
                JobDoc.emailWithAttachments(ctx, "", "Application",
                    email.ifBlank { "Hi,\n\nPlease find my résumé and cover letter attached.\n\nBest," },
                    listOfNotNull(resumeFile, coverFile))
                feed("Sent an application (résumé + cover attached)", 1500)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // Full-screen, scrollable preview of a whole document.
    if (fullView.isNotBlank()) {
        Dialog(onDismissRequest = { fullView = "" }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
                AndroidView(
                    factory = { c -> WebView(c).apply { settings.javaScriptEnabled = false; settings.builtInZoomControls = true; settings.displayZoomControls = false } },
                    update = { it.loadDataWithBaseURL(null, fullView, "text/html", "utf-8", null) },
                    modifier = Modifier.fillMaxSize())
                Text("Close", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).clip(RoundedCornerShape(999.dp))
                        .background(T.accent).clickable { fullView = "" }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}
