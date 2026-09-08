package live.citymall.facecapture

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/** Mirrors AppSettingsIntentPackage — registered in MainApplication.getPackages(). */
class FaceCapturePackage : ReactPackage {

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
        listOf(FaceCaptureModule(reactContext))

    // RN 0.86 deprecates this ReactPackage member; the interface still requires it.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        emptyList()
}
