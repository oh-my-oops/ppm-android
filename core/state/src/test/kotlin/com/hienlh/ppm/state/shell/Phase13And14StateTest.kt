package com.hienlh.ppm.state.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase13And14StateTest {
    @Test
    fun `resolveWindowWideMode applies hysteresis only during active resize and preserves state on width 0`() {
        // Normal (non-resizing) threshold is strictly >= 768 dp
        assertTrue(resolveWindowWideMode(widthDp = 768, previousWide = false, isResizing = false))
        assertFalse(resolveWindowWideMode(widthDp = 767, previousWide = true, isResizing = false))

        // Width 0 preserves previousWide
        assertTrue(resolveWindowWideMode(widthDp = 0, previousWide = true, isResizing = false))
        assertFalse(resolveWindowWideMode(widthDp = 0, previousWide = false, isResizing = true))

        // During active freeform border resize, 768 +/- 12 dp hysteresis band prevents layout thrashing
        assertFalse(resolveWindowWideMode(widthDp = 775, previousWide = false, isResizing = true))
        assertTrue(resolveWindowWideMode(widthDp = 780, previousWide = false, isResizing = true))
        assertTrue(resolveWindowWideMode(widthDp = 760, previousWide = true, isResizing = true))
        assertFalse(resolveWindowWideMode(widthDp = 755, previousWide = true, isResizing = true))
    }

    @Test
    fun `summarizeFrameDurationsMs computes 3x3 panel jank stats and budget`() {
        val cleanFrames = List(100) { 12.0 }
        val cleanSummary = summarizeFrameDurationsMs(cleanFrames)
        assertEquals(100, cleanSummary.totalFrames)
        assertEquals(0, cleanSummary.jankyFrames)
        assertTrue(cleanSummary.meetsTargetBudget)

        val jankyFrames = List(90) { 14.0 } + List(10) { 32.0 }
        val jankySummary = summarizeFrameDurationsMs(jankyFrames)
        assertEquals(10, jankySummary.jankyFrames)
        assertEquals(10.0, jankySummary.jankPercent, 0.01)
        assertFalse(jankySummary.meetsTargetBudget)
    }

    @Test
    fun `classifyExternalDrop distinguishes cross-app files, text, and uri drops`() {
        val fileDrop = classifyExternalDrop(
            mimeTypes = listOf("image/png"),
            items = listOf(
                ExternalDropItem(uriString = "content://com.android.providers.media.documents/document/image%3A10"),
                ExternalDropItem(uriString = "content://com.android.providers.media.documents/document/image%3A11"),
                ExternalDropItem(uriString = "content://com.android.providers.media.documents/document/image%3A12"),
            ),
            isCrossApp = true,
        )
        assertEquals(ExternalDropKind.FILES, fileDrop.kind)
        assertEquals(3, fileDrop.fileUris.size)
        assertTrue(fileDrop.needsUriPermission)
        assertTrue(canDestinationAcceptDrop(DropDestinationKind.OS_EXPLORER, fileDrop))
        assertTrue(canDestinationAcceptDrop(DropDestinationKind.PROJECT_FILES_TREE, fileDrop))
        assertTrue(canDestinationAcceptDrop(DropDestinationKind.CHAT_COMPOSER, fileDrop))

        val textDrop = classifyExternalDrop(
            mimeTypes = listOf("text/plain"),
            items = listOf(ExternalDropItem(text = "fun main() = println(\"hello\")")),
            isCrossApp = false,
        )
        assertEquals(ExternalDropKind.TEXT, textDrop.kind)
        assertEquals("fun main() = println(\"hello\")", textDrop.textPayload)
        assertFalse(textDrop.needsUriPermission)
        assertFalse(canDestinationAcceptDrop(DropDestinationKind.OS_EXPLORER, textDrop))
        assertTrue(canDestinationAcceptDrop(DropDestinationKind.CODE_EDITOR, textDrop))
        assertTrue(canDestinationAcceptDrop(DropDestinationKind.TERMINAL, textDrop))
    }

    @Test
    fun `computeFoldableSplit detects book and tabletop postures around hinge`() {
        // Flat when no hinge or non-separating
        val flat = computeFoldableSplit(hinge = null, windowWidthPx = 2200, windowHeightPx = 1800)
        assertEquals(FoldablePosture.FLAT, flat.posture)

        // Book posture (vertical separating hinge at center with 24px full occlusion)
        val bookHinge = HingeBounds(
            leftPx = 1088,
            topPx = 0,
            rightPx = 1112,
            bottomPx = 1800,
            orientation = HingeOrientation.VERTICAL,
            isSeparating = true,
            occlusion = HingeOcclusion.FULL,
        )
        val book = computeFoldableSplit(bookHinge, windowWidthPx = 2200, windowHeightPx = 1800)
        assertEquals(FoldablePosture.BOOK, book.posture)
        assertEquals(0.5f, book.bookLeftFraction, 0.01f)
        assertEquals(24, book.hingeGapPx)

        // Tabletop posture (horizontal separating hinge at y=900)
        val tabletopHinge = HingeBounds(
            leftPx = 0,
            topPx = 890,
            rightPx = 1800,
            bottomPx = 910,
            orientation = HingeOrientation.HORIZONTAL,
            isSeparating = true,
            occlusion = HingeOcclusion.NONE,
        )
        val tabletop = computeFoldableSplit(tabletopHinge, windowWidthPx = 1800, windowHeightPx = 1800)
        assertEquals(FoldablePosture.TABLETOP, tabletop.posture)
        assertEquals(890, tabletop.tabletopTopHeightPx)
        assertEquals(890, tabletop.tabletopBottomHeightPx)
    }

    @Test
    fun `OnboardingState tracks goals, evidence, Escape pause, Quick Orientation, and never auto-sends prompts`() {
        var state = OnboardingState(
            active = true,
            familiarity = OnboardingFamiliarity.BEGINNER,
            selectedGoal = OnboardingGoalId.AI,
        )
        assertEquals("ai.project", state.currentStep?.id)

        // Evidence from server satisfies project + chat + history steps
        state = state.mergeEvidence(
            OnboardingEvidence(
                hasProjectSelected = true,
                chatSessionCount = 3,
            ),
        )
        assertTrue("ai.project" in state.completedSteps)
        assertTrue("ai.chat" in state.completedSteps)
        assertTrue("ai.history" in state.completedSteps)
        assertEquals("ai.send", state.currentStep?.id)

        // Suggested prompt only updates draft text, never sends
        val stepPrompt = state.currentStep?.suggestedPrompt
        assertNotNull(stepPrompt)
        val draft = applySuggestedPromptToDraft("", stepPrompt!!)
        assertTrue(draft.contains("Summarize the architecture"))

        // Escape pauses guide
        state = state.pauseOnEscape()
        assertTrue(state.paused)

        // Quick Orientation 2 pages (0 -> 1 -> null)
        state = state.openQuickOrientation()
        assertEquals(0, state.quickOrientationPage)
        state = state.nextOrientationPage()
        assertEquals(1, state.quickOrientationPage)
        state = state.nextOrientationPage()
        assertNull(state.quickOrientationPage)

        // Round-trip JSON persistence
        val roundTripped = OnboardingState.fromJson(state.toJson())
        assertEquals(state.completedSteps, roundTripped.completedSteps)
        assertEquals(state.familiarity, roundTripped.familiarity)
    }

    @Test
    fun `mobile ergonomics, WCAG contrast, font scaling, and Phase 14 exit crash report work`() {
        assertTrue(meetsMobileTouchTarget(44, 44))
        assertFalse(meetsMobileTouchTarget(36, 44))
        assertTrue(isInBottomThumbZone(centerYPx = 600, screenHeightPx = 800))
        assertFalse(isInBottomThumbZone(centerYPx = 200, screenHeightPx = 800))

        // Dark text on light background meets WCAG AA (>= 4.5)
        val ratio = wcagContrastRatio(0x0F172A, 0xF8FAFC)
        assertTrue(ratio >= MobileErgonomicsSpec.WCAG_AA_NORMAL_CONTRAST)

        // Reading role scales with system font size; code/terminal/grid stays fixed at 1.0f
        assertEquals(1.35f, effectiveFontScaleForRole(SurfaceFontRole.READING, 1.35f), 0.001f)
        assertEquals(1.0f, effectiveFontScaleForRole(SurfaceFontRole.CODE_FIXED, 1.5f), 0.001f)

        // Protocol compatibility check
        val compat = checkProtocolCompatibility(
            appProtocolVersion = 2,
            serverProtocolVersion = 1,
            serverCapabilities = setOf("chat"),
            requiredCapabilities = setOf("chat", "design"),
        )
        assertEquals(ProtocolCompatStatus.SERVER_UPDATE_RECOMMENDED, compat.status)
        assertTrue(compat.bannerMessage!!.contains("Update PPM on this machine"))

        // ApplicationExitInfo crash markdown formatting
        val crash = PreviousExitCrashInfo(
            reasonCode = 4, // REASON_CRASH
            reasonName = "REASON_CRASH",
            timestampMs = 1762560000000L,
            processName = "com.hienlh.ppm",
            description = "NullPointerException in Sample",
            traceSnippet = "java.lang.NullPointerException: boom\n\tat com.hienlh.ppm.Sample.run(Sample.kt:12)",
        )
        assertTrue(crash.isActionableCrashOrAnr)
        val md = crash.toBugReportMarkdown("1.0.0", 100, "Pixel Tablet")
        assertTrue(md.contains("REASON_CRASH"))
        assertTrue(md.contains("NullPointerException: boom"))
    }
}
