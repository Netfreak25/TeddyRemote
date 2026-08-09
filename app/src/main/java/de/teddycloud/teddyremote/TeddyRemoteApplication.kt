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

    override fun onCreate() {
        super<Application>.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        applicationScope.launch { container.repository.autoStartIfConfigured() }
        applicationScope.launch {
            container.repository.connection.collectLatest { status ->
                if (status.desiredConnected) TeddyRemoteService.start(this@TeddyRemoteApplication)
                else TeddyRemoteService.stop(this@TeddyRemoteApplication)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        container.repository.setForeground(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        container.repository.setForeground(false)
    }
}
