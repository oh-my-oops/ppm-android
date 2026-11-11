package com.hienlh.ppm.app.ui.phone.chat

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.HistoryByDay
import com.hienlh.ppm.app.ui.HistoryLine
import com.hienlh.ppm.app.ui.HistoryTreeLines
import com.hienlh.ppm.app.ui.PhoneSessionHistoryList
import com.hienlh.ppm.app.ui.SpawnedSessionBadge
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.SpecSummary
import com.hienlh.ppm.client.SpecSummaryTask
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.SessionTree
import com.hienlh.ppm.state.SessionTreeRoot
import com.hienlh.ppm.state.SpecBarCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** The phone's Chat History tree: families under their creator, a spec chat's progress, and "Created by". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class HistoryTreeTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = Instant.parse("2026-11-11T08:00:00Z")

    private fun s(id: String, title: String = id, spawnedBy: String? = null, parentTitle: String? = null, pinned: Boolean = false, at: String = "2026-11-11T07:00:00Z") =
        SessionInfo(id = id, providerId = "claude", title = title, updatedAt = Instant.parse(at), spawnedBy = spawnedBy, parentTitle = parentTitle, pinned = pinned)

    private fun spec(sessionId: String, vararg tasks: SpecSummaryTask) = SpecSummary(
        slug = "login", title = "Login", kind = "feature", checks = "standard", current = "execution",
        tasksDone = tasks.count { it.status == "done" }, tasksTotal = tasks.size, red = 0, running = 0,
        waitingForYou = false, tasks = tasks.toList(), sessionId = sessionId, updatedAt = null,
    )

    private fun ids(lines: List<HistoryLine>) = lines.map {
        when (it) {
            is HistoryLine.Chat -> it.session.id
            is HistoryLine.Toggle -> "toggle:" + it.parentId + (if (it.open) "+" else "-")
            is HistoryLine.More -> "more:" + it.hidden
        }
    }

    @Test
    fun `a family is collapsed under its creator until opened, or while one of its chats is the open one`() {
        val root = SessionTree.build(listOf(s("gc", spawnedBy = "c"), s("c", spawnedBy = "p"), s("p"))).single()
        assertEquals(listOf("p", "toggle:p-"), ids(HistoryTreeLines.lines(root)))
        val toggle = HistoryTreeLines.lines(root, running = setOf("gc"), unread = setOf("c"))[1] as HistoryLine.Toggle
        assertEquals(2, toggle.count)
        assertEquals(1, toggle.running)
        assertEquals(1, toggle.unread)
        assertEquals(listOf("p", "toggle:p+", "c", "toggle:c-"), ids(HistoryTreeLines.lines(root, expanded = mapOf("p" to true))))
        // The open chat is never folded away.
        assertEquals(listOf("p", "toggle:p+", "c", "toggle:c+", "gc"), ids(HistoryTreeLines.lines(root, activeSessionId = "gc")))
        val depths = HistoryTreeLines.lines(root, activeSessionId = "gc").filterIsInstance<HistoryLine.Chat>().map { it.depth }
        assertEquals(listOf(0, 1, 2), depths)
    }

    @Test
    fun `a spec's task chats come running first, then failed, seven of them until Show more`() {
        val kids = (1..9).map { s("t$it", spawnedBy = "chat") }
        val root = SessionTree.build(kids + s("chat")).single()
        val summary = spec(
            "chat",
            SpecSummaryTask("1", "done", false, "t1"),
            SpecSummaryTask("2", "failed", false, "t2"),
            SpecSummaryTask("3", "running", false, "t3"),
        )
        val lines = HistoryTreeLines.lines(root, expanded = mapOf("chat" to true), specByChat = mapOf("chat" to summary))
        assertEquals(summary, (lines[0] as HistoryLine.Chat).spec)
        assertTrue((lines[1] as HistoryLine.Toggle).taskChats)
        assertEquals(listOf("chat", "toggle:chat+", "t3", "t2", "t1", "t4", "t5", "t6", "t7", "more:2"), ids(lines))
        assertEquals(SpecBarCell.RUNNING, (lines[2] as HistoryLine.Chat).taskCell)
        assertTrue((lines[2] as HistoryLine.Chat).task)
        assertEquals(11, ids(HistoryTreeLines.lines(root, expanded = mapOf("chat" to true), showAll = setOf("chat"), specByChat = mapOf("chat" to summary))).size)
        // The open chat past the seventh shows them all.
        assertEquals(11, HistoryTreeLines.lines(root, activeSessionId = "t9", specByChat = mapOf("chat" to summary)).size)
    }

    @Test
    fun `a family is placed by its newest member, and a missing creator gets a stand-in named by its children`() {
        val roots = SessionTree.build(
            listOf(
                s("t1", spawnedBy = "spec", at = "2026-11-11T07:00:00Z"),
                s("other", at = "2026-11-10T07:00:00Z"),
                s("spec", "Login spec", at = "2026-11-01T07:00:00Z"),
                s("x1", spawnedBy = "gone", parentTitle = "Planner", at = "2026-11-10T06:00:00Z"),
            ),
        )
        val groups = HistoryByDay.groupFamiliesByDay(roots, now, zone)
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.label })
        assertEquals(listOf("spec"), groups[0].sessions.map { it.id })
        assertEquals(listOf("other", "gone"), groups[1].sessions.map { it.id })
        assertEquals("Planner", groups[1].sessions[1].title)
    }

    private fun renderTree(sessions: List<SessionInfo>, specByChat: Map<String, SpecSummary>, onStandIn: (SessionTreeRoot) -> Unit = {}) {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val roots = SessionTree.build(sessions)
        val groups = HistoryByDay.groupFamiliesByDay(roots, now, zone)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSessionHistoryList(
                    groups = groups,
                    onOpenSession = {},
                    onTogglePin = {},
                    onDeleteSession = {},
                    families = roots.associateBy { it.id },
                    specByChat = specByChat,
                    onOpenStandIn = onStandIn,
                    spawnedBadge = { SpawnedSessionBadge(model, it, "ppm") },
                )
            }
        }
    }

    private val family = listOf(
        s("t1", "Task one", spawnedBy = "spec"),
        s("t2", "Task two", spawnedBy = "spec"),
        s("spec", "Login spec"),
        s("pinned", "Pinned helper", spawnedBy = "spec", parentTitle = "Login spec", pinned = true),
        s("x1", "Research", spawnedBy = "gone", parentTitle = "Planner"),
    )

    @Test
    fun `a spec chat shows its progress and its task chats open under it`() {
        var standIn: String? = null
        renderTree(
            family,
            mapOf("spec" to spec("spec", SpecSummaryTask("1", "done", false, "t1"), SpecSummaryTask("2", "running", false, "t2"))),
        ) { standIn = it.id }

        composeRule.onNodeWithTag("history-spec-progress:login", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("1/2 done", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("2 task chats", useUnmergedTree = true).assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag("history-task-chat:t1").fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("history-family-toggle:spec").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history-task-chat:t1").assertExists()
        composeRule.onNodeWithTag("history-task-chat:t2").assertExists()

        // A pinned chat an AI made stays on its own, and says who made it.
        composeRule.onNodeWithContentDescription("Created by Login spec", useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag("history-stand-in:gone").performClick()
        assertEquals("gone", standIn)
    }

    @Test
    fun `without the server's task list a family is plain sub-chats with no progress`() {
        renderTree(family, emptyMap())

        assertEquals(0, composeRule.onAllNodesWithTag("history-spec-progress:login", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("2 sub-chats", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("history-family-toggle:spec").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Task one", useUnmergedTree = true).assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag("history-task-chat:t1").fetchSemanticsNodes().size)
    }
}
