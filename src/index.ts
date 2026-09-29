import { NativeModules, Platform } from "react-native";

/**
 * react-native-face-capture — JS surface of the native Android screen
 * (`live.citymall.facecapture.FaceCaptureActivity`).
 *
 * The SDK owns the camera, the live and still face checks, the JPEG and the
 * multipart POST. The host owns policy and identity: when to launch, the
 * CAMERA permission, the endpoint, auth headers, and the form fields that
 * endpoint expects (event type, idempotency key, coordinates…).
 *
 * Android only. On any other platform `isFaceCaptureAvailable()` is false and
 * `launchFaceCapture()` rejects.
 */

/**
 * `uploaded`: the endpoint accepted the photo (2xx) — `response` is set.
 * `closed`: the user closed the screen (only possible with `canClose`).
 * `dismissed`: the host called `dismissFaceCapture()` and the screen left.
 */
export type FaceCaptureStatus = "uploaded" | "closed" | "dismissed";

/** `"auto"` (default) captures by itself once the face is steady; `"manual"` shows a shutter. */
export type FaceCaptureMode = "auto" | "manual";

/** Form-field values are stringified natively; nulls and nested values are dropped. */
export type FaceCaptureFieldValue = string | number | boolean;

export interface FaceCaptureUpload {
  /** Absolute http(s) URL the JPEG is POSTed to as multipart/form-data. */
  url: string;
  /** Sent verbatim, e.g. `Authorization`, `x-app-name`, `x-app-version`. */
  headers?: Record<string, string>;
  /**
   * Text parts sent beside the file — whatever the endpoint needs:
   * `event_type`, `client_event_id`, `lat`, `lng`, `captured_at`…
   */
  fields?: Record<string, FaceCaptureFieldValue | null | undefined>;
  /** Multipart name of the JPEG part. Default `image`. */
  fileField?: string;
  /** Filename sent with the JPEG part. Default `faceCapture.jpg`. */
  fileName?: string;
  /** Multipart name of the capture-quality JSON the SDK adds. Default `meta_data`. */
  metaDataField?: string;
}

export interface FaceCaptureConfig {
  /** Top-bar title, e.g. "Mark In Attendance". Empty → the screen's default ("Verify your identity"). */
  title?: string;
  /** Instruction line under the align title. Empty → the screen's default copy. */
  text?: string;
  /** `false` (default) hides the close button and swallows back: the capture is mandatory. */
  canClose?: boolean;
  captureMode?: FaceCaptureMode;
  /**
   * Minimum Laplacian-variance blur score for the captured face crop.
   * Omit for the native default (100); `0` disables the gate.
   */
  blurMinScore?: number;
  /**
   * Largest head angle in degrees (yaw, pitch or roll) accepted live and in
   * the still. Omit for the native default (15); `0` disables the gate.
   */
  maxHeadAngle?: number;
  /**
   * `true`: if CAMERA is not granted, the screen asks for it (one system
   * prompt) before opening the camera. `false` (default): the screen never
   * prompts and rejects with `camera_permission_denied`; the host owns the
   * permission flow.
   */
  requestPermission?: boolean;
  upload: FaceCaptureUpload;
}

/** Live changes the host can push into a running session; only present keys apply. */
export interface FaceCaptureUpdate {
  canClose?: boolean;
  text?: string;
}

export interface FaceCaptureResponse {
  /** HTTP status of the upload. */
  status: number;
  /** Response text, capped at 64 KiB natively. */
  body: string;
  /** `body` parsed as JSON when it is JSON, else `undefined`. */
  json?: unknown;
}

export interface FaceCaptureResult {
  status: FaceCaptureStatus;
  /** Present only for `uploaded`. */
  response?: FaceCaptureResponse;
}

/**
 * The capture-quality JSON the SDK sends as `meta_data` with every upload.
 * Numbers are `null` when the still check did not run (a detector error).
 */
export interface FaceCaptureQualityMeta {
  attempts: number;
  blur_score: number | null;
  blur_min_score: number;
  blur_rejects: number;
  blur_gate: "enforced" | "relaxed" | "off";
  capture_mode: FaceCaptureMode;
  head_yaw: number | null;
  head_pitch: number | null;
  head_roll: number | null;
  max_head_angle: number;
  face_found: boolean | null;
  face_area_pct: number | null;
}

/** Exactly what `FaceCaptureModule.launch()` reads — see FaceCaptureConfig.kt / UploadSpec. */
export interface NativeFaceCaptureConfig {
  title: string;
  text: string;
  canClose: boolean;
  captureMode: FaceCaptureMode;
  blurMinScore: number;
  maxHeadAngle: number;
  requestPermission: boolean;
  upload: {
    url: string;
    headers: Record<string, string>;
    fields: Record<string, string>;
    fileField: string;
    fileName: string;
    metaDataField: string;
  };
}

interface NativeFaceCaptureResult {
  status: FaceCaptureStatus;
  response?: { status: number; body: string };
}

