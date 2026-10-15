package de.teddycloud.teddyremote.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import de.teddycloud.teddyremote.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OverviewSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineCardExpandsAndControlsDoNotCollapseIt() {
        val model = mutableStateOf(BoxUiModel(TonieboxDto("BOX", boxName = "Testbox", runtime = BoxRuntime(
            online = false,
            battery = BatteryRuntime(percent = 63),
            controls = BoxControls(playback = true),
            playback = PlaybackRuntime(ruid = "1234567890ABCDEF"),
        ))))
        var plays = 0
        compose.setContent {
            TeddyRemoteTheme(ThemeMode.LIGHT) {
                TonieboxCard(model.value, showTonieId = false, highlighted = false,
                    onPlayback = { plays++ }, onSeek = { _, _ -> }, onResumePlayback = {}, onDeclineResume = {},
                    onRefreshPlaylist = {}, onVolume = {}, onPing = {}, onBedtime = { _, _ -> }, onSleep = {},
                    onBrightness = {}, onBedtimeBrightness = {})
            }
        }
        compose.onNodeWithText("63 %").assertDoesNotExist()
        compose.onNodeWithText("Testbox").performClick()
        compose.onNodeWithText("63 %").assertExists()
        compose.runOnIdle { model.value = model.value.let { it.copy(box = it.box.copy(runtime = it.box.runtime.copy(online = true))) } }
        compose.onNodeWithContentDescription("Play/Pause").performClick()
        compose.onNodeWithText("63 %").assertExists()
        compose.onNodeWithText("1234567890ABCDEF").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, plays) }
        compose.onNodeWithText("Testbox").performClick()
        compose.onNodeWithText("63 %").assertDoesNotExist()
        compose.runOnIdle { model.value = model.value.let { it.copy(box = it.box.copy(runtime = it.box.runtime.copy(volume = VolumeRuntime(level = 8)))) } }
        compose.onNodeWithText("63 %").assertDoesNotExist()
    }

    @Test fun historyStartsCollapsedAndSearchesNamesAndIds() {
        val state = MainUiState(knownTonies = listOf(
            PlaybackToniePreference("ABC123", "Schlummerband", true, 20),
            PlaybackToniePreference("DEF456", "Geschichten", false, 10),
        ))
        compose.setContent { TeddyRemoteTheme(ThemeMode.LIGHT) { PlaybackHistorySettings(state, {}, { _, _ -> }) } }
        compose.onNodeWithText("Schlummerband").assertDoesNotExist()
        compose.onNodeWithText("Wiedergabeverlauf").performClick()
        compose.onNodeWithText("Name oder Tonie-ID suchen").performTextInput("def")
        compose.onNodeWithText("Schlummerband").assertDoesNotExist()
        compose.onNodeWithText("Geschichten").assertExists()
        compose.onNodeWithContentDescription("Suche zurücksetzen").performClick()
        compose.onNodeWithText("Schlummerband").assertExists()
    }

    @Test fun wifiGatePreventsConnectButAlwaysAllowsDisconnect() {
        val profile = ConnectionProfile()
        val state = mutableStateOf(MainUiState(profiles = ProfilesState(profiles = listOf(profile), activeProfileId = profile.id)))
        compose.setContent { TeddyRemoteTheme(ThemeMode.LIGHT) { ConnectionAction(state.value, {}, {}) } }
        compose.onNodeWithText("Verbinden").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(connection = state.value.connection.copy(desiredConnected = true)) }
        compose.onNodeWithText("Trennen").assertIsEnabled()
    }
}
