package de.teddycloud.teddyremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import coil.compose.AsyncImage
import de.teddycloud.teddyremote.model.BoxUiModel
import de.teddycloud.teddyremote.model.BoxVolume
import de.teddycloud.teddyremote.model.BedtimeRuntime
import de.teddycloud.teddyremote.model.LinkStatus
import de.teddycloud.teddyremote.model.WifiGateState
import de.teddycloud.teddyremote.model.userMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onOpenOverview: () -> Unit,
    onPlayback: (String, String, Int?) -> Unit,
    onRefreshPlaylist: (String) -> Unit,
    onVolume: (String, Int) -> Unit,
    onPing: (String) -> Unit,
    onBedtime: (String, Boolean, Int?) -> Unit,
    onSleep: (String) -> Unit,
    onBrightness: (String, Int) -> Unit,
    onBedtimeBrightness: (String, Int) -> Unit,
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
            },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        )

        when {
            state.profiles.activeProfile != null && state.connection.wifiGate != WifiGateState.AVAILABLE -> ConnectionEmptyState(
                title = if (state.connection.desiredConnected) "Verbindung pausiert" else "WLAN nicht verfügbar",
                detail = state.connection.wifiGate.userMessage,
                action = "Zur Übersicht",
                onAction = onOpenOverview,
            )
            !state.connection.desiredConnected -> ConnectionEmptyState(
                title = "Nicht verbunden",
                detail = "Wähle ein Profil und verbinde dich mit deiner TeddyCloud.",
                action = "Zur Übersicht",
                onAction = onOpenOverview,
            )
            !state.connection.isApiUsable -> ConnectionEmptyState(
                title = "TeddyCloud nicht erreichbar",
                detail = state.connection.message ?: "Verbindung wird hergestellt …",
                action = "Zur Übersicht",
                onAction = onOpenOverview,
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
                                onRefreshPlaylist = { onRefreshPlaylist(model.box.id) },
                                onVolume = { onVolume(model.box.id, it) },
                                onPing = { onPing(model.box.id) },
                                onBedtime = { enabled, duration -> onBedtime(model.box.id, enabled, duration) },
                                onSleep = { onSleep(model.box.id) },
                                onBrightness = { onBrightness(model.box.id, it) },
                                onBedtimeBrightness = { onBedtimeBrightness(model.box.id, it) },
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
    onRefreshPlaylist: () -> Unit,
    onVolume: (Int) -> Unit,
    onPing: () -> Unit,
    onBedtime: (Boolean, Int?) -> Unit,
    onSleep: () -> Unit,
    onBrightness: (Int) -> Unit,
    onBedtimeBrightness: (Int) -> Unit,
) {
    var playlistExpanded by remember(model.box.id) { mutableStateOf(false) }
    var deviceExpanded by remember(model.box.id) { mutableStateOf(false) }
    var bedtimeDialogVisible by remember(model.box.id) { mutableStateOf(false) }
    var sleepDialogVisible by remember(model.box.id) { mutableStateOf(false) }
    val runtime = model.box.runtime
    val confirmedVolume = model.desiredVolume ?: runtime.volume.level ?: BoxVolume.MIN_LEVEL
    var draggedVolume by remember(model.box.id) { mutableStateOf<Float?>(null) }
    val volume = draggedVolume ?: confirmedVolume.toFloat()
    var brightness by remember(model.box.id, model.ringBrightness) {
        mutableFloatStateOf((model.ringBrightness ?: 100).toFloat())
    }
    val remoteControlVisible = isRemoteControlVisible(runtime.online, runtime.lastConnection)
    val bedtimeRemaining = rememberBedtimeRemaining(runtime.bedtime)
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(),
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
                if (runtime.bedtime.isActive) {
                    StatusChip(
                        Icons.Rounded.Bedtime,
                        bedtimeRemaining?.let { "Bedtime ${formatCountdown(it)}" } ?: "Bedtime aktiv",
                    )
                }
            }

            if (remoteControlVisible) {
                if (runtime.controls.bedtime || runtime.controls.sleep || runtime.bedtime.isActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { bedtimeDialogVisible = true },
                            enabled = runtime.controls.bedtime && model.pendingCommand == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Bedtime, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (runtime.bedtime.isActive) "Bedtime" else "Bedtime starten")
                        }
                        OutlinedButton(
                            onClick = { sleepDialogVisible = true },
                            enabled = runtime.controls.sleep && model.pendingCommand == null,
                        ) {
                            Icon(Icons.Rounded.PowerSettingsNew, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Schlafen")
                        }
                    }
                }

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
                    onValueChange = { draggedVolume = it },
                    onValueChangeFinished = {
                        val requestedVolume = BoxVolume.clamp(volume.roundToInt())
                        draggedVolume = null
                        onVolume(requestedVolume)
                    },
                    enabled = runtime.controls.volume && model.pendingCommand == null,
                    valueRange = BoxVolume.MIN_LEVEL.toFloat()..BoxVolume.MAX_LEVEL.toFloat(),
                    steps = BoxVolume.SLIDER_STEPS,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(volume.roundToInt().toString(), style = MaterialTheme.typography.labelLarge)
            }

            model.commandError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            val playlist = model.metadata?.playlist.orEmpty()
            val playlistCanLoad = runtime.playback.ruid?.matches(RUID_PATTERN) == true
            OutlinedButton(
                onClick = {
                    if (playlist.isEmpty()) {
                        playlistExpanded = true
                        onRefreshPlaylist()
                    } else {
                        playlistExpanded = !playlistExpanded
                    }
                },
                enabled = playlist.isNotEmpty() || playlistCanLoad,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null)
                Spacer(Modifier.width(8.dp))
                Text(if (playlist.isEmpty()) "Kapitelinformationen laden" else "Playlist (${playlist.size})")
                Spacer(Modifier.weight(1f))
                Icon(if (playlistExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(playlistExpanded) {
                if (playlist.isEmpty()) {
                    Text(
                        "Noch keine Kapitelinformationen verfügbar. Tippe zum erneuten Laden noch einmal auf die Schaltfläche.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                } else {
                    Column {
                        playlist.forEach { track ->
                            val selected = runtime.playback.chapter == track.index
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${track.index + 1}.", modifier = Modifier.width(28.dp))
                                Text(track.title, modifier = Modifier.weight(1f), maxLines = 2)
                                track.durationSeconds?.let {
                                    Text(formatDuration(it), style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(
                                    enabled = runtime.controls.playback,
                                    onClick = { onPlayback("setPosition", track.index) },
                                ) { Icon(Icons.Rounded.PlayArrow, "Kapitel abspielen") }
                            }
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

    if (bedtimeDialogVisible) {
        BedtimeDialog(
            active = runtime.bedtime.isActive,
            remainingSeconds = bedtimeRemaining,
            initialMinutes = ((runtime.bedtime.defaultDuration ?: runtime.bedtime.duration ?: 1_800) / 60)
                .coerceIn(BEDTIME_MINUTES_MIN, BEDTIME_MINUTES_MAX),
            bedtimeBrightness = model.bedtimeRingBrightness ?: DEFAULT_BEDTIME_BRIGHTNESS,
            onDismiss = { bedtimeDialogVisible = false },
            onStart = { minutes ->
                bedtimeDialogVisible = false
                onBedtime(true, minutes * 60)
            },
            onStop = {
                bedtimeDialogVisible = false
                onBedtime(false, null)
            },
            onBrightness = onBedtimeBrightness,
        )
    }

    if (sleepDialogVisible) {
        SleepDialog(
            bedtimeActive = runtime.bedtime.isActive,
            onDismiss = { sleepDialogVisible = false },
            onConfirm = {
                sleepDialogVisible = false
                onSleep()
            },
        )
    }
}

@Composable
private fun BedtimeDialog(
    active: Boolean,
    remainingSeconds: Long?,
    initialMinutes: Int,
    bedtimeBrightness: Int,
    onDismiss: () -> Unit,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onBrightness: (Int) -> Unit,
) {
    var minutes by remember(initialMinutes) { mutableFloatStateOf(initialMinutes.toFloat()) }
    var brightness by remember(bedtimeBrightness) { mutableFloatStateOf(bedtimeBrightness.toFloat()) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(max = 760.dp),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DialogHeader(
                    icon = Icons.Rounded.Bedtime,
                    eyebrow = if (active) "BEDTIME AKTIV" else "GUTE NACHT",
                    title = if (active) "Bedtime anpassen" else "Bedtime starten",
                    onDismiss = onDismiss,
                )

                if (active) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("NOCH", style = MaterialTheme.typography.labelMedium)
                            Text(
                                remainingSeconds?.let(::formatCountdown) ?: "Aktiv",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Dauer", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                formatBedtimeMinutes(minutes.roundToInt()),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = minutes,
                            onValueChange = {
                                minutes = ((it / BEDTIME_MINUTES_STEP).roundToInt() * BEDTIME_MINUTES_STEP)
                                    .coerceIn(BEDTIME_MINUTES_MIN, BEDTIME_MINUTES_MAX)
                                    .toFloat()
                            },
                            valueRange = BEDTIME_MINUTES_MIN.toFloat()..BEDTIME_MINUTES_MAX.toFloat(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BEDTIME_PRESETS.forEach { preset ->
                                FilterChip(
                                    selected = minutes.roundToInt() == preset,
                                    onClick = { minutes = preset.toFloat() },
                                    label = { Text(formatBedtimeMinutes(preset)) },
                                )
                            }
                        }
                    }
                }

                if (active) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(42.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Brightness6, null, Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Ringhelligkeit", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${brightness.roundToInt()} % im Bedtime-Modus",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Slider(
                                value = brightness,
                                onValueChange = { brightness = it },
                                onValueChangeFinished = { onBrightness(brightness.roundToInt()) },
                                valueRange = 0f..100f,
                            )
                        }
                    }
                }

                Button(
                    onClick = { onStart(minutes.roundToInt()) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.Bedtime, null)
                    Spacer(Modifier.width(10.dp))
                    Text(if (active) "Mit neuer Dauer starten" else "Bedtime starten")
                }
                if (active) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Bedtime beenden")
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepDialog(
    bedtimeActive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                DialogHeader(
                    icon = Icons.Rounded.PowerSettingsNew,
                    eyebrow = "SCHLAFZUSTAND",
                    title = "Box schlafen legen?",
                    onDismiss = onDismiss,
                )
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (bedtimeActive) {
                                "Bedtime ist bereits aktiv. Der Schlafbefehl kann direkt gesendet werden."
                            } else {
                                "TeddyRemote aktiviert zuerst fünf Minuten Bedtime und wartet auf die Bestätigung der Box. Danach folgt automatisch der Schlafbefehl."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Der Ton wird kurz ausgeblendet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Jetzt schlafen legen")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Abbrechen")
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    eyebrow: String,
    title: String,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Schließen") }
    }
}

@Composable
private fun rememberBedtimeRemaining(bedtime: BedtimeRuntime): Long? {
    val remaining by produceState<Long?>(
        initialValue = bedtime.remainingSeconds(),
        bedtime.state,
        bedtime.duration,
        bedtime.until,
        bedtime.updatedAt,
    ) {
        while (bedtime.isActive) {
            value = bedtime.remainingSeconds()
            if (value == 0L) break
            delay(1_000)
        }
    }
    return remaining
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

private val RUID_PATTERN = Regex("^[0-9A-Fa-f]{16}$")

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

private fun formatCountdown(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}

private fun formatBedtimeMinutes(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) "$hours h" else "$hours h $remainder min"
}

private fun isRemoteControlVisible(online: Boolean, lastConnection: Long): Boolean {
    if (online) return true
    if (lastConnection <= 0L) return false
    val offlineSeconds = (System.currentTimeMillis() / 1_000 - lastConnection).coerceAtLeast(0L)
    return offlineSeconds <= REMOTE_CONTROL_GRACE_SECONDS
}

private const val REMOTE_CONTROL_GRACE_SECONDS = 3 * 60L
private const val BEDTIME_MINUTES_MIN = 5
private const val BEDTIME_MINUTES_MAX = 24 * 60
private const val BEDTIME_MINUTES_STEP = 5
private const val DEFAULT_BEDTIME_BRIGHTNESS = 75
private val BEDTIME_PRESETS = listOf(5, 15, 30, 60, 120)
