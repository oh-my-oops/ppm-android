package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hienlh.ppm.app.push.FcmFallbackReason
import com.hienlh.ppm.app.push.PushTransport
import com.hienlh.ppm.app.ui.phone.settings.PhoneNotificationsContent
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.FcmAndroidConfig
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.settings.NotificationSettingsState
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Push notifications on this device" switch when the server pushes through Firebase. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class PushThisDeviceFcmUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val config = FcmAndroidConfig("k", "1:1:android:a", "1", "ppm-home")

    private class FakeRegistrar(
        var distributors: List<String> = emptyList(),
        var transport: PushTransport = PushTransport.UnifiedPush(FcmFallbackReason.NOT_CONFIGURED),
        var enabled: Boolean = false,
        var viaFcm: Boolean = false,
        var fcmFails: Boolean = false,
    ) : PushThisDeviceRegistrar {
        var deviceId: String? = null
        val fcmCalls = mutableListOf<Pair<String, FcmAndroidConfig>>()
        var unifiedPushCalls = 0
        var unregisterCalls = 0

        override fun distributors() = distributors
        override fun isEnabled(instance: String) = enabled
        override fun savedDeviceId(instance: String) = deviceId
        override fun register(instance: String, serverUrl: String?, vapidPublicKey: String?): Boolean {
            unifiedPushCalls++
            enabled = distributors.isNotEmpty()
            return enabled
        }
        override suspend fun unregister(instance: String) {
            unregisterCalls++
            enabled = false
            viaFcm = false
            deviceId = null
        }
        override suspend fun transport(instance: String) = transport
        override fun enrolledViaFcm(instance: String) = viaFcm
        override suspend fun registerFcm(instance: String, serverUrl: String?, config: FcmAndroidConfig): String {
            fcmCalls += instance to config
            if (fcmFails) throw IllegalStateException("no network")
            enabled = true
            viaFcm = true
            deviceId = "fcm-1"
            return "fcm-1"
        }
    }

    private val server = MockWebServer()

    @After fun tearDown() {
        runCatching { server.close() }
    }

    private fun startServer(): ApiClient {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET" && request.url.encodedPath == "/api/notifications/push") {
                    MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"publicKey":"vapid-key","devices":[{"id":"chrome-1","label":"Chrome on Android","endpoint":"https://push.example/c"}]}}""")
                        .build()
                } else {
                    MockResponse.Builder().code(404).build()
                }
        }
        server.start()
        return ApiClient(server.url("/"), "t")
    }

    private fun setTablet(fake: FakeRegistrar) {
        val client = startServer()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                val push = remember {
                    PushThisDevice(instance = "srv-1", registrar = fake, notificationsEnabledOverride = true)
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NotificationsSettingsSection(api = client, push = push)
                }
            }
        }
    }

    private fun waitForText(text: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    // --- shared state ----------------------------------------------------------------------------

    @Test fun firebaseMakesTheSwitchAvailableWithoutADistributor() = runBlocking {
        val push = PushThisDevice("srv-1", FakeRegistrar(transport = PushTransport.Firebase(config)), notificationsEnabledOverride = true)
        assertFalse(push.available)
        push.resolveTransport()
        assertTrue(push.available)
        assertTrue(push.viaFirebase)
        assertEquals("Firebase", push.viaName)
        assertFalse(push.distributorAvailable)
    }

    @Test fun switchingOnRegistersThroughFirebaseNotUnifiedPush() = runBlocking {
        val fake = FakeRegistrar(distributors = listOf("io.heckel.ntfy"), transport = PushTransport.Firebase(config))
        val push = PushThisDevice("srv-1", fake, notificationsEnabledOverride = true)
        assertEquals("Push notifications enabled on this device", push.switchOn("vapid"))
        assertEquals(listOf("srv-1" to config), fake.fcmCalls)
        assertEquals(0, fake.unifiedPushCalls)
        assertTrue(push.enabledOnThisDevice)
        assertTrue(push.enrolledViaFcm)
        assertEquals("fcm-1", push.savedDeviceId)

        push.disable()
        assertFalse(push.enrolledViaFcm)
        assertEquals(1, fake.unregisterCalls)
    }

    @Test fun aFirebaseFailureFallsBackToTheDistributorOrSaysWhy() = runBlocking {
        val withNtfy = FakeRegistrar(distributors = listOf("io.heckel.ntfy"), transport = PushTransport.Firebase(config), fcmFails = true)
        val push = PushThisDevice("srv-1", withNtfy, notificationsEnabledOverride = true)
        assertEquals("Push notifications enabled on this device", push.switchOn("vapid"))
        assertEquals(1, withNtfy.unifiedPushCalls)
        assertEquals("ntfy", push.viaName)

        val alone = PushThisDevice("srv-1", FakeRegistrar(transport = PushTransport.Firebase(config), fcmFails = true), notificationsEnabledOverride = true)
        assertEquals("Could not register with Firebase: no network", alone.switchOn("vapid"))
        assertFalse(alone.enabledOnThisDevice)
    }

    @Test fun withoutFirebaseTheSwitchStaysOnUnifiedPush() = runBlocking {
        val fake = FakeRegistrar(distributors = listOf("io.heckel.ntfy"))
        val push = PushThisDevice("srv-1", fake, notificationsEnabledOverride = true)
        push.switchOn("vapid")
        assertEquals(1, fake.unifiedPushCalls)
        assertTrue(fake.fcmCalls.isEmpty())
        assertEquals("ntfy", push.viaName)
    }

    @Test fun subtitleNamesTheWayAndWhyFirebaseWasNotUsed() {
        assertEquals("Receive alerts on this device via Firebase", pushViaSubtitle("this device", "Firebase"))
        assertEquals(
            "Receive alerts on this phone via ntfy (Firebase is in use by another server)",
            pushViaSubtitle("this phone", "ntfy", firebaseInUseElsewhere = true),
        )
    }

    // --- tablet ------------------------------------------------------------------------------------

    @Test fun tablet_firebaseServer_switchSaysViaFirebaseAndRegistersThroughIt() {
        val fake = FakeRegistrar(transport = PushTransport.Firebase(config))
        setTablet(fake)
        waitForText("Receive alerts on this device via Firebase")

        composeRule.onNodeWithTag("notify-push-no-distributor-notice").assertDoesNotExist()
        composeRule.onNodeWithTag("notify-push-this-device").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(5_000) { fake.fcmCalls.isNotEmpty() }
        composeRule.onNodeWithTag("notify-push-this-device")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNodeWithTag("notifications-status-msg")
            .assertTextContains("Push notifications enabled on this device")
        assertEquals(0, fake.unifiedPushCalls)
    }

    @Test fun tablet_enrolledThroughFirebase_isOnViaFirebase() {
        setTablet(FakeRegistrar(enabled = true, viaFcm = true))
        waitForText("Receive alerts on this device via Firebase")
        composeRule.onNodeWithTag("notify-push-this-device")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test fun tablet_otherProject_fallsBackToTheDistributorAndSaysSo() {
        setTablet(
            FakeRegistrar(
                distributors = listOf("io.heckel.ntfy"),
                transport = PushTransport.UnifiedPush(FcmFallbackReason.OTHER_PROJECT),
            ),
        )
        waitForText("Receive alerts on this device via ntfy (Firebase is in use by another server)")
    }

    @Test fun tablet_serverWithoutFirebase_keepsTheDistributorLine() {
        setTablet(FakeRegistrar(distributors = listOf("io.heckel.ntfy")))
        waitForText("Receive alerts on this device via ntfy")
        composeRule.onNodeWithText("Receive alerts on this device via ntfy").assertIsDisplayed()
    }

    // --- phone -------------------------------------------------------------------------------------

    private fun setPhone(fake: FakeRegistrar): MutableList<Boolean> {
        val push = PushThisDevice("srv-1", fake, notificationsEnabledOverride = true)
        runBlocking { push.resolveTransport() }
        val toggles = mutableListOf<Boolean>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneNotificationsContent(
                    settings = NotificationSettingsState(),
                    notificationsEnabledOnDevice = true,
                    distributorAvailable = push.available,
                    distributorName = push.viaName,
                    firebaseInUseElsewhere = push.firebaseInUseElsewhere,
                    pushEnabledOnThisDevice = push.enabledOnThisDevice,
                    onTogglePushOnDevice = { toggles += it },
                    onUpdateSettings = {},
                )
            }
        }
        return toggles
    }

    @Test
    @Config(qualifiers = "w400dp-h844dp-xxhdpi")
    fun phone_firebaseServer_switchSaysViaFirebaseWithoutADistributor() {
        val toggles = setPhone(FakeRegistrar(transport = PushTransport.Firebase(config)))
        composeRule.onNodeWithText("Receive alerts on this phone via Firebase").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("phone-notify-no-distributor-notice").assertDoesNotExist()
        composeRule.onNodeWithTag("phone-notify-push-toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(true), toggles)
    }

    @Test
    @Config(qualifiers = "w400dp-h844dp-xxhdpi")
    fun phone_otherProject_fallsBackToTheDistributorAndSaysSo() {
        setPhone(
            FakeRegistrar(
                distributors = listOf("io.heckel.ntfy"),
                transport = PushTransport.UnifiedPush(FcmFallbackReason.OTHER_PROJECT),
            ),
        )
        composeRule.onNodeWithText("Receive alerts on this phone via ntfy (Firebase is in use by another server)")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w400dp-h844dp-xxhdpi")
    fun phone_noFirebaseNoDistributor_showsTheDistributorNotice() {
        setPhone(FakeRegistrar())
        composeRule.onNodeWithTag("phone-notify-no-distributor-notice").performScrollTo().assertIsDisplayed()
    }
}
