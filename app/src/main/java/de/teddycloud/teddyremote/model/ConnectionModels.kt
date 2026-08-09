package de.teddycloud.teddyremote.model

enum class LinkStatus {
    NOT_CHECKED,
    CONNECTING,
    CONNECTED,
    WARNING,
    ERROR,
    DISCONNECTED,
}

data class CertificateCandidate(
    val target: CertificateTarget,
    val host: String,
    val port: Int,
    val subject: String,
    val issuer: String,
    val fingerprintSha256: String,
)

enum class CertificateTarget { API, MQTT }

data class ConnectionStatus(
    val desiredConnected: Boolean = false,
    val apiStatus: LinkStatus = LinkStatus.DISCONNECTED,
    val mqttStatus: LinkStatus = LinkStatus.NOT_CHECKED,
    val profileName: String? = null,
    val message: String? = null,
    val retryAttempt: Int = 0,
    val certificateCandidate: CertificateCandidate? = null,
) {
    val isApiUsable: Boolean get() = apiStatus == LinkStatus.CONNECTED || apiStatus == LinkStatus.WARNING
}

sealed interface MqttBoxEvent {
    val boxId: String
    val timestampMillis: Long

    data class Value(
        override val boxId: String,
        val field: String,
        val value: String,
        override val timestampMillis: Long = System.currentTimeMillis(),
    ) : MqttBoxEvent
}
