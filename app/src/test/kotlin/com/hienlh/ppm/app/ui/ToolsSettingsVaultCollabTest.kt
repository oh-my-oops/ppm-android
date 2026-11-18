package com.hienlh.ppm.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.settings.SessionCollabSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Tools: the Tasks and Vault switches, design screenshots and the session-collaboration limits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ToolsSettingsVaultCollabTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private fun show(mobile: Boolean, toggles: MutableList<Pair<String, Boolean>>, collab: MutableList<Pair<String, JsonElement>>) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ToolsSettingsContent(
                        ppmTools = mapOf("create_session" to true),
                        onToggleTool = { id, on -> toggles += id to on },
                        sessionCollab = SessionCollabSettings(maxDepth = 2),
                        onSessionCollab = { k, v -> collab += k to v },
                    )
                }
            }
        }
    }

    @Test
    fun `vault tools start off and a limit is clamped before it is saved (phone)`() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        val collab = mutableListOf<Pair<String, JsonElement>>()
        show(mobile = true, toggles, collab)
        composeRule.onNodeWithTag("ppm-tool-toggle-vault_read").performScrollTo()
        composeRule.onNodeWithText("Read vault items (vault_read)").assertExists()
        composeRule.onNodeWithTag("ppm-tool-toggle-vault_read").performClick()
        assertEquals(listOf("vault_read" to true), toggles)
        composeRule.onNodeWithTag("session-collab-maxRunningSpawned").performScrollTo().performTextReplacement("99")
        composeRule.onNodeWithTag("session-collab-maxRunningSpawned").performImeAction()
        assertEquals(listOf<Pair<String, JsonElement>>("maxRunningSpawned" to JsonPrimitive(20)), collab)
    }

    @Test
    fun `tasks, branch review and design screenshots are listed with the ask switch (tablet)`() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        val collab = mutableListOf<Pair<String, JsonElement>>()
        show(mobile = false, toggles, collab)
        composeRule.onNodeWithText("Open Branch Review (open_branch_review)").assertExists()
        composeRule.onNodeWithText("Screenshot designs (design_screenshot)").assertExists()
        composeRule.onNodeWithText("Tell the manager now (ppm_task_notify_manager)").assertExists()
        composeRule.onNodeWithText("How deep").assertExists()
        composeRule.onNodeWithText("How long a session waits for a reply before going on. 1–60 min.").assertExists()
        composeRule.onNodeWithTag("session-collab-ask").performScrollTo().performClick()
        assertEquals(listOf<Pair<String, JsonElement>>("askBeforeCreate" to JsonPrimitive(false)), collab)
    }
}
