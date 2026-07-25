package com.agentos.shell.tools

import android.content.Context
import android.util.Log

/**
 * PER-CHANNEL / PER-PERSON QUALITY AUDIT. Response quality is the product, so it needs to be measurable, not
 * a vibe. This drafts a reply to the SAME incoming message as if it arrived on each platform, from a real
 * person, and reports what the drafter actually saw and produced.
 *
 * Run it from a shell with:
 *   adb shell am broadcast -a com.agentos.shell.AUDIT_VOICE
 * and read the results with `adb logcat -s SlyOS-Audit`. It writes nothing and sends nothing.
 */
object VoiceAudit {
    private const val TAG = "SlyOS-Audit"

    private val CHANNELS = listOf("LinkedIn", "Email", "Instagram", "X", "WhatsApp", "Telegram", "Slack", "SMS")

    /**
     * PLANNER PROBE: run the real action planner over phone-operation prompts and log exactly what actions it
     * emits. "Operate my phone" failing silently (planner runs, zero actions) is invisible without this.
     */
    fun planner(ctx: Context, custom: String = "") {
        // Google integration state first: "I can't connect to Google Calendar" is the CORRECT answer when
        // nothing is connected, and a completely different bug when something is. Never guess which.
        try {
            Log.i(TAG, "google connected: ${GoogleAuth.isConnected(ctx)} · calendar permission: ${CalendarTool.hasPermission(ctx)}")
        } catch (t: Throwable) { Log.w(TAG, "google state: ${t.message}") }
        val prompts = if (custom.isNotBlank()) listOf(custom) else listOf(
            "open instagram",
            "open instagram and search for anduril",
            "turn on the flashlight",
            "text Anna that I'm running late",
            "post on linkedin about on-device AI",
            "connect with 10 people in my network on linkedin",
            "what's on my calendar tomorrow"
        )
        Log.i(TAG, "══════ PLANNER PROBE ══════")
        val apps = try { ToolRouter.installedApps(ctx).map { it.label } } catch (t: Throwable) { emptyList() }
        for (p in prompts) {
            try {
                val brain = try { BrainContext.build(ctx, p) } catch (t: Throwable) { "" }
                val t0 = System.currentTimeMillis()
                val r = AgentClient.ask(p, apps, brain, emptyList())
                val acts = r.actions.filter { it.type.isNotBlank() && it.type != "none" }
                Log.i(TAG, "\"$p\" (${System.currentTimeMillis() - t0}ms)")
                Log.i(TAG, "   say    : ${r.say.take(120)}")
                Log.i(TAG, "   ACTIONS: ${if (acts.isEmpty()) "*** NONE ***" else acts.joinToString(", ") { it.type + "(" + it.arg.take(60) + ")" }}")
            } catch (t: Throwable) { Log.w(TAG, "\"$p\" failed: ${t.message}") }
        }
        Log.i(TAG, "══════ END PLANNER PROBE ══════")
    }

    /**
     * OUTREACH PROBE: draft the intro message for the first N LinkedIn connections the owner has never
     * reached out to, and log each one with timing. DRAFTS ONLY — nothing is sent.
     */
    /** Preview what the REAL sender (tailoredOutreach + the owner's template) would say. Nothing is sent. */
    fun outreachPreview(ctx: Context, n: Int, template: String) {
        Log.i(TAG, "══════ OUTREACH PREVIEW — template-based, NOTHING SENT ══════")
        val targets = try { ConnectionStore.neverReachedOut(ctx).filter { it.url.isNotBlank() }.take(n) } catch (t: Throwable) { emptyList() }
        Log.i(TAG, "targets with profile links: ${targets.size}")
        val liStyle = try { MemoryStore.styleFor(ctx, "LinkedIn") } catch (t: Throwable) { "" }
        val profile = (if (liStyle.isNotBlank()) "Your LinkedIn voice/persona: $liStyle\n\n" else "") +
            (try { BrainDigest.getOrFull(ctx) } catch (t: Throwable) { "" })
        targets.forEachIndexed { i, c ->
            try {
                val t0 = System.currentTimeMillis()
                val msg = AgentClient.tailoredOutreach(
                    "invite them to test SlyOS as an early developer tester",
                    c.name, c.role, c.company, profile, "", template)
                Log.i(TAG, "${i + 1}. ${c.name}" + (if (c.role.isNotBlank()) " — ${c.role}" else "") +
                    (if (c.company.isNotBlank()) " @ ${c.company}" else "") + " (${System.currentTimeMillis() - t0}ms)")
                Log.i(TAG, "     ${msg.replace("\n", " ⏎ ")}")
            } catch (t: Throwable) { Log.w(TAG, "${c.name}: ${t.message}") }
        }
        Log.i(TAG, "══════ END PREVIEW ══════")
    }

