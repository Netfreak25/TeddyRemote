package de.teddycloud.teddyremote.model

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.UUID

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Meine TeddyCloud",
    val apiBaseUrl: String = "",
    val mqttEnabled: Boolean = false,
    val mqttHost: String = "",
    val mqttPort: Int = 1883,
    val mqttPrefix: String = "teddyCloud",
    val mqttTls: Boolean = false,
    val mqttUsername: String = "",
    val mqttClientId: String = "teddyremote-${UUID.randomUUID()}",
    val connectOnAppStart: Boolean = true,
    val autoReconnect: Boolean = true,
    val maxRetries: Int = 0,
    val initialRetrySeconds: Int = 2,
    val maxRetrySeconds: Int = 30,
    val apiCertificateFingerprint: String? = null,
    val mqttCertificateFingerprint: String? = null,
) {
    fun normalized(): ConnectionProfile = copy(
        name = name.trim(),
        apiBaseUrl = normalizeBaseUrl(apiBaseUrl),
        mqttHost = mqttHost.trim(),
        mqttPrefix = mqttPrefix.trim().trim('/').ifBlank { "teddyCloud" },
        mqttUsername = mqttUsername.trim(),
        mqttPort = mqttPort.coerceIn(1, 65_535),
        maxRetries = maxRetries.coerceAtLeast(0),
        initialRetrySeconds = initialRetrySeconds.coerceIn(1, 300),
        maxRetrySeconds = maxRetrySeconds.coerceAtLeast(initialRetrySeconds).coerceAtMost(3_600),
        apiCertificateFingerprint = apiCertificateFingerprint?.normalizeFingerprint(),
        mqttCertificateFingerprint = mqttCertificateFingerprint?.normalizeFingerprint(),
    )

    fun validate(): List<String> = buildList {
        val profile = normalized()
        if (profile.name.isBlank()) add("Profilname fehlt")
        val uri = runCatching { URI(profile.apiBaseUrl) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() || uri.scheme !in setOf("http", "https")) {
            add("TeddyCloud-URL muss mit http:// oder https:// beginnen")
        }
        if (profile.mqttEnabled && profile.mqttHost.isBlank()) add("MQTT-Hostname fehlt")
        if (profile.mqttEnabled && profile.mqttPrefix.isBlank()) add("MQTT-Präfix fehlt")
        if (profile.maxRetrySeconds < profile.initialRetrySeconds) {
            add("Maximale Retry-Zeit muss mindestens der initialen Retry-Zeit entsprechen")
        }
    }

    companion object {
        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return ""
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            return withScheme.trimEnd('/') + "/"
        }
    }
}

private fun String.normalizeFingerprint(): String =
    replace(":", "").replace(" ", "").uppercase()

@Serializable
data class ProfilesState(
    val schemaVersion: Int = 1,
    val profiles: List<ConnectionProfile> = emptyList(),
    val activeProfileId: String? = null,
    val connectionRequested: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    val activeProfile: ConnectionProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }
}
