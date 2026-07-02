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

    // ── Export: dump the entire brain to a single .md in Downloads/SlyOS ──
    fun exportBrain(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("# SlyOS Brain Export\n_Exported ").append(Date()).append("_\n\n")
        sb.append("## About\n").append(MemoryStore.about(ctx)).append("\n\n")
        MemoryStore.positions(ctx).takeIf { it.isNotBlank() }?.let { sb.append("## Work history\n").append(it).append("\n\n") }
        MemoryStore.education(ctx).takeIf { it.isNotBlank() }?.let { sb.append("## Education\n").append(it).append("\n\n") }
        MemoryStore.learnedFacts(ctx).takeIf { it.isNotEmpty() }?.let { f -> sb.append("## Learned facts\n"); f.forEach { sb.append("- ").append(it).append("\n") }; sb.append("\n") }
        MemoryStore.styleProfile(ctx).takeIf { it.isNotBlank() }?.let { sb.append("## Voice profile\n").append(it).append("\n\n") }

        // Mission (goal + plan + latest read).
        MissionStore.mission(ctx).takeIf { it.isNotBlank() }?.let { g ->
            sb.append("## Mission\n").append("Goal: ").append(g).append("\n")
            MissionStore.milestones(ctx).forEach { sb.append("- [").append(if (it.done) "x" else " ").append("] ").append(it.text).append("\n") }
            MissionStore.latest(ctx)?.let { sb.append("Progress: ").append(it.percent).append("% — ").append(it.note).append("\n") }
            sb.append("\n")
        }

        // Checklist.
        ChecklistStore.load(ctx).takeIf { it.isNotEmpty() }?.let { items ->
            sb.append("## Checklist\n"); items.forEach { sb.append("- [").append(if (it.done) "x" else " ").append("] ").append(it.text).append("\n") }; sb.append("\n")
        }

        // Research papers (titles).
        PaperStore.list(ctx).takeIf { it.isNotEmpty() }?.let { ps ->
            sb.append("## Research papers (").append(ps.size).append(")\n"); ps.forEach { sb.append("- ").append(it.title).append(" (").append(it.docType).append(")\n") }; sb.append("\n")
        }

        // LinkedIn network — the big one that was missing.
        val conns = ConnectionStore.load(ctx)
        if (conns.isNotEmpty()) {
            sb.append("## Connections (").append(conns.size).append(")\n")
            conns.forEach { sb.append("- ").append(it.name).append(" — ").append(it.role).append(" @ ").append(it.company).append(if (it.url.isNotBlank()) " | ${it.url}" else "").append("\n") }
            sb.append("\n")
        }

        val rows = MessageStore.allRows(ctx, 200000)
        sb.append("## Messages (").append(rows.size).append(")\n")
        rows.forEach { sb.append("[").append(it.contact).append(" | ").append(it.role).append("] ").append(it.body.replace("\n", " ").take(6000)).append("\n") }

        val name = "slyos-brain-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".md"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SlyOS")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return "Couldn't create the export file."
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            "Exported your whole brain (${rows.size} messages) to Downloads/SlyOS/$name"
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
