# IP_Cam Refactoring Plan V3

## Goal

Reduce coupling and simplify architecture so that `CameraService` (~5000 lines),
`HttpServer` (~2000 lines), `RTSPServer` (~1400 lines), and `MainActivity`
(~2200 lines) stop being god-classes that mix unrelated responsibilities.
The project should become easier to maintain and extend without growing in size;
ideally it should shrink.

## Guiding principles

- **Extract real runtime boundaries, not packages.** Package moves are a
  side-effect of extraction, not a goal.
- **Each step must leave the project fully functional.** Run the full test suite
  (`testDebugUnitTest` + `connectedDebugAndroidTest`) after every step. If a
  test fails, the step is not done.
- **Tests change only when the interface they test changes.** Every test scenario
  that existed before a step must still exist after. New code must get new tests.
  This prevents reward-hacking where passing tests merely means fewer assertions.
- **Shrink before you split.** Remove dead code and inline legacy migration paths
  before extracting subsystems, so every extraction starts from a smaller surface.
- **No premature modularization.** The project is ~10 K lines of Kotlin in a
  single `:app` module. Splitting into Gradle modules adds build complexity
  (manifest merging, resource scoping, dependency graphs) with negligible benefit
  at this scale. If the project grows 3–5× in the future, revisit.

## Current state summary

| File | Lines | Problem |
|------|------:|---------|
| `CameraService.kt` | ~5000 | Camera catalog, CameraX binding, frame pipeline, torch, watchdog, battery policy, telemetry, consumer accounting, settings I/O, RTSP lifecycle, server lifecycle — all in one class. |
| `HttpServer.kt` | ~2000 | Ktor bootstrap, 30+ route handlers, MJPEG connection registry, SSE fan-out, admin/diagnostics/update orchestration — flat in one `routing {}` block. |
| `RTSPServer.kt` | ~1400 | Socket accept, RTSP protocol, SDP, RTP packetization, session registry, codec config, metrics — monolithic server. |
| `MainActivity.kt` | ~2200 | Permissions, service binding, spinner/checkbox UI, preview, update flow, device-owner/reboot UI, periodic metrics refresh — all in the Activity. |
| `CameraServiceInterface.kt` | 113 | 71 methods. Every caller depends on everything. |

## Identified dead code

| Item | Location | Evidence |
|------|----------|----------|
| `setResolution()` | `CameraService.kt` ~2769 | Never called; `setResolutionAndRebind` is the public API. |
| `getCurrentMjpegFps()` | `CameraService.kt` ~2941 | Defined only; no callers. |
| `getCurrentRtspFps()` | `CameraService.kt` ~2946 | Defined only; no callers. |
| `getCurrentCpuUsage()` | `CameraService.kt` ~3118 | Defined only; UI uses `getCpuUsagePercent()`. |
| `getThemeAwareBackgroundColor()` | `MainActivity.kt` ~1638 | Defined only; never called. |
| `lastHeapSize`, `lastHeapFree` | `PerformanceMetrics.kt` | Assigned in `getMemoryStats()` but never read. |
| `import java.io.File` | `CameraServiceInterface.kt` | Unused import. |

## Identified legacy code

| Item | Location | Notes |
|------|----------|-------|
| `PREF_LEGACY_MAX_CONNECTIONS` migration | `CameraService.loadSettings()` ~3170 | Converts old single `maxConnections` to three separate limits. |
| `flashlightOn` → `desiredTorchEnabled` key migration | `CameraService.loadSettings()` ~3196 | Old boolean key. |
| `cameraType` (front/back) → `selectedCameraId` migration | `CameraService.loadSettings()` ~3215 | Enum-style camera selection. |
| Per-face resolution keys (`frontCameraResolution*`, `backCameraResolution*`) | `CameraService.loadSettings()` ~3150 | Replaced by `cameraResolution.<id>.*`. |
| `fromLegacyMaxConnections()` | `ConnectionModels.kt` | Called only from migration code and one test. |

---

## Step 1 — Remove dead code and finalize legacy migrations

### What to do

1. Delete the dead methods and fields listed in the table above.
2. Mark legacy SharedPreferences migration code with a `@Deprecated` annotation
   and a clear comment stating it will be removed after one more release cycle.
   Alternatively, if all target devices are already on modern keys, delete the
   migration paths and the `fromLegacyMaxConnections` helper now.
