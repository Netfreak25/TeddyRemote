package de.teddycloud.teddyremote.repository

import android.content.Context
import de.teddycloud.teddyremote.data.ProfilesStore
import de.teddycloud.teddyremote.model.BatteryRuntime
import de.teddycloud.teddyremote.model.BedtimeRuntime
import de.teddycloud.teddyremote.model.BoxRuntime
import de.teddycloud.teddyremote.model.BoxUiModel
import de.teddycloud.teddyremote.model.BoxVolume
import de.teddycloud.teddyremote.model.CertificateCandidate
import de.teddycloud.teddyremote.model.CertificateTarget
import de.teddycloud.teddyremote.model.CommandResponse
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.ConnectionStatus
import de.teddycloud.teddyremote.model.HeadphonesRuntime
import de.teddycloud.teddyremote.model.LinkStatus
import de.teddycloud.teddyremote.model.MqttBoxEvent
import de.teddycloud.teddyremote.model.MqttSettingsImport
import de.teddycloud.teddyremote.model.PlaybackRuntime
import de.teddycloud.teddyremote.model.TonieMetadata
import de.teddycloud.teddyremote.model.TonieboxDto
import de.teddycloud.teddyremote.model.VolumeRuntime
import de.teddycloud.teddyremote.model.WifiGateState
import de.teddycloud.teddyremote.model.userMessage
import de.teddycloud.teddyremote.mqtt.CertificateConfirmationRequired
import de.teddycloud.teddyremote.mqtt.MqttConnection
import de.teddycloud.teddyremote.network.CertificateProbe
import de.teddycloud.teddyremote.network.TeddyCloudClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException

