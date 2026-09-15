package de.teddycloud.teddyremote.ui

import de.teddycloud.teddyremote.model.CertificateCandidate
import de.teddycloud.teddyremote.model.CertificateTarget
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.LinkStatus
import de.teddycloud.teddyremote.model.MqttSettingsImport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileTestState(
    val status: LinkStatus = LinkStatus.NOT_CHECKED,
    val message: String? = null,
    val candidate: CertificateCandidate? = null,
    val target: String? = null,
    val isStale: Boolean = false,
)

data class MqttImportState(
    val status: LinkStatus = LinkStatus.NOT_CHECKED,
    val message: String? = null,
    val target: String? = null,
    val isStale: Boolean = false,
)

enum class MqttDraftField {
    ENABLED,
    HOST,
    PORT,
    PREFIX,
    TLS,
    USERNAME,
    PASSWORD,
}

data class ProfileEditorDraft(
    val originalProfile: ConnectionProfile,
    val originalPassword: String,
    val profile: ConnectionProfile = originalProfile,
    val password: String = originalPassword,
    val mqttDirtyFields: Set<MqttDraftField> = emptySet(),
    val apiRevision: Long = 0,
    val mqttRevision: Long = 0,
) {
    val hasUnsavedChanges: Boolean
        get() = profile != originalProfile || password != originalPassword

    internal fun updateProfile(requested: ConnectionProfile): DraftMutation {
        val apiIdentityChanged = profile.apiBaseUrl != requested.apiBaseUrl
        val mqttIdentityChanged = profile.mqttHost != requested.mqttHost ||
            profile.mqttPort != requested.mqttPort ||
            profile.mqttTls != requested.mqttTls
        val updated = requested.copy(
            apiCertificateFingerprint = if (apiIdentityChanged) null else requested.apiCertificateFingerprint,
            mqttCertificateFingerprint = if (mqttIdentityChanged) null else requested.mqttCertificateFingerprint,
        )
        val changedMqttFields = changedMqttFields(profile, updated)
        val apiChanged = profile.apiBaseUrl != updated.apiBaseUrl ||
            profile.apiCertificateFingerprint != updated.apiCertificateFingerprint
        val mqttChanged = changedMqttFields.isNotEmpty() ||
            profile.mqttCertificateFingerprint != updated.mqttCertificateFingerprint

        return DraftMutation(
            draft = copy(
                profile = updated,
                mqttDirtyFields = mqttDirtyFields + changedMqttFields,
                apiRevision = apiRevision + if (apiChanged) 1 else 0,
                mqttRevision = mqttRevision + if (mqttChanged) 1 else 0,
            ),
            apiChanged = apiChanged,
            mqttChanged = mqttChanged,
            mqttImportFieldsChanged = changedMqttFields.isNotEmpty(),
        )
    }

    internal fun updatePassword(updatedPassword: String): DraftMutation {
        if (updatedPassword == password) return DraftMutation(this)
        return DraftMutation(
            draft = copy(
                password = updatedPassword,
                mqttDirtyFields = mqttDirtyFields + MqttDraftField.PASSWORD,
                mqttRevision = mqttRevision + 1,
            ),
            mqttChanged = true,
            mqttImportFieldsChanged = true,
        )
    }

    internal fun acceptCertificate(candidate: CertificateCandidate): ProfileEditorDraft = when (candidate.target) {
        CertificateTarget.API -> copy(
            profile = profile.copy(apiCertificateFingerprint = candidate.fingerprintSha256),
            apiRevision = apiRevision + 1,
        )
        CertificateTarget.MQTT -> copy(
            profile = profile.copy(mqttCertificateFingerprint = candidate.fingerprintSha256),
            mqttRevision = mqttRevision + 1,
        )
    }

    internal fun resetCertificate(target: CertificateTarget): DraftMutation = when (target) {
        CertificateTarget.API -> {
            if (profile.apiCertificateFingerprint == null) DraftMutation(this) else DraftMutation(
                draft = copy(
                    profile = profile.copy(apiCertificateFingerprint = null),
                    apiRevision = apiRevision + 1,
                ),
                apiChanged = true,
            )
        }
        CertificateTarget.MQTT -> {
            if (profile.mqttCertificateFingerprint == null) DraftMutation(this) else DraftMutation(
                draft = copy(
                    profile = profile.copy(mqttCertificateFingerprint = null),
                    mqttRevision = mqttRevision + 1,
                ),
                mqttChanged = true,
            )
        }
    }

    internal fun applyMqttSettings(settings: MqttSettingsImport, automatic: Boolean): ImportMutation {
        fun mayImport(field: MqttDraftField): Boolean = !automatic || field !in mqttDirtyFields

        val updatedProfile = profile.copy(
            mqttEnabled = settings.enabled.takeIf { mayImport(MqttDraftField.ENABLED) } ?: profile.mqttEnabled,
            mqttHost = settings.host.takeIf { mayImport(MqttDraftField.HOST) } ?: profile.mqttHost,
            mqttPort = settings.port.takeIf { mayImport(MqttDraftField.PORT) } ?: profile.mqttPort,
            mqttPrefix = settings.prefix.takeIf { mayImport(MqttDraftField.PREFIX) } ?: profile.mqttPrefix,
            mqttTls = settings.tlsEnabled.takeIf { mayImport(MqttDraftField.TLS) } ?: profile.mqttTls,
            mqttUsername = settings.username.takeIf { mayImport(MqttDraftField.USERNAME) } ?: profile.mqttUsername,
        )
        val updatedPassword = settings.password.takeIf { mayImport(MqttDraftField.PASSWORD) } ?: password
        val identityChanged = profile.mqttHost != updatedProfile.mqttHost ||
            profile.mqttPort != updatedProfile.mqttPort ||
            profile.mqttTls != updatedProfile.mqttTls
        val finalProfile = if (identityChanged) {
            updatedProfile.copy(mqttCertificateFingerprint = null)
        } else {
            updatedProfile
        }
        val changed = finalProfile != profile || updatedPassword != password
        val preservedFields = if (automatic) mqttDirtyFields else emptySet()

        return ImportMutation(
            draft = copy(
                profile = finalProfile,
                password = updatedPassword,
                mqttDirtyFields = if (automatic) mqttDirtyFields else emptySet(),
                mqttRevision = mqttRevision + if (changed) 1 else 0,
            ),
            changed = changed,
            preservedFields = preservedFields,
        )
    }
}

