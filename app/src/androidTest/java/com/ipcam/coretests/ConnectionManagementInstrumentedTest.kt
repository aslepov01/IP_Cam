package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder

@RunWith(AndroidJUnit4::class)
class ConnectionManagementInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies concurrent MJPEG streams from different client addresses: when the limit allows it,
    // loopback and the device's non-loopback host should remain active together and clean up fully.
    @Test
    fun distinctAddressMjpegClientsWithinLimitRemainActiveUntilAllDisconnect() {
        val alternateHost = env.discoverAlternateHostOrNull()
        assumeTrue("Device did not expose a reachable non-loopback HTTP host", alternateHost != null)

        env.setConnectionLimits(mjpegStreams = 4)

        val first = env.openMjpegStream()
        val second = env.openMjpegStream(alternateHost!!)
        val expectedLongLivedConnections = env.expectedTotalLongLivedConnections(2)

        assertTrue(first.awaitFirstJpegFrame().size > 1_000)
        assertTrue(second.awaitFirstJpegFrame().size > 1_000)

        env.waitForConnectionCount("mjpeg", 2)
        val metrics = env.waitForMetrics(description = "two MJPEG clients to remain active") {
            it.optInt("activeHttpStreams", -1) == 2 &&
                it.optInt("totalLongLivedConnections", -1) == expectedLongLivedConnections
        }
        assertEquals(2, metrics.getInt("activeHttpStreams"))

        second.close()
        first.close()

        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
        env.waitForStreamingTelemetryQuiescent()
    }

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
            it.optInt("activeSseClients", -1) >= 1 &&
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
            it.optInt("activeSseClients", -1) >= 1 &&
                it.optInt("activeHttpStreams", -1) == 1 &&
                it.optInt("rtspPlayingSessions", -1) == 1 &&
                it.optInt("totalCameraClients", -1) == 2
        }

        rtsp.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForMetrics(description = "MJPEG remains the last video consumer") {
            it.optInt("activeSseClients", -1) >= 1 &&
                it.optInt("activeHttpStreams", -1) == 1 &&
                it.optInt("activeRtspConnections", -1) == 0 &&
                it.optInt("rtspPlayingSessions", -1) == 0 &&
                it.optInt("totalCameraClients", -1) == 1
        }
        env.waitForCameraState("ACTIVE")

        mjpeg.close()

        env.waitForMetrics(description = "SSE stays connected after video cleanup") {
            it.optInt("activeSseClients", -1) >= 1 &&
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

    // Verifies that /connections exposes full metadata (endpoint, active, state, duration) for each
    // transport kind, and that RTSP sessions transition through READY and PLAYING states.
    @Test
    fun connectionSnapshotsExposeMetadataAndRtspStateTransitions() {
        env.ensureRtspEnabled()

        val sse = env.openSse()
        sse.awaitInitialEvents()

        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()

        val rtsp = env.openRtspTcp()
        rtsp.describe()
        rtsp.setupTcp()

        // After SETUP the RTSP session should be in READY state
        val readyConnections = env.waitForConnectionCount("rtsp", 1)
        val rtspReady = findConnectionOfKind(readyConnections, "rtsp")
        assertEquals("/events", findConnectionOfKind(env.connectionSnapshots(), "sse").getString("endpoint"))
        assertEquals("/stream", findConnectionOfKind(env.connectionSnapshots(), "mjpeg").getString("endpoint"))
        assertTrue(rtspReady.getString("endpoint").contains("rtsp://"))
        assertEquals("READY", rtspReady.getString("state"))
        assertTrue(rtspReady.getBoolean("active"))
        assertTrue("Duration should be non-negative", rtspReady.getLong("duration") >= 0)

        // After PLAY the RTSP session should transition to PLAYING
        rtsp.play()
        rtsp.awaitInterleavedRtpPacket()
        env.waitForRtspPlayingSessions(1)

        val playingConnections = env.connectionSnapshots()
        val rtspPlaying = findConnectionOfKind(playingConnections, "rtsp")
        assertEquals("PLAYING", rtspPlaying.getString("state"))
        assertTrue(rtspPlaying.getBoolean("active"))

        // Verify MJPEG and SSE metadata
        val mjpegConn = findConnectionOfKind(playingConnections, "mjpeg")
        assertEquals("/stream", mjpegConn.getString("endpoint"))
        assertTrue(mjpegConn.getBoolean("active"))
        assertTrue(mjpegConn.has("state"))

        val sseConn = findConnectionOfKind(playingConnections, "sse")
        assertEquals("/events", sseConn.getString("endpoint"))
        assertTrue(sseConn.getBoolean("active"))
        assertEquals("CONNECTED", sseConn.getString("state"))

        rtsp.teardown()
        rtsp.close()
        mjpeg.close()
        sse.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun findConnectionOfKind(connections: JSONArray, kind: String): JSONObject {
        for (index in 0 until connections.length()) {
            val connection = connections.getJSONObject(index)
            if (connection.getString("kind") == kind) {
                return connection
            }
        }
        throw AssertionError("No $kind connection found in /connections")
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
