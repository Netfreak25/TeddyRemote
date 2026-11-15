package de.teddycloud.teddyremote.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** A single foreground operation; cancellation closes even a response body being read. */
internal class GitHubUpdateClient(
    private val http: () -> OkHttpClient,
    private val directory: File,
    private val latestUrl: String = UpdatePolicy.LATEST_URL,
) {
    @Volatile private var activeCall: Call? = null

    fun cancel() { activeCall?.cancel() }

    suspend fun latest(installedVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        cleanup()
        request(latestUrl) { response ->
            if (response.code == 404) return@request null
            checkResponse(response)
            val body = requireNotNull(response.body) { "GitHub hat keine Release-Daten geliefert." }
            val buffer = body.source()
            require(!buffer.request(UpdatePolicy.MAX_RELEASE_BYTES + 1)) { "Die GitHub-Antwort ist zu groß." }
            UpdatePolicy.selectRelease(buffer.readUtf8(), installedVersion)
        }
    }

    suspend fun download(release: AppRelease, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        require(release.size in 1..UpdatePolicy.MAX_APK_BYTES) { "Ungültige APK-Größe." }
        check(directory.isDirectory || directory.mkdirs()) { "Update-Verzeichnis konnte nicht angelegt werden." }
        val partial = File.createTempFile("update-", ".part", directory)
        val complete = File(directory, partial.name.removeSuffix(".part") + ".apk")
        try {
            val context = coroutineContext
            request(release.downloadUrl) { response ->
                checkResponse(response)
                require(response.code == 200) { "GitHub hat keine vollständige APK geliefert." }
                val body = requireNotNull(response.body) { "Der APK-Download ist leer." }
                require(body.contentLength() == -1L || body.contentLength() == release.size) { "Die APK-Downloadgröße stimmt nicht überein." }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastProgress = -1
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(bytes)
                            if (count == -1) break
                            received += count
                            require(received <= release.size) { "Der Download überschreitet die angekündigte APK-Größe." }
                            output.write(bytes, 0, count)
                            digest.update(bytes, 0, count)
                            val progress = (received * 100 / release.size).toInt()
                            if (progress != lastProgress) { onProgress(progress); lastProgress = progress }
                        }
                    }
                }
                require(received == release.size) { "Der APK-Download ist unvollständig." }
                release.sha256?.let { require(digest.digest().hex() == it) { "Die SHA-256-Prüfsumme der APK stimmt nicht überein." } }
            }
            coroutineContext.ensureActive()
            check(partial.renameTo(complete)) { "Die fertige APK konnte nicht bereitgestellt werden." }
            complete
        } catch (error: Throwable) {
            complete.delete()
            throw error
        } finally {
            partial.delete()
        }
    }

    private suspend fun <T> request(url: String, consume: (Response) -> T): T = coroutineScope {
        coroutineContext.ensureActive()
        val call = http().newCall(Request.Builder().url(url)
            .header("Accept", if (url == latestUrl) "application/vnd.github+json" else "application/octet-stream")
            .header("User-Agent", "TeddyRemote-Updater").build())
        activeCall = call
        // Also handles cancellation before the caller has seen activeCall and while read() blocks.
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            coroutineContext.ensureActive()
            call.execute().use(consume)
        } finally { activeCall = null; cancellation.cancel() }
    }

    private fun checkResponse(response: Response) {
        check(response.isSuccessful) {
            when (response.code) {
                403, 429 -> "GitHub begrenzt die Anfragen. Bitte später erneut versuchen."
                else -> "GitHub-Anfrage fehlgeschlagen (HTTP ${response.code})."
            }
        }
    }

    private fun cleanup() {
        directory.listFiles()?.filter { it.name.startsWith("update-") && it.extension in setOf("part", "apk") }
            ?.forEach { check(it.delete()) { "Alte Update-Datei konnte nicht entfernt werden." } }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
