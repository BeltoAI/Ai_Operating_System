package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Creates REAL Google Workspace documents from a prompt — a Google Doc or a Sheet you can open on any
 * device — using the user's own OAuth token. This is the "write me a doc / make me a sheet" capability
 * toward SlyOS as a full Android replacement.
 */
object GoogleWorkspace {
    private const val TAG = "SlyOS"
    data class Result(val ok: Boolean, val url: String = "", val error: String = "")

    private fun req(method: String, url: String, token: String, json: String?): Pair<Int, String> {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = 15000; readTimeout = 25000
                setRequestProperty("Authorization", "Bearer $token")
                if (json != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            }
            if (json != null) c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } catch (e: Exception) { -1 to (e.message ?: "network error") }
    }

    private fun err(raw: String, code: Int): String =
        try { JSONObject(raw).optJSONObject("error")?.optString("message") ?: "" } catch (e: Exception) { "" }
            .ifBlank { "error $code" } + (if (code == 403) " — reconnect Google (Disconnect→Connect) to grant Docs/Sheets." else "")

    /** Create a Google Doc titled [title] containing [body]. Returns its shareable URL. */
    fun createDoc(ctx: Context, title: String, body: String): Result {
        val token = GoogleAuth.accessToken(ctx); if (token.isBlank()) return Result(false, error = "Google not connected.")
        val (c1, r1) = req("POST", "https://docs.googleapis.com/v1/documents", token, JSONObject().put("title", title).toString())
        if (c1 !in 200..299) { Log.e(TAG, "doc create $c1: ${r1.take(160)}"); return Result(false, error = err(r1, c1)) }
        val id = try { JSONObject(r1).optString("documentId") } catch (e: Exception) { "" }
        if (id.isBlank()) return Result(false, error = "no document id")
        if (body.isNotBlank()) {
            val reqs = JSONArray().put(JSONObject().put("insertText",
                JSONObject().put("location", JSONObject().put("index", 1)).put("text", body)))
            req("POST", "https://docs.googleapis.com/v1/documents/$id:batchUpdate", token, JSONObject().put("requests", reqs).toString())
        }
        return Result(true, url = "https://docs.google.com/document/d/$id/edit")
    }

    /** Create a Google Sheet titled [title] filled with [rows]. Returns its shareable URL. */
    fun createSheet(ctx: Context, title: String, rows: List<List<String>>): Result {
        val token = GoogleAuth.accessToken(ctx); if (token.isBlank()) return Result(false, error = "Google not connected.")
        val (c1, r1) = req("POST", "https://sheets.googleapis.com/v4/spreadsheets", token,
            JSONObject().put("properties", JSONObject().put("title", title)).toString())
        if (c1 !in 200..299) { Log.e(TAG, "sheet create $c1: ${r1.take(160)}"); return Result(false, error = err(r1, c1)) }
        val id = try { JSONObject(r1).optString("spreadsheetId") } catch (e: Exception) { "" }
        if (id.isBlank()) return Result(false, error = "no spreadsheet id")
        if (rows.isNotEmpty()) {
            val values = JSONArray(); rows.forEach { row -> values.put(JSONArray().apply { row.forEach { put(it) } }) }
            req("PUT", "https://sheets.googleapis.com/v4/spreadsheets/$id/values/Sheet1!A1?valueInputOption=RAW",
                token, JSONObject().put("values", values).toString())
        }
        return Result(true, url = "https://docs.google.com/spreadsheets/d/$id/edit")
    }

    /** Create a Google Slides deck. [slides] = list of (slideTitle, slideBody). Returns its URL. */
    fun createSlides(ctx: Context, title: String, slides: List<Pair<String, String>>): Result {
        val token = GoogleAuth.accessToken(ctx); if (token.isBlank()) return Result(false, error = "Google not connected.")
        val (c1, r1) = req("POST", "https://slides.googleapis.com/v1/presentations", token, JSONObject().put("title", title).toString())
        if (c1 !in 200..299) { Log.e(TAG, "slides create $c1: ${r1.take(160)}"); return Result(false, error = err(r1, c1)) }
        val o = try { JSONObject(r1) } catch (e: Exception) { null } ?: return Result(false, error = "bad response")
        val id = o.optString("presentationId"); if (id.isBlank()) return Result(false, error = "no presentation id")
        val firstSlideId = o.optJSONArray("slides")?.optJSONObject(0)?.optString("objectId").orEmpty()
        val requests = JSONArray()
        slides.forEachIndexed { i, (t, b) ->
            val sid = "s$i"; val tid = "t$i"; val bid = "b$i"
            requests.put(JSONObject().put("createSlide", JSONObject()
                .put("objectId", sid)
                .put("slideLayoutReference", JSONObject().put("predefinedLayout", "TITLE_AND_BODY"))
                .put("placeholderIdMappings", JSONArray()
                    .put(JSONObject().put("layoutPlaceholder", JSONObject().put("type", "TITLE")).put("objectId", tid))
                    .put(JSONObject().put("layoutPlaceholder", JSONObject().put("type", "BODY")).put("objectId", bid)))))
            if (t.isNotBlank()) requests.put(JSONObject().put("insertText", JSONObject().put("objectId", tid).put("text", t).put("insertionIndex", 0)))
            if (b.isNotBlank()) requests.put(JSONObject().put("insertText", JSONObject().put("objectId", bid).put("text", b).put("insertionIndex", 0)))
        }
        // Remove the default blank first slide.
        if (firstSlideId.isNotBlank()) requests.put(JSONObject().put("deleteObject", JSONObject().put("objectId", firstSlideId)))
        val (c2, r2) = req("POST", "https://slides.googleapis.com/v1/presentations/$id:batchUpdate", token, JSONObject().put("requests", requests).toString())
        if (c2 !in 200..299) Log.w(TAG, "slides batchUpdate $c2: ${r2.take(160)}")
        return Result(true, url = "https://docs.google.com/presentation/d/$id/edit")
    }
}
