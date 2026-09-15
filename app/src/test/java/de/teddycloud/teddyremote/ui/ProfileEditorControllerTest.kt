package de.teddycloud.teddyremote.ui

import de.teddycloud.teddyremote.model.CertificateCandidate
import de.teddycloud.teddyremote.model.CertificateTarget
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.LinkStatus
import de.teddycloud.teddyremote.model.MqttSettingsImport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditorControllerTest {
    @Test
    fun `accepting API certificate preserves URL retries and imports once`() = runTest {
        val candidate = certificate(CertificateTarget.API)
        val operations = FakeOperations().apply {
            apiResults.add(Result.failure(IllegalStateException("certificate unknown")))
            apiResults.add(Result.success(Unit))
            inspectedCertificate = candidate
        }
        val controller = ProfileEditorController(this, operations)
        val enteredUrl = "https://192.168.1.25:8443/"
        controller.open(ConnectionProfile(apiBaseUrl = enteredUrl), "secret")

        controller.testApi()
        advanceUntilIdle()
        assertEquals(candidate, controller.state.value?.apiTest?.candidate)

        controller.acceptCertificate(candidate)
        advanceUntilIdle()

        val state = requireNotNull(controller.state.value)
        assertEquals(enteredUrl, state.draft.profile.apiBaseUrl)
        assertEquals(candidate.fingerprintSha256, state.draft.profile.apiCertificateFingerprint)
        assertEquals(LinkStatus.CONNECTED, state.apiTest.status)
        assertEquals(2, operations.apiCalls)
        assertEquals(1, operations.importCalls)
    }

    @Test
    fun `rejecting certificate leaves draft untouched and does not retry`() = runTest {
        val candidate = certificate(CertificateTarget.API)
        val profile = ConnectionProfile(apiBaseUrl = "https://192.168.1.25:8443/")
        val operations = FakeOperations().apply {
            apiResults.add(Result.failure(IllegalStateException("certificate unknown")))
            inspectedCertificate = candidate
        }
        val controller = ProfileEditorController(this, operations)
        controller.open(profile, "")
        controller.testApi()
        advanceUntilIdle()

        controller.rejectCertificate(candidate)

        val state = requireNotNull(controller.state.value)
        assertEquals(profile, state.draft.profile)
        assertNull(state.apiTest.candidate)
        assertEquals(LinkStatus.ERROR, state.apiTest.status)
        assertEquals(1, operations.apiCalls)
        assertEquals(0, operations.importCalls)
    }

    @Test
    fun `automatic import preserves manually edited MQTT fields`() = runTest {
        val operations = FakeOperations().apply {
            importedSettings = importedMqtt()
        }
        val controller = ProfileEditorController(this, operations)
        controller.open(ConnectionProfile(), "")
        controller.updateProfile(
            requireNotNull(controller.state.value).draft.profile.copy(
                mqttEnabled = true,
                mqttHost = "manual-broker",
            ),
        )
        controller.updatePassword("manual-password")

        controller.testApi()
        advanceUntilIdle()

        val state = requireNotNull(controller.state.value)
        assertEquals("manual-broker", state.draft.profile.mqttHost)
        assertEquals("manual-password", state.draft.password)
        assertEquals(2883, state.draft.profile.mqttPort)
        assertEquals("remote-prefix", state.draft.profile.mqttPrefix)
        assertTrue(state.mqttImport.message.orEmpty().contains("beibehalten"))
    }

    @Test
    fun `manual import replaces all MQTT fields`() = runTest {
        val operations = FakeOperations().apply {
            importedSettings = importedMqtt()
        }
        val controller = ProfileEditorController(this, operations)
        controller.open(ConnectionProfile(mqttEnabled = true, mqttHost = "old-broker"), "old-password")
        controller.testApi()
        advanceUntilIdle()

        controller.updateProfile(
            requireNotNull(controller.state.value).draft.profile.copy(mqttHost = "manual-broker"),
        )
        controller.updatePassword("manual-password")
        controller.importMqttSettings()
        advanceUntilIdle()

        val state = requireNotNull(controller.state.value)
        assertEquals("remote-broker", state.draft.profile.mqttHost)
        assertEquals("remote-password", state.draft.password)
        assertTrue(state.draft.mqttDirtyFields.isEmpty())
        assertEquals(2, operations.importCalls)
    }

    @Test
    fun `result from an obsolete API revision is ignored`() = runTest {
        val pendingResult = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations().apply {
            apiResultProvider = { pendingResult.await() }
        }
        val controller = ProfileEditorController(this, operations)
        controller.open(ConnectionProfile(apiBaseUrl = "https://old.example/"), "")
        controller.testApi()
        runCurrent()

        controller.updateProfile(
            requireNotNull(controller.state.value).draft.profile.copy(apiBaseUrl = "https://new.example/"),
        )
        pendingResult.complete(Result.success(Unit))
        advanceUntilIdle()

        val state = requireNotNull(controller.state.value)
        assertEquals("https://new.example/", state.draft.profile.apiBaseUrl)
        assertTrue(state.apiTest.isStale)
        assertFalse(state.apiTest.status == LinkStatus.CONNECTED)
        assertEquals(0, operations.importCalls)
    }

    @Test
    fun `accepting MQTT certificate retries with current password`() = runTest {
        val candidate = certificate(CertificateTarget.MQTT)
        val operations = FakeOperations().apply {
            mqttResults.add(Result.failure(IllegalStateException("certificate unknown")))
            mqttResults.add(Result.success(Unit))
            inspectedCertificate = candidate
        }
        val controller = ProfileEditorController(this, operations)
        controller.open(
            ConnectionProfile(mqttEnabled = true, mqttHost = "mqtt.example", mqttTls = true),
            "current-password",
        )
        controller.testMqtt()
        advanceUntilIdle()

        controller.acceptCertificate(candidate)
        advanceUntilIdle()

        val state = requireNotNull(controller.state.value)
        assertEquals(LinkStatus.CONNECTED, state.mqttTest.status)
        assertEquals(candidate.fingerprintSha256, state.draft.profile.mqttCertificateFingerprint)
        assertEquals(listOf("current-password", "current-password"), operations.mqttPasswords)
    }
}

