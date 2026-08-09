package de.teddycloud.teddyremote.mqtt

import de.teddycloud.teddyremote.model.MqttBoxEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MqttTopicParserTest {
    @Test
    fun `parses TB2 box event with nested prefix`() {
        val event = MqttTopicParser.parse(
            "home/teddyCloud",
            "home/teddyCloud/box/d4594404deac/PlaybackStatus",
            "playing".encodeToByteArray(),
        ) as MqttBoxEvent.Value

        assertEquals("D4594404DEAC", event.boxId)
        assertEquals("PlaybackStatus", event.field)
        assertEquals("playing", event.value)
    }

    @Test
    fun `ignores unknown topic shapes and invalid box ids`() {
        assertNull(MqttTopicParser.parse("teddyCloud", "teddyCloud/status", byteArrayOf()))
        assertNull(MqttTopicParser.parse("teddyCloud", "teddyCloud/box/default/VolumeLevel", byteArrayOf()))
    }
}
