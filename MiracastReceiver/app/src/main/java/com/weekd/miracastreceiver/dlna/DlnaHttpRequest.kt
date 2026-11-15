package com.weekd.miracastreceiver.dlna

import java.io.EOFException
import java.io.InputStream
import java.util.Locale

/** Read Content-Length as bytes, including fragmented UTF-8 SOAP bodies. */
internal data class DlnaHttpRequest(
    val method: String, val path: String, val headers: Map<String, String>, val body: String
) {
    companion object {
        fun read(input: InputStream): DlnaHttpRequest? {
            fun line(): String? {
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) {
                        if (bytes.size() == 0) return null
                        throw EOFException("Incomplete HTTP header")
                    }
                    if (b == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
                    require(bytes.size() < 8192) { "HTTP header line too large" }
                    bytes.write(b)
                }
            }
            val start = line()?.split(' ', limit = 3) ?: return null
            require(start.size == 3 && start[2].startsWith("HTTP/1.")) { "Invalid HTTP request" }
            val headers = linkedMapOf<String, String>()
            var count = 0
            while (true) {
                val value = line() ?: throw EOFException("Incomplete HTTP headers")
                if (value.isEmpty()) break
                require(++count <= 100) { "Too many HTTP headers" }
                val colon = value.indexOf(':')
                require(colon > 0) { "Invalid HTTP header" }
                val name = value.substring(0, colon).trim().lowercase(Locale.ROOT)
                require(name != "content-length" || name !in headers) { "Duplicate Content-Length" }
                headers[name] = value.substring(colon + 1).trim()
            }
            require("transfer-encoding" !in headers) { "Chunked requests are not supported" }
            val length = headers["content-length"]?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("Invalid Content-Length")
            } ?: 0
            require(length in 0..2_097_152) { "SOAP request too large" }
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw EOFException("Incomplete SOAP body")
                read += n
            }
            return DlnaHttpRequest(start[0], start[1], headers, body.toString(Charsets.UTF_8))
        }
    }
}
