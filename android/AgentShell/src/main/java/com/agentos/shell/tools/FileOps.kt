package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream

/**
 * Deep operations on an attached file — read it, fill it, convert it, move it, send it. Images are handled by
 * the vision model (it already reads text, detects people/objects, describes). This covers the file-side work:
 * PDF text + form-filling from the brain's profile, sending with an attachment, and moving into folders.
 */
object FileOps {
    private const val TAG = "SlyOS-Files"
    private var inited = false
    private fun ensure(ctx: Context) { if (!inited) { try { PDFBoxResourceLoader.init(ctx.applicationContext); inited = true } catch (e: Exception) {} } }

    fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else uri.lastPathSegment ?: "file"
        } ?: (uri.lastPathSegment ?: "file")
    } catch (e: Exception) { uri.lastPathSegment ?: "file" }

    fun mimeOf(ctx: Context, uri: Uri): String = try { ctx.contentResolver.getType(uri) ?: "application/octet-stream" } catch (e: Exception) { "application/octet-stream" }
    fun isPdf(ctx: Context, uri: Uri): Boolean = mimeOf(ctx, uri).contains("pdf") || displayName(ctx, uri).endsWith(".pdf", true)
    fun isImage(ctx: Context, uri: Uri): Boolean = mimeOf(ctx, uri).startsWith("image/")

    /** Extract all text from a PDF (so the AI can read/summarise/answer questions about it). */
    fun pdfText(ctx: Context, uri: Uri): String = try {
        ensure(ctx)
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            PDDocument.load(ins).use { doc -> PDFTextStripper().getText(doc).trim() }
        } ?: ""
    } catch (e: Exception) { Log.w(TAG, "pdfText: ${e.message}"); "" }

    /** Fill a fillable PDF form from the owner's saved profile. Returns the saved file Uri, or null. */
    fun fillPdfForm(ctx: Context, uri: Uri): Pair<Uri?, String> {
        ensure(ctx)
        return try {
            val profile = mapOf(
                "name" to MemoryStore.profileName(ctx), "full name" to MemoryStore.profileName(ctx),
                "email" to MemoryStore.profileEmail(ctx), "e-mail" to MemoryStore.profileEmail(ctx),
                "phone" to MemoryStore.profilePhone(ctx), "mobile" to MemoryStore.profilePhone(ctx), "tel" to MemoryStore.profilePhone(ctx),
                "address" to MemoryStore.profileAddress(ctx), "street" to MemoryStore.profileAddress(ctx)
            ).filterValues { it.isNotBlank() }
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val doc = PDDocument.load(ins)
                val form = doc.documentCatalog?.acroForm
                if (form == null) { doc.close(); return null to "This PDF has no fillable fields." }
                var filled = 0
                for (field in form.fields) {
                    val key = (field.fullyQualifiedName ?: "").lowercase()
                    val value = profile.entries.firstOrNull { key.contains(it.key) }?.value
                    if (!value.isNullOrBlank()) try { field.setValue(value); filled++ } catch (e: Exception) {}
                }
                val bos = ByteArrayOutputStream(); doc.save(bos); doc.close()
                val out = saveToDownloads(ctx, "filled_${displayName(ctx, uri)}", "application/pdf", bos.toByteArray())
                if (filled == 0) out to "I couldn't match any fields to what I know about you — open it and check."
                else out to "Filled $filled field${if (filled == 1) "" else "s"} from your profile — saved to Downloads/SlyOS."
            } ?: (null to "Couldn't open that file.")
        } catch (e: Exception) { Log.w(TAG, "fillForm: ${e.message}"); null to "Couldn't fill that form." }
    }

    /**
     * Copy any content Uri into our FileProvider cache and hand back a Uri EVERY app can read. This is the
     * fix for "WhatsApp/Gmail won't accept the file": a raw MediaStore/document Uri often isn't grantable to
     * another app, but a FileProvider Uri always is.
     */
    fun stage(ctx: Context, uri: Uri): Uri? {
        return try {
            val safe = displayName(ctx, uri).replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file_${System.currentTimeMillis()}" }
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val f = java.io.File(ctx.cacheDir, safe); f.writeBytes(bytes)
            androidx.core.content.FileProvider.getUriForFile(ctx, "com.agentos.shell.fileprovider", f)
        } catch (e: Exception) { Log.w(TAG, "stage: ${e.message}"); null }
    }

    /** Open a file in whatever app can view it (preview). Staged so external viewers can read it. */
    fun preview(ctx: Context, uri: Uri): Boolean = try {
        val shareable = stage(ctx, uri) ?: uri
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(shareable, mimeOf(ctx, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) {
            ctx.startActivity(Intent.createChooser(i, "Open").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        true
    } catch (e: Exception) { Log.w(TAG, "preview: ${e.message}"); false }

    /**
     * Send file(s) straight into ONE person's chat, with the message already typed — you just hit send.
     *
     * WhatsApp: the "jid" extra routes an ACTION_SEND with an attachment directly to a contact's chat, and
     * EXTRA_TEXT rides along as the caption. That is the one reliable way to pre-fill BOTH the person and the
     * file (wa.me links can pre-fill a message but cannot carry a file). Needs the contact's phone number.
     *
     * @param toNumber E.164-ish digits (no +, spaces) for WhatsApp/Telegram; ignored for email.
     * @param toEmail  recipient for email.
     * Returns a human line describing what opened, or null if it couldn't.
     */
    /** Name a channel (however the user says it) → that app's package. null = let the user pick an app. */
    fun packageForChannel(hint: String): String? {
        val h = hint.lowercase()
        return when {
            h.contains("whatsapp") -> "com.whatsapp"
            h.contains("telegram") -> "org.telegram.messenger"
            h.contains("signal") -> "org.thoughtcrime.securesms"
            h.contains("instagram") || h.contains("insta") || h.contains(" dm") -> "com.instagram.android"
            h.contains("messenger") || h.contains("facebook") -> "com.facebook.orca"
            h.contains("snap") -> "com.snapchat.android"
            h.contains("discord") -> "com.discord"
            h.contains("slack") -> "com.Slack"
            h.contains("outlook") -> "com.microsoft.office.outlook"
            h.contains("gmail") || h.contains("mail") || h.contains("email") -> "com.google.android.gm"
            h.contains("drive") -> "com.google.android.apps.docs"
            h.contains("twitter") || h == "x" || h.contains(" x ") -> "com.twitter.android"
            else -> null
        }
    }

    fun sendToPerson(
        ctx: Context, uris: List<Uri>, appHint: String, personName: String,
        toNumber: String = "", toEmail: String = "", message: String = "", subject: String = ""
    ): String? {
        return try {
            val staged = ArrayList(uris.mapNotNull { stage(ctx, it) })
            if (staged.isEmpty()) null else {
                val hint = appHint.lowercase()
                val multi = staged.size > 1
                val pkg = packageForChannel(hint)
                val i = Intent(if (multi) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
                    .setType(if (multi) "*/*" else mimeOf(ctx, uris.first()))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (multi) i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, staged)
                else i.putExtra(Intent.EXTRA_STREAM, staged.first())
                if (message.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, message)

                var targeted = false   // did we land straight in the person's chat / pre-fill the recipient?
                when {
                    pkg == "com.whatsapp" && toNumber.isNotBlank() -> {
                        i.putExtra("jid", digits(toNumber) + "@s.whatsapp.net").setPackage(pkg); targeted = true
                    }
                    pkg == "com.google.android.gm" || pkg == "com.microsoft.office.outlook" -> {
                        if (toEmail.isNotBlank()) { i.putExtra(Intent.EXTRA_EMAIL, arrayOf(toEmail)); targeted = true }
                        i.putExtra(Intent.EXTRA_SUBJECT, subject.ifBlank { "For you" }); i.setPackage(pkg)
                    }
                    pkg != null -> i.setPackage(pkg)   // opens that app's share; recipient picked inside it
                    // else: no package → system share sheet
                }
                val what = if (multi) "the ${staged.size} files are" else "the file's"
                val app = channelName(hint)
                try {
                    if (pkg == null) {
                        ctx.startActivity(Intent.createChooser(i, "Send").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Opened your share sheet — $what attached. Pick where to send."
                    } else {
                        ctx.startActivity(i)
                        if (targeted) "Opened $app with $personName — $what attached${if (message.isNotBlank()) " with your message" else ""}. Just hit send."
                        else "Opened $app with $what attached${if (message.isNotBlank()) " and your message" else ""} — pick $personName and send. ($app doesn't let apps pick the person for you.)"
                    }
                } catch (e: Exception) {
                    ctx.startActivity(Intent.createChooser(i.setPackage(null), "Send").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "$app isn't installed — opened your share sheet with $what attached instead."
                }
            }
        } catch (e: Exception) { Log.w(TAG, "sendToPerson: ${e.message}"); null }
    }

    /** Pull whatever channel the user named out of a sentence ("send it to Ana over Instagram"). */
    fun detectChannel(text: String): String {
        val t = text.lowercase()
        val known = listOf("whatsapp", "telegram", "signal", "instagram", "insta", "messenger",
            "facebook", "snapchat", "snap", "discord", "slack", "outlook", "gmail", "e-mail", "email", "mail", "twitter")
        return known.firstOrNull { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) } ?: ""
    }
    fun isEmailChannel(hint: String): Boolean {
        val p = packageForChannel(hint)
        return p == "com.google.android.gm" || p == "com.microsoft.office.outlook"
    }

    private fun digits(s: String) = s.filter { it.isDigit() }
    private fun channelName(hint: String): String {
        val h = hint.lowercase()
        return when {
            h.contains("whatsapp") -> "WhatsApp"; h.contains("telegram") -> "Telegram"
            h.contains("signal") -> "Signal"; h.contains("insta") -> "Instagram"
            h.contains("messenger") || h.contains("facebook") -> "Messenger"; h.contains("snap") -> "Snapchat"
            h.contains("discord") -> "Discord"; h.contains("slack") -> "Slack"
            h.contains("outlook") -> "Outlook"; h.contains("mail") -> "email"
            h.contains("twitter") -> "X"; else -> "your app"
        }
    }

    /** Send one or more files to a channel. Email pre-fills the recipient; others open with the file(s) attached. */
    fun send(ctx: Context, uris: List<Uri>, appHint: String, to: String = "", subject: String = ""): Boolean = try {
        val staged = ArrayList(uris.mapNotNull { stage(ctx, it) })
        if (staged.isEmpty()) false else {
            val hint = appHint.lowercase()
            val multi = staged.size > 1
            val i = Intent(if (multi) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
                .setType(if (multi) "*/*" else mimeOf(ctx, uris.first()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (multi) i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, staged)
            else i.putExtra(Intent.EXTRA_STREAM, staged.first())
            val pkg = packageForChannel(hint)
            if (pkg == "com.google.android.gm" || pkg == "com.microsoft.office.outlook") {
                if (to.isNotBlank()) i.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                if (subject.isNotBlank()) i.putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            if (pkg != null) i.setPackage(pkg)
            try {
                if (pkg == null) ctx.startActivity(Intent.createChooser(i, "Send").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else ctx.startActivity(i)
            } catch (e: Exception) {
                // Named app missing / refused → fall back to the system share sheet (still has the file attached).
                ctx.startActivity(Intent.createChooser(i.setPackage(null), "Send").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        }
    } catch (e: Exception) { Log.w(TAG, "send: ${e.message}"); false }

    /** Copy an attachment into Downloads/SlyOS/<folder> (a simple, safe "move into a folder"). */
    fun moveToFolder(ctx: Context, uri: Uri, folder: String): String {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Couldn't read that file."
            val out = saveToDownloads(ctx, displayName(ctx, uri), mimeOf(ctx, uri), bytes, folder.ifBlank { "" })
            if (out != null) "Saved to Downloads/SlyOS${if (folder.isBlank()) "" else "/$folder"}." else "Couldn't save it there."
        } catch (e: Exception) { "Couldn't move that file." }
    }

    fun saveToDownloads(ctx: Context, name: String, mime: String, bytes: ByteArray, sub: String = ""): Uri? = try {
        val rel = Environment.DIRECTORY_DOWNLOADS + "/SlyOS" + (if (sub.isBlank()) "" else "/$sub")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        uri
    } catch (e: Exception) { Log.w(TAG, "save: ${e.message}"); null }
}
