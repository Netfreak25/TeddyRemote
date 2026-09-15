package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.data.PlaybackBookmarkStorage
import de.teddycloud.teddyremote.model.BoxRuntime
import de.teddycloud.teddyremote.model.BoxUiModel
import de.teddycloud.teddyremote.model.PlaybackBookmark
import de.teddycloud.teddyremote.model.PlaybackBookmarkKey
import de.teddycloud.teddyremote.model.PlaybackRuntime
import de.teddycloud.teddyremote.model.PlaylistTrack
import de.teddycloud.teddyremote.model.TonieMetadata
import de.teddycloud.teddyremote.model.TonieboxDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackResumeCoordinatorTest {
    @Test
    fun `offers a saved position and decline replaces it with the current position`() = runTest {
        val storage = FakePlaybackStorage(bookmark(positionMs = 120_000L))
        val coordinator = coordinator(storage)

        coordinator.observe(PROFILE, listOf(model(positionMs = 10_000L)))

        assertEquals(120_000L, coordinator.offers.value.getValue(BOX_ID).positionMs)
        coordinator.decline(BOX_ID)
        assertNull(coordinator.offers.value[BOX_ID])
        assertEquals(10_000L, storage.bookmarks.getValue(KEY).positionMs)
    }

    @Test
    fun `content change discards a pending position and preserves the old bookmark`() = runTest {
        val original = bookmark(positionMs = 180_000L)
        val storage = FakePlaybackStorage(original)
        val coordinator = coordinator(storage)
        coordinator.observe(PROFILE, listOf(model(positionMs = 10_000L)))

        coordinator.observe(PROFILE, listOf(model(ruid = OTHER_RUID, version = 2L, positionMs = 0L)))

        assertEquals(original, storage.bookmarks[KEY])
        assertFalse(coordinator.offers.value.containsKey(BOX_ID))
    }

    @Test
    fun `timeout commits the current temporary position while the same content remains active`() = runTest {
        val storage = FakePlaybackStorage(bookmark(positionMs = 180_000L))
        val coordinator = coordinator(storage)
        coordinator.observe(PROFILE, listOf(model(positionMs = 0L, durationMs = 3_600_000L)))

        advanceTimeBy(20L * 60L * 1_000L + 1_000L)
        runCurrent()

        assertNull(coordinator.offers.value[BOX_ID])
        assertEquals(1_200_000L, storage.bookmarks.getValue(KEY).positionMs)
    }

    @Test
    fun `pause and resume save immediately and periodic playback advances every minute`() = runTest {
        val storage = FakePlaybackStorage()
        val coordinator = coordinator(storage)
        coordinator.observe(PROFILE, listOf(model(positionMs = 60_000L)))
        assertEquals(60_000L, storage.bookmarks.getValue(KEY).positionMs)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(120_000L, storage.bookmarks.getValue(KEY).positionMs)

        coordinator.observe(PROFILE, listOf(model(status = "paused", positionMs = 125_000L)))
        assertEquals(125_000L, storage.bookmarks.getValue(KEY).positionMs)
        coordinator.observe(PROFILE, listOf(model(status = "playing", positionMs = 126_000L)))
        assertEquals(126_000L, storage.bookmarks.getValue(KEY).positionMs)
    }

    @Test
    fun `resume clears only after the requested chapter position is observed`() = runTest {
        val storage = FakePlaybackStorage(bookmark(chapter = 1, positionMs = 90_000L))
        val coordinator = coordinator(storage)
        coordinator.observe(PROFILE, listOf(model(chapter = 0, positionMs = 10_000L)))

        val target = coordinator.beginResume(BOX_ID)
        assertNotNull(target)
        assertEquals(1, target?.chapter)
        assertEquals(true, coordinator.offers.value.getValue(BOX_ID).pending)

        coordinator.observe(PROFILE, listOf(model(chapter = 1, positionMs = 92_000L)))

        assertNull(coordinator.offers.value[BOX_ID])
        assertEquals(92_000L, storage.bookmarks.getValue(KEY).positionMs)
    }

    @Test
    fun `completed final chapter removes the bookmark after stopped state`() = runTest {
        val storage = FakePlaybackStorage(bookmark(chapter = 1, positionMs = 300_000L))
        val coordinator = coordinator(storage)
        coordinator.observe(PROFILE, listOf(model(chapter = 1, positionMs = 300_000L)))

        coordinator.observe(PROFILE, listOf(model(status = "stopped", chapter = 1, positionMs = 300_000L)))

        assertNull(storage.bookmarks[KEY])
        assertNull(coordinator.offers.value[BOX_ID])
    }

    @Test
    fun `completed content removes an unanswered resume offer`() = runTest {
        val storage = FakePlaybackStorage(bookmark(chapter = 1, positionMs = 60_000L))
        val coordinator = coordinator(storage)
        coordinator.observe(PROFILE, listOf(model(chapter = 1, positionMs = 300_000L)))
        assertNotNull(coordinator.offers.value[BOX_ID])

        coordinator.observe(PROFILE, listOf(model(status = "stopped", chapter = 1, positionMs = 300_000L)))

        assertNull(storage.bookmarks[KEY])
        assertNull(coordinator.offers.value[BOX_ID])
    }

    @Test
    fun `reset discards temporary progress without overwriting the saved position`() = runTest {
        val original = bookmark(positionMs = 180_000L)
        val storage = FakePlaybackStorage(original)
        val coordinator = coordinator(storage)
        coordinator.observe(PROFILE, listOf(model(positionMs = 10_000L)))

        coordinator.reset()

        assertEquals(original, storage.bookmarks[KEY])
        assertTrue(coordinator.offers.value.isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(storage: PlaybackBookmarkStorage) =
        PlaybackResumeCoordinator(
            scope = backgroundScope,
            storage = storage,
            nowEpochMillis = { BASE_TIME_MS + testScheduler.currentTime },
            tickIntervalMs = 1_000L,
        )

    private fun kotlinx.coroutines.test.TestScope.model(
        status: String = "playing",
        ruid: String = RUID,
        version: Long = VERSION,
        chapter: Int = 0,
        positionMs: Long,
        durationMs: Long = 300_000L,
    ): BoxUiModel {
        val now = BASE_TIME_MS + testScheduler.currentTime
        val reference = now
        return BoxUiModel(
            box = TonieboxDto(
                id = BOX_ID,
                runtime = BoxRuntime(
                    online = true,
                    playback = PlaybackRuntime(
                        valid = true,
                        status = status,
                        updatedAt = reference / 1_000L,
                        tonie = "Testtonie",
                        ruid = ruid,
                        contentVersion = version,
                        chapter = chapter,
                        chapterUntilMs = reference + (durationMs - positionMs),
                        chapterDuration = (durationMs / 1_000.0).toString(),
                    ),
                ),
            ),
            metadata = TonieMetadata(
                ruid = ruid,
                title = "Testtonie",
                playlist = listOf(
                    PlaylistTrack(0, "Kapitel 1", durationMs / 1_000L),
                    PlaylistTrack(1, "Kapitel 2", durationMs / 1_000L),
                ),
                playlistComplete = true,
            ),
        )
    }

    private fun bookmark(
        chapter: Int = 0,
        positionMs: Long,
    ) = PlaybackBookmark(
        key = KEY,
        chapter = chapter,
        positionMs = positionMs,
        contentTitle = "Testtonie",
        chapterTitle = "Kapitel ${chapter + 1}",
        updatedAtEpochMs = BASE_TIME_MS - 60_000L,
    )

    private class FakePlaybackStorage(vararg initial: PlaybackBookmark) : PlaybackBookmarkStorage {
        val bookmarks = initial.associateByTo(mutableMapOf()) { it.key }

        override suspend fun find(key: PlaybackBookmarkKey): PlaybackBookmark? = bookmarks[key]

        override suspend fun upsert(bookmark: PlaybackBookmark) {
            bookmarks[bookmark.key] = bookmark
        }

        override suspend fun delete(key: PlaybackBookmarkKey) {
            bookmarks.remove(key)
        }
    }

    private companion object {
        const val PROFILE = "profile"
        const val BOX_ID = "D4594404DEAC"
        const val RUID = "28F28F11500304E0"
        const val OTHER_RUID = "77F73E23500304E0"
        const val VERSION = 7L
        const val BASE_TIME_MS = 1_786_000_000_000L
        val KEY = PlaybackBookmarkKey(PROFILE, RUID, VERSION)
    }
}