class TeddyRemoteRepository(
    context: Context,
    val profilesStore: ProfilesStore,
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
    private val certificateProbe: CertificateProbe = CertificateProbe(),
    private val backoffPolicy: BackoffPolicy = BackoffPolicy(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connection = MutableStateFlow(ConnectionStatus())
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()
    private val _boxes = MutableStateFlow<List<BoxUiModel>>(emptyList())
    val boxes: StateFlow<List<BoxUiModel>> = _boxes.asStateFlow()
    private val _foreground = MutableStateFlow(true)

    private var api: TeddyCloudClient? = null
    private var mqtt = MqttConnection(certificateProbe)
    private var connectionJob: Job? = null
    private var pollingJob: Job? = null
    private var mqttReconnectJob: Job? = null
    private var activeProfile: ConnectionProfile? = null
    private var lastWifiGate = WifiGateState.NO_WIFI
    private val connectionLifecycleMutex = Mutex()
    private val knownGenerations = mutableMapOf<String, Int>()
    private val metadataKeys = mutableMapOf<String, Pair<String, Long?>>()
    private val boxUpdateMutex = Mutex()
    private val imageCache = RemoteImageCache(context)
    private val volumeCommands = VolumeCommandCoordinator(
        scope = scope,
        send = { boxId, level ->
            val client = api ?: error("Keine API-Verbindung")
            val response = client.setVolume(boxId, level)
            check(response.ok) { response.error ?: response.message ?: "Lautstärkebefehl wurde abgelehnt" }
        },
        refreshConfirmed = { boxId ->
            refreshSnapshot(loadStaticData = false)
            _boxes.value.firstOrNull { it.box.id.equals(boxId, ignoreCase = true) }
                ?.box?.runtime?.volume?.level
        },
        onDesiredChanged = { boxId, level ->
            updateBox(boxId) { it.copy(desiredVolume = level, commandError = null) }
        },
        onSettled = { boxId, error ->
            updateBox(boxId) { it.copy(desiredVolume = null, commandError = error) }
        },
    )

    init {
        scope.launch {
            combine(networkMonitor.state, profilesStore.state) { wifi, profiles -> wifi to profiles }
                .collect { (wifi, profiles) ->
                    val profile = profiles.activeProfile?.normalized()
                    val gate = profile?.let { evaluateWifiGate(wifi) } ?: WifiGateState.NO_WIFI
                    handleWifiUpdate(profile, gate, profiles.connectionRequested)
                }
        }
    }

    suspend fun autoStartIfConfigured() {
        val state = profilesStore.state.first()
        if (state.activeProfile?.connectOnAppStart == true) connect()
    }

    fun setForeground(foreground: Boolean) {
        _foreground.value = foreground
    }

    suspend fun connect() {
        val profile = profilesStore.state.first().activeProfile?.normalized() ?: return
        networkMonitor.refresh()
        connectionLifecycleMutex.withLock { activeProfile = profile }
        profilesStore.setConnectionRequested(true)
        reconcileWifi(profile, networkMonitor.gate(), requested = true)
    }

    suspend fun disconnect() {
        profilesStore.setConnectionRequested(false)
        connectionLifecycleMutex.withLock {
            stopNetworkJobsLocked()
            activeProfile = null
            networkMonitor.releaseBinding()
            _connection.value = ConnectionStatus(
                desiredConnected = false,
                apiStatus = LinkStatus.DISCONNECTED,
                mqttStatus = LinkStatus.DISCONNECTED,
                wifiGate = _connection.value.wifiGate,
            )
        }
    }

    suspend fun switchProfile(profileId: String) {
        disconnect()
        profilesStore.activateProfile(profileId)
    }

    suspend fun confirmCertificate(candidate: CertificateCandidate) {
        val state = profilesStore.state.first()
        val profile = state.activeProfile ?: return
        val updated = when (candidate.target) {
            CertificateTarget.API -> profile.copy(apiCertificateFingerprint = candidate.fingerprintSha256)
            CertificateTarget.MQTT -> profile.copy(mqttCertificateFingerprint = candidate.fingerprintSha256)
        }
        profilesStore.saveProfile(updated, null)
        connect()
    }

    suspend fun rejectCertificate() {
        _connection.value = _connection.value.copy(
            certificateCandidate = null,
            message = "Zertifikat wurde nicht bestätigt",
        )
    }

    suspend fun testApi(profile: ConnectionProfile): Result<Unit> = runCatching {
        withWifiAccess(profile) {
            require(profile.validate().isEmpty()) { profile.validate().joinToString() }
            TeddyCloudClient.create(profile.normalized()).getBoxes()
        }
    }

    suspend fun importMqttSettings(profile: ConnectionProfile): Result<MqttSettingsImport> = runCatching {
        withWifiAccess(profile) {
            require(profile.validate().none { it.startsWith("TeddyCloud-URL") }) {
                "TeddyCloud-URL ist ungültig"
            }
            TeddyCloudClient.create(profile.normalized()).getMqttSettingsForRemote()
        }
    }

    suspend fun testMqtt(profile: ConnectionProfile, password: String?): Result<Unit> = runCatching {
        withWifiAccess(profile) {
            if (!profile.mqttEnabled) return@withWifiAccess
            val connection = MqttConnection(certificateProbe)
            try {
                connection.connect(profile.normalized(), password, {}, {})
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun inspectCertificate(profile: ConnectionProfile, target: CertificateTarget): CertificateCandidate {
        return withWifiAccess(profile) {
            val normalized = profile.normalized()
            when (target) {
                CertificateTarget.API -> {
                    val uri = URI(normalized.apiBaseUrl)
                    certificateProbe.inspect(target, uri.host, if (uri.port > 0) uri.port else 443)
                }
                CertificateTarget.MQTT -> certificateProbe.inspect(target, normalized.mqttHost, normalized.mqttPort)
            }
        }
    }

    suspend fun refresh() {
        try {
            requireActiveWifi()
            refreshSnapshot(loadStaticData = false, forceMetadata = true)
            val current = _connection.value
            _connection.value = current.copy(
                apiStatus = LinkStatus.CONNECTED,
                message = current.message.takeIf {
                    current.mqttStatus == LinkStatus.ERROR || current.mqttStatus == LinkStatus.WARNING
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _connection.value = _connection.value.copy(
                apiStatus = LinkStatus.WARNING,
                message = "Aktualisierung fehlgeschlagen: ${error.userMessage()}",
            )
        }
    }

    suspend fun refreshMetadata(boxId: String) {
        val client = api ?: return
        updateBox(boxId) { model ->
            model.copy(metadata = metadataFor(client, model.box, model.metadata, force = true))
        }
    }

    suspend fun playback(boxId: String, action: String, chapter: Int? = null) {
        issueCommand(boxId, "playback:$action") { client -> client.playback(boxId, action, chapter) }
    }

    suspend fun setVolume(boxId: String, level: Int) {
        val boundedLevel = BoxVolume.clamp(level)
        val currentLevel = _boxes.value
            .firstOrNull { it.box.id.equals(boxId, ignoreCase = true) }
            ?.let { it.desiredVolume ?: it.box.runtime.volume.level }
        if (currentLevel == boundedLevel) return
        volumeCommands.submit(boxId, boundedLevel)
    }

    suspend fun ping(boxId: String) {
        issueCommand(boxId, "ping") { client -> client.ping(boxId) }
    }

    suspend fun setBedtime(boxId: String, enabled: Boolean, durationSeconds: Int? = null) {
        issueCommand(boxId, if (enabled) "bedtime:start" else "bedtime:stop") { client ->
            if (enabled) client.setBedtime(boxId, requireNotNull(durationSeconds))
            else client.cancelBedtime(boxId)
        }
    }

    suspend fun sleep(boxId: String) {
        issueCommand(boxId, "sleep") { client ->
            val bedtimeActive = box(boxId)?.box?.runtime?.bedtime?.isActive == true
            if (!bedtimeActive) {
                client.setBedtime(boxId, SHUTDOWN_BEDTIME_SECONDS).requireAccepted()
                awaitBedtimeActive(boxId)
            }
            client.sleep(boxId)
        }
    }

    suspend fun setRingBrightness(boxId: String, brightness: Int) {
        issueCommand(boxId, "brightness") { client ->
            client.setRingBrightness(boxId, brightness)
            updateBox(boxId) { it.copy(ringBrightness = brightness.coerceIn(0, 100)) }
            null
        }
    }

    suspend fun setBedtimeRingBrightness(boxId: String, brightness: Int) {
        issueCommand(boxId, "bedtime:brightness") { client ->
            client.setBedtimeRingBrightness(boxId, brightness)
            updateBox(boxId) { it.copy(bedtimeRingBrightness = brightness.coerceIn(0, 100)) }
            null
        }
    }

    private suspend fun issueCommand(
        boxId: String,
        command: String,
        operation: suspend (TeddyCloudClient) -> Any?,
    ) {
        val client = api ?: return
        updateBox(boxId) { it.copy(pendingCommand = command, commandError = null) }
        runCatching { operation(client).also { (it as? CommandResponse)?.requireAccepted() } }
            .onFailure { error ->
                updateBox(boxId) { it.copy(pendingCommand = null, commandError = error.userMessage()) }
                return
            }
        delay(COMMAND_REFRESH_DELAY_MS)
        runCatching { refreshSnapshot(loadStaticData = false) }
            .onFailure { error -> updateBox(boxId) { it.copy(commandError = error.userMessage()) } }
        updateBox(boxId) { it.copy(pendingCommand = null) }
    }

    private suspend fun awaitBedtimeActive(boxId: String) {
        withTimeout(BEDTIME_CONFIRM_TIMEOUT_MS) {
            while (box(boxId)?.box?.runtime?.bedtime?.isActive != true) {
                delay(BEDTIME_CONFIRM_POLL_MS)
                refreshSnapshot(loadStaticData = false)
            }
        }
    }

    private fun box(boxId: String): BoxUiModel? =
        _boxes.value.firstOrNull { it.box.id.equals(boxId, ignoreCase = true) }

    private suspend fun handleWifiUpdate(
        profile: ConnectionProfile?,
        gate: WifiGateState,
        requested: Boolean,
    ) {
        val returnedToApprovedWifi = shouldResumeAfterWifiTransition(lastWifiGate, gate, requested)
        lastWifiGate = gate
        if (profile == null) {
            _connection.value = _connection.value.copy(wifiGate = gate)
            return
        }
        val currentProfile = activeProfile
        if (currentProfile == null || currentProfile.id != profile.id) {
            if (!_connection.value.desiredConnected) {
                _connection.value = _connection.value.copy(
                    profileName = profile.name,
                    wifiGate = gate,
                    message = gate.takeUnless { it == WifiGateState.AVAILABLE }?.userMessage,
                )
            }
            return
        }
        activeProfile = profile
        reconcileWifi(profile, gate, requested, allowStart = returnedToApprovedWifi)
    }

    private suspend fun reconcileWifi(
        profile: ConnectionProfile,
        gate: WifiGateState,
        requested: Boolean,
        allowStart: Boolean = true,
    ) {
        var clearConnectionRequest = false
        connectionLifecycleMutex.withLock {
            if (gate != WifiGateState.AVAILABLE) {
                val keepRequested = keepConnectionRequestWhilePaused(requested, profile.reconnectOnWifiReconnect)
                pauseForWifiLocked(profile, gate, keepRequested)
                clearConnectionRequest = requested && !keepRequested
                return@withLock
            }
            if (!requested) {
                _connection.value = _connection.value.copy(
                    desiredConnected = false,
                    profileName = profile.name,
                    wifiGate = gate,
                    message = null,
                )
                return@withLock
            }
            if (!networkMonitor.bindToActiveWifi()) {
                pauseForWifiLocked(profile, WifiGateState.NO_WIFI, profile.reconnectOnWifiReconnect)
                clearConnectionRequest = !profile.reconnectOnWifiReconnect
                return@withLock
            }
            _connection.value = _connection.value.copy(
                desiredConnected = true,
                profileName = profile.name,
                wifiGate = WifiGateState.AVAILABLE,
            )
            if (allowStart && !_connection.value.isApiUsable && connectionJob?.isActive != true) {
                beginConnectionLocked(profile)
            }
        }
        if (clearConnectionRequest) profilesStore.setConnectionRequested(false)
    }

    private suspend fun pauseForWifiLocked(
        profile: ConnectionProfile,
        gate: WifiGateState,
        keepRequested: Boolean,
    ) {
        stopNetworkJobsLocked()
        networkMonitor.releaseBinding()
        _boxes.value = _boxes.value.map { model ->
            model.copy(
                box = model.box.copy(runtime = model.box.runtime.copy(online = false)),
                desiredVolume = null,
                pendingCommand = null,
            )
        }
        _connection.value = ConnectionStatus(
            desiredConnected = keepRequested,
            apiStatus = LinkStatus.DISCONNECTED,
            mqttStatus = if (profile.mqttEnabled) LinkStatus.DISCONNECTED else LinkStatus.NOT_CHECKED,
            profileName = profile.name,
            message = if (keepRequested) "Pausiert · ${gate.userMessage}" else gate.userMessage,
            wifiGate = gate,
        )
    }

    private suspend fun stopNetworkJobsLocked() {
        volumeCommands.cancelAll()
        connectionJob?.cancelAndJoin()
        connectionJob = null
        pollingJob?.cancelAndJoin()
        pollingJob = null
        mqttReconnectJob?.cancelAndJoin()
        mqttReconnectJob = null
        mqtt.disconnect()
        api = null
    }

    private fun requireWifiAccess() {
        val gate = networkMonitor.gate()
        if (gate != WifiGateState.AVAILABLE || !networkMonitor.bindToActiveWifi()) {
            throw WifiAccessException(gate.takeUnless { it == WifiGateState.AVAILABLE } ?: WifiGateState.NO_WIFI)
        }
    }

    private fun requireActiveWifi() {
        checkNotNull(activeProfile) { "Keine aktive TeddyCloud-Verbindung" }
        requireWifiAccess()
    }

    private suspend fun <T> withWifiAccess(profile: ConnectionProfile, operation: suspend () -> T): T {
        val normalized = profile.normalized()
        networkMonitor.refresh()
        requireWifiAccess()
        return try {
            operation()
        } finally {
            if (!_connection.value.desiredConnected || activeProfile?.id != normalized.id) {
                networkMonitor.releaseBinding()
            }
        }
    }

    private suspend fun beginConnectionLocked(profile: ConnectionProfile) {
        stopNetworkJobsLocked()
        knownGenerations.clear()
        metadataKeys.clear()
        activeProfile = profile.normalized()
        connectionJob = scope.launch { connectionLoop(profile.normalized()) }
    }

    private suspend fun connectionLoop(profile: ConnectionProfile) {
        var attempt = 0
        while (scope.isActive && profilesStore.state.first().connectionRequested) {
            requireWifiAccess()
            attempt++
            _connection.value = ConnectionStatus(
                desiredConnected = true,
                apiStatus = LinkStatus.CONNECTING,
                mqttStatus = if (profile.mqttEnabled) LinkStatus.CONNECTING else LinkStatus.NOT_CHECKED,
                profileName = profile.name,
                retryAttempt = attempt,
                wifiGate = WifiGateState.AVAILABLE,
            )
            try {
                connectOnce(profile)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val candidate = apiCertificateCandidate(profile, error)
                if (candidate != null) {
                    _connection.value = _connection.value.copy(
                        apiStatus = LinkStatus.WARNING,
                        certificateCandidate = candidate,
                        message = "API-Zertifikat muss bestätigt werden",
                    )
                    return
                }
                _connection.value = _connection.value.copy(
                    apiStatus = LinkStatus.ERROR,
                    message = error.userMessage(),
                    retryAttempt = attempt,
                )
                if (!profile.autoReconnect || (profile.maxRetries > 0 && attempt >= profile.maxRetries)) return
                delay(backoffPolicy.delayMillis(attempt, profile.initialRetrySeconds, profile.maxRetrySeconds))
            }
        }
    }

    private suspend fun connectOnce(profile: ConnectionProfile) {
        requireWifiAccess()
        val password = profilesStore.mqttPassword(profile.id)
        val bufferedEvents = Collections.synchronizedList(mutableListOf<MqttBoxEvent>())
        val snapshotReady = AtomicBoolean(false)
        var mqttFailure: Throwable? = null

        if (profile.mqttEnabled) {
            try {
                mqtt.connect(
                    profile,
                    password,
                    onEvent = { event ->
                        if (snapshotReady.get()) launchMqttCallback("MQTT-Ereignis") { applyMqttEvent(event) }
                        else bufferedEvents += event
                    },
                    onDisconnected = { error ->
                        launchMqttCallback("MQTT-Verbindungsabbruch") {
                            handleMqttDisconnect(profile, error)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mqttFailure = error
            }
        }

        requireWifiAccess()
        api = TeddyCloudClient.create(profile)
        refreshSnapshot(loadStaticData = true)
        snapshotReady.set(true)
        synchronized(bufferedEvents) { bufferedEvents.toList() }.forEach { applyMqttEvent(it) }

        val mqttStatus = when {
            !profile.mqttEnabled -> LinkStatus.NOT_CHECKED
            mqttFailure == null -> LinkStatus.CONNECTED
            else -> LinkStatus.ERROR
        }
        val candidate = (mqttFailure as? CertificateConfirmationRequired)?.candidate
        _connection.value = ConnectionStatus(
            desiredConnected = true,
            apiStatus = LinkStatus.CONNECTED,
            mqttStatus = if (candidate != null) LinkStatus.WARNING else mqttStatus,
            profileName = profile.name,
            message = mqttFailure?.userMessage(),
            certificateCandidate = candidate,
            wifiGate = WifiGateState.AVAILABLE,
        )
        startPolling()
        if (mqttFailure != null && candidate == null && profile.autoReconnect) {
            startMqttReconnect(profile, password)
        }
    }

    private suspend fun refreshSnapshot(
        loadStaticData: Boolean,
        forceMetadata: Boolean = false,
    ) {
        val client = api ?: return
        val snapshot = client.getBoxes()
        val tb2Boxes = coroutineScope {
            snapshot.boxes.map { box ->
                async {
                    val generation = knownGenerations[box.id] ?: client.getBoxGeneration(box.id).also {
                        knownGenerations[box.id] = it
                    }
                    if (generation == TB2_GENERATION) box else null
                }
            }.awaitAll().filterNotNull()
        }
        val previous = _boxes.value.associateBy { it.box.id.uppercase() }
        val catalog = if (loadStaticData) client.getTonieboxCatalog().associateBy { it.id.lowercase() } else emptyMap()
        val models = coroutineScope {
            tb2Boxes.map { box ->
                async {
                    val old = previous[box.id.uppercase()]
                    val metadata = metadataFor(client, box, old?.metadata, forceMetadata)
                    val brightness = old?.ringBrightness ?: runCatching { client.getRingBrightness(box.id) }.getOrNull()
                    val bedtimeBrightness = old?.bedtimeRingBrightness
                        ?: runCatching { client.getBedtimeRingBrightness(box.id) }.getOrNull()
                    val catalogImage = old?.boxImageUrl
                        ?: catalog[box.boxModel.lowercase()]?.imageUrl?.let(client::resolveUrl)
                    BoxUiModel(
                        box = box,
                        metadata = metadata,
                        boxImageUrl = imageCache.materialize(catalogImage, client),
                        ringBrightness = brightness,
                        bedtimeRingBrightness = bedtimeBrightness,
                        desiredVolume = old?.desiredVolume,
                        pendingCommand = old?.pendingCommand,
                        commandError = old?.commandError,
                    )
                }
            }.awaitAll()
        }
        _boxes.value = models.sortedBy { it.box.boxName.ifBlank { it.box.commonName } }
    }

    private suspend fun metadataFor(
        client: TeddyCloudClient,
        box: TonieboxDto,
        existing: TonieMetadata?,
        force: Boolean = false,
    ): TonieMetadata? {
        val ruid = box.runtime.playback.ruid?.takeIf { it.matches(Regex("^[0-9A-Fa-f]{16}$")) } ?: return null
        val key = ruid.uppercase() to box.runtime.playback.contentVersion
        if (!force && metadataKeys[box.id.uppercase()] == key && existing != null) return existing
        return runCatching {
            val metadata = client.getTonieMetadata(box.id, ruid, key.second)
            metadata.copy(pictureUrl = imageCache.materialize(metadata.pictureUrl, client))
        }
            .onSuccess { metadataKeys[box.id.uppercase()] = key }
            .getOrElse {
                existing?.takeIf { it.ruid.equals(key.first, ignoreCase = true) } ?: TonieMetadata(
                    ruid = key.first,
                    title = box.runtime.playback.tonie ?: "Tonie",
                    playlist = box.runtime.playback.chapter?.let { chapter ->
                        listOf(de.teddycloud.teddyremote.model.PlaylistTrack(chapter, "Kapitel ${chapter + 1}"))
                    } ?: emptyList(),
                )
            }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val mqttConnected = _connection.value.mqttStatus == LinkStatus.CONNECTED
                val playing = _boxes.value.any { it.box.runtime.playback.isPlaying }
                val interval = when {
                    mqttConnected -> MQTT_RECONCILIATION_MS
                    _foreground.value -> FOREGROUND_POLL_MS
                    playing -> BACKGROUND_PLAYING_POLL_MS
                    else -> BACKGROUND_IDLE_POLL_MS
                }
                delay(interval)
                try {
                    requireActiveWifi()
                    refreshSnapshot(loadStaticData = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                        _connection.value = _connection.value.copy(
                            apiStatus = LinkStatus.ERROR,
                            message = error.userMessage(),
                        )
                }
            }
        }
    }

    private suspend fun handleMqttDisconnect(profile: ConnectionProfile, error: Throwable?) {
        if (!_connection.value.desiredConnected || activeProfile?.id != profile.id) return
        if (networkMonitor.gate() != WifiGateState.AVAILABLE) return
        _connection.value = _connection.value.copy(
            mqttStatus = LinkStatus.ERROR,
            message = error?.userMessage() ?: "MQTT-Verbindung getrennt",
        )
        if (profile.autoReconnect) startMqttReconnect(profile, profilesStore.mqttPassword(profile.id))
    }

    private fun startMqttReconnect(profile: ConnectionProfile, password: String?) {
        if (activeProfile?.id != profile.id) return
        if (mqttReconnectJob?.isActive == true) return
        mqttReconnectJob = scope.launch {
            var attempt = 0
            while (isActive && profilesStore.state.first().connectionRequested) {
                requireWifiAccess()
                attempt++
                try {
                    mqtt.connect(
                        profile,
                        password,
                        onEvent = { event ->
                            launchMqttCallback("MQTT-Ereignis") { applyMqttEvent(event) }
                        },
                        onDisconnected = { error ->
                            launchMqttCallback("MQTT-Verbindungsabbruch") {
                                handleMqttDisconnect(profile, error)
                            }
                        },
                    )
                    _connection.value = _connection.value.copy(mqttStatus = LinkStatus.CONNECTED, message = null)
                    refreshSnapshot(loadStaticData = false)
                    return@launch
                } catch (confirmation: CertificateConfirmationRequired) {
                    _connection.value = _connection.value.copy(
                        mqttStatus = LinkStatus.WARNING,
                        certificateCandidate = confirmation.candidate,
                        message = confirmation.userMessage(),
                    )
                    return@launch
                } catch (error: Throwable) {
                    _connection.value = _connection.value.copy(
                        mqttStatus = LinkStatus.ERROR,
                        message = error.userMessage(),
                        retryAttempt = attempt,
                    )
                    if (profile.maxRetries > 0 && attempt >= profile.maxRetries) return@launch
                    delay(backoffPolicy.delayMillis(attempt, profile.initialRetrySeconds, profile.maxRetrySeconds))
                }
            }
        }
    }

    private fun launchMqttCallback(label: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _connection.value = _connection.value.copy(
                    mqttStatus = LinkStatus.WARNING,
                    message = "$label fehlgeschlagen: ${error.userMessage()}",
                )
            }
        }
    }

    private suspend fun applyMqttEvent(event: MqttBoxEvent) {
        val valueEvent = event as? MqttBoxEvent.Value ?: return
        var metadataChanged = false
        var forceMetadataRefresh = false
        var confirmedVolume: Int? = null
        updateBox(valueEvent.boxId) { model ->
            val old = model.box
            val now = event.timestampMillis / 1_000
            val runtime = BoxStateReducer.reduce(old.runtime, valueEvent.field, valueEvent.value, now)
            metadataChanged = old.runtime.playback.ruid != runtime.playback.ruid ||
                old.runtime.playback.contentVersion != runtime.playback.contentVersion
            forceMetadataRefresh = valueEvent.field == "PlaybackChapter" &&
                model.metadata?.playlist.isNullOrEmpty()
            if (valueEvent.field == "VolumeLevel") {
                confirmedVolume = valueEvent.value.toIntOrNull()?.takeIf(BoxVolume::isValid)
            }
            model.copy(box = old.copy(runtime = runtime), commandError = null)
        }
        confirmedVolume?.let { volumeCommands.confirm(valueEvent.boxId, it) }
        if (metadataChanged || forceMetadataRefresh) {
            val client = api ?: return
            updateBox(valueEvent.boxId) { model ->
                model.copy(
                    metadata = metadataFor(
                        client,
                        model.box,
                        model.metadata,
                        force = forceMetadataRefresh,
                    ),
                )
            }
        }
    }

    private suspend fun updateBox(boxId: String, transform: suspend (BoxUiModel) -> BoxUiModel) {
        boxUpdateMutex.withLock {
            val index = _boxes.value.indexOfFirst { it.box.id.equals(boxId, ignoreCase = true) }
            if (index < 0) return
            val current = _boxes.value.toMutableList()
            current[index] = transform(current[index])
            _boxes.value = current
        }
    }

    private suspend fun apiCertificateCandidate(profile: ConnectionProfile, error: Throwable): CertificateCandidate? {
        if (!profile.apiBaseUrl.startsWith("https://", ignoreCase = true)) return null
        if (error.causeChain().none {
                it is SSLHandshakeException ||
                    it is javax.net.ssl.SSLPeerUnverifiedException ||
                    it is java.security.cert.CertificateException
            }
        ) return null
        val uri = URI(profile.apiBaseUrl)
        return runCatching {
            certificateProbe.inspect(
                CertificateTarget.API,
                uri.host,
                if (uri.port > 0) uri.port else 443,
            )
        }.getOrNull()
    }

    private fun Throwable.userMessage(): String = causeChain()
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .lastOrNull()
        ?: this::class.simpleName
        ?: "Unbekannter Fehler"

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private companion object {
        const val TB2_GENERATION = 2
        const val COMMAND_REFRESH_DELAY_MS = 500L
        const val SHUTDOWN_BEDTIME_SECONDS = 300
        const val BEDTIME_CONFIRM_TIMEOUT_MS = 8_000L
        const val BEDTIME_CONFIRM_POLL_MS = 400L
        const val MQTT_RECONCILIATION_MS = 60_000L
        const val FOREGROUND_POLL_MS = 2_000L
        const val BACKGROUND_PLAYING_POLL_MS = 5_000L
        const val BACKGROUND_IDLE_POLL_MS = 15_000L
    }
}

private class WifiAccessException(gate: WifiGateState) : IllegalStateException(gate.userMessage)

private fun CommandResponse.requireAccepted() {
    check(ok) { error ?: message ?: "TeddyCloud hat den Befehl abgelehnt" }
}
