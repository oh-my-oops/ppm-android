package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.MemoryEmbedding
import com.hienlh.ppm.protocol.MemoryEmbeddingTest
import com.hienlh.ppm.protocol.MemoryInstallStatus
import com.hienlh.ppm.protocol.MemoryProviderStatus
import com.hienlh.ppm.protocol.MemoryStatus
import com.hienlh.ppm.state.settings.MemorySettingsData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Memory (`memory-settings-section.tsx`, `memory-embedding-source.tsx`) and Tools → Memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class MemorySettingsUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val status = MemoryStatus(
        enabled = true,
        root = "/home/u/.ppm/memory",
        projectCount = 4,
        globalCount = 2,
        archivedCount = 1,
        providers = mapOf(
            "claude" to MemoryProviderStatus(count = 3, syncedAt = "2026-11-14T08:00:00Z"),
            "codex" to MemoryProviderStatus(count = 0, error = "Permission denied"),
        ),
        embedding = MemoryEmbedding(source = "off", reason = ""),
    )

    @Test
    fun tablet_showsTheStoreProvidersAndSyncsAndSwitchesOff() {
        var synced = 0
        val enabled = mutableListOf<Boolean>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MemorySettingsContent(
                        state = MemorySettingsData.State(status = status, chosenSource = "off"),
                        onEnabledChange = { enabled.add(it) },
                        onSyncNow = { synced++ },
                        relative = { "5 min ago" },
                    )
                }
            }
        }
        composeRule.onNodeWithText("PPM memory").assertExists()
        composeRule.onNodeWithText("/home/u/.ppm/memory").assertExists()
        composeRule.onNodeWithText("This project 4 · Global 2 · Archived 1").assertExists()
        composeRule.onNodeWithTag("memory-provider-claude").assertExists()
        composeRule.onNodeWithText("3 memories · 5 min ago").assertExists()
        composeRule.onNodeWithText("Permission denied", substring = true).assertExists()
        composeRule.onNodeWithTag("memory-provider-antigravity").assertExists()

        composeRule.onNodeWithTag("memory-sync-now").performClick()
        assertEquals(1, synced)
        composeRule.onNodeWithTag("memory-enabled-switch").performClick()
        assertEquals(listOf(false), enabled)

        // Off source: search runs on words only, and no recompute note.
        composeRule.onNodeWithTag("memory-words-only").performScrollTo()
        composeRule.onNodeWithText("Searching by words only").assertExists()
        composeRule.onNodeWithText("Vectors from two models are never mixed", substring = true).assertDoesNotExist()
    }

    @Test
    fun loadErrorAndLoading_areShownInsteadOfThePane() {
        var state by mutableStateOf(MemorySettingsData.State(loadError = "Server unreachable"))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                MemorySettingsContent(state = state, onEnabledChange = {}, onSyncNow = {})
            }
        }
        composeRule.onNodeWithText("Could not load memory status: Server unreachable").assertExists()
        state = MemorySettingsData.State()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memory-settings").assertDoesNotExist()
    }

    @Test
    fun syncNow_isDisabledWhileMemoryIsOff() {
        var synced = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                MemorySettingsContent(
                    state = MemorySettingsData.State(status = status.copy(enabled = false)),
                    onEnabledChange = {},
                    onSyncNow = { synced++ },
                )
            }
        }
        composeRule.onNodeWithText("Off: nothing is synced", substring = true).assertExists()
        composeRule.onNodeWithTag("memory-sync-now").performClick()
        assertEquals(0, synced)
    }

    @Test
    fun localModel_notInstalledOffersTheInstallAndPicksASource() {
        var installs = 0
        val chosen = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MemorySettingsContent(
                        state = MemorySettingsData.State(
                            status = status.copy(embedding = MemoryEmbedding(source = "local", reason = "No model to compute meanings with.")),
                            chosenSource = "local",
                        ),
                        onEnabledChange = {},
                        onSyncNow = {},
                        onChooseSource = { chosen.add(it) },
                        onInstall = { installs++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText("The model is not installed.").assertExists()
        composeRule.onNodeWithTag("memory-local-install-button").performScrollTo().performClick()
        assertEquals(1, installs)
        composeRule.onNodeWithTag("memory-source-api").performScrollTo().performClick()
        assertEquals(listOf("api"), chosen)
        composeRule.onNodeWithText("Vectors from two models are never mixed", substring = true).assertExists()
    }

    @Test
    fun localModel_installingShowsProgressAndCancel() {
        var removed = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MemorySettingsContent(
                        state = MemorySettingsData.State(
                            status = status.copy(embedding = MemoryEmbedding(source = "local")),
                            chosenSource = "local",
                            install = MemoryInstallStatus(
                                state = MemoryInstallStatus.INSTALLING,
                                step = "model",
                                bytes = 50L * 1024 * 1024,
                                totalBytes = 650L * 1024 * 1024,
                            ),
                        ),
                        onEnabledChange = {},
                        onSyncNow = {},
                        onRemove = { removed++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Installing the model…").assertExists()
        composeRule.onNodeWithText("50 / 650 MB").assertExists()
        composeRule.onNodeWithTag("memory-local-cancel").performScrollTo().performClick()
        assertEquals(1, removed)
    }

    @Test
    fun phone_apiFormSavesTestsAndShowsTheResult() {
        var url by mutableStateOf("")
        var saves = 0
        var tests = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MemorySettingsContent(
                        state = MemorySettingsData.State(
                            status = status.copy(embedding = MemoryEmbedding(source = "api", hasApiKey = true)),
                            chosenSource = "api",
                            url = url,
                            model = "text-embedding-3-small",
                            testResult = MemoryEmbeddingTest(ok = true, dims = 1536, ms = 1210),
                        ),
                        onEnabledChange = {},
                        onSyncNow = {},
                        onUrlChange = { url = it },
                        onSave = { saves++ },
                        onTest = { tests++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Every memory's text is sent to this endpoint", substring = true).assertExists()
        // Save needs an endpoint first.
        composeRule.onNodeWithTag("memory-api-save").performScrollTo().performClick()
        assertEquals(0, saves)

        composeRule.onNodeWithTag("memory-api-url").performScrollTo().performTextReplacement("https://api.openai.com/v1")
        assertEquals("https://api.openai.com/v1", url)
        composeRule.onNodeWithTag("memory-api-save").performScrollTo().performClick()
        composeRule.onNodeWithTag("memory-api-test").performClick()
        assertEquals(1, saves)
        assertEquals(1, tests)
        composeRule.onNodeWithText("Works · 1,536 dimensions · 1,210 ms").assertExists()
        // A saved key is never shown, only that one is set.
        composeRule.onNodeWithText("••••••••••••••••").assertExists()
    }

    @Test
    fun tools_memoryGroupListsItsFourSwitches() {
        var toggled: Pair<String, Boolean>? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ToolsSettingsContent(
                        ppmTools = mapOf("memory_write" to true),
                        onToggleTool = { id, on -> toggled = id to on },
                    )
                }
            }
        }
        for (id in listOf("memory_search", "memory_read", "memory_write", "memory_forget")) {
            composeRule.onNodeWithTag("ppm-tool-toggle-$id").assertExists()
        }
        composeRule.onNodeWithText("Save memory (memory_write)", substring = true).assertExists()
        composeRule.onNodeWithTag("ppm-tool-toggle-memory_write").performScrollTo().performClick()
        assertEquals("memory_write" to false, toggled)
    }
}
