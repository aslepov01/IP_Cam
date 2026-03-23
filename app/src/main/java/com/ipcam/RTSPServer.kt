package com.ipcam

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.ipcam.InMemoryLogBuffer
import kotlinx.coroutines.*
import java.io.*
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RTSP Server implementation for hardware-accelerated H.264 streaming
 * 
 * Implementation follows RTSP_RECOMMENDATION.md architecture:
 * - RTSP protocol handler (DESCRIBE, SETUP, PLAY, TEARDOWN)
 * - RTP packetizer for H.264 NAL units
 * - MediaCodec surface-input encoder integration
 * - Multi-client session management
 * 
 * Industry standard for IP cameras compatible with:
 * - VLC, FFmpeg, and all major media players
 * - Professional NVR software (ZoneMinder, Shinobi, Blue Iris, MotionEye)
 * - Lower latency than HLS (~500ms-1s vs 6-12s)
 * - No container format issues (streams raw H.264 over RTP)
 */
class RTSPServer(
    private val port: Int = 8554, // Standard RTSP port
    private var width: Int = 1920,
    private var height: Int = 1080,
    initialFps: Int = 30,
    initialBitrate: Int = calculateBitrate(width, height), // Dynamic based on resolution
    initialBitrateMode: String = "VBR",
    private val cameraService: CameraService? = null // Optional reference for FPS tracking
) {
    private enum class CodecConfigState {
        INVALID,
        WAITING,
        READY
    }

    private var serverSocket: ServerSocket? = null
    private val sessions = ConcurrentHashMap<String, RTSPSession>()
    private val sessionIdCounter = AtomicInteger(0)
    private val isRunning = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private val droppedFrameCount = AtomicLong(0)
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Sessions that currently hold the RTSP camera lease. The lease is acquired on
    // DESCRIBE/PLAY and released on PAUSE/TEARDOWN/disconnect so camera activation
    // stays symmetric even when clients disconnect mid-handshake.
    private val cameraLeaseSessions = LinkedHashSet<String>()
    private val cameraLeaseLock = Any()
    private val codecConfigLock = Any()
    private val streamTimelineLock = Any()
    
    // Synchronization lock for start/stop operations
    private val serverLock = Any()
    
    @Volatile private var encoderName: String = "unknown"
    @Volatile private var isHardwareEncoder: Boolean = false
    @Volatile private var lastError: String? = null
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var codecConfigState: CodecConfigState = CodecConfigState.INVALID
    @Volatile private var readyCodecGeneration: Long = 0L
    @Volatile private var encoderColorFormat: Int = -1
    @Volatile private var encoderColorFormatName: String = "unknown"
    
    // Encoder configuration (mutable for runtime changes)
    @Volatile private var targetFps: Int = initialFps
    @Volatile private var bitrate: Int = initialBitrate
    @Volatile private var bitrateModeName: String = normalizeBitrateMode(initialBitrateMode)
    
    // Frame timing control
    @Volatile private var streamStartTimeMs: Long = 0
    @Volatile private var encoderSessionGeneration: Long = 0L
    @Volatile private var lastRawPtsUs: Long = Long.MIN_VALUE
    @Volatile private var lastNormalizedRtpTimestamp: Long = Long.MIN_VALUE
    
    companion object {
        private const val TAG = "RTSPServer"
        private const val RTP_VERSION = 2
        private const val RTP_PT_H264 = 96 // Dynamic payload type for H.264
        
        /**
         * Calculate appropriate bitrate based on resolution
         * Uses industry-standard bitrate guidelines for H.264 streaming
         */
        fun calculateBitrate(width: Int, height: Int): Int {
            val pixels = width * height
            return when {
                // 4K: 2160p (3840x2160)
                pixels >= 3840 * 2160 -> 12_000_000  // 12 Mbps
                // 1440p (2560x1440)
                pixels >= 2560 * 1440 -> 8_000_000   // 8 Mbps
                // 1080p (1920x1080)
                pixels >= 1920 * 1080 -> 5_000_000   // 5 Mbps
                // 720p (1280x720)
                pixels >= 1280 * 720 -> 3_000_000    // 3 Mbps
                // 480p (854x480 or 640x480)
                pixels >= 640 * 480 -> 1_500_000     // 1.5 Mbps
                // Lower resolutions
                else -> 1_000_000                     // 1 Mbps
            }
        }
        
        /**
         * Check if hardware H.264 encoder is available
         */
        fun isHardwareEncoderAvailable(): Boolean {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) continue
                if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
                
                val isHardware = !codecInfo.name.contains("OMX.google", ignoreCase = true) &&
                                !codecInfo.name.contains("c2.android", ignoreCase = true)
                
                if (isHardware) {
                    Log.d(TAG, "Hardware H.264 encoder found: ${codecInfo.name}")
                    return true
                }
            }
            return false
        }

    }

    data class EncoderSession(
        val encoderName: String,
        val isHardware: Boolean,
        val colorFormat: Int,
        val colorFormatName: String,
        val targetFps: Int,
        val bitrate: Int,
        val bitrateMode: String
    )

    private data class CodecConfigSnapshot(
        val sps: ByteArray,
        val pps: ByteArray,
        val generation: Long
    )

    private fun normalizeBitrateMode(mode: String): String = mode.uppercase()

    private fun getReadyCodecConfig(): Pair<ByteArray, ByteArray>? {
        synchronized(codecConfigLock) {
            val currentSps = sps
            val currentPps = pps
            if (codecConfigState != CodecConfigState.READY || currentSps == null || currentPps == null) {
                return null
            }
            return currentSps to currentPps
        }
    }

    private fun getReadyCodecConfigSnapshot(): CodecConfigSnapshot? {
        synchronized(codecConfigLock) {
            val currentSps = sps
            val currentPps = pps
            if (codecConfigState != CodecConfigState.READY || currentSps == null || currentPps == null) {
                return null
            }
            return CodecConfigSnapshot(
                sps = currentSps.copyOf(),
                pps = currentPps.copyOf(),
                generation = readyCodecGeneration
            )
        }
    }

    private fun normalizeRtpTimestamp(rawPresentationTimeUs: Long): Long {
        synchronized(streamTimelineLock) {
            val minFrameDurationTicks = if (targetFps > 0) {
                maxOf(1L, 90_000L / targetFps.toLong())
            } else {
                3_000L
            }

            val normalizedTimestamp = if (lastNormalizedRtpTimestamp == Long.MIN_VALUE) {
                0L
            } else {
                val rawDeltaUs = if (lastRawPtsUs == Long.MIN_VALUE || rawPresentationTimeUs <= lastRawPtsUs) {
                    0L
                } else {
                    rawPresentationTimeUs - lastRawPtsUs
                }
                val rawDeltaTicks = if (rawDeltaUs > 0L) {
                    maxOf(1L, (rawDeltaUs * 90L) / 1000L)
                } else {
                    0L
                }
                lastNormalizedRtpTimestamp + maxOf(minFrameDurationTicks, rawDeltaTicks)
            }

            lastRawPtsUs = rawPresentationTimeUs
            lastNormalizedRtpTimestamp = normalizedTimestamp
            return normalizedTimestamp
        }
    }

    fun invalidateCodecConfig(reason: String) {
        synchronized(codecConfigLock) {
            sps = null
            pps = null
            codecConfigState = CodecConfigState.INVALID
            readyCodecGeneration = 0L
            frameCount.set(0)
            droppedFrameCount.set(0)
            streamStartTimeMs = 0
        }
        Log.d(TAG, "RTSP codec config invalidated ($reason)")
    }

    /**
     * RTSP session for a connected client
     */
    private inner class RTSPSession(
        val sessionId: String,
        val socket: Socket,
        @Volatile var state: SessionState = SessionState.INIT
    ) {
        val startTimeMs: Long = System.currentTimeMillis()
        @Volatile var playingStartedAtMs: Long = 0L
        
        var clientAddress: InetAddress? = null
        var clientRtpPort: Int = 0
        var clientRtcpPort: Int = 0
        var rtpSocket: DatagramSocket? = null
        var rtcpSocket: DatagramSocket? = null
        var serverRtpPort: Int = 0
        var serverRtcpPort: Int = 0
        var sequenceNumber = 0
        var timestamp: Long = 0
        @Volatile var lastCodecGenerationSent: Long = 0L
        val ssrc = (Math.random() * Int.MAX_VALUE).toInt()
        
        // TCP interleaved mode support
        var useTCP: Boolean = false
        var interleavedRtpChannel: Int = 0
        var interleavedRtcpChannel: Int = 1
        private val tcpOutputStream: OutputStream? get() = if (useTCP && !socket.isClosed) socket.getOutputStream() else null
        
        fun sendAccessUnit(nalUnits: List<ByteArray>, rtpTimestamp: Long, isKeyFrame: Boolean) {
            try {
                var totalBytesSent = 0
                
                if (useTCP) {
                    // TCP interleaved mode - send over RTSP socket
                    tcpOutputStream?.let { stream ->
                        var sentCount = 0
                        synchronized(stream) {
                            nalUnits.forEachIndexed { index, nalUnit ->
                                val isLastNalInAccessUnit = index == nalUnits.lastIndex
                                val rtpPackets = packetizeNALUnit(nalUnit, isLastNalInAccessUnit, rtpTimestamp)
                                rtpPackets.forEach { packet ->
                                    // RFC 2326 Section 10.12: Interleaved Binary Data
                                    // Format: $ <channel> <length_msb> <length_lsb> <data>
                                    val header = byteArrayOf(
                                        0x24, // '$' marker
                                        interleavedRtpChannel.toByte(),
                                        (packet.size shr 8).toByte(), // length MSB
                                        (packet.size and 0xFF).toByte() // length LSB
                                    )
                                    stream.write(header)
                                    stream.write(packet)
                                    totalBytesSent += header.size + packet.size
                                    sentCount++
                                }
                            }
                            stream.flush()
                        }
                        if (sequenceNumber == 0) {
                            Log.d(TAG, "TCP: Sent first ${sentCount} RTP packets for session $sessionId, keyframe=$isKeyFrame")
                        }
                    } ?: run {
                        Log.w(TAG, "TCP stream not available for session $sessionId")
                    }
                } else {
                    // UDP mode - send via DatagramSocket
                    if (clientAddress == null || clientRtpPort == 0) {
                        Log.w(TAG, "UDP: clientAddress or port not set for session $sessionId")
                        return
                    }
                    
                    val socket = rtpSocket
                    if (socket == null) {
                        Log.w(TAG, "UDP: rtpSocket is null for session $sessionId")
                        return
                    }
                    
                    if (socket.isClosed) {
                        Log.w(TAG, "UDP: socket closed for session $sessionId")
                        return
                    }
                    
                    var sentCount = 0
                    nalUnits.forEachIndexed { index, nalUnit ->
                        val isLastNalInAccessUnit = index == nalUnits.lastIndex
                        val rtpPackets = packetizeNALUnit(nalUnit, isLastNalInAccessUnit, rtpTimestamp)
                        rtpPackets.forEach { packet ->
                            val dgPacket = DatagramPacket(
                                packet,
                                packet.size,
                                clientAddress,
                                clientRtpPort
                            )
                            socket.send(dgPacket)
                            sentCount++
                            totalBytesSent += packet.size
                        }
                    }
                    
                    if (sequenceNumber < 5) {
                        Log.d(TAG, "UDP: Sent ${sentCount} RTP packets to ${clientAddress}:${clientRtpPort} for session $sessionId, keyframe=$isKeyFrame, seq=$sequenceNumber")
                    }
                }
                
                // Track aggregate RTSP bandwidth using actual bytes written
                if (totalBytesSent > 0) {
                    this@RTSPServer.cameraService?.recordStreamingBytes(StreamTransport.RTSP, totalBytesSent.toLong())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending RTP packet for session $sessionId", e)
            }
        }
        
        private fun packetizeNALUnit(
            nalUnit: ByteArray,
            isLastNalInAccessUnit: Boolean,
            rtpTimestamp: Long
        ): List<ByteArray> {
            val maxPayloadSize = 1400 // MTU - headers
            val packets = mutableListOf<ByteArray>()
            
            if (nalUnit.size <= maxPayloadSize) {
                // Single NAL unit mode
                val rtpPacket = createRTPPacket(
                    nalUnit,
                    marker = isLastNalInAccessUnit,
                    rtpTimestamp = rtpTimestamp
                )
                packets.add(rtpPacket)
            } else {
                // Fragmentation Unit (FU-A) mode for large NAL units
                val nalHeader = nalUnit[0]
                val nalType = (nalHeader.toInt() and 0x1F)
                val fuIndicator = ((nalHeader.toInt() and 0xE0) or 28).toByte() // FU-A type
                
                var offset = 1
                var isFirst = true
                var isLast = false
                
                while (offset < nalUnit.size) {
                    val fragmentSize = minOf(maxPayloadSize, nalUnit.size - offset)
                    isLast = (offset + fragmentSize >= nalUnit.size)
                    
                    val fuHeader = (
                        (if (isFirst) 0x80 else 0) or
                        (if (isLast) 0x40 else 0) or
                        nalType
                    ).toByte()
                    
                    val payload = ByteArray(2 + fragmentSize)
                    payload[0] = fuIndicator
                    payload[1] = fuHeader
                    System.arraycopy(nalUnit, offset, payload, 2, fragmentSize)
                    
                    val rtpPacket = createRTPPacket(
                        payload,
                        marker = isLast && isLastNalInAccessUnit,
                        rtpTimestamp = rtpTimestamp
                    )
                    packets.add(rtpPacket)
                    
                    offset += fragmentSize
                    isFirst = false
                }
            }
            
            return packets
        }
        
        private fun createRTPPacket(payload: ByteArray, marker: Boolean, rtpTimestamp: Long): ByteArray {
            val packet = ByteArray(12 + payload.size) // RTP header (12 bytes) + payload
            
            // Byte 0: Version (2), Padding (0), Extension (0), CSRC count (0)
            packet[0] = (RTP_VERSION shl 6).toByte()
            
            // Byte 1: Marker bit, Payload type
            packet[1] = ((if (marker) 1 shl 7 else 0) or RTP_PT_H264).toByte()
            
            // Bytes 2-3: Sequence number
            packet[2] = (sequenceNumber shr 8).toByte()
            packet[3] = (sequenceNumber and 0xFF).toByte()
            sequenceNumber++
            
            // Bytes 4-7: Timestamp (90kHz clock for video)
            val ts = rtpTimestamp.toInt()
            timestamp = rtpTimestamp
            packet[4] = (ts shr 24).toByte()
            packet[5] = (ts shr 16).toByte()
            packet[6] = (ts shr 8).toByte()
            packet[7] = (ts and 0xFF).toByte()
            
            // Bytes 8-11: SSRC
            packet[8] = (ssrc shr 24).toByte()
            packet[9] = (ssrc shr 16).toByte()
            packet[10] = (ssrc shr 8).toByte()
            packet[11] = (ssrc and 0xFF).toByte()
            
            // Payload
            System.arraycopy(payload, 0, packet, 12, payload.size)
            
            return packet
        }
    }
    
    enum class SessionState {
        INIT, READY, PLAYING
    }
    
    /**
     * Start RTSP server with retry logic for bind failures
     */
    fun start(): Boolean = synchronized(serverLock) {
        if (isRunning.get()) {
            Log.w(TAG, "RTSP server already running")
            return false
        }
        
        // Retry logic with exponential backoff for bind failures
        val maxAttempts = 3
        val retryDelays = listOf(1000L, 2000L, 4000L) // 1s, 2s, 4s
        
        for (attempt in 1..maxAttempts) {
            try {
                // Detect encoder and color format early for web UI display
                if (attempt == 1) {
                    detectEncoderCapabilities()
                }

                // Create server socket with SO_REUSEADDR to allow binding to recently closed sockets
                Log.d(TAG, "RTSP server bind attempt $attempt/$maxAttempts on port $port")
                serverSocket = createServerSocket(port)
                isRunning.set(true)
                
                // Start accepting connections
                serverJob = serverScope.launch {
                    acceptConnections()
                }
                
                Log.i(TAG, "RTSP server started on port $port")
                Log.i(TAG, "Encoder: $encoderName (hardware: $isHardwareEncoder), Color format: $encoderColorFormatName")
                Log.i(TAG, "Encoder session will start when an RTSP client activates the camera lease")
                
                return true
                
            } catch (e: BindException) {
                Log.w(TAG, "RTSP server bind attempt $attempt/$maxAttempts failed: ${e.message}")
                lastError = "Bind failed (attempt $attempt/$maxAttempts): ${e.message}"
                
                // If this was not the last attempt, wait before retry
                if (attempt < maxAttempts) {
                    val delay = retryDelays[attempt - 1]
                    Log.i(TAG, "Waiting ${delay}ms before retry...")
                    // Thread.sleep is intentional here (not in coroutine context)
                    // We want to block the calling thread to ensure sequential retry attempts
                    Thread.sleep(delay)
                } else {
                    Log.e(TAG, "Failed to start RTSP server after $maxAttempts attempts", e)
                    lastError = "Server start failed after $maxAttempts attempts: ${e.message}"
                    InMemoryLogBuffer.add("E", TAG, "RTSP server start failed after $maxAttempts attempts: ${e.message}")
                    cleanup()
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RTSP server on attempt $attempt", e)
                lastError = "Server start failed: ${e.message}"
                InMemoryLogBuffer.add("E", TAG, "RTSP server start failed on attempt $attempt: ${e.message}")
                cleanup()
                return false
            }
        }
        
        return false
    }
    
    /**
     * Create ServerSocket with proper options to handle bind failures
     */
    private fun createServerSocket(port: Int): ServerSocket {
        // Create unbound socket first so we can set options
        val socket = ServerSocket()
        
        // Enable SO_REUSEADDR to allow binding to recently closed sockets
        // This is crucial for preventing EADDRINUSE errors
        socket.reuseAddress = true
        
        // Set timeout for accept() operations (5 seconds)
        socket.soTimeout = 5000
        
        // Now bind to the port
        socket.bind(java.net.InetSocketAddress(port))
        
        Log.d(TAG, "ServerSocket created: reuseAddress=${socket.reuseAddress}, soTimeout=${socket.soTimeout}")
        return socket
    }
    
    /**
     * Detect encoder capabilities early for web UI display
     * This doesn't actually create the encoder, just queries capabilities
     */
    private fun detectEncoderCapabilities() {
        try {
            // Select best encoder (sets encoderName and isHardwareEncoder)
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            
            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) continue
                if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
                
                if (!codecInfo.name.contains("OMX.google", ignoreCase = true) &&
                    !codecInfo.name.contains("c2.android", ignoreCase = true)) {
                    encoderName = codecInfo.name
                    isHardwareEncoder = true
                    
                    // Surface input is the active encoder architecture.
                    val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    val colorFormats = capabilities.colorFormats.toSet()

                    encoderColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    encoderColorFormatName = getColorFormatName(encoderColorFormat)

                    if (!colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                        Log.w(TAG, "Encoder $encoderName does not report COLOR_FormatSurface support explicitly")
                    }
                    Log.i(TAG, "Selected active RTSP input format: $encoderColorFormatName")
                    return
                }
            }
            
            // Software encoder fallback
            Log.w(TAG, "Hardware encoder not found, will use software fallback")
            encoderName = "software"
            isHardwareEncoder = false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting encoder capabilities", e)
            encoderName = "detection failed"
            isHardwareEncoder = false
        }
    }
    
    /**
     * Get human-readable name for color format
     */
    private fun getColorFormatName(format: Int): String {
        return when (format) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> "COLOR_FormatSurface"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "COLOR_FormatYUV420Flexible"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "COLOR_FormatYUV420Planar (I420)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "COLOR_FormatYUV420SemiPlanar (NV12)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> "COLOR_FormatYUV420PackedPlanar"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> "COLOR_FormatYUV420PackedSemiPlanar (NV21)"
            0x7F420888 -> "COLOR_FormatYUV420Flexible (Android)"
            else -> "Unknown (0x${Integer.toHexString(format)})"
        }
    }
    
    /**
     * Accept incoming RTSP connections
     */
    private suspend fun acceptConnections() {
        while (isRunning.get()) {
            try {
                val clientSocket = serverSocket?.accept()
                if (clientSocket != null) {
                    serverScope.launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Expected - socket timeout allows periodic checking of isRunning
                // Not an error, just continue the loop
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting connection", e)
                }
            }
        }
    }
    
    /**
     * Handle RTSP client connection
     */
    private suspend fun handleClient(socket: Socket) {
        val sessionId = "session${sessionIdCounter.incrementAndGet()}"
        Log.d(TAG, "New RTSP client connected: $sessionId from ${socket.inetAddress}")
        
        val session = RTSPSession(sessionId, socket)
        sessions[sessionId] = session
        cameraService?.onLongLivedConnectionsChanged()
        
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            
            while (isRunning.get() && !socket.isClosed) {
                val requestLine = reader.readLine() ?: break
                if (requestLine.isEmpty()) continue
                
                val parts = requestLine.split(" ")
                if (parts.size < 3) continue
                
                val method = parts[0]
                val url = parts[1]
                
                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val headerParts = line!!.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        headers[headerParts[0].trim().lowercase()] = headerParts[1].trim()
                    }
                }
                
                // Handle RTSP methods
                when (method) {
                    "OPTIONS" -> handleOptions(writer, headers)
                    "DESCRIBE" -> handleDescribe(writer, session, headers)
                    "SETUP" -> handleSetup(writer, session, headers)
                    "PLAY" -> handlePlay(writer, session, headers)
                    "PAUSE" -> handlePause(writer, session, headers)
                    "TEARDOWN" -> handleTeardown(writer, session, headers)
                    else -> sendResponse(writer, "405 Method Not Allowed", headers["cseq"] ?: "0")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client $sessionId", e)
        } finally {
            releaseCameraLease(sessionId, "disconnect")
            try {
                session.rtpSocket?.close()
                session.rtcpSocket?.close()
            } catch (e: Exception) {
                Log.d(TAG, "Error closing RTP/RTCP sockets for $sessionId during disconnect", e)
            }
            sessions.remove(sessionId)
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
            Log.d(TAG, "Client disconnected: $sessionId")
            cameraService?.onLongLivedConnectionsChanged()
        }
    }
    
    private fun handleOptions(writer: BufferedWriter, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Public: DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    private fun acquireCameraLease(sessionId: String, trigger: String) {
        synchronized(cameraLeaseLock) {
            if (!cameraLeaseSessions.add(sessionId)) {
                Log.d(TAG, "RTSP camera lease already held by $sessionId ($trigger)")
                return
            }

            val leaseCount = cameraLeaseSessions.size
            if (leaseCount == 1) {
                Log.i(TAG, "Acquiring RTSP camera lease for $sessionId via $trigger")
                cameraService?.registerRtspConsumer()
            } else {
                Log.d(TAG, "RTSP camera lease acquired for $sessionId via $trigger (leases: $leaseCount)")
            }
        }
    }

    fun hasActiveCameraLease(): Boolean {
        synchronized(cameraLeaseLock) {
            return cameraLeaseSessions.isNotEmpty()
        }
    }

    private fun releaseCameraLease(sessionId: String, trigger: String) {
        synchronized(cameraLeaseLock) {
            if (!cameraLeaseSessions.remove(sessionId)) {
                return
            }

            val leaseCount = cameraLeaseSessions.size
            if (leaseCount == 0) {
                Log.i(TAG, "Releasing last RTSP camera lease from $sessionId via $trigger")
                cameraService?.unregisterRtspConsumer()
            } else {
                Log.d(TAG, "RTSP camera lease released from $sessionId via $trigger (leases: $leaseCount)")
            }
        }
    }

    private fun releaseAllCameraLeases(trigger: String) {
        synchronized(cameraLeaseLock) {
            val leaseCount = cameraLeaseSessions.size
            if (leaseCount == 0) {
                return
            }

            cameraLeaseSessions.clear()
            Log.i(TAG, "Releasing all RTSP camera leases via $trigger (leases: $leaseCount)")
            cameraService?.unregisterRtspConsumer()
        }
    }

    private suspend fun handleDescribe(writer: BufferedWriter, session: RTSPSession, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        
        Log.d(TAG, "DESCRIBE request received")
        
        // Activate camera early so SPS/PPS can be generated even before PLAY.
        acquireCameraLease(session.sessionId, "DESCRIBE")
        
        Log.d(TAG, "Waiting for SPS/PPS...")
        
        // Wait briefly for SPS/PPS to be available (encoder needs to start first)
        var retries = 0
        val maxRetries = 150 // 150 * 100ms = 15 seconds max wait
        while (getReadyCodecConfig() == null && retries < maxRetries) {
            if (retries % 10 == 0) {
                Log.d(
                    TAG,
                    "Waiting for SPS/PPS... attempt ${retries}/${maxRetries}, " +
                        "frames=${frameCount.get()}, leases=${cameraLeaseSessions.size}, state=$codecConfigState"
                )
            }
            kotlinx.coroutines.delay(100)
            retries++
        }
        
        val codecConfig = getReadyCodecConfig()
        if (codecConfig == null) {
            Log.w(
                TAG,
                "SPS/PPS not available after ${retries * 100}ms. " +
                    "Frames=${frameCount.get()}, leases=${cameraLeaseSessions.size}, state=$codecConfigState"
            )
            writer.write("RTSP/1.0 500 Internal Server Error\r\n")
            writer.write("CSeq: $cseq\r\n")
            writer.write("Content-Type: text/plain\r\n")
            writer.write("\r\n")
            writer.write(
                "Encoder session not ready. State=$codecConfigState, " +
                    "SPS=${sps != null}, PPS=${pps != null}, Frames=${frameCount.get()}. " +
                    "Please ensure camera is streaming.\r\n"
            )
            writer.flush()
            return
        }
        
        Log.i(TAG, "SPS/PPS available after ${retries * 100}ms, generating SDP")
        
        // Generate SDP (Session Description Protocol)
        val (readySps, readyPps) = codecConfig
        val spsBase64 = android.util.Base64.encodeToString(readySps, android.util.Base64.NO_WRAP)
        val ppsBase64 = android.util.Base64.encodeToString(readyPps, android.util.Base64.NO_WRAP)
        
        val sdp = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=IP_Cam RTSP Stream
            c=IN IP4 0.0.0.0
            t=0 0
            a=tool:IP_Cam RTSP Server
            a=type:broadcast
            a=control:*
            a=range:npt=0-
            m=video 0 RTP/AVP $RTP_PT_H264
            a=rtpmap:$RTP_PT_H264 H264/90000
            a=fmtp:$RTP_PT_H264 packetization-mode=1;profile-level-id=42C01F;sprop-parameter-sets=$spsBase64,$ppsBase64
            a=control:track0
        """.trimIndent()
        
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Content-Type: application/sdp\r\n")
        writer.write("Content-Length: ${sdp.length}\r\n")
        writer.write("\r\n")
        writer.write(sdp)
        writer.flush()
    }
    
    private fun handleSetup(writer: BufferedWriter, session: RTSPSession, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        val transport = headers["transport"] ?: ""
        
        // Parse transport header for client ports
        // Example UDP: RTP/AVP;unicast;client_port=5000-5001
        // Example TCP: RTP/AVP/TCP;unicast;interleaved=0-1
        
        try {
            if (transport.contains("TCP", ignoreCase = true) || transport.contains("interleaved")) {
                // TCP interleaved mode
                val interleavedPattern = Regex("interleaved=(\\d+)-(\\d+)")
                val match = interleavedPattern.find(transport)
                
                if (match != null) {
                    session.useTCP = true
                    session.interleavedRtpChannel = match.groupValues[1].toInt()
                    session.interleavedRtcpChannel = match.groupValues[2].toInt()
                    session.state = SessionState.READY
                    
                    val responseTransport = "RTP/AVP/TCP;unicast;interleaved=${session.interleavedRtpChannel}-${session.interleavedRtcpChannel}"
                    
                    Log.d(TAG, "SETUP TCP transport for session ${session.sessionId}: interleaved=${session.interleavedRtpChannel}-${session.interleavedRtcpChannel}")
                    
                    writer.write("RTSP/1.0 200 OK\r\n")
                    writer.write("CSeq: $cseq\r\n")
                    writer.write("Session: ${session.sessionId}\r\n")
                    writer.write("Transport: $responseTransport\r\n")
                    writer.write("\r\n")
                    writer.flush()
                } else {
                    // TCP requested but no interleaved channels specified, use defaults
                    session.useTCP = true
                    session.interleavedRtpChannel = 0
                    session.interleavedRtcpChannel = 1
                    session.state = SessionState.READY
                    
                    val responseTransport = "RTP/AVP/TCP;unicast;interleaved=0-1"
                    
                    Log.d(TAG, "SETUP TCP transport for session ${session.sessionId}: interleaved=0-1 (default)")
                    
                    writer.write("RTSP/1.0 200 OK\r\n")
                    writer.write("CSeq: $cseq\r\n")
                    writer.write("Session: ${session.sessionId}\r\n")
                    writer.write("Transport: $responseTransport\r\n")
                    writer.write("\r\n")
                    writer.flush()
                }
                return
            }
            
            // Parse UDP client ports
            val portPattern = Regex("client_port=(\\d+)-(\\d+)")
            val match = portPattern.find(transport)
            if (match != null) {
                session.useTCP = false
                session.clientRtpPort = match.groupValues[1].toInt()
                session.clientRtcpPort = match.groupValues[2].toInt()
                session.clientAddress = session.socket.inetAddress
                
                // Allocate server RTP/RTCP ports
                session.rtpSocket = DatagramSocket()
                session.serverRtpPort = session.rtpSocket!!.localPort
                session.rtcpSocket = DatagramSocket()
                session.serverRtcpPort = session.rtcpSocket!!.localPort
                
                session.state = SessionState.READY
                
                Log.d(TAG, "SETUP UDP transport for session ${session.sessionId}: client=${session.clientRtpPort}-${session.clientRtcpPort}, server=${session.serverRtpPort}-${session.serverRtcpPort}")
                
                val responseTransport = "RTP/AVP;unicast;client_port=${session.clientRtpPort}-${session.clientRtcpPort};" +
                                      "server_port=${session.serverRtpPort}-${session.serverRtcpPort}"
                
                writer.write("RTSP/1.0 200 OK\r\n")
                writer.write("CSeq: $cseq\r\n")
                writer.write("Session: ${session.sessionId}\r\n")
                writer.write("Transport: $responseTransport\r\n")
                writer.write("\r\n")
                writer.flush()
            } else {
                writer.write("RTSP/1.0 400 Bad Request\r\n")
                writer.write("CSeq: $cseq\r\n")
                writer.write("\r\n")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SETUP", e)
            writer.write("RTSP/1.0 500 Internal Server Error\r\n")
            writer.write("CSeq: $cseq\r\n")
            writer.write("\r\n")
            writer.flush()
        }
    }
    
    private fun handlePlay(writer: BufferedWriter, session: RTSPSession, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        
        acquireCameraLease(session.sessionId, "PLAY")
        if (session.state != SessionState.PLAYING) {
            session.playingStartedAtMs = System.currentTimeMillis()
        }
        session.state = SessionState.PLAYING
        enforceRtspPlayingSessionLimit(session)
        cameraService?.onLongLivedConnectionsChanged()
        Log.d(TAG, "Client ${session.sessionId} started playing")
        
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Session: ${session.sessionId}\r\n")
        writer.write("RTP-Info: url=track0;seq=0;rtptime=0\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    private fun handleTeardown(writer: BufferedWriter, session: RTSPSession, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        
        session.state = SessionState.INIT
        session.playingStartedAtMs = 0L
        releaseCameraLease(session.sessionId, "TEARDOWN")
        cameraService?.onLongLivedConnectionsChanged()
        
        // Close RTP/RTCP sockets
        try {
            session.rtpSocket?.close()
            session.rtcpSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session sockets", e)
        }
        
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Session: ${session.sessionId}\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    private fun handlePause(writer: BufferedWriter, session: RTSPSession, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        
        session.state = SessionState.READY
        session.playingStartedAtMs = 0L
        releaseCameraLease(session.sessionId, "PAUSE")
        cameraService?.onLongLivedConnectionsChanged()
        
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Session: ${session.sessionId}\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    private fun sendResponse(writer: BufferedWriter, status: String, cseq: String) {
        writer.write("RTSP/1.0 $status\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    /**
     * Update codec configuration (SPS/PPS) from pre-encoded H.264 stream
     * Called by H264PreviewEncoder when codec config is available
     */
    fun updateCodecConfig(configData: ByteArray) {
        // Parse SPS and PPS from codec config
        val nalUnits = parseNALUnitsFromBuffer(configData)
        
        synchronized(codecConfigLock) {
            nalUnits.forEach { nal ->
                if (nal.isEmpty()) return@forEach
                val nalType = nal[0].toInt() and 0x1F
                when (nalType) {
                    7 -> {
                        // SPS (Sequence Parameter Set)
                        sps = nal
                        Log.i(TAG, "SPS updated from pre-encoded stream: ${nal.size} bytes")
                    }
                    8 -> {
                        // PPS (Picture Parameter Set)
                        pps = nal
                        Log.i(TAG, "PPS updated from pre-encoded stream: ${nal.size} bytes")
                    }
                }
            }

            if (sps != null && pps != null) {
                codecConfigState = CodecConfigState.READY
                readyCodecGeneration = encoderSessionGeneration
            }
        }
    }
    
    /**
     * Send pre-encoded H.264 frame to all RTSP clients
     * Called by H264PreviewEncoder with encoded NAL units
     */
    fun sendH264Frame(
        nalUnitData: ByteArray,
        presentationTimeUs: Long,
        isKeyFrame: Boolean
    ) {
        if (sessions.isEmpty()) return
        
        // Parse NAL units from frame
        val nalUnits = parseNALUnitsFromBuffer(nalUnitData).filter { it.isNotEmpty() }
        if (nalUnits.isEmpty()) return
        val normalizedRtpTimestamp = normalizeRtpTimestamp(presentationTimeUs)
        val codecConfigSnapshot = if (isKeyFrame) getReadyCodecConfigSnapshot() else null
        
        if (frameCount.get() == 0L) {
            streamStartTimeMs = System.currentTimeMillis()
        }
        frameCount.incrementAndGet()
        
        // Track FPS
        cameraService?.recordRtspFrameEncoded()
        
        sessions.values.forEach { session ->
            if (session.state != SessionState.PLAYING) {
                return@forEach
            }

            val accessUnit = if (
                isKeyFrame &&
                codecConfigSnapshot != null &&
                session.lastCodecGenerationSent < codecConfigSnapshot.generation
            ) {
                session.lastCodecGenerationSent = codecConfigSnapshot.generation
                Log.d(
                    TAG,
                    "Injecting SPS/PPS for session ${session.sessionId} before keyframe " +
                        "(generation=${codecConfigSnapshot.generation})"
                )
                listOf(codecConfigSnapshot.sps, codecConfigSnapshot.pps) + nalUnits
            } else {
                nalUnits
            }

            session.sendAccessUnit(accessUnit, normalizedRtpTimestamp, isKeyFrame)
        }
    }
    
    /**
     * Parse NAL units from byte array (handles Annex B format with start codes)
     * Used for pre-encoded H.264 streams from H264PreviewEncoder
     */
    private fun parseNALUnitsFromBuffer(data: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var offset = 0
        
        while (offset < data.size) {
            // Check for start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
            var startCodeLength = 0
            if (offset + 3 < data.size && 
                data[offset] == 0.toByte() && 
                data[offset + 1] == 0.toByte() && 
                data[offset + 2] == 0.toByte() && 
                data[offset + 3] == 1.toByte()) {
                startCodeLength = 4
            } else if (offset + 2 < data.size && 
                       data[offset] == 0.toByte() && 
                       data[offset + 1] == 0.toByte() && 
                       data[offset + 2] == 1.toByte()) {
                startCodeLength = 3
            }
            
            if (startCodeLength > 0) {
                // Find next start code
                var nextOffset = offset + startCodeLength
                // Safe bounds check: ensure we can read 4-byte start code
                while (nextOffset <= data.size - 4) {
                    if (data[nextOffset] == 0.toByte() && 
                        data[nextOffset + 1] == 0.toByte() && 
                        data[nextOffset + 2] == 0.toByte() && 
                        data[nextOffset + 3] == 1.toByte()) {
                        break
                    }
                    // Check for 3-byte start code only if 4-byte didn't match
                    if (nextOffset <= data.size - 3 &&
                        data[nextOffset] == 0.toByte() && 
                        data[nextOffset + 1] == 0.toByte() && 
                        data[nextOffset + 2] == 1.toByte()) {
                        break
                    }
                    nextOffset++
                }
                
                // If we stopped before data.size, we might have partial start code at end
                // Check remaining bytes for 3-byte start code
                if (nextOffset == data.size - 3) {
                    if (data[nextOffset] == 0.toByte() && 
                        data[nextOffset + 1] == 0.toByte() && 
                        data[nextOffset + 2] == 1.toByte()) {
                        // Found 3-byte start code at end
                        // nextOffset stays the same (points to start code)
                    } else {
                        // No start code, include rest of data
                        nextOffset = data.size
                    }
                } else if (nextOffset > data.size - 3) {
                    // No room for start code, include rest of data
                    nextOffset = data.size
                }
                
                // Extract NAL unit (without start code)
                val nalSize = nextOffset - (offset + startCodeLength)
                if (nalSize > 0) {
                    val nalUnit = data.copyOfRange(offset + startCodeLength, nextOffset)
                    nalUnits.add(nalUnit)
                }
                
                offset = nextOffset
            } else {
                // No start code found - might be a single NAL unit without start code
                if (offset == 0 && nalUnits.isEmpty()) {
                    // Entire buffer is a single NAL unit
                    nalUnits.add(data)
                }
                break
            }
        }
        
        return nalUnits
    }
    
    /**
     * Stop RTSP server with proper cleanup
     */
    fun stop() {
        synchronized(serverLock) {
            if (!isRunning.get()) return@synchronized
            
            isRunning.set(false)
            
            releaseAllCameraLeases("server stop")
            
            try {
                // Close all sessions
                sessions.values.forEach { session ->
                    try {
                        session.socket.close()
                        session.rtpSocket?.close()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                sessions.clear()
                cameraService?.onLongLivedConnectionsChanged()
                
                // Stop server job first
                serverJob?.cancel()
                serverJob = null
                
                // Close server socket with proper cleanup
                serverSocket?.let { socket ->
                    try {
                        // Close the socket immediately
                        socket.close()
                        Log.d(TAG, "ServerSocket closed")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing ServerSocket", e)
                    }
                }
                serverSocket = null
                
                // Brief delay to allow socket cleanup at OS level
                // This helps prevent EADDRINUSE on rapid restart
                // Thread.sleep is intentional here (not in coroutine context)
                // We want to block to ensure proper socket cleanup before returning
                Thread.sleep(100)
                
                Log.i(TAG, "RTSP server stopped")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping server", e)
                lastError = "Server stop failed: ${e.message}"
            } finally {
                cleanup()
            }
        }
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        invalidateCodecConfig("server cleanup")
    }
    
    /**
     * Check if server is alive (accepting connections).
     */
    fun isAlive(): Boolean = isRunning.get()

    fun getConnectionSnapshots(): List<ConnectionSnapshot> {
        return sessions.values
            .map { session ->
                ConnectionSnapshot(
                    id = "rtsp:${session.sessionId}",
                    kind = ConnectionKind.RTSP,
                    state = session.state.name,
                    remoteAddr = session.socket.inetAddress?.hostAddress ?: "unknown",
                    endpoint = "rtsp://:8554/stream",
                    startTimeMs = session.startTimeMs
                )
            }
            .sortedBy { it.startTimeMs }
    }

    fun closeConnection(connectionId: String): Boolean {
        if (!connectionId.startsWith("rtsp:")) {
            return false
        }

        val sessionId = connectionId.removePrefix("rtsp:")
        val session = sessions[sessionId] ?: return false
        return try {
            session.socket.close()
            true
        } catch (e: Exception) {
            Log.d(TAG, "Error closing RTSP session $sessionId", e)
            false
        }
    }

    private fun enforceRtspPlayingSessionLimit(currentSession: RTSPSession) {
        val maxSessions = cameraService?.getConnectionLimits()?.maxRtspSessions
            ?: ConnectionLimits.DEFAULT.maxRtspSessions

        while (sessions.values.count { it.state == SessionState.PLAYING } > maxSessions) {
            val oldestPlayingSession = sessions.values
                .filter { it.sessionId != currentSession.sessionId && it.state == SessionState.PLAYING }
                .minByOrNull { session ->
                    if (session.playingStartedAtMs > 0L) session.playingStartedAtMs else session.startTimeMs
                }
                ?: break

            Log.w(
                TAG,
                "RTSP playing-session limit ($maxSessions) reached, closing oldest playing session " +
                    "${oldestPlayingSession.sessionId} (${oldestPlayingSession.socket.inetAddress}) " +
                    "to admit ${currentSession.sessionId}"
            )
            try {
                oldestPlayingSession.socket.close()
            } catch (e: Exception) {
                Log.d(TAG, "Error closing evicted RTSP session ${oldestPlayingSession.sessionId}", e)
                break
            }
        }
    }
    
    /**
     * Get server metrics
     */
    fun getMetrics(): ServerMetrics {
        // Calculate encoded FPS (successful encodes) based on time elapsed
        // Note: This shows encoding rate, not input frame rate
        // Input frame rate would be higher when including dropped frames
        val encodedFps = if (streamStartTimeMs > 0 && frameCount.get() > 0) {
            val elapsedSec = (System.currentTimeMillis() - streamStartTimeMs) / 1000.0
            if (elapsedSec > 0) {
                (frameCount.get() / elapsedSec).toFloat()
            } else {
                0f
            }
        } else {
            0f
        }
        
        return ServerMetrics(
            encoderName = encoderName,
            isHardware = isHardwareEncoder,
            colorFormat = encoderColorFormatName,
            colorFormatHex = if (encoderColorFormat != -1) "0x${Integer.toHexString(encoderColorFormat)}" else "unknown",
            resolution = "${width}x${height}",
            bitrateMbps = bitrate / 1_000_000f,
            bitrateMode = bitrateModeName,
            activeSessions = sessions.size,
            playingSessions = sessions.values.count { it.state == SessionState.PLAYING },
            maxSessions = cameraService?.getConnectionLimits()?.maxRtspSessions
                ?: ConnectionLimits.DEFAULT.maxRtspSessions,
            framesEncoded = frameCount.get(),
            droppedFrames = droppedFrameCount.get(),
            targetFps = targetFps,
            encodedFps = encodedFps, // Rate of successful encodes (excludes drops)
            lastError = lastError
        )
    }

    /**
     * Update encoder session metadata for the active surface-input encoder.
     */
    fun beginEncoderSession(session: EncoderSession) {
        encoderName = session.encoderName
        isHardwareEncoder = session.isHardware
        encoderColorFormat = session.colorFormat
        encoderColorFormatName = session.colorFormatName
        targetFps = session.targetFps
        bitrate = session.bitrate
        bitrateModeName = normalizeBitrateMode(session.bitrateMode)
        synchronized(streamTimelineLock) {
            encoderSessionGeneration += 1L
            lastRawPtsUs = Long.MIN_VALUE
        }
        synchronized(codecConfigLock) {
            frameCount.set(0)
            droppedFrameCount.set(0)
            streamStartTimeMs = 0
            sps = null
            pps = null
            codecConfigState = CodecConfigState.WAITING
            readyCodecGeneration = 0L
        }
        lastError = null
    }

    fun updateEncoderConfig(
        targetFps: Int = this.targetFps,
        bitrate: Int = this.bitrate,
        bitrateMode: String = bitrateModeName
    ) {
        if (targetFps > 0) {
            this.targetFps = targetFps
        }
        if (bitrate > 0) {
            this.bitrate = bitrate
        }
        this.bitrateModeName = normalizeBitrateMode(bitrateMode)
    }

    fun reportEncoderError(message: String) {
        lastError = message
    }
    
    data class ServerMetrics(
        val encoderName: String,
        val isHardware: Boolean,
        val colorFormat: String,
        val colorFormatHex: String,
        val resolution: String,
        val bitrateMbps: Float,
        val bitrateMode: String,
        val activeSessions: Int,
        val playingSessions: Int,
        /** Maximum concurrent RTSP playing sessions allowed. */
        val maxSessions: Int,
        val framesEncoded: Long,
        val droppedFrames: Long,
        val targetFps: Int,
        val encodedFps: Float, // Actual encoding rate (successful frames/sec)
        val lastError: String?
    )
}
