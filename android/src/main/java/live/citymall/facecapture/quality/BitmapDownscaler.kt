package live.citymall.facecapture.quality

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Android-side pixel plumbing: everything that turns a camera frame or a JPEG
 * on disk into the fixed-width GrayFrame the analyser wants, plus writing the
 * final JPEG back out.
 *
 * Kept apart from ImageQualityAnalyzer so the metric maths stays free of
 * android.graphics and can be unit-tested on a plain JVM.
 */
object BitmapDownscaler {

    /**
     * Decode a captured JPEG at roughly analysis size.
     *
     * inSampleSize does the bulk of the reduction inside the decoder, so a 48 MP
     * capture never materialises as a full bitmap — these are 2 GB-RAM phones and
     * a full-size decode next to a live camera session is an OOM waiting to happen.
     */
    fun decodeForAnalysis(path: String, targetWidth: Int = ImageQualityAnalyzer.TARGET_WIDTH): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, targetWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, options)
    }

    /** Largest power-of-two reduction that still leaves us at or above the target width. */
    private fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    /** Scale to the fixed analysis width and flatten to 8-bit luma (ITU-R BT.601). */
    fun toGrayFrame(bitmap: Bitmap, targetWidth: Int = ImageQualityAnalyzer.TARGET_WIDTH): GrayFrame {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return GrayFrame(IntArray(0), 0, 0)

        val scaledW = targetWidth.coerceAtMost(w)
        val scaledH = ((h.toLong() * scaledW) / w).toInt().coerceAtLeast(1)

        val scaled = if (scaledW == w && scaledH == h) bitmap
        else Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)

        val pixels = IntArray(scaledW * scaledH)
        scaled.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)
        if (scaled !== bitmap) scaled.recycle()

        val luma = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
        }
        return GrayFrame(luma, scaledW, scaledH)
    }

    /**
     * Build a GrayFrame straight from a CameraX YUV_420_888 frame.
     *
     * Plane 0 of YUV *is* luma, so the live-hint path needs no colour conversion
     * and no Bitmap allocation at all — it box-averages the Y plane down to the
     * analysis width in one pass. That is what makes running this at ~2 fps on a
     * budget device affordable.
     *
     * Box-averaging rather than nearest-neighbour on purpose: it approximates the
     * bilinear downscale the still path uses, so the two produce comparable blur
     * scores. They are still not identical, which is why the live hints trigger on
     * a margin above the gate's own thresholds rather than on the thresholds
     * themselves — a hint that fires slightly early is helpful, one that stays
     * quiet on a photo the gate will reject is not.
     */
    fun fromImageProxy(image: ImageProxy, targetWidth: Int = ImageQualityAnalyzer.TARGET_WIDTH): GrayFrame? {
        val srcW = image.width
        val srcH = image.height
        if (srcW <= 0 || srcH <= 0) return null

        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val dstW = targetWidth.coerceAtMost(srcW)
        val dstH = ((srcH.toLong() * dstW) / srcW).toInt().coerceAtLeast(1)

        val blockW = srcW / dstW
        val blockH = srcH / dstH
        if (blockW < 1 || blockH < 1) return null

        val out = IntArray(dstW * dstH)
        val row = ByteArray(rowStride)

        // Accumulate one source block-row at a time so we touch the direct
        // ByteBuffer sequentially — random access across a direct buffer is
        // markedly slower on older devices.
        val acc = IntArray(dstW)
        val counts = IntArray(dstW)

        for (dy in 0 until dstH) {
            java.util.Arrays.fill(acc, 0)
            java.util.Arrays.fill(counts, 0)

            val y0 = dy * blockH
            val y1 = (y0 + blockH).coerceAtMost(srcH)

            for (sy in y0 until y1) {
                val offset = sy * rowStride
                if (offset + rowStride > buffer.limit()) break
                buffer.position(offset)
                val available = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, available)

                for (dx in 0 until dstW) {
                    val x0 = dx * blockW
                    val x1 = (x0 + blockW).coerceAtMost(srcW)
                    for (sx in x0 until x1) {
                        val idx = sx * pixelStride
                        if (idx >= available) break
                        acc[dx] += row[idx].toInt() and 0xFF
                        counts[dx]++
                    }
                }
            }

            val rowStart = dy * dstW
            for (dx in 0 until dstW) {
                out[rowStart + dx] = if (counts[dx] > 0) acc[dx] / counts[dx] else 0
            }
        }

        return GrayFrame(out, dstW, dstH)
    }

    /**
     * Re-encode the capture to the size and quality the upload expects.
     *
     * Matches what imagePickerHelper asked react-native-image-picker for
     * (quality 0.4, maxHeight 2048) so swapping the capture path does not
     * silently change upload sizes or push the multipart POST past the axios
     * 10 s timeout.
     */
    fun writeJpeg(source: Bitmap, target: File, maxDimension: Int, quality: Int): Boolean = runCatching {
        val longest = maxOf(source.width, source.height)
        val bitmap = if (longest <= maxDimension) source else {
            val ratio = maxDimension.toDouble() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (bitmap !== source) bitmap.recycle()
        true
    }.getOrDefault(false)

    /**
     * Apply the EXIF orientation as real pixels.
     *
     * ML Kit and the analyser both read raw pixel order, so a portrait photo
     * carrying orientation 6 would be analysed sideways — and the upload consumer
     * would render it sideways too. Bake the rotation in and the tag becomes moot.
     */
    fun applyExifRotation(bitmap: Bitmap, path: String): Bitmap = runCatching {
        val orientation = ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}
