package live.citymall.facecapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import live.citymall.facecapture.R
import live.citymall.facecapture.databinding.ActivityFaceCaptureBinding
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Native face capture for KYC identity verification.
 *
 * Replaces the React Native `FaceCaptureModal` (vision-camera + a JS frame
 * processor) with CameraX + ML Kit face detection driven from Kotlin. The
 * screen is a small state machine — align, detecting, low light,
 * multiple faces, success, no face — plus the two states that come with
 * owning the upload, uploading and upload failed. Every live state is decided
 * by ML Kit face detection on the analysis stream (face count, bounds, size)
 * and the same frames' luminance. Once the shutter fires, the still itself is
 * checked again (CapturedPhotoQuality): a face must be in it and the face crop
 * must clear the blur threshold before the review is shown.
 *
 * CAMERA permission: by default the screen never asks — the host's own
 * permission flow owns it, and landing here without it ends the activity at
 * once with ERROR_CAMERA_PERMISSION so the host can wait for its gate. With
 * `requestPermission` in the config the screen asks once itself, then either
 * starts the camera or finishes with ERROR_CAMERA_PERMISSION /
 * ERROR_CAMERA_PERMISSION_BLOCKED (cannot be asked again → send to Settings).
 *
 * Contract with FaceCaptureModule:
 *   in  — FaceCaptureConfig as intent extras (copy, close policy, flow, and
 *         the auth/base URL/version values JS already resolves)
 *   out — RESULT_OK once the photo is uploaded, RESULT_CANCELED when the DB
 *         closes it (only possible with canClose), RESULT_ERROR when the
 *         screen could not run at all (bad config, or no camera permission).
 *
 * Capture is automatic by default (config.captureMode): the detecting phase
 * starts by itself after a few consecutive good frames, so the DB only has to
 * hold still. In manual mode a shutter appears instead and it is never
 * disabled: the detecting phase verifies for a few frames after the tap and
 * explains a failure with a way forward, rather than leaving a greyed-out
 * button with no hint.
 */
class FaceCaptureActivity : AppCompatActivity(), FaceCaptureSession.Listener {

    private enum class Stage {
        STARTING, ALIGN, DETECTING, LOWLIGHT, MULTIFACE, SUCCESS, FAILED,
        UPLOADING, UPLOAD_FAILED, CAMERA_ERROR,
    }

    /** What the detecting phase could not confirm, chosen from what it did see. */
    private enum class Failure(@StringRes val title: Int, @StringRes val subtitle: Int) {
        NO_FACE(R.string.face_failed_title, R.string.face_failed_sub),
        NOT_CENTERED(R.string.face_failed_centered_title, R.string.face_failed_centered_sub),
        TURNED(R.string.face_failed_turned_title, R.string.face_failed_turned_sub),
        UNSTABLE(R.string.face_failed_unstable_title, R.string.face_failed_unstable_sub),
        BLURRY(R.string.face_failed_blurry_title, R.string.face_failed_blurry_sub),
        CAPTURE(R.string.face_capture_error_title, R.string.face_capture_error_sub),
    }

    private data class Checks(
        val faceCount: Int,
        val centered: Boolean,
        val sizeOk: Boolean,
        val goodLight: Boolean,
        /** Yaw, pitch and roll of the largest face all within maxHeadAngle. */
        val straight: Boolean,
        val extraFaces: List<RectF>,
    ) {
        val allGood: Boolean get() = faceCount == 1 && centered && sizeOk && goodLight && straight
    }

    private lateinit var binding: ActivityFaceCaptureBinding
    private lateinit var config: FaceCaptureConfig

    /**
     * Live copies of the close policy and instruction text. Seeded from the
     * launch config, then updated through FaceCaptureSession so a backend
     * change mid-session applies at once — Redux stays the source of truth,
     * exactly as it was for the RN modal.
     */
    private var canClose = false
    private var instructionText = ""

    /** A dismiss that arrived mid-upload; applied if the upload fails. */
    private var pendingDismiss = false

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val uploader = FaceCaptureUploader()
    private val photoQuality = CapturedPhotoQuality()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var analyzer: FaceFrameAnalyzer? = null

    /** Guards against a second capture being queued while one is in flight. */
    private val capturing = AtomicBoolean(false)

    private var stage = Stage.STARTING
    private var capturedFile: File? = null

