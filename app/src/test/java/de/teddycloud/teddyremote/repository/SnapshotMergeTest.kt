package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotMergeTest {
    private fun model(version: Long, online: Boolean = true) = BoxUiModel(
        box = TonieboxDto("BOX", runtime = BoxRuntime(online = online, playback = PlaybackRuntime(
            ruid = "1234567890ABCDEF", contentVersion = version,
        ))),
        metadata = TonieMetadata("1234567890ABCDEF", "Title $version", key = MetadataKey("profile", "BOX", "1234567890ABCDEF", version)),
    )

    @Test fun `MQTT generation change during HTTP request keeps current chapters`() {
        val baseline = model(1)
        val current = model(2)
        val merged = mergeBoxSnapshot("profile", model(1), baseline, current)
        assertEquals(2L, merged.box.runtime.playback.contentVersion)
        assertEquals("Title 2", merged.metadata?.title)
    }

    @Test fun `live volume does not prevent HTTP online and metadata updates`() {
        val baseline = model(1, false).copy(metadata = null)
        val current = baseline.copy(box = baseline.box.copy(runtime = baseline.box.runtime.copy(volume = VolumeRuntime(level = 8))))
        val merged = mergeBoxSnapshot("profile", model(1), baseline, current)
        assertTrue(merged.box.runtime.online)
        assertEquals(8, merged.box.runtime.volume.level)
        assertEquals("Title 1", merged.metadata?.title)
    }

    @Test fun `metadata from another box profile or version is never reused`() {
        val baseline = model(1)
        val switched = model(2).copy(metadata = baseline.metadata)
        assertNull(mergeBoxSnapshot("profile", baseline, baseline, switched).metadata)
        assertNull(mergeBoxSnapshot("otherProfile", baseline, baseline, baseline).metadata)
    }
}