    fun outreach(ctx: Context, n: Int = 10) {
        Log.i(TAG, "══════ OUTREACH PROBE (drafts only, nothing sent) ══════")
        val never = try { ConnectionStore.neverReachedOut(ctx) } catch (t: Throwable) { emptyList() }
        Log.i(TAG, "never-reached-out pool: ${never.size}")
        val started = System.currentTimeMillis()
        never.take(n).forEachIndexed { i, c ->
            try {
                val t0 = System.currentTimeMillis()
                val mem = ReplyContext.forSender(ctx, c.source, c.name)
                val msg = AgentClient.introMessage(c.name, c.company, c.role, c.source, mem)
                Log.i(TAG, "${i + 1}. ${c.name}" + (if (c.role.isNotBlank()) " — ${c.role}" else "") +
                    (if (c.company.isNotBlank()) " @ ${c.company}" else "") + "  (${System.currentTimeMillis() - t0}ms)")
                Log.i(TAG, "     ${msg.replace("\n", " ⏎ ").take(320)}")
            } catch (t: Throwable) { Log.w(TAG, "${c.name} failed: ${t.message}") }
        }
        Log.i(TAG, "TOTAL for ${minOf(n, never.size)} drafts: ${(System.currentTimeMillis() - started) / 1000}s")
        Log.i(TAG, "══════ END OUTREACH PROBE ══════")
    }

    /**
     * THE MATRIX AUDIT — the real test of "does it sound right everywhere". Same engine, but varied across the
     * three axes that actually determine whether a reply is appropriate: WHO is writing (wife vs co-founder vs
     * investor vs stranger), WHICH channel (register), and WHAT the topic is (personal vs technical vs money vs
     * emotional). A single-channel test can't catch a warm-to-your-wife tone leaking into an investor thread.
     */
    /** What's ACTUALLY in the brain, per platform — the honest coverage picture. */
    /** Import a chat archive from a path and report the honest per-file outcome. */
    fun importFile(ctx: Context, path: String) {
        Log.i(TAG, "══════ IMPORT: $path ══════")
        val before = try { MessageStore.count(ctx) } catch (t: Throwable) { 0 }
        val t0 = System.currentTimeMillis()
        try {
            val uri = android.net.Uri.fromFile(java.io.File(path))
            val owner = try { MemoryStore.ownerName(ctx).ifBlank { MemoryStore.profileName(ctx) } } catch (t: Throwable) { "" }
            val r = ChatImport.importAny(ctx, uri, owner)
            val after = try { MessageStore.count(ctx) } catch (t: Throwable) { 0 }
            Log.i(TAG, "RESULT: ${r.messages} added · ${r.contacts} contacts · ${(System.currentTimeMillis() - t0) / 1000}s")
            Log.i(TAG, "brain: $before -> $after (+${after - before})")
            r.files.sortedByDescending { it.parsed }.forEach {
                Log.i(TAG, "  ${if (it.ok) "OK  " else "FAIL"} ${it.name.take(46).padEnd(48)} parsed=${it.parsed} added=${it.added} ${it.error}")
            }
            Log.i(TAG, "files: ${r.files.count { it.ok }} ok, ${r.files.count { !it.ok }} failed")
        } catch (t: Throwable) { Log.e(TAG, "import blew up: ${t.message}", t) }
        Log.i(TAG, "══════ END IMPORT ══════")
    }