3. Remove the unused `import java.io.File` from `CameraServiceInterface.kt`.

### Why

Every line of dead or legacy code is cognitive load and an obstacle to later
extractions. Removing it first means fewer methods to reason about, fewer
SharedPreferences keys to track, and fewer surprises when moving code later.

### Tests

- Run `./gradlew testDebugUnitTest`. All existing tests must pass.
- Run `./gradlew connectedDebugAndroidTest`. All instrumented tests must pass.
- If `fromLegacyMaxConnections` is deleted, update `ConnectionLimitsTest` to
  remove only those specific migration assertions. Do not remove unrelated
  assertions.
- No new test scenarios are needed here — this step only removes code.

### Documentation

- Update `README.md` and any relevant documentation to remove references to deleted
  methods and fields.
- If legacy migration paths are removed, update any migration guides or changelog
  notes accordingly.

### Exit criterion

The deleted methods and fields are gone. The project compiles and all tests pass.
Documentation reflects the removals.

---

## Step 2 — Extract settings into repositories

### What to do

1. Create `RuntimeSettingsRepository` — owns all service/runtime SharedPreferences:
   selected camera ID, per-camera resolution, overlay flags, target FPS values,
   connection limits, torch intent, RTSP bitrate/mode, device name, camera
   orientation, rotation.
2. Create `UiPrefsRepository` — owns MainActivity-only preferences: collapsible
   section states, launcher prompt shown, battery dialog shown, autostart
   (including device-protected storage for Direct Boot).
3. Move `loadSettings()` and `saveSettings()` logic from `CameraService` into
   `RuntimeSettingsRepository`. Keep any remaining legacy migration code here.
4. Move preference reads/writes from `MainActivity` into `UiPrefsRepository`.
5. `BootReceiver` should read autostart through `UiPrefsRepository` (or directly
   from device-protected prefs as it does now — the repository wraps that).
6. Both repositories expose typed properties (not raw string keys) and handle
   defaults internally.

### Why

Settings access is currently scattered across `CameraService`, `MainActivity`,
and `BootReceiver` with raw string keys. Centralizing it removes a cross-cutting
coupling that makes every subsequent extraction harder, because extracted
components would otherwise each need their own SharedPreferences access.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `RuntimeSettingsRepository`: default values, round-trip
  read/write for each setting, per-camera resolution storage.
- Add JVM unit tests for `UiPrefsRepository`: default values, Direct Boot
  context selection, autostart read/write.
- Existing instrumented tests must pass unchanged.

### Documentation

- Document the public API of `RuntimeSettingsRepository` and `UiPrefsRepository`:
  available properties, default values, and threading guarantees.
- Update architecture documentation to reflect the new settings access pattern.
- Update any developer guides that reference direct `SharedPreferences` usage.

### Exit criterion

`CameraService`, `MainActivity`, and `BootReceiver` no longer call
`getSharedPreferences()` or use raw preference key strings directly. All
preference access goes through the two repositories. Documentation reflects the
new settings architecture.

---

## Step 3 — Extract camera catalog and selection policy

### What to do

1. Create `CameraCatalog` — takes `CameraManager` and builds the grouped camera
   list on construction or explicit refresh.
2. Move these responsibilities out of `CameraService`:
   - `lensFacingToLabel`, `lensFacingDisplayName`, `cameraFacingPriority`
   - `buildCameraGroupKey`, `formatFloatKey`, camera fingerprinting
   - `RawCameraGroup` / `CameraOption` assembly
   - `startCameraCatalogBootstrap`, `probeCameraCandidate`, catalog probing
   - `buildCameraSelector`, resolution selector logic
   - `initializeCameraCharacteristicsCache`, characteristics caching
   - `getSupportedResolutions`, `sizeLabel`
   - `getCameraCatalogVersion`, `getAvailableCameras`, `selectCamera` (the
     selection decision — not the CameraX rebind that follows)
3. `CameraCatalog` exposes: `cameras: List<CameraOption>`, `catalogVersion: Int`,
   `getSupportedResolutions(cameraId): List<Size>`, `selectCamera(id): Boolean`,
   `getSelectedCameraId()`, `buildCameraSelector(id): CameraSelector`.
4. `CameraService` holds a `CameraCatalog` instance and delegates catalog
   queries to it.

