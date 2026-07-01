package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Your LinkedIn network + message history, imported from the official data export. Lets SlyOS reach
 * the whole graph (not just phone chats), know exactly who you've messaged and when, and find who
 * you've never actually reached out to.
 */
object ConnectionStore {
    data class Conn(
        val name: String, val company: String, val role: String, val connectedOn: String,
        val url: String, val source: String = "LinkedIn", var reachedOut: Boolean = false
    )

    private const val PREF = "slyos_connections"
    private const val KEY = "items"
    private const val KEY_MSG = "contacted"   // name -> last-contacted epoch millis
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Conn> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Conn(o.getString("name"), o.optString("company"), o.optString("role"),
                o.optString("connectedOn"), o.optString("url"),
                o.optString("source", "LinkedIn"), o.optBoolean("reachedOut", false))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, items: List<Conn>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("name", it.name).put("company", it.company).put("role", it.role)
                .put("connectedOn", it.connectedOn).put("url", it.url)
                .put("source", it.source).put("reachedOut", it.reachedOut))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun count(ctx: Context): Int = load(ctx).size
    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).remove(KEY_MSG).apply()

    fun markReachedOut(ctx: Context, name: String) =
        save(ctx, load(ctx).map { if (it.name == name) it.copy(reachedOut = true) else it })

    // ---- contacted map (from messages.csv) ----
    private fun contacted(ctx: Context): Map<String, Long> = try {
        val o = JSONObject(prefs(ctx).getString(KEY_MSG, "{}"))
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (e: Exception) { emptyMap() }

    fun messagedCount(ctx: Context): Int = contacted(ctx).size

    /** Connections you've NEVER messaged (per LinkedIn history + phone chats) and not marked done. */
    fun neverReachedOut(ctx: Context): List<Conn> {
        val talked = HashSet<String>()
        ConversationStore.all(ctx).keys.forEach { talked.add(it.substringAfter("|").lowercase().trim()) }
        contacted(ctx).keys.forEach { talked.add(it.lowercase().trim()) }
        return load(ctx).filter { !it.reachedOut && !talked.contains(it.name.lowercase().trim()) }
    }

    /** Connections you HAVE messaged but not in over [days] days (from message history). */
    fun staleConnections(ctx: Context, days: Int): List<Pair<Conn, Long>> {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val map = contacted(ctx)
        val byName = load(ctx).associateBy { it.name.lowercase().trim() }
        return map.entries.filter { it.value in 1 until cutoff }
            .mapNotNull { e -> byName[e.key.lowercase().trim()]?.let { it to e.value } }
            .sortedBy { it.second }
    }

    // General intent synonyms so ANY kind of ask matches — not just investors.
    private val SYN = mapOf(
        "vc" to listOf("venture", "capital", "ventures", "investor", "partner", "fund", "equity", "angel"),
        "investor" to listOf("venture", "capital", "investor", "partner", "fund", "angel"),
        "founder" to listOf("founder", "cofounder", "co-founder", "ceo", "owner"),
        "engineer" to listOf("engineer", "developer", "software", "swe"),
        "designer" to listOf("designer", "design", "ux", "ui"),
        "sales" to listOf("sales", "account", "business development"),
        "marketing" to listOf("marketing", "growth", "brand"),
        "recruiter" to listOf("recruiter", "talent", "recruiting"),
        "professor" to listOf("professor", "phd", "research", "lab", "university"),
        "lawyer" to listOf("lawyer", "legal", "attorney", "counsel"),
        // Executive titles — connections are usually stored with the FULL title, so map the abbreviation.
        "cto" to listOf("cto", "chief technology", "chief technical", "technology officer", "vp engineering", "head of engineering", "technical cofounder", "engineering"),
        "ceo" to listOf("ceo", "chief executive", "founder", "president"),
        "cfo" to listOf("cfo", "chief financial", "finance"),
        "coo" to listOf("coo", "chief operating", "operations"),
        "cmo" to listOf("cmo", "chief marketing", "marketing"),
        "cpo" to listOf("cpo", "chief product", "product officer", "head of product"),
        "cio" to listOf("cio", "chief information", "information officer"),
        "vp" to listOf("vp", "vice president", "head of", "director"),
        "pm" to listOf("pm", "product manager", "product management")
    )
    // Words that carry no matching signal in a "find me a X" request — dropped so they don't pollute.
    private val STOP = setOf("find", "candidate", "candidates", "need", "want", "looking", "some", "good",
        "new", "people", "person", "someone", "help", "get", "hire", "hiring", "potential", "list", "the",
        "and", "for", "who", "any", "give", "show", "recommend", "suggest", "best", "top", "great",
        "me", "at", "in", "on", "of", "to", "is", "it", "my", "an", "or", "do", "we", "us", "be", "by", "as", "so")

    /**
     * Search your connections by name / company / role for the Memory Ask — works for ANY query
     * (people, companies, roles, schools…), with plural/singular stemming and light synonym boosts.
     */
    fun search(ctx: Context, query: String, limit: Int = 40): List<Conn> {
        val raw = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in STOP }
        if (raw.isEmpty()) return emptyList()
        val terms = HashSet<String>()
        raw.forEach { w ->
            terms.add(w)
            if (w.endsWith("s") && w.length > 3) terms.add(w.dropLast(1)) else terms.add(w + "s")
            SYN[w]?.let { terms.addAll(it) }
        }
        return load(ctx).map { c ->
            val hay = (c.name + " " + c.company + " " + c.role).lowercase()
            c to terms.count { hay.contains(it) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /**
     * Smart import: sniff a LinkedIn CSV (Connections / messages / Profile) and route it.
     * Returns a human status string.
     */
    fun importLinkedIn(ctx: Context, uri: Uri): String {
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return "Couldn't open the file."
        } catch (e: Exception) { return "Couldn't open the file." }
        val rows = parseCsv(text)
        if (rows.isEmpty()) return "Empty file."
        val headerRow = rows.firstOrNull { r -> r.any { it.contains("First Name", true) || it.contains("CONVERSATION ID", true) || it.contains("Headline", true) } }
            ?: return "Unrecognized CSV."
        val hl = headerRow.joinToString(",").lowercase()
        return when {
            hl.contains("conversation id") -> importMessages(ctx, rows, headerRow)
            hl.contains("company name") && hl.contains("title") -> importPositions(ctx, rows, headerRow)
            hl.contains("school name") -> importEducation(ctx, rows, headerRow)
            hl.contains("headline") && hl.contains("summary") -> importProfile(ctx, rows, headerRow)
            hl.contains("first name") -> importConnections(ctx, rows, headerRow)
            else -> "Unrecognized LinkedIn CSV."
        }
    }

    /** Positions.csv → your real job history, stored so the brain (and the résumé builder) know it. */
    private fun importPositions(ctx: Context, rows: List<List<String>>, header: List<String>): String {
        val h = header.map { it.trim().lowercase() }
        fun col(vararg n: String) = h.indexOfFirst { x -> n.any { x == it } }
        val iC = col("company name"); val iT = col("title"); val iD = col("description")
        val iS = col("started on"); val iF = col("finished on"); val iL = col("location")
        val start = rows.indexOf(header) + 1
        val out = StringBuilder(); var n = 0
        for (i in start until rows.size) {
            val c = rows[i]; fun g(idx: Int) = if (idx in c.indices) c[idx].trim() else ""
            val title = g(iT); val company = g(iC)
            if (title.isBlank() && company.isBlank()) continue
            val span = listOf(g(iS), g(iF).ifBlank { "Present" }).filter { it.isNotBlank() }.joinToString(" – ")
            out.append("• ").append(title).append(if (company.isNotBlank()) " at $company" else "")
            if (span.isNotBlank()) out.append(" ($span)")
            if (g(iL).isNotBlank()) out.append(" — ").append(g(iL))
            if (g(iD).isNotBlank()) out.append("\n    ").append(g(iD).replace("\n", " ").take(400))
            out.append("\n"); n++
        }
        if (n == 0) return "No positions found."
        MemoryStore.setPositions(ctx, out.toString().trim())   // replace so re-import doesn't duplicate
        return "Added $n LinkedIn positions to your brain."
    }

    /** Education.csv → schools/degrees, stored for the résumé and profile. */
    private fun importEducation(ctx: Context, rows: List<List<String>>, header: List<String>): String {
        val h = header.map { it.trim().lowercase() }
        fun col(vararg n: String) = h.indexOfFirst { x -> n.any { x == it } }
        val iS = col("school name"); val iD = col("degree name"); val iSt = col("start date"); val iE = col("end date")
        val start = rows.indexOf(header) + 1
        val out = StringBuilder(); var n = 0
        for (i in start until rows.size) {
            val c = rows[i]; fun g(idx: Int) = if (idx in c.indices) c[idx].trim() else ""
            val school = g(iS); if (school.isBlank()) continue
            val span = listOf(g(iSt), g(iE)).filter { it.isNotBlank() }.joinToString(" – ")
            out.append("• ").append(school).append(if (g(iD).isNotBlank()) " — ${g(iD)}" else "")
            if (span.isNotBlank()) out.append(" ($span)")
            out.append("\n"); n++
        }
        if (n == 0) return "No education found."
        MemoryStore.setEducation(ctx, out.toString().trim())
        return "Added $n schools to your brain."
    }

    private fun importConnections(ctx: Context, rows: List<List<String>>, header: List<String>): String {
        val h = header.map { it.trim().lowercase() }
        fun col(vararg n: String) = h.indexOfFirst { x -> n.any { x == it } }
        val iF = col("first name"); val iL = col("last name"); val iU = col("url")
        val iC = col("company"); val iR = col("position"); val iW = col("connected on")
        val start = rows.indexOf(header) + 1
        val out = ArrayList<Conn>()
        for (i in start until rows.size) {
            val c = rows[i]; fun g(idx: Int) = if (idx in c.indices) c[idx].trim() else ""
            val name = (g(iF) + " " + g(iL)).trim()
            if (name.isBlank()) continue
            out.add(Conn(name, g(iC), g(iR), g(iW), g(iU), "LinkedIn"))
        }
        if (out.isEmpty()) return "No connections found."
        val existing = load(ctx).filter { it.source != "LinkedIn" }   // replace LinkedIn set
        save(ctx, existing + out)
        return "Imported ${out.size} LinkedIn connections."
    }

    private fun importMessages(ctx: Context, rows: List<List<String>>, header: List<String>): String {
        val h = header.map { it.trim().uppercase() }
        val iFrom = h.indexOf("FROM"); val iTo = h.indexOf("TO"); val iDate = h.indexOf("DATE")
        if (iFrom < 0 || iTo < 0) return "Couldn't read messages.csv."
        val start = rows.indexOf(header) + 1
        // Owner = the name appearing most often across FROM (you sent the most).
        val freq = HashMap<String, Int>()
        for (i in start until rows.size) {
            val c = rows[i]; if (iFrom in c.indices) { val f = c[iFrom].trim(); if (f.isNotBlank()) freq[f] = (freq[f] ?: 0) + 1 }
        }
        val owner = freq.maxByOrNull { it.value }?.key ?: ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val last = HashMap<String, Long>()
        for (i in start until rows.size) {
            val c = rows[i]
            fun g(idx: Int) = if (idx in c.indices) c[idx].trim() else ""
            val from = g(iFrom); val to = g(iTo)
            val other = if (from.equals(owner, true)) to else from
            if (other.isBlank() || other.equals(owner, true)) continue
            val t = try { fmt.parse(g(iDate).replace(" UTC", ""))?.time ?: 0L } catch (e: Exception) { 0L }
            val cur = last[other] ?: 0L
            if (t > cur) last[other] = t
        }
        if (last.isEmpty()) return "No messages parsed."
        val o = JSONObject(); last.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString(KEY_MSG, o.toString()).apply()
        return "Imported message history for ${last.size} people."
    }

    private fun importProfile(ctx: Context, rows: List<List<String>>, header: List<String>): String {
        val h = header.map { it.trim().lowercase() }
        val iHead = h.indexOf("headline"); val iSum = h.indexOf("summary")
        val iF = h.indexOf("first name"); val iL = h.indexOf("last name")
        val data = rows.getOrNull(rows.indexOf(header) + 1) ?: return "No profile row."
        fun g(idx: Int) = if (idx in data.indices) data[idx].trim() else ""
        val name = (g(iF) + " " + g(iL)).trim()
        val parts = listOfNotNull(
            name.ifBlank { null }?.let { "My name is $it." },
            g(iHead).ifBlank { null }?.let { "Headline: $it." },
            g(iSum).ifBlank { null }
        )
        if (parts.isEmpty()) return "No profile details found."
        val cur = MemoryStore.about(ctx)
        val block = parts.joinToString(" ")
        MemoryStore.setAbout(ctx, if (cur.isBlank()) block else "$cur\n\n$block")
        return "Added your LinkedIn profile to your About/memory."
    }

    /** Robust CSV parser: handles quoted fields that contain commas AND newlines. */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val sb = StringBuilder(); var inQ = false; var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' && inQ && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQ = !inQ
                ch == ',' && !inQ -> { row.add(sb.toString()); sb.setLength(0) }
                (ch == '\n' || ch == '\r') && !inQ -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(sb.toString()); sb.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = ArrayList()
                }
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); if (row.any { it.isNotBlank() }) rows.add(row) }
        return rows
    }
}
