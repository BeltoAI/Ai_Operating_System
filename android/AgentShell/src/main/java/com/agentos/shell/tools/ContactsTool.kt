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
        var cands = findCandidates(ctx, query)
        // THE CLOSEST PERSON, RATHER THAN NOBODY.
        //
        // Reported: a message drafted to "Joslyn 💞" failed to send with "contact couldn't be
        // found". Stripping the emoji fixes that particular name, and it does not fix the general
        // case — the address book spells people differently from the way a chat app does, so
        // "Joslyn Barragán" here can be "Joslyn B" there, and an exact search returns nothing.
        //
        // Failing outright when the answer is one obvious person away is the wrong trade. Two looser
        // passes run only where this used to give up: the first name alone, then a token-overlap
        // scan for anyone sharing a whole word. Anything genuinely ambiguous still comes back
        // Ambiguous and gets asked about — a wrong recipient is far worse than a question.
        if (cands.isEmpty()) {
            val first = query.trim().split(Regex("\\s+")).firstOrNull { it.length > 1 }.orEmpty()
            if (first.isNotBlank() && !first.equals(query.trim(), true))
                cands = findCandidates(ctx, first)
        }
        if (cands.isEmpty()) {
            val words = query.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length > 2 }.toSet()
            // A blank query returns nothing from findCandidates, so the net is cast with the first
            // two letters — broad enough to catch a differently-spelled surname, narrow enough that
            // the provider does the work rather than this loop.
            val seed = words.firstOrNull()?.take(2).orEmpty()
            if (words.isNotEmpty() && seed.length == 2) cands = try {
                findCandidates(ctx, seed, limit = 200).filter { c ->
                    val cw = c.name.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length > 2 }
                    cw.any { it in words } || words.any { w -> cw.any { it.startsWith(w) || w.startsWith(it) } }
                }.take(5)
            } catch (e: Exception) { emptyList() }
        }
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

    /** Best email address for a contact name (for pre-filling email sends). */
    fun findEmail(ctx: Context, query: String): String? {
        if (!canRead(ctx) || query.isBlank()) return null
        val q = query.trim().lowercase()
        return try {
            val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
            )
            val sel = "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?"
            val rows = mutableListOf<Pair<String, String>>()
            ctx.contentResolver.query(uri, projection, sel, arrayOf("%$query%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    rows.add(name to addr)
                }
            }
            rows.firstOrNull { it.first.lowercase() == q }?.second
                ?: rows.firstOrNull { it.first.lowercase().startsWith(q) }?.second
                ?: rows.firstOrNull()?.second
        } catch (e: Exception) { null }
    }
}
