package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Move the whole brain in and out: export everything to one big Markdown file, import it back, and
 * feed ANY uploaded file (PDF, txt, md, csv, json…) into memory. This makes the brain portable and
 * lets the user load documents of any format — not just the Telegram-only PDF.
 */
object BrainData {

    // ── Export: STREAM the whole brain to a single .md (never holds it all in memory → no OOM) ──
    fun exportBrain(ctx: Context): String {
        val name = "slyos-brain-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".md"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SlyOS")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return "Couldn't create the export file."
            var msgCount = 0
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { w ->
                w.write("# SlyOS Brain Export\n_Exported ${Date()}_\n\n")
                w.write("## About\n${MemoryStore.about(ctx)}\n\n")
                MemoryStore.positions(ctx).takeIf { it.isNotBlank() }?.let { w.write("## Work history\n$it\n\n") }
                MemoryStore.education(ctx).takeIf { it.isNotBlank() }?.let { w.write("## Education\n$it\n\n") }
                MemoryStore.learnedFacts(ctx).takeIf { it.isNotEmpty() }?.let { f -> w.write("## Learned facts\n"); f.forEach { w.write("- $it\n") }; w.write("\n") }
                MemoryStore.styleProfile(ctx).takeIf { it.isNotBlank() }?.let { w.write("## Voice profile\n$it\n\n") }
                MissionStore.mission(ctx).takeIf { it.isNotBlank() }?.let { g ->
                    w.write("## Mission\nGoal: $g\n")
                    MissionStore.milestones(ctx).forEach { w.write("- [${if (it.done) "x" else " "}] ${it.text}\n") }
                    MissionStore.latest(ctx)?.let { w.write("Progress: ${it.percent}% — ${it.note}\n") }
                    w.write("\n")
                }
                ChecklistStore.load(ctx).takeIf { it.isNotEmpty() }?.let { items ->
                    w.write("## Checklist\n"); items.forEach { w.write("- [${if (it.done) "x" else " "}] ${it.text}\n") }; w.write("\n")
                }
                PaperStore.list(ctx).takeIf { it.isNotEmpty() }?.let { ps ->
                    w.write("## Research papers (${ps.size})\n"); ps.forEach { w.write("- ${it.title} (${it.docType})\n") }; w.write("\n")
                }
                val conns = ConnectionStore.load(ctx)
                if (conns.isNotEmpty()) {
                    w.write("## Connections (${conns.size})\n")
                    conns.forEach { w.write("- ${it.name} — ${it.role} @ ${it.company}${if (it.url.isNotBlank()) " | ${it.url}" else ""}\n") }
                    w.write("\n")
                }
                w.write("## Messages\n")
                msgCount = MessageStore.forEachRow(ctx) { contact, role, body ->
                    w.write("[$contact | $role] ${body.replace("\n", " ").take(2000)}\n")
                }
                w.flush()
            }
            "Exported your whole brain ($msgCount messages) to Downloads/SlyOS/$name"
        } catch (e: Exception) { "Export failed: ${e.message}" }
    }

    // ── Import: read a previously-exported .md back into the brain ──
    private val MSG = Regex("^\\[(.+?) \\| (me|them)\\] (.*)$")
    fun importBrain(ctx: Context, uri: Uri): String {
        val text = readText(ctx, uri)
        if (text.isBlank()) return "Couldn't read that file."
        var section = ""
        val about = StringBuilder(); val positions = StringBuilder(); val education = StringBuilder(); val voice = StringBuilder()
        val learned = ArrayList<String>(); val rows = ArrayList<MessageStore.Row>()
        val milestones = ArrayList<String>(); val checklist = ArrayList<String>(); var goal = ""
        var ts = System.currentTimeMillis() - 1_000_000
        for (raw in text.split("\n")) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## About") -> { section = "about"; continue }
                line.startsWith("## Work history") -> { section = "pos"; continue }
                line.startsWith("## Education") -> { section = "edu"; continue }
                line.startsWith("## Learned") -> { section = "learn"; continue }
                line.startsWith("## Voice") -> { section = "voice"; continue }
                line.startsWith("## Mission") -> { section = "mission"; continue }
                line.startsWith("## Checklist") -> { section = "check"; continue }
                line.startsWith("## Messages") -> { section = "msg"; continue }
                line.startsWith("#") -> { section = ""; continue }   // Connections/Papers = backup only
            }
            val m = MSG.find(line)
            if (m != null) { rows.add(MessageStore.Row(m.groupValues[1], "Import", m.groupValues[1], m.groupValues[2], m.groupValues[3], ts++)); continue }
            fun task(l: String) = l.replace(Regex("^- \\[[ x]\\]\\s*"), "").trim()
            when (section) {
                "about" -> if (line.isNotBlank()) about.append(line).append("\n")
                "pos" -> if (line.isNotBlank()) positions.append(line).append("\n")
                "edu" -> if (line.isNotBlank()) education.append(line).append("\n")
                "voice" -> if (line.isNotBlank()) voice.append(line).append("\n")
                "learn" -> line.removePrefix("- ").trim().takeIf { it.isNotBlank() }?.let { learned.add(it) }
                "mission" -> when {
                    line.startsWith("Goal:") -> goal = line.removePrefix("Goal:").trim()
                    line.startsWith("- [") -> task(line).takeIf { it.isNotBlank() }?.let { milestones.add(it) }
                }
                "check" -> if (line.startsWith("- [")) task(line).takeIf { it.isNotBlank() }?.let { checklist.add(it) }
            }
        }
        if (about.isNotBlank() && MemoryStore.about(ctx).isBlank()) MemoryStore.setAbout(ctx, about.toString().trim())
        if (positions.isNotBlank()) MemoryStore.setPositions(ctx, positions.toString().trim())
        if (education.isNotBlank()) MemoryStore.setEducation(ctx, education.toString().trim())
        if (voice.isNotBlank() && MemoryStore.styleProfile(ctx).isBlank()) MemoryStore.setStyleProfile(ctx, voice.toString().trim())
        learned.forEach { MemoryStore.addLearnedFact(ctx, it) }
        if (goal.isNotBlank() && MissionStore.mission(ctx).isBlank()) { MissionStore.setMission(ctx, goal); if (milestones.isNotEmpty()) MissionStore.setPlan(ctx, milestones) }
        checklist.forEach { ChecklistStore.add(ctx, it) }
        val added = MessageStore.insertBatchDedupe(ctx, rows)
        return "Imported $added messages + about, work history, voice, mission & checklist (skipped duplicates)."
    }

    // ── Any file → brain: extract text and store it, chunked, so it's searchable everywhere ──
    fun ingestFile(ctx: Context, uri: Uri): String {
        val name = fileName(ctx, uri)
        val text = readText(ctx, uri)
        if (text.length < 20) return "Couldn't read text from that file."
        val chunks = text.split(Regex("\\n{2,}")).flatMap { p -> p.trim().chunked(1200) }.filter { it.trim().length > 20 }
        var ts = System.currentTimeMillis() - chunks.size
        val rows = chunks.map { MessageStore.Row(name, "Document", name, "them", it.trim(), ts++) }
        val added = MessageStore.insertBatchDedupe(ctx, rows)
        return "Added $added chunks from “$name” to your brain — searchable everywhere now."
    }

    private fun fileName(ctx: Context, uri: Uri): String {
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            } ?: "Document"
        } catch (e: Exception) { "Document" }
    }

    private fun readText(ctx: Context, uri: Uri): String {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
            if (bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte()) {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { com.tom_roush.pdfbox.text.PDFTextStripper().getText(it) }
            } else String(bytes, Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }
}
