package de.teddycloud.teddyremote.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.teddycloud.teddyremote.model.ThemeMode
import de.teddycloud.teddyremote.updates.AppRelease
import de.teddycloud.teddyremote.updates.UpdateStage
import de.teddycloud.teddyremote.updates.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyAnExplicitInstallTapStartsInstallation() {
        var installs = 0
        compose.setContent {
            TeddyRemoteTheme(ThemeMode.LIGHT) {
                UpdateCard(UpdateUiState(UpdateStage.READY), true,
                    {}, {}, { installs++ }, {}, {}, {})
            }
        }
        compose.runOnIdle { assertEquals(0, installs) }
        compose.onNodeWithText("Installieren").performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }

    @Test fun noWifiDisablesNetworkButtonsAndDoesNotOfferInstallation() {
        compose.setContent {
            TeddyRemoteTheme(ThemeMode.LIGHT) {
                UpdateCard(UpdateUiState(UpdateStage.AVAILABLE,
                    AppRelease("0.1.34", "Notes", "release.apk", "https://example.invalid/a.apk", 1)),
                    false, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Nach Updates suchen").assertIsNotEnabled()
        compose.onNodeWithText("Herunterladen").assertIsNotEnabled()
        compose.onNodeWithText("Installieren").assertDoesNotExist()
    }
}
