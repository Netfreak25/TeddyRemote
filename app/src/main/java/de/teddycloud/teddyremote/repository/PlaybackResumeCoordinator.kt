package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.data.PlaybackBookmarkStorage
import de.teddycloud.teddyremote.model.BoxUiModel
import de.teddycloud.teddyremote.model.PlaybackBookmark
import de.teddycloud.teddyremote.model.PlaybackBookmarkKey
import de.teddycloud.teddyremote.model.PlaybackPosition
import de.teddycloud.teddyremote.model.PlaybackPositionCalculator
import de.teddycloud.teddyremote.model.ResumeOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

internal data class ResumeTarget(
    val chapter: Int,
    val positionMs: Long,
)

internal data class PlaybackResumePolicy(
    val offerTimeoutMs: Long = DEFAULT_OFFER_TIMEOUT_MS,
) {
    init {
        require(offerTimeoutMs > 0L) { "Resume offer timeout must be positive" }
    }

    companion object {
        const val DEFAULT_OFFER_TIMEOUT_MS = 20L * 60L * 1_000L
        val DEFAULT = PlaybackResumePolicy()
    }
}

/** Owns playback bookmarks and the temporary state of resume prompts. */
internal class PlaybackResumeCoordinator(
    scope: CoroutineScope,
    private val storage: PlaybackBookmarkStorage,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val onError: (Throwable) -> Unit = {},
) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, PlaybackSession>()
    private var latestBoxes = emptyMap<String, BoxUiModel>()
    private var activeProfileId: String? = null
    private val _offers = MutableStateFlow<Map<String, ResumeOffer>>(emptyMap())
    val offers: StateFlow<Map<String, ResumeOffer>> = _offers.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                delay(tickIntervalMs)
                runCatching { tick() }.onFailure(onError)
            }
        }
    }

    suspend fun observe(
        profileId: String?,
        boxes: List<BoxUiModel>,
        policy: PlaybackResumePolicy = PlaybackResumePolicy.DEFAULT,
    ): List<String> = mutex.withLock {
            if (activeProfileId != profileId) {
                sessions.clear()
                latestBoxes = emptyMap()
                activeProfileId = profileId
            }
            if (profileId == null) {
                publishOffersLocked()
                return@withLock emptyList()
            }

            latestBoxes = boxes.associateBy { it.box.id.uppercase() }
            sessions.keys.retainAll(latestBoxes.keys)
            boxes.forEach { processModelLocked(profileId, it, nowEpochMillis(), policy) }
            publishOffersLocked()
            val autoResumeBoxes = mutableListOf<String>()
            sessions.forEach { (boxId, session) ->
                val offer = session.offer ?: return@forEach
                if (offer.autoResumeRequested || offer.pendingSinceEpochMs != null || offer.error != null) {
                    return@forEach
                }
                if (!storage.isAutoResumeEnabled(session.key.ruid)) return@forEach
                session.offer = offer.copy(autoResumeRequested = true)
                autoResumeBoxes += boxId
            }
            autoResumeBoxes
        }

    suspend fun reset() {
        mutex.withLock {
            sessions.clear()
            latestBoxes = emptyMap()
            activeProfileId = null
            publishOffersLocked()
        }
    }

    suspend fun beginResume(boxId: String): ResumeTarget? = mutex.withLock {
        val session = sessions[boxId.uppercase()] ?: return@withLock null
        val offer = session.offer ?: return@withLock null
        session.offer = offer.copy(
            pendingSinceEpochMs = nowEpochMillis(),
            error = null,
        )
        publishOffersLocked()
        ResumeTarget(offer.bookmark.chapter, offer.bookmark.positionMs)
    }

    suspend fun resumeFailed(boxId: String, message: String) {
        mutex.withLock {
            val session = sessions[boxId.uppercase()] ?: return@withLock
            val offer = session.offer ?: return@withLock
            session.offer = offer.copy(pendingSinceEpochMs = null, error = message)
            publishOffersLocked()
        }
    }

    suspend fun decline(boxId: String) {
        mutex.withLock {
            val id = boxId.uppercase()
            val session = sessions[id] ?: return@withLock
            if (session.offer == null) return@withLock
            val now = nowEpochMillis()
            updatePositionLocked(session, latestBoxes[id], now)
            if (session.reachedEnd && session.lastStatus.equals(STATUS_STOPPED, ignoreCase = true)) {
                storage.delete(session.key)
            } else {
                session.lastPosition?.let { storage.upsert(it.toBookmark(session.key, now)) }
            }
            session.offer = null
            session.lastSavedAtEpochMs = now
            publishOffersLocked()
        }
    }

    private suspend fun tick() {
        mutex.withLock {
            val now = nowEpochMillis()
            sessions.forEach { (boxId, session) ->
                updatePositionLocked(session, latestBoxes[boxId], now)
                confirmPendingSeekLocked(session, now)

                val offer = session.offer
                if (offer != null && now >= offer.expiresAtEpochMs) {
                    if (session.reachedEnd && session.lastStatus.equals(STATUS_STOPPED, ignoreCase = true)) {
                        storage.delete(session.key)
                    } else {
                        session.lastPosition?.let { storage.upsert(it.toBookmark(session.key, now)) }
                    }
                    session.offer = null
                    session.lastSavedAtEpochMs = now
                } else if (
                    session.hasPlayed &&
                    session.lastStatus.equals(STATUS_PLAYING, ignoreCase = true) &&
                    now - session.lastSavedAtEpochMs >= SAVE_INTERVAL_MS
                ) {
                    saveCurrentLocked(session, now)
                }
            }
            publishOffersLocked()
        }
    }

    private suspend fun processModelLocked(
        profileId: String,
        model: BoxUiModel,
        now: Long,
        policy: PlaybackResumePolicy,
    ) {
        val boxId = model.box.id.uppercase()
        val existing = sessions[boxId]
        if (!model.box.runtime.online) {
            sessions.remove(boxId)
            return
        }

        val key = model.playbackKey(profileId)
        if (key == null) {
            existing?.let { finishForContentChangeLocked(it, now) }
            sessions.remove(boxId)
            return
        }

        if (existing == null || existing.key != key) {
            existing?.let { finishForContentChangeLocked(it, now) }
            val session = PlaybackSession(key = key, lastStatus = model.box.runtime.playback.status)
            sessions[boxId] = session
            updatePositionLocked(session, model, now)
            if (model.box.runtime.playback.isPlaying) startPlaybackLocked(session, now, policy.offerTimeoutMs)
            return
        }

        val previousStatus = existing.lastStatus
        updatePositionLocked(existing, model, now)
        confirmPendingSeekLocked(existing, now)
        val status = model.box.runtime.playback.status
        when {
            status.equals(STATUS_PLAYING, ignoreCase = true) && !existing.hasPlayed -> {
                startPlaybackLocked(existing, now, policy.offerTimeoutMs)
            }
            status.equals(STATUS_PLAYING, ignoreCase = true) && previousStatus.equals(STATUS_PAUSED, ignoreCase = true) -> {
                saveCurrentLocked(existing, now)
            }
            (status.equals(STATUS_PAUSED, ignoreCase = true) || status.equals(STATUS_STOPPED, ignoreCase = true)) &&
                previousStatus.equals(STATUS_PLAYING, ignoreCase = true) -> {
                if (status.equals(STATUS_STOPPED, ignoreCase = true) && existing.reachedEnd) {
                    storage.delete(existing.key)
                    existing.offer = null
                    existing.lastSavedAtEpochMs = now
                } else {
                    saveCurrentLocked(existing, now)
                }
            }
        }
        if (status.equals(STATUS_STOPPED, ignoreCase = true)) existing.hasPlayed = false
        existing.lastStatus = status
    }

    private suspend fun startPlaybackLocked(session: PlaybackSession, now: Long, offerTimeoutMs: Long) {
        session.hasPlayed = true
        session.lastSavedAtEpochMs = now
        if (session.offer != null) {
            saveCurrentLocked(session, now)
            return
        }
        val current = session.lastPosition ?: return
        storage.rememberTonie(session.key.ruid, current.contentTitle, now)
        val bookmark = storage.find(session.key)
        if (bookmark != null && bookmark.isWorthOffering() && current.differsMeaningfullyFrom(bookmark)) {
            session.offer = PendingResumeOffer(
                bookmark = bookmark,
                expiresAtEpochMs = now + offerTimeoutMs,
            )
            return
        }
        storage.upsert(current.toBookmark(session.key, now))
    }

    private suspend fun finishForContentChangeLocked(session: PlaybackSession, now: Long) {
        if (session.offer != null) return
        if (session.reachedEnd) {
            storage.delete(session.key)
        } else {
            session.lastPosition?.let { storage.upsert(it.toBookmark(session.key, now)) }
        }
    }

    private suspend fun saveCurrentLocked(session: PlaybackSession, now: Long) {
        val current = session.lastPosition ?: return
        session.lastSavedAtEpochMs = now
        if (session.offer != null) return
        storage.upsert(current.toBookmark(session.key, now))
    }

    private fun updatePositionLocked(session: PlaybackSession, model: BoxUiModel?, now: Long) {
        if (model == null || model.playbackKey(session.key.profileId) != session.key) return
        val chapter = model.box.runtime.playback.chapter ?: return
        val track = model.metadata?.playlist?.firstOrNull { it.index == chapter }
        val progress = PlaybackPositionCalculator.calculate(
            playback = model.box.runtime.playback,
            fallbackDurationSeconds = track?.durationSeconds,
            nowEpochMillis = now,
        ) ?: return
        session.lastPosition = ObservedPosition(
            chapter = chapter,
            progress = progress,
            contentTitle = model.metadata?.title ?: model.box.runtime.playback.tonie ?: "Tonie",
            chapterTitle = track?.title ?: "Kapitel ${chapter + 1}",
        )
        val lastChapter = model.metadata
            ?.takeIf { it.playlistComplete }
            ?.playlist
            ?.maxByOrNull { it.index }
            ?.index
        if (lastChapter == chapter && progress.remainingMs == 0L) session.reachedEnd = true
    }

    private suspend fun confirmPendingSeekLocked(session: PlaybackSession, now: Long) {
        val offer = session.offer ?: return
        val pendingSince = offer.pendingSinceEpochMs ?: return
        val current = session.lastPosition
        if (
            current != null &&
            current.chapter == offer.bookmark.chapter &&
            abs(current.progress.positionMs - offer.bookmark.positionMs) <= SEEK_CONFIRM_TOLERANCE_MS
        ) {
            storage.upsert(current.toBookmark(session.key, now))
            session.offer = null
            session.lastSavedAtEpochMs = now
        } else if (now - pendingSince >= SEEK_CONFIRM_TIMEOUT_MS) {
            session.offer = offer.copy(
                pendingSinceEpochMs = null,
                error = "Fortsetzen wurde von der Box nicht bestätigt",
            )
        }
    }

    private fun publishOffersLocked() {
        _offers.value = sessions.mapNotNull { (boxId, session) ->
            session.offer?.let { offer ->
                boxId to ResumeOffer(
                    contentTitle = offer.bookmark.contentTitle,
                    chapterTitle = offer.bookmark.chapterTitle,
                    chapter = offer.bookmark.chapter,
                    positionMs = offer.bookmark.positionMs,
                    expiresAtEpochMs = offer.expiresAtEpochMs,
                    pending = offer.pendingSinceEpochMs != null,
                    error = offer.error,
                )
            }
        }.toMap()
    }

    private data class PlaybackSession(
        val key: PlaybackBookmarkKey,
        var lastStatus: String,
        var hasPlayed: Boolean = false,
        var lastSavedAtEpochMs: Long = 0L,
        var lastPosition: ObservedPosition? = null,
        var reachedEnd: Boolean = false,
        var offer: PendingResumeOffer? = null,
    )

    private data class PendingResumeOffer(
        val bookmark: PlaybackBookmark,
        val expiresAtEpochMs: Long,
        val pendingSinceEpochMs: Long? = null,
        val error: String? = null,
        val autoResumeRequested: Boolean = false,
    )

    private data class ObservedPosition(
        val chapter: Int,
        val progress: PlaybackPosition,
        val contentTitle: String,
        val chapterTitle: String,
    ) {
        fun toBookmark(key: PlaybackBookmarkKey, now: Long) = PlaybackBookmark(
            key = key,
            chapter = chapter,
            positionMs = progress.positionMs,
            contentTitle = contentTitle,
            chapterTitle = chapterTitle,
            updatedAtEpochMs = now,
        )

        fun differsMeaningfullyFrom(bookmark: PlaybackBookmark): Boolean =
            chapter != bookmark.chapter || abs(progress.positionMs - bookmark.positionMs) >= RESUME_DIFFERENCE_MS
    }

    private fun PlaybackBookmark.isWorthOffering(): Boolean =
        chapter > 0 || positionMs >= MIN_RESUME_POSITION_MS

    private fun BoxUiModel.playbackKey(profileId: String): PlaybackBookmarkKey? {
        val playback = box.runtime.playback
        val ruid = playback.ruid?.takeIf { RUID_PATTERN.matches(it) }?.uppercase() ?: return null
        val contentVersion = playback.contentVersion ?: return null
        return PlaybackBookmarkKey(profileId, ruid, contentVersion)
    }

    private companion object {
        val RUID_PATTERN = Regex("^[0-9A-Fa-f]{16}$")
        const val STATUS_PLAYING = "playing"
        const val STATUS_PAUSED = "paused"
        const val STATUS_STOPPED = "stopped"
        const val SAVE_INTERVAL_MS = 60_000L
        const val RESUME_DIFFERENCE_MS = 30_000L
        const val MIN_RESUME_POSITION_MS = 30_000L
        const val SEEK_CONFIRM_TOLERANCE_MS = 15_000L
        const val SEEK_CONFIRM_TIMEOUT_MS = 5_000L
        const val TICK_INTERVAL_MS = 5_000L
    }
}
