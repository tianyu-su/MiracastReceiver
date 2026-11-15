package com.weekd.miracastreceiver.dlna

import timber.log.Timber
import java.util.regex.Pattern

/**
 * DLNA 媒体渲染器状态管理
 */
class DlnaMediaRenderer {

    enum class TransportState {
        STOPPED,
        PLAYING,
        PAUSED_PLAYBACK,
        TRANSITIONING,
        NO_MEDIA_PRESENT
    }

    data class MediaState(
        var uri: String = "",
        var metadata: String = "",
        var transportState: TransportState = TransportState.NO_MEDIA_PRESENT,
        var volume: Int = 50,
        var muted: Boolean = false,
        var position: Long = 0L,
        var duration: Long = 0L,
        var playlist: List<String> = emptyList(),
        var currentIndex: Int = 0,
        var playbackSpeed: Float = 1f,
        var nextUri: String = "",
        var nextMetadata: String = "",
        var generation: Long = 0L
    )

    private val state = MediaState()
    private var playbackCompleted = false

    enum class PlaybackStatus { BUFFERING, PLAYING, PAUSED, ENDED, IDLE }

    /** The listener must enqueue notifications, never perform network I/O here. */
    @Volatile var onStateChanged: ((MediaState) -> Unit)? = null

    private fun changed() { onStateChanged?.invoke(state.copy()) }

    var onSetUri: ((String, String) -> Unit)? = null
    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var onSpeedChanged: ((Float) -> Unit)? = null
    var onQualityUriChanged: ((String) -> Unit)? = null
    var onVolumeChanged: ((Int) -> Unit)? = null
    var onMuteChanged: ((Boolean) -> Unit)? = null

    @Synchronized fun setAVTransportURI(uri: String, metadata: String) {
        load(decodeXmlEntities(uri), decodeXmlEntities(metadata), TransportState.STOPPED)
    }

    private fun load(uri: String, metadata: String, transportState: TransportState) {
        state.uri = uri
        state.metadata = metadata
        state.playlist = parsePlaylist(state.uri, state.metadata)
        state.currentIndex = state.playlist.indexOf(state.uri).takeIf { it >= 0 } ?: 0
        state.nextUri = ""
        state.nextMetadata = ""
        state.position = 0L
        state.duration = 0L
        state.generation++
        playbackCompleted = false
        state.transportState = transportState
        changed()
        Timber.i("DLNA SetAVTransportURI: ${state.uri}, playlist size=${state.playlist.size}")
        onSetUri?.invoke(state.uri, state.metadata)
    }

    /** A next URI is a buffer, not a quality change or an immediate load. */
    @Synchronized fun setNextAVTransportURI(uri: String, metadata: String) {
        state.nextUri = decodeXmlEntities(uri)
        state.nextMetadata = if (state.nextUri.isBlank()) "" else decodeXmlEntities(metadata)
        changed()
    }

    @Synchronized fun next(): Boolean {
        if (state.nextUri.isBlank()) return false
        val uri = state.nextUri
        val metadata = state.nextMetadata
        load(uri, metadata, TransportState.TRANSITIONING)
        return true
    }

    @Synchronized fun play() {
        if (state.uri.isNotBlank()) {
            playbackCompleted = false
            state.transportState = TransportState.PLAYING
            changed()
            Timber.i("DLNA Play")
            onPlay?.invoke()
        }
    }

    @Synchronized fun pause() {
        state.transportState = TransportState.PAUSED_PLAYBACK
        changed()
        Timber.i("DLNA Pause")
        onPause?.invoke()
    }

    @Synchronized fun stop() {
        playbackCompleted = true
        state.transportState = TransportState.STOPPED
        changed()
        Timber.i("DLNA Stop")
        onStop?.invoke()
    }

    @Synchronized fun seek(target: String) {
        val position = parseTimeToMs(target)
        state.position = position
        Timber.i("DLNA Seek: $target -> $position ms")
        onSeek?.invoke(position)
    }

    @Synchronized fun setPlaybackSpeed(speed: String) {
        val value = speed.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f
        state.playbackSpeed = value
        changed()
        Timber.i("DLNA SetSpeed: $value")
        onSpeedChanged?.invoke(value)
    }

