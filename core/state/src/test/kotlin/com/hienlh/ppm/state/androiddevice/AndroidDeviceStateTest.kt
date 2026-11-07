package com.hienlh.ppm.state.androiddevice

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AndroidDeviceStateTest {

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
    fun `containedRect and pointerToFrame reject letterbox taps and map inside rect`() {
        // 1000 x 1000 box holding a 500 x 1000 portrait frame -> scaled to 500 x 1000 centered at left = 250
        val rect = containedRect(boxWidth = 1000.0, boxHeight = 1000.0, frameWidth = 500, frameHeight = 1000)
        assertEquals(250.0, rect.left, 0.001)
        assertEquals(0.0, rect.top, 0.001)
        assertEquals(500.0, rect.width, 0.001)
        assertEquals(1000.0, rect.height, 0.001)

        // Tap on left pillarbox bar -> null
        assertNull(pointerToFrame(1000.0, 1000.0, 500, 1000, offsetX = 100.0, offsetY = 500.0))
        // Tap on right pillarbox bar -> null
        assertNull(pointerToFrame(1000.0, 1000.0, 500, 1000, offsetX = 800.0, offsetY = 500.0))

        // Tap center -> (250, 500)
        val center = pointerToFrame(1000.0, 1000.0, 500, 1000, offsetX = 500.0, offsetY = 500.0)
        assertNotNull(center)
        assertEquals(250.0, center!!.x, 0.001)
        assertEquals(500.0, center.y, 0.001)

        // Tap exact far edge -> clamped to (499, 999)
        val edge = pointerToFrame(1000.0, 1000.0, 500, 1000, offsetX = 750.0, offsetY = 1000.0)
        assertNotNull(edge)
        assertEquals(499.0, edge!!.x, 0.001)
        assertEquals(999.0, edge.y, 0.001)
    }

    @Test
    fun `frameToDevice unrotates across 0 90 180 and 270 degrees`() {
        val g0 = AndroidGeometry(1000, 2000, 1000, 2000, rotation = 0, generation = 1)
        assertEquals(AndroidIntPoint(250, 500), frameToDevice(AndroidPoint(250.0, 500.0), g0))

        // 90 deg: pu = 1 - v, pv = u
        val g90 = AndroidGeometry(2000, 1000, 1000, 2000, rotation = 90, generation = 2)
        val p90 = frameToDevice(AndroidPoint(500.0, 200.0), g90) // u = 0.25, v = 0.20 -> pu = 0.80, pv = 0.25
        assertTrue(abs(800 - p90.x) <= 2)
        assertTrue(abs(500 - p90.y) <= 2)

        // 180 deg: pu = 1 - u, pv = 1 - v
        val g180 = AndroidGeometry(1000, 2000, 1000, 2000, rotation = 180, generation = 3)
        val p180 = frameToDevice(AndroidPoint(250.0, 500.0), g180) // u = 0.25, v = 0.25 -> pu = 0.75, pv = 0.75
        assertTrue(abs(749 - p180.x) <= 2)
        assertTrue(abs(1499 - p180.y) <= 2)

        // 270 deg: pu = v, pv = 1 - u
        val g270 = AndroidGeometry(2000, 1000, 1000, 2000, rotation = 270, generation = 4)
        val p270 = frameToDevice(AndroidPoint(500.0, 200.0), g270) // u = 0.25, v = 0.20 -> pu = 0.20, pv = 0.75
        assertTrue(abs(200 - p270.x) <= 2)
        assertTrue(abs(1499 - p270.y) <= 2)

        assertEquals(90, nextAndroidRotation(0))
        assertEquals(180, nextAndroidRotation(90))
        assertEquals(270, nextAndroidRotation(180))
        assertEquals(0, nextAndroidRotation(270))
    }

    @Test
    fun `AndroidTouchTracker manages multi-touch slots and releasing pressure`() {
        val tracker = AndroidTouchTracker()
        val down0 = tracker.onDown(pointerId = 10, point = AndroidIntPoint(100, 200))
        assertNotNull(down0)
        assertEquals(listOf(AndroidTouchPoint(slot = 0, x = 100, y = 200, pressure = 1f)), down0)

        val down1 = tracker.onDown(pointerId = 20, point = AndroidIntPoint(300, 400))
        assertNotNull(down1)
        assertEquals(
            listOf(
                AndroidTouchPoint(slot = 0, x = 100, y = 200, pressure = 1f),
                AndroidTouchPoint(slot = 1, x = 300, y = 400, pressure = 1f),
            ),
            down1,
        )

        val moved = tracker.onMove(listOf(20 to AndroidIntPoint(350, 450)))
        assertNotNull(moved)
        assertEquals(
            listOf(
                AndroidTouchPoint(slot = 0, x = 100, y = 200, pressure = 1f),
                AndroidTouchPoint(slot = 1, x = 350, y = 450, pressure = 1f),
            ),
            moved,
        )

        // Lift first finger (pointerId = 10): slot 1 stays pressure=1f, slot 0 included with pressure=0f
        val up0 = tracker.onUpOrCancel(pointerId = 10, finalPoint = AndroidIntPoint(110, 210))
        assertNotNull(up0)
        assertEquals(
            listOf(
                AndroidTouchPoint(slot = 1, x = 350, y = 450, pressure = 1f),
                AndroidTouchPoint(slot = 0, x = 110, y = 210, pressure = 0f),
            ),
            up0,
        )
        assertEquals(1, tracker.activeCount)
        assertTrue(tracker.clear())
        assertEquals(0, tracker.activeCount)
    }

    @Test
    fun `classifyAndroidTextInput routes ASCII to text and Unicode to paste`() {
        assertEquals(AndroidTextInputKind.ASCII_TEXT, classifyAndroidTextInput("hello 123!"))
        assertEquals(AndroidTextInputKind.UNICODE_PASTE, classifyAndroidTextInput("Xin chào"))
        assertEquals(AndroidTextInputKind.UNICODE_PASTE, classifyAndroidTextInput("line1\nline2"))
    }

    @Test
    fun `logcat filter and bounded ring buffer work as expected`() {
        val e1 = AndroidLogEntry(1, 1_000L, AndroidLogLevel.D, 100, 101, "ActivityManager", "Start proc")
        val e2 = AndroidLogEntry(2, 2_000L, AndroidLogLevel.E, 200, 201, "AndroidRuntime", "FATAL EXCEPTION")
        val filter = AndroidLogFilter(minLevel = AndroidLogLevel.W, tag = "runtime", pid = "200", search = "fatal")
        assertFalse(matchesAndroidLogFilter(e1, filter))
        assertTrue(matchesAndroidLogFilter(e2, filter))

        val res = appendBoundedLogs(listOf(e1, e2), listOf(e1, e2), limit = 3)
        assertEquals(3, res.entries.size)
        assertEquals(1, res.dropped)
        assertEquals(2L, res.entries.first().seq)
    }

    @Test
    fun `validateAvdName filenameFromDisposition and server message parsing`() {
        assertNull(validateAvdName("Pixel_8_API_34.test-1"))
        assertNotNull(validateAvdName(""))
        assertNotNull(validateAvdName("has space"))

        assertEquals(
            "pixel-2026.png",
            filenameFromDisposition("""attachment; filename="pixel-2026.png"""", "fallback"),
        )
        assertEquals(
            "my-avd-screenshot.png",
            filenameFromDisposition(null, "my avd!"),
        )

        val readyMsg = parseAndroidServerMessage(
            """{"type":"ready","sessionId":"s1","deviceId":"Pixel_7","generation":2,"controller":true,"quality":"high","geometry":{"frameWidth":1080,"frameHeight":2400,"deviceWidth":1080,"deviceHeight":2400,"rotation":90,"generation":4},"hardwareKeyboard":true}""",
        ) as? AndroidServerMessage.Ready
        assertNotNull(readyMsg)
        assertEquals("Pixel_7", readyMsg!!.deviceId)
        assertTrue(readyMsg.controller)
        assertEquals(AndroidQuality.HIGH, readyMsg.quality)
        assertEquals(90, readyMsg.geometry.rotation)

        val caps = parseAndroidCapabilities(
            Json.parseToJsonElement(
                """{"enabled":true,"ready":false,"avdCount":2,"accelerationOk":true,"accelerationDetail":"KVM","requirements":[{"id":"emulator","label":"Android Emulator","met":false,"detail":"missing","fix":"sdkmanager emulator"}],"encoders":["h264_nvenc"],"authRequired":false,"canCreateAvd":true}""",
            ),
        )
        assertTrue(caps.enabled)
        assertFalse(caps.ready)
        assertEquals(1, caps.requirements.size)
        assertEquals("sdkmanager emulator", caps.requirements.first().fix)
    }
}
