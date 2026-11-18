package com.hienlh.ppm.app.ui.phone.areas

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.ui.androiddevice.AndroidTabBody
import com.hienlh.ppm.app.ui.design.DesignTabBody
import com.hienlh.ppm.app.ui.phone.PhoneArea
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.phone.PhoneShell
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.design.DesignDeviceFrame
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

private const val PHONE = "w400dp-h840dp-xhdpi"
private const val TABLET = "w1280dp-h900dp-xhdpi"

/** A scripted server: each `METHOD /path` answers its `data`, and every request is kept with its body. */
private class ScriptedServer(private val routes: Map<String, String>) : Dispatcher() {
    val seen = CopyOnWriteArrayList<Pair<String, String>>()

    fun body(line: String): String? = seen.lastOrNull { it.first == line }?.second

    override fun dispatch(request: RecordedRequest): MockResponse {
        val line = "${request.method} ${request.url.encodedPath}"
        seen += line to (request.body?.utf8() ?: "")
        val data = routes[line] ?: "{}"
        return MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()
    }
}

/**
 * Designs, Teams, AI Resources, Extensions, Remote Desktop and Android on the phone layout: every
 * tab type of theirs and every list opens full screen (no "opens on a tablet" card), the drawer
 * reaches them, and the screens a phone adapts keep the tablet's layout on a tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = PHONE)
class PhoneFeatureAreasTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private var scripted: ScriptedServer? = null

    @After
    fun stop() {
        if (scripted != null) server.close()
    }

    private fun model(routes: Map<String, String>? = null): AppModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return AppModel(app).apply {
            selectedProject = "ppm"
            if (routes != null) {
                val s = ScriptedServer(routes)
                scripted = s
                server.dispatcher = s
                server.start()
                api = ApiClient(server.url("/"), "secret")
            }
        }
    }

    private fun prefs(): PrefsStore = PrefsStore(ApplicationProvider.getApplicationContext<Application>()).apply {
        put("onboarding.dismissed", "1")
    }

    private fun tab(type: String, id: String, metadata: Map<String, String> = emptyMap()) = WorkspaceTab(
        id = id,
        type = type,
        title = type,
        projectId = "ppm",
        metadata = buildJsonObject {
            put("projectName", "ppm")
            for ((k, v) in metadata) put(k, v)
        },
    )

    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `every feature tab opens full screen on the phone, none on a tablet-only card`() {
        val model = model()
        val tabs = listOf(
            tab("design", "design:landing", mapOf("designSlug" to "landing")) to PhoneArea.DESIGNS,
            tab("group", "group:g1", mapOf("groupId" to "g1")) to PhoneArea.TEAMS,
            tab("ai-resource", "ai-resource:1", mapOf("filePath" to "/home/u/.claude/skills/a/SKILL.md")) to PhoneArea.AI_RESOURCES,
            // Not Git Graph: a phone hosts that one in Source Control (PhoneGitTabs.kt).
            tab("extension", "extension:todo", mapOf("viewType" to "todo-tree.view")) to PhoneArea.EXTENSIONS,
            tab("remote-desktop", "remote-desktop:1") to PhoneArea.REMOTE_DESKTOP,
            tab("android", "android:1") to PhoneArea.ANDROID,
        )
        val nav = PhoneNav(initialTabId = tabs.first().first.id)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(model = model, nav = nav, prefs = prefs(), overrideOpenTabs = tabs.map { it.first })
            }
        }
        for ((t, area) in tabs) {
            composeRule.runOnIdle { nav.visitTab(t.id) }
            composeRule.waitForIdle()
            assertEquals("${t.type} opens its area", 1, count("phone-${area.id}-screen"))
            assertEquals("${t.type} shows no tablet card", 0, composeRule.onAllNodesWithText("Kept open in this project for your tablet.").fetchSemanticsNodes().size)
            when (t.type) {
                // The design's own phone layout: one pane and the thumb-zone bar.
                "design" -> assertEquals(1, count("design-phone-pane-bar"))
                // Remote Desktop asks first, as the web's sheet does.
                "remote-desktop" -> assertEquals(1, count("remote-desktop-warning-gate"))
            }
        }
    }

    @Test
    fun `the lists open full screen, an extension's view among them`() {
        val model = model()
        val nav = PhoneNav(initialTabId = PhoneNav.areaTabId(PhoneArea.DESIGNS))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(model = model, nav = nav, prefs = prefs())
            }
        }
        composeRule.onNodeWithTag("phone-designs-screen").assertExists()

        composeRule.runOnIdle { nav.visitTab(PhoneNav.areaTabId(PhoneArea.TEAMS)) }
        composeRule.onNodeWithTag("phone-teams-screen").assertExists()

        composeRule.runOnIdle { nav.visitTab(PhoneNav.areaTabId(PhoneArea.AI_RESOURCES)) }
        composeRule.onNodeWithTag("phone-ai-resources-screen").assertExists()

        composeRule.runOnIdle { nav.visitTab(PhoneNav.areaTabId(PhoneArea.EXTENSIONS)) }
        composeRule.onNodeWithTag("phone-extensions-manager").assertExists()

        composeRule.runOnIdle { nav.visitTab(extensionViewTabId("git-graph.view")) }
        composeRule.onNodeWithTag("phone-extensions-screen").assertExists()
        composeRule.onNodeWithTag("ext-treeview-git-graph.view", useUnmergedTree = true).assertExists()
        assertEquals("git-graph.view", extensionViewOf(nav.currentTabId))
    }

    @Test
    fun `a section asked for by id shows its area, an unknown one is left to the drawer`() {
        val nav = PhoneNav()
        assertTrue(showPhoneFeatureSection("designs", nav))
        assertEquals(PhoneNav.areaTabId(PhoneArea.DESIGNS), nav.currentTabId)
        assertTrue(showPhoneFeatureSection("teams", nav))
        assertEquals(PhoneNav.areaTabId(PhoneArea.TEAMS), nav.currentTabId)
        assertTrue(showPhoneFeatureSection("ai-resources", nav))
        assertEquals(PhoneNav.areaTabId(PhoneArea.AI_RESOURCES), nav.currentTabId)
        assertTrue(showPhoneFeatureSection("ext:todo.view", nav))
        assertEquals("phone:ext:todo.view", nav.currentTabId)
        assertEquals(PhoneArea.EXTENSIONS, PhoneNav.parseAreaTabId(nav.currentTabId))
        assertTrue(!showPhoneFeatureSection("search", nav))
        assertEquals("phone:ext:todo.view", nav.currentTabId)
    }

    @Test
    fun `the drawer reaches Teams, Designs, AI Resources and the extensions manager`() {
        val model = model()
        val nav = PhoneNav()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(model = model, nav = nav, prefs = prefs())
            }
        }
        for ((label, area) in listOf("Teams" to PhoneArea.TEAMS, "Designs" to PhoneArea.DESIGNS, "AI Resources" to PhoneArea.AI_RESOURCES)) {
            composeRule.runOnIdle { nav.openDrawer() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(label, useUnmergedTree = true).performClick()
            composeRule.waitForIdle()
            assertEquals(PhoneNav.areaTabId(area), nav.currentTabId)
            composeRule.onNodeWithTag("phone-${area.id}-screen").assertExists()
        }
        composeRule.runOnIdle { nav.openDrawer() }
        composeRule.waitForIdle()
        // Remote shows while the host supports it; Android only once the server says it is enabled.
        composeRule.onNodeWithText("Remote", useUnmergedTree = true).assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Android", useUnmergedTree = true).fetchSemanticsNodes().size)
        // The tools grid sits below the fold of the drawer's scrolling body.
        composeRule.onNodeWithText("Extensions", useUnmergedTree = true).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-extensions-screen").assertExists()
    }

    @Test
    fun `the extensions manager installs, switches and removes against the server`() {
        val model = model(
            mapOf(
                "GET /api/extensions" to """[{"id":"ext.todo","version":"1.2.0","displayName":"Todo","description":"","icon":"list-ordered","enabled":true,"activated":true}]""",
            ),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                Box(Modifier.fillMaxSize()) { PhoneExtensionsScreen(model) }
            }
        }
        composeRule.waitUntil(5_000) { count("phone-ext-row-ext.todo") == 1 }
        composeRule.onNodeWithText("Installed (1)").assertExists()
        composeRule.onNodeWithText("ext.todo v1.2.0", useUnmergedTree = true).assertExists()
        // A phone's targets are 44px.
        println("DBGTREE " + composeRule.onNodeWithTag("phone-ext-row-ext.todo", useUnmergedTree = true).printToString(maxDepth = 9))
        composeRule.onNodeWithTag("phone-ext-toggle-ext.todo").assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithTag("phone-ext-remove-ext.todo").assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithTag("phone-ext-install").assertHeightIsAtLeast(44.dp)

        composeRule.onNodeWithTag("phone-ext-toggle-ext.todo").performClick()
        composeRule.waitUntil(5_000) { scripted!!.body("PATCH /api/extensions/ext.todo") != null }
        assertEquals("""{"enabled":false}""", scripted!!.body("PATCH /api/extensions/ext.todo"))

        composeRule.onNodeWithTag("phone-ext-install-input").performTextInput("@ppm/ext-notes")
        composeRule.onNodeWithTag("phone-ext-install").performClick()
        composeRule.waitUntil(5_000) { scripted!!.body("POST /api/extensions/install") != null }
        assertEquals("""{"name":"@ppm/ext-notes"}""", scripted!!.body("POST /api/extensions/install"))

        composeRule.onNodeWithTag("phone-ext-devlink-toggle").performClick()
        composeRule.onNodeWithText("Hide").assertExists()
        composeRule.onNodeWithTag("phone-ext-devlink-input").performTextInput("./packages/ext-x")
        composeRule.onNodeWithTag("phone-ext-devlink").performClick()
        composeRule.waitUntil(5_000) { scripted!!.body("POST /api/extensions/dev-link") != null }
        assertEquals("""{"path":"./packages/ext-x"}""", scripted!!.body("POST /api/extensions/dev-link"))

        composeRule.onNodeWithTag("phone-ext-remove-ext.todo").performClick()
        composeRule.waitUntil(5_000) { scripted!!.seen.any { it.first == "DELETE /api/extensions/ext.todo" } }
    }

    @Test
    fun `a design on the phone folds its toolbar into More, which opens Share for review and the panels`() {
        val model = model()
        val design = tab("design", "design:landing", mapOf("designSlug" to "landing"))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                Box(Modifier.fillMaxSize()) { DesignTabBody(model, design) }
            }
        }
        composeRule.onNodeWithTag("design-phone-pane-bar").assertExists()
        // The bar's three 44px buttons; no toolbar above the canvas.
        composeRule.onNodeWithTag("design-phone-pane-more").assertHeightIsAtLeast(44.dp)
        assertEquals(0, composeRule.onAllNodesWithText("Share for review", useUnmergedTree = true).fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("design-phone-pane-more").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("design-phone-more-sheet", useUnmergedTree = true).assertExists()
        for (frame in DesignDeviceFrame.entries) assertEquals(1, count("design-phone-frame-" + frame.id))
        composeRule.onNodeWithTag("design-phone-more-share", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("share-dialog", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("share-close", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("design-phone-pane-more").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("design-phone-more-history", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("design-phone-panel-history", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = TABLET)
    fun `a design on a tablet keeps its toolbar and has no phone bar`() {
        val model = model()
        val design = tab("design", "design:landing", mapOf("designSlug" to "landing"))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.fillMaxSize()) { DesignTabBody(model, design) }
            }
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("Share for review", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, count("design-phone-pane-bar"))
        assertEquals(0, count("design-phone-more-sheet"))
    }

    private val androidRoutes = mapOf(
        "GET /api/android/capabilities" to """{"enabled":true,"requirements":[]}""",
        "GET /api/android/devices" to """{"devices":[]}""",
    )

    @Test
    fun `the Android device list's buttons are 44px on a phone`() {
        val model = model(androidRoutes)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                Box(Modifier.fillMaxSize()) { AndroidTabBody(model, tab("android", "android:1")) }
            }
        }
        composeRule.waitUntil(5_000) { count("android-refresh") == 1 }
        composeRule.onNodeWithTag("android-refresh", useUnmergedTree = true).assertHeightIsAtLeast(44.dp)
    }

    @Test
    @Config(qualifiers = TABLET)
    fun `the Android device list's buttons keep the tablet's size on a tablet`() {
        val model = model(androidRoutes)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.fillMaxSize()) { AndroidTabBody(model, tab("android", "android:1")) }
            }
        }
        composeRule.waitUntil(5_000) { count("android-refresh") == 1 }
        val bounds = composeRule.onNodeWithTag("android-refresh", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("tablet button is ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top < 44.dp)
    }
}
