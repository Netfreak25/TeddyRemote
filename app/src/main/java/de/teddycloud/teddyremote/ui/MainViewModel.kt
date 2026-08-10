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
import de.teddycloud.teddyremote.model.LinkStatus
import de.teddycloud.teddyremote.model.ProfilesState
import de.teddycloud.teddyremote.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen { HOME, SETTINGS, DIAGNOSTICS, PROFILE_EDITOR }

data class ProfileTestState(
    val status: LinkStatus = LinkStatus.NOT_CHECKED,
    val message: String? = null,
    val candidate: CertificateCandidate? = null,
)

data class MainUiState(
    val profiles: ProfilesState = ProfilesState(),
    val connection: ConnectionStatus = ConnectionStatus(),
    val boxes: List<BoxUiModel> = emptyList(),
    val screen: AppScreen = AppScreen.HOME,
    val editingProfile: ConnectionProfile? = null,
    val editingPassword: String = "",
    val apiTest: ProfileTestState = ProfileTestState(),
    val mqttTest: ProfileTestState = ProfileTestState(),
    val focusedBoxId: String? = null,
    val isRefreshing: Boolean = false,
) {
    val needsOnboarding: Boolean get() = profiles.profiles.isEmpty()
}

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<MainUiState> = combine(
        container.profilesStore.state,
        container.repository.connection,
        container.repository.boxes,
        transient,
    ) { profiles, connection, boxes, local ->
        MainUiState(
            profiles = profiles,
            connection = connection,
            boxes = boxes,
            screen = local.screen,
            editingProfile = local.editingProfile,
            editingPassword = local.editingPassword,
            apiTest = local.apiTest,
            mqttTest = local.mqttTest,
            focusedBoxId = local.focusedBoxId,
            isRefreshing = local.isRefreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun navigate(screen: AppScreen) {
        transient.value = transient.value.copy(screen = screen)
    }

    fun editProfile(profile: ConnectionProfile?) {
        if (profile == null) {
            transient.value = transient.value.copy(
                screen = AppScreen.PROFILE_EDITOR,
                editingProfile = ConnectionProfile(),
                editingPassword = "",
                apiTest = ProfileTestState(),
                mqttTest = ProfileTestState(),
            )
            return
        }
        viewModelScope.launch {
            transient.value = transient.value.copy(
                screen = AppScreen.PROFILE_EDITOR,
                editingProfile = profile,
                editingPassword = container.profilesStore.mqttPassword(profile.id).orEmpty(),
                apiTest = ProfileTestState(),
                mqttTest = ProfileTestState(),
            )
        }
    }

    fun saveProfile(profile: ConnectionProfile, password: String, connectAfterSave: Boolean) {
        viewModelScope.launch {
            val saved = container.profilesStore.saveProfile(profile, password)
            container.profilesStore.activateProfile(saved.id)
            transient.value = transient.value.copy(screen = AppScreen.SETTINGS, editingProfile = null)
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

    fun playback(boxId: String, action: String, chapter: Int? = null) {
        viewModelScope.launch { container.repository.playback(boxId, action, chapter) }
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

    fun testApi(profile: ConnectionProfile) {
        transient.value = transient.value.copy(apiTest = ProfileTestState(LinkStatus.CONNECTING))
        viewModelScope.launch {
            val result = container.repository.testApi(profile)
            if (result.isSuccess) {
                transient.value = transient.value.copy(apiTest = ProfileTestState(LinkStatus.CONNECTED, "API verbunden"))
            } else {
                val candidate = if (profile.normalized().apiBaseUrl.startsWith("https://")) {
                    runCatching { container.repository.inspectCertificate(profile, CertificateTarget.API) }.getOrNull()
                } else null
                transient.value = transient.value.copy(
                    apiTest = ProfileTestState(
                        status = if (candidate != null) LinkStatus.WARNING else LinkStatus.ERROR,
                        message = result.exceptionOrNull()?.message,
                        candidate = candidate,
                    ),
                )
            }
        }
    }

    fun testMqtt(profile: ConnectionProfile, password: String) {
        transient.value = transient.value.copy(mqttTest = ProfileTestState(LinkStatus.CONNECTING))
        viewModelScope.launch {
            val result = container.repository.testMqtt(profile, password)
            if (result.isSuccess) {
                transient.value = transient.value.copy(mqttTest = ProfileTestState(LinkStatus.CONNECTED, "MQTT verbunden"))
            } else {
                val candidate = if (profile.mqttTls) {
                    runCatching { container.repository.inspectCertificate(profile, CertificateTarget.MQTT) }.getOrNull()
                } else null
                transient.value = transient.value.copy(
                    mqttTest = ProfileTestState(
                        status = if (candidate != null) LinkStatus.WARNING else LinkStatus.ERROR,
                        message = result.exceptionOrNull()?.message,
                        candidate = candidate,
                    ),
                )
            }
        }
    }

    fun acceptTestCertificate(candidate: CertificateCandidate) {
        val current = transient.value.editingProfile ?: return
        val updated = when (candidate.target) {
            CertificateTarget.API -> current.copy(apiCertificateFingerprint = candidate.fingerprintSha256)
            CertificateTarget.MQTT -> current.copy(mqttCertificateFingerprint = candidate.fingerprintSha256)
        }
        transient.value = transient.value.copy(
            editingProfile = updated,
            apiTest = if (candidate.target == CertificateTarget.API) ProfileTestState() else transient.value.apiTest,
            mqttTest = if (candidate.target == CertificateTarget.MQTT) ProfileTestState() else transient.value.mqttTest,
        )
    }

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
        val screen: AppScreen = AppScreen.HOME,
        val editingProfile: ConnectionProfile? = null,
        val editingPassword: String = "",
        val apiTest: ProfileTestState = ProfileTestState(),
        val mqttTest: ProfileTestState = ProfileTestState(),
        val focusedBoxId: String? = null,
        val isRefreshing: Boolean = false,
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
    }
}
