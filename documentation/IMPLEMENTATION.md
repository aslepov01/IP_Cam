# IP_Cam — Implementation Notes

This document describes how the app is built and wired: dependencies, HTTP surface, persistence, threading, and streaming mechanics. For system design and component relationships, see [ARCHITECTURE.md](ARCHITECTURE.md). For tests and how to run them, see [TESTING.md](TESTING.md).

**Last Updated:** 2026-03-26

---

## Build and SDK

Versions are centralized in `gradle/libs.versions.toml` and the root `app/build.gradle` helpers.

| Item | Value |
|------|--------|
| Kotlin | 2.1.0 |
| minSdk | 30 |
| targetSdk | 33 |
| compileSdk | 35 |
| CameraX | 1.4.1 |
| AndroidX Lifecycle | 2.8.7 |
| Kotlin Coroutines (`kotlinx-coroutines-android`) | 1.9.0 |
| Ktor server | 2.3.12, **CIO** engine (`ktor-server-cio`) |

The HTTP server is **Ktor on CIO**, not NanoHTTPD.

---

## Versioning and release

- **`version.properties`** (repo root) defines:
  - `VERSION_NAME` — semantic label in **MAJOR.MINOR** form (e.g. `1.14`), not `major.minor.patch`.
  - `VERSION_CODE` — monotonic integer (e.g. `13`).
- **`BuildConfig.BUILD_NUMBER`** is set at build time to a **UTC** timestamp formatted as `yyyyMMddHHmmss` and exposed in code as `BuildInfo.buildNumber` (`Long`).
- **`BuildConfig`** also carries `GIT_COMMIT_HASH`, `GIT_BRANCH`, and `BUILD_TIMESTAMP` (UTC, `yyyyMMdd-HHmmss` for display).
- **CI:** `.github/workflows/version-and-release.yml` bumps `VERSION_NAME` / `VERSION_CODE` on merges to `main` and drives release builds.

There is **no** dedicated HTTP route such as `/version`; build metadata is included in the `/status` JSON under a `version` object and in the web UI template strings.

---

## Camera lifecycle state (app-level)

`CameraService` uses a private enum for demand-driven camera lifecycle (distinct from CameraX’s own `CameraState` type):

`IDLE`, `INITIALIZING`, `ACTIVE`, `STOPPING`, `ERROR`

Exposed to HTTP/SSE as the string returned by `getCameraStateString()` (e.g. for `/status`, `/cameraState`).

---

## HTTP server (`HttpServer.kt`)

- **Engine:** `embeddedServer(CIO, host = "0.0.0.0", port = …)`.
- **CORS:** Ktor `CORS` plugin — `anyHost()`, GET/OPTIONS, `Content-Type` header allowed.
- **Errors:** `StatusPages` maps failures to plain-text 500 bodies; cancellation is rethrown.
- **Concurrency:** Request handling uses Ktor/coroutines on CIO; no custom thread pool for HTTP. A `CoroutineScope` (`Dispatchers.IO` + supervisor) backs background work (e.g. update flow).

### Route catalog (GET-only unless noted)

All routes are registered in `HttpServer` routing; there is **no** `/version` endpoint.

| Area | Paths |
|------|--------|
| Web UI | `/`, `/index.html`, `/styles.css`, `/script.js` |
| Streaming | `/stream` (MJPEG), `/snapshot` |
| Camera control | `/cameras`, `/selectCamera`, `/toggleFlashlight`, `/flashOn`, `/flashOff` |
| Status and monitoring | `/status`, `/metrics`, `/events` (SSE), `/connections`, `/closeConnection`, `/stats` |
| Resolution and format | `/formats`, `/setFormat`, `/setCameraOrientation`, `/setRotation`, `/setResolutionOverlay` |
| OSD overlays | `/setDateTimeOverlay`, `/setBatteryOverlay`, `/setFpsOverlay` |
| FPS | `/setMjpegFps`, `/setRtspFps` |
| Connection limits | `/setConnectionLimits` |
| Server lifecycle | `/restart` |
| RTSP | `/enableRTSP`, `/disableRTSP`, `/rtspStatus`, `/setRTSPBitrate`, `/setRTSPBitrateMode` |
| Battery | `/overrideBatteryLimit` |
| Camera state API | `/cameraState`, `/activateCamera`, `/deactivateCamera`, `/resetCamera` |
| Diagnostics | `/diagnostics/camera`, `/diagnostics/reboot`, `/logs` |
| OTA | `/checkUpdate`, `/triggerUpdate` |
| Device | `/reboot` |

### API response shapes

There is **no** single unified JSON envelope. Many handlers return a string field `status` (e.g. `ok`, `error`, `info`); `/status` uses `"status": "running"` for the server heartbeat. Other endpoints return different top-level fields (`cameras`, `connections`, nested `version`, booleans like `updateAvailable`, etc.). Some resources are **not** JSON: JPEG for `/snapshot` and stream parts, plain text for `/logs`. Clients must not assume one standard success format (e.g. a universal `"success": true`).

### MJPEG (`/stream`)

