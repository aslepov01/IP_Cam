package com.ipcam.testsupport

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.ipcam.CameraService
import com.ipcam.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLEncoder

class DeviceTestEnvironment {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext: Context = instrumentation.targetContext
    private val trackedCloseables = mutableListOf<Closeable>()
    private var mainActivityScenario: ActivityScenario<MainActivity>? = null
    private val localConnectionAddresses: Set<String> by lazy { discoverLocalConnectionAddresses() }

    fun startFreshApp() {
        shutdownAndReset()
        seedUiPreferences()
        launchMainActivity()
        startHttpServer()
        ensureRuntimePermissionsGranted()
        waitForHttpServer()
        waitForCameraCatalogReady()
    }

    fun shutdownAndReset() {
        closeTrackedResources()
        closeAllConnectionsIfPossible()
        disableRtspIfPossible()
        closeMainActivity()
        runCatching { appContext.stopService(cameraServiceIntent()) }

        waitForPortClosedOrServiceStopped(8080)
        waitForPortClosedOrServiceStopped(8554)

        clearPreferences()
        deleteExternalFiles()
    }

    fun httpGet(
        path: String,
        host: String = LOOPBACK_HOST,
        expectedCode: Int? = 200,
        readTimeoutMs: Int = 10_000
    ): HttpResponse {
        openRawHttpGet(
            host = host,
            port = 8080,
            path = path,
            readTimeoutMs = readTimeoutMs,
            keepAlive = false
        ).use { connection ->
            val response = HttpResponse(
                statusCode = connection.statusCode,
                headers = connection.headers,
                body = readHttpBodyFully(connection)
            )
            expectedCode?.let {
                assertEquals("Unexpected HTTP status for $path. Body: ${response.bodyText()}", it, connection.statusCode)
            }
            return response
        }
    }

    fun jsonGet(
        path: String,
        host: String = LOOPBACK_HOST,
        expectedCode: Int? = 200
    ): JSONObject = httpGet(path, host, expectedCode).jsonObject()

    fun openMjpegStream(host: String = LOOPBACK_HOST): MjpegStreamClient =
        MjpegStreamClient(host, 8080).also { trackedCloseables += it }

    fun openSse(host: String = LOOPBACK_HOST): SseClient =
        SseClient(host, 8080).also { trackedCloseables += it }

    fun openRtspTcp(host: String = LOOPBACK_HOST): RtspTcpClient =
        RtspTcpClient(host).also { trackedCloseables += it }

    fun setConnectionLimits(
        mjpegStreams: Int? = null,
        sseClients: Int? = null,
        rtspSessions: Int? = null
    ): JSONObject {
        val query = buildList {
            mjpegStreams?.let { add("mjpegStreams=$it") }
            sseClients?.let { add("sseClients=$it") }
            rtspSessions?.let { add("rtspSessions=$it") }
        }.joinToString("&")

        val suffix = if (query.isNotEmpty()) "?$query" else ""
        return jsonGet("/setConnectionLimits$suffix")
    }

    fun waitForCameraState(
        vararg expectedStates: String,
        timeoutMs: Long = 20_000L
    ): JSONObject {
        val expected = expectedStates.toSet()
        return waitUntil(timeoutMs, "camera state in $expected") {
            val state = jsonGet("/cameraState")
            if (expected.contains(state.getString("cameraState"))) {
                state
            } else {
                null
            }
        }
    }

    fun waitForNoLongLivedConnections(timeoutMs: Long = 15_000L) {
        waitUntil(timeoutMs, "all long-lived connections to close") {
            val connections = connectionSnapshots()
            if (connections.length() == 0) Unit else null
        }
    }

    fun connectionSnapshots(): JSONArray = filterToLocalConnections(allConnectionSnapshots())

    fun allConnectionSnapshots(): JSONArray = jsonGet("/connections").getJSONArray("connections")

