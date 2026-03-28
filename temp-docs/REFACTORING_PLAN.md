# IP_Cam Refactoring Plan V4

## Goal

Reduce coupling, enforce clearer module boundaries, and make the project easier
to maintain by shrinking god-classes before extracting subsystems. The target
state is:

- `CameraService` becomes an orchestrator instead of a 5000-line implementation dump.
- `HttpServer` stops mixing route wiring, connection registries, JSON assembly,
  diagnostics, and update/reboot orchestration in one file.
- `RTSPServer` stops mixing protocol parsing, session state, RTP packetization,
  and codec-config tracking.
- `MainActivity` becomes a thin view layer instead of a 2200-line UI/service
  controller.
- Cross-module dependencies are narrowed so callers depend on small ports, not
  on one massive service contract.

## Guiding principles

- **Cleanup before architecture.** Remove bugs, dead code, legacy branches, and
  duplication before any large extraction.
- **Each step must be shippable.** The project must compile and all tests must
  pass after every step.
- **Every step must update tests and documentation.** Each step must include
  targeted test additions/updates for the changed behavior and mandatory
  updates to all relevant documentation.
- **Keep steps atomic.** Each step should be executable as one focused task with
  a clear exit criterion.
- **Behavior stays stable by default.** Public API or UX changes are allowed
  only in explicit API-redesign steps or in explicit cleanup steps that retire
  confirmed dead/obsolete product surface.
- **Shrink before you split.** Extracting from smaller files produces better
  boundaries than extracting from legacy sludge.
- **No premature Gradle modularization.** The immediate problem is runtime
  coupling and god-classes, not the number of Gradle modules.

## Confirmed decision

- **Remove all legacy SharedPreferences migrations now.** There is no remaining
  requirement to support old keys such as `maxConnections`, `flashlightOn`,
  `cameraType`, `resolutionWidth`, `frontCameraResolution*`, or
  `backCameraResolution*`.
- **After removing those migrations, immediately run a second cleanup pass**
  over newly orphaned helpers, constants, tests, documentation, and code paths
  that existed only to support the migration layer.
- **`GET /camera` should stay compact.** Camera discovery and capabilities
  should live in dedicated read-only subresources rather than in one aggregated
  "everything about camera" document.
- **Change flashlight only through `PATCH /camera`.** Do not carry separate
  toggle/on/off flashlight actions into the target API.
- **Remove `/status` without a direct replacement overview endpoint.** The new
  resource-oriented surface should be consumed through its individual resources
  instead of recreating one large aggregated status document.
- **Remove `/stats` without a replacement debug-dump endpoint.** After the
  pressure-model cleanup, observability should flow through `/metrics` and
  `/logs` instead of preserving a second plain-text telemetry surface.
- **Remove `/cameraState` without a special probe replacement.** Tests and UI
  should use the ordinary resource model instead of preserving a dedicated
  legacy camera-state probe endpoint.
- **Make `GET /camera/options` contextual.** It should expose options for the
  current selected camera/configuration rather than acting as a full catalog
  dump. Keep the full camera catalog in a separate read-only subresource.
- **Do not make `GET /camera/options` polymorphic by `cameraId`.** Options for
  non-selected cameras should be read through dedicated catalog subresources,
  not through query-parameter overloading of the contextual options endpoint.
- **Keep `GET /camera/catalog` compact.** The catalog should expose only a
  lightweight list of cameras; detailed per-camera capabilities and resolution
  options belong to dedicated per-camera catalog subresources.
- **Use `GET /camera/catalog/{cameraId}` for per-camera catalog detail.**
  Do not add an extra `/options` suffix there unless a later requirement
  introduces multiple distinct per-camera detail resources.
- **Keep `GET /camera/snapshot` as an active snapshot endpoint.** It may
  temporarily create camera demand and wake the camera from `IDLE` in order to
  produce a JPEG, rather than being limited to a passive cached-frame read.
- **Do not promote `activeSnapshots` into the target model.** Snapshot demand is
  real, but the current HTTP-local concurrent snapshot counter should remain an
  internal coordination detail or disappear entirely after extraction; it
  should not become a first-class API, metrics, or connections concept.
- **Remove the separate camera-orientation concept.** `cameraOrientation` is a
  redundant abstraction because the behavior can be expressed fully via
  `rotation`. Keep one rotation model and delete the extra orientation setting,
  API, UI, persistence, and frame-processing branches that exist only to support it.
- **Simplify runtime telemetry.** Remove the `PerformanceMetrics` pressure
  model, adaptive-quality leftovers, and related dead code. Keep only the
  metrics that matter operationally: CPU, bandwidth, memory `used/total`, and
  battery state.
- **Expose memory usage in the user-facing metrics surface.** The reduced
  telemetry model should include memory `used/total` in the API and in the UI,
  instead of keeping memory statistics buried only in debug text dumps.
- **Do not treat Samsung Knox as a reboot blocker.** Reboot gating should be
  based on documented platform requirements, with device-owner status as the
  actual blocker. Knox may remain diagnostic-only if still useful, but it must
  not drive `rebootPossible`, UI blocking, or API rejection paths.
- **Remove Wi-Fi ADB management entirely.** `WiFiDebuggingManager` and all
  related ADB-over-WiFi maintenance, display, and integration logic should be
  deleted from the project rather than preserved as a secondary subsystem.
- **Keep the new HTTP surface minimal.** Do not introduce a separate `/ui`
  API layer for now; the web UI should use the same resource-oriented API as
  other clients.
- **Keep SSE only as a localized live-update adapter.** Ordinary resource
  endpoints remain the source of truth; `GET /events` is a secondary UI-only
  transport that must stay inside the HTTP layer.
- **Drive live UI updates through resource invalidation events, not SSE-aware
  domain code.** Domain/orchestrator components may publish narrow
  resource-change invalidations, but they must not know about SSE clients,
  SSE payloads, JSON deltas, or broadcast mechanics.
- **Use full resource snapshots on `GET /events`.** SSE messages should carry
  the current snapshot of a resource (`camera`, `mjpeg`, `rtsp`, `connections`,
  `metrics`, `system`, `update`) rather than field-level deltas or invalidation-only
  payloads.
- **Do not let SSE leak into connection-domain semantics.** SSE client
  management and fan-out stay in the HTTP transport module instead of shaping
  domain connection limits, core service interfaces, or business logic.
- **Use `/connections` as the connection-operations resource.**
  `GET /connections` returns active connections and current limits,
  `PATCH /connections` updates only connection limits, and
  `DELETE /connections/{id}` closes one specific connection.