data class ProfileEditorUiState(
    val sessionId: Long,
    val draft: ProfileEditorDraft,
    val apiTest: ProfileTestState = ProfileTestState(),
    val mqttImport: MqttImportState = MqttImportState(),
    val mqttTest: ProfileTestState = ProfileTestState(),
)

internal data class DraftMutation(
    val draft: ProfileEditorDraft,
    val apiChanged: Boolean = false,
    val mqttChanged: Boolean = false,
    val mqttImportFieldsChanged: Boolean = false,
)

internal data class ImportMutation(
    val draft: ProfileEditorDraft,
    val changed: Boolean,
    val preservedFields: Set<MqttDraftField>,
)

interface ProfileConnectionOperations {
    suspend fun testApi(profile: ConnectionProfile): Result<Unit>
    suspend fun importMqttSettings(profile: ConnectionProfile): Result<MqttSettingsImport>
    suspend fun testMqtt(profile: ConnectionProfile, password: String): Result<Unit>
    suspend fun inspectCertificate(profile: ConnectionProfile, target: CertificateTarget): CertificateCandidate
}

/** Coordinates the editable profile and rejects results that belong to an older draft revision. */
class ProfileEditorController(
    private val scope: CoroutineScope,
    private val operations: ProfileConnectionOperations,
) {
    private val mutableState = MutableStateFlow<ProfileEditorUiState?>(null)
    val state: StateFlow<ProfileEditorUiState?> = mutableState.asStateFlow()

    private var nextSessionId = 1L
    private var apiJob: Job? = null
    private var importJob: Job? = null
    private var mqttJob: Job? = null
    private var importIsAutomatic = false

    fun open(profile: ConnectionProfile, password: String) {
        cancelOperations()
        mutableState.value = ProfileEditorUiState(
            sessionId = nextSessionId++,
            draft = ProfileEditorDraft(profile, password),
        )
    }

    fun close() {
        cancelOperations()
        mutableState.value = null
    }

    fun updateProfile(profile: ConnectionProfile) {
        val current = mutableState.value ?: return
        val mutation = current.draft.updateProfile(profile)
        if (mutation.apiChanged) {
            apiJob?.cancel()
            importJob?.cancel()
        }
        if (mutation.mqttChanged) {
            mqttJob?.cancel()
            if (!importIsAutomatic) importJob?.cancel()
        }
        mutableState.value = current.copy(
            draft = mutation.draft,
            apiTest = if (mutation.apiChanged) current.apiTest.stale("API-Einstellungen geändert") else current.apiTest,
            mqttImport = when {
                mutation.apiChanged -> current.mqttImport.stale("API-Einstellungen geändert")
                mutation.mqttImportFieldsChanged && !(importIsAutomatic && importJob?.isActive == true) ->
                    current.mqttImport.stale("MQTT-Einstellungen manuell geändert")
                else -> current.mqttImport
            },
            mqttTest = if (mutation.mqttChanged) current.mqttTest.stale("MQTT-Einstellungen geändert") else current.mqttTest,
        )
    }

    fun updatePassword(password: String) {
        val current = mutableState.value ?: return
        val mutation = current.draft.updatePassword(password)
        if (!mutation.mqttChanged) return
        mqttJob?.cancel()
        if (!importIsAutomatic) importJob?.cancel()
        mutableState.value = current.copy(
            draft = mutation.draft,
            mqttImport = if (importIsAutomatic && importJob?.isActive == true) {
                current.mqttImport
            } else {
                current.mqttImport.stale("MQTT-Einstellungen manuell geändert")
            },
            mqttTest = current.mqttTest.stale("MQTT-Passwort geändert"),
        )
    }

    fun testApi() {
        val current = mutableState.value ?: return
        if (current.apiTest.status == LinkStatus.CONNECTING) return
        val sessionId = current.sessionId
        val revision = current.draft.apiRevision
        val profile = current.draft.profile.normalized()
        val target = apiTarget(profile)
        mutableState.value = current.copy(
            apiTest = ProfileTestState(LinkStatus.CONNECTING, "API wird geprüft …", target = target),
        )
        apiJob = scope.launch {
            val result = operations.testApi(profile)
            currentCoroutineContext().ensureActive()
            if (!isCurrentApiRequest(sessionId, revision)) return@launch
            if (result.isSuccess) {
                updateCurrent { it.copy(apiTest = ProfileTestState(LinkStatus.CONNECTED, "API verbunden", target = target)) }
                startMqttImport(automatic = true, expectedSessionId = sessionId, expectedApiRevision = revision)
                return@launch
            }

            val candidate = if (profile.apiBaseUrl.startsWith("https://", ignoreCase = true)) {
                runCatching { operations.inspectCertificate(profile, CertificateTarget.API) }.getOrNull()
            } else {
                null
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrentApiRequest(sessionId, revision)) return@launch
            updateCurrent {
                it.copy(
                    apiTest = ProfileTestState(
                        status = if (candidate != null) LinkStatus.WARNING else LinkStatus.ERROR,
                        message = result.exceptionOrNull()?.message ?: "API-Test fehlgeschlagen",
                        candidate = candidate,
                        target = target,
                    ),
                )
            }
        }
    }

    fun importMqttSettings() {
        val current = mutableState.value ?: return
        if (current.apiTest.status != LinkStatus.CONNECTED || current.apiTest.isStale) return
        startMqttImport(
            automatic = false,
            expectedSessionId = current.sessionId,
            expectedApiRevision = current.draft.apiRevision,
        )
    }

    fun testMqtt() {
        val current = mutableState.value ?: return
        if (current.mqttTest.status == LinkStatus.CONNECTING || !current.draft.profile.mqttEnabled) return
        val sessionId = current.sessionId
        val revision = current.draft.mqttRevision
        val profile = current.draft.profile.normalized()
        val password = current.draft.password
        val target = mqttTarget(profile)
        mutableState.value = current.copy(
            mqttTest = ProfileTestState(LinkStatus.CONNECTING, "MQTT wird geprüft …", target = target),
        )
        mqttJob = scope.launch {
            val result = operations.testMqtt(profile, password)
            currentCoroutineContext().ensureActive()
            if (!isCurrentMqttRequest(sessionId, revision)) return@launch
            if (result.isSuccess) {
                updateCurrent { it.copy(mqttTest = ProfileTestState(LinkStatus.CONNECTED, "MQTT verbunden", target = target)) }
                return@launch
            }

            val candidate = if (profile.mqttTls) {
                runCatching { operations.inspectCertificate(profile, CertificateTarget.MQTT) }.getOrNull()
            } else {
                null
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrentMqttRequest(sessionId, revision)) return@launch
            updateCurrent {
                it.copy(
                    mqttTest = ProfileTestState(
                        status = if (candidate != null) LinkStatus.WARNING else LinkStatus.ERROR,
                        message = result.exceptionOrNull()?.message ?: "MQTT-Test fehlgeschlagen",
                        candidate = candidate,
                        target = target,
                    ),
                )
            }
        }
    }

    fun acceptCertificate(candidate: CertificateCandidate) {
        val current = mutableState.value ?: return
        val pendingCandidate = when (candidate.target) {
            CertificateTarget.API -> current.apiTest.candidate
            CertificateTarget.MQTT -> current.mqttTest.candidate
        }
        if (pendingCandidate != candidate) return

        val updatedDraft = current.draft.acceptCertificate(candidate)
        mutableState.value = when (candidate.target) {
            CertificateTarget.API -> current.copy(draft = updatedDraft, apiTest = ProfileTestState())
            CertificateTarget.MQTT -> current.copy(draft = updatedDraft, mqttTest = ProfileTestState())
        }
        when (candidate.target) {
            CertificateTarget.API -> testApi()
            CertificateTarget.MQTT -> testMqtt()
        }
    }

    fun rejectCertificate(candidate: CertificateCandidate) {
        val current = mutableState.value ?: return
        mutableState.value = when (candidate.target) {
            CertificateTarget.API -> {
                if (current.apiTest.candidate != candidate) return
                current.copy(
                    apiTest = current.apiTest.copy(
                        status = LinkStatus.ERROR,
                        message = "API-Zertifikat wurde abgelehnt",
                        candidate = null,
                    ),
                )
            }
            CertificateTarget.MQTT -> {
                if (current.mqttTest.candidate != candidate) return
                current.copy(
                    mqttTest = current.mqttTest.copy(
                        status = LinkStatus.ERROR,
                        message = "MQTT-Zertifikat wurde abgelehnt",
                        candidate = null,
                    ),
                )
            }
        }
    }

    fun resetCertificate(target: CertificateTarget) {
        val current = mutableState.value ?: return
        val mutation = current.draft.resetCertificate(target)
        if (mutation.draft == current.draft) return
        when (target) {
            CertificateTarget.API -> {
                apiJob?.cancel()
                importJob?.cancel()
                mutableState.value = current.copy(
                    draft = mutation.draft,
                    apiTest = current.apiTest.stale("API-Zertifikat wird erneut geprüft"),
                    mqttImport = current.mqttImport.stale("API-Zertifikat wird erneut geprüft"),
                )
            }
            CertificateTarget.MQTT -> {
                mqttJob?.cancel()
                mutableState.value = current.copy(
                    draft = mutation.draft,
                    mqttTest = current.mqttTest.stale("MQTT-Zertifikat wird erneut geprüft"),
                )
            }
        }
    }

    private fun startMqttImport(automatic: Boolean, expectedSessionId: Long, expectedApiRevision: Long) {
        val current = mutableState.value ?: return
        if (current.sessionId != expectedSessionId || current.draft.apiRevision != expectedApiRevision) return
        importJob?.cancel()
        importIsAutomatic = automatic
        val expectedMqttRevision = current.draft.mqttRevision
        val profile = current.draft.profile.normalized()
        val target = apiTarget(profile)
        mutableState.value = current.copy(
            mqttImport = MqttImportState(LinkStatus.CONNECTING, "MQTT-Einstellungen werden gelesen …", target),
        )
        importJob = scope.launch {
            val result = operations.importMqttSettings(profile)
            currentCoroutineContext().ensureActive()
            val latest = mutableState.value ?: return@launch
            if (latest.sessionId != expectedSessionId || latest.draft.apiRevision != expectedApiRevision) return@launch
            if (!automatic && latest.draft.mqttRevision != expectedMqttRevision) return@launch

            result.fold(
                onSuccess = { settings ->
                    val imported = latest.draft.applyMqttSettings(settings, automatic)
                    val preserved = imported.preservedFields.size
                    val message = when {
                        preserved > 0 -> "MQTT-Einstellungen übernommen; $preserved manuell geänderte Werte beibehalten"
                        settings.enabled -> "MQTT-Einstellungen aus TeddyCloud übernommen"
                        else -> "MQTT-Einstellungen übernommen; MQTT ist in TeddyCloud deaktiviert"
                    }
                    mutableState.value = latest.copy(
                        draft = imported.draft,
                        mqttImport = MqttImportState(LinkStatus.CONNECTED, message, target),
                        mqttTest = if (imported.changed) latest.mqttTest.stale("Importierte MQTT-Einstellungen geändert") else latest.mqttTest,
                    )
                },
                onFailure = { error ->
                    mutableState.value = latest.copy(
                        mqttImport = MqttImportState(
                            LinkStatus.ERROR,
                            error.message ?: "MQTT-Import fehlgeschlagen",
                            target,
                        ),
                    )
                },
            )
        }
    }

    private fun isCurrentApiRequest(sessionId: Long, revision: Long): Boolean {
        val current = mutableState.value ?: return false
        return current.sessionId == sessionId && current.draft.apiRevision == revision
    }

    private fun isCurrentMqttRequest(sessionId: Long, revision: Long): Boolean {
        val current = mutableState.value ?: return false
        return current.sessionId == sessionId && current.draft.mqttRevision == revision
    }

    private inline fun updateCurrent(transform: (ProfileEditorUiState) -> ProfileEditorUiState) {
        mutableState.value = mutableState.value?.let(transform)
    }

    private fun cancelOperations() {
        apiJob?.cancel()
        importJob?.cancel()
        mqttJob?.cancel()
        apiJob = null
        importJob = null
        mqttJob = null
        importIsAutomatic = false
    }
}

