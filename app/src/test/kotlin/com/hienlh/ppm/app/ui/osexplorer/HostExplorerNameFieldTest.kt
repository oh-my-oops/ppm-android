package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.osexplorer.HostFileActions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The inline name field (`commitInline`, use-explorer-actions.ts, and `createEntry` and `renameEntry`, explorer-actions-mutate.ts),
 * pressed with Enter against a server that answers as a test says: the name is trimmed, an empty one is said under the field and sends
 * nothing, and one the server refuses is a toast naming what failed over why, with the field still open and nothing under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h400dp")
class HostExplorerNameFieldTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val toasts = CopyOnWriteArrayList<ErrorToastMessage>()
    private val old = HostFileEntry("old.txt", "/home/me/old.txt", "file", "file", size = 1)
    private val session = explorerSessionOn(
        HostDirectory(listOf(old), "/home/me", "/home", listOf(HostPlace("me", "/home/me")), "/", truncated = false),
    )
    private val outsideFocus = FocusRequester()
    private lateinit var commands: HostExplorerCommands

    @Before
    fun start() {
        server.start()
        val api = ApiClient(server.url("/"), "secret")
        commands = HostExplorerCommands(
            toasts::add, api, session, HostFileActions(api, FileMutations(api), "/", null), null,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        )
        compose.setContent {
            Column {
                commands.edit?.let { HostInlineName(commands, it) }
                Box(Modifier.size(16.dp).focusRequester(outsideFocus).focusable())
            }
        }
    }

    @After
    fun stop() {
        server.close()
        session.close()
    }

    private fun ok() = MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":true}""").build()

    private fun refused(code: String, hint: String? = null) = MockResponse.Builder().code(409).addHeader("Content-Type", "application/json")
        .body(buildJsonObject {
            put("ok", false)
            put("error", code)
            put("code", code)
            if (hint != null) put("hint", hint)
        }.toString()).build()

    private fun field() = compose.onAllNodes(hasSetTextAction())[0]

    private fun enter() {
        field().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
    }

    private fun answered() {
        compose.waitUntil(timeoutMillis = 5_000) { toasts.isNotEmpty() || commands.edit == null }
        compose.waitForIdle()
    }

    private fun requestBody(): String = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).body!!.utf8()

    @Test
    fun `a new file's name is trimmed before it is sent, and the field closes once the server has made it`() {
        server.enqueue(ok())
        commands.create(folder = false)
        compose.waitForIdle()
        field().performTextInput("  notes.txt  ")
        enter()
        answered()
        assertEquals("""{"path":"/home/me/notes.txt"}""", requestBody())
        assertNull(commands.edit)
    }

    @Test
    fun `a name of spaces is refused under the field in the web's words, sends nothing, and keeps the field`() {
        commands.create(folder = false)
        compose.waitForIdle()
        field().performTextInput("   ")
        enter()
        compose.onNodeWithText("Name cannot be empty").assertIsDisplayed()
        assertEquals(0, server.requestCount)
        assertNotNull(commands.edit)
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
    }

    @Test
    fun `a name with a slash is refused under the field, as the host's rules say`() {
        commands.create(folder = true)
        compose.waitForIdle()
        field().performTextInput("a/b")
        enter()
        compose.onNodeWithText("Name cannot contain path separators").assertIsDisplayed()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a file the server refuses is a toast of Could not create file over its hint, with the field open and no line under it`() {
        server.enqueue(refused("EEXIST", hint = "That name is taken."))
        commands.create(folder = false)
        compose.waitForIdle()
        field().performTextInput("dup.txt")
        enter()
        answered()
        val toast = toasts.single()
        assertEquals("Could not create file", toast.title)
        assertEquals("That name is taken.", toast.description)
        assertNotNull("the field is still open", commands.edit)
        assertNull("and says nothing under it", commands.inlineError)
        compose.onNodeWithText("That name is taken.").assertDoesNotExist()
    }

    @Test
    fun `a folder the server refuses is a toast of Could not create folder over its message when it gave no hint`() {
        server.enqueue(refused("ENOSPC"))
        commands.create(folder = true)
        compose.waitForIdle()
        field().performTextInput("new")
        enter()
        answered()
        val toast = toasts.single()
        assertEquals("Could not create folder", toast.title)
        assertEquals("ENOSPC", toast.description)
        assertNotNull(commands.edit)
    }

    @Test
    fun `a rename the server refuses is a toast of Rename failed, and the field stays on the new name`() {
        server.enqueue(refused("EACCES", hint = "Permission denied."))
        commands.rename(old)
        compose.waitForIdle()
        field().performTextReplacement("renamed.txt")
        enter()
        answered()
        val toast = toasts.single()
        assertEquals("Rename failed", toast.title)
        assertEquals("Permission denied.", toast.description)
        assertNotNull(commands.edit)
        assertNull(commands.inlineError)
    }

    @Test
    fun `a rename to the name the entry has closes the field and sends nothing`() {
        commands.rename(old)
        compose.waitForIdle()
        enter()
        assertNull(commands.edit)
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<ErrorToastMessage>(), toasts.toList())
    }

    @Test
    fun `a rename to the same name with spaces round it closes the field too`() {
        commands.rename(old)
        compose.waitForIdle()
        field().performTextReplacement("  old.txt ")
        enter()
        assertNull(commands.edit)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the inline name field has no Save or Cancel buttons and commits when focus leaves it`() {
        server.enqueue(ok())
        commands.create(folder = false)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Name").assertExists()
        compose.onNodeWithContentDescription("Save name").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel").assertDoesNotExist()

        field().performTextInput("blurred.txt")
        compose.runOnUiThread { outsideFocus.requestFocus() }
        compose.waitForIdle()
        answered()
        assertEquals("""{"path":"/home/me/blurred.txt"}""", requestBody())
        assertNull(commands.edit)
    }

    @Test
    fun `trash and failed delete toasts match the web's deleteEntries messages`() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("""{"ok":true,"data":{"removed":"/home/me/old.txt","permanent":false,"trashed":true}}""")
                .build(),
        )
        commands.trash(listOf(old))
        compose.waitUntil(timeoutMillis = 5_000) { toasts.isNotEmpty() }
        assertEquals("Moved 1 item to Trash", toasts.single().title)
        toasts.clear()

        server.enqueue(refused("EACCES", hint = "Permission denied."))
        commands.trash(listOf(old))
        compose.waitUntil(timeoutMillis = 5_000) { toasts.isNotEmpty() }
        val failure = toasts.single()
        assertEquals("Delete failed", failure.title)
        assertEquals("Permission denied.", failure.description)
    }
}
