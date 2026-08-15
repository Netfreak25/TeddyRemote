package de.teddycloud.teddyremote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class ConnectionProfileTest {
    @Test
    fun `uses the public TeddyCloud endpoint for new profiles`() {
        assertEquals("https://tbs2.tonie.cloud:8443/", ConnectionProfile().apiBaseUrl)
    }

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

    @Test
    fun `ignores retired home ssid fields in stored profiles`() {
        val profile = tolerantProfileJson.decodeFromString<ConnectionProfile>(
            """{"name":"Zuhause","homeSsidPrimary":"Home","homeSsidSecondary":"Workshop"}""",
        )

        assertEquals("Zuhause", profile.name)
        assertTrue(profile.reconnectOnWifiReconnect)
    }
}

private val tolerantProfileJson = Json { ignoreUnknownKeys = true }
