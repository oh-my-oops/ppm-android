package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** The tablet Notifications pane's "Push notifications on this device" switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class TabletPushThisDeviceUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val server = MockWebServer()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The server's device list; this device joins it once the fake registrar "subscribes" it. */
    @Volatile private var deviceIds: List<String> = listOf("chrome-1")

    private val fake = FakeRegistrar()

    private inner class FakeRegistrar : PushThisDeviceRegistrar {
        var distributors: List<String> = listOf("io.heckel.ntfy")
        var enabled = false
        var deviceId: String? = null
        val registerCalls = mutableListOf<Triple<String, String?, String?>>()
        var unregisterCalls = 0

        override fun distributors(): List<String> = distributors
        override fun isEnabled(instance: String): Boolean = enabled
        override fun savedDeviceId(instance: String): String? = deviceId

        override fun register(instance: String, serverUrl: String?, vapidPublicKey: String?): Boolean {
            registerCalls += Triple(instance, serverUrl, vapidPublicKey)
            enabled = true
            deviceId = "tablet-1"
            deviceIds = deviceIds + "tablet-1"
            return true
        }

        override suspend fun unregister(instance: String) {
            unregisterCalls++
            enabled = false
            deviceIds = deviceIds - deviceId.orEmpty()
            deviceId = null
        }
    }

    private fun json(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun startServer(): ApiClient {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += "${request.method} $path"
                return when {
                    request.method == "GET" && path == "/api/notifications/push" -> {
                        val devices = deviceIds.joinToString(",") { id ->
                            val label = if (id == "tablet-1") "Lenovo TB-J716F" else "Chrome on Android"
                            """{"id":"$id","label":"$label","endpoint":"https://push.example/$id"}"""
                        }
                        json("""{"ok":true,"data":{"publicKey":"vapid-key","devices":[$devices]}}""")
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        return ApiClient(server.url("/"), "test-token")
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun setPane(client: ApiClient, notificationsAllowed: Boolean = true) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                val push = remember {
                    PushThisDevice(
                        instance = "srv-1",
                        registrar = fake,
                        notificationsEnabledOverride = notificationsAllowed,
                    ).also { it.serverUrl = "http://192.168.1.10:3210" }
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NotificationsSettingsSection(api = client, push = push)
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Chrome on Android").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun switchRendersAtTheTopOfThePushCard_namingTheDistributor() {
        setPane(startServer())

        composeRule.onNodeWithTag("notify-push-this-device").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Push notifications on this device").assertIsDisplayed()
        composeRule.onNodeWithText("Receive alerts on this device via ntfy").assertIsDisplayed()
        composeRule.onNodeWithTag("notify-push-this-device")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        composeRule.onNodeWithTag("notify-push-no-distributor-notice").assertDoesNotExist()
    }

    @Test
    fun withoutADistributor_theSwitchIsDisabledWithTheNotice() {
        fake.distributors = emptyList()
        setPane(startServer())

        composeRule.onNodeWithTag("notify-push-this-device").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("notify-push-no-distributor-notice").assertIsDisplayed()
        composeRule.onNodeWithText("UnifiedPush distributor required").assertIsDisplayed()
        composeRule.onNodeWithTag("notify-push-this-device").performClick()
        composeRule.waitForIdle()
        assertTrue(fake.registerCalls.isEmpty())
    }

    @Test
    fun switchingOn_registersAndTheListShowsThisDevice() {
        setPane(startServer())

        composeRule.onNodeWithTag("notify-push-this-device").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("push-this-device-tablet-1").fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(listOf(Triple("srv-1", "http://192.168.1.10:3210", "vapid-key")), fake.registerCalls)
        composeRule.onNodeWithTag("notify-push-this-device")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNodeWithTag("notifications-status-msg")
            .assertTextContains("Push notifications enabled on this device")
        composeRule.onNodeWithText("Subscribed Push Devices (2)").assertIsDisplayed()
        composeRule.onNodeWithText("This device").assertIsDisplayed()
        // Only this device's row carries the tag.
        composeRule.onNodeWithTag("push-this-device-chrome-1").assertDoesNotExist()
    }

    @Test
    fun switchingOff_unregisters() {
        fake.enabled = true
        fake.deviceId = "tablet-1"
        deviceIds = listOf("chrome-1", "tablet-1")
        setPane(startServer())

        composeRule.onNodeWithTag("notify-push-this-device")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("push-this-device-tablet-1").fetchSemanticsNodes().isEmpty()
        }

        assertEquals(1, fake.unregisterCalls)
        assertTrue(fake.registerCalls.isEmpty())
        composeRule.onNodeWithTag("notifications-status-msg")
            .assertTextContains("Push notifications turned off on this device")
        composeRule.onNodeWithTag("notify-push-this-device")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test
    fun removingThisDevicesRow_switchesItOff() {
        fake.enabled = true
        fake.deviceId = "tablet-1"
        deviceIds = listOf("chrome-1", "tablet-1")
        setPane(startServer())

        composeRule.onNodeWithTag("push-this-device-tablet-1").assertIsDisplayed()
        composeRule.onNodeWithTag("push-remove-tablet-1").performScrollTo().performClick()
        composeRule.onNodeWithTag("push-remove-confirm").performClick()
        composeRule.waitUntil(5_000) { fake.unregisterCalls == 1 }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("push-remove-tablet-1").fetchSemanticsNodes().isEmpty()
        }
        // The section's own server-side unsubscribe (another device's Remove) is not used.
        assertTrue(requests.none { it.endsWith("/api/notifications/push/unsubscribe") })
    }

    @Test
    fun removingThisDevicesRow_worksAfterTheDistributorIsGone() {
        fake.distributors = emptyList()
        fake.enabled = true
        fake.deviceId = "tablet-1"
        deviceIds = listOf("chrome-1", "tablet-1")
        setPane(startServer())

        composeRule.onNodeWithTag("push-remove-tablet-1").performScrollTo().performClick()
        composeRule.onNodeWithTag("push-remove-confirm").performClick()
        composeRule.waitUntil(5_000) { fake.unregisterCalls == 1 }
    }

    @Test
    fun permissionRequest_waitsForTheSystemAnswerAndFailsClosedWhenItCannotAsk() {
        val push = PushThisDevice(instance = "srv-1", registrar = fake, hasPermission = { false })
        var launched = 0
        push.needsRuntimeRequest = { true }
        push.launchRuntimeRequest = { launched++; true }
        val answers = mutableListOf<Boolean>()

        push.requestPermission { answers += it }
        assertEquals(1, launched)
        assertTrue(answers.isEmpty())
        push.onPermissionResult(true)
        assertEquals(listOf(true), answers)
        assertTrue(push.permissionGranted)

        push.launchRuntimeRequest = { false }
        push.requestPermission { answers += it }
        assertEquals(listOf(true, false), answers)
    }

    @Test
    fun deniedPermission_staysOffAndOffersAndroidSettings() {
        setPane(startServer(), notificationsAllowed = false)

        composeRule.onNodeWithTag("notify-push-this-device").performScrollTo().performClick()
        composeRule.onNodeWithTag("notify-push-permission-denied").assertIsDisplayed()
        composeRule.onNodeWithTag("notify-push-open-system-settings").assertIsDisplayed()
        assertTrue(fake.registerCalls.isEmpty())
        composeRule.onNodeWithTag("notify-push-this-device")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }
}
