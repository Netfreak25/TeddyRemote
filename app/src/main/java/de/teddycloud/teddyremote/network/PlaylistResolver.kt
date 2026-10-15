package de.teddycloud.teddyremote.network

import de.teddycloud.teddyremote.model.PlaylistTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Resolves chapter structure from the selected content, never from the figurine's appearance. */
internal object PlaylistResolver {
    data class Result(val tracks: List<PlaylistTrack>, val complete: Boolean)

    fun resolve(tag: JsonObject, requestedVersion: Long?): Result {
        val playlist = tag["playlist"] as? JsonObject
        val reportedVersion = (playlist?.get("contentVersion") as? JsonPrimitive)?.longOrNull
        if (requestedVersion != null && reportedVersion != null && requestedVersion != reportedVersion) {
            return Result(emptyList(), false)
        }
        val sourceInfo = tag["sourceInfo"] as? JsonObject
        val info = tag["tonieInfo"] as? JsonObject
        val kind = text(playlist?.get("kind"))
        val custom = !text(tag["source"]).isNullOrBlank() || kind in setOf("direct_taf", "tap", "native_collection")
        val names = listOfNotNull(
            playlist?.get("tracks") as? JsonArray,
            sourceInfo?.get("tracks") as? JsonArray,
            if (!custom) info?.get("tracks") as? JsonArray else null,
        ).firstOrNull { it.isNotEmpty() }.orEmpty()
        // Native/TAP chapter structure must not borrow offsets from an unrelated cached TAF.
        val starts = if (kind in setOf("native_collection", "tap")) emptyList() else {
            (tag["trackSeconds"] as? JsonArray).orEmpty().map(::seconds)
        }
        val durations = (playlist?.get("durations") as? JsonArray).orEmpty().map(::seconds)
        val explicitCount = (playlist?.get("chapterCount") as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        val count = explicitCount ?: starts.size.takeIf { it > 0 } ?: names.size
        val total = seconds(tag["totalSeconds"])
        val tracks = List(count) { index ->
            val start = starts.getOrNull(index)
            val end = if (index == count - 1) total else starts.getOrNull(index + 1)
            val derived = if (starts.size == count && start != null && end != null && end >= start) end - start else null
            PlaylistTrack(
                index,
                text(names.getOrNull(index))?.takeIf(String::isNotBlank) ?: "Kapitel ${index + 1}",
                durations.getOrNull(index) ?: derived,
            )
        }
        return Result(tracks, count > 0 && tracks.all { it.durationSeconds != null })
    }

    private fun text(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun seconds(value: kotlinx.serialization.json.JsonElement?): Long? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
}
