package com.hienlh.ppm.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.ui.phone.PhoneArea
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.phone.PhoneShell
import com.hienlh.ppm.app.ui.phone.SheetKey
import com.hienlh.ppm.app.ui.tablet.TabBar
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.workspace.WorkspaceTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pinned tabs on screen (draggable-tab.tsx, mobile-tab-switcher-sheet.tsx, mobile-nav.tsx): a pin
 * stands where the × was, a phone's long press offers Pin and the bulk closes, and the palette
 * lists Pin / Unpin Tab, Close Saved Tabs and Close All Tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
class TabPinningUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    // Chat tabs: an editor tab's file icon reads assets a JVM test does not have.
    private val pinned = WorkspaceTab(id = "chat:a", type = "chat", title = "Pinned chat", projectId = "ppm", pinned = true)
    private val plain = WorkspaceTab(id = "chat:b", type = "chat", title = "Plain chat", projectId = "ppm")
    private val other = WorkspaceTab(id = "chat:c", type = "chat", title = "Other chat", projectId = "ppm")

    @Test
    fun aPinnedTabInTheTabletBarShowsAPinWhereItsCloseButtonWas() {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.setWindowWide(true)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalRedesign provides true) {
                    TabBar(model = model, panelId = "panel-1", tabs = listOf(pinned, plain), activeTabId = plain.id)
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodesWithTag("tab-pin-chat:a").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("tab-pin-chat:b").fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w400dp-h840dp-xhdpi")
    fun thePhonesTabSwitcherPinsInPlaceOfCloseAndALongPressOffersPinAndTheBulkCloses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PrefsStore(context).apply { put("onboarding.dismissed", "1") }
        val model = AppModel(context).apply { selectedProject = "ppm" }
        val nav = PhoneNav(initialTabId = PhoneNav.areaTabId(PhoneArea.CHAT))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneShell(model = model, nav = nav, prefs = prefs, overrideOpenTabs = listOf(pinned, plain, other))
            }
        }
        composeRule.runOnIdle { nav.pushSheet(SheetKey.TabSwitcher) }
        composeRule.waitForIdle()

        assertEquals(1, composeRule.onAllNodesWithContentDescription("Unpin Pinned chat").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithContentDescription("Close Pinned chat").fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithContentDescription("Close Plain chat").fetchSemanticsNodes().size)

        composeRule.onNodeWithText("Plain chat").performTouchInput { longClick() }
        composeRule.waitForIdle()
        for (label in listOf("Pin", "Close", "Close Others", "Close to the Right", "Close Saved", "Close All")) {
            composeRule.waitUntil(5_000L) { composeRule.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty() }
        }
        assertEquals(0, composeRule.onAllNodes(hasText("Unpin")).fetchSemanticsNodes().size)
    }

    @Test
    fun thePaletteListsPinUnpinTab() = paletteLists("pin unpin", "Pin / Unpin Tab")

    @Test
    fun thePaletteListsCloseSavedAndCloseAllTabs() = paletteLists("close tabs", "Close Saved Tabs", "Close All Tabs")

    private fun paletteLists(query: String, vararg labels: String) {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.setWindowWide(true)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                // LocalSizeClass defaults to Phone, where the redesigned palette gives way to the Search tab.
                CompositionLocalProvider(LocalRedesign provides true, LocalSizeClass provides SizeClass.Tablet) {
                    CommandPalette(model = model, initialQuery = query, onDismiss = {})
                }
            }
        }
        for (label in labels) {
            composeRule.waitUntil(5_000L) { composeRule.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
