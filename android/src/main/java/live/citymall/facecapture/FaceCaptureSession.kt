package live.citymall.facecapture

import android.os.Handler
import android.os.Looper

/**
 * Process-local channel from [FaceCaptureModule] to a running
 * [FaceCaptureActivity].
 *
 * Redux stays the source of truth for the whole session, as it was for the
 * React Native modal: the JS controller pushes changes to the close policy
 * and instruction text here, plus dismiss requests when the backend clears
 * `faceCaptureVisible`, and the activity applies them live instead of reading
 * its config once at launch.
 *
 * Everything runs on the main thread. Values that arrive before the activity
 * has attached (an update racing the launch) are held and delivered on attach,
 * so nothing is lost in that gap.
 */
object FaceCaptureSession {

    interface Listener {
        /** Null means "unchanged" for either field. */
        fun onPolicyChanged(canClose: Boolean?, instructionText: String?)
        fun onDismissRequested()
    }

    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var pendingCanClose: Boolean? = null
    private var pendingText: String? = null
    private var pendingDismiss = false

    /** Called by the module right before starting the activity, so stale pending values never leak into a new session. */
    fun reset() = main.post {
        pendingCanClose = null
        pendingText = null
        pendingDismiss = false
    }

    fun attach(l: Listener) = main.post {
        listener = l
        if (pendingCanClose != null || pendingText != null) {
            l.onPolicyChanged(pendingCanClose, pendingText)
        }
        if (pendingDismiss) l.onDismissRequested()
        pendingCanClose = null
        pendingText = null
        pendingDismiss = false
    }

    fun detach(l: Listener) = main.post {
        if (listener === l) listener = null
    }

    fun update(canClose: Boolean?, instructionText: String?) = main.post {
        val l = listener
        if (l != null) {
            l.onPolicyChanged(canClose, instructionText)
        } else {
            if (canClose != null) pendingCanClose = canClose
            if (instructionText != null) pendingText = instructionText
        }
    }

    fun dismiss() = main.post {
        val l = listener
        if (l != null) l.onDismissRequested() else pendingDismiss = true
    }
}
