package de.teddycloud.teddyremote.updates

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private class FakeOperations(private val file: File) : UpdateOperations {
        var checks = 0
        var downloads = 0
        var installs = 0
        var cancels = 0
        var permission = false
        var waitForDownload: CompletableDeferred<Unit>? = null
        var downloadFailure = false
        val installation = MutableStateFlow(InstallStatus())
        override suspend fun check(): AppRelease {
            checks++
            return AppRelease("0.1.34", "Notes", "release.apk", "https://example.invalid/release.apk", 1)
        }
        override suspend fun download(release: AppRelease, progress: (Int) -> Unit): File {
            downloads++
            waitForDownload?.await()
            check(!downloadFailure) { "Download fehlgeschlagen" }
            progress(100)
            return file
        }
        override suspend fun install(file: File, release: AppRelease) { installs++ }
        override fun canInstall() = permission
        override fun cancelNetwork() { cancels++ }
        override fun cancelInstallation() { installation.value = InstallStatus(message = "Installation abgebrochen.") }
    }

    @Test fun `checking downloading and granting permission never automatically install`() = runTest {
        val operations = FakeOperations(temporary.newFile())
        val updater = UpdateController(backgroundScope, operations, operations.installation, true)
        runCurrent()
        assertEquals(0, operations.checks)
        updater.check(); runCurrent()
        assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
        assertEquals(0, operations.downloads)
        updater.download(); runCurrent()
        assertEquals(UpdateStage.READY, updater.state.value.stage)
        assertEquals(0, operations.installs)
        updater.install(); runCurrent()
        assertTrue(updater.state.value.permissionRequired)
        assertEquals(0, operations.installs)
        operations.permission = true
        updater.refreshPermission(); runCurrent()
        assertFalse(updater.state.value.permissionRequired)
        assertEquals(0, operations.installs)
        updater.install(); updater.install(); runCurrent()
        assertEquals(1, operations.installs)
        updater.cancelInstallation(); runCurrent()
        assertEquals(UpdateStage.READY, updater.state.value.stage)
        assertEquals("Installation abgebrochen.", updater.state.value.message)
    }

    @Test fun `cancelled wifi download cannot finish or install and concurrent starts are ignored`() = runTest {
        val operations = FakeOperations(temporary.newFile())
        val updater = UpdateController(backgroundScope, operations, operations.installation, true)
        updater.check(); runCurrent()
        operations.waitForDownload = CompletableDeferred()
        updater.download(); updater.download(); updater.check(); runCurrent()
        assertEquals(1, operations.downloads)
        assertEquals(1, operations.checks)
        updater.cancelNetwork("Kein WLAN."); runCurrent()
        operations.waitForDownload!!.complete(Unit); runCurrent()
        assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
        assertEquals(1, operations.cancels)
        updater.install(); runCurrent()
        assertEquals(0, operations.installs)
    }

    @Test fun `failed downloads and debug builds never offer installation`() = runTest {
        val operations = FakeOperations(temporary.newFile()).apply { downloadFailure = true }
        val updater = UpdateController(backgroundScope, operations, operations.installation, true)
        updater.check(); runCurrent()
        updater.download(); runCurrent()
        assertEquals(UpdateStage.ERROR, updater.state.value.stage)
        updater.install(); runCurrent()
        val debug = UpdateController(backgroundScope, operations, operations.installation, false)
        debug.check(); debug.download(); debug.install(); runCurrent()
        assertEquals(UpdateStage.DISABLED, debug.state.value.stage)
        assertEquals(1, operations.checks)
        assertEquals(0, operations.installs)
    }

    @Test fun `immediately completed operations do not block the next manual check`() = runTest {
        val operations = FakeOperations(temporary.newFile())
        val immediateScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val updater = UpdateController(immediateScope, operations, operations.installation, true)
        updater.check()
        updater.check()
        assertEquals(2, operations.checks)
        assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
    }
}
