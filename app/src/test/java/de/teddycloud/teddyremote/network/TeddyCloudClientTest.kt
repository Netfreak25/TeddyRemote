package de.teddycloud.teddyremote.network

import de.teddycloud.teddyremote.model.ConnectionProfile
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeddyCloudClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TeddyCloudClient

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        client = TeddyCloudClient.create(
            ConnectionProfile(name = "Test", apiBaseUrl = server.url("/").toString()),
        )
    }

    @After
    fun cleanup() {
        server.shutdown()
    }

    @Test
    fun `loads runtime snapshot and generation setting`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"boxes":[{"ID":"D4594404DEAC","commonName":"D4594404DEAC","boxName":"Blau","boxModel":"tb2-blue","runtime":{"online":true,"controls":{"bedtime":true,"sleep":true},"playback":{"valid":true,"status":"playing","ruid":"28F28F11500304E0"},"volume":{"valid":true,"level":5}}}]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("2"))

        val box = client.getBoxes().boxes.single()
        val generation = client.getBoxGeneration(box.id)

        assertTrue(box.runtime.playback.isPlaying)
        assertEquals(5, box.runtime.volume.level)
        assertTrue(box.runtime.controls.bedtime)
        assertTrue(box.runtime.controls.sleep)
        assertEquals(2, generation)
        assertEquals("/api/getBoxes", server.takeRequest().path)
        assertEquals("/api/settings/get/toniebox.boxGeneration?overlay=D4594404DEAC", server.takeRequest().path)
    }

    @Test
    fun `sends bounded volume command`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"message":"queued"}"""))

        val response = client.setVolume("D4594404DEAC", 99)
        val request = server.takeRequest()

        assertTrue(response.ok)
        assertEquals("/api/box/volume?overlay=D4594404DEAC", request.path)
        assertEquals("{\"level\":10}", request.body.readUtf8())
    }

    @Test
    fun `sends bedtime and sleep commands with exact payloads`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"message":"bedtime queued"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"message":"sleep queued"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"message":"bedtime stopped"}"""))

        assertTrue(client.setBedtime("D4594404DEAC", 300).ok)
        assertTrue(client.sleep("D4594404DEAC").ok)
        assertTrue(client.cancelBedtime("D4594404DEAC").ok)

        server.takeRequest().let { request ->
            assertEquals("/api/box/bedtime?overlay=D4594404DEAC", request.path)
            assertEquals("{\"state\":\"on\",\"duration\":300}", request.body.readUtf8())
        }
        server.takeRequest().let { request ->
            assertEquals("/api/box/sleep?overlay=D4594404DEAC", request.path)
            assertEquals("{}", request.body.readUtf8())
        }
        server.takeRequest().let { request ->
            assertEquals("/api/box/bedtime?overlay=D4594404DEAC", request.path)
            assertEquals("{\"state\":\"off\"}", request.body.readUtf8())
        }
    }

    @Test
    fun `reads and updates bedtime ring brightness`() = runTest {
        server.enqueue(MockResponse().setBody("75"))
        server.enqueue(MockResponse().setBody("ok"))

        assertEquals(75, client.getBedtimeRingBrightness("D4594404DEAC"))
        client.setBedtimeRingBrightness("D4594404DEAC", 42)

        assertEquals(
            "/api/settings/get/toniebox2.bedtime_lightring_brightness?overlay=D4594404DEAC",
            server.takeRequest().path,
        )
        server.takeRequest().let { request ->
            assertEquals(
                "/api/settings/set/toniebox2.bedtime_lightring_brightness?overlay=D4594404DEAC",
                request.path,
            )
            assertEquals("42", request.body.readUtf8())
        }
    }

    @Test
    fun `parses playlist and title from tag info`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"tagInfo":{"tonieInfo":{"series":"Serie","episode":"Original","picture":"/img.png","tracks":["Alt"]},"playlist":{"title":"Lokal","chapterCount":2,"tracks":["Eins","Zwei"],"durations":[61,125]}}}""",
            ),
        )

        val metadata = client.getTonieMetadata("D4594404DEAC", "28F28F11500304E0", 42)

        assertEquals("Original", metadata.title)
        assertEquals(listOf("Eins", "Zwei"), metadata.playlist.map { it.title })
        assertEquals(125L, metadata.playlist[1].durationSeconds)
    }

    @Test
    fun `keeps physical tonie identity while using active source playlist`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"tagInfo":{"tonieInfo":{"series":"Originalserie","episode":"Originaltitel","picture":"/original.png","tracks":["Originalkapitel"]},"sourceInfo":{"series":"Eigene Serie","title":"Eigener Inhalt","picture":"/custom.png","tracks":["Eigenes Kapitel 1","Eigenes Kapitel 2"]}}}""",
            ),
        )

        val metadata = client.getTonieMetadata("D4594404DEAC", "28F28F11500304E0", 43)

        assertEquals("Originaltitel", metadata.title)
        assertEquals("Originalserie", metadata.subtitle)
        assertEquals(server.url("/original.png").toString(), metadata.pictureUrl)
        assertEquals(listOf("Eigenes Kapitel 1", "Eigenes Kapitel 2"), metadata.playlist.map { it.title })
    }

    @Test
    fun `uses track starts as chapter fallback for regular content`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"tagInfo":{"tonieInfo":{"episode":"Original"},"trackSeconds":[0,61,125]}}""",
            ),
        )

        val metadata = client.getTonieMetadata("D4594404DEAC", "28F28F11500304E0", 44)

        assertEquals(listOf("Kapitel 1", "Kapitel 2", "Kapitel 3"), metadata.playlist.map { it.title })
        assertEquals(listOf(61L, 64L, null), metadata.playlist.map { it.durationSeconds })
    }

    @Test
    fun `empty playlist tracks do not hide source tracks`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"tagInfo":{"playlist":{"tracks":[]},"sourceInfo":{"tracks":["Quelle 1","Quelle 2"]}}}""",
            ),
        )

        val metadata = client.getTonieMetadata("D4594404DEAC", "28F28F11500304E0", 45)

        assertEquals(listOf("Quelle 1", "Quelle 2"), metadata.playlist.map { it.title })
    }

    @Test
    fun `downloads TeddyCloud artwork with the API client`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(png)))

        val result = client.downloadImage(server.url("/cache/box.png").toString())

        assertTrue(png.contentEquals(result))
        assertEquals("/cache/box.png", server.takeRequest().path)
    }
}
