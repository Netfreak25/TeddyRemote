package de.teddycloud.teddyremote.ui

import de.teddycloud.teddyremote.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BoxDisplayPolicyTest {
    private fun box(id: String, name: String, online: Boolean, playing: Boolean, seen: Long) = BoxUiModel(
        TonieboxDto(id, boxName = name, runtime = BoxRuntime(
            online = online, lastConnection = seen,
            playback = PlaybackRuntime(status = if (playing) "playing" else "paused"),
        )),
    )

    @Test fun `playing boxes sort by name then idle and offline by contact`() {
        val boxes = listOf(
            box("1", "Offline", false, true, 500),
            box("2", "Zebra", true, true, 400),
            box("3", "Alpha", true, true, 100),
            box("4", "Alt", true, false, 200),
            box("5", "Neu", true, false, 300),
            box("6", "Unbekannt", false, false, 0),
        )
        assertEquals(listOf("3", "2", "5", "4", "1", "6"), sortedBoxes(boxes).map { it.box.id })
        val offline = boxes[2].let { it.copy(box = it.box.copy(runtime = it.box.runtime.copy(online = false))) }
        assertEquals("2", sortedBoxes(boxes.filterNot { it.box.id == "3" } + offline).first().box.id)
    }

    @Test fun `equal contact uses name and box id deterministically`() {
        assertEquals(listOf("1", "2", "3"), sortedBoxes(listOf(
            box("3", "b", true, false, 0), box("2", "a", true, false, 0), box("1", "A", true, false, 0),
        )).map { it.box.id })
    }

    @Test fun `partial playlists resolve actual chapter index`() {
        val model = box("1", "Test", true, true, 1).let {
            it.copy(box = it.box.copy(runtime = it.box.runtime.copy(playback = PlaybackRuntime(chapter = 4))),
                metadata = TonieMetadata("RUID", "Title", playlist = listOf(PlaylistTrack(4, "Fünftes"))))
        }
        assertEquals("Fünftes", currentTrackTitle(model))
    }

    @Test fun `history filter searches names and IDs without changing resume preferences`() {
        val first = PlaybackToniePreference("ABC123", "Schlummerband", true, 20)
        val second = PlaybackToniePreference("DEF456", "Geschichten", false, 10)
        assertEquals(listOf(first), filteredHistory(listOf(first, second), " SCHLUMMER "))
        assertEquals(listOf(second), filteredHistory(listOf(first, second), "def"))
        assertEquals(listOf(first, second), filteredHistory(listOf(second, first), ""))
        assertTrue(filteredHistory(listOf(first), "nicht vorhanden").isEmpty())
        assertTrue(first.autoResumeEnabled)
    }

    @Test fun `old settings hide IDs and new setting survives serialization`() {
        assertFalse(Json.decodeFromString<ProfilesState>("{}").showTonieId)
        val state = ProfilesState(showTonieId = true)
        assertTrue(Json.decodeFromString<ProfilesState>(Json.encodeToString(ProfilesState.serializer(), state)).showTonieId)
    }

    @Test fun `metadata keys distinguish profiles boxes RUIDs and generations`() {
        val base = MetadataKey("profile", "BOX", "RUID", 1)
        assertNotEquals(base, base.copy(profileId = "other"))
        assertNotEquals(base, base.copy(boxId = "other"))
        assertNotEquals(base, base.copy(ruid = "other"))
        assertNotEquals(base, base.copy(contentVersion = 2))
    }
}
