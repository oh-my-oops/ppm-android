package com.hienlh.ppm.app.push

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.MainActivity
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.ui.phone.settings.PhoneNotificationsContent
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ApiClientPush
import com.hienlh.ppm.client.FeedKind
import com.hienlh.ppm.client.originUrl
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.settings.NotificationSettingsState
import com.hienlh.ppm.state.workspace.parsePpmLink
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h844dp-xxhdpi")
class PushPayloadRoutingTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `payload kind maps to the four Android notification channels and falls back to Other when kind is absent`() {
        val approvalPayload = WebPushPayload.fromJson(
            """
            {
              "title": "Approval needed · PPM",
              "body": "Claude wants to run Bash",
              "url": "https://ppm.example.com/project/ppm?openChat=claude%2F550e8400-e29b-41d4-a716-446655440000",
              "tag": "ppm-550e8400",
              "project": "ppm",
              "sessionId": "550e8400-e29b-41d4-a716-446655440000",
              "providerId": "claude",
              "kind": "approval"
            }
            """.trimIndent(),
        )
        assertNotNull(approvalPayload)
        assertEquals(FeedKind.APPROVAL, approvalPayload!!.kind)
        assertEquals(PushChannel.APPROVAL_QUESTION, channelForPushPayload(approvalPayload))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, approvalPayload.channel.importance)

        val questionPayload = WebPushPayload.fromJson(
            """{"title":"Question · PPM","body":"Which branch?","kind":"question"}""",
        )
        assertNotNull(questionPayload)
        assertEquals(PushChannel.APPROVAL_QUESTION, questionPayload!!.channel)

        val donePayload = WebPushPayload.fromJson(
            """{"title":"Chat finished · PPM","body":"Done","kind":"done"}""",
        )
        assertNotNull(donePayload)
        assertEquals(PushChannel.DONE, donePayload!!.channel)

        val schedulePayload = WebPushPayload.fromJson(
            """{"title":"Nightly backup · PPM","body":"Succeeded","kind":"schedule"}""",
        )
        assertNotNull(schedulePayload)
        assertEquals(PushChannel.SCHEDULE, schedulePayload!!.channel)

        val otherPayload = WebPushPayload.fromJson(
            """{"title":"Jira · PPM","body":"PROJ-123 updated","kind":"other"}""",
        )
        assertNotNull(otherPayload)
        assertEquals(PushChannel.OTHER, otherPayload!!.channel)

        // Older PPM servers omit `kind` — must default to OTHER channel
        val legacyPayload = WebPushPayload.fromJson(
            """{"title":"Legacy · PPM","body":"From an older server"}""",
        )
        assertNotNull(legacyPayload)
        assertNull(legacyPayload!!.rawKind)
        assertEquals(FeedKind.OTHER, legacyPayload.kind)
        assertEquals(PushChannel.OTHER, legacyPayload.channel)
    }

    @Test
    fun `unencrypted UnifiedPush messages are dropped and decrypted RFC 8291 payloads post notifications with ppm deep links`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager = context.getSystemService(NotificationManager::class.java)!!
        shadowOf(notificationManager).setNotificationsEnabled(true)

        val rawJson = """
            {
              "title": "Input needed · Workstation",
              "body": "Codex is asking a question",
              "url": "https://ppm.example.com/project/my-app?openChat=codex%2F550e8400-e29b-41d4-a716-446655440000",
              "tag": "ppm-550e8400-e29b-41d4-a716-446655440000",
              "project": "my-app",
              "sessionId": "550e8400-e29b-41d4-a716-446655440000",
              "providerId": "codex",
              "kind": "question"
            }
        """.trimIndent()

        // 1. Unencrypted message (decrypted = false) must be rejected
        val unencrypted = PushMessage(rawJson.toByteArray(Charsets.UTF_8), false)
        assertNull(WebPushPayload.fromPushMessage(unencrypted))

        val receiver = UnifiedPushReceiver()
        receiver.onMessage(context, unencrypted, "srv-1")
        assertEquals(0, shadowOf(notificationManager).allNotifications.size)

        // 2. Decrypted message (decrypted = true) posts notification to the target channel
        val decryptedMessage = PushMessage(rawJson.toByteArray(Charsets.UTF_8), true)
        receiver.onMessage(context, decryptedMessage, "srv-1")

        // Verify all 4 channels were created and notification was posted to the Approvals & questions channel
        val channelIds = shadowOf(notificationManager).notificationChannels.map { it.id }.toSet()
        assertEquals(PushChannel.entries.map { it.id }.toSet(), channelIds)

        val notifications = shadowOf(notificationManager).allNotifications
        assertEquals(1, notifications.size)
        val posted = notifications.single()
        assertEquals(PushChannel.APPROVAL_QUESTION.id, posted.channelId)

        // Verify tapping the notification launches MainActivity with the ppm:// deep link
        val pendingIntent = posted.contentIntent
        assertNotNull(pendingIntent)
        val savedIntent = shadowOf(pendingIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, savedIntent.action)
        assertEquals(MainActivity::class.java.name, savedIntent.component?.className)
        val deepLink = savedIntent.dataString
        assertEquals(
            "ppm://ppm.example.com/project/my-app?openChat=codex%2F550e8400-e29b-41d4-a716-446655440000",
            deepLink,
        )
        val parsedLink = parsePpmLink(deepLink!!)
        assertNotNull(parsedLink)
        assertEquals("ppm.example.com", parsedLink!!.host)
        assertEquals("my-app", parsedLink.state.projectName)
        assertEquals("codex/550e8400-e29b-41d4-a716-446655440000", parsedLink.state.openChat)
    }

    @Test
    fun `buildPpmDeepLink resolves relative URLs against fallback server URL for chat and schedule notifications`() {
        val chatPayload = WebPushPayload(
            title = "Turn finished · Mini PC",
            body = "All tests green",
            url = "/",
            project = "ppm-android",
            sessionId = "550e8400-e29b-41d4-a716-446655440000",
            providerId = "claude",
            kind = FeedKind.DONE,
        )
        val chatLink = buildPpmDeepLink(chatPayload, fallbackServerUrl = "http://100.64.0.2:8080")
        assertEquals(
            "ppm://100.64.0.2:8080/project/ppm-android?openChat=claude%2F550e8400-e29b-41d4-a716-446655440000",
            chatLink,
        )
        val parsedChat = parsePpmLink(chatLink!!)
        assertNotNull(parsedChat)
        assertEquals("100.64.0.2", parsedChat!!.host)
        assertEquals(8080, parsedChat.port)
        assertEquals("ppm-android", parsedChat.state.projectName)
        assertEquals("claude/550e8400-e29b-41d4-a716-446655440000", parsedChat.state.openChat)

        val schedulePayload = WebPushPayload(
            title = "Schedule · Mini PC",
            body = "Nightly check completed",
            url = "/project/ppm-android",
            project = "ppm-android",
            sessionId = null,
            providerId = null,
            kind = FeedKind.SCHEDULE,
        )
        val scheduleLink = buildPpmDeepLink(schedulePayload, fallbackServerUrl = "http://100.64.0.2:8080")
        assertEquals("ppm://100.64.0.2:8080/project/ppm-android", scheduleLink)
        val parsedSchedule = parsePpmLink(scheduleLink!!)
        assertNotNull(parsedSchedule)
        assertEquals("ppm-android", parsedSchedule!!.state.projectName)
        assertNull(parsedSchedule.state.openChat)
    }

    @Test
    fun `a push about a task opens the task, beside the chat it also names`() {
        val task = "11111111-1111-4111-8111-111111111111"
        val payload = WebPushPayload(
            title = "Task · Mini PC",
            body = "#12 waits on you",
            url = "/?openTask=$task",
            project = "ppm",
            sessionId = "550e8400-e29b-41d4-a716-446655440000",
            providerId = "claude",
        )
        val link = buildPpmDeepLink(payload, fallbackServerUrl = "http://100.64.0.2:8080")!!
        assertEquals("ppm://100.64.0.2:8080/project/ppm?openChat=claude%2F550e8400-e29b-41d4-a716-446655440000&openTask=$task", link)
        val parsed = parsePpmLink(link)!!
        assertEquals("ppm", parsed.state.projectName)
        assertEquals(task, parsed.state.openTask)
        // A project address that already carries it is left as it is.
        val direct = buildPpmDeepLink(payload.copy(url = "/project/ppm?openTask=$task", sessionId = null), fallbackServerUrl = "http://h:8080")!!
        assertEquals("ppm://h:8080/project/ppm?openTask=$task", direct)
        assertEquals(task, parsePpmLink(direct)!!.state.openTask)
    }

    @Test
    fun `ApiClientPush subscribe sends endpoint, p256dh, auth, label and server origin to POST push subscribe`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "ok": true,
                      "data": {
                        "id": "dev-42",
                        "endpoint": "https://ntfy.sh/up-test-topic?up=1",
                        "label": "Android · Pixel 8",
                        "origin": "https://ppm.example.com",
                        "createdAt": "2026-11-11 00:00:00",
                        "lastSuccessAt": null,
                        "lastError": null
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        server.start()
        try {
            val baseUrl = server.url("/")
            val api = ApiClient(baseUrl, "secret-token")
            val p256dh = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteArray(65).apply { this[0] = 0x04 },
            )
            val auth = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 7 })

            val context = ApplicationProvider.getApplicationContext<Application>()
            val prefs = PrefsStore(context)
            var subscribedDeviceId: String? = null
            UnifiedPushReceiver.serverSubscriber = { _, store, instance, endpoint, pubKey, authKey ->
                val device = ApiClientPush(api).subscribe(
                    endpoint = endpoint,
                    p256dh = pubKey,
                    auth = authKey,
                    label = "Android · Pixel 8",
                    origin = api.originUrl(),
                )
                PushDeviceManager.recordDeviceId(store, instance, device.id)
                subscribedDeviceId = device.id
            }

            UnifiedPushReceiver.subscribeEndpointOnServer(
                context = context,
                prefs = prefs,
                instance = "srv-1",
                endpoint = "https://ntfy.sh/up-test-topic?up=1",
                p256dh = p256dh,
                auth = auth,
            )

            assertEquals("dev-42", subscribedDeviceId)
            assertEquals("dev-42", PushDeviceManager.savedDeviceId(prefs, "srv-1"))

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/notifications/push/subscribe", recorded.target)
            val bodyJson = WireJson.parseOrNull(recorded.body?.utf8().orEmpty())
            assertNotNull(bodyJson)
            assertEquals("Android · Pixel 8", bodyJson!!["label"].string)
            assertEquals("http://${baseUrl.host}:${baseUrl.port}", bodyJson["origin"].string)
            val sub = bodyJson["subscription"].obj
            assertNotNull(sub)
            assertEquals("https://ntfy.sh/up-test-topic?up=1", sub!!["endpoint"].string)
            val keys = sub["keys"].obj
            assertNotNull(keys)
            assertEquals(p256dh, keys!!["p256dh"].string)
            assertEquals(auth, keys["auth"].string)

            // Also verify onNewEndpoint stores the endpoint + keys in PrefsStore
            UnifiedPushReceiver().onNewEndpoint(
                context,
                PushEndpoint(
                    "https://ntfy.sh/up-test-topic?up=1",
                    PublicKeySet(p256dh, auth),
                    false,
                ),
                "srv-1",
            )
            assertEquals("https://ntfy.sh/up-test-topic?up=1", PushDeviceManager.savedEndpoint(prefs, "srv-1"))
            assertEquals(p256dh, PushDeviceManager.savedP256dh(prefs, "srv-1"))
            assertEquals(auth, PushDeviceManager.savedAuth(prefs, "srv-1"))
        } finally {
            UnifiedPushReceiver.serverSubscriber = null
            server.close()
        }
    }

    @Test
    fun `NotificationsPage never asks permission on launch, disables switch without distributor, and reverts switch when permission is denied`() {
        var distributorAvailable by mutableStateOf(false)
        var grantPermission by mutableStateOf(false)
        var permissionRequests by mutableStateOf(0)
        var toggledPushStates = mutableListOf<Boolean>()
        var openedAndroidSettings by mutableStateOf(false)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneNotificationsContent(
                    settings = NotificationSettingsState(),
                    notificationsEnabledOnDevice = true,
                    notificationPermissionGranted = false,
                    distributorAvailable = distributorAvailable,
                    distributorName = "ntfy",
                    pushEnabledOnThisDevice = false,
                    onOpenSystemNotificationSettings = { openedAndroidSettings = true },
                    onRequestNotificationPermission = { callback ->
                        permissionRequests += 1
                        callback(grantPermission)
                    },
                    onTogglePushOnDevice = { enabled ->
                        toggledPushStates.add(enabled)
                    },
                    onUpdateSettings = {},
                )
            }
        }

        // 1. Never requests POST_NOTIFICATIONS on launch/composition (Requirement 7.1)
        assertEquals(0, permissionRequests)

        // 2. When no UnifiedPush distributor is installed, notice is shown and toggle is disabled
        composeRule.onNodeWithTag("phone-notify-no-distributor-notice").assertIsDisplayed()
        composeRule.onNodeWithText("UnifiedPush distributor required").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-notify-push-toggle")
            .assertIsNotEnabled()
            .assertIsNotSelected()

        // 3. When a distributor becomes available (e.g. ntfy installed), clicking the switch
        // requests POST_NOTIFICATIONS; if denied, switch stays off and shows the warning banner
        distributorAvailable = true
        grantPermission = false
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("phone-notify-push-toggle").performClick()
        composeRule.waitForIdle()
        assertEquals(1, permissionRequests)
        assertTrue(toggledPushStates.isEmpty())
        composeRule.onNodeWithTag("phone-notify-push-toggle").assertIsNotSelected()
        composeRule.onNodeWithTag("phone-notifications-off-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-notifications-off-banner").performClick()
        assertTrue(openedAndroidSettings)

        // 4. When user turns the switch on and grants POST_NOTIFICATIONS, switch turns on and registers push
        grantPermission = true
        composeRule.onNodeWithTag("phone-notify-push-toggle").performClick()
        composeRule.waitForIdle()
        assertEquals(2, permissionRequests)
        assertEquals(listOf(true), toggledPushStates)
        composeRule.onNodeWithTag("phone-notify-push-toggle").assertIsSelected()
    }
}
