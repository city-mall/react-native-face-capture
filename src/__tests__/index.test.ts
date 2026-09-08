const launch = jest.fn();
const update = jest.fn();
const dismiss = jest.fn();

jest.mock(
  "react-native",
  () => ({
    NativeModules: { FaceCaptureModule: { launch, update, dismiss } },
    Platform: { OS: "android" },
  }),
  { virtual: true },
);

import {
  BAD_CONFIG,
  CAMERA_PERMISSION_BLOCKED,
  CAMERA_PERMISSION_DENIED,
  E_FACE_CAPTURE,
  E_NO_ACTIVITY,
  dismissFaceCapture,
  isCameraPermissionBlockedError,
  isCameraPermissionError,
  isFaceCaptureAvailable,
  isNoActivityError,
  launchFaceCapture,
  normalizeFaceCaptureConfig,
  updateFaceCapture,
} from "../index";

const upload = {
  url: "https://example.test/api/attendance/capture",
  headers: { Authorization: "tok", "x-app-name": "TL" },
  fields: { event_type: "IN", client_event_id: "uuid-1", lat: 28.4595, lng: 77.0266, retry: false, note: null },
};

describe("normalizeFaceCaptureConfig", () => {
  it("fills defaults, sentinels thresholds and stringifies fields", () => {
    expect(normalizeFaceCaptureConfig({ upload })).toEqual({
      text: "",
      canClose: false,
      captureMode: "auto",
      blurMinScore: -1,
      maxHeadAngle: -1,
      requestPermission: false,
      upload: {
        url: upload.url,
        headers: { Authorization: "tok", "x-app-name": "TL" },
        fields: { event_type: "IN", client_event_id: "uuid-1", lat: "28.4595", lng: "77.0266", retry: "false" },
        fileField: "image",
        fileName: "faceCapture.jpg",
        metaDataField: "meta_data",
      },
    });
  });

  it("passes explicit thresholds and manual mode through; 0 disables a gate", () => {
    const c = normalizeFaceCaptureConfig({
      text: "  कृपया अपना चेहरा दिखाएं  ",
      canClose: true,
      captureMode: "manual",
      blurMinScore: 0,
      maxHeadAngle: 20,
      upload,
    });
    expect(c.text).toBe("कृपया अपना चेहरा दिखाएं");
    expect(c.canClose).toBe(true);
    expect(c.captureMode).toBe("manual");
    expect(c.blurMinScore).toBe(0);
    expect(c.maxHeadAngle).toBe(20);
  });

  it("only an explicit true opts into the in-screen permission prompt", () => {
    expect(normalizeFaceCaptureConfig({ upload, requestPermission: true }).requestPermission).toBe(true);
    expect(normalizeFaceCaptureConfig({ upload, requestPermission: undefined }).requestPermission).toBe(false);
    expect(normalizeFaceCaptureConfig({ upload, requestPermission: "yes" as unknown as boolean }).requestPermission).toBe(false);
  });

  it("treats a negative or non-numeric threshold as the native default", () => {
    expect(normalizeFaceCaptureConfig({ upload, blurMinScore: -5 }).blurMinScore).toBe(-1);
    expect(normalizeFaceCaptureConfig({ upload, maxHeadAngle: Number.NaN }).maxHeadAngle).toBe(-1);
  });

  it("rejects a missing or relative upload url before anything native runs", () => {
    expect(() => normalizeFaceCaptureConfig({ upload: { url: "" } })).toThrow(/upload\.url/);
    expect(() => normalizeFaceCaptureConfig({ upload: { url: "leader/attendance/capture" } })).toThrow(/upload\.url/);
  });
});

describe("launchFaceCapture", () => {
  beforeEach(() => jest.clearAllMocks());

  it("hands the normalised config to the module and parses a JSON response", async () => {
    launch.mockResolvedValueOnce({ status: "uploaded", response: { status: 200, body: '{"id":"42"}' } });
    const result = await launchFaceCapture({ upload });
    expect(launch).toHaveBeenCalledWith(normalizeFaceCaptureConfig({ upload }));
    expect(result).toEqual({
      status: "uploaded",
      response: { status: 200, body: '{"id":"42"}', json: { id: "42" } },
    });
  });

  it("leaves json undefined for a non-JSON body and response undefined for closed", async () => {
    launch.mockResolvedValueOnce({ status: "uploaded", response: { status: 201, body: "ok" } });
    expect((await launchFaceCapture({ upload })).response).toEqual({ status: 201, body: "ok", json: undefined });
    launch.mockResolvedValueOnce({ status: "closed" });
    expect(await launchFaceCapture({ upload })).toEqual({ status: "closed", response: undefined });
  });

  it("propagates module rejections with their codes", async () => {
    launch.mockRejectedValueOnce(Object.assign(new Error("no foreground activity"), { code: E_NO_ACTIVITY }));
    await expect(launchFaceCapture({ upload })).rejects.toMatchObject({ code: E_NO_ACTIVITY });
  });
});

describe("error helpers and passthroughs", () => {
  it("classifies the codes the module and activity produce", () => {
    expect(isNoActivityError({ code: E_NO_ACTIVITY })).toBe(true);
    expect(isCameraPermissionError({ code: E_FACE_CAPTURE, message: CAMERA_PERMISSION_DENIED })).toBe(true);
    expect(isCameraPermissionError({ code: E_FACE_CAPTURE, message: CAMERA_PERMISSION_BLOCKED })).toBe(true);
    expect(isCameraPermissionBlockedError({ code: E_FACE_CAPTURE, message: CAMERA_PERMISSION_BLOCKED })).toBe(true);
    expect(isCameraPermissionBlockedError({ code: E_FACE_CAPTURE, message: CAMERA_PERMISSION_DENIED })).toBe(false);
    expect(isCameraPermissionError({ code: E_FACE_CAPTURE, message: BAD_CONFIG })).toBe(false);
    expect(isCameraPermissionError(new Error(CAMERA_PERMISSION_DENIED))).toBe(false);
    expect(isNoActivityError(null)).toBe(false);
  });

  it("forwards update/dismiss and reports availability", () => {
    updateFaceCapture({ canClose: true, text: "x" });
    expect(update).toHaveBeenCalledWith({ canClose: true, text: "x" });
    dismissFaceCapture();
    expect(dismiss).toHaveBeenCalledTimes(1);
    expect(isFaceCaptureAvailable()).toBe(true);
  });
});
