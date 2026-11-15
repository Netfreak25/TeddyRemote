package de.teddycloud.teddyremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.teddycloud.teddyremote.BuildConfig
import de.teddycloud.teddyremote.updates.UpdateStage
import de.teddycloud.teddyremote.updates.UpdateUiState

@Composable
internal fun UpdateCard(
    state: UpdateUiState,
    wifiAvailable: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onCancelInstallation: () -> Unit,
    onPermission: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleMedium)
            Text("Installiert: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
            if (state.stage == UpdateStage.DISABLED) {
                Text("Der GitHub-Updater ist nur in der Release-App verfügbar. Debug-APKs werden nicht automatisch ersetzt.")
                return@Column
            }
            Text("Nur reguläre GitHub-Releases. Die Installation bestätigst du selbst.", style = MaterialTheme.typography.bodySmall)
            state.release?.let { release ->
                Text("Verfügbar: ${release.version}", style = MaterialTheme.typography.titleSmall)
                if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodySmall)
            }
            state.message?.let { Text(it, color = if (state.stage == UpdateStage.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            when (state.stage) {
                UpdateStage.CHECKING, UpdateStage.DOWNLOADING -> {
                    if (state.stage == UpdateStage.DOWNLOADING) {
                        LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Download: ${state.progress} %", style = MaterialTheme.typography.labelSmall)
                    } else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Release wird geprüft …") }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
                }
                UpdateStage.INSTALLING -> {
                    OutlinedButton(onClick = onCancelInstallation, modifier = Modifier.fillMaxWidth()) { Text("Installation abbrechen") }
                }
                UpdateStage.READY -> {
                    Text("Release-APK geprüft. Android fragt vor der Installation noch einmal nach.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = if (state.permissionRequired) onPermission else onInstall, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.permissionRequired) "Installation erlauben" else "Installieren")
                    }
                }
                else -> {
                    if (state.stage == UpdateStage.CURRENT && state.message == null) Text("Keine neuere reguläre Release verfügbar.")
                    if (!wifiAvailable) Text("Für die Update-Suche und den Download ist WLAN erforderlich.", style = MaterialTheme.typography.bodySmall)
                    if (state.release != null && state.stage in setOf(UpdateStage.AVAILABLE, UpdateStage.ERROR)) {
                        Button(onClick = onDownload, enabled = wifiAvailable, modifier = Modifier.fillMaxWidth()) { Text("Herunterladen") }
                    }
                    OutlinedButton(onClick = onCheck, enabled = wifiAvailable, modifier = Modifier.fillMaxWidth()) { Text("Nach Updates suchen") }
                }
            }
        }
    }
}
