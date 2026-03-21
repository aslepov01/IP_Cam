package com.ipcam

enum class ConnectionKind {
    MJPEG,
    SSE,
    RTSP
}

data class ConnectionLimits(
    val maxMjpegStreams: Int,
    val maxSseClients: Int,
    val maxRtspSessions: Int
) {
    fun normalized(): ConnectionLimits {
        return ConnectionLimits(
            maxMjpegStreams = maxMjpegStreams.coerceIn(MIN_LIMIT, MAX_LIMIT),
            maxSseClients = maxSseClients.coerceIn(MIN_LIMIT, MAX_LIMIT),
            maxRtspSessions = maxRtspSessions.coerceIn(MIN_LIMIT, MAX_LIMIT)
        )
    }

    companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 100

        val DEFAULT = ConnectionLimits(
            maxMjpegStreams = 32,
            maxSseClients = 16,
            maxRtspSessions = 8
        )

        fun fromLegacyMaxConnections(maxConnections: Int): ConnectionLimits {
            val normalizedLegacyMax = maxConnections.coerceIn(MIN_LIMIT, MAX_LIMIT)
            return ConnectionLimits(
                maxMjpegStreams = normalizedLegacyMax,
                maxSseClients = normalizedLegacyMax,
                maxRtspSessions = maxOf(1, normalizedLegacyMax / 4)
            ).normalized()
        }
    }
}

data class ConnectionSnapshot(
    val id: String,
    val kind: ConnectionKind,
    val state: String,
    val remoteAddr: String,
    val endpoint: String,
    val startTimeMs: Long,
    val active: Boolean = true
)
