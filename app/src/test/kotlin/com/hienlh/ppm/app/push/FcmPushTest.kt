package com.hienlh.ppm.app.push

import com.hienlh.ppm.app.ui.UnifiedPushThisDeviceRegistrar
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.FcmAndroidConfig
import com.hienlh.ppm.client.FcmServerConfig
import com.hienlh.ppm.client.FeedKind
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.originUrl
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** FCM push: the transport choice, the message payload, Firebase's options, and the server calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FcmPushTest {

    private val config = FcmAndroidConfig("AIzaKey", "1:123:android:abc", "123", "ppm-home", "ppm-home.appspot.com")
    private val configured = FcmServerConfig(configured = true, projectId = "ppm-home", android = config)

    private class Memory : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class FakeGateway(
        var gms: Boolean = true,
        var existing: String? = null,
        var initOk: Boolean = true,
    ) : FirebaseGateway {
        var token = "tok-1"
        var deleted = 0
        val initialized = mutableListOf<FcmAndroidConfig>()
        override fun playServicesAvailable() = gms
        override fun existingProjectId() = existing
        override fun initialize(config: FcmAndroidConfig): Boolean {
            initialized += config
            if (!initOk) return false
            existing = config.projectId
            return true
        }
        override suspend fun token() = token
        override suspend fun deleteToken() {
            deleted++
        }
    }

    private val server = MockWebServer()

    @After fun tearDown() {
        runCatching { server.close() }
    }

    private fun json(body: String, status: Int = 200) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun api(): ApiClient {
        server.start()
        return ApiClient(server.url("/"), "t")
    }

    private fun RecordedRequest.json() = WireJson.parseOrNull(body!!.utf8())

    // --- choosePushTransport -------------------------------------------------------------------

    @Test fun `a configured server with Play services pushes through Firebase`() {
        assertEquals(PushTransport.Firebase(config), choosePushTransport(configured, playServicesAvailable = true, existingProjectId = null))
        assertEquals(PushTransport.Firebase(config), choosePushTransport(configured, true, existingProjectId = "ppm-home"))
    }

    @Test fun `no server answer, not configured, or no android entry is UnifiedPush`() {
        val notConfigured = PushTransport.UnifiedPush(FcmFallbackReason.NOT_CONFIGURED)
        assertEquals(notConfigured, choosePushTransport(null, true, null))
        assertEquals(notConfigured, choosePushTransport(FcmServerConfig.NOT_CONFIGURED, true, null))
        assertEquals(notConfigured, choosePushTransport(FcmServerConfig(configured = true, projectId = "p"), true, null))
        assertEquals(notConfigured, choosePushTransport(FcmServerConfig(configured = false, android = config), true, null))
    }

    @Test fun `no Play services is UnifiedPush`() {
        assertEquals(
            PushTransport.UnifiedPush(FcmFallbackReason.NO_PLAY_SERVICES),
            choosePushTransport(configured, playServicesAvailable = false, existingProjectId = null),
        )
    }

    @Test fun `Firebase already running another server's project is UnifiedPush`() {
        assertEquals(
            PushTransport.UnifiedPush(FcmFallbackReason.OTHER_PROJECT),
            choosePushTransport(configured, playServicesAvailable = true, existingProjectId = "someone-else"),
        )
    }

    // --- message -> payload ---------------------------------------------------------------------

    @Test fun `an FCM data message carries the Web Push payload under ppm`() {
        val payload = fcmPushPayload(
            mapOf(
                "ppm" to """{"title":"Chat finished","body":"Done","url":"https://ppm.example/project/app?openChat=claude%2Fs1",
                    "tag":"chat-s1","project":"app","sessionId":"s1","providerId":"claude","kind":"done"}""",
            ),
        )!!
        assertEquals("Chat finished", payload.title)
        assertEquals("Done", payload.body)
        assertEquals("chat-s1", payload.tag)
        assertEquals("app", payload.project)
        assertEquals("s1", payload.sessionId)
        assertEquals("claude", payload.providerId)
        assertEquals(FeedKind.DONE, payload.kind)
        assertEquals(PushChannel.DONE, payload.channel)
        // The same tap target as a UnifiedPush message.
        assertEquals("ppm://ppm.example/project/app?openChat=claude%2Fs1", buildPpmDeepLink(payload))
    }

    @Test fun `a message without a PPM payload is dropped`() {
        assertNull(fcmPushPayload(emptyMap()))
        assertNull(fcmPushPayload(mapOf("other" to "{}")))
        assertNull(fcmPushPayload(mapOf("ppm" to "not json")))
        assertNull(fcmPushPayload(mapOf("ppm" to """{"body":"no title"}""")))
    }

    // --- FirebaseOptions --------------------------------------------------------------------------

    @Test fun `the server's android entry becomes Firebase's options`() {
        val options = firebaseOptions(config)
        assertEquals("ppm-home", options.projectId)
        assertEquals("1:123:android:abc", options.applicationId)
        assertEquals("AIzaKey", options.apiKey)
        assertEquals("123", options.gcmSenderId)
        assertEquals("ppm-home.appspot.com", options.storageBucket)
        assertNull(firebaseOptions(config.copy(storageBucket = null)).storageBucket)
    }

    // --- enrollment store -----------------------------------------------------------------------

    @Test fun `enrollments are kept per server with the config Firebase starts with`() {
        val store = Memory()
        FcmEnrollment.record(store, "srv-1", "dev-1", "http://a:3210", config)
        FcmEnrollment.record(store, "srv-2", "dev-2", "http://b:3210", config)
        FcmEnrollment.record(store, "srv-1", "dev-1b", "http://a:3210", config)
        assertEquals(listOf("srv-2", "srv-1"), FcmEnrollment.instances(store))
        assertTrue(FcmEnrollment.isFcm(store, "srv-1"))
        assertTrue(PushDeviceManager.isEnabled(store, "srv-1"))
        assertEquals("dev-1b", PushDeviceManager.savedDeviceId(store, "srv-1"))
        assertEquals(config, FcmEnrollment.savedConfig(store))

        assertTrue(FcmEnrollment.remove(store, "srv-1"))
        assertFalse(FcmEnrollment.isFcm(store, "srv-1"))
        assertNull(PushDeviceManager.savedDeviceId(store, "srv-1"))
        assertEquals(config, FcmEnrollment.savedConfig(store))
        assertFalse(FcmEnrollment.remove(store, "srv-2"))
        assertNull(FcmEnrollment.savedConfig(store))
        assertTrue(FcmEnrollment.instances(store).isEmpty())
    }

    // --- registration flow against a server -----------------------------------------------------

    @Test fun `enrolling starts Firebase, subscribes the token and remembers the id`() = runBlocking {
        val api = api()
        server.enqueue(json("""{"ok":true,"data":{"id":"dev-1","kind":"fcm","label":"Android · Pixel 7","endpoint":""}}"""))
        val store = Memory()
        val gateway = FakeGateway()
        val id = FcmPushRegistration(store, gateway, "Android · Pixel 7").enroll("srv-1", api, config, "http://a:3210")

        assertEquals("dev-1", id)
        assertEquals(listOf(config), gateway.initialized)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST /api/notifications/push/subscribe", "${request.method} ${request.url.encodedPath}")
        assertEquals(
            WireJson.parseOrNull("""{"kind":"fcm","token":"tok-1","label":"Android · Pixel 7","origin":"""" + api.originUrl() + """"}"""),
            request.json(),
        )
        assertTrue(FcmEnrollment.isFcm(store, "srv-1"))
        assertEquals("dev-1", PushDeviceManager.savedDeviceId(store, "srv-1"))
    }

    @Test fun `enrolling when Firebase runs another project fails before any request`() = runBlocking {
        val api = api()
        val gateway = FakeGateway(initOk = false)
        try {
            FcmPushRegistration(Memory(), gateway, "Android · X").enroll("srv-1", api, config, null)
            fail("expected a refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("another server"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `a new token re-subscribes every enrolled server, replacing its entry`() = runBlocking {
        val api = api()
        server.enqueue(json("""{"ok":true,"data":{"id":"dev-1","kind":"fcm","label":"L"}}"""))
        server.enqueue(json("""{"ok":true,"data":{"id":"dev-2","kind":"fcm","label":"L"}}"""))
        val store = Memory()
        FcmEnrollment.record(store, "srv-1", "dev-1", null, config)
        FcmEnrollment.record(store, "srv-2", "dev-2", null, config)
        val asked = mutableListOf<String>()
        FcmPushRegistration(store, FakeGateway(), "L").refreshToken("tok-2") { asked += it; api }

        assertEquals(listOf("srv-1", "srv-2"), asked)
        val bodies = List(2) { server.takeRequest(2, TimeUnit.SECONDS)!!.json() }
        assertEquals(
            listOf("dev-1", "dev-2").map {
                WireJson.parseOrNull("""{"kind":"fcm","token":"tok-2","label":"L","deviceId":"$it","origin":"""" + api.originUrl() + """"}""")
            },
            bodies,
        )
    }

    @Test fun `a server that fails the refresh does not stop the others`() = runBlocking {
        val api = api()
        server.enqueue(json("""{"ok":false,"error":"boom"}""", 500))
        server.enqueue(json("""{"ok":true,"data":{"id":"dev-2","kind":"fcm","label":"L"}}"""))
        val store = Memory()
        FcmEnrollment.record(store, "srv-1", "dev-1", null, config)
        FcmEnrollment.record(store, "srv-2", "dev-2", null, config)
        FcmPushRegistration(store, FakeGateway(), "L").refreshToken("tok-2") { api }
        assertEquals(2, server.requestCount)
    }

    @Test fun `unenrolling removes the subscription by id and drops the token with the last server`() = runBlocking {
        val api = api()
        server.enqueue(json("""{"ok":true,"data":{"removed":true}}"""))
        server.enqueue(json("""{"ok":true,"data":{"removed":true}}"""))
        val store = Memory()
        val gateway = FakeGateway()
        FcmEnrollment.record(store, "srv-1", "dev-1", null, config)
        FcmEnrollment.record(store, "srv-2", "dev-2", null, config)
        val registration = FcmPushRegistration(store, gateway, "L")

        registration.unenroll("srv-1", api)
        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST /api/notifications/push/unsubscribe", "${first.method} ${first.url.encodedPath}")
        assertEquals(WireJson.parseOrNull("""{"id":"dev-1"}"""), first.json())
        assertEquals(0, gateway.deleted)

        registration.unenroll("srv-2", api)
        assertEquals(WireJson.parseOrNull("""{"id":"dev-2"}"""), server.takeRequest(2, TimeUnit.SECONDS)!!.json())
        assertEquals(1, gateway.deleted)
        assertNull(FcmEnrollment.savedConfig(store))
    }

    // --- the switch's real registrar -------------------------------------------------------------

    @Test fun `the registrar asks the server and falls back on an older server`() = runBlocking {
        val api = api()
        val context = RuntimeEnvironment.getApplication()
        server.enqueue(json("""{"ok":true,"data":{"configured":true,"projectId":"ppm-home","android":${config.toJson()}}}"""))
        server.enqueue(json("""{"ok":false,"error":"Not found"}""", 404))
        server.enqueue(json("""{"ok":true,"data":{"configured":true,"projectId":"ppm-home","android":${config.toJson()}}}"""))
        val registrar = UnifiedPushThisDeviceRegistrar(context, Memory(), api, FakeGateway())
        assertEquals(PushTransport.Firebase(config), registrar.transport("srv-1"))
        assertEquals(PushTransport.UnifiedPush(FcmFallbackReason.NOT_CONFIGURED), registrar.transport("srv-1"))
        val noGms = UnifiedPushThisDeviceRegistrar(context, Memory(), api, FakeGateway(gms = false))
        assertEquals(PushTransport.UnifiedPush(FcmFallbackReason.NO_PLAY_SERVICES), noGms.transport("srv-1"))
        assertEquals("/api/notifications/fcm", server.takeRequest(2, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test fun `the registrar enrolls through FCM and switching off unsubscribes that entry`() = runBlocking {
        val api = api()
        val context = RuntimeEnvironment.getApplication()
        server.enqueue(json("""{"ok":true,"data":{"id":"dev-9","kind":"fcm","label":"L"}}"""))
        server.enqueue(json("""{"ok":true,"data":{"removed":true}}"""))
        val store = Memory()
        val registrar = UnifiedPushThisDeviceRegistrar(context, store, api, FakeGateway())

        assertEquals("dev-9", registrar.registerFcm("srv-1", "http://a:3210", config))
        assertTrue(registrar.enrolledViaFcm("srv-1"))
        assertTrue(registrar.isEnabled("srv-1"))
        assertEquals("dev-9", registrar.savedDeviceId("srv-1"))

        registrar.unregister("srv-1")
        server.takeRequest(2, TimeUnit.SECONDS)
        val off = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/notifications/push/unsubscribe", off.url.encodedPath)
        assertEquals(WireJson.parseOrNull("""{"id":"dev-9"}"""), off.json())
        assertFalse(registrar.enrolledViaFcm("srv-1"))
        assertFalse(registrar.isEnabled("srv-1"))
    }
}
