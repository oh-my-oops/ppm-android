package com.hienlh.ppm.app.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.PendingApprovalCard
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.ApprovalRule
import com.hienlh.ppm.protocol.WireJson
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * approval-card-scopes.test.tsx and the plan card of line-comments.test.tsx: a Claude tool card
 * offers Allow, Allow <rule> for this chat, Always in this project and Deny…, where Deny opens a
 * box for what Claude should do instead; an ExitPlanMode call is a plan to comment on line by line.
 * Each on a phone and a tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ApprovalCardChoicesTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val answers = mutableListOf<Pair<Boolean, JsonElement?>>()

    private fun show(request: ApprovalRequest, mobile: Boolean) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                PendingApprovalCard(request, { approved, data -> answers += approved to data })
            }
        }
    }

    private fun bash(rule: ApprovalRule?) = ApprovalRequest("r1", "Bash", WireJson.parse("""{"command":"git status"}"""), rule = rule)

    private fun json(text: String) = WireJson.parse(text)

    private fun scopes(mobile: Boolean) {
        show(bash(ApprovalRule("`git status` commands", project = true)), mobile)
        composeRule.onNodeWithText("Allow").assertExists()
        composeRule.onNodeWithText("Deny…").assertExists()
        composeRule.onNodeWithText("Allow `git status` commands for this chat").performClick()
        composeRule.onNodeWithText("Always in this project").performClick()
        assertEquals(listOf(true to json("""{"scope":"chat"}"""), true to json("""{"scope":"project"}""")), answers)
    }

    @Test fun `a phone's card offers the chat and project scopes and sends the one picked`() = scopes(mobile = true)

    @Test fun `a tablet's card offers the chat and project scopes and sends the one picked`() = scopes(mobile = false)

    private fun denyWithInstructions(mobile: Boolean) {
        show(bash(ApprovalRule("`npm i` commands", project = false)), mobile)
        composeRule.onAllNodesWithText("Always in this project").assertCountEquals(0)
        composeRule.onNodeWithText("Deny…").performClick()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Deny…").performClick()
        composeRule.onNodeWithText("Deny").assertExists()
        composeRule.onNodeWithContentDescription("Tell Claude what to do instead").performTextInput("use bun install")
        composeRule.onNodeWithText("Deny and send").performClick()
        assertEquals(listOf(false to json("""{"message":"use bun install"}""")), answers)
    }

    @Test fun `Deny… on a phone opens a box whose text goes to Claude`() = denyWithInstructions(mobile = true)

    @Test fun `Deny… on a tablet opens a box whose text goes to Claude`() = denyWithInstructions(mobile = false)

    @Test
    fun `Deny with nothing written sends no instructions`() {
        show(bash(ApprovalRule("`git status` commands", project = true)), mobile = true)
        composeRule.onNodeWithText("Deny…").performClick()
        composeRule.onNodeWithText("Deny").performClick()
        assertEquals(listOf(false to null), answers)
    }

    @Test
    fun `a card with no rule keeps plain Allow and Deny`() {
        show(bash(null), mobile = false)
        composeRule.onAllNodesWithText("Always in this project").assertCountEquals(0)
        composeRule.onAllNodesWithText("Deny…").assertCountEquals(0)
        composeRule.onNodeWithText("Deny").performClick()
        assertEquals(listOf(false to null), answers)
    }

    private val plan = ApprovalRequest("r2", "ExitPlanMode", WireJson.parse("""{"plan":"# Plan\n1. Add a table\n2. Write tests"}"""))

    private fun planFeedback(mobile: Boolean) {
        show(plan, mobile)
        composeRule.onNodeWithText("Plan ready for review").assertExists()
        composeRule.onNodeWithText("Keep planning").assertExists()
        composeRule.onNodeWithContentDescription("Comment on line 2").performClick()
        composeRule.onNodeWithContentDescription("Comment on this line").performTextInput("Use the lazy table")
        composeRule.onNodeWithText("Comment").performClick()
        composeRule.onNodeWithText("Use the lazy table").assertExists()
        composeRule.onNodeWithText("Approving ignores the comments.").assertExists()
        composeRule.onNodeWithText("Send feedback (1 comment)").performClick()
        assertEquals(
            listOf(false to json("""{"message":"Comments on the plan — revise it to address each one, then present it again:\n\n> 2: 1. Add a table\n\nUse the lazy table"}""")),
            answers,
        )
    }

    @Test fun `a phone's plan card sends the line comments back as feedback, quoting each line`() = planFeedback(mobile = true)

    @Test fun `a tablet's plan card sends the line comments back as feedback, quoting each line`() = planFeedback(mobile = false)

    @Test
    fun `a plan comment can be deleted, and the plan approved as is`() {
        show(plan, mobile = true)
        composeRule.onNodeWithContentDescription("Comment on line 3").performClick()
        composeRule.onNodeWithContentDescription("Comment on this line").performTextInput("Tests first")
        composeRule.onNodeWithText("Comment").performClick()
        composeRule.onNodeWithContentDescription("Delete comment").performClick()
        composeRule.onAllNodesWithText("Tests first").assertCountEquals(0)
        composeRule.onNodeWithText("Keep planning").assertExists()
        composeRule.onNodeWithText("Approve plan").performClick()
        assertEquals(listOf(true to null), answers)
    }

    @Test
    fun `the overall note alone is feedback, and Preview shows the plan rendered`() {
        show(plan, mobile = false)
        composeRule.onNodeWithText("Preview").performClick()
        composeRule.onNodeWithText("Comment").assertExists()
        composeRule.onNodeWithContentDescription("Feedback on the plan").performTextInput("Simpler please")
        composeRule.onNodeWithText("Send feedback").performClick()
        assertEquals(listOf(false to json("""{"message":"Simpler please"}""")), answers)
    }
}
