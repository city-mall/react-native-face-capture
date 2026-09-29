# react-native-face-capture

Native Android face-capture screen for React Native apps. One shared
implementation of "take a verified selfie and upload it": CameraX front
preview, ML Kit live checks that gate the shutter, a post-shutter quality
check on the still, a review step, and a multipart POST to whatever endpoint
the host names.

Extracted from a screen already running in production, so every app that needs
a verified selfie runs the same one. Android only; the JS surface reports
`isFaceCaptureAvailable() === false` elsewhere.

## What the SDK owns, and what the host owns

| SDK | Host app |
|---|---|
| camera session, preview, bracket guide | **when** to launch (boot flag, OTP, foreground, day rollover) |
| live checks: one face, centred, large enough, lit, looking straight | the runtime CAMERA permission — the screen never prompts |
| auto / manual shutter, retake, review | identity: auth headers, the endpoint URL |
| still check: face present, head pose, Laplacian blur (fail-open) | the form fields the endpoint expects: `event_type`, `client_event_id`, `lat`, `lng`, `captured_at`… |
| JPEG (un-mirrored, long edge ≤ 1280 px, q85) | persisting `client_event_id` before launch and re-launching after a process death |
| the multipart POST, its `meta_data` quality JSON, retry UI on failure | what to do with the response |

## Install

```sh
yarn add react-native-face-capture@city-mall/react-native-face-capture#v0.1.0
```

Autolinking registers `FaceCapturePackage`. Requirements on the host:

- React Native ≥ 0.73 (old-bridge module; runs under the New Architecture interop), Kotlin 2.1, `minSdk` ≥ 24.
- `android.permission.CAMERA` at runtime: either granted by the host before `launchFaceCapture()`, or requested by the screen with `requestPermission: true`. The manifest permission is merged from the library.
- The library brings CameraX 1.4.2, ML Kit face detection 16.1.7 **bundled** (~7 MB, so it works offline and on a fresh install), AppCompat, ConstraintLayout, ExifInterface. A host that already ships a newer CameraX wins the resolution.
- Two Devanagari faces render the copy; the SDK bundles `NotoSansDevanagari-Medium.ttf` and falls back to the system font for the rest.

## API

```ts
import {
  launchFaceCapture,
  updateFaceCapture,
  dismissFaceCapture,
  isFaceCaptureAvailable,
  isNoActivityError,
  isCameraPermissionError,
} from "react-native-face-capture";

const result = await launchFaceCapture({
  title: "Mark In Attendance",          // top-bar title; optional (default "Verify your identity")
  text: "अपना चेहरा फ़्रेम में रखें",   // instruction line; optional
  canClose: false,                      // mandatory by default
  captureMode: "auto",                  // or "manual" (shows a shutter)
  requestPermission: true,              // ask for CAMERA from the screen if it is missing (default false)
  // blurMinScore / maxHeadAngle: omit for the native defaults (100 / 15°), 0 disables a gate
  upload: {
    url: `${BASE_URL}leader/attendance/capture`,
    headers: { Authorization: token, "x-app-name": "TL", "x-app-version": version },
    fields: { event_type: "IN", client_event_id, lat, lng, captured_at, app_version: version, device: "android" },
  },
});

// result.status: "uploaded" | "closed" | "dismissed"
// result.response (uploaded only): { status: 200, body: "...", json: { id: "42", ... } }
```

| Call | Does |
|---|---|
| `launchFaceCapture(config)` | Opens the screen over whatever is showing; resolves when it finishes. Rejects only when the screen could not run at all (see codes). A failed upload stays on screen with **Try again**; it never rejects |
| `updateFaceCapture({ canClose?, text? })` (title is launch-only) | Applies live to a running session (toggle the close button, replace the copy). Safe when nothing is running |
| `dismissFaceCapture()` | Asks a running screen to leave with `dismissed`. Mid-upload it waits for the outcome first |
| `isFaceCaptureAvailable()` | `true` on an Android binary that includes the module |
| `normalizeFaceCaptureConfig(config)` | The pure mapping `launch` applies; exported for tests |

Rejection codes on `launchFaceCapture`:

