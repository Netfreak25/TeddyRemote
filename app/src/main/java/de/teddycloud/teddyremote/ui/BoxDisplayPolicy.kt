package de.teddycloud.teddyremote.ui

import de.teddycloud.teddyremote.model.BoxUiModel
import de.teddycloud.teddyremote.model.PlaybackToniePreference
import java.util.Locale

/** Shared by snapshot and MQTT-driven UI updates. Playback outranks contact time only while online. */
internal fun sortedBoxes(boxes: List<BoxUiModel>): List<BoxUiModel> = boxes.sortedWith(
    compareBy<BoxUiModel> {
        when {
            !it.box.runtime.online -> 2
            it.box.runtime.playback.isPlaying -> 0
            else -> 1
        }
    }.thenByDescending {
        if (it.box.runtime.online && it.box.runtime.playback.isPlaying) 0L
        else it.box.runtime.lastConnection.coerceAtLeast(0L)
    }.thenBy { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.box.id.uppercase(Locale.ROOT) },
)

internal val BoxUiModel.displayName: String
    get() = box.boxName.ifBlank { box.commonName.ifBlank { box.id } }

internal fun currentTrackTitle(model: BoxUiModel): String {
    val chapter = model.box.runtime.playback.chapter ?: return "Kein Kapitel"
    return model.metadata?.playlist?.firstOrNull { it.index == chapter }?.title ?: "Kapitel ${chapter + 1}"
}

internal fun filteredHistory(tonies: List<PlaybackToniePreference>, query: String): List<PlaybackToniePreference> {
    val search = query.trim()
    return tonies.filter { it.title.contains(search, ignoreCase = true) || it.ruid.contains(search, ignoreCase = true) }
        .sortedByDescending { it.updatedAtEpochMs }
}
