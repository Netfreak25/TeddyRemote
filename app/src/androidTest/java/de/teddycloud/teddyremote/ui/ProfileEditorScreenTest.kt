package de.teddycloud.teddyremote.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileEditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mqttActionsAreHiddenWhenLiveUpdatesAreDisabled() {
        showEditor(ConnectionProfile(mqttEnabled = false))
        assertEquals(0, compose.onAllNodesWithText("MQTT Settings importieren").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("MQTT-Verbindung testen").fetchSemanticsNodes().size)
    }

    @Test
    fun mqttActionsAreShownWhenLiveUpdatesAreEnabled() {
        showEditor(ConnectionProfile(mqttEnabled = true, mqttHost = "mqtt.example"))
        assertEquals(1, compose.onAllNodesWithText("MQTT Settings importieren").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("MQTT-Verbindung testen").fetchSemanticsNodes().size)
    }

    @Test
    fun leavingAChangedDraftRequiresConfirmation() {
        val original = ConnectionProfile()
        val changed = original.copy(name = "Geändert")
        showEditor(changed, original)

        compose.onNodeWithContentDescription("Zurück").performClick()

        assertEquals(1, compose.onAllNodesWithText("Änderungen verwerfen?").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Weiter bearbeiten").fetchSemanticsNodes().size)
    }

    private fun showEditor(profile: ConnectionProfile, original: ConnectionProfile = profile) {
        compose.setContent {
            TeddyRemoteTheme(ThemeMode.LIGHT) {
                ProfileEditorScreen(
                    editor = ProfileEditorUiState(
                        sessionId = 1,
                        draft = ProfileEditorDraft(originalProfile = original, originalPassword = "", profile = profile),
                    ),
                    onBack = {},
                    onSave = {},
                    onProfileChange = {},
                    onPasswordChange = {},
                    onTestApi = {},
                    onTestMqtt = {},
                    onImportMqtt = {},
                    onAcceptCertificate = {},
                    onRejectCertificate = {},
                    onResetCertificate = {},
                )
            }
        }
    }
}
