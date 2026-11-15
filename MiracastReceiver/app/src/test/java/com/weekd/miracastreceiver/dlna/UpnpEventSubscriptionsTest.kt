package com.weekd.miracastreceiver.dlna

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class UpnpEventSubscriptionsTest {
    private val peer = InetAddress.getByName("127.0.0.1")

    @Test fun stateChangeWhileInitialNotificationIsPendingIsNotLost() {
        val r = DlnaMediaRenderer()
        r.setAVTransportURI("http://sender/episode", "")
        val messages = LinkedBlockingQueue<String>()
        UpnpEventSubscriptions(stateProvider = {
            val old = r.getState()
            r.play() // Reproduce a state change between the snapshot and initial enqueue.
            old
        }, send = { _, _, _, body -> messages.add(body) }).use { events ->
            r.onStateChanged = events::publish
            val reply = events.subscribe("AVTransport", "<http://127.0.0.1:1234/>", null, peer)!!
            events.initial(reply.sid)
            val notification = messages.poll(3, TimeUnit.SECONDS)!!
            val outer = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(notification.byteInputStream())
            val change = outer.getElementsByTagName("LastChange").item(0).textContent
            val inner = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(change.byteInputStream())
            assertEquals("PLAYING", inner.getElementsByTagName("TransportState").item(0).attributes.getNamedItem("val").nodeValue)
        }
    }

    @Test fun initialAndStoppedNotificationsHaveOrderedSequenceAndEscapedLastChange() {
        val r = DlnaMediaRenderer()
        r.setAVTransportURI("http://sender/one?a=1&amp;b=2", "片名 &lt;one&gt;")
        val messages = LinkedBlockingQueue<Pair<Long, String>>()
        UpnpEventSubscriptions(r::getState, send = { _, _, sequence, body ->
            messages.add(sequence to body)
        }).use { events ->
            r.onStateChanged = events::publish
            val reply = events.subscribe("AVTransport", "<http://127.0.0.1:1234/callback>", "Second-300", peer)!!
            events.initial(reply.sid)
            r.play()
            r.updatePlayback(r.getState().generation, 100, 100, DlnaMediaRenderer.PlaybackStatus.ENDED)
            val received = (0..2).map { messages.poll(3, TimeUnit.SECONDS)!! }
            assertEquals(listOf(0L, 1L, 2L), received.map { it.first })
            val outer = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(received.last().second.byteInputStream())
            val change = outer.getElementsByTagName("LastChange").item(0).textContent
            val inner = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(change.byteInputStream())
            assertEquals("STOPPED", inner.getElementsByTagName("TransportState").item(0).attributes.getNamedItem("val").nodeValue)
            assertEquals("http://sender/one?a=1&b=2", inner.getElementsByTagName("AVTransportURI").item(0).attributes.getNamedItem("val").nodeValue)
            assertEquals(300, events.renew("AVTransport", reply.sid, "Second-300")!!.timeoutSeconds)
            assertTrue(events.unsubscribe("AVTransport", reply.sid))
            assertNull(events.renew("AVTransport", reply.sid, null))
        }
    }

    @Test fun expiryWrongServicesAndForeignCallbacksAreRejected() {
        val r = DlnaMediaRenderer()
        var time = 1000L
        UpnpEventSubscriptions(r::getState, now = { time }, send = { _, _, _, _ -> }).use { events ->
            assertNull(events.subscribe("AVTransport", "<http://192.168.1.99:1234/>", null, peer))
            assertNull(events.subscribe("AVTransport", "<file:///tmp/callback>", null, peer))
            val reply = events.subscribe("AVTransport", "<http://127.0.0.1:1234/>", "Second-1", peer)!!
            assertNull(events.renew("RenderingControl", reply.sid, null))
            assertFalse(events.unsubscribe("RenderingControl", reply.sid))
            time += 1001
            assertNull(events.renew("AVTransport", reply.sid, null))
        }
    }

    @Test fun serviceDescriptionAdvertisesNextAndStandardLastChangeEventing() {
        val factory = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val xml = factory.parse(DlnaXmlBuilder.buildAvTransportScpd().byteInputStream())
        val actions = xml.getElementsByTagName("action")
        val names = (0 until actions.length).map { actions.item(it).textContent }
        assertTrue(names.any { it.startsWith("SetNextAVTransportURI") })
        assertTrue(names.any { it.startsWith("Next") })
        val variables = xml.getElementsByTagName("stateVariable")
        val evented = (0 until variables.length).map { variables.item(it) }
            .filter { it.attributes.getNamedItem("sendEvents").nodeValue == "yes" }
        assertEquals(1, evented.size)
        assertTrue(evented.single().textContent.startsWith("LastChange"))
    }
}
