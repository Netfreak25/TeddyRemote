package de.teddycloud.teddyremote.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.VolumeProvider
import android.os.Bundle
import androidx.core.graphics.drawable.toBitmap
import androidx.mediarouter.media.MediaRouter
import coil.ImageLoader
import coil.request.ImageRequest
import de.teddycloud.teddyremote.MainActivity
import de.teddycloud.teddyremote.R
import de.teddycloud.teddyremote.model.BoxUiModel
import de.teddycloud.teddyremote.model.BoxVolume
import de.teddycloud.teddyremote.repository.TeddyRemoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

class BoxMediaSessionManager(
    private val context: Context,
    private val repository: TeddyRemoteRepository,
    private val scope: CoroutineScope,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val imageLoader = ImageLoader(context)
    private val sessions = mutableMapOf<String, BoxSession>()
    private val mediaRouter = MediaRouter.getInstance(context)
    private val routeProvider = TeddyRemoteMediaRouteProvider(context, ::setRouteVolume)
    private var selectedRouteBoxId: String? = null
    private var bedtimeTicker: Job? = null

    init {
        mediaRouter.addProvider(routeProvider)
    }

    fun update(boxes: List<BoxUiModel>) {
        val eligible = boxes.filter { it.box.runtime.online && it.box.runtime.controls.playback }
            .associateBy { it.box.id.uppercase() }
        (sessions.keys - eligible.keys).forEach(::remove)
        eligible.forEach { (id, model) ->
            val session = sessions.getOrPut(id) { createSession(model) }
            session.model = model
            updateSession(session)
            updateNotification(session, artwork = session.artwork)
            val imageUrl = model.metadata?.pictureUrl ?: model.boxImageUrl
            if (imageUrl != null && imageUrl != session.artworkUrl) loadArtwork(session, imageUrl)
        }
        updateMediaRoute(eligible)
        updateBedtimeTicker()
    }

    fun handleAction(boxId: String, action: String) {
        val session = sessions[boxId.uppercase()] ?: return
        when (action) {
            TeddyRemoteService.ACTION_PREVIOUS -> scope.launch { repository.playback(boxId, "prev") }
            TeddyRemoteService.ACTION_PLAY_PAUSE -> scope.launch {
                repository.playback(boxId, if (session.model.box.runtime.playback.isPlaying) "pause" else "start")
            }
            TeddyRemoteService.ACTION_NEXT -> scope.launch { repository.playback(boxId, "next") }
            TeddyRemoteService.ACTION_VOLUME_DOWN -> setVolume(session, session.volumeProvider.currentVolume - 1)
            TeddyRemoteService.ACTION_VOLUME_UP -> setVolume(session, session.volumeProvider.currentVolume + 1)
        }
    }

    fun release() {
        bedtimeTicker?.cancel()
        clearSelectedMediaRoute()
        routeProvider.update(emptyList())
        mediaRouter.removeProvider(routeProvider)
        sessions.keys.toList().forEach(::remove)
        imageLoader.shutdown()
    }

    private fun createSession(model: BoxUiModel): BoxSession {
        lateinit var holder: BoxSession
        val mediaSession = MediaSession(context, "TeddyRemote:${model.box.id}").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    scope.launch { repository.playback(model.box.id, "start") }
                }

                override fun onPause() {
                    scope.launch { repository.playback(model.box.id, "pause") }
                }

                override fun onSkipToPrevious() {
                    scope.launch { repository.playback(model.box.id, "prev") }
                }

                override fun onSkipToNext() {
                    scope.launch { repository.playback(model.box.id, "next") }
                }

                override fun onSkipToQueueItem(id: Long) {
                    scope.launch { repository.playback(model.box.id, "setPosition", id.toInt()) }
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    handleAction(model.box.id, action)
                }
            })
            setSessionActivity(openBoxIntent(model.box.id))
        }
        val volumeProvider = object : VolumeProvider(
            VOLUME_CONTROL_ABSOLUTE,
            BoxVolume.MAX_LEVEL,
            model.box.runtime.volume.level?.let(BoxVolume::clamp) ?: BoxVolume.MIN_LEVEL,
        ) {
            override fun onSetVolumeTo(volume: Int) = setVolume(holder, volume)
            override fun onAdjustVolume(direction: Int) = setVolume(holder, currentVolume + direction)
        }
        mediaSession.setPlaybackToRemote(volumeProvider)
        holder = BoxSession(
            model = model,
            mediaSession = mediaSession,
            volumeProvider = volumeProvider,
            notificationId = notificationId(model.box.id),
        )
        return holder
    }

    private fun updateSession(session: BoxSession) {
        val model = session.model
        val playback = model.box.runtime.playback
        val chapterIndex = playback.chapter?.coerceAtLeast(0) ?: 0
        val track = model.metadata?.playlist?.getOrNull(chapterIndex)
        val bedtime = bedtimeLabel(model)
        val boxName = model.box.boxName.ifBlank { model.box.commonName }
        val state = when (playback.status.lowercase()) {
            "playing" -> PlaybackState.STATE_PLAYING
            "paused" -> PlaybackState.STATE_PAUSED
            "stopped" -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_NONE
        }
        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM,
            )
            .addCustomAction(
                TeddyRemoteService.ACTION_VOLUME_DOWN,
                context.getString(R.string.media_volume_down),
                R.drawable.ic_volume_down,
            )
            .addCustomAction(
                TeddyRemoteService.ACTION_VOLUME_UP,
                context.getString(R.string.media_volume_up),
                R.drawable.ic_volume_up,
            )
            .setState(state, playback.chapterUntilMs ?: 0L, if (playback.isPlaying) 1f else 0f)
            .build()
        session.mediaSession.setPlaybackState(playbackState)
        session.mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track?.title ?: "Kapitel ${chapterIndex + 1}")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, model.metadata?.title ?: playback.tonie ?: "Tonie")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, listOfNotNull(boxName, bedtime).joinToString(" · "))
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, chapterIndex.toLong() + 1)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, model.metadata?.playlist?.size?.toLong() ?: 0L)
                .apply { session.artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        session.mediaSession.setQueue(
            model.metadata?.playlist?.map { item ->
                MediaSession.QueueItem(
                    android.media.MediaDescription.Builder().setTitle(item.title).build(),
                    item.index.toLong(),
                )
            },
        )
        val volume = (model.desiredVolume ?: model.box.runtime.volume.level)
            ?.let(BoxVolume::clamp)
            ?: BoxVolume.MIN_LEVEL
        if (volume != session.volumeProvider.currentVolume) session.volumeProvider.setCurrentVolume(volume)
        session.mediaSession.isActive = true
    }

    private fun updateNotification(session: BoxSession, artwork: Bitmap?) {
        val model = session.model
        val playing = model.box.runtime.playback.isPlaying
        val chapter = model.metadata?.playlist?.getOrNull(model.box.runtime.playback.chapter ?: 0)?.title
        val bedtime = bedtimeLabel(model)
        val subText = listOfNotNull(chapter, bedtime).joinToString(" · ")
        val builder = Notification.Builder(context, TeddyRemoteService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(model.metadata?.title ?: model.box.runtime.playback.tonie ?: "Tonie")
            .setContentText(model.box.boxName.ifBlank { model.box.commonName })
            .setSubText(subText.takeIf { it.isNotBlank() })
            .setContentIntent(openBoxIntent(model.box.id))
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                action(
                    android.R.drawable.ic_media_previous,
                    context.getString(R.string.media_previous),
                    model.box.id,
                    TeddyRemoteService.ACTION_PREVIOUS,
                    1,
                ),
            )
            .addAction(
                action(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    context.getString(if (playing) R.string.media_pause else R.string.media_play),
                    model.box.id,
                    TeddyRemoteService.ACTION_PLAY_PAUSE,
                    2,
                ),
            )
            .addAction(
                action(
                    android.R.drawable.ic_media_next,
                    context.getString(R.string.media_next),
                    model.box.id,
                    TeddyRemoteService.ACTION_NEXT,
                    3,
                ),
            )
            .addAction(
                action(
                    R.drawable.ic_volume_down,
                    context.getString(R.string.media_volume_down),
                    model.box.id,
                    TeddyRemoteService.ACTION_VOLUME_DOWN,
                    4,
                ),
            )
            .addAction(
                action(
                    R.drawable.ic_volume_up,
                    context.getString(R.string.media_volume_up),
                    model.box.id,
                    TeddyRemoteService.ACTION_VOLUME_UP,
                    5,
                ),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        artwork?.let(builder::setLargeIcon)
        notificationManager.notify(session.notificationId, builder.build())
    }

    private fun loadArtwork(session: BoxSession, url: String) {
        session.artworkUrl = url
        scope.launch {
            val result = imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).build())
            val bitmap = result.drawable?.toBitmap()
            if (session.artworkUrl == url && bitmap != null) {
                session.artwork = bitmap
                updateSession(session)
                updateNotification(session, bitmap)
            }
        }
    }

    private fun setVolume(session: BoxSession, value: Int) {
        val bounded = BoxVolume.clamp(value)
        session.volumeProvider.setCurrentVolume(bounded)
        scope.launch { repository.setVolume(session.model.box.id, bounded) }
    }

    private fun setRouteVolume(boxId: String, value: Int) {
        sessions[boxId.uppercase()]?.let { setVolume(it, value) }
    }

    private fun updateBedtimeTicker() {
        val needed = sessions.values.any { it.model.box.runtime.bedtime.isActive }
        if (!needed) {
            bedtimeTicker?.cancel()
            bedtimeTicker = null
            return
        }
        if (bedtimeTicker?.isActive == true) return
        bedtimeTicker = scope.launch {
            while (isActive && sessions.values.any { it.model.box.runtime.bedtime.isActive }) {
                delay(BEDTIME_MEDIA_REFRESH_MS)
                sessions.values.filter { it.model.box.runtime.bedtime.isActive }.forEach { session ->
                    updateSession(session)
                    updateNotification(session, session.artwork)
                }
            }
            bedtimeTicker = null
        }
    }

    private fun bedtimeLabel(model: BoxUiModel): String? {
        val remaining = model.box.runtime.bedtime.remainingSeconds() ?: return null
        return if (remaining < 60) "Bedtime ${remaining}s"
        else "Bedtime ${(remaining + 59) / 60} min"
    }

    private fun updateMediaRoute(eligible: Map<String, BoxUiModel>) {
        routeProvider.update(eligible.values)
        val nextBoxId = selectMediaRouteBoxId(
            candidates = eligible.map { (boxId, model) ->
                MediaRouteCandidate(
                    boxId = boxId,
                    isPlaying = model.box.runtime.playback.isPlaying,
                    playbackUpdatedAt = model.box.runtime.playback.updatedAt,
                    displayName = model.box.boxName.ifBlank { model.box.commonName.ifBlank { boxId } },
                )
            },
            previousBoxId = selectedRouteBoxId,
        )
        if (nextBoxId == null) {
            clearSelectedMediaRoute()
            return
        }
        selectedRouteBoxId = nextBoxId
        val routeId = routeProvider.routeId(nextBoxId)
        val route = mediaRouter.routes.firstOrNull { it.mediaRouteDescriptor?.id == routeId } ?: return
        if (!route.isSelected) mediaRouter.selectRoute(route)
        mediaRouter.setMediaSession(sessions.getValue(nextBoxId).mediaSession)
    }

    private fun clearSelectedMediaRoute() {
        mediaRouter.setMediaSession(null)
        if (mediaRouter.selectedRoute.mediaRouteDescriptor?.id?.startsWith(MEDIA_ROUTE_PREFIX) == true) {
            mediaRouter.selectRoute(mediaRouter.defaultRoute)
        }
        selectedRouteBoxId = null
    }

    private fun action(icon: Int, title: String, boxId: String, action: String, offset: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, icon),
            title,
            serviceIntent(boxId, action, offset),
        ).build()

    private fun serviceIntent(boxId: String, action: String, offset: Int): PendingIntent = PendingIntent.getService(
        context,
        notificationId(boxId) * 10 + offset,
        Intent(context, TeddyRemoteService::class.java).setAction(action).putExtra(TeddyRemoteService.EXTRA_BOX_ID, boxId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openBoxIntent(boxId: String): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId(boxId),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(TeddyRemoteService.EXTRA_BOX_ID, boxId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun remove(boxId: String) {
        sessions.remove(boxId)?.let { session ->
            notificationManager.cancel(session.notificationId)
            session.mediaSession.release()
        }
    }

    private fun notificationId(boxId: String): Int = 1_000 + (boxId.hashCode().absoluteValue % 8_000)

    private data class BoxSession(
        var model: BoxUiModel,
        val mediaSession: MediaSession,
        val volumeProvider: VolumeProvider,
        val notificationId: Int,
        var artworkUrl: String? = null,
        var artwork: Bitmap? = null,
    )

    private companion object {
        const val MEDIA_ROUTE_PREFIX = "teddyremote:"
        const val BEDTIME_MEDIA_REFRESH_MS = 30_000L
    }
}