    /** Does search actually find the newly imported history? Keyword + FTS health + semantic. */
    fun searchProbe(ctx: Context, q: String) {
        Log.i(TAG, "══════ SEARCH PROBE: \"$q\" ══════")
        try { Log.i(TAG, "messages=${MessageStore.count(ctx)} ftsRows=${MessageStore.ftsCount(ctx)}") } catch (t: Throwable) { Log.w(TAG, "counts: ${t.message}") }
        try {
            val hits = MessageStore.search(ctx, q, 8)
            Log.i(TAG, "keyword/FTS hits: ${hits.size}")
            hits.take(5).forEach { Log.i(TAG, "   [${it.contact}] ${it.body.take(120)}") }
        } catch (t: Throwable) { Log.w(TAG, "search failed: ${t.message}") }
        try {
            val sem = VectorStore.search(ctx, q, 5)
            Log.i(TAG, "semantic hits: ${sem.size} (embedded=${VectorStore.embeddedCount(ctx)})")
        } catch (t: Throwable) { Log.w(TAG, "semantic: ${t.message}") }
        Log.i(TAG, "══════ END SEARCH PROBE ══════")
    }

    /**
     * Run the WHOLE self-test suite from adb and print it as a scoreboard. `runAll` was only reachable by
     * tapping through the Memory screen, so "is the app actually working end to end?" could not be answered
     * headlessly — which is exactly the question that matters before shipping a build. Failures are listed
     * first because that's the part you act on.
     */
    /**
     * Why screen recall has N rows. "It writes zero rows" was treated as a defect across multiple sessions
     * when the capture path simply returns early unless the user has switched recall ON (it defaults OFF) —
     * and nothing surfaced that. Each line below is a precondition; the first FALSE is the answer.
     */
    fun recallState(ctx: Context) {
        Log.i(TAG, "══════ SCREEN RECALL ══════")
        val on = try { MemoryStore.recallEnabled(ctx) } catch (t: Throwable) { false }
        Log.i(TAG, "recall_capture setting: " + (if (on) "ON" else "OFF  ← captures are skipped entirely while this is off"))
        val svc = try {
            android.provider.Settings.Secure.getString(ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        } catch (t: Throwable) { "" }
        val bound = svc.contains(ctx.packageName)
        Log.i(TAG, "accessibility service granted: " + (if (bound) "YES" else "NO   ← nothing can be captured without it"))
        val n = try { InteractionStore.count(ctx) } catch (t: Throwable) { -1 }
        Log.i(TAG, "interactions.log rows: $n")
        try {
            InteractionStore.appCounts(ctx).take(8).forEach { (a, c) -> Log.i(TAG, "   $c  $a") }
        } catch (t: Throwable) {}
        Log.i(TAG, "verdict: " + when {
            !bound -> "grant the accessibility service, then re-check"
            !on -> "NOT A BUG — recall is switched off; turn it on in Settings, use another app, re-check"
            n > 0 -> "working"
            else -> "REAL BUG: enabled + granted but nothing captured"
        })
        Log.i(TAG, "══════ END SCREEN RECALL ══════")
    }

    /**
     * Everything a real LinkedIn outreach run would do, WITHOUT sending anything: the accessibility gate,
     * the cap, how many targets actually survive selection, and a real draft for the first of them.
     * Sending to real people is not a thing to "test" — this proves the engine end to end up to the tap.
     */
    fun outreachDry(ctx: Context, want: Int = 50) {
        Log.i(TAG, "══════ OUTREACH DRY RUN (want=$want, NOTHING IS SENT) ══════")
        val tap = try { TapSend.available() } catch (t: Throwable) { false }
        Log.i(TAG, "accessibility/TapSend available: " + (if (tap) "YES" else "NO  ← run would abort immediately"))
        val cap = try { MissionStore.dailyCap(ctx) } catch (t: Throwable) { -1 }
        Log.i(TAG, "daily cap: $cap  → effective count: " + want.coerceIn(1, if (cap > 0) cap else want))
        val all = try { ConnectionStore.load(ctx).size } catch (t: Throwable) { -1 }
        val never = try { ConnectionStore.neverReachedOut(ctx) } catch (t: Throwable) { emptyList() }
        val withUrl = never.filter { it.url.isNotBlank() }
        Log.i(TAG, "connections: $all total · ${never.size} never reached out · ${withUrl.size} of those have a profile URL")
        val targets = withUrl.take(want.coerceIn(1, if (cap > 0) cap else want))
        Log.i(TAG, "would message ${targets.size} people" +
            (if (targets.size < want) "  ← FEWER THAN THE $want REQUESTED (this is the silent shortfall to watch)" else ""))
        targets.take(5).forEach { Log.i(TAG, "   → ${it.name} — ${it.role.ifBlank { "?" }} @ ${it.company.ifBlank { "?" }}") }
        val first = targets.firstOrNull()
        if (first == null) { Log.w(TAG, "no eligible targets — a real run would stop here"); Log.i(TAG, "══════ END OUTREACH DRY RUN ══════"); return }
        try {
            val liStyle = try { MemoryStore.styleFor(ctx, "LinkedIn") } catch (t: Throwable) { "" }
            val profile = (if (liStyle.isNotBlank()) "Your LinkedIn voice/persona: $liStyle\n\n" else "") + MemoryStore.fullProfile(ctx)
            val msg = AgentClient.tailoredOutreach("invite them to test SlyOS as an early developer tester",
                first.name, first.role, first.company, profile, "", "")
            Log.i(TAG, "sample draft for ${first.name} (${msg.length} chars):")
            msg.split("\n").forEach { Log.i(TAG, "   $it") }
            if (msg.length < 8 || msg.startsWith("[")) Log.w(TAG, "   ← a real run would SKIP this one as undraftable")
        } catch (t: Throwable) { Log.w(TAG, "draft failed: ${t.message}") }
        Log.i(TAG, "══════ END OUTREACH DRY RUN ══════")
    }

    /**
     * How much of the context every AI surface receives is the SETTINGS PROFILE versus actual remembered
     * life. "The AI only knows what's in my characteristics card" turned out to be literally true — message
     * recall was throttled to 2,600 characters while the profile went in unbounded — and nothing measured
     * the ratio, so it stayed invisible. Now it's one number.
     */
    fun contextMix(ctx: Context, q: String) {
        Log.i(TAG, "══════ CONTEXT MIX: \"$q\" ══════")
        val profile = try { BrainContext.profileBlock(ctx) } catch (t: Throwable) { "" }
        val full = try { BrainContext.build(ctx, q) } catch (t: Throwable) { "" }
        val rest = (full.length - profile.length).coerceAtLeast(0)
        val pct = if (full.isNotEmpty()) profile.length * 100 / full.length else 0
        Log.i(TAG, "total context: ${full.length} chars")
        Log.i(TAG, "  settings profile : ${profile.length} chars ($pct%)")
        Log.i(TAG, "  everything else  : $rest chars (${100 - pct}%)")
        val mem = Regex("Most relevant memories.*?(?=\\n[A-Z])", RegexOption.DOT_MATCHES_ALL).find(full)?.value?.length ?: 0
        Log.i(TAG, "  of which ranked memories: $mem chars, ${full.lines().count { it.startsWith("• ") }} lines")
        // The ceiling that actually reaches the model. A context bigger than this is not "extra detail" —
        // it's material being silently thrown away, and because the profile is emitted first, what gets
        // thrown away is always the query-specific part.
        val ceiling = 30000
        Log.i(TAG, "  model ceiling    : $ceiling chars → " +
            (if (full.length > ceiling) "${full.length - ceiling} chars TRUNCATED (the tail is the memories)" else "fits, nothing lost"))
        Log.i(TAG, "verdict: " + when {
            full.isEmpty() -> "NO CONTEXT AT ALL — every answer is ungrounded"
            profile.length >= ceiling -> "FATAL: the profile alone fills the window; no memory can reach the model"
            full.length > ceiling && profile.length * 2 > ceiling -> "profile crowds the window — memories are being truncated away"
            pct > 70 -> "PROFILE-DOMINATED ($pct%) — this is the 'only knows my settings card' failure"
            mem < 1500 -> "recall thin ($mem chars) — check the rankedRecall budget"
            else -> "healthy mix"
        })
        Log.i(TAG, "══════ END CONTEXT MIX ══════")
    }

    /**
     * Ground truth for honest testing: what the owner TOLD the brain (learned facts / profile) versus what
     * the brain IMPORTED. An answer built from the first is the brain quoting its own input back — it looks
     * like knowledge and proves nothing. Real questions must come from the second.
     */
    fun sample(ctx: Context, platform: String, n: Int) {
        Log.i(TAG, "══════ GROUND TRUTH: $platform ══════")
        val dropped = try { MemoryStore.compactLearnedFacts(ctx) } catch (t: Throwable) { 0 }
        if (dropped > 0) Log.i(TAG, "compacted learned facts: removed $dropped restatements " +
            "(profile bloat is what pushed real messages out of the context window)")
        val facts = try { MemoryStore.learnedFacts(ctx) } catch (t: Throwable) { emptyList() }
        Log.i(TAG, "learned facts (things YOU typed — answers using these prove nothing): ${facts.size}")
        facts.take(12).forEach { Log.i(TAG, "   TOLD: ${it.take(150)}") }
        Log.i(TAG, "── random IMPORTED messages from $platform (only these prove recall) ──")
        val rows = try { MessageStore.sampleFrom(ctx, platform, n) } catch (t: Throwable) { emptyList() }
        if (rows.isEmpty()) Log.w(TAG, "   none found — is the platform name right?")
        val df = java.text.SimpleDateFormat("MMM d yyyy", java.util.Locale.getDefault())
        rows.forEach { Log.i(TAG, "   [${it.contact} · ${df.format(java.util.Date(it.ts))}] ${it.body.replace("\n", " ").take(220)}") }
        Log.i(TAG, "── semantic index ──")
        // Migrate on demand too, so a probe run doesn't have to wait for the periodic worker.
        var moved = 0
        while (true) { val n = try { VectorStore.migrateQ8(ctx, 4000) } catch (t: Throwable) { 0 }; if (n == 0) break; moved += n }
        if (moved > 0) Log.i(TAG, "quantised $moved vectors onto the fast search path")
        try { Log.i(TAG, "awaiting quantisation: ${VectorStore.unquantizedCount(ctx)}") } catch (t: Throwable) {}
        try { Log.i(TAG, "embedded=${VectorStore.embeddedCount(ctx)} of ${MessageStore.count(ctx)}") } catch (t: Throwable) {}
        Log.i(TAG, "══════ END GROUND TRUTH ══════")
    }

    /**
     * The HOME AI path end to end (BrainContext.build → answerWell), with timings split between retrieval
     * and the model. Different code from the Memory tab, so proving one says nothing about the other —
     * and response speed is the product, so it gets measured every time, not assumed.
     */
    fun home(ctx: Context, q: String) {
        Log.i(TAG, "══════ HOME AI: \"$q\" ══════")
        val t0 = System.currentTimeMillis()
        val ctxStr = try { BrainContext.build(ctx, q) } catch (t: Throwable) { "" }
        val tCtx = System.currentTimeMillis() - t0
        val t1 = System.currentTimeMillis()
        val a = try { AgentClient.answerWell(q, ctxStr, emptyList()) } catch (t: Throwable) { "FAILED: ${t.message}" }
        val tLlm = System.currentTimeMillis() - t1
        Log.i(TAG, "context ${ctxStr.length} chars in ${tCtx}ms · model ${tLlm}ms · TOTAL ${tCtx + tLlm}ms")
        if (tCtx + tLlm > 8000) Log.w(TAG, "SLOW — over 8s is felt as lag")
        a.split("\n").forEach { Log.i(TAG, "   $it") }
        Log.i(TAG, "══════ END HOME AI ══════")
    }

    /**
     * Build a document end to end and inspect the FILE, not just the return code.
     *
     * A generated pitch deck reported success while containing one slide, five blank pages and no text at
     * all — "it produced a PDF" was true and useless. Page count, font count and image count are what
     * actually distinguish a deck someone can send an investor from a screenshot of one.
     */
    fun deck(ctx: Context, brief: String) {
        Log.i(TAG, "══════ DOC BUILD: \"$brief\" ══════")
        val t0 = System.currentTimeMillis()
        val made = try { DocForge.create(ctx, brief.take(60), brief, "pdf") } catch (t: Throwable) {
            Log.e(TAG, "create threw: ${t.message}", t); return
        }
        Log.i(TAG, "ok=${made.ok} name=${made.name} path=${made.path} ${if (made.error.isNotBlank()) "err=" + made.error else ""} (${System.currentTimeMillis() - t0}ms)")
        val f = try { java.io.File(made.path) } catch (t: Throwable) { null }
        if (f == null || !f.exists()) { Log.w(TAG, "no file on disk"); return }
        val bytes = try { f.readBytes() } catch (t: Throwable) { ByteArray(0) }
        fun count(needle: String) = Regex(Regex.escape(needle)).findAll(String(bytes, Charsets.ISO_8859_1)).count()
        val pages = count("/Type/Page") + count("/Type /Page")
        val fonts = count("/Font")
        val images = count("/Image")
        Log.i(TAG, "size=${bytes.size} pages≈$pages fonts=$fonts images=$images")
        // LEAK CHECK. Documents must belong to their SUBJECT, not to whoever generated them — every user has
        // an employer in their brain, so an unrelated deck must not carry it. Letter-spaced CSS renders
        // "BELTO" as "B E LT O" in the extracted text, which is how a plain substring search missed exactly
        // this leak on a 5th-grade science deck; strip non-letters before comparing.
        try {
            val flat = String(bytes, Charsets.ISO_8859_1).replace(Regex("[^A-Za-z]"), "").lowercase()
            val owner = listOfNotNull(
                MemoryStore.profileName(ctx).takeIf { it.isNotBlank() },
                MemoryStore.ownerName(ctx).takeIf { it.isNotBlank() }
            ).flatMap { it.split(" ") }.filter { it.length > 3 }.distinct()
            // SAY WHAT WAS CHECKED. A clean verdict from an empty term list is not evidence of anything —
            // this reported "no leak" on a deck that plainly carried the owner's name, because the profile
            // lookup returned nothing to look for. An unverifiable pass must not read like a pass.
            if (owner.isEmpty()) Log.w(TAG, "leak check SKIPPED — no owner identity terms available to test")
            else {
                val leaked = owner.filter { flat.contains(it.lowercase()) }
                val briefMentions = brief.lowercase().split(Regex("[^a-z]+")).filter { it.length > 3 }
                val expected = briefMentions.any { b -> owner.any { it.lowercase().startsWith(b) } }
                Log.i(TAG, "leak check over [${owner.joinToString()}] → found [${leaked.joinToString().ifBlank { "none" }}]")
                if (leaked.isNotEmpty() && !expected)
                    Log.w(TAG, "OWNER DETAILS IN AN UNRELATED DOCUMENT: ${leaked.joinToString()} — every user has an " +
                        "employer in their brain, so this would brand anyone's personal document with their company")
            }
        } catch (t: Throwable) {}
        Log.i(TAG, "verdict: " + when {
            bytes.isEmpty() -> "EMPTY FILE"
            fonts == 0 && images > 0 -> "RASTERISED — no selectable text, blurs when zoomed, huge file"
            pages <= 1 -> "SINGLE PAGE — a deck should paginate"
            fonts > 0 -> "vector text, $pages pages — sendable"
            else -> "unclear"
        })
        Log.i(TAG, "══════ END DOC BUILD ══════")
    }

    fun health(ctx: Context, deep: Boolean = false) {
        Log.i(TAG, "══════ FEATURE HEALTH (deep=$deep) ══════")
        recallState(ctx)
        val checks = try { FeatureHealth.runAll(ctx, deep) } catch (t: Throwable) {
            Log.e(TAG, "suite blew up: ${t.message}", t); return
        }
        val by = checks.groupingBy { it.status }.eachCount()
        Log.i(TAG, "PASS=${by["PASS"] ?: 0} FAIL=${by["FAIL"] ?: 0} SKIP=${by["SKIP"] ?: 0} DRYRUN=${by["DRYRUN"] ?: 0} (${checks.size} checks)")
        checks.filter { it.status == "FAIL" }.forEach { Log.w(TAG, "  FAIL  ${it.area} / ${it.feature}: ${it.detail}") }
        checks.filter { it.status != "FAIL" }.forEach { Log.i(TAG, "  ${it.status.padEnd(6)}${it.area} / ${it.feature}: ${it.detail.take(90)}") }
        Log.i(TAG, "══════ END FEATURE HEALTH ══════")
    }

    /** Force the semantic index to rebuild in the CURRENT provider's space, reporting progress. */
    fun reembed(ctx: Context, rounds: Int = 40) {
        Log.i(TAG, "══════ RE-EMBED ══════")
        Log.i(TAG, "provider=${EmbeddingClient.provider(ctx)} sidelined=${EmbeddingClient.unhealthyReason(ctx).ifBlank { "-" }}")
        Log.i(TAG, "before: embedded=${VectorStore.embeddedCount(ctx)} of ${MessageStore.count(ctx)} messages")
        val t0 = System.currentTimeMillis()
        repeat(rounds) { i ->
            try { VectorStore.backfill(ctx, 1000) } catch (t: Throwable) { Log.w(TAG, "round $i: ${t.message}") }
            if (i % 5 == 0) Log.i(TAG, "  round $i: embedded=${VectorStore.embeddedCount(ctx)} (${(System.currentTimeMillis() - t0) / 1000}s)")
        }
        Log.i(TAG, "after: embedded=${VectorStore.embeddedCount(ctx)} in ${(System.currentTimeMillis() - t0) / 1000}s")
        try {
            val hits = VectorStore.search(ctx, "who is Carlos", 5)
            Log.i(TAG, "semantic test 'who is Carlos' -> ${hits.size} hits")
            hits.take(3).forEach { Log.i(TAG, "   [${it.contact}] ${it.body.take(100)}") }
        } catch (t: Throwable) { Log.w(TAG, "test: ${t.message}") }
        Log.i(TAG, "══════ END RE-EMBED ══════")
    }

    fun brainStats(ctx: Context) {
        Log.i(TAG, "══════ BRAIN COVERAGE ══════")
        try { Log.i(TAG, "total messages: ${MessageStore.count(ctx)}") } catch (t: Throwable) {}
        try {
            // TRUE totals from SQL. The earlier top-400 sample badly understated platforms with many
            // small threads and led me to a wrong "96% lost" conclusion.
            Log.i(TAG, "── messages by platform (TRUE counts) ──")
            MessageStore.countsByPlatform(ctx).forEach { (plat, c) ->
                Log.i(TAG, "  ${plat.padEnd(14)} $c msgs across ${MessageStore.contactCount(ctx, plat)} contacts")
            }
            val top = MessageStore.topContacts(ctx, 400)
            Log.i(TAG, "── top 15 contacts ──")
            top.take(15).forEach { (n, c, p) -> Log.i(TAG, "  ${n.take(28).padEnd(30)} $c  [$p]") }
        } catch (t: Throwable) { Log.w(TAG, "stats: ${t.message}") }
        try { Log.i(TAG, "LinkedIn connections: ${ConnectionStore.count(ctx)}") } catch (t: Throwable) {}
        try { Log.i(TAG, "vectors embedded: ${VectorStore.embeddedCount(ctx)}") } catch (t: Throwable) {}
        Log.i(TAG, "══════ END COVERAGE ══════")
    }

    fun matrix(ctx: Context) {
        data class Case(val who: String, val channel: String, val topic: String, val msg: String)
        val cases = listOf(
            Case("Joslyn Barragan", "WhatsApp", "personal/spouse", "can you take Bello to the vet tomorrow? i cant make it"),
            Case("Joslyn Barragan", "WhatsApp", "emotional", "rough day today. feeling really low"),
            Case("Anna A", "Telegram", "work/co-founder", "the satlyt deck needs the pricing slide before monday. can you do it?"),
            Case("Anna A", "Telegram", "disagreement", "i think we should drop the satellite angle and focus purely on consumer"),
            Case("Rama", "LinkedIn", "deal/professional", "Great meeting today. Can you send over pricing for the onboard inference pilot?"),
            Case("Nabeel Khan", "LinkedIn", "investor", "Interesting. What's your current traction and round size?"),
            Case("Elon Musk", "X", "technical/banter", "on-device inference is the only way this scales"),
            Case("Carlos XOG", "Instagram", "friend/casual", "yo that dnd session was insane last night 😂"),
            Case("Tamer Elsawaf", "Email", "technical peer", "How are you handling thermal constraints for onboard inference?"),
            Case("Unknown Recruiter", "LinkedIn", "cold/irrelevant", "Hi! Are you open to a Senior Data Analyst role in Ohio?")
        )
        Log.i(TAG, "══════ MATRIX AUDIT — person × channel × topic ══════")
        for (c in cases) {
            try {
                val t0 = System.currentTimeMillis()
                val mem = ReplyContext.forSender(ctx, c.channel, c.who, c.msg)
                val draft = AgentClient.draftReplyThread(c.who, listOf("them" to c.msg), mem, null, c.msg)
                val ident = try { PersonResolver.identityLine(ctx, c.who).take(120) } catch (t: Throwable) { "" }
                Log.i(TAG, "── ${c.who} | ${c.channel} | ${c.topic} (${System.currentTimeMillis() - t0}ms)")
                Log.i(TAG, "   THEY: ${c.msg}")
                Log.i(TAG, "   ID  : ${ident.ifBlank { "(unresolved)" }}")
                Log.i(TAG, "   YOU : ${draft.replace("\n", " ⏎ ").take(300)}")
            } catch (t: Throwable) { Log.w(TAG, "${c.who}/${c.channel} failed: ${t.message}") }
        }
        Log.i(TAG, "══════ END MATRIX ══════")
    }

    /** Draft a reply per channel to [incoming] from [sender] and log what each one produced. */
    fun run(ctx: Context, sender: String = "Anna Schmidt",
            incoming: String = "Hey! Loved what you're building. Any chance you're free this week for a quick call?") {
        Log.i(TAG, "══════ PER-CHANNEL DRAFT AUDIT ══════")
        Log.i(TAG, "from=\"$sender\"  message=\"$incoming\"")

        // Identity resolution first — the same human should be recognised across every platform.
        try {
            val p = PersonResolver.resolve(ctx, sender)
            Log.i(TAG, "IDENTITY: name=${p.name} aliases=${p.aliases} email=${p.email.ifBlank { "-" }} company=${p.company.ifBlank { "-" }}")
            val hist = PersonResolver.historyFor(ctx, sender, 12)
            Log.i(TAG, "CROSS-PLATFORM HISTORY: ${if (hist.isBlank()) "(none found)" else hist.replace("\n", " ").take(400)}")
        } catch (t: Throwable) { Log.w(TAG, "identity failed: ${t.message}") }

        for (ch in CHANNELS) {
            try {
                val persona = MemoryStore.styleFor(ctx, ch)
                val voice = Voice.voiceFor(ctx, ch)
                val exemplars = Regex("Real examples of how you actually write[^\n]*").find(voice)?.value.orEmpty()
                val t0 = System.currentTimeMillis()
                val draft = AgentClient.draftReplyThread(sender, listOf("them" to incoming),
                    ReplyContext.forSender(ctx, ch, sender, incoming), null, incoming)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "───── $ch ─────")
                Log.i(TAG, "  persona   : ${persona.ifBlank { "*** NONE SET ***" }}")
                Log.i(TAG, "  exemplars : ${if (exemplars.isBlank()) "(none — writing to character)" else exemplars.take(180)}")
                Log.i(TAG, "  ctxChars  : ${voice.length}")
                Log.i(TAG, "  DRAFT(${ms}ms): ${draft.replace("\n", " ⏎ ").take(400)}")
            } catch (t: Throwable) { Log.w(TAG, "$ch failed: ${t.message}") }
        }
        Log.i(TAG, "══════ END AUDIT ══════")
    }
}
