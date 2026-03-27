package com.ipcam.testsupport

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.max

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray
) {
    fun bodyText(): String = body.toString(Charsets.UTF_8)

    fun jsonObject(): JSONObject = JSONObject(bodyText())
}

class MjpegStreamClient(
    host: String,
    port: Int,
    path: String = "/stream"
) : Closeable {
    private val connection = openRawHttpGet(
        host = host,
        port = port,
        path = path,
        readTimeoutMs = 2_000,
        keepAlive = true
    )
    private val input = connection.input

    val statusCode: Int = connection.statusCode
    val contentType: String = connection.headerValue("Content-Type").orEmpty()

    fun awaitFirstJpegFrame(timeoutMs: Long = 20_000L): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            try {
                return readNextJpegFrameByMarkerScan()
            } catch (_: SocketTimeoutException) {
                // Keep waiting until timeout expires.
            } catch (_: java.io.EOFException) {
                throw AssertionError("MJPEG stream closed before delivering a frame")
            }
        }

        throw AssertionError("Timed out waiting for the first JPEG frame from MJPEG stream")
    }

    fun awaitDisconnected(timeoutMs: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(1_024)

        while (System.currentTimeMillis() < deadline) {
            try {
                val read = input.read(buffer)
                if (read == -1) {
                    return true
                }
            } catch (_: SocketTimeoutException) {
                // Keep polling.
            } catch (_: IOException) {
                return true
            }
        }

        return false
    }

    override fun close() {
        connection.close()
    }

    private fun readNextJpegFrameByMarkerScan(): ByteArray {
        val chunk = ByteArray(8_192)
        val captured = ByteArrayOutputStream()

        while (true) {
            val read = input.read(chunk)
            if (read == -1) {
                throw java.io.EOFException("MJPEG stream closed before delivering a frame")
            }
            captured.write(chunk, 0, read)
            val bytes = captured.toByteArray()
            val start = bytes.indexOfSequence(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            if (start >= 0) {
                val end = bytes.indexOfSequence(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), start + 2)
                if (end >= 0) {
                    return bytes.copyOfRange(start, end + 2)
                }
            }
            trimIfNeeded(captured)
        }
    }

    private fun trimIfNeeded(captured: ByteArrayOutputStream) {
        val current = captured.toByteArray()
        if (current.size <= MAX_CAPTURE_BYTES) {
            return
        }

        val keepFrom = max(0, current.size - MAX_CAPTURE_BYTES / 2)
        captured.reset()
        captured.write(current, keepFrom, current.size - keepFrom)
    }

    companion object {
        private const val MAX_CAPTURE_BYTES = 16 * 1024 * 1024
    }
}

