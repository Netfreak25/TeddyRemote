package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.BoxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxStateReducerTest {
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
