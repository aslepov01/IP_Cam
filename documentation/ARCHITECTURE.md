# IP_Cam - Architecture

## System Overview

IP_Cam is an Android application that transforms a device into an IP camera with HTTP and RTSP streaming. The architecture follows a single-source-of-truth pattern where `CameraService` owns all camera state and frame data, and all consumers (app UI, HTTP clients, RTSP clients) receive frames through it.

```
┌──────────────────────────────────────────────────────────────────────┐
│  MainActivity                                                        │
│  ├── ServiceConnection → CameraService                               │
│  ├── Camera preview (bitmap via callback)                            │
│  ├── Server controls, camera controls, update/reboot UI              │
│  └── View Binding (activity_main.xml)                                │
└──────────────────────────────────────────────────────────────────────┘
         │ binds
         ▼
┌──────────────────────────────────────────────────────────────────────┐
│  CameraService  (Foreground Service + LifecycleOwner)                │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  CameraX Pipeline                                              │  │
│  │  ProcessCameraProvider → ImageAnalysis (YUV_420_888)            │  │
│  │                        → Preview (SurfaceTexture, optional)     │  │
│  └────────────────────────────────────────────────────────────────┘  │
│         │ frames                                                     │
│         ▼                                                            │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  Image Processing (processingExecutor, 2 threads)              │  │
│  │  YUV → Bitmap → rotation → OSD overlay → JPEG compression     │  │
│  │                                                                │  │
│  │  Output: ProcessedFrame { bitmap, jpegBytes, timestamp }       │  │
│  │          stored in AtomicReference (lock-free)                  │  │
│  └────────────────────────────────────────────────────────────────┘  │
│         │                                                            │
│         ├──→ MainActivity callback (bitmap for preview)              │
│         ├──→ HttpServer (JPEG bytes for MJPEG /stream)               │
│         └──→ H264PreviewEncoder → MediaCodec → RTSPServer            │
│                                                                      │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────────────┐ │
│  │ HttpServer   │  │ RTSPServer  │  │ Watchdog                    │ │
│  │ (Ktor CIO)  │  │ (port 8554) │  │ Frozen-frame detection      │ │
│  │ port 8080   │  │ RTP/H.264   │  │ Exponential backoff recovery│ │
│  └─────────────┘  └─────────────┘  └──────────────────────────────┘ │
│                                                                      │
│  Persistence: SharedPreferences, wake locks, battery exemption       │
│  Network: WiFi monitor, auto-restart on connectivity change          │
└──────────────────────────────────────────────────────────────────────┘
         ▲
         │ starts on boot
┌────────────────┐
│  BootReceiver  │
└────────────────┘
```

## Core Components

### CameraService

**File:** `CameraService.kt`  
**Type:** `Service() + LifecycleOwner + CameraServiceInterface`

The central component that owns all camera operations, servers, and state.

**Camera State Machine:**
```
IDLE ──→ INITIALIZING ──→ ACTIVE ──→ STOPPING ──→ IDLE
  ▲                          │                       │
  └──────────────────────────┴── ERROR ──────────────┘
```

States:
- `IDLE` — camera not initialized, no consumers
- `INITIALIZING` — camera binding in progress
- `ACTIVE` — camera bound and delivering frames
- `STOPPING` — camera unbinding in progress
- `ERROR` — camera failed to initialize

**ProcessedFrame:**  
An immutable data class held in an `AtomicReference<ProcessedFrame?>` that bundles bitmap (for UI), pre-compressed JPEG bytes (for HTTP), and a timestamp. This guarantees all consumers see the same frame atomically without locks.

**Callbacks to MainActivity:**
1. `onFrameAvailableCallback` — delivers preview bitmaps
2. `onCameraStateChangedCallback` — camera/settings state changes
3. `onConnectionsChangedCallback` — connection count updates

All callbacks are `@Volatile`, owner-scoped to prevent stale Activity references, and wrapped in safe invocation helpers that check lifecycle state.

**On-demand camera binding:**  
The camera is bound when the first consumer appears (MJPEG client, RTSP client, or snapshot request) and unbound when all consumers disconnect.

**Watchdog:**  
A coroutine monitors frame delivery. If no new frames arrive for a configurable timeout, the watchdog triggers `fullCameraReset()` — unbind, clear provider, reset state, rebind. Recovery uses exponential backoff.

### HttpServer

**File:** `HttpServer.kt`  
**Type:** Ktor CIO embedded server

Serves the web UI, MJPEG streams, snapshots, SSE events, and the full REST API on port 8080.

