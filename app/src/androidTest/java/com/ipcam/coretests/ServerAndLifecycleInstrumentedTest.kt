package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerAndLifecycleInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies that a freshly started app exposes a healthy HTTP server state and does not leak
    // any local long-lived connections before a client explicitly opens a stream.
    @Test
    fun statusEndpointReportsRunningServerAndNoUnexpectedConnections() {
        val status = env.loopbackStatus()

        assertEquals("running", status.getString("status"))
        assertEquals("Ktor", status.getString("server"))
        assertTrue(status.getString("url").startsWith("http://"))
        assertTrue(status.getJSONObject("connectionLimits").getInt("maxMjpegStreams") >= 1)
        assertEquals(0, env.connectionSnapshots().length())
    }

    // Verifies the manual camera leasing flow over HTTP: activating the camera moves runtime
    // state to ACTIVE, and deactivating it releases the lease back to IDLE without leftovers.
    @Test
    fun manualCameraLeaseActivatesAndReleasesCamera() {
        env.waitForCameraState("IDLE")

        val activated = env.jsonGet("/activateCamera")
        assertEquals("ok", activated.getString("status"))

        env.waitForCameraState("ACTIVE")

        val deactivated = env.jsonGet("/deactivateCamera")
        assertEquals("ok", deactivated.getString("status"))

        env.waitForCameraState("IDLE")
        env.waitForNoLongLivedConnections()
    }

    // Locks down the full /status JSON field contract at idle so refactoring cannot silently
    // drop or mistype fields that external consumers rely on.
    @Test
    fun statusEndpointExposesCompleteFieldContract() {
        val status = env.loopbackStatus()

        assertEquals("running", status.getString("status"))
        assertEquals("Ktor", status.getString("server"))

        // Device and camera identity
        assertFalse("deviceName should be non-empty", status.getString("deviceName").isEmpty())
        assertNotNull("cameraId should be present", status.opt("cameraId"))
        assertFalse("cameraLabel should be non-empty", status.getString("cameraLabel").isEmpty())
        assertFalse("cameraFacing should be non-empty", status.getString("cameraFacing").isEmpty())
        assertTrue("cameraCatalogVersion should be positive", status.getInt("cameraCatalogVersion") > 0)

        // Runtime state
        assertTrue("cameraState should be a known value",
            status.getString("cameraState") in listOf("IDLE", "INITIALIZING", "ACTIVE", "STOPPING", "ERROR"))
        assertTrue("url should start with http://", status.getString("url").startsWith("http://"))
        assertNotNull("resolution should be present", status.getString("resolution"))

        // Flashlight
        status.getBoolean("flashlightAvailable")
        status.getBoolean("flashlightOn")

        // Active connections
        val connections = status.getJSONObject("activeConnections")
        assertTrue(connections.getInt("total") >= 0)
        assertTrue(connections.getInt("mjpeg") >= 0)
        assertTrue(connections.getInt("sse") >= 0)
        assertTrue(connections.getInt("rtspActive") >= 0)
        assertTrue(connections.getInt("rtspPlaying") >= 0)

        // Connection limits
        val limits = status.getJSONObject("connectionLimits")
        assertTrue(limits.getInt("maxMjpegStreams") >= 1)
        assertTrue(limits.getInt("maxSseClients") >= 1)
        assertTrue(limits.getInt("maxRtspSessions") >= 1)

        // Battery and streaming
        assertFalse("batteryMode should be non-empty", status.getString("batteryMode").isEmpty())
        status.getBoolean("streamingAllowed")

        // Endpoints array
        val endpoints = status.getJSONArray("endpoints")
        assertTrue("endpoints should list at least 10 routes", endpoints.length() >= 10)

        // Version block
        val version = status.getJSONObject("version")
        assertFalse(version.getString("versionName").isEmpty())
        assertTrue(version.getInt("versionCode") > 0)
        assertFalse(version.getString("commitHash").isEmpty())
        assertFalse(version.getString("branch").isEmpty())
        assertFalse(version.getString("buildTimestamp").isEmpty())
        assertTrue(version.getLong("buildNumber") > 0)
    }

    // Verifies the one-shot snapshot scenario: the server should return a valid JPEG, acquire the
    // camera only for the request, and release it again after the response completes.
    @Test
    fun snapshotRequestReturnsJpegAndReleasesCameraAfterCompletion() {
        env.waitForCameraState("IDLE")

        val response = env.waitForSnapshotReady()
        val contentType = response.headers["Content-Type"]?.firstOrNull().orEmpty()

        assertEquals(200, response.statusCode)
        assertTrue(contentType.contains("image/jpeg"))
        assertTrue("Snapshot JPEG should not be empty", response.body.size > 1_000)

        env.waitForCameraState("IDLE")
        env.waitForNoLongLivedConnections()
    }
}