class SseClient(
    host: String,
    port: Int,
    path: String = "/events"
) : Closeable {
    private val connection = openRawHttpGet(
        host = host,
        port = port,
        path = path,
        readTimeoutMs = 2_000,
        keepAlive = true
    )
    private val input = connection.input
    private val lineAccumulator = ByteArrayOutputStream()

    val statusCode: Int = connection.statusCode
    val contentType: String = connection.headerValue("Content-Type").orEmpty()

    fun awaitInitialEvents(
        requiredEvents: Set<String> = setOf("state", "metrics"),
        timeoutMs: Long = 15_000L
    ): Set<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        val observed = linkedSetOf<String>()

        while (System.currentTimeMillis() < deadline && !observed.containsAll(requiredEvents)) {
            try {
                val line = readSseLine() ?: break
                if (line.startsWith("event:")) {
                    observed += line.removePrefix("event:").trim()
                }
            } catch (_: SocketTimeoutException) {
                // Keep waiting.
            }
        }

        return observed
    }

    fun awaitInitialEventPayloads(
        requiredEvents: Set<String> = setOf("state", "metrics"),
        timeoutMs: Long = 15_000L
    ): Map<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        val observed = linkedMapOf<String, String>()
        var currentEvent: String? = null
        val currentData = StringBuilder()

        fun commitCurrentEvent() {
            val event = currentEvent ?: return
            observed.putIfAbsent(event, currentData.toString())
            currentEvent = null
            currentData.setLength(0)
        }

        while (System.currentTimeMillis() < deadline && !observed.keys.containsAll(requiredEvents)) {
            try {
                val line = readSseLine() ?: break
                when {
                    line.startsWith("event:") -> {
                        commitCurrentEvent()
                        currentEvent = line.removePrefix("event:").trim()
                    }

                    line.startsWith("data:") -> {
                        if (currentData.isNotEmpty()) {
                            currentData.append('\n')
                        }
                        currentData.append(line.removePrefix("data:").trimStart())
                    }

                    line.isEmpty() -> commitCurrentEvent()
                }
            } catch (_: SocketTimeoutException) {
                // Keep waiting.
            }
        }

        commitCurrentEvent()
        return observed
    }

    fun awaitEventOfType(
        eventType: String,
        timeoutMs: Long = 10_000L
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var currentEvent: String? = null
        val currentData = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            try {
                val line = readSseLine() ?: return null
                when {
                    line.startsWith("event:") -> {
                        currentEvent = line.removePrefix("event:").trim()
                        currentData.setLength(0)
                    }
                    line.startsWith("data:") -> {
                        if (currentData.isNotEmpty()) currentData.append('\n')
                        currentData.append(line.removePrefix("data:").trimStart())
                    }
                    line.isEmpty() -> {
                        if (currentEvent == eventType && currentData.isNotEmpty()) {
                            return currentData.toString()
                        }
                        currentEvent = null
                        currentData.setLength(0)
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Keep waiting.
            }
        }
        return null
    }

    fun awaitDisconnected(timeoutMs: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            try {
                val line = readSseLine()
                if (line == null) {
                    return true
                }
            } catch (_: SocketTimeoutException) {
                // Keep polling.
            } catch (_: IOException) {
                return true
            }
        }

        return false
    }

    override fun close() {
        connection.close()
    }

    /**
     * Reads a single line from the SSE stream, preserving partial data across
     * SocketTimeoutExceptions. Unlike BufferedReader.readLine(), this method keeps
     * bytes accumulated before a timeout so they are not lost on retry.
     */
    private fun readSseLine(): String? {
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (lineAccumulator.size() > 0) flushLine() else null
            }
            if (b == '\n'.code) {
                return flushLine()
            }
            if (b != '\r'.code) {
                lineAccumulator.write(b)
            }
        }
    }

    private fun flushLine(): String {
        val result = lineAccumulator.toByteArray().toString(Charsets.UTF_8)
        lineAccumulator.reset()
        return result
    }
}

data class RtspResponse(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String>,
    val body: String
)

