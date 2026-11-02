package com.hienlh.ppm.app.ui.osexplorer

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.DragDriver
import com.hienlh.ppm.app.ui.files.EntryDrag
import com.hienlh.ppm.app.ui.files.ExplorerRowDrag
import com.hienlh.ppm.app.ui.files.explorerDragClip
import com.hienlh.ppm.app.ui.files.flushStateWrites
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.files.FileMutations
import com.hienlh.ppm.state.osexplorer.ExplorerSession
import com.hienlh.ppm.state.osexplorer.ExplorerView
import com.hienlh.ppm.state.osexplorer.HostFileActions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain

/**
 * An explorer window's own views over a folder, and what is dragged over them delivered as Android delivers a drag (started, entered, a
 * location per move, a drop, ended), to a host that answers a move or a copy as the real one does. The folder is
 *
 *     /home/me/   docs/  src/  a.txt  b.txt
 *
 * with `/home` above it (`me/`, `shared/`, `z.txt`), which is what the second column of the Columns view reads. A transfer the views ask
 * for is in [transfers], as "move /from -> /to" or "copy /from -> /to", and what they say of it in [toasts].
 */
internal abstract class HostEntryDndTestBase {
    protected val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    protected val server = MockWebServer()
    protected val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    protected val toasts = CopyOnWriteArrayList<ErrorToastMessage>()
    protected val transfers = CopyOnWriteArrayList<String>()
    protected lateinit var api: ApiClient
    protected lateinit var session: ExplorerSession
    protected lateinit var commands: HostExplorerCommands
    protected var host: View? = null
    protected val driver = DragDriver(compose, local = null) { host!! }

    /** What the window's views are drawn on, and how big a box they get; a test that reads pixels sets both. */
    protected open val ground: Color? = null
    protected open val boxWidth = 700.dp
    protected open val boxHeight = 600.dp

    /** The Windows skin's list has no shading on every other row, which a test that reads pixels is better without. */
    protected open val windowsSkin = false

    /** How the window was asked to open its folder, when that is not the folder's own path: `~`, which the host answers with `/home/me`. */
    protected open val startPath: String? = null

    /** What a test flips while the window is up: whether it has commands, which ones, and whether it is a phone's. */
    protected val commandsAvailable = mutableStateOf(true)
    protected val replacement = mutableStateOf<HostExplorerCommands?>(null)
    protected val phone = mutableStateOf(false)

    protected fun dir(path: String) = HostFileEntry(path.substringAfterLast('/'), path, "directory", "directory")

    protected fun file(path: String) = HostFileEntry(path.substringAfterLast('/'), path, "file", "file", size = 1)

    private val listings = mapOf("/home" to listOf(dir("/home/me"), dir("/home/shared"), file("/home/z.txt")))

