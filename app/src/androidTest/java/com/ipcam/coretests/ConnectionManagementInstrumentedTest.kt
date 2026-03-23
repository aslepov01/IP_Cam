package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder

@RunWith(AndroidJUnit4::class)
class ConnectionManagementInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies mixed transport accounting: when SSE, MJPEG, and RTSP are active at the same time,
    // /connections must expose all three kinds and cleanup must return the app to a clean state.
    @Test
    fun connectionsEndpointReportsMjpegSseAndRtspSessionsTogether() {
        env.ensureRtspEnabled()

        val sse = env.openSse()
        sse.awaitInitialEvents()

        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()

        val rtsp = env.openRtspTcp()
        rtsp.describe()
        rtsp.setupTcp()
        rtsp.play()
        rtsp.awaitInterleavedRtpPacket()

        val connections = env.connectionSnapshots()
        assertTrue(hasConnectionKind(connections, "sse"))
        assertTrue(hasConnectionKind(connections, "mjpeg"))
        assertTrue(hasConnectionKind(connections, "rtsp"))

        rtsp.teardown()
        rtsp.close()
        mjpeg.close()
        sse.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    // Verifies mixed-consumer release order: the camera must remain ACTIVE while any MJPEG or RTSP
    // consumer is still present, and only return to IDLE once the last video consumer disconnects.
    @Test
    fun cameraRemainsActiveUntilLastVideoConsumerDisconnectsEvenIfSseStaysConnected() {
        env.ensureRtspEnabled()

        val sse = env.openSse()
        sse.awaitInitialEvents()
        env.waitForMetrics(description = "SSE-only connection state") {
            it.optInt("activeSseClients", -1) == 1 &&
                it.optInt("totalCameraClients", -1) == 0
        }
        env.waitForCameraState("IDLE")

        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()

        val rtsp = env.openRtspTcp()
        rtsp.describe()
        rtsp.setupTcp()
        rtsp.play()
        rtsp.awaitInterleavedRtpPacket()

        env.waitForCameraState("ACTIVE")
        env.waitForRtspPlayingSessions(1)
        env.waitForMetrics(description = "mixed video consumers active") {
            it.optInt("activeSseClients", -1) == 1 &&
                it.optInt("activeHttpStreams", -1) == 1 &&
                it.optInt("rtspPlayingSessions", -1) == 1 &&
                it.optInt("totalCameraClients", -1) == 2
        }

        rtsp.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForMetrics(description = "MJPEG remains the last video consumer") {
            it.optInt("activeSseClients", -1) == 1 &&
                it.optInt("activeHttpStreams", -1) == 1 &&
                it.optInt("activeRtspConnections", -1) == 0 &&
                it.optInt("rtspPlayingSessions", -1) == 0 &&
                it.optInt("totalCameraClients", -1) == 1
        }
        env.waitForCameraState("ACTIVE")

        mjpeg.close()

        env.waitForMetrics(description = "SSE stays connected after video cleanup") {
            it.optInt("activeSseClients", -1) == 1 &&
                it.optInt("activeHttpStreams", -1) == 0 &&
                it.optInt("activeRtspConnections", -1) == 0 &&
                it.optInt("totalCameraClients", -1) == 0 &&
                it.optDouble("currentMjpegFps", -1.0) <= 0.01 &&
                it.optDouble("currentRtspFps", -1.0) <= 0.01
        }
        env.waitForCameraState("IDLE")

        sse.close()
        env.waitForNoLongLivedConnections()
        env.waitForStreamingTelemetryQuiescent()
    }

    // Verifies targeted connection eviction: closing a named MJPEG client through /closeConnection
    // should remove that connection and release the camera when it was the last consumer.
    @Test
    fun closeConnectionEndpointTerminatesNamedMjpegConnectionAndReleasesCamera() {
        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()
        env.waitForCameraState("ACTIVE")

        val connectionId = findFirstConnectionId("mjpeg")
        val response = env.jsonGet("/closeConnection?id=${URLEncoder.encode(connectionId, "UTF-8")}")

        assertEquals("ok", response.getString("status"))
        env.waitForConnectionAbsent(connectionId)

        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun hasConnectionKind(connections: JSONArray, kind: String): Boolean {
        for (index in 0 until connections.length()) {
            if (connections.getJSONObject(index).getString("kind") == kind) {
                return true
            }
        }
        return false
    }

    private fun findFirstConnectionId(kind: String): String {
        val connections = env.connectionSnapshots()
        for (index in 0 until connections.length()) {
            val connection = connections.getJSONObject(index)
            if (connection.getString("kind") == kind) {
                return connection.getString("id")
            }
        }
        throw AssertionError("No $kind connection found in /connections")
    }
}
