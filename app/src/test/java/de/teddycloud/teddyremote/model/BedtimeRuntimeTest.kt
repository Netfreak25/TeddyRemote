package de.teddycloud.teddyremote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BedtimeRuntimeTest {
    @Test
    fun `uses confirmed duration and update time for countdown`() {
        val bedtime = BedtimeRuntime(
            valid = true,
            state = "on",
            duration = 300,
            updatedAt = 1_000,
        )

        assertTrue(bedtime.isActive)
        assertEquals(250L, bedtime.remainingSeconds(nowEpochSeconds = 1_050))
    }

    @Test
    fun `prefers confirmed deadline and ignores inactive state`() {
        val active = BedtimeRuntime(state = "active", duration = 300, updatedAt = 1_000, until = "1970-01-01T00:20:00Z")
        val inactive = active.copy(state = "off")

        assertEquals(100L, active.remainingSeconds(nowEpochSeconds = 1_100))
        assertNull(inactive.remainingSeconds(nowEpochSeconds = 1_100))
    }
}
