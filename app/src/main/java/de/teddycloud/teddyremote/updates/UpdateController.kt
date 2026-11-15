package de.teddycloud.teddyremote.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

enum class UpdateStage { DISABLED, IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }

data class UpdateUiState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val release: AppRelease? = null,
    val progress: Int = 0,
    val message: String? = null,
    val permissionRequired: Boolean = false,
)

internal data class InstallStatus(val active: Boolean = false, val message: String? = null, val succeeded: Boolean = false)

internal interface UpdateOperations {
    suspend fun check(): AppRelease?
    suspend fun download(release: AppRelease, progress: (Int) -> Unit): File
    suspend fun install(file: File, release: AppRelease)
    fun canInstall(): Boolean
    fun cancelNetwork()
    fun cancelInstallation()
}

/** Manual-only updater: neither a check, a download nor a permission grant starts an installation. */
internal class UpdateController(
    private val scope: CoroutineScope,
    private val operations: UpdateOperations,
    installation: StateFlow<InstallStatus>,
    private val enabled: Boolean,
) {
    private val _state = MutableStateFlow(UpdateUiState(if (enabled) UpdateStage.IDLE else UpdateStage.DISABLED))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var apk: File? = null

    init {
        scope.launch {
            installation.collect { status ->
                if (enabled && (status.active || status.message != null)) {
                    _state.update { it.copy(
                        stage = if (status.active) UpdateStage.INSTALLING else if (status.succeeded) UpdateStage.CURRENT
                            else if (apk?.isFile == true) UpdateStage.READY else UpdateStage.ERROR,
                        message = status.message, permissionRequired = false,
                    ) }
                }
            }
        }
    }

    fun check() = start(UpdateStage.CHECKING) {
        apk?.delete()
        apk = null
        val release = operations.check()
        coroutineContext.ensureActive()
        _state.value = UpdateUiState(if (release == null) UpdateStage.CURRENT else UpdateStage.AVAILABLE, release)
    }

    fun download() {
        val release = state.value.release ?: return
        if (state.value.stage !in setOf(UpdateStage.AVAILABLE, UpdateStage.ERROR)) return
        start(UpdateStage.DOWNLOADING) {
            apk?.delete()
            apk = null
            val downloaded = operations.download(release) { progress ->
                _state.update { if (it.stage == UpdateStage.DOWNLOADING) it.copy(progress = progress) else it }
            }
            coroutineContext.ensureActive()
            apk = downloaded
            _state.update { it.copy(stage = UpdateStage.READY, progress = 100) }
        }
    }

    fun install() {
        if (!enabled || job != null || state.value.stage != UpdateStage.READY) return
        val file = apk ?: return
        val release = state.value.release ?: return
        if (!operations.canInstall()) {
            _state.update { it.copy(permissionRequired = true, message = "Erlaube TeddyRemote die Installation aus dieser Quelle. Danach erneut auf Installieren tippen.") }
            return
        }
        start(UpdateStage.INSTALLING) { operations.install(file, release) }
    }

    fun refreshPermission() {
        if (state.value.permissionRequired && operations.canInstall()) {
            _state.update { it.copy(permissionRequired = false, message = "Freigabe erteilt. Du kannst die Installation jetzt starten.") }
        }
    }

    fun cancelNetwork(message: String = "Abgebrochen.") {
        if (state.value.stage !in setOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING)) return
        job?.cancel()
        operations.cancelNetwork()
        _state.update { it.copy(stage = if (it.release == null) UpdateStage.IDLE else UpdateStage.AVAILABLE, message = message) }
    }

    fun cancelInstallation() {
        if (state.value.stage != UpdateStage.INSTALLING) return
        job?.cancel()
        operations.cancelInstallation()
    }

    fun close() {
        cancelNetwork()
        // A committed installer session is owned by Android, not by this screen.
    }

    private fun start(stage: UpdateStage, action: suspend () -> Unit) {
        if (!enabled || job != null || state.value.stage == UpdateStage.INSTALLING) return
        _state.update { it.copy(stage = stage, message = null, permissionRequired = false, progress = 0) }
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                _state.update { it.copy(stage = UpdateStage.ERROR, message = error.message ?: "Update fehlgeschlagen. Bitte erneut versuchen.") }
            } finally { job = null }
        }
        job = next
        next.start()
    }
}
