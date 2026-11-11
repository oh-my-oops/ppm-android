package com.hienlh.ppm.app.ui.phone.onboarding

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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

/** The phone's "PPM has new features" step against `/api/feature-intros` (`onboarding-feature-intro.tsx`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h844dp-xxhdpi")
class PhoneFeatureIntrosTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val posts = mutableListOf<Pair<String, String>>()
    @Volatile private var pending = true
    @Volatile private var existingUser = true
    private lateinit var model: AppModel

    private val intro = """{"id":"session-tools-2026-11","title":"Sessions that read and message each other","body":"The AI can search this project's other chats.","tools":["search_sessions","create_session"],"since":"2026-11"}"""

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = when {
                    request.method == "POST" && path.startsWith("/api/feature-intros/") -> {
                        synchronized(posts) { posts += path to request.body?.utf8().orEmpty() }
                        if (path.endsWith("/answer")) pending = false
                        """{"ok":true,"data":{"answer":"on","skips":0}}"""
                    }
                    path == "/api/feature-intros" ->
                        """{"ok":true,"data":{"pending":[${if (pending) intro else ""}],"isExistingUser":$existingUser,"newInSettings":[]}}"""
                    else -> """{"ok":true,"data":{}}"""
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("feature-intros-test", Context.MODE_PRIVATE).edit().clear().commit()
        model = AppModel(context)
        model.api = ApiClient(server.url("/"), "t")
    }

    @After
    fun stop() = server.close()

    private fun prefs() = PrefsStore(ApplicationProvider.getApplicationContext(), "feature-intros-test")
    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun textShown(text: String) = composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun sent() = synchronized(posts) { posts.toList() }

    private fun host(prefs: PrefsStore) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneOnboardingHost(model = model, controller = rememberPhoneOnboardingController(prefs))
            }
        }
    }

    @Test
    fun `an install in use is asked at once, not offered the tour, and Turn on answers on`() {
        host(prefs())
        composeRule.waitUntil(5_000) { shown("phone-feature-intro-on") }
        assertTrue(textShown("Sessions that read and message each other"))
        assertFalse("no basic-tour offer for an install in use", textShown("Welcome to PPM"))

        composeRule.onNodeWithTag("phone-feature-intro-on").performClick()
        composeRule.waitUntil(5_000) { sent().isNotEmpty() && !shown("phone-feature-intro-on") }
        assertEquals(listOf("/api/feature-intros/session-tools-2026-11/answer" to """{"answer":"on"}"""), sent())
        assertFalse("the old routes are gone", sent().any { it.first.endsWith("/dismiss") || it.first.endsWith("/reset-all") })
    }

    @Test
    fun `a new install finishes or dismisses its tour first, then Later records one skip`() {
        existingUser = false
        host(prefs())
        composeRule.waitUntil(5_000) { textShown("Welcome to PPM") }
        assertFalse(shown("phone-feature-intro-on"))

        composeRule.onNodeWithText("Skip for now", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { shown("phone-feature-intro-later") }
        composeRule.onNodeWithTag("phone-feature-intro-later").performClick()
        composeRule.waitUntil(5_000) { sent().isNotEmpty() && !shown("phone-feature-intro-later") }
        assertEquals(listOf("/api/feature-intros/session-tools-2026-11/skip" to ""), sent())
    }

    private class FakeBackend : FeatureIntrosBackend {
        var view: FeatureIntrosView? = null
        val calls = mutableListOf<String>()
        var failAnswer = false
        var slow: CompletableDeferred<FeatureIntrosView?>? = null
        override suspend fun load(): FeatureIntrosView? = slow?.await() ?: view
        override suspend fun answer(id: String, on: Boolean) {
            calls += "answer:$id:$on"
            if (failAnswer) throw PpmError.Server(500, "database is locked")
        }
        override suspend fun skip(id: String) {
            calls += "skip:$id"
        }
    }

    private fun intro(id: String) = FeatureIntro(id, "T $id", "B", listOf("search_sessions"))

    @Test
    fun `one skip per feature per app open, and one put off elsewhere is not skipped here`() = runTest {
        val backend = FakeBackend().apply { view = FeatureIntrosView(listOf(intro("a"), intro("b"), intro("c")), isExistingUser = true) }
        val intros = PhoneFeatureIntros(backend, backgroundScope)
        intros.load()
        runCurrent()
        assertEquals(listOf("a", "b", "c"), intros.remaining.map { it.id })
        assertTrue(intros.allowed(tourInProgress = false, tourDone = false))
        assertFalse(intros.allowed(tourInProgress = true, tourDone = true))

        intros.skippedElsewhere("a")
        intros.later("b")
        intros.later("b")
        runCurrent()
        assertEquals("c", intros.current?.id)
        assertEquals("the counter keeps the handled steps", listOf("a", "b", "c"), intros.steps)
        intros.close()
        runCurrent()
        assertEquals(listOf("skip:b", "skip:c"), backend.calls)
        assertFalse(intros.allowed(tourInProgress = false, tourDone = true))
    }

    @Test
    fun `a refused answer keeps the question with the reason, and an older load never wins`() = runTest {
        val backend = FakeBackend().apply { view = FeatureIntrosView(listOf(intro("a")), isExistingUser = false); failAnswer = true }
        val intros = PhoneFeatureIntros(backend, backgroundScope)
        intros.load()
        runCurrent()
        assertFalse("a new install waits for its tour", intros.allowed(tourInProgress = false, tourDone = false))
        intros.answer("a", false)
        runCurrent()
        assertEquals("Could not save your choice: database is locked", intros.error)
        assertEquals("a", intros.current?.id)
        assertFalse(intros.busy)

        val old = CompletableDeferred<FeatureIntrosView?>()
        backend.slow = old
        intros.load()
        runCurrent()
        backend.slow = null
        backend.view = FeatureIntrosView(emptyList(), isExistingUser = true)
        intros.load()
        runCurrent()
        old.complete(FeatureIntrosView(listOf(intro("a")), isExistingUser = false))
        runCurrent()
        assertNull(intros.current)
        assertEquals(true, intros.view?.isExistingUser)
    }

    @Test
    fun `the server's view is read, and anything else shows nothing`() {
        val view = parseFeatureIntrosView(WireJson.parse("""{"pending":[$intro,{"id":"x"}],"isExistingUser":true,"newInSettings":["y"]}"""))
        assertEquals(listOf("session-tools-2026-11"), view?.pending?.map { it.id })
        assertEquals(listOf("search_sessions", "create_session"), view?.pending?.single()?.tools)
        assertEquals(listOf("y"), view?.newInSettings)
        assertNull("an older server's shape", parseFeatureIntrosView(WireJson.parse("""{"intros":[]}""")))
    }
}
