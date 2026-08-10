package de.teddycloud.teddyremote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.OffsetDateTime

@Serializable
data class BoxesResponse(val boxes: List<TonieboxDto> = emptyList())

@Serializable
data class TonieboxDto(
    @SerialName("ID") val id: String,
    val commonName: String = "",
    val boxName: String = "",
    val boxModel: String = "",
    val runtime: BoxRuntime = BoxRuntime(),
)

@Serializable
data class BoxRuntime(
    val online: Boolean = false,
    val lastConnection: Long = 0,
    val controls: BoxControls = BoxControls(),
    val playback: PlaybackRuntime = PlaybackRuntime(),
    val volume: VolumeRuntime = VolumeRuntime(),
    val battery: BatteryRuntime = BatteryRuntime(),
    val headphones: HeadphonesRuntime = HeadphonesRuntime(),
    val bedtime: BedtimeRuntime = BedtimeRuntime(),
)

@Serializable
data class BoxControls(
    val playback: Boolean = false,
    val volume: Boolean = false,
    val ping: Boolean = false,
    val bedtime: Boolean = false,
    val sleep: Boolean = false,
)

@Serializable
data class PlaybackRuntime(
    val valid: Boolean = false,
    val status: String = "unknown",
    val updatedAt: Long = 0,
    val tonie: String? = null,
    val ruid: String? = null,
    val contentVersion: Long? = null,
    val chapter: Int? = null,
    val chapterUntilMs: Long? = null,
    val chapterDuration: String? = null,
) {
    val isPlaying: Boolean get() = status.equals("playing", ignoreCase = true)
}

@Serializable
data class VolumeRuntime(
    val valid: Boolean = false,
    val updatedAt: Long = 0,
    val level: Int? = null,
)

@Serializable
data class BatteryRuntime(
    val valid: Boolean = false,
    val updatedAt: Long = 0,
    val percent: Int? = null,
    val status: String? = null,
)

@Serializable
data class HeadphonesRuntime(
    val valid: Boolean = false,
    val updatedAt: Long = 0,
    val speakerOutput: Boolean? = null,
    val connectedCount: Int? = null,
)

@Serializable
data class BedtimeRuntime(
    val valid: Boolean = false,
    val updatedAt: Long = 0,
    val state: String? = null,
    val duration: Int? = null,
    val defaultDuration: Int? = null,
    val until: String? = null,
) {
    val isActive: Boolean
        get() = state.equals("on", ignoreCase = true) || state.equals("active", ignoreCase = true)

    /** Returns the confirmed remaining bedtime duration, never an optimistic command value. */
    fun remainingSeconds(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): Long? {
        if (!isActive) return null
        val deadline = until?.let(::parseBedtimeDeadline)
        if (deadline != null) return (deadline - nowEpochSeconds).coerceAtLeast(0)
        val total = duration?.toLong()?.takeIf { it >= 0 } ?: return null
        return (total - (nowEpochSeconds - updatedAt).coerceAtLeast(0)).coerceAtLeast(0)
    }
}

private fun parseBedtimeDeadline(value: String): Long? {
    value.toLongOrNull()?.let { raw ->
        return if (raw > 10_000_000_000L) raw / 1_000 else raw
    }
    return runCatching { Instant.parse(value).epochSecond }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toEpochSecond() }.getOrNull()
}

@Serializable
data class CommandResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val requestId: String? = null,
)

@Serializable
data class TagInfoEnvelope(val tagInfo: JsonObject)

data class PlaylistTrack(
    val index: Int,
    val title: String,
    val durationSeconds: Long? = null,
)

data class TonieMetadata(
    val ruid: String,
    val title: String,
    val subtitle: String = "",
    val pictureUrl: String? = null,
    val playlist: List<PlaylistTrack> = emptyList(),
)

data class BoxUiModel(
    val box: TonieboxDto,
    val metadata: TonieMetadata? = null,
    val boxImageUrl: String? = null,
    val ringBrightness: Int? = null,
    val desiredVolume: Int? = null,
    val pendingCommand: String? = null,
    val commandError: String? = null,
)

@Serializable
data class TonieboxCatalogEntry(
    val id: String = "",
    val name: String = "",
    @SerialName("img_src") val imageUrl: String = "",
    val generation: String? = null,
)