**MJPEG streaming:**  
Each connected client gets a `StreamClient` entry. Per-IP eviction ensures one MJPEG stream per IP address (reconnecting replaces the old stream). Frames are delivered as `multipart/x-mixed-replace` JPEG boundaries.

**SSE (Server-Sent Events):**  
The `/events` endpoint pushes JSON status updates to connected web clients at ~2-second intervals.

**Web UI:**  
Loaded from `app/src/main/assets/web/` (index.html, styles.css, script.js). Template variables in HTML (`{{displayName}}`, `{{versionString}}`, `{{buildString}}`, `{{connectionDisplay}}`, `{{adbConnection}}`) are substituted at serve time.

**Complete route list:**

| Category | Endpoints |
|---|---|
| Web UI | `/`, `/index.html`, `/styles.css`, `/script.js` |
| Streaming | `/stream` (MJPEG), `/snapshot` |
| Camera control | `/cameras`, `/selectCamera`, `/toggleFlashlight`, `/flashOn`, `/flashOff` |
| Status & monitoring | `/status`, `/metrics`, `/events` (SSE), `/connections`, `/closeConnection`, `/stats` |
| Resolution & format | `/formats`, `/setFormat`, `/setCameraOrientation`, `/setRotation`, `/setResolutionOverlay` |
| OSD overlays | `/setDateTimeOverlay`, `/setBatteryOverlay`, `/setFpsOverlay` |
| FPS control | `/setMjpegFps`, `/setRtspFps` |
| Connection limits | `/setConnectionLimits` |
| Server lifecycle | `/restart` |
| RTSP management | `/enableRTSP`, `/disableRTSP`, `/rtspStatus`, `/setRTSPBitrate`, `/setRTSPBitrateMode` |
| Battery management | `/overrideBatteryLimit` |
| Camera state | `/cameraState`, `/activateCamera`, `/deactivateCamera`, `/resetCamera` |
| Diagnostics | `/diagnostics/camera`, `/diagnostics/reboot`, `/logs` |
| OTA updates | `/checkUpdate`, `/triggerUpdate` |
| Device management | `/reboot` |

### RTSPServer

**File:** `RTSPServer.kt`  
**Port:** 8554

Implements RTSP protocol (OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN) with RTP/H.264 interleaved over TCP. The H.264 encoding is handled by `H264PreviewEncoder` using Android's `MediaCodec` API. Encoded NAL units are broadcast to all connected RTSP clients via `sendH264Frame()`.

### H264PreviewEncoder

**File:** `H264PreviewEncoder.kt`

Accepts bitmaps from `CameraService`, feeds them to a hardware `MediaCodec` H.264 encoder, and delivers encoded frames to `RTSPServer`. Tracks encoding FPS via `cameraService.recordRtspFrameEncoded()`.

### MainActivity

**File:** `MainActivity.kt`

The UI layer. Binds to `CameraService` via `ServiceConnection` and registers callbacks for frame preview, state changes, and connection updates. Provides controls for:
- Server start/stop
- Camera selection and flashlight
- Resolution/rotation/overlay settings
- OTA update checking (`UpdateManager`)
- Device reboot (`RebootHelper`)
- Camera reset

Uses View Binding with `activity_main.xml`.

## Supporting Components

| File | Purpose |
|---|---|
| `CameraServiceInterface.kt` | Interface defining the public API of `CameraService` — used by `HttpServer` and `RTSPServer` to access camera operations without coupling to the concrete class |
| `UpdateManager.kt` | OTA updates via GitHub Releases API: check, download, install APK |
| `RebootHelper.kt` | Multi-method device reboot (DevicePolicyManager → PowerManager → shell exec) with diagnostics |
| `DiagnosticModels.kt` | `RebootDiagnostics` and `RebootResult` data models |
| `DeviceAdminReceiver.kt` | Android Device Admin/Owner receiver; clears restrictive user restrictions on provisioning |
| `BootReceiver.kt` | Starts `CameraService` on `BOOT_COMPLETED` (auto-start enabled by default) |
| `BuildInfo.kt` | Exposes version name, code, commit hash, branch, build timestamp, build number from `BuildConfig` |
| `BitmapPool.kt` | Thread-safe bitmap object pool (default 64 MB cap) to reduce GC pressure |
| `PerformanceMetrics.kt` | CPU, memory, frame timing, drop rate tracking |
| `RuntimeTelemetry.kt` | `RuntimeTelemetrySampler` for bandwidth accounting (MJPEG/RTSP) and `RuntimeTelemetrySnapshot` |
| `ConnectionModels.kt` | `ConnectionKind`, `ConnectionLimits`, `ConnectionSnapshot` |
| `CameraOption.kt` | Camera ID/label/capabilities model |
| `WiFiDebuggingManager.kt` | Device Owner–gated ADB-over-WiFi management |
| `InMemoryLogBuffer.kt` | Circular buffer (500 entries) for in-app log access via `/logs` |

