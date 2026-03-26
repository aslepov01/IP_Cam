# IP_Cam documentation index

This directory holds project documentation for the IP_Cam Android app. Use the sections below to find the right file.

## Core documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System architecture, components, data flow, and threading model. Replaces the former `ANALYSIS.md` overview. |
| [IMPLEMENTATION.md](IMPLEMENTATION.md) | Implementation details for each component (streaming, lifecycle, web server, persistence, and related behavior). |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Requirements specification with implementation status. |
| [TESTING.md](TESTING.md) | Test suite guide: JVM tests plus 59 on-device instrumentation tests, execution, and troubleshooting. |

## Operational guides

| Document | Description |
|----------|-------------|
| [AUTO_UPDATE_IMPLEMENTATION.md](AUTO_UPDATE_IMPLEMENTATION.md) | OTA updates via GitHub Releases. **Implemented:** `UpdateManager.kt`, HTTP endpoints `/checkUpdate` and `/triggerUpdate`. |
| [SIGNING_SETUP.md](SIGNING_SETUP.md) | APK signing configuration for auto updates. |
| [CAMERA_RESET_AND_REBOOT_GUIDE.md](CAMERA_RESET_AND_REBOOT_GUIDE.md) | Camera reset and device reboot troubleshooting. |
| [DEVICE_OWNER_TROUBLESHOOTING.md](DEVICE_OWNER_TROUBLESHOOTING.md) | Device Owner setup and troubleshooting. |
| [SILENT_UPDATES.md](SILENT_UPDATES.md) | Silent update options for remotely managed devices. |

## Technical reference

| Document | Description |
|----------|-------------|
| [FPS_CALCULATION.md](FPS_CALCULATION.md) | How FPS is tracked and reported for camera capture, MJPEG, and RTSP paths. |
| [HISTORY.md](HISTORY.md) | Development history consolidated from 61 prior documents. |

## Quick reference: questions → docs

| Question | Start here |
|----------|------------|
| How is the app structured? Where does data flow and which threads run what? | [ARCHITECTURE.md](ARCHITECTURE.md) |
| How is a specific feature or module built? | [IMPLEMENTATION.md](IMPLEMENTATION.md) |
| What was required, and is it done? | [REQUIREMENTS.md](REQUIREMENTS.md) |
| How do I run unit and device tests? | [TESTING.md](TESTING.md) |
| How do OTA updates work and what is wired up today? | [AUTO_UPDATE_IMPLEMENTATION.md](AUTO_UPDATE_IMPLEMENTATION.md) |
| How do I sign builds for update compatibility? | [SIGNING_SETUP.md](SIGNING_SETUP.md) |
| Camera or device reboot issues | [CAMERA_RESET_AND_REBOOT_GUIDE.md](CAMERA_RESET_AND_REBOOT_GUIDE.md) |
| Device Owner / provisioning problems | [DEVICE_OWNER_TROUBLESHOOTING.md](DEVICE_OWNER_TROUBLESHOOTING.md) |
| Updates without user interaction | [SILENT_UPDATES.md](SILENT_UPDATES.md) |
| FPS numbers and how they are computed | [FPS_CALCULATION.md](FPS_CALCULATION.md) |
| Why past decisions were made | [HISTORY.md](HISTORY.md) |

---

**Tracked documentation files (this index):** `ARCHITECTURE.md`, `IMPLEMENTATION.md`, `REQUIREMENTS.md`, `TESTING.md`, `AUTO_UPDATE_IMPLEMENTATION.md`, `SIGNING_SETUP.md`, `CAMERA_RESET_AND_REBOOT_GUIDE.md`, `DEVICE_OWNER_TROUBLESHOOTING.md`, `SILENT_UPDATES.md`, `FPS_CALCULATION.md`, `HISTORY.md`, and this `README.md`.