| `error.code` | `error.message` | Meaning | Do |
|---|---|---|---|
| `E_NO_ACTIVITY` | | React has no foreground activity (host being recreated, app backgrounded) | retry after ~1 s (`isNoActivityError`) |
| `E_ALREADY_RUNNING` | | a capture is in flight | wait for it |
| `E_FACE_CAPTURE` | `camera_permission_denied` | launched without CAMERA, or the in-screen prompt was refused but can be shown again | grant it, relaunch on next foreground (`isCameraPermissionError`) |
| `E_FACE_CAPTURE` | `camera_permission_blocked` | `requestPermission` prompt refused and Android will not ask again | send the user to app settings (`isCameraPermissionBlockedError`); relaunching is pointless |
| `E_FACE_CAPTURE` | `bad_config` | no upload URL reached the activity | fix the config |
| `E_FACE_CAPTURE` | `module_invalidated`, other | the module was torn down / the activity refused | treat as failed, relaunch later |
| `Error` (no code) | `upload.url must be an absolute http(s) URL` | thrown in JS before anything native runs | fix the config |

### Upload contract

`POST upload.url` as `multipart/form-data`:

| Part | From | Content |
|---|---|---|
| every key of `upload.fields` | host | text part, values stringified (`lat: 28.4` → `"28.4"`, `retry: false` → `"false"`; `null`/`undefined` dropped) |
| `meta_data` (rename with `metaDataField`) | SDK | JSON below |
| `image` (rename with `fileField`; filename `faceCapture.jpg`, `fileName`) | SDK | the JPEG |

Headers: `Accept: application/json`, then `upload.headers` verbatim. Any 2xx is
`uploaded`; the response text (capped at 64 KiB) comes back as `response.body`
and, when it parses, `response.json`. Non-2xx and network errors show the
retry state on screen.

`meta_data` — the capture-quality record, for calibrating thresholds server-side:

```json
{ "attempts": 2, "blur_score": 312.4, "blur_min_score": 100, "blur_rejects": 0,
  "blur_gate": "enforced", "face_found": true, "face_area_pct": 17.2, "capture_mode": "auto",
  "head_yaw": -3.1, "head_pitch": 4.8, "head_roll": 1.2, "max_head_angle": 15 }
```

`blur_gate` is `enforced`, `relaxed` (after 3 blur rejections in one session the
gate relaxes rather than strand the user) or `off`. Numbers are `null` when the
still check did not run (a detector error — both checks fail open).

## What the host must guarantee

1. **CAMERA.** By default the activity never prompts: without the permission it finishes at once with `camera_permission_denied`, and the host's own permission flow is expected to have handled it. Pass `requestPermission: true` to let the screen ask instead — one system prompt, then the camera starts or the launch rejects with `camera_permission_denied` (can ask again) or `camera_permission_blocked` (cannot; open settings). Either way treat a denial as "not yet", not as failure.
2. **One launch in flight per app.** The module holds a single pending promise.
3. **Survive a process death under the screen.** On a low-memory device the RN host activity can be killed while the capture activity is on top; when the app comes back the promise is gone. Keep the "a capture is due" state in your store, re-check it on `AppState → active`, and persist `client_event_id` *before* launching so the relaunch is idempotent server-side. Do not regenerate the id on retake — the SDK's retake happens before upload, inside the same session.
4. **Request code `0x9A02`** is used for `startActivityForResult`; it must stay unique within the host activity.

## On screen

```
STARTING ──camera bound──▶ ALIGN ──auto: 3 good frames / manual: shutter──▶ DETECTING ──8 good frames──▶ takePicture ──▶ still check ──▶ SUCCESS
    │ (no CAMERA: finish RESULT_ERROR camera_permission_denied)               │ 8 s without them ──▶ FAILED ──Try again──▶ ALIGN
    ├── ≥5 dark frames ──────▶ LOWLIGHT ◀──────────────────────────────────────┤        SUCCESS ──Retake──▶ ALIGN
    │      (10 bright frames or "I've moved" → ALIGN)                          │        SUCCESS ──Confirm──▶ UPLOADING ──2xx──▶ finish(uploaded)
    └── ≥5 multi-face frames ▶ MULTIFACE ◀────────────────────────────────────┘                        └──fail──▶ UPLOAD_FAILED ──Try again──▶ UPLOADING
CAMERA_ERROR ──Try again──▶ startCamera()                                                                                     └──Retake──▶ ALIGN
```

Live checks (ML Kit on the analysis stream, frame = the bracket rectangle):
≥ 1 face at ≥ 0.15 of the frame; > 1 face for 5 consecutive frames → MULTIFACE
(extra faces get dashed boxes); largest face inside the frame ± 12 % of its
width, centre within 20 % of the frame centre; face width ≥ 50 % of frame width;
mean Y-plane ≥ 50/255; |yaw|, |pitch|, |roll| ≤ `maxHeadAngle`. Only 8
consecutive all-good frames (~1.3 s) fire the camera.

