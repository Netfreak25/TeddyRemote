package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.BoxUiModel
import de.teddycloud.teddyremote.model.MetadataKey

/** Keeps only fields actually updated since this HTTP request began; HTTP still owns online state. */
internal fun mergeBoxSnapshot(
    profileId: String,
    snapshot: BoxUiModel,
    baseline: BoxUiModel?,
    current: BoxUiModel?,
): BoxUiModel {
    if (current == null) return snapshot
    val live = current.box.runtime
    val old = baseline?.box?.runtime
    val remote = snapshot.box.runtime
    val runtime = remote.copy(
        playback = if (live.playback != old?.playback) live.playback else remote.playback,
        volume = if (live.volume != old?.volume) live.volume else remote.volume,
        battery = if (live.battery != old?.battery) live.battery else remote.battery,
        headphones = if (live.headphones != old?.headphones) live.headphones else remote.headphones,
        bedtime = if (live.bedtime != old?.bedtime) live.bedtime else remote.bedtime,
        lastConnection = maxOf(live.lastConnection, remote.lastConnection),
    )
    val box = snapshot.box.copy(runtime = runtime)
    val key = MetadataKey.forBox(profileId, box)
    return snapshot.copy(
        box = box,
        metadata = if (key == null) null else snapshot.metadata?.takeIf { it.key == key }
            ?: current.metadata?.takeIf { it.key == key },
        desiredVolume = current.desiredVolume,
        pendingCommand = current.pendingCommand,
        commandError = current.commandError,
        resumeOffer = current.resumeOffer,
    )
}
