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
    fun `sends exact minimum and maximum volume commands`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"message":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"message":"queued"}"""))

        val minimumResponse = client.setVolume("D4594404DEAC", 1)
        val minimumRequest = server.takeRequest()
        val maximumResponse = client.setVolume("D4594404DEAC", 12)
        val maximumRequest = server.takeRequest()

        assertTrue(minimumResponse.ok)
        assertTrue(maximumResponse.ok)
        assertEquals("/api/box/volume?overlay=D4594404DEAC", minimumRequest.path)
        assertEquals("{\"level\":1}", minimumRequest.body.readUtf8())
        assertEquals("{\"level\":12}", maximumRequest.body.readUtf8())
    }

    @Test
    fun `rejects volume outside the TB2 range before the request`() = runTest {
        var rejected = 0
        for (level in listOf(0, 13)) {
            try {
                client.setVolume("D4594404DEAC", level)
            } catch (_: IllegalArgumentException) {
                rejected++
            }
        }

        assertEquals(2, rejected)
        assertEquals(0, server.requestCount)
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
    fun `imports remote MQTT settings using the public TeddyCloud host`() = runTest {
        listOf(
            "https;//tbs2.tonie.cloud:8443/",
            "true",
            "1883",
            "TeddyUser",
            " TeddyPassword ",
            "teddyCloud",
            "true",
        ).forEach { server.enqueue(MockResponse().setBody(it)) }

        val imported = client.getMqttSettingsForRemote()

        assertTrue(imported.enabled)
        assertEquals("tbs2.tonie.cloud", imported.host)
        assertEquals(1883, imported.port)
        assertEquals("TeddyUser", imported.username)
        assertEquals(" TeddyPassword ", imported.password)
        assertEquals("teddyCloud", imported.prefix)
        assertTrue(imported.tlsEnabled)
        assertEquals(
            listOf(
                "/api/settings/get/core.host_url",
                "/api/settings/get/mqtt.enabled",
                "/api/settings/get/mqtt.port",
                "/api/settings/get/mqtt.username",
                "/api/settings/get/mqtt.password",
                "/api/settings/get/mqtt.topic",
                "/api/settings/get/mqtt.tls_enabled",
            ),
            List(7) { server.takeRequest().path },
        )
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
