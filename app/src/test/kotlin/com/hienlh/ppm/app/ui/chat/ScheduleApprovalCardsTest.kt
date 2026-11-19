package com.hienlh.ppm.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.HIDDEN_CHARACTERS_WARNING
import com.hienlh.ppm.chat.LocalAssistantName
import com.hienlh.ppm.chat.LocalPpmApprovals
import com.hienlh.ppm.chat.PendingApprovalCard
import com.hienlh.ppm.chat.PpmApprovals
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.SessionCollabAnswer
import com.hienlh.ppm.protocol.WireJson
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The schedule cards of `session_schedule_propose_script` / `session_schedule_set_enabled` and the
 * PPM Assistant's endpoint card (`schedule_propose_script`): the server's summary shown whole and
 * verbatim, on a phone and on a tablet. This app is PPM's own chat, so Allow is offered as the
 * web's chat offers it; the "allowed only in PPM" rule is the server's, for Telegram and mirrors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScheduleApprovalCardsTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private class RecordingApprovals : PpmApprovals {
        val collab = mutableListOf<Pair<String, SessionCollabAnswer>>()

        override suspend fun answerCollab(requestId: String, answer: SessionCollabAnswer) {
            collab += requestId to answer
        }

        override suspend fun answerDb(requestId: String, approved: Boolean, password: String?) = Unit
    }

    private fun content(mobile: Boolean, approvals: PpmApprovals? = null, assistantName: String = "PPM Assistant", body: @Composable () -> Unit) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalPpmApprovals provides approvals, LocalAssistantName provides assistantName) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { body() }
                }
            }
        }
    }

    private val script = "export default async (ctx) => {\n  await ctx.ai.chat({ sessionId: ctx.inputs.chat })\n}"

    /** As `scriptProposalSummary` builds it, the RLO in the description included. */
    private val proposal = ApprovalRequest(
        "s-1",
        "ppm:session_collab",
        WireJson.parse(
            """{"kind":"schedule","action":"create_script","summary":{
              "headline":"Create the script schedule \"watch\" in \"ppm\": it runs code on this machine with nobody watching",
              "facts":[
                {"label":"Cron","value":"*/20 * * * * *"},
                {"label":"Next 3 runs","value":"2026-11-19 09:00:20, 2026-11-19 09:00:40, 2026-11-19 09:01:00 (Europe/London)"},
                {"label":"Input folder","value":"\"C:\\Users\\me\\repo\""},
                {"label":"Its AI turns run in","value":"Bypass permissions","tone":"warning"},
                {"label":"Description (the AI's words, not checked)","value":"safe\u202Egnp.exe"}
              ],
              "body":{"label":"Script (runs exactly as shown)","text":"export default async (ctx) => {\n  await ctx.ai.chat({ sessionId: ctx.inputs.chat })\n}","format":"code"},
              "warning":"Nobody watches this: the script runs on this machine as you.",
              "reviewInPpm":"A script schedule runs code unattended, so it can only be allowed in PPM."
            }}""",
        ),
    )

    private fun assertProposalShownWhole() {
        composeRule.onNodeWithText("The AI proposes a script schedule").assertExists()
        composeRule.onNodeWithText("Create the script schedule \"watch\" in \"ppm\": it runs code on this machine with nobody watching").assertExists()
        // The seconds a 6-field cron fires on, and a Windows path as the script receives it.
        composeRule.onNodeWithText("2026-11-19 09:00:20, 2026-11-19 09:00:40, 2026-11-19 09:01:00 (Europe/London)").assertExists()
        composeRule.onNodeWithText("\"C:\\Users\\me\\repo\"").assertExists()
        // A text-direction override is drawn as a marker, with the line saying so.
        composeRule.onNodeWithText("safe⟨U+202E⟩gnp.exe").assertExists()
        composeRule.onNodeWithText(HIDDEN_CHARACTERS_WARNING).assertExists()
        composeRule.onNodeWithText(script).assertExists()
        composeRule.onNodeWithText("Script (runs exactly as shown)").assertExists()
        composeRule.onNodeWithText("Nobody watches this: the script runs on this machine as you.").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Allow for this session").fetchSemanticsNodes().size)
    }

    @Test
    fun `a script proposal shows the server's whole card on a phone and is allowed once over HTTP`() {
        val approvals = RecordingApprovals()
        val socket = mutableListOf<Boolean>()
        content(mobile = true, approvals = approvals) { PendingApprovalCard(proposal, { approved, _ -> socket += approved }) }
        assertProposalShownWhole()
        composeRule.onNodeWithText("Allow once").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("s-1" to SessionCollabAnswer.ALLOW_ONCE), approvals.collab)
        assertEquals(emptyList<Boolean>(), socket)
    }

    @Test
    fun `a script proposal on a tablet shows the same card and can be denied`() {
        val approvals = RecordingApprovals()
        content(mobile = false, approvals = approvals) { PendingApprovalCard(proposal, { _, _ -> }) }
        assertProposalShownWhole()
        composeRule.onNodeWithText("Deny").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("s-1" to SessionCollabAnswer.DENY), approvals.collab)
    }

    private val turnOn = ApprovalRequest(
        "s-2",
        "ppm:session_collab",
        WireJson.parse(
            """{"kind":"schedule","action":"enable","summary":{"headline":"Turn on the schedule \"nightly\"","facts":[{"label":"Schedule","value":"nightly (#7)"}]}}""",
        ),
    )

    private fun turnOnCard(mobile: Boolean) {
        val approvals = RecordingApprovals()
        content(mobile = mobile, approvals = approvals) { PendingApprovalCard(turnOn, { _, _ -> }) }
        composeRule.onNodeWithText("The AI wants to turn on a schedule").assertExists()
        composeRule.onNodeWithText("Turn on the schedule \"nightly\"").assertExists()
        composeRule.onNodeWithText("nightly (#7)").assertExists()
        composeRule.onNodeWithText("Allow once").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("s-2" to SessionCollabAnswer.ALLOW_ONCE), approvals.collab)
    }

    @Test
    fun `turning a project's schedule on asks with its own heading on a phone`() = turnOnCard(mobile = true)

    @Test
    fun `turning a project's schedule on asks with its own heading on a tablet`() = turnOnCard(mobile = false)

    /** The PPM Assistant's own `schedule_propose_script`: an endpoint card, answered on the chat socket. */
    private val endpoint = ApprovalRequest.from(
        WireJson.parse(
            """{"type":"approval_request","requestId":"e-1","tool":"mcp__ppm-assistant__schedule_propose_script","input":{"name":"watch"},"origin":"endpoint",
               "summary":{"headline":"Create the script schedule \"watch\" in \"ppm\"","facts":[{"label":"Cron","value":"0 9 * * 1-5"}],
               "body":{"label":"Script (runs exactly as shown)","text":"console.log(1)","format":"code"},
               "warning":"Nobody watches this.","reviewInPpm":"Only in PPM."}}""",
        ),
    )!!

    private fun endpointCard(mobile: Boolean) {
        val socket = mutableListOf<Boolean>()
        content(mobile = mobile, assistantName = "Pip") { PendingApprovalCard(endpoint, { approved, _ -> socket += approved }) }
        composeRule.onNodeWithText("Pip asks for approval").assertExists()
        composeRule.onNodeWithText("Create the script schedule \"watch\" in \"ppm\"").assertExists()
        composeRule.onNodeWithText("0 9 * * 1-5").assertExists()
        composeRule.onNodeWithText("console.log(1)").assertExists()
        composeRule.onNodeWithText("Nobody watches this.").assertExists()
        // Never the raw tool name and input the agent chose.
        assertEquals(0, composeRule.onAllNodesWithText("Tool Approval Required").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("mcp__ppm-assistant__schedule_propose_script").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(listOf(true), socket)
    }

    @Test
    fun `the Assistant's endpoint card shows its summary under its name and answers on the socket, phone`() = endpointCard(mobile = true)

    @Test
    fun `the Assistant's endpoint card shows its summary under its name and answers on the socket, tablet`() = endpointCard(mobile = false)
}
