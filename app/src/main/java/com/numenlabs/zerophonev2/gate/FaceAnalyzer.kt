package com.numenlabs.zerophonev2.gate

import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable frame payload for the UI overlay — no references to the closed ImageProxy. */
data class FaceUiData(
    val faceFound: Boolean,
    val eyesOpenEnough: Boolean,
    val boundingBox: Rect?,
    /** Face contours (points in IMAGE coordinates) for the on-screen "графы". */
    val contours: List<List<PointF>>,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
)

/**
 * ImageAnalysis.Analyzer over the bundled on-device ML Kit face detector.
 * Emits per-frame [FaceUiData]: "a face with both eyes open" drives the
 * attention hysteresis; bounding box + contours drive the live overlay.
 * Throttled to ~5 fps and serialised so overlapping inferences are dropped.
 */
class FaceAnalyzer(
    private val onResult: (FaceUiData) -> Unit,
) : ImageAnalysis.Analyzer {
    private val detector =
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                // Contours power the overlay; ML Kit then tracks only the most
                // prominent face — which is all the gate needs (we never used
                // tracking ids anyway).
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.3f)
                .build(),
        )

    private val inFlight = AtomicBoolean(false)
    private var lastProcessedAt = 0L

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessedAt < MIN_FRAME_INTERVAL_MILLIS || !inFlight.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastProcessedAt = now
        val width = image.width
        val height = image.height
        val rotation = image.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        detector
            .process(input)
            .addOnSuccessListener { faces ->
                val best = faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }
                val left = best?.leftEyeOpenProbability
                val right = best?.rightEyeOpenProbability
                val eyesOpen = left != null && right != null && left >= EYE_OPEN_THRESHOLD && right >= EYE_OPEN_THRESHOLD
                onResult(
                    FaceUiData(
                        faceFound = best != null,
                        eyesOpenEnough = eyesOpen,
                        boundingBox = best?.boundingBox,
                        contours =
                            best?.allContours?.map { contour ->
                                contour.points.map { PointF(it.x, it.y) }
                            } ?: emptyList(),
                        imageWidth = width,
                        imageHeight = height,
                        rotationDegrees = rotation,
                    ),
                )
            }
            .addOnFailureListener {
                onResult(
                    FaceUiData(
                        faceFound = false,
                        eyesOpenEnough = false,
                        boundingBox = null,
                        contours = emptyList(),
                        imageWidth = width,
                        imageHeight = height,
                        rotationDegrees = rotation,
                    ),
                )
            }
            .addOnCompleteListener {
                inFlight.set(false)
                image.close()
            }
    }

    fun close() {
        detector.close()
    }

    private companion object {
        const val EYE_OPEN_THRESHOLD = 0.4
        const val MIN_FRAME_INTERVAL_MILLIS = 200L
    }
}
