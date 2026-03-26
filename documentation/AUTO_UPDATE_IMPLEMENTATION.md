# OTA Auto-Update

**Status:** Production — implemented and working  

**Last Updated:** 2026-03-26

## Overview

IP_Cam ships over-the-air (OTA) updates via **GitHub Releases**: CI builds and signs a release APK, publishes it, and the app queries the GitHub API, downloads when newer, and runs installation through Android’s package APIs.

## How it works

### Release pipeline

- **Workflow:** [`.github/workflows/version-and-release.yml`](../.github/workflows/version-and-release.yml) — **not** `release.yml`.
- On **push to `main`**, with `paths-ignore` for `.github/workflows/**`, `docs/**`, and `**.md`, the **version bump** job increments **`VERSION_NAME`** / **`VERSION_CODE`** in **`version.properties`**, commits (`[skip ci]`), and pushes.
- If the version file changed, **build-and-release** checks out `main`, runs `./gradlew assembleRelease`, **signs** the APK (r0adkll/sign-android-release), and **creates a GitHub Release** with the signed APK. The release **tag** is **`v{BUILD_NUMBER}`** (aligned with generated `BuildConfig`).

### On the device

- **`UpdateManager.kt`** queries `releases/latest`, derives the latest build number from the tag, and compares it to **`BuildInfo.buildNumber`** (backed by **`BuildConfig.BUILD_NUMBER`** at compile time).
- If newer: downloads the release **`.apk`** asset over **HTTPS**, then triggers install (see implementation for PackageInstaller vs FileProvider paths).
- **HTTP:** **`GET /checkUpdate`** and **`GET /triggerUpdate`** — routed in **`HttpServer.kt`**.
- **Web UI:** **Software Update** in the **Server Management** tab — **`app/src/main/assets/web/`** (`index.html`, `script.js`).
- **App UI:** **Check for Update** — **`MainActivity`** + **`checkUpdateButton`** in **`activity_main.xml`**.

## Update flow

1. **Check** — In-app button, web UI, or `/checkUpdate` / `/triggerUpdate`.  
2. **Compare** — Latest release vs **`BuildInfo.buildNumber`**.  
3. **Download** — APK saved under app-accessible storage when an update exists.  
4. **Install** — System installer (or Device Owner flow where applicable); user confirmation when the platform requires it.  
5. **Restart** — App comes up on the new build after install.

## Security

- **Signed release APKs** in CI using the release keystore (never committed).  
- **HTTPS** for GitHub API and APK download.  
- **Android signature verification** — updates must match the installed app’s signing key.  
- **User confirmation** where Android mandates it; Device Owner deployments may use silent install paths implemented in **`UpdateManager`**.

## For developers

Keystore creation, local verification, and base64 packaging for CI: **[`SIGNING_SETUP.md`](SIGNING_SETUP.md)**.

**GitHub Actions secrets** (repository → Settings → Secrets and variables → Actions):

| Secret | Role |
|--------|------|
| `SIGNING_KEY` | Base64-encoded release keystore |
| `KEY_STORE_PASSWORD` | Keystore password |
| `ALIAS` | Key alias (e.g. `release`) |
| `KEY_PASSWORD` | Key password |

## Troubleshooting

| Problem | What to check |
|--------|----------------|
| **“Failed to check” / API errors** | Device network; unauthenticated GitHub API rate limits if polling often; latest release must include an `.apk` and a tag parseable as **`v<build number>`**. |
| **Update not offered** | Compare `/checkUpdate` `currentVersion` vs `latestVersion`; confirm the release tag is **greater** than local **`BuildInfo.buildNumber`**. |
| **Download fails** | Free space; retry; confirm the asset URL opens from a browser on the same network. |
| **Install blocked / “not installed”** | **Signature mismatch** — rebuilds must use the **same** keystore as the installed app. |
| **CI release job fails** | Actions log; local `./gradlew assembleRelease`; all **four** signing secrets set and correct. |

---

**Code reference (no duplicates here):** `UpdateManager.kt`, `HttpServer.kt`, `MainActivity.kt`, `app/src/main/assets/web/`, `version.properties`, `.github/workflows/version-and-release.yml`.