### Why

Camera discovery and grouping is pure CameraManager logic with no dependency on
active camera state, HTTP, or UI. It is ~500 lines of `CameraService` that can
be tested with a fake `CameraManager` or Robolectric without touching CameraX
binding.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `CameraCatalog`: grouping logic, representative
  selection, resolution filtering, `sizeLabel` formatting, catalog version
  increment on refresh.
- Existing `/cameras`, `/formats`, `/selectCamera` instrumented tests must pass
  unchanged.

### Documentation

- Document `CameraCatalog` public API: how to obtain the camera list, select a
  camera, query supported resolutions, and interpret `catalogVersion`.
- Update architecture documentation to show that camera discovery now lives in
  `CameraCatalog`, not `CameraService`.

### Exit criterion

`CameraService` no longer contains camera discovery, grouping, or resolution
enumeration logic. Those ~500 lines live in `CameraCatalog`. Documentation
reflects the extraction.

---

## Step 4 — Extract consumer demand tracker

### What to do

1. Create `CameraDemandTracker` — a state machine that tracks consumer
   registrations and determines when the camera should activate or deactivate.
2. Move from `CameraService`:
   - `ConsumerType` enum
   - `CameraState` enum (`IDLE`, `INITIALIZING`, `ACTIVE`, `STOPPING`, `ERROR`)
   - `consumers` map and `consumersLock`
   - `registerConsumer`, `unregisterConsumer`, `hasConsumers`, `getConsumerCount`
   - `activateCameraForConsumers`, `deactivateCameraForConsumers`
3. The tracker emits activation/deactivation decisions via a callback or
   interface (e.g. `onDemandChanged(shouldBeActive: Boolean)`), which
   `CameraService` implements to call `startCamera()` / `stopCamera()`.
4. Public consumer registration methods on `CameraService`
   (`registerMjpegConsumer`, `registerPreviewConsumer`, etc.) delegate to the
   tracker.

### Why

Consumer accounting is an isolated state machine. Currently it is tangled with
CameraX binding code inside `CameraService`, making it impossible to unit-test
transitions like "last MJPEG client disconnects → camera should deactivate"
without a device.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `CameraDemandTracker`: register/unregister for each
  `ConsumerType`, transition from 0→1 consumers fires activation, transition
  from 1→0 fires deactivation, multiple consumers of different types, idempotent
  unregister.
- Existing MJPEG, RTSP, and lifecycle instrumented tests must pass unchanged.

### Documentation

- Document `CameraDemandTracker` API: consumer types, registration/unregistration
  contract, and the activation/deactivation callback mechanism.
- Update architecture documentation to reflect that consumer lifecycle management
  is now separated from `CameraService`.

### Exit criterion

Consumer counting and activation/deactivation decisions live in
`CameraDemandTracker`. `CameraService` only reacts to the tracker's decisions.
Documentation reflects the extraction.

---

## Step 5 — Extract frame pipeline

### What to do

1. Create `FramePipeline` — owns frame processing from raw `ImageProxy` to
   final JPEG bytes.
2. Move from `CameraService`:
   - `imageProxyToBitmap`
   - `applyRotationCorrectly`, `applyCameraOrientation`, `applyRotation`
   - `annotateBitmap` (OSD overlay rendering)
   - `processImageHeavyOperations` (bitmap → annotated → JPEG → store)
   - `ProcessedFrame` data class
   - Atomic `lastProcessedFrame` store (becomes `FrameStore`)
   - `clearLastProcessedFrame`
3. `BitmapPool` becomes an internal dependency of `FramePipeline`.
4. `FramePipeline` exposes:
   - `processFrame(imageProxy, orientation, overlayConfig): ProcessedFrame`
   - `getLastFrameJpegBytes(): ByteArray?`
   - `clear()`
5. OSD overlay settings (which overlays are on/off, FPS value, battery info) are
   passed as a config object, not read from shared state.
6. `processMjpegFrame` (the ImageAnalysis callback) stays in `CameraService` as
   the thin scheduling layer that calls `FramePipeline.processFrame` on the
   processing executor and stores the result.

### Why

