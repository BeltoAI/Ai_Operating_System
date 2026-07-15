package com.agentos.shell.tools

import android.content.Context
import org.json.JSONObject

/**
 * THE PLANNER — turns natural language into a precise, executable action.
 *
 * Regexes miss phrasings; the model doesn't. Given what the user said, what's attached, and what's in their
 * SlyOS folder, it returns ONE structured action. Everything downstream (the executor) is then deterministic
 * and reliable. This is the fix for "it said it sent but didn't".
 */
object AttachmentPlanner {

    enum class Action { SEND, READ, FILL, EDIT_IMAGE, GENERATE_IMAGE, CONVERT, MOVE, REPLY, NONE }

    data class Plan(
        val action: Action,
        val fileRef: String = "",     // "attached" or a description to look up ("my white paper")
        val recipient: String = "",   // contact name or email
        val channel: String = "",     // whatsapp, gmail, instagram…
        val message: String = "",     // caption / email body
        val editPrompt: String = "",  // "remove the background", "make the sky purple"
        val nativeOp: String = "",    // bg | crop | rotate | grayscale | compress  (on-device, no key)
        val targetFormat: String = "",// pdf, png…
        val folder: String = "",      // SlyOS drawer
        val question: String = ""     // what to answer about a doc/image
    )

    private const val SYS =
        "You convert a phone user's request about a file/photo into ONE JSON action. Output ONLY compact JSON, " +
        "no prose, no code fences. Schema:\n" +
        "{\"action\":\"send|read|fill|edit_image|generate_image|convert|move|reply|none\"," +
        "\"file\":\"attached | <short description to find> | \"," +
        "\"recipient\":\"<name or email>\",\"channel\":\"whatsapp|gmail|instagram|telegram|signal|messenger|\"," +
        "\"message\":\"<caption/body>\",\"edit\":\"<free-text image edit instruction>\"," +
        "\"native\":\"bg|crop|rotate|grayscale|compress|\",\"format\":\"pdf|png|\"," +
        "\"folder\":\"<SlyOS drawer>\",\"question\":\"<what to answer about it>\"}\n" +
        "Rules: pick the SINGLE best action. If the user names a file (\"my resume\", \"the white paper\") set file to that; " +
        "if they mean the thing already attached, set file to \"attached\". For image edits, put the plain instruction in " +
        "\"edit\"; ALSO set \"native\" if it's clearly just background-removal/crop/rotate/grayscale/compress. " +
        "\"generate_image\" only when they want a NEW image from scratch (nothing attached) — put the full " +
        "image description in \"edit\". " +
        "For send, extract recipient + channel + message if present. If it isn't about a file at all, action=none."

    fun plan(ctx: Context, userText: String, attachedNames: List<String>): Plan {
        val ctxLine = buildString {
            append("User said: \"").append(userText).append("\". ")
            if (attachedNames.isNotEmpty()) append("Currently attached: ").append(attachedNames.joinToString(", ")).append(". ")
            else append("Nothing is attached right now. ")
            val cab = SlyFolder.index(ctx).take(30).joinToString(", ") { it.name }
            if (cab.isNotBlank()) append("Files in their SlyOS folder: ").append(cab).append(".")
        }
        val raw = AgentClient.complete(SYS, ctxLine, 400)
        // If the planning call failed (network/model blip), don't silently drop the request — fall back to a
        // simple heuristic so an attachment action still happens.
        return if (raw.isBlank()) heuristic(userText, attachedNames.isNotEmpty()) else parse(raw)
    }

    /** Conservative offline fallback: only returns a concrete action when the wording clearly implies one. */
    private fun heuristic(text: String, hasAttachment: Boolean): Plan {
        val t = text.lowercase()
        if (!hasAttachment) return Plan(Action.NONE)
        return when {
            Regex("\\b(send|share|forward|email|whats\\s?app|text (it|this|them))\\b").containsMatchIn(t) -> {
                val channel = FileOps.detectChannel(text)
                val rec = Regex("(?i)\\bto\\s+([\\p{L}][\\p{L} .'-]{1,30})").find(text)?.groupValues?.get(1)
                    ?.replace(Regex("(?i)\\s+(on|via|over|using|by|saying|and)\\b.*$"), "")?.trim().orEmpty()
                val msg = Regex("(?i)(?:saying|say|tell(?:\\s+(?:her|him|them))?|message)\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
                Plan(Action.SEND, fileRef = "attached", recipient = rec, channel = channel, message = msg)
            }
            ImageEdits.nativeOpFor(t) != null -> Plan(Action.EDIT_IMAGE, fileRef = "attached", nativeOp = ImageEdits.nativeOpFor(t) ?: "")
            Regex("\\b(fill|complete)\\b").containsMatchIn(t) -> Plan(Action.FILL, fileRef = "attached")
            Regex("\\b(file it|move (it|this)|save (it|this) (to|in|into))\\b").containsMatchIn(t) -> Plan(Action.MOVE, fileRef = "attached")
            Regex("\\b(read|summari|what does|what'?s in|who|explain|extract)\\b").containsMatchIn(t) -> Plan(Action.READ, fileRef = "attached", question = text)
            else -> Plan(Action.READ, fileRef = "attached", question = text)   // a file's attached → default to reading it
        }
    }

    private fun parse(raw: String): Plan {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return Plan(Action.NONE)
        val json = raw.substring(start, end + 1)
        return try {
            val o = JSONObject(json)
            val a = when (o.optString("action").lowercase()) {
                "send" -> Action.SEND; "read", "summarize", "answer" -> Action.READ
                "fill" -> Action.FILL; "edit_image", "edit" -> Action.EDIT_IMAGE
                "generate_image", "generate" -> Action.GENERATE_IMAGE; "convert" -> Action.CONVERT
                "move", "file" -> Action.MOVE; "reply" -> Action.REPLY; else -> Action.NONE
            }
            Plan(
                action = a,
                fileRef = o.optString("file"),
                recipient = o.optString("recipient"),
                channel = o.optString("channel"),
                message = o.optString("message"),
                editPrompt = o.optString("edit"),
                nativeOp = o.optString("native"),
                targetFormat = o.optString("format"),
                folder = o.optString("folder"),
                question = o.optString("question")
            )
        } catch (e: Exception) { Plan(Action.NONE) }
    }
}
