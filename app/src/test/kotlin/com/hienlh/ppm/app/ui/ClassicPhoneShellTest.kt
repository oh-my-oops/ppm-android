package com.hienlh.ppm.app.ui

import com.hienlh.ppm.app.LocalRedesign
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.LocalPrefs
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Classic phone layout (the one before the redesign) behind Settings → Appearance → Interface:
 * offered on the phone only, kept on the device, and drawn with its bottom nav.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class ClassicPhoneShellTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun onlyAPhoneWithClassicChosenUsesTheClassicShell() {
        assertTrue(usesClassicPhoneShell(SizeClass.Phone, redesign = false))
        assertFalse(usesClassicPhoneShell(SizeClass.Phone, redesign = true))
        assertFalse(usesClassicPhoneShell(SizeClass.Tablet, redesign = false))
        assertFalse(usesClassicPhoneShell(SizeClass.Tablet, redesign = true))
    }

    @Test
    fun theNewLayoutIsTheDefaultOnANewInstall() {
        LocalPrefs.of(context)
        assertTrue(LocalPrefs.redesign)
    }

    @Test
    fun appearanceOffersNewAndClassicOnAPhoneAndKeepsTheChoice() {
        val store = PrefsStore(context)
        LocalPrefs.of(context)
        val model = AppModel(context)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(LocalSizeClass provides SizeClass.Phone) {
                    AppearanceExtrasSection(model)
                }
            }
        }
        composeRule.onNodeWithTag("settings-interface-classic").performClick()
        composeRule.waitForIdle()
        assertFalse(LocalPrefs.redesign)
        assertEquals("false", store.get(LocalPrefs.REDESIGN_KEY))

        composeRule.onNodeWithTag("settings-interface-new").performClick()
        composeRule.waitForIdle()
        assertTrue(LocalPrefs.redesign)
        assertEquals("true", store.get(LocalPrefs.REDESIGN_KEY))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun aTabletHasNoInterfaceChoice() {
        val model = AppModel(context)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalSizeClass provides SizeClass.Tablet) {
                    AppearanceExtrasSection(model)
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("settings-interface-classic").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("settings-interface-new").fetchSemanticsNodes().size)
    }

    @Test
    fun theClassicShellDrawsItsBottomNav() {
        PrefsStore(context).put("onboarding.dismissed", "1")
        val model = AppModel(context)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                ClassicPhoneShell(model)
            }
        }
        composeRule.waitForIdle()
        val labels = composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("has click label") { it.config.getOrNull(SemanticsActions.OnClick)?.label != null })
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.label }
        for (label in listOf("Open menu", "Switch project", "Terminal", "New tab")) {
            assertTrue("missing nav cell $label in $labels", label in labels)
        }
    }

    @Test
    fun classicSettingsListsTheCategoriesAndOpensAppearanceWithTheInterfaceChoice() {
        LocalPrefs.of(context)
        val model = AppModel(context)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(LocalRedesign provides false, LocalSizeClass provides SizeClass.Phone) {
                    SettingsBody(model, backReturnsToList = true)
                }
            }
        }
        composeRule.waitForIdle()
        // The redesigned phone settings are not shown; the web's category list is.
        assertEquals(0, composeRule.onAllNodesWithText("THIS PHONE").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Appearance").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-interface-classic").performScrollTo()
        assertEquals(1, composeRule.onAllNodesWithTag("settings-interface-classic").fetchSemanticsNodes().size)
    }

    @Test
    fun theClassicDrawerNamesTheSectionsAddedAfterIt() {
        PrefsStore(context).put("onboarding.dismissed", "1")
        val model = AppModel(context)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                ClassicPhoneShell(model)
            }
        }
        composeRule.runOnIdle { model.mobileDrawerOpenRequest = "tasks" }
        composeRule.waitForIdle()
        val sections = model.sidebarSections
        assertTrue("tasks" in sections && "specs" in sections)
        // Every section has its own name: before, Tasks and Specs both read "History".
        val slots = composeRule.onAllNodes(androidx.compose.ui.test.hasText("History")).fetchSemanticsNodes().size
        assertTrue("History shown $slots times", slots <= 1)
        assertTrue(composeRule.onAllNodesWithText("Tasks").fetchSemanticsNodes().isNotEmpty())
    }
}
