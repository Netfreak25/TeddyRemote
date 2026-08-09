package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.BoxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxStateReducerTest {
    @Test
    fun `updates playback and clamps volume`() {
        val playing = BoxStateReducer.reduce(BoxRuntime(), "PlaybackStatus", "playing", 100)
        val withRuid = BoxStateReducer.reduce(playing, "PlaybackRuid", "28F28F11500304E0", 101)
        val withVolume = BoxStateReducer.reduce(withRuid, "VolumeLevel", "17", 102)

        assertTrue(withVolume.playback.isPlaying)
        assertEquals("28F28F11500304E0", withVolume.playback.ruid)
        assertEquals(10, withVolume.volume.level)
    }

    @Test
    fun `unknown fields keep state unchanged`() {
        val state = BoxRuntime(lastConnection = 123)
        assertEquals(state, BoxStateReducer.reduce(state, "FutureField", "value", 999))
    }
}
