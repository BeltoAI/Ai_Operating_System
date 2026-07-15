package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * THE EXECUTOR — runs a Plan deterministically and reports honestly. Everything the attachment flow can do
 * lands here: send, read, fill, edit/generate images, convert, move, reply. One place, predictable behaviour.
 *
 * Produced images (edits/generations) come back as [producedPng] so the UI can preview them and offer a
 * one-tap "Save to gallery". Everything is also fed into the brain by the caller.
 */
object ActionExecutor {
    private const val TAG = "SlyOS-Exec"

    data class Result(
        val message: String,
        val producedPng: ByteArray? = null,
        val producedName: String = ""
    )

    /** Resolve the files a plan should act on. A specifically-NAMED file wins even if something's attached
     *  (so "send my resume" sends the resume, not the photo you happen to have open); otherwise use attached. */
    private fun resolveFiles(ctx: Context, plan: AttachmentPlanner.Plan, attached: List<Uri>): List<Uri> {
        val named = plan.fileRef.isNotBlank() && !plan.fileRef.equals("attached", true)
        if (named) {
            val found = FileResolver.find(ctx, plan.fileRef).map { it.uri }
            if (found.isNotEmpty()) return found
        }
        return attached
    }

    fun run(ctx: Context, plan: AttachmentPlanner.Plan, attached: List<Uri>, rawText: String): Result {
        val FO = FileOps
        return try {
            when (plan.action) {
                AttachmentPlanner.Action.GENERATE_IMAGE -> generate(plan, rawText)
                AttachmentPlanner.Action.EDIT_IMAGE -> editImage(ctx, plan, resolveFiles(ctx, plan, attached), rawText)
                AttachmentPlanner.Action.SEND -> send(ctx, plan, resolveFiles(ctx, plan, attached))
                AttachmentPlanner.Action.READ -> read(ctx, plan, resolveFiles(ctx, plan, attached))
                AttachmentPlanner.Action.FILL -> fill(ctx, resolveFiles(ctx, plan, attached))
                AttachmentPlanner.Action.CONVERT -> convert(ctx, resolveFiles(ctx, plan, attached))
                AttachmentPlanner.Action.MOVE -> move(ctx, plan, resolveFiles(ctx, plan, attached))
                AttachmentPlanner.Action.REPLY -> reply(ctx, plan, resolveFiles(ctx, plan, attached))
                AttachmentPlanner.Action.NONE -> Result("")
            }
        } catch (e: Exception) { Log.w(TAG, "run: ${e.message}"); Result("Something went wrong doing that.") }
    }

    // ── Images ────────────────────────────────────────────────────────────────────────────────────
    private fun generate(plan: AttachmentPlanner.Plan, rawText: String): Result {
        val prompt = plan.editPrompt.ifBlank { plan.question }.ifBlank { plan.message }.ifBlank { rawText }
        if (!ImageAI.available()) return Result(
            "I can make images once an image key is added (OpenAI or Gemini) — Claude itself can't generate pictures. " +
            "Add OPENAI_API_KEY or GEMINI_API_KEY and I'll draw \"$prompt\".")
        val png = ImageAI.generate(prompt)
        return if (png != null) Result("Here's what I made. Tap Save to keep it.", png, "generated_${System.currentTimeMillis()}.png")
        else Result("The image generator didn't return anything that time — try rephrasing the prompt.")
    }

    private fun editImage(ctx: Context, plan: AttachmentPlanner.Plan, files: List<Uri>, rawText: String): Result {
        val src = files.firstOrNull { FileOps.isImage(ctx, it) } ?: files.firstOrNull()
            ?: return Result("Attach a photo first and tell me the edit.")
        val bytes = read(ctx, src) ?: return Result("I couldn't read that photo.")
        // Native op first (no key, instant) — bg / grayscale / rotate / compress / crop.
        val op = plan.nativeOp.ifBlank { ImageEdits.nativeOpFor(plan.editPrompt.ifBlank { rawText }) ?: "" }
        if (op.isNotBlank()) {
            val out = ImageEdits.apply(op, bytes)
            if (out != null) return Result("Done — ${humanOp(op)}. Tap Save to keep it.", out, "edit_${System.currentTimeMillis()}.png")
        }
        // Otherwise a creative edit → the image model.
        val instruction = plan.editPrompt.ifBlank { plan.question }.ifBlank { rawText }
        if (!ImageAI.available()) return Result(
            "That edit (\"$instruction\") needs an image model — Claude can't edit pictures. I CAN do background, " +
            "crop, rotate, grayscale and compress right now with no setup. For the rest, add OPENAI_API_KEY or GEMINI_API_KEY.")
        val out = ImageAI.edit(bytes, instruction)
        return if (out != null) Result("Done — ${ImageAI.providerName()} edited it: \"$instruction\". Tap Save to keep it.", out, "edit_${System.currentTimeMillis()}.png")
        else Result("The editor didn't return an image that time — try describing the change differently.")
    }

