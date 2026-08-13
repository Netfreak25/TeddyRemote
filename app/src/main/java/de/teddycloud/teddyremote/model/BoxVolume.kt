package de.teddycloud.teddyremote.model

/** Confirmed discrete TB2 volume levels used by the box protocol. */
object BoxVolume {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 12
    const val SLIDER_STEPS = MAX_LEVEL - MIN_LEVEL - 1

    fun isValid(level: Int): Boolean = level in MIN_LEVEL..MAX_LEVEL

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
}
