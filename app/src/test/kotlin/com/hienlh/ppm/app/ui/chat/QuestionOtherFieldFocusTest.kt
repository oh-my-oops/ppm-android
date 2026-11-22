package com.hienlh.ppm.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.leavesTextOnTap
import com.hienlh.ppm.app.tracksValueTextFocus
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.PendingApprovalCard
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.LocalTextFieldFocus
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.WireJson
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The question card's "Other" answer under `Root`'s tap-to-blur and keyboard pan. Its field is drawn in `ui/chat`,
 * below the app's [TextInputFocus], so nothing told the app it held the focus: on the tablet the tap that focused
 * it was taken back as a tap on nothing (the answer typed into nothing) and the keyboard pan, which follows only
 * a field the app knows, left it under the keyboard. Each on a phone and a tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class QuestionOtherFieldFocusTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val answers = mutableListOf<Pair<Boolean, JsonElement?>>()
    private var page: LayoutCoordinates? = null

    private val request = ApprovalRequest(
        "q1",
        "AskUserQuestion",
        WireJson.parse("""{"questions":[{"question":"Which DB?","header":"DB","multiSelect":false,"options":[{"label":"Postgres","description":""},{"label":"SQLite","description":""}]}]}"""),
    )

    private fun otherAnswer(mobile: Boolean) {
        composeRule.setContent {
            val focusManager = LocalFocusManager.current
            CompositionLocalProvider(LocalTextFieldFocus provides Modifier.tracksValueTextFocus()) {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                    Column(Modifier.fillMaxSize().leavesTextOnTap(focusManager).onPlaced { page = it }) {
                        PendingApprovalCard(request, { approved, data -> answers += approved to data })
                    }
                }
            }
        }
        composeRule.onNodeWithText("Other (press O to type)...").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue("the app knows a field holds the focus", TextInputFocus.focused)
            assertNotNull("the keyboard pan can find the field", TextInputFocus.panTargetIn(page!!))
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("DuckDB")
        composeRule.onNodeWithText("Submit ✓").performClick()
        composeRule.runOnIdle {
            assertEquals(1, answers.size)
            assertTrue(answers.single().first)
            assertTrue("the typed answer is sent: ${answers.single().second}", answers.single().second.toString().contains("DuckDB"))
        }
    }

    @Test fun `a phone's Other field keeps the focus its tap gave it and sends what is typed`() = otherAnswer(mobile = true)

    @Test fun `a tablet's Other field keeps the focus its tap gave it and sends what is typed`() = otherAnswer(mobile = false)
}
