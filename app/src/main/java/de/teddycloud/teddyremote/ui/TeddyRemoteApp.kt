package de.teddycloud.teddyremote.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

@Composable
fun TeddyRemoteApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    TeddyRemoteTheme(state.profiles.themeMode) {
        val showNavigation = !state.needsOnboarding && state.screen in setOf(AppScreen.HOME, AppScreen.SETTINGS)
        Scaffold(
            bottomBar = {
                if (showNavigation) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = state.screen == AppScreen.HOME,
                            onClick = { viewModel.navigate(AppScreen.HOME) },
                            icon = { Icon(Icons.Rounded.Home, null) },
                            label = { Text("Boxen") },
                        )
                        NavigationBarItem(
                            selected = state.screen == AppScreen.SETTINGS,
                            onClick = { viewModel.navigate(AppScreen.SETTINGS) },
                            icon = { Icon(Icons.Rounded.Settings, null) },
                            label = { Text("Einstellungen") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    state.needsOnboarding && state.screen != AppScreen.PROFILE_EDITOR -> OnboardingScreen(
                        onCreateProfile = { viewModel.editProfile(null) },
                    )
                    state.screen == AppScreen.PROFILE_EDITOR -> ProfileEditorScreen(
                        initialProfile = requireNotNull(state.editingProfile),
                        initialPassword = state.editingPassword,
                        apiTest = state.apiTest,
                        mqttTest = state.mqttTest,
                        mqttImport = state.mqttImport,
                        onBack = { viewModel.navigate(if (state.needsOnboarding) AppScreen.HOME else AppScreen.SETTINGS) },
                        onSave = viewModel::saveProfile,
                        onTestApi = viewModel::testApi,
                        onTestMqtt = viewModel::testMqtt,
                        onImportMqtt = viewModel::importMqttSettings,
                        onAcceptCertificate = viewModel::acceptTestCertificate,
                    )
                    state.screen == AppScreen.DIAGNOSTICS -> DiagnosticsScreen(
                        state = state,
                        onBack = { viewModel.navigate(AppScreen.SETTINGS) },
                    )
                    state.screen == AppScreen.SETTINGS -> SettingsScreen(
                        state = state,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                        onEdit = viewModel::editProfile,
                        onAdd = { viewModel.editProfile(null) },
                        onDuplicate = viewModel::duplicateProfile,
                        onDelete = viewModel::deleteProfile,
                        onActivate = viewModel::activateProfile,
                        onTheme = viewModel::setTheme,
                        onDiagnostics = { viewModel.navigate(AppScreen.DIAGNOSTICS) },
                        onOpenMqttGuide = { openMqttGuide(context) },
                    )
                    else -> HomeScreen(
                        state = state,
                        onRefresh = viewModel::refresh,
                        onConnect = viewModel::connect,
                        onOpenSettings = { viewModel.navigate(AppScreen.SETTINGS) },
                        onPlayback = viewModel::playback,
                        onRefreshPlaylist = viewModel::refreshPlaylist,
                        onVolume = viewModel::setVolume,
                        onPing = viewModel::ping,
                        onBedtime = viewModel::setBedtime,
                        onSleep = viewModel::sleep,
                        onBrightness = viewModel::setBrightness,
                        onBedtimeBrightness = viewModel::setBedtimeBrightness,
                    )
                }
            }
        }

        state.connection.certificateCandidate?.let { candidate ->
            CertificateDialog(
                candidate = candidate,
                onAccept = { viewModel.confirmConnectionCertificate(candidate) },
                onReject = viewModel::rejectConnectionCertificate,
            )
        }
    }
}

private const val MQTT_GUIDE_ASSET = "guides/mqtt-server-konfigurieren.html"
private const val MQTT_GUIDE_FILE = "TeddyRemote-MQTT-Server-konfigurieren.html"

private fun openMqttGuide(context: android.content.Context) {
    val result = runCatching {
        val guideDirectory = File(context.cacheDir, "guides").apply { mkdirs() }
        val guide = File(guideDirectory, MQTT_GUIDE_FILE)
        context.assets.open(MQTT_GUIDE_ASSET).use { source -> guide.outputStream().use(source::copyTo) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", guide)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/html")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
    if (result.isFailure) {
        Toast.makeText(context, "Die MQTT-Anleitung konnte nicht im Browser geöffnet werden", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun CertificateDialog(
    candidate: de.teddycloud.teddyremote.model.CertificateCandidate,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Serverzertifikat bestätigen") },
        text = {
            Text(
                "${candidate.host}:${candidate.port}\n\n" +
                    "Subject: ${candidate.subject}\nIssuer: ${candidate.issuer}\n\n" +
                    "SHA-256:\n${candidate.fingerprintSha256}\n\n" +
                    "Bestätige den Fingerprint nur, wenn er zu deiner TeddyCloud gehört.",
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Vertrauen") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Abbrechen") } },
    )
}
