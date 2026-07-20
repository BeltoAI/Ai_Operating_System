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

    // ── SlyOS document design system ─────────────────────────────────────────────────────────────
    // One accent colour used across Docs, Sheets and Slides so everything SlyOS produces looks like it
    // came from the same place, instead of the default unstyled white page.
    private const val ACCENT_R = 0.90; private const val ACCENT_G = 0.35; private const val ACCENT_B = 0.16
    private fun rgb(r: Double, g: Double, b: Double) = JSONObject().put("rgbColor",
        JSONObject().put("red", r).put("green", g).put("blue", b))
    private fun accent() = rgb(ACCENT_R, ACCENT_G, ACCENT_B)

    private data class Ln(val text: String, val kind: String)   // h1 | h2 | bullet | body

    /** Turn light markdown into typed lines, and give back the clean text that actually gets inserted. */
    private fun parseDoc(body: String): List<Ln> = body.replace("\r", "").split("\n").map { raw ->
        val t = raw.trim()
        when {
            t.startsWith("### ") -> Ln(t.removePrefix("### "), "h2")
            t.startsWith("## ") -> Ln(t.removePrefix("## "), "h2")
            t.startsWith("# ") -> Ln(t.removePrefix("# "), "h1")
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> Ln(t.drop(2).trim(), "bullet")
            else -> Ln(t, "body")
        }
    }.map { Ln(it.text.replace("**", "").replace("__", ""), it.kind) }   // strip inline md markers

    /**
     * Create a Google Doc titled [title] containing [body], PROPERLY TYPESET.
     * The old version dumped the whole body as one raw insertText — no headings, no bullets, no styling,
     * which is why every generated doc looked like a plain text file. This maps light markdown onto real
     * Google Docs named styles (TITLE / HEADING_1 / HEADING_2), real bullet lists, and accent-coloured
     * headings, so the result looks designed rather than pasted.
     */
    fun createDoc(ctx: Context, title: String, body: String): Result {
        val token = GoogleAuth.accessToken(ctx); if (token.isBlank()) return Result(false, error = "Google not connected.")
        val (c1, r1) = req("POST", "https://docs.googleapis.com/v1/documents", token, JSONObject().put("title", title).toString())
        if (c1 !in 200..299) { Log.e(TAG, "doc create $c1: ${r1.take(160)}"); return Result(false, error = err(r1, c1)) }
        val id = try { JSONObject(r1).optString("documentId") } catch (e: Exception) { "" }
        if (id.isBlank()) return Result(false, error = "no document id")

        if (body.isNotBlank()) {
            val lines = ArrayList<Ln>()
            lines.add(Ln(title, "title"))                       // a real title block at the top
            lines.addAll(parseDoc(body).filter { it.text.isNotBlank() || it.kind == "body" })
            val text = lines.joinToString("\n") { it.text } + "\n"

            val reqs = JSONArray()
            reqs.put(JSONObject().put("insertText",
                JSONObject().put("location", JSONObject().put("index", 1)).put("text", text)))

            // Index math: Docs is 1-based and each line costs text.length + 1 (the newline).
            var idx = 1
            val bulletRanges = ArrayList<Pair<Int, Int>>()
            lines.forEach { ln ->
                val start = idx; val end = idx + ln.text.length
                idx = end + 1
                if (ln.text.isBlank()) return@forEach
                val named = when (ln.kind) {
                    "title" -> "TITLE"; "h1" -> "HEADING_1"; "h2" -> "HEADING_2"; else -> "NORMAL_TEXT"
                }
                if (named != "NORMAL_TEXT") {
                    reqs.put(JSONObject().put("updateParagraphStyle", JSONObject()
                        .put("range", JSONObject().put("startIndex", start).put("endIndex", end + 1))
                        .put("paragraphStyle", JSONObject().put("namedStyleType", named)
                            .put("spaceAbove", JSONObject().put("magnitude", if (ln.kind == "title") 0 else 14).put("unit", "PT"))
                            .put("spaceBelow", JSONObject().put("magnitude", 6).put("unit", "PT")))
                        .put("fields", "namedStyleType,spaceAbove,spaceBelow")))
                    // Accent-colour the headings — the single touch that stops it looking like plain text.
                    reqs.put(JSONObject().put("updateTextStyle", JSONObject()
                        .put("range", JSONObject().put("startIndex", start).put("endIndex", end))
                        .put("textStyle", JSONObject().put("bold", true)
                            .put("foregroundColor", JSONObject().put("color", accent())))
                        .put("fields", "bold,foregroundColor")))
                }
                if (ln.kind == "bullet") bulletRanges.add(start to end + 1)
            }
            // Bullets last: createParagraphBullets inserts glyphs and would shift earlier indices.
            bulletRanges.asReversed().forEach { (s, e) ->
                reqs.put(JSONObject().put("createParagraphBullets", JSONObject()
                    .put("range", JSONObject().put("startIndex", s).put("endIndex", e))
                    .put("bulletPreset", "BULLET_DISC_CIRCLE_SQUARE")))
            }
            val (c2, r2) = req("POST", "https://docs.googleapis.com/v1/documents/$id:batchUpdate",
                token, JSONObject().put("requests", reqs).toString())
            if (c2 !in 200..299) Log.w(TAG, "doc style $c2: ${r2.take(200)}")   // text is in; styling is best-effort
        }
        return Result(true, url = "https://docs.google.com/document/d/$id/edit")
    }

    /** Create a Google Sheet titled [title] filled with [rows]. Returns its shareable URL. */
    fun createSheet(ctx: Context, title: String, rows: List<List<String>>): Result {
        val token = GoogleAuth.accessToken(ctx); if (token.isBlank()) return Result(false, error = "Google not connected.")
        val (c1, r1) = req("POST", "https://sheets.googleapis.com/v4/spreadsheets", token,
            JSONObject().put("properties", JSONObject().put("title", title)).toString())
        if (c1 !in 200..299) { Log.e(TAG, "sheet create $c1: ${r1.take(160)}"); return Result(false, error = err(r1, c1)) }
        val top = try { JSONObject(r1) } catch (e: Exception) { null }
        val id = top?.optString("spreadsheetId").orEmpty()
        if (id.isBlank()) return Result(false, error = "no spreadsheet id")
        val sheetId = top?.optJSONArray("sheets")?.optJSONObject(0)?.optJSONObject("properties")?.optInt("sheetId") ?: 0
        if (rows.isNotEmpty()) {
            val values = JSONArray(); rows.forEach { row -> values.put(JSONArray().apply { row.forEach { put(it) } }) }
            // USER_ENTERED (not RAW) so numbers/dates/currency land as real typed values you can sum,
            // instead of text that silently breaks every formula.
            req("PUT", "https://sheets.googleapis.com/v4/spreadsheets/$id/values/Sheet1!A1?valueInputOption=USER_ENTERED",
                token, JSONObject().put("values", values).toString())

            // Make it look like a designed tracker, not a CSV paste: branded frozen header, banded rows,
            // auto-fitted columns and a filter you can actually use.
            val cols = rows.maxOf { it.size }
            val reqs = JSONArray()
            reqs.put(JSONObject().put("repeatCell", JSONObject()
                .put("range", JSONObject().put("sheetId", sheetId).put("startRowIndex", 0).put("endRowIndex", 1)
                    .put("startColumnIndex", 0).put("endColumnIndex", cols))
                .put("cell", JSONObject().put("userEnteredFormat", JSONObject()
                    .put("backgroundColor", accent().getJSONObject("rgbColor"))
                    .put("verticalAlignment", "MIDDLE")
                    .put("textFormat", JSONObject()
                        .put("foregroundColor", rgb(1.0, 1.0, 1.0).getJSONObject("rgbColor"))
                        .put("bold", true).put("fontSize", 11))))
                .put("fields", "userEnteredFormat(backgroundColor,textFormat,verticalAlignment)")))
            reqs.put(JSONObject().put("updateSheetProperties", JSONObject()
                .put("properties", JSONObject().put("sheetId", sheetId)
                    .put("gridProperties", JSONObject().put("frozenRowCount", 1)))
                .put("fields", "gridProperties.frozenRowCount")))
            reqs.put(JSONObject().put("autoResizeDimensions", JSONObject()
                .put("dimensions", JSONObject().put("sheetId", sheetId).put("dimension", "COLUMNS")
                    .put("startIndex", 0).put("endIndex", cols))))
            if (rows.size > 1) reqs.put(JSONObject().put("addBanding", JSONObject()
                .put("bandedRange", JSONObject()
                    .put("range", JSONObject().put("sheetId", sheetId).put("startRowIndex", 1)
                        .put("endRowIndex", rows.size).put("startColumnIndex", 0).put("endColumnIndex", cols))
                    .put("rowProperties", JSONObject()
                        .put("firstBandColor", rgb(1.0, 1.0, 1.0).getJSONObject("rgbColor"))
                        .put("secondBandColor", rgb(0.98, 0.95, 0.93).getJSONObject("rgbColor"))))))
            reqs.put(JSONObject().put("setBasicFilter", JSONObject().put("filter", JSONObject()
                .put("range", JSONObject().put("sheetId", sheetId).put("startRowIndex", 0)
                    .put("endRowIndex", rows.size).put("startColumnIndex", 0).put("endColumnIndex", cols)))))
            val (c3, r3) = req("POST", "https://sheets.googleapis.com/v4/spreadsheets/$id:batchUpdate",
                token, JSONObject().put("requests", reqs).toString())
            if (c3 !in 200..299) Log.w(TAG, "sheet style $c3: ${r3.take(200)}")
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
        fun box(objId: String, pageId: String, x: Long, y: Long, w: Long, h: Long) = JSONObject().put("createShape", JSONObject()
            .put("objectId", objId).put("shapeType", "TEXT_BOX")
            .put("elementProperties", JSONObject().put("pageObjectId", pageId)
                .put("size", JSONObject().put("width", JSONObject().put("magnitude", w).put("unit", "EMU")).put("height", JSONObject().put("magnitude", h).put("unit", "EMU")))
                .put("transform", JSONObject().put("scaleX", 1).put("scaleY", 1).put("translateX", x).put("translateY", y).put("unit", "EMU"))))
        // Typography with real colour + weight, not just a size. This is most of what separates a deck
        // that looks designed from two default text boxes on white.
        fun style(objId: String, size: Int, bold: Boolean, color: JSONObject? = null, font: String = "Inter") =
            JSONObject().put("updateTextStyle", JSONObject()
                .put("objectId", objId)
                .put("style", JSONObject().put("bold", bold)
                    .put("fontFamily", font)
                    .put("fontSize", JSONObject().put("magnitude", size).put("unit", "PT"))
                    .apply { if (color != null) put("foregroundColor", JSONObject().put("opaqueColor", color)) })
                .put("textRange", JSONObject().put("type", "ALL"))
                .put("fields", "bold,fontFamily,fontSize" + (if (color != null) ",foregroundColor" else "")))
        // A solid shape used as the accent bar / background block.
        fun rect(objId: String, pageId: String, x: Long, y: Long, w: Long, h: Long) = JSONObject().put("createShape",
            JSONObject().put("objectId", objId).put("shapeType", "RECTANGLE")
                .put("elementProperties", JSONObject().put("pageObjectId", pageId)
                    .put("size", JSONObject().put("width", JSONObject().put("magnitude", w).put("unit", "EMU")).put("height", JSONObject().put("magnitude", h).put("unit", "EMU")))
                    .put("transform", JSONObject().put("scaleX", 1).put("scaleY", 1).put("translateX", x).put("translateY", y).put("unit", "EMU"))))
        fun fill(objId: String, color: JSONObject) = JSONObject().put("updateShapeProperties", JSONObject()
            .put("objectId", objId)
            .put("shapeProperties", JSONObject()
                .put("shapeBackgroundFill", JSONObject().put("solidFill", JSONObject().put("color", color)))
                .put("outline", JSONObject().put("propertyState", "NOT_RENDERED")))
            .put("fields", "shapeBackgroundFill.solidFill.color,outline"))
        fun darkBg(pageId: String) = JSONObject().put("updatePageProperties", JSONObject()
            .put("objectId", pageId)
            .put("pageProperties", JSONObject().put("pageBackgroundFill",
                JSONObject().put("solidFill", JSONObject().put("color", rgb(0.07, 0.07, 0.08)))))
            .put("fields", "pageBackgroundFill.solidFill.color"))

        val ink = rgb(0.98, 0.98, 0.98)      // near-white body text on the dark theme
        val muted = rgb(0.72, 0.72, 0.74)
        val requests = JSONArray()
        slides.forEachIndexed { i, (t, b) ->
            // Slides API requires object IDs 5-50 chars — short ids like "s0" are silently rejected.
            val sid = "slide_$i"; val tid = "title_$i"; val bid = "body_$i"
            val barId = "bar_$i"; val numId = "num_$i"
            requests.put(JSONObject().put("createSlide", JSONObject().put("objectId", sid)
                .put("slideLayoutReference", JSONObject().put("predefinedLayout", "BLANK"))))
            // Branded dark canvas + accent bar under the title — the deck's visual signature.
            requests.put(darkBg(sid))
            requests.put(rect(barId, sid, 600000, 1620000, 900000, 70000))
            requests.put(fill(barId, accent()))
            requests.put(box(tid, sid, 600000, 500000, 8000000, 1100000))
            if (t.isNotBlank()) {
                requests.put(JSONObject().put("insertText", JSONObject().put("objectId", tid).put("text", t).put("insertionIndex", 0)))
                requests.put(style(tid, 32, true, ink))
            }
            requests.put(box(bid, sid, 600000, 1950000, 8000000, 4200000))
            if (b.isNotBlank()) {
                requests.put(JSONObject().put("insertText", JSONObject().put("objectId", bid).put("text", b).put("insertionIndex", 0)))
                requests.put(style(bid, 15, false, ink))
                // Real bullets for lines that were written as bullets.
                if (b.lines().any { it.trimStart().startsWith("-") || it.trimStart().startsWith("•") })
                    requests.put(JSONObject().put("createParagraphBullets", JSONObject()
                        .put("objectId", bid).put("textRange", JSONObject().put("type", "ALL"))
                        .put("bulletPreset", "BULLET_DISC_CIRCLE_SQUARE")))
            }
            // Slide number, bottom-right — the small touch that reads as "produced".
            requests.put(box(numId, sid, 8300000, 4900000, 400000, 300000))
            requests.put(JSONObject().put("insertText", JSONObject().put("objectId", numId).put("text", "${i + 1}").put("insertionIndex", 0)))
            requests.put(style(numId, 10, false, muted))
        }
        if (firstSlideId.isNotBlank()) requests.put(JSONObject().put("deleteObject", JSONObject().put("objectId", firstSlideId)))
        val (c2, r2) = req("POST", "https://slides.googleapis.com/v1/presentations/$id:batchUpdate", token, JSONObject().put("requests", requests).toString())
        if (c2 !in 200..299) { Log.e(TAG, "slides batchUpdate $c2: ${r2.take(200)}"); return Result(true, url = "https://docs.google.com/presentation/d/$id/edit", error = "deck created but content failed: ${err(r2, c2)}") }
        return Result(true, url = "https://docs.google.com/presentation/d/$id/edit")
    }
}
