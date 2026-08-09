package de.teddycloud.teddyremote.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onboardingOffersProfileSetup() {
        compose.setContent { TeddyRemoteTheme(de.teddycloud.teddyremote.model.ThemeMode.LIGHT) { OnboardingScreen {} } }
        compose.onNodeWithText("Willkommen bei TeddyRemote").assertIsDisplayed()
        compose.onNodeWithText("Serverprofil einrichten").assertIsDisplayed()
    }
}
