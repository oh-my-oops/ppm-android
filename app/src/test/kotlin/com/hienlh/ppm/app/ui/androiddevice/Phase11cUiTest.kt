package com.hienlh.ppm.app.ui.androiddevice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.androiddevice.AndroidCapabilities
import com.hienlh.ppm.state.androiddevice.AndroidDeviceItem
import com.hienlh.ppm.state.androiddevice.AndroidDeviceStatus
import com.hienlh.ppm.state.androiddevice.AndroidKey
import com.hienlh.ppm.state.androiddevice.AndroidQuality
import com.hienlh.ppm.state.androiddevice.AndroidRequirement
import com.hienlh.ppm.state.androiddevice.AndroidRuntimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class Phase11cUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `AndroidDeviceListView renders unmet requirements and device actions`() {
        var startedAvd: String? = null
        var viewedAvd: String? = null
        var wipedAvd: String? = null
        var copiedFix: String? = null

        val caps = AndroidCapabilities(
            enabled = true,
            ready = true,
            avdCount = 2,
            accelerationOk = true,
            accelerationDetail = "KVM available",
            requirements = listOf(
                AndroidRequirement(
                    id = "emulator",
                    label = "Android Emulator",
                    met = false,
                    detail = "Emulator binary missing",
                    fix = "sdkmanager --install emulator",
                ),
            ),
            canCreateAvd = true,
        )
        val devices = listOf(
            AndroidDeviceItem(
                avdId = "Pixel_7_API_34",
                name = "Pixel 7 API 34",
                target = "Android 14.0",
                abi = "x86_64",
                runtime = null,
            ),
            AndroidDeviceItem(
                avdId = "Pixel_8_Ready",
                name = "Pixel 8 Ready",
                target = "Android 14.0",
                abi = "x86_64",
                runtime = AndroidRuntimeInfo(
                    serial = "emulator-5554",
                    status = AndroidDeviceStatus.READY,
                    generation = 2,
                ),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AndroidDeviceListView(
                    capabilities = caps,
                    devices = devices,
                    operations = emptyMap(),
                    loading = false,
                    error = null,
                    onRefresh = {},
                    onOpenCreateAvd = {},
                    onCopyFix = { copiedFix = it },
                    onStartAvd = { startedAvd = it.avdId },
                    onStopDevice = {},
                    onCancelOperation = {},
                    onSelectDevice = { viewedAvd = it.avdId },
                    onRequestWipe = { wipedAvd = it.avdId },
                    onRequestDelete = {},
                    onShowBootLog = {},
                )
            }
        }

        composeRule.onNodeWithTag("android-requirements-card").assertIsDisplayed()
        composeRule.onNodeWithText("sdkmanager --install emulator").performClick()
        assertEquals("sdkmanager --install emulator", copiedFix)

        composeRule.onNodeWithTag("android-start-Pixel_7_API_34").performClick()
        assertEquals("Pixel_7_API_34", startedAvd)

        composeRule.onNodeWithTag("android-view-Pixel_8_Ready").performClick()
        assertEquals("Pixel_8_Ready", viewedAvd)

        composeRule.onNodeWithTag("android-more-Pixel_7_API_34").performClick()
        composeRule.onNodeWithTag("android-wipe-Pixel_7_API_34").performClick()
        assertEquals("Pixel_7_API_34", wipedAvd)
    }

    @Test
    fun `AndroidDestructiveConfirmDialog requires exact AVD ID before confirming`() {
        var confirmed = false
        val device = AndroidDeviceItem(
            avdId = "Pixel_7_API_34",
            name = "Pixel 7 API 34",
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AndroidDestructiveConfirmContent(
                    device = device,
                    isDelete = true,
                    onDismiss = {},
                    onConfirm = { confirmed = true },
                )
            }
        }

        // Clicking before typing exact avdId does nothing
        composeRule.onNodeWithTag("android-confirm-submit").performClick()
        assertFalse(confirmed)

        composeRule.onNodeWithTag("android-confirm-input").performTextInput("Pixel_7_API_34")
        composeRule.onNodeWithTag("android-confirm-submit").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `AndroidControlsBar dispatches navigation keys rotation quality and hardware buttons`() {
        val keys = mutableListOf<AndroidKey>()
        var rotated = false
        var selectedQuality = AndroidQuality.BALANCED

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AndroidControlsBar(
                    controller = true,
                    quality = selectedQuality,
                    keyboardOpen = false,
                    onKey = { keys.add(it) },
                    onRotate = { rotated = true },
                    onQuality = { selectedQuality = it },
                    onToggleKeyboard = {},
                )
            }
        }

        composeRule.onNodeWithTag("android-key-back").performClick()
        composeRule.onNodeWithTag("android-key-home").performClick()
        composeRule.onNodeWithTag("android-key-recent").performClick()
        assertEquals(listOf(AndroidKey.BACK, AndroidKey.HOME, AndroidKey.APP_SWITCH), keys)

        composeRule.onNodeWithTag("android-key-rotate").performClick()
        assertTrue(rotated)

        composeRule.onNodeWithTag("android-quality-high").performClick()
        assertEquals(AndroidQuality.HIGH, selectedQuality)

        composeRule.onNodeWithTag("android-key-more").performClick()
        composeRule.onNodeWithText("Power").performClick()
        assertTrue(AndroidKey.POWER in keys)
    }
}
