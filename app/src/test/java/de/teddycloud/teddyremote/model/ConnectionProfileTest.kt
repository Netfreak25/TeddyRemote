package de.teddycloud.teddyremote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileTest {
    @Test
    fun `normalizes URL prefix and retry bounds`() {
        val profile = ConnectionProfile(
            name = "  Zuhause  ",
            apiBaseUrl = "192.168.1.100:8443",
            mqttPrefix = "/teddyCloud/",
            initialRetrySeconds = 9,
            maxRetrySeconds = 2,
        ).normalized()

        assertEquals("Zuhause", profile.name)
        assertEquals("https://192.168.1.100:8443/", profile.apiBaseUrl)
        assertEquals("teddyCloud", profile.mqttPrefix)
        assertEquals(9, profile.maxRetrySeconds)
        assertTrue(profile.validate().isEmpty())
    }

    @Test
    fun `rejects profile without an API host`() {
        val errors = ConnectionProfile(apiBaseUrl = "https://").validate()
        assertTrue(errors.any { it.contains("URL") })
    }
}
