package com.hienlh.ppm.app.ui.design

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.filenameFromContentDisposition
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
import com.hienlh.ppm.state.design.PreviewPhase
import com.hienlh.ppm.state.design.SelectOption
import com.hienlh.ppm.state.design.TweakDef
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                id = "a1b2c3d4e5f6",
                number = 1,
                file = "index.html",
                anchor = CommentAnchor(
                    file = "index.html",
                    ppmId = 1,
                    tag = "h1",
                    cssPath = "section.hero > h1",
                ),
                body = "Increase heading contrast",
                resolved = false,
                createdAt = "2026-11-08T00:00:00Z",
                updatedAt = "2026-11-08T00:00:00Z",
            ),
        )
        val pinRects = mapOf(
            "a1b2c3d4e5f6" to PinRect(id = "a1b2c3d4e5f6", found = false),
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
                            cssPath = "section.hero > h1",
                            ppmId = 1,
                            tag = "h1",
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

        // 5. Export panel assertions: ZIP, HTML, Print, and Slides PPTX + filename sanitisation
        activePanel = "export"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Download ZIP (.zip)").assertIsDisplayed()
        composeRule.onNodeWithText("Export Slide Deck (.pptx)").performClick()
        assertTrue(exportedPptx)
        assertEquals("pitch-deck.zip", filenameFromContentDisposition("""attachment; filename="../../evil.zip"""", "pitch-deck.zip"))
        assertEquals("hero-v2.zip", filenameFromContentDisposition("""attachment; filename="hero-v2.zip"""", "pitch-deck.zip"))
    }

    @Test
    fun previewSelfHealOn404_clearsTokenRetriesOnceAndEntersDeadState() = runBlocking {
        val mintBodies = mutableListOf<String>()
        var failAll = false
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    request.method == "POST" && path == "/api/design-preview" -> {
                        val raw = request.body?.utf8().orEmpty()
                        mintBodies += raw
                        val obj = Json.parseToJsonElement(raw).jsonObject
                        val passedToken = obj["token"]?.jsonPrimitive?.content
                        val issued = if (failAll) {
                            "tok-dead"
                        } else if (passedToken == "tok-stale") {
                            "tok-stale"
                        } else {
                            "tok-fresh"
                        }
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{"token":"$issued","url":"/dp/$issued/index.html","expiresAt":999999}}""")
                            .build()
                    }
                    request.method == "HEAD" && path == "/dp/tok-stale/index.html" -> {
                        MockResponse.Builder().code(404).build()
                    }
                    request.method == "HEAD" && path == "/dp/tok-dead/index.html" -> {
                        MockResponse.Builder().code(404).build()
                    }
                    request.method == "HEAD" && path == "/dp/tok-fresh/index.html" -> {
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("X-PPM-Gen", "gen-2")
                            .build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        try {
            val baseUrl = server.url("/")
            val api = ApiClient(baseUrl = baseUrl, token = "test-token")

            // 1) First 404 clears the stale token, retries once with token = null, and recovers on 200.
            val recovered = loadPreviewWithSelfHeal(
                api = api,
                projectName = "demo",
                slug = "pitch-deck",
                entry = "index.html",
                initialToken = "tok-stale",
                initialRecoveredOnce = false,
                retryDelayMs = 0L,
            )
            assertEquals(PreviewPhase.LOADING, recovered.phase)
            assertEquals("tok-fresh", recovered.token)
            assertEquals("/dp/tok-fresh/index.html", recovered.url)
            assertEquals("gen-2", recovered.gen)
            assertTrue(recovered.hasRecoveredOnce)
            assertEquals(2, recovered.attempts)
            assertEquals(2, mintBodies.size)
            assertEquals(
                "tok-stale",
                Json.parseToJsonElement(mintBodies[0]).jsonObject["token"]?.jsonPrimitive?.content,
            )
            assertNull(Json.parseToJsonElement(mintBodies[1]).jsonObject["token"])

            // 2) When both attempts answer HTTP 404, token is cleared, one retry runs, then phase is DEAD.
            mintBodies.clear()
            failAll = true
            val dead = loadPreviewWithSelfHeal(
                api = api,
                projectName = "demo",
                slug = "pitch-deck",
                entry = "index.html",
                initialToken = "tok-stale",
                initialRecoveredOnce = false,
                retryDelayMs = 0L,
            )
            assertEquals(PreviewPhase.DEAD, dead.phase)
            assertNull(dead.token)
            assertNull(dead.url)
            assertNull(dead.gen)
            assertTrue(dead.hasRecoveredOnce)
            assertEquals(2, dead.attempts)
            assertEquals(2, mintBodies.size)
            assertEquals(
                "tok-stale",
                Json.parseToJsonElement(mintBodies[0]).jsonObject["token"]?.jsonPrimitive?.content,
            )
            assertNull(Json.parseToJsonElement(mintBodies[1]).jsonObject["token"])
        } finally {
            server.close()
        }
    }

    @Test
    fun saveStreamToDownloadsBelowAndroid10_writesSanitizedFileToLegacyDownloadsDir() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val payload = "PK\u0003\u0004-legacy-minSdk26-export".toByteArray(Charsets.UTF_8)
        val written = saveStreamToDownloads(
            context = context,
            rawFilename = "pitch-deck.zip",
            input = ByteArrayInputStream(payload),
            sdkInt = 26, // Android 8.0 (minSdk 26), below Android 10 (API 29 / Q)
        )
        assertEquals(payload.size.toLong(), written)
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        requireNotNull(downloadsDir)
        val savedFile = File(downloadsDir, "pitch-deck.zip")
        assertTrue(savedFile.exists())
        assertEquals(downloadsDir.canonicalPath, savedFile.parentFile?.canonicalPath)
        assertArrayEquals(payload, savedFile.readBytes())

        // Path traversal in rawFilename falls back to safe "design-export.bin" inside downloadsDir
        saveStreamToDownloads(
            context = context,
            rawFilename = "../../evil.zip",
            input = ByteArrayInputStream(payload),
            sdkInt = 26,
        )
        val fallbackFile = File(downloadsDir, "design-export.bin")
        assertTrue(fallbackFile.exists())
        assertEquals(downloadsDir.canonicalPath, fallbackFile.parentFile?.canonicalPath)
    }
}