    /** Detecting runs this session (shutter taps or auto starts); travels in meta_data so the backend can see retake rates. */
    private var attempts = 0
    private var lastQuality: PhotoQuality? = null

    /** Consecutive all-good frames while aligning — auto mode starts detecting once this is long enough. */
    private var autoStartRun = 0

    /** Blur rejections this session. After BLUR_MAX_REJECTS the gate relaxes so a soft camera cannot strand the DB. */
    private var blurRejects = 0
    private var blurGateRelaxed = false

    // Live-detection bookkeeping. Runs of consecutive frames give the hazard
    // states hysteresis so a single dark or crowded frame does not flip the UI.
    private var multiRun = 0
    private var darkRun = 0
    private var goodRun = 0
    private var recoverRun = 0
    private var detectStartedAt = 0L
    private var seenFace = false
    private var seenCentered = false
    private var seenLight = false
    private var seenStraight = false
    private var failure = Failure.NO_FACE

    private var panelPadding = Insets.NONE
    private var topBarPadding = Insets.NONE

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = FaceCaptureConfig.from(intent)
        canClose = config.canClose
        instructionText = config.instructionText
        if (!config.isUploadable) {
            // Nothing this screen could do would end well; let JS fall back.
            finishWithError("bad_config")
            return
        }

        binding = ActivityFaceCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (config.title.isNotBlank()) binding.titleText.text = config.title
        setUpWindow()
        applyTypography()
        wireControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Mirrors the RN modal: back is swallowed unless the backend
                // said the capture is dismissable, and never mid-upload.
                if (canClose && stage != Stage.UPLOADING) cancel()
            }
        })

        if (!hasCameraPermission()) {
            if (config.requestPermission) {
                // The host opted in: one system prompt from here, then either
                // the camera starts or we finish the way a denied launch does.
                setStage(Stage.STARTING)
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CAMERA_PERMISSION,
                )
                return
            }
            // Default: the host's permission flow owns CAMERA and checks it
            // before launching. Never prompt from here — hand back to JS.
            finishWithError(ERROR_CAMERA_PERMISSION)
            return
        }
        setStage(Stage.STARTING)
        startCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        if (hasCameraPermission()) {
            startCamera()
            return
        }
        // After a denial, "cannot show rationale" means the system will not ask
        // again (two denials on Android 11+, or "don't ask again" earlier). Tell
        // the host, so it sends the user to Settings instead of re-launching.
        val blocked = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
        finishWithError(if (blocked) ERROR_CAMERA_PERMISSION_BLOCKED else ERROR_CAMERA_PERMISSION)
    }

    override fun onStart() {
        super.onStart()
        FaceCaptureSession.attach(this)
    }

    override fun onStop() {
        FaceCaptureSession.detach(this)
        super.onStop()
    }

    // --------------------------------------------------- live session updates

    /** Main thread, via FaceCaptureSession. Null means "unchanged". */
    override fun onPolicyChanged(canClose: Boolean?, instructionText: String?) {
        if (isFinishing || isDestroyed) return
        if (canClose != null) this.canClose = canClose
        if (instructionText != null) this.instructionText = instructionText.trim()
        applyCloseButton()
        renderPanel()
    }

    /**
     * The backend withdrew the request (`faceCaptureVisible: false`). Mirrors
     * the RN modal's reducer guard: honoured at once except while an upload is
     * in flight, where it waits for the outcome — success still reports
     * `uploaded`, failure then dismisses instead of showing the retry state.
     */
    override fun onDismissRequested() {
        if (isFinishing || isDestroyed) return
        if (stage == Stage.UPLOADING) {
            pendingDismiss = true
            return
        }
        finishDismissed()
    }

    private fun applyCloseButton() {
        binding.closeButton.visibility =
            if (canClose && stage != Stage.UPLOADING) View.VISIBLE else View.GONE
    }

    /**
     * The app runs edge-to-edge (Android 15 enforces it for targetSdk 35
     * anyway), so the top bar and the panel absorb the system bars themselves.
     * Dark camera above, light panel below — the bar icons follow.
     *
     * statusBarColor / navigationBarColor are deprecated from API 35, where
     * edge-to-edge makes them no-ops; they still matter on 26–34.
     */
    @Suppress("DEPRECATION")
    private fun setUpWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }

        topBarPadding = Insets.of(
            binding.topBar.paddingLeft, binding.topBar.paddingTop,
            binding.topBar.paddingRight, binding.topBar.paddingBottom,
        )
        panelPadding = Insets.of(
            binding.panel.paddingLeft, binding.panel.paddingTop,
            binding.panel.paddingRight, binding.panel.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            binding.topBar.setPadding(
                topBarPadding.left, topBarPadding.top + bars.top,
                topBarPadding.right, topBarPadding.bottom,
            )
            binding.panel.setPadding(
                panelPadding.left, panelPadding.top,
                panelPadding.right, panelPadding.bottom + bars.bottom,
            )
            // Keep the panel's content area constant: grow the fixed height by the
            // inset we just padded, so the preview above never changes size.
            binding.panel.layoutParams = binding.panel.layoutParams.apply {
                height = PANEL_HEIGHT_DP.dp() + bars.bottom
            }
            windowInsets
        }
    }

    /** The app's Noto Sans Devanagari faces, straight from the RN-linked asset folder. */
    private fun applyTypography() {
        val medium = typeface("fonts/NotoSansDevanagari-Medium.ttf")
        val regular = typeface("fonts/NotoSansDevanagari-Regular.ttf")
        listOf(
            binding.titleText, binding.statusTitle,
            binding.primaryButton, binding.secondaryButton, binding.ghostButton,
        ).forEach { view -> medium?.let { view.typeface = it } }
        listOf(
            binding.statusSubtitle, binding.check1Label, binding.check2Label, binding.check3Label,
            binding.check4Label,
        ).forEach { view -> regular?.let { view.typeface = it } }
    }

    private fun typeface(assetPath: String): Typeface? =
        runCatching { Typeface.createFromAsset(assets, assetPath) }.getOrNull()

    private fun wireControls() {
        binding.closeButton.setOnClickListener { if (stage != Stage.UPLOADING) cancel() }
        binding.shutterButton.setOnClickListener { onShutter() }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ----------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider

                val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                if (!provider.hasCamera(selector)) {
                    setStage(Stage.CAMERA_ERROR)
                    return@addListener
                }

                val rotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(rotation)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    ANALYSIS_TARGET,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                            .build(),
                    )
                    .setTargetRotation(rotation)
                    .build()

                analyzer?.close()
                val faceAnalyzer = FaceFrameAnalyzer(mirrored = true, onResult = ::onFrame)
                analyzer = faceAnalyzer
                analysis.setAnalyzer(worker, faceAnalyzer)

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture, analysis)
                imageCapture = capture

                if (stage == Stage.STARTING || stage == Stage.CAMERA_ERROR) setStage(Stage.ALIGN)
            } catch (e: Exception) {
                // No front camera, another app holding it, an OEM HAL throwing
                // on bind. Explain in place and offer a retry.
                Log.e(TAG, "camera bind failed", e)
                setStage(Stage.CAMERA_ERROR)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------ live frames

    /** Main thread — ML Kit delivers on its default executor. */
    private fun onFrame(result: FrameResult) {
        if (isFinishing || isDestroyed) return
        val checks = evaluate(result)

        when (stage) {
            Stage.ALIGN -> {
                trackHazards(checks)
                autoStartRun = if (checks.allGood) autoStartRun + 1 else 0
                when {
                    multiRun >= HAZARD_ENTER_FRAMES -> setStage(Stage.MULTIFACE)
                    darkRun >= HAZARD_ENTER_FRAMES -> setStage(Stage.LOWLIGHT)
                    config.autoCapture && autoStartRun >= AUTO_START_FRAMES -> startDetecting()
                }
            }

            Stage.DETECTING -> {
                trackHazards(checks)
                if (multiRun >= HAZARD_ENTER_FRAMES) return setStage(Stage.MULTIFACE)
                if (darkRun >= HAZARD_ENTER_FRAMES) return setStage(Stage.LOWLIGHT)

                if (checks.faceCount >= 1) seenFace = true
                if (checks.centered && checks.sizeOk) seenCentered = true
                if (checks.goodLight) seenLight = true
                if (checks.faceCount >= 1 && checks.straight) seenStraight = true
                goodRun = if (checks.allGood) goodRun + 1 else 0

                binding.overlay.progress = goodRun.toFloat() / REQUIRED_GOOD_FRAMES
                renderChecklist()

                when {
                    goodRun >= REQUIRED_GOOD_FRAMES -> takePicture()
                    SystemClock.elapsedRealtime() - detectStartedAt > DETECT_TIMEOUT_MS -> {
                        failure = when {
                            !seenFace -> Failure.NO_FACE
                            !seenCentered -> Failure.NOT_CENTERED
                            !seenStraight -> Failure.TURNED
                            else -> Failure.UNSTABLE
                        }
                        setStage(Stage.FAILED)
                    }
                }
            }

            Stage.LOWLIGHT -> {
                recoverRun = if (checks.goodLight) recoverRun + 1 else 0
                if (recoverRun >= HAZARD_EXIT_FRAMES) setStage(Stage.ALIGN)
            }

            Stage.MULTIFACE -> {
                binding.overlay.extraFaces = checks.extraFaces
                recoverRun = if (checks.faceCount <= 1) recoverRun + 1 else 0
                if (recoverRun >= HAZARD_EXIT_FRAMES) setStage(Stage.ALIGN)
            }

            else -> Unit
        }
    }

    private fun trackHazards(checks: Checks) {
        multiRun = if (checks.faceCount > 1) multiRun + 1 else 0
        darkRun = if (!checks.goodLight) darkRun + 1 else 0
    }

    /**
     * Maps ML Kit's normalised faces into overlay coordinates (PreviewView's
     * FILL_CENTER: scale the upright frame to cover the view, centred) and
     * measures the largest one against the bracket frame.
     */
    private fun evaluate(result: FrameResult): Checks {
        val overlay = binding.overlay
        val frame = overlay.frameRect()
        val viewW = overlay.width.toFloat()
        val viewH = overlay.height.toFloat()
        val goodLight = result.meanLuma >= MIN_LUMA
        if (viewW <= 0f || viewH <= 0f || frame.isEmpty || result.frameAspect <= 0f) {
            return Checks(result.faces.size, centered = false, sizeOk = false, goodLight = goodLight, straight = false, extraFaces = emptyList())
        }

        val scale = max(viewW / result.frameAspect, viewH)
        val shownW = result.frameAspect * scale
        val shownH = scale
        val offsetX = (viewW - shownW) / 2f
        val offsetY = (viewH - shownH) / 2f
        val rects = result.faces.map {
            RectF(
                offsetX + it.left * shownW,
                offsetY + it.top * shownH,
                offsetX + it.right * shownW,
                offsetY + it.bottom * shownH,
            )
        }
        if (rects.isEmpty()) {
            return Checks(0, centered = false, sizeOk = false, goodLight = goodLight, straight = false, extraFaces = emptyList())
        }

        val primaryIndex = result.faces.indices.maxByOrNull { result.faces[it].area } ?: 0
        val primary = rects[primaryIndex]
        val tolerance = frame.width() * EDGE_TOLERANCE
        val inside = primary.left >= frame.left - tolerance &&
            primary.right <= frame.right + tolerance &&
            primary.top >= frame.top - tolerance &&
            primary.bottom <= frame.bottom + tolerance
        val centred = abs(primary.centerX() - frame.centerX()) <= frame.width() * CENTER_TOLERANCE &&
            abs(primary.centerY() - frame.centerY()) <= frame.height() * CENTER_TOLERANCE
        val sizeOk = primary.width() >= frame.width() * MIN_FACE_WIDTH_FRACTION
        val primaryFace = result.faces[primaryIndex]
        val straight = headStraight(primaryFace.yaw, primaryFace.pitch, primaryFace.roll)

        return Checks(
            faceCount = rects.size,
            centered = inside && centred,
            sizeOk = sizeOk,
            goodLight = goodLight,
            straight = straight,
            extraFaces = rects.filterIndexed { index, _ -> index != primaryIndex },
        )
    }

    // ---------------------------------------------------------------- stages

    private fun onShutter() {
        if (stage == Stage.ALIGN) startDetecting()
    }

    /** One detecting run: from here it is 8 good frames to the photo, or a timeout with a reason. */
    private fun startDetecting() {
        attempts++
        detectStartedAt = SystemClock.elapsedRealtime()
        seenFace = false
        seenCentered = false
        seenLight = false
        seenStraight = false
        setStage(Stage.DETECTING)
    }

    private fun setStage(next: Stage) {
        stage = next
        multiRun = 0
        darkRun = 0
        recoverRun = 0
        autoStartRun = 0
        if (next != Stage.DETECTING) {
            goodRun = 0
            binding.overlay.progress = 0f
        }
        if (next != Stage.MULTIFACE) binding.overlay.extraFaces = emptyList()

        analyzer?.enabled = next == Stage.ALIGN || next == Stage.DETECTING ||
            next == Stage.LOWLIGHT || next == Stage.MULTIFACE

        binding.overlay.look = when (next) {
            Stage.ALIGN -> FaceGuideOverlayView.Look.ALIGN
            Stage.DETECTING -> FaceGuideOverlayView.Look.DETECTING
            Stage.LOWLIGHT -> FaceGuideOverlayView.Look.LOWLIGHT
            Stage.MULTIFACE -> FaceGuideOverlayView.Look.MULTIFACE
            Stage.SUCCESS, Stage.UPLOADING -> FaceGuideOverlayView.Look.SUCCESS
            Stage.FAILED, Stage.UPLOAD_FAILED -> FaceGuideOverlayView.Look.FAILED
            Stage.STARTING, Stage.CAMERA_ERROR -> FaceGuideOverlayView.Look.HIDDEN
        }

        val reviewing = next == Stage.SUCCESS || next == Stage.UPLOADING || next == Stage.UPLOAD_FAILED
        binding.reviewImage.visibility = if (reviewing) View.VISIBLE else View.GONE
        applyCloseButton()

        renderPanel()
    }

    private fun renderPanel() {
        val b = binding
        b.checklist.visibility = if (stage == Stage.DETECTING) View.VISIBLE else View.GONE
        b.shutterButton.visibility =
            if (stage == Stage.ALIGN && !config.autoCapture) View.VISIBLE else View.GONE
        b.uploadProgress.visibility = if (stage == Stage.UPLOADING) View.VISIBLE else View.GONE

        var ghost: Action? = null
        var secondary: Action? = null
        var primary: Action? = null

        when (stage) {
            Stage.STARTING, Stage.ALIGN -> {
                copy(
                    R.string.face_align_title,
                    if (config.autoCapture) R.string.face_align_sub_auto else R.string.face_align_sub,
                )
                if (instructionText.isNotBlank()) b.statusSubtitle.text = instructionText
            }
            Stage.DETECTING -> {
                copy(R.string.face_detecting_title, R.string.face_detecting_sub)
                renderChecklist()
            }
            Stage.LOWLIGHT -> {
                copy(R.string.face_lowlight_title, R.string.face_lowlight_sub)
                secondary = Action(R.string.face_lowlight_action) { setStage(Stage.ALIGN) }
            }
            Stage.MULTIFACE -> {
                copy(R.string.face_multiface_title, R.string.face_multiface_sub)
                secondary = Action(R.string.face_multiface_action) { setStage(Stage.ALIGN) }
            }
            Stage.SUCCESS -> {
                copy(R.string.face_success_title, R.string.face_success_sub)
                ghost = Action(R.string.face_action_retake) { retake() }
                primary = Action(R.string.face_action_confirm) { upload() }
            }
            Stage.FAILED -> {
                copy(failure.title, failure.subtitle)
                primary = Action(R.string.face_action_try_again) { setStage(Stage.ALIGN) }
            }
            Stage.UPLOADING -> copy(R.string.face_uploading_title, R.string.face_uploading_sub)
            Stage.UPLOAD_FAILED -> {
                copy(R.string.face_upload_failed_title, subtitle = null)
                ghost = Action(R.string.face_action_retake) { retake() }
                primary = Action(R.string.face_action_try_again) { upload() }
            }
            Stage.CAMERA_ERROR -> {
                copy(R.string.face_camera_error_title, R.string.face_camera_error_sub)
                if (canClose) ghost = Action(R.string.face_cd_close) { cancel() }
                primary = Action(R.string.face_action_try_again) { startCamera() }
            }
        }

        bind(b.ghostButton, ghost)
        bind(b.secondaryButton, secondary)
        bind(b.primaryButton, primary)
        b.actions.visibility =
            if (ghost != null || secondary != null || primary != null) View.VISIBLE else View.GONE
    }

    private class Action(@StringRes val label: Int, val onClick: () -> Unit)

    private fun bind(view: TextView, action: Action?) {
        if (action == null) {
            view.visibility = View.GONE
            view.setOnClickListener(null)
            return
        }
        view.setText(action.label)
        view.setOnClickListener { action.onClick() }
        view.visibility = View.VISIBLE
    }

    /** Title plus an optional subtitle; a null subtitle collapses the line rather than leaving a gap. */
    private fun copy(@StringRes title: Int, @StringRes subtitle: Int?) {
        binding.statusTitle.setText(title)
        if (subtitle == null) {
            binding.statusSubtitle.visibility = View.GONE
        } else {
            binding.statusSubtitle.setText(subtitle)
            binding.statusSubtitle.visibility = View.VISIBLE
        }
    }

    /** Ticks latch once seen during a detecting run, so the list reads as progress rather than flicker. */
    private fun renderChecklist() {
        tick(binding.check1Icon, binding.check1Label, seenFace)
        tick(binding.check2Icon, binding.check2Label, seenCentered)
        tick(binding.check3Icon, binding.check3Label, seenLight)
        tick(binding.check4Icon, binding.check4Label, seenStraight)
    }

    private fun tick(icon: ImageView, label: TextView, done: Boolean) {
        icon.setImageResource(if (done) R.drawable.ic_face_check_done else R.drawable.ic_face_check_ring)
        icon.setColorFilter(color(if (done) R.color.face_accent else R.color.face_check_pending))
        label.setTextColor(color(if (done) R.color.face_text_primary else R.color.face_check_pending_text))
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    // --------------------------------------------------------------- capture

    private fun takePicture() {
        val capture = imageCapture ?: return
        if (!capturing.compareAndSet(false, true)) return
        analyzer?.enabled = false
        binding.overlay.progress = 1f

        val raw = File(outputDir(), "raw_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            worker,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = processCapture(raw)

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exception)
                    raw.delete()
                    capturing.set(false)
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        failure = Failure.CAPTURE
                        setStage(Stage.FAILED)
                    }
                }
            },
        )
    }

    /**
     * Worker thread. Bakes the EXIF rotation into the pixels (the consumer
     * would otherwise render a tagged portrait sideways), caps the long edge
     * and re-encodes — a KYC selfie does not need a 12 MP upload on a metered
     * connection.
     */
    private fun processCapture(raw: File) {
        var delivered: File? = null
        var bitmap: Bitmap? = null
        var quality: PhotoQuality? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.absolutePath, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            val decoded = BitmapFactory.decodeFile(raw.absolutePath, options)
            if (decoded != null) {
                val upright = applyExifRotation(decoded, raw)
                val scaled = capLongEdge(upright)
                val out = File(outputDir(), "face_${System.currentTimeMillis()}.jpg")
                FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                delivered = out
                bitmap = scaled
                if (upright !== decoded) decoded.recycle()
                if (scaled !== upright) upright.recycle()
                // Fail open: a check that cannot run must not block the capture.
                quality = runCatching { photoQuality.measure(scaled) }
                    .onFailure { Log.w(TAG, "quality check failed, skipping", it) }
                    .getOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture processing failed", e)
        }
        raw.delete()

        val file = delivered
        val image = bitmap
        val reject = rejectReason(quality)
        Log.i(
            TAG,
            "capture quality: attempt=$attempts face=${quality?.faceFound} " +
                "blur=${quality?.blurScore?.let { "%.1f".format(it) }} min=${blurMinScore()} " +
                "faceArea=${quality?.faceAreaPct?.let { "%.1f".format(it) }}% " +
                "pose=${quality?.yaw?.let { "%.0f".format(it) }}/${quality?.pitch?.let { "%.0f".format(it) }}/" +
                "${quality?.roll?.let { "%.0f".format(it) }} maxAngle=${maxHeadAngle()} verdict=${reject ?: "ok"}",
        )
        capturing.set(false)
        main.post {
            if (isFinishing || isDestroyed) {
                file?.delete()
                return@post
            }
            if (file == null || image == null) {
                failure = Failure.CAPTURE
                setStage(Stage.FAILED)
                return@post
            }
            if (reject != null) {
                file.delete()
                failure = reject
                setStage(Stage.FAILED)
                return@post
            }
            capturedFile?.delete()
            capturedFile = file
            lastQuality = quality
            binding.reviewImage.setImageBitmap(image)
            setStage(Stage.SUCCESS)
        }
    }

    /**
     * Why the still cannot be used, or null to accept it. Only an *evaluated*
     * failing check rejects; anything unmeasured lets the photo through.
     */
    private fun rejectReason(quality: PhotoQuality?): Failure? {
        if (quality == null) return null
        if (quality.faceFound == false) return Failure.NO_FACE
        if (quality.yaw != null && quality.pitch != null && quality.roll != null &&
            !headStraight(quality.yaw, quality.pitch, quality.roll)
        ) {
            return Failure.TURNED
        }
        val score = quality.blurScore ?: return null
        val minScore = blurMinScore()
        if (minScore <= 0 || score >= minScore || blurGateRelaxed) return null
        blurRejects++
        if (blurRejects >= BLUR_MAX_REJECTS) {
            // A camera that cannot produce a photo this gate likes (heavy
            // denoise, a scratched lens) must not strand a mandatory capture.
            // Let this one through and tell the backend via meta_data.
            blurGateRelaxed = true
            Log.w(TAG, "blur gate relaxed after $blurRejects rejections")
            return null
        }
        return Failure.BLURRY
    }

    /** Backend-supplied threshold when present, else the client default. Zero disables the gate. */
    private fun blurMinScore(): Double =
        if (config.blurMinScore >= 0) config.blurMinScore else DEFAULT_BLUR_MIN_SCORE

    /** Backend-supplied limit when present, else the client default. Zero disables the gate. */
    private fun maxHeadAngle(): Double =
        if (config.maxHeadAngle >= 0) config.maxHeadAngle else DEFAULT_MAX_HEAD_ANGLE

    /** All three Euler angles within the limit. The front camera mirrors yaw's sign; an absolute limit does not care. */
    private fun headStraight(yaw: Float, pitch: Float, roll: Float): Boolean {
        val limit = maxHeadAngle()
        if (limit <= 0) return true
        return abs(yaw) <= limit && abs(pitch) <= limit && abs(roll) <= limit
    }

    /**
     * Capture-quality numbers for the upload's `meta_data`. The RN modal always
     * sent `{}`; these let the backend calibrate the blur threshold on real
     * photos before tightening it via `face_capture.blurMinScore`.
     */
    private fun metaDataJson(): String {
        val quality = lastQuality
        return JSONObject().apply {
            put("attempts", attempts)
            put("blur_score", quality?.blurScore ?: JSONObject.NULL)
            put("blur_min_score", blurMinScore())
            put("blur_rejects", blurRejects)
            put(
                "blur_gate",
                when {
                    blurMinScore() <= 0 -> "off"
                    blurGateRelaxed -> "relaxed"
                    else -> "enforced"
                },
            )
            put("capture_mode", config.captureMode)
            put("head_yaw", quality?.yaw ?: JSONObject.NULL)
            put("head_pitch", quality?.pitch ?: JSONObject.NULL)
            put("head_roll", quality?.roll ?: JSONObject.NULL)
            put("max_head_angle", maxHeadAngle())
            put("face_found", quality?.faceFound ?: JSONObject.NULL)
            put("face_area_pct", quality?.faceAreaPct ?: JSONObject.NULL)
        }.toString()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longEdge = max(width, height)
        while (longEdge / 2 >= MAX_DIMENSION) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    private fun applyExifRotation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun capLongEdge(bitmap: Bitmap): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun retake() {
        capturedFile?.delete()
        capturedFile = null
        binding.reviewImage.setImageDrawable(null)
        setStage(Stage.ALIGN)
    }

    // ---------------------------------------------------------------- upload

    private fun upload() {
        val file = capturedFile ?: return setStage(Stage.ALIGN)
        setStage(Stage.UPLOADING)
        worker.execute {
            val outcome = uploader.upload(file, config.upload, metaDataJson())
            main.post {
                if (isFinishing || isDestroyed) return@post
                when (outcome) {
                    is FaceCaptureUploader.Outcome.Success -> finishUploaded(outcome)
                    is FaceCaptureUploader.Outcome.Failure -> {
                        Log.w(TAG, "upload failed: ${outcome.message}")
                        if (pendingDismiss) finishDismissed() else setStage(Stage.UPLOAD_FAILED)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- result

    private fun finishUploaded(outcome: FaceCaptureUploader.Outcome.Success) {
        capturedFile?.delete()
        capturedFile = null
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT_STATUS, STATUS_UPLOADED)
                .putExtra(EXTRA_RESULT_HTTP_CODE, outcome.httpCode)
                .putExtra(EXTRA_RESULT_BODY, outcome.body),
        )
        finish()
    }

    /** Backend-driven close: no photo is kept and JS closes Redux silently (no close analytics). */
    private fun finishDismissed() {
        capturedFile?.delete()
        capturedFile = null
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_STATUS, STATUS_DISMISSED))
        finish()
    }

    private fun cancel() {
        capturedFile?.delete()
        capturedFile = null
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun finishWithError(reason: String) {
        setResult(RESULT_ERROR, Intent().putExtra(EXTRA_RESULT_ERROR, reason))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { cameraProvider?.unbindAll() }
        runCatching { analyzer?.close() }
        analyzer = null
        runCatching { photoQuality.close() }
        worker.shutdown()
        capturedFile?.delete()
    }

    private fun outputDir(): File =
        File(cacheDir, CAPTURE_DIR).apply { if (!exists()) mkdirs() }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FaceCapture"
        private const val CAPTURE_DIR = "face-capture"

        /** Analysis stream target; small enough for a 2 GB device, plenty for face bounds. */
        private val ANALYSIS_TARGET = Size(480, 640)

        /** Consecutive all-good frames (~1.3 s at the analyser's cadence) before the shutter fires. */
        private const val REQUIRED_GOOD_FRAMES = 8

        /** How long the detecting phase keeps trying before it explains what it could not confirm. */
        private const val DETECT_TIMEOUT_MS = 8_000L

        /** Hysteresis for the low-light and multiple-face states, in consecutive frames. */
        private const val HAZARD_ENTER_FRAMES = 5
        private const val HAZARD_EXIT_FRAMES = 10

        /** Mean Y-plane value below which the scene counts as too dark. */
        private const val MIN_LUMA = 50

        /** The face must fill at least this much of the bracket frame's width. */
        private const val MIN_FACE_WIDTH_FRACTION = 0.5f

        /** How far outside the frame the face box may spill, as a fraction of frame width. */
        private const val EDGE_TOLERANCE = 0.12f

        /** How far the face centre may sit from the frame centre, as a fraction of frame size. */
        private const val CENTER_TOLERANCE = 0.2f

        /**
         * Client default for the blur gate (Laplacian variance on the 320 px-wide
         * face crop). Calibrated 2026-09-03 on a Pixel 9 front camera: steady
         * captures scored 305 / 400 / 507, a visibly motion-smeared one 51–60, and
         * offline blurring of a sharp crop gave ~170 (mild) → ~95 (clear) → ~60
         * (bad). 100 rejects the clear cases with a 3x margin under the sharp ones.
         * Budget front cameras with heavy denoise score lower, hence
         * BLUR_MAX_REJECTS below and the backend override `face_capture.blurMinScore`
         * (`0` switches the gate off). Fleet data arrives in meta_data.blur_score.
         */
        private const val DEFAULT_BLUR_MIN_SCORE = 100.0

        /**
         * Largest yaw / pitch / roll (degrees) allowed, live and in the still.
         * Typical identity-photo limits are 15° of turn and about 12° of tilt;
         * ML Kit stops detecting faces at roughly 35–40° of yaw anyway.
         */
        private const val DEFAULT_MAX_HEAD_ANGLE = 15.0

        /** Blur rejections in one session before the gate stops enforcing. */
        private const val BLUR_MAX_REJECTS = 3

        /** Consecutive all-good frames (~0.5 s) before auto mode starts a detecting run. */
        private const val AUTO_START_FRAMES = 3

        /** Must match activity_face_capture.xml's panel height — see the comment there. */
        private const val PANEL_HEIGHT_DP = 216

        private const val MAX_DIMENSION = 1280
        private const val JPEG_QUALITY = 85

        /** Distinct from RESULT_CANCELED: the DB did not decline, the screen could not run. */
        const val RESULT_ERROR = 2

        const val EXTRA_RESULT_STATUS = "face_capture_status"
        const val EXTRA_RESULT_ERROR = "face_capture_error"
        /** Set with STATUS_UPLOADED: the endpoint's HTTP status and (capped) response text. */
        const val EXTRA_RESULT_HTTP_CODE = "face_capture_http_code"
        const val EXTRA_RESULT_BODY = "face_capture_body"
        const val STATUS_UPLOADED = "uploaded"
        const val STATUS_DISMISSED = "dismissed"

        /** RESULT_ERROR reason when the CAMERA permission is missing; JS then waits for the PermissionsGate. */
        const val ERROR_CAMERA_PERMISSION = "camera_permission_denied"
        /** RESULT_ERROR reason when the in-screen prompt (requestPermission) was refused and cannot be shown again. */
        const val ERROR_CAMERA_PERMISSION_BLOCKED = "camera_permission_blocked"
        private const val REQUEST_CAMERA_PERMISSION = 0x9A03
    }
}
