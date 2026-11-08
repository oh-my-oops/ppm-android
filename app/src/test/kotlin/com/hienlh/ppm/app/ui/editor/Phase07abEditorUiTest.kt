package com.hienlh.ppm.app.ui.editor

import android.app.Application
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.editor.EditorSessionState
import com.hienlh.ppm.state.editor.MAX_IME_SLICE_CHARS
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class Phase07abEditorUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun nativeCodeEditorCanvas_findReplaceRegexAndUndoRedoWork() {
        val session = EditorSessionState(
            initialText = "const alpha = 1;\nconst beta = 2;\nconst gamma = 3;",
            language = "typescript",
        )

        composeRule.setContent {
            var tick by remember { mutableLongStateOf(0L) }
            var wrap by remember { mutableStateOf(false) }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                NativeCodeEditorCanvas(
                    session = session,
                    editTick = tick,
                    onMutated = { tick++ },
                    isDirty = tick > 0,
                    wrap = wrap,
                    onToggleWrap = { wrap = !wrap },
                )
            }
        }

        composeRule.onNodeWithTag("editor-cursor-status").assertTextContains("Ln 1, Col 1")

        // Open Find & Replace bar, enable regex, replace "const (\w+)" -> "let $1"
        composeRule.onNodeWithTag("editor-toggle-find").performClick()
        composeRule.onNodeWithTag("editor-find-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("editor-find-regex").performClick()
        composeRule.onNodeWithTag("editor-find-input").performTextReplacement("const (\\w+)")
        composeRule.onNodeWithTag("editor-find-count").assertTextContains("1/3")
        composeRule.onNodeWithTag("editor-replace-input").performTextReplacement("let $1")
        composeRule.onNodeWithTag("editor-replace-all").performClick()

        assertEquals("let alpha = 1;\nlet beta = 2;\nlet gamma = 3;", session.buffer.toString())

        // Undo restores original
        composeRule.onNodeWithTag("editor-undo-btn").performClick()
        assertEquals("const alpha = 1;\nconst beta = 2;\nconst gamma = 3;", session.buffer.toString())
    }

    @Test
    fun nativeCodeEditorCanvas_goToLineAndMobileSymbolBarInsertAutoPairs() {
        val session = EditorSessionState(
            initialText = "line1\nline2\nline3",
            language = "typescript",
        )

        composeRule.setContent {
            var tick by remember { mutableLongStateOf(0L) }
            var wrap by remember { mutableStateOf(true) }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                NativeCodeEditorCanvas(
                    session = session,
                    editTick = tick,
                    onMutated = { tick++ },
                    isDirty = false,
                    wrap = wrap,
                    onToggleWrap = { wrap = !wrap },
                )
            }
        }

        // Go to line 3:6
        composeRule.onNodeWithTag("editor-toggle-goto").performClick()
        composeRule.onNodeWithTag("editor-goto-input").performTextReplacement("3:6")
        composeRule.onNodeWithTag("editor-goto-submit").performClick()
        composeRule.onNodeWithTag("editor-cursor-status").assertTextContains("Ln 3, Col 6")

        // Tap '{' on mobile symbol bar -> inserts "{}" at end of line 3
        composeRule.onNodeWithTag("mobile-editor-symbol-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("mobile-key-sym-{").performClick()
        assertEquals("line1\nline2\nline3{}", session.buffer.toString())
    }

    @Test
    fun editorImeBridgeView_createsBoundedInputConnectionAndSupportsTelexComposition() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val hugeText = "abcdef\n".repeat(10_000)
        val session = EditorSessionState(initialText = hugeText, language = "plaintext")
        val view = EditorImeBridgeView(app).apply {
            this.session = session
        }

        val attrs = EditorInfo()
        val ic = view.onCreateInputConnection(attrs)
        assertNotNull(ic)

        // Select all (~70,000 chars) -> getSelectedText must be capped at MAX_IME_SLICE_CHARS (4096)
        session.selectAll()
        val selected = ic!!.getSelectedText(0)
        assertNotNull(selected)
        assertEquals(MAX_IME_SLICE_CHARS, selected!!.length)

        // Reset to empty and test Telex composition via InputConnection
        session.reloadExternalContent("")
        ic.setComposingText("tiê", 1)
        ic.setComposingText("tiếng", 1)
        ic.commitText("tiếng ", 1)
        ic.setComposingText("việt", 1)
        ic.commitText("việt", 1)
        assertEquals("tiếng việt", session.buffer.toString())
    }

    @Test
    fun editableFileCodeView_loadCrlfTypeAndSave_preservesCrlfInRequestBody() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{"written":true}}""")
                    .build(),
            )
            val app = ApplicationProvider.getApplicationContext<Application>()
            val model = AppModel(app).apply {
                api = ApiClient(server.url("/"), "secret")
            }

            composeRule.setContent {
                var wrap by remember { mutableStateOf(true) }
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                    EditableFileCodeView(
                        model = model,
                        project = "app",
                        path = "src/crlf.ts",
                        initialContent = "a\r\nb",
                        initialLanguage = "typescript",
                        wrap = wrap,
                        onToggleWrap = { wrap = !wrap },
                    )
                }
            }

            composeRule.onNodeWithTag("mobile-key-sym-#").performClick()
            composeRule.onNodeWithTag("editor-save-btn").performClick()

            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(recorded)
            val bodyJson = Json.parseToJsonElement(recorded!!.body!!.utf8()).jsonObject
            assertEquals("src/crlf.ts", bodyJson["path"]?.jsonPrimitive?.content)
            assertEquals("#a\r\nb", bodyJson["content"]?.jsonPrimitive?.content)
        } finally {
            server.close()
        }
    }

    @Test
    fun editableFileCodeView_c9_saveError403HaltsAutosaveRetryAndShowsBanner() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(403)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":false,"error":"Read-only path"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{"written":true}}""")
                    .build(),
            )
            val app = ApplicationProvider.getApplicationContext<Application>()
            val model = AppModel(app).apply {
                api = ApiClient(server.url("/"), "secret")
            }

            composeRule.setContent {
                var wrap by remember { mutableStateOf(true) }
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                    EditableFileCodeView(
                        model = model,
                        project = "app",
                        path = "src/locked.ts",
                        initialContent = "const x = 1;",
                        initialLanguage = "typescript",
                        wrap = wrap,
                        onToggleWrap = { wrap = !wrap },
                    )
                }
            }

            composeRule.onNodeWithTag("mobile-key-sym-#").performClick()
            composeRule.onNodeWithTag("editor-save-btn").performClick()

            val firstReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(firstReq)
            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule.onNodeWithTag("editor-save-error-banner").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            // Wait past the 1500ms autosave debounce window: a 403 error must NOT loop-retry automatically
            composeRule.mainClock.advanceTimeBy(2_000)
            composeRule.waitForIdle()
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun aiResourceEditorBody_c11_readsReadOnlyMetadataShowsSaveErrorAndPreservesEditsAcrossSave() {
        val server = MockWebServer()
        server.start()
        try {
            // 1. Read-only tab from metadata["readOnly"] = true
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{"content":"# Skill ReadOnly","editable":true}}""")
                    .build(),
            )
            // 2. Editable tab initial load
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{"content":"hello","editable":true}}""")
                    .build(),
            )
            // 3. First save fails with 500
            server.enqueue(
                MockResponse.Builder()
                    .code(500)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":false,"error":"Disk write failed"}""")
                    .build(),
            )
            // 4. Retry save succeeds with 200
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{"written":true}}""")
                    .build(),
            )

            val app = ApplicationProvider.getApplicationContext<Application>()
            val model = AppModel(app).apply {
                api = ApiClient(server.url("/"), "secret")
            }

            var activeTab by mutableStateOf(
                WorkspaceTab(
                    id = "ai-ro",
                    type = "ai-resource",
                    title = "RO Skill",
                    projectId = "app",
                    metadata = JsonObject(
                        mapOf(
                            "path" to JsonPrimitive("/skills/ro/SKILL.md"),
                            "project" to JsonPrimitive("/repo"),
                            "readOnly" to JsonPrimitive(true),
                            "scope" to JsonPrimitive("user"),
                        ),
                    ),
                ),
            )

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                    AiResourceEditorBody(model = model, tab = activeTab)
                }
            }

            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule.onNodeWithTag("ai-resource-readonly-banner").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }

            // Switch to editable tab
            activeTab = WorkspaceTab(
                id = "ai-rw",
                type = "ai-resource",
                title = "RW Skill",
                projectId = "app",
                metadata = JsonObject(
                    mapOf(
                        "path" to JsonPrimitive("/skills/rw/SKILL.md"),
                        "project" to JsonPrimitive("/repo"),
                        "readOnly" to JsonPrimitive(false),
                        "scope" to JsonPrimitive("user"),
                    ),
                ),
            )
            composeRule.waitForIdle()

            val loadReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(loadReq)
            assertTrue(loadReq!!.url.encodedQuery.orEmpty().contains("project=%2Frepo"))

            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule.onNodeWithTag("mobile-key-sym-#").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }

            // Type '#' and click Save -> triggers 500 error banner
            composeRule.onNodeWithTag("mobile-key-sym-#").performClick()
            composeRule.onNodeWithTag("ai-resource-save-btn").performClick()
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule.onNodeWithTag("ai-resource-save-error-banner").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }

            // Type another '#' and click Save -> succeeds and preserves "#hello" + second "#" = "##hello"
            composeRule.onNodeWithTag("mobile-key-sym-#").performClick()
            composeRule.onNodeWithTag("ai-resource-save-btn").performClick()
            val putReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(putReq)
            val putBody = Json.parseToJsonElement(putReq!!.body!!.utf8()).jsonObject
            assertEquals("##hello", putBody["content"]?.jsonPrimitive?.content)
            assertEquals("/repo", putBody["project"]?.jsonPrimitive?.content)
        } finally {
            server.close()
        }
    }
}
