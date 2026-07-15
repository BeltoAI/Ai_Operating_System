package com.agentos.shell.tools

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.smartreply.SmartReply as MLSmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage

/**
 * On-device Smart Reply (ML Kit) — instant, free, offline one-tap reply suggestions for a conversation.
 * Great as a zero-latency first option before (or instead of) a full agent-drafted reply. Blocking — run
 * off the main thread. History is oldest→newest; the last message should be the incoming one from THEM.
 */
object SmartReply {
    /** [history] = list of (isFromYou, text). Returns up to 3 short suggested replies, or empty. */
    fun suggest(history: List<Pair<Boolean, String>>): List<String> {
        val recent = history.filter { it.second.isNotBlank() }.takeLast(10)
        if (recent.isEmpty() || recent.last().first) return emptyList()   // needs a remote message last
        val now = System.currentTimeMillis()
        val msgs = recent.mapIndexed { i, (mine, text) ->
            val ts = now - (recent.size - i) * 1000L
            if (mine) TextMessage.createForLocalUser(text, ts) else TextMessage.createForRemoteUser(text, ts, "them")
        }
        return try {
            val res = Tasks.await(MLSmartReply.getClient().suggestReplies(msgs))
            if (res.status == SmartReplySuggestionResult.STATUS_SUCCESS) res.suggestions.map { it.text } else emptyList()
        } catch (e: Exception) { emptyList() }
    }
}
