package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.BoxUiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** One local projection for app and media sessions; never fetches data or modifies source metadata. */
internal fun playlistDisplayFlow(
    boxes: Flow<List<BoxUiModel>>,
    inferIntroOutro: Flow<Boolean>,
): Flow<List<BoxUiModel>> = combine(boxes, inferIntroOutro.distinctUntilChanged(), ::displayPlaylists)

internal fun displayPlaylists(boxes: List<BoxUiModel>, inferIntroOutro: Boolean): List<BoxUiModel> {
    if (!inferIntroOutro) return boxes
    return boxes.map { model ->
        val metadata = model.metadata ?: return@map model
        val candidate = metadata.introOutroPlaylist ?: return@map model
        model.copy(metadata = metadata.copy(
            playlist = candidate,
            playlistComplete = candidate.isNotEmpty() && candidate.all { it.durationMs != null },
        ))
    }
}
