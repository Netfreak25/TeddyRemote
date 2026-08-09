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
                """{"boxes":[{"ID":"D4594404DEAC","commonName":"D4594404DEAC","boxName":"Blau","boxModel":"tb2-blue","runtime":{"online":true,"playback":{"valid":true,"status":"playing","ruid":"28F28F11500304E0"},"volume":{"valid":true,"level":5}}}]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("2"))

        val box = client.getBoxes().boxes.single()
        val generation = client.getBoxGeneration(box.id)

        assertTrue(box.runtime.playback.isPlaying)
        assertEquals(5, box.runtime.volume.level)
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
    fun `downloads TeddyCloud artwork with the API client`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(png)))

        val result = client.downloadImage(server.url("/cache/box.png").toString())

        assertTrue(png.contentEquals(result))
        assertEquals("/cache/box.png", server.takeRequest().path)
    }
}
