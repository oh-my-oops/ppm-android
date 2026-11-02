package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The version chip's wire types and CHANGELOG.md read as changelog.ts reads it (goldens from bun). */
class UpgradeTest {
    @Test
    fun `sections are the versioned headings, with what follows each up to the next`() {
        val markdown = "# Changelog\r\n\r\n## [Unreleased]\n- pending\n\n## [0.23.24] - 2026-11-02\n### Fixed\n- **Chat**: a `thing`\n\n" +
            "## [0.23.23]\n- y\n## [0.23.22] -   2026-10-30  \n##[1.2.3]-x\n## [1.2] - no\n"
        assertEquals(
            listOf(
                ChangelogSection("0.23.24", "2026-11-02", "### Fixed\n- **Chat**: a `thing`"),
                ChangelogSection("0.23.23", "", "- y"),
                ChangelogSection("0.23.22", "2026-10-30", ""),
                ChangelogSection("1.2.3", "x", "## [1.2] - no"),
            ),
            parseChangelog(markdown),
        )
    }

    @Test
    fun `the newest section is found in any order`() {
        assertEquals("0.23.10", newestSectionVersion(parseChangelog("## [0.23.9]\n## [0.23.10]\n## [0.23.2]\n")))
        assertNull(newestSectionVersion(emptyList()))
    }

    @Test
    fun `status and result read the routes' answers`() {
        assertEquals(
            UpgradeStatus("0.23.21", "0.23.24", "bun"),
            UpgradeStatus.from(WireJson.parse("""{"currentVersion":"0.23.21","availableVersion":"0.23.24","installMethod":"bun"}""")),
        )
        assertEquals(
            UpgradeStatus("0.23.21", null, "binary"),
            UpgradeStatus.from(WireJson.parse("""{"currentVersion":"0.23.21","availableVersion":null,"installMethod":"binary"}""")),
        )
        assertNull(UpgradeStatus.from(WireJson.parse("{}")))
        assertEquals(UpgradeResult(true, "0.23.24", true, null), UpgradeResult.from(WireJson.parse("""{"success":true,"newVersion":"0.23.24","restart":true}""")))
        assertEquals(
            UpgradeResult(true, "0.23.24", false, "Restart manually"),
            UpgradeResult.from(WireJson.parse("""{"success":true,"newVersion":"0.23.24","restart":false,"message":"Restart manually"}""")),
        )
    }
}
