package de.teddycloud.teddyremote.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsTrustTest {
    @Test
    fun `fingerprint comparison accepts formatting and case differences`() {
        assertTrue(fingerprintMatchesPin("AA:BB:0C", "aabb0c"))
    }

    @Test
    fun `fingerprint comparison rejects missing and changed pins`() {
        assertFalse(fingerprintMatchesPin(null, "AABB0C"))
        assertFalse(fingerprintMatchesPin("AA:BB:0C", "AA:BB:0D"))
    }
}
