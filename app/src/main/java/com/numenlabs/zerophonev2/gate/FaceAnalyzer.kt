package com.numenlabs.zerophonev2.gate

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ImageAnalysis.Analyzer over the bundled on-device ML Kit face detector:
 * emits `true` on frames where at least one face with both eyes open
 * (probability >= [EYE_OPEN_THRESHOLD]) is visible. Throttled to ~5 fps and
 * serialised so overlapping inferences are dropped, not queued.
 */
class FaceAnalyzer(
    private val onResult: (Boolean) -> Unit,
) : ImageAnalysis.Analyzer {
    private val detector =
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
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
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        detector
            .process(input)
            .addOnSuccessListener { faces ->
                val looking =
                    faces.any { face ->
                        val left = face.leftEyeOpenProbability
                        val right = face.rightEyeOpenProbability
                        left != null && right != null && left >= EYE_OPEN_THRESHOLD && right >= EYE_OPEN_THRESHOLD
                    }
                onResult(looking)
            }
            .addOnFailureListener {
                onResult(false)
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
