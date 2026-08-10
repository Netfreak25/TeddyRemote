package de.teddycloud.teddyremote.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes absolute volume commands per box while retaining only the latest desired value.
 * Confirmed device values are kept separate from the value shown during a pending user change.
 */
internal class VolumeCommandCoordinator(
    private val scope: CoroutineScope,
    private val send: suspend (boxId: String, level: Int) -> Unit,
    private val refreshConfirmed: suspend (boxId: String) -> Int?,
    private val onDesiredChanged: suspend (boxId: String, level: Int) -> Unit,
    private val onSettled: suspend (boxId: String, error: String?) -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val confirmationTimeoutMillis: Long = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, PendingVolume>()

    suspend fun submit(boxId: String, level: Int) {
        val normalizedId = boxId.uppercase()
        val boundedLevel = level.coerceIn(MIN_VOLUME, MAX_VOLUME)
        mutex.withLock {
            val existing = states[normalizedId]
            if (existing == null) {
                val state = PendingVolume(desired = boundedLevel)
                states[normalizedId] = state
                state.job = scope.launch { runSender(normalizedId, state) }
            } else if (existing.desired != boundedLevel) {
                existing.desired = boundedLevel
                existing.changed.trySend(Unit)
            }
        }
        onDesiredChanged(normalizedId, boundedLevel)
    }

    suspend fun confirm(boxId: String, level: Int) {
        val normalizedId = boxId.uppercase()
        val completed = mutex.withLock {
            val state = states[normalizedId] ?: return
            if (state.desired != level.coerceIn(MIN_VOLUME, MAX_VOLUME)) return
            states.remove(normalizedId)
            state.changed.close()
            state.job
        }
        completed?.cancel()
        onSettled(normalizedId, null)
    }

    suspend fun cancelAll() {
        val cancelled = mutex.withLock {
            val entries = states.toList()
            states.clear()
            entries
        }
        cancelled.forEach { (_, state) ->
            state.changed.close()
            state.job?.cancel()
        }
        cancelled.forEach { (boxId, _) -> onSettled(boxId, null) }
    }

    private suspend fun runSender(boxId: String, state: PendingVolume) {
        try {
            while (currentCoroutineContext().isActive) {
                delay(debounceMillis)
                while (state.changed.tryReceive().isSuccess) Unit
                val target = mutex.withLock {
                    if (states[boxId] !== state) return
                    state.desired.also { state.inFlight = it }
                }
                send(boxId, target)

                val superseded = mutex.withLock {
                    states[boxId] !== state || state.desired != target
                }
                if (superseded) continue

                val changed = withTimeoutOrNull(confirmationTimeoutMillis) {
                    state.changed.receive()
                    true
                } ?: false
                if (changed) continue

                val confirmed = refreshConfirmed(boxId)
                val result = mutex.withLock {
                    if (states[boxId] !== state) return
                    if (state.desired != target) return@withLock SettleResult.CONTINUE
                    states.remove(boxId)
                    state.changed.close()
                    if (confirmed == target) SettleResult.SUCCESS else SettleResult.UNCONFIRMED
                }
                when (result) {
                    SettleResult.CONTINUE -> continue
                    SettleResult.SUCCESS -> onSettled(boxId, null)
                    SettleResult.UNCONFIRMED -> onSettled(boxId, "Lautstärke konnte nicht bestätigt werden")
                }
                return
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching { refreshConfirmed(boxId) }
            val removed = mutex.withLock {
                if (states[boxId] !== state) false else {
                    states.remove(boxId)
                    state.changed.close()
                    true
                }
            }
            if (removed) {
                onSettled(boxId, error.message?.takeIf(String::isNotBlank) ?: "Lautstärke konnte nicht gesetzt werden")
            }
        }
    }

    private data class PendingVolume(
        var desired: Int,
        var inFlight: Int? = null,
        val changed: Channel<Unit> = Channel(Channel.CONFLATED),
        var job: Job? = null,
    )

    private enum class SettleResult { CONTINUE, SUCCESS, UNCONFIRMED }

    private companion object {
        const val MIN_VOLUME = 0
        const val MAX_VOLUME = 10
        const val DEFAULT_DEBOUNCE_MILLIS = 100L
        const val DEFAULT_CONFIRMATION_TIMEOUT_MILLIS = 2_000L
    }
}
