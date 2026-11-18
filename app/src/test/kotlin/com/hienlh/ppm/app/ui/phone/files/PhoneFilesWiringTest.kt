package com.hienlh.ppm.app.ui.phone.files

import android.app.Application
import android.app.Activity
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.performImeAction
import com.hienlh.ppm.app.ui.editor.SharedCompareStore
import com.hienlh.ppm.state.workspace.LayoutStorage
import com.hienlh.ppm.state.workspace.PanelLayout
import com.hienlh.ppm.state.workspace.WorkspaceStore
import java.io.ByteArrayInputStream
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.state.FileIndexStore
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.files.FileTreeBackend
import com.hienlh.ppm.state.files.ProjectFileTree
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The phone Files area against a server that answers as PPM does: the file tab reads, edits and saves through the
 * old editor's own state, and the tree's Go to file, overflow, long-press and file operations reach the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneFilesWiringTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val requests = mutableListOf<Recorded>()
    private lateinit var app: Application
    private lateinit var model: AppModel
    private lateinit var api: ApiClient

    @Volatile private var writeRefusals = 0

    private data class Recorded(val method: String, val path: String, val query: String?, val body: String)

    @Before
    fun connect() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                synchronized(requests) {
                    requests += Recorded(request.method ?: "GET", path, request.url.encodedQuery, request.body?.utf8().orEmpty())
                }
                return when {
                    path.endsWith("/files/write") && writeRefusals > 0 -> {
                        writeRefusals--
                        MockResponse.Builder().code(403).addHeader("Content-Type", "application/json")
                            .body("""{"ok":false,"error":"Read-only file"}""").build()
                    }
                    // The search host's reply is bare JSON, no envelope.
                    path.endsWith("/files/search") -> MockResponse.Builder().addHeader("Content-Type", "application/json")
                        .body("""{"results":[{"file":"src/Main.kt","matches":[{"line":1,"column":0,"length":3,"before":"","cutBefore":false,"text":"fun","after":" main() {}"}]}],"total":1,"files":1,"limitHit":false,"engine":"rg"}""")
                        .build()
                    path.endsWith("/files/read") && request.url.queryParameter("path") == "data/users.csv" ->
                        ok("""{"content":"name,score\nalpha,1\nbeta,2\n","path":"data/users.csv"}""")
                    path.endsWith("/files/read") -> ok("""{"content":"fun main() {}\n","path":"src/Main.kt"}""")
                    path.endsWith("/files/move") -> ok("""{"moved":{"from":"README.md","to":"src/README.md"}}""")
                    path.endsWith("/files/upload") -> ok("""{"uploaded":[{"name":"notes.txt","path":"src/notes.txt","size":5}]}""")
                    path.endsWith("/files/list-batch") -> ok("""[{"path":"","entries":$ROOT_LISTING},{"path":"src","entries":$SRC_LISTING}]""")
                    path.endsWith("/files/list") ->
                        ok(if (request.url.queryParameter("path").orEmpty() == "src") SRC_LISTING else ROOT_LISTING)
                    path.endsWith("/files/index") ->
                        ok("""[{"path":"src","name":"src","type":"directory"},{"path":"src/Main.kt","name":"Main.kt","type":"file"},{"path":"README.md","name":"README.md","type":"file"}]""")
                    else -> ok("{}")
                }
            }
        }
        server.start()
        app = ApplicationProvider.getApplicationContext()
        model = AppModel(app)
        api = ApiClient(server.url("/"), "secret")
        model.api = api
        stateOf<String?>("selectedProject").value = "ppm"
        stateOf<List<ProjectInfo>>("projects").value = listOf(ProjectInfo("ppm", ROOT))
        stateOf<FileMutations?>("fileMutations").value = FileMutations(api)
    }

    @After
    fun disconnect() = server.close()

    @Suppress("UNCHECKED_CAST")
    private fun <T> stateOf(property: String): MutableState<T> = ReflectionHelpers.getField(model, property + "\$delegate")

    private fun ok(data: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body("""{"ok":true,"data":$data}""")
        .build()

    private fun recorded(method: String, suffix: String) = synchronized(requests) { requests.filter { it.method == method && it.path.endsWith(suffix) } }

    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun textShown(text: String) = composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private val mainKtTab = WorkspaceTab(
        id = "editor:src/Main.kt",
        type = "editor",
        title = "Main.kt",
        projectId = "ppm",
        metadata = buildJsonObject {
            put("filePath", JsonPrimitive("src/Main.kt"))
            put("projectName", JsonPrimitive("ppm"))
        },
    )

    @Test
    fun fileTab_readsTheFile_namesItsLanguage_andSaveWritesTheEdit() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneFileTabHost(model = model, tab = mainKtTab)
            }
        }
        composeRule.waitUntil(5_000) { textShown("Kotlin · Ln 1, Col 1") }
        assertEquals(1, recorded("GET", "/files/read").size)
        assertFalse("The old fake label must be gone", textShown("TypeScript React"))

        // Type with the editor's own key row: the tab turns dirty and the phone's Save is live.
        writeRefusals = 1
        composeRule.onNodeWithTag("mobile-key-sym-#").performScrollTo().performClick()
        val saveButton = composeRule.onNodeWithTag("phone-editor-save-button", useUnmergedTree = true)
        composeRule.waitUntil(5_000) {
            saveButton.fetchSemanticsNode().config[SemanticsProperties.ContentDescription] == listOf("Save")
        }
        assertTrue(textShown("Edited"))

        // The first Save is refused: the bottom card says so with the server's own words, and Save stays live.
        saveButton.performClick()
        composeRule.waitUntil(5_000) { recorded("PUT", "/files/write").size == 1 && textShown("Read-only file") }
        assertTrue(textShown("Failed to save file"))
        assertEquals(listOf("Save"), saveButton.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])

        // Save again: written, and the card is back to the file's language and cursor.
        saveButton.performClick()
        composeRule.waitUntil(5_000) { recorded("PUT", "/files/write").size == 2 && textShown("Kotlin · Ln 1") }
        val write = recorded("PUT", "/files/write").last()
        assertEquals("/api/project/ppm/files/write", write.path)
        assertTrue(write.body, write.body.contains("\"content\":\"#fun main() {}"))
        assertTrue(write.body, write.body.contains("\"path\":\"src/Main.kt\""))
        assertFalse(textShown("Failed to save file"))
        assertEquals(
            listOf("Saved — nothing to save"),
            saveButton.fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
    }

    @Test
    fun fileTab_overflowCopiesThePath_downloadsThroughTheSystemPicker_andReloadsFromDisk() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneFileTabHost(model = model, tab = mainKtTab)
            }
        }
        composeRule.waitUntil(5_000) { textShown("Kotlin · Ln") }

        composeRule.onNodeWithTag("phone-breadcrumb-more", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-editor-overflow-sheet") }
        composeRule.onNodeWithTag("phone-editor-overflow-copy_path", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("src/Main.kt", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(model.toasts.any { it.message.title == "Copied path" })

        composeRule.onNodeWithTag("phone-breadcrumb-more", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-editor-overflow-sheet") }
        composeRule.onNodeWithTag("phone-editor-overflow-download", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("Download opens the system's Create Document screen", started)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started!!.action)
        assertEquals("Main.kt", started.getStringExtra(Intent.EXTRA_TITLE))

        val reads = recorded("GET", "/files/read").size
        composeRule.onNodeWithTag("phone-breadcrumb-more", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-editor-overflow-sheet") }
        composeRule.onNodeWithTag("phone-editor-overflow-reload_from_disk", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { recorded("GET", "/files/read").size > reads }
    }

    @Test
    fun fileTab_breadcrumbListsTheFolderFromTheServer() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneFileTabHost(model = model, tab = mainKtTab)
            }
        }
        composeRule.waitUntil(5_000) { textShown("Kotlin · Ln") }
        composeRule.onNodeWithText("src", useUnmergedTree = true).performClick()
        // The segment's own folder is listed: src sits in the project root, beside README.md.
        composeRule.waitUntil(5_000) { shown("phone-breadcrumb-siblings-sheet") && textShown("README.md") }
        assertTrue(synchronized(requests) { requests.any { it.path.endsWith("/files/list") && it.query == "path=" } })
    }

    private fun treeScreen(onActivity: (ComponentActivity) -> Unit = {}) {
        val tree = ProjectFileTree(FileTreeBackend.api(api), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        composeRule.setContent {
            remember(tree) { stateOf<ProjectFileTree?>("fileTree").value = tree }
            val activity = LocalContext.current as ComponentActivity
            remember(activity) { onActivity(activity) }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneFilesTreeScreen(model = model)
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-tree-row-README.md") }
    }

    private fun longPress(path: String) {
        composeRule.onNodeWithTag("phone-tree-row-$path").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitUntil(5_000) { shown("phone-tree-long-press-sheet") }
    }

    private fun sheetAction(id: String) {
        composeRule.onNodeWithTag("phone-tree-action-$id", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(5_000) { !shown("phone-tree-long-press-sheet") }
    }

    @Test
    fun tree_overflowRefreshesTheTree_andLongPressDownloads() {
        treeScreen()
        assertFalse("No recent files, no Recent chip", shown("phone-files-recent-chip"))
        assertTrue("Go to file is offered", shown("phone-files-go-to-file"))

        // Overflow: Refresh lists the tree again.
        val listsBefore = synchronized(requests) { requests.count { it.path.contains("/files/list") } }
        composeRule.onNodeWithTag("phone-breadcrumb-more", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-files-overflow-sheet") }
        composeRule.onNodeWithTag("phone-files-overflow-refresh", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { synchronized(requests) { requests.count { it.path.contains("/files/list") } } > listsBefore }

        // Long-press a file → Download goes to the system's Create Document screen, not to the editor.
        longPress("README.md")
        sheetAction("download")
        composeRule.waitForIdle()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started?.action)
        assertEquals("README.md", started?.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun tree_newFileFromTheOverflow_renameAndDeleteFromTheLongPressSheet_reachTheServer() {
        treeScreen()

        // ⋮ → New File: a name typed in the tree, sent as the web's tree sends it.
        composeRule.onNodeWithTag("phone-breadcrumb-more", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-files-overflow-sheet") }
        assertTrue(shown("phone-files-overflow-new_folder"))
        assertTrue(shown("phone-files-overflow-upload"))
        assertFalse("nothing cut or copied, no Paste", shown("phone-files-overflow-paste"))
        composeRule.onNodeWithTag("phone-files-overflow-new_file", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-tree-inline-input") }
        composeRule.onNodeWithContentDescription("New name").performTextInput("notes.md")
        composeRule.onNodeWithContentDescription("New name").performImeAction()
        composeRule.waitUntil(5_000) { recorded("POST", "/files/create").isNotEmpty() && !shown("phone-tree-inline-input") }
        val create = recorded("POST", "/files/create").single()
        assertEquals("/api/project/ppm/files/create", create.path)
        assertTrue(create.body, create.body.contains("\"path\":\"notes.md\"") && create.body.contains("\"type\":\"file\""))

        // The sheet offers what the web's tree menu does, and says nothing about a tablet.
        longPress("README.md")
        for (id in listOf("open", "cut", "copy", "rename", "delete", "copy_path", "copy_full_path", "download", "select_for_compare")) {
            assertTrue("$id on a file", shown("phone-tree-action-$id"))
        }
        assertFalse(textShown("on a tablet"))
        sheetAction("rename")
        composeRule.waitUntil(5_000) { shown("phone-tree-inline-input") }
        composeRule.onNodeWithContentDescription("Rename README.md").performTextReplacement("GUIDE.md")
        composeRule.onNodeWithContentDescription("Rename README.md").performImeAction()
        composeRule.waitUntil(5_000) { recorded("POST", "/files/rename").isNotEmpty() && !shown("phone-tree-inline-input") }
        val rename = recorded("POST", "/files/rename").single()
        assertTrue(rename.body, rename.body.contains("\"oldPath\":\"README.md\"") && rename.body.contains("\"newPath\":\"GUIDE.md\""))

        // Delete asks first, as the tablet's tree does.
        longPress("src")
        for (id in listOf("new_file", "new_folder", "upload", "open_in_file_explorer", "open_in_terminal", "cut", "copy", "rename", "delete")) {
            assertTrue("$id on a folder", shown("phone-tree-action-$id"))
        }
        assertTrue(textShown("Download as Zip"))
        sheetAction("delete")
        composeRule.waitUntil(5_000) { textShown("Delete Folder") }
        assertTrue(recorded("DELETE", "/files/delete").isEmpty())
        composeRule.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { recorded("DELETE", "/files/delete").isNotEmpty() && !textShown("Delete Folder") }
        assertTrue(recorded("DELETE", "/files/delete").single().body.contains("\"path\":\"src\""))
    }

    @Test
    fun tree_cutAndPasteMovesAFile_andCompareOpensADiffOfTheTwoFiles() {
        val store = WorkspaceStore(object : LayoutStorage {
            override fun load(project: String): PanelLayout? = null
            override fun save(project: String, layout: PanelLayout) = Unit
        }, isMobile = { true })
        store.switchProject("ppm")
        ReflectionHelpers.setField(model, "workspace", store)
        SharedCompareStore.store.clearSelection()
        treeScreen()

        // Cut README.md, then Paste on src: a move into the folder.
        longPress("README.md")
        sheetAction("cut")
        longPress("src")
        sheetAction("paste")
        composeRule.waitUntil(5_000) { recorded("POST", "/files/move").isNotEmpty() }
        val move = recorded("POST", "/files/move").single()
        assertTrue(move.body, move.body.contains("\"source\":\"README.md\"") && move.body.contains("\"destination\":\"src/README.md\""))

        // Select README.md for compare, then compare src/Main.kt with it.
        longPress("README.md")
        assertFalse("nothing selected yet", shown("phone-tree-action-compare_with_selected"))
        sheetAction("select_for_compare")
        composeRule.waitUntil(5_000) { shown("phone-tree-row-src/Main.kt") }
        longPress("src/Main.kt")
        assertTrue(textShown("Compare with Selected (README.md)"))
        sheetAction("compare_with_selected")
        val diff = store.state.value.panels.values.flatMap { it.tabs }.single { it.type == "git-diff" }
        assertEquals("README.md", diff.metaString("file1"))
        assertEquals("src/Main.kt", diff.metaString("file2"))
        assertEquals("README.md ↔ Main.kt", diff.title)
        assertEquals(null, SharedCompareStore.store.selection)
    }

    @Test
    fun tree_uploadFilesPicksDocuments_andUploadsThemIntoTheFolder() {
        var activity: ComponentActivity? = null
        treeScreen { activity = it }
        longPress("src")
        sheetAction("upload")
        composeRule.waitForIdle()
        val shadow = shadowOf(activity!!)
        val picker = shadow.nextStartedActivityForResult
        assertNotNull("Upload Files… opens the system's document picker", picker)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action)
        assertTrue(picker.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))

        val picked = Uri.parse("content://picked/notes.txt")
        shadowOf(app.contentResolver).registerInputStream(picked, ByteArrayInputStream("hello".toByteArray()))
        shadow.receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(picked))
        composeRule.waitUntil(5_000) { recorded("POST", "/files/upload").isNotEmpty() && textShown("Uploaded notes.txt.") }
        val upload = recorded("POST", "/files/upload").single()
        assertEquals("/api/project/ppm/files/upload", upload.path)
        assertTrue(upload.body, upload.body.contains("notes.txt") && upload.body.contains("hello"))
        assertTrue(upload.body, upload.body.contains("name=\"targetDir\"") && upload.body.contains("\r\n\r\nsrc\r\n"))
    }

    @Test
    fun tree_emptyProjectOffersNewFileAndNewFolder() {
        val empty = MockWebServer()
        empty.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) {
                    requests += Recorded(request.method ?: "GET", request.url.encodedPath, request.url.encodedQuery, request.body?.utf8().orEmpty())
                }
                return when {
                    request.url.encodedPath.endsWith("/files/list-batch") -> ok("""[{"path":"","entries":[]}]""")
                    request.url.encodedPath.endsWith("/files/list") -> ok("[]")
                    else -> ok("{}")
                }
            }
        }
        empty.start()
        try {
            api = ApiClient(empty.url("/"), "secret")
            model.api = api
            stateOf<FileMutations?>("fileMutations").value = FileMutations(api)
            val tree = ProjectFileTree(FileTreeBackend.api(api), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
            composeRule.setContent {
                remember(tree) { stateOf<ProjectFileTree?>("fileTree").value = tree }
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                    PhoneFilesTreeScreen(model = model)
                }
            }
            composeRule.waitUntil(5_000) { shown("phone-files-empty") }
            assertTrue(textShown("Empty project."))
            assertFalse(textShown("on a tablet"))
            composeRule.onNodeWithText("New Folder", useUnmergedTree = true).performClick()
            composeRule.waitUntil(5_000) { shown("phone-tree-inline-input") }
            composeRule.onNodeWithContentDescription("New name").performTextInput("docs")
            composeRule.onNodeWithContentDescription("New name").performImeAction()
            composeRule.waitUntil(5_000) { recorded("POST", "/files/create").isNotEmpty() }
            assertTrue(recorded("POST", "/files/create").single().body.contains("\"type\":\"directory\""))
        } finally {
            empty.close()
        }
    }

    @Test
    fun fileTab_aCsvShowsInTheTabletsTableViewer() {
        val csvTab = WorkspaceTab(
            id = "editor:data/users.csv",
            type = "editor",
            title = "users.csv",
            projectId = "ppm",
            metadata = buildJsonObject {
                put("filePath", JsonPrimitive("data/users.csv"))
                put("projectName", JsonPrimitive("ppm"))
            },
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneFileTabHost(model = model, tab = csvTab)
            }
        }
        composeRule.waitUntil(5_000) { textShown("beta") }
        assertTrue(shown("phone-file-viewer"))
        assertFalse("not the code editor", shown("phone-editor-save-button"))
        assertFalse(textShown("on a tablet"))
    }

    @Test
    fun filesArea_problemsAndAFileExplorerTabShowInPlaceOfAFile_andASearchTabOpensSearch() {
        com.hienlh.ppm.app.ui.files.installBlankFileIcons()
        var tab by mutableStateOf<WorkspaceTab?>(WorkspaceTab(id = "problems", type = "problems", title = "Problems"))
        val tree = ProjectFileTree(FileTreeBackend.api(api), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        composeRule.setContent {
            remember(tree) { stateOf<ProjectFileTree?>("fileTree").value = tree }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                com.hienlh.ppm.app.ui.phone.PhoneFilesAreaHost(model = model, tab = tab, nav = com.hienlh.ppm.app.ui.phone.PhoneNav())
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-files-panel-problems") }
        assertTrue(textShown("No problems have been detected in the open files."))

        composeRule.runOnIdle { tab = WorkspaceTab(id = "explorer", type = "os-explorer", title = "Home") }
        composeRule.waitUntil(5_000) { shown("phone-files-panel-os-explorer") }

        composeRule.runOnIdle { tab = WorkspaceTab(id = "search", type = "search", title = "Search") }
        composeRule.waitUntil(5_000) { shown("phone-search-screen") }
        assertFalse(textShown("on a tablet"))
    }

    @Test
    fun tree_offlineBannerCountsFromTheRealDisconnect() {
        val tree = ProjectFileTree(FileTreeBackend.api(api), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        composeRule.setContent {
            remember(tree) { stateOf<ProjectFileTree?>("fileTree").value = tree }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneFilesTreeScreen(model = model)
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-tree-row-README.md") }
        assertFalse(shown("phone-files-offline-banner"))
        composeRule.runOnIdle { stateOf<Boolean>("connectionLost").value = true }
        composeRule.waitUntil(5_000) { shown("phone-files-offline-banner") }
        assertTrue(textShown("Offline for 0 s"))
        composeRule.runOnIdle { stateOf<Boolean>("connectionLost").value = false }
        composeRule.waitUntil(5_000) { !shown("phone-files-offline-banner") }
    }

    @Test
    fun filesArea_searchInFilesOpensTheSearchScreen_whichSearchesTheServer_andBackReturnsToTheTree() {
        com.hienlh.ppm.app.ui.files.installBlankFileIcons()
        val tree = ProjectFileTree(FileTreeBackend.api(api), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        composeRule.setContent {
            remember(tree) { stateOf<ProjectFileTree?>("fileTree").value = tree }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                com.hienlh.ppm.app.ui.phone.PhoneFilesAreaHost(model = model, tab = null, nav = com.hienlh.ppm.app.ui.phone.PhoneNav())
            }
        }
        composeRule.waitUntil(5_000) { shown("phone-files-search-chip") }
        composeRule.onNodeWithTag("phone-files-search-chip", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-search-screen") }
        composeRule.onNodeWithTag("phone-search-query").performTextInput("fun")
        composeRule.waitUntil(5_000) { shown("phone-search-match-src/Main.kt:1:0") }
        val search = recorded("GET", "/files/search").last()
        assertEquals("/api/project/ppm/files/search", search.path)
        assertTrue(search.query.orEmpty(), search.query.orEmpty().contains("q=fun"))
        composeRule.onNodeWithText("1 result in 1 file").assertExists()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(5_000) { !shown("phone-search-screen") && shown("phone-files-search-chip") }
        // Coming back finds the search as it was left.
        composeRule.onNodeWithTag("phone-files-search-chip", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-search-match-src/Main.kt:1:0") }
    }

    @Test
    fun goToFile_searchesTheProjectFileList_andOpensTheChosenFile() {
        model.fileIndex = FileIndexStore(api, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        var opened: String? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneGoToFileSheet(model = model, project = "ppm", onOpen = { opened = it }, onDismiss = {})
            }
        }
        composeRule.waitUntil(5_000) { recorded("GET", "/files/index").isNotEmpty() }
        composeRule.onNodeWithTag("phone-go-to-file-input").performTextInput("main")
        composeRule.waitUntil(5_000) { shown("phone-go-to-file-result-src/Main.kt") }
        assertFalse(shown("phone-go-to-file-result-README.md"))
        composeRule.onNodeWithTag("phone-go-to-file-result-src/Main.kt", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals("src/Main.kt", opened)
    }

    @Test
    fun recentFiles_areTheProjectsOpenFileTabs_mostRecentFirst_andOpenTheTab() {
        fun editor(id: String, path: String, project: String = "ppm") = WorkspaceTab(
            id = id,
            type = "editor",
            title = path.substringAfterLast('/'),
            projectId = project,
            metadata = buildJsonObject { put("filePath", JsonPrimitive(path)) },
        )
        val a = editor("a", "src/A.kt")
        val b = editor("b", "src/B.kt")
        val other = editor("o", "x/O.kt", project = "other")
        val terminal = WorkspaceTab(id = "t", type = "terminal", title = "Terminal", projectId = "ppm")
        val recent = phoneRecentFileTabs(listOf(a, terminal, other, b), "ppm", recentTabIds = listOf("b", "t", "a"))
        assertEquals(listOf("b", "a"), recent.map { it.id })

        var opened: WorkspaceTab? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneRecentFilesSheet(tabs = recent, onOpen = { opened = it }, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag("phone-recent-file-a", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals("a", opened?.id)
    }

    @Test
    fun languageLabel_followsTheFile() {
        assertEquals("Kotlin", phoneEditorLanguageLabel("src/Main.kt", null))
        assertEquals("TypeScript React", phoneEditorLanguageLabel("src/App.tsx", null))
        assertEquals("JavaScript React", phoneEditorLanguageLabel("src/App.jsx", null))
        assertFalse(phoneEditorLanguageLabel("README.md", null) == "TypeScript React")
    }

    private companion object {
        const val ROOT = "/work/ppm"
        const val ROOT_LISTING = """[{"name":"src","type":"directory"},{"name":"README.md","type":"file"}]"""
        const val SRC_LISTING = """[{"name":"Main.kt","type":"file"},{"name":"Util.kt","type":"file"}]"""
    }
}
