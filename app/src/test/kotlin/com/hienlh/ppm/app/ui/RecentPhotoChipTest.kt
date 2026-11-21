package com.hienlh.ppm.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.recentphoto.MediaImage
import com.hienlh.ppm.app.recentphoto.PhotoAccess
import com.hienlh.ppm.app.recentphoto.RecentPhotoDetector
import com.hienlh.ppm.app.recentphoto.RecentPhotos
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.ui.phone.PhoneChatContent
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.DraftLocalStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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

/**
 * The "Add to chat" chip over the real chat hosts, the tablet's [ChatPane] and the phone's
 * [PhoneChatContent], fed by a fake media store: what it shows, what a tap attaches (through the
 * paperclip's own `model.attach`), × and the minute it stays, and the variant for a screenshot the
 * app cannot read yet.
 */
abstract class RecentPhotoChipTestBase {
    protected val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    protected abstract val mobile: Boolean

    /** The host under test: the chat for [tab], or that layout with no chat. */
    @Composable
    protected abstract fun Host(tab: ChatTab?)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    protected lateinit var context: Context
    protected lateinit var model: AppModel
    private lateinit var api: ApiClient
    private val session = SessionInfo(id = "s-1", providerId = "claude", title = "Fix the parser")

    // The fake media store.
    private var now = 1_800_000_000_000L
    protected var access = PhotoAccess.FULL
    private val images = CopyOnWriteArrayList<MediaImage>()
    private val files = HashMap<Long, File>()
    protected lateinit var detector: RecentPhotoDetector
    protected lateinit var photos: RecentPhotos

