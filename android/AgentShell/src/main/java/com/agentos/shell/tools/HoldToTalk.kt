package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Dictation that waits for you to finish thinking.
 *
 * Home used `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` — the system dialog — which owns its own
 * silence timeout and submits the moment it decides you have stopped. So pausing mid-sentence to
 * think ended the sentence, the half-formed request went off to the model, and the only way back
 * was to start again. That is the most irritating thing an assistant can do, because it punishes
 * exactly the moment someone is trying to be precise.
 *
 * An in-app recogniser can be held open instead. While the finger is down, silence means nothing;
 * on release, whatever was said is submitted. It is the voice-note gesture, so it needs no
 * explaining.
 *
 * The recogniser still stops on its own after a while — Android offers no way to disable that — so
 * a long hold is stitched from restarts, with finished text banked between them.
 */
class HoldToTalk(private val ctx: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var held = false
    /** Text from earlier segments of the same hold; each restart's result replaces only its own. */
    private var banked = ""

    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onError: (String) -> Unit = {}

    val isHeld: Boolean get() = held

    /** Finger down. Listen until told otherwise. */
    fun start() {
        if (held) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("No voice input available on this device."); return
        }
        held = true
        banked = ""
        listen()
    }

    /** Finger up. Submit whatever was said. */
    fun stop() {
        if (!held) return
        held = false
        try { recognizer?.stopListening() } catch (e: Exception) {}
        // The final callback often never arrives when a hold is released during silence.
        // Submitting what was banked is better than losing the sentence.
        val text = banked.trim()
        release()
        if (text.isNotEmpty()) onFinal(text)
    }

    /** Slid away — throw it away, as a voice note does. */
    fun cancel() {
        held = false
        banked = ""
        try { recognizer?.cancel() } catch (e: Exception) {}
        release()
    }

    private fun release() {
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
    }

    private fun listen() {
        release()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx) ?: run {
            onError("Voice input couldn't start."); held = false; return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(res: Bundle?) {
                val said = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (said.isNotBlank()) onPartial((banked + " " + said).trim())
            }

            override fun onResults(res: Bundle?) {
                val said = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (said.isNotBlank()) banked = (banked + " " + said).trim()
                onPartial(banked)
                // Still held: the recogniser gave up on its own, not the user. Start another segment
                // so a pause to think does not end the sentence.
                if (held) listen() else if (banked.isNotEmpty()) onFinal(banked)
            }

            override fun onError(code: Int) {
                // A silence timeout while held is expected — that IS someone thinking. Anything else
                // is only worth reporting when it happens on the very first segment.
                val silence = code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    code == SpeechRecognizer.ERROR_NO_MATCH
                when {
                    held -> { if (!silence) Log.w("SlyOS", "hold-to-talk error $code"); listen() }
                    banked.isEmpty() && !silence -> onError("Voice input failed ($code).")
                }
            }

            override fun onEvent(t: Int, b: Bundle?) {}
        })

        try {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Generous, though Android treats these as hints and routinely ignores them — which
                // is exactly why the restart loop exists rather than trusting the timeouts.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000))
        } catch (e: Exception) {
            held = false
            onError("Voice input couldn't start.")
        }
    }
}
