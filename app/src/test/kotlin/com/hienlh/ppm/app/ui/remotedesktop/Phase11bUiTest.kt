package com.hienlh.ppm.app.ui.remotedesktop

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.string
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
    fun `H264SurfaceDecoder reset posts codec release to worker thread instead of caller thread`() {
        val decoder = H264SurfaceDecoder()
        try {
            decoder.reset()
            val deadline = System.currentTimeMillis() + 2_000L
            while (decoder.lastReleaseThreadName == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L)
            }
            assertEquals("ppm-rd-video-decoder", decoder.lastReleaseThreadName)
        } finally {
            decoder.release()
        }
    }
}
