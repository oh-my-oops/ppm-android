package com.hienlh.ppm.app.ui.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.design.kit.LocalSheetScrimLayer
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.kit.SheetScrimLayer
import com.hienlh.ppm.design.kit.SheetScrimLayerHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sheet opened inside a page (under the app bar, as every phone page's are) dims the strip above it too, so
 * the backdrop covers the whole window, app bar and status bar included, as a system bottom sheet's does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class SheetScrimAboveTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun aPageSheetDimsTheAppBarAboveIt_andATapThereDismissesIt() {
        var open by mutableStateOf(true)
        var dismissed = 0
        val layer = SheetScrimLayer()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(LocalSheetScrimLayer provides layer) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(120.dp)) // the app bar, status bar included
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                if (open) {
                                    Sheet(title = "Add to Chat", onDismiss = { dismissed++; open = false }, forceBottomSheet = true) {
                                        BasicText("tiles")
                                    }
                                }
                            }
                        }
                        SheetScrimLayerHost(layer)
                    }
                }
            }
        }

        val above = composeRule.onNodeWithTag("sheet-scrim-above").getBoundsInRoot()
        assertEquals(0f, above.top.value, 0.5f)
        assertEquals(120f, above.bottom.value, 0.5f)

        composeRule.onNodeWithTag("sheet-scrim-above").performClick()
        assertEquals(1, dismissed)
        composeRule.waitForIdle()
        assertTrue("closed sheet leaves nothing dimmed", composeRule.onAllNodesWithTag("sheet-scrim-above").fetchSemanticsNodes().isEmpty())
    }
}
