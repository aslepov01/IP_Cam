# IP_Cam - Testing Guide

## Overview

The project has an automated core test suite built around two layers:
- fast JVM unit tests for pure logic
- real-device instrumentation tests for camera, streaming, lifecycle, and cleanup behavior

This test suite is intended to protect the core runtime while refactoring and while adding new streaming features.

Automated coverage currently includes:
- HTTP server startup and health
- manual camera lease acquire and release
- one-shot snapshot capture and concurrent snapshot callers
- camera catalog validation and camera switching
- format selection and connection limit normalization
- flashlight API behavior on flash and non-flash cameras
- persisted camera selection, resolution, and rotation across app restart
- live camera and format reconfiguration under active MJPEG and RTSP streams
- MJPEG streaming, eviction, and cleanup
- SSE streaming, eviction, and targeted shutdown
- RTSP handshake, playback, pause/resume, eviction, shutdown, and live bitrate/FPS reconfiguration
- mixed SSE + MJPEG + RTSP consumer scenarios
- server reachability after MainActivity closes
- connection accounting and runtime telemetry quiescence
- `/metrics` and `/stats` reporting
- OSD overlay HTTP toggles and validation
- `/restart` dropping long-lived clients while preserving limits
- `/resetCamera` from idle and with active MJPEG
- critical-battery streaming policy and override (where shell simulation works)
- `BootReceiver` autostart behavior without full device reboot
- target MJPEG/RTSP FPS and RTSP bitrate/mode HTTP APIs

Manual testing remains useful for browser UX, external client compatibility, OTA flows, and long-duration thermal checks, but core regressions should be caught by the automated suite first.

## Test Layers

### JVM Unit Tests

Location:
- `app/src/test/java/com/ipcam`

Covered areas:
- `ConnectionLimitsTest`
  - normalization of configured limits
  - migration from legacy max-connections setting
- `RuntimeTelemetrySamplerTest`
  - bandwidth accounting for MJPEG and RTSP
  - reset semantics for telemetry sampling

Run:

```bash
./gradlew testDebugUnitTest
```

### Real-Device Instrumentation Tests

Location:
- `app/src/androidTest/java/com/ipcam/coretests`
- `app/src/androidTest/java/com/ipcam/testsupport`

Current suite size:
- **59** real-device instrumentation tests across **17** test classes

Covered areas by class (each line lists the `@Test` count):

- **`ServerAndLifecycleInstrumentedTest`** (4) — `/status` health, manual camera lease `IDLE -> ACTIVE -> IDLE`, snapshot acquire/release, baseline lifecycle behavior.

- **`CameraConfigurationInstrumentedTest`** (5) — `/cameras` integrity, invalid camera rejection, multi-camera selection, format changes, connection limits via HTTP API.

- **`MjpegStreamingInstrumentedTest`** (4) — multipart MJPEG happy path, same-client eviction, global limit eviction, post-eviction cleanup and telemetry quiescence.

- **`SseStreamingInstrumentedTest`** (6) — SSE event stream happy path, SSE not activating the camera alone, limit eviction, targeted shutdown via `/closeConnection`, and related SSE edge cases.

- **`RtspStreamingInstrumentedTest`** (7) — RTSP `OPTIONS` through `TEARDOWN`, interleaved RTP over TCP, session limit eviction, cleanup without explicit `TEARDOWN`, `PAUSE`/`PLAY` lease behavior, disabling RTSP with an active client, passive `DESCRIBE` lease lifecycle.

- **`ConnectionManagementInstrumentedTest`** (6) — mixed SSE + MJPEG + RTSP accounting, camera stays active until the last video consumer disconnects, concurrent MJPEG from distinct client addresses, targeted MJPEG shutdown.

- **`DynamicReconfigurationInstrumentedTest`** (4) — camera and format changes while MJPEG or RTSP is already active.

- **`FlashlightAndPersistenceInstrumentedTest`** (5) — flashlight `toggle` / `flashOn` / `flashOff` on capable hardware, graceful rejection without flash, persistence of camera, format, and rotation across app restart.

- **`ServicePersistenceInstrumentedTest`** (1) — after closing MainActivity, server stays reachable and snapshots still work with the service in the background.

- **`BatteryPolicyInstrumentedTest`** (1) — critical-battery streaming policy via shell battery simulation: service state, MJPEG blocked or overridden per API, recovery when level is raised (skipped when simulation is ineffective).

- **`BootReceiverInstrumentedTest`** (3) — `BootReceiver` autostart vs preferences by invoking `onReceive` directly (no full reboot), CE vs device-protected storage alignment.

- **`CameraResetInstrumentedTest`** (2) — `/resetCamera` from idle (fresh snapshot, clean connections) and with active MJPEG (pipeline restarts, stream recovers, clean teardown).

- **`MetricsAndStatsInstrumentedTest`** (2) — `/metrics` field completeness at idle; under load metrics reflect streaming; `/stats` plain-text sections.

- **`OverlayConfigurationInstrumentedTest`** (2) — OSD overlay HTTP endpoints (date/time, battery, resolution, FPS): truthy/falsy values, invalid input rejection, applied flags in JSON.

- **`RestartServerInstrumentedTest`** (1) — `/restart` disconnects long-lived SSE/MJPEG clients, HTTP comes back, persisted connection limits unchanged, clean idle teardown.

- **`SnapshotConcurrencyInstrumentedTest`** (3) — concurrent `/snapshot` threads all get JPEGs without wedging; `/snapshot` while MJPEG is active (stream keeps frames); `/snapshot` while RTSP is playing (RTP continues until teardown).

