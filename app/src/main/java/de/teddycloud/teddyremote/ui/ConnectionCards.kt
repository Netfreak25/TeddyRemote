package de.teddycloud.teddyremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.WifiGateState
import de.teddycloud.teddyremote.model.userMessage

@Composable
internal fun ConnectionAction(state: MainUiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val requested = state.connection.desiredConnected
    TextButton(
        onClick = if (requested) onDisconnect else onConnect,
        enabled = requested || (state.profiles.activeProfile != null && state.connection.wifiGate == WifiGateState.AVAILABLE),
    ) {
        Icon(if (requested) Icons.Rounded.LinkOff else Icons.Rounded.Link, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (requested) "Trennen" else "Verbinden")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServerProfileCard(
    profile: ConnectionProfile,
    state: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val active = profile.id == state.profiles.activeProfileId
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, null, Modifier.size(38.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (active) "Aktives Serverprofil" else "Serverprofil", style = MaterialTheme.typography.labelMedium)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Profilaktionen") }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Duplizieren") }, onClick = { menuOpen = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Löschen") }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            Text(profile.apiBaseUrl, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (profile.apiBaseUrl.startsWith("https://", true)) "HTTPS · verschlüsselt" else "HTTP · unverschlüsselt",
                style = MaterialTheme.typography.labelMedium,
            )
            if (active) ConnectionBadge(state.connection.apiStatus, "API")
            HorizontalDivider()
            Text(
                if (profile.mqttEnabled) "MQTT · ${profile.mqttHost}:${profile.mqttPort}" else "MQTT deaktiviert · HTTP-Abgleich",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (profile.mqttEnabled) {
                Text(
                    "${if (profile.mqttTls) "TLS · verschlüsselt" else "Unverschlüsselt"} · ${profile.mqttPrefix}",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (active) ConnectionBadge(state.connection.mqttStatus, "MQTT")
            }
            if (active) state.connection.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bearbeiten")
                }
                if (active) ConnectionAction(state, onConnect, onDisconnect)
                else TextButton(onClick = onActivate) { Text("Aktivieren") }
            }
        }
    }
}

@Composable
internal fun WifiStatus(state: MainUiState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (state.connection.wifiGate == WifiGateState.AVAILABLE) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, null)
        Text(state.connection.wifiGate.userMessage, style = MaterialTheme.typography.bodyMedium)
    }
}
