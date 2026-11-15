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
    if (current == null) {
        val key = MetadataKey.forBox(profileId, snapshot.box)
        return snapshot.copy(metadata = snapshot.metadata?.takeIf { key != null && it.key == key })
    }
    val live = current.box.runtime
    val old = baseline?.box?.runtime
    val remote = snapshot.box.runtime
    val playback = if (live.playback != old?.playback) {
        // MQTT position updates must not hide same-version metadata enrichment
        // from HTTP. Conversely an older HTTP response cannot undo a newer revision.
        if (live.playback.ruid.equals(remote.playback.ruid, ignoreCase = true) &&
            live.playback.contentVersion == remote.playback.contentVersion &&
            live.playback.metadataRevision == old?.playback?.metadataRevision
        ) live.playback.copy(metadataRevision = remote.playback.metadataRevision) else live.playback
    } else remote.playback
    val runtime = remote.copy(
        playback = playback,
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
