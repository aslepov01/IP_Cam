package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraResetInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies `/resetCamera` from IDLE: endpoint returns ok, a subsequent `/snapshot` yields a
    // large JPEG, and the runtime finishes with no dangling connections and camera IDLE.
    @Test
    fun resetCameraFromIdleAllowsFreshSnapshotAndCleanConnections() {
        env.waitForCameraState("IDLE")

        val reset = env.jsonGet("/resetCamera")
        assertEquals("ok", reset.getString("status"))

        val snap = env.waitForSnapshotReady()
        assertTrue(snap.body.size > 1_000)

        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    // Verifies `/resetCamera` with an active MJPEG client: after reset the service keeps the MJPEG
    // consumer and restarts the camera pipeline, so the same HTTP stream can receive another JPEG
    // frame and the camera stays ACTIVE until the client closes; then `/snapshot` works and state
    // returns to IDLE.
    @Test
    fun resetCameraWithActiveMjpegAllowsSnapshotAfterResetAndEndsClean() {
        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()
        env.waitForCameraState("ACTIVE")

        val reset = env.jsonGet("/resetCamera")
        assertEquals("ok", reset.getString("status"))

        val frameAfterReset = mjpeg.awaitFirstJpegFrame(timeoutMs = 45_000L)
        assertTrue(frameAfterReset.size > 1_000)
        env.waitForCameraState("ACTIVE")

        mjpeg.close()
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")

        val snap = env.waitForSnapshotReady()
        assertTrue(snap.body.size > 1_000)
        env.waitForCameraState("IDLE")
    }
}
