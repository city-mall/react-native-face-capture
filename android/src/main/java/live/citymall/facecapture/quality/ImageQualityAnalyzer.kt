package live.citymall.facecapture.quality

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A downscaled 8-bit grayscale frame.
 *
 * Deliberately free of android.graphics so every metric below is unit-testable
 * on a plain JVM — see BitmapDownscaler for the Android-side conversion.
 *
 * Frames MUST be produced at a FIXED pixel width, not a fixed ratio. Camera
 * resolution across this fleet spans roughly 2 MP to 108 MP, and Laplacian
 * variance scales with sampling density: a ratio-based downscale would make one
 * threshold mean "sharp" on a cheap phone and "blurry" on a flagship.
 */
data class GrayFrame(val luma: IntArray, val width: Int, val height: Int) {
    val size: Int get() = luma.size

    // IntArray gives identity equals/hashCode; data class needs these spelled out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GrayFrame) return false
        return width == other.width && height == other.height && luma.contentEquals(other.luma)
    }

    override fun hashCode(): Int =
        (luma.contentHashCode() * 31 + width) * 31 + height
}

/** Every raw measurement taken from one frame. Reported whether or not a check gated on it. */
data class FrameMetrics(
    /** Variance of the Laplacian response. Higher is sharper. */
    val blurScore: Double,
    /** Mean luma, 0..255. */
    val meanLuma: Double,
    /** Percent of pixels crushed to black or blown to white. */
    val clippedPct: Double,
    /** Standard deviation of luma. Near zero means a featureless frame. */
    val stdDev: Double,
    /** Shannon entropy of the 256-bin luma histogram, in bits, 0..8. */
    val entropy: Double,
    /** Share of the single most-populated 16-bin bucket, as a percent. */
    val dominantBucketPct: Double,
) {
    fun toScoreMap(): Map<String, Double> = mapOf(
        "blur" to blurScore,
        "luma" to meanLuma,
        "clipped_pct" to clippedPct,
        "std_dev" to stdDev,
        "entropy" to entropy,
        "dominant_pct" to dominantBucketPct,
    )
}

/**
 * Pure luma metrics for a downscaled frame: blur (Laplacian variance),
 * exposure, entropy and flatness.
 *
 * No ML, no model download, no Play Services, no android.graphics — so it
 * always runs and is unit-testable on a plain JVM. The face-capture screen
 * uses [laplacianVariance] as its post-shutter sharpness gate; [measure] is
 * kept for callers that want the full set.
 */
object ImageQualityAnalyzer {

    /** Fixed target width for every analysed frame. See GrayFrame's note. */
    const val TARGET_WIDTH = 320

    private const val HISTOGRAM_BINS = 256
    private const val BUCKET_SHIFT = 4          // 256 → 16 buckets
    private const val CLIP_LOW = 2
    private const val CLIP_HIGH = 253
    private val LOG_2 = ln(2.0)

    /** The Laplacian window needs a 1px border on every side; anything smaller cannot be scored. */
    fun isMeasurable(frame: GrayFrame): Boolean =
        frame.width >= 3 && frame.height >= 3 && frame.size >= frame.width * frame.height

    /** One pass for the histogram-derived metrics, one for the Laplacian. */
    fun measure(frame: GrayFrame): FrameMetrics {
        val histogram = IntArray(HISTOGRAM_BINS)
        var sum = 0L
        var sumSq = 0L

        for (i in 0 until frame.width * frame.height) {
            val v = frame.luma[i]
            histogram[v]++
            sum += v
            sumSq += v.toLong() * v
        }

        val total = (frame.width * frame.height).toDouble()
        val mean = sum / total
        // E[x²] − E[x]², floored at 0 against float drift on a uniform frame.
        val variance = (sumSq / total - mean * mean).coerceAtLeast(0.0)

        var clipped = 0L
        for (v in 0..CLIP_LOW) clipped += histogram[v]
        for (v in CLIP_HIGH until HISTOGRAM_BINS) clipped += histogram[v]

        var entropy = 0.0
        for (count in histogram) {
            if (count == 0) continue
            val p = count / total
            entropy -= p * (ln(p) / LOG_2)
        }

        val buckets = IntArray(HISTOGRAM_BINS shr BUCKET_SHIFT)
        for (v in 0 until HISTOGRAM_BINS) buckets[v shr BUCKET_SHIFT] += histogram[v]
        val dominant = (buckets.max() / total) * 100.0

        return FrameMetrics(
            blurScore = laplacianVariance(frame),
            meanLuma = mean,
            clippedPct = (clipped / total) * 100.0,
            stdDev = sqrt(variance),
            entropy = entropy,
            dominantBucketPct = dominant,
        )
    }

    /**
     * Variance of the 4-neighbour Laplacian response.
     *
     *      0  1  0
     *      1 -4  1
     *      0  1  0
     *
     * A sharp frame has strong edges, so the response spreads out and its
     * variance is high; a defocused or shaken frame smears them and the
     * variance collapses. The 1px border is excluded rather than clamped —
     * edge replication would fabricate zero-responses and drag the score down
     * on small frames.
     */
    fun laplacianVariance(frame: GrayFrame): Double {
        val w = frame.width
        val h = frame.height
        val px = frame.luma

        var sum = 0.0
        var sumSq = 0.0
        var n = 0

        for (y in 1 until h - 1) {
            val row = y * w
            val above = row - w
            val below = row + w
            for (x in 1 until w - 1) {
                val response = (
                    px[above + x] +
                        px[row + x - 1] +
                        px[row + x + 1] +
                        px[below + x] -
                        4 * px[row + x]
                    ).toDouble()
                sum += response
                sumSq += response * response
                n++
            }
        }

        if (n == 0) return 0.0
        val mean = sum / n
        return (sumSq / n - mean * mean).coerceAtLeast(0.0)
    }
}
