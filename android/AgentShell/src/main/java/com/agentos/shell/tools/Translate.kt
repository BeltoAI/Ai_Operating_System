package com.agentos.shell.tools

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * On-device translation + language detection (ML Kit) — free, offline, private. The first use of a language
 * pair downloads a small model (~30MB) once; after that it's instant and works with no network. Blocking
 * calls — run off the main thread.
 */
object Translate {
    private const val TAG = "SlyOS-Translate"

    /** BCP-47 language code of [text] ("en", "es", "de"…) or "und" if unknown. */
    fun detect(text: String): String = try {
        if (text.isBlank()) "und" else Tasks.await(LanguageIdentification.getClient().identifyLanguage(text))
    } catch (e: Exception) { "und" }

    /** Translate [text] into [targetTag] (default English). Returns the translation, or the original on failure. */
    fun translate(text: String, targetTag: String = "en"): String {
        if (text.isBlank()) return text
        val srcTag = detect(text)
        val src = TranslateLanguage.fromLanguageTag(if (srcTag == "und") "en" else srcTag) ?: TranslateLanguage.ENGLISH
        val tgt = TranslateLanguage.fromLanguageTag(targetTag) ?: TranslateLanguage.ENGLISH
        if (src == tgt) return text
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        return try {
            Tasks.await(translator.downloadModelIfNeeded())
            Tasks.await(translator.translate(text))
        } catch (e: Exception) {
            Log.w(TAG, "translate: ${e.message}"); text
        } finally { try { translator.close() } catch (e: Exception) {} }
    }
}
