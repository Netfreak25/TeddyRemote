package de.teddycloud.teddyremote.network

import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.BoxRuntime
import de.teddycloud.teddyremote.model.MetadataKey
import de.teddycloud.teddyremote.model.PlaybackRuntime
import de.teddycloud.teddyremote.model.TonieboxDto
import de.teddycloud.teddyremote.repository.BoxStateReducer
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
    fun `sends chapter-relative seek position without remapping`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"message":"queued"}"""))

        val response = client.seek("D4594404DEAC", chapter = 2, positionMs = 90_000L)
        val request = server.takeRequest()

        assertTrue(response.ok)
        assertEquals("/api/box/playback?overlay=D4594404DEAC", request.path)
        assertEquals("{\"action\":\"setPosition\",\"chapter\":2,\"ms\":90000}", request.body.readUtf8())
    }

    @Test
    fun `rejects invalid seek values before the request`() = runTest {
        val invalidOperations: List<suspend () -> Unit> = listOf(
            { client.seek("D4594404DEAC", chapter = -1, positionMs = 0L) },
            { client.seek("D4594404DEAC", chapter = 0, positionMs = -1L) },
            { client.seek("D4594404DEAC", chapter = 0, positionMs = 0x1_0000_0000L) },
        )
        invalidOperations.forEach { operation ->
            runCatching { operation() }.onSuccess { error("Invalid seek was accepted") }
        }

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
        assertEquals(125_000L, metadata.playlist[1].durationMs)
    }

    @Test
    fun `keeps figurine identity but rejects unverified old backend source chapters`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"tagInfo":{"tonieInfo":{"series":"Originalserie","episode":"Originaltitel","picture":"/original.png","tracks":["Originalkapitel"]},"sourceInfo":{"series":"Eigene Serie","title":"Eigener Inhalt","picture":"/custom.png","tracks":["Eigenes Kapitel 1","Eigenes Kapitel 2"]}}}""",
            ),
        )

        val metadata = client.getTonieMetadata("D4594404DEAC", "28F28F11500304E0", 43)

        assertEquals("Originaltitel", metadata.title)
        assertEquals("Originalserie", metadata.figureSubtitle)
        assertEquals("", metadata.subtitle)
        assertEquals(server.url("/original.png").toString(), metadata.pictureUrl)
        assertTrue(metadata.playlist.isEmpty())
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
        assertEquals(listOf(61_000L, 64_000L, null), metadata.playlist.map { it.durationMs })
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

    @Test
    fun `versioned native content keeps precise durations and its own title`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"tagInfo":{"tonieInfo":{"episode":"Figur","series":"Figurenserie"},"playlist":{"kind":"native_collection","contentVersion":42,"chapterCount":3,"title":"Neue Folgen","tracks":["A","","C"],"durations":[1.625,2.625,3.625],"metadataState":"ready","metadataRevision":"rev42"}}}""",
        ))
        val metadata = client.getTonieMetadata("BOX", "28F28F11500304E0", 42, "rev42")
        assertEquals("Neue Folgen", metadata.title)
        assertEquals("Figur", metadata.figureTitle)
        assertEquals("Figurenserie", metadata.figureSubtitle)
        assertEquals("", metadata.subtitle)
        assertEquals(listOf("A", "Kapitel 2", "C"), metadata.playlist.map { it.title })
        assertEquals(listOf(1625L, 2625L, 3625L), metadata.playlist.map { it.durationMs })
        assertEquals(7875L, metadata.totalDurationMs)
        assertEquals("ready", metadata.metadataState)
        assertEquals("rev42", metadata.key?.metadataRevision)
        assertTrue(metadata.introOutroPlaylist == null)
    }

    @Test
    fun `MQTT content switch accepts the new native playlist without waiting for a box snapshot`() = runTest {
        val original = BoxRuntime(playback = PlaybackRuntime(
            ruid = "AAAAAAAAAAAAAAAA", contentVersion = 42, metadataRevision = "revision-A",
        ))
        val ruidChange = "PlaybackRuid" to "BBBBBBBBBBBBBBBB"
        val versionChange = "PlaybackContentVersion" to "43"
        val sequences = listOf(
            listOf(ruidChange), listOf(versionChange),
            listOf(ruidChange, versionChange), listOf(versionChange, ruidChange),
        )
        for (events in sequences) {
            val runtime = events.fold(original) { state, (field, value) ->
                BoxStateReducer.reduce(state, field, value, 101)
            }
            val key = requireNotNull(MetadataKey.forBox(client.profileId, TonieboxDto("BOX", runtime = runtime)))
            server.enqueue(MockResponse().setBody(
                """{"tagInfo":{"playlist":{"kind":"native_collection","contentVersion":${key.contentVersion},"metadataRevision":"revision-B","chapterCount":1,"title":"Content B","tracks":["New chapter"],"durations":[1.25]}}}""",
            ))
            val metadata = client.getTonieMetadata("BOX", key.ruid, key.contentVersion, key.metadataRevision)

            // The repository accepts a metadata response only when this key matches.
            assertEquals("New content must not be rejected for $events", key, metadata.key)
            assertEquals("revision-B", metadata.metadataRevision)
            assertEquals("Content B", metadata.title)
            assertEquals("New chapter", metadata.playlist.single().title)
            assertEquals(1250L, metadata.playlist.single().durationMs)
        }
        assertEquals(sequences.size, server.requestCount)
    }

    @Test
    fun `incomplete native duration has no fabricated total and carries enrichment state`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"tagInfo":{"playlist":{"kind":"native_collection","contentVersion":42,"chapterCount":2,"durations":[1.25,null],"metadataState":"partial","metadataReason":"audio_missing","metadataRevision":"new"}}}""",
        ))
        val metadata = client.getTonieMetadata("BOX", "28F28F11500304E0", 42, "old")
        assertEquals("partial", metadata.metadataState)
        assertEquals("audio_missing", metadata.metadataReason)
        assertEquals("new", metadata.key?.metadataRevision)
        assertTrue(metadata.totalDurationMs == null)
    }

    @Test
    fun `native response for another version does not advertise its titles`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"tagInfo":{"tonieInfo":{"episode":"Figur"},"playlist":{"kind":"native_collection","contentVersion":43,"chapterCount":2,"title":"Falsche Fassung","tracks":["A","B"]}}}""",
        ))
        val metadata = client.getTonieMetadata("BOX", "28F28F11500304E0", 42)
        assertTrue(metadata.playlist.isEmpty())
        assertEquals("Figur", metadata.title)
        assertEquals("unavailable", metadata.metadataState)
    }

    @Test
    fun `runtime carries an additive metadata revision`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"boxes":[{"ID":"BOX","runtime":{"playback":{"ruid":"28F28F11500304E0","contentVersion":42,"metadataRevision":"enriched"}}}]}""",
        ))
        assertEquals("enriched", client.getBoxes().boxes.single().runtime.playback.metadataRevision)
    }

    @Test
    fun `returns normal and inferred playlist from the same single API response`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"tagInfo":{"tonieInfo":{"episode":"Figur","picture":"/original.png"},"sourceInfo":{"tracks":["A","B"]},"trackSeconds":[0,10,70,140],"totalSeconds":150}}""",
        ))
        val metadata = client.getTonieMetadata("BOX", "28F28F11500304E0", 46)
        assertEquals("Figur", metadata.title)
        assertEquals(listOf("A", "B", "Kapitel 3", "Kapitel 4"), metadata.playlist.map { it.title })
        assertEquals(listOf("Intro", "A", "B", "Outro"), metadata.introOutroPlaylist?.map { it.title })
        assertEquals(listOf(10_000L, 60_000L, 70_000L, 10_000L), metadata.introOutroPlaylist?.map { it.durationMs })
        assertEquals(1, server.requestCount)
    }
}
