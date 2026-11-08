package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class AndroidClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ok(data: String) =
        MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("""{"ok":true,"data":$data}""")
            .build()

    private fun withServer(
        vararg responses: MockResponse,
        block: suspend (MockWebServer, ApiClient) -> Unit,
    ) = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "secret-token"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `discovery and lifecycle endpoints send exact paths query strings and bodies without Origin header`() = withServer(
        ok("""{"enabled":true}"""),
        ok("""{"devices":[]}"""),
        ok("""{"operationId":"op-1","state":"pending","detail":"starting"}"""),
        ok("""{"id":"op-1","state":"succeeded","detail":"booted","result":{"deviceId":"emu-5554-gen1"},"error":null}"""),
        ok("""{"cancelled":true}"""),
        ok("""{"outcome":"stopped"}"""),
        ok("""{"outcome":"stopped"}"""),
        ok("""{"lines":["emulator: boot completed"]}"""),
        ok("""{"nonce":"nonce-abc","deviceId":"emu-5554-gen1","generation":2,"hardwareKeyboard":true}"""),
    ) { server, api ->
        api.androidCapabilities()
        val reqCaps = server.takeRequest()
        assertEquals("GET", reqCaps.method)
        assertEquals("/api/android/capabilities", reqCaps.target)
        assertEquals("Bearer secret-token", reqCaps.headers["Authorization"])
        assertNull(reqCaps.headers["Origin"])

        api.androidDevices()
        val reqDevs = server.takeRequest()
        assertEquals("GET", reqDevs.method)
        assertEquals("/api/android/devices", reqDevs.target)

        api.androidStartAvd("Pixel 8 API 34")
        val reqStart = server.takeRequest()
        assertEquals("POST", reqStart.method)
        assertEquals("/api/android/avds/Pixel%208%20API%2034/start", reqStart.target)
        assertEquals("{}", reqStart.body?.utf8())

        val opJson = api.androidOperationStatus("op-1").jsonObject
        val reqOp = server.takeRequest()
        assertEquals("GET", reqOp.method)
        assertEquals("/api/android/operations/op-1", reqOp.target)
        assertEquals("succeeded", opJson["state"]?.jsonPrimitive?.content)

        api.androidCancelOperation("op-1")
        val reqCancel = server.takeRequest()
        assertEquals("POST", reqCancel.method)
        assertEquals("/api/android/operations/op-1/cancel", reqCancel.target)
        assertEquals("{}", reqCancel.body?.utf8())

        api.androidStopDevice("emu-5554-gen1", generation = 3)
        val reqStopGen = server.takeRequest()
        assertEquals("POST", reqStopGen.method)
        assertEquals("/api/android/devices/emu-5554-gen1/stop?generation=3", reqStopGen.target)
        assertEquals("{}", reqStopGen.body?.utf8())

        api.androidStopDevice("emu-5554-gen1", generation = null)
        val reqStopNoGen = server.takeRequest()
        assertEquals("POST", reqStopNoGen.method)
        assertEquals("/api/android/devices/emu-5554-gen1/stop", reqStopNoGen.target)

        api.androidDeviceBootLog("emu-5554-gen1")
        val reqLog = server.takeRequest()
        assertEquals("GET", reqLog.method)
        assertEquals("/api/android/devices/emu-5554-gen1/log", reqLog.target)

        api.androidCreateSession("emu-5554-gen1")
        val reqSess = server.takeRequest()
        assertEquals("POST", reqSess.method)
        assertEquals("/api/android/devices/emu-5554-gen1/sessions", reqSess.target)
        assertEquals("{}", reqSess.body?.utf8())
    }

    @Test
    fun `AVD management clipboard screenshot and APK endpoints match server routes`() = withServer(
        ok("""{"images":[]}"""),
        ok("""{"profiles":[]}"""),
        ok("""{"avd":{"avdId":"Pixel_8","name":"Pixel_8"}}"""),
        ok("""{"removed":["userdata-qemu.img.qcow2"],"freedBytes":1024,"message":"wiped"}"""),
        ok("""{"deleted":"Pixel 8"}"""),
        ok("""{"text":"clip-out"}"""),
        ok("""{"set":true}"""),
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "image/png")
            .addHeader("Content-Disposition", "attachment; filename=\"screenshot-Pixel_8.png\"")
            .addHeader("X-Android-Screenshot-Size", "1080x2400")
            .body(Buffer().write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
            .build(),
        ok("""{"apks":[]}"""),
        ok("""{"operationId":"op-proj-apk","filename":"app-debug.apk"}"""),
        ok("""{"operationId":"op-upload-apk","bytes":6,"filename":"sample.apk"}"""),
    ) { server, api ->
        api.androidSystemImages()
        assertEquals("/api/android/system-images", server.takeRequest().target)

        api.androidDeviceProfiles()
        assertEquals("/api/android/device-profiles", server.takeRequest().target)

        api.androidCreateAvd(
            name = "Pixel_8",
            systemImage = "system-images;android-35;google_apis;x86_64",
            deviceProfile = "pixel_8",
            ramMb = 4096,
            storageMb = 8192,
            sdCardMb = 512,
        )
        val reqCreate = server.takeRequest()
        assertEquals("POST", reqCreate.method)
        assertEquals("/api/android/avds", reqCreate.target)
        val createBody = json.parseToJsonElement(reqCreate.body!!.utf8()).jsonObject
        assertEquals("Pixel_8", createBody["name"]?.jsonPrimitive?.content)
        assertEquals("system-images;android-35;google_apis;x86_64", createBody["systemImage"]?.jsonPrimitive?.content)
        assertEquals("pixel_8", createBody["deviceProfile"]?.jsonPrimitive?.content)
        assertEquals(4096, createBody["ramMb"]?.jsonPrimitive?.int)
        assertEquals(8192, createBody["storageMb"]?.jsonPrimitive?.int)
        assertEquals(512, createBody["sdCardMb"]?.jsonPrimitive?.int)

        api.androidWipeAvd("Pixel_8_id", "Pixel 8")
        val reqWipe = server.takeRequest()
        assertEquals("POST", reqWipe.method)
        assertEquals("/api/android/avds/Pixel_8_id/wipe", reqWipe.target)
        val wipeBody = json.parseToJsonElement(reqWipe.body!!.utf8()).jsonObject
        assertEquals("Pixel 8", wipeBody["confirmName"]?.jsonPrimitive?.content)

        api.androidDeleteAvd("Pixel_8_id", "Pixel 8")
        val reqDel = server.takeRequest()
        assertEquals("DELETE", reqDel.method)
        assertEquals("/api/android/avds/Pixel_8_id?confirmName=Pixel%208", reqDel.target)
        val delBody = json.parseToJsonElement(reqDel.body!!.utf8()).jsonObject
        assertEquals("Pixel 8", delBody["confirmName"]?.jsonPrimitive?.content)

        api.androidGetClipboard("emu-5554-gen1")
        val reqGetClip = server.takeRequest()
        assertEquals("GET", reqGetClip.method)
        assertEquals("/api/android/devices/emu-5554-gen1/clipboard", reqGetClip.target)

        api.androidSetClipboard("emu-5554-gen1", "hello emulator")
        val reqSetClip = server.takeRequest()
        assertEquals("POST", reqSetClip.method)
        assertEquals("/api/android/devices/emu-5554-gen1/clipboard", reqSetClip.target)
        val clipBody = json.parseToJsonElement(reqSetClip.body!!.utf8()).jsonObject
        assertEquals("hello emulator", clipBody["text"]?.jsonPrimitive?.content)

        val shot = api.androidTakeScreenshot("emu-5554-gen1")
        val reqShot = server.takeRequest()
        assertEquals("GET", reqShot.method)
        assertEquals("/api/android/devices/emu-5554-gen1/screenshot", reqShot.target)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), shot.pngBytes)
        assertEquals("1080x2400", shot.dimensionsHeader)
        assertTrue(shot.contentDisposition.orEmpty().contains("screenshot-Pixel_8.png"))

        api.androidProjectApks("my app")
        val reqApks = server.takeRequest()
        assertEquals("GET", reqApks.method)
        assertEquals("/api/android/projects/my%20app/apks", reqApks.target)

        api.androidInstallProjectApk(
            deviceId = "emu-5554-gen1",
            project = "my app",
            path = "app/build/outputs/apk/debug/app-debug.apk",
            downgrade = true,
        )
        val reqProjApk = server.takeRequest()
        assertEquals("POST", reqProjApk.method)
        assertEquals("/api/android/devices/emu-5554-gen1/apk/project", reqProjApk.target)
        val projApkBody = json.parseToJsonElement(reqProjApk.body!!.utf8()).jsonObject
        assertEquals("my app", projApkBody["project"]?.jsonPrimitive?.content)
        assertEquals("app/build/outputs/apk/debug/app-debug.apk", projApkBody["path"]?.jsonPrimitive?.content)
        assertEquals(true, projApkBody["downgrade"]?.jsonPrimitive?.boolean)

        val apkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x01, 0x02)
        val progressEvents = mutableListOf<Pair<Long, Long>>()
        val uploadRes = api.androidUploadApk(
            deviceId = "emu-5554-gen1",
            fileName = "sample app.apk",
            contentLength = apkBytes.size.toLong(),
            allowDowngrade = true,
            reinstall = false,
            openStream = { ByteArrayInputStream(apkBytes) },
            onProgress = { loaded, total -> progressEvents.add(loaded to total) },
        ).jsonObject
        val reqUpload = server.takeRequest()
        assertEquals("POST", reqUpload.method)
        assertEquals(
            "/api/android/devices/emu-5554-gen1/apk?filename=sample%20app.apk&reinstall=0&downgrade=1",
            reqUpload.target,
        )
        assertEquals("application/vnd.android.package-archive", reqUpload.headers["Content-Type"])
        assertNull(reqUpload.headers["Origin"])
        assertArrayEquals(apkBytes, reqUpload.body?.toByteArray())
        assertEquals("op-upload-apk", uploadRes["operationId"]?.jsonPrimitive?.content)
        assertEquals(6L to 6L, progressEvents.last())

        val wsUrl = api.androidSocketUrl()
        assertTrue(wsUrl.startsWith("ws://"))
        assertTrue(wsUrl.endsWith("/ws/android?token=secret-token"))
    }
}
