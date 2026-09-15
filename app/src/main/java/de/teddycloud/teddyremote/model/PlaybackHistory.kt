package de.teddycloud.teddyremote.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackBookmarkKey(
    val profileId: String,
    val ruid: String,
    val contentVersion: Long,
)

@Serializable
data class PlaybackBookmark(
    val key: PlaybackBookmarkKey,
    val chapter: Int,
    val positionMs: Long,
    val contentTitle: String,
    val chapterTitle: String,
    val updatedAtEpochMs: Long,
)

@Serializable
data class PlaybackHistoryState(
    val schemaVersion: Int = 1,
    val bookmarks: List<PlaybackBookmark> = emptyList(),
)

data class ResumeOffer(
    val contentTitle: String,
    val chapterTitle: String,
    val chapter: Int,
    val positionMs: Long,
    val expiresAtEpochMs: Long,
    val pending: Boolean = false,
    val error: String? = null,
)
