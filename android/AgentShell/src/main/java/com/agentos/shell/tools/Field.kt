package com.agentos.shell.tools

import android.content.Context

/**
 * Everyone, from everywhere, in one flat list.
 *
 * The field's first version read the LinkedIn import and nothing else, which meant a screen that
 * claimed to be your network was missing every person you had ever actually messaged — four and a
 * half thousand of them. A network view that only grows when you re-export a CSV is a chart, not a
 * network.
 *
 * So this merges the two things the phone already knows and keeps merging them:
 *
 *  - **Connections** — the LinkedIn import. Twenty thousand people, an employer each, no history.
 *  - **The roster** — every human you have exchanged a message with, on any channel. WhatsApp,
 *    Instagram, email, Telegram, calls. This one is live: a new thread today is a new dot tonight,
 *    with nothing to import and nothing to press.
 *
 * They overlap heavily, so the merge is by normalised name and the messaged version wins — someone
 * you actually speak to should never appear as a stranger.
 *
 * `group` is what the dot clusters by, and it is deliberately two different things depending on
 * what is known: an employer if there is one, otherwise the channel you speak on. Both are true,
 * both make visible structure, and between them almost nobody falls through to the haze.
 */
object Field {

    class Sky(
        val names: Array<String>,
        /** Employer, or the channel — whichever this person is actually defined by. */
        val companies: Array<String>,
        /** Have you ever exchanged a word? The one bit that separates a contact from a name. */
        val touched: BooleanArray,
        /** Everyone, including anyone the draw cap left out. */
        val total: Int
    ) {
        val size: Int get() = names.size
        val sampled: Boolean get() = size < total
    }

    // MARK: - Cache
    //
    // Reading twenty-one thousand people takes sixteen seconds: seventy thousand message rows for
    // the roster, twenty thousand connection rows, and the layout on top. Doing that every time the
    // screen opens is the difference between a place you drop into and a page you wait for.
    //
    // So it is read once and kept — in memory for the session, and on disk for the next cold start.
    // The snapshot is name, group and one bit per person, which is the whole of what the field
    // draws; everything else is looked up only when a dot is tapped.

    @Volatile private var memo: Sky? = null

    /** How long a snapshot is trusted before the phone goes and looks again. */
    private const val FRESH_MS = 12L * 60 * 60 * 1000

    /** Old enough to be worth rebuilding? Nothing here changes minute to minute. */
    fun stale(ctx: Context): Boolean = try {
        val f = file(ctx)
        !f.exists() || System.currentTimeMillis() - f.lastModified() > FRESH_MS
    } catch (e: Exception) { true }

    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "field_sky.tsv")

    /** Whatever can be shown instantly. Null only on the very first open. */
    fun cached(ctx: Context): Sky? {
        memo?.let { return it }
        return try {
            val f = file(ctx)
            if (!f.exists()) return null
            val lines = f.readLines()
            if (lines.isEmpty()) return null
            val total = lines[0].toIntOrNull() ?: return null
            val n = lines.size - 1
            val names = arrayOfNulls<String>(n); val groups = arrayOfNulls<String>(n)
            val touched = BooleanArray(n)
            val intern = HashMap<String, String>(4096)
            for (i in 0 until n) {
                val parts = lines[i + 1].split('\t')
                names[i] = parts.getOrElse(0) { "" }
                val g = parts.getOrElse(1) { "" }
                groups[i] = intern.getOrPut(g) { g }
                touched[i] = parts.getOrElse(2) { "0" } == "1"
            }
            @Suppress("UNCHECKED_CAST")
            Sky(names as Array<String>, groups as Array<String>, touched, total).also { memo = it }
        } catch (e: Exception) { null }
    }

    private fun write(ctx: Context, sky: Sky) {
        try {
            val sb = StringBuilder(sky.size * 32)
            sb.append(sky.total).append('\n')
            for (i in 0 until sky.size) {
                // Tabs and newlines out of names, or one bad contact shifts every row after it.
                sb.append(sky.names[i].replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(sky.companies[i].replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(if (sky.touched[i]) '1' else '0').append('\n')
            }
            file(ctx).writeText(sb.toString())
        } catch (e: Exception) {}
    }

    /** Normalised enough to catch the same person twice, not so much it merges two people. */
    private fun key(n: String): String =
        n.lowercase().replace(Regex("[^a-z0-9@. ]"), "").trim().replace(Regex("\\s+"), " ")

    /**
     * The cap is on what gets DRAWN, never on what counts.
     *
     * Twenty thousand dots is 320KB of floats and ten draw calls a frame — nothing. A million is a
     * hundred megabytes of strings before a pixel is drawn, and past what the GPU will do at sixty
     * hertz. Above the cap the connections table is sampled in SQL, so the extra rows are never
     * materialised at all, and `total` still reports the truth — including on screen.
     */
    fun load(ctx: Context, cap: Int = 60_000): Sky {
        val messaged = try { Crm.roster(ctx) } catch (e: Exception) { emptyList() }
        // Leave room for the messaged half; they are the half that matters.
        val conns = try { ConnectionStore.sky(ctx, (cap - messaged.size).coerceAtLeast(1000)) }
                    catch (e: Exception) { null }

        val byKey = HashMap<String, Crm.RosterEntry>(messaged.size * 2)
        messaged.forEach { r -> key(r.name).takeIf { it.isNotBlank() }?.let { byKey[it] = r } }

        val n0 = (conns?.size ?: 0) + messaged.size
        val names = ArrayList<String>(n0)
        val groups = ArrayList<String>(n0)
        val touched = ArrayList<Boolean>(n0)
        val seen = HashSet<String>(n0 * 2)

        // Connections FIRST, so anyone with a known employer is placed by their employer.
        //
        // Doing this the other way round put three and a half thousand people you have messaged on
        // LinkedIn into a single group called "LinkedIn" — one enormous solid slab in the middle of
        // the disc, which is both ugly and a worse fact than the one available: WHERE they work.
        // The channel is the fallback, never the answer when an employer is known.
        if (conns != null) for (i in 0 until conns.size) {
            val k = key(conns.names[i])
            if (k.isBlank() || !seen.add(k)) continue
            names.add(conns.names[i])
            groups.add(conns.companies[i])          // blank stays blank — haze, not a fake arm
            touched.add(conns.touched[i] || k in byKey)
        }
        // Then everyone you have only ever messaged: no employer on file, so the channel groups them.
        byKey.forEach { (k, r) ->
            if (!seen.add(k)) return@forEach
            names.add(r.name); groups.add(r.channel); touched.add(true)
        }

        // Exact when nothing was capped, which is the normal case. The only time this exceeds what
        // is drawn is when the connections table itself was sampled in SQL.
        val dropped = if (conns != null && conns.sampled) conns.total - conns.size else 0
        val sky = Sky(names.toTypedArray(), groups.toTypedArray(),
            BooleanArray(touched.size) { touched[it] }, names.size + dropped)
        memo = sky
        write(ctx, sky)
        return sky
    }
}
