package com.hienlh.ppm.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
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
 * The PPM Assistant asking to message one of the user's chats (`kind: "message"` in
 * `collab-approval-card.tsx`): its own card, never the raw tool card; the chat, its project, the
 * mode it runs in (a chat that asks nothing is called out) and the whole message; Allow once or Deny.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CollabMessageApprovalCardTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val answers = mutableListOf<Pair<String, SessionCollabAnswer>>()

    private val approvals = object : PpmApprovals {
        override suspend fun answerCollab(requestId: String, answer: SessionCollabAnswer) {
            answers += requestId to answer
        }

        override suspend fun answerDb(requestId: String, approved: Boolean, password: String?) = Unit
    }

    private fun card(mode: String) = ApprovalRequest(
        "collab-9",
        "ppm:session_collab",
        WireJson.parse(
            """{"kind":"message","targetSessionId":"s1","targetTitle":"Fix login","projectName":"web","providerId":"claude",""" +
                """"permissionMode":"$mode","modeSource":"chat","message":"Please go on\nwith step 2"}""",
        ),
    )

    private fun show(mobile: Boolean, mode: String) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalPpmApprovals provides approvals) {
                    PendingApprovalCard(card(mode), { _, _ -> })
                }
            }
        }
    }

    private fun theCardNamesTheChatAndIsAnsweredOnce(mobile: Boolean) {
        show(mobile, "bypassPermissions")
        composeRule.onNodeWithText("The Assistant wants to message a chat in web").assertExists()
        composeRule.onNodeWithText("Fix login").assertExists()
        composeRule.onNodeWithText("Runs in Bypass permissions mode.").assertExists()
        composeRule.onNodeWithTag("collab-bypass-note").assertExists()
        composeRule.onNodeWithText("The whole message (24 characters, 2 lines):").assertExists()
        composeRule.onNodeWithText("Please go on\nwith step 2").assertExists()
        // Allowing the Assistant "for this session" would let it write into chats unasked from then on.
        assertEquals(0, composeRule.onAllNodesWithText("Allow for this session").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Deny").assertExists()
        composeRule.onNodeWithText("Allow once").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("collab-9" to SessionCollabAnswer.ALLOW_ONCE), answers)
    }

    @Test
    fun theAssistantsMessageCard_onThePhone() = theCardNamesTheChatAndIsAnsweredOnce(mobile = true)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun theAssistantsMessageCard_onTheTablet() = theCardNamesTheChatAndIsAnsweredOnce(mobile = false)

    @Test
    fun aChatThatAsksStatesItsModeWithNoWarning() {
        show(mobile = true, mode = "default")
        composeRule.onNodeWithText("Runs in Ask mode.").assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag("collab-bypass-note").fetchSemanticsNodes().size)
    }
}
