package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.BoxRuntime
import de.teddycloud.teddyremote.model.VolumeRuntime

object BoxStateReducer {
    fun reduce(runtime: BoxRuntime, field: String, raw: String, timestampSeconds: Long): BoxRuntime = when (field) {
        "PlaybackStatus" -> runtime.copy(playback = runtime.playback.copy(valid = true, status = raw, updatedAt = timestampSeconds))
        "PlaybackTonie" -> runtime.copy(playback = runtime.playback.copy(valid = true, tonie = raw.ifBlank { null }, updatedAt = timestampSeconds))
        "PlaybackRuid" -> runtime.copy(playback = runtime.playback.copy(valid = true, ruid = raw.ifBlank { null }, updatedAt = timestampSeconds))
        "PlaybackContentVersion" -> runtime.copy(playback = runtime.playback.copy(valid = true, contentVersion = raw.toLongOrNull(), updatedAt = timestampSeconds))
        "PlaybackChapter" -> runtime.copy(playback = runtime.playback.copy(valid = true, chapter = raw.toIntOrNull(), updatedAt = timestampSeconds))
        "PlaybackChapterUntilMs" -> runtime.copy(playback = runtime.playback.copy(valid = true, chapterUntilMs = raw.toLongOrNull(), updatedAt = timestampSeconds))
        "PlaybackChapterDuration" -> runtime.copy(playback = runtime.playback.copy(valid = true, chapterDuration = raw.ifBlank { null }, updatedAt = timestampSeconds))
        "VolumeLevel" -> runtime.copy(volume = VolumeRuntime(true, timestampSeconds, raw.toIntOrNull()?.coerceIn(0, 10)))
        "BatteryPercent" -> runtime.copy(battery = runtime.battery.copy(valid = true, updatedAt = timestampSeconds, percent = raw.toIntOrNull()?.coerceIn(0, 100)))
        "BatteryStatus" -> runtime.copy(battery = runtime.battery.copy(valid = true, updatedAt = timestampSeconds, status = raw.ifBlank { null }))
        "SpeakerOutput" -> runtime.copy(headphones = runtime.headphones.copy(valid = true, updatedAt = timestampSeconds, speakerOutput = raw.toBooleanStrictOrNull()))
        "HeadphonesConnected", "HeadphonesConnectedCount" -> runtime.copy(
            headphones = runtime.headphones.copy(
                valid = true,
                updatedAt = timestampSeconds,
                connectedCount = raw.toIntOrNull() ?: if (raw.toBooleanStrictOrNull() == true) 1 else 0,
            ),
        )
        "BedtimeState" -> runtime.copy(bedtime = runtime.bedtime.copy(valid = true, updatedAt = timestampSeconds, state = raw.ifBlank { null }))
        "BedtimeDuration" -> runtime.copy(bedtime = runtime.bedtime.copy(valid = true, updatedAt = timestampSeconds, duration = raw.toIntOrNull()))
        "BedtimeDefaultDuration" -> runtime.copy(bedtime = runtime.bedtime.copy(valid = true, updatedAt = timestampSeconds, defaultDuration = raw.toIntOrNull()))
        "BedtimeUntil" -> runtime.copy(bedtime = runtime.bedtime.copy(valid = true, updatedAt = timestampSeconds, until = raw.ifBlank { null }))
        "LastSeen" -> runtime.copy(lastConnection = raw.toLongOrNull() ?: runtime.lastConnection)
        else -> runtime
    }
}
