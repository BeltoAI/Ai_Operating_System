package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Your own lightweight CRM, on-device. Every real human your agents find or correspond with lands here —
 * name, email, role, company, where they came from, notes, when. Deduped by email. No HubSpot required:
 * agents read and write this directly, you can export it to CSV, and later link/push it to HubSpot from
 * Settings. This is what makes the "email collector" and outreach agents actually useful.
 */
object LeadStore {
    private const val TAG = "SlyOS-Leads"

    data class Lead(
        val id: Long, val name: String, val email: String, val role: String,
        val company: String, val source: String, val notes: String, val ts: Long,
        val extra: String = "{}"   // free-form JSON: phone, linkedin, location, interests, lastContact, nextStep, tags…
    )

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "slyos_leads.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS leads(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, " +
                "email TEXT UNIQUE, role TEXT, company TEXT, source TEXT, notes TEXT, ts INTEGER, extra TEXT DEFAULT '{}')")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            if (o < 2) try { db.execSQL("ALTER TABLE leads ADD COLUMN extra TEXT DEFAULT '{}'") } catch (e: Exception) {}
        }
    }

    private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase {
        if (helper == null) helper = Helper(ctx.applicationContext)
        return helper!!.writableDatabase
    }

    /** Shallow-merge two JSON objects (new keys win); tolerant of junk. */
    private fun mergeJson(oldJson: String, newJson: String): String = try {
        val a = try { org.json.JSONObject(oldJson) } catch (e: Exception) { org.json.JSONObject() }
        val b = try { org.json.JSONObject(newJson) } catch (e: Exception) { org.json.JSONObject() }
        b.keys().forEach { k -> val v = b.optString(k).trim(); if (v.isNotEmpty()) a.put(k, v) }
        a.toString()
    } catch (e: Exception) { newJson.ifBlank { oldJson } }

    /** Add or update a lead, deduped by email. [extra] is free-form JSON of anything useful about them. */
    /**
     * Senders that are not people, and must never become CRM contacts.
     *
     * Measured on a real device: 436 rows, 98% with no company and no role, and dozens of them
     * newsletters — "Zapier News", "F6S Startup Alert", noreply@app.documenso.com. A CRM whose rows
     * are mostly bulk mail is worse than an empty one, because the handful of real people in it can
     * no longer be found. The inbox harvester was adding every address it saw.
     *
     * Matched on the local part rather than the domain, because the domain is often a company you
     * genuinely deal with — noreply@stripe.com is not a contact, but a person at stripe.com is.
     */
    private val NOT_A_PERSON = Regex(
        "(?i)^(no[-_.]?reply|do[-_.]?not[-_.]?reply|donotreply|noreply|news|newsletter|notifications?|" +
        "notify|updates?|alerts?|mailer|mailer[-_.]?daemon|bounce[sd]?|postmaster|automated|auto|" +
        "marketing|promo(tions?)?|offers?|deals?|billing|invoices?|receipts?|statements?|" +
        "support|help|helpdesk|info|contact|hello|hi|team|admin|webmaster|security|" +
        "careers|jobs|recruiting|unsubscribe|feedback|survey|digest|reply|robot|bot|system|" +
        "account|accounts|service|services|mail|email|messages?)([-_.+].*)?$")

    /** A bulk-mail domain: nothing at e.foo.com or send.foo.com is a person writing to you. */
    private val BULK_DOMAIN = Regex("(?i)^(e|em|email|send|sendgrid|mail|mailer|news|reply|m|t|mg|smtp|" +
        "notifications?|updates?|marketing|bounces?|list|links?)\\.")

    /**
     * Is this address a human correspondent, or machinery?
     *
     * Public so the harvester can skip before it even builds a row, and so a future clean-up pass
     * can be honest about which existing rows it is removing.
     */
    fun isPerson(email: String): Boolean {
        val e = email.trim().lowercase()
        if (!e.contains('@') || e.count { it == '@' } != 1) return false
        val local = e.substringBefore('@')
        val domain = e.substringAfter('@')
        if (NOT_A_PERSON.matches(local)) return false
        if (BULK_DOMAIN.containsMatchIn(domain)) return false
        // A local part that is a long opaque token is a per-message reply address, not a person.
        if (local.length > 28 && !local.contains('.')) return false
        return true
    }

    /**
     * The company, from the address, when nobody supplied one.
     *
     * 98% of rows had an empty company field, which is most of what makes a CRM row worth having.
     * The domain is not a perfect answer but it is a true one and it is free — and it turns an
     * address list into something you can actually group and search by. Free-mail domains are left
     * blank rather than filed as though Gmail were an employer.
     */
    private val FREE_MAIL = setOf("gmail.com", "googlemail.com", "yahoo.com", "ymail.com",
        "hotmail.com", "outlook.com", "live.com", "msn.com", "icloud.com", "me.com", "mac.com",
        "aol.com", "proton.me", "protonmail.com", "gmx.com", "mail.com", "yandex.com", "qq.com")

    fun companyFromEmail(email: String): String {
        val domain = email.trim().lowercase().substringAfter('@', "")
        if (domain.isBlank() || domain in FREE_MAIL) return ""
        // Drop a leading subdomain that is not part of the name, keep the registrable-ish part.
        val parts = domain.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return ""
        val name = if (parts.size > 2 && parts[parts.size - 2].length <= 3)
            parts[parts.size - 3]          // co.uk, com.au and friends
        else parts[parts.size - 2]
        return name.replace('-', ' ').split(' ')
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    fun add(ctx: Context, name: String, email: String, role: String = "", company: String = "", source: String = "", notes: String = "", extra: String = "{}"): Boolean {
        val key = email.trim().lowercase()
        // Machinery never becomes a contact. Rejected here rather than at each caller, because there
        // are several callers and only one of them was ever going to remember.
        if (key.isNotBlank() && !isPerson(key)) return false
        return addChecked(ctx, name, key, role, company.ifBlank { companyFromEmail(key) },
            source, notes, extra)
    }

    private fun addChecked(ctx: Context, name: String, key: String, role: String, company: String,
                           source: String, notes: String, extra: String): Boolean = try {
        val cv = ContentValues().apply {
            put("name", name.trim()); put("email", key); put("role", role.trim())
            put("company", company.trim()); put("source", source.trim()); put("notes", notes.trim())
            put("ts", System.currentTimeMillis()); put("extra", extra.ifBlank { "{}" })
        }
        // Upsert: replace on email conflict, but keep existing non-blank fields we aren't overwriting.
        if (key.isNotBlank()) {
            val existing = byEmail(ctx, key)
            if (existing != null) {
                val merged = ContentValues().apply {
                    put("name", name.ifBlank { existing.name })
                    put("role", role.ifBlank { existing.role })
                    put("company", company.ifBlank { existing.company })
                    put("source", source.ifBlank { existing.source })
                    put("notes", (existing.notes + (if (notes.isNotBlank() && !existing.notes.contains(notes)) "\n$notes" else "")).trim())
                    put("extra", mergeJson(existing.extra, extra))
                    put("ts", System.currentTimeMillis())
                }
                db(ctx).update("leads", merged, "email=?", arrayOf(key))
            } else db(ctx).insert("leads", null, cv)
        } else db(ctx).insert("leads", null, cv)
        true
    } catch (e: Exception) { false }

    fun byEmail(ctx: Context, email: String): Lead? = all(ctx).firstOrNull { it.email.equals(email.trim(), true) }

    /**
     * Clean what is already in there, once.
     *
     * The filter above stops new machinery arriving and does nothing about the rows already filed —
     * and the whole complaint is about the state of the existing CRM, not the next entry. Removes
     * the bulk senders and backfills the company from the address for everyone else, so the
     * existing 400-odd rows become groupable instead of only future ones.
     *
     * Returns how many were dropped and how many gained a company, so the result can be reported
     * rather than quietly assumed.
     */
    fun tidy(ctx: Context): Pair<Int, Int> {
        var dropped = 0; var filled = 0
        try {
            all(ctx).forEach { l ->
                if (l.email.isNotBlank() && !isPerson(l.email)) {
                    db(ctx).delete("leads", "email=?", arrayOf(l.email.lowercase())); dropped++
                } else if (l.company.isBlank()) {
                    val c = companyFromEmail(l.email)
                    if (c.isNotBlank()) {
                        db(ctx).update("leads", ContentValues().apply { put("company", c) },
                            "email=?", arrayOf(l.email.lowercase()))
                        filled++
                    }
                }
            }
        } catch (e: Exception) {}
        return dropped to filled
    }

    fun all(ctx: Context): List<Lead> = try {
        val out = ArrayList<Lead>()
        db(ctx).rawQuery("SELECT id,name,email,role,company,source,notes,ts,extra FROM leads ORDER BY ts DESC", null).use { c ->
            while (c.moveToNext()) out.add(Lead(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "",
                c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getLong(7),
                c.getString(8) ?: "{}"))
        }
        out
    } catch (e: Exception) { emptyList() }

    fun count(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT COUNT(*) FROM leads", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    fun search(ctx: Context, q: String, limit: Int = 12): List<Lead> {
        val t = q.lowercase()
        return all(ctx).filter { it.name.lowercase().contains(t) || it.email.lowercase().contains(t) ||
            it.role.lowercase().contains(t) || it.company.lowercase().contains(t) }.take(limit)
    }

    fun remove(ctx: Context, id: Long) = try { db(ctx).delete("leads", "id=?", arrayOf(id.toString())); Unit } catch (e: Exception) {}

    /** Export the whole CRM to a CSV in the app files dir; returns the file (or null). */
    fun exportCsv(ctx: Context): File? = try {
        val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "slyos_contacts.csv")
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""
        f.printWriter().use { w ->
            w.println("Name,Email,Role,Company,Source,Notes,Added")
            val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            all(ctx).forEach { l ->
                w.println(listOf(l.name, l.email, l.role, l.company, l.source, l.notes, df.format(java.util.Date(l.ts))).joinToString(",") { esc(it) })
            }
        }
        f
    } catch (e: Exception) { null }

    /** Compact brief for the brain so agents/HomeAI know who's in your CRM. */
    fun brief(ctx: Context, limit: Int = 12): String {
        val leads = all(ctx).take(limit)
        if (leads.isEmpty()) return ""
        return leads.joinToString("\n") { l ->
            val ex = try {
                val o = org.json.JSONObject(l.extra)
                o.keys().asSequence().take(4).joinToString(", ") { k -> "$k: ${o.optString(k)}" }
            } catch (e: Exception) { "" }
            "• ${l.name}" + (if (l.email.isNotBlank()) " <${l.email}>" else "") +
                (if (l.role.isNotBlank()) " — ${l.role}" else "") + (if (l.company.isNotBlank()) " @ ${l.company}" else "") +
                (if (l.notes.isNotBlank()) " · ${l.notes.replace("\n", " ").take(80)}" else "") +
                (if (ex.isNotBlank()) " · $ex" else "")
        }
    }
}
