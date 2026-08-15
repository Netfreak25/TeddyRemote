package de.teddycloud.teddyremote.repository

import de.teddycloud.teddyremote.model.WifiGateState
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiGatePolicyTest {
    @Test
    fun `allows the active wifi transport`() {
        val snapshot = WifiNetworkSnapshot(wifiAvailable = true)

        assertEquals(WifiGateState.AVAILABLE, evaluateWifiGate(snapshot))
    }

    @Test
    fun `blocks non wifi transports`() {
        assertEquals(
            WifiGateState.NO_WIFI,
            evaluateWifiGate(WifiNetworkSnapshot()),
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