- **`StreamRateAndRtspConfigInstrumentedTest`** (3) — `/setMjpegFps` and `/setRtspFps` validation; RTSP bitrate and bitrate-mode validation; `/rtspStatus` vs live changes while a TCP RTSP session is playing.

Run:

```bash
./gradlew connectedDebugAndroidTest
```

Run on one specific USB-connected device:

```bash
ANDROID_SERIAL=<device-serial> ./gradlew connectedDebugAndroidTest
```

Run a single class or test method:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ipcam.coretests.RtspStreamingInstrumentedTest

./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ipcam.coretests.SseStreamingInstrumentedTest#sseLimitEvictsOldestClient
```

## Device Test Requirements

- Android device connected through USB and visible in `adb devices`
- camera hardware available on the device
- USB debugging enabled
- device state that allows camera access during tests

The Gradle configuration installs debug packages with runtime permissions granted automatically. The instrumentation suite is designed for real hardware because CameraX, MediaCodec, MJPEG, RTSP, and lease/release behavior are device-dependent.

If Google Play Protect blocks installation of the instrumentation package `com.ipcam.test` on a dedicated local test phone, temporarily pause or disable Play Protect for the test run and enable it again afterward.

## Test Isolation

Every device test is expected to leave the runtime clean for the next test.

Isolation mechanisms:
- Android Test Orchestrator is enabled
- package data is cleared between tests
- every test launches a fresh app instance
- long-lived local test clients are tracked and closed explicitly
- RTSP is disabled during teardown if needed
- preferences and external test files are cleared

Supporting classes (under `app/src/androidTest/java/com/ipcam/testsupport`):
- `BaseDeviceCoreTest`
- `DeviceTestEnvironment`
- `LongLivedClients.kt`
- `RawHttpSupport.kt`

## Recommended Validation Flow

Routine validation:

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

Focused iteration on one area:

```bash
./gradlew testDebugUnitTest
ANDROID_SERIAL=<device-serial> ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ipcam.coretests.MjpegStreamingInstrumentedTest
```

Before merging changes that affect camera or streaming runtime:
- run the full JVM suite
- run the full device suite on at least one real device
- if possible, repeat the device suite on a second phone with a different Android version or vendor skin

## Manual Scenarios Not Covered Yet

The previous version of this document included several manual scenarios that are still useful and are not fully covered by the current automated suite.

Keep these scenarios in the regression checklist:

- UI start and stop flow
  - verify the MainActivity buttons
  - verify notification appearance and removal
  - verify the displayed server URL matches actual reachability

- Flashlight behavior
  - verify physical LED behavior on real hardware
  - verify visual/UX behavior in the app UI and web UI

- Stream reconfiguration under active load
  - stress longer reconfiguration sequences, not just one switch
  - verify repeated rebinds do not degrade over time

- Long-running stability
  - keep MJPEG running for 30+ minutes
  - keep RTSP running in VLC or FFmpeg for 15+ minutes
  - watch for freezes, drift, reconnect loops, or resource growth

- High-load behavior
  - open many concurrent MJPEG clients, not just small-count automated checks
  - watch non-stream endpoints during load
  - verify no starvation, runaway CPU, or broken cleanup

- Background persistence and process lifecycle
  - send app to background and verify continuity
  - dismiss from recents and verify service continuity
  - validate full task-removal behavior beyond simple activity close

- Network change recovery
  - disconnect and reconnect Wi-Fi
  - verify server restart, URL refresh, and renewed reachability

- Watchdog and recovery scenarios
  - simulate app or service failure
  - verify restart timing and state recovery

- Settings persistence across restart
  - validate additional persisted settings beyond camera, format, and rotation
  - force-stop-specific behavior remains manual because force-stopping the target package also kills instrumentation

- External client compatibility
  - VLC for MJPEG and RTSP
  - FFmpeg or ffplay for RTSP TCP and UDP
  - browser checks for desktop and mobile
  - surveillance systems such as ZoneMinder, Shinobi, and Blue Iris

- Performance characterization
  - frame rate validation
  - end-to-end latency measurement
  - bandwidth checks
  - CPU, memory, and thermal behavior under sustained load

## Manual and Exploratory Testing

Keep these checks for final validation:
- browser UI sanity at `GET /`
- VLC or FFmpeg verification for external MJPEG and RTSP playback
- long-running thermal and battery checks
- OTA update flow
- device owner, reboot, and boot receiver scenarios
- compatibility checks with surveillance systems such as ZoneMinder, Shinobi, and Blue Iris

Useful repository scripts:
- `test_concurrent_connections.sh`
- `test_rtsp_reliability.sh`
- `test_version_endpoint.sh`

## Troubleshooting

### No device detected

```bash
adb devices
```

Reconnect USB, confirm the authorization dialog on the phone, and retry.

### Camera-related failures on a new device

Inspect runtime permissions:

```bash
adb shell dumpsys package com.ipcam | grep -A 5 runtime
```

If the device got into a bad install state, remove both debug packages and rerun the suite:

```bash
adb uninstall com.ipcam
adb uninstall com.ipcam.test
./gradlew connectedDebugAndroidTest
```

### A single instrumentation test is flaky

Run the failing class or method in isolation first. Real-device failures are often caused by transient device state, install state, or vendor-specific camera timing.

### Play Protect blocks `com.ipcam.test`

Some phones show a Play Protect warning for the instrumentation APK during local sideload-based test runs. On a dedicated test phone, temporarily pause or disable Play Protect during the run.

## Related Documentation

- [README.md](/Users/aslepov/projects/IP_Cam/README.md)
- [ARCHITECTURE.md](/Users/aslepov/projects/IP_Cam/documentation/ARCHITECTURE.md)

**Last Updated:** 2026-03-26
