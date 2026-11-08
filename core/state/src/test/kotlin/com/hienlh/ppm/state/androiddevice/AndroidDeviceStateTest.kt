package com.hienlh.ppm.state.androiddevice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AndroidDeviceStateTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `quality presets match ANDROID_QUALITY_PRESETS in android-protocol ts`() {
        assertEquals(720, AndroidQuality.LOW.maxHeight)
        assertEquals(24, AndroidQuality.LOW.fps)
        assertEquals(1_500_000, AndroidQuality.LOW.bitrateBps)
        assertEquals("1.5M", AndroidQuality.LOW.bitrateLabel)

        assertEquals(1280, AndroidQuality.BALANCED.maxHeight)
        assertEquals(30, AndroidQuality.BALANCED.fps)
        assertEquals(4_000_000, AndroidQuality.BALANCED.bitrateBps)
        assertEquals("4M", AndroidQuality.BALANCED.bitrateLabel)

        assertEquals(1920, AndroidQuality.HIGH.maxHeight)
        assertEquals(30, AndroidQuality.HIGH.fps)
        assertEquals(8_000_000, AndroidQuality.HIGH.bitrateBps)
        assertEquals("8M", AndroidQuality.HIGH.bitrateLabel)
    }

    @Test
    fun `computeAndroidLetterbox and pointerToFrame reject letterbox bars`() {
        val rect = computeAndroidLetterbox(
            stageWidth = 1000f,
            stageHeight = 1000f,
            frameWidth = 500,
            frameHeight = 1000,
        )
        assertEquals(1f, rect.scale, 0.001f)
        assertEquals(500f, rect.width, 0.001f)
        assertEquals(1000f, rect.height, 0.001f)
        assertEquals(250f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)

        // Left & right letterbox bars return null
        assertNull(pointerToFrame(100f, 500f, rect, 500, 1000))
        assertNull(pointerToFrame(800f, 500f, rect, 500, 1000))

        // Inside active video rect
        val inside = pointerToFrame(500f, 500f, rect, 500, 1000)
        assertNotNull(inside)
        assertEquals(250f, inside!!.first, 0.01f)
        assertEquals(500f, inside.second, 0.01f)
    }

    @Test
    fun `frameToDevice rotates coordinates across 0, 90, 180 and 270 degrees`() {
        val baseGeo = AndroidGeometry(
            width = 720,
            height = 1600,
            deviceWidth = 1080,
            deviceHeight = 2400,
            rotation = 0,
            generation = 3,
        )

        // 0°: top-left -> (0, 0), center -> (540, 1200)
        assertEquals(AndroidIntPoint(0, 0), frameToDevice(0f, 0f, baseGeo))
        assertEquals(AndroidIntPoint(540, 1200), frameToDevice(360f, 800f, baseGeo))

        // 90°: u=0.25, v=0.75 => pu = 1 - 0.75 = 0.25, pv = 0.25
        val geo90 = baseGeo.copy(width = 1600, height = 720, rotation = 90)
        val pt90 = frameToDevice(400f, 540f, geo90)
        assertEquals(270, pt90.x)
        assertEquals(600, pt90.y)

        // 180°: u=0.25, v=0.25 => pu = 0.75, pv = 0.75
        val geo180 = baseGeo.copy(rotation = 180)
        val pt180 = frameToDevice(180f, 400f, geo180)
        assertEquals(810, pt180.x)
        assertEquals(1800, pt180.y)

        // 270°: u=0.25, v=0.75 => pu = 0.75, pv = 1 - 0.25 = 0.75
        val geo270 = baseGeo.copy(width = 1600, height = 720, rotation = 270)
        val pt270 = frameToDevice(400f, 540f, geo270)
        assertEquals(810, pt270.x)
        assertEquals(1800, pt270.y)
    }

    @Test
    fun `AndroidTouchTracker emits all active contacts plus pressure 0 on release`() {
        val tracker = AndroidTouchTracker()
        val down0 = tracker.onDown(100L, AndroidIntPoint(10, 20))
        assertEquals(listOf(AndroidTouchPoint(id = 0, x = 10, y = 20, pressure = 1)), down0)

        val down1 = tracker.onDown(200L, AndroidIntPoint(30, 40))
        assertEquals(
            listOf(
                AndroidTouchPoint(id = 0, x = 10, y = 20, pressure = 1),
                AndroidTouchPoint(id = 1, x = 30, y = 40, pressure = 1),
            ),
            down1,
        )

        // Release first finger: slot 1 stays pressure=1, slot 0 is emitted with pressure=0
        val up0 = tracker.onUpOrCancel(100L)
        assertEquals(
            listOf(
                AndroidTouchPoint(id = 1, x = 30, y = 40, pressure = 1),
                AndroidTouchPoint(id = 0, x = 10, y = 20, pressure = 0),
            ),
            up0,
        )

        // Next finger reuses freed slot 0
        val down2 = tracker.onDown(300L, AndroidIntPoint(50, 60))
        assertEquals(
            listOf(
                AndroidTouchPoint(id = 1, x = 30, y = 40, pressure = 1),
                AndroidTouchPoint(id = 0, x = 50, y = 60, pressure = 1),
            ),
            down2,
        )

        assertTrue(tracker.reset())
        assertFalse(tracker.reset())
    }

    @Test
    fun `encode and decode 22-byte video frame round-trip`() {
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F)
        val header = AndroidVideoHeader(
            version = ANDROID_PROTOCOL_VERSION,
            keyframe = true,
            codecConfig = true,
            sessionGeneration = 7,
            geometryGeneration = 3,
            sequence = 42_000L,
            ptsMs = 1234.5,
            payloadLength = payload.size,
        )
        val encoded = encodeAndroidVideoFrame(header, payload)
        assertEquals(ANDROID_VIDEO_HEADER_BYTES + payload.size, encoded.size)

        val decoded = decodeAndroidVideoFrame(encoded)
        assertNotNull(decoded)
        assertEquals(header, decoded!!.header)
        assertArrayEquals(payload, decoded.payload)

        // Reject short buffer, wrong version, or mismatched payload length
        assertNull(decodeAndroidVideoFrame(ByteArray(21)))
        val badVersion = encoded.copyOf().also { it[0] = 2 }
        assertNull(decodeAndroidVideoFrame(badVersion))
        assertNull(decodeAndroidVideoFrame(encoded + byteArrayOf(0x99.toByte())))
    }

    @Test
    fun `decodeAndroidVideoFrame decodes real server encodeVideoFrame bytes`() {
        // Produced by server's encodeVideoFrame in src/shared/android-protocol.ts via bun:
        // encodeVideoFrame({ keyframe: true, codecConfig: true, sessionGeneration: 7, geometryGeneration: 3, sequence: 42000, ptsMs: 1234.5 }, Uint8Array([0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f]))
        val serverKeyframeHex = "01030700030010a4000000000000004a934008000000000000016742001f"
        val keyframeBytes = serverKeyframeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decodedKeyframe = decodeAndroidVideoFrame(keyframeBytes)
        assertNotNull(decodedKeyframe)
        assertEquals(1, decodedKeyframe!!.header.version)
        assertTrue(decodedKeyframe.header.keyframe)
        assertTrue(decodedKeyframe.header.codecConfig)
        assertEquals(7, decodedKeyframe.header.sessionGeneration)
        assertEquals(3, decodedKeyframe.header.geometryGeneration)
        assertEquals(42_000L, decodedKeyframe.header.sequence)
        assertEquals(1234.5, decodedKeyframe.header.ptsMs, 0.0)
        assertEquals(8, decodedKeyframe.header.payloadLength)
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F),
            decodedKeyframe.payload,
        )

        // encodeVideoFrame({ keyframe: false, codecConfig: false, sessionGeneration: 2, geometryGeneration: 1, sequence: 99, ptsMs: 33.333 }, Uint8Array([0x00, 0x00, 0x00, 0x01, 0x41, 0x9a]))
        val serverDeltaHex = "01000200010063000000b4c876be9faa40400600000000000001419a"
        val deltaBytes = serverDeltaHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decodedDelta = decodeAndroidVideoFrame(deltaBytes)
        assertNotNull(decodedDelta)
        assertEquals(1, decodedDelta!!.header.version)
        assertFalse(decodedDelta.header.keyframe)
        assertFalse(decodedDelta.header.codecConfig)
        assertEquals(2, decodedDelta.header.sessionGeneration)
        assertEquals(1, decodedDelta.header.geometryGeneration)
        assertEquals(99L, decodedDelta.header.sequence)
        assertEquals(33.333, decodedDelta.header.ptsMs, 1e-9)
        assertEquals(6, decodedDelta.header.payloadLength)
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x9A.toByte()),
            decodedDelta.payload,
        )
    }

    @Test
    fun `filterAndroidLogs and appendBoundedLogs dedupe by id and cap ring`() {
        val entries = listOf(
            AndroidLogEntry(1L, 1_700_000_000_123L, 100, 101, AndroidLogLevel.DEBUG, "ActivityManager", "Start proc"),
            AndroidLogEntry(2L, 1_700_000_000_456L, 200, 201, AndroidLogLevel.WARN, "WindowManager", "Slow layout"),
            AndroidLogEntry(3L, 1_700_000_000_789L, 200, 202, AndroidLogLevel.ERROR, "AndroidRuntime", "FATAL EXCEPTION: main"),
        )
        val warnOrHigher = filterAndroidLogs(entries, AndroidLogFilter(minimum = AndroidLogLevel.WARN))
        assertEquals(2, warnOrHigher.size)

        val runtimeOnly = filterAndroidLogs(entries, AndroidLogFilter(text = "exception"))
        assertEquals(1, runtimeOnly.size)
        assertEquals(3L, runtimeOnly.single().id)

        // Replay of ids 2..3 plus new id 4 dedupes ids <= 3
        val replayed = listOf(
            entries[1],
            entries[2],
            AndroidLogEntry(4L, 1_700_000_000_999L, 200, 202, AndroidLogLevel.INFO, "System", "Boot completed"),
        )
        val merged = appendBoundedLogs(entries, replayed, max = 10)
        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.id })

        // Cap ring at 3
        val capped = appendBoundedLogs(entries, replayed, max = 3)
        assertEquals(listOf(2L, 3L, 4L), capped.map { it.id })

        val formatted = formatAndroidLogText(listOf(entries[2]), ZoneOffset.UTC)
        assertTrue(formatted.contains("200   202 E AndroidRuntime: FATAL EXCEPTION: main"))
    }

    @Test
    fun `AVD validation matches src shared android-avd ts`() {
        assertNull(validateAvdName("Pixel_9_API_35", listOf("Other_AVD")))
        assertNotNull(validateAvdName("", emptyList()))
        assertNotNull(validateAvdName("_leading_underscore", emptyList()))
        assertNotNull(validateAvdName("-leading-dash", emptyList()))
        assertNotNull(validateAvdName("bad space", emptyList()))
        assertNotNull(validateAvdName("a".repeat(64), emptyList()))
        assertEquals(
            "an AVD with that name already exists",
            validateAvdName("pixel_9_api_35", listOf("Pixel_9_API_35")),
        )

        assertNull(validateAvdRam(512))
        assertNull(validateAvdRam(2048))
        assertNull(validateAvdRam(16384))
        assertNotNull(validateAvdRam(256))
        assertNotNull(validateAvdRam(32768))

        assertNull(validateAvdStorage(1024))
        assertNull(validateAvdStorage(6144))
        assertNull(validateAvdStorage(131072))
        assertNotNull(validateAvdStorage(512))
        assertNotNull(validateAvdStorage(262144))

        // SD card range: 0..32768 MB (src/shared/android-avd.ts:29)
        assertEquals(0, AvdLimits.SD_CARD_MIN_MB)
        assertEquals(32768, AvdLimits.SD_CARD_MAX_MB)
        assertEquals(512, AvdLimits.SD_CARD_DEFAULT_MB)
        assertNull(validateAvdSdCard(null))
        assertNull(validateAvdSdCard(0))
        assertNull(validateAvdSdCard(32))
        assertNull(validateAvdSdCard(512))
        assertNull(validateAvdSdCard(32768))
        assertNotNull(validateAvdSdCard(-1))
        assertNotNull(validateAvdSdCard(32769))
        assertNotNull(validateAvdSdCard(65536))
    }

    @Test
    fun `classifyCommittedText ignores active IME composition and emits committed text`() {
        assertEquals(
            AndroidTextInputKind.None,
            classifyCommittedText(previous = "", current = "Tie", hasComposition = true),
        )
        assertEquals(
            AndroidTextInputKind.Commit("Tiếng"),
            classifyCommittedText(previous = "", current = "Tiếng", hasComposition = false),
        )
        assertEquals(
            AndroidTextInputKind.Backspace(1),
            classifyCommittedText(previous = "abc", current = "ab", hasComposition = false),
        )
        assertEquals(
            AndroidTextInputKind.Enter,
            classifyCommittedText(previous = "abc", current = "abc\n", hasComposition = false),
        )
    }

    @Test
    fun `filenameFromDisposition parses RFC 5987 and quoted filenames safely`() {
        assertEquals(
            "Pixel_8-20261107.png",
            filenameFromDisposition("""attachment; filename="Pixel_8-20261107.png""""),
        )
        assertEquals(
            "Pixel 9.png",
            filenameFromDisposition("""attachment; filename*=UTF-8''Pixel%209.png"""),
        )
        assertEquals(
            "fallback.png",
            filenameFromDisposition(null, "fallback.png"),
        )
    }

    @Test
    fun `parsers decode exact server JSON payloads from routes android ts and android-session ts`() {
        // 1. GET /api/android/capabilities
        val caps = parseAndroidCapabilities(
            json.parseToJsonElement(
                """
                {
                  "enabled": true,
                  "authRequired": true,
                  "ready": false,
                  "avdCount": 1,
                  "encoders": ["h264_vaapi", "libx264"],
                  "accelerationOk": true,
                  "accelerationDetail": "KVM (version 12) is installed and usable.",
                  "canCreateAvd": true,
                  "sdk": {
                    "root": "/home/user/Android/Sdk",
                    "avdmanager": { "path": "/home/user/Android/Sdk/cmdline-tools/latest/bin/avdmanager", "version": "12.0" }
                  },
                  "requirements": [
                    { "id": "sdk", "label": "Android SDK", "met": true, "detail": "/home/user/Android/Sdk", "fix": null },
                    { "id": "avd", "label": "At least one AVD", "met": false, "detail": "none created yet", "fix": "avdmanager create avd" }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertTrue(caps.enabled)
        assertFalse(caps.ready)
        assertTrue(caps.canCreateAvd)
        assertEquals(listOf("h264_vaapi", "libx264"), caps.encoders)
        assertEquals(2, caps.requirements.size)
        assertEquals("avdmanager create avd", caps.requirements[1].fix)

        // 2. GET /api/android/devices
        val devices = parseAndroidDevices(
            json.parseToJsonElement(
                """
                {
                  "devices": [
                    {
                      "avdId": "Pixel_8_API_34",
                      "name": "Pixel 8 API 34",
                      "apiLevel": 34,
                      "abi": "x86_64",
                      "displayWidth": 1080,
                      "displayHeight": 2400,
                      "hardwareKeyboard": false,
                      "lockedByAnotherProcess": false,
                      "state": "ready",
                      "runtime": {
                        "deviceId": "emu-5554-gen1",
                        "generation": 2,
                        "adbSerial": "emulator-5554",
                        "ownedByPpm": true
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(1, devices.size)
        val dev = devices.single()
        assertEquals("Pixel_8_API_34", dev.avdId)
        assertEquals("Pixel 8 API 34", dev.name)
        assertEquals(AndroidDeviceStatus.READY, dev.state)
        assertFalse(dev.hardwareKeyboard)
        assertEquals("emu-5554-gen1", dev.runtime?.deviceId)
        assertEquals(2, dev.runtime?.generation)
        assertEquals("emulator-5554", dev.runtime?.adbSerial)
        assertEquals(true, dev.runtime?.ownedByPpm)

        // 3. GET /api/android/system-images
        val images = parseAndroidSystemImages(
            json.parseToJsonElement(
                """
                {
                  "images": [
                    {
                      "id": "system-images;android-35;google_apis;x86_64",
                      "apiLevel": 35,
                      "abi": "x86_64",
                      "tag": "google_apis",
                      "tagDisplay": "Google APIs",
                      "description": "Google APIs Intel x86_64 Atom System Image",
                      "playStore": false,
                      "bytes": 1610612736,
                      "hostCompatible": true
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(1, images.size)
        assertEquals("Google APIs", images[0].tagDisplay)
        assertTrue(images[0].hostCompatible)

        // 4. GET /api/android/projects/:project/apks
        val apks = parseProjectApks(
            json.parseToJsonElement(
                """
                {
                  "apks": [
                    {
                      "path": "app/build/outputs/apk/debug/app-debug.apk",
                      "bytes": 12582912,
                      "modifiedAt": 1700000000000
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(1, apks.size)
        assertEquals("app/build/outputs/apk/debug/app-debug.apk", apks[0].path)
        assertEquals("app-debug.apk", apks[0].fileName)
        assertEquals(12582912L, apks[0].bytes)

        // 5. POST /api/android/avds/:avdId/start and GET /api/android/operations/:id
        val startOp = parseAndroidOperation(
            json.parseToJsonElement("""{"operationId":"op-123","state":"running","detail":"Cold booting…"}"""),
        )
        assertNotNull(startOp)
        assertEquals("op-123", startOp!!.id)
        assertEquals("Cold booting…", startOp.detail)

        // 6. Server WS messages (`ready`, `codec`, `geometry`, `controller`, `log`, `error`)
        val ready = parseAndroidServerMessage(
            """
            {
              "type": "ready",
              "sessionId": "viewer-1",
              "sessionGeneration": 4,
              "geometry": { "width": 720, "height": 1600, "deviceWidth": 1080, "deviceHeight": 2400, "rotation": 90, "generation": 2 },
              "controller": true,
              "codec": "avc1.42E01F",
              "encoder": "libx264"
            }
            """.trimIndent(),
        ) as AndroidServerMessage.Ready
        assertEquals("viewer-1", ready.sessionId)
        assertEquals(4, ready.sessionGeneration)
        assertEquals(720, ready.geometry.width)
        assertEquals(1600, ready.geometry.height)
        assertEquals(90, ready.geometry.rotation)
        assertEquals(2, ready.geometry.generation)
        assertTrue(ready.controller)
        assertEquals("avc1.42E01F", ready.codec)
        assertEquals("libx264", ready.encoder)

        val logMsg = parseAndroidServerMessage(
            """
            {
              "type": "log",
              "entries": [
                { "id": 15, "timestamp": 1700000001234, "pid": 1234, "tid": 1250, "level": "warn", "tag": "ActivityTaskManager", "message": "Displayed MainActivity" }
              ]
            }
            """.trimIndent(),
        ) as AndroidServerMessage.Log
        assertEquals(1, logMsg.entries.size)
        assertEquals(15L, logMsg.entries[0].id)
        assertEquals(AndroidLogLevel.WARN, logMsg.entries[0].level)

        val errMsg = parseAndroidServerMessage(
            """{"type":"error","message":"emulator exited","fatal":true}""",
        ) as AndroidServerMessage.Error
        assertEquals("emulator exited", errMsg.message)
        assertTrue(errMsg.fatal)
    }

    @Test
    fun `client WS JSON builders match AndroidClientMessage in android-protocol ts`() {
        val auth = json.parseToJsonElement(buildAndroidAuthJson("nonce-xyz", AndroidQuality.HIGH)).jsonObject
        assertEquals("auth", auth["type"]?.jsonPrimitive?.content)
        assertEquals("nonce-xyz", auth["nonce"]?.jsonPrimitive?.content)
        assertEquals("high", auth["quality"]?.jsonPrimitive?.content)

        val touch = json.parseToJsonElement(
            buildAndroidTouchJson(3, listOf(AndroidTouchPoint(id = 0, x = 120, y = 340, pressure = 1))),
        ).jsonObject
        assertEquals("touch", touch["type"]?.jsonPrimitive?.content)
        assertEquals(3, touch["geometryGeneration"]?.jsonPrimitive?.int)
        val pt = touch["touches"]!!.jsonArray.single().jsonObject
        assertEquals(0, pt["id"]?.jsonPrimitive?.int)
        assertEquals(120, pt["x"]?.jsonPrimitive?.int)
        assertEquals(340, pt["y"]?.jsonPrimitive?.int)
        assertEquals(1, pt["pressure"]?.jsonPrimitive?.int)

        val key = json.parseToJsonElement(buildAndroidKeyJson(5, "Backspace", "press")).jsonObject
        assertEquals("key", key["type"]?.jsonPrimitive?.content)
        assertEquals(5, key["geometryGeneration"]?.jsonPrimitive?.int)
        assertEquals("Backspace", key["key"]?.jsonPrimitive?.content)
        assertEquals("press", key["action"]?.jsonPrimitive?.content)

        val hw = json.parseToJsonElement(buildAndroidHardwareKeyJson(AndroidHardwareKey.RECENT)).jsonObject
        assertEquals("hardware", hw["type"]?.jsonPrimitive?.content)
        assertEquals("recent", hw["key"]?.jsonPrimitive?.content)

        val qual = json.parseToJsonElement(buildAndroidQualityJson(AndroidQuality.LOW)).jsonObject
        assertEquals("quality", qual["type"]?.jsonPrimitive?.content)
        assertEquals("low", qual["quality"]?.jsonPrimitive?.content)

        val logcat = json.parseToJsonElement(buildAndroidLogcatJson(true)).jsonObject
        assertEquals("logcat", logcat["type"]?.jsonPrimitive?.content)
        assertEquals(true, logcat["subscribe"]?.jsonPrimitive?.boolean)

        val vis = json.parseToJsonElement(buildAndroidVisibilityJson(false)).jsonObject
        assertEquals("visibility", vis["type"]?.jsonPrimitive?.content)
        assertEquals(false, vis["visible"]?.jsonPrimitive?.boolean)
    }
}
