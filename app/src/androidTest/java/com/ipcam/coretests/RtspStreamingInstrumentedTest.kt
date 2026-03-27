package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RtspStreamingInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies the full RTSP-over-TCP playback handshake: OPTIONS, DESCRIBE, SETUP, and PLAY must
    // succeed, SDP must describe H264 video, RTP packets must arrive, and teardown must clean up.
    @Test
    fun rtspTcpSessionProducesSdpAndInterleavedRtpPackets() {
        env.ensureRtspEnabled()

        val client = env.openRtspTcp()

        assertEquals(200, client.options().statusCode)

        val describe = client.describe()
        assertEquals(200, describe.statusCode)
        assertTrue(describe.body.contains("m=video"))
        assertTrue(describe.body.contains("a=rtpmap:96 H264/90000"))

        val setup = client.setupTcp()
        assertEquals(200, setup.statusCode)

        val play = client.play()
        assertEquals(200, play.statusCode)

        val firstPacket = client.awaitInterleavedRtpPacket()
        assertTrue("Expected a non-empty RTP payload from RTSP TCP session", firstPacket.isNotEmpty())

        env.waitForRtspPlayingSessions(1)
        env.waitForCameraState("ACTIVE")

        val teardown = client.teardown()
        assertEquals(200, teardown.statusCode)
        client.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    // Verifies RTSP playing-session eviction: with the session limit set to one, starting a second
    // playing client must displace the first while the replacement stream continues to play.
    @Test
    fun rtspPlayingSessionLimitEvictsOldestPlayingClient() {
        env.ensureRtspEnabled()
        env.setConnectionLimits(rtspSessions = 1)

        val first = env.openRtspTcp()
        first.describe()
        first.setupTcp()
        first.play()
        first.awaitInterleavedRtpPacket()
        val firstConnectionId = findFirstConnectionId("rtsp")

        val second = env.openRtspTcp()
        second.describe()
        second.setupTcp()
        second.play()
        second.awaitInterleavedRtpPacket()

        env.waitForConnectionAbsent(firstConnectionId)

        env.waitForRtspPlayingSessions(1)

        val teardown = second.teardown()
        assertEquals(200, teardown.statusCode)
        second.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    // Verifies post-eviction cleanup for RTSP: after the surviving client disconnects without an
    // explicit TEARDOWN, the server must release the camera and clear all streaming telemetry.
    @Test
    fun lastRtspDisconnectAfterEvictionQuiescesStreamingTelemetry() {
        env.ensureRtspEnabled()
        env.setConnectionLimits(rtspSessions = 1)

        val first = env.openRtspTcp()
        first.describe()
        first.setupTcp()
        first.play()
        first.awaitInterleavedRtpPacket()
        val firstConnectionId = findFirstConnectionId("rtsp")

        val second = env.openRtspTcp()
        second.describe()
        second.setupTcp()
        second.play()
        second.awaitInterleavedRtpPacket()

        env.waitForConnectionAbsent(firstConnectionId)
        env.waitForMetrics(description = "single RTSP stream after eviction") {
            it.optInt("activeRtspConnections", -1) == 1 &&
                it.optInt("rtspPlayingSessions", -1) == 1 &&
                it.optInt("totalCameraClients", -1) == 1
        }

        second.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")

        val idleMetrics = env.waitForStreamingTelemetryQuiescent()
        assertEquals(0, idleMetrics.getInt("activeRtspConnections"))
        assertEquals(0, idleMetrics.getInt("totalCameraClients"))
    }

    // Verifies RTSP pause semantics: PAUSE must release the camera lease and drop camera-client
    // counters to zero, while a subsequent PLAY on the same session must reacquire the stream.
    @Test
    fun rtspPauseReleasesCameraLeaseAndPlayReacquiresIt() {
        env.ensureRtspEnabled()
        env.waitForCameraState("IDLE")

        val client = env.openRtspTcp()
        assertEquals(200, client.describe().statusCode)
        assertEquals(200, client.setupTcp().statusCode)
        assertEquals(200, client.play().statusCode)
        client.awaitInterleavedRtpPacket()

        env.waitForRtspPlayingSessions(1)
        env.waitForCameraState("ACTIVE")

        val pause = client.pause()
        assertEquals(200, pause.statusCode)

        env.waitForRtspPlayingSessions(0)
        env.waitForCameraState("IDLE")
        env.waitForMetrics(description = "paused RTSP session without camera lease") {
            it.optInt("activeRtspConnections", -1) == 1 &&
                it.optInt("rtspPlayingSessions", -1) == 0 &&
                it.optInt("totalCameraClients", -1) == 0 &&
                it.optDouble("currentRtspFps", -1.0) <= 0.01
        }

        val resumed = client.play()
        assertEquals(200, resumed.statusCode)
        client.awaitInterleavedRtpPacket()

        env.waitForRtspPlayingSessions(1)
        env.waitForCameraState("ACTIVE")

        val teardown = client.teardown()
        assertEquals(200, teardown.statusCode)
        client.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
        env.waitForStreamingTelemetryQuiescent()
    }

    // Verifies RTSP subsystem shutdown with an active playing client: disabling RTSP must close the
    // live session, stop the camera lease, and leave no RTSP activity in telemetry afterward.
    @Test
    fun disablingRtspWithActiveClientClosesSessionAndReleasesCamera() {
        env.ensureRtspEnabled()

        val client = env.openRtspTcp()
        client.describe()
        client.setupTcp()
        client.play()
        client.awaitInterleavedRtpPacket()

        env.waitForRtspPlayingSessions(1)
        env.waitForCameraState("ACTIVE")

        val disableResponse = env.jsonGet("/disableRTSP")
        assertEquals("ok", disableResponse.getString("status"))
        assertEquals(false, disableResponse.getBoolean("rtspEnabled"))

        assertTrue("RTSP client should be disconnected when RTSP is disabled", client.awaitDisconnected())

        env.waitForMetrics(description = "RTSP disabled state") {
            it.optInt("activeRtspConnections", -1) == 0 &&
                it.optInt("rtspPlayingSessions", -1) == 0 &&
                it.optInt("totalCameraClients", -1) == 0
        }
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
        env.waitForStreamingTelemetryQuiescent()
    }

    // Verifies RTSP camera leasing on passive access: a DESCRIBE request should acquire the camera
    // lease, and simply closing the socket must release the lease when no consumers remain.
    @Test
    fun rtspDescribeAcquiresCameraLeaseAndSocketCloseReleasesIt() {
        env.ensureRtspEnabled()
        env.waitForCameraState("IDLE")

        val client = env.openRtspTcp()
        val describe = client.describe()

        assertEquals(200, describe.statusCode)
        env.waitForCameraState("ACTIVE")

        client.close()

        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    // Verifies the manual reset path under an active RTSP stream. Resetting the camera must not
    // accumulate extra H.264 encoder instances; the stream should recover with exactly one active
    // encoder and teardown must return the runtime to zero active encoders.
    @Test
    fun resettingCameraDuringActiveRtspStreamDoesNotLeakEncoderThreads() {
        env.ensureRtspEnabled()
        env.waitForRtspActiveEncoderCount(0)

        val client = env.openRtspTcp()
        assertEquals(200, client.describe().statusCode)
        assertEquals(200, client.setupTcp().statusCode)
        assertEquals(200, client.play().statusCode)
        client.awaitInterleavedRtpPacket()

        env.waitForRtspPlayingSessions(1)
        env.waitForCameraState("ACTIVE")
        assertEquals(1, env.waitForRtspActiveEncoderCount(1).getInt("activeEncoders"))

        repeat(2) { resetIndex ->
            val reset = env.jsonGet("/resetCamera")
            assertEquals("ok", reset.getString("status"))

            client.awaitInterleavedRtpPacket()
            env.waitForRtspPlayingSessions(1)
            env.waitForCameraState("ACTIVE")
            assertEquals(
                "reset ${resetIndex + 1} must not leak an extra H.264 encoder instance",
                1,
                env.waitForRtspActiveEncoderCount(1).getInt("activeEncoders")
            )
        }

        assertEquals(200, client.teardown().statusCode)
        client.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
        assertEquals(0, env.waitForRtspActiveEncoderCount(0).getInt("activeEncoders"))
    }

    // Verifies the full RTSP-over-UDP playback handshake: SETUP negotiates UDP transport with
    // client/server port pairs, PLAY triggers RTP packet delivery via DatagramSocket, the first
    // received packet has a valid RTP header, and TEARDOWN releases the camera back to IDLE.
    @Test
    fun rtspUdpSessionReceivesRtpPacketsViaDatagramSocket() {
        env.ensureRtspEnabled()

        val client = env.openRtspUdp()

        assertEquals(200, client.options().statusCode)

        val describe = client.describe()
        assertEquals(200, describe.statusCode)
        assertTrue("SDP should contain video media line", describe.body.contains("m=video"))
        assertTrue("SDP should describe H264 codec", describe.body.contains("a=rtpmap:96 H264/90000"))

        val setup = client.setupUdp()
        assertEquals(200, setup.statusCode)
        assertTrue("Server should allocate RTP port", client.serverRtpPort > 0)
        assertTrue("Server should allocate RTCP port", client.serverRtcpPort > 0)
        val transport = setup.headers["transport"] ?: ""
        assertTrue("Transport should echo client ports",
            transport.contains("client_port=${client.clientRtpPort}-${client.clientRtcpPort}"))
        assertTrue("Transport should include server ports",
            transport.contains("server_port="))

        val play = client.play()
        assertEquals(200, play.statusCode)

        val rtpPacket = client.awaitUdpRtpPacket()
        assertTrue("RTP packet should have at least 12-byte header", rtpPacket.size >= 12)
        val rtpVersion = (rtpPacket[0].toInt() shr 6) and 0x03
        assertEquals("RTP version should be 2", 2, rtpVersion)
        val payloadType = rtpPacket[1].toInt() and 0x7F
        assertEquals("RTP payload type should be 96 (dynamic H264)", 96, payloadType)

        env.waitForRtspPlayingSessions(1)
        env.waitForCameraState("ACTIVE")

        val teardown = client.teardown()
        assertEquals(200, teardown.statusCode)
        client.close()

        env.waitForRtspPlayingSessions(0)
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun findFirstConnectionId(kind: String): String {
        val connections: JSONArray = env.connectionSnapshots()
        for (index in 0 until connections.length()) {
            val connection = connections.getJSONObject(index)
            if (connection.getString("kind") == kind) {
                return connection.getString("id")
            }
        }
        throw AssertionError("No $kind connection found in /connections")
    }
}