class RtspTcpClient(
    private val host: String,
    private val port: Int = 8554
) : Closeable {
    private val socket = connectRtspSocket(host, port)
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private var cSeq = 1
    private var sessionId: String? = null
    private val streamUrl = "rtsp://$host:$port/stream"

    fun options(): RtspResponse = sendRequest("OPTIONS", streamUrl)

    fun describe(): RtspResponse =
        sendRequest(
            method = "DESCRIBE",
            url = streamUrl,
            extraHeaders = listOf("Accept: application/sdp")
        )

    fun setupTcp(interleavedStartChannel: Int = 0): RtspResponse {
        val channelEnd = interleavedStartChannel + 1
        return sendRequest(
            method = "SETUP",
            url = "$streamUrl/track0",
            extraHeaders = listOf(
                "Transport: RTP/AVP/TCP;unicast;interleaved=$interleavedStartChannel-$channelEnd"
            )
        )
    }

    fun play(): RtspResponse = sendRequest("PLAY", streamUrl)

    fun pause(): RtspResponse = sendRequest("PAUSE", streamUrl)

    fun teardown(): RtspResponse = sendRequest("TEARDOWN", streamUrl)

    fun awaitInterleavedRtpPacket(timeoutMs: Long = 20_000L): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            try {
                val marker = input.read()
                if (marker == -1) {
                    throw AssertionError("RTSP socket closed before any RTP packet was received")
                }
                if (marker != INTERLEAVED_MARKER) {
                    continue
                }

                val channel = input.read()
                val lengthMsb = input.read()
                val lengthLsb = input.read()
                if (channel == -1 || lengthMsb == -1 || lengthLsb == -1) {
                    throw AssertionError("RTSP interleaved header was truncated")
                }

                val payloadLength = (lengthMsb shl 8) or lengthLsb
                return readExact(payloadLength)
            } catch (_: SocketTimeoutException) {
                // Keep waiting.
            }
        }

        throw AssertionError("Timed out waiting for interleaved RTP packet from RTSP server")
    }

    fun awaitDisconnected(timeoutMs: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            try {
                val read = input.read()
                if (read == -1) {
                    return true
                }
            } catch (_: SocketTimeoutException) {
                // Keep polling.
            } catch (_: IOException) {
                return true
            }
        }

        return false
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun sendRequest(
        method: String,
        url: String,
        extraHeaders: List<String> = emptyList()
    ): RtspResponse {
        val requestBuilder = StringBuilder()
        requestBuilder.append("$method $url RTSP/1.0\r\n")
        requestBuilder.append("CSeq: ${cSeq++}\r\n")
        sessionId?.let { requestBuilder.append("Session: $it\r\n") }
        extraHeaders.forEach { requestBuilder.append(it).append("\r\n") }
        requestBuilder.append("\r\n")

        output.write(requestBuilder.toString().toByteArray(Charsets.UTF_8))
        output.flush()

        val response = readResponse()
        response.headers["session"]?.let { rawSession ->
            sessionId = rawSession.substringBefore(';').trim()
        }
        return response
    }

    private fun readResponse(): RtspResponse {
        val statusLine = readRtspStatusLine()
        val headers = linkedMapOf<String, String>()

        while (true) {
            val line = readAsciiLine()
                ?: throw AssertionError("Expected RTSP headers but socket closed")
            if (line.isEmpty()) {
                break
            }

            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                headers[name] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            readExact(contentLength).toString(Charsets.UTF_8)
        } else {
            ""
        }

        val statusCode = statusLine.split(' ')
            .getOrNull(1)
            ?.toIntOrNull()
            ?: throw AssertionError("Could not parse RTSP status code from '$statusLine'")

        return RtspResponse(
            statusCode = statusCode,
            statusLine = statusLine,
            headers = headers,
            body = body
        )
    }

    private fun readRtspStatusLine(): String {
        while (true) {
            val first = input.read()
            if (first == -1) {
                throw AssertionError("Expected RTSP response status line but socket closed")
            }

            if (first == INTERLEAVED_MARKER) {
                skipInterleavedPacket()
                continue
            }

            val line = readAsciiLine(first)
                ?: throw AssertionError("Expected RTSP response status line but socket closed")
            if (line.startsWith("RTSP/")) {
                return line
            }
        }
    }

    private fun skipInterleavedPacket() {
        val channel = input.read()
        val lengthMsb = input.read()
        val lengthLsb = input.read()
        if (channel == -1 || lengthMsb == -1 || lengthLsb == -1) {
            throw AssertionError("RTSP interleaved header was truncated while waiting for response")
        }

        val payloadLength = (lengthMsb shl 8) or lengthLsb
        readExact(payloadLength)
    }

    private fun readAsciiLine(): String? {
        val first = input.read()
        if (first == -1) {
            return null
        }
        return readAsciiLine(first)
    }

    private fun readAsciiLine(firstByte: Int): String? {
        val line = ByteArrayOutputStream()
        var previous = firstByte
        line.write(firstByte)

        while (true) {
            val next = input.read()
            if (next == -1) {
                return if (line.size() == 0) null else line.toString(Charsets.UTF_8.name())
            }

            if (previous == '\r'.code && next == '\n'.code) {
                val raw = line.toByteArray()
                return raw.copyOf(raw.size - 1).toString(Charsets.UTF_8)
            }

            line.write(next)
            previous = next
        }
    }

    private fun readExact(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0

        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) {
                throw AssertionError("Expected $length bytes but socket closed after $offset bytes")
            }
            offset += read
        }

        return buffer
    }

    companion object {
        private const val INTERLEAVED_MARKER = 0x24

        private fun connectRtspSocket(host: String, port: Int, timeoutMs: Long = 15_000L): Socket {
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastError: IOException? = null

            while (System.currentTimeMillis() < deadline) {
                try {
                    return Socket().apply {
                        connect(InetSocketAddress(host, port), 2_000)
                        soTimeout = 2_000
                    }
                } catch (error: IOException) {
                    lastError = error
                    runCatching { Thread.sleep(250) }
                }
            }

            throw ConnectException("Failed to connect to RTSP server at $host:$port").also {
                if (lastError != null) {
                    it.initCause(lastError)
                }
            }
        }
    }
}

