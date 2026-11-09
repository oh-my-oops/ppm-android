package com.hienlh.ppm.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.PromptCacheStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class ComposerSingleBarTest {

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var model: AppModel
    private lateinit var chat: ChatController

    @Before
    fun setUp() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true,"data":[]}""").build())
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val api = ApiClient(server.url("/"), "secret")
        model.api = api
        chat = ChatController(api, "p", null, scope, providerId = "claude")
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun singleBarComposer_placesTextareaAboveOneActionRowWithoutProviderChip_andAdaptsLabelsToWidth() {
        val tab = ChatTab("chat-new", session = null, project = "p", providerId = "claude")
        tab.permissionMode = PermissionMode.BYPASS_PERMISSIONS

        var narrow by mutableStateOf(false)
        var mobile by mutableStateOf(false)
        var modelId by mutableStateOf("claude-opus-4-7")

        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                Box(Modifier.width(if (narrow) 380.dp else 720.dp)) {
                    val fileDrop = ChatFileDrop(tab) { _, _ -> }
                    val pickers = rememberComposerPickers(model, tab)
                    ChatComposer(
                        model = model,
                        tab = tab,
                        chat = chat,
                        state = ChatState(
                            sessionId = null,
                            model = modelId,
                            effort = "high",
                            thinking = true,
                        ),
                        narrow = narrow,
                        fileDrop = fileDrop,
                        pickers = pickers,
                    )
                }
            }
        }
        compose.waitForIdle()

        // No standalone provider chip exists on the composer bar even for a brand-new chat (session == null).
        assertTrue(
            compose.onAllNodes(hasContentDescription("AI Provider", substring = true))
                .fetchSemanticsNodes()
                .isEmpty(),
        )

        // All action controls share one horizontal bar below the textarea.
        val composerBounds = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot
        val barBounds = compose.onNodeWithTag("composer-action-bar").fetchSemanticsNode().boundsInRoot
        val attachBounds = compose.onNodeWithContentDescription("Attach file").fetchSemanticsNode().boundsInRoot
        val modeNode = compose.onNodeWithTag("composer-mode-chip").fetchSemanticsNode()
        val modelNode = compose.onNodeWithTag("composer-model-chip").fetchSemanticsNode()
        val sendBounds = compose.onNodeWithContentDescription("Send message").fetchSemanticsNode().boundsInRoot

        assertTrue("action bar sits below textarea", barBounds.top > composerBounds.top + 30f)
        for (itemBounds in listOf(attachBounds, modeNode.boundsInRoot, modelNode.boundsInRoot, sendBounds)) {
            assertTrue("control sits inside action bar vertically", itemBounds.top >= barBounds.top - 1f && itemBounds.bottom <= barBounds.bottom + 1f)
        }
        assertTrue("Attach precedes Mode", attachBounds.right <= modeNode.boundsInRoot.left + 1f)
        assertTrue("Mode precedes Model", modeNode.boundsInRoot.right <= modelNode.boundsInRoot.left + 1f)
        assertTrue("Model stays left of Send", modelNode.boundsInRoot.right <= sendBounds.left + 1f)
        assertTrue("Send stays inside composer", sendBounds.right <= composerBounds.right + 1f)

        // Wide tablet: ModeChip shows short label ("Bypass"), ModelChip shows "Opus 4.7 · High" + chevron.
        assertEquals("Bypass", modeNode.config[DisplayedChipTextKey])
        assertEquals("Opus 4.7 · High", modelNode.config[DisplayedChipTextKey])
        assertEquals(true, modelNode.config[ShowsChevronKey])

        // Long model name is bounded (<= 260dp) and never pushes Send outside the composer.
        modelId = "custom-ultra-verbose-enterprise-reasoning-preview-2026-11-10-extra-long-identifier"
        compose.waitForIdle()
        val longModelBounds = compose.onNodeWithTag("composer-model-chip").fetchSemanticsNode().boundsInRoot
        val sendAfterLong = compose.onNodeWithContentDescription("Send message").fetchSemanticsNode().boundsInRoot
        val maxChipWidthPx = with(compose.density) { 260.dp.toPx() }
        assertTrue("model chip width bounded: ${longModelBounds.width} <= $maxChipWidthPx", longModelBounds.width <= maxChipWidthPx)
        assertTrue("Send remains inside composer", sendAfterLong.right <= composerBounds.right + 1f)

        // Narrow chat (@max-[420px]/chat): ModeChip hides text, ModelChip drops " · High" and chevron so "Opus 4.7" isn't cut.
        modelId = "claude-opus-4-7"
        narrow = true
        compose.waitForIdle()
        val narrowMode = compose.onNodeWithTag("composer-mode-chip").fetchSemanticsNode()
        val narrowModel = compose.onNodeWithTag("composer-model-chip").fetchSemanticsNode()
        assertEquals("", narrowMode.config[DisplayedChipTextKey])
        assertEquals("Opus 4.7", narrowModel.config[DisplayedChipTextKey])
        assertEquals(false, narrowModel.config[ShowsChevronKey])

        // Mobile layout (<md) even on wider width: also hides Mode short label, effort suffix, and chevron.
        narrow = false
        mobile = true
        compose.waitForIdle()
        val mobileMode = compose.onNodeWithTag("composer-mode-chip").fetchSemanticsNode()
        val mobileModel = compose.onNodeWithTag("composer-model-chip").fetchSemanticsNode()
        assertEquals("", mobileMode.config[DisplayedChipTextKey])
        assertEquals("Opus 4.7", mobileModel.config[DisplayedChipTextKey])
        assertEquals(false, mobileModel.config[ShowsChevronKey])
    }

    @Test
    fun providerChoiceInsideModelPicker_offeredOnlyBeforeSessionOutsideDesign_andKeepsPickerOpenOnSwitch() {
        val freshTab = ChatTab("chat-1", session = null, project = "p", providerId = "claude")
        val existingTab = ChatTab("chat-2", session = SessionInfo("s1", "claude", "Existing"), project = "p", providerId = "claude")
        val designTab = ChatTab("design:landing", session = null, project = "p", providerId = "claude")
        assertTrue(choosesProvider(freshTab))
        assertFalse(choosesProvider(existingTab))
        assertFalse(choosesProvider(designTab))

        var currentProvider by mutableStateOf("claude")
        var pickedModel: String? = null
        var closedCount = 0

        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ModelPickerColumns(
                    models = if (currentProvider == "codex") {
                        listOf(ModelOption("gpt-5.2-codex", "GPT-5.2 Codex"))
                    } else {
                        listOf(ModelOption("claude-opus-4-7", "Claude Opus 4.7"))
                    },
                    status = null,
                    model = if (currentProvider == "codex") "gpt-5.2-codex" else "claude-opus-4-7",
                    effort = Effort.HIGH,
                    thinking = true,
                    box = Modifier.width(320.dp),
                    inner = Modifier,
                    providerId = currentProvider,
                    header = {
                        ProviderChoiceBar(
                            current = currentProvider,
                            providers = listOf(
                                ProviderInfo("claude", "claude"),
                                ProviderInfo("codex", "codex"),
                                ProviderInfo("antigravity", "antigravity"),
                            ),
                            status = "",
                            pick = { currentProvider = it },
                        )
                    },
                    onModel = {
                        pickedModel = it
                        closedCount++
                    },
                    onEffort = { closedCount++ },
                    onThinking = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("provider-choice-row").assertIsDisplayed()
        compose.onNodeWithText("Opus 4.7").assertIsDisplayed()

        // Picking Codex updates the provider & model list WITHOUT closing the picker (`closedCount == 0`).
        compose.onNodeWithText("Codex").performClick()
        compose.waitForIdle()
        assertEquals("codex", currentProvider)
        assertEquals(0, closedCount)
        compose.onNodeWithText("GPT-5.2 Codex").assertIsDisplayed()

        // Picking a model afterwards selects it and triggers close (`closedCount == 1`).
        compose.onNodeWithText("GPT-5.2 Codex").performClick()
        compose.waitForIdle()
        assertEquals("gpt-5.2-codex", pickedModel)
        assertEquals(1, closedCount)
    }

    @Test
    fun promptCacheChip_rendersNothingWhileWarm_andShowsBorderlessColdClockWithResponsiveLabel() {
        val now = System.currentTimeMillis().toDouble()
        val warmState = PromptCacheState(
            ttlMs = 300_000.0,
            lastTurnEndedAt = now - 60_000.0,
            compactedAt = null,
            billedPrefixTokens = PromptCacheStatus.WARN_TOKENS + 5_000.0,
            contextTokens = 48_000.0,
        )
        val coldState = PromptCacheState(
            ttlMs = 300_000.0,
            lastTurnEndedAt = now - (111 * 60_000.0), // 1h 51m idle
            compactedAt = null,
            billedPrefixTokens = PromptCacheStatus.WARN_TOKENS + 5_000.0,
            contextTokens = 48_000.0,
        )

        var cache by mutableStateOf<PromptCacheState?>(warmState)
        var narrow by mutableStateOf(false)

        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                PromptCacheChip(cache = cache, narrow = narrow)
            }
        }
        compose.waitForIdle()

        // Warm cache renders nothing at all.
        assertTrue(compose.onAllNodesWithTag("prompt-cache-cold").fetchSemanticsNodes().isEmpty())

        // Cold cache renders clock + tabular idle text + full price detail on wide tablet.
        cache = coldState
        compose.waitForIdle()
        compose.onNodeWithTag("prompt-cache-cold").assertIsDisplayed()
        compose.onNodeWithText("1h 51m").assertIsDisplayed()
        compose.onNode(hasContentDescription("Prompt cache expired 1h 51m ago", substring = true)).assertIsDisplayed()

        // Narrow chat keeps the cold indicator & aria-label, hiding the text label.
        narrow = true
        compose.waitForIdle()
        compose.onNodeWithTag("prompt-cache-cold").assertIsDisplayed()
        compose.onNodeWithText("1h 51m").assertDoesNotExist()
    }

    @Test
    fun streamingState_swapsSendToStop_showsPriorityCycle_andLocksModelChip() {
        val tab = ChatTab("chat-stream", session = SessionInfo("s1", "claude", "Stream"), project = "p", providerId = "claude")
        var phase by mutableStateOf(SessionPhase.IDLE)

        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.width(720.dp)) {
                    val fileDrop = ChatFileDrop(tab) { _, _ -> }
                    val pickers = rememberComposerPickers(model, tab)
                    ChatComposer(
                        model = model,
                        tab = tab,
                        chat = chat,
                        state = ChatState(
                            sessionId = "s1",
                            model = "claude-opus-4-7",
                            effort = "high",
                            thinking = false,
                            phase = phase,
                        ),
                        narrow = false,
                        fileDrop = fileDrop,
                        pickers = pickers,
                    )
                }
            }
        }
        compose.waitForIdle()

        // Idle: Send disabled (empty draft), no Stop, no Priority chip, Model chip enabled.
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(compose.onAllNodes(hasContentDescription("Stop response")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("composer-priority-chip").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("composer-model-chip").assertIsEnabled()

        // Streaming with empty draft: Stop replaces Send, Model chip locks, Priority chip cycles Queue -> Later -> Interrupt.
        phase = SessionPhase.STREAMING
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Stop response").assertIsDisplayed().assertIsEnabled()
        assertTrue(compose.onAllNodes(hasContentDescription("Send message")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("composer-model-chip").assertIsNotEnabled()

        assertEquals("Queue", compose.onNodeWithTag("composer-priority-chip").fetchSemanticsNode().config[DisplayedChipTextKey])
        compose.onNodeWithTag("composer-priority-chip").performClick()
        compose.waitForIdle()
        assertEquals("Later", compose.onNodeWithTag("composer-priority-chip").fetchSemanticsNode().config[DisplayedChipTextKey])
        compose.onNodeWithTag("composer-priority-chip").performClick()
        compose.waitForIdle()
        assertEquals("Interrupt", compose.onNodeWithTag("composer-priority-chip").fetchSemanticsNode().config[DisplayedChipTextKey])
    }
}
