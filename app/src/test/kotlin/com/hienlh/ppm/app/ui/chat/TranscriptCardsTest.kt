package com.hienlh.ppm.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import kotlinx.serialization.json.JsonElement
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.AgentSessionOpener
import com.hienlh.ppm.chat.ChatMessageView
import com.hienlh.ppm.chat.ChatSessionOpener
import com.hienlh.ppm.chat.ChatSessionTarget
import com.hienlh.ppm.chat.LocalAgentSessionOpener
import com.hienlh.ppm.chat.LocalChatSessionOpener
import com.hienlh.ppm.chat.LocalPpmApprovals
import com.hienlh.ppm.chat.PendingApprovalCard
import com.hienlh.ppm.chat.PpmApprovals
import com.hienlh.ppm.chat.ServerPpmApprovals
import com.hienlh.ppm.chat.ToolCardView
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.SessionCollabAnswer
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.AgentSessionIdentity
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ToolBlock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The transcript's cards shared by the phone and the tablet (`ui/chat`): PPM's own approvals
 * answered over HTTP, a message another session sent, turn chips and the question previews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TranscriptCardsTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val fixtures = WireJson.parse(checkNotNull(javaClass.getResource("/wp2-message-blocks.json")).readText()) as JsonObject

    private class RecordingApprovals(private val failDb: String? = null) : PpmApprovals {
        val collab = mutableListOf<Pair<String, SessionCollabAnswer>>()
        val db = mutableListOf<Triple<String, Boolean, String?>>()

        override suspend fun answerCollab(requestId: String, answer: SessionCollabAnswer) {
            collab += requestId to answer
        }

        override suspend fun answerDb(requestId: String, approved: Boolean, password: String?) {
            db += Triple(requestId, approved, password)
            if (failDb != null) throw PpmError.Server(403, failDb)
        }
    }

    private fun content(mobile: Boolean = true, approvals: PpmApprovals? = null, opener: ChatSessionOpener? = null, body: @Composable () -> Unit) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(
                    LocalPpmApprovals provides approvals,
                    LocalChatSessionOpener provides opener,
                    LocalAgentSessionOpener provides AgentSessionOpener(AgentSessionIdentity("ppm", "claude", "this-session-1")) { _, _ -> },
                ) { body() }
            }
        }
    }

    private fun request(id: String, tool: String, input: String) = ApprovalRequest(id, tool, WireJson.parse(input))

    private val createCard = request(
        "collab-1",
        "ppm:session_collab",
        """{"kind":"create","title":"Write the tests","providerId":"codex","model":"gpt-5","task":"Add tests\nfor login","depth":1}""",
    )

    @Test
    fun `a session tool's card is answered over HTTP, never through the chat socket`() {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("""{"ok":true,"data":{"answer":"allow_session"}}""").build())
        server.start()
        try {
            val socketReplies = mutableListOf<Boolean>()
            content(approvals = ServerPpmApprovals(ApiClient(server.url("/"), "tok"))) {
                PendingApprovalCard(createCard, { approved, _ -> socketReplies += approved })
            }
            composeRule.onNodeWithText("The AI wants to create a session").assertExists()
            composeRule.onNodeWithText("Write the tests").assertExists()
            composeRule.onNodeWithText("The whole task (19 characters, 2 lines):").assertExists()
            composeRule.onNodeWithText("Allow for this session").performClick()
            val sent = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("POST /api/sessions-collab/approvals/collab-1", sent?.let { "${it.method} ${it.target}" })
            assertEquals("""{"answer":"allow_session"}""", sent?.body?.utf8())
            assertEquals(emptyList<Boolean>(), socketReplies)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a pair-limit card offers only its own answers`() {
        val approvals = RecordingApprovals()
        content(mobile = false, approvals = approvals) {
            PendingApprovalCard(
                request("collab-2", "ppm:session_collab", """{"kind":"pair_limit","targetSessionId":"t1","targetTitle":"Reviewer","sent":10,"limit":10,"grant":5}"""),
                { _, _ -> },
            )
        }
        composeRule.onNodeWithText("These sessions reached their message limit").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Allow for this session").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Deny").assertExists()
        composeRule.onNodeWithText("Allow 5 more").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("collab-2" to SessionCollabAnswer.ALLOW_ONCE), approvals.collab)
    }

    private val dbCard = request(
        "db-7",
        "ppm:db_execute",
        """{"connectionId":3,"connectionName":"payroll","dbType":"mysql","group":"Prod","color":"#ff0000","readonly":true,
           "sql":"ALTER TABLE t ADD c INT;\nUPDATE t SET c = 1;","reason":"Add the column","expectedRows":2,"passwordRequired":true}""",
    )

    @Test
    fun `a database change needs the password, and a wrong one is said on the card`() {
        val approvals = RecordingApprovals(failDb = "Wrong PPM password.")
        val socketReplies = mutableListOf<Boolean>()
        content(approvals = approvals) { PendingApprovalCard(dbCard, { approved, _ -> socketReplies += approved }) }
        composeRule.onNodeWithText("Approve a database change").assertExists()
        composeRule.onNodeWithText("payroll").assertExists()
        composeRule.onNodeWithText("Add the column").assertExists()
        composeRule.onNodeWithText("Runs once, in one transaction. Rolled back unless exactly 2 rows change.").assertExists()
        composeRule.onNodeWithText("readonly — lifted for this script only").assertExists()
        composeRule.onNodeWithText("MySQL commits the ALTER in statement 1 by itself, so it cannot be rolled back.").assertExists()
        // No password, no Run once.
        composeRule.onNodeWithContentDescription("Run once").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("PPM password").performTextInput("hunter2")
        composeRule.onNodeWithText("Run once").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Triple("db-7", true, "hunter2")), approvals.db)
        composeRule.onNodeWithText("Wrong PPM password.").assertExists()
        // The card stays to be answered again; Decline goes over HTTP too.
        composeRule.onNodeWithText("Decline").performClick()
        composeRule.waitForIdle()
        assertEquals(Triple("db-7", false, null), approvals.db.last())
        assertEquals(emptyList<Boolean>(), socketReplies)
    }

    private fun userItem(text: String) = ChatItem("u1", ChatItem.Role.USER, listOf(ChatBlock.Text(text)), Instant.parse("2026-11-10T12:00:00Z"))

    @Test
    fun `a message from another session is its own bubble, and its header opens the sender`() {
        val opened = mutableListOf<ChatSessionTarget>()
        content(opener = { opened += it }) { ChatMessageView(userItem(fixtures.getValue("all").jsonPrimitive.content), actions = null) }
        composeRule.onNodeWithText("From:").assertExists()
        composeRule.onNodeWithText("Fix the login bug <now>").assertExists()
        composeRule.onNodeWithText("Body text").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("ppm-from-session-v1", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("ppm-turnrefs-v1", substring = true).fetchSemanticsNodes().size)
        // Nothing of the person's to edit or fork, and no Reply.
        assertEquals(0, composeRule.onAllNodesWithText("Reply").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Open \"Fix the login bug <now>\" at the turn that sent this").performClick()
        assertEquals(listOf(ChatSessionTarget("ppm", "codex", "sender-session-1", "Fix the login bug <now>", "turn-abcdef12")), opened)
    }

    @Test
    fun `turn chips name the turn and the other project, and open the session at it`() {
        val opened = mutableListOf<ChatSessionTarget>()
        content(opener = { opened += it }) { ChatMessageView(userItem(fixtures.getValue("refsUnknown").jsonPrimitive.content), actions = null) }
        composeRule.onNodeWithText("See these").assertExists()
        composeRule.onNodeWithText("Turn 3").assertExists()
        composeRule.onNodeWithText("Turn 12").assertExists()
        // The chat's own project is not named; another one is.
        assertEquals(0, composeRule.onAllNodesWithText("· ppm").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("· other").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Display data", substring = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Turn 12 of Other project chat").performClick()
        assertEquals("019a-turn-0001", opened.single().turnId)
        assertEquals("zzzzzzzz-9999", opened.single().sessionId)
    }

    @Test
    fun `a long message from another session folds behind Show more`() {
        val long = (1..12).joinToString("\n\n") { "Paragraph $it of the report." }
        val from = com.hienlh.ppm.protocol.FromSessionReference("sender-session-1", "claude", "ppm", "Reporter", null, "2026-11-10T12:00:00.000Z")
        content { ChatMessageView(userItem(com.hienlh.ppm.protocol.ChatFromSession.encode(long, from)), actions = null) }
        composeRule.onNodeWithText("Reporter").assertExists()
        composeRule.onNodeWithText("Show more").performClick()
        composeRule.onNodeWithText("Show less").assertExists()
    }

    @Test
    fun `a short message from another session has no Show more`() {
        val from = com.hienlh.ppm.protocol.FromSessionReference("sender-session-1", "claude", "ppm", "Reporter", null, "2026-11-10T12:00:00.000Z")
        content { ChatMessageView(userItem(com.hienlh.ppm.protocol.ChatFromSession.encode("Done.", from)), actions = null) }
        composeRule.onNodeWithText("Done.").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Show more").fetchSemanticsNodes().size)
    }

    @Test
    fun `a question shows the preview of the focused option`() {
        content {
            PendingApprovalCard(
                request(
                    "q1",
                    "AskUserQuestion",
                    """{"questions":[{"question":"Which plan?","options":[{"label":"Small","preview":"1. edit\n2. test"},{"label":"Big"}]}]}""",
                ),
                { _, _ -> },
            )
        }
        composeRule.onNodeWithText("PREVIEW · SMALL").assertExists()
        composeRule.onNodeWithText("1. edit\n2. test").assertExists()
        composeRule.onNodeWithText("Big").performClick()
        composeRule.onNodeWithText("PREVIEW · BIG").assertExists()
        composeRule.onNodeWithText("No preview for this option.").assertExists()
        assertTrue(composeRule.onAllNodesWithText("1. edit\n2. test").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `an answered question keeps each option's preview readable`() {
        val input = WireJson.parse(
            """{"questions":[{"question":"Which plan?","options":[{"label":"Small","preview":"1. edit\n2. test"},{"label":"Big"}]}],"answers":{"Which plan?":"Small"}}""",
        )
        content { ToolCardView(ToolBlock(toolUseId = "t1", name = "AskUserQuestion", input = input), completed = true) }
        composeRule.onNodeWithText("1 question ✓", substring = true).performClick()
        composeRule.onNodeWithText("Answer: Small").assertExists()
        assertTrue(composeRule.onAllNodesWithText("1. edit\n2. test").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Preview · Small").performClick()
        composeRule.onNodeWithText("1. edit\n2. test").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Preview · Big").fetchSemanticsNodes().size)
    }

    @Test
    fun `a tap anywhere on the Other row lets you type an answer of your own, and Submit sends it`() {
        var sent: JsonElement? = null
        content {
            PendingApprovalCard(
                request("q3", "AskUserQuestion", """{"questions":[{"question":"Which?","header":"Pick","options":[{"label":"A"},{"label":"B"}]}]}"""),
                { _, data -> sent = data },
            )
        }
        // The "O" square at the row's start, not the field: the whole row is the target, as the web's label is.
        composeRule.onNodeWithText("O", useUnmergedTree = true).performClick()
        composeRule.onNode(hasSetTextAction() and isFocused()).performTextInput("Neither")
        composeRule.onNodeWithText("Submit ✓").performClick()
        assertTrue(sent.toString(), sent.toString().contains("Neither"))
    }

    @Test
    fun `a question with no previews has no pane`() {
        content {
            PendingApprovalCard(request("q2", "AskUserQuestion", """{"questions":[{"question":"Which?","options":[{"label":"A"},{"label":"B"}]}]}"""), { _, _ -> })
        }
        composeRule.onNodeWithText("Which?").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("PREVIEW", substring = true).fetchSemanticsNodes().size)
    }

    private fun collabTool(name: String, input: String, output: String? = null, isError: Boolean = false) =
        ToolBlock(toolUseId = "c1", name = name, input = WireJson.parse(input), result = output?.let { ToolOutcome(it, isError) })

    @Test
    fun `a created session's card waits, then shows the reply and opens the session`() {
        val opened = mutableListOf<ChatSessionTarget>()
        val input = """{"title":"Write tests","task":"Cover login","provider":"codex","wait":true}"""
        val reply = """{"sessionId":"child-1","title":"Write tests","providerId":"codex","delivered":"started","reply":{"replied":true,"text":"All green now.","turnId":"turn-9"}}"""
        var tool by mutableStateOf(collabTool("mcp__ppm-sessions__create_session", input))
        content(opener = { opened += it }) { ToolCardView(tool, completed = false) }
        composeRule.onNodeWithText("Created").assertExists()
        composeRule.onNodeWithText("waiting for reply…").assertExists()
        // No session id yet: nothing to open.
        assertEquals(0, composeRule.onAllNodesWithText("Open session").fetchSemanticsNodes().size)

        tool = collabTool("mcp__ppm-sessions__create_session", input, reply)
        composeRule.onNodeWithText("replied").assertExists()
        composeRule.onNodeWithText("All green now.", substring = true).assertExists()
        composeRule.onNodeWithText("Open session").performClick()
        composeRule.onNodeWithText("Open the turn that replied").performClick()
        assertEquals(
            listOf(
                ChatSessionTarget("ppm", "codex", "child-1", "Write tests", null),
                ChatSessionTarget("ppm", "codex", "child-1", "Write tests", "turn-9"),
            ),
            opened,
        )
        composeRule.onNodeWithText("Created").performClick()
        composeRule.onNodeWithText("Cover login").assertExists()
    }

    @Test
    fun `a message with no reply says why, and a stopped wait says it was cut`() {
        val input = """{"arguments":{"session_id":"s-2","message":"Ping","wait":true}}"""
        var tool by mutableStateOf(collabTool("ppm_sessions:send_to_session", input, """{"sessionId":"s-2","title":"Other","reply":{"replied":false,"reason":"timeout"}}"""))
        content(opener = {}) { ToolCardView(tool, completed = true) }
        composeRule.onNodeWithText("Message to").assertExists()
        composeRule.onNodeWithText("Other").assertExists()
        composeRule.onNodeWithText("no reply").assertExists()
        composeRule.onNodeWithText("No reply before the wait timed out.").assertExists()

        tool = collabTool("ppm_sessions:send_to_session", input)
        composeRule.onNodeWithText("wait cancelled").assertExists()
        composeRule.onNodeWithText("Wait cancelled: the turn was stopped before a reply came.").assertExists()
    }

    @Test
    fun `a status call lists each session it asked about`() {
        val output = """{"sessions":[
            {"sessionId":"a","title":"Alpha","providerId":"claude","phase":"idle","unreadReplies":2},
            {"sessionId":"b","title":"Beta","providerId":"codex","phase":"streaming","waitingFor":"approval"}
        ]}"""
        val opened = mutableListOf<ChatSessionTarget>()
        content(opener = { opened += it }) {
            ToolCardView(collabTool("mcp__ppm-sessions__session_status", """{"session_ids":["a","b"]}""", output), completed = true)
        }
        composeRule.onNodeWithText("· 2 sessions").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Open session").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Session status").performClick()
        composeRule.onNodeWithText("· Idle").assertExists()
        composeRule.onNodeWithText("· 2 unread").assertExists()
        composeRule.onNodeWithText("· Needs approval").assertExists()
        composeRule.onNodeWithText("Beta").performClick()
        assertEquals(listOf(ChatSessionTarget("ppm", "codex", "b", "Beta", null)), opened)
    }
}