Still check, on the worker thread before review: a face in the 1280 px photo;
head pose within `maxHeadAngle`; Laplacian variance of the face crop (padded
20 %, downscaled to 320 px wide) ≥ `blurMinScore`. Calibration on a Pixel 9
front camera: steady captures 305 / 400 / 507, a motion-smeared one 51–60.
Budget cameras with heavy denoise score lower, hence the relaxing gate and the
numbers in `meta_data`.

`canClose: false` swallows back and hides the close button, never during an
upload. The uploaded JPEG is un-mirrored; only the review `ImageView` flips it.

## Testing on a device

Debug builds of the host export the activity, so it opens straight from adb
without a JS trigger. Headers and fields are JSON strings:

```sh
adb shell am start \
  -n <host.application.id>.debug/live.citymall.facecapture.FaceCaptureActivity \
  --es face_capture_upload_url "https://your-api.example/api/attendance/capture" \
  --es face_capture_upload_headers '{"Authorization":"<token>"}' \
  --es face_capture_upload_fields '{"event_type":"IN","client_event_id":"adb-test-1"}' \
  --ez face_capture_can_close true

adb logcat -s FaceCapture:V AndroidRuntime:E
```

Any string as the token lands on the upload-failed state, which is a fine way
to exercise the retry UI. Release keeps `exported=false`.

## Development

```sh
npm install
npm run check        # tsc --noEmit, jest, build lib/
```

`lib/` is committed: hosts install this as a git dependency and must not need
a build step. Rebuild and commit it with any change under `src/`.

The Android module has no root project of its own; it compiles inside a host.
For a stand-alone compile + JVM tests, point a throwaway Gradle root at
`android/` with `AGP`, `kotlinVersion`, `compileSdkVersion`, `minSdkVersion`,
`targetSdkVersion` on `rootProject.ext` and a `resolutionStrategy` pinning
`com.facebook.react:react-android` to the host's RN version, then run
`:react-native-face-capture:compileDebugKotlin testDebugUnitTest assembleDebug`.

## Layout of the native module

| File | Role |
|---|---|
| `FaceCaptureModule.kt` | JS bridge: `launch(config) → Promise<{ status, response? }>`, `update`, `dismiss` |
| `FaceCapturePackage.kt` | `ReactPackage`, found by autolinking |
| `FaceCaptureConfig.kt` | `FaceCaptureConfig` + `UploadSpec`: intent extras in / out. JS is the source of truth; the activity reads nothing else |
| `FaceCaptureSession.kt` | process-local channel so `update` / `dismiss` reach the running activity |
| `FaceCaptureActivity.kt` | CameraX front preview → state machine → shutter → review → upload |
| `FaceFrameAnalyzer.kt` | ML Kit face detection on the analysis stream + Y-plane luminance |
| `FaceGuideOverlayView.kt` | scrim, corner brackets, scan line, progress outline, check badge, extra-face boxes |
| `CapturedPhotoQuality.kt` | still check: ML Kit face present + pose + Laplacian blur on the face crop |
| `FaceCaptureUploader.kt` | OkHttp multipart POST of `UploadSpec` |
| `quality/ImageQualityAnalyzer.kt`, `quality/BitmapDownscaler.kt` | pure luma metrics (JVM-tested) and the Android-side bitmap → gray frame conversion |

Resources are all prefixed `face_`; the activity uses its own
`Theme.FaceCapture` (AppCompat DayNight, no action bar).

## Differences from the origin screen

| origin | here |
|---|---|
| uploader hardcoded one endpoint path, one app-name header and the field names | `upload: { url, headers, fields, fileField, fileName, metaDataField }` |
| `launch()` resolved `{ status }` | `{ status, response: { status, body, json } }` |
| fonts from the host's assets | `NotoSansDevanagari-Medium.ttf` bundled |
| host `@style/AppTheme` | own `Theme.FaceCapture` |
| image analyzer carried the origin app's own check orchestration | `quality/ImageQualityAnalyzer` trimmed to the metrics |
| JS wrapper read Redux (`auth.face_capture`) and `react-native-permissions` | wrapper is store-agnostic; permissions are the host's |
| minSdk 26 | minSdk 24 |
