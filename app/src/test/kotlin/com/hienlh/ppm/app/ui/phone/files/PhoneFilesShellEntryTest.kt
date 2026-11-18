package com.hienlh.ppm.app.ui.phone.files

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.phoneSearch
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.app.ui.phone.PhoneArea
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.phone.PhoneShell
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How a phone reaches the host's File Explorer and project search: the drawer's tile and the sidebar's Search request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneFilesShellEntryTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = PrefsStore(context).apply { put("onboarding.dismissed", "1") }

    @Test
    fun theDrawersFileExplorerTileOpensTheExplorerSheet() {
        val model = AppModel(context).apply { selectedProject = "ppm" }
        val nav = PhoneNav(initialDrawerOpen = true)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(model = model, nav = nav, prefs = prefs)
            }
        }
        composeRule.onNodeWithText("File Explorer").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertNotNull("the explorer sheet is asked for", model.explorerSheetPath)
        assertFalse(nav.drawerOpen)
    }

    @Test
    fun aSearchRequestOpensProjectSearchInTheFilesArea() {
        val model = AppModel(context).apply { selectedProject = "ppm" }
        model.api = ApiClient("http://127.0.0.1:9/".toHttpUrl(), "secret")
        val nav = PhoneNav()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(model = model, nav = nav, prefs = prefs)
            }
        }
        composeRule.runOnIdle { model.showSidebarSection("search") }
        composeRule.waitForIdle()
        assertEquals(PhoneNav.areaTabId(PhoneArea.FILES), nav.currentTabId)
        assertTrue(model.phoneSearch?.open?.value == true)
        assertTrue(composeRule.onAllNodesWithText("on a tablet", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
