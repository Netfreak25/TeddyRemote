package de.teddycloud.teddyremote.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
        coordinator.cancelAll()
    }

    @Test
    fun `change during request waits and sends only the latest target next`() = runTest {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        var activeRequests = 0
        var maximumActiveRequests = 0
        val coordinator = coordinator(
            send = { target ->
                activeRequests++
                maximumActiveRequests = maxOf(maximumActiveRequests, activeRequests)
                sent += target
                try {
                    if (sent.size == 1) {
                        firstRequestStarted.complete(Unit)
                        releaseFirstRequest.await()
                    }
                } finally {
                    activeRequests--
                }
            },
        )

        coordinator.submit(BOX_ID, 3)
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()
        firstRequestStarted.await()

        coordinator.submit(BOX_ID, 4)
        coordinator.submit(BOX_ID, 7)
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf(3), sent)

        releaseFirstRequest.complete(Unit)
        runCurrent()

        assertEquals(listOf(3, 7), sent)
        assertEquals(1, maximumActiveRequests)
        coordinator.cancelAll()
    }

    @Test
    fun `stale confirmations never replace or settle the latest target`() = runTest {
        val desired = mutableListOf<Int>()
        val settled = mutableListOf<String?>()
        val coordinator = coordinator(
            send = {},
            onDesired = { desired += it },
            onSettled = { settled += it },
        )

        coordinator.submit(BOX_ID, 12)
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()
        (2..11).forEach { coordinator.confirm(BOX_ID, it) }
        advanceTimeBy(CONFIRMATION_TIMEOUT_MILLIS / 2)
        runCurrent()

        assertEquals(listOf(12), desired)
        assertTrue(settled.isEmpty())

        coordinator.confirm(BOX_ID, 12)
        assertEquals(listOf<String?>(null), settled)
    }

    @Test
    fun `confirmation can settle while the HTTP request is still returning`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val settled = mutableListOf<String?>()
        val coordinator = coordinator(
            send = {
                requestStarted.complete(Unit)
                releaseRequest.await()
            },
            onSettled = { settled += it },
        )

        coordinator.submit(BOX_ID, 6)
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()
        requestStarted.await()
        coordinator.confirm(BOX_ID, 6)

        assertEquals(listOf<String?>(null), settled)

        releaseRequest.complete(Unit)
        runCurrent()
        advanceTimeBy(CONFIRMATION_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(listOf<String?>(null), settled)
    }

    @Test
    fun `confirmation timeout starts after the request completes`() = runTest {
        var refreshes = 0
        val settled = mutableListOf<String?>()
        val coordinator = coordinator(
            send = { delay(REQUEST_DURATION_MILLIS) },
            refresh = {
                refreshes++
                5
            },
            onSettled = { settled += it },
        )

        coordinator.submit(BOX_ID, 5)
        advanceTimeBy(DEBOUNCE_MILLIS + REQUEST_DURATION_MILLIS + CONFIRMATION_TIMEOUT_MILLIS - 1)
        runCurrent()

        assertEquals(0, refreshes)
        assertTrue(settled.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, refreshes)
        assertEquals(listOf<String?>(null), settled)
    }

    @Test
    fun `failure of an obsolete request does not discard a newer target`() = runTest {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val failFirstRequest = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        val settled = mutableListOf<String?>()
        val coordinator = coordinator(
            send = { target ->
                sent += target
                if (sent.size == 1) {
                    firstRequestStarted.complete(Unit)
                    failFirstRequest.await()
                    error("old request failed")
                }
            },
            onSettled = { settled += it },
        )

        coordinator.submit(BOX_ID, 3)
        advanceTimeBy(DEBOUNCE_MILLIS)
        runCurrent()
        firstRequestStarted.await()
        coordinator.submit(BOX_ID, 8)
        advanceTimeBy(DEBOUNCE_MILLIS)
        failFirstRequest.complete(Unit)
        runCurrent()

        assertEquals(listOf(3, 8), sent)
        assertTrue(settled.isEmpty())

        coordinator.confirm(BOX_ID, 8)
        assertEquals(listOf<String?>(null), settled)
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
    fun `duplicate pending target does not restart timers or send twice`() = runTest {
        val sent = mutableListOf<Int>()
        val coordinator = coordinator(send = { sent += it })

        coordinator.submit(BOX_ID, 12)
        advanceTimeBy(DEBOUNCE_MILLIS - 1)
        coordinator.submit(BOX_ID, 12)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(12), sent)
        coordinator.cancelAll()
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
        nowMillis = { testScheduler.currentTime },
    )

    private companion object {
        const val BOX_ID = "D4594404DEAC"
        const val DEBOUNCE_MILLIS = 100L
        const val CONFIRMATION_TIMEOUT_MILLIS = 500L
        const val REQUEST_DURATION_MILLIS = 300L
    }
}
