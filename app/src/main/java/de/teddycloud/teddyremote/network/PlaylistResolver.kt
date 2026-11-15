package de.teddycloud.teddyremote.network

import de.teddycloud.teddyremote.model.PlaylistTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

/** Resolves chapter structure from the selected content, never from the figurine's appearance. */
internal object PlaylistResolver {
    data class Result(
        val tracks: List<PlaylistTrack>,
        val complete: Boolean,
        val introOutroTracks: List<PlaylistTrack>? = null,
        val native: Boolean = false,
        val verified: Boolean = true,
    )

    private const val INTRO_OUTRO_CHAPTERS = 2

    fun resolve(tag: JsonObject, requestedVersion: Long?): Result {
        val playlist = tag["playlist"] as? JsonObject
        val kind = text(playlist?.get("kind"))
        val native = kind == "native_collection"
        // Older backends may omit the native playlist altogether. A catalogue is not
        // evidence of the structure of a versioned TB2 content item.
        val unknownNative = playlist == null && requestedVersion != null &&
            (tag["trackSeconds"] as? JsonArray).isNullOrEmpty()
        val reportedVersion = (playlist?.get("contentVersion") as? JsonPrimitive)?.longOrNull
        if (unknownNative || requestedVersion != null &&
            (reportedVersion != null || native) && requestedVersion != reportedVersion
        ) {
            return Result(emptyList(), false, native = native || unknownNative, verified = false)
        }
        val sourceInfo = tag["sourceInfo"] as? JsonObject
        val info = tag["tonieInfo"] as? JsonObject
        val custom = !text(tag["source"]).isNullOrBlank() || kind in setOf("direct_taf", "tap", "native_collection")
        val names = if (native) (playlist?.get("tracks") as? JsonArray).orEmpty() else listOfNotNull(
            playlist?.get("tracks") as? JsonArray,
            sourceInfo?.get("tracks") as? JsonArray,
            if (!custom) info?.get("tracks") as? JsonArray else null,
        ).firstOrNull { it.isNotEmpty() }.orEmpty()
        // Native/TAP chapter structure must not borrow offsets from an unrelated cached TAF.
        val starts = if (native || kind == "tap") emptyList() else {
            (tag["trackSeconds"] as? JsonArray).orEmpty().map(::milliseconds)
        }
        val durations = (playlist?.get("durations") as? JsonArray).orEmpty().map(::milliseconds)
        val explicitCount = (playlist?.get("chapterCount") as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        if (native && explicitCount == null) return Result(emptyList(), false, native = true)
        val knownCount = explicitCount ?: starts.size.takeIf { it > 0 }
        val count = knownCount ?: names.size
        val titles = names.map { text(it)?.takeIf(String::isNotBlank) }
        val total = milliseconds(tag["totalSeconds"])
        val derivedDurations = List(count) { index ->
            val start = starts.getOrNull(index)
            val end = if (index == count - 1) total else starts.getOrNull(index + 1)
            if (starts.size == count && start != null && end != null && end >= start) end - start else null
        }
        val tracks = List(count) { index ->
            PlaylistTrack(
                index,
                titles.getOrNull(index) ?: "Kapitel ${index + 1}",
                durations.getOrNull(index) ?: derivedDurations[index],
            )
        }
        // Keep the original result intact. The display preference selects this candidate locally.
        val introOutroTracks = if (!native && knownCount != null && titles.isNotEmpty() &&
            count - titles.size == INTRO_OUTRO_CHAPTERS && titles.all { it != null }
        ) {
            tracks.map { track ->
                track.copy(
                    title = when (track.index) {
                        0 -> "Intro"
                        count - 1 -> "Outro"
                        else -> requireNotNull(titles[track.index - 1])
                    },
                    // A shorter duration array may describe the names, not the real chapter indices.
                    durationMs = if (durations.size == count) track.durationMs else derivedDurations[track.index],
                )
            }
        } else null
        return Result(tracks, count > 0 && tracks.all { it.durationMs != null }, introOutroTracks, native)
    }

    private fun text(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun milliseconds(value: kotlinx.serialization.json.JsonElement?): Long? {
        val seconds = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val milliseconds = seconds * MILLIS_PER_SECOND
        return milliseconds.takeIf { it.isFinite() && it < Long.MAX_VALUE.toDouble() }?.roundToLong()
    }

    private const val MILLIS_PER_SECOND = 1_000L
}
