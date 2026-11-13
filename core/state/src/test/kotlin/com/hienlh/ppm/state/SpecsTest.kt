package com.hienlh.ppm.state

import com.hienlh.ppm.client.SpecPhaseView
import com.hienlh.ppm.client.SpecSummary
import com.hienlh.ppm.client.SpecSummaryTask
import com.hienlh.ppm.protocol.SessionInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat History's family tree and spec progress, the workflow a chat starts in, and what a spec document shows. */
class SpecsTest {
    private fun s(id: String, spawnedBy: String? = null, parentTitle: String? = null, pinned: Boolean = false) =
        SessionInfo(id = id, providerId = "claude", title = id, spawnedBy = spawnedBy, parentTitle = parentTitle, pinned = pinned)

    @Test
    fun `a person's chats stay a flat list in their order`() {
        val roots = SessionTree.build(listOf(s("a"), s("b"), s("c")))
        assertEquals(listOf("a", "b", "c"), roots.map { it.id })
        assertTrue(roots.all { it.children.isEmpty() })
    }

    @Test
    fun `task chats sit under the spec's chat, and the family takes its newest member's place`() {
        val roots = SessionTree.build(listOf(s("t2", "spec"), s("other"), s("t1", "spec"), s("spec")))
        assertEquals(listOf("spec", "other"), roots.map { it.id })
        assertEquals("spec", roots[0].session?.id)
        assertEquals(listOf("t2", "t1"), roots[0].children.map { it.session.id })
        assertEquals(listOf("spec", "t2", "t1"), roots[0].members.map { it.id })
    }

    @Test
    fun `grandchildren nest under their own parent`() {
        val roots = SessionTree.build(listOf(s("gc", "c"), s("c", "p"), s("p")))
        assertEquals(1, roots.size)
        assertEquals("c", roots[0].children.single().session.id)
        assertEquals("gc", roots[0].children.single().children.single().session.id)
        assertEquals(listOf("c", "gc"), roots[0].descendants.map { it.id })
    }

    @Test
    fun `children whose creator is not in the list are grouped under a stand-in named by parentTitle`() {
        val roots = SessionTree.build(listOf(s("a"), s("t1", "gone", "Big spec"), s("t2", "gone", "Big spec")))
        assertEquals(listOf("a", "gone"), roots.map { it.id })
        assertNull(roots[1].session)
        assertEquals("Big spec", roots[1].parentTitle)
        assertEquals(listOf("t1", "t2"), roots[1].children.map { it.session.id })
    }

    @Test
    fun `a pinned child stays on its own row`() {
        val roots = SessionTree.build(listOf(s("t1", "p", pinned = true), s("p")))
        assertEquals(listOf("t1", "p"), roots.map { it.id })
        assertTrue(roots[1].children.isEmpty())
    }

    @Test
    fun `a loop in the data does not hang or drop sessions`() {
        val roots = SessionTree.build(listOf(s("a", "b"), s("b", "a")))
        assertEquals(listOf("a", "b"), roots.flatMap { it.members }.map { it.id }.sorted())
    }

    private fun T(id: String, status: String, sessionId: String? = null, optional: Boolean = false) = SpecSummaryTask(id, status, optional, sessionId)

    private fun spec(tasks: List<SpecSummaryTask>, sessionId: String? = "chat") = SpecSummary(
        slug = "s", title = "S", kind = "feature", checks = "thorough", current = "execution",
        tasksDone = tasks.count { !it.optional && it.status == "done" }, tasksTotal = tasks.count { !it.optional },
        red = 0, running = 0, waitingForYou = false, tasks = tasks, sessionId = sessionId, updatedAt = null,
    )

    private val family = spec(listOf(T("1", "done", "a"), T("2", "red", "b"), T("3", "failed", "c"), T("4", "todo"), T("5", "running", "d", true)))

    @Test
    fun `one bar cell per required task, red counting as running`() {
        assertEquals(listOf(SpecBarCell.DONE, SpecBarCell.RUNNING, SpecBarCell.FAILED, SpecBarCell.TODO), SpecFamily.bar(family))
        assertEquals(SpecFamily.Progress(done = 1, total = 4, running = 2, failed = 1), SpecFamily.progress(family))
    }

