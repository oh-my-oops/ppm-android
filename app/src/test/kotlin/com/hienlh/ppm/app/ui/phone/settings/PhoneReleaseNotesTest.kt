package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ChangelogSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The release notes sheet opened from About (`release-notes-view.tsx` + `release-notes-entry.tsx`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h844dp-xxhdpi")
class PhoneReleaseNotesTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private fun shown(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun textShown(text: String) = composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `About opens the recent notes with the installed version marked`() {
        var loads = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneAboutContent(
                    serverVersion = "v0.23.37",
                    installedVersion = "0.23.37",
                    loadReleaseNotes = {
                        loads++
                        listOf(
                            ChangelogSection("0.23.38", "2026-11-11", "### Added\n- **Search** in `Files`"),
                            ChangelogSection("0.23.37", "2026-11-10", "### Fixed\n- A fix"),
                        )
                    },
                )
            }
        }
        assertFalse("nothing is fetched until the notes are opened", shown("phone-release-notes-sheet"))
        assertEquals(0, loads)
        composeRule.onNodeWithTag("phone-about-release-notes").performClick()
        composeRule.waitUntil(3_000) { shown("phone-release-notes-entry-0.23.37") }
        assertEquals(1, loads)
        assertTrue(textShown("PPM v0.23.37 installed"))
        assertTrue(textShown("ADDED") && textShown("Search in Files"))
        val marks = composeRule.onAllNodesWithTag("phone-release-notes-installed", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, marks.size)
        composeRule.onNode(
            hasTestTag("phone-release-notes-installed") and hasAnyAncestor(hasTestTag("phone-release-notes-entry-0.23.37")),
            useUnmergedTree = true,
        ).assertExists()
        assertTrue(shown("phone-release-notes-github"))
    }

    @Test
    fun `notes that cannot be read point to GitHub`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneAboutContent(serverVersion = "v1", loadReleaseNotes = { throw IOException("CHANGELOG fetch failed (503)") })
            }
        }
        composeRule.onNodeWithTag("phone-about-release-notes").performClick()
        composeRule.waitUntil(3_000) { shown("phone-release-notes-failed") }
        assertTrue(textShown("Release notes could not be loaded. Open them on GitHub instead."))
    }

    @Test
    fun `About offers no Release notes without a way to read them`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) { PhoneAboutContent(serverVersion = "v1") }
        }
        assertFalse(shown("phone-about-release-notes"))
    }

    @Test
    fun `bold and code are read as the web reads them, bold inside read again`() {
        assertEquals(
            listOf(
                ReleaseNoteSpan.Bold(listOf(ReleaseNoteSpan.Text("Run "), ReleaseNoteSpan.Code("ppm x"), ReleaseNoteSpan.Text(" first."))),
                ReleaseNoteSpan.Text(" then <b>"),
                ReleaseNoteSpan.Code("y"),
            ),
            releaseNoteSpans("**Run `ppm x` first.** then <b>`y`"),
        )
        assertEquals(30, PHONE_RELEASE_NOTES_SHOWN)
    }
}
