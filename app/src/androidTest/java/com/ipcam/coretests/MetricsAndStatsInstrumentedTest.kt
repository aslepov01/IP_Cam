package com.ipcam.coretests

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetricsAndStatsInstrumentedTest : BaseDeviceCoreTest() {

    // Verifies that /metrics returns all expected telemetry fields at idle with coherent values.
    @Test
    fun metricsEndpointExposesAllFieldsAtIdle() {
        env.waitForCameraState("IDLE")

        val m = env.metrics()

        assertTrue("timestampMs should be positive", m.getLong("timestampMs") > 0)
        assertTrue("cpuUsagePercent should be non-negative", m.getDouble("cpuUsagePercent") >= 0.0)

        assertEquals(0L, m.getLong("bandwidthBps"))
        assertEquals(0L, m.getLong("mjpegBandwidthBps"))
        assertEquals(0L, m.getLong("rtspBandwidthBps"))

        assertTrue("currentCameraFps should be non-negative", m.getDouble("currentCameraFps") >= 0.0)
        assertTrue("currentMjpegFps should be non-negative", m.getDouble("currentMjpegFps") >= 0.0)
        assertTrue("currentRtspFps should be non-negative", m.getDouble("currentRtspFps") >= 0.0)

        assertEquals(0, m.getInt("activeHttpStreams"))
        assertTrue("activeSseClients should be non-negative", m.getInt("activeSseClients") >= 0)
        assertEquals(0, m.getInt("activeRtspConnections"))
        assertEquals(0, m.getInt("rtspPlayingSessions"))
        assertEquals(0, m.getInt("totalCameraClients"))
        assertTrue("totalLongLivedConnections should be non-negative",
            m.getInt("totalLongLivedConnections") >= 0)

        assertTrue("batteryLevel should be 0-100", m.getInt("batteryLevel") in 0..100)
        m.getBoolean("isCharging")
    }

    // Verifies that /metrics reflects active streaming load and that /stats returns a plain-text
    // report containing the expected sections.
    @Test
    fun metricsReflectLoadAndStatsContainExpectedSections() {
        val mjpeg = env.openMjpegStream()
        mjpeg.awaitFirstJpegFrame()
        env.waitForCameraState("ACTIVE")

        val loaded = env.waitForMetrics(description = "MJPEG load visible in metrics") {
            it.optInt("activeHttpStreams", -1) >= 1 &&
                it.optInt("totalCameraClients", -1) >= 1
        }
        assertTrue(loaded.getInt("activeHttpStreams") >= 1)
        assertTrue(loaded.getInt("totalCameraClients") >= 1)

        mjpeg.close()
        env.waitForCameraState("IDLE")
        env.waitForStreamingTelemetryQuiescent()

        val stats = env.httpGet("/stats")
        assertEquals(200, stats.statusCode)
        val body = stats.bodyText()
        assertTrue("Stats should contain Runtime Telemetry section",
            body.contains("Runtime Telemetry", ignoreCase = true))
        assertTrue("Stats should contain Performance Metrics section",
            body.contains("Performance Metrics", ignoreCase = true))
    }
}
