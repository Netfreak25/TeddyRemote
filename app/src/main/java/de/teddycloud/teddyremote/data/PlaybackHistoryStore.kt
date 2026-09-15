package de.teddycloud.teddyremote.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import de.teddycloud.teddyremote.model.PlaybackBookmark
import de.teddycloud.teddyremote.model.PlaybackBookmarkKey
import de.teddycloud.teddyremote.model.PlaybackHistoryState
import de.teddycloud.teddyremote.model.PlaybackToniePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private val playbackHistoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

internal object PlaybackHistorySerializer : Serializer<PlaybackHistoryState> {
    override val defaultValue: PlaybackHistoryState = PlaybackHistoryState()

    override suspend fun readFrom(input: InputStream): PlaybackHistoryState = try {
        playbackHistoryJson.decodeFromString(input.readBytes().decodeToString())
    } catch (error: SerializationException) {
        throw CorruptionException("Die TeddyRemote-Wiedergabepositionen sind beschädigt", error)
    }

    override suspend fun writeTo(t: PlaybackHistoryState, output: OutputStream) {
        output.write(playbackHistoryJson.encodeToString(PlaybackHistoryState.serializer(), t).encodeToByteArray())
    }
}

internal interface PlaybackBookmarkStorage {
    suspend fun find(key: PlaybackBookmarkKey): PlaybackBookmark?
    suspend fun upsert(bookmark: PlaybackBookmark)
    suspend fun delete(key: PlaybackBookmarkKey)
    suspend fun rememberTonie(ruid: String, title: String, updatedAtEpochMs: Long) = Unit
    suspend fun isAutoResumeEnabled(ruid: String): Boolean = false
}

class PlaybackHistoryStore internal constructor(
    private val dataStore: DataStore<PlaybackHistoryState>,
) : PlaybackBookmarkStorage {
    constructor(context: Context) : this(
        DataStoreFactory.create(
            serializer = PlaybackHistorySerializer,
            produceFile = {
                File(context.filesDir, "datastore/teddyremote-playback-history.json").also {
                    it.parentFile?.mkdirs()
                }
            },
        ),
    )

    internal val state: Flow<PlaybackHistoryState> = dataStore.data

    override suspend fun find(key: PlaybackBookmarkKey): PlaybackBookmark? =
        dataStore.data.first().bookmarks.firstOrNull { it.key == key }

    override suspend fun upsert(bookmark: PlaybackBookmark) {
        dataStore.updateData { current ->
            val retained = current.bookmarks.filterNot { it.key == bookmark.key }
            val profileBookmarks = (retained.filter { it.key.profileId == bookmark.key.profileId } + bookmark)
                .sortedByDescending(PlaybackBookmark::updatedAtEpochMs)
                .take(MAX_BOOKMARKS_PER_PROFILE)
            current.copy(
                bookmarks = retained.filterNot { it.key.profileId == bookmark.key.profileId } + profileBookmarks,
            )
        }
    }

    override suspend fun delete(key: PlaybackBookmarkKey) {
        dataStore.updateData { current ->
            current.copy(bookmarks = current.bookmarks.filterNot { it.key == key })
        }
    }

    override suspend fun rememberTonie(ruid: String, title: String, updatedAtEpochMs: Long) {
        val canonicalRuid = ruid.uppercase()
        require(RUID_PATTERN.matches(canonicalRuid)) { "Invalid content RUID" }
        val displayTitle = title.trim().ifBlank { "Tonie" }
        dataStore.updateData { current ->
            val existing = current.knownTonies.firstOrNull { it.ruid == canonicalRuid }
            if (existing != null && existing.title == displayTitle) return@updateData current
            val updated = PlaybackToniePreference(
                ruid = canonicalRuid,
                title = displayTitle,
                autoResumeEnabled = existing?.autoResumeEnabled ?: false,
                updatedAtEpochMs = updatedAtEpochMs,
            )
            current.copy(
                knownTonies = (current.knownTonies.filterNot { it.ruid == canonicalRuid } + updated)
                    .sortedByDescending(PlaybackToniePreference::updatedAtEpochMs)
                    .take(MAX_KNOWN_TONIES),
            )
        }
    }

    override suspend fun isAutoResumeEnabled(ruid: String): Boolean {
        val canonicalRuid = ruid.uppercase()
        return dataStore.data.first().knownTonies
            .firstOrNull { it.ruid == canonicalRuid }
            ?.autoResumeEnabled == true
    }

    suspend fun setAutoResumeEnabled(ruid: String, enabled: Boolean) {
        val canonicalRuid = ruid.uppercase()
        require(RUID_PATTERN.matches(canonicalRuid)) { "Invalid content RUID" }
        dataStore.updateData { current ->
            current.copy(
                knownTonies = current.knownTonies.map {
                    if (it.ruid == canonicalRuid) it.copy(autoResumeEnabled = enabled) else it
                },
            )
        }
    }

    private companion object {
        const val MAX_BOOKMARKS_PER_PROFILE = 500
        const val MAX_KNOWN_TONIES = 500
        val RUID_PATTERN = Regex("^[0-9A-F]{16}$")
    }
}
