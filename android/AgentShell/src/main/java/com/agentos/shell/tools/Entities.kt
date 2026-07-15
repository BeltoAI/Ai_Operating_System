package com.agentos.shell.tools

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions

/**
 * On-device entity extraction (ML Kit) — pulls the useful bits out of any text (emails, phone numbers,
 * addresses, dates, URLs, tracking numbers) with zero network and zero API cost. Used to auto-build the CRM
 * from your inbox and to surface actionable details from messages. Blocking — run off the main thread.
 */
object Entities {
    private const val TAG = "SlyOS-Entities"

    data class Found(
        val emails: List<String> = emptyList(),
        val phones: List<String> = emptyList(),
        val addresses: List<String> = emptyList(),
        val dates: List<String> = emptyList(),
        val urls: List<String> = emptyList(),
        val tracking: List<String> = emptyList()
    )

    fun extract(text: String): Found {
        if (text.isBlank()) return Found()
        val extractor = EntityExtraction.getClient(
            EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build())
        return try {
            Tasks.await(extractor.downloadModelIfNeeded())
            val annotations = Tasks.await(extractor.annotate(EntityExtractionParams.Builder(text).build()))
            val emails = ArrayList<String>(); val phones = ArrayList<String>(); val addrs = ArrayList<String>()
            val dates = ArrayList<String>(); val urls = ArrayList<String>(); val tracking = ArrayList<String>()
            for (a in annotations) {
                val span = a.annotatedText
                for (e in a.entities) when (e.type) {
                    Entity.TYPE_EMAIL -> emails.add(span)
                    Entity.TYPE_PHONE -> phones.add(span)
                    Entity.TYPE_ADDRESS -> addrs.add(span)
                    Entity.TYPE_DATE_TIME -> dates.add(span)
                    Entity.TYPE_URL -> urls.add(span)
                    Entity.TYPE_TRACKING_NUMBER -> tracking.add(span)
                }
            }
            Found(emails.distinct(), phones.distinct(), addrs.distinct(), dates.distinct(), urls.distinct(), tracking.distinct())
        } catch (e: Exception) {
            Log.w(TAG, "extract: ${e.message}"); Found()
        } finally { try { extractor.close() } catch (e: Exception) {} }
    }
}
