package com.ipcam.testsupport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

internal class RawHttpConnection(
    private val socket: Socket,
    val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    val statusLine: String,
    val statusCode: Int,
    val headers: Map<String, List<String>>
) : Closeable {
    fun headerValue(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    override fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { socket.close() }
    }
}

internal fun openRawHttpGet(
    host: String,
    port: Int,
    path: String,
    readTimeoutMs: Int,
    keepAlive: Boolean
): RawHttpConnection {
    val socket = Socket().apply {
        connect(InetSocketAddress(host, port), 10_000)
        soTimeout = readTimeoutMs
    }
    val input = BufferedInputStream(socket.getInputStream())
    val output = BufferedOutputStream(socket.getOutputStream())
    val request = buildString {
        append("GET ").append(path).append(" HTTP/1.1\r\n")
        append("Host: ").append(host).append(':').append(port).append("\r\n")
        append("Accept: */*\r\n")
        append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        append("\r\n")
    }
    output.write(request.toByteArray(Charsets.US_ASCII))
    output.flush()

    val responseHead = readResponseHead(input)
    return RawHttpConnection(
        socket = socket,
        input = input,
        output = output,
        statusLine = responseHead.statusLine,
        statusCode = responseHead.statusCode,
        headers = responseHead.headers
    )
}

internal fun readHttpBodyFully(connection: RawHttpConnection): ByteArray {
    val transferEncoding = connection.headerValue("Transfer-Encoding").orEmpty().lowercase()
    val contentLength = connection.headerValue("Content-Length")?.trim()?.toIntOrNull()

    return when {
        "chunked" in transferEncoding -> readChunkedBody(connection.input)
        contentLength != null -> readExact(connection.input, contentLength)
        else -> connection.input.readBytes()
    }
}

private data class RawHttpResponseHead(
    val statusLine: String,
    val statusCode: Int,
    val headers: Map<String, List<String>>
)

private fun readResponseHead(input: InputStream): RawHttpResponseHead {
    val headerBytes = ByteArrayOutputStream()
    val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    var matched = 0

    while (true) {
        val next = input.read()
        if (next == -1) {
            throw EOFException("HTTP response ended before headers were fully received")
        }

        val byte = next.toByte()
        headerBytes.write(next)

        if (byte == delimiter[matched]) {
            matched += 1
            if (matched == delimiter.size) {
                break
            }
        } else {
            matched = if (byte == delimiter[0]) 1 else 0
        }
    }

    val headerText = headerBytes.toByteArray().toString(Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
    val lines = headerText.split("\r\n")
    val statusLine = lines.firstOrNull() ?: throw EOFException("HTTP response did not include a status line")
    val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        ?: throw EOFException("Unable to parse HTTP status code from '$statusLine'")

    val headers = linkedMapOf<String, MutableList<String>>()
    for (line in lines.drop(1)) {
        val separatorIndex = line.indexOf(':')
        if (separatorIndex <= 0) {
            continue
        }

        val name = line.substring(0, separatorIndex).trim()
        val value = line.substring(separatorIndex + 1).trim()
        headers.getOrPut(name) { mutableListOf() }.add(value)
    }

    return RawHttpResponseHead(
        statusLine = statusLine,
        statusCode = statusCode,
        headers = headers
    )
}

private fun readChunkedBody(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()

    while (true) {
        val chunkHeader = readAsciiLine(input).substringBefore(';').trim()
        val chunkSize = chunkHeader.toIntOrNull(16)
            ?: throw EOFException("Unable to parse HTTP chunk size from '$chunkHeader'")

        if (chunkSize == 0) {
            // Consume trailers.
            while (readAsciiLine(input).isNotEmpty()) {
                // Ignore trailer headers.
            }
            return output.toByteArray()
        }

        output.write(readExact(input, chunkSize))
        expectCrLf(input)
    }
}

private fun readAsciiLine(input: InputStream): String {
    val lineBytes = ByteArrayOutputStream()

    while (true) {
        val next = input.read()
        if (next == -1) {
            throw EOFException("Unexpected end of stream while reading HTTP line")
        }

        if (next == '\r'.code) {
            val lineFeed = input.read()
            if (lineFeed != '\n'.code) {
                throw EOFException("Malformed HTTP line ending")
            }
            return lineBytes.toByteArray().toString(Charsets.US_ASCII)
        }

        lineBytes.write(next)
    }
}

private fun expectCrLf(input: InputStream) {
    val carriageReturn = input.read()
    val lineFeed = input.read()
    if (carriageReturn != '\r'.code || lineFeed != '\n'.code) {
        throw EOFException("Malformed HTTP chunk terminator")
    }
}

private fun readExact(input: InputStream, length: Int): ByteArray {
    var remaining = length
    val buffer = ByteArray(length)
    var offset = 0

    while (remaining > 0) {
        val read = input.read(buffer, offset, remaining)
        if (read == -1) {
            throw EOFException("Expected $length bytes, but stream ended after $offset bytes")
        }
        offset += read
        remaining -= read
    }

    return buffer
}
