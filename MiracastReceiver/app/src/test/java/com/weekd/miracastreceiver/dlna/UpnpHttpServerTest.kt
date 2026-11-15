package com.weekd.miracastreceiver.dlna

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Exercise the actual SOAP/GENA sockets, not only renderer methods. */
class UpnpHttpServerTest {
    private class Harness : AutoCloseable {
        val renderer = DlnaMediaRenderer()
        val port = ServerSocket(0).use { it.localPort }
        val server = UpnpHttpServer(ContextWrapper(null), renderer, "test-device", "Test TV",
            "Test", "API21", "127.0.0.1", port)

        init {
            server.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                ready = runCatching { request("GET", "/device.xml").startsWith("HTTP/1.1 200") }.getOrDefault(false)
                if (!ready) Thread.sleep(10)
            }
            check(ready) { "UPnP server did not bind" }
        }

        fun request(method: String, path: String, body: String = "", headers: Map<String, String> = emptyMap()): String {
            val bytes = body.toByteArray()
            val extra = headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            val header = "$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "${extra}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            return Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        }

        fun soap(action: String, args: String = ""): String {
            val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                "<u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
            val response = request("POST", "/control/AVTransport", body,
                mapOf("sOaPaCtIoN" to "\"urn:schemas-upnp-org:service:AVTransport:1#$action\""))
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertFalse(response.contains("<s:Fault>"))
            return response.substringAfter("\r\n\r\n")
        }

        override fun close() { server.stop() }
    }

    @Test fun soapNextQueueDoesNotInterruptCurrentEpisodeAndIsConsumedOnCompletion() {
        Harness().use { h ->
            val loads = mutableListOf<String>()
            h.renderer.onSetUri = { uri, _ -> loads.add(uri) }
            h.soap("SetAVTransportURI", "<CurrentURI>http://sender/one.mp4</CurrentURI><CurrentURIMetaData/>")
            h.soap("Play", "<Speed>1</Speed>")
            val generation = h.renderer.getState().generation
            h.soap("SetNextAVTransportURI", "<NextURI>http://sender/two.mp4?a=1&amp;b=2</NextURI>" +
                "<NextURIMetaData>第二集</NextURIMetaData>")
            assertEquals(1, loads.size)
            val media = xml(h.soap("GetMediaInfo"))
            assertEquals("http://sender/one.mp4", media.getElementsByTagName("CurrentURI").item(0).textContent)
            assertEquals("http://sender/two.mp4?a=1&b=2", media.getElementsByTagName("NextURI").item(0).textContent)
            h.renderer.updatePlayback(generation, 1000, 1000, DlnaMediaRenderer.PlaybackStatus.ENDED)
            assertEquals(2, loads.size)
            assertEquals("第二集", h.renderer.getState().metadata)
            val next = h.renderer.getState().generation
            h.renderer.updatePlayback(generation, 1000, 1000, DlnaMediaRenderer.PlaybackStatus.PAUSED)
            h.renderer.updatePlayback(next, 2000, 2000, DlnaMediaRenderer.PlaybackStatus.ENDED)
            val stopped = xml(h.soap("GetTransportInfo"))
            assertEquals("STOPPED", stopped.getElementsByTagName("CurrentTransportState").item(0).textContent)
        }
    }

    @Test fun realSubscriberReceivesInitialPlayingAndNaturalEndThenCanRenewAndUnsubscribe() {
        Harness().use { h ->
            ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { callback ->
                callback.soTimeout = 5000
                val notifications = LinkedBlockingQueue<DlnaHttpRequest>()
                val listener = Thread {
                    repeat(3) {
                        callback.accept().use { socket ->
                            socket.soTimeout = 5000
                            val request = DlnaHttpRequest.read(socket.getInputStream())!!
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            notifications.add(request)
                        }
                    }
                }.apply { isDaemon = true; start() }
                h.renderer.setAVTransportURI("http://sender/episode", "")
                val response = h.request("SUBSCRIBE", "/event/AVTransport", headers = mapOf(
                    "CALLBACK" to "<http://127.0.0.1:${callback.localPort}/events>",
                    "NT" to "upnp:event", "TIMEOUT" to "Second-300"))
                assertTrue(response.startsWith("HTTP/1.1 200"))
                val sid = response.lineSequence().first { it.startsWith("SID:") }.substringAfter(':').trim()
                assertTrue(sid.startsWith("uuid:"))
                val initial = notifications.poll(5, TimeUnit.SECONDS)!!
                assertEquals("NOTIFY", initial.method)
                assertEquals("0", initial.headers["seq"])
                assertEquals("upnp:propchange", initial.headers["nts"])
                h.renderer.play()
                h.renderer.updatePlayback(h.renderer.getState().generation, 100, 100, DlnaMediaRenderer.PlaybackStatus.ENDED)
                val playing = notifications.poll(5, TimeUnit.SECONDS)!!
                val ended = notifications.poll(5, TimeUnit.SECONDS)!!
                assertEquals("1", playing.headers["seq"])
                assertEquals("2", ended.headers["seq"])
                val change = xml(ended.body).getElementsByTagName("LastChange").item(0).textContent
                assertEquals("STOPPED", xml(change).getElementsByTagName("TransportState").item(0).attributes.getNamedItem("val").nodeValue)
                assertTrue(h.request("SUBSCRIBE", "/event/AVTransport", headers = mapOf("SID" to sid))
                    .startsWith("HTTP/1.1 200"))
                assertTrue(h.request("UNSUBSCRIBE", "/event/AVTransport", headers = mapOf("SID" to sid))
                    .startsWith("HTTP/1.1 200"))
                assertTrue(h.request("SUBSCRIBE", "/event/AVTransport", headers = mapOf("SID" to sid))
                    .startsWith("HTTP/1.1 412"))
                // A controller can respond to STOPPED by sending the next episode
                // instead of using the optional prebuffered NextURI action.
                h.soap("SetAVTransportURI", "<CurrentURI>http://sender/episode2</CurrentURI><CurrentURIMetaData/>")
                h.soap("Play", "<Speed>1</Speed>")
                assertEquals("http://sender/episode2", h.renderer.getState().uri)
                assertEquals(DlnaMediaRenderer.TransportState.PLAYING, h.renderer.getState().transportState)
                listener.join(1000)
            }
        }
    }

    private fun xml(value: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(value.byteInputStream())
}
