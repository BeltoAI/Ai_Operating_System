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
