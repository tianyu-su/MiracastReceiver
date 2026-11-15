package com.weekd.miracastreceiver.dlna

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream

class DlnaHttpRequestTest {
    @Test fun emptyNextUriDoesNotFallBackToMetadataAndCdataIsUnwrapped() {
        assertEquals("", SoapParser.extractTagValue(
            "<NextURI></NextURI><NextURIMetaData>old metadata</NextURIMetaData>", "NextURI"))
        assertEquals("http://sender/two?a=1&b=2", SoapParser.extractTagValue(
            "<u:NextURI xmlns:u=\"urn:test\"><![CDATA[http://sender/two?a=1&b=2]]></u:NextURI>", "NextURI"))
    }

    @Test fun fragmentedUtf8AndMixedCaseHeadersAreReadWithoutTruncation() {
        val body = "<NextURIMetaData>第二集 &amp; 片名</NextURIMetaData>"
        val bytes = body.toByteArray()
        val wire = ("POST /control/AVTransport HTTP/1.1\r\n" +
            "sOaPaCtIoN: \"urn:schemas-upnp-org:service:AVTransport:1#SetNextAVTransportURI\"\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n").toByteArray() + bytes
        val fragments = object : FilterInputStream(ByteArrayInputStream(wire)) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 2))
        }
        val request = DlnaHttpRequest.read(fragments)!!
        assertEquals(body, request.body)
        assertEquals("SetNextAVTransportURI", SoapParser.extractAction(request.headers["soapaction"]))
    }

    @Test fun invalidLengthsAndTruncatedBodiesAreRejected() {
        for (length in listOf("-1", "999999999", "not-a-number")) {
            val wire = "POST / HTTP/1.1\r\nContent-Length: $length\r\n\r\n"
            assertThrows(IllegalArgumentException::class.java) { DlnaHttpRequest.read(wire.byteInputStream()) }
        }
        assertThrows(EOFException::class.java) {
            DlnaHttpRequest.read("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc".byteInputStream())
        }
    }
}
