package com.agentos.shell.tools

import android.content.Context
import java.io.File

/**
 * The single "speak in the owner's voice" entry point every surface uses (Home, hold-brain/Converse, camera,
 * phone calls). Centralizes the voice priority so there's ONE place to reason about it:
 *
 *   1) LocalVoice  — free, on-device, private clone (preferred once the model + sample are ready)
 *   2) ElevenLabs  — the user's own paid cloned voice (fallback if they set a key but local isn't ready)
 *   3) system TTS  — the generic device voice (handled by each caller when this returns null)
 *
 * [available] tells a caller whether a cloned voice exists at all; [synthesize] returns a playable audio file
 * (or null → the caller uses system TTS). Adding the local path here means no surface had to change its logic.
 */
object VoiceOut {
    /** A cloned voice (local or ElevenLabs) is available. */
    fun available(ctx: Context): Boolean =
        try { LocalVoice.available(ctx) } catch (t: Throwable) { false } || ElevenLabs.available(ctx)

    /** The low-latency STREAMING local voice is ready — speech starts as it's generated (no file, no wait). */
    fun canStream(ctx: Context): Boolean = try { LocalVoice.available(ctx) } catch (t: Throwable) { false }

    /**
     * Speak [text] in the owner's cloned voice, streaming to the speaker (starts in ~1s). Blocks until done,
     * so call it off the main thread. Returns true if it played; false → caller falls back (ElevenLabs/system).
     */
    fun speakStreaming(ctx: Context, text: String): Boolean =
        try { LocalVoice.speakStreaming(ctx, text) } catch (t: Throwable) { false }

    /** Stop any in-progress cloned-voice playback (e.g. the user starts talking again). */
    fun stop() { try { LocalVoice.stop() } catch (t: Throwable) {} }

    /** Speak [text] in the owner's voice → a playable audio file, or null to fall back to system TTS. */
    fun synthesize(ctx: Context, text: String): File? {
        try { LocalVoice.synthesize(ctx, text)?.let { return it } } catch (t: Throwable) {}
        return ElevenLabs.synthesize(ctx, text)
    }
}
