package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `GET /api/notifications/fcm` and the FCM shape of `POST /api/notifications/push/subscribe`. */
class FcmApiTest {
    private fun json(body: String, status: Int = 200) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun <T> withServer(block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        server.start()
        try { block(server, ApiClient(server.url("/"), "t")) } finally { server.close() }
    }

    @Test fun `a configured server answers the android client entry`() = withServer { server, api ->
        server.enqueue(
            json(
                """{"ok":true,"data":{"configured":true,"projectId":"ppm-home",
                "android":{"apiKey":"AIzaKey","appId":"1:123:android:abc","senderId":"123","projectId":"ppm-home","storageBucket":"ppm-home.appspot.com"}}}""",
            ),
        )
        val config = api.notificationsGetFcm()
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/api/notifications/fcm", request.url.encodedPath)
        assertEquals("Bearer t", request.headers["Authorization"])
        assertEquals(
            FcmServerConfig(
                configured = true,
                projectId = "ppm-home",
                android = FcmAndroidConfig("AIzaKey", "1:123:android:abc", "123", "ppm-home", "ppm-home.appspot.com"),
            ),
            config,
        )
        assertTrue(config.usable)
    }

    @Test fun `not configured, and configured without an android entry, are not usable`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"configured":false,"android":null}}"""))
        assertEquals(FcmServerConfig.NOT_CONFIGURED, api.notificationsGetFcm())
        server.enqueue(json("""{"ok":true,"data":{"configured":true,"projectId":"p","android":null}}"""))
        val noAndroid = api.notificationsGetFcm()
        assertTrue(noAndroid.configured)
        assertFalse(noAndroid.usable)
    }

    @Test fun `an android entry missing a required field is no entry`() {
        assertNull(FcmAndroidConfig.from(WireJson.parseOrNull("""{"apiKey":"k","appId":"a","senderId":"","projectId":"p"}""")))
        assertNull(FcmAndroidConfig.from(WireJson.parseOrNull("""{"apiKey":"k","appId":"a","projectId":"p"}""")))
        assertEquals(
            FcmAndroidConfig("k", "a", "s", "p"),
            FcmAndroidConfig.from(WireJson.parseOrNull("""{"apiKey":"k","appId":"a","senderId":"s","projectId":"p"}""")),
        )
    }

    @Test fun `the android entry round-trips through its JSON`() {
        val config = FcmAndroidConfig("k", "a", "s", "p", "b")
        assertEquals(config, FcmAndroidConfig.from(config.toJson()))
        assertEquals(FcmAndroidConfig("k", "a", "s", "p"), FcmAndroidConfig.from(FcmAndroidConfig("k", "a", "s", "p").toJson()))
    }

    @Test fun `an older server without the route is not configured, never an error`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"Not found"}""", 404))
        assertEquals(FcmServerConfig.NOT_CONFIGURED, api.notificationsGetFcm())
        server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
        assertEquals(FcmServerConfig.NOT_CONFIGURED, api.notificationsGetFcm())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html></html>").build())
        assertEquals(FcmServerConfig.NOT_CONFIGURED, api.notificationsGetFcm())
    }

    @Test fun `a server error other than 404 is reported`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"boom"}""", 500))
        try {
            api.notificationsGetFcm()
            fail("expected an error")
        } catch (error: PpmError.Server) {
            assertEquals(500, error.status)
        }
    }

    @Test fun `subscribing an FCM token posts kind fcm with the token, label and the app's origin`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"id":"dev-1","kind":"fcm","token":"tok","label":"Android · Pixel 7","createdAt":"2026-11-22T00:00:00Z"}}"""))
        val device = api.subscribeFcmPush(token = "tok", label = "Android · Pixel 7")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/notifications/push/subscribe", request.url.encodedPath)
        assertEquals(
            WireJson.parseOrNull("""{"kind":"fcm","token":"tok","label":"Android · Pixel 7","origin":"""" + api.originUrl() + """"}"""),
            WireJson.parseOrNull(request.body!!.utf8()),
        )
        assertEquals("dev-1", device.id)
        assertEquals("Android · Pixel 7", device.label)
    }

    @Test fun `a token refresh names the device it replaces`() = withServer { server, api ->
        server.enqueue(json("""{"ok":true,"data":{"id":"dev-1","kind":"fcm","label":"Android · Pixel 7"}}"""))
        api.subscribeFcmPush(token = "tok2", label = "Android · Pixel 7", deviceId = "dev-1", origin = "")
        assertEquals(
            WireJson.parseOrNull("""{"kind":"fcm","token":"tok2","label":"Android · Pixel 7","deviceId":"dev-1"}"""),
            WireJson.parseOrNull(server.takeRequest(2, TimeUnit.SECONDS)!!.body!!.utf8()),
        )
    }

    @Test fun `a server without FCM subscriptions refuses the subscribe as an error`() = withServer { server, api ->
        server.enqueue(json("""{"ok":false,"error":"Invalid subscription"}""", 400))
        try {
            api.subscribeFcmPush(token = "tok")
            fail("expected a refusal")
        } catch (error: PpmError.Server) {
            assertEquals(400, error.status)
        }
    }
}
