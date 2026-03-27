package com.ipcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeTelemetrySamplerTest {

    // Verifies bandwidth sampling across transports: recorded MJPEG and RTSP bytes must be
    // converted into per-transport and aggregate bandwidth values in the emitted snapshot.
    @Test
    fun sampleCalculatesPerTransportAndAggregateBandwidth() {
        val sampler = RuntimeTelemetrySampler()
        sampler.reset(nowMs = 1_000L)

        sampler.recordBytes(StreamTransport.MJPEG, 1_000L)
        sampler.recordBytes(StreamTransport.RTSP, 2_000L)

        val snapshot = sampler.sample(
            cpuUsagePercent = 12.5f,
            currentCameraFps = 30f,
            currentMjpegFps = 10f,
            currentRtspFps = 24f,
            activeHttpStreams = 1,
            activeSseClients = 2,
            activeRtspConnections = 3,
            rtspPlayingSessions = 2,
            totalCameraClients = 3,
            totalLongLivedConnections = 6,
            batteryLevel = 55,
            isCharging = true,
            nowMs = 2_000L
        )

        assertEquals(8_000L, snapshot.mjpegBandwidthBps)
        assertEquals(16_000L, snapshot.rtspBandwidthBps)
        assertEquals(24_000L, snapshot.bandwidthBps)
        assertEquals(3, snapshot.totalCameraClients)
        assertEquals(6, snapshot.totalLongLivedConnections)
    }

    // Verifies reset semantics for telemetry accounting: after reset, previously accumulated
    // bandwidth must no longer leak into subsequent samples and basic fields stay consistent.
    @Test
    fun resetClearsAccumulatedBandwidthCounters() {
        val sampler = RuntimeTelemetrySampler()
        sampler.reset(nowMs = 100L)
        sampler.recordBytes(StreamTransport.MJPEG, 5_000L)

        sampler.sample(
            cpuUsagePercent = 0f,
            currentCameraFps = 0f,
            currentMjpegFps = 0f,
            currentRtspFps = 0f,
            activeHttpStreams = 0,
            activeSseClients = 0,
            activeRtspConnections = 0,
            rtspPlayingSessions = 0,
            totalCameraClients = 0,
            totalLongLivedConnections = 0,
            batteryLevel = 0,
            isCharging = false,
            nowMs = 1_100L
        )

        sampler.reset(nowMs = 2_000L)
        val afterReset = sampler.sample(
            cpuUsagePercent = 1f,
            currentCameraFps = 0f,
            currentMjpegFps = 0f,
            currentRtspFps = 0f,
            activeHttpStreams = 0,
            activeSseClients = 0,
            activeRtspConnections = 0,
            rtspPlayingSessions = 0,
            totalCameraClients = 0,
            totalLongLivedConnections = 0,
            batteryLevel = 10,
            isCharging = false,
            nowMs = 3_000L
        )

        assertEquals(0L, afterReset.bandwidthBps)
        assertEquals(0L, afterReset.mjpegBandwidthBps)
        assertEquals(0L, afterReset.rtspBandwidthBps)
        assertFalse(afterReset.isCharging)
    }

    // Zero and negative byte counts must be silently ignored; they must not corrupt bandwidth.
    @Test
    fun recordBytesIgnoresZeroAndNegativeValues() {
        val sampler = RuntimeTelemetrySampler()
        sampler.reset(nowMs = 1_000L)
        sampler.recordBytes(StreamTransport.MJPEG, 0L)
        sampler.recordBytes(StreamTransport.MJPEG, -500L)
        sampler.recordBytes(StreamTransport.RTSP, -1L)

        val snapshot = sampler.sample(
            cpuUsagePercent = 0f,
            currentCameraFps = 0f,
            currentMjpegFps = 0f,
            currentRtspFps = 0f,
            activeHttpStreams = 0,
            activeSseClients = 0,
            activeRtspConnections = 0,
            rtspPlayingSessions = 0,
            totalCameraClients = 0,
            totalLongLivedConnections = 0,
            batteryLevel = 0,
            isCharging = false,
            nowMs = 2_000L
        )

        assertEquals(0L, snapshot.mjpegBandwidthBps)
        assertEquals(0L, snapshot.rtspBandwidthBps)
    }

    // Each sample() window must only count bytes recorded since the previous sample, not cumulative.
    @Test
    fun sequentialSamplesOnlyCountNewlyRecordedBytes() {
        val sampler = RuntimeTelemetrySampler()
        sampler.reset(nowMs = 0L)
        sampler.recordBytes(StreamTransport.MJPEG, 4_000L)
        val first = sampler.sample(
            cpuUsagePercent = 0f,
            currentCameraFps = 0f,
            currentMjpegFps = 0f,
            currentRtspFps = 0f,
            activeHttpStreams = 0,
            activeSseClients = 0,
            activeRtspConnections = 0,
            rtspPlayingSessions = 0,
            totalCameraClients = 0,
            totalLongLivedConnections = 0,
            batteryLevel = 0,
            isCharging = false,
            nowMs = 1_000L
        )
        sampler.recordBytes(StreamTransport.MJPEG, 1_000L)
        val second = sampler.sample(
            cpuUsagePercent = 0f,
            currentCameraFps = 0f,
            currentMjpegFps = 0f,
            currentRtspFps = 0f,
            activeHttpStreams = 0,
            activeSseClients = 0,
            activeRtspConnections = 0,
            rtspPlayingSessions = 0,
            totalCameraClients = 0,
            totalLongLivedConnections = 0,
            batteryLevel = 0,
            isCharging = false,
            nowMs = 2_000L
        )

        assertEquals(32_000L, first.mjpegBandwidthBps)
        assertEquals(8_000L, second.mjpegBandwidthBps)
    }

    // When the sample timestamp equals the reset timestamp (0ms elapsed), a 1ms floor must
    // prevent division by zero and still produce a finite bandwidth value.
    @Test
    fun elapsedMsFloorPreventsZeroDivisionWhenClockUnchanged() {
        val sampler = RuntimeTelemetrySampler()
        sampler.reset(nowMs = 5_000L)
        sampler.recordBytes(StreamTransport.MJPEG, 1_000L)
        val snapshot = sampler.sample(
            cpuUsagePercent = 0f,
            currentCameraFps = 0f,
            currentMjpegFps = 0f,
            currentRtspFps = 0f,
            activeHttpStreams = 0,
            activeSseClients = 0,
            activeRtspConnections = 0,
            rtspPlayingSessions = 0,
            totalCameraClients = 0,
            totalLongLivedConnections = 0,
            batteryLevel = 0,
            isCharging = false,
            nowMs = 5_000L
        )

        assertEquals(8_000_000L, snapshot.mjpegBandwidthBps)
    }
}