Frame processing (bitmap conversion, rotation, annotation, JPEG compression) is
the second-largest block of logic in `CameraService` (~400 lines). It has no
dependency on HTTP, RTSP, battery, or settings beyond a configuration snapshot.
Extracting it makes frame handling independently testable and opens the door for
future format changes (HEIF, WebP) without touching service orchestration.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `FramePipeline`: rotation math correctness for all
  sensor orientations, OSD annotation with various flag combinations (use
  `Bitmap.createBitmap` in JVM tests or Robolectric), JPEG quality parameter
  effect.
- Existing `/snapshot`, `/stream`, and preview instrumented tests must pass
  unchanged.

### Documentation

- Document `FramePipeline` public API: `processFrame` input/output contract,
  overlay configuration structure, and `FrameStore` access pattern.
- Update architecture documentation to show the frame processing data flow
  through the new pipeline component.

### Exit criterion

`CameraService` no longer contains bitmap transformation, OSD annotation, or
JPEG compression logic. Those ~400 lines live in `FramePipeline`. Documentation
reflects the extraction.

---

## Step 6 — Extract CameraX binding and torch control

### What to do

1. Create `CameraBinder` — owns CameraX lifecycle integration.
2. Move from `CameraService`:
   - `initializeCameraProvider`
   - `bindCamera` (Preview, ImageAnalysis setup, `bindToLifecycle`)
   - `stopCamera` (unbind, cleanup)
   - `requestBindCamera` (debounced rebind)
   - `fullCameraReset`
   - Camera state observer attachment
   - `shouldBindRtspPipeline`, `refreshRtspPipelineIfNeeded`
   - Placeholder preview logic
   - `startCamera` orchestration
3. Create `TorchController` — owns torch state.
4. Move from `CameraService`:
   - `desiredTorchEnabled`, `effectiveTorchEnabled`, `effectiveTorchOwnerCameraId`
   - `applyTorchState`, `reconcileTorchState`
   - `toggleFlashlight`, `setFlashlight`, `isFlashlightEnabled`,
     `isFlashlightAvailable`
5. `CameraBinder` depends on `CameraCatalog`, `FramePipeline` (to install the
   ImageAnalysis callback), and `CameraDemandTracker` (for state transitions).
6. `TorchController` depends on `CameraBinder` (for `CameraControl` access) and
   `CameraManager` (for fallback torch).
7. `CameraService` delegates camera operations to `CameraBinder` and torch
   operations to `TorchController`.

### Why

CameraX bind/unbind is ~500 lines of Android-specific lifecycle code. Torch
control is another ~100 lines with its own state machine (desired vs effective,
fallback to CameraManager). Extracting both leaves `CameraService` as an
orchestrator rather than an implementor of camera details.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `TorchController`: desired/effective state transitions,
  availability checks.
- `CameraBinder` is hard to unit-test without Robolectric or a device, but its
  extraction enables future testability. Existing camera/flashlight instrumented
  tests cover it.
- Existing instrumented tests for camera activation, flashlight, dynamic
  reconfiguration, and RTSP must pass unchanged.

### Documentation

- Document `CameraBinder` API: bind/unbind lifecycle, dependency on `CameraCatalog`
  and `FramePipeline`, and RTSP pipeline refresh mechanism.
- Document `TorchController` API: desired vs effective state model, fallback
  to `CameraManager`, availability checks.
- Update architecture documentation to show that CameraX integration and torch
  control are now separate components.

### Exit criterion

`CameraService` no longer contains CameraX `bindToLifecycle`/`unbindAll` calls
or direct `CameraControl.enableTorch` calls. Those ~600 lines live in
`CameraBinder` and `TorchController`. Documentation reflects the extraction.

---

## Step 7 — Narrow the service interface

### What to do

1. Replace the 71-method `CameraServiceInterface` with focused port interfaces:
   - `CameraControlPort` — camera list, selection, flashlight, resolution, OSD,
     FPS, orientation, rotation, device name.
   - `StreamingPort` — frame access (`getLastFrameJpegBytes`), consumer
     registration/unregistration, MJPEG/RTSP FPS recording, streaming-allowed
     check, `recordStreamingBytes`.
   - `TelemetryPort` — connection counts, snapshots, limits, bandwidth, CPU,
     runtime telemetry snapshot, camera state JSON, detailed stats, logs.
   - `AdminPort` — server restart, battery override, manual activate/deactivate,
     camera reset, RTSP enable/disable/status/config.
