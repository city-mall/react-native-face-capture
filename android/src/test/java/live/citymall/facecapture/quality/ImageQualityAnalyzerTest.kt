package live.citymall.facecapture.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frames are synthesised to isolate one property each: a linear ramp has
 * a mathematically zero Laplacian, a constant frame has zero entropy, and so
 * on. The screen's blur threshold is calibrated against real uploads; these
 * tests pin the *behaviour* of the metric so recalibration cannot silently
 * invert it.
 */
class ImageQualityAnalyzerTest {

    private val w = ImageQualityAnalyzer.TARGET_WIDTH
    private val h = 240

    private fun frame(width: Int = w, height: Int = h, fill: (Int, Int) -> Int) =
        GrayFrame(
            IntArray(width * height) { i -> fill(i % width, i / width).coerceIn(0, 255) },
            width,
            height,
        )

    /** Uniform pseudo-random noise: sharp, well exposed, high entropy. */
    private fun noiseFrame(): GrayFrame {
        var seed = 12345L
        return frame { _, _ ->
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            (seed % 256).toInt()
        }
    }

    /** A linear ramp. Its Laplacian is exactly zero, so this isolates blur. */
    private fun gradientFrame() = frame { x, _ -> x * 255 / w }

    private fun constantFrame(value: Int) = frame { _, _ -> value }

    @Test
    fun `laplacian variance collapses on a smooth gradient and rises on noise`() {
        val smooth = ImageQualityAnalyzer.laplacianVariance(gradientFrame())
        val sharp = ImageQualityAnalyzer.laplacianVariance(noiseFrame())
        assertTrue("a linear ramp has a near-zero Laplacian, got $smooth", smooth < 1.0)
        assertTrue("noise should be far sharper, got $sharp", sharp > 1000.0)
    }

    @Test
    fun `laplacian variance is zero, not NaN, on a frame too small to score`() {
        assertEquals(0.0, ImageQualityAnalyzer.laplacianVariance(frame(2, 2) { _, _ -> 128 }), 0.0)
        assertFalse(ImageQualityAnalyzer.isMeasurable(frame(2, 2) { _, _ -> 128 }))
        assertTrue(ImageQualityAnalyzer.isMeasurable(frame(3, 3) { _, _ -> 128 }))
    }

    @Test
    fun `measure reports exposure and clipping on constant frames`() {
        val dark = ImageQualityAnalyzer.measure(constantFrame(1))
        assertEquals(1.0, dark.meanLuma, 0.0)
        assertEquals(100.0, dark.clippedPct, 0.0)
        assertEquals(0.0, dark.entropy, 1e-9)
        assertEquals(0.0, dark.stdDev, 1e-9)
        assertEquals(100.0, dark.dominantBucketPct, 0.0)

        val mid = ImageQualityAnalyzer.measure(constantFrame(128))
        assertEquals(128.0, mid.meanLuma, 0.0)
        assertEquals(0.0, mid.clippedPct, 0.0)
    }

    @Test
    fun `measure sees noise as high-entropy and spread across buckets`() {
        val m = ImageQualityAnalyzer.measure(noiseFrame())
        assertTrue("entropy of uniform noise approaches 8 bits, got ${m.entropy}", m.entropy > 7.5)
        assertTrue("no single 16-bin bucket should dominate, got ${m.dominantBucketPct}%", m.dominantBucketPct < 10.0)
        assertTrue(m.stdDev > 60.0)
        assertEquals(m.blurScore, ImageQualityAnalyzer.laplacianVariance(noiseFrame()), 0.0)
    }

    @Test
    fun `gray frames compare by content`() {
        assertEquals(constantFrame(7), constantFrame(7))
        assertEquals(constantFrame(7).hashCode(), constantFrame(7).hashCode())
        assertFalse(constantFrame(7) == constantFrame(8))
    }
}
