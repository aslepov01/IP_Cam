package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ipcam.testsupport.DeviceTestEnvironment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestartServerInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies `/restart`: with SSE and MJPEG clients connected, restart drops both connections,
    // HTTP becomes reachable again with the same persisted connection limits, and the suite ends
    // with no long-lived connections and camera IDLE.
    @Test
    fun restartEndpointDisconnectsLongLivedClientsAndPreservesConnectionLimits() {
        env.setConnectionLimits(mjpegStreams = 7, sseClients = 5, rtspSessions = 4)

        val sse = env.openSse()
        sse.awaitInitialEvents()

        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()

        env.jsonGet("/restart")

        assertTrue(sse.awaitDisconnected(timeoutMs = 20_000L))
        assertTrue(mjpeg.awaitDisconnected(timeoutMs = 20_000L))

        val status = waitForStatusAfterRestart(env)
        val limits = status.getJSONObject("connectionLimits")
        assertEquals(7, limits.getInt("maxMjpegStreams"))
        assertEquals(5, limits.getInt("maxSseClients"))
        assertEquals(4, limits.getInt("maxRtspSessions"))

        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun waitForStatusAfterRestart(env: DeviceTestEnvironment, timeoutMs: Long = 45_000L): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return env.jsonGet("/status")
            } catch (e: Throwable) {
                lastError = e
            }
            Thread.sleep(250)
        }
        throw AssertionError("HTTP server did not become reachable after /restart", lastError)
    }
}