    /** What the permission dialog was asked for, and how it answers. */
    protected val asked = CopyOnWriteArrayList<String>()
    protected var grant = true
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            @Suppress("UNCHECKED_CAST")
            val permissions = (input as? Array<String>) ?: return
            asked += permissions
            if (grant) access = PhotoAccess.FULL
            dispatchResult(requestCode, permissions.associateWith { grant })
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val data = when {
                    path.startsWith("/ws/") -> return MockResponse.Builder().code(503).build()
                    path.endsWith("/messages") -> """{"messages":[],"total":0,"hasMore":false}"""
                    else -> """{}"""
                }
                return MockResponse.Builder().code(200).body("""{"ok":true,"data":$data}""").build()
            }
        }
        server.start()
        context = ApplicationProvider.getApplicationContext<Application>()
        model = AppModel(context)
        api = ApiClient(server.url("/"), "secret")
        model.api = api
        model.selectedProject = "p"
        model.projects = listOf(ProjectInfo(name = "p", path = "/repo"))
        model.sessions = listOf(session)
        installBlankFileIcons()
        detector = RecentPhotoDetector(
            query = { images.toList() },
            now = { now },
            ownPackage = context.packageName,
            access = { access },
        )
        photos = RecentPhotos(detector, thumbnails = { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap() }, uriOf = { Uri.fromFile(files.getValue(it)) }, accessPermissions = arrayOf(READ_IMAGES))
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    protected fun startedTab(): ChatTab = ChatTab("tab-1", session, "p", "claude").also {
        it.start(api, scope, DraftLocalStore(PrefsStore(context, "recent-photo-chip-test"), "server"))
    }

    private var shown by mutableStateOf<ChatTab?>(null)

    protected fun show(tab: ChatTab?) {
        shown = tab
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalRecentPhotos provides photos, LocalActivityResultRegistryOwner provides registryOwner) {
                    Host(shown)
                }
            }
        }
        composeRule.waitForIdle()
    }

    protected fun showChat(tab: ChatTab?) {
        composeRule.runOnIdle { shown = tab }
        composeRule.waitForIdle()
    }

    /** A screenshot taken [secondsAgo] seconds ago, its file a real PNG for the attach to copy. */
    protected fun screenshot(id: Long, secondsAgo: Long = 30): File {
        val file = File(context.cacheDir, "Screenshot_$id.png")
        file.outputStream().use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        files[id] = file
        images += MediaImage(id, now / 1000 - secondsAgo, file.length(), path = "Pictures/Screenshots/")
        return file
    }

    protected fun scan() {
        composeRule.runOnIdle { detector.scan() }
        composeRule.waitForIdle()
    }

    protected fun chipCount() = composeRule.onAllNodesWithTag("recent-photo-chip").fetchSemanticsNodes().size

    protected fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            composeRule.waitForIdle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "Condition not met in 5 s" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `a new screenshot shows the chip over the chat, with its thumbnail`() {
        show(startedTab())
        assertEquals(0, chipCount())
        screenshot(1)
        scan()
        composeRule.onNodeWithText("Add to chat").assertExists()
        // The thumbnail is read off the main thread.
        waitFor { composeRule.onAllNodesWithTag("recent-photo-thumbnail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Dismiss").assertExists()
    }

    @Test
    fun `a tap attaches the picture to the chat's message box, as the paperclip does`() {
        val tab = startedTab()
        show(tab)
        screenshot(1)
        scan()
        composeRule.onNodeWithTag("recent-photo-chip").performClick()
        waitFor { tab.attachments.isNotEmpty() }
        assertEquals("Screenshot_1.png", tab.attachments.single().name)
        assertEquals("the chip goes", 0, chipCount())
        scan()
        assertEquals("and the picture is not offered again", 0, chipCount())
    }

    @Test
    fun `x dismisses the chip and the picture is not offered again`() {
        val tab = startedTab()
        show(tab)
        screenshot(1)
        scan()
        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        composeRule.waitForIdle()
        assertEquals(0, chipCount())
        assertTrue("nothing attached", tab.attachments.isEmpty())
        scan()
        assertEquals(0, chipCount())
    }

    @Test
    fun `the chip hides by itself after a minute`() {
        show(startedTab())
        composeRule.mainClock.autoAdvance = false
        screenshot(1)
        composeRule.runOnIdle { detector.scan() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(1, chipCount())
        composeRule.mainClock.advanceTimeBy(55_000)
        assertEquals("still there before the minute", 1, chipCount())
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(0, chipCount())
        assertEquals(null, detector.offer.value)
    }

    @Test
    fun `no chat on screen, no chip, and it shows once a chat is`() {
        show(null)
        screenshot(1)
        scan()
        assertEquals(0, chipCount())
        showChat(startedTab())
        assertEquals(1, chipCount())
    }

    @Test
    fun `without photo access a screenshot is offered with no thumbnail, and a tap asks for access then attaches it`() {
        access = PhotoAccess.NONE
        val tab = startedTab()
        show(tab)
        composeRule.runOnIdle { detector.screenshotTaken() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Attach the screenshot you just took?").assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag("recent-photo-thumbnail", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("nothing asked before the tap", asked.isEmpty())

        screenshot(2, secondsAgo = 2)
        composeRule.onNodeWithTag("recent-photo-chip").performClick()
        waitFor { tab.attachments.isNotEmpty() }
        assertEquals(listOf(READ_IMAGES), asked)
        assertEquals("Screenshot_2.png", tab.attachments.single().name)
        assertEquals(0, chipCount())
    }

    @Test
    fun `a denial attaches nothing and stops the offers`() {
        access = PhotoAccess.NONE
        grant = false
        val tab = startedTab()
        show(tab)
        composeRule.runOnIdle { detector.screenshotTaken() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recent-photo-chip").performClick()
        waitFor { chipCount() == 0 }
        assertTrue(tab.attachments.isEmpty())
        assertTrue(detector.isDeclined)
        composeRule.runOnIdle { detector.screenshotTaken() }
        composeRule.waitForIdle()
        assertEquals("not offered again", 0, chipCount())
    }

    companion object {
        const val READ_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    }
}

/** The tablet: the chip over [ChatPane], above its context row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class RecentPhotoChipTabletTest : RecentPhotoChipTestBase() {
    override val mobile = false

    @Composable
    override fun Host(tab: ChatTab?) {
        Box(Modifier.size(width = 800.dp, height = 700.dp)) {
            if (tab != null) ChatPane(model = model, tab = tab)
        }
    }

    @Test
    fun `with two chats on screen only one shows the chip, and its tap hands over the picture's uri`() {
        val first = mutableListOf<List<Uri>>()
        val second = mutableListOf<List<Uri>>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalRecentPhotos provides photos) {
                    Column(Modifier.fillMaxSize()) {
                        RecentPhotoChipHost("a", activeTabId = "b", onAttach = { first += it })
                        RecentPhotoChipHost("b", activeTabId = "b", onAttach = { second += it })
                    }
                }
            }
        }
        val file = screenshot(1)
        scan()
        assertEquals(1, chipCount())
        composeRule.onNodeWithTag("recent-photo-chip").performClick()
        composeRule.waitForIdle()
        assertTrue(first.isEmpty())
        assertEquals(listOf(listOf(Uri.fromFile(file))), second)
        assertFalse(chipCount() > 0)
    }
}

/** The phone: the chip over the transcript of [PhoneChatContent], just above the composer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class RecentPhotoChipPhoneTest : RecentPhotoChipTestBase() {
    override val mobile = true

    @Composable
    override fun Host(tab: ChatTab?) {
        // No chat: the phone's new-chat welcome, which has nothing to attach to yet.
        PhoneChatContent(model, tab, "Fix the parser", {})
    }
}
