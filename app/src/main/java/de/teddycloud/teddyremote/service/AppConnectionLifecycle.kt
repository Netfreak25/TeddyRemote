package de.teddycloud.teddyremote.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Application-owned lifetime: destroying the service cannot cancel its own disconnect. Main thread only. */
internal class AppConnectionLifecycle(
    private val scope: CoroutineScope,
    private val autoStart: suspend () -> Unit,
    private val disconnect: suspend () -> Unit,
    private val startService: () -> Boolean,
    private val stopService: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val _closed = MutableStateFlow(false)
    val closed = _closed.asStateFlow()
    private var startupHandled = false
    private var serviceRequested = false
    private var closingJob: Job? = null
    private var startupJob: Job? = null

    fun opened() {
        if (_closed.value) {
            _closed.value = false
            startupHandled = false
        }
        if (startupHandled) return
        startupHandled = true
        startupJob = scope.launch {
            closingJob?.join()
            if (!_closed.value) autoStart()
        }
    }

    fun reconcile(wanted: Boolean, foreground: Boolean) {
        if (_closed.value || closingJob?.isActive == true || !wanted) {
            if (serviceRequested) stopService()
            serviceRequested = false
        } else if (foreground && !serviceRequested) {
            serviceRequested = startService()
        }
    }

    fun serviceDestroyed() { serviceRequested = false }

    fun quit() {
        if (_closed.value || closingJob?.isActive == true) return
        _closed.value = true
        startupJob?.cancel()
        serviceRequested = false
        closingJob = scope.launch {
            try {
                disconnect()
            } catch (error: Exception) {
                onError(error)
            } finally {
                stopService()
            }
        }
    }
}
