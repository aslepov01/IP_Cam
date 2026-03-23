package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ipcam.testsupport.DeviceTestEnvironment
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MjpegStreamingInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies the core MJPEG happy path: the endpoint must return multipart content, deliver a
    // real JPEG frame, register the stream as an active connection, and release resources on close.
    @Test
    fun mjpegStreamProducesMultipartFramesAndRegistersConnection() {
        val stream = env.openMjpegStream()

        assertEquals(200, stream.statusCode)
        assertTrue(stream.contentType.contains("multipart/x-mixed-replace"))

        val firstFrame = stream.awaitFirstJpegFrame()
        assertTrue("Expected a non-trivial JPEG frame from MJPEG stream", firstFrame.size > 1_000)

        env.waitForCameraState("ACTIVE")
        assertEquals(1, countConnectionsOfKind(env.connectionSnapshots(), "mjpeg"))

        stream.close()
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    // Verifies same-origin eviction for MJPEG: when a newer stream from the same client replaces
    // the previous one, the oldest connection must be removed and only one active stream remains.
    @Test
    fun newerMjpegStreamFromSameIpEvictsOlderConnection() {
        env.setConnectionLimits(mjpegStreams = 4)

        val first = env.openMjpegStream()
        first.awaitFirstJpegFrame()
        val firstConnectionId = findFirstConnectionId("mjpeg")

        val second = env.openMjpegStream()
        second.awaitFirstJpegFrame()

        env.waitForConnectionAbsent(firstConnectionId)
        env.waitForConnectionCount("mjpeg", 1)

        second.close()
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    // Verifies post-eviction cleanup for MJPEG: once the replacement stream disconnects, the
    // camera must return to IDLE and runtime telemetry must quiesce back to zero streaming load.
    @Test
    fun lastMjpegDisconnectAfterEvictionQuiescesStreamingTelemetry() {
        env.setConnectionLimits(mjpegStreams = 4)

        val first = env.openMjpegStream()
        first.awaitFirstJpegFrame()
        val firstConnectionId = findFirstConnectionId("mjpeg")

        val second = env.openMjpegStream()
        second.awaitFirstJpegFrame()

        env.waitForConnectionAbsent(firstConnectionId)
        env.waitForMetrics(description = "single MJPEG stream after eviction") {
            it.optInt("activeHttpStreams", -1) == 1 &&
                it.optInt("totalCameraClients", -1) == 1
        }

        second.close()

        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")

        val idleMetrics = env.waitForStreamingTelemetryQuiescent()
        assertEquals(0, idleMetrics.getInt("activeHttpStreams"))
        assertEquals(0, idleMetrics.getInt("totalCameraClients"))
    }

    // Verifies global MJPEG limit enforcement across different client addresses: with the limit set
    // to one, opening a stream from an alternate host must evict the original stream and keep one.
    @Test
    fun globalMjpegLimitEvictsOldestStreamWhenAlternateHostIsAvailable() {
        val alternateHost = env.discoverAlternateHostOrNull()
        assumeTrue("Device did not expose a reachable non-loopback HTTP host", alternateHost != null)

        env.setConnectionLimits(mjpegStreams = 1)

        val first = env.openMjpegStream(DeviceTestEnvironment.LOOPBACK_HOST)
        first.awaitFirstJpegFrame()
        val firstConnectionId = findFirstConnectionId("mjpeg")

        val second = env.openMjpegStream(alternateHost!!)
        second.awaitFirstJpegFrame()

        env.waitForConnectionAbsent(firstConnectionId)
        env.waitForConnectionCount("mjpeg", 1)

        second.close()
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun countConnectionsOfKind(connections: JSONArray, kind: String): Int {
        var count = 0
        for (index in 0 until connections.length()) {
            if (connections.getJSONObject(index).getString("kind") == kind) {
                count += 1
            }
        }
        return count
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
