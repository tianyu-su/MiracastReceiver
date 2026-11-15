package com.weekd.miracastreceiver.dlna

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** GENA subscriptions and ordered LastChange notifications (UPnP AVTransport:1).
 * Network callbacks run outside the renderer lock, on one ordered worker. */
internal class UpnpEventSubscriptions(
    private val stateProvider: () -> DlnaMediaRenderer.MediaState,
    private val now: () -> Long = System::currentTimeMillis,
    private val send: (URI, String, Long, String) -> Unit = ::sendNotification
) : AutoCloseable {
    data class Reply(val sid: String, val timeoutSeconds: Int)
    private data class Subscription(
        val sid: String, val service: String, val callback: URI,
        var expires: Long, var sequence: Long = 0L, var initialPending: Boolean = true
    )
    private val subscriptions = linkedMapOf<String, Subscription>()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dlna-events").apply { isDaemon = true }
    }
    private val lastEvents = mutableMapOf<String, String>()

    @Synchronized fun subscribe(
        service: String, callback: String, timeout: String?, peer: InetAddress
    ): Reply? {
        expire()
        if (service !in SERVICES || subscriptions.size >= 32) return null
        val raw = Regex("<([^>]+)>").find(callback)?.groupValues?.get(1) ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "http" || uri.host == null || uri.userInfo != null ||
            uri.fragment != null || uri.port !in -1..65535 || uri.port == 0) return null
        // A LAN subscriber may only ask us to notify its own address.
        if (runCatching { InetAddress.getByName(uri.host) }.getOrNull() != peer) return null
        val seconds = timeoutSeconds(timeout)
        val sid = "uuid:${UUID.randomUUID()}"
        subscriptions[sid] = Subscription(sid, service, uri, now() + seconds * 1000L)
        return Reply(sid, seconds)
    }

    @Synchronized fun renew(service: String, sid: String, timeout: String?): Reply? {
        expire()
        val sub = subscriptions[sid]?.takeIf { it.service == service } ?: return null
        val seconds = timeoutSeconds(timeout)
        sub.expires = now() + seconds * 1000L
        return Reply(sid, seconds)
    }

    @Synchronized fun unsubscribe(service: String, sid: String): Boolean {
        expire()
        if (subscriptions[sid]?.service != service) return false
        return subscriptions.remove(sid) != null
    }

    fun initial(sid: String) {
        // Never acquire the renderer lock while holding our subscription lock.
        val state = stateProvider()
        synchronized(this) {
            expire()
            val sub = subscriptions[sid] ?: return
            sub.initialPending = false
            // A change may have been published after stateProvider returned but
            // while the initial subscription was pending. Do not send an old snapshot.
            enqueue(sub, lastEvents[sub.service] ?: eventBody(sub.service, state))
        }
    }

    @Synchronized fun publish(state: DlnaMediaRenderer.MediaState) {
        expire()
        for (service in SERVICES) {
            val body = eventBody(service, state)
            if (lastEvents.put(service, body) == body) continue
            subscriptions.values.filter { it.service == service && !it.initialPending }
                .forEach { enqueue(it, body) }
        }
    }

    private fun enqueue(sub: Subscription, body: String) {
        val sequence = sub.sequence
        sub.sequence = if (sequence == 0xffff_ffffL) 1L else sequence + 1L
        try {
            worker.execute {
                val valid = synchronized(this) {
                    subscriptions[sub.sid] === sub && sub.expires > now()
                }
                if (valid) runCatching { send(sub.callback, sub.sid, sequence, body) }
            }
        } catch (_: RejectedExecutionException) { /* Service is shutting down. */ }
    }

    private fun expire() { subscriptions.entries.removeAll { it.value.expires <= now() } }

    @Synchronized override fun close() {
        subscriptions.clear()
        lastEvents.clear()
        worker.shutdownNow()
    }

    companion object {
        val SERVICES = setOf("AVTransport", "RenderingControl", "ConnectionManager")
        private fun timeoutSeconds(value: String?): Int = value
            ?.substringAfter('-', "")?.toIntOrNull()?.coerceIn(1, 3600) ?: 1800

        internal fun eventBody(service: String, state: DlnaMediaRenderer.MediaState): String {
            fun property(name: String, value: String) =
                "<e:property><$name>${SoapParser.escapeXml(value)}</$name></e:property>"
            fun variable(name: String, value: String) =
                "<$name val=\"${SoapParser.escapeXml(value)}\"/>"
            val properties = when (service) {
                "AVTransport" -> {
                    val values = linkedMapOf(
                        "TransportState" to state.transportState.name,
                        "TransportStatus" to "OK",
                        "TransportPlaySpeed" to if (state.playbackSpeed == 1f) "1" else state.playbackSpeed.toString(),
                        "AVTransportURI" to state.uri, "AVTransportURIMetaData" to state.metadata,
                        "CurrentTrackURI" to state.uri, "CurrentTrackMetaData" to state.metadata,
                        "CurrentTrack" to if (state.uri.isBlank()) "0" else (state.currentIndex + 1).toString(),
                        "NumberOfTracks" to state.playlist.size.toString(),
                        "CurrentTrackDuration" to SoapParser.formatTime(state.duration),
                        "CurrentMediaDuration" to SoapParser.formatTime(state.duration),
                        "NextAVTransportURI" to state.nextUri,
                        "NextAVTransportURIMetaData" to state.nextMetadata
                    )
                    val change = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">" +
                        "<InstanceID val=\"0\">" + values.entries.joinToString("") { variable(it.key, it.value) } +
                        "</InstanceID></Event>"
                    property("LastChange", change)
                }
                "RenderingControl" -> property("LastChange",
                    "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
                        "<Volume channel=\"Master\" val=\"${state.volume}\"/>" +
                        "<Mute channel=\"Master\" val=\"${if (state.muted) 1 else 0}\"/>" +
                        "</InstanceID></Event>")
                else -> property("SourceProtocolInfo", "") +
                    property("SinkProtocolInfo", "http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*") +
                    property("CurrentConnectionIDs", "0")
            }
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">$properties</e:propertyset>"
        }

        private fun sendNotification(callback: URI, sid: String, sequence: Long, body: String) {
            val port = if (callback.port < 0) 80 else callback.port
            val path = callback.rawPath.orEmpty().ifEmpty { "/" } +
                (callback.rawQuery?.let { "?$it" } ?: "")
            val bytes = body.toByteArray(Charsets.UTF_8)
            val host = if (callback.host.contains(':')) "[${callback.host}]" else callback.host
            Socket().use { socket ->
                socket.connect(InetSocketAddress(callback.host, port), 2000)
                socket.soTimeout = 2000
                val header = "NOTIFY $path HTTP/1.1\r\nHOST: $host:$port\r\n" +
                    "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\n" +
                    "NTS: upnp:propchange\r\nSID: $sid\r\nSEQ: $sequence\r\n" +
                    "CONTENT-LENGTH: ${bytes.size}\r\nCONNECTION: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush()
                }
                socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
            }
        }
    }
}