2. `CameraService` implements all four ports (it already has the methods).
3. `HttpServer` constructor takes the ports it needs instead of one wide
   interface. Most route handlers need only one or two ports.
4. `RTSPServer` takes only `StreamingPort` (for consumer registration and byte
   recording) and `TelemetryPort` (for connection limits) instead of a concrete
   `CameraService` reference.
5. Remove the three `as? CameraService` casts in `HttpServer` by adding
   `getADBConnectionInfo()` and `setFlashlight(Boolean)` to the appropriate
   ports.
6. Delete `CameraServiceInterface.kt`.

### Why

Interface segregation is the single most impactful architectural change for
coupling. After this step, `HttpServer` route handlers can be reasoned about
in terms of the narrow port they use, and `RTSPServer` no longer holds a
concrete `CameraService` reference at all. This also makes it possible to
write JVM tests for HTTP routes using fake port implementations.

### Tests

- Run the full test suite after extraction.
- All existing tests must pass. Test code should only change where it referenced
  `CameraServiceInterface` directly — update to the new port type.
- Add JVM tests for at least one HTTP route group using a fake `StreamingPort`
  (e.g. `/snapshot` returning a known JPEG).
- Verify that `RTSPServer` no longer has `import com.ipcam.CameraService`.

### Documentation

- Document each port interface (`CameraControlPort`, `StreamingPort`,
  `TelemetryPort`, `AdminPort`): purpose, method contracts, and which components
  consume each port.
- Update architecture documentation to replace references to the monolithic
  `CameraServiceInterface` with the new port-based dependency model.
- Update any contributor guides that described the old interface.

### Exit criterion

`CameraServiceInterface.kt` is deleted. `HttpServer` and `RTSPServer` depend
only on narrow port interfaces. No `as? CameraService` casts remain in
`HttpServer`. Documentation reflects the new port architecture.

---

## Step 8 — Extract battery policy, watchdog, and telemetry

### What to do

1. Create `BatteryPolicy` — owns battery mode evaluation and streaming-allowed
   decision.
2. Move from `CameraService`:
   - `BatteryManagementMode` enum and threshold constants
   - `determineBatteryMode`, `updateBatteryManagement`
   - `isStreamingAllowed`, `overrideBatteryLimit`
   - `acquireLocks`, `releaseLocks` (wake lock + WiFi lock)
   - `registerBatteryReceiver`, `unregisterBatteryReceiver`
   - `BatteryInfo`, `batteryInfoFromIntent`
3. Create `CameraWatchdog` — owns periodic health monitoring.
4. Move from `CameraService`:
   - `startWatchdog`, watchdog retry/backoff fields
   - Frame staleness detection, frozen-frame detection
   - Server health check
5. `CameraWatchdog` depends on `CameraBinder` (to trigger rebind/reset),
   `BatteryPolicy` (to call `updateBatteryManagement`), and a server-health
   callback.
6. Create `TelemetryCoordinator` — owns periodic telemetry sampling and
   broadcast.
7. Move from `CameraService`:
   - `startTelemetryLoop`, `sampleRuntimeTelemetry`
   - `broadcastImmediateTelemetrySnapshot`
   - FPS tracking fields and methods (`recordMjpegFrameServed`,
     `recordRtspFrameEncoded`, `checkAndResetFpsCounters`)
8. `TelemetryCoordinator` depends on `PerformanceMetrics`, `RuntimeTelemetrySampler`,
   and a broadcast callback to `HttpServer.broadcastMetrics`.

### Why

Battery policy (~250 lines), watchdog (~160 lines), and telemetry (~120 lines)
are three independent operational concerns mixed into `CameraService`. Each has
its own timer/loop and distinct state. Extracting them removes ~530 lines and
makes each policy testable: battery mode transitions, watchdog recovery
decisions, and telemetry sampling can all be verified with JVM tests.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `BatteryPolicy`: mode transitions with hysteresis,
  charging overrides, streaming-allowed in each mode, battery override escape
  hatch, lock acquire/release pairing.
- Add JVM unit tests for `CameraWatchdog`: stale-frame detection triggers
  rebind, frozen-frame detection triggers full reset, server-down triggers
  restart, backoff progression.
- Add JVM unit tests for `TelemetryCoordinator`: FPS window calculation,
  counter reset, sampling interval.
- Existing battery and streaming instrumented tests must pass unchanged.

