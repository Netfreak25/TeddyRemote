package de.teddycloud.teddyremote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.teddycloud.teddyremote.AppContainer
import de.teddycloud.teddyremote.model.BoxUiModel
import de.teddycloud.teddyremote.model.CertificateCandidate
import de.teddycloud.teddyremote.model.CertificateTarget
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.ConnectionStatus
import de.teddycloud.teddyremote.model.ProfilesState
import de.teddycloud.teddyremote.model.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen { OVERVIEW, HOME, SETTINGS, DIAGNOSTICS, PROFILE_EDITOR }

data class MainUiState(
    val profiles: ProfilesState = ProfilesState(),
    val connection: ConnectionStatus = ConnectionStatus(),
    val boxes: List<BoxUiModel> = emptyList(),
    val screen: AppScreen = AppScreen.OVERVIEW,
    val profileEditor: ProfileEditorUiState? = null,
    val focusedBoxId: String? = null,
    val isRefreshing: Boolean = false,
) {
    val needsOnboarding: Boolean get() = profiles.profiles.isEmpty()
}

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val transient = MutableStateFlow(TransientState())
    private val profileEditorController = ProfileEditorController(
        scope = viewModelScope,
        operations = object : ProfileConnectionOperations {
            override suspend fun testApi(profile: ConnectionProfile): Result<Unit> = container.repository.testApi(profile)

            override suspend fun importMqttSettings(profile: ConnectionProfile) =
                container.repository.importMqttSettings(profile)

            override suspend fun testMqtt(profile: ConnectionProfile, password: String): Result<Unit> =
                container.repository.testMqtt(profile, password)

            override suspend fun inspectCertificate(profile: ConnectionProfile, target: CertificateTarget) =
                container.repository.inspectCertificate(profile, target)
        },
    )
    private var profileLoadJob: Job? = null

    val uiState: StateFlow<MainUiState> = combine(
        container.profilesStore.state,
        container.repository.connection,
        container.repository.boxes,
        transient,
        profileEditorController.state,
    ) { profiles, connection, boxes, local, editor ->
        MainUiState(
            profiles = profiles,
            connection = connection,
            boxes = boxes,
            screen = local.screen,
            profileEditor = editor,
            focusedBoxId = local.focusedBoxId,
            isRefreshing = local.isRefreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun navigate(screen: AppScreen) {
        if (screen != AppScreen.PROFILE_EDITOR) {
            profileLoadJob?.cancel()
            profileLoadJob = null
            if (transient.value.screen == AppScreen.PROFILE_EDITOR) profileEditorController.close()
        }
        transient.value = transient.value.copy(screen = screen)
    }

    fun editProfile(profile: ConnectionProfile?) {
        profileLoadJob?.cancel()
        if (profile == null) {
            profileEditorController.open(ConnectionProfile(), "")
            transient.value = transient.value.copy(
                screen = AppScreen.PROFILE_EDITOR,
            )
            return
        }
        profileLoadJob = viewModelScope.launch {
            val password = container.profilesStore.mqttPassword(profile.id).orEmpty()
            profileEditorController.open(profile, password)
            transient.value = transient.value.copy(screen = AppScreen.PROFILE_EDITOR)
        }
    }

    fun saveProfile(connectAfterSave: Boolean) {
        val editor = profileEditorController.state.value ?: return
        viewModelScope.launch {
            val wasOnboarding = container.profilesStore.state.first().profiles.isEmpty()
            val saved = container.profilesStore.saveProfile(editor.draft.profile.normalized(), editor.draft.password)
            container.profilesStore.activateProfile(saved.id)
            transient.value = transient.value.copy(
                screen = if (wasOnboarding) AppScreen.OVERVIEW else AppScreen.SETTINGS,
            )
            profileEditorController.close()
            if (connectAfterSave) container.repository.connect()
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch { container.profilesStore.deleteProfile(profileId) }
    }

    fun duplicateProfile(profileId: String) {
        viewModelScope.launch { container.profilesStore.duplicateProfile(profileId) }
    }

    fun activateProfile(profileId: String) {
        viewModelScope.launch { container.repository.switchProfile(profileId) }
    }

    fun connect() {
        viewModelScope.launch { container.repository.connect() }
    }

    fun disconnect() {
        viewModelScope.launch { container.repository.disconnect() }
    }

    fun refresh() {
        if (transient.value.isRefreshing) return
        transient.value = transient.value.copy(isRefreshing = true)
        viewModelScope.launch {
            try {
                container.repository.refresh()
            } finally {
                transient.value = transient.value.copy(isRefreshing = false)
            }
        }
    }

    fun refreshPlaylist(boxId: String) {
        viewModelScope.launch { container.repository.refreshMetadata(boxId) }
    }

    fun playback(boxId: String, action: String) {
        viewModelScope.launch { container.repository.playback(boxId, action) }
    }

    fun seek(boxId: String, chapter: Int, positionMs: Long) {
        viewModelScope.launch { container.repository.seek(boxId, chapter, positionMs) }
    }

    fun setVolume(boxId: String, level: Int) {
        viewModelScope.launch { container.repository.setVolume(boxId, level) }
    }

    fun ping(boxId: String) {
        viewModelScope.launch { container.repository.ping(boxId) }
    }

    fun setBedtime(boxId: String, enabled: Boolean, durationSeconds: Int?) {
        viewModelScope.launch { container.repository.setBedtime(boxId, enabled, durationSeconds) }
    }

    fun sleep(boxId: String) {
        viewModelScope.launch { container.repository.sleep(boxId) }
    }

    fun setBrightness(boxId: String, level: Int) {
        viewModelScope.launch { container.repository.setRingBrightness(boxId, level) }
    }

    fun setBedtimeBrightness(boxId: String, level: Int) {
        viewModelScope.launch { container.repository.setBedtimeRingBrightness(boxId, level) }
    }

    fun setTheme(themeMode: ThemeMode) {
        viewModelScope.launch { container.profilesStore.setThemeMode(themeMode) }
    }

    fun updateEditingProfile(profile: ConnectionProfile) = profileEditorController.updateProfile(profile)

    fun updateEditingPassword(password: String) = profileEditorController.updatePassword(password)

    fun testApi() = profileEditorController.testApi()

    fun testMqtt() = profileEditorController.testMqtt()

    fun importMqttSettings() = profileEditorController.importMqttSettings()

    fun acceptTestCertificate(candidate: CertificateCandidate) = profileEditorController.acceptCertificate(candidate)

    fun rejectTestCertificate(candidate: CertificateCandidate) = profileEditorController.rejectCertificate(candidate)

    fun resetTestCertificate(target: CertificateTarget) = profileEditorController.resetCertificate(target)

    fun confirmConnectionCertificate(candidate: CertificateCandidate) {
        viewModelScope.launch { container.repository.confirmCertificate(candidate) }
    }

    fun rejectConnectionCertificate() {
        viewModelScope.launch { container.repository.rejectCertificate() }
    }

    fun focusBox(boxId: String?) {
        transient.value = transient.value.copy(focusedBoxId = boxId?.uppercase(), screen = AppScreen.HOME)
    }

    private data class TransientState(
        val screen: AppScreen = AppScreen.OVERVIEW,
        val focusedBoxId: String? = null,
        val isRefreshing: Boolean = false,
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
    }
}
