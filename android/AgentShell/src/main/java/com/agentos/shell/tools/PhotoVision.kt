package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/**
 * On-device photo understanding — runs entirely on the phone with ML Kit, ZERO network and ZERO API cost.
 * For each image it returns content labels ("person, beach, dog") and a coarse "kind" derived from how the
 * face(s) fill the frame: a big face = selfie/portrait, a small face in a tall frame = a full-body shot, two+
 * faces = a group. That lets us index a whole gallery for free and answer "a full-body photo of me" from a
 * local DB query, only sending a tiny shortlist to the paid vision model (for the final "is it me?").
 *
 * All calls are blocking (Tasks.await) — run on a background thread only.
 */
object PhotoVision {
    private const val TAG = "SlyOS-PhotoVision"

    data class Result(val labels: List<String>, val faces: Int, val biggestFaceRatio: Float, val kind: String)

    fun analyze(ctx: Context, uri: Uri): Result? {
        val image = try { InputImage.fromFilePath(ctx, uri) } catch (e: Exception) { return null }
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
        return try {
            val labels = try { Tasks.await(labeler.process(image)).map { it.text.lowercase() } } catch (e: Exception) { emptyList() }
            val faces = try { Tasks.await(detector.process(image)) } catch (e: Exception) { emptyList() }
            val h = image.height.toFloat().coerceAtLeast(1f)
            val biggest = faces.maxOfOrNull { it.boundingBox.height() / h } ?: 0f
            val hasPerson = labels.any { it.contains("person") } || faces.isNotEmpty()
            val kind = when {
                faces.size >= 2 -> "group"
                faces.size == 1 && biggest >= 0.30f -> "selfie"
                faces.size == 1 && biggest >= 0.14f -> "portrait"
                faces.size == 1 -> "fullbody"          // one person, small in the frame → whole body shows
                hasPerson -> "person"
                else -> "scene"
            }
            Result(labels, faces.size, biggest, kind)
        } catch (e: Exception) {
            Log.w(TAG, "analyze: ${e.message}"); null
        } finally {
            try { labeler.close() } catch (e: Exception) {}
            try { detector.close() } catch (e: Exception) {}
        }
    }
}