### Documentation

- Document `BatteryPolicy` API: mode evaluation logic, thresholds, hysteresis
  behavior, lock management, and override mechanism.
- Document `CameraWatchdog` API: health check triggers, recovery strategies,
  and backoff behavior.
- Document `TelemetryCoordinator` API: sampling intervals, FPS tracking windows,
  and broadcast mechanism.
- Update architecture documentation to show these as independent operational
  components outside `CameraService`.

### Exit criterion

`CameraService` no longer contains battery threshold logic, watchdog loop, or
telemetry sampling. Those ~530 lines live in three focused components.
Documentation reflects the extractions.

---

## Step 9 — Organize HTTP routes into feature groups

### What to do

1. Keep `HttpServer` as the Ktor startup shell (engine config, CORS, status
   pages, `routing {}` entry point).
2. Extract route installer functions, one per feature area:
   - `installWebAssetRoutes` — `/`, `/index.html`, `/styles.css`, `/script.js`
   - `installStreamRoutes` — `/stream`, `/snapshot`
   - `installCameraControlRoutes` — `/cameras`, `/selectCamera`,
     `/toggleFlashlight`, `/flashOn`, `/flashOff`, `/formats`, `/setFormat`,
     `/setCameraOrientation`, `/setRotation`
   - `installOverlayRoutes` — `/setResolutionOverlay`, `/setDateTimeOverlay`,
     `/setBatteryOverlay`, `/setFpsOverlay`, `/setMjpegFps`, `/setRtspFps`
   - `installStatusRoutes` — `/status`, `/metrics`, `/events`, `/connections`,
     `/closeConnection`, `/stats`, `/cameraState`, `/logs`
   - `installAdminRoutes` — `/setConnectionLimits`, `/restart`,
     `/activateCamera`, `/deactivateCamera`, `/resetCamera`,
     `/overrideBatteryLimit`
   - `installRtspConfigRoutes` — `/enableRTSP`, `/disableRTSP`, `/rtspStatus`,
     `/setRTSPBitrate`, `/setRTSPBitrateMode`
   - `installDeviceOpsRoutes` — `/checkUpdate`, `/triggerUpdate`, `/reboot`,
     `/diagnostics/*`
3. Extract `MjpegStreamRegistry` — owns `StreamClient` map, per-IP replacement,
   global cap enforcement, `acquireStreamLease` / `releaseStreamClient`.
4. Extract `SseHub` — owns `SSEClient` list, `writeSseMessage`,
   `broadcastSseMessage`, `broadcastCameraState`, `broadcastMetrics`.
5. Each route installer is a top-level or extension function that takes only the
   ports and registries it needs as parameters.
6. Extract shared helpers: `escapeJson`, boolean query parsing, FPS validation,
   `substituteTemplateVariables`.

### Why

`HttpServer.kt` is ~2000 lines with a flat structure. After extraction, the
startup shell will be ~100 lines, and each route group will be a focused file
of 50–200 lines. `MjpegStreamRegistry` and `SseHub` encapsulate connection
lifecycle that currently clutters route handlers.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `MjpegStreamRegistry`: per-IP replacement, global cap,
  lease acquire/release, `activeStreams` counter correctness.
- Add JVM unit tests for `SseHub`: client eviction at limit, broadcast delivery
  to multiple clients, timeout handling.
- Add JVM unit tests for shared helpers: `escapeJson`, boolean parsing, FPS
  validation range.
- Existing MJPEG, SSE, status, connection, and configuration instrumented tests
  must pass unchanged.

### Documentation

- Document the route group structure: which routes belong to each installer
  function and what ports/registries each group depends on.
- Document `MjpegStreamRegistry` API: per-IP replacement policy, global cap,
  lease lifecycle.
- Document `SseHub` API: client management, broadcast semantics, eviction policy.
- Update architecture documentation to reflect the new HTTP server structure.

### Exit criterion

`HttpServer.kt` is under ~200 lines. Route logic lives in focused installer
functions. MJPEG and SSE connection management live in their own classes.
Documentation reflects the new structure.

---

## Step 10 — Modularize RTSP server internals

### What to do

1. Keep `RTSPServer` as the socket acceptor and composition root.
2. Extract `RtspProtocolHandler` — RTSP method dispatch (`handleOptions`,
   `handleDescribe`, `handleSetup`, `handlePlay`, `handlePause`,
   `handleTeardown`), SDP generation, `sendResponse`.