    fun waitForConnectionAbsent(connectionId: String, timeoutMs: Long = 15_000L) {
        waitUntil(timeoutMs, "connection $connectionId to disappear") {
            val connections = connectionSnapshots()
            val stillPresent = (0 until connections.length()).any {
                connections.getJSONObject(it).getString("id") == connectionId
            }
            if (!stillPresent) Unit else null
        }
    }

    fun waitForConnectionCount(
        kind: String,
        expectedCount: Int,
        timeoutMs: Long = 15_000L
    ): JSONArray {
        return waitUntil(timeoutMs, "$expectedCount $kind connection(s)") {
            val connections = connectionSnapshots()
            var count = 0
            for (index in 0 until connections.length()) {
                if (connections.getJSONObject(index).optString("kind") == kind) {
                    count += 1
                }
            }
            if (count == expectedCount) connections else null
        }
    }

    fun waitForRtspPlayingSessions(
        expectedCount: Int,
        timeoutMs: Long = 20_000L
    ): JSONObject {
        return waitUntil(timeoutMs, "RTSP playing sessions == $expectedCount") {
            val status = jsonGet("/rtspStatus")
            if (status.optInt("playingSessions", 0) == expectedCount) status else null
        }
    }

    fun ensureRtspEnabled(): JSONObject {
        val status = jsonGet("/rtspStatus")
        val enabledStatus = if (status.optBoolean("rtspEnabled")) {
            status
        } else {
            jsonGet("/enableRTSP")
        }
        waitUntil(15_000L, "RTSP port to start listening") {
            if (isPortOpen(8554)) Unit else null
        }
        return enabledStatus
    }

    fun loopbackStatus(): JSONObject = jsonGet("/status")

    fun metrics(): JSONObject = jsonGet("/metrics")

    fun waitForMetrics(
        timeoutMs: Long = 20_000L,
        description: String = "metrics condition",
        predicate: (JSONObject) -> Boolean
    ): JSONObject {
        return waitUntil(timeoutMs, description) {
            val metrics = metrics()
            if (predicate(metrics)) metrics else null
        }
    }

    fun waitForStreamingTelemetryQuiescent(
        expectedSseClients: Int = 0,
        timeoutMs: Long = 25_000L
    ): JSONObject {
        return waitForMetrics(
            timeoutMs = timeoutMs,
            description = "streaming telemetry to become idle"
        ) { metrics ->
            metrics.optInt("activeHttpStreams", -1) == 0 &&
                metrics.optInt("activeSseClients", -1) == expectedSseClients &&
                metrics.optInt("activeRtspConnections", -1) == 0 &&
                metrics.optInt("rtspPlayingSessions", -1) == 0 &&
                metrics.optInt("totalCameraClients", -1) == 0 &&
                metrics.optInt("totalLongLivedConnections", -1) == expectedSseClients &&
                metrics.optDouble("currentMjpegFps", -1.0) <= 0.01 &&
                metrics.optDouble("currentRtspFps", -1.0) <= 0.01 &&
                metrics.optLong("mjpegBandwidthBps", -1L) == 0L &&
                metrics.optLong("rtspBandwidthBps", -1L) == 0L &&
                metrics.optLong("bandwidthBps", -1L) == 0L
        }
    }

    fun waitForSnapshotReady(timeoutMs: Long = 25_000L): HttpResponse {
        return waitUntil(timeoutMs, "snapshot to become available") {
            val response = httpGet("/snapshot", expectedCode = null, readTimeoutMs = 15_000)
            if (response.statusCode == 200) response else null
        }
    }

    fun discoverAlternateHostOrNull(): String? {
        val host = loopbackStatus()
            .getString("url")
            .removePrefix("http://")
            .substringBefore(':')
            .trim()

        return when (host) {
            "", "0.0.0.0", LOOPBACK_HOST, "localhost" -> null
            else -> host
        }
    }

