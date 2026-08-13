package de.teddycloud.teddyremote.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VolumeCommandCoordinatorTest {
    @Test
    fun `rapid changes use trailing debounce and send only the final value`() = runTest {
        val sent = mutableListOf<Int>()
        val coordinator = coordinator(send = { sent += it })

        coordinator.submit(BOX_ID, 1)
        advanceTimeBy(DEBOUNCE_MILLIS - 1)
        coordinator.submit(BOX_ID, 2)
        advanceTimeBy(DEBOUNCE_MILLIS - 1)
        coordinator.submit(BOX_ID, 4)
        advanceTimeBy(DEBOUNCE_MILLIS - 1)
        runCurrent()

        assertTrue(sent.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(4), sent)
    }

    @Test
    fun `matching confirmation keeps optimistic value until lockout expires`() = runTest {
        val expired = mutableListOf<String>()
        val settled = mutableListOf<String?>()
        val coordinator = coordinator(
            send = {},
            onOptimisticExpired = { expired += it },
            onSettled = { settled += it },
        )

        coordinator.submit(BOX_ID, 4)
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()
        coordinator.confirm(BOX_ID, 4)
        advanceTimeBy(LOCKOUT_MILLIS - DEBOUNCE_MILLIS - 1)
        runCurrent()

        assertTrue(expired.isEmpty())
        assertTrue(settled.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(BOX_ID), expired)
        assertEquals(listOf<String?>(null), settled)
    }

    @Test
    fun `stale confirmation does not settle and lockout reveals confirmed state`() = runTest {
        val expired = mutableListOf<String>()
        val settled = mutableListOf<String?>()
        val coordinator = coordinator(
            send = {},
            onOptimisticExpired = { expired += it },
            onSettled = { settled += it },
        )

        coordinator.submit(BOX_ID, 4)
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()
        coordinator.confirm(BOX_ID, 2)
        advanceTimeBy(LOCKOUT_MILLIS - DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf(BOX_ID), expired)
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `new local change restarts debounce and lockout`() = runTest {
        val sent = mutableListOf<Int>()
        val expired = mutableListOf<String>()
        val coordinator = coordinator(
            send = { sent += it },
            onOptimisticExpired = { expired += it },
        )

        coordinator.submit(BOX_ID, 3)
        advanceTimeBy(LOCKOUT_MILLIS - 1)
        coordinator.submit(BOX_ID, 5)
        runCurrent()

        assertTrue(expired.isEmpty())

        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf(3, 5), sent)

        advanceTimeBy(LOCKOUT_MILLIS - DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf(BOX_ID), expired)
    }

    @Test
    fun `missing confirmation refreshes once and reports the confirmed mismatch`() = runTest {
        var refreshes = 0
        val settled = mutableListOf<String?>()
        val coordinator = coordinator(
            send = {},
            refresh = {
                refreshes++
                4
            },
            onSettled = { settled += it },
        )

        coordinator.submit(BOX_ID, 5)
        advanceTimeBy(DEBOUNCE_MILLIS + CONFIRMATION_TIMEOUT_MILLIS)
        runCurrent()

        assertEquals(1, refreshes)
        assertEquals(1, settled.size)
        assertEquals("Lautstärke konnte nicht bestätigt werden", settled.single())
    }

    @Test
    fun `duplicate target does not restart timers or send twice`() = runTest {
        val sent = mutableListOf<Int>()
        val coordinator = coordinator(send = { sent += it })

        coordinator.submit(BOX_ID, 12)
        advanceTimeBy(DEBOUNCE_MILLIS - 1)
        coordinator.submit(BOX_ID, 12)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(12), sent)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        send: suspend (Int) -> Unit,
        refresh: suspend () -> Int? = { null },
        onDesired: suspend (Int) -> Unit = {},
        onOptimisticExpired: suspend (String) -> Unit = {},
        onSettled: suspend (String?) -> Unit = {},
    ) = VolumeCommandCoordinator(
        scope = this,
        send = { _, level -> send(level) },
        refreshConfirmed = { refresh() },
        onDesiredChanged = { _, level -> onDesired(level) },
        onOptimisticExpired = onOptimisticExpired,
        onSettled = { _, error -> onSettled(error) },
        debounceMillis = DEBOUNCE_MILLIS,
        optimisticLockoutMillis = LOCKOUT_MILLIS,
        confirmationTimeoutMillis = CONFIRMATION_TIMEOUT_MILLIS,
    )

    private companion object {
        const val BOX_ID = "D4594404DEAC"
        const val DEBOUNCE_MILLIS = 100L
        const val LOCKOUT_MILLIS = 300L
        const val CONFIRMATION_TIMEOUT_MILLIS = 500L
    }
}
