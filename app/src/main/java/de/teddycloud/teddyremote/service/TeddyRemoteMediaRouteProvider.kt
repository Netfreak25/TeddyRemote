package de.teddycloud.teddyremote.service

import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouter
import de.teddycloud.teddyremote.R
import de.teddycloud.teddyremote.model.BoxUiModel

/** Publishes every controllable TB2 as a named remote Android media route. */
internal class TeddyRemoteMediaRouteProvider(
    private val routeContext: Context,
    private val onVolumeChanged: (boxId: String, volume: Int) -> Unit,
) : MediaRouteProvider(routeContext) {
    private var routes = emptyMap<String, TeddyRemoteMediaRoute>()

    fun update(models: Collection<BoxUiModel>) {
        routes = models.associate { model ->
            val boxId = model.box.id.uppercase()
            routeId(boxId) to TeddyRemoteMediaRoute(
                boxId = boxId,
                name = model.box.boxName.ifBlank { model.box.commonName.ifBlank { boxId } },
                description = routeContext.getString(R.string.media_route_description),
                volume = (model.desiredVolume ?: model.box.runtime.volume.level)
                    ?.coerceIn(MIN_BOX_VOLUME, MAX_BOX_VOLUME)
                    ?: MIN_BOX_VOLUME,
            )
        }
        descriptor = MediaRouteProviderDescriptor.Builder().apply {
            routes.forEach { (routeId, route) -> addRoute(route.toDescriptor(routeId)) }
        }.build()
    }

    override fun onCreateRouteController(routeId: String): RouteController? {
        if (routeId !in routes) return null
        return object : RouteController() {
            override fun onSetVolume(volume: Int) {
                routes[routeId]?.let { route ->
                    onVolumeChanged(route.boxId, volume.coerceIn(MIN_BOX_VOLUME, MAX_BOX_VOLUME))
                }
            }

            override fun onUpdateVolume(delta: Int) {
                routes[routeId]?.let { route ->
                    onVolumeChanged(
                        route.boxId,
                        (route.volume + delta).coerceIn(MIN_BOX_VOLUME, MAX_BOX_VOLUME),
                    )
                }
            }
        }
    }

    fun routeId(boxId: String): String = "$ROUTE_ID_PREFIX${boxId.uppercase()}"

    private fun TeddyRemoteMediaRoute.toDescriptor(routeId: String): MediaRouteDescriptor =
        MediaRouteDescriptor.Builder(routeId, name)
            .setDescription(description)
            .setEnabled(true)
            .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
            .setPlaybackStream(AudioManager.STREAM_MUSIC)
            .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(MAX_BOX_VOLUME)
            .setVolume(volume)
            .addControlFilter(LIVE_AUDIO_FILTER)
            .build()

    private data class TeddyRemoteMediaRoute(
        val boxId: String,
        val name: String,
        val description: String,
        val volume: Int,
    )

    private companion object {
        const val ROUTE_ID_PREFIX = "teddyremote:"
        const val MIN_BOX_VOLUME = 0
        const val MAX_BOX_VOLUME = 10
        val LIVE_AUDIO_FILTER = IntentFilter().apply {
            addCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
        }
    }
}

internal data class MediaRouteCandidate(
    val boxId: String,
    val isPlaying: Boolean,
    val playbackUpdatedAt: Long,
    val displayName: String,
)

/** Chooses the one route Android can expose as the currently selected media destination. */
internal fun selectMediaRouteBoxId(
    candidates: Collection<MediaRouteCandidate>,
    previousBoxId: String?,
): String? {
    if (candidates.isEmpty()) return null
    return candidates
        .filter(MediaRouteCandidate::isPlaying)
        .maxWithOrNull(compareBy<MediaRouteCandidate> { it.playbackUpdatedAt }.thenBy { it.boxId })
        ?.boxId
        ?: previousBoxId?.takeIf { previous -> candidates.any { it.boxId == previous } }
        ?: candidates.minWith(compareBy<MediaRouteCandidate> { it.displayName.lowercase() }.thenBy { it.boxId }).boxId
}
