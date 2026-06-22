package com.agentos.shell.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Publishes a paper to Zenodo (https://zenodo.org) via its deposition REST API.
 * Flow: create deposition -> upload the PDF to its bucket -> set metadata -> (optionally) publish.
 * The token is the user's personal access token (scopes deposit:write + deposit:actions); it lives
 * only on-device in MemoryStore and is sent as a Bearer header — never logged or stored in the repo.
 */
object ZenodoClient {
    private const val BASE = "https://zenodo.org/api"

    data class Result(val ok: Boolean, val doi: String = "", val url: String = "", val error: String = "", val depId: Long = 0L)

    /**
     * publicationType: Zenodo subtype, e.g. "preprint", "report".
     * existingDepositId: if > 0, publish a NEW VERSION of that record (shared concept-DOI) instead of a
     * brand-new record. Returns the new deposition id in Result.depId so the caller can remember it.
     */
    fun publish(
        token: String, pdf: File, title: String, author: String, affiliation: String,
        description: String, publicationType: String, keywords: List<String>, doPublish: Boolean,
        existingDepositId: Long = 0L
    ): Result {
        if (token.isBlank()) return Result(false, error = "No Zenodo token set.")
        if (!pdf.exists() || pdf.length() == 0L) return Result(false, error = "PDF wasn't built.")
        try {
            val id: Long; val bucket: String
            if (existingDepositId > 0L) {
                // New version of an existing record: start a draft, then clear its inherited files.
                val (nid, nbucket, err) = startNewVersion(token, existingDepositId)
                if (err != null) return Result(false, error = err)
                id = nid; bucket = nbucket
            } else {
                // Brand-new record.
                val (c1, b1) = json("POST", "$BASE/deposit/depositions", token, "{}")
                if (c1 !in 200..299) return Result(false, error = "create failed ($c1): ${b1.take(300)}")
                val dep = JSONObject(b1)
                id = dep.getLong("id")
                bucket = dep.optJSONObject("links")?.optString("bucket") ?: ""
            }

            // 2) Upload the PDF (new bucket API if present, else legacy multipart-less PUT).
            val fname = sanitize(title).take(80).ifBlank { "paper" } + ".pdf"
            val up = if (bucket.isNotBlank()) putFile("$bucket/$fname", token, pdf)
                     else putFileLegacy(id, token, pdf, fname)
            if (up.first !in 200..299) return Result(false, error = "upload failed (${up.first}): ${up.second.take(200)}")

            // 3) Rich metadata for discoverability: open access, license, keywords, language, date.
            val creator = JSONObject().put("name", author.ifBlank { "Anonymous" })
            if (affiliation.isNotBlank()) creator.put("affiliation", affiliation)
            val kw = JSONArray(); keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12).forEach { kw.put(it) }
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val m = JSONObject()
                .put("title", title.ifBlank { "Untitled" })
                .put("upload_type", "publication")
                .put("publication_type", publicationType)
                .put("description", (description.ifBlank { title }).take(8000))
                .put("creators", JSONArray().put(creator))
                .put("access_right", "open")
                .put("license", "cc-by-4.0")
                .put("language", "eng")
                .put("publication_date", today)
            if (kw.length() > 0) m.put("keywords", kw)
            val meta = JSONObject().put("metadata", m)
            val (c3, b3) = json("PUT", "$BASE/deposit/depositions/$id", token, meta.toString())
            if (c3 !in 200..299) return Result(false, error = "metadata failed ($c3): ${b3.take(200)}")

            if (!doPublish) return Result(true, doi = "draft", url = "https://zenodo.org/uploads/$id", depId = id)

            // 4) Publish -> mints (or versions) the DOI.
            val (c4, b4) = json("POST", "$BASE/deposit/depositions/$id/actions/publish", token, "")
            if (c4 !in 200..299) return Result(false, error = "publish failed ($c4): ${b4.take(200)}", depId = id)
            val pub = JSONObject(b4)
            val doi = pub.optString("doi").ifBlank { pub.optJSONObject("metadata")?.optString("doi") ?: "" }
            val rec = pub.optJSONObject("links")?.optString("record_html")
                ?: (if (doi.isNotBlank()) "https://doi.org/$doi" else "https://zenodo.org/records/$id")
            return Result(true, doi = doi, url = rec, depId = id)
        } catch (e: Exception) {
            return Result(false, error = e.message ?: "network error")
        }
    }

    /**
     * Start a new-version draft of a published record: POST actions/newversion → follow latest_draft →
     * delete the files it inherited (we replace the PDF). Returns (newId, bucketUrl, errorOrNull).
     */
    private fun startNewVersion(token: String, existingId: Long): Triple<Long, String, String?> {
        val (c, b) = json("POST", "$BASE/deposit/depositions/$existingId/actions/newversion", token, "")
        if (c !in 200..299) return Triple(0L, "", "new-version failed ($c): ${b.take(200)}")
        val draftUrl = JSONObject(b).optJSONObject("links")?.optString("latest_draft") ?: ""
        if (draftUrl.isBlank()) return Triple(0L, "", "no draft link returned")
        val (cg, bg) = json("GET", draftUrl, token, "")
        if (cg !in 200..299) return Triple(0L, "", "draft fetch failed ($cg)")
        val draft = JSONObject(bg)
        val newId = draft.optLong("id")
        val bucket = draft.optJSONObject("links")?.optString("bucket") ?: ""
        // Remove inherited files so the version contains only the freshly built PDF.
        draft.optJSONArray("files")?.let { files ->
            for (i in 0 until files.length()) {
                val fid = files.getJSONObject(i).optString("id")
                if (fid.isNotBlank()) json("DELETE", "$BASE/deposit/depositions/$newId/files/$fid", token, "")
            }
        }
        return Triple(newId, bucket, null)
    }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9 _-]"), "").replace(Regex("\\s+"), "_")

    private fun json(method: String, urlStr: String, token: String, body: String): Pair<Int, String> {
        val c = (URL(urlStr).openConnection() as HttpURLConnection)
        c.requestMethod = method
        c.connectTimeout = 30000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        if (method == "POST" || method == "PUT") {
            c.doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.outputStream.use { it.write(bytes) }
        }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to txt
    }

    /** New files API: PUT raw bytes to {bucket}/{filename}. */
    private fun putFile(urlStr: String, token: String, f: File): Pair<Int, String> {
        val c = (URL(urlStr).openConnection() as HttpURLConnection)
        c.requestMethod = "PUT"
        c.connectTimeout = 30000; c.readTimeout = 180000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.doOutput = true
        c.setFixedLengthStreamingMode(f.length())
        f.inputStream().use { input -> c.outputStream.use { out -> input.copyTo(out) } }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to txt
    }

    /** Legacy files API fallback (multipart). Used only if the deposition has no bucket link. */
    private fun putFileLegacy(id: Long, token: String, f: File, fname: String): Pair<Int, String> {
        val boundary = "----slyos${System.currentTimeMillis()}"
        val c = (URL("$BASE/deposit/depositions/$id/files").openConnection() as HttpURLConnection)
        c.requestMethod = "POST"
        c.connectTimeout = 30000; c.readTimeout = 180000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.doOutput = true
        c.outputStream.use { out ->
            val pre = ("--$boundary\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n$fname\r\n" +
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fname\"\r\n" +
                "Content-Type: application/pdf\r\n\r\n").toByteArray()
            out.write(pre)
            f.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to txt
    }
}
