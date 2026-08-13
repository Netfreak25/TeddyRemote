package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.BoxVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coalesces absolute volume changes per box and keeps optimistic UI state separate
 * from the latest confirmed device value.
 */
internal class VolumeCommandCoordinator(
    private val scope: CoroutineScope,
    private val send: suspend (boxId: String, level: Int) -> Unit,
    private val refreshConfirmed: suspend (boxId: String) -> Int?,
    private val onDesiredChanged: suspend (boxId: String, level: Int) -> Unit,
    private val onOptimisticExpired: suspend (boxId: String) -> Unit,
    private val onSettled: suspend (boxId: String, error: String?) -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val optimisticLockoutMillis: Long = DEFAULT_OPTIMISTIC_LOCKOUT_MILLIS,
    private val confirmationTimeoutMillis: Long = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, PendingVolume>()

    suspend fun submit(boxId: String, level: Int) {
        val normalizedId = boxId.uppercase()
        val boundedLevel = BoxVolume.clamp(level)
        mutex.withLock {
            val state = states.getOrPut(normalizedId) { PendingVolume(desired = boundedLevel) }
            if (state.revision > 0 && state.desired == boundedLevel) return

            state.revision++
            state.desired = boundedLevel
            state.inFlight = null
            state.confirmed = false
            state.optimisticVisible = true
            state.senderJob?.cancel()
            state.lockoutJob?.cancel()

            val revision = state.revision
            state.senderJob = scope.launch { runSender(normalizedId, state, revision) }
            state.lockoutJob = scope.launch { expireOptimisticValue(normalizedId, state, revision) }
            onDesiredChanged(normalizedId, boundedLevel)
        }
    }

    suspend fun confirm(boxId: String, level: Int) {
        if (!BoxVolume.isValid(level)) return
        val normalizedId = boxId.uppercase()
        mutex.withLock {
            val state = states[normalizedId] ?: return
            if (state.inFlight != level || state.desired != level) return

            state.confirmed = true
            state.senderJob?.cancel()
            if (!state.optimisticVisible) {
                states.remove(normalizedId)
                state.lockoutJob?.cancel()
                onSettled(normalizedId, null)
            }
        }
    }

    suspend fun cancelAll() {
        mutex.withLock {
            val entries = states.toList()
            states.clear()
            entries.forEach { (_, state) ->
                state.senderJob?.cancel()
                state.lockoutJob?.cancel()
            }
            entries.forEach { (boxId, _) -> onSettled(boxId, null) }
        }
    }

    private suspend fun runSender(boxId: String, state: PendingVolume, revision: Long) {
        try {
            delay(debounceMillis)
            val target = mutex.withLock {
                if (states[boxId] !== state || state.revision != revision) return
                state.desired.also { state.inFlight = it }
            }
            send(boxId, target)
            delay(confirmationTimeoutMillis)

            val confirmed = refreshConfirmed(boxId)
            mutex.withLock {
                if (states[boxId] !== state || state.revision != revision) return
                if (confirmed == target) {
                    state.confirmed = true
                    if (!state.optimisticVisible) {
                        states.remove(boxId)
                        state.lockoutJob?.cancel()
                        onSettled(boxId, null)
                    }
                } else {
                    states.remove(boxId)
                    state.lockoutJob?.cancel()
                    onSettled(boxId, "Lautstärke konnte nicht bestätigt werden")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching { refreshConfirmed(boxId) }
            mutex.withLock {
                if (states[boxId] !== state || state.revision != revision) return
                states.remove(boxId)
                state.lockoutJob?.cancel()
                onSettled(
                    boxId,
                    error.message?.takeIf(String::isNotBlank) ?: "Lautstärke konnte nicht gesetzt werden",
                )
            }
        }
    }

    private suspend fun expireOptimisticValue(boxId: String, state: PendingVolume, revision: Long) {
        delay(optimisticLockoutMillis)
        mutex.withLock {
            if (states[boxId] !== state || state.revision != revision) return
            state.optimisticVisible = false
            onOptimisticExpired(boxId)
            if (state.confirmed) {
                states.remove(boxId)
                state.senderJob?.cancel()
                onSettled(boxId, null)
            }
        }
    }

    private data class PendingVolume(
        var desired: Int,
        var revision: Long = 0,
        var inFlight: Int? = null,
        var confirmed: Boolean = false,
        var optimisticVisible: Boolean = true,
        var senderJob: Job? = null,
        var lockoutJob: Job? = null,
    )

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 250L
        const val DEFAULT_OPTIMISTIC_LOCKOUT_MILLIS = 700L
        const val DEFAULT_CONFIRMATION_TIMEOUT_MILLIS = 2_000L
    }
}
