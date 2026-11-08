package com.hienlh.ppm.app.ui.androiddevice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.androiddevice.AndroidCapabilities
import com.hienlh.ppm.state.androiddevice.AndroidDeviceItem
import com.hienlh.ppm.state.androiddevice.AndroidDeviceStatus
import com.hienlh.ppm.state.androiddevice.AndroidHardwareKey
import com.hienlh.ppm.state.androiddevice.AndroidLogEntry
import com.hienlh.ppm.state.androiddevice.AndroidLogLevel
import com.hienlh.ppm.state.androiddevice.AndroidQuality
import com.hienlh.ppm.state.androiddevice.AndroidRequirement
import com.hienlh.ppm.state.androiddevice.AndroidRuntimeInfo
import com.hienlh.ppm.state.androiddevice.AvdDeviceProfile
import com.hienlh.ppm.state.androiddevice.SystemImageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h1200dp-xxhdpi")
class Phase11cUiTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(rule)

    @Test
    fun `AndroidDeviceListView renders unmet requirements, locked warning, hw keyboard warning and ownedByPpm stop`() {
        var connectedId: String? = null
        var stoppedDeviceId: String? = null

        val caps = AndroidCapabilities(
            enabled = true,
            ready = true,
            canCreateAvd = true,
            requirements = listOf(
                AndroidRequirement(id = "sdk", label = "Android SDK", met = true, detail = "/opt/android-sdk"),
                AndroidRequirement(
                    id = "kvm",
                    label = "Hardware acceleration",
                    met = false,
                    detail = "/dev/kvm not accessible",
                    fix = "sudo usermod -aG kvm \$USER",
                ),
            ),
        )
        val devices = listOf(
            AndroidDeviceItem(
                avdId = "Pixel_8_API_34",
                name = "Pixel 8 API 34",
                apiLevel = 34,
                abi = "x86_64",
                hardwareKeyboard = false,
                lockedByAnotherProcess = true,
                state = AndroidDeviceStatus.STOPPED,
                runtime = null,
            ),
            AndroidDeviceItem(
                avdId = "Pixel_Tablet_API_34",
                name = "Pixel Tablet API 34",
                apiLevel = 34,
                abi = "x86_64",
                state = AndroidDeviceStatus.READY,
                runtime = AndroidRuntimeInfo(
                    deviceId = "emu-5554-gen1",
                    generation = 2,
                    adbSerial = "emulator-5554",
                    ownedByPpm = true,
                ),
            ),
            AndroidDeviceItem(
                avdId = "External_API_34",
                name = "External API 34",
                apiLevel = 34,
                abi = "x86_64",
                state = AndroidDeviceStatus.READY,
                runtime = AndroidRuntimeInfo(
                    deviceId = "emu-5556-gen1",
                    generation = 1,
                    adbSerial = "emulator-5556",
                    ownedByPpm = false,
                ),
            ),
        )

        rule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AndroidDeviceListView(
                    capabilities = caps,
                    devices = devices,
                    loading = false,
                    error = null,
                    busyByAvd = emptyMap(),
                    onRefresh = {},
                    onOpenCreateAvd = {},
                    onConnect = { connectedId = it.runtime?.deviceId },
                    onStart = {},
                    onStop = { stoppedDeviceId = it.runtime?.deviceId },
                    onWipe = {},
                    onDelete = {},
                )
            }
        }

        rule.onNodeWithTag("android-capabilities-card").assertIsDisplayed()
        rule.onNodeWithText("This host is not ready yet").assertIsDisplayed()
        rule.onNodeWithText("Hardware acceleration").assertIsDisplayed()

        // Locked device disables Start and shows lock + hardwareKeyboard warnings
        rule.onNodeWithTag("android-locked-Pixel_8_API_34").assertIsDisplayed()
        rule.onNodeWithTag("android-no-hw-keyboard-Pixel_8_API_34").assertIsDisplayed()
        rule.onNodeWithTag("android-start-Pixel_8_API_34").assertIsNotEnabled()

        // Running PPM-owned device has View and Stop
        rule.onNodeWithTag("android-connect-Pixel_Tablet_API_34").performClick()
        assertEquals("emu-5554-gen1", connectedId)
        rule.onNodeWithTag("android-stop-Pixel_Tablet_API_34").performClick()
        assertEquals("emu-5554-gen1", stoppedDeviceId)

        // Running external device has View but NO Stop button
        rule.onNodeWithTag("android-connect-External_API_34").assertIsDisplayed()
        rule.onNodeWithTag("android-stop-External_API_34").assertDoesNotExist()
    }

    @Test
    fun `AndroidAvdDestroyDialog requires exact device name match and sends confirmName`() {
        var confirmedName: String? = null
        val device = AndroidDeviceItem(
            avdId = "Pixel_8_API_34_id",
            name = "Pixel 8 API 34",
            apiLevel = 34,
        )

        rule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AndroidAvdDestroyDialog(
                    action = "delete",
                    device = device,
                    onDismiss = {},
                    onConfirm = { confirmedName = it },
                )
            }
        }

        rule.onNodeWithTag("android-destroy-dialog").assertIsDisplayed()
        rule.onNodeWithTag("avd-destroy-confirm").assertIsNotEnabled()

        // Typing avdId does NOT arm the button; only device.name does
        rule.onNodeWithTag("avd-destroy-confirm-input").performTextReplacement("Pixel_8_API_34_id")
        rule.onNodeWithTag("avd-destroy-confirm").assertIsNotEnabled()

        rule.onNodeWithTag("avd-destroy-confirm-input").performTextReplacement("Pixel 8 API 34")
        rule.onNodeWithTag("avd-destroy-confirm").assertIsEnabled()
        rule.onNodeWithTag("avd-destroy-confirm").performClick()
        assertEquals("Pixel 8 API 34", confirmedName)
    }

    @Test
    fun `AndroidAvdCreateFormContent validates name and limits and submits systemImage and deviceProfile`() {
        var submitted: List<Any>? = null
        val images = listOf(
            SystemImageRow(
                id = "system-images;android-35;google_apis;x86_64",
                apiLevel = 35,
                abi = "x86_64",
                tag = "google_apis",
                tagDisplay = "Google APIs",
                hostCompatible = true,
            ),
            SystemImageRow(
                id = "system-images;android-35;google_apis;arm64-v8a",
                apiLevel = 35,
                abi = "arm64-v8a",
                tag = "google_apis",
                tagDisplay = "Google APIs",
                hostCompatible = false,
            ),
        )
        val profiles = listOf(
            AvdDeviceProfile(id = "pixel_9", name = "Pixel 9", oem = "Google", kind = "phone"),
        )

        rule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                var name = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("_invalid") }
                var ram = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("2048") }
                var storage = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("6144") }
                AndroidAvdCreateFormContent(
                    name = name.value,
                    onNameChange = { name.value = it },
                    existingNames = listOf("Existing_AVD"),
                    images = images,
                    selectedImageId = images[0].id,
                    onSelectImage = {},
                    profiles = profiles,
                    selectedProfileId = profiles[0].id,
                    onSelectProfile = {},
                    ramMbText = ram.value,
                    onRamChange = { ram.value = it },
                    storageMbText = storage.value,
                    onStorageChange = { storage.value = it },
                    loading = false,
                    error = null,
                    submitting = false,
                    onDismiss = {},
                    onSubmit = { n, img, prof, r, s -> submitted = listOf(n, img, prof, r, s) },
                )
            }
        }

        rule.onNodeWithTag("avd-name-error").assertIsDisplayed()
        rule.onNodeWithTag("avd-create-submit").assertIsNotEnabled()
        rule.onNodeWithText("Runs on this host").assertIsDisplayed()
        rule.onNodeWithText("Wrong CPU for this host").assertIsDisplayed()

        rule.onNodeWithTag("avd-name-input").performTextReplacement("Pixel_9_API_35")
        rule.onNodeWithTag("avd-create-submit").assertIsEnabled()
        rule.onNodeWithTag("avd-create-submit").performClick()

        assertEquals(
            listOf("Pixel_9_API_35", "system-images;android-35;google_apis;x86_64", "pixel_9", 2048, 6144),
            submitted,
        )
    }

    @Test
    fun `AndroidTakeControlBanner and AndroidControlsBar dispatch hardware keys, rotate and quality`() {
        var tookControl = false
        val pressedKeys = mutableListOf<AndroidHardwareKey>()
        var rotated = false
        var selectedQuality: AndroidQuality? = null

        rule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                androidx.compose.foundation.layout.Column {
                    AndroidTakeControlBanner(
                        visible = true,
                        controllerReason = "another viewer took control",
                        onTakeControl = { tookControl = true },
                    )
                    AndroidControlsBar(
                        controller = true,
                        rotation = 0,
                        quality = AndroidQuality.BALANCED,
                        keyboardOpen = false,
                        onHardwareKey = { pressedKeys.add(it) },
                        onToggleKeyboard = {},
                        onRotate = { rotated = true },
                        onQualityChange = { selectedQuality = it },
                    )
                }
            }
        }

        rule.onNodeWithTag("android-take-control-banner").assertIsDisplayed()
        rule.onNodeWithText("another viewer took control").assertIsDisplayed()
        rule.onNodeWithTag("android-take-control").performClick()
        assertTrue(tookControl)

        rule.onNodeWithTag("android-hw-back").performClick()
        rule.onNodeWithTag("android-hw-home").performClick()
        rule.onNodeWithTag("android-hw-recent").performClick()
        assertEquals(
            listOf(AndroidHardwareKey.BACK, AndroidHardwareKey.HOME, AndroidHardwareKey.RECENT),
            pressedKeys,
        )

        rule.onNodeWithTag("android-more-keys").performClick()
        rule.onNodeWithTag("android-hw-volume-up").performClick()
        rule.onNodeWithTag("android-hw-power").performClick()
        assertEquals(AndroidHardwareKey.VOLUME_UP, pressedKeys[3])
        assertEquals(AndroidHardwareKey.POWER, pressedKeys[4])

        rule.onNodeWithTag("android-rotate").performClick()
        assertTrue(rotated)

        rule.onNodeWithTag("android-quality-select").performClick()
        rule.onNodeWithTag("android-quality-high").performClick()
        assertEquals(AndroidQuality.HIGH, selectedQuality)
    }

    @Test
    fun `AndroidLogcatPanel keys LazyColumn by monotonic entry id without crashing`() {
        val entries = (1L..25L).map { id ->
            AndroidLogEntry(
                id = id,
                timestamp = 1_700_000_000_000L + id * 100L,
                pid = 1234,
                tid = 1250,
                level = if (id % 2L == 0L) AndroidLogLevel.ERROR else AndroidLogLevel.INFO,
                tag = "Tag$id",
                message = "Message number $id",
            )
        }

        rule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AndroidLogcatPanel(
                    entries = entries,
                    paused = false,
                    onTogglePause = {},
                    onClear = {},
                )
            }
        }

        rule.onNodeWithTag("android-logcat-list").assertIsDisplayed()
        rule.onNodeWithText("25 lines").assertIsDisplayed()
    }
}
