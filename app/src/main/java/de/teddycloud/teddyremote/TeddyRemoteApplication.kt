package de.teddycloud.teddyremote

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.teddycloud.teddyremote.service.TeddyRemoteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TeddyRemoteApplication : Application(), DefaultLifecycleObserver {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appIsForeground = false

    override fun onCreate() {
        super<Application>.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        applicationScope.launch { container.repository.autoStartIfConfigured() }
        applicationScope.launch {
            container.repository.connection.collectLatest { status ->
                when {
                    !status.desiredConnected -> TeddyRemoteService.stop(this@TeddyRemoteApplication)
                    appIsForeground -> TeddyRemoteService.start(this@TeddyRemoteApplication)
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        appIsForeground = true
        container.repository.setForeground(true)
        if (container.repository.connection.value.desiredConnected) {
            TeddyRemoteService.start(this)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        appIsForeground = false
        container.repository.setForeground(false)
    }
}
