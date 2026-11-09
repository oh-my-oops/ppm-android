package com.hienlh.ppm.app.ui

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.SlashItem
import com.hienlh.ppm.protocol.SlashItemsPayload
import com.hienlh.ppm.state.FileIndexStore
import com.hienlh.ppm.state.SlashItemsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ports `tests/unit/web/composer-pickers.test.tsx`: floating vs static `PickerPanel`, kind filter
 * tabs (`Commands` covering built-ins, empty tab staying open without picking, non-matching query
 * closing), tinted kind tiles, `HighlightMatch`, `slashScopeLabel`, 2-line selected description,
 * `PickerKeyHints` responsive visibility, `FilePicker` match counts, `StartEllipsisText`, 44dp
 * narrow/mobile wrapping rows, and keyboard navigation (`ArrowDown`/`ArrowUp`/`Enter`/`Tab`/`Escape`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class ComposerPickersFooterTest {

    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var model: AppModel

    private val slashFixture = listOf(
        SlashItem(
            type = "skill",
            name = "ak-research",
            description = "Research technical solutions.",
            argumentHint = "[topic]",
            scope = "user",
        ),
        SlashItem(
            type = "agent",
            name = "Explore",
            description = "Fast read-only search.",
            scope = "bundled",
        ),
        SlashItem(
            type = "command",
            name = "deploy-staging",
            description = "Deploy to staging.",
            scope = "project",
        ),
        SlashItem(
            type = "builtin",
            name = "compact",
            description = "Summarize the conversation.",
            scope = "bundled",
        ),
    )

    @Before
    fun setUp() {
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val api = ApiClient(server.url("/"), "secret")
        model.api = api
        val slashStore = SlashItemsStore(scope)
        slashStore.seed(SlashItemsPayload(slashFixture, recentNames = emptyList()), "p", "claude")
        model.slashItems = slashStore
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun keyDown(code: Int): KeyEvent = KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, code))

    private fun ChatTab.typeDraft(text: String) {
        draft.edit {
            replace(0, length, text)
            selection = TextRange(text.length)
        }
        updatePickers(text, text.length)
    }

    @Test
    fun slashPicker_supportsFloatingAndStaticVariants_kindFilters_emptyTab_andNoMatchClose() {
        val tab = ChatTab("t1", session = null, project = "p", providerId = "claude")
        tab.typeDraft("/")
        var variant by mutableStateOf(PickerVariant.FLOATING)
        var pickersRef: ComposerPickers? = null

        compose.setContent {
            PpmThemeProvider(BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.width(560.dp)) {
                    val pickers = rememberComposerPickers(model, tab)
                    pickersRef = pickers
                    ComposerPickerList(model, tab, pickers, narrow = false, variant = variant)
                }
            }
        }
        compose.waitForIdle()

        // Floating variant by default, with key hints visible on wide tablet.
        assertEquals(
            "floating",
            compose.onNodeWithTag("composer-picker").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        assertEquals(
            "Skills and commands",
            compose.onNodeWithTag("composer-picker-listbox").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single(),
        )
        compose.onNodeWithTag("picker-key-hints").assertIsDisplayed()

        // Static variant reports static stateDescription.
        variant = PickerVariant.STATIC
        compose.waitForIdle()
        assertEquals(
            "static",
            compose.onNodeWithTag("composer-picker").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        variant = PickerVariant.FLOATING
        compose.waitForIdle()

        // Filter tabs: All, Skills, Commands (covers built-ins), Agents.
        compose.onNodeWithTag("slash-kind-tab:all").assertIsSelected()
        compose.onNodeWithTag("slash-kind-tab:agent").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("slash-kind-tab:agent").assertIsSelected()
        assertEquals(1, compose.onAllNodesWithTag("slash-picker-row:0").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("slash-picker-row:1").fetchSemanticsNodes().size)
        compose.onNodeWithText("/Explore").assertIsDisplayed()

        compose.onNodeWithTag("slash-kind-tab:command").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("slash-kind-tab:command").assertIsSelected()
        compose.onNodeWithText("/deploy-staging").assertIsDisplayed()
        compose.onNodeWithText("/compact").assertIsDisplayed()

        // Query matching only in Skills keeps Commands tab open with empty message, and Enter picks nothing.
        compose.runOnIdle { tab.typeDraft("/research") }
        compose.waitForIdle()
        compose.onNodeWithText("No skill or command matches.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("slash-picker-row:0").fetchSemanticsNodes().size)
        compose.runOnIdle {
            assertTrue(pickersRef!!.key(keyDown(AndroidKeyEvent.KEYCODE_ENTER)))
            assertTrue(pickersRef!!.send())
        }
        assertEquals("/research", tab.draft.text.toString())

        // Query matching nothing anywhere closes the picker completely (renders nothing).
        compose.runOnIdle { tab.typeDraft("/zzzzzzzzzzqqqq") }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("composer-picker").fetchSemanticsNodes().size)
    }

    @Test
    fun slashPickerRow_highlightsMatch_tintsTiles_clampsDescriptions_andHidesHintsWhenNarrowOrMobile() {
        val withCustomArt = slashFixture + listOf(
            SlashItem(
                type = "skill",
                name = "imagegen",
                description = "Generate images.",
                invokeSigil = "$",
                iconDataUri = "data:image/png;base64,AA==",
            ),
        )
        model.slashItems!!.seed(SlashItemsPayload(withCustomArt, recentNames = listOf("compact", "Explore")), "p", "claude")

        val tab = ChatTab("t2", session = null, project = "p", providerId = "claude")
        tab.typeDraft("/")
        var mobile by mutableStateOf(false)
        var narrow by mutableStateOf(false)
        var pickersRef: ComposerPickers? = null
        var accentColor = androidx.compose.ui.graphics.Color.Unspecified

        compose.setContent {
            PpmThemeProvider(BuiltInThemes.auroraDark, mobile = mobile) {
                accentColor = com.hienlh.ppm.design.LocalPpmTokens.current.accent.color
                Box(Modifier.width(if (narrow) 360.dp else 560.dp)) {
                    val pickers = rememberComposerPickers(model, tab)
                    pickersRef = pickers
                    ComposerPickerList(model, tab, pickers, narrow = narrow)
                }
            }
        }
        compose.waitForIdle()

        // Recents appear first under RECENT, rest under ALL.
        compose.onNodeWithText("RECENT").assertIsDisplayed()
        compose.onNodeWithText("ALL").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh skill list").assertIsDisplayed()

        // Selected row (index 0: compact) clamps description to 2 lines; unselected row (index 1: Explore) to 1 line.
        assertEquals(
            2,
            compose.onNodeWithTag("slash-desc:compact", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SlashDescMaxLinesKey),
        )
        assertEquals(
            1,
            compose.onNodeWithTag("slash-desc:Explore", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SlashDescMaxLinesKey),
        )

        // Tile kinds & runtime data-URI icon.
        assertEquals(
            "builtin",
            compose.onNodeWithTag("slash-tile:compact", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SlashTileKindKey),
        )
        assertEquals(
            "agent",
            compose.onNodeWithTag("slash-tile:Explore", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SlashTileKindKey),
        )
        assertEquals(
            "skill",
            compose.onNodeWithTag("slash-tile:ak-research", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SlashTileKindKey),
        )
        assertEquals(
            "data:image/png;base64,AA==",
            compose.onNodeWithTag("slash-data-uri-icon", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )

        // Argument hint `[topic]` and key hints are shown on wide tablet, hidden when narrow or mobile.
        assertEquals(1, compose.onAllNodesWithTag("slash-arg-hint:ak-research", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("picker-key-hints").fetchSemanticsNodes().size)

        narrow = true
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("slash-arg-hint:ak-research", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("picker-key-hints").fetchSemanticsNodes().size)

        narrow = false
        mobile = true
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("slash-arg-hint:ak-research", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("picker-key-hints").fetchSemanticsNodes().size)
        // Mobile filter tab touch height >= 44dp.
        val tabBounds = compose.onNodeWithTag("slash-kind-tab:all").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("Expected mobile tab height >= 44dp, got ${tabBounds.height / density}dp", tabBounds.height / density >= 43.5f)

        mobile = false
        compose.runOnIdle { tab.typeDraft("/research") }
        compose.waitForIdle()

        // Match highlight marks "research" in "/ak-research" with SemiBold + accent color and no background.
        val nameTexts = compose.onNodeWithTag("slash-name:ak-research", useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!
        val annotated = nameTexts.single()
        assertEquals("/ak-research", annotated.text)
        val marks = annotated.getStringAnnotations("mark", 0, annotated.length)
        assertEquals(1, marks.size)
        assertEquals("research", marks.single().item)
        val style = annotated.spanStyles.single { it.start == marks.single().start && it.end == marks.single().end }.item
        assertEquals(FontWeight.SemiBold, style.fontWeight)
        assertEquals(accentColor, style.color)
        assertEquals(androidx.compose.ui.graphics.Color.Unspecified, style.background)

        // Keyboard navigation: ArrowDown, Tab, Escape.
        compose.runOnIdle { tab.typeDraft("/") }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(pickersRef!!.key(keyDown(AndroidKeyEvent.KEYCODE_DPAD_DOWN)))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("slash-picker-row:1").assertIsSelected()
        compose.runOnIdle {
            assertTrue(pickersRef!!.key(keyDown(AndroidKeyEvent.KEYCODE_TAB)))
        }
        compose.waitForIdle()
        // Row 1 is "Explore" (an agent), so picking it sets tab.agent = "Explore" and closes slashQuery.
        assertEquals("Explore", tab.agent)
        assertNull(tab.slashQuery)

        compose.runOnIdle { tab.typeDraft("/") }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(pickersRef!!.key(keyDown(AndroidKeyEvent.KEYCODE_ESCAPE)))
        }
        compose.waitForIdle()
        assertNull(tab.slashQuery)
    }

    @Test
    fun filePicker_showsMatchCount_startEllipsizesDirectory_wrapsOnNarrow_andHandlesKeyboard() {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """
                {"ok":true,"data":[
                  {"path":"src/very/deeply/nested/web/components/chat/chat-tab.tsx","name":"chat-tab.tsx","type":"file"},
                  {"path":"src/web/components/chat","name":"chat","type":"directory"},
                  {"path":"README.md","name":"README.md","type":"file"}
                ]}
                """.trimIndent(),
            ).build(),
        )
        val fileStore = FileIndexStore(model.api!!, scope)
        runBlocking { fileStore.load("p").join() }
        model.fileIndex = fileStore

        val tab = ChatTab("t3", session = null, project = "p", providerId = "claude")
        tab.typeDraft("@chat")

        var narrow by mutableStateOf(false)
        var paneWidth by mutableStateOf(220.dp)
        var pickersRef: ComposerPickers? = null

        compose.setContent {
            PpmThemeProvider(BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.width(paneWidth)) {
                    val pickers = rememberComposerPickers(model, tab)
                    pickersRef = pickers
                    ComposerPickerList(model, tab, pickers, narrow = narrow)
                }
            }
        }
        compose.waitForIdle()

        // Header shows "Files" and "2 matches for “chat”".
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onNodeWithText("2 matches for “chat”").assertIsDisplayed()

        // Row 0 is a file, row 1 is a folder (chat/).
        assertEquals(
            "file",
            compose.onNodeWithTag("file-icon:0", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        assertEquals(
            "directory",
            compose.onNodeWithTag("file-icon:1", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        val fileName = compose.onNodeWithTag("file-name:0", useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.single()
        assertEquals("chat-tab.tsx", fileName.text)
        assertEquals("chat", fileName.getStringAnnotations("mark", 0, fileName.length).single().item)

        // At 300dp width, the long directory overflows and truncates from the start ("….../chat/"), keeping "chat/" at the end!
        val truncatedDir = compose.onAllNodesWithTag("start-ellipsis", useUnmergedTree = true)[0]
            .fetchSemanticsNode().config.getOrNull(StartEllipsisShownKey)!!
        assertTrue("Expected start-ellipsis truncation, got: $truncatedDir", truncatedDir.startsWith("…") && truncatedDir.endsWith("chat/"))

        // At 700dp width, the full directory fits without an ellipsis.
        paneWidth = 700.dp
        compose.waitForIdle()
        val fullDir = compose.onAllNodesWithTag("start-ellipsis", useUnmergedTree = true)[0]
            .fetchSemanticsNode().config.getOrNull(StartEllipsisShownKey)!!
        assertEquals("src/very/deeply/nested/web/components/chat/", fullDir)

        // On wide layout (!narrow), directory sits beside basename on the same row; on narrow, directory wraps below basename and row >= 44dp.
        val wideNameBounds = compose.onNodeWithTag("file-name:0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val wideDirBounds = compose.onNodeWithTag("file-dir:0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Expected directory to the right of name on wide row", wideDirBounds.left >= wideNameBounds.right - 1f)

        narrow = true
        paneWidth = 360.dp
        compose.runOnIdle { tab.typeDraft("@") }
        compose.waitForIdle()
        compose.onNodeWithText("3 matches").assertIsDisplayed()

        val narrowRowBounds = compose.onNodeWithTag("file-picker-row:0").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("Expected narrow file row >= 44dp, got ${narrowRowBounds.height / density}dp", narrowRowBounds.height / density >= 43.5f)
        val narrowNameBounds = compose.onNodeWithTag("file-name:0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val narrowDirBounds = compose.onNodeWithTag("file-dir:0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Expected directory wrapped below name on narrow row", narrowDirBounds.top >= narrowNameBounds.bottom - 1f)

        // Keyboard navigation: ArrowDown -> Enter picks row 1 ("src/web/components/chat"), Escape closes.
        compose.runOnIdle {
            assertTrue(pickersRef!!.key(keyDown(AndroidKeyEvent.KEYCODE_DPAD_DOWN)))
            assertTrue(pickersRef!!.key(keyDown(AndroidKeyEvent.KEYCODE_ENTER)))
        }
        compose.waitForIdle()
        assertEquals("@src/web/components/chat ", tab.draft.text.toString())
        assertFalse(pickersRef!!.isShowing)
    }
}
