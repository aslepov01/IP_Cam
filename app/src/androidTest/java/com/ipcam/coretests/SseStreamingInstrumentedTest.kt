package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder

@RunWith(AndroidJUnit4::class)
class SseStreamingInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies the core SSE happy path: the endpoint must establish an event stream, emit the
    // initial state and metrics events, register the client, and leave no residue after close.
    @Test
    fun sseConnectionReceivesInitialStateAndMetricsEvents() {
        val sse = env.openSse()

        assertEquals(200, sse.statusCode)
        assertTrue(sse.contentType.contains("text/event-stream"))

        val events = sse.awaitInitialEvents()
        assertTrue("Expected initial SSE state event", events.contains("state"))
        assertTrue("Expected initial SSE metrics event", events.contains("metrics"))
        assertEquals(1, countConnectionsOfKind(env.connectionSnapshots(), "sse"))

        sse.close()
        env.waitForNoLongLivedConnections()
    }

    // Verifies that SSE is a status-only transport: it should register as an SSE client in
    // telemetry, but it must not activate the camera or count as a camera consumer by itself.
    @Test
    fun sseClientDoesNotActivateCameraOrCreateCameraClients() {
        env.waitForCameraState("IDLE")

        val sse = env.openSse()
        val events = sse.awaitInitialEvents()
        val expectedSseClients = env.expectedActiveSseClients(1)

        assertTrue("Expected initial SSE state event", events.contains("state"))
        assertTrue("Expected initial SSE metrics event", events.contains("metrics"))

        val metrics = env.waitForMetrics(description = "SSE-only telemetry state") {
            it.optInt("activeSseClients", -1) == expectedSseClients &&
                it.optInt("activeHttpStreams", -1) == 0 &&
                it.optInt("activeRtspConnections", -1) == 0 &&
                it.optInt("rtspPlayingSessions", -1) == 0 &&
                it.optInt("totalCameraClients", -1) == 0
        }

        assertEquals(expectedSseClients, metrics.getInt("activeSseClients"))
        env.waitForCameraState("IDLE")

        sse.close()
        env.waitForNoLongLivedConnections()
        env.waitForStreamingTelemetryQuiescent()
    }

    // Verifies SSE limit eviction: when only one SSE client is allowed, opening a second client
    // must evict the oldest one so that exactly one active SSE connection remains.
    @Test
    fun sseLimitEvictsOldestClient() {
        env.setConnectionLimits(sseClients = 1)

        val first = env.openSse()
        first.awaitInitialEvents()
        val firstConnectionId = findFirstConnectionId("sse")

        val second = env.openSse()
        second.awaitInitialEvents()

        env.waitForConnectionAbsent(firstConnectionId)
        env.waitForConnectionCount("sse", 1)

        second.close()
        env.waitForNoLongLivedConnections()
    }

    // Verifies targeted SSE shutdown through /closeConnection: the named client must disappear
    // from the connection registry and cleanup must leave the runtime in a clean state.
    @Test
    fun closeConnectionEndpointTerminatesNamedSseClient() {
        val sse = env.openSse()
        sse.awaitInitialEvents()

        val sseConnectionId = findFirstConnectionId("sse")
        val response = env.jsonGet("/closeConnection?id=${URLEncoder.encode(sseConnectionId, "UTF-8")}")

        assertEquals("ok", response.getString("status"))
        env.waitForConnectionAbsent(sseConnectionId)

        env.waitForNoLongLivedConnections()
    }

    // Verifies SSE state deltas after configuration changes: /setRotation and /setCameraOrientation
    // must produce delta events with only the changed fields, and setting the same value twice
    // must not emit a redundant delta.
    @Test
    fun sseStateDeltasReflectConfigurationChangesWithoutRedundancy() {
        val sse = env.openSse()
        sse.awaitInitialEventPayloads()

        env.jsonGet("/setRotation?value=90")
        val rotationDelta = sse.awaitEventOfType("state", timeoutMs = 10_000L)
        assertNotNull("Expected SSE state delta after /setRotation", rotationDelta)
        val rotationJson = JSONObject(rotationDelta!!)
        assertEquals(90, rotationJson.getInt("rotation"))

        env.jsonGet("/setCameraOrientation?value=portrait")
        val orientationDelta = sse.awaitEventOfType("state", timeoutMs = 10_000L)
        assertNotNull("Expected SSE state delta after /setCameraOrientation", orientationDelta)
        val orientationJson = JSONObject(orientationDelta!!)
        assertEquals("portrait", orientationJson.getString("cameraOrientation"))

        // Setting the same rotation again should not produce a redundant delta.
        // Verify by setting the same value, then changing a different field:
        // the next delta must contain the new field but NOT the unchanged rotation.
        env.jsonGet("/setRotation?value=90")
        env.jsonGet("/setCameraOrientation?value=landscape")
        val nextDelta = sse.awaitEventOfType("state", timeoutMs = 10_000L)
        assertNotNull("Expected SSE state delta after /setCameraOrientation reset", nextDelta)
        val nextJson = JSONObject(nextDelta!!)
        assertEquals("landscape", nextJson.getString("cameraOrientation"))
        assertFalse("Redundant rotation should not appear in delta", nextJson.has("rotation"))

        // Restore defaults
        env.jsonGet("/setRotation?value=0")

        sse.close()
        env.waitForNoLongLivedConnections()
    }

    // Verifies that SSE metrics events reflect connection churn: opening and closing MJPEG and
    // RTSP clients must cause subsequent metrics events to report the updated connection counts.
    @Test
    fun sseMetricsEventsReflectConnectionChurn() {
        env.ensureRtspEnabled()

        val sse = env.openSse()
        sse.awaitInitialEventPayloads()

        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()

        val metricsWithMjpeg = awaitSseMetricsMatching(sse) {
            it.optInt("activeHttpStreams", -1) >= 1
        }
        assertTrue(metricsWithMjpeg.getInt("activeHttpStreams") >= 1)

        val rtsp = env.openRtspTcp()
        rtsp.describe()
        rtsp.setupTcp()
        rtsp.play()
        rtsp.awaitInterleavedRtpPacket()

        val metricsWithBoth = awaitSseMetricsMatching(sse) {
            it.optInt("activeHttpStreams", -1) >= 1 &&
                it.optInt("rtspPlayingSessions", -1) >= 1
        }
        assertTrue(metricsWithBoth.getInt("activeHttpStreams") >= 1)
        assertTrue(metricsWithBoth.getInt("rtspPlayingSessions") >= 1)

        rtsp.teardown()
        rtsp.close()
        mjpeg.close()

        val idleMetrics = awaitSseMetricsMatching(sse) {
            it.optInt("activeHttpStreams", -1) == 0 &&
                it.optInt("activeRtspConnections", -1) == 0 &&
                it.optInt("rtspPlayingSessions", -1) == 0 &&
                it.optInt("totalCameraClients", -1) == 0
        }
        assertEquals(0, idleMetrics.getInt("activeHttpStreams"))
        assertEquals(0, idleMetrics.getInt("totalCameraClients"))

        sse.close()
        env.waitForNoLongLivedConnections()
        env.waitForCameraState("IDLE")
    }

    private fun awaitSseMetricsMatching(
        sse: com.ipcam.testsupport.SseClient,
        timeoutMs: Long = 20_000L,
        predicate: (JSONObject) -> Boolean
    ): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val payload = sse.awaitEventOfType("metrics", timeoutMs = 5_000L) ?: continue
            val json = try {
                JSONObject(payload)
            } catch (_: org.json.JSONException) {
                // Server may truncate SSE messages under heavy write pressure; skip and retry.
                continue
            }
            if (predicate(json)) return json
        }
        throw AssertionError("Timed out waiting for matching SSE metrics event")
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

    private fun countConnectionsOfKind(connections: JSONArray, kind: String): Int {
        var count = 0
        for (index in 0 until connections.length()) {
            if (connections.getJSONObject(index).getString("kind") == kind) {
                count += 1
            }
        }
        return count
    }
}
