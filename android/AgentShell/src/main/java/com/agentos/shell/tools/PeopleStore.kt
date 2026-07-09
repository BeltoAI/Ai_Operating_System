package com.agentos.shell.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * People the user has taught SlyOS to recognize — a name, optional notes, and a reference face photo
 * saved on the device. The camera "Who's this?" flow matches a fresh shot against this roster via the
 * model's vision. Everything stays on the phone; photos live in filesDir/faces.
 */
object PeopleStore {
    data class Person(val id: Long, val name: String, val notes: String)

    private const val PREF = "slyos_faces"
    private const val KEY = "people"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun dir(ctx: Context): File = File(ctx.filesDir, "faces").apply { mkdirs() }
    fun photoFile(ctx: Context, id: Long): File = File(dir(ctx), "$id.jpg")

    fun list(ctx: Context): List<Person> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Person(o.getLong("id"), o.getString("name"), o.optString("notes"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, people: List<Person>) {
        val arr = JSONArray()
        people.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("notes", it.notes)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Enroll a person with a reference photo. Returns the new id, or -1 on failure. */
    fun add(ctx: Context, name: String, notes: String, photo: Bitmap): Long {
        val n = name.trim(); if (n.isBlank()) return -1
        val id = System.currentTimeMillis()
        return try {
            photoFile(ctx, id).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            save(ctx, list(ctx) + Person(id, n, notes.trim()))
            id
        } catch (e: Exception) { -1 }
    }

    fun update(ctx: Context, id: Long, name: String, notes: String) =
        save(ctx, list(ctx).map { if (it.id == id) it.copy(name = name.trim(), notes = notes.trim()) else it })

    fun remove(ctx: Context, id: Long) {
        try { photoFile(ctx, id).delete() } catch (e: Exception) {}
        save(ctx, list(ctx).filterNot { it.id == id })
    }

    fun photoBitmap(ctx: Context, id: Long): Bitmap? = try {
        val f = photoFile(ctx, id); if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (e: Exception) { null }

    /** Base64-encoded reference photo (downscaled) for the vision match. */
    fun photoB64(ctx: Context, id: Long): String? = photoBitmap(ctx, id)?.let { ImageUtil.encodeBitmap(it) }
}
