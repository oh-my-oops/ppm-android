package com.hienlh.ppm.app.ui.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.design.CommentAnchor
import com.hienlh.ppm.state.design.DesignComment
import com.hienlh.ppm.state.design.DesignDetail
import com.hienlh.ppm.state.design.DesignDeviceFrame
import com.hienlh.ppm.state.design.DesignKind
import com.hienlh.ppm.state.design.DesignLayoutOverride
import com.hienlh.ppm.state.design.DesignSnapshotInfo
import com.hienlh.ppm.state.design.DesignSummary
import com.hienlh.ppm.state.design.DesignVariant
import com.hienlh.ppm.state.design.ElementRect
import com.hienlh.ppm.state.design.PickedElement
import com.hienlh.ppm.state.design.PinRect
import com.hienlh.ppm.state.design.SelectOption
import com.hienlh.ppm.state.design.TweakDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class Phase10bDesignModeUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `renders design toolbar variants frames tools comments detached badge tweaks history and export`() {
        var activePanel by mutableStateOf("toolbar")
        var selectedVariant by mutableStateOf("index.html")
        var selectedFrame by mutableStateOf(DesignDeviceFrame.DESKTOP)
        var pickerOn by mutableStateOf(false)
        var transformOn by mutableStateOf(false)
        var handoffClicked by mutableStateOf(false)
        var sentToAiClicked by mutableStateOf(false)
        var appliedTweaks by mutableStateOf(false)
        var restoredSnapId by mutableStateOf<String?>(null)
        var exportedPptx by mutableStateOf(false)

        val detail = DesignDetail(
            summary = DesignSummary(
                slug = "pitch-deck",
                title = "Pitch Deck",
                kind = DesignKind.SLIDES,
                hasIndex = true,
                variants = listOf(
                    DesignVariant("index.html", "Main"),
                    DesignVariant("mobile.html", "Mobile"),
                ),
            ),
            files = listOf("index.html", "mobile.html"),
        )

        val comments = listOf(
            DesignComment(
                id = "c-1",
                number = 1,
                file = "index.html",
                anchor = CommentAnchor(
                    selector = "section.hero > h1",
                    ppmId = "ppm-1",
                    tag = "h1",
                    xPct = 40.0,
                    yPct = 20.0,
                ),
                body = "Increase heading contrast",
                resolved = false,
                createdAt = "2026-11-08T00:00:00Z",
                updatedAt = "2026-11-08T00:00:00Z",
            ),
        )
        val pinRects = mapOf(
            "c-1" to PinRect(id = "c-1", found = false),
        )

        val tweakDefs = listOf(
            TweakDef.Color(variable = "--accent", label = "Accent"),
            TweakDef.Range(variable = "--radius", label = "Radius", min = 0.0, max = 24.0, step = 4.0, unit = "px"),
            TweakDef.Select(
                variable = "--density",
                label = "Density",
                options = listOf(SelectOption("Compact", "8px"), SelectOption("Roomy", "16px")),
            ),
        )
        val savedTweaks = mapOf("--accent" to "#6366f1", "--radius" to "8px", "--density" to "8px")
        val currentTweaks = mutableStateMapOf("--radius" to "12px")

        val snapshots = listOf(
            DesignSnapshotInfo(
                id = "snap-1",
                turn = 1,
                label = "Initial hero layout",
                createdAt = "2026-11-08T00:00:00Z",
                fileCount = 2,
                totalBytes = 4096L,
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                when (activePanel) {
                    "toolbar" -> DesignHeaderToolbar(
                        detail = detail,
                        activeVariant = selectedVariant,
                        onSelectVariant = { selectedVariant = it },
                        deviceFrame = selectedFrame,
                        onSelectDeviceFrame = { selectedFrame = it },
                        zoom = 1f,
                        onZoomChange = {},
                        layoutOverride = DesignLayoutOverride.AUTO,
                        onSelectLayoutOverride = {},
                        pickerEnabled = pickerOn,
                        onTogglePicker = { pickerOn = !pickerOn },
                        transformModeOn = transformOn,
                        onToggleTransform = { transformOn = !transformOn },
                        undoCount = 2,
                        isStreaming = false,
                        onUndo = {},
                        openCommentsCount = 1,
                        sidePanel = DesignSidePanel.NONE,
                        onToggleSidePanel = {},
                        onBuildInNewChat = { handoffClicked = true },
                        onReload = {},
                    )
                    "comments" -> DesignCommentsPanel(
                        slug = "pitch-deck",
                        activeVariant = "index.html",
                        pickedElement = PickedElement(
                            selector = "section.hero > h1",
                            ppmId = "ppm-1",
                            tag = "h1",
                            xPct = 40.0,
                            yPct = 20.0,
                            rect = ElementRect(10.0, 20.0, 200.0, 40.0),
                        ),
                        comments = comments,
                        pinRects = pinRects,
                        onClose = {},
                        onAddComment = {},
                        onToggleResolve = {},
                        onDeleteComment = {},
                        onReplyComment = { _, _ -> },
                        onSendCommentsToAi = { sentToAiClicked = true },
                    )
                    "tweaks" -> DesignTweaksPanel(
                        activeVariant = "index.html",
                        defs = tweakDefs,
                        errors = listOf("tweaks[3]: invalid CSS variable"),
                        saved = savedTweaks,
                        live = savedTweaks,
                        current = currentTweaks,
                        onClose = {},
                        onChangeValue = { def, v -> currentTweaks[def.variable] = v },
                        onResetAll = { currentTweaks.clear() },
                        onApply = { appliedTweaks = true },
                        onAskAiAddTweaks = {},
                        onAskAiFixTweaks = {},
                    )
                    "history" -> DesignHistoryPanel(
                        snapshots = snapshots,
                        isStreaming = false,
                        onClose = {},
                        onRestore = { restoredSnapId = it.id },
                    )
                    "export" -> DesignExportPanel(
                        detail = detail,
                        activeVariant = "index.html",
                        onClose = {},
                        onDownloadZip = {},
                        onDownloadHtml = {},
                        onPrintPdf = {},
                        onOpenStandalone = {},
                        onExportPptx = { exportedPptx = true },
                    )
                }
            }
        }

        // 1. Toolbar assertions: variants, device frames, picker, transform, undo, handoff
        composeRule.onNodeWithText("Pitch Deck").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mobile").performClick()
        assertEquals("mobile.html", selectedVariant)
        composeRule.onNodeWithContentDescription("Phone").performClick()
        assertEquals(DesignDeviceFrame.PHONE, selectedFrame)
        composeRule.onNodeWithContentDescription("Picker").performClick()
        assertTrue(pickerOn)
        composeRule.onNodeWithContentDescription("Build in new chat").performClick()
        assertTrue(handoffClicked)

        // 2. Comments panel assertions: Detached badge & Send to AI
        activePanel = "comments"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Increase heading contrast").assertIsDisplayed()
        composeRule.onNodeWithText("Detached").assertIsDisplayed()
        composeRule.onNodeWithText("Send to AI (1 open)").performClick()
        assertTrue(sentToAiClicked)

        // 3. Tweaks panel assertions: controls & Apply
        activePanel = "tweaks"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Accent").assertIsDisplayed()
        composeRule.onNodeWithText("Radius").assertIsDisplayed()
        composeRule.onNodeWithText("Apply (1)").performClick()
        assertTrue(appliedTweaks)

        // 4. History panel assertions: Snapshot #1 & Restore
        activePanel = "history"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Initial hero layout").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Restore").performClick()
        assertEquals("snap-1", restoredSnapId)

        // 5. Export panel assertions: ZIP, HTML, Print, and Slides PPTX
        activePanel = "export"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Download ZIP (.zip)").assertIsDisplayed()
        composeRule.onNodeWithText("Export Slide Deck (.pptx)").performClick()
        assertTrue(exportedPptx)
    }
}
