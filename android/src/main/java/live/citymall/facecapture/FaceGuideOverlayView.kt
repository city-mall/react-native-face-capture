package live.citymall.facecapture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import live.citymall.facecapture.R
import kotlin.math.min

/**
 * The guide drawn over the camera preview: a dimming scrim with a rounded
 * cut-out, four corner brackets, and the per-state decorations from the
 * approved design — a sweeping scan line and progress outline while
 * detecting, a check badge on success, dashed boxes around extra faces.
 *
 * Geometry comes from the mock (a 360x520 camera area with the frame at
 * x 62, y 96, 236x294, radius 16) expressed as fractions of the view, so it
 * scales with the device. [frameRect] is what the activity measures faces
 * against.
 */
class FaceGuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Look { HIDDEN, ALIGN, DETECTING, LOWLIGHT, MULTIFACE, SUCCESS, FAILED }

    var look: Look = Look.HIDDEN
        set(value) {
            if (field == value) return
            field = value
            syncAnimators()
            invalidate()
        }

    /** 0..1 — fraction of the frame outline drawn while [Look.DETECTING]. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Faces other than the primary one, in view coordinates, boxed while [Look.MULTIFACE]. */
    var extraFaces: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val bracketStroke = dp(3.5f)
    private val cornerRadius = dp(16f)
    private val armLength = dp(34f)

    private val colorScrim = color(R.color.face_scrim)
    private val colorLowlight = color(R.color.face_lowlight_scrim)
    private val colorAccent = color(R.color.face_accent)
    private val colorAccentGlow = color(R.color.face_accent_glow)
    private val colorSuccess = color(R.color.face_success)
    private val colorSuccessGlow = color(R.color.face_success_glow)
    private val colorSuccessFill = color(R.color.face_success_fill)
    private val colorIdle = color(R.color.face_guide_idle)
    private val colorMuted = color(R.color.face_guide_muted)
    private val colorDim = color(R.color.face_guide_dim)
    private val colorOnAccent = color(R.color.face_on_accent)

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = bracketStroke
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = bracketStroke * 3
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2f)
        color = colorAccent
        alpha = 230
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorSuccessFill
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = colorSuccess
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorOnAccent
    }
    private val extraFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = colorIdle
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(5f)), 0f)
    }

    private val frame = RectF()
    private val cutoutPath = Path()
    private val outlinePath = Path()
    private val bracketPath = Path()
    private val checkPath = Path()
    private val segmentPath = Path()
    private val pathMeasure = PathMeasure()
    private val scanRect = RectF()

    private var scanPosition = 0f
    private var pulseAlpha = 1f

    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            scanPosition = it.animatedValue as Float
            invalidate()
        }
    }
    private val pulseAnimator = ValueAnimator.ofFloat(0.75f, 1f).apply {
        duration = 1000L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulseAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    /** The bracket frame in view coordinates. Empty until the view is laid out. */
    fun frameRect(): RectF = RectF(frame)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        val frameWidth = w * FRAME_WIDTH_FRACTION
        val frameHeight = min(h * FRAME_HEIGHT_FRACTION, frameWidth * MAX_FRAME_ASPECT)
        val left = (w - frameWidth) / 2f
        val top = min(h * FRAME_TOP_FRACTION, h - frameHeight - dp(16f)).coerceAtLeast(0f)
        frame.set(left, top, left + frameWidth, top + frameHeight)

        cutoutPath.reset()
        cutoutPath.addRoundRect(frame, cornerRadius, cornerRadius, Path.Direction.CW)
        outlinePath.reset()
        outlinePath.addRoundRect(frame, cornerRadius, cornerRadius, Path.Direction.CW)
        buildBrackets()
        buildCheck()

        val inset = dp(10f)
        scanRect.set(frame.left + inset, 0f, frame.right - inset, 0f)
        scanPaint.shader = LinearGradient(
            scanRect.left, 0f, scanRect.right, 0f,
            intArrayOf(Color.TRANSPARENT, colorAccent, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        scanGlowPaint.shader = LinearGradient(
            scanRect.left, 0f, scanRect.right, 0f,
            intArrayOf(Color.TRANSPARENT, colorAccentGlow, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
    }

    private fun buildBrackets() {
        val r = cornerRadius
        val a = armLength
        bracketPath.reset()
        // top-left
        bracketPath.moveTo(frame.left, frame.top + a)
        bracketPath.lineTo(frame.left, frame.top + r)
        bracketPath.quadTo(frame.left, frame.top, frame.left + r, frame.top)
        bracketPath.lineTo(frame.left + a, frame.top)
        // top-right
        bracketPath.moveTo(frame.right - a, frame.top)
        bracketPath.lineTo(frame.right - r, frame.top)
        bracketPath.quadTo(frame.right, frame.top, frame.right, frame.top + r)
        bracketPath.lineTo(frame.right, frame.top + a)
        // bottom-right
        bracketPath.moveTo(frame.right, frame.bottom - a)
        bracketPath.lineTo(frame.right, frame.bottom - r)
        bracketPath.quadTo(frame.right, frame.bottom, frame.right - r, frame.bottom)
        bracketPath.lineTo(frame.right - a, frame.bottom)
        // bottom-left
        bracketPath.moveTo(frame.left + a, frame.bottom)
        bracketPath.lineTo(frame.left + r, frame.bottom)
        bracketPath.quadTo(frame.left, frame.bottom, frame.left, frame.bottom - r)
        bracketPath.lineTo(frame.left, frame.bottom - a)
    }

    private fun buildCheck() {
        val cx = frame.centerX()
        val cy = frame.centerY()
        checkPath.reset()
        checkPath.moveTo(cx - dp(15f), cy)
        checkPath.lineTo(cx - dp(4f), cy + dp(11f))
        checkPath.lineTo(cx + dp(17f), cy - dp(11f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (look == Look.HIDDEN || frame.isEmpty) return

        // Dim everything outside the frame.
        canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(cutoutPath)
        } else {
            // clipOutPath is API 26; the Region.Op form is deprecated there but is
            // the only way to punch the hole on the API 24/25 phones that
            // consuming apps still support.
            @Suppress("DEPRECATION")
            canvas.clipPath(cutoutPath, Region.Op.DIFFERENCE)
        }
        canvas.drawColor(colorScrim)
        canvas.restore()
        if (look == Look.LOWLIGHT) canvas.drawColor(colorLowlight)

        drawBrackets(canvas)

        when (look) {
            Look.DETECTING -> {
                drawProgressOutline(canvas)
                drawScanLine(canvas)
            }
            Look.SUCCESS -> drawCheckBadge(canvas)
            Look.MULTIFACE -> extraFaces.forEach { canvas.drawRoundRect(it, dp(10f), dp(10f), extraFacePaint) }
            else -> Unit
        }
    }

    private fun drawBrackets(canvas: Canvas) {
        val color: Int
        var alpha = 1f
        var glow: Int? = null
        when (look) {
            Look.ALIGN -> color = colorIdle
            Look.DETECTING -> { color = colorAccent; glow = colorAccentGlow }
            Look.SUCCESS -> { color = colorSuccess; glow = colorSuccessGlow; alpha = pulseAlpha }
            Look.LOWLIGHT -> color = colorDim
            Look.MULTIFACE, Look.FAILED -> color = colorMuted
            Look.HIDDEN -> return
        }
        if (glow != null) {
            glowPaint.color = glow
            glowPaint.alpha = (Color.alpha(glow) * alpha).toInt()
            canvas.drawPath(bracketPath, glowPaint)
        }
        bracketPaint.color = color
        bracketPaint.alpha = (Color.alpha(color) * alpha).toInt()
        canvas.drawPath(bracketPath, bracketPaint)
    }

    private fun drawProgressOutline(canvas: Canvas) {
        if (progress <= 0f) return
        pathMeasure.setPath(outlinePath, false)
        segmentPath.reset()
        pathMeasure.getSegment(0f, pathMeasure.length * progress, segmentPath, true)
        canvas.drawPath(segmentPath, outlinePaint)
    }

    private fun drawScanLine(canvas: Canvas) {
        val travelPad = dp(8f)
        val y = frame.top + travelPad + (frame.height() - travelPad * 2) * scanPosition
        val glowHalf = dp(6f)
        scanRect.top = y - glowHalf
        scanRect.bottom = y + glowHalf
        canvas.drawRect(scanRect, scanGlowPaint)
        val lineHalf = dp(1f)
        scanRect.top = y - lineHalf
        scanRect.bottom = y + lineHalf
        canvas.drawRect(scanRect, scanPaint)
    }

    private fun drawCheckBadge(canvas: Canvas) {
        val radius = dp(34f)
        canvas.drawCircle(frame.centerX(), frame.centerY(), radius, badgeFillPaint)
        canvas.drawCircle(frame.centerX(), frame.centerY(), radius, badgeStrokePaint)
        canvas.drawPath(checkPath, checkPaint)
    }

    private fun syncAnimators() {
        if (!isAttachedToWindow) return
        if (look == Look.DETECTING) {
            if (!scanAnimator.isStarted) scanAnimator.start()
        } else {
            scanAnimator.cancel()
        }
        if (look == Look.SUCCESS) {
            if (!pulseAnimator.isStarted) pulseAnimator.start()
        } else {
            pulseAnimator.cancel()
            pulseAlpha = 1f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimators()
    }

    override fun onDetachedFromWindow() {
        scanAnimator.cancel()
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)

    companion object {
        private const val FRAME_WIDTH_FRACTION = 236f / 360f
        private const val FRAME_HEIGHT_FRACTION = 294f / 520f
        private const val FRAME_TOP_FRACTION = 96f / 520f
        private const val MAX_FRAME_ASPECT = 1.3f
    }
}
