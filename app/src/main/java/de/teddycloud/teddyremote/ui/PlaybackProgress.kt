package de.teddycloud.teddyremote.ui

import de.teddycloud.teddyremote.model.PlaybackRuntime
import de.teddycloud.teddyremote.model.PlaybackPosition
import de.teddycloud.teddyremote.model.PlaybackPositionCalculator

internal typealias ChapterProgress = PlaybackPosition

/** Derives a chapter-relative position from TeddyCloud's absolute chapter deadline. */
internal object PlaybackProgress {
    fun calculate(
        playback: PlaybackRuntime,
        fallbackDurationSeconds: Long?,
        nowEpochMillis: Long,
    ): ChapterProgress? = PlaybackPositionCalculator.calculate(playback, fallbackDurationSeconds, nowEpochMillis)

    fun shifted(progress: ChapterProgress, deltaMs: Long): Long =
        (progress.positionMs + deltaMs).coerceIn(0L, progress.lastSeekableMs)

    fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / MILLIS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
}
