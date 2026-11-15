package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.BoxRuntime
import de.teddycloud.teddyremote.model.PlaybackRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxStateReducerTest {
    private fun revisedRuntime() = BoxRuntime(playback = PlaybackRuntime(
        ruid = "AAAAAAAAAAAAAAAA", contentVersion = 42, metadataRevision = "revision-A",
    ))

    @Test
    fun `changing or clearing content identity discards its old metadata revision`() {
        val original = revisedRuntime()
        for ((field, value) in listOf(
            "PlaybackRuid" to "BBBBBBBBBBBBBBBB",
            "PlaybackContentVersion" to "43",
            "PlaybackRuid" to "",
            "PlaybackContentVersion" to "",
        )) {
            val updated = BoxStateReducer.reduce(original, field, value, 101)
            assertNull("$field=$value must not keep the previous content revision", updated.playback.metadataRevision)
        }
    }

    @Test
    fun `duplicate identities and playback updates preserve the current metadata revision`() {
        val original = revisedRuntime()
        for ((field, value) in listOf(
            "PlaybackRuid" to "AAAAAAAAAAAAAAAA",
            "PlaybackRuid" to "aaaaaaaaaaaaaaaa",
            "PlaybackContentVersion" to "42",
            "PlaybackStatus" to "playing",
            "PlaybackChapter" to "1",
        )) {
            val updated = BoxStateReducer.reduce(original, field, value, 101)
            assertEquals("$field=$value must keep the current content revision", "revision-A", updated.playback.metadataRevision)
        }
    }

    @Test
    fun `updates playback and accepts the complete TB2 volume range`() {
        val playing = BoxStateReducer.reduce(BoxRuntime(), "PlaybackStatus", "playing", 100)
        val withRuid = BoxStateReducer.reduce(playing, "PlaybackRuid", "28F28F11500304E0", 101)
        val withMinimumVolume = BoxStateReducer.reduce(withRuid, "VolumeLevel", "1", 102)
        val withMaximumVolume = BoxStateReducer.reduce(withMinimumVolume, "VolumeLevel", "12", 103)

        assertTrue(withMaximumVolume.playback.isPlaying)
        assertEquals("28F28F11500304E0", withMaximumVolume.playback.ruid)
        assertEquals(1, withMinimumVolume.volume.level)
        assertEquals(12, withMaximumVolume.volume.level)
    }

    @Test
    fun `invalid volume events leave the confirmed value unchanged`() {
        val confirmed = BoxStateReducer.reduce(BoxRuntime(), "VolumeLevel", "6", 100)

        assertEquals(confirmed, BoxStateReducer.reduce(confirmed, "VolumeLevel", "0", 101))
        assertEquals(confirmed, BoxStateReducer.reduce(confirmed, "VolumeLevel", "13", 102))
        assertEquals(confirmed, BoxStateReducer.reduce(confirmed, "VolumeLevel", "invalid", 103))
    }

    @Test
    fun `unknown fields keep state unchanged`() {
        val state = BoxRuntime(lastConnection = 123)
        assertEquals(state, BoxStateReducer.reduce(state, "FutureField", "value", 999))
    }
}
