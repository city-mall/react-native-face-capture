package live.citymall.facecapture

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import live.citymall.facecapture.quality.BitmapDownscaler
import live.citymall.facecapture.quality.ImageQualityAnalyzer
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** What the still photo looks like once it exists. Every field is null when it could not be measured. */
data class PhotoQuality(
    /** ML Kit found at least one face in the still. */
    val faceFound: Boolean?,
    /** Variance of the Laplacian over the face crop (whole frame when no face was found). Higher is sharper. */
    val blurScore: Double?,
    /** Largest face box as a percent of the photo's area. */
    val faceAreaPct: Double?,
    /** Head pose of the largest face in degrees (ML Kit Euler angles); null when no face was found. */
    val yaw: Float? = null,
    val pitch: Float? = null,
    val roll: Float? = null,
)

/**
 * Post-capture quality check, run on the worker thread before the DB sees
 * the review.
 *
 * Two things the live stream cannot tell us: whether the *captured* frame,
 * taken a few hundred milliseconds after the last good live frame, still holds
 * a face, and whether it is sharp. Sharpness is the proof package's Laplacian
 * variance (see ImageQualityAnalyzer for why the frame is downscaled to a
 * FIXED width), measured on the face crop rather than the whole frame: a sharp
 * face on a plain wall scores low, and background texture inflates the score
 * of a blurry one.
 *
 * Every failure to measure degrades to null. A quality check must never be
 * the thing that strands a mandatory capture.
 */
class CapturedPhotoQuality : Closeable {

    private val detectorDelegate = lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build(),
        )
    }
    private val detector by detectorDelegate

    /** Blocking — worker thread only. */
    fun measure(bitmap: Bitmap): PhotoQuality {
        val faces = try {
            Tasks.await(
                detector.process(InputImage.fromBitmap(bitmap, 0)),
                DETECT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        } catch (e: Exception) {
            Log.w(TAG, "still-image face detection unavailable, skipping the face check", e)
            null
        }

        val face = faces?.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }
        val region = face?.boundingBox?.let { padded(it, bitmap.width, bitmap.height) }

        val blur = try {
            val subject = if (region != null) {
                Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
            } else {
                bitmap
            }
            val score = ImageQualityAnalyzer.laplacianVariance(BitmapDownscaler.toGrayFrame(subject))
            if (subject !== bitmap) subject.recycle()
            score
        } catch (e: Exception) {
            Log.w(TAG, "blur measurement failed, skipping the blur check", e)
            null
        }

        val areaPct = face?.boundingBox?.let {
            val photoArea = bitmap.width.toDouble() * bitmap.height
            if (photoArea <= 0) null else (it.width().toDouble() * it.height()) / photoArea * 100.0
        }

        return PhotoQuality(
            faceFound = faces?.isNotEmpty(),
            blurScore = blur,
            faceAreaPct = areaPct,
            yaw = face?.headEulerAngleY,
            pitch = face?.headEulerAngleX,
            roll = face?.headEulerAngleZ,
        )
    }

    /** The face box grown by [CROP_PADDING] on every side and clamped to the photo; null if degenerate. */
    private fun padded(box: Rect, width: Int, height: Int): Rect? {
        val padX = (box.width() * CROP_PADDING).roundToInt()
        val padY = (box.height() * CROP_PADDING).roundToInt()
        val crop = Rect(
            (box.left - padX).coerceAtLeast(0),
            (box.top - padY).coerceAtLeast(0),
            (box.right + padX).coerceAtMost(width),
            (box.bottom + padY).coerceAtMost(height),
        )
        return if (crop.width() >= MIN_CROP_PX && crop.height() >= MIN_CROP_PX) crop else null
    }

    override fun close() {
        if (detectorDelegate.isInitialized()) runCatching { detector.close() }
    }

    companion object {
        private const val TAG = "FaceCapture"
        private const val MIN_FACE_SIZE = 0.15f
        private const val CROP_PADDING = 0.2f
        private const val MIN_CROP_PX = 48
        private const val DETECT_TIMEOUT_SECONDS = 3L
    }
}
