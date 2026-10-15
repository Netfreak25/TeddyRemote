package de.teddycloud.teddyremote.repository

import android.content.Context
import android.util.Log
import de.teddycloud.teddyremote.data.PlaybackHistoryStore
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
import de.teddycloud.teddyremote.model.MetadataKey
import de.teddycloud.teddyremote.model.PlaybackRuntime
import de.teddycloud.teddyremote.model.ProfilesState
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.coroutineContext

class TeddyRemoteRepository(
    context: Context,
    val profilesStore: ProfilesStore,
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
    private val certificateProbe: CertificateProbe = CertificateProbe(),
    private val backoffPolicy: BackoffPolicy = BackoffPolicy(),
    private val playbackHistoryStore: PlaybackHistoryStore = PlaybackHistoryStore(context),
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
    private var playbackHistoryObserveJob: Job? = null
    private var activeProfile: ConnectionProfile? = null
    private var lastWifiGate = WifiGateState.NO_WIFI
    private val connectionLifecycleMutex = Mutex()
    private val connectionIntent = ConnectionIntent()
    private val knownGenerations = ConcurrentHashMap<String, Int>()
    private val boxUpdateMutex = Mutex()
    private val imageCache = RemoteImageCache(context)
    private val playbackResume = PlaybackResumeCoordinator(
        scope = scope,
        storage = playbackHistoryStore,
        onError = { error -> Log.w(LOG_TAG, "Playback history update failed", error) },
    )
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
            playbackResume.offers.collect { offers ->
                boxUpdateMutex.withLock {
                    _boxes.value = _boxes.value.map { model ->
                        model.copy(resumeOffer = offers[model.box.id.uppercase()])
                    }
                }
            }
        }
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
        connectionLifecycleMutex.withLock {
            coroutineContext.ensureActive()
            // A deliberate reconnect replaces even an attempt currently waiting in retry backoff.
            stopNetworkJobsLocked()
            connectionIntent.request()
            if (activeProfile?.id != profile.id) _boxes.value = emptyList()
            activeProfile = profile
            _connection.value = _connection.value.copy(apiStatus = LinkStatus.DISCONNECTED)
            profilesStore.setConnectionRequested(true)
        }
        reconcileWifi(profile, networkMonitor.gate(), requested = true)
    }

    suspend fun disconnect() {
        connectionIntent.stop()
        _connection.value = _connection.value.copy(desiredConnected = false)
        connectionLifecycleMutex.withLock {
            connectionIntent.stop()
            profilesStore.setConnectionRequested(false)
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
            require(profile.validateApi().isEmpty()) { profile.validateApi().joinToString() }
            TeddyCloudClient.create(profile.normalized()).getBoxes()
        }
    }

    suspend fun importMqttSettings(profile: ConnectionProfile): Result<MqttSettingsImport> = runCatching {
        withWifiAccess(profile) {
            require(profile.validateApi().isEmpty()) {
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
        val token = connectionIntent.token()
        try {
            requireActiveWifi()
            refreshSnapshot(loadStaticData = false, forceMetadata = true)
            updateConnection(token) { current ->
                current.copy(
                    apiStatus = LinkStatus.CONNECTED,
                    message = current.message.takeIf {
                        current.mqttStatus == LinkStatus.ERROR || current.mqttStatus == LinkStatus.WARNING
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateConnection(token) {
                it.copy(apiStatus = LinkStatus.WARNING, message = "Aktualisierung fehlgeschlagen: ${error.userMessage()}")
            }
        }
    }

    suspend fun refreshMetadata(boxId: String) {
        val client = api ?: return
        val token = connectionIntent.token()
        val requestedBox = box(boxId) ?: return
        val key = MetadataKey.forBox(client.profileId, requestedBox.box) ?: return
        val metadata = metadataFor(client, requestedBox.box, requestedBox.metadata, force = true)
        updateBox(boxId, token) { model ->
            if (connectionIntent.isCurrent(token) && api === client &&
                key == MetadataKey.forBox(client.profileId, model.box)
            ) model.copy(metadata = metadata) else model
        }
    }

    suspend fun playback(boxId: String, action: String) {
        issueCommand(boxId, "playback:$action") { client -> client.playback(boxId, action) }
    }

    suspend fun seek(boxId: String, chapter: Int, positionMs: Long) {
        issueCommand(boxId, "playback:seek") { client -> client.seek(boxId, chapter, positionMs) }
    }

    suspend fun resumePlayback(boxId: String) {
        val target = playbackResume.beginResume(boxId) ?: return
        val error = issueCommand(boxId, "playback:seek") { client ->
            client.seek(boxId, target.chapter, target.positionMs)
        }
        if (error != null) playbackResume.resumeFailed(boxId, error)
    }

    suspend fun declineResume(boxId: String) {
        playbackResume.decline(boxId)
    }

    suspend fun setResumeOfferTimeoutMinutes(timeoutMinutes: Int) {
        profilesStore.setResumeOfferTimeoutMinutes(timeoutMinutes)
        observePlaybackHistory()
    }

    suspend fun setTonieAutoResume(ruid: String, enabled: Boolean) {
        playbackHistoryStore.setAutoResumeEnabled(ruid, enabled)
        observePlaybackHistory()
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
    ): String? {
        val client = api ?: return "Keine API-Verbindung"
        val token = connectionIntent.token()
        if (!connectionIntent.isCurrent(token)) return "Keine API-Verbindung"
        updateBox(boxId) { it.copy(pendingCommand = command, commandError = null) }
        val failure = runCatching { operation(client).also { (it as? CommandResponse)?.requireAccepted() } }
            .exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (!connectionIntent.isCurrent(token) || api !== client) return "Verbindung beendet"
        if (failure != null) {
            val message = failure.userMessage()
            updateBox(boxId) { it.copy(pendingCommand = null, commandError = message) }
            return message
        }
        delay(COMMAND_REFRESH_DELAY_MS)
        if (!connectionIntent.isCurrent(token)) return "Verbindung beendet"
        runCatching { refreshSnapshot(loadStaticData = false) }
            .onFailure { error -> updateBox(boxId) { it.copy(commandError = error.userMessage()) } }
        updateBox(boxId) { it.copy(pendingCommand = null) }
        return null
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
            if (!connectionIntent.wanted) return@withLock
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
        if (!keepRequested) connectionIntent.stop()
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
            desiredConnected = keepRequested && connectionIntent.wanted,
            apiStatus = LinkStatus.DISCONNECTED,
            mqttStatus = if (profile.mqttEnabled) LinkStatus.DISCONNECTED else LinkStatus.NOT_CHECKED,
            profileName = profile.name,
            message = if (keepRequested) "Pausiert · ${gate.userMessage}" else gate.userMessage,
            wifiGate = gate,
        )
    }

    private suspend fun stopNetworkJobsLocked() {
        connectionIntent.invalidate()
        api?.cancelRequests()
        volumeCommands.cancelAll()
        playbackHistoryObserveJob?.cancelAndJoin()
        playbackHistoryObserveJob = null
        playbackResume.reset()
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
        activeProfile = profile.normalized()
        val token = connectionIntent.token()
        connectionJob = scope.launch { connectionLoop(profile.normalized(), token) }
    }

    private suspend fun connectionLoop(profile: ConnectionProfile, token: Long) {
        var attempt = 0
        while (coroutineContext.isActive && connectionIntent.isCurrent(token)) {
            attempt++
            updateConnection(token) {
                ConnectionStatus(
                    desiredConnected = true,
                    apiStatus = LinkStatus.CONNECTING,
                    mqttStatus = if (profile.mqttEnabled) LinkStatus.CONNECTING else LinkStatus.NOT_CHECKED,
                    profileName = profile.name,
                    retryAttempt = attempt,
                    wifiGate = WifiGateState.AVAILABLE,
                )
            }
            try {
                requireWifiAccess()
                connectOnce(profile, token)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!connectionIntent.isCurrent(token)) return
                val candidate = apiCertificateCandidate(profile, error)
                updateConnection(token) {
                    it.copy(
                        apiStatus = if (candidate != null) LinkStatus.WARNING else LinkStatus.ERROR,
                        certificateCandidate = candidate,
                        message = if (candidate != null) "API-Zertifikat muss bestätigt werden" else error.userMessage(),
                        retryAttempt = attempt,
                    )
                }
                if (candidate != null || !profile.autoReconnect || (profile.maxRetries > 0 && attempt >= profile.maxRetries)) return
                delay(backoffPolicy.delayMillis(attempt, profile.initialRetrySeconds, profile.maxRetrySeconds))
            }
        }
    }

    private suspend fun connectOnce(profile: ConnectionProfile, token: Long) {
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
                        if (snapshotReady.get()) launchMqttCallback("MQTT-Ereignis", token) { applyMqttEvent(event, token) }
                        else bufferedEvents += event
                    },
                    onDisconnected = { error ->
                        launchMqttCallback("MQTT-Verbindungsabbruch", token) {
                            handleMqttDisconnect(profile, error, token)
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
        coroutineContext.ensureActive()
        if (!connectionIntent.isCurrent(token)) return
        api = TeddyCloudClient.create(profile)
        refreshSnapshot(loadStaticData = true)
        snapshotReady.set(true)
        synchronized(bufferedEvents) { bufferedEvents.toList() }.forEach { applyMqttEvent(it, token) }

        val mqttStatus = when {
            !profile.mqttEnabled -> LinkStatus.NOT_CHECKED
            mqttFailure == null -> LinkStatus.CONNECTED
            else -> LinkStatus.ERROR
        }
        val candidate = (mqttFailure as? CertificateConfirmationRequired)?.candidate
        updateConnection(token) {
            ConnectionStatus(
                desiredConnected = true,
                apiStatus = LinkStatus.CONNECTED,
                mqttStatus = if (candidate != null) LinkStatus.WARNING else mqttStatus,
                profileName = profile.name,
                message = mqttFailure?.userMessage(),
                certificateCandidate = candidate,
                wifiGate = WifiGateState.AVAILABLE,
            )
        }
        if (!connectionIntent.isCurrent(token)) return
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
        val token = connectionIntent.token()
        val baseline = _boxes.value.associateBy { it.box.id.uppercase() }
        val snapshot = client.getBoxes()
        if (!connectionIntent.isCurrent(token) || api !== client) return
        val tb2Boxes = coroutineScope {
            snapshot.boxes.map { box ->
                async {
                    if (!connectionIntent.isCurrent(token)) return@async null
                    val generation = knownGenerations[box.id] ?: client.getBoxGeneration(box.id).also {
                        connectionIntent.apply(token) { knownGenerations[box.id] = it }
                    }
                    if (generation == TB2_GENERATION) box else null
                }
            }.awaitAll().filterNotNull()
        }
        val previous = _boxes.value.associateBy { it.box.id.uppercase() }
        if (!connectionIntent.isCurrent(token) || api !== client) return
        val catalog = if (loadStaticData) client.getTonieboxCatalog().associateBy { it.id.lowercase() } else emptyMap()
        val models = coroutineScope {
            tb2Boxes.map { box ->
                async {
                    if (!connectionIntent.isCurrent(token)) throw CancellationException("Connection replaced")
                    val old = previous[box.id.uppercase()]
                    val metadata = metadataFor(client, box, old?.metadata, forceMetadata)
                    if (!connectionIntent.isCurrent(token)) throw CancellationException("Connection replaced")
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
                        resumeOffer = playbackResume.offers.value[box.id.uppercase()],
                    )
                }
            }.awaitAll()
        }
        boxUpdateMutex.withLock {
            connectionIntent.apply(token) {
                if (api === client) {
                    val current = _boxes.value.associateBy { it.box.id.uppercase() }
                    _boxes.value = models.map { model ->
                        mergeBoxSnapshot(
                            client.profileId, model,
                            baseline[model.box.id.uppercase()], current[model.box.id.uppercase()],
                        )
                    }
                }
            }
        }
        if (connectionIntent.isCurrent(token)) observePlaybackHistory()
    }

    private suspend fun metadataFor(
        client: TeddyCloudClient,
        box: TonieboxDto,
        existing: TonieMetadata?,
        force: Boolean = false,
    ): TonieMetadata? {
        val key = MetadataKey.forBox(client.profileId, box) ?: return null
        if (!force && existing?.key == key) return existing
        return try {
            val metadata = client.getTonieMetadata(box.id, key.ruid, key.contentVersion)
            metadata.copy(pictureUrl = imageCache.materialize(metadata.pictureUrl, client))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Chapter metadata could not be loaded", error)
            existing?.takeIf { it.key == key } ?: TonieMetadata(
                ruid = key.ruid,
                title = box.runtime.playback.tonie ?: "Tonie",
                key = key,
                playlist = box.runtime.playback.chapter?.let { chapter ->
                    listOf(de.teddycloud.teddyremote.model.PlaylistTrack(chapter, "Kapitel ${chapter + 1}"))
                } ?: emptyList(),
            )
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        val token = connectionIntent.token()
        pollingJob = scope.launch {
            while (isActive && connectionIntent.isCurrent(token)) {
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
                    updateConnection(token) { it.copy(apiStatus = LinkStatus.ERROR, message = error.userMessage()) }
                }
            }
        }
    }

    private suspend fun handleMqttDisconnect(profile: ConnectionProfile, error: Throwable?, token: Long) {
        if (!connectionIntent.isCurrent(token) || activeProfile?.id != profile.id) return
        if (networkMonitor.gate() != WifiGateState.AVAILABLE) return
        updateConnection(token) {
            it.copy(mqttStatus = LinkStatus.ERROR, message = error?.userMessage() ?: "MQTT-Verbindung getrennt")
        }
        if (profile.autoReconnect) {
            val password = profilesStore.mqttPassword(profile.id)
            if (connectionIntent.isCurrent(token)) startMqttReconnect(profile, password)
        }
    }

    private fun startMqttReconnect(profile: ConnectionProfile, password: String?) {
        if (activeProfile?.id != profile.id || mqttReconnectJob?.isActive == true) return
        val token = connectionIntent.token()
        mqttReconnectJob = scope.launch {
            var attempt = 0
            while (isActive && connectionIntent.isCurrent(token)) {
                attempt++
                try {
                    requireWifiAccess()
                    mqtt.connect(
                        profile, password,
                        onEvent = { event ->
                            launchMqttCallback("MQTT-Ereignis", token) { applyMqttEvent(event, token) }
                        },
                        onDisconnected = { error ->
                            launchMqttCallback("MQTT-Verbindungsabbruch", token) { handleMqttDisconnect(profile, error, token) }
                        },
                    )
                    updateConnection(token) { it.copy(mqttStatus = LinkStatus.CONNECTED, message = null) }
                    if (connectionIntent.isCurrent(token)) refreshSnapshot(loadStaticData = false)
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (confirmation: CertificateConfirmationRequired) {
                    updateConnection(token) {
                        it.copy(mqttStatus = LinkStatus.WARNING, certificateCandidate = confirmation.candidate, message = confirmation.userMessage())
                    }
                    return@launch
                } catch (error: Throwable) {
                    updateConnection(token) {
                        it.copy(mqttStatus = LinkStatus.ERROR, message = error.userMessage(), retryAttempt = attempt)
                    }
                    if (profile.maxRetries > 0 && attempt >= profile.maxRetries) return@launch
                    delay(backoffPolicy.delayMillis(attempt, profile.initialRetrySeconds, profile.maxRetrySeconds))
                }
            }
        }
    }

    private fun updateConnection(token: Long, transform: (ConnectionStatus) -> ConnectionStatus) {
        connectionIntent.apply(token) { _connection.value = transform(_connection.value) }
    }

    private fun launchMqttCallback(label: String, token: Long, block: suspend () -> Unit) {
        scope.launch {
            try {
                if (connectionIntent.isCurrent(token)) block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateConnection(token) { it.copy(mqttStatus = LinkStatus.WARNING, message = "$label fehlgeschlagen: ${error.userMessage()}") }
            }
        }
    }

    private suspend fun applyMqttEvent(event: MqttBoxEvent, token: Long) {
        val valueEvent = event as? MqttBoxEvent.Value ?: return
        var metadataChanged = false
        var forceMetadataRefresh = false
        var confirmedVolume: Int? = null
        updateBox(valueEvent.boxId, token) { model ->
            if (!connectionIntent.isCurrent(token)) return@updateBox model
            val old = model.box
            val now = event.timestampMillis / 1_000
            val runtime = BoxStateReducer.reduce(old.runtime, valueEvent.field, valueEvent.value, now)
            metadataChanged = old.runtime.playback.ruid != runtime.playback.ruid ||
                old.runtime.playback.contentVersion != runtime.playback.contentVersion
            forceMetadataRefresh = valueEvent.field == "PlaybackChapter" &&
                model.metadata?.playlist?.none { it.index == runtime.playback.chapter } != false
            if (valueEvent.field == "VolumeLevel") {
                confirmedVolume = valueEvent.value.toIntOrNull()?.takeIf(BoxVolume::isValid)
            }
            model.copy(box = old.copy(runtime = runtime), metadata = if (metadataChanged) null else model.metadata, commandError = null)
        }
        confirmedVolume?.let { volumeCommands.confirm(valueEvent.boxId, it) }
        if (metadataChanged || forceMetadataRefresh) {
            if (connectionIntent.isCurrent(token)) refreshMetadata(valueEvent.boxId)
        }
        if (connectionIntent.isCurrent(token) && valueEvent.field.startsWith("Playback")) schedulePlaybackHistoryObservation()
    }

    private fun schedulePlaybackHistoryObservation() {
        playbackHistoryObserveJob?.cancel()
        playbackHistoryObserveJob = scope.launch {
            delay(PLAYBACK_IDENTITY_SETTLE_MS)
            observePlaybackHistory()
        }
    }

    private suspend fun observePlaybackHistory() {
        try {
            val settings = profilesStore.state.first()
            val timeoutMinutes = settings.resumeOfferTimeoutMinutes.coerceIn(
                ProfilesState.MIN_RESUME_OFFER_TIMEOUT_MINUTES,
                ProfilesState.MAX_RESUME_OFFER_TIMEOUT_MINUTES,
            )
            val autoResumeBoxes = playbackResume.observe(
                profileId = activeProfile?.id,
                boxes = _boxes.value,
                policy = PlaybackResumePolicy(
                    offerTimeoutMs = timeoutMinutes * MILLIS_PER_MINUTE,
                ),
            )
            autoResumeBoxes.forEach { resumePlayback(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Playback history observation failed", error)
        }
    }

    private suspend fun updateBox(
        boxId: String,
        token: Long? = null,
        transform: suspend (BoxUiModel) -> BoxUiModel,
    ) {
        boxUpdateMutex.withLock {
            val index = _boxes.value.indexOfFirst { it.box.id.equals(boxId, ignoreCase = true) }
            if (index < 0) return
            val current = _boxes.value.toMutableList()
            current[index] = transform(current[index])
            if (token == null) _boxes.value = current
            else connectionIntent.apply(token) { _boxes.value = current }
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
        const val LOG_TAG = "TeddyRemoteRepository"
        const val TB2_GENERATION = 2
        const val COMMAND_REFRESH_DELAY_MS = 500L
        const val SHUTDOWN_BEDTIME_SECONDS = 300
        const val BEDTIME_CONFIRM_TIMEOUT_MS = 8_000L
        const val BEDTIME_CONFIRM_POLL_MS = 400L
        const val MQTT_RECONCILIATION_MS = 60_000L
        const val FOREGROUND_POLL_MS = 2_000L
        const val BACKGROUND_PLAYING_POLL_MS = 5_000L
        const val BACKGROUND_IDLE_POLL_MS = 15_000L
        const val PLAYBACK_IDENTITY_SETTLE_MS = 300L
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

private class WifiAccessException(gate: WifiGateState) : IllegalStateException(gate.userMessage)

private fun CommandResponse.requireAccepted() {
    check(ok) { error ?: message ?: "TeddyCloud hat den Befehl abgelehnt" }
}
