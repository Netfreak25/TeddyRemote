package de.teddycloud.teddyremote.model

import kotlin.math.roundToLong

data class PlaybackPosition(
    val positionMs: Long,
    val durationMs: Long,
) {
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
    val lastSeekableMs: Long get() = (durationMs - 1L).coerceAtLeast(0L)
}

/** Derives a chapter-relative position from TeddyCloud's absolute chapter deadline. */
object PlaybackPositionCalculator {
    fun calculate(
        playback: PlaybackRuntime,
        fallbackDurationSeconds: Long?,
        nowEpochMillis: Long,
    ): PlaybackPosition? {
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
        return PlaybackPosition(
            positionMs = (durationMs - remainingMs).coerceIn(0L, durationMs),
            durationMs = durationMs,
        )
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
}
