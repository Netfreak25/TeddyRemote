package de.teddycloud.teddyremote.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistResolverTest {
    private fun resolve(json: String, version: Long? = null) =
        PlaylistResolver.resolve(Json.parseToJsonElement(json).jsonObject, version)

    @Test fun `last chapter uses total seconds without requiring names`() {
        val result = resolve("""{"trackSeconds":[0,61,125],"totalSeconds":200}""")
        assertEquals(listOf(61L, 64L, 75L), result.tracks.map { it.durationSeconds })
        assertEquals("Kapitel 3", result.tracks.last().title)
        assertTrue(result.complete)
    }

    @Test fun `invalid entries never shift names or durations`() {
        val result = resolve("""{"playlist":{"chapterCount":3,"tracks":["A",null,"C"],"durations":[12,{},34]}}""")
        assertEquals(listOf("A", "Kapitel 2", "C"), result.tracks.map { it.title })
        assertEquals(listOf(12L, null, 34L), result.tracks.map { it.durationSeconds })
        assertFalse(result.complete)
    }

    @Test fun `actual count wins over longer catalogue and starts`() {
        assertEquals(2, resolve("""{"playlist":{"chapterCount":2},"trackSeconds":[0,10,20],"tonieInfo":{"tracks":["A","B","C","D"]}}""").tracks.size)
        assertEquals(0, resolve("""{"playlist":{"chapterCount":0},"trackSeconds":[0,10]}""").tracks.size)
        assertEquals(2, resolve("""{"trackSeconds":[0,10],"tonieInfo":{"tracks":["A","B","C"]}}""").tracks.size)
    }

    @Test fun `unknown last duration and invalid starts stay unknown`() {
        assertEquals(listOf(10L, null), resolve("""{"trackSeconds":[0,10]}""").tracks.map { it.durationSeconds })
        assertEquals(listOf(null, null, null), resolve("""{"trackSeconds":[0,null,20],"totalSeconds":19}""").tracks.map { it.durationSeconds })
    }

    @Test fun `custom and native content never borrow figurine chapters or TAF offsets`() {
        val result = resolve("""{"playlist":{"kind":"native_collection","chapterCount":2},"trackSeconds":[0,10],"totalSeconds":20,"tonieInfo":{"tracks":["Wrong","Wrong"]}}""")
        assertEquals(listOf("Kapitel 1", "Kapitel 2"), result.tracks.map { it.title })
        assertEquals(listOf(null, null), result.tracks.map { it.durationSeconds })
    }

    @Test fun `mismatched generation has no usable playlist`() {
        assertTrue(resolve("""{"playlist":{"contentVersion":9,"chapterCount":3}}""", 10).tracks.isEmpty())
    }

    @Test fun `single chapter and explicit durations use trustworthy timing only`() {
        assertEquals(75L, resolve("""{"trackSeconds":[0],"totalSeconds":75}""").tracks.single().durationSeconds)
        assertEquals(80L, resolve("""{"playlist":{"chapterCount":1,"durations":[80]},"trackSeconds":[0],"totalSeconds":75}""").tracks.single().durationSeconds)
        assertNull(resolve("""{"trackSeconds":[0],"totalSeconds":-5}""").tracks.single().durationSeconds)
        assertNull(resolve("""{"playlist":{"chapterCount":1},"trackSeconds":[0,10],"totalSeconds":75}""").tracks.single().durationSeconds)
    }
}
