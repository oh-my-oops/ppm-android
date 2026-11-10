package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.state.WaitingCardKind
import com.hienlh.ppm.state.needsYouPillLabel
import com.hienlh.ppm.state.resolveNeedsYouState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class NeedsYouTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: org.junit.rules.RuleChain = com.hienlh.ppm.app.withComposeActivity(composeRule)

    private fun json(raw: String) = Json.parseToJsonElement(raw)

    @Test
    fun resolvesPillVisibilityAndExactLabelsForAllWaitingCards() {
        val bashReq = ApprovalRequest("r-bash", "Bash", json("""{"command":"bun test"}"""))
        val questionReq = ApprovalRequest("r-q", "AskUserQuestion", json("""{"questions":[]}"""))
        val dbReq = ApprovalRequest(
            "r-db",
            "ppm:db_execute",
            json("""{"connectionId":1,"connectionName":"prod","sql":"UPDATE users SET active = true"}"""),
        )
        val createSessionReq = ApprovalRequest(
            "r-create",
            "ppm:create_session",
            json("""{"title":"Worker","permissionMode":"acceptEdits"}"""),
        )
        val elevateReq = ApprovalRequest(
            "r-elevate",
            "ppm:send_message",
            json("""{"recipientPermissionMode":"bypassPermissions"}"""),
        )
        val rateLimitReq = ApprovalRequest(
            "r-rate",
            "ppm:rate_limit",
            json("""{"turns":20}"""),
        )

        // Hidden when nothing waits or when already at bottom
        assertFalse(resolveNeedsYouState(null, atBottom = false).visible)
        assertFalse(resolveNeedsYouState(bashReq, atBottom = true).visible)

        // Visible when scrolled away from bottom (`!atBottom`)
        val bashState = resolveNeedsYouState(bashReq, atBottom = false)
        assertTrue(bashState.visible)
        assertEquals(WaitingCardKind.TOOL_APPROVAL, bashState.kind)
        assertEquals("Waiting for approval · Bash", bashState.label)
        assertTrue(bashState.warning)

        val qState = resolveNeedsYouState(questionReq, atBottom = false)
        assertTrue(qState.visible)
        assertEquals(WaitingCardKind.ASK_USER_QUESTION, qState.kind)
        assertEquals("AI has a question", qState.label)
        assertFalse(qState.warning)

        assertEquals("Approve a database change", needsYouPillLabel(dbReq))
        assertEquals("The AI wants to create a session", needsYouPillLabel(createSessionReq))
        assertEquals("The AI wants to message a session with more rights", needsYouPillLabel(elevateReq))
        assertEquals("These sessions reached their message limit", needsYouPillLabel(rateLimitReq))
    }

    @Test
    fun waitingCardKeyboardFocusStepsComposerAside() {
        val questionReq = ApprovalRequest("r-q", "AskUserQuestion", json("""{"questions":[]}"""))
        val dbReq = ApprovalRequest("r-db", "ppm:db_execute", json("""{"connectionId":1,"sql":"DELETE FROM t"}"""))
        val bashReq = ApprovalRequest("r-bash", "Bash", json("""{"command":"ls"}"""))

        assertTrue(resolveNeedsYouState(questionReq, atBottom = true, cardKeyboardFocused = true).hidesComposerOnKeyboard)
        assertTrue(resolveNeedsYouState(dbReq, atBottom = true, cardKeyboardFocused = true).hidesComposerOnKeyboard)
        assertFalse(resolveNeedsYouState(bashReq, atBottom = true, cardKeyboardFocused = true).hidesComposerOnKeyboard)
        assertFalse(resolveNeedsYouState(questionReq, atBottom = true, cardKeyboardFocused = false).hidesComposerOnKeyboard)
    }

    @Test
    fun phoneChatScreenShowsNeedsYouPillWhenScrolledAwayAndScrollsOnTap() {
        val bashReq = ApprovalRequest("r-1", "Bash", json("""{"command":"git status"}"""))
        var scrolledToWaitingCard = 0
        val atBottom = androidx.compose.runtime.mutableStateOf(false)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneChatScreen(
                    composerState = TextFieldState(""),
                    pendingApproval = bashReq,
                    atBottom = atBottom.value,
                    modelLabel = "Sonnet 4.6",
                    onSendMessage = { _, _ -> },
                    onStopTurn = {},
                    onScrollToWaitingCard = { scrolledToWaitingCard++ },
                )
            }
        }

        composeRule
            .onNodeWithText("Waiting for approval · Bash")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, scrolledToWaitingCard)

        // When atBottom is true, NeedsYouPill disappears
        atBottom.value = true
        composeRule.waitForIdle()

        assertEquals(
            0,
            composeRule.onAllNodesWithText("Waiting for approval · Bash").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun phoneToolCardPolicyEnforcesPreviewAndWriteQueryRules() {
        assertFalse(PhoneToolCardPolicy.canOpenPreviewInTabOnPhone("open_preview"))
        assertFalse(PhoneToolCardPolicy.canOpenPreviewInTabOnPhone("mcp__ppm-tabs__open_preview"))
        assertTrue(PhoneToolCardPolicy.canOpenPreviewInTabOnPhone("open_file"))
        assertEquals("no problems", PhoneToolCardPolicy.inlinePreviewDiagnosticSummary(0))
        assertEquals("2 problems", PhoneToolCardPolicy.inlinePreviewDiagnosticSummary(2))

        assertEquals(
            "Open on a tablet to run it with write access",
            PhoneToolCardPolicy.openQueryWriteNoticeOnPhone("open_query", isWriteSql = true),
        )
        assertNull(PhoneToolCardPolicy.openQueryWriteNoticeOnPhone("open_query", isWriteSql = false))
    }
}
