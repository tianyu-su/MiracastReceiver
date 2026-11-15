package com.weekd.miracastreceiver.dlna

import com.weekd.miracastreceiver.dlna.DlnaMediaRenderer.PlaybackStatus as Playback
import com.weekd.miracastreceiver.dlna.DlnaMediaRenderer.TransportState.*
import org.junit.Assert.*
import org.junit.Test

class DlnaMediaRendererTest {
    @Test fun completionRemainsStoppedAfterLateProgressAndKeepsFinalPosition() {
        val r = DlnaMediaRenderer()
        r.setAVTransportURI("http://sender/episode1.mp4", "")
        val generation = r.getState().generation
        r.updatePlayback(generation, 10_000, 10_000, Playback.ENDED)
        repeat(3) { r.updatePlayback(generation, 10_000, 10_000, Playback.PAUSED) }
        assertEquals(STOPPED, r.getState().transportState)
        assertEquals(10_000L, r.getState().position)
        assertEquals(10_000L, r.getState().duration)
    }

    @Test fun nextUriIsBufferedAndPromotedOnlyOnceAtNaturalEnd() {
        val r = DlnaMediaRenderer()
        val loads = mutableListOf<String>()
        val qualityChanges = mutableListOf<String>()
        val states = mutableListOf<DlnaMediaRenderer.TransportState>()
        r.onSetUri = { uri, _ -> loads.add(uri) }
        r.onQualityUriChanged = { qualityChanges.add(it) }
        r.onStateChanged = { states.add(it.transportState) }
        r.setAVTransportURI("http://sender/one.mp4", "one")
        val first = r.getState().generation
        r.updatePlayback(first, 500, 1000, Playback.PLAYING)
        r.setNextAVTransportURI("http://sender/two.mp4?a=1&amp;b=2", "two")
        assertEquals(listOf("http://sender/one.mp4"), loads)
        assertEquals(PLAYING, r.getState().transportState)
        assertTrue(qualityChanges.isEmpty())
        states.clear()
        r.updatePlayback(first, 1000, 1000, Playback.ENDED)
        val second = r.getState().generation
        assertTrue(second > first)
        assertEquals(listOf("http://sender/one.mp4", "http://sender/two.mp4?a=1&b=2"), loads)
        assertEquals("two", r.getState().metadata)
        assertEquals("", r.getState().nextUri)
        assertEquals(listOf(TRANSITIONING), states)
        r.updatePlayback(first, 1000, 1000, Playback.ENDED)
        r.playbackStopped(first)
        assertEquals(2, loads.size)
        assertEquals(TRANSITIONING, r.getState().transportState)
        r.updatePlayback(second, 100, 500, Playback.PLAYING)
        assertEquals(PLAYING, r.getState().transportState)
        r.updatePlayback(second, 500, 500, Playback.ENDED)
        assertEquals(STOPPED, r.getState().transportState)
    }

    @Test fun clearingNextAndManualStopDoNotAutoAdvance() {
        val r = DlnaMediaRenderer()
        val loads = mutableListOf<String>()
        r.onSetUri = { uri, _ -> loads.add(uri) }
        r.setAVTransportURI("http://sender/one.mp4", "")
        r.setNextAVTransportURI("http://sender/two.mp4", "two")
        r.stop()
        r.updatePlayback(r.getState().generation, 100, 100, Playback.ENDED)
        assertEquals(1, loads.size)
        r.setNextAVTransportURI("", "must be cleared")
        assertEquals("", r.getState().nextMetadata)
        assertFalse(r.next())
    }

    @Test fun replacingMediaClearsOldQueueAndRejectsOldEpisodeUpdates() {
        val r = DlnaMediaRenderer()
        r.setAVTransportURI("http://sender/one", "")
        val old = r.getState().generation
        r.setNextAVTransportURI("http://sender/two", "")
        r.setAVTransportURI("http://sender/new-show", "")
        val current = r.getState().generation
        r.updatePlayback(current, 100, 500, Playback.PLAYING)
        r.updatePlayback(old, 500, 500, Playback.ENDED)
        r.playbackStopped(old)
        assertEquals("http://sender/new-show", r.getState().uri)
        assertEquals(PLAYING, r.getState().transportState)
        assertEquals("", r.getState().nextUri)
    }

    @Test fun bufferingPauseAndEndAreDifferentAndReplayStillWorks() {
        val r = DlnaMediaRenderer()
        r.setAVTransportURI("http://sender/one", "")
        val generation = r.getState().generation
        r.updatePlayback(generation, 0, 100, Playback.BUFFERING)
        assertEquals(TRANSITIONING, r.getState().transportState)
        r.updatePlayback(generation, 50, 100, Playback.PAUSED)
        assertEquals(PAUSED_PLAYBACK, r.getState().transportState)
        r.updatePlayback(generation, 100, 100, Playback.ENDED)
        r.play()
        r.updatePlayback(generation, 0, 100, Playback.PLAYING)
        assertEquals(PLAYING, r.getState().transportState)
    }
}
