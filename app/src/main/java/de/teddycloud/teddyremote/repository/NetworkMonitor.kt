package de.teddycloud.teddyremote.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import de.teddycloud.teddyremote.model.WifiGateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WifiNetworkSnapshot(
    val wifiAvailable: Boolean = false,
)

internal fun evaluateWifiGate(snapshot: WifiNetworkSnapshot): WifiGateState =
    if (snapshot.wifiAvailable) WifiGateState.AVAILABLE else WifiGateState.NO_WIFI

internal fun shouldResumeAfterWifiTransition(
    previous: WifiGateState,
    current: WifiGateState,
    requested: Boolean,
): Boolean = requested && previous != WifiGateState.AVAILABLE && current == WifiGateState.AVAILABLE

internal fun keepConnectionRequestWhilePaused(
    requested: Boolean,
    reconnectOnWifiReconnect: Boolean,
): Boolean = requested && reconnectOnWifiReconnect

/** Observes only Android's active default network and never scans for Wi-Fi networks. */
class NetworkMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(WifiNetworkSnapshot())
    val state: StateFlow<WifiNetworkSnapshot> = _state.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var snapshotNetwork: Network? = null

    init {
        registerCallback()
    }

    fun gate(): WifiGateState = evaluateWifiGate(_state.value)

    /** Refreshes the current default-network snapshot without replacing the registered callback. */
    fun refresh() {
        publishActiveNetwork()
    }

    /** Pins all sockets in this app process to the currently active Wi-Fi network. */
    fun bindToActiveWifi(): Boolean {
        if (gate() != WifiGateState.AVAILABLE) return false
        val network = connectivityManager.activeNetwork ?: return false
        if (snapshotNetwork != network) return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        return connectivityManager.bindProcessToNetwork(network)
    }

    fun releaseBinding() {
        connectivityManager.bindProcessToNetwork(null)
    }

    private fun registerCallback() {
        callback?.let { previous -> runCatching { connectivityManager.unregisterNetworkCallback(previous) } }
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (snapshotNetwork == network) publish(null, null)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                publish(network, capabilities)
            }
        }
        callback = next
        connectivityManager.registerDefaultNetworkCallback(next)
        publishActiveNetwork()
    }

    private fun publishActiveNetwork() {
        val network = connectivityManager.activeNetwork
        publish(network, network?.let(connectivityManager::getNetworkCapabilities))
    }

    private fun publish(network: Network?, capabilities: NetworkCapabilities?) {
        val activeNetwork = connectivityManager.activeNetwork
        if (network == null || network != activeNetwork || capabilities == null) {
            snapshotNetwork = null
            _state.value = WifiNetworkSnapshot()
            return
        }
        snapshotNetwork = network
        val wifiAvailable = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        _state.value = WifiNetworkSnapshot(wifiAvailable = wifiAvailable)
    }
}
