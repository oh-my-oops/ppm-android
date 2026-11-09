package com.hienlh.ppm.app.ui.remotedesktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.tablet.Sidebar
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.remotedesktop.HostMode
import com.hienlh.ppm.state.remotedesktop.HostResolutions
import com.hienlh.ppm.state.remotedesktop.RdSize
import com.hienlh.ppm.state.remotedesktop.RemoteDesktopCapabilities
import com.hienlh.ppm.state.remotedesktop.RemoteDesktopRequirement
import com.hienlh.ppm.state.remotedesktop.RemoteDisplay
import com.hienlh.ppm.state.remotedesktop.RequirementAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class Phase11bUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `RemoteDesktopWarningGate toggles dont show again and invokes continue and cancel`() {
        var dontShowAgain = false
        var continued = false
        var cancelled = false

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                RemoteDesktopWarningGate(
                    dontShowAgain = dontShowAgain,
                    onToggleDontShowAgain = { dontShowAgain = it },
                    onCancel = { cancelled = true },
                    onContinue = { continued = true },
                )
            }
        }

        composeRule.onNodeWithTag("remote-desktop-warning-gate").assertIsDisplayed()
        composeRule.onNodeWithText("Remote Desktop").assertIsDisplayed()
        composeRule.onNodeWithText("BETA").assertIsDisplayed()

        composeRule.onNodeWithTag("remote-desktop-warning-dont-show").performClick()
        assertTrue(dontShowAgain)

        composeRule.onNodeWithTag("remote-desktop-warning-continue").performClick()
        assertTrue(continued)

        composeRule.onNodeWithTag("remote-desktop-warning-cancel").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `RemoteDesktopReadinessGate renders requirements list and allows view only continue when videoReady`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)
        var viewOnlyContinued = false

        val caps = RemoteDesktopCapabilities(
            platform = "linux",
            videoReady = true,
            inputReady = false,
            requirements = listOf(
                RemoteDesktopRequirement(
                    id = "ffmpeg",
                    title = "FFmpeg",
                    ok = true,
                    gates = "video",
                    detail = "/usr/bin/ffmpeg",
                    actions = emptyList(),
                ),
                RemoteDesktopRequirement(
                    id = "xdotool",
                    title = "xdotool (input control)",
                    ok = false,
                    gates = "input",
                    detail = "Install xdotool to send mouse and keyboard input.",
                    actions = listOf(
                        RequirementAction.Terminal(label = "Run in Terminal", command = "sudo apt install -y xdotool"),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                RemoteDesktopReadinessGate(
                    model = model,
                    initialCaps = caps,
                    onCancel = {},
                ) {
                    viewOnlyContinued = true
                    BasicText("Viewer Ready")
                }
            }
        }

        composeRule.onNodeWithTag("remote-desktop-readiness-gate").assertIsDisplayed()
        composeRule.onNodeWithText("FFmpeg").assertIsDisplayed()
        composeRule.onNodeWithText("xdotool (input control)").assertIsDisplayed()
        composeRule.onNodeWithText("Continue (view only)").assertIsDisplayed()

        composeRule.onNodeWithTag("remote-desktop-readiness-continue").performClick()
        composeRule.waitForIdle()
        assertTrue(viewOnlyContinued)
        composeRule.onNodeWithText("Viewer Ready").assertIsDisplayed()
    }

    @Test
    fun `RemoteDesktopTabletToolbar and Mobile controls render and trigger callbacks`() {
        var selectedDisplay: String? = "0"
        var selectedViewStyle = "fit-window"
        var customDialogOpened = false
        var statsVisible = true
        val sentMessages = mutableListOf<JsonElement>()

        val caps = RemoteDesktopCapabilities(
            platform = "linux",
            videoReady = true,
            inputReady = true,
            displays = listOf(
                RemoteDisplay(id = "0", label = "eDP-1", width = 1920, height = 1080, primary = true),
                RemoteDisplay(id = "1", label = "HDMI-1", width = 2560, height = 1440, primary = false),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Column {
                    RemoteDesktopTabletToolbar(
                        caps = caps,
                        currentDisplayId = selectedDisplay,
                        onSelectDisplay = { selectedDisplay = it },
                        qualityChoice = "balanced",
                        serverPreset = "balanced",
                        qualityHeld = 0.0,
                        onSetQuality = {},
                        onOpenCustomQuality = { customDialogOpened = true },
                        codec = "h264_nvenc",
                        onSetCodec = {},
                        hostModeId = null,
                        hostOriginalModeId = null,
                        resolutionError = null,
                        onSetHostMode = {},
                        viewStyle = selectedViewStyle,
                        customScale = 1.0,
                        onSetViewStyle = { selectedViewStyle = it },
                        onSetCustomScale = {},
                        showCursor = true,
                        onSetShowCursor = {},
                        audioOn = false,
                        onSetAudioOn = {},
                        privacyOn = false,
                        privacyError = null,
                        onSetPrivacyOn = {},
                        clipboardEnabled = true,
                        onSetClipboardEnabled = {},
                        statsVisible = statsVisible,
                        onToggleStats = { statsVisible = !statsVisible },
                        recording = false,
                        recordingElapsedSec = 0,
                        onToggleRecording = {},
                        onScreenshot = {},
                    )
                    RemoteDesktopStatsOverlay(
                        frameSize = RdSize(1920, 1080),
                        getFrameCount = { 120 },
                        getTotalBytes = { 512_000L },
                    )
                    RemoteDesktopMobileKeyBarView(
                        onSendMessage = { sentMessages += it },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("remote-desktop-toolbar").assertIsDisplayed()
        composeRule.onNodeWithTag("remote-desktop-stats-overlay").assertIsDisplayed()
        composeRule.onNodeWithText("1920×1080").assertIsDisplayed()

        // Switch display
        composeRule.onNodeWithTag("remote-desktop-display-trigger").performClick()
        composeRule.onNodeWithTag("remote-desktop-display-1").performClick()
        assertEquals("1", selectedDisplay)

        // Open settings menu and click Custom… quality
        composeRule.onNodeWithTag("remote-desktop-settings-trigger").performClick()
        composeRule.onNodeWithTag("remote-desktop-settings-menu").assertIsDisplayed()
        composeRule.onNodeWithText("Custom…").performClick()
        assertTrue(customDialogOpened)

        // Tap Esc on mobile key bar
        composeRule.onNodeWithTag("rd-key-Esc").performClick()
        assertTrue(sentMessages.any { it.jsonObject["code"].string == "Escape" })
    }

    @Test
    fun `RemoteDesktopViewer sizes SurfaceView to letterboxed rect instead of stretching full height`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(width = 800.dp, height = 800.dp)) {
                    RemoteDesktopViewer(model = model)
                }
            }
        }
        composeRule.waitForIdle()

        val stageBounds = composeRule.onNodeWithTag("remote-desktop-stage").fetchSemanticsNode().boundsInRoot
        val surfaceBounds = composeRule.onNodeWithTag("remote-desktop-surface").fetchSemanticsNode().boundsInRoot
        assertEquals(stageBounds.width, surfaceBounds.width, 2f)
        // 1920x1080 (16:9) inside a square stage must be letterboxed to 9/16 of stage height, not stretched to full height
        assertEquals(stageBounds.width * 9f / 16f, surfaceBounds.height, 4f)
        assertTrue(surfaceBounds.height < stageBounds.height - 100f)
    }

    @Test
    fun `H264SurfaceDecoder and OpusAudioPlayer release quits worker HandlerThreads`() {
        val decoder = H264SurfaceDecoder()
        val audioPlayer = OpusAudioPlayer()

        decoder.configure()
        audioPlayer.decodePacket(byteArrayOf(0x01, 0x02), 0, 2)
        assertTrue(decoder.isWorkerAlive)
        assertTrue(audioPlayer.isWorkerAlive)

        decoder.release()
        audioPlayer.release()

        val deadline = System.currentTimeMillis() + 2_000L
        while ((decoder.isWorkerAlive || audioPlayer.isWorkerAlive) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
        assertEquals(false, decoder.isWorkerAlive)
        assertEquals(false, audioPlayer.isWorkerAlive)

        // Late arrivals after release() must not resurrect a worker thread
        assertEquals(false, decoder.enqueueAccessUnit(byteArrayOf(0, 0, 0, 1, 0x65), 0, 5, isKeyframe = true))
        decoder.configure()
        decoder.reset()
        audioPlayer.decodePacket(byteArrayOf(0x01, 0x02), 0, 2)
        audioPlayer.configure()
        audioPlayer.reset()
        assertEquals(false, decoder.isWorkerAlive)
        assertEquals(false, audioPlayer.isWorkerAlive)
    }

    @Test
    fun `OpusAudioPlayer writes PCM with AudioTrack WRITE_BLOCKING outside lock so decodePacket does not stall`() {
        val enteredWrite = java.util.concurrent.CountDownLatch(1)
        val allowWriteToFinish = java.util.concurrent.CountDownLatch(1)
        var capturedMode = -1
        var capturedSize = 0
        val audioPlayer = OpusAudioPlayer(
            pcmDecoder = { listOf(ByteArray(960) { 0x11 }) },
        )
        val track = object : android.media.AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            48_000,
            android.media.AudioFormat.CHANNEL_OUT_STEREO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            4096,
            MODE_STREAM,
        ) {
            override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, writeMode: Int): Int {
                capturedMode = writeMode
                capturedSize = sizeInBytes
                enteredWrite.countDown()
                allowWriteToFinish.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return sizeInBytes
            }
        }
        try {
            audioPlayer.attachAudioTrack(track)
            audioPlayer.decodePacket(byteArrayOf(0x01, 0x02), 0, 2)
            assertTrue(enteredWrite.await(2, java.util.concurrent.TimeUnit.SECONDS))

            // While AudioTrack.write is blocked on the worker thread, decodePacket on another thread must return promptly
            val decodeDone = java.util.concurrent.CountDownLatch(1)
            val readerThread = Thread {
                audioPlayer.decodePacket(byteArrayOf(0x03, 0x04), 0, 2)
                decodeDone.countDown()
            }.apply { start() }
            assertTrue(
                "decodePacket blocked while AudioTrack.write was in progress",
                decodeDone.await(500, java.util.concurrent.TimeUnit.MILLISECONDS),
            )
            allowWriteToFinish.countDown()
            readerThread.join(1_000)

            assertEquals(960, capturedSize)
            assertEquals(android.media.AudioTrack.WRITE_BLOCKING, capturedMode)
        } finally {
            allowWriteToFinish.countDown()
            audioPlayer.release()
            track.release()
        }
    }

    @Test
    fun `H264SurfaceDecoder and OpusAudioPlayer reset without worker releases inline and with worker posts to worker thread`() {
        val releaseThreads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val decoder = object : H264SurfaceDecoder() {
            override fun releaseCodecLocked() {
                releaseThreads += Thread.currentThread().name
                super.releaseCodecLocked()
            }
        }
        val audioPlayer = OpusAudioPlayer()
        try {
            // Calling reset() before any worker is started must release inline and NOT spawn a HandlerThread
            decoder.reset()
            audioPlayer.reset()
            assertEquals(false, decoder.isWorkerAlive)
            assertEquals(false, audioPlayer.isWorkerAlive)
            assertEquals(1, releaseThreads.size)

            // Once the worker is alive, reset() posts releaseCodecLocked() to the worker thread
            decoder.configure()
            assertTrue(decoder.isWorkerAlive)
            val beforeCount = releaseThreads.size
            decoder.reset()
            val deadline = System.currentTimeMillis() + 2_000L
            while (releaseThreads.size <= beforeCount && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L)
            }
            assertEquals("ppm-rd-video-decoder", releaseThreads.last())
        } finally {
            decoder.release()
            audioPlayer.release()
        }
    }

    @Test
    fun `RemoteDesktopViewer receives hardware keys without focusing hidden input and drops both halves of Ctrl+V`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("test", "hello from clipboard"))

        val sent = mutableListOf<JsonElement>()
        model.remoteDesktopSender = { sent += it }
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(width = 800.dp, height = 600.dp)) {
                    RemoteDesktopViewer(
                        model = model,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Hardware KeyA down & up on stage (without focusing the 1dp soft-keyboard field)
        composeRule.onNodeWithTag("remote-desktop-window").performKeyPress(
            androidx.compose.ui.input.key.KeyEvent(
                KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ),
        )
        composeRule.onNodeWithTag("remote-desktop-window").performKeyPress(
            androidx.compose.ui.input.key.KeyEvent(
                KeyEvent(0L, 10L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0),
            ),
        )
        assertTrue(sent.any { it.jsonObject["type"].string == "key" && it.jsonObject["code"].string == "KeyA" && it.jsonObject["down"].bool == true })
        assertTrue(sent.any { it.jsonObject["type"].string == "key" && it.jsonObject["code"].string == "KeyA" && it.jsonObject["down"].bool == false })

        // Ctrl+V down sends clipboard paste, and BOTH KeyDown and KeyUp of KeyV are dropped when clipboardSync is true
        sent.clear()
        composeRule.onNodeWithTag("remote-desktop-window").performKeyPress(
            androidx.compose.ui.input.key.KeyEvent(
                KeyEvent(0L, 20L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
            ),
        )
        composeRule.onNodeWithTag("remote-desktop-window").performKeyPress(
            androidx.compose.ui.input.key.KeyEvent(
                KeyEvent(0L, 30L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
            ),
        )
        assertEquals(1, sent.size)
        assertEquals("clipboard", sent[0].jsonObject["type"].string)
        assertEquals("hello from clipboard", sent[0].jsonObject["text"].string)
        assertEquals(true, sent[0].jsonObject["paste"].bool)
        assertTrue(sent.none { it.jsonObject["type"].string == "key" && it.jsonObject["code"].string == "KeyV" })
    }

    @Test
    fun `RemoteDesktop preferences persist across AppModel recreation and warning gate dismissal is saved`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)
        model.setRemoteDesktopWarningDismissedPref(false)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                RemoteDesktopAppBody(
                    model = model,
                    initialCaps = RemoteDesktopCapabilities(
                        platform = "linux",
                        videoReady = true,
                        inputReady = true,
                    ),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote-desktop-warning-gate").assertIsDisplayed()
        composeRule.onNodeWithTag("remote-desktop-warning-dont-show").performClick()
        composeRule.onNodeWithTag("remote-desktop-warning-continue").performClick()
        composeRule.waitForIdle()
        assertTrue(model.remoteDesktopWarningDismissed)

        model.setRemoteDesktopQualityPref("low")
        model.setRemoteDesktopViewStylePref("custom")
        model.setRemoteDesktopCustomScalePref(1.5)
        model.setRemoteDesktopCustomQualityPref(percent = 120, fps = 45, more = true)
        model.setRemoteDesktopShowCursorPref(false)
        model.setRemoteDesktopClipboardSyncPref(false)
        model.setRemoteDesktopCodecPref("h264_nvenc")
        model.setRemoteDesktopStatsVisiblePref(true)

        val reloaded = AppModel(context)
        assertTrue(reloaded.remoteDesktopWarningDismissed)
        assertTrue(reloaded.remoteDesktopStatsVisible)
        assertEquals("low", reloaded.remoteDesktopQuality)
        assertEquals("custom", reloaded.remoteDesktopViewStyle)
        assertEquals(1.5, reloaded.remoteDesktopCustomScale, 1e-6)
        assertEquals(120, reloaded.remoteDesktopCustomQualityPercent)
        assertEquals(45, reloaded.remoteDesktopCustomFps)
        assertEquals(true, reloaded.remoteDesktopCustomQualityMore)
        assertEquals(false, reloaded.remoteDesktopShowCursor)
        assertEquals(false, reloaded.remoteDesktopClipboardSync)
        assertEquals("h264_nvenc", reloaded.remoteDesktopCodec)
    }

    @Test
    fun `Fit local uses device screen dimensions and mobile settings panel renders Fit local and resolutionError and no-audio recording label`() {
        val caps = RemoteDesktopCapabilities(
            platform = "linux",
            videoReady = true,
            inputReady = true,
            resolutions = HostResolutions(
                output = "DP-1",
                modes = listOf(
                    HostMode(id = "m1", width = 1920, height = 1080, refresh = 60, current = true, preferred = true),
                    HostMode(id = "m2", width = 1280, height = 800, refresh = 60, current = false, preferred = false),
                    HostMode(id = "m3", width = 800, height = 1280, refresh = 60, current = false, preferred = false),
                ),
            ),
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val density = context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        val shadowDisplay = org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowDisplay.getDefaultDisplay()).apply {
            setRealWidth(1280 * density)
            setRealHeight(800 * density)
        }
        var configuration by mutableStateOf(
            android.content.res.Configuration(context.resources.configuration).apply {
                orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
            },
        )
        var requestedMode: String? = null

        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalConfiguration provides configuration,
            ) {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    Column {
                        RemoteDesktopTabletToolbar(
                            caps = caps,
                            currentDisplayId = "0",
                            onSelectDisplay = {},
                            qualityChoice = "balanced",
                            serverPreset = "balanced",
                            qualityHeld = 1.0,
                            onSetQuality = {},
                            onOpenCustomQuality = {},
                            codec = null,
                            onSetCodec = {},
                            hostModeId = "m1",
                            hostOriginalModeId = null,
                            resolutionError = null,
                            onSetHostMode = { requestedMode = it },
                            viewStyle = "adaptive",
                            customScale = 1.0,
                            onSetViewStyle = {},
                            onSetCustomScale = {},
                            showCursor = true,
                            onSetShowCursor = {},
                            audioOn = false,
                            onSetAudioOn = {},
                            privacyOn = false,
                            privacyError = null,
                            onSetPrivacyOn = {},
                            clipboardEnabled = true,
                            onSetClipboardEnabled = {},
                            statsVisible = false,
                            onToggleStats = {},
                            recording = false,
                            recordingElapsedSec = 0,
                            onToggleRecording = {},
                            onScreenshot = {},
                        )
                        RemoteDesktopMobileSettingsPanel(
                            caps = caps,
                            statsVisible = false,
                            onToggleStats = {},
                            showCursor = true,
                            onSetShowCursor = {},
                            clipboardEnabled = true,
                            onSetClipboardEnabled = {},
                            audioOn = false,
                            onSetAudioOn = {},
                            privacyOn = false,
                            privacyError = null,
                            onSetPrivacyOn = {},
                            hostModeId = "m1",
                            hostOriginalModeId = null,
                            resolutionError = "The host refused the mode.",
                            onSetHostMode = { requestedMode = it },
                            recording = false,
                            onToggleRecording = {},
                            onScreenshot = {},
                        )
                    }
                }
            }
        }

        // Mobile settings panel shows Fit local, resolutionError, and "Start recording (no audio)"
        composeRule.onNodeWithTag("remote-desktop-mobile-resolution-fit-local").assertIsDisplayed()
        composeRule.onNodeWithTag("remote-desktop-mobile-resolution-error").assertIsDisplayed()
        composeRule.onNodeWithText("The host refused the mode.").assertIsDisplayed()
        composeRule.onNodeWithText("Start recording (no audio)").assertIsDisplayed()

        // Tablet toolbar settings menu shows Fit local 1280×800 (`remote-desktop-toolbar.tsx:285`: no spaces around ×, no parentheses)
        composeRule.onNodeWithTag("remote-desktop-settings-trigger").performClick()
        composeRule.onNodeWithTag("remote-desktop-resolution-fit-local").assertIsDisplayed()
        composeRule.onNodeWithText("Fit local 1280×800").performClick()
        assertEquals("m2", requestedMode)

        // Rotate to portrait (800×1280) without recreating the Activity/Context: LocalConfiguration change recomposes Fit local
        requestedMode = null
        composeRule.runOnIdle {
            shadowDisplay.setRealWidth(800 * density)
            shadowDisplay.setRealHeight(1280 * density)
            configuration = android.content.res.Configuration(configuration).apply {
                orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
        }
        composeRule.waitForIdle()

        // Mobile Fit local button now selects the portrait mode (m3)
        composeRule.onNodeWithTag("remote-desktop-mobile-resolution-fit-local").performClick()
        assertEquals("m3", requestedMode)

        // Tablet toolbar settings menu label follows the rotated display dimensions (Fit local 800×1280)
        requestedMode = null
        composeRule.onNodeWithText("Fit local 800×1280").performClick()
        assertEquals("m3", requestedMode)

        // Restore landscape display dimensions and verify deviceFullScreenSizeDp ignores split-screen configuration.screenWidthDp
        shadowDisplay.setRealWidth(1280 * density)
        shadowDisplay.setRealHeight(800 * density)
        val origW = context.resources.configuration.screenWidthDp
        val origH = context.resources.configuration.screenHeightDp
        try {
            context.resources.configuration.screenWidthDp = 640
            context.resources.configuration.screenHeightDp = 400
            assertEquals(RdSize(1280, 800), deviceFullScreenSizeDp(context))
        } finally {
            context.resources.configuration.screenWidthDp = origW
            context.resources.configuration.screenHeightDp = origH
        }
    }

    @Test
    fun `Recording stops on viewer unmount and screenshot target bitmap uses decoded frameSize and Sidebar hides Remote Desktop when unsupported`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)
        model.remoteDesktopPlatformSupported = false
        assertEquals(false, model.remoteDesktopAvailable)

        var showViewer by mutableStateOf(true)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(width = 800.dp, height = 600.dp)) {
                    Sidebar(model = model)
                    if (showViewer) {
                        RemoteDesktopViewer(
                            model = model,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Sidebar hides Remote Desktop button when platformSupported is false
        composeRule.onAllNodesWithTag("sidebar-remote-desktop").assertCountEquals(0)

        // Start recording via the toolbar settings menu, then unmount RemoteDesktopViewer
        composeRule.onNodeWithTag("remote-desktop-settings-trigger").performClick()
        composeRule.onNodeWithTag("remote-desktop-record-toggle").performClick()
        composeRule.waitForIdle()
        showViewer = false
        composeRule.waitForIdle()

        // Verify stopAndSaveRecordingAsync runs recorder.stop() on Dispatchers.IO worker thread
        val tempMp4 = File(context.cacheDir, "test-unmount-record.mp4")
        var stopThreadName: String? = null
        val recorder = object : H264Mp4Recorder(tempMp4, 480, 360, { RdSize(480, 360) }) {
            override fun stop(): File? {
                stopThreadName = Thread.currentThread().name
                return super.stop()
            }
        }
        stopAndSaveRecordingAsync(model.scope, context, recorder)
        val stopDeadline = System.currentTimeMillis() + 2_000L
        while (!recorder.isStopped && System.currentTimeMillis() < stopDeadline) {
            Thread.sleep(10L)
        }
        assertTrue(recorder.isStopped)
        assertTrue(
            "Expected recorder.stop() on Dispatchers.IO worker thread, got $stopThreadName",
            stopThreadName?.contains("DefaultDispatcher-worker") == true,
        )
        tempMp4.delete()

        // Below API 29 (e.g. API 28), copyMp4ToDownloads saves the MP4 into the app's Downloads directory and deletes the cache copy
        val sourceMp4 = File(context.cacheDir, "ppm-remote-pre-q.mp4").apply {
            writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70))
        }
        val expectedDownloadsDir = checkNotNull(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS))
        val savedPreQ = copyMp4ToDownloads(
            context = context,
            file = sourceMp4,
            sdkInt = android.os.Build.VERSION_CODES.P,
        )
        assertTrue(savedPreQ != null && savedPreQ.exists())
        assertEquals(expectedDownloadsDir.canonicalPath, savedPreQ!!.parentFile!!.canonicalPath)
        assertEquals(8L, savedPreQ.length())
        assertEquals(false, sourceMp4.exists())
        savedPreQ.delete()

        // Screenshot target bitmap is allocated at decoded frameSize (1920x1080), not surfaceView layout size (800x450)
        val bmp = createScreenshotTargetBitmap(surfaceWidth = 800, surfaceHeight = 450, frameSize = RdSize(1920, 1080))
        assertEquals(1920, bmp!!.width)
        assertEquals(1080, bmp.height)
    }

    @Test
    fun `Recording auto-stops at MAX_RECORDING_MS 2 hours`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(width = 800.dp, height = 600.dp)) {
                    RemoteDesktopViewer(
                        model = model,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Start recording via the toolbar settings menu
        composeRule.onNodeWithTag("remote-desktop-settings-trigger").performClick()
        composeRule.onNodeWithTag("remote-desktop-record-toggle").performClick()
        composeRule.waitForIdle()

        // Reopen settings menu to observe the live recording toggle label
        composeRule.onNodeWithTag("remote-desktop-settings-trigger").performClick()
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        try {
            // Advance just shy of 2 hours (7,199 seconds): still recording
            composeRule.mainClock.advanceTimeBy(com.hienlh.ppm.state.remotedesktop.MAX_RECORDING_MS - 1_000L)
            composeRule.onNodeWithText("Stop recording", substring = true).assertIsDisplayed()

            // Advance past 2 hours (7,200 seconds): auto-stopped
            composeRule.mainClock.advanceTimeBy(2_000L)
            composeRule.onNodeWithText("Start recording (no audio)").assertIsDisplayed()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `Mouse wheel scroll preserves AXIS_VSCROLL sign and magnitude in both directions`() {
        val sent = mutableListOf<JsonElement>()
        val controller = StageInputController(
            moveCoalescer = com.hienlh.ppm.state.remotedesktop.PointerMoveCoalescer(send = { sent += it }),
        )

        fun scrollEvent(vScroll: Float): android.view.MotionEvent {
            val props = arrayOf(
                android.view.MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
                },
            )
            val coords = arrayOf(
                android.view.MotionEvent.PointerCoords().apply {
                    x = 400f
                    y = 300f
                    setAxisValue(android.view.MotionEvent.AXIS_VSCROLL, vScroll)
                },
            )
            return android.view.MotionEvent.obtain(
                0L,
                0L,
                android.view.MotionEvent.ACTION_SCROLL,
                1,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                android.view.InputDevice.SOURCE_MOUSE,
                0,
            )
        }

        // Positive AXIS_VSCROLL (wheel rolled away from user = scroll up) -> positive dy = vScroll * 120
        val upNotch = scrollEvent(1.0f)
        controller.onMotionEvent(upNotch, androidx.compose.ui.unit.IntSize(800, 600))
        upNotch.recycle()

        val upSmooth = scrollEvent(0.5f)
        controller.onMotionEvent(upSmooth, androidx.compose.ui.unit.IntSize(800, 600))
        upSmooth.recycle()

        // Negative AXIS_VSCROLL (wheel rolled toward user = scroll down) -> negative dy = vScroll * 120
        val downNotch = scrollEvent(-1.0f)
        controller.onMotionEvent(downNotch, androidx.compose.ui.unit.IntSize(800, 600))
        downNotch.recycle()

        val downSmooth = scrollEvent(-0.25f)
        controller.onMotionEvent(downSmooth, androidx.compose.ui.unit.IntSize(800, 600))
        downSmooth.recycle()

        assertEquals(4, sent.size)
        assertEquals("wheel", sent[0].jsonObject["type"].string)
        assertEquals(120.0, sent[0].jsonObject["dy"]!!.toString().toDouble(), 1e-3)
        assertEquals(60.0, sent[1].jsonObject["dy"]!!.toString().toDouble(), 1e-3)
        assertEquals(-120.0, sent[2].jsonObject["dy"]!!.toString().toDouble(), 1e-3)
        assertEquals(-30.0, sent[3].jsonObject["dy"]!!.toString().toDouble(), 1e-3)
    }

    @Test
    fun `StageInputController tracks multiple held mouse buttons as a set and stage or window blur sends releaseAll`() {
        val sent = mutableListOf<JsonElement>()
        val controller = StageInputController(
            moveCoalescer = com.hienlh.ppm.state.remotedesktop.PointerMoveCoalescer(send = { sent += it }),
        )

        fun mouseEvent(action: Int, buttonState: Int): android.view.MotionEvent {
            val props = arrayOf(
                android.view.MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
                },
            )
            val coords = arrayOf(
                android.view.MotionEvent.PointerCoords().apply {
                    x = 400f
                    y = 300f
                },
            )
            return android.view.MotionEvent.obtain(
                0L,
                0L,
                action,
                1,
                props,
                coords,
                0,
                buttonState,
                1f,
                1f,
                0,
                0,
                android.view.InputDevice.SOURCE_MOUSE,
                0,
            )
        }

        val stageSize = androidx.compose.ui.unit.IntSize(800, 600)

        // 1. Press left mouse button
        mouseEvent(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.BUTTON_PRIMARY).also {
            controller.onMotionEvent(it, stageSize)
            it.recycle()
        }
        assertEquals(1, sent.size)
        assertEquals("left", sent[0].jsonObject["button"].string)
        assertEquals(true, sent[0].jsonObject["down"].bool)

        // 2. Press right mouse button while left is still held
        mouseEvent(
            android.view.MotionEvent.ACTION_BUTTON_PRESS,
            android.view.MotionEvent.BUTTON_PRIMARY or android.view.MotionEvent.BUTTON_SECONDARY,
        ).also {
            controller.onMotionEvent(it, stageSize)
            it.recycle()
        }
        assertEquals(2, sent.size)
        assertEquals("right", sent[1].jsonObject["button"].string)
        assertEquals(true, sent[1].jsonObject["down"].bool)

        // 3. Release right mouse button while left stays held
        mouseEvent(android.view.MotionEvent.ACTION_BUTTON_RELEASE, android.view.MotionEvent.BUTTON_PRIMARY).also {
            controller.onMotionEvent(it, stageSize)
            it.recycle()
        }
        assertEquals(3, sent.size)
        assertEquals("right", sent[2].jsonObject["button"].string)
        assertEquals(false, sent[2].jsonObject["down"].bool)

        // 4. Release left mouse button
        mouseEvent(android.view.MotionEvent.ACTION_UP, 0).also {
            controller.onMotionEvent(it, stageSize)
            it.recycle()
        }
        assertEquals(4, sent.size)
        assertEquals("left", sent[3].jsonObject["button"].string)
        assertEquals(false, sent[3].jsonObject["down"].bool)

        // 5. Window blur while both buttons are held releases both buttons and sends releaseAll
        sent.clear()
        mouseEvent(
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.BUTTON_PRIMARY or android.view.MotionEvent.BUTTON_SECONDARY,
        ).also {
            controller.onMotionEvent(it, stageSize)
            it.recycle()
        }
        assertEquals(2, sent.size)
        assertEquals("left", sent[0].jsonObject["button"].string)
        assertEquals(true, sent[0].jsonObject["down"].bool)
        assertEquals("right", sent[1].jsonObject["button"].string)
        assertEquals(true, sent[1].jsonObject["down"].bool)
        sent.clear()

        controller.onWindowFocusChanged(false)
        assertTrue(sent.any { it.jsonObject["type"].string == "pointer" && it.jsonObject["button"].string == "left" && it.jsonObject["down"].bool == false })
        assertTrue(sent.any { it.jsonObject["type"].string == "pointer" && it.jsonObject["button"].string == "right" && it.jsonObject["down"].bool == false })
        assertTrue(sent.any { it.jsonObject["type"].string == "releaseAll" })

        // Repeated window blur after release emits no duplicate pointer up messages
        sent.clear()
        controller.onWindowFocusChanged(true)
        controller.onWindowFocusChanged(false)
        assertTrue(sent.none { it.jsonObject["type"].string == "pointer" })

        // 6. Stage focus loss in RemoteDesktopViewer sends releaseAll
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AppModel(context)
        val viewerSent = mutableListOf<JsonElement>()
        model.remoteDesktopSender = { viewerSent += it }
        val outsideFocus = androidx.compose.ui.focus.FocusRequester()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Column {
                    Box(
                        Modifier
                            .size(40.dp)
                            .focusRequester(outsideFocus)
                            .focusable()
                            .testTag("outside-focus-target"),
                    )
                    Box(Modifier.size(width = 800.dp, height = 600.dp)) {
                        RemoteDesktopViewer(
                            model = model,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        viewerSent.clear()

        composeRule.runOnIdle {
            outsideFocus.requestFocus()
        }
        composeRule.waitForIdle()
        assertTrue(viewerSent.any { it.jsonObject["type"].string == "releaseAll" })
    }
}
