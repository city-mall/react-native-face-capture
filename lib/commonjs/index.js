"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.dismissFaceCapture = exports.updateFaceCapture = exports.launchFaceCapture = exports.isCameraPermissionBlockedError = exports.isCameraPermissionError = exports.isAlreadyRunningError = exports.isNoActivityError = exports.normalizeFaceCaptureConfig = exports.isFaceCaptureAvailable = exports.NATIVE_DEFAULT_THRESHOLD = exports.BAD_CONFIG = exports.CAMERA_PERMISSION_BLOCKED = exports.CAMERA_PERMISSION_DENIED = exports.E_FACE_CAPTURE = exports.E_ALREADY_RUNNING = exports.E_NO_ACTIVITY = void 0;
const react_native_1 = require("react-native");
const nativeModule = react_native_1.NativeModules?.FaceCaptureModule;
/** Rejection code when React has no foreground activity to launch from. Retry after a moment. */
exports.E_NO_ACTIVITY = "E_NO_ACTIVITY";
/** Rejection code when a capture is already in flight. */
exports.E_ALREADY_RUNNING = "E_ALREADY_RUNNING";
/** Rejection code for anything the activity itself refused to run with; see `message`. */
exports.E_FACE_CAPTURE = "E_FACE_CAPTURE";
/** `E_FACE_CAPTURE` message when the activity was launched without the CAMERA permission (or the in-screen prompt was refused). */
exports.CAMERA_PERMISSION_DENIED = "camera_permission_denied";
/**
 * `E_FACE_CAPTURE` message when the in-screen prompt (`requestPermission`) was
 * refused and Android will not show it again. Re-launching is pointless;
 * send the user to app settings.
 */
exports.CAMERA_PERMISSION_BLOCKED = "camera_permission_blocked";
/** `E_FACE_CAPTURE` message when the upload spec had no URL. */
exports.BAD_CONFIG = "bad_config";
/** Sentinel the native side reads as "use the client default" for a threshold. */
exports.NATIVE_DEFAULT_THRESHOLD = -1;
const isFaceCaptureAvailable = () => react_native_1.Platform.OS === "android" && typeof nativeModule?.launch === "function";
exports.isFaceCaptureAvailable = isFaceCaptureAvailable;
const asThreshold = (value) => typeof value === "number" && Number.isFinite(value) && value >= 0
    ? value
    : exports.NATIVE_DEFAULT_THRESHOLD;
const asCaptureMode = (value) => value === "manual" ? "manual" : "auto";
const asFieldString = (value) => {
    if (typeof value === "string")
        return value;
    if (typeof value === "number" && Number.isFinite(value))
        return String(value);
    if (typeof value === "boolean")
        return String(value);
    return undefined;
};
const stringRecord = (input) => {
    const out = {};
    if (!input)
        return out;
    for (const key of Object.keys(input)) {
        const value = asFieldString(input[key]);
        if (value !== undefined)
            out[key] = value;
    }
    return out;
};
/**
 * Pure mapping from the public config onto the native contract: defaults
 * filled, thresholds sentinelled, fields stringified. Throws on a missing or
 * non-http(s) upload URL so the mistake surfaces in JS, before a camera opens.
 */
const normalizeFaceCaptureConfig = (config) => {
    const url = typeof config.upload?.url === "string" ? config.upload.url.trim() : "";
    if (!/^https?:\/\//i.test(url)) {
        throw new Error(`react-native-face-capture: upload.url must be an absolute http(s) URL, got "${url}"`);
    }
    return {
        title: typeof config.title === "string" ? config.title.trim() : "",
        text: typeof config.text === "string" ? config.text.trim() : "",
        canClose: Boolean(config.canClose),
        captureMode: asCaptureMode(config.captureMode),
        blurMinScore: asThreshold(config.blurMinScore),
        maxHeadAngle: asThreshold(config.maxHeadAngle),
        requestPermission: config.requestPermission === true,
        upload: {
            url,
            headers: stringRecord(config.upload.headers),
            fields: stringRecord(config.upload.fields),
            fileField: config.upload.fileField?.trim() || "image",
            fileName: config.upload.fileName?.trim() || "faceCapture.jpg",
            metaDataField: config.upload.metaDataField?.trim() || "meta_data",
        },
    };
};
exports.normalizeFaceCaptureConfig = normalizeFaceCaptureConfig;
const errorCode = (error) => typeof error === "object" && error !== null
    ? error.code
    : undefined;
const errorText = (error) => typeof error === "object" && error !== null
    ? error.message
    : undefined;
const isNoActivityError = (error) => errorCode(error) === exports.E_NO_ACTIVITY;
exports.isNoActivityError = isNoActivityError;
const isAlreadyRunningError = (error) => errorCode(error) === exports.E_ALREADY_RUNNING;
exports.isAlreadyRunningError = isAlreadyRunningError;
/** The activity bailed because CAMERA is not granted — a "not yet", not a failure. Covers the blocked case too. */
const isCameraPermissionError = (error) => errorCode(error) === exports.E_FACE_CAPTURE &&
    (errorText(error) === exports.CAMERA_PERMISSION_DENIED ||
        errorText(error) === exports.CAMERA_PERMISSION_BLOCKED);
exports.isCameraPermissionError = isCameraPermissionError;
/** The in-screen prompt was refused for good; only app settings can grant CAMERA now. */
const isCameraPermissionBlockedError = (error) => errorCode(error) === exports.E_FACE_CAPTURE &&
    errorText(error) === exports.CAMERA_PERMISSION_BLOCKED;
exports.isCameraPermissionBlockedError = isCameraPermissionBlockedError;
const parseResponse = (response) => {
    if (!response)
        return undefined;
    let json;
    try {
        json = response.body ? JSON.parse(response.body) : undefined;
    }
    catch {
        json = undefined;
    }
    return { status: response.status, body: response.body, json };
};
/**
 * Opens the native screen over whatever is on screen and resolves when it
 * finishes. Rejects only when the screen could not run at all (see the
 * `E_*` codes); a failed upload stays on screen with a retry, it never
 * rejects.
 */
const launchFaceCapture = async (config) => {
    if (!nativeModule?.launch) {
        throw new Error("react-native-face-capture: FaceCaptureModule is not available on this platform/binary");
    }
    const native = (0, exports.normalizeFaceCaptureConfig)(config);
    const result = await nativeModule.launch(native);
    return { status: result.status, response: parseResponse(result.response) };
};
exports.launchFaceCapture = launchFaceCapture;
/**
 * Push a change into the running session: toggle the close button, replace
 * the instruction line. Safe to call when nothing is running.
 */
const updateFaceCapture = (changes) => {
    nativeModule?.update?.(changes);
};
exports.updateFaceCapture = updateFaceCapture;
/** Ask a running screen to leave (`dismissed`). Mid-upload it waits for the outcome first. */
const dismissFaceCapture = () => {
    nativeModule?.dismiss?.();
};
exports.dismissFaceCapture = dismissFaceCapture;
