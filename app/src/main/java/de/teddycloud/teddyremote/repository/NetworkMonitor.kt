package de.teddycloud.teddyremote.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import androidx.core.content.ContextCompat
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.WifiGateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WifiNetworkSnapshot(
    val wifiAvailable: Boolean = false,
    val ssid: String? = null,
    val locationPermissionGranted: Boolean = false,
    val locationEnabled: Boolean = false,
)

internal fun evaluateWifiGate(
    snapshot: WifiNetworkSnapshot,
    profile: ConnectionProfile,
): WifiGateState {
    if (!snapshot.wifiAvailable) return WifiGateState.NO_WIFI
    if (profile.homeSsids.isEmpty()) return WifiGateState.AVAILABLE
    if (!snapshot.locationPermissionGranted) return WifiGateState.PERMISSION_REQUIRED
    if (!snapshot.locationEnabled || snapshot.ssid == null) return WifiGateState.SSID_UNAVAILABLE
    return if (snapshot.ssid in profile.homeSsids) {
        WifiGateState.AVAILABLE
    } else {
        WifiGateState.NOT_HOME_WIFI
    }
}

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
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val _state = MutableStateFlow(WifiNetworkSnapshot())
    val state: StateFlow<WifiNetworkSnapshot> = _state.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var snapshotNetwork: Network? = null

    init {
        registerCallback()
    }

    fun gate(profile: ConnectionProfile): WifiGateState = evaluateWifiGate(_state.value, profile.normalized())

    /** Refreshes permission-sensitive network information after a runtime permission change. */
    fun refresh() {
        registerCallback()
    }

    /** Pins all sockets in this app process to the currently approved Wi-Fi network. */
    fun bindToApprovedWifi(profile: ConnectionProfile): Boolean {
        if (gate(profile) != WifiGateState.AVAILABLE) return false
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
        val initialRegistration = callback == null
        callback?.let { previous -> runCatching { connectivityManager.unregisterNetworkCallback(previous) } }
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = publish(network)
                override fun onLost(network: Network) = publishActiveNetwork()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    publish(network, capabilities)
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publish(network)
                override fun onLost(network: Network) = publishActiveNetwork()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    publish(network, capabilities)
                }
            }
        }
        callback = next
        connectivityManager.registerDefaultNetworkCallback(next)
        if (initialRegistration) publishActiveNetwork()
    }

    private fun publishActiveNetwork() {
        val network = connectivityManager.activeNetwork
        publish(network, network?.let(connectivityManager::getNetworkCapabilities))
    }

    private fun publish(network: Network?) {
        publish(network, network?.let(connectivityManager::getNetworkCapabilities))
    }

    private fun publish(network: Network?, capabilities: NetworkCapabilities?) {
        val activeNetwork = connectivityManager.activeNetwork
        if (network == null || network != activeNetwork || capabilities == null) {
            snapshotNetwork = null
            _state.value = WifiNetworkSnapshot(
                locationPermissionGranted = hasLocationPermission(),
                locationEnabled = locationManager.isLocationEnabled,
            )
            return
        }
        snapshotNetwork = network
        val wifiAvailable = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val permissionGranted = hasLocationPermission()
        val locationEnabled = locationManager.isLocationEnabled
        val ssid = if (wifiAvailable && permissionGranted && locationEnabled) {
            (capabilities.transportInfo as? WifiInfo)?.ssid.normalizeSsid()
        } else {
            null
        }
        _state.value = WifiNetworkSnapshot(
            wifiAvailable = wifiAvailable,
            ssid = ssid,
            locationPermissionGranted = permissionGranted,
            locationEnabled = locationEnabled,
        )
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun String?.normalizeSsid(): String? = this
    ?.takeUnless { it == UNKNOWN_SSID }
    ?.removeSurrounding("\"")
    ?.takeIf(String::isNotBlank)

private const val UNKNOWN_SSID = "<unknown ssid>"
