package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.ui.osexplorer.isOsExplorerTab
import com.hienlh.ppm.app.ui.osexplorer.openOsExplorerTab
import com.hienlh.ppm.app.ui.osexplorer.osExplorerTabPath
import com.hienlh.ppm.app.ui.windows.absorbExplorerWindowsToTabs
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.design.RegexHighlight
import com.hienlh.ppm.protocol.FileContent
import com.hienlh.ppm.protocol.FileSearchAnswer
import com.hienlh.ppm.protocol.FileSearchMatch
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.protocol.FileSearchResult
import com.hienlh.ppm.state.files.FileSearchBackend
import com.hienlh.ppm.state.files.FileSearchRegex
import com.hienlh.ppm.state.files.FileSearchReplacement
import com.hienlh.ppm.state.files.ProjectFileSearch
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowStore
import com.hienlh.ppm.state.workspace.LayoutStorage
import com.hienlh.ppm.state.workspace.PanelLayout
import com.hienlh.ppm.state.workspace.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
@Config(sdk = [34], qualifiers = "w1000dp-h700dp")
class ReplaceAllConfirmTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        installBlankFileIcons()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FakeBackend(
        var answer: FileSearchAnswer,
        private val files: MutableMap<String, String>,
    ) : FileSearchBackend {
        val writes = linkedMapOf<String, String>()

        override suspend fun search(project: String, options: FileSearchOptions): FileSearchAnswer = answer

        override suspend fun read(project: String, path: String): FileContent =
            FileContent(content = files.getValue(path), encoding = "utf-8")

        override suspend fun write(project: String, path: String, content: String) {
            writes[path] = content
            files[path] = content
        }
    }

    private object SimpleRegex : FileSearchRegex {
        override suspend fun validate(options: FileSearchOptions): String? = null

        override suspend fun replace(
            content: String,
            options: FileSearchOptions,
            replacement: String,
        ): FileSearchReplacement {
            if (options.query.isEmpty()) return FileSearchReplacement(content, 0)
            val occurrences = content.windowed(options.query.length, 1).count { it == options.query }
            return FileSearchReplacement(content.replace(options.query, replacement), occurrences)
        }
    }

    @Test
    fun `clicking Replace All shows confirmation naming match and file counts and only replaces after confirming`() {
        val files = linkedMapOf(
            "src/a.kt" to "val old = 1\nval next = old + 1",
            "src/b.kt" to "println(old)",
            "src/c.kt" to "return old",
        )
        val backend = FakeBackend(
            answer = FileSearchAnswer(
                results = listOf(
                    FileSearchResult("src/a.kt", listOf(FileSearchMatch(1, "val old = 1"), FileSearchMatch(2, "val next = old + 1"))),
                    FileSearchResult("src/b.kt", listOf(FileSearchMatch(4, "println(old)"))),
                    FileSearchResult("src/c.kt", listOf(FileSearchMatch(9, "return old"))),
                ),
                total = 4,
            ),
            files = files,
        )
        val controller = ProjectFileSearch(backend, SimpleRegex, scope)
        controller.setProject("app")
        controller.setQuery("old")
        controller.setReplacement("fresh")
        controller.retry()

        val toasts = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                SearchBody(
                    controller = controller,
                    project = "app",
                    onToast = { toasts += it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { controller.state.value.canReplace }
        composeRule.waitForIdle()

        // 1. Clicking Replace All opens the confirmation card and does NOT write any file yet.
        composeRule.onNodeWithText("Replace All").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Replace 4 matches in 3 files?").assertIsDisplayed()
        composeRule.onNodeWithText(REPLACE_ALL_CONFIRM_DETAIL).assertIsDisplayed()
        assertTrue("no file may be written before confirmation", backend.writes.isEmpty())

        // 2. Cancelling dismisses the prompt and leaves all files untouched.
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Replace 4 matches in 3 files?").assertDoesNotExist()
        assertTrue("cancelling Replace All must not write any file", backend.writes.isEmpty())

        // 3. Opening again and confirming executes Replace All across all 3 files.
        composeRule.onNodeWithText("Replace All").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Replace 4 matches in 3 files?").assertIsDisplayed()

        composeRule.onNodeWithTag("replace-all-confirm").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { backend.writes.size == 3 && !controller.state.value.replacing }

        assertEquals(
            mapOf(
                "src/a.kt" to "val fresh = 1\nval next = fresh + 1",
                "src/b.kt" to "println(fresh)",
                "src/c.kt" to "return fresh",
            ),
            backend.writes,
        )
        controller.close()
    }

    @Test
    fun `single match in a single file uses singular wording and mutual exclusion of Whole Word and Regex notifies the user`() {
        val files = linkedMapOf("src/only.kt" to "val token = 1")
        val backend = FakeBackend(
            answer = FileSearchAnswer(
                results = listOf(FileSearchResult("src/only.kt", listOf(FileSearchMatch(1, "val token = 1")))),
                total = 1,
            ),
            files = files,
        )
        val controller = ProjectFileSearch(backend, SimpleRegex, scope)
        controller.setProject("app")
        controller.setQuery("token")
        controller.setReplacement("key")
        controller.retry()

        val toasts = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                SearchBody(
                    controller = controller,
                    project = "app",
                    onToast = { toasts += it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { controller.state.value.canReplace }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Replace All").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Replace 1 match in 1 file?").assertIsDisplayed()
        assertTrue(backend.writes.isEmpty())

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        // Mutual exclusion between Whole Word (Alt+W) and Regular Expression (Alt+R)
        composeRule.onNodeWithContentDescription("Match Whole Word (Alt+W)").performClick()
        composeRule.waitForIdle()
        assertTrue(controller.state.value.options.wholeWord)
        assertFalse(controller.state.value.options.regex)

        composeRule.onNodeWithContentDescription("Use Regular Expression (Alt+R)").performClick()
        composeRule.waitForIdle()
        assertFalse(controller.state.value.options.wholeWord)
        assertTrue(controller.state.value.options.regex)
        assertEquals(listOf("Match Whole Word disabled (incompatible with Regular Expression)"), toasts)

        controller.close()
    }

    @Test
    fun `replacement preview strikes through each match and appends replacement inline`() {
        val preview = searchReplacementPreviewText(
            text = "val old = old",
            highlights = listOf(RegexHighlight(4, 7), RegexHighlight(10, 13)),
            replacement = "new",
            matchHighlight = Color.Yellow,
            deleteColor = Color.Red,
            deleteBackground = Color.Red.copy(alpha = 0.2f),
            insertColor = Color.Green,
            insertBackground = Color.Green.copy(alpha = 0.2f),
        )
        assertEquals("val oldnew = oldnew", preview.text)
        assertEquals(2, preview.spanStyles.count { it.item.textDecoration == TextDecoration.LineThrough })
        assertEquals(2, preview.spanStyles.count { it.item.color == Color.Green })
    }

    @Test
    fun `OS File Explorer on tablet opens as a workspace tab and absorbs floating Explorer windows`() {
        val workspace = WorkspaceStore(
            storage = object : LayoutStorage {
                override fun load(project: String): PanelLayout? = null
                override fun save(project: String, layout: PanelLayout) {}
            },
            isMobile = { false },
        )
        workspace.switchProject("ppm")

        val tabId = openOsExplorerTab(workspace, "/home/thawng/Projects/ppm", projectId = "ppm")
        val opened = workspace.state.value.activeGridTab
        requireNotNull(opened)
        assertEquals(tabId, opened.id)
        assertTrue("tab must be recognized as OS File Explorer tab", isOsExplorerTab(opened))
        assertEquals("ppm", opened.title)
        assertEquals("/home/thawng/Projects/ppm", osExplorerTabPath(opened))

        // If a WindowKind.Explorer window is opened on a tablet, absorbExplorerWindowsToTabs promotes it to a tab and closes the window.
        val windowStore = WindowStore(
            object : KeyValueStore {
                private val map = mutableMapOf<String, String>()
                override fun get(key: String): String? = map[key]
                override fun put(key: String, value: String?) {
                    if (value == null) map.remove(key) else map[key] = value
                }
            },
            "windows",
        )
        windowStore.open(WindowKind.Explorer, buildJsonObject { put("path", "/var/log") })
        assertEquals(1, windowStore.state.value.windows.size)

        val remaining = absorbExplorerWindowsToTabs(windowStore, windowStore.state.value) { path ->
            openOsExplorerTab(workspace, path, projectId = "ppm", newInstance = true)
        }
        assertTrue("no floating Explorer window remains", remaining.isEmpty())
        assertTrue("windowStore has closed the Explorer window", windowStore.state.value.windows.isEmpty())
        val activeAfter = workspace.state.value.activeGridTab
        requireNotNull(activeAfter)
        assertTrue(isOsExplorerTab(activeAfter))
        assertEquals("log", activeAfter.title)
        assertEquals("/var/log", osExplorerTabPath(activeAfter))
    }
}
