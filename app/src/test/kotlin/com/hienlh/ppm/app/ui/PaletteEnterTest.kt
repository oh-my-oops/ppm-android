package com.hienlh.ppm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Enter in the palette runs on the list of the text it was pressed on, as on the web, whose
 * list is made in the render that holds the text. Here the list is ranked off the main thread and
 * lands after the text, so these drive a ranker that is slow on purpose.
 */
class PaletteEnterTest {
    private fun action(label: String, words: String) = PaletteItem(PaletteGroup.ACTION, label, "$label $words", action = {})

    private val actions = listOf(
        action("New AI Chat", "ai assistant claude"),
        action("New Terminal", "bash shell console"),
        action("Voice Input", "speech microphone dictate voice"),
        action("Settings", "config preferences theme"),
    )

    /** What `CommandPalette` does around its ranker, with a ranker that lands only when told to, and what an Enter ran. */
    private inner class Palette {
        private val index = PaletteSearchIndex(actions)
        private val gate = PaletteEnter()
        var text = ""
            private set
        var onScreen = PaletteRanked("", actions)
            private set
        private var listing = false
        val ran = mutableListOf<String>()

        /** The ranker's one job, for the text it was started on. */
        private var job: String? = null

        private val answered get() = onScreen.answers(text, listing)

        fun type(next: String) {
            text = next
            job = next
            moved()
        }

        fun enter() {
            if (gate.press(text, answered)) act()
        }

        /** The ranker finishes. A job for text that was typed over is dropped, as `produceState` drops it. */
        fun rankerLands() {
            val finished = job ?: return
            job = null
            if (finished == text) onScreen = PaletteRanked(finished, paletteResults(index, actions, emptyList(), finished))
            moved()
        }

        fun startReadingFolder() {
            listing = true
            moved()
        }

        fun folderRead() {
            listing = false
            moved()
        }

        private fun moved() {
            if (gate.due(text, answered)) act()
        }

        private fun act() {
            ran += onScreen.items.firstOrNull()?.label ?: "Ask AI"
        }
    }

    @Test
    fun `an Enter on a list that is there runs its first row at once`() {
        val palette = Palette()
        palette.type("new term")
        palette.rankerLands()
        palette.enter()
        assertEquals(listOf("New Terminal"), palette.ran)
    }

    @Test
    fun `an Enter before the ranker lands waits, and runs the row of its own text, not the first of the list on screen`() {
        val palette = Palette()
        palette.type("new")
        palette.rankerLands()
        palette.type("new term")
        // The ranker is slow: what is on screen still answers "new", whose first row starts a real chat.
        assertEquals("New AI Chat", palette.onScreen.items.first().label)
        palette.enter()
        assertEquals(emptyList<String>(), palette.ran)
        palette.rankerLands()
        assertEquals(listOf("New Terminal"), palette.ran)
    }

    @Test
    fun `a waiting Enter runs once, and the list landing again does not run it a second time`() {
        val palette = Palette()
        palette.type("new term")
        palette.enter()
        palette.rankerLands()
        palette.type("new term ")
        palette.rankerLands()
        assertEquals(listOf("New Terminal"), palette.ran)
    }

    @Test
    fun `typing on drops an Enter that is still waiting`() {
        val palette = Palette()
        palette.type("new")
        palette.rankerLands()
        palette.type("new term")
        palette.enter()
        palette.type("new chat")
        palette.rankerLands()
        assertEquals(emptyList<String>(), palette.ran)
        // The Enter pressed afterwards is the one that counts, on the list for "new chat".
        palette.enter()
        assertEquals(listOf("New AI Chat"), palette.ran)
    }

    @Test
    fun `with nothing typed the actions are the list, and Enter runs the first at once`() {
        val palette = Palette()
        palette.enter()
        assertEquals(listOf("New AI Chat"), palette.ran)
    }

    @Test
    fun `a text with no match asks AI once its list has landed, not before`() {
        val palette = Palette()
        palette.type("zzzzqq")
        palette.enter()
        assertEquals(emptyList<String>(), palette.ran)
        palette.rankerLands()
        assertEquals(listOf("Ask AI"), palette.ran)
    }

    @Test
    fun `a folder the host is still being asked for is not a list either`() {
        val palette = Palette()
        palette.type("~/pro")
        palette.rankerLands()
        palette.startReadingFolder()
        palette.enter()
        assertEquals(emptyList<String>(), palette.ran)
        palette.folderRead()
        assertEquals(1, palette.ran.size)
    }

    @Test
    fun `a list answers only the text it was ranked for`() {
        val list = PaletteRanked("new", actions)
        assertTrue(list.answers("new", listing = false))
        assertFalse(list.answers("new ", listing = false))
        assertFalse(list.answers("ne", listing = false))
        assertFalse(list.answers("new", listing = true))
    }
}
