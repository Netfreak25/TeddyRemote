package de.teddycloud.teddyremote.ui

import de.teddycloud.teddyremote.model.PlaybackRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackProgressTest {
    @Test
    fun `calculates a playing chapter from its absolute deadline`() {
        val progress = PlaybackProgress.calculate(
            playback = PlaybackRuntime(
                valid = true,
                status = "playing",
                chapterUntilMs = 1_124_293L,
                chapterDuration = "124.293",
            ),
            fallbackDurationSeconds = null,
            nowEpochMillis = 1_103_000L,
        )

        assertEquals(103_000L, progress?.positionMs)
        assertEquals(21_293L, progress?.remainingMs)
    }

    @Test
    fun `freezes a paused chapter at the playback update time`() {
        val progress = PlaybackProgress.calculate(
            playback = PlaybackRuntime(
                valid = true,
                status = "paused",
                updatedAt = 1_100L,
                chapterUntilMs = 1_130_000L,
                chapterDuration = "90",
            ),
            fallbackDurationSeconds = null,
            nowEpochMillis = 1_900_000L,
        )

        assertEquals(60_000L, progress?.positionMs)
        assertEquals(30_000L, progress?.remainingMs)
    }

    @Test
    fun `uses playlist duration and rejects incomplete timing data`() {
        val playback = PlaybackRuntime(
            status = "playing",
            chapterUntilMs = 1_061_000L,
        )

        assertEquals(
            60_000L,
            PlaybackProgress.calculate(playback, 61L, 1_060_000L)?.positionMs,
        )
        assertNull(PlaybackProgress.calculate(playback.copy(chapterUntilMs = null), 61L, 1_060_000L))
        assertNull(PlaybackProgress.calculate(playback, null, 1_060_000L))
    }

    @Test
    fun `clamps relative seeks inside the current chapter`() {
        val progress = ChapterProgress(positionMs = 10_000L, durationMs = 60_000L)

        assertEquals(0L, PlaybackProgress.shifted(progress, -30_000L))
        assertEquals(40_000L, PlaybackProgress.shifted(progress, 30_000L))
        assertEquals(59_999L, PlaybackProgress.shifted(progress.copy(positionMs = 50_000L), 30_000L))
    }

    @Test
    fun `formats chapter time with optional hours`() {
        assertEquals("3:27", PlaybackProgress.formatTime(207_999L))
        assertEquals("1:02:03", PlaybackProgress.formatTime(3_723_999L))
    }
}
