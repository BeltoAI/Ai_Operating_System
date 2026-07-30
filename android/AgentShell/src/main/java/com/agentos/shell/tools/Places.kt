package com.agentos.shell.tools

/**
 * Every screen SlyOS has, as something you can find by typing three letters.
 *
 * SlyOS replaced the launcher, and a launcher's one job is getting you to a thing quickly. But the
 * screens here have no icons on a grid — they are reached by asking, which is wonderful when you
 * know what to ask and useless when you half-remember that there is a portfolio page somewhere. A
 * phone's app drawer answers "por…" with Portfolio before you finish the word; this had no
 * equivalent, so features that took a week to build were unreachable by anyone who had not been
 * told they existed.
 *
 * Prefix-first, deliberately. Typing "hea" should offer Health above anything that merely mentions
 * health, because that is how every launcher on earth behaves and people already know the rule.
 */
object Places {

    /**
     * @param key matches the ScreenIntent action already handled in Home, so nothing new is wired
     *   downstream — a suggestion is exactly the request the owner could have typed.
     */
    data class Place(
        val key: String,
        val name: String,
        val hint: String,
        /** Extra words people reach for. Matched after the name, never before it. */
        val also: List<String> = emptyList()
    )

    val ALL = listOf(
        Place("google", "Google", "calendar, mail and Meet",
            listOf("calendar", "gmail", "mail", "email", "meet", "schedule", "diary", "invite", "meeting")),
        Place("health", "Health", "sleep, heart, recovery", listOf("vitals", "fitness", "whoop", "sleep", "hrv", "steps", "body")),
        // ONE entry, not two. "Meetings" and "Record a meeting" were separate Places that landed on
        // the same screen, so typing "meeting" offered what looked like two identical pages — the
        // distinction between them (one starts recording) is invisible in a list of shortcuts and
        // reads purely as a duplicate. Recording still starts from the spoken form and from the
        // button on the page itself, where the difference is obvious.
        Place("meetings", "Meetings", "record one, or read the last", listOf("recordings", "transcripts", "record", "transcribe", "notes")),
        // The documents SlyOS has MADE, which had no home at all — decks and one-pagers were only
        // reachable from inside an email draft, so anything generated from chat could be opened in
        // a PDF viewer and never changed again.
        Place("made_docs", "Documents", "preview and edit what you've made",
            listOf("document", "doc", "slides", "deck", "pdf", "presentation", "one-pager", "report")),
        // Replaces a contact list, which answers "what is their number" — a question that mattered
        // when you had to dial it, and not the two people actually have: who is this again, and do
        // I owe them something.
        // Bills, invoices, contracts and tickets that were emailed to you — the third kind of
        // document, which had nowhere to live until now.
        Place("papers", "Papers", "bills, invoices, contracts you were sent",
            listOf("bill", "bills", "invoice", "invoices", "receipt", "receipts", "contract",
                   "insurance", "statement", "tax", "paperwork", "attachments", "documents")),
        Place("graph", "Network map", "who knows whom, and how",
            listOf("graph", "map", "web", "who knows", "introductions")),
        Place("crm", "People", "everyone you know, and where you stand",
            listOf("contacts", "crm", "person", "who", "company", "companies", "relationship",
                   "owed", "network", "connections", "linkedin", "intros", "reconnect")),
        Place("invest", "Portfolio", "holdings and performance", listOf("stocks", "shares", "trading", "money", "investments")),
        Place("expenses", "Expenses", "what you've spent", listOf("spending", "receipts", "budget")),
        Place("find_job", "Job hunt", "roles, CV, outreach", listOf("jobs", "career", "cv", "resume")),
        // THE OLD NETWORK SHORTCUT IS GONE.
        //
        // It searched the same 20,005 LinkedIn connections the People page now holds, so typing
        // "network" offered two entries to one dataset — and I sent myself to the wrong one twice
        // while testing this, which is the clearest evidence it was a coin toss rather than a
        // choice. Its search words moved onto People above; the screen itself still exists for the
        // network_search ACTION, which does a different job (find and message strangers).
        Place("write_paper", "Research", "papers and sources", listOf("paper", "writing", "study")),
        Place("cowork", "Cowork", "build something with it", listOf("workspace", "code", "project")),
        Place("shop", "Shopping", "find and compare", listOf("buy", "price", "deals")),
        Place("look", "Look", "point the camera", listOf("camera", "identify", "scan")),
        Place("faces", "Faces", "who's in your photos", listOf("photos", "people", "recognise")),
        Place("set_mission", "Mission", "a standing goal", listOf("campaign", "outreach", "goal")),
        Place("translate_live", "Live translate", "two people, one phone", listOf("translation", "language", "interpreter", "speak")),
        Place("spicy_post", "Spicy post", "a sharper take", listOf("hot take", "post"))
    )

    /** A suggestion, with why it matched, so the list can be ordered honestly. */
    data class Hit(val place: Place, val score: Int)

    /**
     * What to offer for what has been typed so far.
     *
     * Scored rather than filtered, because the order is the whole feature: a list that puts Health
     * third for "hea" is worse than no list, since the eye has to read all of it.
     *
     * Nothing is offered until two characters — one letter matches a third of everything, and a
     * suggestion row that appears on the first keystroke is a flinch, not a help.
     */
    fun suggest(typed: String, max: Int = 4): List<Place> {
        val q = typed.trim().lowercase()
        if (q.length < 2) return emptyList()
        // Once it is a sentence it is a request, not a search for a screen. "health" is a lookup;
        // "how did I sleep this week" is a question and must not be answered with a shortcut.
        if (q.length > 18 || q.contains(' ') && q.split(' ').size > 3) return emptyList()

        val hits = ALL.mapNotNull { p ->
            val name = p.name.lowercase()
            val score = when {
                name == q -> 100
                name.startsWith(q) -> 90 - (name.length - q.length).coerceAtMost(20)
                // A word inside the name: "meeting" should find "Record a meeting".
                name.split(' ').any { it.startsWith(q) } -> 70
                p.also.any { it == q } -> 65
                p.also.any { it.startsWith(q) } -> 55
                name.contains(q) -> 40
                p.also.any { it.contains(q) } -> 25
                else -> 0
            }
            if (score > 0) Hit(p, score) else null
        }
        return hits.sortedByDescending { it.score }.take(max).map { it.place }
    }
}
