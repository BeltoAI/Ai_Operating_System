package com.agentos.shell.tools

import android.content.Context
import java.io.File

/**
 * Stores the user's voice-clone sample + a read-aloud training script. This is the on-ramp for on-device
 * cloning (Chatterbox / NeuTTS / Qwen3-TTS): a 15–20 s clean recording of the owner's voice is all a
 * zero-shot cloner needs. The sample lives ONLY on the device (app-private storage) — never uploaded.
 */
object VoiceSampleStore {
    /** The saved clone sample (app-private). */
    fun sampleFile(ctx: Context): File = File(ctx.filesDir, "voice_clone_sample.m4a")

    fun hasSample(ctx: Context): Boolean = sampleFile(ctx).let { it.exists() && it.length() > 2000 }

    fun clear(ctx: Context) { try { sampleFile(ctx).delete() } catch (e: Exception) {} }

    fun recordedAt(ctx: Context): Long = sampleFile(ctx).let { if (it.exists()) it.lastModified() else 0L }

    /**
     * A ~20-second script covering a broad phoneme range — reading it once gives a clean, expressive
     * clone. Kept warm and natural so the recording sounds like a real answered call, not a robot.
     */
    val TRAINING_SCRIPT: String =
        "Hey, thanks for calling — I can't get to the phone this second, but go ahead and I'll catch " +
        "everything. I'm all ears, so tell me what's going on and what you need from me. If it's " +
        "urgent, just say so and I'll make sure it jumps the queue. Otherwise, take your time — quick " +
        "question, big favor, good news, whatever it is, I've got you. Talk to me."
}
