package de.teddycloud.teddyremote.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffPolicyTest {
    @Test
    fun `grows exponentially and respects maximum with jitter`() {
        val policy = BackoffPolicy(Random(7))
        val first = policy.delayMillis(1, 2, 30)
        val fifth = policy.delayMillis(5, 2, 30)

        assertTrue(first in 1_700L..2_300L)
        assertTrue(fifth in 25_500L..34_500L)
    }
}
