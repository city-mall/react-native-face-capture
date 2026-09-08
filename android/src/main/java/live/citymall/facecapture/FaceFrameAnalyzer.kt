package live.citymall.facecapture

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One face, normalised to the upright frame (0..1). X is already mirrored for
 * the front lens. Head pose is ML Kit's Euler angles in degrees, zero when the
 * face looks straight into the lens: yaw = turned left/right, pitch = nodded
 * up/down, roll = tilted toward a shoulder.
 */
data class NormalizedFace(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}

data class FrameResult(
    val faces: List<NormalizedFace>,
    /** Mean of the Y plane, 0..255 — a cheap proxy for scene brightness. */
    val meanLuma: Int,
    /** Upright frame width / height, so the screen can reproduce PreviewView's FILL_CENTER mapping. */
    val frameAspect: Float,
)

/**
 * Live ML Kit face detection over the CameraX analysis stream.
 *
 * Same detector options the React Native frame processor used (fast mode, no
 * landmarks / contours / classification) and the same cadence: at most one
 * evaluation every [MIN_INTERVAL_MS], with frames dropped while one is in
 * flight. Results land on the main thread via ML Kit's default task executor.
 */
class FaceFrameAnalyzer(
    private val mirrored: Boolean,
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE)
            .build(),
    )

    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var lastRunAt = 0L

    /** Cleared while a photo is being reviewed so the review is not fighting live results. */
    @Volatile
    var enabled = true

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!enabled || now - lastRunAt < MIN_INTERVAL_MS || !inFlight.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastRunAt = now

        val media = image.image
        if (media == null) {
            release(image)
            return
        }

        val rotation = image.imageInfo.rotationDegrees
        val swapped = rotation == 90 || rotation == 270
        val uprightWidth = (if (swapped) image.height else image.width).toFloat()
        val uprightHeight = (if (swapped) image.width else image.height).toFloat()

        val input = try {
            InputImage.fromMediaImage(media, rotation)
        } catch (e: Exception) {
            Log.w(TAG, "could not wrap frame", e)
            release(image)
            return
        }
        val luma = runCatching { meanLuma(image) }.getOrDefault(FULL_LUMA)

        detector.process(input)
            .addOnSuccessListener { faces ->
                val mapped = faces.map { face ->
                    val box = face.boundingBox
                    val left = box.left / uprightWidth
                    val right = box.right / uprightWidth
                    NormalizedFace(
                        left = if (mirrored) 1f - right else left,
                        top = box.top / uprightHeight,
                        right = if (mirrored) 1f - left else right,
                        bottom = box.bottom / uprightHeight,
                        yaw = face.headEulerAngleY,
                        pitch = face.headEulerAngleX,
                        roll = face.headEulerAngleZ,
                    )
                }
                onResult(FrameResult(mapped, luma, uprightWidth / uprightHeight))
            }
            .addOnFailureListener { Log.w(TAG, "face detection failed", it) }
            .addOnCompleteListener { release(image) }
    }

    private fun release(image: ImageProxy) {
        runCatching { image.close() }
        inFlight.set(false)
    }

    /** Box-samples the Y plane. Stride-aware; absolute gets, so the buffer position ML Kit reads is untouched. */
    private fun meanLuma(image: ImageProxy): Int {
        val plane = image.planes.firstOrNull() ?: return FULL_LUMA
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()
        var sum = 0L
        var count = 0
        var y = 0
        while (y < image.height) {
            val row = y * rowStride
            var x = 0
            while (x < image.width) {
                val index = row + x * pixelStride
                if (index < limit) {
                    sum += buffer.get(index).toInt() and 0xFF
                    count++
                }
                x += LUMA_STEP
            }
            y += LUMA_STEP
        }
        return if (count == 0) FULL_LUMA else (sum / count).toInt()
    }

    override fun close() {
        enabled = false
        runCatching { detector.close() }
    }

    companion object {
        private const val TAG = "FaceCapture"

        /** ~6 evaluations a second, the cadence the RN frame processor settled on for this fleet. */
        private const val MIN_INTERVAL_MS = 150L

        /** Faces smaller than this fraction of the frame are ignored — a KYC selfie is close-up. */
        private const val MIN_FACE_SIZE = 0.15f

        private const val LUMA_STEP = 8
        private const val FULL_LUMA = 255
    }
}
