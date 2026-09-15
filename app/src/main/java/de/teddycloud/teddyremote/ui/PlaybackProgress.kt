package de.teddycloud.teddyremote.ui

import de.teddycloud.teddyremote.model.PlaybackRuntime
import kotlin.math.roundToLong

internal data class ChapterProgress(
    val positionMs: Long,
    val durationMs: Long,
) {
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
    val lastSeekableMs: Long get() = (durationMs - 1L).coerceAtLeast(0L)
}

/** Derives a chapter-relative position from TeddyCloud's absolute chapter deadline. */
internal object PlaybackProgress {
    fun calculate(
        playback: PlaybackRuntime,
        fallbackDurationSeconds: Long?,
        nowEpochMillis: Long,
    ): ChapterProgress? {
        val durationMs = parseDurationMillis(playback.chapterDuration)
            ?: fallbackDurationSeconds?.secondsToMillis()
            ?: return null
        if (durationMs <= 0L) return null

        val chapterUntilMs = playback.chapterUntilMs ?: return null
        val referenceTimeMs = if (playback.isPlaying) {
            nowEpochMillis
        } else {
            playback.updatedAt.takeIf { it > 0L }?.secondsToMillis() ?: return null
        }
        val remainingMs = (chapterUntilMs - referenceTimeMs).coerceIn(0L, durationMs)
        return ChapterProgress(
            positionMs = (durationMs - remainingMs).coerceIn(0L, durationMs),
            durationMs = durationMs,
        )
    }

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

    private fun parseDurationMillis(raw: String?): Long? {
        val seconds = raw?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val milliseconds = seconds * MILLIS_PER_SECOND
        return milliseconds.takeIf { it <= Long.MAX_VALUE.toDouble() }?.roundToLong()
    }

    private fun Long.secondsToMillis(): Long? = runCatching {
        Math.multiplyExact(this, MILLIS_PER_SECOND)
    }.getOrNull()

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
}
