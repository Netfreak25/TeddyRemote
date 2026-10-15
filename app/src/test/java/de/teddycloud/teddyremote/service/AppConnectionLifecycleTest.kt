package de.teddycloud.teddyremote.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppConnectionLifecycleTest {
    @Test fun `quit survives delayed cleanup and overlapping status updates exactly once`() = runTest {
        var starts = 0
        var stops = 0
        var disconnects = 0
        var autoStarts = 0
        val cleanup = CompletableDeferred<Unit>()
        val lifecycle = AppConnectionLifecycle(this, { autoStarts++ }, {
            disconnects++
            cleanup.await()
        }, { starts++; true }, { stops++ }, { throw it })
        lifecycle.opened()
        runCurrent()
        repeat(3) { lifecycle.reconcile(true, true) }
        assertEquals(1, starts)
        lifecycle.quit()
        runCurrent()
        lifecycle.serviceDestroyed()
        repeat(3) { lifecycle.reconcile(true, true); lifecycle.quit() }
        assertEquals(1, starts)
        assertEquals(1, disconnects)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(1, stops)
        assertTrue(lifecycle.closed.value)
        lifecycle.opened()
        runCurrent()
        assertEquals(2, autoStarts)
        assertFalse(lifecycle.closed.value)
    }

    @Test fun `manual disconnect or activity recreation does not reapply autostart`() = runTest {
        var autoStarts = 0
        val lifecycle = AppConnectionLifecycle(this, { autoStarts++ }, {}, { true }, {}, { throw it })
        lifecycle.opened()
        runCurrent()
        lifecycle.reconcile(false, true)
        lifecycle.opened()
        lifecycle.reconcile(false, true)
        runCurrent()
        assertEquals(1, autoStarts)
    }

    @Test fun `reopen waits for cleanup without reconnecting twice`() = runTest {
        var autoStarts = 0
        val cleanup = CompletableDeferred<Unit>()
        val lifecycle = AppConnectionLifecycle(this, { autoStarts++ }, { cleanup.await() }, { true }, {}, { throw it })
        lifecycle.opened()
        runCurrent()
        lifecycle.quit()
        runCurrent()
        lifecycle.opened()
        lifecycle.opened()
        runCurrent()
        assertEquals(1, autoStarts)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(2, autoStarts)
    }

    @Test fun `deferred service start is retried on foreground entry`() = runTest {
        var attempts = 0
        val lifecycle = AppConnectionLifecycle(this, {}, {}, { ++attempts > 1 }, {}, { throw it })
        lifecycle.reconcile(true, true)
        lifecycle.reconcile(true, true)
        lifecycle.reconcile(true, true)
        assertEquals(2, attempts)
    }
}
