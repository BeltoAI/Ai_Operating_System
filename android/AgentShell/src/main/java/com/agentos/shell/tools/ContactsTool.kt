package com.agentos.shell.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/** Resolves contact names to phone numbers (local, READ_CONTACTS), with ranked matching. */
object ContactsTool {

    data class Contact(val name: String, val number: String)

    fun canRead(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Outcome of resolving a name: one clear person, several possible people, or nobody. */
    sealed class Resolution {
        data class Found(val contact: Contact) : Resolution()
        data class Ambiguous(val options: List<Contact>) : Resolution()
        object None : Resolution()
    }

    /** All contacts matching [query], ranked (exact → starts-with → word-starts-with → contains), de-duped. */
    fun findCandidates(ctx: Context, query: String, limit: Int = 8): List<Contact> {
        if (!canRead(ctx) || query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val all = mutableListOf<Contact>()
            ctx.contentResolver.query(uri, projection, selection, arrayOf("%$query%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    all.add(Contact(name, number))
                }
            }
            fun rank(n: String): Int {
                val l = n.lowercase()
                return when {
                    l == q -> 0
                    l.startsWith(q) -> 1
                    l.split(" ", ".").any { it.startsWith(q) } -> 2
                    else -> 3
                }
            }
            val out = LinkedHashMap<String, Contact>()
            all.sortedBy { rank(it.name) }.forEach { out.putIfAbsent("${it.name.lowercase()}|${it.number}", it) }
            out.values.take(limit)
        } catch (e: Exception) { Log.e("SlyOS", "contacts query failed", e); emptyList() }
    }

    /**
     * Resolve a name to a single contact, or ask for help. Found only when there's an exact name or a
     * single distinct person; Ambiguous when several different people match (so the agent can ask which).
     */
    fun resolve(ctx: Context, query: String): Resolution {
        if (!canRead(ctx)) return Resolution.None
        val cands = findCandidates(ctx, query)
        if (cands.isEmpty()) return Resolution.None
        val q = query.trim().lowercase()
        cands.firstOrNull { it.name.lowercase() == q }?.let { return Resolution.Found(it) }
        val distinct = cands.distinctBy { it.name.lowercase() }
        return if (distinct.size == 1) Resolution.Found(cands.first()) else Resolution.Ambiguous(distinct.take(5))
    }

    /** Best contact match for [query]: exact name → starts-with → contains. */
    fun findContact(ctx: Context, query: String): Contact? {
        if (!canRead(ctx) || query.isBlank()) return null
        val q = query.trim().lowercase()
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$query%")
            val candidates = mutableListOf<Contact>()
            ctx.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    candidates.add(Contact(name, number))
                }
            }
            Log.i("SlyOS", "contacts for \"$query\": ${candidates.map { it.name }}")
            candidates.firstOrNull { it.name.lowercase() == q }
                ?: candidates.firstOrNull { it.name.lowercase().startsWith(q) }
                ?: candidates.firstOrNull()
        } catch (e: Exception) {
            Log.e("SlyOS", "contacts query failed", e); null
        }
    }
}
