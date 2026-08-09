package de.teddycloud.teddyremote.mqtt

import de.teddycloud.teddyremote.model.MqttBoxEvent

object MqttTopicParser {
    private val boxIdPattern = Regex("^[0-9A-Fa-f]{12}$")

    fun parse(prefix: String, topic: String, payload: ByteArray): MqttBoxEvent? {
        val normalizedPrefix = prefix.trim('/').split('/').filter(String::isNotBlank)
        val segments = topic.trim('/').split('/')
        if (segments.size != normalizedPrefix.size + 3) return null
        if (segments.take(normalizedPrefix.size) != normalizedPrefix) return null
        if (segments[normalizedPrefix.size] != "box") return null
        val boxId = segments[normalizedPrefix.size + 1]
        if (!boxIdPattern.matches(boxId)) return null
        val field = segments[normalizedPrefix.size + 2]
        val value = payload.decodeToString().trim()
        return MqttBoxEvent.Value(boxId.uppercase(), field, value)
    }
}
