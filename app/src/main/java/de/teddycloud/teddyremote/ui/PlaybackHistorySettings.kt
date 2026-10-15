package de.teddycloud.teddyremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.teddycloud.teddyremote.model.ProfilesState

@Composable
internal fun PlaybackHistorySettings(
    state: MainUiState,
    onResumeOfferTimeout: (Int) -> Unit,
    onTonieAutoResume: (String, Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var timeoutText by remember(state.profiles.resumeOfferTimeoutMinutes) {
        mutableStateOf(state.profiles.resumeOfferTimeoutMinutes.toString())
    }
    val timeout = timeoutText.toIntOrNull()
    val valid = timeout != null && timeout in ProfilesState.MIN_RESUME_OFFER_TIMEOUT_MINUTES..ProfilesState.MAX_RESUME_OFFER_TIMEOUT_MINUTES
    Card(shape = RoundedCornerShape(22.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.History, null)
            Spacer(Modifier.width(12.dp))
            Text("Wiedergabeverlauf", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Verlauf einklappen" else "Verlauf ausklappen")
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Fortsetzen-Angebot", style = MaterialTheme.typography.titleSmall)
                Text("So lange bleibt die bisherige Position geschützt, bevor die aktuelle übernommen wird.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { value ->
                        if (value.length <= 3 && value.all(Char::isDigit)) {
                            timeoutText = value
                            value.toIntOrNull()?.takeIf {
                                it in ProfilesState.MIN_RESUME_OFFER_TIMEOUT_MINUTES..ProfilesState.MAX_RESUME_OFFER_TIMEOUT_MINUTES
                            }?.let(onResumeOfferTimeout)
                        }
                    },
                    label = { Text("Antwortfrist") },
                    suffix = { Text("Minuten") },
                    supportingText = { Text("1 bis 120 Minuten") },
                    isError = !valid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Text("Auto-Resume pro Tonie", style = MaterialTheme.typography.titleSmall)
                Text("Die Einstellung gilt anhand der RUID auch nach einem Content- oder Versionswechsel.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Name oder Tonie-ID suchen") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Suche zurücksetzen") }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val tonies = filteredHistory(state.knownTonies, query)
                if (tonies.isEmpty()) Text(
                    if (state.knownTonies.isEmpty()) "Noch keine Tonies mit Wiedergabeverlauf erkannt." else "Keine passenden Tonies gefunden.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                tonies.forEach { tonie ->
                    key(tonie.ruid) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(tonie.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(tonie.ruid, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(tonie.autoResumeEnabled, onCheckedChange = { onTonieAutoResume(tonie.ruid, it) })
                        }
                    }
                }
            }
        }
    }
}
