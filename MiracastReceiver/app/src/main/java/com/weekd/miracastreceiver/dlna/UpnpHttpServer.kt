package com.weekd.miracastreceiver.dlna

import android.content.Context
import android.content.Intent
import com.weekd.miracastreceiver.ui.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * UPnP HTTP 服务器
 * 处理设备描述、服务描述和 SOAP 控制请求
 */
class UpnpHttpServer(
    private val context: Context,
    private val renderer: DlnaMediaRenderer,
    private val deviceUuid: String,
    @Volatile private var deviceName: String,
    private val manufacturer: String,
    private val modelName: String,
    private val localIp: String,
    private val port: Int = 8080
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val events = UpnpEventSubscriptions(renderer::getState)

    fun start() {
        if (serverJob?.isActive == true) {
            Timber.w("UPnP HTTP server already running")
            return
        }

        renderer.onStateChanged = events::publish
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Timber.i("UPnP HTTP server started on port $port")

                while (serverSocket?.isClosed == false) {
                    val client = serverSocket?.accept()
                    if (client != null) {
                        launch {
                            handleClient(client)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "UPnP HTTP server error")
            }
        }
    }

    fun stop() {
        renderer.onStateChanged = null
        events.close()
        try {
            serverJob?.cancel()
            serverSocket?.close()
            Timber.i("UPnP HTTP server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping UPnP HTTP server")
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 10_000
                val output = client.getOutputStream()
                val request = DlnaHttpRequest.read(client.getInputStream()) ?: return
                val headers = request.headers
                val body = request.body
                val method = request.method
                val path = request.path

                Timber.d("UPnP $method $path")

                when {
                    path == "/device.xml" -> sendDeviceDescription(output)
                    path == "/service/ConnectionManager.xml" -> sendConnectionManagerScpd(output)
                    path == "/service/AVTransport.xml" -> sendAvTransportScpd(output)
                    path == "/service/RenderingControl.xml" -> sendRenderingControlScpd(output)
                    path == "/control/AVTransport" -> handleAvTransportControl(output, body, headers)
                    path == "/control/RenderingControl" -> handleRenderingControl(output, body, headers)
                    path == "/control/ConnectionManager" -> handleConnectionManager(output, body, headers)
                    path.startsWith("/event/") -> handleEvent(output, method, path, headers, client.inetAddress)
                    else -> sendNotFound(output)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling UPnP client")
            }
        }
    }

    fun updateDisplayName(value: String) {
        deviceName = value
    }

    private fun sendDeviceDescription(output: OutputStream) {
        val baseUrl = "http://$localIp:$port"
        val xml = DlnaXmlBuilder.buildDeviceDescription(
            deviceUuid, deviceName, manufacturer, modelName, baseUrl
        )
        sendXmlResponse(output, xml)
    }

    private fun sendConnectionManagerScpd(output: OutputStream) {
        sendXmlResponse(output, DlnaXmlBuilder.buildConnectionManagerScpd())
    }

    private fun sendAvTransportScpd(output: OutputStream) {
        sendXmlResponse(output, DlnaXmlBuilder.buildAvTransportScpd())
    }

    private fun sendRenderingControlScpd(output: OutputStream) {
        sendXmlResponse(output, DlnaXmlBuilder.buildRenderingControlScpd())
    }

    private fun handleAvTransportControl(output: OutputStream, body: String, headers: Map<String, String>) {
        val action = SoapParser.extractAction(headers["soapaction"])
        Timber.i("AVTransport action: $action")

        // 调试：打印完整请求体，帮助排查 Emby 的格式
        if (action == "SetAVTransportURI") {
            Timber.d("SetAVTransportURI body (first 1000 chars): ${body.take(1000)}")
        }

        val response = when (action) {
            "SetAVTransportURI" -> {
                val uri = SoapParser.extractTagValue(body, "CurrentURI")
                val metadata = SoapParser.extractTagValue(body, "CurrentURIMetaData")

                // 如果 URI 为空，记录错误并尝试更宽松的解析
                if (uri.isBlank()) {
                    Timber.e("SetAVTransportURI: Empty URI! Trying fallback parsing...")
                    Timber.e("Request body: $body")

                    // 尝试其他可能的标签名
                    val fallbackUri = listOf("URI", "Url", "MediaUri", "CurrentUri").firstNotNullOfOrNull { tag ->
                        val value = SoapParser.extractTagValue(body, tag)
                        if (value.isNotBlank()) value else null
                    } ?: ""

                    if (fallbackUri.isNotBlank()) {
                        Timber.i("Found URI via fallback: $fallbackUri")
                        renderer.setAVTransportURI(fallbackUri, metadata)
                    } else {
                        renderer.setAVTransportURI(uri, metadata)
                    }
                } else {
                    renderer.setAVTransportURI(uri, metadata)
                }
                buildSoapResponse(action, "")
            }
            "SetNextAVTransportURI" -> {
                val uri = SoapParser.extractTagValue(body, "NextURI")
                val metadata = SoapParser.extractTagValue(body, "NextURIMetaData")
                renderer.setNextAVTransportURI(uri, metadata)
                buildSoapResponse(action, "")
            }
            "Next" -> if (renderer.next()) buildSoapResponse(action, "")
                else buildSoapFault("701", "Transition not available")
            "Play" -> {
                val speed = extractSpeed(body)
                if (speed.isNotBlank()) {
                    renderer.setPlaybackSpeed(speed)
                }
                renderer.play()
                buildSoapResponse(action, "")
            }
            "SetRate", "SetSpeed", "SetPlaybackSpeed", "SetTransportPlaySpeed",
            "X_SetRate", "X_SetSpeed", "X_SetPlaybackSpeed", "X_SetTransportPlaySpeed" -> {
                val speed = extractSpeed(body)
                if (speed.isNotBlank()) {
                    renderer.setPlaybackSpeed(speed)
                    buildSoapResponse(action, "")
                } else {
                    buildSoapFault("402", "Invalid Args")
                }
            }
            "Pause" -> {
                renderer.pause()
                buildSoapResponse(action, "")
            }
            "Stop" -> {
                renderer.stop()
                buildSoapResponse(action, "")
            }
            "Seek" -> {
                val target = SoapParser.extractTagValue(body, "Target")
                renderer.seek(target)
                buildSoapResponse(action, "")
            }
            "GetTransportInfo" -> {
                val state = renderer.getState()
                val content = """
                    <CurrentTransportState>${state.transportState}</CurrentTransportState>
                    <CurrentTransportStatus>OK</CurrentTransportStatus>
                    <CurrentSpeed>${formatSpeed(state.playbackSpeed)}</CurrentSpeed>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetPositionInfo" -> {
                val state = renderer.getState()
                val content = """
                    <Track>${if (state.uri.isBlank()) 0 else state.currentIndex + 1}</Track>
                    <TrackDuration>${SoapParser.formatTime(state.duration)}</TrackDuration>
                    <TrackMetaData>${SoapParser.escapeXml(state.metadata)}</TrackMetaData>
                    <TrackURI>${SoapParser.escapeXml(state.uri)}</TrackURI>
                    <RelTime>${SoapParser.formatTime(state.position)}</RelTime>
                    <AbsTime>${SoapParser.formatTime(state.position)}</AbsTime>
                    <RelCount>2147483647</RelCount>
                    <AbsCount>2147483647</AbsCount>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetMediaInfo" -> {
                val state = renderer.getState()
                val content = """
                    <NrTracks>${state.playlist.size}</NrTracks>
                    <MediaDuration>${SoapParser.formatTime(state.duration)}</MediaDuration>
                    <CurrentURI>${SoapParser.escapeXml(state.uri)}</CurrentURI>
                    <CurrentURIMetaData>${SoapParser.escapeXml(state.metadata)}</CurrentURIMetaData>
                    <NextURI>${SoapParser.escapeXml(state.nextUri)}</NextURI>
                    <NextURIMetaData>${SoapParser.escapeXml(state.nextMetadata)}</NextURIMetaData>
                    <PlayMedium>NETWORK</PlayMedium>
                    <RecordMedium>NOT_IMPLEMENTED</RecordMedium>
                    <WriteStatus>NOT_IMPLEMENTED</WriteStatus>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetCurrentTransportActions" -> {
                val next = if (renderer.getState().nextUri.isNotBlank()) ",Next" else ""
                val content = "<Actions>Play,Pause,Stop,Seek$next</Actions>"
                buildSoapResponse(action, content)
            }
            "GetTransportSettings" -> {
                val content = """
                    <PlayMode>NORMAL</PlayMode>
                    <RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "X_GetFeatureList" -> {
                // 一些客户端请求扩展特性
                buildSoapResponse(action, "<FeatureList></FeatureList>")
            }
            else -> buildSoapFault("401", "Invalid Action")
        }

        sendSoapResponse(output, response)
    }

    private fun handleRenderingControl(output: OutputStream, body: String, headers: Map<String, String>) {
        val action = SoapParser.extractAction(headers["soapaction"])
        Timber.i("RenderingControl action: $action")
        fun respond(content: String) = buildSoapResponse(action, content, "RenderingControl")

        val response = when (action) {
            "GetVolume" -> {
                val state = renderer.getState()
                respond("<CurrentVolume>${state.volume}</CurrentVolume>")
            }
            "SetVolume" -> {
                val volume = SoapParser.extractTagValue(body, "DesiredVolume").toIntOrNull() ?: 50
                renderer.setVolume(volume)
                respond("")
            }
            "GetMute" -> {
                val state = renderer.getState()
                val mute = if (state.muted) "1" else "0"
                respond("<CurrentMute>$mute</CurrentMute>")
            }
            "SetMute" -> {
                val mute = SoapParser.extractTagValue(body, "DesiredMute") == "1"
                renderer.setMute(mute)
                respond("")
            }
            else -> buildSoapFault("401", "Invalid Action")
        }

        sendSoapResponse(output, response)
    }

    private fun handleConnectionManager(output: OutputStream, body: String, headers: Map<String, String>) {
        val action = SoapParser.extractAction(headers["soapaction"])
        Timber.i("ConnectionManager action: $action")
        fun respond(content: String) = buildSoapResponse(action, content, "ConnectionManager")

        val response = when (action) {
            "GetProtocolInfo" -> {
                val content = """
                    <Source></Source>
                    <Sink>http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*</Sink>
                """.trimIndent()
                respond(content)
            }
            "GetCurrentConnectionIDs" -> {
                respond("<ConnectionIDs>0</ConnectionIDs>")
            }
            "GetCurrentConnectionInfo" -> {
                val content = """
                    <RcsID>0</RcsID>
                    <AVTransportID>0</AVTransportID>
                    <ProtocolInfo></ProtocolInfo>
                    <PeerConnectionManager></PeerConnectionManager>
                    <PeerConnectionID>-1</PeerConnectionID>
                    <Direction>Input</Direction>
                    <Status>OK</Status>
                """.trimIndent()
                respond(content)
            }
            else -> buildSoapFault("401", "Invalid Action")
        }

        sendSoapResponse(output, response)
    }

    private fun handleEvent(
        output: OutputStream, method: String, path: String,
        headers: Map<String, String>, peer: InetAddress
    ) {
        val service = path.removePrefix("/event/")
        if (service !in UpnpEventSubscriptions.SERVICES) { sendNotFound(output); return }
        val sid = headers["sid"]
        when (method) {
            "SUBSCRIBE" -> {
                val reply = if (sid != null) {
                    if ("callback" in headers || "nt" in headers) null
                    else events.renew(service, sid, headers["timeout"])
                } else {
                    if (!headers["nt"].equals("upnp:event", ignoreCase = true)) null
                    else events.subscribe(service, headers["callback"].orEmpty(), headers["timeout"], peer)
                }
                if (reply == null) { eventResponse(output, "412 Precondition Failed"); return }
                eventResponse(output, "200 OK", "SID: ${reply.sid}\r\nTIMEOUT: Second-${reply.timeoutSeconds}\r\n")
                if (sid == null) events.initial(reply.sid)
            }
            "UNSUBSCRIBE" -> {
                val removed = sid != null && "callback" !in headers && "nt" !in headers &&
                    events.unsubscribe(service, sid)
                eventResponse(output, if (removed) "200 OK" else "412 Precondition Failed")
            }
            else -> eventResponse(output, "405 Method Not Allowed")
        }
    }

    private fun eventResponse(output: OutputStream, status: String, headers: String = "") {
        output.write(("HTTP/1.1 $status\r\n${headers}Content-Length: 0\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun buildSoapResponse(action: String, content: String, service: String = "AVTransport"): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">
      $content
    </u:${action}Response>
  </s:Body>
</s:Envelope>"""
    }

    private fun buildSoapFault(errorCode: String, errorDescription: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>UPnPError</faultstring>
      <detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
          <errorCode>$errorCode</errorCode>
          <errorDescription>$errorDescription</errorDescription>
        </UPnPError>
      </detail>
    </s:Fault>
  </s:Body>
</s:Envelope>"""
    }

    private fun sendXmlResponse(output: OutputStream, xml: String) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/xml; charset=utf-8\r\n" +
            "Content-Length: ${xml.toByteArray().size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(xml.toByteArray())
        output.flush()
    }

    private fun sendSoapResponse(output: OutputStream, xml: String) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/xml; charset=utf-8\r\n" +
            "EXT:\r\n" +
            "Content-Length: ${xml.toByteArray().size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(xml.toByteArray())
        output.flush()
    }

    private fun sendNotFound(output: OutputStream) {
        val response = "HTTP/1.1 404 Not Found\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed == 1f) "1" else speed.toString()
    }

    private fun extractSpeed(body: String): String {
        val tagNames = listOf(
            "Speed",
            "DesiredSpeed",
            "Rate",
            "DesiredRate",
            "PlaybackSpeed",
            "TransportPlaySpeed",
            "CurrentSpeed"
        )
        for (tag in tagNames) {
            val value = SoapParser.extractTagValue(body, tag)
            if (value.isNotBlank()) return normalizeSpeed(value)
        }

        // 兜底：有些客户端会把倍速放在非标准字段或属性里
        val regexes = listOf(
            Regex("(?i)<[^>]*(?:speed|rate)[^>]*>([^<]+)</[^>]+>"),
            Regex("(?i)(?:speed|rate)\\s*=\\s*[\"']?([0-9.]+)"),
            Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*x")
        )
        for (regex in regexes) {
            val value = regex.find(body)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) return normalizeSpeed(value)
        }
        return ""
    }

    private fun normalizeSpeed(value: String): String {
        return value.trim()
            .removeSuffix("x")
            .removeSuffix("X")
            .toFloatOrNull()
            ?.coerceIn(0.25f, 4f)
            ?.toString()
            ?: ""
    }
}