private class FakeOperations : ProfileConnectionOperations {
    val apiResults = ArrayDeque<Result<Unit>>()
    val mqttResults = ArrayDeque<Result<Unit>>()
    var apiResultProvider: (suspend () -> Result<Unit>)? = null
    var importedSettings: MqttSettingsImport = importedMqtt()
    var inspectedCertificate: CertificateCandidate? = null
    var apiCalls = 0
    var importCalls = 0
    val mqttPasswords = mutableListOf<String>()

    override suspend fun testApi(profile: ConnectionProfile): Result<Unit> {
        apiCalls += 1
        return apiResultProvider?.invoke() ?: apiResults.removeFirstOrNull() ?: Result.success(Unit)
    }

    override suspend fun importMqttSettings(profile: ConnectionProfile): Result<MqttSettingsImport> {
        importCalls += 1
        return Result.success(importedSettings)
    }

    override suspend fun testMqtt(profile: ConnectionProfile, password: String): Result<Unit> {
        mqttPasswords += password
        return mqttResults.removeFirstOrNull() ?: Result.success(Unit)
    }

    override suspend fun inspectCertificate(
        profile: ConnectionProfile,
        target: CertificateTarget,
    ): CertificateCandidate = requireNotNull(inspectedCertificate)
}

private fun importedMqtt() = MqttSettingsImport(
    enabled = true,
    host = "remote-broker",
    port = 2883,
    prefix = "remote-prefix",
    tlsEnabled = true,
    username = "remote-user",
    password = "remote-password",
)

private fun certificate(target: CertificateTarget) = CertificateCandidate(
    target = target,
    host = if (target == CertificateTarget.API) "192.168.1.25" else "mqtt.example",
    port = if (target == CertificateTarget.API) 8443 else 1883,
    subject = "CN=TeddyCloud",
    issuer = "CN=Local CA",
    fingerprintSha256 = "AABBCCDD",
)