private fun ProfileTestState.stale(reason: String): ProfileTestState {
    if (status == LinkStatus.NOT_CHECKED && message == null && candidate == null) return this
    return ProfileTestState(
        status = LinkStatus.NOT_CHECKED,
        message = "Veraltet – $reason",
        target = target,
        isStale = true,
    )
}

private fun MqttImportState.stale(reason: String): MqttImportState {
    if (status == LinkStatus.NOT_CHECKED && message == null) return this
    return MqttImportState(
        status = LinkStatus.NOT_CHECKED,
        message = "Veraltet – $reason",
        target = target,
        isStale = true,
    )
}

private fun changedMqttFields(before: ConnectionProfile, after: ConnectionProfile): Set<MqttDraftField> = buildSet {
    if (before.mqttEnabled != after.mqttEnabled) add(MqttDraftField.ENABLED)
    if (before.mqttHost != after.mqttHost) add(MqttDraftField.HOST)
    if (before.mqttPort != after.mqttPort) add(MqttDraftField.PORT)
    if (before.mqttPrefix != after.mqttPrefix) add(MqttDraftField.PREFIX)
    if (before.mqttTls != after.mqttTls) add(MqttDraftField.TLS)
    if (before.mqttUsername != after.mqttUsername) add(MqttDraftField.USERNAME)
}

private fun apiTarget(profile: ConnectionProfile): String = profile.apiBaseUrl

private fun mqttTarget(profile: ConnectionProfile): String {
    val transport = if (profile.mqttTls) "TLS" else "unverschlüsselt"
    return "${profile.mqttHost}:${profile.mqttPort} · $transport"
}
