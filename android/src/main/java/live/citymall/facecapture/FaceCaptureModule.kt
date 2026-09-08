package live.citymall.facecapture

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap

/**
 * JS entry point for the native face-capture screen.
 *
 * Old-bridge ReactContextBaseJavaModule to match AppSettingsIntentModule —
 * New Architecture interop handles it, and there is no codegen setup in this
 * repo to hang a TurboModule spec off.
 *
 * `launch(config)` resolves with `{ status: "uploaded" | "closed" | "dismissed" }`
 * once the activity finishes — closed by the DB, dismissed by the backend
 * (JS called `dismiss()` after `faceCaptureVisible` went false). While it
 * runs, `update({ canClose, text })` and `dismiss()` reach the activity
 * through FaceCaptureSession, keeping Redux live for the whole session as it
 * was for the RN modal. It rejects only when the screen could not run at all
 * (no foreground activity, a launch already in flight, or a config the
 * activity refused); JS treats a rejection as "fall back to the React Native
 * modal" so a device that cannot run this screen is never stranded.
 */
class FaceCaptureModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    private var pendingPromise: Promise? = null

    private val activityEventListener: ActivityEventListener =
        object : BaseActivityEventListener() {
            override fun onActivityResult(
                activity: Activity,
                requestCode: Int,
                resultCode: Int,
                data: Intent?,
            ) {
                if (requestCode != REQUEST_CODE) return
                val promise = pendingPromise ?: return
                pendingPromise = null

                when (resultCode) {
                    Activity.RESULT_OK -> promise.resolve(result(data))
                    Activity.RESULT_CANCELED -> promise.resolve(result(STATUS_CLOSED))
                    FaceCaptureActivity.RESULT_ERROR -> promise.reject(
                        E_FACE_CAPTURE,
                        data?.getStringExtra(FaceCaptureActivity.EXTRA_RESULT_ERROR) ?: "capture_failed",
                    )
                    else -> promise.reject(E_FACE_CAPTURE, "unexpected_result_$resultCode")
                }
            }
        }

    init {
        reactContext.addActivityEventListener(activityEventListener)
    }

    override fun getName(): String = NAME

    override fun invalidate() {
        reactContext.removeActivityEventListener(activityEventListener)
        // A pending promise outliving the module would hang the JS caller's
        // await forever, leaving face_capture stuck in its "uploading" state.
        pendingPromise?.reject(E_FACE_CAPTURE, "module_invalidated")
        pendingPromise = null
        super.invalidate()
    }

    @ReactMethod
    fun launch(config: ReadableMap?, promise: Promise) {
        if (pendingPromise != null) {
            promise.reject(E_ALREADY_RUNNING, "a face capture is already in progress")
            return
        }

        // Null under the New Architecture when the host activity is being
        // recreated, or if the app is backgrounded between dispatch and launch.
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject(E_NO_ACTIVITY, "no foreground activity to launch face capture from")
            return
        }

        pendingPromise = promise
        FaceCaptureSession.reset()
        try {
            activity.startActivityForResult(
                FaceCaptureConfig.intent(activity, toConfig(config)),
                REQUEST_CODE,
            )
        } catch (e: Exception) {
            pendingPromise = null
            promise.reject(E_FACE_CAPTURE, e)
        }
    }

    /**
     * Live changes for a running session. Only the keys present are applied;
     * `canClose` toggles the close button and back handling, `text` the
     * instruction line. Safe to call when nothing is running (held for the
     * next attach, cleared at the next launch).
     */
    @ReactMethod
    fun update(changes: ReadableMap?) {
        val canClose = changes?.takeIf { it.hasKey("canClose") && !it.isNull("canClose") }
            ?.getBoolean("canClose")
        val text = changes?.takeIf { it.hasKey("text") && !it.isNull("text") }?.getString("text")
        FaceCaptureSession.update(canClose, text)
    }

    /** The backend withdrew the request. The activity finishes with `dismissed` unless an upload is in flight. */
    @ReactMethod
    fun dismiss() {
        FaceCaptureSession.dismiss()
    }

    private fun toConfig(map: ReadableMap?): FaceCaptureConfig {
        val upload = map?.takeIf { it.hasKey("upload") && !it.isNull("upload") }?.getMap("upload")
        return FaceCaptureConfig(
            instructionText = map.string("text"),
            canClose = map?.takeIf { it.hasKey("canClose") && !it.isNull("canClose") }
                ?.getBoolean("canClose") ?: false,
            blurMinScore = map?.takeIf { it.hasKey("blurMinScore") && !it.isNull("blurMinScore") }
                ?.getDouble("blurMinScore") ?: -1.0,
            captureMode = map.string("captureMode").takeIf { it == FaceCaptureConfig.CAPTURE_MODE_MANUAL }
                ?: FaceCaptureConfig.CAPTURE_MODE_AUTO,
            maxHeadAngle = map?.takeIf { it.hasKey("maxHeadAngle") && !it.isNull("maxHeadAngle") }
                ?.getDouble("maxHeadAngle") ?: -1.0,
            requestPermission = map?.takeIf { it.hasKey("requestPermission") && !it.isNull("requestPermission") }
                ?.getBoolean("requestPermission") ?: false,
            upload = UploadSpec(
                url = upload.string("url"),
                headers = upload.stringMap("headers"),
                fields = upload.stringMap("fields"),
                fileField = upload.string("fileField").ifBlank { UploadSpec.DEFAULT_FILE_FIELD },
                fileName = upload.string("fileName").ifBlank { UploadSpec.DEFAULT_FILE_NAME },
                metaDataField = upload.string("metaDataField").ifBlank { UploadSpec.DEFAULT_META_FIELD },
            ),
        )
    }

    private fun ReadableMap?.string(key: String): String =
        this?.takeIf { it.hasKey(key) && !it.isNull(key) }?.getString(key).orEmpty()

    /**
     * A flat `{ name: value }` map to form-field / header strings. Numbers and
     * booleans are stringified; nulls and nested values are dropped — the JS
     * wrapper normalises before launch, this is the last line of defence.
     */
    private fun ReadableMap?.stringMap(key: String): Map<String, String> {
        val nested = this?.takeIf { it.hasKey(key) && !it.isNull(key) }?.getMap(key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = nested.keySetIterator()
        while (keys.hasNextKey()) {
            val name = keys.nextKey()
            when (nested.getType(name)) {
                ReadableType.String -> out[name] = nested.getString(name).orEmpty()
                ReadableType.Number -> {
                    val d = nested.getDouble(name)
                    out[name] = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
                }
                ReadableType.Boolean -> out[name] = nested.getBoolean(name).toString()
                else -> Unit
            }
        }
        return out
    }

    private fun result(status: String): WritableMap =
        Arguments.createMap().apply { putString("status", status) }

    /** RESULT_OK payload: the status the activity set, plus the endpoint response when it uploaded. */
    private fun result(data: Intent?): WritableMap {
        val map = result(data?.getStringExtra(FaceCaptureActivity.EXTRA_RESULT_STATUS) ?: STATUS_UPLOADED)
        if (data != null && data.hasExtra(FaceCaptureActivity.EXTRA_RESULT_HTTP_CODE)) {
            map.putMap(
                "response",
                Arguments.createMap().apply {
                    putInt("status", data.getIntExtra(FaceCaptureActivity.EXTRA_RESULT_HTTP_CODE, 0))
                    putString("body", data.getStringExtra(FaceCaptureActivity.EXTRA_RESULT_BODY).orEmpty())
                },
            )
        }
        return map
    }

    companion object {
        const val NAME = "FaceCaptureModule"

        /** Arbitrary but must stay stable and unique within the host activity. */
        private const val REQUEST_CODE = 0x9A02

        private const val STATUS_UPLOADED = "uploaded"
        private const val STATUS_CLOSED = "closed"

        private const val E_NO_ACTIVITY = "E_NO_ACTIVITY"
        private const val E_ALREADY_RUNNING = "E_ALREADY_RUNNING"
        private const val E_FACE_CAPTURE = "E_FACE_CAPTURE"
    }
}
