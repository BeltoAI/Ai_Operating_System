package com.agentos.shell.tools

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device photo/frame understanding — runs entirely on the phone with ML Kit, ZERO network, ZERO API cost:
 *   • content labels ("person, beach, dog")
 *   • face count + how the face fills the frame → a coarse "kind" (selfie/portrait/fullbody/group/person/scene)
 *   • pose (BlazePose) to confirm a FULL body (ankles in frame)
 *   • OCR — the text inside the image (screenshots, whiteboards, receipts) so it's searchable in the brain
 *   • barcodes / QR values
 * This lets us index a whole gallery (and video frames) for free and answer rich queries from a local DB.
 * All calls block (Tasks.await) — run on a background thread only.
 */
object PhotoVision {
    private const val TAG = "SlyOS-PhotoVision"

    data class Result(
        val labels: List<String>, val faces: Int, val biggestFaceRatio: Float, val kind: String,
        val text: String = "", val barcodes: List<String> = emptyList()
    )

    fun analyze(ctx: Context, uri: Uri): Result? {
        val image = try { InputImage.fromFilePath(ctx, uri) } catch (e: Exception) { return null }
        return analyzeImage(image, image.height.toFloat())
    }

    fun analyzeBitmap(bitmap: Bitmap): Result? {
        val image = try { InputImage.fromBitmap(bitmap, 0) } catch (e: Exception) { return null }
        return analyzeImage(image, bitmap.height.toFloat())
    }

    private fun analyzeImage(image: InputImage, heightPx: Float): Result? {
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val scanner = BarcodeScanning.getClient()
        return try {
            val labels = try { Tasks.await(labeler.process(image)).map { it.text.lowercase() } } catch (e: Exception) { emptyList() }
            val faces = try { Tasks.await(detector.process(image)) } catch (e: Exception) { emptyList() }
            val text = try { Tasks.await(recognizer.process(image)).text.trim() } catch (e: Exception) { "" }
            val barcodes = try { Tasks.await(scanner.process(image)).mapNotNull { it.rawValue } } catch (e: Exception) { emptyList() }
            val h = heightPx.coerceAtLeast(1f)
            val biggest = faces.maxOfOrNull { it.boundingBox.height() / h } ?: 0f
            val hasPerson = labels.any { it.contains("person") } || faces.isNotEmpty()
            var kind = when {
                faces.size >= 2 -> "group"
                faces.size == 1 && biggest >= 0.30f -> "selfie"
                faces.size == 1 && biggest >= 0.14f -> "portrait"
                faces.size == 1 -> "fullbody"
                hasPerson -> "person"
                else -> "scene"
            }
            if (faces.isNotEmpty() && (kind == "fullbody" || kind == "person" || kind == "portrait")) {
                val full = try {
                    val pd = PoseDetection.getClient(
                        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build())
                    try {
                        val pose = Tasks.await(pd.process(image))
                        val la = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)?.inFrameLikelihood ?: 0f
                        val ra = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)?.inFrameLikelihood ?: 0f
                        la > 0.5f || ra > 0.5f
                    } finally { try { pd.close() } catch (e: Exception) {} }
                } catch (e: Exception) { false }
                kind = if (full) "fullbody" else if (kind == "fullbody") "person" else kind
            }
            Result(labels, faces.size, biggest, kind, text.take(2000), barcodes)
        } catch (e: Exception) {
            Log.w(TAG, "analyze: ${e.message}"); null
        } finally {
            try { labeler.close() } catch (e: Exception) {}
            try { detector.close() } catch (e: Exception) {}
            try { recognizer.close() } catch (e: Exception) {}
            try { scanner.close() } catch (e: Exception) {}
        }
    }
}
