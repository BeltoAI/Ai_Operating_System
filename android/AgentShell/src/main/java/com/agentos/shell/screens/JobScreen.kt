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
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.JobStore
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.PdfBuilder
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
    var target by remember { mutableStateOf(JobStore.target(ctx).ifBlank { initialTarget }) }
    var posting by remember { mutableStateOf(JobStore.posting(ctx)) }
    var tailored by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var ideas by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }   // which action is running

    // "Find a job at Apple" → open real listings for that target immediately, once.
    LaunchedEffect(Unit) {
        if (initialTarget.isNotBlank()) {
            try {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.linkedin.com/jobs/search/?keywords=" +
                        java.net.URLEncoder.encode(initialTarget, "UTF-8"))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {}
        }
    }

    fun run(tag: String, block: suspend () -> String, onResult: (String) -> Unit) {
        if (busy.isNotEmpty()) return
        busy = tag
        scope.launch { val r = withContext(Dispatchers.IO) { block() }; onResult(r); busy = "" }
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

        Spacer(Modifier.height(16.dp))
        Text("1 · YOUR RÉSUMÉ", fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(4.dp))
        Text("Fastest: “Build from my brain” uses your imported LinkedIn history. Or on LinkedIn tap Profile ▸ More ▸ Save to PDF and attach it here.",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        field(resume, "Paste your résumé, build it from your brain, or attach a LinkedIn PDF.", 120) { resume = it; JobStore.setResume(ctx, it) }
        Spacer(Modifier.height(8.dp))
        Row {
            pill("Build from my brain", "brain", accent = true) { run("brain", { AgentClient.jobResumeFromBrain(MemoryStore.fullProfile(ctx)) }) { resume = it; JobStore.setResume(ctx, it) } }
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
        Row {
            pill("Tailor résumé", "tailor", accent = true) { run("tailor", { AgentClient.jobTailorResume(resume, posting) }) { tailored = it } }
            Spacer(Modifier.width(8.dp))
            pill("Cover letter", "cover", accent = true) { run("cover", { AgentClient.jobCoverLetter(resume, posting, MemoryStore.fullProfile(ctx)) }) { cover = it } }
            Spacer(Modifier.width(8.dp))
            pill("Outreach email", "email", accent = true) { run("email", { AgentClient.jobEmail(resume, posting, MemoryStore.fullProfile(ctx)) }) { email = it } }
        }
        output("Tailored résumé", tailored) { tailored = "" }
        output("Cover letter", cover) { cover = "" }
        output("Outreach email", email) { email = "" }
        Spacer(Modifier.height(24.dp))
    }
}
