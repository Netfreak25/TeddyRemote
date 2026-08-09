package de.teddycloud.teddyremote.repository

import kotlin.math.min
import kotlin.random.Random

class BackoffPolicy(private val random: Random = Random.Default) {
    fun delayMillis(attempt: Int, initialSeconds: Int, maximumSeconds: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 20)
        val base = min(maximumSeconds.toLong(), initialSeconds.toLong() * (1L shl exponent)) * 1_000L
        val jitter = (base * 0.15).toLong()
        return if (jitter == 0L) base else base + random.nextLong(-jitter, jitter + 1)
    }
}
