package de.teddycloud.teddyremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.teddycloud.teddyremote.model.LinkStatus

/** Central connection surface kept separate from persistent profile settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionOverviewScreen(
    state: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenBoxes: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val profile = state.profiles.activeProfile
    val connecting = state.connection.apiStatus == LinkStatus.CONNECTING
    val onlineBoxes = state.boxes.count { it.box.runtime.online }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Übersicht") },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile?.name ?: "Keine TeddyCloud eingerichtet",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                profile?.apiBaseUrl ?: "Lege zuerst ein Serverprofil an.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ConnectionBadge(state.connection.apiStatus, "API")
                        if (profile?.mqttEnabled == true) {
                            ConnectionBadge(state.connection.mqttStatus, "MQTT")
                        }
                    }

                    state.connection.message?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    if (state.connection.desiredConnected) {
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                            Text("Verbindung trennen")
                        }
                    } else {
                        Button(
                            onClick = onConnect,
                            enabled = profile != null && !connecting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (connecting) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (connecting) "Verbindet …" else "Verbinden")
                        }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Toniebox 2", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.connection.isApiUsable) {
                                    "$onlineBoxes von ${state.boxes.size} online"
                                } else {
                                    "Nach dem Verbinden werden hier deine Boxen angezeigt."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onOpenBoxes, enabled = state.connection.isApiUsable) {
                            Text("Boxen öffnen")
                        }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Router, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Serverprofile", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Server, MQTT und Reconnect-Verhalten verwalten",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Einstellungen")
                    }
                }
            }
        }
    }
}
