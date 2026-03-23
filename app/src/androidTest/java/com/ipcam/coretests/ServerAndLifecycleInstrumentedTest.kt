package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