    @Synchronized fun setQualityUri(uri: String) {
        val decoded = decodeXmlEntities(uri)
        if (decoded.isNotBlank()) {
            state.uri = decoded
            changed()
            Timber.i("DLNA SetQualityURI: $decoded")
            onQualityUriChanged?.invoke(decoded)
        }
    }

    @Synchronized fun setVolume(volume: Int) {
        state.volume = volume.coerceIn(0, 100)
        changed()
        Timber.i("DLNA SetVolume: ${state.volume}")
        onVolumeChanged?.invoke(state.volume)
    }

    @Synchronized fun setMute(muted: Boolean) {
        state.muted = muted
        changed()
        Timber.i("DLNA SetMute: $muted")
        onMuteChanged?.invoke(muted)
    }

    @Synchronized fun getState(): MediaState = state.copy()

    /** Explicit player state keeps completion distinct from pause and buffering.
     * A generation rejects delayed broadcasts from a replaced episode. */
    @Synchronized fun updatePlayback(
        generation: Long, position: Long, duration: Long, status: PlaybackStatus,
        currentUri: String? = null
    ) {
        if (generation != state.generation || playbackCompleted) return
        state.position = position.coerceAtLeast(0L)
        state.duration = duration.coerceAtLeast(0L)
        val index = state.playlist.indexOf(currentUri)
        if (index >= 0) {
            state.currentIndex = index
            state.uri = state.playlist[index]
        }
        when (status) {
            PlaybackStatus.ENDED -> {
                playbackCompleted = true
                if (next()) return
                state.transportState = TransportState.STOPPED
            }
            PlaybackStatus.BUFFERING -> state.transportState = TransportState.TRANSITIONING
            PlaybackStatus.PLAYING -> state.transportState = TransportState.PLAYING
            PlaybackStatus.PAUSED -> state.transportState = TransportState.PAUSED_PLAYBACK
            PlaybackStatus.IDLE -> state.transportState = TransportState.STOPPED
        }
        changed()
    }

    @Synchronized fun playbackStopped(generation: Long) {
        if (generation != state.generation) return
        playbackCompleted = true
        state.transportState = TransportState.STOPPED
        changed()
    }

    @Synchronized fun setStopped() {
        playbackCompleted = true
        state.transportState = TransportState.STOPPED
        changed()
    }

    private fun parseTimeToMs(time: String): Long {
        return try {
            val parts = time.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val seconds = parts[2].toDouble()
                ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
            } else {
                0L
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing time: $time")
            0L
        }
    }

    private fun decodeXmlEntities(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun parsePlaylist(currentUri: String, metadata: String): List<String> {
        val uris = mutableListOf<String>()
        // 如果有播放列表元数据，尝试解析
        if (metadata.contains("<container") || metadata.contains("playlist", ignoreCase = true)) {
            // 简单解析 DIDL-Lite 中的多个 item
            val itemPattern = Pattern.compile("<item[^>]*>.*?<res[^>]*>([^<]+)</res>.*?</item>", Pattern.DOTALL)
            val matcher = itemPattern.matcher(metadata)
            while (matcher.find()) {
                matcher.group(1)?.trim()?.let { uri ->
                    if (uri.isNotBlank()) uris.add(decodeXmlEntities(uri))
                }
            }
        }
        // 如果没有找到播放列表，至少包含当前 URI
        return if (uris.isEmpty()) listOf(currentUri) else uris
    }
}

/**
 * SOAP 请求解析工具
 */
object SoapParser {
    fun extractAction(soapAction: String?): String {
        if (soapAction.isNullOrBlank()) return ""
        return soapAction
            .trim('"')
            .substringAfterLast('#')
            .trim()
    }

    fun extractTagValue(xml: String, tagName: String): String {
        // Match the exact local name: an empty NextURI must never match NextURIMetaData.
        val name = "(?:[\\w.-]+:)?${Pattern.quote(tagName)}"
        val pattern = Pattern.compile("<$name(?:\\s[^>]*)?>(.*?)</$name\\s*>",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(xml)
        if (!matcher.find()) return ""
        val value = matcher.group(1)?.trim().orEmpty()
        return if (value.startsWith("<![CDATA[") && value.endsWith("]]>"))
            value.removePrefix("<![CDATA[").removeSuffix("]]>") else value
    }

    fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}
