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
    fun `OnboardingState tracks goals, server evidence, Escape pause, Quick Orientation, and JSON string round-trip`() {
        var state = OnboardingState(
            active = true,
            familiarity = OnboardingFamiliarity.BEGINNER,
            selectedGoal = OnboardingGoalId.AI,
        )
        assertEquals("ai.project", state.currentStep?.id)

        // Evidence from server and open tabs satisfies project + chat + history + explore + developer steps
        state = state.mergeEvidence(
            OnboardingEvidence(
                hasProjectSelected = true,
                projectFileCount = 12,
                chatSessionCount = 3,
                hasOpenedFileTab = true,
                hasOpenedTerminalTab = true,
                hasOpenedGitTab = true,
            ),
        )
        assertTrue("ai.project" in state.completedSteps)
        assertTrue("ai.chat" in state.completedSteps)
        assertTrue("ai.history" in state.completedSteps)
        assertTrue("explore.file" in state.completedSteps)
        assertTrue("developer.terminal" in state.completedSteps)
        assertTrue("developer.git" in state.completedSteps)
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

        // Round-trip JSON string persistence (used by PrefsStore "learn_ppm.state")
        val rawJson = state.toJson().toString()
        val roundTripped = OnboardingState.fromJsonString(rawJson)
        assertEquals(state.completedSteps, roundTripped.completedSteps)
        assertEquals(state.familiarity, roundTripped.familiarity)
        assertEquals(state.paused, roundTripped.paused)
    }

    @Test
    fun `PreviousExitCrashInfo formats markdown and shouldShowCrashExit respects persisted dismissal timestamp`() {
        val crash = PreviousExitCrashInfo(
            reasonCode = 4, // REASON_CRASH
            reasonName = "REASON_CRASH",
            timestampMs = 1762560000000L,
            processName = "com.hienlh.ppm",
            description = "NullPointerException in Sample",
            traceSnippet = "java.lang.NullPointerException: boom\n\tat com.hienlh.ppm.Sample.run(Sample.kt:12)",
        )
        assertTrue(crash.isActionableCrashOrAnr)
        assertTrue(shouldShowCrashExit(crash, dismissedTimestampMs = null))
        assertFalse(shouldShowCrashExit(crash, dismissedTimestampMs = 1762560000000L))
        assertFalse(shouldShowCrashExit(crash, dismissedTimestampMs = 1762560000500L))
        assertTrue(shouldShowCrashExit(crash, dismissedTimestampMs = 1762559999999L))

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
