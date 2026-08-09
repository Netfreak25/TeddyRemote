package de.teddycloud.teddyremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.teddycloud.teddyremote.model.CertificateCandidate
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.LinkStatus
import de.teddycloud.teddyremote.model.ThemeMode

@Composable
fun OnboardingScreen(onCreateProfile: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.padding(24.dp).fillMaxWidth(), elevation = CardDefaults.cardElevation(8.dp)) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Icon(Icons.Rounded.Router, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Willkommen bei TeddyRemote", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "TeddyRemote braucht eine TeddyCloud-Verbindung. MQTT ist optional und beschleunigt Live-Updates.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onCreateProfile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Serverprofil einrichten")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: (ConnectionProfile?) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onActivate: (String) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onDiagnostics: () -> Unit,
    onExportMqttGuide: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ConnectionProfile?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Einstellungen") },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        Icons.Rounded.PowerSettingsNew,
                        null,
                        Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            state.profiles.activeProfile?.name ?: "Kein aktives Profil",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConnectionBadge(state.connection.apiStatus, "API")
                            if (state.profiles.activeProfile?.mqttEnabled == true) {
                                ConnectionBadge(state.connection.mqttStatus, "MQTT")
                            }
                        }
                        state.connection.message?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (state.connection.desiredConnected) {
                        OutlinedButton(onClick = onDisconnect) { Text("Trennen") }
                    } else {
                        Button(onClick = onConnect, enabled = state.profiles.activeProfile != null) { Text("Verbinden") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Serverprofile", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "TeddyCloud und optionale Live-Updates",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Neu")
                }
            }
            state.profiles.profiles.forEach { profile ->
                val active = profile.id == state.profiles.activeProfileId
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = if (active) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    profile.apiBaseUrl,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (profile.mqttEnabled) "MQTT · ${profile.mqttHost}:${profile.mqttPort} · ${profile.mqttPrefix}" else "MQTT deaktiviert",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (active) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Rounded.CheckCircle, "Aktiv", tint = MaterialTheme.colorScheme.primary)
                                    Text("Aktiv", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!active) {
                                TextButton(onClick = { onActivate(profile.id) }) { Text("Aktivieren") }
                            }
                            TextButton(onClick = { onEdit(profile) }) {
                                Icon(Icons.Rounded.Edit, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Bearbeiten")
                            }
                            IconButton(onClick = { onDuplicate(profile.id) }) {
                                Icon(Icons.Rounded.ContentCopy, "Duplizieren")
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { pendingDelete = profile }) {
                                Icon(Icons.Rounded.Delete, "Löschen", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            Text("App", style = MaterialTheme.typography.titleLarge)
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Darstellung", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.profiles.themeMode == mode,
                                onClick = { onTheme(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "System"
                                            ThemeMode.LIGHT -> "Hell"
                                            ThemeMode.DARK -> "Dunkel"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                    OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Info, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Verbindungsdiagnose")
                    }
                }
            }

            Text("Anleitungen", style = MaterialTheme.typography.titleLarge)
            Card(
                onClick = onExportMqttGuide,
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("MQTT-Server konfigurieren", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Offline-Anleitung als HTML-Datei speichern",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.Download, "HTML herunterladen", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Profil löschen?") },
            text = { Text("${profile.name} und das verschlüsselte MQTT-Passwort werden entfernt.") },
            confirmButton = {
                TextButton(onClick = { onDelete(profile.id); pendingDelete = null }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    initialProfile: ConnectionProfile,
    initialPassword: String,
    apiTest: ProfileTestState,
    mqttTest: ProfileTestState,
    onBack: () -> Unit,
    onSave: (ConnectionProfile, String, Boolean) -> Unit,
    onTestApi: (ConnectionProfile) -> Unit,
    onTestMqtt: (ConnectionProfile, String) -> Unit,
    onAcceptCertificate: (CertificateCandidate) -> Unit,
) {
    var profile by remember(initialProfile.id) { mutableStateOf(initialProfile) }
    var password by remember(initialProfile.id) { mutableStateOf(initialPassword) }
    var saveAndConnect by remember { mutableStateOf(true) }
    val errors = profile.validate()
    LaunchedEffect(initialProfile) { profile = initialProfile }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialProfile.apiBaseUrl.isBlank()) "Profil einrichten" else "Profil bearbeiten") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("TeddyCloud API", Icons.Rounded.Cloud)
            OutlinedTextField(
                value = profile.name,
                onValueChange = { profile = profile.copy(name = it) },
                label = { Text("Profilname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = profile.apiBaseUrl,
                onValueChange = { profile = profile.copy(apiBaseUrl = it, apiCertificateFingerprint = null) },
                label = { Text("TeddyCloud-URL") },
                supportingText = { Text("z. B. https://192.168.1.100:8443/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (profile.apiBaseUrl.startsWith("http://", ignoreCase = true)) {
                InlineNotice("Die API-Verbindung ist unverschlüsselt.", warning = true)
            }
            profile.apiCertificateFingerprint?.let { FingerprintLine("API-Pin", it) }
            TestRow(
                label = "API testen",
                state = apiTest,
                enabled = profile.apiBaseUrl.isNotBlank(),
                onClick = { onTestApi(profile.normalized()) },
                onAcceptCertificate = { candidate ->
                    profile = profile.copy(apiCertificateFingerprint = candidate.fingerprintSha256)
                    onAcceptCertificate(candidate)
                },
            )

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("MQTT Live-Updates", Icons.Rounded.Router, Modifier.weight(1f))
                Switch(checked = profile.mqttEnabled, onCheckedChange = { profile = profile.copy(mqttEnabled = it) })
            }
            Text("Optional. Ohne MQTT verwendet TeddyRemote adaptives API-Polling.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (profile.mqttEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = profile.mqttHost,
                        onValueChange = { profile = profile.copy(mqttHost = it, mqttCertificateFingerprint = null) },
                        label = { Text("Brokerhost") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = profile.mqttPort.toString(),
                        onValueChange = { profile = profile.copy(mqttPort = it.toIntOrNull() ?: profile.mqttPort) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                }
                OutlinedTextField(
                    value = profile.mqttPrefix,
                    onValueChange = { profile = profile.copy(mqttPrefix = it) },
                    label = { Text("Themenpräfix") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = profile.mqttUsername,
                    onValueChange = { profile = profile.copy(mqttUsername = it) },
                    label = { Text("Benutzername (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Passwort (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledSwitch("TLS aktivieren", profile.mqttTls) {
                    profile = profile.copy(mqttTls = it, mqttCertificateFingerprint = null)
                }
                profile.mqttCertificateFingerprint?.let { FingerprintLine("MQTT-Pin", it) }
                Text("Client-ID: ${profile.mqttClientId}", style = MaterialTheme.typography.labelSmall)
                TestRow(
                    label = "MQTT testen",
                    state = mqttTest,
                    enabled = profile.mqttHost.isNotBlank(),
                    onClick = { onTestMqtt(profile.normalized(), password) },
                    onAcceptCertificate = { candidate ->
                        profile = profile.copy(mqttCertificateFingerprint = candidate.fingerprintSha256)
                        onAcceptCertificate(candidate)
                    },
                )
            }

            HorizontalDivider()
            SectionTitle("Verbindungsverhalten", Icons.Rounded.PlayArrow)
            LabeledSwitch("Beim App-Start verbinden", profile.connectOnAppStart) {
                profile = profile.copy(connectOnAppStart = it)
            }
            LabeledSwitch("Automatisch neu verbinden", profile.autoReconnect) {
                profile = profile.copy(autoReconnect = it)
            }
            if (profile.autoReconnect) {
                NumberField("Maximale Retries (0 = unbegrenzt)", profile.maxRetries, Modifier.fillMaxWidth()) {
                    profile = profile.copy(maxRetries = it.coerceAtLeast(0))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("Initial (s)", profile.initialRetrySeconds, Modifier.weight(1f)) {
                        profile = profile.copy(initialRetrySeconds = it)
                    }
                    NumberField("Maximum (s)", profile.maxRetrySeconds, Modifier.weight(1f)) {
                        profile = profile.copy(maxRetrySeconds = it)
                    }
                }
            }
            errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = saveAndConnect, onCheckedChange = { saveAndConnect = it })
                Text("Nach dem Speichern verbinden")
            }
            Button(
                onClick = { onSave(profile.normalized(), password, saveAndConnect) },
                enabled = errors.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Profil speichern") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(state: MainUiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Verbindungsdiagnose") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiagnosticCard("API", state.connection.apiStatus, state.profiles.activeProfile?.apiBaseUrl, state.connection.message)
            DiagnosticCard(
                "MQTT",
                state.connection.mqttStatus,
                state.profiles.activeProfile?.takeIf { it.mqttEnabled }?.let { "${it.mqttHost}:${it.mqttPort}/${it.mqttPrefix}" }
                    ?: "Nicht konfiguriert",
                null,
            )
            state.profiles.activeProfile?.let { profile ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Profil", style = MaterialTheme.typography.titleMedium)
                        Text(profile.name)
                        Text("Client-ID: ${profile.mqttClientId}", style = MaterialTheme.typography.bodySmall)
                        Text("Auto-Reconnect: ${if (profile.autoReconnect) "an" else "aus"}")
                        Text("Retries: ${if (profile.maxRetries == 0) "unbegrenzt" else profile.maxRetries}")
                        profile.apiCertificateFingerprint?.let { FingerprintLine("API", it) }
                        profile.mqttCertificateFingerprint?.let { FingerprintLine("MQTT", it) }
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("TB2-Status", style = MaterialTheme.typography.titleMedium)
                    Text("${state.boxes.count { it.box.runtime.online }} online / ${state.boxes.size} erkannt")
                    Text("TeddyRemote protokolliert keine Passwörter oder Zertifikatsinhalte.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TestRow(
    label: String,
    state: ProfileTestState,
    enabled: Boolean,
    onClick: () -> Unit,
    onAcceptCertificate: (CertificateCandidate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onClick, enabled = enabled && state.status != LinkStatus.CONNECTING) {
            if (state.status == LinkStatus.CONNECTING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Security, null)
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.candidate?.let { candidate ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Unbekanntes Zertifikat", style = MaterialTheme.typography.titleSmall)
                    Text(candidate.subject, style = MaterialTheme.typography.bodySmall)
                    Text(candidate.fingerprintSha256, style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { onAcceptCertificate(candidate) }) { Text("Fingerprint übernehmen") }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, status: LinkStatus, target: String?, message: String?) {
    Card {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when (status) {
                LinkStatus.CONNECTED -> Icons.Rounded.CheckCircle
                LinkStatus.WARNING, LinkStatus.CONNECTING -> Icons.Rounded.Warning
                LinkStatus.ERROR -> Icons.Rounded.Error
                else -> Icons.Rounded.Info
            }
            val tint = when (status) {
                LinkStatus.CONNECTED -> Color(0xFF28A862)
                LinkStatus.WARNING, LinkStatus.CONNECTING -> Color(0xFFF0A020)
                LinkStatus.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            }
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("$title · ${statusLabel(status)}", style = MaterialTheme.typography.titleMedium)
                target?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier = Modifier, onChanged: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let(onChanged) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun FingerprintLine(label: String, fingerprint: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Fingerprint, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("$label: $fingerprint", style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}

@Composable
private fun InlineNotice(message: String, warning: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (warning) Icons.Rounded.Warning else Icons.Rounded.Info, null)
            Spacer(Modifier.width(8.dp))
            Text(message)
        }
    }
}

private fun statusLabel(status: LinkStatus): String = when (status) {
    LinkStatus.NOT_CHECKED -> "nicht geprüft"
    LinkStatus.CONNECTING -> "verbindet"
    LinkStatus.CONNECTED -> "verbunden"
    LinkStatus.WARNING -> "Warnung"
    LinkStatus.ERROR -> "Fehler"
    LinkStatus.DISCONNECTED -> "getrennt"
}