- **Keep transport-specific connection state local.** HTTP/MJPEG/SSE and RTSP
  should keep their own local registries/sources. Cross-transport aggregation
  for `/connections` should happen through one thin facade with only aggregated
  snapshot reads and `close-by-id` routing, not through a global shared mutable
  registry.
- **Do not add `PATCH /system` yet.** Only introduce mutable system-resource
  fields when there is a clear, cohesive system resource to update.
- **Remove the public server-restart API.** Do not carry legacy `/restart` or
  a new `POST /system/restart` into the target HTTP surface. Keep internal
  server stop/start mechanics only as implementation details where they are
  still needed by the runtime itself.
- **Model reboot as a read/write pair under `/system/reboot`.**
  `GET /system/reboot` returns reboot capability/preflight data, and
  `POST /system/reboot` executes the action.
- **Model updates as a read/write pair under `/system/update`.**
  `GET /system/update` performs a live remote update check and also exposes the
  current local update-operation state from the coordinator, while
  `POST /system/update` starts the download/install flow.
- **Keep `POST /system/update` single-flight.** If an update operation is
  already in progress, repeated `POST /system/update` calls must not start a
  second flow; they should return the current update-operation state/ack
  instead.
- **Keep `GET /system` minimal.** It should expose only a compact system
  snapshot: `deviceName`, `serverUrl`, app/build info, manufacturer/model, and
  a device-owner/admin summary. Do not duplicate telemetry there.
- **Introduce one shared logging subsystem.** Create a logical logging module
  with a small `AppLogger`-style interface and simple configuration, so any
  component can write through the same entry point instead of mixing direct
  `android.util.Log` calls with ad-hoc writes to `InMemoryLogBuffer`.
- **Keep `/logs` capture policy inside the logging subsystem.** If the UI can
  change which log levels should reach `/logs`, that setting must belong to the
  logging subsystem itself as persistent sink configuration for the in-memory
  log sink, not as read-time filtering inside the HTTP endpoint and not inside
  `UiPrefsRepository`.
- **Keep `GET /logs` minimal as `text/plain`.** The logging subsystem may use a
  richer internal entry model, but the external `/logs` contract should stay a
  simple plain-text dump instead of growing into a structured log API.
- **Expose log-capture settings as a separate logs resource.** Keep the dump and
  the configuration separate: `GET /logs` stays a plain-text dump, while
  logging capture settings should live under a dedicated logs-settings resource
  such as `GET /logs/settings` and `PATCH /logs/settings`.
- **Keep settings repositories strictly persistent.** `RuntimeSettingsRepository`
  and `UiPrefsRepository` should store only persisted configuration, not
  runtime/derived state such as camera lifecycle state, telemetry, connection
  snapshots, client counts, or SSE/live-update state.
- **Keep `CameraCatalog` read-only.** Camera discovery/capabilities should be
  separated from selected-camera ownership and persistence. The catalog should
  not become a hidden stateful controller for the current camera choice.
- **Keep remembered resolution per camera.** Do not collapse camera resolution
  persistence to one global selected resolution; preserve per-camera remembered
  resolution as part of the camera-configuration domain.

## Current high-risk hotspots

| Area | Problem |
|------|---------|
| `CameraService.kt` | Camera catalog, CameraX binding, frame processing, torch, watchdog, telemetry, settings I/O, RTSP lifecycle, and server lifecycle are mixed together. |
| `HttpServer.kt` | Ktor bootstrap, 40+ routes, MJPEG registry, SSE fan-out, diagnostics, reboot/update flows, and manual JSON all live in one file. |
| `RTSPServer.kt` | Socket accept, RTSP protocol, RTP packetization, session lifecycle, and codec config are coupled in one implementation. |
| `MainActivity.kt` | Permissions, service binding, preference reads, UI state derivation, reboot/update flows, and metrics refresh are all embedded in the Activity. |
| `CameraServiceInterface.kt` | 71 methods; every caller depends on everything, and `HttpServer` still bypasses it in some places. |
| Logging | Most components log directly to Android `Log`, while the in-memory `/logs` buffer captures only a small hand-picked subset of events. There is no single logging entry point or shared policy. |

## Phase 1 — Cleanup and stabilization

### Step 1 — Fix correctness bugs before structural changes

**What to do**

1. Fix `HttpServer` lifecycle so restartable server coroutines still work after
   `stop()` / `start()`. `serverScope` must be recreated on restart instead of
   being a permanently cancelled singleton.
2. Remove the public `/restart` endpoint, its UI hooks, and its tests/docs
   instead of preserving remote server restart as part of the product surface.
3. Keep internal server stop/start cycles correct for runtime use:
   if the app still restarts the embedded server internally, `HttpServer`
   lifecycle and coroutine scope handling must remain safe across repeated
   `stop()` / `start()` cycles.
4. Fix reboot capability semantics according to Android documentation:
   `DevicePolicyManager.reboot()` is gated by device-owner status, not by
   `KeyguardManager.isDeviceLocked()` or `KeyguardManager.isKeyguardLocked()`.
5. Separate and correctly model `isDeviceLocked` vs `isKeyguardLocked`
   everywhere they appear. Do not populate one field from the other. Use the
   correct field in diagnostics, helper logic, HTTP responses, UI messaging,
   and tests according to Android API semantics.
6. Remove Knox-based reboot blocking logic from diagnostics, helper behavior,
   HTTP responses, UI messaging, and tests. If Knox detection remains, keep it
   informational only.
7. Remove `RebootResult.DeviceLocked` unless a real runtime path still returns
   it for a documented reason unrelated to keyguard state.
8. Add or update targeted tests for internal server restart behavior and reboot
   behavior. Delete tests that only validate the removed public restart route.
9. Update all relevant documentation for the changed restart/reboot behavior:
   API docs, operator-facing notes, test-support docs, and any comments or
   diagrams that still mention the removed public restart route or the old
   reboot blockers.

**Why**

These are correctness bugs, not style issues. They can invalidate later
refactoring work by making internal server lifecycle and reboot behavior
nondeterministic.

**Tests**

- Add or update targeted unit/instrumented tests for the changed internal
  server lifecycle and reboot behavior.
- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest`
- Ensure internal server restart tests cover at least one stop/start cycle.
- Ensure the removed public restart route no longer appears in UI, tests, or docs.
- Ensure reboot diagnostics and reboot execution tests treat device-owner status
  as the real reboot gate.
- Ensure tests cover the semantic difference between `isDeviceLocked` and
  `isKeyguardLocked` where the app exposes both concepts.
- Ensure tests no longer treat Knox presence as a reboot blocker.

**Documentation**

- Update all relevant API, operator, developer, and test-support
  documentation for internal restart handling, reboot preflight, and lock-state
  semantics.

**Exit criterion**

Internal server restart works repeatedly where the runtime still uses it, the
public restart route is gone, and reboot logic no longer misinterprets
device-lock/keyguard state or Knox as reboot blockers.

---

### Step 2 — Remove legacy settings migrations and migration-only code

**What to do**

1. Delete all legacy read-side migrations from `CameraService.loadSettings()`:
   old connection-limit key, old torch key, old camera-selection key, and old
   per-facing resolution keys.
2. Delete `ConnectionLimits.fromLegacyMaxConnections()`.
3. Remove migration-only tests from `ConnectionLimitsTest` and any other test
   assertions that exist solely to validate deleted migration behavior.
4. Run a second pass over the project and remove newly orphaned dead code above
   the migration layer: helper functions, constants, comments, docs, and any
   conditionals that no longer have a caller after migration removal.
5. Add or update targeted tests so the remaining settings-loading behavior is
   covered without preserving migration-only assertions.
6. Update all relevant documentation for the current settings contract and
   remove references to deleted legacy keys and migration paths.

**Why**

This removes a full compatibility layer before settings extraction, so the new
settings repository will model only the current runtime contract.

**Tests**

- Add or update targeted tests for the surviving settings-loading and
  connection-limit behavior after migration removal.
- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest`
- Keep non-migration connection-limit tests intact.

**Documentation**

- Update all relevant settings, migration, developer, and test-support
  documentation so it no longer describes removed legacy preference keys.

**Exit criterion**

No production code reads legacy preference keys anymore, and no helper, test,
or documentation remains that refers to removed migration behavior unless it is
historical release documentation.

---

### Step 3 — Remove direct dead code and minSdk-dead branches

**What to do**

1. Delete confirmed unused methods and fields across the project that are not
   already scheduled for wholesale feature removal in later cleanup steps,
   including the dead methods already identified in `CameraService`,
   `BitmapPool`, `RuntimeTelemetry`, `BuildInfo`, and `InMemoryLogBuffer`.
2. Delete dead `DeviceAdminReceiver` overrides that only log and call `super`.
3. Remove unused imports and reduce visibility where values are public without
   a caller.
4. Delete branches that are dead under `minSdk = 30`, such as fallback paths
   for `createDeviceProtectedStorageContext()` and pre-API-30 Wi-Fi debugging logic.
5. Add or update targeted tests only where deleting dead branches changes
   compile-time or runtime coverage assumptions.
6. Update all relevant documentation and comments so they no longer describe
   removed dead branches, dead APIs, or pre-API-30 fallback behavior.

**Why**

This step is mostly subtractive and should shrink the codebase before any
extraction or interface redesign starts.

**Tests**

- Add or update targeted tests when dead-code removal changes coverage
  expectations or removes obsolete assertions.
- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest`

**Documentation**

- Update all relevant developer docs, inline comments, and maintenance notes to
  match the post-cleanup code surface.

**Exit criterion**

All dead methods, dead branches, dead overrides, dead imports, and other direct
dead code identified so far are removed.

---

### Step 4 — Remove duplicate camera concepts and obsolete product tails

**What to do**

1. Delete the separate `cameraOrientation` concept across the project:
   remove its persistence, service API, HTTP endpoint, UI controls, web UI
   controls, SSE/status fields, tests, and frame-processing branches. Keep only
   one canonical `rotation` model.
2. Remove the `PerformanceMetrics` pressure model and adaptive-quality leftovers:
   delete pressure thresholds, `PressureLevel`, `PerformancePressure`,
   `isUnderPressure()`, `recordFrameSkipped()`, and related tests/docs that
   exist only to support an unused pressure/adaptation concept.
3. Reduce telemetry to the operational set that is actually needed:
   CPU, bandwidth, memory `used/total`, and battery state, and make sure memory
   becomes part of the ordinary API/UI metrics surface instead of staying only
   in debug output.
4. Delete `WiFiDebuggingManager` and all ADB-over-WiFi integration:
   remove manager startup/shutdown, ADB info display in the web UI, any HTTP/UI
   template variables or service methods that exist only for this feature, and
   all related documentation/tests.
5. Delete debug-only camera activation surfaces:
   remove `/activateCamera`, `/deactivateCamera`, `manualActivateCamera()`,
   `manualDeactivateCamera()`, the `MANUAL` consumer type, and related tests or
   docs. Keep camera activation driven only by real product demand sources.
6. Delete the entire battery-policy feature and its tails:
   remove battery-mode state machines, `streamingAllowed` gating,
   `/overrideBatteryLimit`, battery-policy-specific HTTP/UI messaging, blocked
   stream HTML fallbacks, and related tests/docs. Keep ordinary battery
   telemetry and battery overlay support only.
7. Add or update targeted tests for the surviving camera rotation, telemetry,
   and demand behavior after these feature removals.
8. Update all relevant documentation for camera controls, telemetry, Wi-Fi ADB,
   battery behavior, and debug endpoints to remove obsolete product features.

**Why**

This step removes obsolete product branches and duplicate runtime concepts
before extraction starts. It also prevents the new API and settings model from
preserving duplicate camera transforms or dead operational features.

**Tests**

- Add or update targeted tests for rotation-only behavior, reduced telemetry,
  and the removal of obsolete product features.
- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest`

**Documentation**

- Update all relevant API, UI/help, developer, and support documentation for
  the removal of orientation, battery policy, Wi-Fi ADB, pressure model, and
  manual camera activation.

**Exit criterion**

Rotation remains the only transform control exposed by the project, the
remaining telemetry surface is reduced to operational metrics, battery policy
is no longer part of the product, ADB-over-WiFi maintenance is gone, and
debug-only manual camera activation no longer exists.

---

### Step 5 — Deduplicate low-risk helpers

**What to do**

1. Extract shared helpers for exact or near-exact duplicates:
   `getColorFormatName()`, device-owner checks, and permission checks.
2. Centralize shared constants and storage helpers such as `PREFS_NAME` and the
   device-protected prefs access pattern.
3. Collapse copy-pasted HTTP helper logic for overlay booleans, FPS parsing, and
   repeated flashlight availability guards.
4. Remove duplicate JSON escaping code by using one shared helper where manual
   JSON is still temporarily kept.
5. Collapse user-restriction clearing to one canonical automatic path in
   `DeviceAdminReceiver`: keep a single shared restriction list/helper for the
   automatic device-owner flow, and remove the duplicated manual UI-triggered
   path from `MainActivity`.
6. Defer larger UI-specific duplication cleanup that depends on presenter
   extraction; do not over-refactor `MainActivity` yet.
7. Add or update targeted tests for any extracted helper with meaningful logic
   and for the surviving automatic user-restriction flow.
8. Update all relevant documentation and comments to point at the canonical
   helpers and the canonical automatic restriction-clearing path.

**Why**

