package com.ipcam

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLimitsTest {

    // Verifies normalization of explicit limits: values outside the supported range must be clamped
    // to the minimum and maximum bounds used by the runtime.
    @Test
    fun normalizedClampsAllValuesToSupportedRange() {
        val normalized = ConnectionLimits(
            maxMjpegStreams = -5,
            maxSseClients = 150,
            maxRtspSessions = 0
        ).normalized()

        assertEquals(1, normalized.maxMjpegStreams)
        assertEquals(100, normalized.maxSseClients)
        assertEquals(1, normalized.maxRtspSessions)
    }

    // Verifies migration from the legacy single max-connections setting: MJPEG and SSE keep the
    // legacy value, while RTSP is derived as one quarter of that limit.
    @Test
    fun legacyConversionKeepsRtspSessionsAtQuarterOfLegacyLimit() {
        val converted = ConnectionLimits.fromLegacyMaxConnections(20)

        assertEquals(20, converted.maxMjpegStreams)
        assertEquals(20, converted.maxSseClients)
        assertEquals(5, converted.maxRtspSessions)
    }

    // Verifies the legacy conversion floor: even the smallest historical max-connections value
    // must still produce at least one RTSP session instead of dropping the transport to zero.
    @Test
    fun legacyConversionStillProducesAtLeastOneRtspSession() {
        val converted = ConnectionLimits.fromLegacyMaxConnections(1)

        assertEquals(1, converted.maxMjpegStreams)
        assertEquals(1, converted.maxSseClients)
        assertEquals(1, converted.maxRtspSessions)
    }
}