3. Extract `RtspSessionRegistry` — session map, `RTSPSession` data class,
   session creation/removal, camera lease tracking (`acquireCameraLease`,
   `releaseCameraLease`, `releaseAllCameraLeases`), playing session limit
   enforcement.
4. Extract `RtpPacketizer` — `createRTPPacket`, `packetizeNALUnit`, timestamp
   normalization, `parseNALUnitsFromBuffer`.
5. Extract `CodecConfigStore` — `CodecConfigState`, `EncoderSession`,
   `CodecConfigSnapshot`, `updateCodecConfig`, `getReadyCodecConfig`,
   `invalidateCodecConfig`.
6. `RTSPServer` wires these together: accepts connections, creates a
   `RtspProtocolHandler` per client, and delegates frame distribution.

### Why

`RTSPServer.kt` is ~1400 lines mixing protocol parsing, binary packetization,
session lifecycle, and codec state. RTP packetization and codec config are pure
logic that can be fully unit-tested. Session management has its own state
machine. Separating them makes each concern independently understandable and
testable.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `RtpPacketizer`: single NAL vs FU-A fragmentation,
  sequence number wrapping, timestamp calculation, marker bit.
- Add JVM unit tests for `CodecConfigStore`: SPS/PPS storage, snapshot
  consistency, invalidation.
- Add JVM unit tests for `RtspSessionRegistry`: session limit enforcement,
  camera lease counting, session state transitions.
- Existing RTSP instrumented tests must pass unchanged.

### Documentation

- Document `RtspProtocolHandler` API: supported RTSP methods and SDP generation.
- Document `RtspSessionRegistry` API: session lifecycle, camera lease management,
  and playing session limits.
- Document `RtpPacketizer` API: NAL unit handling, FU-A fragmentation, sequence
  numbering.
- Document `CodecConfigStore` API: SPS/PPS storage, snapshot access, invalidation.
- Update architecture documentation to reflect the modular RTSP server structure.

### Exit criterion

`RTSPServer.kt` is a thin acceptor under ~200 lines. Protocol, sessions,
packetization, and codec config each have their own file. Documentation reflects
the modularization.

---

## Step 11 — Extract device operations

### What to do

1. Create `UpdateCoordinator` — owns the update check and install flow.
2. Move shared update orchestration from `MainActivity` and `HttpServer` into
   `UpdateCoordinator`. `UpdateManager` stays as the low-level GitHub API and
   installer; `UpdateCoordinator` provides the high-level "check → confirm →
   download → install" workflow.
3. Create `DeviceOpsCoordinator` — owns reboot, diagnostics assembly, and
   WiFi-debugging lifecycle.
4. Both `HttpServer` (via admin routes) and `MainActivity` become thin callers
   of these coordinators instead of each implementing their own orchestration.
5. `RebootHelper` and `WiFiDebuggingManager` remain as low-level utilities used
   by `DeviceOpsCoordinator`.

### Why

Update checks and reboot flows are currently duplicated between HTTP endpoints
and UI buttons, with slightly different error handling. A single coordinator per
operation eliminates this duplication and ensures consistent behavior regardless
of entry point.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `UpdateCoordinator`: state transitions (idle → checking
  → downloading → installing), error handling, version comparison.
- Existing update and reboot instrumented tests must pass unchanged.

### Documentation

- Document `UpdateCoordinator` API: state machine (idle → checking → downloading
  → installing), entry points, and error handling contract.
- Document `DeviceOpsCoordinator` API: reboot flow, diagnostics assembly, and
  WiFi-debugging lifecycle.
- Update architecture documentation to show the unified device operations layer
  replacing duplicated logic in `MainActivity` and `HttpServer`.

### Exit criterion

`MainActivity` and `HttpServer` no longer contain update or reboot orchestration
logic. They delegate to coordinators. Documentation reflects the new coordination
layer.

---

## Step 12 — Thin MainActivity into a view layer

### What to do

1. Create `MainPresenter` (or `MainViewModel` if Jetpack ViewModel is adopted)
   — owns UI state assembly, service binding lifecycle, spinner/checkbox state
   derivation, and periodic metrics refresh.
