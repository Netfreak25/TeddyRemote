package de.teddycloud.teddyremote.repository

import android.content.Context
import android.net.Uri
import de.teddycloud.teddyremote.network.TeddyCloudClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Stores remote artwork as immutable local files shared by UI and media sessions. */
class RemoteImageCache(context: Context) {
    private val root = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun materialize(url: String?, client: TeddyCloudClient): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("file:", ignoreCase = true)) return url
        val key = url.sha256()
        val target = File(root, key)
        if (target.isFile && target.length() > 0L) return Uri.fromFile(target).toString()

        val mutex = locks.getOrPut(key) { Mutex() }
        return try {
            mutex.withLock {
                if (target.isFile && target.length() > 0L) return@withLock Uri.fromFile(target).toString()
                val bytes = withContext(Dispatchers.IO) { client.downloadImage(url) } ?: return@withLock url
                val staging = File(root, ".$key.tmp")
                withContext(Dispatchers.IO) {
                    staging.writeBytes(bytes)
                    if (!staging.renameTo(target)) {
                        target.writeBytes(bytes)
                        staging.delete()
                    }
                }
                Uri.fromFile(target).toString()
            }
        } finally {
            locks.remove(key, mutex)
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val CACHE_DIRECTORY = "teddyremote-images"
    }
}
