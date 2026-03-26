# IP_Cam - Requirements Specification

## Table of Contents

1. [Overview](#overview)
2. [Functional Requirements](#functional-requirements)
3. [Non-Functional Requirements](#non-functional-requirements)
4. [Technical Requirements](#technical-requirements)
5. [API Requirements](#api-requirements)
6. [Implementation Status](#implementation-status)

---

## Overview

This document specifies the complete requirements for the IP_Cam Android application, which transforms Android devices into IP cameras for surveillance and monitoring applications.

**Version:** 1.0  
**Target Platform:** Android 11+ (API Level 30+); `compileSdk` 35, `targetSdk` 33  
**Primary Use Case:** Local network IP camera with surveillance system integration

---

## Functional Requirements

### FR-1: Camera Capture & Display

#### FR-1.1: Live Camera Preview ✅ IMPLEMENTED
**Requirement:** Display live camera preview in the Android application  
**Status:** Complete  
**Implementation:** CameraX preview with callback-based frame distribution

#### FR-1.2: Camera Selection ✅ IMPLEMENTED
**Requirement:** Support selecting the active camera from the available device cameras
**Status:** Complete
**Implementation:** Camera dropdown in UI and HTTP endpoints (`/cameras`, `/selectCamera?cameraId=ID`)

#### FR-1.3: Resolution Configuration ✅ IMPLEMENTED
**Requirement:** Allow user to select from available camera resolutions  
**Status:** Complete  
**Implementation:** Web UI dropdown with supported formats

#### FR-1.4: Flashlight Control ✅ IMPLEMENTED
**Requirement:** Toggle flashlight for the selected camera when torch hardware is available
**Status:** Complete
**Implementation:** In-app button and HTTP API (`/toggleFlashlight`, `/flashOn`, `/flashOff`)

#### FR-1.5: Rotation Control ✅ IMPLEMENTED
**Requirement:** Support 0°, 90°, 180°, 270° rotation and auto-rotation  
**Status:** Complete  
**Implementation:** API endpoint (`/setRotation?value=...`)

### FR-2: Video Streaming

#### FR-2.1: MJPEG Streaming ✅ IMPLEMENTED
**Requirement:** Provide MJPEG video stream over HTTP  
**Status:** Complete  
**Format:** `multipart/x-mixed-replace`  
**Endpoint:** `GET /stream`  
**Performance:** ~10 fps, 150-280ms latency

#### FR-2.2: RTSP Streaming ✅ IMPLEMENTED
**Requirement:** Provide RTSP/H.264 streaming as bandwidth-efficient alternative  
**Status:** Complete  
**Format:** RTSP with RTP/H.264  
**Performance:** 30 fps, 2-4 Mbps, 500ms-1s latency

#### FR-2.3: Multiple Concurrent Streams ✅ IMPLEMENTED
**Requirement:** Support 32+ simultaneous client connections  
**Status:** Complete  
**Implementation:** Thread pool expansion and dedicated streaming executor

#### FR-2.4: Dual-Stream Operation ✅ IMPLEMENTED
**Requirement:** MJPEG and RTSP can operate simultaneously  
**Status:** Complete  
**Implementation:** Single capture pipeline with frame duplication

### FR-3: Snapshot Capture

#### FR-3.1: Single Frame Capture ✅ IMPLEMENTED
**Requirement:** Provide API to capture single JPEG frame  
**Status:** Complete  
**Endpoint:** `GET /snapshot`  
**Format:** JPEG image

### FR-4: Web Interface

#### FR-4.1: Browser-Based Control ✅ IMPLEMENTED
**Requirement:** Provide web interface for camera control and viewing  
**Status:** Complete  
**Features:**
- Live MJPEG stream display
- Camera selection dropdown
- Flashlight toggle
- Format/rotation controls
- Real-time connection count

#### FR-4.2: Real-Time Status Updates ✅ IMPLEMENTED
**Requirement:** Display live connection and camera status  
**Status:** Complete  
**Implementation:** Server-Sent Events (SSE) with 2-second updates

#### FR-4.3: Responsive Design ✅ IMPLEMENTED
**Requirement:** Web UI works on mobile and desktop browsers  
**Status:** Complete  
**Implementation:** Responsive HTML/CSS

### FR-5: Device Configuration

#### FR-5.1: Device Naming ✅ IMPLEMENTED
**Requirement:** Allow custom device name for identification  
**Status:** Complete  
**Default:** `IP_CAM_{device_model}`  
**Persistence:** SharedPreferences

#### FR-5.2: Server Port Configuration ⚠️ PARTIALLY IMPLEMENTED
**Requirement:** Allow user to configure HTTP server port  
**Status:** Default listen port is defined in code (`CameraService` uses `PORT = 8080` as the preferred port; binding may advance to the next free port if 8080 is busy). There is no in-app settings UI to change the port without modifying source.  
**Note:** Change the `PORT` constant (or equivalent wiring) in code to use a different default.

#### FR-5.3: Auto-Start on Boot ✅ IMPLEMENTED
**Requirement:** Optionally start camera service on device boot  
**Status:** Complete  
**Implementation:** `BootReceiver` handles `BOOT_COMPLETED`; `autoStartServer` in SharedPreferences defaults to `true` so the service starts on boot unless the user disables it. The main activity exposes an auto-start checkbox tied to the same preference.

---

## Non-Functional Requirements

### NFR-1: Reliability

#### NFR-1.1: Service Persistence ✅ IMPLEMENTED
**Requirement:** Foreground service that survives app closure  
**Status:** Complete  
**Implementation:**
- START_STICKY restart policy
- Persistent notification
- Wake lock management
- Battery optimization exemption support

#### NFR-1.2: Automatic Recovery ✅ IMPLEMENTED
**Requirement:** Watchdog system to detect and recover from failures  
**Status:** Complete  
**Implementation:**
- 5-second health checks
- Exponential backoff (1s → 30s)
- Component restart on failure
- Network change detection

#### NFR-1.3: Settings Persistence ✅ IMPLEMENTED
**Requirement:** All settings survive app restart and device reboot  
**Status:** Complete  
**Storage:** SharedPreferences with immediate writes

### NFR-2: Performance

#### NFR-2.1: Frame Rate ✅ IMPLEMENTED
**Requirement:** Maintain target frame rates for streaming  
**Status:** Complete  
**MJPEG:** 10 fps target  
**RTSP:** 30 fps target

#### NFR-2.2: Latency ✅ IMPLEMENTED
**Requirement:** Minimize streaming latency for surveillance use  
**Status:** Complete  
**MJPEG:** 150-280ms  
**RTSP:** 500ms-1s

#### NFR-2.3: Resource Efficiency ✅ IMPLEMENTED
**Requirement:** Optimize CPU, memory, and bandwidth usage  
**Status:** Complete  
**Implementation:**
- Hardware-accelerated encoding (H.264)
- Efficient threading model
- Bitmap recycling
- Frame dropping for slow clients

#### NFR-2.4: Concurrent Connections ✅ IMPLEMENTED
**Requirement:** Support 32+ simultaneous clients without degradation  
**Status:** Complete  
**Implementation:** Thread pool (32) + streaming executor (unbounded)

### NFR-3: Compatibility

#### NFR-3.1: Surveillance System Integration ✅ IMPLEMENTED
**Requirement:** Work with popular NVR/surveillance systems  
**Status:** Complete  
**Tested With:**
- ZoneMinder
- Shinobi
- Blue Iris
- MotionEye
- VLC
- FFmpeg

#### NFR-3.2: Standard Protocols ✅ IMPLEMENTED
**Requirement:** Use standard streaming protocols  
**Status:** Complete  
**Protocols:**
- MJPEG (RFC 2046)
- RTSP/RTP (RFC 2326/3550)
- HTTP/1.1 (RFC 2616)

#### NFR-3.3: Android Version Support ✅ IMPLEMENTED
**Requirement:** Support Android 11+ (API 30+)  
**Status:** Complete  
**Minimum:** API 30 (Android 11)  
**Target:** API 33  
**Compile:** API 35

### NFR-4: Usability

#### NFR-4.1: Simple User Interface ✅ IMPLEMENTED
**Requirement:** Intuitive controls for end users  
**Status:** Complete  
**Features:**
- One-tap server start/stop
- Clear status indicators
- Real-time connection display
- Straightforward camera controls

#### NFR-4.2: Error Handling ✅ IMPLEMENTED
**Requirement:** Graceful error handling with informative messages  
**Status:** Complete  
**Implementation:**
- JSON or plain-text error payloads where applicable
- User-friendly error messages
- Automatic retry for recoverable errors

#### NFR-4.3: Documentation ✅ IMPLEMENTED
**Requirement:** Clear documentation for setup and API usage  
**Status:** Complete  
**Documents:**
- README with quick start
- API endpoint documentation
- Implementation details
- Testing guides

---

## Technical Requirements

### TR-1: Camera Framework

#### TR-1.1: CameraX Integration ✅ IMPLEMENTED
**Requirement:** Use CameraX for camera management  
**Status:** Complete  
**Version:** androidx.camera 1.4.1

#### TR-1.2: Single Camera Binding ✅ IMPLEMENTED
**Requirement:** One camera binding serves all consumers  
**Status:** Complete  
**Architecture:** CameraService owns binding, distributes frames via callbacks

### TR-2: Web Server

#### TR-2.1: Ktor Framework ✅ IMPLEMENTED
**Requirement:** Use Ktor for HTTP server implementation  
**Status:** Complete  
**Version:** Ktor 2.3.12

#### TR-2.2: Server-Sent Events ✅ IMPLEMENTED
**Requirement:** SSE support for real-time updates  
**Status:** Complete  
**Endpoint:** `GET /events`

### TR-3: Encoding

#### TR-3.1: JPEG Compression ✅ IMPLEMENTED
**Requirement:** JPEG encoding for MJPEG frames and snapshots  
**Status:** Complete  
**Quality:** 80% (configurable)

#### TR-3.2: H.264 Hardware Encoding ✅ IMPLEMENTED
**Requirement:** Hardware-accelerated H.264 for RTSP  
**Status:** Complete  
**Implementation:** MediaCodec API

### TR-4: Concurrency

#### TR-4.1: Threading Model ✅ IMPLEMENTED
**Requirement:** Separate executors for different task types  
**Status:** Complete  
**Executors:**
- Main: UI updates
- Camera: 1 thread for capture
- Processing: 2 threads for image processing
- HTTP: 32 threads for requests
- Streaming: Unbounded for long-lived streams
- Watchdog: Coroutine for monitoring

#### TR-4.2: Coroutines ✅ IMPLEMENTED
**Requirement:** Kotlin coroutines for async operations  
**Status:** Complete  
**Version:** kotlinx.coroutines 1.9.0

### TR-5: Lifecycle Management

#### TR-5.1: Service Lifecycle ✅ IMPLEMENTED
**Requirement:** Proper foreground service lifecycle  
**Status:** Complete  
**Type:** `android:foregroundServiceType="camera"`

#### TR-5.2: Callback Lifecycle ✅ IMPLEMENTED
**Requirement:** Lifecycle-aware callbacks to prevent crashes  
**Status:** Complete  
**Implementation:** Explicit registration/unregistration tied to lifecycle

#### TR-5.3: AndroidX Lifecycle ✅ IMPLEMENTED
**Requirement:** Use AndroidX Lifecycle libraries where applicable  
**Status:** Complete  
**Version:** 2.8.7

### TR-6: Language & Toolchain

#### TR-6.1: Kotlin ✅ IMPLEMENTED
**Requirement:** Kotlin language and Android Gradle plugin alignment  
**Status:** Complete  
**Version:** Kotlin 2.1.0

---

## API Requirements

### API-1: HTTP Endpoints

#### API-1.1: Core Endpoints ✅ IMPLEMENTED
**Requirement:** HTTP API for camera control, diagnostics, static web assets, and status  
**Status:** Complete

**Implemented routes** (all registered as `GET` in `HttpServer.kt`):

| Endpoint | Purpose |
|----------|---------|
| `/` | Web interface (index) |
| `/index.html` | Web interface (alias) |
| `/styles.css` | Static stylesheet |
| `/script.js` | Static script |
| `/snapshot` | Single JPEG frame |
| `/stream` | MJPEG video stream (`multipart/x-mixed-replace`) |
| `/cameras` | List available cameras |
| `/selectCamera` | Select camera by `cameraId` query parameter |
| `/toggleFlashlight` | Toggle torch |
| `/flashOn` | Turn torch on |
| `/flashOff` | Turn torch off |
| `/status` | JSON status summary |
| `/metrics` | JSON metrics |
| `/events` | SSE real-time updates |
| `/connections` | Active connection listing |
| `/closeConnection` | Close a connection (query parameters per handler) |
| `/stats` | Detailed statistics JSON |
| `/formats` | Supported capture formats / resolutions |
| `/setFormat` | Set resolution / format |
| `/setCameraOrientation` | Set camera orientation |
| `/setRotation` | Set rotation |
| `/setResolutionOverlay` | Toggle resolution overlay |
| `/setDateTimeOverlay` | Toggle date/time overlay |
| `/setBatteryOverlay` | Toggle battery overlay |
| `/setFpsOverlay` | Toggle FPS overlay |
| `/setMjpegFps` | Set MJPEG target FPS |
| `/setRtspFps` | Set RTSP target FPS |
| `/setConnectionLimits` | Adjust connection limits |
| `/restart` | Restart HTTP server |
| `/enableRTSP` | Enable RTSP streaming |
| `/disableRTSP` | Disable RTSP streaming |
| `/rtspStatus` | RTSP status JSON |
| `/setRTSPBitrate` | Set RTSP bitrate |
| `/setRTSPBitrateMode` | Set RTSP bitrate mode |
| `/overrideBatteryLimit` | Override battery-related streaming limits |
| `/cameraState` | Camera state JSON |
| `/activateCamera` | Activate camera pipeline |
| `/deactivateCamera` | Deactivate camera pipeline |
| `/resetCamera` | Reset camera / recovery |
| `/logs` | Log output for debugging |
| `/diagnostics/camera` | Camera diagnostics |
| `/diagnostics/reboot` | Reboot-related diagnostics |
| `/checkUpdate` | Check for application update |
| `/triggerUpdate` | Trigger update flow |
| `/reboot` | Device reboot (privileged / device-owner contexts as implemented) |

#### API-1.2: Response Payloads ✅ IMPLEMENTED
**Requirement:** Predictable behavior per endpoint  
**Status:** Complete  
**Note:** There is **no** single unified JSON envelope across all endpoints. Responses include ad-hoc JSON objects, plain text, binary JPEG (`/snapshot`), `multipart` MJPEG (`/stream`), and `text/event-stream` (`/events`). Clients should treat each route according to its `Content-Type` and documented handler behavior.

#### API-1.3: CORS Support ✅ IMPLEMENTED
**Requirement:** CORS headers for web-based clients  
**Status:** Complete  
**Headers:** `Access-Control-Allow-Origin: *`

### API-2: RTSP Protocol

#### API-2.1: RTSP Server ✅ IMPLEMENTED
**Requirement:** RTSP server on port 8554  
**Status:** Complete  
**URL:** `rtsp://<device-ip>:8554/stream`

#### API-2.2: Transport Modes ✅ IMPLEMENTED
**Requirement:** Support UDP and TCP transport  
**Status:** Complete  
**Methods:** Automatic negotiation, fallback to TCP

---

## Implementation Status

### Summary by Category

| Category | Total | Implemented | Partial | Open |
|----------|-------|-------------|---------|------|
| Functional | 16 | 15 | 1 | 0 |
| Non-Functional | 13 | 13 | 0 | 0 |
| Technical | 12 | 12 | 0 | 0 |
| API | 5 | 5 | 0 | 0 |
| **TOTAL** | **46** | **45** | **1** | **0** |

**Completion:** 45 of 46 requirement items fully implemented; 1 partial (FR-5.2). Approximately **97.8%** full implementation by item count.

### Open/Partial Items

1. **FR-5.2: Server Port Configuration** ⚠️ PARTIAL  
   - Preferred port is configurable in source (default 8080); server may bind to the next available port if busy.  
   - No end-user settings UI to choose the port at runtime.

### Recommended Enhancements

#### High Priority
- [ ] Add settings UI for HTTP listen port (runtime configuration)
- [ ] Implement adaptive bitrate for MJPEG based on network conditions
- [ ] Add authentication/security for network access

#### Medium Priority
- [ ] PTZ controls simulation (digital zoom/pan)
- [ ] Audio streaming support
- [ ] Motion detection with notifications

#### Low Priority
- [ ] Cloud storage integration
- [ ] Multi-camera support
- [ ] Custom overlay text/timestamp

---

## Related Documentation

- **[Implementation](IMPLEMENTATION.md)** — Current implementation details  
- **[Architecture](ARCHITECTURE.md)** — System structure and design  
- **[Testing](TESTING.md)** — Testing guides and procedures  

---

**Document Version:** 1.0  
**Last Updated:** 2026-03-26
