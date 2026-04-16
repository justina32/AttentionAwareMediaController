package com.example.attentionawaremediacontrolleraiver

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Runs ML Kit face detection on every CameraX ImageProxy frame.
 * Calls [onResult] with (facePresent, latencyMs) after each detection.
 *
 * Important: ImageProxy is closed inside the ML Kit completion listener,
 * not before — closing it early would corrupt the image data.
 */
class FaceAnalyzer(
    private val onResult: (facePresent: Boolean, latencyMs: Long) -> Unit
) : ImageAnalysis.Analyzer {

    // Fastest config: no landmarks, no contours, no classification
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f) // ignore tiny faces far from camera
            .build()
    )

    override fun analyze(imageProxy: ImageProxy) {
        val startMs = System.currentTimeMillis()

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                val latencyMs = System.currentTimeMillis() - startMs
                onResult(faces.isNotEmpty(), latencyMs)
            }
            .addOnFailureListener {
                // On failure treat as no face — safer than stalling the player
                onResult(false, 0L)
            }
            .addOnCompleteListener {
                // Must close AFTER the task, not before — ML Kit reads the buffer lazily
                imageProxy.close()
            }
    }
}