This step removes noise without changing architecture yet. It also prevents the
same helper logic from being extracted multiple times into different future
modules.

**Tests**

- Add or update targeted tests for extracted helpers and deduplicated
  restriction-clearing behavior.
- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest`
- Add focused JVM tests for any new pure helper that contains non-trivial logic.

**Documentation**

- Update all relevant developer docs, helper references, and maintenance notes
  so duplicated helper paths are no longer documented.

**Exit criterion**

Exact-copy helpers are unified, shared constants are centralized, and route
handlers stop repeating trivial parsing and guard code. User-restriction
clearing no longer has a duplicated manual UI implementation.

---

### Step 6 — Introduce unified logging

**What to do**

1. Introduce a shared logging subsystem as a logical module, not a Gradle split:
   a small `AppLogger`-style interface, one simple logging config, and concrete
   sinks/adapters for Android Logcat and the in-memory log buffer used by the
   logs endpoint.
2. Keep the logging interface intentionally minimal and cross-cutting only:
   components should be able to log through `d/i/w/e`-style methods with an
   optional throwable, but the logger must not know about HTTP, UI, telemetry,
   RTSP, or any business-domain concepts.
3. Add sink-specific logging configuration inside the logging subsystem itself,
   including a persistent minimum level for the in-memory sink that powers
   `/logs`. If UI controls are added, they should update that config through a
   narrow logging-settings port instead of teaching the HTTP layer or general
   UI prefs about log filtering.
4. Migrate components away from direct `android.util.Log` calls and direct
   `InMemoryLogBuffer` writes so the in-memory log view becomes a true shared
   sink instead of a hand-maintained partial mirror.
5. Add or update targeted tests for logger routing, sink-level filtering, and
   persistent in-memory log capture configuration.
6. Update all relevant documentation for logging usage, `/logs`, and logging
   settings so other steps can depend on the new shared logging contract.

**Why**

Unifying logging early gives later extracted modules one stable cross-cutting
dependency instead of continuing to spread direct platform logging calls
through the codebase.

**Tests**

- Add or update targeted tests for the shared logger interface, sink behavior,
  and log-capture settings.
- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest`
- Add focused JVM tests for logging configuration and in-memory buffer behavior.

**Documentation**

- Update all relevant developer, operator, API, and test-support documentation
  for the shared logging subsystem and log-capture settings.

**Exit criterion**

Components log through one shared logging entry point, and the in-memory log
sink is no longer fed only by a few hand-picked call sites.

---

### Step 7 — Close interface leaks and internal HTTP inconsistencies

**What to do**

1. Remove `HttpServer` downcasts to `CameraService` by making the required
   operations available through the public contract used by the server.
2. Remove static or concrete-type shortcuts that bypass the intended service
   boundary, including direct transport-visible shortcuts such as
   `H264PreviewEncoder.getActiveEncoderInstances()`.
3. Normalize route-parameter access style inside `HttpServer`.
4. Fix incomplete or hardcoded introspection data such as the partial endpoint
   list exposed by `/status`.
5. Fix correctness issues in manual JSON builders that can produce invalid JSON
   when values contain quotes or control characters, and stop introducing new
   ad-hoc JSON builders in the legacy server code.
6. Keep the full move to DTO-based serialization out of this cleanup step; it
   should happen later together with route extraction so the team does not
   churn the legacy server twice.
7. Remove the most obvious existing SSE-specific coupling points from the
   current service surface where they are clearly transport-driven rather than
   domain-driven, without attempting the full port-segregation work yet.
8. Add or update targeted tests for interface-boundary behavior, JSON
   correctness fixes, and the surviving HTTP contract.
9. Update all relevant documentation for service ports, HTTP internals, and
   temporary manual-JSON constraints until the later DTO migration step.

**Why**

The project cannot be meaningfully modularized while one of the main consumers
still cheats around the service boundary.

**Tests**

- Add or update targeted tests for boundary-safe HTTP behavior and for any
  fixed manual-JSON correctness cases.
- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest`
- Ensure HTTP routes still work with test doubles instead of relying on a
  concrete `CameraService` implementation.

**Documentation**

- Update all relevant developer, API, and test-support documentation for the
  cleaned service boundary and legacy HTTP constraints that remain temporarily.

**Exit criterion**

`HttpServer` no longer depends on concrete `CameraService` behavior outside its
declared contract, known internal HTTP inconsistencies are removed, manual JSON
correctness bugs are fixed without expanding the old approach further, and the
current SSE implementation no longer dictates core service contracts.

---

### Step 8 — Implement module-aligned resource groups

**What to do**

1. Replace the flat legacy endpoint list with a resource-oriented surface that
   matches future module boundaries, centered around:
   `/camera`, `/mjpeg`, `/rtsp`, `/connections`, `/metrics`, `/logs`, and `/system`.
2. Map current endpoints either to removal or to the new resource/subresource
   structure. Do not keep temporary compatibility shims.
3. Place cross-cutting debug/inspection operations explicitly, including
   connection inspection, forced connection close, and reboot capability
   preflight. Do not preserve a separate `/diagnostics/*` namespace just for
   historical debugging routes.
4. Keep `GET /camera` as a compact state/config resource; move camera discovery,
   catalog, supported formats, and other capabilities into dedicated read-only
   subresources under `/camera/*`.
5. Express flashlight changes only through `PATCH /camera` in the target API.
   Do not preserve separate toggle/on/off flashlight endpoints.
6. Keep `GET /camera/options` contextual to the current selection (for example:
   supported resolutions and other options for the selected camera) instead of
   turning it into a full all-cameras bootstrap document. Expose the complete
   camera catalog separately.
7. Do not overload `GET /camera/options` with `cameraId`-style selectors for
   non-selected cameras. Read options/capabilities for other cameras through
   dedicated catalog subresources instead.
8. Keep `GET /camera/catalog` compact:
   return only lightweight camera-list data there, and move detailed
   capabilities/resolution options for a specific camera into dedicated
   per-camera catalog subresources.
9. Use `GET /camera/catalog/{cameraId}` as the per-camera catalog-detail
   resource for non-selected cameras instead of introducing an extra
   `/options` suffix there by default.
10. Keep `GET /camera/snapshot` as an active operation-oriented read:
   it may temporarily activate the camera and wait briefly for a frame instead
   of acting only as a passive cached-frame endpoint.
11. Do not reintroduce `orientation` on the new API surface; camera transform is
   represented only through `rotation`.
12. Keep `/metrics` focused on operational telemetry and include memory
   `used/total` and battery state there; do not reintroduce
   pressure/adaptation abstractions via the API.
13. Remove `/diagnostics/camera` instead of re-homing it; move reboot
   capability/preflight reads to `GET /system/reboot`, paired with
   `POST /system/reboot` for the action.
14. Move update reads/writes to `GET /system/update` and `POST /system/update`,
   with the `GET` side performing the live availability check and also exposing
   the current local update-operation state instead of returning only raw
   remote-check data.
15. Keep `GET /system` intentionally compact: identity/build/admin summary only,
   while battery/CPU/bandwidth/memory stay in `/metrics` and reboot-specific
   preflight stays in `GET /system/reboot`.
16. Keep log dump and log configuration separate:
    `GET /logs` remains a plain-text dump, while log-capture settings should be
    exposed through a dedicated logs-settings resource rather than through
    `/system` or by overloading `/logs` itself.
17. Explicitly retire overlapping legacy monitoring and control routes rather
    than re-homing them one by one:
    `/status`, `/stats`, `/cameraState`, `/flashOn`, `/flashOff`,
    `/toggleFlashlight`, and other legacy convenience endpoints should be
    removed in favor of the new resource-oriented surface.
18. Do not preserve server-restart as a public operation. Delete the legacy
    restart route and do not replace it with `/system/restart`.
19. Add or update targeted endpoint tests for the redesigned resource-oriented
    API surface and remove obsolete endpoint coverage in the same step.
20. Update all relevant API, web UI, test-support, and developer documentation
    to match the new resource-oriented HTTP surface.

**Why**

This is the intentional public API redesign step. It should be aligned with the
future modular architecture instead of merely renaming old routes.

**Tests**

- Add or update targeted endpoint tests for every contract intentionally added,
  merged, renamed, or removed in this step.
- `./gradlew connectedDebugAndroidTest`
- Update only the tests that cover endpoints intentionally removed or merged.

**Documentation**

- Update all relevant API contracts, endpoint reference material, web UI notes,
  and test-support documentation for the redesigned HTTP surface.

**Exit criterion**

The HTTP surface is organized by domain rather than by historical accidents, and
tests document the chosen contract.

---

### Step 9 — Implement verbs, live updates, and legacy-route retirement

**What to do**

1. Express writes on the new API surface through `PATCH`, `POST`, and
   resource-specific actions instead of state-changing `GET` routes.
2. Keep live UI updates on `GET /events`, but define them as a secondary
   UI-only transport over the same resource model used by ordinary endpoints.
3. Implement live updates through a narrow resource-invalidation mechanism with
   coalescing, so the SSE adapter reacts to resource changes without requiring
   SSE-specific domain APIs or JSON delta builders.
4. Send full resource snapshots on SSE events instead of field-level deltas, so
   `/events` reuses the same read-model contracts as ordinary `GET` endpoints.
5. Retire legacy routes by immediate removal in the API-redesign step itself:
   update the web UI, `DeviceTestEnvironment`, and instrumented tests in the
   same step instead of carrying temporary aliases or a staged compatibility
   layer into the new HTTP surface.
6. Implement these contract changes in one isolated step.
7. Add or update targeted tests for write verbs, SSE snapshot events, and
   immediate legacy-route retirement.
8. Update all relevant API, web UI, and test-support documentation for verb
   changes, `/events`, and removed legacy routes.

**Why**

These are externally visible contract changes. They affect tests, clients, and
the shape of the future HTTP module boundaries, so they should be implemented
deliberately in one contained step.

**Tests**

- Add or update targeted tests for the new write verbs, `/events` contract, and
  the removal of legacy routes.
- `./gradlew connectedDebugAndroidTest`
- Update endpoint tests only for the contract that was deliberately changed.

**Documentation**

- Update all relevant API contracts, client expectations, web UI notes, and
  test-support docs for verbs, SSE snapshots, and route retirement.

**Exit criterion**

Writes, live-update behavior, full-snapshot SSE contracts, invalidation
semantics, and legacy-route retirement all have an explicit contract, and that
contract is fully documented and test-covered.

## Phase 2 — Architectural extraction

### Step 10 — Extract settings repositories

**What to do**

1. Create `RuntimeSettingsRepository` for persisted service configuration only:
   selected camera, remembered resolution per camera, rotation, overlay flags,
   target FPS, RTSP bitrate/mode, connection limits, and device name.
2. Create `UiPrefsRepository` for persisted `MainActivity` and `BootReceiver`
   prefs only, such as UI-only preferences and autostart.
3. Move settings reads/writes out of `CameraService`, `MainActivity`, and
   `BootReceiver`.
4. Expose typed properties and hide raw string keys.
5. Keep runtime/derived state out of these repositories:
   no camera lifecycle state, connection snapshots, telemetry, stream/client
   counts, or SSE/live-update state.
6. Do not fold logging capture-level settings into `UiPrefsRepository` just
   because the UI edits them. Logging sink configuration belongs to the logging
   subsystem and should be accessed through a narrow logging-settings port.
7. Add or update targeted tests for repository defaults, round-trip
   persistence, and the absence of runtime-state leakage.
8. Update all relevant documentation for persisted configuration ownership,
   settings keys, and repository boundaries.

**Why**

Settings access is cross-cutting and currently blocks almost every future
extraction. Keeping the repositories strictly persistent prevents them from
turning into a second application state container.

**Tests**

- Add or update targeted tests for repository defaults, round-trip persistence,
  and repository-boundary rules.
- Full test suite
- Add JVM tests for repository defaults and round-trip persistence

**Documentation**

- Update all relevant developer, architecture, and maintenance documentation
  for the new settings repositories and their ownership boundaries.

**Exit criterion**

Production code no longer uses raw shared-preference keys outside the
repositories, and the repositories do not own runtime/derived state.

---

### Step 11 — Extract camera catalog and selection policy

**What to do**

1. Create `CameraCatalog` as a read/discovery component.
2. Move camera discovery, grouping, selector creation, and
   supported-resolution enumeration out of `CameraService`.
3. Keep selected-camera ownership and persistence above the catalog:
   the orchestrator/settings layer owns the current selected camera ID, while
   `CameraCatalog` provides discovery data and selector/capability helpers.
4. Align the HTTP read-model with that boundary:
   the full catalog should be exposed through a dedicated read-only catalog
   resource, while `GET /camera/options` should stay contextual to the current
   selected camera.
5. Keep catalog/resource boundaries explicit:
   options/capabilities for non-selected cameras should be exposed through
   dedicated catalog subresources rather than by parameterizing the contextual
   `/camera/options` endpoint.
6. Keep the top-level catalog lightweight:
   `GET /camera/catalog` should provide a compact list view, while detailed
   capabilities/resolution options for one camera should live in dedicated
   per-camera catalog resources.
7. Use one clear per-camera detail shape by default:
   `GET /camera/catalog/{cameraId}` should be the per-camera catalog-detail
   resource unless later requirements justify further sub-splitting.
8. Keep remembered resolution as part of the settings/orchestration layer above
   the catalog: `CameraCatalog` may expose supported resolutions, but it should
   not own persisted remembered resolution state for each camera.
9. Keep CameraX bind/rebind behavior in `CameraService` for now.
10. Add or update targeted tests for camera catalog grouping, selection helpers,
    and contextual options/catalog boundaries.
11. Update all relevant documentation for camera catalog resources, selection
    policy boundaries, and remembered-resolution ownership.

**Why**

Camera discovery logic is a coherent, mostly pure subsystem and should not live
inside camera lifecycle orchestration. Keeping the catalog read-only also
prevents selection persistence from leaking back into discovery code.

**Tests**

- Add or update targeted tests for camera discovery, grouping, capability
  exposure, and catalog/options boundaries.
- Full test suite
- Add JVM or Robolectric tests for grouping, selection, and resolution filtering

**Documentation**

- Update all relevant API, architecture, and developer documentation for the
  extracted camera catalog and its read-only contract.

**Exit criterion**

`CameraService` no longer owns camera catalog construction and capability
enumeration, and `CameraCatalog` does not own selected-camera state.

---

### Step 12 — Extract consumer demand tracking

**What to do**

1. Create `CameraDemandTracker`.
2. Move consumer registration, counting, and activation/deactivation decisions
   out of `CameraService`.
3. Keep the tracker intentionally narrow: it should model only demand state and
   the resulting "camera should be active / camera may be stopped" decision.
   It must not know about HTTP, RTSP, UI, SSE, telemetry, CameraX binding, or
   frame-processing details.
4. Model only real product demand sources in the tracker (`preview`, `snapshot`,
   `mjpeg`, `rtsp`, and any still-valid system-driven demand). Do not preserve
   a synthetic manual/debug consumer category.
5. Treat snapshot demand as real transient demand:
   `GET /camera/snapshot` may wake the camera through the tracker and release
   that demand when the request finishes instead of bypassing demand tracking
   with ad-hoc camera activation logic.
6. Do not preserve the current `activeSnapshots` counter as a separate
   first-class runtime concept. If concurrent snapshot coordination still needs
   a counter internally, keep it inside the snapshot adapter path rather than
   exposing it through telemetry, `/connections`, or the domain model.
7. Make `CameraService` react to tracker decisions instead of managing the state
   machine inline.
8. Add or update targeted tests for consumer transitions, snapshot demand, and
   the absence of manual/debug demand categories.
9. Update all relevant documentation for camera demand sources, snapshot
   behavior, and tracker responsibilities.

**Why**

Demand tracking is an isolated state machine and should be testable without a device.

**Tests**

- Add or update targeted tests for mixed demand sources, transient snapshot
  demand, and activation/deactivation transitions.
- Full test suite
- Add JVM tests for 0->1 and 1->0 transitions and mixed-consumer scenarios

**Documentation**

- Update all relevant architecture, API, and developer documentation for the
  extracted demand tracker and snapshot-demand semantics.

**Exit criterion**

Consumer lifecycle decisions are no longer embedded in `CameraService`, and the
tracker does not own transport-specific or binding-specific behavior.

---

### Step 13 — Extract frame pipeline and frame store

**What to do**

1. Create `FramePipeline`, `FrameStore`, and `PreviewFramePublisher`.
2. Move image conversion, rotation, annotation, JPEG compression, and last-frame
   storage out of `CameraService`.
3. Keep `FrameStore` focused on server/snapshot concerns only: latest JPEG
   payload plus the metadata needed by `/camera/snapshot`, `/mjpeg/stream`, and
   watchdog/telemetry consumers. Do not keep `Bitmap` as part of the shared
   storage contract.
4. Route `MainActivity` preview delivery through a separate preview publisher
   path so UI bitmap delivery is no longer coupled to the shared frame store.
5. Keep snapshot semantics explicit:
   `/camera/snapshot` may wait briefly for a fresh frame after activating
   camera demand, but frame retrieval should still go through `FrameStore`
   rather than through bespoke per-route frame plumbing.
6. Keep only the scheduling hook in the CameraX analyzer callback.
7. Add or update targeted tests for frame transforms, JPEG storage, preview
   publishing, and snapshot-read behavior.
8. Update all relevant documentation for frame flow, snapshot semantics, and
   preview-delivery boundaries.

**Why**

Frame processing is a large, self-contained block and a natural extraction
target. Separating HTTP/snapshot frame storage from UI preview delivery also
removes `Bitmap`-level Android/UI coupling from the shared frame storage layer.

**Tests**

- Add or update targeted tests for frame transforms, overlay behavior,
  `FrameStore`, and `PreviewFramePublisher`.
- Full test suite
- Add JVM or Robolectric tests for rotation, overlays, and frame storage behavior

**Documentation**

- Update all relevant architecture, API, and developer documentation for the
  extracted frame pipeline, shared frame store, and preview publisher.

**Exit criterion**

`CameraService` no longer implements bitmap transformation and JPEG production
directly, and the shared frame store is no longer responsible for UI preview
bitmap delivery.

---

### Step 14 — Extract CameraX binding and torch control

**What to do**

1. Create `CameraBinder`.
2. Create `TorchController`.
3. Keep `CameraBinder` narrow: it should own generic CameraX bind/unbind/rebind
   mechanics, lifecycle-safe use-case attachment, and integration points needed
   for torch reconciliation.
4. Extract RTSP-specific encoder/surface/lease-driven binding logic into a
   separate component such as `RtspCapturePipeline` or `RtspUseCaseFactory`
   instead of folding it into `CameraBinder`.
5. Move bind/unbind, rebind, camera reset wiring, RTSP use-case assembly, and
   torch desired/effective state logic out of `CameraService`.
6. Add or update targeted tests for generic binding behavior, torch-state
   reconciliation, and RTSP-specific binding boundaries.
7. Update all relevant documentation for CameraX binding ownership, torch
   control, and the extracted RTSP capture path boundary.

**Why**

Camera lifecycle and torch lifecycle are Android-specific implementation details,
not service-orchestration responsibilities. Separating generic binding from the
RTSP capture path also prevents `CameraBinder` from becoming another monolith.

**Tests**

- Add or update targeted tests for binder behavior, camera reset wiring, and
  torch desired/effective state logic.
- Full test suite
- Add JVM tests for torch state logic where possible

**Documentation**

- Update all relevant architecture, Android-integration, and developer
  documentation for `CameraBinder`, `TorchController`, and RTSP capture wiring.

**Exit criterion**

`CameraService` delegates camera binding and torch control instead of owning the
details, and RTSP-specific pipeline assembly no longer lives inside the generic
binder.

---

### Step 15 — Segregate `CameraServiceInterface` into narrow ports

**What to do**

1. Replace the wide interface with focused ports such as
   `CameraControlPort`, `StreamingPort`, `TelemetryPort`, `ConnectionsPort`,
   and `AdminPort`.
2. Update `HttpServer`, `RTSPServer`, and UI-facing code to depend only on the
   ports they actually need.
3. Remove transport-specific SSE methods and delta-oriented state access from
   the public service contract. The HTTP live-update adapter must read ordinary
   resource snapshots through read ports instead of depending on SSE-specific
   service methods.
4. Verify that debug-only camera activation methods do not survive in any new
   port after the earlier cleanup removal; do not re-home them into extracted
   interfaces.
5. Keep the cross-transport connections contract intentionally tiny: aggregated
   `ConnectionSnapshot` reads and `closeConnection(id)` routing only. Do not
   move transport-specific session state, lease tracking, or registry mutation
   into the shared port.
6. Do not route general-purpose logging through service ports. Logging should
   stay available as a separate shared subsystem dependency rather than leaking
   through `CameraService`-shaped interfaces.
7. Delete the monolithic interface once all callers are migrated.
8. Add or update targeted tests for the new narrow ports and fake-port-based
   callers.
9. Update all relevant architecture, developer, and test-support documentation
   for the segregated service ports and removed monolithic interface.

**Why**

Interface segregation is the main coupling reduction step for module boundaries.

**Tests**

- Add or update targeted tests that exercise callers against the new narrow
  ports instead of the monolithic interface.
- Full test suite
- Add at least one route-level JVM test using fake ports

**Documentation**

- Update all relevant architecture, API, and developer documentation for the
  new ports and the removal of the catch-all service contract.

**Exit criterion**

No major caller depends on a catch-all service interface anymore.

---

### Step 16 — Extract watchdog and telemetry coordination

**What to do**

1. Create `CameraWatchdog`.
2. Create `ServerHealthMonitor`.
3. Create `TelemetryCoordinator`.
4. Split runtime recovery responsibilities intentionally:
   `CameraWatchdog` handles only camera-pipeline health and recovery decisions,
   while `ServerHealthMonitor` handles embedded HTTP-server liveness and
   network-triggered server recovery. Do not keep both concerns in one
   watchdog-style loop.
5. Move watchdog/server-health loops and reduced periodic telemetry sampling out of
   `CameraService`.
6. Keep the telemetry model intentionally small: CPU, bandwidth, and memory
   `used/total`, battery state, and the connection/activity counters needed by
   `/metrics` and the UI. Do not resurrect pressure/adaptive-quality
   abstractions in the new coordinator. Do not add a dedicated snapshot
   concurrency metric just to preserve the old `activeSnapshots` counter.
7. Add or update targeted tests for camera recovery decisions, server-health
   recovery, and the reduced telemetry contract.
8. Update all relevant documentation for watchdog responsibilities, telemetry
   ownership, and `/metrics` semantics.

**Why**

These are independent operational concerns and should not be interleaved with
camera binding, server lifecycle, and frame handling. Splitting camera recovery
from server/network liveness also prevents the future watchdog from becoming
another hidden runtime god-object. The telemetry extraction should move only
the metrics the product actually uses, not a larger unused performance framework.

**Tests**

- Add or update targeted tests for watchdog decisions, server-health recovery,
  telemetry windows, and the reduced metrics set.
- Full test suite
- Add JVM tests for camera-watchdog recovery decisions, server-liveness
  recovery decisions, telemetry windows, and memory `used/total` reporting

**Documentation**

- Update all relevant architecture, API, operator, and developer documentation
  for watchdog responsibilities and telemetry semantics.

**Exit criterion**

Camera health recovery, server/network liveness recovery, and reduced telemetry
sampling are no longer owned by `CameraService`, and camera/server recovery no
longer share one monolithic watchdog loop.

---

### Step 17 — Split HTTP server into route groups and registries

**What to do**

1. Keep `HttpServer` as the startup shell only.
2. Extract focused route installers by feature area.
3. Extract `MjpegStreamRegistry`.
4. Extract a thin cross-transport `ConnectionsFacade`/`ConnectionDirectory`
   above transport-specific connection sources. It should aggregate snapshots
   for `/connections` and route `closeConnection(id)` calls without owning the
   underlying transport registries.
5. Introduce a narrow internal resource-invalidation layer:
   typed resource invalidation events plus an in-memory bus/sink interface used
   by orchestrators to announce that a resource snapshot is stale.
6. Extract read-model assemblers for the ordinary HTTP resources (`/camera`,
   `/camera/options`, `/camera/catalog`, per-camera catalog resources,
   `/mjpeg`, `/rtsp`, `/connections`, `/metrics`, `/system`,
   `/system/reboot`, `/system/update`) so
   ordinary routes and live updates read from the same snapshot builders.
7. Extract the live-update transport adapter (for `GET /events`) into its own
   HTTP module component. It should subscribe to the invalidation layer,
   re-read current resource snapshots through read ports/assemblers, coalesce
   bursts of changes, and broadcast full resource snapshots without leaking SSE
   concerns back into domain components.
8. As routes move out, replace ad-hoc JSON string building with structured DTOs
   and one serialization approach (`kotlinx.serialization`) instead of carrying
   manual JSON builders into the new route modules.
9. Re-home the `/logs` endpoint onto the shared logging subsystem instead of
   reading logs through ad-hoc service plumbing or a partially maintained buffer
   owned by unrelated components.
10. Keep `/logs` itself simple. The endpoint should read what the logging
    subsystem has already captured according to its current sink-level policy,
    rather than implementing ad-hoc read-time filtering rules in HTTP.
11. Keep the external `/logs` response as `text/plain` even if the logging
    subsystem uses typed log entries internally. Do not grow `/logs` into a
    structured JSON log API unless a future product requirement justifies it.
12. Expose logging capture settings through a separate logs-settings resource
    (for example `GET /logs/settings` and `PATCH /logs/settings`) backed by the
    logging subsystem's own persistent sink configuration, not by `UiPrefsRepository`
    and not by overloading `GET /logs`.
13. Add or update targeted tests for route groups, DTO serialization, logs
    resources, invalidation/coalescing, and the live-update adapter.
14. Update all relevant API, architecture, developer, and test-support
    documentation for the new route groups, `/events`, `/logs`, and
    `/logs/settings`.

**Why**

This is the main decomposition step for the HTTP transport layer. It also
localizes live-update behavior so SSE remains responsive without becoming a
cross-cutting architectural concern. Deferring the full serialization migration
to this step keeps cleanup focused while ensuring the new HTTP surface is built
on DTOs instead of string templates.

**Tests**

- Add or update targeted tests for extracted route groups, DTO serialization,
  connections aggregation, `/logs`, `/logs/settings`, and live updates.
- Full test suite
- Add JVM tests for MJPEG registry, invalidation/coalescing logic, the live
  update adapter, and any extracted pure route helper

**Documentation**

- Update all relevant API contracts, architecture notes, operator docs, and
  test-support documentation for the split HTTP transport layer.

**Exit criterion**

`HttpServer.kt` becomes a thin composition root and route wiring file.
Live updates are implemented by a localized adapter over shared read-models,
not by SSE-specific logic spread through the service layer.

---

### Step 18 — Modularize RTSP server internals

**What to do**

1. Keep `RTSPServer` as the socket acceptor and top-level coordinator.
2. Extract `RtspProtocolHandler`, `RtspSessionRegistry`, `RtspCameraLeaseTracker`,
   `RtspAdmissionPolicy`, `RtpPacketizer`, and `CodecConfigStore`.
3. Keep these extracted RTSP collaborators narrow:
   `RtspSessionRegistry` for bookkeeping only,
   `RtspCameraLeaseTracker` for lease symmetry only,
   and `RtspAdmissionPolicy` for playing-session limits/eviction only.
4. Expose RTSP connection snapshots/close operations to the rest of the system
   only through the local RTSP connection source used by the thin
   cross-transport connections facade. Do not let other modules reach into RTSP
   session internals directly.
5. Keep transport metrics and frame broadcast wiring at the top level only.
6. Add or update targeted tests for protocol parsing, session bookkeeping,
   admission policy, lease tracking, and exposed RTSP connection snapshots.
7. Update all relevant architecture, developer, and API documentation for the
   extracted RTSP collaborators and their boundaries.

**Why**

Protocol parsing, packetization, session bookkeeping, lease symmetry, admission
policy, and codec config should not live in one monolithic transport class.
Keeping those concerns separate also prevents the extracted registry from
becoming another hidden god-object.

**Tests**

- Add or update targeted tests for extracted RTSP collaborators and their
  external-facing connection contract.
- Full test suite
- Add JVM tests for RTP packetization, codec config storage, and session limits

**Documentation**

- Update all relevant architecture, transport, and developer documentation for
  the modularized RTSP server internals.

**Exit criterion**

`RTSPServer.kt` becomes a thin acceptor/wiring class instead of a protocol god-class.

---

### Step 19 — Extract device operations coordinators

**What to do**

1. Create `UpdateCoordinator`.
2. Create `DeviceOpsCoordinator`.
3. Keep the split intentional:
   `UpdateCoordinator` owns remote check/download/install orchestration only,
   while `DeviceOpsCoordinator` owns reboot, camera reset, and device-owner /
   reboot-preflight operations, but not a public server-restart action.
4. Make `UpdateCoordinator` the single source of truth for update-operation
   state exposed by `GET /system/update`:
   at minimum it should own an explicit phase model such as
   `idle/checking/downloading/installing/waiting_user_confirmation/completed/failed`,
   so HTTP and UI stop inventing separate ad-hoc update status handling.
5. Make update execution single-flight:
   if `POST /system/update` is called while an update operation is already in
   progress, `UpdateCoordinator` should return the current operation state/ack
   instead of launching a duplicate check/download/install flow.
6. Move shared update, reboot, diagnostics, and other remaining device-operation
   orchestration out of `HttpServer` and `MainActivity`.
7. Add or update targeted tests for coordinator state transitions, reboot
   preflight, and single-flight update behavior.
8. Update all relevant API, operator, architecture, and developer
   documentation for update/reboot/reset coordination.

**Why**

Device operations are duplicated across UI and HTTP entry points and need one
consistent coordination layer, but update flow has materially different
dependencies and lifecycle from reboot/reset/preflight logic. Keeping it in a
separate coordinator produces smaller and clearer interfaces than one generic
system-ops god-object.

**Tests**

- Add or update targeted tests for update phases, reboot-preflight behavior,
  error handling, and single-flight execution.
- Full test suite
- Add JVM tests for coordinator state transitions and error handling

**Documentation**

- Update all relevant API, operator, architecture, and developer documentation
  for `UpdateCoordinator`, `DeviceOpsCoordinator`, `/system/update`, and
  `/system/reboot`.

**Exit criterion**

UI and HTTP layers trigger device operations through coordinators instead of
duplicating orchestration.

---

### Step 20 — Thin `MainActivity` into a view layer

**What to do**

1. Create `MainViewModel` for screen state, UI state derivation, and
   preference-backed view-state loading.
2. Extract Android-specific collaborators such as `ActivityServiceBinder` and
   `PreviewController` for service binding lifecycle, preview subscription, and
   other binder/callback concerns that should not live inside the ViewModel.
3. Move metrics refresh scheduling, update/reboot/camera-reset UI
   orchestration, and other non-rendering coordination out of `MainActivity`.
4. Collapse repetitive UI boilerplate that remains after that split, including
   spinner setup/load patterns and duplicated zero-state branches such as the
   repeated `updateConnectionsUI()` reset logic.
5. Keep the Activity as listener wiring and view rendering only.
6. Add or update targeted tests for view-model state derivation, scheduling,
   and extracted binder/preview collaborators.
7. Update all relevant UI, architecture, developer, and test-support
   documentation for the thinner Activity boundary and new collaborators.

**Why**

UI logic is currently tangled with service coordination, Android binding
details, and persistence concerns. The hybrid split keeps screen state in a
testable ViewModel without turning it into another Android/service god-object.

**Tests**

- Add or update targeted tests for `MainViewModel`, extracted collaborators,
  and the remaining Activity rendering contract.
- Full test suite
- Add JVM tests for view-model state derivation and scheduling
- Add focused tests for extracted binder/preview collaborators where practical

**Documentation**

- Update all relevant UI, architecture, developer, and test-support
  documentation for the refactored Activity/view-model split.

**Exit criterion**

`MainActivity` becomes a thin Android view adapter and no longer acts as a
second application controller.

## Expected result

After the full plan:

- The cleanup phase has already removed dead code, legacy code, and obvious duplication.
- `CameraService`, `HttpServer`, `RTSPServer`, and `MainActivity` each own one
  primary responsibility instead of many.
- Dependencies between modules are expressed through small ports and focused collaborators.
- Public API changes, where chosen, were handled deliberately in isolated steps
  instead of leaking into architectural refactoring.
- The total codebase should be roughly the same size or smaller, but the main
  improvement is reduced coupling and easier reasoning.

## Out of scope

- Gradle multi-module split
- Dependency-injection framework adoption
- Compose migration
- New product features unrelated to coupling reduction