    @Before
    fun startHost() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
        api = ApiClient(server.url("/"), "secret")
        val home = HostDirectory(
            listOf(dir("/home/me/docs"), dir("/home/me/src"), file("/home/me/a.txt"), file("/home/me/b.txt")),
            "/home/me", "/home", listOf(HostPlace("home", "/home"), HostPlace("me", "/home/me")), "/", truncated = false,
        )
        // Any other folder is empty.
        session = explorerSessionOn(home, { path ->
            if (path == home.current || path == startPath) home
            else HostDirectory(emptyList(), path, path.substringBeforeLast('/'), home.breadcrumbs, "/", truncated = false)
        }, startPath ?: home.current)
        commands = HostExplorerCommands(toasts::add, api, session, HostFileActions(api, FileMutations(api), "/", null), null, scope)
    }

    @After
    fun stopHost() {
        DragModifierKeys.update(ctrl = false, alt = false)
        scope.cancel()
        server.close()
        session.close()
    }

    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()

    private fun refused(code: String, status: Int) = MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
        .body(buildJsonObject { put("ok", false); put("error", code); put("code", code) }.toString()).build()

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when (path) {
            "/api/fs/move", "/api/fs/copy" -> {
                val body = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject
                val source = body.getValue("source").jsonPrimitive.content
                val destination = body.getValue("destination").jsonPrimitive.content
                transfers += "${path.substringAfterLast('/')} $source -> $destination"
                ok(buildJsonObject { put("source", source); put("destination", destination) }.toString())
            }
            "/api/fs/browse" -> {
                val folder = request.url.queryParameter("path").orEmpty()
                ok(buildJsonObject {
                    put("current", folder); put("parent", "/"); put("sep", "/"); put("truncated", false)
                    put("breadcrumbs", JsonArray(emptyList()))
                    put("entries", buildJsonArray {
                        for (entry in listings[folder].orEmpty()) add(buildJsonObject {
                            put("name", entry.name); put("path", entry.path); put("type", entry.type); put("kind", entry.kind)
                        })
                    })
                }.toString())
            }
            // Every name is free, so a copy into a folder that has the name already is numbered.
            "/api/fs/stat" -> refused("ENOENT", 404)
            else -> refused("NOT_FOUND", 404)
        }
    }

    /**
     * Composes [view] of the window over the folder, in a box of [boxWidth] by [boxHeight]. A phone's ([mobile]) or one with no commands is a window
     * that can drag nothing and take nothing. With [clockHeld] the clock of the test moves only when the test says, which a drag that
     * rests on something needs: the tint after 120 ms and the spring-load after 800.
     */
    protected fun show(view: ExplorerView, mobile: Boolean = false, withCommands: Boolean = true, clockHeld: Boolean = false) {
        installBlankFileIcons()
        session.preferences.update(session.preferences.state.value.copy(view = view))
        commandsAvailable.value = withCommands
        phone.value = mobile
        compose.setContent {
            host = LocalView.current
            val shownCommands = (replacement.value ?: commands).takeIf { commandsAvailable.value }
            val isPhone = phone.value
            val state by session.state.collectAsState()
            val preferences by session.preferences.state.collectAsState()
            val entries = remember(state.directory, state.filter, preferences) { session.visibleEntries() }
            Box(Modifier.size(boxWidth, boxHeight).testTag("explorer").then(if (ground != null) Modifier.background(ground!!) else Modifier)) {
                if (view == ExplorerView.COLUMNS) {
                    HostExplorerColumns(api, session, entries, state, preferences, isPhone, onSelect = {}, onOpen = {}, onMenu = { _, _, _ -> }, commands = shownCommands)
                } else {
                    HostExplorerEntries(
                        null, session, entries, state, preferences, isPhone, selectMode = false, windows = windowsSkin, commands = shownCommands,
                        cut = emptySet(), onFocus = {}, onOpen = {}, onMenu = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()
        // The column left of the last is read from the host, and shows once it has been.
        if (view == ExplorerView.COLUMNS && !mobile) compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("z.txt").fetchSemanticsNodes().isNotEmpty() }
        if (clockHeld) compose.mainClock.autoAdvance = false
    }

    /** The middle of what [description] names: an entry's row or tile, or in the Columns view a column, by its folder. */
    protected fun center(description: String): Offset = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.center

    /** A point of the window's views just inside the bottom edge, which is below the last row of a list and of a grid. */
    protected fun below(): Offset = compose.onNodeWithTag("explorer").fetchSemanticsNode().boundsInRoot.let { Offset(it.center.x, it.bottom - 4f) }

    /** A point just inside the bottom of the column of [folder], below its last row. */
    protected fun belowColumn(folder: String): Offset =
        compose.onNodeWithContentDescription(folder).fetchSemanticsNode().boundsInRoot.let { Offset(it.center.x, it.bottom - 4f) }

    /** Just inside the top of the window's views, over the list's header and the grid's first padding, where no row is. */
    protected fun top(): Offset = compose.onNodeWithTag("explorer").fetchSemanticsNode().boundsInRoot.let { Offset(it.center.x, it.top + 3f) }

    /** A drag of [entries] begins: the clip and the local state of a real one. Whether anything took it. */
    protected fun begin(entries: EntryDrag): Boolean {
        driver.local = entries
        driver.clip = explorerDragClip(entries)
        val taken = driver.begin()
        flushStateWrites()
        return taken
    }

    protected fun hover(at: Offset) {
        driver.move(at.x.roundToInt(), at.y.roundToInt())
        flushStateWrites()
    }

    /** Lets go at [at], and waits for the transfer that follows to be over. Whether the drop was taken. */
    protected fun letGo(at: Offset): Boolean {
        val taken = driver.drop(at.x.roundToInt(), at.y.roundToInt())
        driver.end()
        flushStateWrites()
        awaitTransfer()
        return taken
    }

    protected fun awaitTransfer() {
        val deadline = System.nanoTime() + 10_000_000_000L
        fun busy() = commands.busy || replacement.value?.busy == true
        while (busy() && System.nanoTime() < deadline) Thread.sleep(10)
        check(!busy()) { "the transfer never finished" }
    }

    /** Other commands over the same host and session, which say what they have done into [said] instead of [toasts]. */
    protected fun otherCommands(said: MutableList<ErrorToastMessage>) =
        HostExplorerCommands(said::add, api, session, HostFileActions(api, FileMutations(api), "/", null), null, scope)

    /** Drags [paths] from the window, which is the shape of a drag that began on a row or a tile. */
    protected fun entries(vararg paths: String) = com.hienlh.ppm.app.ui.files.HostEntryDrag(paths.toList())

    /** Rows of a project tree dragged, under the project folder `/home/me/p`. */
    protected fun treeRows(vararg paths: String, root: String? = "/home/me/p") = ExplorerRowDrag("p", paths.first(), false, paths.toList(), root)

    protected fun titles() = toasts.map { it.title }
}
