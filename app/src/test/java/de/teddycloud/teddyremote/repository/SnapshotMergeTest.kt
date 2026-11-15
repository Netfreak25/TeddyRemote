package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotMergeTest {
    private fun revised(revision: String, chapter: Int = 0): BoxUiModel {
        val initial = model(1)
        val box = initial.box.copy(runtime = initial.box.runtime.copy(
            playback = initial.box.runtime.playback.copy(metadataRevision = revision, chapter = chapter),
        ))
        return initial.copy(box = box, metadata = initial.metadata!!.copy(
            title = revision, metadataRevision = revision, key = MetadataKey.forBox("profile", box),
        ))
    }
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

    @Test fun `same version enrichment changes cache key and replaces metadata`() {
        val old = revised("partial")
        val ready = revised("ready")
        assertNotEquals(MetadataKey.forBox("profile", old.box), MetadataKey.forBox("profile", ready.box))
        assertEquals("ready", mergeBoxSnapshot("profile", ready, old, old).metadata?.title)
    }

    @Test fun `MQTT chapter changes do not hide new HTTP metadata revision`() {
        val merged = mergeBoxSnapshot("profile", revised("ready"), revised("partial"), revised("partial", 7))
        assertEquals(7, merged.box.runtime.playback.chapter)
        assertEquals("ready", merged.box.runtime.playback.metadataRevision)
        assertEquals("ready", merged.metadata?.title)
    }

    @Test fun `late old snapshot does not overwrite concurrent enriched metadata`() {
        val old = revised("partial")
        val merged = mergeBoxSnapshot("profile", old, old, revised("ready", 7))
        assertEquals("ready", merged.box.runtime.playback.metadataRevision)
        assertEquals("ready", merged.metadata?.title)
    }

    @Test fun `response revision must match request snapshot even on initial load`() {
        val old = revised("partial")
        val mismatched = old.copy(metadata = revised("ready").metadata)
        assertNull(mergeBoxSnapshot("profile", mismatched, null, null).metadata)
        assertEquals("partial", mergeBoxSnapshot("profile", mismatched, old, old).metadata?.title)
    }
}
