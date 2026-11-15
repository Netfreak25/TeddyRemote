package de.teddycloud.teddyremote.updates

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GitHubUpdateClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: GitHubUpdateClient
    private lateinit var directory: File
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        directory = temporary.newFolder()
        client = GitHubUpdateClient({ OkHttpClient() }, directory, server.url("/latest").toString())
    }
    @After fun close() { server.shutdown() }

    @Test fun `only a complete hash checked download becomes an apk`() = runTest {
        server.enqueue(MockResponse().setBody("apk bytes"))
        val release = AppRelease("0.1.34", "", "release.apk", server.url("/apk").toString(), 9,
            MessageDigest.getInstance("SHA-256").digest("apk bytes".toByteArray()).hex())
        val progress = mutableListOf<Int>()
        val file = client.download(release) { progress += it }
        assertEquals("apk bytes", file.readText())
        assertEquals("apk", file.extension)
        assertEquals(100, progress.last())
        assertFalse(directory.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun `incomplete downloads and bad digests are removed`() = runTest {
        for ((size, hash) in listOf(20L to null, 3L to "0".repeat(64))) {
            server.enqueue(MockResponse().setBody("apk"))
            var failed = false
            try { client.download(AppRelease("0.1.34", "", "release.apk", server.url("/apk").toString(), size, hash)) {} }
            catch (_: IllegalArgumentException) { failed = true }
            assertTrue(failed)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun `check cleans only updater artifacts and missing release is not an update`() = runTest {
        File(directory, "update-old.apk").writeText("old")
        File(directory, "update-old.part").writeText("partial")
        val unrelated = File(directory, "unrelated.txt").apply { writeText("keep") }
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.latest("0.1.33"))
        assertEquals(listOf(unrelated), directory.listFiles()!!.toList())
    }

    // MockWebServer throttles real sockets, so this timeout must use wall-clock time.
    @Test fun `cancelling a streaming download removes its partial file`() = runBlocking {
        val bytes = "x".repeat(8192)
        server.enqueue(MockResponse().setBody(bytes).throttleBody(1024, 200, TimeUnit.MILLISECONDS))
        val started = CompletableDeferred<Unit>()
        val download = async(Dispatchers.IO) {
            runCatching {
                client.download(AppRelease("0.1.34", "", "release.apk", server.url("/apk").toString(), bytes.length.toLong())) {
                    if (it > 0) started.complete(Unit)
                }
            }
        }
        withTimeout(5000) { started.await() }
        client.cancel()
        assertTrue(download.await().isFailure)
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
