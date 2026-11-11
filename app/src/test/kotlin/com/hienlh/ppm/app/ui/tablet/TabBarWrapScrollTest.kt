package com.hienlh.ppm.app.ui.tablet

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.workspace.WorkspaceTab
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With Wrap Tabs on, the bar shows 3 rows and scrolls the rest. A tab opened past the third row
 * (the tablet's File Explorer, opened from the rail) is scrolled into view, as the web's
 * `scrollIntoView({ block: "nearest" })` does, so its title and its close button can be reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
class TabBarWrapScrollTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun aNewTabOnAHiddenWrappedRowIsScrolledIntoView() {
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.setWindowWide(true)
        model.toggleTabWrap()
        val chats = (1..20).map { WorkspaceTab(id = "chat-$it", type = "chat", title = "A fairly long chat title number $it") }
        var tabs by mutableStateOf(chats)
        var active by mutableStateOf("chat-1")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalRedesign provides true) {
                    Column(Modifier.width(400.dp)) {
                        Box(Modifier.fillMaxWidth().testTag("bar")) {
                            TabBar(model = model, panelId = "panel-1", tabs = tabs, activeTabId = active)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        tabs = chats + WorkspaceTab(id = "explorer", type = "os-explorer", title = "Home")
        active = "explorer"
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()

        val bar = composeRule.onNodeWithTag("bar").getBoundsInRoot()
        val tab = composeRule.onNodeWithText("Home").getBoundsInRoot()
        // A tab scrolled out of the bar is clipped to nothing.
        assertTrue("new tab $tab is shown inside the bar $bar", tab.right > tab.left && tab.top >= bar.top && tab.bottom <= bar.bottom)
    }
}
