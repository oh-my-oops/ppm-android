package com.hienlh.ppm.state.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `C12 release script requires version tag, mandatory cert sha256, apksigner, rejects debug cert, and passes PppmVersion`() {
        val scriptFile = listOf(
            File("../../scripts/release.sh"),
            File("../scripts/release.sh"),
            File("scripts/release.sh"),
        ).firstOrNull { it.exists() }?.canonicalFile
        assertNotNull("release.sh must exist", scriptFile)

        val tempRoot = Files.createTempDirectory("ppm-release-test").toFile()
        try {
            val fakeAndroidDir = File(tempRoot, "android").apply { mkdirs() }
            val releaseApkDir = File(fakeAndroidDir, "app/build/outputs/apk/release").apply { mkdirs() }
            val fakeApk = File(releaseApkDir, "app-release.apk").apply { writeText("fake-apk-bytes") }
            val gradlewArgsLog = File(tempRoot, "gradlew-args.txt")
            val fakeGradlew = File(tempRoot, "fake-gradlew.sh").apply {
                writeText(
                    """
                    #!/usr/bin/env bash
                    echo "$@" > "${gradlewArgsLog.absolutePath}"
                    exit 0
                    """.trimIndent(),
                )
                setExecutable(true)
            }
            val expectedSha = "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00"

            fun runRelease(
                args: List<String>,
                env: Map<String, String> = emptyMap(),
            ): Pair<Int, String> {
                val pb = ProcessBuilder(listOf("bash", scriptFile!!.absolutePath) + args)
                pb.directory(fakeAndroidDir)
                pb.redirectErrorStream(true)
                val environment = pb.environment()
                environment.remove("PPM_VERSION")
                environment.remove("PPM_RELEASE_CERT_SHA256")
                environment["PPM_ANDROID_DIR"] = fakeAndroidDir.absolutePath
                environment["GRADLEW_BIN"] = fakeGradlew.absolutePath
                environment["RELEASE_ASSETS_PY"] = "/nonexistent/release-assets.py"
                environment.putAll(env)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText()
                return proc.waitFor() to out
            }

            // 1. Fails when version is missing and HEAD has no android-vX.Y.Z tag
            val (noVersionCode, noVersionOut) = runRelease(
                args = listOf("--expected-cert-sha256", expectedSha),
            )
            assertNotEquals(0, noVersionCode)
            assertTrue(noVersionOut.contains("android-vX.Y.Z"))

            // 2. Fails when expected cert SHA-256 is omitted
            val (noCertCode, noCertOut) = runRelease(
                args = listOf("--version", "android-v1.2.3"),
            )
            assertNotEquals(0, noCertCode)
            assertTrue(noCertOut.contains("--expected-cert-sha256"))

            // 3. Fails when apksigner is missing
            val (noSignerCode, noSignerOut) = runRelease(
                args = listOf("--version", "android-v1.2.3", "--expected-cert-sha256", expectedSha),
                env = mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "ANDROID_HOME" to "/nonexistent-sdk",
                    "ANDROID_SDK_ROOT" to "/nonexistent-sdk",
                    "HOME" to tempRoot.absolutePath,
                ),
            )
            assertNotEquals(0, noSignerCode)
            assertTrue(noSignerOut.contains("apksigner not found"))

            // 4. Fails when apksigner verify exits non-zero
            val failingSigner = File(tempRoot, "apksigner-fail.sh").apply {
                writeText("#!/usr/bin/env bash\necho 'DOES NOT VERIFY' >&2\nexit 1\n")
                setExecutable(true)
            }
            val (verifyFailCode, _) = runRelease(
                args = listOf("--version", "android-v1.2.3", "--expected-cert-sha256", expectedSha),
                env = mapOf("APKSIGNER_BIN" to failingSigner.absolutePath),
            )
            assertNotEquals(0, verifyFailCode)

            // 5. Fails when signed with Android Debug cert even if SHA-256 matches
            val debugSigner = File(tempRoot, "apksigner-debug.sh").apply {
                writeText(
                    """
                    #!/usr/bin/env bash
                    echo "Verifies"
                    echo "Signer #1 certificate DN: C=US, O=Android, CN=Android Debug"
                    echo "Signer #1 certificate SHA-256 digest: $expectedSha"
                    exit 0
                    """.trimIndent(),
                )
                setExecutable(true)
            }
            val (debugCertCode, debugCertOut) = runRelease(
                args = listOf("--version", "android-v1.2.3", "--expected-cert-sha256", expectedSha),
                env = mapOf("APKSIGNER_BIN" to debugSigner.absolutePath),
            )
            assertNotEquals(0, debugCertCode)
            assertTrue(debugCertOut.contains("debug certificate"))

            // 6. Succeeds with valid release cert and passes -PppmVersion=1.2.3 to gradlew
            val releaseSigner = File(tempRoot, "apksigner-release.sh").apply {
                writeText(
                    """
                    #!/usr/bin/env bash
                    echo "Verifies"
                    echo "Signer #1 certificate DN: CN=PPM Release, O=HienLH"
                    echo "Signer #1 certificate SHA-256 digest: $expectedSha"
                    exit 0
                    """.trimIndent(),
                )
                setExecutable(true)
            }
            val (okCode, okOut) = runRelease(
                args = listOf("--version", "android-v1.2.3", "--expected-cert-sha256", expectedSha),
                env = mapOf("APKSIGNER_BIN" to releaseSigner.absolutePath),
            )
            assertEquals(okOut, 0, okCode)
            assertTrue(gradlewArgsLog.readText().contains("-PppmVersion=1.2.3"))
            assertTrue(File("${fakeApk.absolutePath}.sha256").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