    private fun humanOp(op: String) = when (op) {
        "bg" -> "removed the background"; "grayscale" -> "made it black & white"
        "rotate" -> "rotated it"; "compress" -> "shrank the file"; "crop" -> "cropped it square"; else -> "edited it"
    }

    // ── Send ──────────────────────────────────────────────────────────────────────────────────────
    private fun send(ctx: Context, plan: AttachmentPlanner.Plan, files: List<Uri>): Result {
        if (files.isEmpty()) return Result(
            if (plan.fileRef.isNotBlank() && !plan.fileRef.equals("attached", true))
                "I couldn't find \"${plan.fileRef}\" in your SlyOS folder or files. Attach it or file it first."
            else "Attach the file you want to send.")
        val channel = plan.channel
        val rec = plan.recipient
        val msg = plan.message
        val CT = ContactsTool
        val what = if (files.size > 1) "${files.size} files" else "the file"
        return when {
            rec.isBlank() -> {
                if (FileOps.send(ctx, files, channel))
                    Result("Opened ${channel.ifBlank { "your share sheet" }} with $what attached — pick who to send to.")
                else Result("I couldn't open that to send. Try again, or share it from the file itself.")
            }
            FileOps.isEmailChannel(channel) || (channel.isBlank() && rec.contains("@")) -> {
                val email = if (rec.contains("@")) rec else (CT.findEmail(ctx, rec) ?: "")
                Result(FileOps.sendToPerson(ctx, files, channel.ifBlank { "mail" }, rec, toEmail = email, message = msg, subject = "For you")
                    ?: "I couldn't open email.")
            }
            else -> {
                val c = CT.findContact(ctx, rec)
                Result(FileOps.sendToPerson(ctx, files, channel, rec, toNumber = c?.number ?: "", message = msg)
                    ?: run { FileOps.send(ctx, files, channel); "Opened ${channel.ifBlank { "your share sheet" }} with $what attached." })
            }
        }
    }

    // ── Read / fill / convert / move / reply ────────────────────────────────────────────────────────
    private fun read(ctx: Context, plan: AttachmentPlanner.Plan, files: List<Uri>): Result {
        val f = files.firstOrNull() ?: return Result("Attach a file or photo and I'll read it.")
        val question = plan.question.ifBlank { "Summarise this and give the key points." }
        // Ask for CLEAN, skimmable markdown so the summary reads nicely in the card (not a wall of text).
        val fmt = " Format the answer as clean markdown: a one-line **bold title**, then a few short bullet " +
            "points for the key facts (dates, names, amounts, actions). Keep it tight — no preamble, no filler."
        return if (FileOps.isImage(ctx, f)) {
            val b64 = ImageUtil.encode(ctx, f) ?: return Result("I couldn't read that image.")
            Result(AgentClient.askVision(question + fmt, listOf(b64), MemoryStore.fullProfile(ctx)))
        } else if (FileOps.isPdf(ctx, f)) {
            val body = FileOps.pdfText(ctx, f)
            if (body.isBlank()) Result("I opened it but it's a scan with no text layer — add the OCR power and I'll read it.")
            else {
                AttachContext.setText(ctx, body)
                Result(AgentClient.ask("$question$fmt\n\n--- Document ---\n${body.take(12000)}", emptyList(), MemoryStore.fullProfile(ctx)).say)
            }
        } else Result("I can read PDFs and images so far.")
    }

    private fun fill(ctx: Context, files: List<Uri>): Result {
        val pdfs = files.filter { FileOps.isPdf(ctx, it) }
        if (pdfs.isEmpty()) return Result("Attach the PDF form you want filled.")
        return Result(pdfs.joinToString("\n") { FileOps.fillPdfForm(ctx, it).second })
    }

    private fun convert(ctx: Context, files: List<Uri>): Result {
        val imgs = files.filter { FileOps.isImage(ctx, it) }
        if (imgs.isEmpty()) return Result("Attach the photos you want turned into a PDF.")
        val uri = PdfTool.imagesToPdf(ctx, imgs) ?: return Result("Couldn't make the PDF.")
        FileOps.stage(ctx, uri)
        return Result("Made a ${imgs.size}-page PDF and saved it. Say \"send it\" to share it.")
    }

    private fun move(ctx: Context, plan: AttachmentPlanner.Plan, files: List<Uri>): Result {
        if (files.isEmpty()) return Result("Attach the file you want filed.")
        // Blend into the SAME system as the camera & email: receipts→Expenses, docs→DocStore, all in the brain.
        return Result(files.joinToString("\n") { AutoFile.file(ctx, it) })
    }

    private fun reply(ctx: Context, plan: AttachmentPlanner.Plan, files: List<Uri>): Result {
        val doc = files.firstOrNull()?.let { if (FileOps.isPdf(ctx, it)) FileOps.pdfText(ctx, it) else "" } ?: ""
        val draft = AgentClient.draftEmailReply(plan.recipient.ifBlank { "them" }, plan.message.ifBlank { plan.question }, doc, MemoryStore.fullProfile(ctx))
        return Result(draft)
    }

    private fun read(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) { null }
}
