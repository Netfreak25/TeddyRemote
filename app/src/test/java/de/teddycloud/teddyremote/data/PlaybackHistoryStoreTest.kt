package de.teddycloud.teddyremote.data

import de.teddycloud.teddyremote.model.PlaybackHistoryState
import de.teddycloud.teddyremote.model.PlaybackToniePreference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PlaybackHistoryStoreTest {
    @Test
    fun `legacy history without known tonies remains readable`() = runTest {
        val history = PlaybackHistorySerializer.readFrom(
            ByteArrayInputStream("""{"schemaVersion":1,"bookmarks":[]}""".encodeToByteArray()),
        )

        assertTrue(history.knownTonies.isEmpty())
    }

    @Test
    fun `auto resume preference survives serialization independently from bookmarks`() = runTest {
        val output = ByteArrayOutputStream()
        PlaybackHistorySerializer.writeTo(
            PlaybackHistoryState(
                knownTonies = listOf(
                    PlaybackToniePreference(
                        ruid = RUID,
                        title = "Testtonie",
                        autoResumeEnabled = true,
                        updatedAtEpochMs = 123L,
                    ),
                ),
            ),
            output,
        )
        val restored = PlaybackHistorySerializer.readFrom(ByteArrayInputStream(output.toByteArray()))

        assertTrue(restored.bookmarks.isEmpty())
        val preference = restored.knownTonies.single()
        assertEquals(RUID, preference.ruid)
        assertEquals("Testtonie", preference.title)
        assertTrue(preference.autoResumeEnabled)
    }

    private companion object {
        const val RUID = "28F28F11500304E0"
    }
}
