package de.teddycloud.teddyremote.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistResolverTest {
    private fun resolve(json: String, version: Long? = null) =
        PlaylistResolver.resolve(Json.parseToJsonElement(json).jsonObject, version)

    @Test fun `native structure is unknown without an explicit count`() {
        val result = resolve("""{"playlist":{"kind":"native_collection","contentVersion":9,"tracks":["Old A","Old B"]},"tonieInfo":{"tracks":["Wrong"]}}""", 9)
        assertTrue(result.tracks.isEmpty())
    }

    @Test fun `native response without requested version cannot label the playing content`() {
        val result = resolve("""{"playlist":{"kind":"native_collection","chapterCount":2,"tracks":["Wrong A","Wrong B"]}}""", 9)
        assertTrue(result.tracks.isEmpty())
    }

    @Test fun `intro outro candidate retains real indices and chapter durations`() {
        val result = resolve("""{"sourceInfo":{"tracks":["A","B"]},"trackSeconds":[0,10,70,140],"totalSeconds":150}""")
        assertEquals(listOf("A", "B", "Kapitel 3", "Kapitel 4"), result.tracks.map { it.title })
        val candidate = requireNotNull(result.introOutroTracks)
        assertEquals(listOf("Intro", "A", "B", "Outro"), candidate.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), candidate.map { it.index })
        assertEquals(listOf(10_000L, 60_000L, 70_000L, 10_000L), candidate.map { it.durationMs })
    }

    @Test fun `complete explicit chapter durations retain priority in candidate`() {
        val result = resolve("""{"playlist":{"chapterCount":4,"tracks":["A","B"],"durations":[11,61,71,12]},"trackSeconds":[0,10,70,140],"totalSeconds":150}""")
        assertEquals(listOf(11_000L, 61_000L, 71_000L, 12_000L), result.introOutroTracks?.map { it.durationMs })
    }

    @Test fun `short duration array is not shifted or assigned to intro`() {
        val result = resolve("""{"playlist":{"chapterCount":4,"tracks":["A","B"],"durations":[60,70]},"trackSeconds":[0,10,70,140],"totalSeconds":150}""")
        assertEquals(listOf(60_000L, 70_000L, 70_000L, 10_000L), result.tracks.map { it.durationMs })
        assertEquals(listOf(10_000L, 60_000L, 70_000L, 10_000L), result.introOutroTracks?.map { it.durationMs })
        val unknown = resolve("""{"playlist":{"chapterCount":4,"tracks":["A","B"],"durations":[60,70]}}""")
        assertTrue(requireNotNull(unknown.introOutroTracks).all { it.durationMs == null })
    }

    @Test fun `inferred outro stays unknown without total duration`() {
        val result = resolve("""{"tonieInfo":{"tracks":["A","B"]},"trackSeconds":[0,10,70,140]}""")
        assertEquals(listOf(10_000L, 60_000L, 70_000L, null), result.introOutroTracks?.map { it.durationMs })
    }

    @Test fun `only exactly two missing complete names qualify`() {
        val ineligible = listOf(
            """{"playlist":{"chapterCount":2,"tracks":["A","B"]}}""",
            """{"playlist":{"chapterCount":3,"tracks":["A","B"]}}""",
            """{"playlist":{"chapterCount":5,"tracks":["A","B"]}}""",
            """{"playlist":{"chapterCount":2,"tracks":[]}}""",
            """{"playlist":{"tracks":["A","B"]}}""",
            """{"playlist":{"chapterCount":4,"tracks":["A",null]}}""",
            """{"playlist":{"chapterCount":4,"tracks":["A",""]}}""",
            """{"playlist":{"chapterCount":4,"tracks":["A","  "]}}""",
            """{"playlist":{"chapterCount":4,"tracks":["A",{}]}}""",
            """{"playlist":{"chapterCount":4,"tracks":["A",12]}}""",
            """{"playlist":{"chapterCount":4,"tracks":["A",null,"B",null]}}""",
        )
        ineligible.forEach { assertNull(it, resolve(it).introOutroTracks) }
    }

    @Test fun `candidate respects version source isolation and authoritative count`() {
        assertNull(resolve("""{"playlist":{"contentVersion":9,"chapterCount":4,"tracks":["A","B"]}}""", 10).introOutroTracks)
        assertNull(resolve("""{"playlist":{"kind":"direct_taf","chapterCount":4},"tonieInfo":{"tracks":["Wrong","Wrong"]}}""").introOutroTracks)
        assertNull(resolve("""{"playlist":{"chapterCount":2,"tracks":["A","B"]},"trackSeconds":[0,10,20,30]}""").introOutroTracks)
        val native = resolve("""{"playlist":{"kind":"native_collection","chapterCount":4,"tracks":["A","B"],"durations":[5,30,40,6]},"trackSeconds":[0,10,20,30]}""")
        assertNull(native.introOutroTracks)
        assertEquals(listOf("A", "B", "Kapitel 3", "Kapitel 4"), native.tracks.map { it.title })
        assertEquals(listOf(5_000L, 30_000L, 40_000L, 6_000L), native.tracks.map { it.durationMs })
    }

    @Test fun `last chapter uses total seconds without requiring names`() {
        val result = resolve("""{"trackSeconds":[0,61,125],"totalSeconds":200}""")
        assertEquals(listOf(61_000L, 64_000L, 75_000L), result.tracks.map { it.durationMs })
        assertEquals("Kapitel 3", result.tracks.last().title)
        assertTrue(result.complete)
    }

    @Test fun `invalid entries never shift names or durations`() {
        val result = resolve("""{"playlist":{"chapterCount":3,"tracks":["A",null,"C"],"durations":[12,{},34]}}""")
        assertEquals(listOf("A", "Kapitel 2", "C"), result.tracks.map { it.title })
        assertEquals(listOf(12_000L, null, 34_000L), result.tracks.map { it.durationMs })
        assertFalse(result.complete)
    }

    @Test fun `actual count wins over longer catalogue and starts`() {
        assertEquals(2, resolve("""{"playlist":{"chapterCount":2},"trackSeconds":[0,10,20],"tonieInfo":{"tracks":["A","B","C","D"]}}""").tracks.size)
        assertEquals(0, resolve("""{"playlist":{"chapterCount":0},"trackSeconds":[0,10]}""").tracks.size)
        assertEquals(2, resolve("""{"trackSeconds":[0,10],"tonieInfo":{"tracks":["A","B","C"]}}""").tracks.size)
    }

    @Test fun `unknown last duration and invalid starts stay unknown`() {
        assertEquals(listOf(10_000L, null), resolve("""{"trackSeconds":[0,10]}""").tracks.map { it.durationMs })
        assertEquals(listOf(null, null, null), resolve("""{"trackSeconds":[0,null,20],"totalSeconds":19}""").tracks.map { it.durationMs })
    }

    @Test fun `custom and native content never borrow figurine chapters or TAF offsets`() {
        val result = resolve("""{"playlist":{"kind":"native_collection","chapterCount":2},"trackSeconds":[0,10],"totalSeconds":20,"tonieInfo":{"tracks":["Wrong","Wrong"]}}""")
        assertEquals(listOf("Kapitel 1", "Kapitel 2"), result.tracks.map { it.title })
        assertEquals(listOf(null, null), result.tracks.map { it.durationMs })
    }

    @Test fun `mismatched generation has no usable playlist`() {
        assertTrue(resolve("""{"playlist":{"contentVersion":9,"chapterCount":3}}""", 10).tracks.isEmpty())
    }

    @Test fun `single chapter and explicit durations use trustworthy timing only`() {
        assertEquals(75_000L, resolve("""{"trackSeconds":[0],"totalSeconds":75}""").tracks.single().durationMs)
        assertEquals(80_000L, resolve("""{"playlist":{"chapterCount":1,"durations":[80]},"trackSeconds":[0],"totalSeconds":75}""").tracks.single().durationMs)
        assertNull(resolve("""{"trackSeconds":[0],"totalSeconds":-5}""").tracks.single().durationMs)
        assertNull(resolve("""{"playlist":{"chapterCount":1},"trackSeconds":[0,10],"totalSeconds":75}""").tracks.single().durationMs)
    }

    @Test fun `native counts never come from figurine titles or old TAF starts`() {
        for (count in listOf(24, 78)) {
            val result = resolve("""{"playlist":{"kind":"native_collection","contentVersion":42,"chapterCount":$count},"tonieInfo":{"tracks":["Wrong A","Wrong B"]},"trackSeconds":[0,5],"totalSeconds":10}""", 42)
            assertEquals(count, result.tracks.size)
            assertTrue(result.tracks.all { it.title == "Kapitel ${it.index + 1}" && it.durationMs == null })
            assertNull(result.introOutroTracks)
        }
    }

    @Test fun `native decimal durations and missing names preserve exact positions`() {
        val result = resolve("""{"playlist":{"kind":"native_collection","contentVersion":42,"chapterCount":4,"tracks":["A","",null,"D"],"durations":[1.025,null,2.75,0.006]}}""", 42)
        assertEquals(listOf("A", "Kapitel 2", "Kapitel 3", "D"), result.tracks.map { it.title })
        assertEquals(listOf(1025L, null, 2750L, 6L), result.tracks.map { it.durationMs })
        assertFalse(result.complete)
        assertNull(result.introOutroTracks)
    }

    @Test fun `unsupported or overflowing duration values remain unknown`() {
        val result = resolve("""{"playlist":{"kind":"native_collection","contentVersion":42,"chapterCount":5,"durations":[-1,"12",{},1e309,1e20]}}""", 42)
        assertTrue(result.tracks.all { it.durationMs == null })
    }

    @Test fun `old backend without native structure does not expose figure chapters`() {
        assertTrue(resolve("""{"tonieInfo":{"tracks":["Wrong A","Wrong B"]}}""", 42).tracks.isEmpty())
        // Unversioned TAF responses from older backends remain supported.
        assertEquals(2, resolve("""{"playlist":{"kind":"direct_taf"},"trackSeconds":[0,5],"totalSeconds":10}""", 42).tracks.size)
    }
}
