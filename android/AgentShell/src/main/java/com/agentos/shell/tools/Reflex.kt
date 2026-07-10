package com.agentos.shell.tools

import com.agentos.shell.InteractionLogService.ScreenNode

/**
 * REFLEX — deterministic, intent-based UI grounding.
 *
 * The planner (LLM) issues a high-level INTENT ("like", "comment", "follow", "search"…). Reflex resolves it
 * to the actual on-screen element by scoring EVERY signal at once — the visible text, the content-description,
 * and the internal resource-id name (which is how unlabelled icon buttons like Comment/Share get found). This
 * replaces the fragile "LLM picks an index from a flat list" approach: the model names the action, Reflex
 * reliably locates it. Works across apps because it matches on MEANING, not per-app hardcoding.
 */
object Reflex {

    /** keys = words that indicate this intent; neg = words that mean it's the OPPOSITE/already-done. */
    private data class Pattern(val keys: List<String>, val neg: List<String> = emptyList())

    private val INTENTS: Map<String, Pattern> = mapOf(
        "like" to Pattern(listOf("like", "react", "heart", "recommend", "upvote", "clap"),
            listOf("unlike", "dislike", "liked", "remove reaction", "unrecommend", "liked by", "likes count", "downvote")),
        "comment" to Pattern(listOf("comment", "reply", "leave a comment", "add a comment", "respond"),
            listOf("comments count", "view comments", "comments,")),
        "share" to Pattern(listOf("share", "repost", "retweet", "send post", "forward"), listOf("shared")),
        "send" to Pattern(listOf("send", "post", "submit", "reply", "publish", "share now"), listOf("sending")),
        "follow" to Pattern(listOf("follow", "connect", "add friend", "subscribe"),
            listOf("following", "unfollow", "followers", "subscribed", "pending", "requested")),
        "message" to Pattern(listOf("message", "send message", "chat", "dm", "write message")),
        "save" to Pattern(listOf("save", "bookmark"), listOf("saved", "unsave")),
        "search" to Pattern(listOf("search", "find")),
        "back" to Pattern(listOf("back", "navigate up", "close")),
        "menu" to Pattern(listOf("more options", "more", "options", "menu", "overflow")),
        "next" to Pattern(listOf("next", "continue", "done", "confirm"), listOf("pay", "buy")),
        "compose" to Pattern(listOf("compose", "new post", "create", "write", "add")),
        "profile" to Pattern(listOf("profile", "account", "me"))
    )

    /** True when a node's label indicates the intent is ALREADY satisfied (e.g. it reads "Liked"). */
    fun alreadyDone(intent: String, nodeText: String): Boolean {
        val p = INTENTS[intent.lowercase()] ?: return false
        val t = nodeText.lowercase().trim()
        return p.neg.any { t == it || t.startsWith("$it ") || (t.length < 24 && t.contains(it)) }
    }

    /** Best clickable/editable node index for [intent], or null. Scores text + desc + resource-id together. */
    fun findIndex(nodes: List<ScreenNode>, intent: String): Int? {
        val key = intent.lowercase().trim()
        val p = INTENTS[key] ?: Pattern(listOf(key))
        var best = -1; var bestScore = 0.0
        for (n in nodes) {
            if (!n.clickable && !n.editable && n.role != "switch") continue
            val t = n.text.lowercase().trim()
            if (t.isEmpty()) continue
            if (p.neg.any { t == it || t.contains(it) }) continue     // it's the opposite / already-done
            var s = 0.0
            for (k in p.keys) {
                when {
                    t == k -> s += 4.0                                // exact label ("Like")
                    t.startsWith(k) || t.endsWith(k) -> s += 2.0
                    Regex("\\b${Regex.escape(k)}\\b").containsMatchIn(t) -> s += 1.5
                    t.contains(k) -> s += 0.8
                }
            }
            if (s <= 0.0) continue
            if (t.length <= 18) s += 0.6                              // short labels are usually the real button
            if (n.role == "button") s += 0.3
            if (s > bestScore) { bestScore = s; best = n.index }
        }
        return if (best >= 0) best else null
    }

    /** The first editable field (for typing a comment / message / query), preferring one matching [hintIntent]. */
    fun fieldIndex(nodes: List<ScreenNode>, hintIntent: String = ""): Int? {
        val editables = nodes.filter { it.editable }
        if (editables.isEmpty()) return null
        if (hintIntent.isNotBlank()) {
            val p = INTENTS[hintIntent.lowercase()]
            if (p != null) editables.firstOrNull { e -> p.keys.any { e.text.lowercase().contains(it) } }?.let { return it.index }
        }
        return editables.first().index
    }
}