## Threading Model

```
Main Thread
  └── UI updates only (View Binding, dialogs)

cameraExecutor (1 thread)
  └── CameraX ImageAnalysis callbacks
      Receives YUV_420_888 frames, dispatches to processingExecutor

processingExecutor (2 threads)
  └── Rotation, OSD annotation, JPEG compression
      Produces ProcessedFrame and updates AtomicReference

Ktor CIO (coroutine-based)
  └── HTTP request handling, MJPEG streaming, SSE
      Coroutines suspended on frame availability

RTSP (per-client threads)
  └── ServerSocket accept loop + per-client read/write threads

serviceScope (SupervisorJob + Dispatchers.Default)
  └── Watchdog, periodic tasks, network monitoring
```

## Data Flow

### MJPEG Path
```
Camera sensor → YUV_420_888 (ImageAnalysis)
  → cameraExecutor: extract planes
  → processingExecutor: YUV→Bitmap → rotate → annotate OSD → compress JPEG
  → ProcessedFrame stored in AtomicReference
  → HttpServer reads jpegBytes, writes multipart/x-mixed-replace boundary to each client
  → recordMjpegFrameServed() called per-client-per-frame for FPS tracking
```

### RTSP Path
```
Camera sensor → YUV_420_888 (ImageAnalysis)
  → processingExecutor: YUV→Bitmap → rotate
  → H264PreviewEncoder: bitmap → MediaCodec (hardware H.264)
  → encoded NAL units → RTSPServer.sendH264Frame()
  → RTP packets broadcast to all connected RTSP clients over TCP
  → recordRtspFrameEncoded() called once per encoded frame
```

### Snapshot Path
```
GET /snapshot → read ProcessedFrame.jpegBytes from AtomicReference
  → respond with image/jpeg
  (activates camera on demand if no consumers are connected)
```

## Persistence & Reliability

**Foreground Service:** `android:foregroundServiceType="camera"` with persistent notification.

**Restart Policy:** `START_STICKY` + `onTaskRemoved()` handler for restart when swiped away.

**Wake Locks:** CPU + WiFi locks held while the server is running.

**Settings:** All persisted in `SharedPreferences` with immediate writes — camera ID, resolution, rotation, flashlight, device name, overlays, FPS targets, RTSP state, connection limits.

**Network Monitor:** Detects WiFi changes and restarts the server with updated IP.

**Cleanup Order (onDestroy):**
1. Set lifecycle to DESTROYED
2. Clear callbacks
3. Unregister receivers, stop HTTP/RTSP servers
4. Shutdown executors (camera → processing)
5. Clear bitmap pool and frame references
6. Cancel coroutine scope
7. Release wake locks

## Build & Versioning

**Version source:** `version.properties`
```
VERSION_NAME=1.14
VERSION_CODE=13
```

Automatically incremented by GitHub Actions on merge to main.

**Build number:** UTC timestamp (`yyyyMMddHHmmss`) generated at build time, exposed as `BuildConfig.BUILD_NUMBER` and via `BuildInfo.buildNumber`.

**Workflow:** `.github/workflows/version-and-release.yml` — bumps version, builds release APK, signs, creates GitHub Release tagged `v{BUILD_NUMBER}`.

## Technology Stack

| Component | Technology | Version |
|---|---|---|
| Camera | CameraX (+ Camera2 interop) | 1.4.1 |
| HTTP Server | Ktor CIO | 2.3.12 |
| Coroutines | kotlinx-coroutines-android | 1.9.0 |
| Lifecycle | androidx.lifecycle | 2.8.7 |
| Serialization | kotlinx-serialization-json | 1.7.3 |
| Language | Kotlin | 2.1.0 |
| Min SDK | Android 11 (API 30) | — |
| Target SDK | Android 13 (API 33) | — |
| Compile SDK | API 35 | — |

## Manifest Highlights

- Permissions: `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`
- `MainActivity`: `launchMode="singleTask"`, `excludeFromRecents="false"`, HOME intent filter (dedicated device mode)
- `CameraService`: `foregroundServiceType="camera"`
- `DeviceAdminReceiver`: Device Admin/Owner support
- `FileProvider`: For APK install during OTA updates
