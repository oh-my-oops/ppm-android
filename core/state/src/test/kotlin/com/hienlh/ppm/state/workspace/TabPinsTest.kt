package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.protocol.WireJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** tab-pins.test.ts, and the store's pin rules (`setTabPinned`, a drag never crossing the pinned edge). */
class TabPinsTest {
    private fun tab(id: String, pinned: Boolean = false, closable: Boolean = true) =
        WorkspaceTab(id, "editor", id, closable = closable, pinned = pinned)

    private fun ids(tabs: List<WorkspaceTab>) = tabs.map { it.id }

    @Test
    fun `pinned tabs come first, each group in its own order`() {
        val tabs = listOf(tab("a"), tab("b", pinned = true), tab("c"), tab("d", pinned = true))
        assertEquals(listOf("b", "d", "a", "c"), ids(pinnedFirst(tabs)))
        val none = listOf(tab("a"), tab("b"))
        assertSame(none, pinnedFirst(none))
    }

    @Test
    fun `a pinned tab joins the end of the pins and an unpinned one becomes the first after them`() {
        val tabs = listOf(tab("p", pinned = true), tab("a"), tab("b"), tab("c"))
        val pinned = withPinned(tabs, "c", true)
        assertEquals(listOf("p", "c", "a", "b"), ids(pinned))
        assertTrue(pinned[1].pinned)
        val unpinned = withPinned(pinned, "p", false)
        assertEquals(listOf("c", "p", "a", "b"), ids(unpinned))
        assertFalse(unpinned[1].pinned)
        // Nothing to change: the same list back.
        assertSame(tabs, withPinned(tabs, "a", false))
        assertSame(tabs, withPinned(tabs, "missing", true))
    }

    @Test
    fun `a bulk close never takes a pinned tab or one that cannot close`() {
        val tabs = listOf(tab("p", pinned = true), tab("a"), tab("fixed", closable = false), tab("b"), tab("dirty"))
        val unsaved = { t: WorkspaceTab -> t.id == "dirty" }
        assertEquals(listOf("b", "dirty"), bulkCloseIds(tabs, "a", BulkClose.OTHERS, unsaved))
        assertEquals(listOf("b", "dirty"), bulkCloseIds(tabs, "fixed", BulkClose.RIGHT, unsaved))
        assertEquals(listOf("a", "b"), bulkCloseIds(tabs, "a", BulkClose.SAVED, unsaved))
        assertEquals(listOf("a", "b", "dirty"), bulkCloseIds(tabs, "a", BulkClose.ALL, unsaved))
        // Others from a pinned tab still leaves the pin and takes the rest.
        assertEquals(listOf("a", "b", "dirty"), bulkCloseIds(tabs, "p", BulkClose.OTHERS, unsaved))
    }

    @Test
    fun `an untitled editor's text counts as unsaved work`() {
        val untitled = tab("u").copy(metadata = JsonObject(mapOf("unsavedContent" to JsonPrimitive("draft"))))
        assertTrue(tabHoldsUnsavedWork(untitled))
        assertFalse(tabHoldsUnsavedWork(tab("plain")))
    }

    @Test
    fun `pinned is read from the layout and written only while true`() {
        val json = WireJson.parse("""{"id":"x","type":"editor","title":"x","projectId":null,"metadata":{},"closable":true,"pinned":true}""")
        val read = WorkspaceTab.fromJson(json)!!
        assertTrue(read.pinned)
        assertTrue(read.extra.isEmpty())
        assertEquals(JsonPrimitive(true), read.toJson()["pinned"])
        assertFalse("pinned" in read.copy(pinned = false).toJson())
        assertFalse(WorkspaceTab.fromJson(WireJson.parse("""{"id":"y","type":"editor","title":"y"}"""))!!.pinned)
    }

    @Test
    fun `the store pins a tab, keeps pins first through a drag and a move, and saves it`() {
        var ids = 0
        val saves = mutableListOf<PanelLayout>()
        val storage = object : LayoutStorage {
            override fun load(project: String): PanelLayout? = null

            override fun save(project: String, layout: PanelLayout) {
                saves += layout
            }
        }
        val store = WorkspaceStore(storage, isMobile = { false }, now = { 1L }, randomId = { "r${++ids}" })
        store.switchProject("app")
        fun open(path: String) = store.openTab(NewTab("editor", path, "app", JsonObject(mapOf("filePath" to JsonPrimitive(path)))))
        val a = open("a")
        val b = open("b")
        val c = open("c")
        val panelId = store.state.value.focusedPanelId
        fun order() = store.state.value.panels.getValue(panelId).tabs.map { it.id }

        store.setTabPinned(c, true)
        assertEquals(listOf(c, a, b), order())
        assertTrue(saves.last().panels.getValue(panelId).tabs.first().pinned)

        // A tab dropped among the pins lands right after them; a pin dropped among the others, at the end of the pins.
        store.reorderTab(b, panelId, 0)
        assertEquals(listOf(c, b, a), order())
        store.setTabPinned(a, true)
        assertEquals(listOf(c, a, b), order())
        store.reorderTab(c, panelId, 2)
        assertEquals(listOf(a, c, b), order())

        store.setTabPinned(a, false)
        assertEquals(listOf(c, a, b), order())
    }
}
