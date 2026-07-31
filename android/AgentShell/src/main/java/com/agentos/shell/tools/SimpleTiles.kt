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

    /**
     * A button, and what pressing it actually does.
     *
     * The first version made every button open the microphone and ask her to explain herself again,
     * which is the opposite of a shortcut. If she goes to the same surgery every month, the button
     * should say "Ride to the surgery" and open Uber with the address already in it. A shortcut
     * that re-asks the question is a longer way of doing the thing.
     *
     * So a tile carries a `kind`, and only the genuinely open-ended ones fall back to speaking:
     *
     *   `url`      open a link — an Uber deep link with a destination, a shop, a video call
     *   `app`      open an app by package
     *   `call`     dial a number, straight through
     *   `run`      hand a complete sentence to the assistant and let it act
     *   `speak`    half a sentence, then the microphone — only when the rest genuinely varies
     */
    data class Tile(
        val id: Long,
        val label: String,
        val kind: String = "run",
        /** URL, package name, phone number, or the sentence — whichever `kind` calls for. */
        val payload: String = "",
        /** Legacy: the sentence handed to the assistant. */
        val prompt: String = "",
        val finish: Boolean = false,
        val app: String = ""
    ) {
        /** Old rows had no `kind`; read them the way they were written. */
        val resolvedKind: String get() = when {
            kind.isNotBlank() && kind != "run" -> kind
            app.isNotBlank() -> "app"
            finish -> "speak"
            else -> "run"
        }
        val arg: String get() = payload.ifBlank { if (app.isNotBlank()) app else prompt }
    }

    /**
     * Deep links that actually exist.
     *
     * A lookup table of facts, not a guess about what anybody wants — and necessary because a model
     * asked for "the Uber deep link" will invent a plausible one. Uber's universal link opens the
     * app when installed and the web when not, which is the right behaviour either way.
     */
    fun rideLink(address: String): String =
        "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[formatted_address]=" +
            java.net.URLEncoder.encode(address.trim(), "UTF-8")

    val KNOWN_APPS = mapOf(
        "uber" to "com.ubercab", "lyft" to "me.lyft.android",
        "instacart" to "com.instacart.client", "whatsapp" to "com.whatsapp",
        "facebook" to "com.facebook.katana", "youtube" to "com.google.android.youtube",
        "photos" to "com.google.android.apps.photos", "maps" to "com.google.android.apps.maps",
        "amazon" to "com.amazon.mShop.android.shopping", "doordash" to "com.dd.doordash",
        "zoom" to "us.zoom.videomeetings", "spotify" to "com.spotify.music")

    private const val PREF = "slyos_simple_tiles"
    private const val KEY = "tiles"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Tile> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Tile(o.getLong("id"), o.getString("label"), o.optString("kind", "run"),
                o.optString("payload"), o.optString("prompt"),
                o.optBoolean("finish", false), o.optString("app"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, items: List<Tile>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("label", it.label).put("kind", it.kind)
                .put("payload", it.payload).put("prompt", it.prompt)
                .put("finish", it.finish).put("app", it.app))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, label: String, kind: String, payload: String) {
        val l = label.trim().take(28)
        if (l.isBlank()) return
        val cur = list(ctx).filterNot { it.label.equals(l, true) }
        save(ctx, cur + Tile(System.currentTimeMillis(), l, kind, payload.trim()))
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
        fun t(label: String, kind: String, payload: String) {
            out.add(Tile(System.currentTimeMillis() + out.size, label, kind, payload))
        }
        // Genuinely open-ended, so these do ask — who, where, what.
        t("Call someone", "speak", "Call ")
        t("Send a message", "speak", "Send a message to ")
        t("Get me a ride", "speak", "I need a ride ")
        t("Remind me", "speak", "Remind me to ")
        // Complete on their own, so these just run.
        t("What's on today?", "run", "What is on my calendar today? Answer in one or two short sentences.")
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

        val known = KNOWN_APPS.keys.joinToString(", ")
        val raw = try {
            AgentClient.complete(
                "You add one big button to a very simple phone for an older person. Output ONLY " +
                "compact JSON: {\"label\":\"…\",\"kind\":\"…\",\"payload\":\"…\"}. " +
                "label: at most 3 words, plain and literal, what the button says. " +
                "kind is one of:\n" +
                "  ride  — payload is JUST the destination address, when they named a place to go\n" +
                "  app   — payload is one of these app names: " + known + "\n" +
                "  call  — payload is a phone number, when they gave one\n" +
                "  run   — payload is a complete instruction the assistant can carry out with no " +
                "further questions (e.g. \"Remind me every day at 9am to take my pills\")\n" +
                "  speak — payload is the START of a sentence ending mid-air, ONLY when the missing " +
                "part changes every time (e.g. \"Call \")\n" +
                "Prefer a kind that DOES the thing. Only use speak when the detail genuinely varies " +
                "each time. No markdown.",
                request, 260)
        } catch (e: Exception) { "" }

        return try {
            val o = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            val label = o.optString("label").trim()
            var kind = o.optString("kind").trim().lowercase()
            var payload = o.optString("payload").trim()
            if (label.isBlank() || payload.isBlank()) return "I couldn't work out what that button should do."

            when (kind) {
                // Turned into a real link here rather than by the model, which would invent one.
                "ride" -> { payload = rideLink(payload); kind = "url" }
                "app"  -> {
                    payload = KNOWN_APPS[payload.lowercase()] ?: payload
                    if (!payload.contains(".")) return "I don't know that app."
                }
                "call", "run", "speak", "url" -> {}
                else -> kind = "run"
            }
            add(ctx, label, kind, payload)
            when (kind) {
                "url"   -> "Added \"$label\". It goes straight there."
                "app"   -> "Added \"$label\". It opens the app."
                "call"  -> "Added \"$label\". It dials straight away."
                "speak" -> "Added \"$label\". It will listen for the rest — where, when, who."
                else    -> "Added \"$label\"."
            }
        } catch (e: Exception) { "I couldn't work out what that button should do." }
    }

    /** For "what buttons do I have?" */
    fun describe(ctx: Context): String {
        val all = list(ctx)
        if (all.isEmpty()) return "There are no buttons yet."
        return "The buttons are: " + all.joinToString(", ") { it.label } + "."
    }
}
