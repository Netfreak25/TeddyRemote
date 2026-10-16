package de.teddycloud.teddyremote.data

import de.teddycloud.teddyremote.model.ProfilesState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProfilesStoreTest {
    @Test fun `legacy settings keep intro outro correction disabled`() = runTest {
        val restored = ProfilesSerializer.readFrom(ByteArrayInputStream("""{"schemaVersion":1,"showTonieId":true}""".encodeToByteArray()))
        assertFalse(restored.inferIntroOutro)
        assertTrue(restored.showTonieId)
    }

    @Test fun `intro outro setting survives persistence in both states`() = runTest {
        for (enabled in listOf(true, false)) {
            val settings = ProfilesState(inferIntroOutro = enabled, showTonieId = true, resumeOfferTimeoutMinutes = 17)
            val output = ByteArrayOutputStream()
            ProfilesSerializer.writeTo(settings, output)
            val restored = ProfilesSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
            assertEquals(settings, restored)
        }
    }
}
