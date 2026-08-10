package de.teddycloud.teddyremote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaRouteSelectionTest {
    @Test
    fun `most recently updated playing box is selected`() {
        val selected = selectMediaRouteBoxId(
            listOf(
                candidate("BOX-A", playing = true, updatedAt = 10),
                candidate("BOX-B", playing = true, updatedAt = 20),
            ),
            previousBoxId = "BOX-A",
        )

        assertEquals("BOX-B", selected)
    }

    @Test
    fun `previous route remains selected while no box is playing`() {
        val selected = selectMediaRouteBoxId(
            listOf(candidate("BOX-A"), candidate("BOX-B")),
            previousBoxId = "BOX-B",
        )

        assertEquals("BOX-B", selected)
    }

    @Test
    fun `stable display name order is used without previous route`() {
        val selected = selectMediaRouteBoxId(
            listOf(candidate("BOX-A", name = "Zimmer"), candidate("BOX-B", name = "Bad")),
            previousBoxId = null,
        )

        assertEquals("BOX-B", selected)
    }

    @Test
    fun `empty candidates have no route`() {
        assertNull(selectMediaRouteBoxId(emptyList(), previousBoxId = "BOX-A"))
    }

    private fun candidate(
        id: String,
        playing: Boolean = false,
        updatedAt: Long = 0,
        name: String = id,
    ) = MediaRouteCandidate(id, playing, updatedAt, name)
}
