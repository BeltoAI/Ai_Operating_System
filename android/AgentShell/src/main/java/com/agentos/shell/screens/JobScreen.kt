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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.JobDoc
import com.agentos.shell.tools.JobStore
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.PdfBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The job-hunt assistant. Give it (or draft) your résumé, name a target, paste a posting, and it
 * produces a tailored résumé, a cover letter, and a ready outreach email — each copyable and
 * savable as a PDF you can attach. Reached from Home by saying "find me a job".
 */
@Composable
fun JobScreen(modifier: Modifier = Modifier, initialTarget: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var resume by remember { mutableStateOf(JobStore.resume(ctx)) }
    // A fresh target from Home ("find a job at IBM") ALWAYS wins over a previously-saved one.
    var target by remember { mutableStateOf(initialTarget.ifBlank { JobStore.target(ctx) }) }
    var posting by remember { mutableStateOf(JobStore.posting(ctx)) }
    var email by remember { mutableStateOf("") }
    var ideas by remember { mutableStateOf("") }
    var resumeHtml by remember { mutableStateOf("") }
    var coverHtml by remember { mutableStateOf("") }
    var resumeFile by remember { mutableStateOf<File?>(null) }
    var coverFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf("") }   // which action is running
    var liMsg by remember { mutableStateOf("") }

    // Everything the job hunt produces flows INTO the brain and counts toward your time-saved score.
    fun feed(note: String, seconds: Int) {
        com.agentos.shell.tools.MessageStore.insertOne(ctx, "Career", "Job hunt", "me", "me", note)
        com.agentos.shell.tools.MetricsStore.record(ctx, seconds)
    }

    // If the posting is a URL, read the actual page text so we tailor to THIS job (not just a link).
    suspend fun resolvePosting(p: String): String {
        val t = p.trim()
        if (!t.startsWith("http")) return p
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            JobDoc.fetchPageText(ctx, t) { txt -> if (cont.isActive) cont.resumeWith(Result.success(if (txt.length > 80) txt else p)) }
        }
    }

    // "Find a job at Apple doing X" → do the work: open live listings AND auto-draft a designed
    // résumé, cover letter and outreach email so you land on finished documents, not a blank form.
    LaunchedEffect(Unit) {
        if (initialTarget.isBlank()) return@LaunchedEffect
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.linkedin.com/jobs/search/?keywords=" +
                    java.net.URLEncoder.encode(initialTarget, "UTF-8"))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
        busy = "auto"
        val ctxFor = resolvePosting(if (posting.isBlank()) initialTarget else posting)
        var r = resume
        if (r.isBlank()) {
            r = withContext(Dispatchers.IO) { AgentClient.jobResumeFromBrain(MemoryStore.fullProfile(ctx)) }
            if (r.length > 20 && !r.startsWith("[")) { resume = r; JobStore.setResume(ctx, r) }
        }
        val rh = withContext(Dispatchers.IO) { AgentClient.jobResumeHtmlDoc(r, ctxFor) }
        if (rh.contains("<")) { resumeHtml = rh; JobDoc.htmlToPdf(ctx, rh, "resume") { f -> resumeFile = f } }
        val ch = withContext(Dispatchers.IO) { AgentClient.jobCoverHtmlDoc(r, ctxFor, MemoryStore.fullProfile(ctx)) }
        if (ch.contains("<")) { coverHtml = ch; JobDoc.htmlToPdf(ctx, ch, "cover") { f -> coverFile = f } }
        email = withContext(Dispatchers.IO) { AgentClient.jobEmail(r, ctxFor, MemoryStore.fullProfile(ctx)) }
        feed("Auto-drafted a full application (résumé, cover letter, email) for $initialTarget", 1800)
        busy = ""
    }

    fun run(tag: String, block: suspend () -> String, onResult: (String) -> Unit) {
        if (busy.isNotEmpty()) return
        busy = tag
        scope.launch { val r = withContext(Dispatchers.IO) { block() }; onResult(r); busy = "" }
    }

    // Import your LinkedIn export CSVs (Positions/Education/Profile/Connections) straight into the brain.
    val liPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            liMsg = "Reading ${uris.size} LinkedIn file(s)…"
            val msgs = withContext(Dispatchers.IO) { uris.map { com.agentos.shell.tools.ConnectionStore.importLinkedIn(ctx, it) } }
            liMsg = msgs.joinToString(" · ")
        }
    }

    // Read a plain-text or PDF résumé from a file.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    if (bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte()) {
                        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
                        com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
                            com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
                        }
                    } else String(bytes, Charsets.UTF_8)
                } catch (e: Exception) { "" }
            }
            if (text.isNotBlank()) { resume = text.trim(); JobStore.setResume(ctx, resume) }
        }
    }

    @Composable
    fun pill(label: String, tag: String, accent: Boolean = false, onClick: () -> Unit) {
        Text(if (busy == tag) "…" else label, fontSize = T.small, maxLines = 1, softWrap = false,
            color = if (accent) T.bgElevated else T.inkSoft,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (accent) T.accent else T.hairline)
                .clickable(enabled = busy.isEmpty()) { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp))
    }

    @Composable
    fun output(title: String, text: String, onClear: () -> Unit) {
        if (text.isBlank()) return
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
                Text("Copy", fontSize = T.caption, color = T.accent,
                    modifier = Modifier.clickable { clip.setText(AnnotatedString(text)) }.padding(horizontal = 8.dp, vertical = 4.dp))
                Text("Save PDF", fontSize = T.caption, color = T.accent,
                    modifier = Modifier.clickable { PdfBuilder.makePdf(ctx, title, text) }.padding(horizontal = 8.dp, vertical = 4.dp))
                Text("✕", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.clickable { onClear() }.padding(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(text, fontSize = T.small, color = T.ink,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
        }
    }

    // Rendered, designed preview of an HTML doc (résumé / cover letter).
    @Composable
    fun htmlPreview(title: String, html: String, file: File?, onClear: () -> Unit) {
        if (html.isBlank()) return
        Spacer(Modifier.height(10.dp))
        Text("$title — live preview (this is exactly how it'll look). Tap Open PDF to view or share the file.",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (file != null) "PDF ready" else "rendering PDF…", fontSize = T.caption, color = if (file != null) T.accent else T.inkFaint, modifier = Modifier.weight(1f))
            if (file != null) Text("Open PDF", fontSize = T.caption, color = T.accent,
                modifier = Modifier.clickable {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.agentos.shell.fileprovider", file)
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {}
                }.padding(horizontal = 8.dp, vertical = 4.dp))
            Text("✕", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.clickable { onClear() }.padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        AndroidView(
            factory = { c -> WebView(c).apply { settings.javaScriptEnabled = false; setBackgroundColor(android.graphics.Color.WHITE) } },
            update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
            modifier = Modifier.fillMaxWidth().height(480.dp).clip(RoundedCornerShape(12.dp)))
    }

    @Composable
    fun field(value: String, placeholder: String, minH: Int, onChange: (String) -> Unit) {
        BasicTextField(value = value, onValueChange = onChange,
            textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().heightIn(min = minH.dp).clip(RoundedCornerShape(10.dp))
                .background(T.bgElevated).padding(12.dp),
            decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, fontSize = T.small, color = T.inkFaint); inner() })
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Find a job") { JobStore.setResume(ctx, resume); JobStore.setTarget(ctx, target); JobStore.setPosting(ctx, posting); onBack() }
        Spacer(Modifier.height(8.dp))
        Text("Give me your résumé, tell me the target, paste a posting — I'll tailor a résumé, write a cover letter, and draft the outreach email. Save each as a PDF to attach.",
            fontSize = T.small, color = T.inkFaint)
        if (busy == "auto") {
            Spacer(Modifier.height(10.dp))
            Text("Drafting everything for you — résumé, cover letter and email. One moment…",
                fontSize = T.small, color = T.accent)
        }

        Spacer(Modifier.height(16.dp))
        Text("0 · ADD YOUR LINKEDIN (once)", fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(4.dp))
        Text("On a computer: LinkedIn ▸ Settings ▸ Data Privacy ▸ Get a copy of your data ▸ pick “Connections, Positions, Education, Profile” ▸ download the ZIP, unzip it, and import the CSVs here. This teaches the brain your real work history — then everything else builds from it.",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        Row {
            pill("Import my LinkedIn CSVs", "li", accent = true) { liPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) }
        }
        if (liMsg.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(liMsg, fontSize = T.caption, color = T.accent) }

        Spacer(Modifier.height(16.dp))
        Text("1 · YOUR RÉSUMÉ", fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(4.dp))
        Text("Fastest: “Build from my brain” uses the LinkedIn history you imported above. Or attach a LinkedIn profile PDF (Profile ▸ More ▸ Save to PDF).",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        field(resume, "Paste your résumé, build it from your brain, or attach a LinkedIn PDF.", 120) { resume = it; JobStore.setResume(ctx, it) }
        Spacer(Modifier.height(8.dp))
        Row {
            pill("Build from my brain", "brain", accent = true) { run("brain", { AgentClient.jobResumeFromBrain(MemoryStore.fullProfile(ctx)) }) { resume = it; JobStore.setResume(ctx, it); feed("Built a résumé from my brain", 600) } }
            Spacer(Modifier.width(8.dp))
            pill("Attach LinkedIn PDF", "attach") { picker.launch(arrayOf("application/pdf", "text/plain", "*/*")) }
        }

        Spacer(Modifier.height(16.dp))
        Text("2 · TARGET", fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(6.dp))
        field(target, "e.g. Senior product designer, remote, $120k+", 46) { target = it; JobStore.setTarget(ctx, it) }
        Spacer(Modifier.height(8.dp))
        pill("Not sure? Suggest roles", "ideas") { run("ideas", { AgentClient.jobIdeas(resume, MemoryStore.fullProfile(ctx)) }) { ideas = it } }
        output("Role ideas", ideas) { ideas = "" }

        Spacer(Modifier.height(16.dp))
        Text("3 · FIND OPENINGS", fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(4.dp))
        Text("Opens live listings for your target — pick one, copy its description into step 4.", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        fun openJobs(base: String) {
            val q = target.ifBlank { "jobs" }
            try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(base + java.net.URLEncoder.encode(q, "UTF-8"))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
        }
        Row {
            pill("LinkedIn", "li_open", accent = true) { openJobs("https://www.linkedin.com/jobs/search/?keywords=") }
            Spacer(Modifier.width(8.dp))
            pill("Indeed", "in_open") { openJobs("https://www.indeed.com/jobs?q=") }
            Spacer(Modifier.width(8.dp))
            pill("Google", "g_open") { openJobs("https://www.google.com/search?q=") }
        }

        Spacer(Modifier.height(16.dp))
        Text("4 · THE POSTING", fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(6.dp))
        field(posting, "Paste the job description (or the text from the job link) here.", 100) { posting = it; JobStore.setPosting(ctx, it) }

        Spacer(Modifier.height(14.dp))
        Text("5 · GENERATE", fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(6.dp))
        Row {
            pill("Design résumé", "resume", accent = true) {
                run("resume", { AgentClient.jobResumeHtmlDoc(resume, resolvePosting(posting)) }) { html ->
                    if (html.contains("<")) { resumeHtml = html; resumeFile = null; JobDoc.htmlToPdf(ctx, html, "resume") { f -> resumeFile = f }; feed("Designed a tailored résumé for ${target.ifBlank { "a role" }}", 900) }
                }
            }
            Spacer(Modifier.width(8.dp))
            pill("Cover letter", "coverh", accent = true) {
                run("coverh", { AgentClient.jobCoverHtmlDoc(resume, resolvePosting(posting), MemoryStore.fullProfile(ctx)) }) { html ->
                    if (html.contains("<")) { coverHtml = html; coverFile = null; JobDoc.htmlToPdf(ctx, html, "cover") { f -> coverFile = f }; feed("Wrote a cover letter for ${target.ifBlank { "a role" }}", 600) }
                }
            }
            Spacer(Modifier.width(8.dp))
            pill("Outreach email", "email") { run("email", { AgentClient.jobEmail(resume, resolvePosting(posting), MemoryStore.fullProfile(ctx)) }) { email = it; feed("Drafted a job outreach email for ${target.ifBlank { "a role" }}", 300) } }
        }
        htmlPreview("Résumé", resumeHtml, resumeFile) { resumeHtml = ""; resumeFile = null }
        htmlPreview("Cover letter", coverHtml, coverFile) { coverHtml = ""; coverFile = null }
        output("Outreach email", email) { email = "" }

        if (resumeFile != null || coverFile != null) {
            Spacer(Modifier.height(12.dp))
            Text("Send the application email with your résumé and cover letter attached as PDFs.",
                fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(6.dp))
            pill("Email with attachments", "send", accent = true) {
                JobDoc.emailWithAttachments(ctx, "",
                    "Application" + (if (target.isNotBlank()) " — $target" else ""),
                    email.ifBlank { "Hi,\n\nPlease find my résumé and cover letter attached.\n\nBest," },
                    listOfNotNull(resumeFile, coverFile))
                feed("Sent a job application (résumé + cover letter attached)${if (target.isNotBlank()) " for $target" else ""}", 1500)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
