package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.WifiGateState
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiGatePolicyTest {
    @Test
    fun `allows every wifi when no home ssid is configured`() {
        val snapshot = WifiNetworkSnapshot(wifiAvailable = true)

        assertEquals(WifiGateState.AVAILABLE, evaluateWifiGate(snapshot, ConnectionProfile()))
    }

    @Test
    fun `blocks non wifi transports`() {
        assertEquals(
            WifiGateState.NO_WIFI,
            evaluateWifiGate(WifiNetworkSnapshot(), ConnectionProfile()),
        )
    }

    @Test
    fun `accepts either configured home ssid with exact case`() {
        val profile = ConnectionProfile(homeSsidPrimary = "Home", homeSsidSecondary = "Workshop")
        val base = WifiNetworkSnapshot(
            wifiAvailable = true,
            locationPermissionGranted = true,
            locationEnabled = true,
        )

        assertEquals(WifiGateState.AVAILABLE, evaluateWifiGate(base.copy(ssid = "Workshop"), profile))
        assertEquals(WifiGateState.NOT_HOME_WIFI, evaluateWifiGate(base.copy(ssid = "workshop"), profile))
    }

    @Test
    fun `requires permission and readable ssid only for restricted profiles`() {
        val profile = ConnectionProfile(homeSsidPrimary = "Home")

        assertEquals(
            WifiGateState.PERMISSION_REQUIRED,
            evaluateWifiGate(WifiNetworkSnapshot(wifiAvailable = true), profile),
        )
        assertEquals(
            WifiGateState.SSID_UNAVAILABLE,
            evaluateWifiGate(
                WifiNetworkSnapshot(
                    wifiAvailable = true,
                    locationPermissionGranted = true,
                    locationEnabled = false,
                ),
                profile,
            ),
        )
    }

    @Test
    fun `resumes once only after returning to approved wifi`() {
        assertEquals(
            true,
            shouldResumeAfterWifiTransition(
                WifiGateState.NO_WIFI,
                WifiGateState.AVAILABLE,
                requested = true,
            ),
        )
        assertEquals(
            false,
            shouldResumeAfterWifiTransition(
                WifiGateState.AVAILABLE,
                WifiGateState.AVAILABLE,
                requested = true,
            ),
        )
    }

    @Test
    fun `wifi reconnect switch controls whether a paused request is retained`() {
        assertEquals(true, keepConnectionRequestWhilePaused(requested = true, reconnectOnWifiReconnect = true))
        assertEquals(false, keepConnectionRequestWhilePaused(requested = true, reconnectOnWifiReconnect = false))
        assertEquals(false, keepConnectionRequestWhilePaused(requested = false, reconnectOnWifiReconnect = true))
    }
}
