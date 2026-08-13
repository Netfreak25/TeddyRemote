package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.BoxVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes absolute volume changes per box and keeps only the latest requested
 * target visible until the box confirms it.
 */
internal class VolumeCommandCoordinator(
    private val scope: CoroutineScope,
    private val send: suspend (boxId: String, level: Int) -> Unit,
    private val refreshConfirmed: suspend (boxId: String) -> Int?,
    private val onDesiredChanged: suspend (boxId: String, level: Int) -> Unit,
    private val onSettled: suspend (boxId: String, error: String?) -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val confirmationTimeoutMillis: Long = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    private val mutex = Mutex()
    private val effectsMutex = Mutex()
    private val states = mutableMapOf<String, PendingVolume>()

    suspend fun submit(boxId: String, level: Int) {
        val normalizedId = boxId.uppercase()
        val boundedLevel = BoxVolume.clamp(level)
        effectsMutex.withLock {
            var workerToStart: Job? = null
            val changed = mutex.withLock {
                val state = states.getOrPut(normalizedId) { PendingVolume(desired = boundedLevel) }
                if (state.revision > 0 && state.desired == boundedLevel) return@withLock false

                state.revision++
                state.desired = boundedLevel
                state.changedAtMillis = nowMillis()
                state.confirmationJob?.cancel()
                state.confirmationJob = null
                if (state.workerJob == null) {
                    workerToStart = scope.launch(start = CoroutineStart.LAZY) {
                        runWorker(normalizedId, state)
                    }
                    state.workerJob = workerToStart
                }
                true
            }
            if (!changed) return@withLock

            onDesiredChanged(normalizedId, boundedLevel)
            workerToStart?.start()
        }
    }

    suspend fun confirm(boxId: String, level: Int) {
        if (!BoxVolume.isValid(level)) return
        val normalizedId = boxId.uppercase()
        effectsMutex.withLock {
            var confirmationJob: Job? = null
            val settled = mutex.withLock {
                val state = states[normalizedId] ?: return@withLock null
                if (state.desired != level || state.lastSentTarget != level) return@withLock null

                states.remove(normalizedId)
                confirmationJob = state.confirmationJob
                state.confirmationJob = null
                true
            } ?: false
            if (!settled) return@withLock
            confirmationJob?.cancel()
            onSettled(normalizedId, null)
        }
    }

    suspend fun cancelAll() {
        effectsMutex.withLock {
            val entries = mutex.withLock {
                states.toList().also { states.clear() }
            }
            entries.forEach { (_, state) ->
                state.workerJob?.cancel()
                state.confirmationJob?.cancel()
            }
            entries.forEach { (boxId, _) -> onSettled(boxId, null) }
        }
    }

    private suspend fun runWorker(boxId: String, state: PendingVolume) {
        while (true) {
            val waitMillis = mutex.withLock {
                if (states[boxId] !== state) return
                remainingDebounce(state)
            }
            if (waitMillis > 0) delay(waitMillis)

            val request = mutex.withLock {
                if (states[boxId] !== state) return
                if (remainingDebounce(state) > 0) return@withLock null
                VolumeRequest(state.desired, state.revision).also {
                    state.lastSentTarget = it.target
                }
            } ?: continue

            try {
                send(boxId, request.target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (reconcileAfterFailure(boxId, state, request, error)) return
                continue
            }

            var timeoutToStart: Job? = null
            val completed = mutex.withLock {
                if (states[boxId] !== state) return
                if (state.revision != request.revision || state.desired != request.target) {
                    false
                } else {
                    state.workerJob = null
                    timeoutToStart = scope.launch(start = CoroutineStart.LAZY) {
                        awaitConfirmation(boxId, state, request)
                    }
                    state.confirmationJob = timeoutToStart
                    true
                }
            }
            if (completed) {
                timeoutToStart?.start()
                return
            }
        }
    }

    /** Returns true when the current request was settled and the worker may stop. */
    private suspend fun reconcileAfterFailure(
        boxId: String,
        state: PendingVolume,
        request: VolumeRequest,
        error: Throwable,
    ): Boolean {
        val stillCurrent = mutex.withLock {
            states[boxId] === state &&
                state.revision == request.revision &&
                state.desired == request.target
        }
        if (!stillCurrent) return false

        val confirmed = refreshConfirmedOrNull(boxId)
        val message = if (confirmed == request.target) {
            null
        } else {
            error.message?.takeIf(String::isNotBlank) ?: "Lautstärke konnte nicht gesetzt werden"
        }
        return settleIfCurrent(boxId, state, request, message)
    }

    private suspend fun awaitConfirmation(boxId: String, state: PendingVolume, request: VolumeRequest) {
        delay(confirmationTimeoutMillis)
        val confirmed = refreshConfirmedOrNull(boxId)
        val error = if (confirmed == request.target) null else "Lautstärke konnte nicht bestätigt werden"
        settleIfCurrent(boxId, state, request, error)
    }

    private suspend fun refreshConfirmedOrNull(boxId: String): Int? = try {
        refreshConfirmed(boxId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun settleIfCurrent(
        boxId: String,
        state: PendingVolume,
        request: VolumeRequest,
        error: String?,
    ): Boolean = effectsMutex.withLock {
        val settled = mutex.withLock {
            if (
                states[boxId] !== state ||
                state.revision != request.revision ||
                state.desired != request.target ||
                state.lastSentTarget != request.target
            ) {
                false
            } else {
                states.remove(boxId)
                true
            }
        }
        if (settled) onSettled(boxId, error)
        settled
    }

    private fun remainingDebounce(state: PendingVolume): Long =
        (state.changedAtMillis + debounceMillis - nowMillis()).coerceAtLeast(0L)

    private data class PendingVolume(
        var desired: Int,
        var revision: Long = 0,
        var changedAtMillis: Long = 0,
        var lastSentTarget: Int? = null,
        var workerJob: Job? = null,
        var confirmationJob: Job? = null,
    )

    private data class VolumeRequest(val target: Int, val revision: Long)

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 250L
        const val DEFAULT_CONFIRMATION_TIMEOUT_MILLIS = 2_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
