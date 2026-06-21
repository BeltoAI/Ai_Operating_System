package com.agentos.shell.tools

import android.content.Context
import android.net.Uri

/**
 * Imports an exported chat log (e.g. WhatsApp "Export chat" .txt) so the brain gets the REAL
 * conversation history with a contact AND samples of how YOU write. Two payoffs: better-grounded
 * replies for that contact, and a learned voice profile.
 */
object ChatImport {
    data class Result(val contact: String, val messages: Int, val mySamples: List<String>)

    // Matches "[2/14/26, 1:21:50 PM] Name: text" and "2/14/26, 13:21 - Name: text".
    private val LINE = Regex("""^\[?\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4},?\s+\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AaPp][Mm])?\]?\s*[-–]?\s*([^:]{1,40}):\s?(.*)$""")

    fun importWhatsApp(ctx: Context, uri: Uri, owner: String): Result {
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return Result("", 0, emptyList())
        } catch (e: Exception) { return Result("", 0, emptyList()) }

        // Parse into (sender, message), joining multi-line messages.
        val msgs = ArrayList<Pair<String, String>>()
        for (raw in text.split(Regex("\r?\n"))) {
            val m = LINE.find(raw)
            if (m != null) {
                val sender = m.groupValues[1].trim()
                val body = m.groupValues[2].trim()
                if (body.isNotBlank() && !body.contains("end-to-end encrypted") && !body.startsWith("‎"))
                    msgs.add(sender to body)
            } else if (raw.isNotBlank() && msgs.isNotEmpty()) {
                val last = msgs.removeAt(msgs.size - 1); msgs.add(last.first to (last.second + " " + raw.trim()))
            }
        }
        if (msgs.isEmpty()) return Result("", 0, emptyList())

        // Owner vs contact. If we know the owner name, the contact is the other frequent sender.
        val freq = msgs.groupingBy { it.first }.eachCount()
        val ownerName = if (owner.isNotBlank()) freq.keys.firstOrNull { it.equals(owner, true) || it.startsWith(owner, true) } else null
        val contact = freq.entries.filter { it.key != ownerName }.maxByOrNull { it.value }?.key
            ?: freq.entries.maxByOrNull { it.value }!!.key

        // Populate per-contact history (so replies to this person have real context).
        msgs.takeLast(120).forEach { (sender, body) ->
            val role = if (ownerName != null && sender.equals(ownerName, true)) "me" else "them"
            ConversationStore.add(ctx, "WhatsApp", contact, role, body)
        }
        // Your own messages → style samples (only if we could identify the owner).
        val mine = if (ownerName != null) msgs.filter { it.first.equals(ownerName, true) }.map { it.second } else emptyList()
        return Result(contact, msgs.size, mine)
    }
}
