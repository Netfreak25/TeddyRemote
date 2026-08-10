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
    fun `rapid changes send only latest value and keep it visible until confirmation`() = runTest {
        val sent = mutableListOf<Int>()
        val desired = mutableListOf<Int>()
        val settled = mutableListOf<String?>()
        val coordinator = coordinator(
            send = { sent += it },
            onDesired = { desired += it },
            onSettled = { settled += it },
        )

        coordinator.submit(BOX_ID, 1)
        coordinator.submit(BOX_ID, 2)
        coordinator.submit(BOX_ID, 3)
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf(1, 2, 3), desired)
        assertEquals(listOf(3), sent)
        assertTrue(settled.isEmpty())

        coordinator.confirm(BOX_ID, 2)
        assertTrue(settled.isEmpty())
        coordinator.confirm(BOX_ID, 3)
        assertEquals(listOf<String?>(null), settled)
    }

    @Test
    fun `change during request skips intermediate targets`() = runTest {
        val sent = mutableListOf<Int>()
        val settled = mutableListOf<String?>()
        val coordinator = coordinator(
            send = { sent += it },
            onSettled = { settled += it },
        )

        coordinator.submit(BOX_ID, 1)
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()
        coordinator.submit(BOX_ID, 2)
        coordinator.submit(BOX_ID, 3)
        runCurrent()
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf(1, 3), sent)
        coordinator.confirm(BOX_ID, 1)
        assertTrue(settled.isEmpty())
        coordinator.confirm(BOX_ID, 3)
        assertEquals(listOf<String?>(null), settled)
    }

    @Test
    fun `missing confirmation refreshes once and returns to confirmed state`() = runTest {
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

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        send: suspend (Int) -> Unit,
        refresh: suspend () -> Int? = { null },
        onDesired: suspend (Int) -> Unit = {},
        onSettled: suspend (String?) -> Unit = {},
    ) = VolumeCommandCoordinator(
        scope = this,
        send = { _, level -> send(level) },
        refreshConfirmed = { refresh() },
        onDesiredChanged = { _, level -> onDesired(level) },
        onSettled = { _, error -> onSettled(error) },
        debounceMillis = DEBOUNCE_MILLIS,
        confirmationTimeoutMillis = CONFIRMATION_TIMEOUT_MILLIS,
    )

    private companion object {
        const val BOX_ID = "D4594404DEAC"
        const val DEBOUNCE_MILLIS = 100L
        const val CONFIRMATION_TIMEOUT_MILLIS = 200L
    }
}