2. Move from `MainActivity`:
   - `ServiceBindingState` and binding logic (`ensureServiceBinding`,
     `releaseServiceBinding`, `ServiceConnection` creation)
   - `metricsUpdateRunnable` / `metricsUpdateHandler` logic
   - `updateUI`, `updateConnectionsUI`, `updateFpsDisplay` state derivation
   - `loadCameraOptions`, `loadResolutions`, `loadConnectionLimitOptions`,
     `loadOsdSettings`, `loadFpsSettings`, `loadDeviceName`
   - Permission state tracking
3. `MainActivity` becomes a thin view adapter: `onCreate` inflates layout, wires
   click listeners to presenter methods, observes presenter state to update
   views.
4. Keep the XML layout and all visible UX behavior identical.

### Why

`MainActivity` is ~2200 lines. Most of that is state assembly and service
interaction, not Android view code. Extracting a presenter makes the UI logic
testable without instrumentation and makes it feasible to migrate to Compose
in the future without rewriting business logic.

### Tests

- Run the full test suite after extraction.
- Add JVM unit tests for `MainPresenter`: UI state derivation from service
  state, binding lifecycle transitions, periodic refresh scheduling.
- Existing instrumented tests that interact with the UI must pass unchanged.

### Documentation

- Document `MainPresenter` API: exposed UI state properties, service binding
  lifecycle, refresh scheduling, and how the Activity observes state changes.
- Update architecture documentation to reflect the presenter/view separation.
- Perform a final review of all project documentation to ensure it matches the
  post-refactoring architecture.

### Exit criterion

`MainActivity` is under ~500 lines and contains only `findViewById`, listeners,
and observer wiring. All state derivation and service interaction live in
`MainPresenter`. All project documentation is fully up to date with the final
architecture.

---

## Expected result

After all 12 steps, the approximate file layout:

```
com/ipcam/
  CameraService.kt            ~800 lines  (orchestrator: wires components, handles service lifecycle)
  settings/
    RuntimeSettingsRepository.kt
    UiPrefsRepository.kt
  camera/
    CameraCatalog.kt
    CameraBinder.kt
    TorchController.kt
    CameraDemandTracker.kt
  frame/
    FramePipeline.kt           (includes FrameStore, uses BitmapPool internally)
  ops/
    BatteryPolicy.kt
    CameraWatchdog.kt
    TelemetryCoordinator.kt
  transport/
    http/
      HttpServer.kt            ~200 lines  (Ktor shell + route wiring)
      MjpegStreamRegistry.kt
      SseHub.kt
      routes/                   (route installer files)
    rtsp/
      RTSPServer.kt            ~200 lines  (socket acceptor + wiring)
      RtspProtocolHandler.kt
      RtspSessionRegistry.kt
      RtpPacketizer.kt
      CodecConfigStore.kt
  device/
    UpdateCoordinator.kt
    DeviceOpsCoordinator.kt
    UpdateManager.kt
    RebootHelper.kt
    WiFiDebuggingManager.kt
    DeviceAdminReceiver.kt
  ui/
    MainActivity.kt            ~500 lines  (thin view adapter)
    MainPresenter.kt
  model/
    CameraOption.kt
    ConnectionModels.kt
    DiagnosticModels.kt
    RuntimeTelemetry.kt
    PerformanceMetrics.kt
  ports/
    CameraControlPort.kt
    StreamingPort.kt
    TelemetryPort.kt
    AdminPort.kt
  BootReceiver.kt
  BuildInfo.kt
  InMemoryLogBuffer.kt
  H264PreviewEncoder.kt
```

**Size estimate:** Total source should be roughly the same or slightly smaller
than today (~10 K lines), because dead code removal, deduplication of
update/reboot flows, and extraction of shared helpers offset the small overhead
of new interface definitions. The critical metric is not line count but that no
single file exceeds ~800 lines and each file has a single clear responsibility.

## What this plan does NOT include

- **Gradle multi-module split.** Not warranted at ~10 K lines. Revisit if the
  project grows significantly.
- **Dependency injection framework.** Constructor injection is sufficient.
  Adding Dagger/Hilt would increase complexity without proportional benefit.
- **Jetpack Compose migration.** Orthogonal to architecture. Can be done after
  Step 12 if desired.
- **New features or behavior changes.** Every step preserves externally visible
  behavior exactly.
