package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The big buttons — owned by whoever uses the phone, not by me.
 *
 * The first version of this was a list in Kotlin, which is the wrong shape twice over. It assumed
 * I know what an eighty-year-old wants on their phone, and it meant the one person who could
 * change it was whoever shipped the next build.
 *
 * So the tiles are data, they are seeded from what this particular phone actually has, and they are
 * edited by talking: *"add a button for ordering from Instacart"*, *"remove the ride one"*. A family
 * member sets it up in a minute without being shown a settings screen, and — deliberately — so can
 * the person using it. Somebody asking their own phone for a button to call their doctor is the
 * feature working, not a risk to guard against.
 *
 * ─── The distinction that matters ───
 *
 * A tile is not a fixed sentence. "Call Carlos" is complete and should just run. "Get me a ride" is
 * not — it needs a destination and a time that only she knows, and a button that fires off a
 * half-request is worse than no button at all.
 *
 * So `finish = true` means: put the beginning of the sentence in the box, open the microphone, and
 * let her say the rest. *"I need a ride…"* → *"…to Dr Patel's at five."* She completes her own
 * request in her own words, which is the whole point. Independence is not a phone doing things for
 * you; it is a phone that lets you finish your own sentence.
 */
object SimpleTiles {

    data class Tile(
        val id: Long,
        val label: String,
        /** What gets handed to the assistant. The whole request, or the start of one. */
        val prompt: String,
        /** true = she says the rest out loud before it runs. */
        val finish: Boolean = false,
        /** Optional: open this app instead of asking the assistant. */
        val app: String = ""
    )

    private const val PREF = "slyos_simple_tiles"
    private const val KEY = "tiles"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Tile> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Tile(o.getLong("id"), o.getString("label"), o.getString("prompt"),
                o.optBoolean("finish", false), o.optString("app"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, items: List<Tile>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("label", it.label)
                .put("prompt", it.prompt).put("finish", it.finish).put("app", it.app))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, label: String, prompt: String, finish: Boolean = false, app: String = "") {
        val l = label.trim().take(28)
        if (l.isBlank()) return
        val cur = list(ctx).filterNot { it.label.equals(l, true) }
        save(ctx, cur + Tile(System.currentTimeMillis(), l, prompt.trim(), finish, app.trim()))
    }

    fun remove(ctx: Context, id: Long) = save(ctx, list(ctx).filterNot { it.id == id })

    /** Loose match, because somebody removing a button says "the ride one", not its exact label. */
    fun removeByName(ctx: Context, name: String): String {
        val n = name.lowercase().trim()
        if (n.isBlank()) return ""
        val hit = list(ctx).firstOrNull { it.label.lowercase().contains(n) }
            ?: list(ctx).firstOrNull { n.split(" ").any { w -> w.length > 3 && it.label.lowercase().contains(w) } }
            ?: return ""
        remove(ctx, hit.id)
        return hit.label
    }

    fun reorder(ctx: Context, items: List<Tile>) = save(ctx, items)

    // MARK: - What it starts with

    /**
     * The starting set, and deliberately nobody in particular.
     *
     * An earlier version seeded "Call Carlos" and "Call Joslyn" from whoever this phone talks to
     * most, which was a guess dressed as personalisation — I decided who mattered to somebody I
     * have never met and put their name on their grandmother's phone.
     *
     * So the seeds are person-agnostic: the kinds of thing anybody's phone should do — a ride, the
     * shopping, a call, a reminder — and every one is a half-sentence she finishes. Whose ride,
     * whose call, which shop: hers to say, or hers to add as a button of its own.
     */
    fun seedIfEmpty(ctx: Context) {
        if (list(ctx).isNotEmpty()) return
        val out = ArrayList<Tile>()
        fun t(label: String, prompt: String, finish: Boolean = false) {
            out.add(Tile(System.currentTimeMillis() + out.size, label, prompt, finish))
        }
        t("Call someone", "Call ", finish = true)
        t("Send a message", "Send a message to ", finish = true)
        t("Get me a ride", "I need a ride ", finish = true)
        t("Order shopping", "Order me ", finish = true)
        t("Remind me", "Remind me to ", finish = true)
        t("What's on today?", "What is on my calendar today? Answer in one or two short sentences.")
        save(ctx, out)
    }

    // MARK: - Editing it by talking

    val EDIT = Regex("(?i)\\b(add|make|create|remove|delete|get rid of)\\b.{0,30}\\b(button|shortcut|tile)\\b")

    /**
     * Turn a spoken request into a change, and say what happened.
     *
     * The model only ever produces a label and a sentence — it cannot reach anything else — so the
     * worst a confused answer can do is leave a badly-named button that one more sentence removes.
     */
    fun configure(ctx: Context, request: String): String {
        val lower = request.lowercase()
        if (Regex("(?i)\\b(remove|delete|get rid of)\\b").containsMatchIn(lower)) {
            val what = request.replace(Regex("(?i)\\b(remove|delete|get rid of|the|button|shortcut|tile|one|please)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val gone = removeByName(ctx, what)
            return if (gone.isNotBlank()) "Removed the \"$gone\" button."
                   else "I couldn't find a button like that. Say \"what buttons do I have?\" to see them."
        }

        val raw = try {
            AgentClient.complete(
                "You add one big button to a very simple phone for an older person. Output ONLY " +
                "compact JSON: {\"label\":\"…\",\"prompt\":\"…\",\"finish\":true|false}. " +
                "label: at most 3 words, what the button says, plain and literal. " +
                "prompt: the sentence handed to the assistant when it is pressed. " +
                "finish: true when the request cannot be complete without details only the user " +
                "knows — a destination, a time, a recipient — in which case `prompt` must END " +
                "mid-sentence so they can say the rest out loud (e.g. \"I need a ride \"). " +
                "false when the request is already complete (e.g. \"Read me my new messages\"). " +
                "No markdown.",
                request, 220)
        } catch (e: Exception) { "" }

        return try {
            val o = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            val label = o.optString("label").trim()
            val prompt = o.optString("prompt").trim()
            if (label.isBlank() || prompt.isBlank()) return "I couldn't work out what that button should do."
            add(ctx, label, prompt, o.optBoolean("finish", false))
            if (o.optBoolean("finish", false))
                "Added \"$label\". Pressing it will listen for the rest — where, when, who."
            else "Added \"$label\"."
        } catch (e: Exception) { "I couldn't work out what that button should do." }
    }

    /** For "what buttons do I have?" */
    fun describe(ctx: Context): String {
        val all = list(ctx)
        if (all.isEmpty()) return "There are no buttons yet."
        return "The buttons are: " + all.joinToString(", ") { it.label } + "."
    }
}