    private fun launchMainActivity() {
        runShell("wm dismiss-keyguard")
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("FROM_CAMERA_ACTIVATION", true)
        }
        mainActivityScenario = ActivityScenario.launch(intent)
        instrumentation.waitForIdleSync()
        Thread.sleep(1_000)
    }

    private fun startHttpServer() {
        ContextCompat.startForegroundService(
            appContext,
            cameraServiceIntent().apply {
                putExtra(CameraService.EXTRA_START_SERVER, true)
            }
        )
    }

    private fun waitForHttpServer(timeoutMs: Long = 45_000L): JSONObject {
        return waitUntil(timeoutMs, "HTTP server to start") { jsonGet("/status") }
    }

    private fun waitForCameraCatalogReady(timeoutMs: Long = 30_000L): JSONObject {
        return waitUntil(timeoutMs, "camera catalog to become available") {
            acceptRuntimePermissionDialogsIfPresent(timeoutMs = 1_500L)
            val response = jsonGet("/cameras")
            if (response.getJSONArray("cameras").length() > 0) response else null
        }
    }

    private fun closeTrackedResources() {
        trackedCloseables.asReversed().forEach { closeable ->
            runCatching { closeable.close() }
        }
        trackedCloseables.clear()
    }

    private fun closeAllConnectionsIfPossible() {
        try {
            repeat(5) {
                val connections = connectionSnapshots()
                if (connections.length() == 0) {
                    return
                }
                for (index in 0 until connections.length()) {
                    val id = connections.getJSONObject(index).getString("id")
                    val encodedId = URLEncoder.encode(id, "UTF-8")
                    runCatching { jsonGet("/closeConnection?id=$encodedId", expectedCode = null) }
                }
                Thread.sleep(250)
            }
        } catch (_: Exception) {
            // Server may already be down.
        }
    }

    private fun disableRtspIfPossible() {
        try {
            jsonGet("/disableRTSP", expectedCode = null)
            waitUntil(10_000L, "RTSP to disable") {
                val status = jsonGet("/rtspStatus")
                if (!status.optBoolean("rtspEnabled")) status else null
            }
        } catch (_: Exception) {
            // HTTP server may already be down.
        }
    }

    private fun closeMainActivity() {
        val scenario = mainActivityScenario ?: return
        runCatching { scenario.close() }
        mainActivityScenario = null
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
    }

    private fun filterToLocalConnections(connections: JSONArray): JSONArray {
        val filtered = JSONArray()
        for (index in 0 until connections.length()) {
            val connection = connections.getJSONObject(index)
            if (isLocalConnection(connection.optString("remoteAddr"))) {
                filtered.put(connection)
            }
        }
        return filtered
    }

    private fun isLocalConnection(remoteAddr: String): Boolean {
        val normalized = remoteAddr
            .removePrefix("/")
            .substringBefore('%')
            .trim()
        return normalized in localConnectionAddresses
    }

    private fun discoverLocalConnectionAddresses(): Set<String> {
        val addresses = linkedSetOf("127.0.0.1", "::1", "localhost")
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return addresses
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val inetAddresses = networkInterface.inetAddresses
            while (inetAddresses.hasMoreElements()) {
                val hostAddress = inetAddresses.nextElement().hostAddress ?: continue
                addresses += hostAddress.substringBefore('%')
            }
        }
        return addresses
    }

    private fun ensureRuntimePermissionsGranted() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        permissions.forEach { permission ->
            attemptRuntimePermissionGrant(permission)
        }

        acceptRuntimePermissionDialogsIfPresent()

        permissions.forEach { permission ->
            if (ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED) {
                acceptRuntimePermissionDialogsIfPresent()
            }

            waitUntil(15_000L, "permission $permission to be granted") {
                if (ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED) {
                    Unit
                } else {
                    null
                }
            }
        }
    }

    private fun attemptRuntimePermissionGrant(permission: String) {
        val packageName = appContext.packageName
        val uiAutomation = instrumentation.uiAutomation

        runCatching {
            uiAutomation.adoptShellPermissionIdentity()
            uiAutomation.grantRuntimePermission(packageName, permission)
        }.recoverCatching {
            runShell("pm grant $packageName $permission")
        }.onFailure {
            // Some OEM builds block shell-based runtime grants. The UI dialog fallback below
            // handles those devices by accepting the system permission prompt after launch.
        }.also {
            runCatching { uiAutomation.dropShellPermissionIdentity() }
        }
    }

    private fun acceptRuntimePermissionDialogsIfPresent(timeoutMs: Long = 15_000L) {
        val device = UiDevice.getInstance(instrumentation)
        val deadline = System.currentTimeMillis() + timeoutMs
        var consecutiveMisses = 0

        while (System.currentTimeMillis() < deadline && consecutiveMisses < 5) {
            val clicked = clickPermissionAllowButton(device)
            if (!clicked) {
                consecutiveMisses += 1
                Thread.sleep(300)
                continue
            }
            consecutiveMisses = 0
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
        }
    }

    private fun clickPermissionAllowButton(device: UiDevice): Boolean {
        val resourceIds = listOf(
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_always_button",
            "com.android.packageinstaller:id/permission_allow_button",
            "com.android.packageinstaller:id/permission_allow_foreground_only_button",
            "com.android.packageinstaller:id/permission_allow_one_time_button"
        )

        resourceIds.forEach { resourceId ->
            val button = device.findObject(By.res(resourceId))
            if (button != null) {
                button.click()
                return true
            }
        }

        val textCandidates = listOf(
            "While using the app",
            "Only this time",
            "Allow",
            "Allow all the time",
            "During use of the app",
            "Permitir",
            "Durante o uso do app",
            "Apenas desta vez"
        )

        textCandidates.forEach { text ->
            val button = device.findObject(By.text(text))
            if (button != null) {
                button.click()
                return true
            }
        }

        return false
    }

    private fun seedUiPreferences() {
        updatePreferences(appContext)
        val deviceProtected = appContext.createDeviceProtectedStorageContext()
        updatePreferences(deviceProtected)
    }

    private fun updatePreferences(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("homeLauncherPrompted", true)
            .putBoolean("batteryDialogShown", true)
            .putBoolean("check_battery_after_recreate", false)
            .putBoolean("autoStartServer", false)
            .commit()
    }

    private fun clearPreferences() {
        clearPreferencesFor(appContext)
        clearPreferencesFor(appContext.createDeviceProtectedStorageContext())
    }

    private fun clearPreferencesFor(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun deleteExternalFiles() {
        appContext.getExternalFilesDir(null)
            ?.listFiles()
            ?.forEach { deleteRecursively(it) }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        runCatching { file.delete() }
    }

    private fun waitForPortClosedOrServiceStopped(port: Int, timeoutMs: Long = 15_000L) {
        waitUntil(timeoutMs, "port $port to close or service to stop") {
            val isOpen = isPortOpen(port)
            if (!isOpen || !isCameraServiceRunning()) Unit else null
        }
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(LOOPBACK_HOST, port), 500)
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun cameraServiceIntent(): Intent = Intent(appContext, CameraService::class.java)

    private fun isCameraServiceRunning(): Boolean {
        return runShell("dumpsys activity services com.ipcam/.CameraService").contains("ServiceRecord")
    }

    private fun runShell(command: String): String {
        val fileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        FileInputStream(fileDescriptor.fileDescriptor).use { input ->
            return input.readBytes().toString(Charsets.UTF_8).trim()
        }
    }

    private fun <T> waitUntil(
        timeoutMs: Long,
        description: String,
        block: () -> T?
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null

        while (System.currentTimeMillis() < deadline) {
            try {
                val result = block()
                if (result != null) {
                    return result
                }
            } catch (error: Throwable) {
                lastError = error
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        throw AssertionError(
            buildString {
                append("Timed out waiting for ").append(description)
                if (lastError != null) {
                    append(". Last error: ").append(lastError.message)
                }
            },
            lastError
        )
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val PREFS_NAME = "IPCamSettings"
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}