class RtspUdpClient(
    private val host: String,
    private val port: Int = 8554
) : Closeable {
    private val socket: Socket
    private val input: BufferedInputStream
    private val output: BufferedOutputStream
    private var cSeq = 1
    private var sessionId: String? = null
    private val streamUrl = "rtsp://$host:$port/stream"

    val rtpSocket = DatagramSocket()
    private val rtcpSocket = DatagramSocket()
    val clientRtpPort: Int = rtpSocket.localPort
    val clientRtcpPort: Int = rtcpSocket.localPort
    var serverRtpPort: Int = 0; private set
    var serverRtcpPort: Int = 0; private set

    init {
        val rtspPort = port
        val deadline = System.currentTimeMillis() + 15_000L
        var lastError: IOException? = null
        var connected: Socket? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                connected = Socket().apply {
                    connect(InetSocketAddress(host, rtspPort), 2_000)
                    soTimeout = 2_000
                }
                break
            } catch (e: IOException) {
                lastError = e
                runCatching { Thread.sleep(250) }
            }
        }
        if (connected == null) {
            throw ConnectException("Failed to connect to RTSP server at $host:$port").also {
                if (lastError != null) it.initCause(lastError)
            }
        }
        socket = connected
        input = BufferedInputStream(socket.getInputStream())
        output = BufferedOutputStream(socket.getOutputStream())
    }

    fun options(): RtspResponse = sendRequest("OPTIONS", streamUrl)

    fun describe(): RtspResponse =
        sendRequest("DESCRIBE", streamUrl, listOf("Accept: application/sdp"))

    fun setupUdp(): RtspResponse {
        val response = sendRequest(
            "SETUP", "$streamUrl/track0",
            listOf("Transport: RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort")
        )
        response.headers["transport"]?.let { transport ->
            Regex("server_port=(\\d+)-(\\d+)").find(transport)?.let { match ->
                serverRtpPort = match.groupValues[1].toInt()
                serverRtcpPort = match.groupValues[2].toInt()
            }
        }
        return response
    }

    fun play(): RtspResponse = sendRequest("PLAY", streamUrl)

    fun teardown(): RtspResponse = sendRequest("TEARDOWN", streamUrl)

    fun awaitUdpRtpPacket(timeoutMs: Long = 20_000L): ByteArray {
        val savedTimeout = rtpSocket.soTimeout
        rtpSocket.soTimeout = 2_000
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(65_536)
        try {
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    rtpSocket.receive(packet)
                    return buffer.copyOf(packet.length)
                } catch (_: SocketTimeoutException) {
                    // Keep waiting.
                }
            }
            throw AssertionError("Timed out waiting for UDP RTP packet from RTSP server")
        } finally {
            rtpSocket.soTimeout = savedTimeout
        }
    }

    fun awaitDisconnected(timeoutMs: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (input.read() == -1) return true
            } catch (_: SocketTimeoutException) {
                // Keep polling.
            } catch (_: IOException) {
                return true
            }
        }
        return false
    }

    override fun close() {
        runCatching { rtpSocket.close() }
        runCatching { rtcpSocket.close() }
        runCatching { socket.close() }
    }

    private fun sendRequest(
        method: String,
        url: String,
        extraHeaders: List<String> = emptyList()
    ): RtspResponse {
        val request = StringBuilder().apply {
            append("$method $url RTSP/1.0\r\n")
            append("CSeq: ${cSeq++}\r\n")
            sessionId?.let { append("Session: $it\r\n") }
            extraHeaders.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        output.write(request.toString().toByteArray(Charsets.UTF_8))
        output.flush()

        val response = readResponse()
        response.headers["session"]?.let { raw ->
            sessionId = raw.substringBefore(';').trim()
        }
        return response
    }

    private fun readResponse(): RtspResponse {
        val statusLine = readAsciiLine()
            ?: throw AssertionError("Expected RTSP response but socket closed")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readAsciiLine()
                ?: throw AssertionError("Expected RTSP headers but socket closed")
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep > 0) {
                headers[line.substring(0, sep).trim().lowercase()] =
                    line.substring(sep + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) readExact(contentLength).toString(Charsets.UTF_8) else ""
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw AssertionError("Could not parse RTSP status code from '$statusLine'")
        return RtspResponse(statusCode, statusLine, headers, body)
    }

    private fun readAsciiLine(): String? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (prev == '\r'.code && b == '\n'.code) {
                val raw = buf.toByteArray()
                return raw.copyOf(raw.size - 1).toString(Charsets.UTF_8)
            }
            buf.write(b)
            prev = b
        }
    }

    private fun readExact(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) throw AssertionError("Expected $length bytes but socket closed after $offset")
            offset += read
        }
        return buffer
    }
}

private fun ByteArray.indexOfSequence(
    sequence: ByteArray,
    startIndex: Int = 0
): Int {
    if (sequence.isEmpty() || this.size < sequence.size || startIndex >= this.size) {
        return -1
    }

    for (index in startIndex..this.size - sequence.size) {
        var matches = true
        for (offset in sequence.indices) {
            if (this[index + offset] != sequence[offset]) {
                matches = false
                break
            }
        }
        if (matches) {
            return index
        }
    }

    return -1
}