- **Content-Type:** `multipart/x-mixed-replace; boundary=--jpgboundary`.
- **Source:** Latest JPEG bytes from `CameraService.getLastFrameJpegBytes()` (see `ProcessedFrame` below).
- **Pacing:** Delay between parts is `1000 / targetMjpegFps` ms (default target **10** fps).
- **JPEG quality:** MJPEG pipeline uses `JPEG_QUALITY_STREAM` (**75**) in `CameraService` when compressing for the stream (separate constants exist for camera-internal and snapshot paths).
- **Limits:** Global cap from `ConnectionLimits.maxMjpegStreams`; **one concurrent MJPEG stream per client IP** — a new connection **evicts** the previous stream for that IP.

### Snapshot (`/snapshot`)

Uses a dedicated snapshot consumer so concurrent snapshots do not steal the pipeline from MJPEG/RTSP; registers/unregisters around the request.

### SSE (`/events`)

- **Content-Type:** `text/event-stream`.
- On connect: full `state` event, then `metrics` event.
- **Metrics** events are also pushed on a **~2 s** timer from `CameraService` (`TELEMETRY_UPDATE_INTERVAL_MS = 2000`) via `HttpServer.broadcastMetrics`.
- **State** deltas are pushed when the camera/settings change (`broadcastCameraState` + delta JSON).
- **Keepalive:** `$` comment lines every **30 s** while the connection stays open.
- Client count is capped by `ConnectionLimits.maxSseClients` (oldest evicted when full).

### Static web UI

- Files live under `app/src/main/assets/web/` (`index.html`, `styles.css`, `script.js`).
- `index.html` is served with placeholder substitution: `{{displayName}}`, `{{versionString}}`, `{{buildString}}`, `{{connectionDisplay}}`, `{{adbConnection}}`.
- CSS/JS are served verbatim from the same directory.

---

## RTSP (`RTSPServer`, default port **8554**)

- Video: **H.264** from **MediaCodec** via `H264PreviewEncoder`; encoded NAL units are passed into `RTSPServer.sendH264Frame(nalUnitData, presentationTimeUs, isKeyFrame)`.
- Transport: clients may negotiate **RTP over TCP** (interleaved) or UDP; the stack implements both in `RTSPServer`.
- Session URL shape includes `rtsp://…:8554/stream` (see server metrics/diagnostics in code).

---

## `CameraService` — threading and frame pipeline

- **`cameraExecutor`:** single-thread `Executor` — CameraX analyzer callbacks run here.
- **`processingExecutor`:** fixed pool of **2** threads — rotation, overlays, JPEG compression for MJPEG.
- **Latest frame:** `ProcessedFrame` holds `bitmap`, `jpegBytes`, and `timestamp`; `lastProcessedFrame` is an `AtomicReference<ProcessedFrame?>` updated with `getAndSet` so UI and HTTP see one consistent frame without separate locks.

---

## Watchdog

A background loop (with delay `watchdogRetryDelay`) monitors health, battery policy, and **frozen frames**: repeated detection of the same frame timestamp increments a counter; after **3** consecutive checks (`FROZEN_FRAME_DETECTION_COUNT`) a full camera reset path runs. On repeated recovery needs, **`watchdogRetryDelay` doubles** up to **30 s** (exponential backoff), then resets when healthy.

---

## Foreground service

- `CameraService` runs as a **foreground** service with `android:foregroundServiceType="camera"` (see `AndroidManifest.xml`).
- `onStartCommand` returns **`START_STICKY`**.
- **`onTaskRemoved`** schedules a restart intent so behavior survives the task being swiped away (see implementation in `CameraService`).

---

## Persistence (`SharedPreferences`)

**File:** `"IPCamSettings"` (`MODE_PRIVATE`).

Loaded/saved in `CameraService` (`loadSettings` / `saveSettings`) and used elsewhere for boot/UI (e.g. `autoStartServer` in `MainActivity` / `BootReceiver`).

**Persisted (representative keys):**

- Selected **camera id** (`selectedCameraId`); per-camera resolution (`cameraResolution.<cameraId>.width/height`) with migration from legacy keys.
- **Orientation** (`cameraOrientation`), **rotation** (`rotation`).
- **Torch intent** (`desiredTorchEnabled`; legacy `flashlightOn` read once for migration).
- **Device name** (`deviceName`).
- **Overlays:** `showDateTimeOverlay`, `showBatteryOverlay`, `showResolutionOverlay`, `showFpsOverlay`.
- **FPS targets:** `targetMjpegFps`, `targetRtspFps`.
- **RTSP encoding prefs:** `rtspBitrate`, `rtspBitrateMode` — **note:** RTSP **enabled/disabled is not persisted** (on-demand; see comments in `loadSettings` / `saveSettings`).
- **Connection limits:** `maxMjpegStreams`, `maxSseClients`, `maxRtspSessions` (with migration from legacy `maxConnections`).
- **Auto-start server:** `autoStartServer` (not in `CameraService.saveSettings`; written from UI/boot flow).

**HTTP listen port:** default base port **8080** (`PORT` in `CameraService`), with sequential fallback if busy — **not** stored in `IPCamSettings` in the current code.

---

## Related documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — structure and data flow between components.
- [TESTING.md](TESTING.md) — automated tests and manual checks.
