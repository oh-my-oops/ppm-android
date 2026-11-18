package com.hienlh.ppm.app.ui.phone.files

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.ui.files.PDF_MAX_ZOOM
import com.hienlh.ppm.app.ui.files.PDF_MIN_ZOOM
import com.hienlh.ppm.app.ui.files.PdfLoadState
import com.hienlh.ppm.app.ui.files.PdfPageSource
import com.hienlh.ppm.app.ui.files.PdfPages
import com.hienlh.ppm.app.ui.files.clampPdfPage
import com.hienlh.ppm.app.ui.files.computePdfRenderFactor
import com.hienlh.ppm.app.ui.files.formatPdfPageCounter
import com.hienlh.ppm.app.ui.files.formatPdfZoomPercent
import com.hienlh.ppm.app.ui.files.parsePdfPageJump
import com.hienlh.ppm.app.ui.files.resolvePdfLoadState
import com.hienlh.ppm.app.ui.files.stepPdfZoom
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h844dp-xxhdpi")
class PhoneFilesTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    // ───────────────────────── PdfPages helpers ─────────────────────────

    @Test
    fun `pdf page helpers clamp pages, parse jump input, step zoom, and cap bitmap scale`() {
        assertEquals(0, clampPdfPage(1, 0))
        assertEquals(1, clampPdfPage(-3, 12))
        assertEquals(12, clampPdfPage(99, 12))
        assertEquals(3, clampPdfPage(3, 12))

        assertNull(parsePdfPageJump("", 12))
        assertNull(parsePdfPageJump("0", 12))
        assertNull(parsePdfPageJump("-2", 12))
        assertNull(parsePdfPageJump("abc", 12))
        assertEquals(7, parsePdfPageJump(" 7 ", 12))
        assertEquals(12, parsePdfPageJump("50", 12))

        assertEquals(1.25f, stepPdfZoom(1.0f, zoomIn = true), 0.001f)
        assertEquals(0.8f, stepPdfZoom(1.0f, zoomIn = false), 0.001f)
        assertEquals(PDF_MAX_ZOOM, stepPdfZoom(2.8f, zoomIn = true), 0.001f)
        assertEquals(PDF_MIN_ZOOM, stepPdfZoom(0.55f, zoomIn = false), 0.001f)

        assertEquals("3 / 12", formatPdfPageCounter(3, 12))
        assertEquals("84%", formatPdfZoomPercent(0.84f))

        val normalFactor = computePdfRenderFactor(PdfPages.Size(600, 800), requestedWidth = 1200)
        assertEquals(2.0, normalFactor, 0.001)

        val cappedFactor = computePdfRenderFactor(PdfPages.Size(1000, 2000), requestedWidth = 10_000)
        val renderedW = 1000 * cappedFactor
        val renderedH = 2000 * cappedFactor
        assertTrue(renderedH <= 4096.0 + 0.5)
        assertTrue(renderedW * renderedH <= 4_194_304.0 + 1.0)

        assertEquals(PdfLoadState.Loading, resolvePdfLoadState(error = null, pageCount = null))
        assertEquals(PdfLoadState.Ready(12), resolvePdfLoadState(error = null, pageCount = 12))
        assertTrue(resolvePdfLoadState(error = "Corrupt PDF", pageCount = null) is PdfLoadState.Error)
    }

    // ───────────────────────── PhoneFilesModels rules ─────────────────────────

    @Test
    fun `phone tree breadcrumbs, git priority, and menus follow the phone design rules`() {
        val rootCrumbs = buildPhoneTreeBreadcrumbs("ppm", "")
        assertEquals(1, rootCrumbs.size)
        assertEquals("ppm", rootCrumbs.single().label)
        assertTrue(rootCrumbs.single().isCurrent)

        val subCrumbs = buildPhoneTreeBreadcrumbs("ppm", "src/web/components")
        assertEquals(listOf("ppm", "src", "web", "components"), subCrumbs.map { it.label })
        assertEquals("src/web", subCrumbs[2].relativePath)
        assertTrue(subCrumbs.last().isCurrent)

        // Git priority: D > A > M > R > C > U
        assertEquals("U", normalizePhoneGitBadgeLetter("??"))
        assertEquals("D", resolveFolderGitPriority(listOf("M", "U", "D", "A")))
        assertEquals("A", resolveFolderGitPriority(listOf("M", "A", "U")))
        assertEquals("M", resolveFolderGitPriority(listOf("R", "C", "M", "U")))
        assertEquals("R", resolveFolderGitPriority(listOf("C", "R", "U")))
        assertEquals("1,284", formatFolderEntryCount(1284))

        // Overflow menus & long-press actions omit tablet-only items
        assertEquals(
            listOf("Reveal Active File", "Collapse All", "Refresh"),
            PhoneFilesOverflowAction.phoneMenu().map { it.label },
        )
        assertEquals(
            listOf("Open", "Copy Path", "Copy Full Path", "Download"),
            PhoneTreeLongPressAction.actionsFor(isDirectory = false).map { it.label },
        )
        assertEquals(
            listOf("Open in Terminal", "Copy Path", "Copy Full Path"),
            PhoneTreeLongPressAction.actionsFor(isDirectory = true).map { it.label },
        )
        assertEquals(
            listOf("Word wrap", "Reload from disk", "Download", "Copy Path"),
            PhoneEditorOverflowAction.phoneMenu().map { it.label },
        )
        // Complete (LSP) is excluded from PhoneEditorKeys
        assertFalse(PhoneEditorKeys.any { it.id == "complete" || it.label.equals("Complete", ignoreCase = true) })
    }

    @Test
    fun `merge conflicts, inline diff folding, and phone file view classifier match phone spec`() {
        val conflicted = """
            fun greet() {
            <<<<<<< HEAD
                println("hello")
            =======
                println("xin chao")
            >>>>>>> feature
            }
        """.trimIndent()
        assertEquals(1, countMergeConflicts(conflicted))
        assertEquals("1 conflict remaining", formatMergeConflictBannerTitle(1))
        assertEquals("2 conflicts remaining", formatMergeConflictBannerTitle(2))

        val diff = """
            diff --git a/src/server/index.ts b/src/server/index.ts
            --- a/src/server/index.ts
            +++ b/src/server/index.ts
            @@ -1,12 +1,13 @@
             line1
             line2
             line3
             line4
             line5
             line6
             line7
             line8
            -oldLine9
            +newLine9
            +newLine10
             tail1
        """.trimIndent()

        val folded = parsePhoneInlineDiff(diffText = diff, contextLines = 2)
        assertEquals(2, folded.addedCount)
        assertEquals(1, folded.removedCount)
        val foldRow = folded.rows.filterIsInstance<PhoneDiffRow.FoldedContext>().single()
        assertEquals("Show 4 unchanged lines", foldRow.label)

        val expanded = parsePhoneInlineDiff(
            diffText = diff,
            contextLines = 2,
            expandedFoldIds = setOf(foldRow.foldId),
        )
        assertTrue(expanded.rows.filterIsInstance<PhoneDiffRow.FoldedContext>().isEmpty())

        // Classifier
        assertEquals(PhoneFileViewKind.CODE, classifyPhoneFileView("src/web/app.tsx"))
        assertEquals(PhoneFileViewKind.IMAGE, classifyPhoneFileView("public/logo.png"))
        assertEquals(PhoneFileViewKind.PDF, classifyPhoneFileView("docs/architecture.pdf"))
        assertEquals(PhoneFileViewKind.MARKDOWN, classifyPhoneFileView("README.md"))
        assertEquals(PhoneFileViewKind.TABLET_ONLY_VIEWER, classifyPhoneFileView("data/sales-q3.xlsx"))
        assertEquals(PhoneFileViewKind.TABLET_ONLY_VIEWER, classifyPhoneFileView("data/users.csv"))
        assertEquals(PhoneFileViewKind.TABLET_ONLY_VIEWER, classifyPhoneFileView("notes/spec.docx"))
        assertEquals(PhoneFileViewKind.BINARY_UNREADABLE, classifyPhoneFileView("dist/ppm.bin"))
    }

    // ───────────────────────── Compose UI screens ─────────────────────────

    @Test
    fun `phone tree screen renders breadcrumbs, git badges, lazy folder loading, and bottom card`() {
        val rows = listOf(
            FileTreeRow(
                node = FileTreeNode(name = "src", path = "src", isDirectory = true, isIgnored = false),
                depth = 0,
            ),
            FileTreeRow(
                node = FileTreeNode(name = "chat.tsx", path = "src/chat.tsx", isDirectory = false, isIgnored = false),
                depth = 1,
            ),
            FileTreeRow(
                node = FileTreeNode(name = "node_modules", path = "node_modules", isDirectory = true, isIgnored = true),
                depth = 0,
            ),
        )

        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneFilesTreeContent(
                    projectName = "ppm",
                    screenState = PhoneFilesScreenState.Ready(),
                    rows = rows,
                    expandedPaths = setOf("src", "node_modules"),
                    loadingPaths = setOf("node_modules"),
                    folderEntryCounts = mapOf("node_modules" to 1284),
                    gitFileStatuses = mapOf("src/chat.tsx" to "M"),
                    gitFolderStatuses = mapOf("src" to "M"),
                    activeFilePath = "src/chat.tsx",
                    onRowClick = {},
                    onRowLongPress = {},
                    onRetry = {},
                    onGoToFile = {},
                    onRecentFiles = {},
                )
            }
        }

        compose.onNodeWithText("chat.tsx").assertExists()
        compose.onNodeWithText("1,284").assertExists()
        compose.onNodeWithText("Go to file…").assertExists()
        compose.onNodeWithText("Recent").assertExists()
    }

    @Test
    fun `phone editor screen renders changed-on-disk banner, merge conflict banner, and key row`() {
        val conflictContent = """
            <<<<<<< HEAD
            const a = 1;
            =======
            const a = 2;
            >>>>>>> branch
        """.trimIndent()

        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneEditorScreen(
                    filePath = "src/web/components/chat/chat-tab.tsx",
                    content = conflictContent,
                    dirty = true,
                    changedOnDisk = true,
                )
            }
        }

        compose.onNodeWithText("1 conflict remaining").assertExists()
        compose.onNodeWithText(MERGE_CONFLICT_RESOLVE_SUBTITLE).assertExists()
        compose.onNodeWithText("File changed on disk while you have unsaved edits.").assertExists()
        compose.onNodeWithText("Discard & Reload").assertExists()
        compose.onNodeWithText("Overwrite Disk").assertExists()
    }

    @Test
    fun `phone inline diff screen folds unchanged lines and expands on click`() {
        val diff = """
            @@ -1,13 +1,14 @@
             c1
             c2
             c3
             c4
             c5
             c6
             c7
             c8
             c9
             c10
             c11
            -old
            +new1
            +new2
        """.trimIndent()

        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneDiffScreen(
                    filePath = "src/server/index.ts",
                    diffText = diff,
                )
            }
        }

        compose.onNodeWithText("+2").assertExists()
        compose.onNodeWithText("−1").assertExists()
        compose.onNodeWithText("Show 5 unchanged lines").performClick()
        assertEquals(0, compose.onAllNodesWithText("Show 5 unchanged lines").fetchSemanticsNodes().size)
    }

    @Test
    fun `phone pdf viewer renders page-by-page canvas, bottom controls, and page jump sheet`() {
        val fakeSource = object : PdfPageSource {
            override val sizes: List<PdfPages.Size> = List(12) { PdfPages.Size(612, 792) }
            override fun render(index: Int, requestedWidth: Int): Bitmap =
                Bitmap.createBitmap(32, 42, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(AndroidColor.WHITE)
                }
        }

        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhonePdfPageSourceViewer(
                    filePath = "docs/ppm-architecture.pdf",
                    pages = fakeSource,
                    initialZoom = 0.84f,
                )
            }
        }

        compose.onNodeWithTag("pdf-page-by-page-canvas").assertExists()
        compose.onNodeWithTag("pdf-bottom-controls").assertExists()
        compose.onNodeWithText("84%").assertExists()

        // Tap the page counter in the bottom controls to open the page-jump sheet
        compose.onNodeWithTag("pdf-page-counter").performClick()
        compose.onNodeWithText("Go to page (1–12)").assertExists()

        // Tap Zoom in and verify percentage updates
        compose.onNode(hasContentDescription("Zoom in")).performClick()
        compose.onNodeWithText("105%").assertExists()
    }

    @Test
    fun `phone unsupported file screen shows tablet notice and download button`() {
        var downloads = 0
        var canDownload by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneFileTabScreen(
                    filePath = "data/sales-q3.xlsx",
                    sizeBytes = 100_761L,
                    onDownload = if (canDownload) ({ downloads++ }) else null,
                )
            }
        }

        compose.onNode(hasTestTag("phone-unsupported-file-screen")).assertExists()
        compose.onNodeWithText("sales-q3.xlsx").assertExists()
        compose.onNodeWithText(PHONE_UNSUPPORTED_FILE_LINE).assertExists()
        compose.onNodeWithText("Download").performClick()
        assertEquals(1, downloads)

        // Nothing to download with (no project file): no dead button.
        canDownload = false
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Download").fetchSemanticsNodes().isEmpty())
    }
}
