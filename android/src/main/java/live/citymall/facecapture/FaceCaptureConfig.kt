package live.citymall.facecapture

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Where the captured JPEG goes.
 *
 * The SDK owns the shape of the multipart body — the file part and the
 * `meta_data` quality JSON. The host owns everything that identifies the
 * request: the URL, auth and app headers, and the form fields its endpoint
 * expects (event type, idempotency key, coordinates…). Nothing here knows
 * which backend it is talking to.
 */
data class UploadSpec(
    val url: String,
    val headers: Map<String, String>,
    val fields: Map<String, String>,
    val fileField: String,
    val fileName: String,
    val metaDataField: String,
) {
    fun applyTo(intent: Intent): Intent = intent
        .putExtra(EXTRA_URL, url)
        .putExtra(EXTRA_HEADERS, JSONObject(headers).toString())
        .putExtra(EXTRA_FIELDS, JSONObject(fields).toString())
        .putExtra(EXTRA_FILE_FIELD, fileField)
        .putExtra(EXTRA_FILE_NAME, fileName)
        .putExtra(EXTRA_META_FIELD, metaDataField)

    companion object {
        const val DEFAULT_FILE_FIELD = "image"
        const val DEFAULT_FILE_NAME = "faceCapture.jpg"
        const val DEFAULT_META_FIELD = "meta_data"

        private const val EXTRA_URL = "face_capture_upload_url"
        private const val EXTRA_HEADERS = "face_capture_upload_headers"
        private const val EXTRA_FIELDS = "face_capture_upload_fields"
        private const val EXTRA_FILE_FIELD = "face_capture_upload_file_field"
        private const val EXTRA_FILE_NAME = "face_capture_upload_file_name"
        private const val EXTRA_META_FIELD = "face_capture_upload_meta_field"

        fun from(intent: Intent?): UploadSpec = UploadSpec(
            url = intent?.getStringExtra(EXTRA_URL).orEmpty().trim(),
            headers = jsonMap(intent?.getStringExtra(EXTRA_HEADERS)),
            fields = jsonMap(intent?.getStringExtra(EXTRA_FIELDS)),
            fileField = intent.stringOr(EXTRA_FILE_FIELD, DEFAULT_FILE_FIELD),
            fileName = intent.stringOr(EXTRA_FILE_NAME, DEFAULT_FILE_NAME),
            metaDataField = intent.stringOr(EXTRA_META_FIELD, DEFAULT_META_FIELD),
        )

        private fun Intent?.stringOr(key: String, default: String): String =
            this?.getStringExtra(key)?.takeIf { it.isNotBlank() } ?: default

        /**
         * `{"k":"v",…}` → map of strings. Null values are dropped; anything
         * unparseable is an empty map, so a broken spec surfaces as
         * `bad_config` from the activity rather than as a crash here.
         */
        private fun jsonMap(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                val obj = JSONObject(raw)
                val out = LinkedHashMap<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.get(key)
                    if (value != JSONObject.NULL) out[key] = value.toString()
                }
                out as Map<String, String>
            }.getOrDefault(emptyMap())
        }
    }
}

/**
 * Everything the capture screen needs, handed over by [FaceCaptureModule] as
 * plain intent extras. JS stays the source of truth for all of it — copy,
 * close policy, quality thresholds and the upload spec — and the activity
 * reads nothing from storage, BuildConfig or the network on its own.
 */
data class FaceCaptureConfig(
    /** Shown under the align title when non-blank. */
    val instructionText: String,
    /** `false` hides the close button and swallows back. */
    val canClose: Boolean,
    /**
     * Minimum Laplacian-variance blur score for the captured face crop.
     * `< 0` means "use the client default", `0` disables the gate, `> 0` is the
     * threshold.
     */
    val blurMinScore: Double,
    /**
     * `"auto"` (default): the detecting phase starts by itself once the face is
     * good and there is no shutter. `"manual"`: the user taps the shutter.
     */
    val captureMode: String,
    /**
     * Largest head angle (degrees of yaw, pitch or roll) the face may show,
     * live and in the still. `< 0` = client default, `0` = gate off.
     */
    val maxHeadAngle: Double,
    /**
     * `true`: if CAMERA is not granted, ask for it from this screen (one system
     * prompt) before opening the camera. `false` (default): never prompt —
     * finish at once with `camera_permission_denied` and let the host's own
     * permission flow handle it.
     */
    val requestPermission: Boolean,
    val upload: UploadSpec,
) {
    val autoCapture: Boolean get() = captureMode != CAPTURE_MODE_MANUAL

    /** Without somewhere to upload to the screen cannot finish its job; bail before opening the camera. */
    val isUploadable: Boolean get() = upload.url.isNotBlank()

    fun applyTo(intent: Intent): Intent = upload.applyTo(
        intent
            .putExtra(EXTRA_TEXT, instructionText)
            .putExtra(EXTRA_CAN_CLOSE, canClose)
            .putExtra(EXTRA_BLUR_MIN_SCORE, blurMinScore)
            .putExtra(EXTRA_CAPTURE_MODE, captureMode)
            .putExtra(EXTRA_MAX_HEAD_ANGLE, maxHeadAngle)
            .putExtra(EXTRA_REQUEST_PERMISSION, requestPermission),
    )

    companion object {
        private const val EXTRA_TEXT = "face_capture_text"
        private const val EXTRA_CAN_CLOSE = "face_capture_can_close"
        private const val EXTRA_BLUR_MIN_SCORE = "face_capture_blur_min_score"
        private const val USE_DEFAULT_BLUR_MIN_SCORE = -1.0
        private const val EXTRA_CAPTURE_MODE = "face_capture_capture_mode"
        const val CAPTURE_MODE_AUTO = "auto"
        const val CAPTURE_MODE_MANUAL = "manual"
        private const val EXTRA_MAX_HEAD_ANGLE = "face_capture_max_head_angle"
        private const val USE_DEFAULT_MAX_HEAD_ANGLE = -1.0
        private const val EXTRA_REQUEST_PERMISSION = "face_capture_request_permission"

        fun from(intent: Intent?): FaceCaptureConfig = FaceCaptureConfig(
            instructionText = intent?.getStringExtra(EXTRA_TEXT).orEmpty().trim(),
            canClose = intent?.getBooleanExtra(EXTRA_CAN_CLOSE, false) ?: false,
            blurMinScore = numberExtra(intent, EXTRA_BLUR_MIN_SCORE) ?: USE_DEFAULT_BLUR_MIN_SCORE,
            captureMode = intent?.getStringExtra(EXTRA_CAPTURE_MODE)
                ?.takeIf { it == CAPTURE_MODE_MANUAL } ?: CAPTURE_MODE_AUTO,
            maxHeadAngle = numberExtra(intent, EXTRA_MAX_HEAD_ANGLE) ?: USE_DEFAULT_MAX_HEAD_ANGLE,
            requestPermission = intent?.getBooleanExtra(EXTRA_REQUEST_PERMISSION, false) ?: false,
            upload = UploadSpec.from(intent),
        )

        /**
         * The module writes a Double; adb's `am start` can only write floats, ints
         * or strings. Accept them all. Bundle.get() is deprecated from API 33 with
         * no typed replacement for "any primitive"; it keeps working.
         */
        @Suppress("DEPRECATION")
        private fun numberExtra(intent: Intent?, key: String): Double? =
            when (val value = intent?.extras?.get(key)) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }

        fun intent(context: Context, config: FaceCaptureConfig): Intent =
            config.applyTo(Intent(context, FaceCaptureActivity::class.java))
    }
}
