package de.teddycloud.teddyremote.repository

import org.junit.Assert.*
import org.junit.Test

class ConnectionIntentTest {
    @Test fun `reconnect invalidates in flight work without losing the new intent`() {
        val intent = ConnectionIntent()
        val previous = intent.request()
        intent.invalidate()
        val current = intent.request()
        var applied = 0
        intent.apply(previous) { applied++ }
        intent.apply(current) { applied++ }
        assertEquals(1, applied)
        assertTrue(intent.wanted)
    }

    @Test fun `late connect and MQTT callbacks cannot resurrect a stopped session`() {
        val intent = ConnectionIntent()
        val old = intent.request()
        intent.stop()
        var accepted = false
        intent.apply(old) { accepted = true }
        assertFalse(accepted)
        val current = intent.request()
        intent.apply(old) { accepted = true }
        assertFalse(accepted)
        intent.apply(current) { accepted = true }
        assertTrue(accepted)
    }
}
