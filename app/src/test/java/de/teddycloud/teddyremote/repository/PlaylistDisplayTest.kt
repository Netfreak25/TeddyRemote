package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.*
import de.teddycloud.teddyremote.network.PlaylistResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistDisplayTest {
    private fun model(): BoxUiModel {
        val playlist = PlaylistResolver.resolve(Json.parseToJsonElement(
            """{"tonieInfo":{"tracks":["A","B"]},"trackSeconds":[0,10,70,140],"totalSeconds":150}""",
        ).jsonObject, null)
        return BoxUiModel(
            TonieboxDto("BOX", runtime = BoxRuntime(playback = PlaybackRuntime(chapter = 1))),
            metadata = TonieMetadata("RUID", "Figur", pictureUrl = "/figur.png", playlist = playlist.tracks,
                playlistComplete = playlist.complete, introOutroPlaylist = playlist.introOutroTracks),
            desiredVolume = 6,
        )
    }

    @Test fun `projection changes only playlist while retaining real chapter identities`() {
        val model = model()
        val raw = listOf(model)
        assertSame(raw, displayPlaylists(raw, false))
        val displayed = displayPlaylists(raw, true).single()
        assertEquals(listOf("Intro", "A", "B", "Outro"), displayed.metadata?.playlist?.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), displayed.metadata?.playlist?.map { it.index })
        assertEquals(model.metadata?.playlist?.map { it.durationSeconds }, displayed.metadata?.playlist?.map { it.durationSeconds })
        assertEquals(model, displayed.copy(metadata = model.metadata))
        assertEquals("Figur", displayed.metadata?.title)
        assertEquals("/figur.png", displayed.metadata?.pictureUrl)
        assertEquals("A", displayed.metadata?.playlist?.first { it.index == displayed.box.runtime.playback.chapter }?.title)
        assertEquals("B", model.metadata?.playlist?.get(1)?.title)
    }

    @Test fun `repeated toggle is local reversible and does not reload metadata`() = runTest {
        var loads = 0
        val raw = listOf(model())
        val source = flow { loads++; emit(raw) }
        val enabled = MutableStateFlow(false)
        val seen = mutableListOf<List<BoxUiModel>>()
        backgroundScope.launch { playlistDisplayFlow(source, enabled).collect { seen += it } }
        runCurrent()
        repeat(3) {
            enabled.value = true
            runCurrent()
            assertEquals(listOf("Intro", "A", "B", "Outro"), seen.last().single().metadata?.playlist?.map { it.title })
            enabled.value = false
            runCurrent()
            assertSame(raw, seen.last())
        }
        assertEquals(1, loads)
        assertEquals(7, seen.size)
    }

    @Test fun `new content replaces candidate and unknown durations remain incomplete`() = runTest {
        val source = MutableStateFlow(listOf(model()))
        val enabled = MutableStateFlow(true)
        val seen = mutableListOf<List<BoxUiModel>>()
        backgroundScope.launch { playlistDisplayFlow(source, enabled).collect { seen += it } }
        runCurrent()
        val original = source.value.single()
        val missingDuration = original.copy(metadata = original.metadata!!.copy(
            introOutroPlaylist = original.metadata.introOutroPlaylist!!.map { it.copy(durationSeconds = null) },
        ))
        source.value = listOf(missingDuration)
        runCurrent()
        assertFalse(seen.last().single().metadata!!.playlistComplete)
        val other = original.copy(metadata = TonieMetadata("OTHER", "Other", playlist = listOf(PlaylistTrack(0, "New", 20))))
        source.value = listOf(other)
        runCurrent()
        assertSame(other, seen.last().single())
    }
}