interface FaceCaptureNativeModule {
  launch(config: NativeFaceCaptureConfig): Promise<NativeFaceCaptureResult>;
  update(changes: FaceCaptureUpdate): void;
  dismiss(): void;
}

const nativeModule: FaceCaptureNativeModule | undefined =
  NativeModules?.FaceCaptureModule;

/** Rejection code when React has no foreground activity to launch from. Retry after a moment. */
export const E_NO_ACTIVITY = "E_NO_ACTIVITY";
/** Rejection code when a capture is already in flight. */
export const E_ALREADY_RUNNING = "E_ALREADY_RUNNING";
/** Rejection code for anything the activity itself refused to run with; see `message`. */
export const E_FACE_CAPTURE = "E_FACE_CAPTURE";
/** `E_FACE_CAPTURE` message when the activity was launched without the CAMERA permission (or the in-screen prompt was refused). */
export const CAMERA_PERMISSION_DENIED = "camera_permission_denied";
/**
 * `E_FACE_CAPTURE` message when the in-screen prompt (`requestPermission`) was
 * refused and Android will not show it again. Re-launching is pointless;
 * send the user to app settings.
 */
export const CAMERA_PERMISSION_BLOCKED = "camera_permission_blocked";
/** `E_FACE_CAPTURE` message when the upload spec had no URL. */
export const BAD_CONFIG = "bad_config";

/** Sentinel the native side reads as "use the client default" for a threshold. */
export const NATIVE_DEFAULT_THRESHOLD = -1;

export const isFaceCaptureAvailable = (): boolean =>
  Platform.OS === "android" && typeof nativeModule?.launch === "function";

const asThreshold = (value: unknown): number =>
  typeof value === "number" && Number.isFinite(value) && value >= 0
    ? value
    : NATIVE_DEFAULT_THRESHOLD;

const asCaptureMode = (value: unknown): FaceCaptureMode =>
  value === "manual" ? "manual" : "auto";

const asFieldString = (value: unknown): string | undefined => {
  if (typeof value === "string") return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (typeof value === "boolean") return String(value);
  return undefined;
};

const stringRecord = (
  input: Record<string, unknown> | undefined,
): Record<string, string> => {
  const out: Record<string, string> = {};
  if (!input) return out;
  for (const key of Object.keys(input)) {
    const value = asFieldString(input[key]);
    if (value !== undefined) out[key] = value;
  }
  return out;
};

/**
 * Pure mapping from the public config onto the native contract: defaults
 * filled, thresholds sentinelled, fields stringified. Throws on a missing or
 * non-http(s) upload URL so the mistake surfaces in JS, before a camera opens.
 */
export const normalizeFaceCaptureConfig = (
  config: FaceCaptureConfig,
): NativeFaceCaptureConfig => {
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

const errorCode = (error: unknown): unknown =>
  typeof error === "object" && error !== null
    ? (error as { code?: unknown }).code
    : undefined;

const errorText = (error: unknown): unknown =>
  typeof error === "object" && error !== null
    ? (error as { message?: unknown }).message
    : undefined;

export const isNoActivityError = (error: unknown): boolean =>
  errorCode(error) === E_NO_ACTIVITY;

export const isAlreadyRunningError = (error: unknown): boolean =>
  errorCode(error) === E_ALREADY_RUNNING;

/** The activity bailed because CAMERA is not granted — a "not yet", not a failure. Covers the blocked case too. */
export const isCameraPermissionError = (error: unknown): boolean =>
  errorCode(error) === E_FACE_CAPTURE &&
  (errorText(error) === CAMERA_PERMISSION_DENIED ||
    errorText(error) === CAMERA_PERMISSION_BLOCKED);

/** The in-screen prompt was refused for good; only app settings can grant CAMERA now. */
export const isCameraPermissionBlockedError = (error: unknown): boolean =>
  errorCode(error) === E_FACE_CAPTURE &&
  errorText(error) === CAMERA_PERMISSION_BLOCKED;

const parseResponse = (
  response: NativeFaceCaptureResult["response"],
): FaceCaptureResponse | undefined => {
  if (!response) return undefined;
  let json: unknown;
  try {
    json = response.body ? JSON.parse(response.body) : undefined;
  } catch {
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
export const launchFaceCapture = async (
  config: FaceCaptureConfig,
): Promise<FaceCaptureResult> => {
  if (!nativeModule?.launch) {
    throw new Error("react-native-face-capture: FaceCaptureModule is not available on this platform/binary");
  }
  const native = normalizeFaceCaptureConfig(config);
  const result = await nativeModule.launch(native);
  return { status: result.status, response: parseResponse(result.response) };
};

/**
 * Push a change into the running session: toggle the close button, replace
 * the instruction line. Safe to call when nothing is running.
 */
export const updateFaceCapture = (changes: FaceCaptureUpdate): void => {
  nativeModule?.update?.(changes);
};

/** Ask a running screen to leave (`dismissed`). Mid-upload it waits for the outcome first. */
export const dismissFaceCapture = (): void => {
  nativeModule?.dismiss?.();
};
