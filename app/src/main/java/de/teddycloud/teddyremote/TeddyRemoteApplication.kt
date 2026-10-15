package de.teddycloud.teddyremote

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.teddycloud.teddyremote.service.TeddyRemoteService
import de.teddycloud.teddyremote.service.AppConnectionLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class TeddyRemoteApplication : Application(), DefaultLifecycleObserver {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appIsForeground = false
    internal lateinit var connectionLifecycle: AppConnectionLifecycle
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        connectionLifecycle = AppConnectionLifecycle(
            applicationScope,
            autoStart = { container.repository.autoStartIfConfigured() },
            disconnect = { container.repository.disconnect() },
            startService = { TeddyRemoteService.start(this) },
            stopService = { TeddyRemoteService.stop(this) },
            onError = { Log.e("TeddyRemote", "Connection shutdown failed", it) },
        )
        applicationScope.launch {
            container.repository.connection.map { it.desiredConnected }.distinctUntilChanged()
                .collectLatest { wanted ->
                    connectionLifecycle.reconcile(wanted, appIsForeground)
                }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        appIsForeground = true
        container.repository.setForeground(true)
        connectionLifecycle.reconcile(container.repository.connection.value.desiredConnected, true)
    }

    override fun onStop(owner: LifecycleOwner) {
        appIsForeground = false
        container.repository.setForeground(false)
    }
}
