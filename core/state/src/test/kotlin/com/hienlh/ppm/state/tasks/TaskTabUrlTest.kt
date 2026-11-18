package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.workspace.buildMetadataFromUrl
import com.hienlh.ppm.state.workspace.buildTitleFromUrl
import com.hienlh.ppm.state.workspace.deriveTabId
import com.hienlh.ppm.state.workspace.parsePpmLink
import com.hienlh.ppm.state.workspace.tabIdFromUrl
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A task's address (`/project/<p>/task/<id>`, `?openTask=<id>`) and its tab id, as `use-url-sync.ts` reads them. */
class TaskTabUrlTest {
    private val id = "11111111-1111-4111-8111-111111111111"

    @Test
    fun `a task address names the task tab`() {
        val link = parsePpmLink("ppm://host:8080/project/my%20app/task/$id")!!
        assertEquals("task", link.state.tabType)
        assertEquals(id, link.state.tabIdentifier)
        val meta = buildMetadataFromUrl("task", id, "my app")!!
        assertEquals(id, meta["taskId"].string)
        assertEquals("my app", meta["projectName"].string)
        assertEquals("Task", buildTitleFromUrl("task", id))
        assertEquals(tabIdFromUrl("task", id), deriveTabId("task", meta))
        assertNull(buildMetadataFromUrl("task", "12", "p"))
    }

    @Test
    fun `openTask is read from the query only when it is a task id`() {
        assertEquals(id, parsePpmLink("ppm://host/project/ppm?openTask=$id")!!.state.openTask)
        assertNull(parsePpmLink("ppm://host/project/ppm?openTask=12")!!.state.openTask)
        assertEquals("task:x", deriveTabId("task", buildJsonObject { put("taskId", "x") }))
    }
}
