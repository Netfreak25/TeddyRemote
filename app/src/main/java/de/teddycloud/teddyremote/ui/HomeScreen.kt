package de.teddycloud.teddyremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import coil.compose.AsyncImage
import de.teddycloud.teddyremote.model.BoxUiModel
import de.teddycloud.teddyremote.model.LinkStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlayback: (String, String, Int?) -> Unit,
    onVolume: (String, Int) -> Unit,
    onPing: (String) -> Unit,
    onBrightness: (String, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("TeddyRemote")
                    Text(
                        state.connection.profileName ?: "Keine TeddyCloud",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                ConnectionBadge(state.connection.apiStatus, "API")
                if (state.profiles.activeProfile?.mqttEnabled == true) {
                    Spacer(Modifier.width(6.dp))
                    ConnectionBadge(state.connection.mqttStatus, "MQTT")
                }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Einstellungen") }
            },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        )

        when {
            !state.connection.desiredConnected -> ConnectionEmptyState(
                title = "Nicht verbunden",
                detail = "Wähle ein Profil und verbinde dich mit deiner TeddyCloud.",
                action = "Verbinden",
                onAction = onConnect,
            )
            !state.connection.isApiUsable -> ConnectionEmptyState(
                title = "TeddyCloud nicht erreichbar",
                detail = state.connection.message ?: "Verbindung wird hergestellt …",
                action = "Erneut versuchen",
                onAction = onConnect,
            )
            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.boxes.isEmpty()) {
                    ConnectionEmptyState(
                        title = "Keine TB2 gefunden",
                        detail = "TeddyRemote zeigt ausschließlich Boxen mit boxGeneration = 2.",
                        action = "Aktualisieren",
                        onAction = onRefresh,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(360.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(state.boxes, key = { it.box.id }) { model ->
                            TonieboxCard(
                                model = model,
                                highlighted = state.focusedBoxId?.equals(model.box.id, ignoreCase = true) == true,
                                onPlayback = { action, chapter -> onPlayback(model.box.id, action, chapter) },
                                onVolume = { onVolume(model.box.id, it) },
                                onPing = { onPing(model.box.id) },
                                onBrightness = { onBrightness(model.box.id, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TonieboxCard(
    model: BoxUiModel,
    highlighted: Boolean,
    onPlayback: (String, Int?) -> Unit,
    onVolume: (Int) -> Unit,
    onPing: () -> Unit,
    onBrightness: (Int) -> Unit,
) {
    var playlistExpanded by remember(model.box.id) { mutableStateOf(false) }
    var deviceExpanded by remember(model.box.id) { mutableStateOf(false) }
    var volume by remember(model.box.id, model.box.runtime.volume.level) {
        mutableFloatStateOf((model.box.runtime.volume.level ?: 0).toFloat())
    }
    var brightness by remember(model.box.id, model.ringBrightness) {
        mutableFloatStateOf((model.ringBrightness ?: 100).toFloat())
    }
    val runtime = model.box.runtime
    val remoteControlVisible = isRemoteControlVisible(runtime.online, runtime.lastConnection)
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = if (highlighted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) 8.dp else 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (model.boxImageUrl == null) 76.dp else 292.dp)
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                model.boxImageUrl?.let { image ->
                    AsyncImage(
                        model = image,
                        contentDescription = model.box.boxName,
                        modifier = Modifier.fillMaxSize().padding(top = 2.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            model.box.boxName.ifBlank { model.box.commonName.ifBlank { model.box.id } },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (runtime.online) "Online" else "Zuletzt ${relativeLastSeen(runtime.lastConnection)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (runtime.online) Color(0xFF16834B) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(
                            if (runtime.online) Color(0xFF28A862) else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                    if (remoteControlVisible) {
                        IconButton(onClick = { deviceExpanded = !deviceExpanded }) {
                            Icon(Icons.Rounded.MoreVert, "Geräteoptionen")
                        }
                    }
                }
            }

            if (remoteControlVisible) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = model.metadata?.pictureUrl,
                        contentDescription = model.metadata?.title,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            model.metadata?.title ?: runtime.playback.tonie ?: "Kein Tonie",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            currentTrackTitle(model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        runtime.playback.ruid?.let {
                            Text(it.uppercase(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                runtime.battery.percent?.let {
                    StatusChip(Icons.Rounded.BatteryChargingFull, "$it %")
                }
                if ((runtime.headphones.connectedCount ?: 0) > 0) {
                    StatusChip(Icons.Rounded.Headphones, "${runtime.headphones.connectedCount} verbunden")
                }
                runtime.bedtime.state?.let { StatusChip(Icons.Rounded.Bedtime, it) }
            }

            if (remoteControlVisible) {
                Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    enabled = runtime.controls.playback && model.pendingCommand == null,
                    onClick = { onPlayback("prev", null) },
                ) { Icon(Icons.Rounded.SkipPrevious, "Vorheriges Kapitel") }
                FilledIconButton(
                    enabled = runtime.controls.playback && model.pendingCommand == null,
                    onClick = { onPlayback(if (runtime.playback.isPlaying) "pause" else "start", null) },
                ) {
                    if (model.pendingCommand?.startsWith("playback") == true) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(if (runtime.playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause")
                    }
                }
                IconButton(
                    enabled = runtime.controls.playback && model.pendingCommand == null,
                    onClick = { onPlayback("next", null) },
                ) { Icon(Icons.Rounded.SkipNext, "Nächstes Kapitel") }
                }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = { onVolume(volume.toInt()) },
                    enabled = runtime.controls.volume && model.pendingCommand == null,
                    valueRange = 0f..10f,
                    steps = 9,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(volume.toInt().toString(), style = MaterialTheme.typography.labelLarge)
            }

            model.commandError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            val playlist = model.metadata?.playlist.orEmpty()
            OutlinedButton(
                onClick = { playlistExpanded = !playlistExpanded },
                enabled = playlist.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null)
                Spacer(Modifier.width(8.dp))
                Text(if (playlist.isEmpty()) "Keine Playlistdaten" else "Playlist (${playlist.size})")
                Spacer(Modifier.weight(1f))
                Icon(if (playlistExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(playlistExpanded && playlist.isNotEmpty()) {
                Column {
                    playlist.forEach { track ->
                        val selected = runtime.playback.chapter == track.index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${track.index + 1}.", modifier = Modifier.width(28.dp))
                            Text(track.title, modifier = Modifier.weight(1f), maxLines = 2)
                            track.durationSeconds?.let { Text(formatDuration(it), style = MaterialTheme.typography.labelSmall) }
                            IconButton(
                                enabled = runtime.controls.playback,
                                onClick = { onPlayback("setPosition", track.index) },
                            ) { Icon(Icons.Rounded.PlayArrow, "Kapitel abspielen") }
                        }
                    }
                }
            }

                AnimatedVisibility(deviceExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text("Gerät", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Brightness6, null)
                            Slider(
                                value = brightness,
                                onValueChange = { brightness = it },
                                onValueChangeFinished = { onBrightness(brightness.toInt()) },
                                valueRange = 0f..100f,
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            )
                            Text("${brightness.toInt()} %")
                        }
                        OutlinedButton(onClick = onPing, enabled = runtime.controls.ping) {
                            Icon(Icons.Rounded.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Box anpingen")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    AssistChip(onClick = {}, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
}

@Composable
fun ConnectionBadge(status: LinkStatus, label: String) {
    val color = when (status) {
        LinkStatus.CONNECTED -> Color(0xFF28A862)
        LinkStatus.WARNING, LinkStatus.CONNECTING -> Color(0xFFF0A020)
        LinkStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ConnectionEmptyState(title: String, detail: String, action: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun currentTrackTitle(model: BoxUiModel): String {
    val chapter = model.box.runtime.playback.chapter ?: 0
    return model.metadata?.playlist?.getOrNull(chapter)?.title ?: "Kapitel ${chapter + 1}"
}

private fun relativeLastSeen(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "unbekannt"
    val seconds = (System.currentTimeMillis() / 1_000 - epochSeconds).coerceAtLeast(0)
    return when {
        seconds < 60 -> "vor ${seconds}s"
        seconds < 3_600 -> "vor ${seconds / 60} min"
        seconds < 86_400 -> "vor ${seconds / 3_600} h"
        else -> DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(epochSeconds))
    }
}

private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun isRemoteControlVisible(online: Boolean, lastConnection: Long): Boolean {
    if (online) return true
    if (lastConnection <= 0L) return false
    val offlineSeconds = (System.currentTimeMillis() / 1_000 - lastConnection).coerceAtLeast(0L)
    return offlineSeconds <= REMOTE_CONTROL_GRACE_SECONDS
}

private const val REMOTE_CONTROL_GRACE_SECONDS = 3 * 60L