    @Test
    fun `task chats list running, then failed, then the rest in their own order`() {
        val nodes = listOf("a", "x", "c", "b", "d").map { SessionTreeNode(s(it), emptyList()) }
        assertEquals(listOf("b", "d", "c", "a", "x"), SpecFamily.orderTaskChats(nodes, SpecFamily.cellBySession(family)).map { it.session.id })
    }

    @Test
    fun `only a spec with a chat and the tasks field gets a progress bar`() {
        val older = spec(emptyList(), sessionId = "old-chat")
        val chatless = spec(listOf(T("1", "todo")), sessionId = null)
        assertEquals(setOf("chat"), SpecFamily.byChat(listOf(family, older, chatless)).keys)
    }

    @Test
    fun `a workflow choice maps to the spec it creates and back from the tab`() {
        assertNull(SpecStartChoice().specKind())
        assertEquals("feature" to "requirements-first", SpecStartChoice("spec").specKind())
        assertEquals("feature" to "design-first", SpecStartChoice("spec", startFrom = "design").specKind())
        assertEquals("bugfix" to "requirements-first", SpecStartChoice("bugfix", startFrom = "design").specKind())
        assertEquals("quick" to "requirements-first", SpecStartChoice("quick").specKind())
        val choice = SpecStartChoice("spec", "design", "thorough")
        assertEquals(choice, SpecStartChoice.from(choice.toJson()))
        assertEquals(SpecStartChoice(), SpecStartChoice.from(Json.parseToJsonElement("""{"workflow":"wild","checks":"max"}""")))
        assertFalse(SpecStartChoice.from(null).isSpec)
    }

    @Test
    fun `a spec's title is the chat's first line, cut at 80`() {
        assertEquals("Add login", SpecStartChoice.titleFrom("\n  Add login  \nwith OAuth"))
        val long = SpecStartChoice.titleFrom("x".repeat(100))
        assertEquals(78, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `a document hides the template's notes and bolds EARS words in criteria only`() {
        val md = "# Req\n<!-- for the AI -->\n1. WHEN a user logs in THE SYSTEM SHALL greet them `IF code`\nWHEN prose\n```\n- WHEN fenced <!-- kept -->\n```"
        assertEquals(
            "# Req\n1. **WHEN** a user logs in **THE SYSTEM SHALL** greet them `IF code`\nWHEN prose\n```\n- WHEN fenced <!-- kept -->\n```",
            SpecDocument.shown("requirements", md),
        )
        assertEquals("# D\n- WHEN x", SpecDocument.shown("design", "# D\n<!-- n -->\n- WHEN x"))
    }

    private fun phase(status: String = "draft", submitted: Boolean = false, approveBlocked: String? = null) =
        SpecPhaseView(status, "specs/x/design.md", emptyList(), "", submitted, approveBlocked)

    @Test
    fun `the status line says where a document stands and what an approval waits for`() {
        assertEquals(
            "Draft · the AI has not submitted it yet · Approve the UI drawing first (UI: yes).",
            SpecWorkflowCopy.phaseState(phase(approveBlocked = "Approve the UI drawing first (UI: yes).")),
        )
        assertEquals("Submitted by the AI · waiting for you", SpecWorkflowCopy.phaseState(phase(submitted = true)))
        assertEquals("Approved", SpecWorkflowCopy.phaseState(phase("approved")))
        assertEquals("Changed after you approved it", SpecWorkflowCopy.phaseState(phase("stale")))
        assertEquals("Not written yet", SpecWorkflowCopy.phaseState(phase("missing")))
        assertEquals("Has problems the AI must fix", SpecWorkflowCopy.phaseState(phase("invalid", submitted = true)))
    }

    @Test
    fun `Approve and Request changes are offered only on a document ready for review`() {
        assertTrue(phase(submitted = true).canAct)
        assertTrue(phase("stale").canAct)
        // A draft the AI has not submitted, or one something else holds back.
        assertFalse(phase().canAct)
        assertFalse(phase(submitted = true, approveBlocked = "Approve the UI drawing first (UI: yes).").canAct)
        assertFalse(phase("approved").canAct)
    }
}
