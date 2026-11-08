package com.hienlh.ppm.state.remotedesktop

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDesktopStateTest {

    @Test
    fun `Quality ladder and RustDesk custom bitrate math match web`() {
        assertEquals("balanced", parseQualityChoice("tiny"))
        assertEquals("custom", parseQualityChoice("custom"))
        assertEquals("balanced", nextQualityChoice("best"))
        assertEquals("low", nextQualityChoice("balanced"))
        assertEquals("best", nextQualityChoice("low"))
        assertEquals("best", nextQualityChoice("custom"))

        // 1920x1080 base bitrate is 2073 kbps; 50% custom = ratio 1.0 -> 2073 kbps
        assertEquals(2073, baseBitrateKbps(1920, 1080))
        assertEquals(2073, customBitrateKbps(50, 1920, 1080))
        // 100% custom = ratio 2.0 -> 4146 kbps
        assertEquals(4146, customBitrateKbps(100, 1920, 1080))
        // 0x0 capture floors at >= 1 kbps
        assertTrue(baseBitrateKbps(0, 0) >= 1)
    }

    @Test
    fun `Piecewise custom scale slider pivots at 100 percent one third along track`() {
        assertEquals(5, scalePosToPercent(0.0))
        assertEquals(100, scalePosToPercent(CUSTOM_SCALE_PIVOT_POS))
        assertEquals(1000, scalePosToPercent(1.0))

        assertEquals(CUSTOM_SCALE_PIVOT_POS, scalePercentToPos(100), 1e-6)
        assertEquals(CUSTOM_SCALE_PIVOT_POS, snapScalePos(CUSTOM_SCALE_PIVOT_POS + 0.003), 1e-6)

        assertEquals(1.01, stepCustomScale(1.0, 1), 1e-6)
        assertEquals(0.99, stepCustomScale(1.0, -1), 1e-6)

        assertNull(canvasCssSize("adaptive", 1.5, RdSize(1920, 1080)))
        assertEquals(RdSize(1920, 1080), canvasCssSize("original", 1.5, RdSize(1920, 1080)))
        assertEquals(RdSize(2880, 1620), canvasCssSize("custom", 1.5, RdSize(1920, 1080)))
    }

    @Test
    fun `Letterboxed content rect and zoomed point reverse mapping match web`() {
        // 16:9 content inside 1000x1000 square box -> width-constrained 1000x562.5 centered vertically
        val box = RdRect(left = 0.0, top = 0.0, width = 1000.0, height = 1000.0)
        val letterbox = letterboxedContentRect(box, 1600, 900)
        assertEquals(0.0, letterbox.left, 1e-3)
        assertEquals(218.75, letterbox.top, 1e-3)
        assertEquals(1000.0, letterbox.width, 1e-3)
        assertEquals(562.5, letterbox.height, 1e-3)

        // Center point (500, 500) under 2x zoom with +100 panX maps back to 0.45 xFrac, 0.5 yFrac
        val frac = fractionFromZoomedPoint(
            clientX = 500.0,
            clientY = 500.0,
            rect = letterbox,
            transform = ZoomPanTransform(scale = 2.0, panX = 100.0, panY = 0.0),
        )
        assertEquals(0.45, frac.xFrac, 1e-3)
        assertEquals(0.50, frac.yFrac, 1e-3)
    }

    @Test
    fun `Touch and mouse gesture trackers emit expected pointer and wheel messages`() {
        val rect = RdRect(0.0, 0.0, 1000.0, 500.0)

        // 1. Mouse mode: relative drag moves virtual cursor, then quick tap clicks at cursor
        val mouseTrack = beginSingleTouch(100.0, 100.0, rect, nowMs = 1000L)
        val moveRes = advanceSingleTouch(
            track = mouseTrack,
            x = 200.0,
            y = 150.0,
            mode = "mouse",
            transform = IDENTITY_TRANSFORM,
            cursor = RdFraction(0.5, 0.5),
        )
        assertTrue(moveRes.sendCursorMove)
        assertEquals(0.6, moveRes.cursor!!.xFrac, 1e-3)
        assertEquals(0.6, moveRes.cursor!!.yFrac, 1e-3)

        val tapTrack = beginSingleTouch(200.0, 150.0, rect, nowMs = 2000L)
        val tapMsgs = resolveSingleTouchEnd(
            track = tapTrack,
            x = 202.0,
            y = 151.0,
            mode = "mouse",
            transform = IDENTITY_TRANSFORM,
            cursor = moveRes.cursor!!,
            nowMs = 2100L,
        )
        assertEquals(2, tapMsgs.size)
        assertEquals("left", tapMsgs[0]["button"].string)
        assertEquals(true, tapMsgs[0]["down"].bool)
        assertEquals(false, tapMsgs[1]["down"].bool)
        assertEquals(0.6, tapMsgs[0]["xFrac"].double!!, 1e-3)

        // 2. Two-finger tap -> right click; two-finger spread -> pinch zoom
        val twoTap = beginTwoFingerTouch(RdPoint(400.0, 250.0), RdPoint(600.0, 250.0), 1.0, nowMs = 3000L)
        val rightClick = resolveTwoFingerEnd(twoTap, rect, IDENTITY_TRANSFORM, nowMs = 3120L)
        assertEquals(2, rightClick.size)
        assertEquals("right", rightClick[0]["button"].string)

        val twoPinch = beginTwoFingerTouch(RdPoint(400.0, 250.0), RdPoint(600.0, 250.0), 1.0, nowMs = 4000L)
        val pinchRes = advanceTwoFingerTouch(twoPinch, RdPoint(300.0, 250.0), RdPoint(700.0, 250.0), IDENTITY_TRANSFORM)
        assertNotNull(pinchRes.transform)
        assertEquals(2.0, pinchRes.transform!!.scale, 1e-3)
    }

    @Test
    fun `Capabilities normalization resolution collapse key mapping and CSD builders work`() {
        val raw = WireJson.parseOrNull(
            """
            {
              "videoAvailable": true,
              "inputAvailable": false,
              "encoders": ["h264_nvenc", "libx264"],
              "resolutions": {
                "output": "DP-1",
                "modes": [
                  {"id": "0x1", "width": 1920, "height": 1080, "refresh": 60, "current": true, "preferred": false},
                  {"id": "0x2", "width": 1920, "height": 1080, "refresh": 144, "current": false, "preferred": true},
                  {"id": "0x3", "width": 2560, "height": 1440, "refresh": 60, "current": false, "preferred": false}
                ]
              }
            }
            """.trimIndent(),
        )
        val caps = RemoteDesktopCapabilities.from(raw)
        assertTrue(caps.videoReady)
        assertEquals(false, caps.inputReady)
        assertEquals("NVIDIA NVENC", codecLabel(caps.encoders.first()))
        assertTrue(isHardwareCodec("h264_nvenc"))
        assertEquals(false, isHardwareCodec("libx264"))

        // Resolution collapse keeps 2 distinct sizes, largest first, and `current` wins within 1920x1080
        val choices = resolutionChoices(caps.resolutions.modes)
        assertEquals(2, choices.size)
        assertEquals("0x3", choices[0].id)
        assertEquals("0x1", choices[1].id)

        // fitLocalMode returns null when already on matching size, or exact match for 2560x1440
        assertNull(fitLocalMode(caps.resolutions.modes, RdSize(1920, 1080), "0x1"))
        assertEquals("0x3", fitLocalMode(caps.resolutions.modes, RdSize(2560, 1440), "0x1")?.id)

        // Android keycode mapping
        assertEquals("KeyA", androidKeyCodeToW3cCode(29))
        assertEquals("ShiftLeft", androidKeyCodeToW3cCode(59))
        assertEquals("Enter", androidKeyCodeToW3cCode(66))
        assertTrue(isCopyChord(listOf("ControlLeft", "KeyC")))

        // Annex-B SPS/PPS extraction
        val annexB = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F, // SPS (type 7)
            0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, (0x80).toByte(), // PPS (type 8)
            0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), // IDR slice (type 5)
        )
        val spsPps = extractAnnexBSpsPps(annexB)
        assertNotNull(spsPps)
        assertEquals(8, spsPps!!.sps.size)
        assertEquals(8, spsPps.pps.size)

        // OpusHead CSD is 19 bytes starting with "OpusHead"
        val opusCsd = buildOpusHeadCsd(channels = 2, sampleRate = 48_000)
        assertEquals(19, opusCsd.size)
        assertEquals("OpusHead", String(opusCsd, 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun `computeStageVideoRect and BoundedVideoFrameQueue enforce letterbox sizing and keyframe resync on dropped delta`() {
        // B7: computeStageVideoRect in adaptive vs original vs custom scale modes
        val adaptiveRect = computeStageVideoRect(
            containerWidthPx = 1000.0,
            containerHeightPx = 1000.0,
            frameSize = RdSize(1920, 1080),
            viewStyle = "adaptive",
        )
        assertEquals(0.0, adaptiveRect.left, 1e-3)
        assertEquals(218.75, adaptiveRect.top, 1e-3)
        assertEquals(1000.0, adaptiveRect.width, 1e-3)
        assertEquals(562.5, adaptiveRect.height, 1e-3)

        val customRect = computeStageVideoRect(
            containerWidthPx = 1000.0,
            containerHeightPx = 1000.0,
            frameSize = RdSize(1920, 1080),
            viewStyle = "custom",
            customScale = 0.5,
            densityScale = 1.0,
        )
        assertEquals(20.0, customRect.left, 1e-3)
        assertEquals(230.0, customRect.top, 1e-3)
        assertEquals(960.0, customRect.width, 1e-3)
        assertEquals(540.0, customRect.height, 1e-3)

        // B8: BoundedVideoFrameQueue drops deltas before keyframe, and dropping a delta on overflow sets waitingForKeyframe = true
        val queue = BoundedVideoFrameQueue(capacity = 2)
        assertTrue(queue.waitingForKeyframe)
        assertEquals(false, queue.offer(byteArrayOf(1), isKeyframe = false))
        assertEquals(1, queue.droppedDeltas)

        // First keyframe + 1 delta fills capacity = 2
        assertTrue(queue.offer(byteArrayOf(10), isKeyframe = true))
        assertEquals(false, queue.waitingForKeyframe)
        assertTrue(queue.offer(byteArrayOf(11), isKeyframe = false))
        assertEquals(2, queue.size)

        // Offering another delta overflows capacity -> dropped AND waitingForKeyframe becomes true
        assertEquals(false, queue.offer(byteArrayOf(12), isKeyframe = false))
        assertTrue(queue.waitingForKeyframe)
        assertEquals(2, queue.droppedDeltas)

        // Even after polling 1 frame so queue has space, subsequent deltas are still rejected until next keyframe
        assertNotNull(queue.poll())
        assertEquals(1, queue.size)
        assertEquals(false, queue.offer(byteArrayOf(13), isKeyframe = false))
        assertTrue(queue.waitingForKeyframe)

        // Next keyframe resets waitingForKeyframe and is accepted
        assertTrue(queue.offer(byteArrayOf(20), isKeyframe = true))
        assertEquals(false, queue.waitingForKeyframe)
    }

    @Test
    fun `BoundedVideoFrameQueue offer keyframe clears everything queued before it`() {
        val queue = BoundedVideoFrameQueue(capacity = 8)
        // [K1, d x 7] fills capacity = 8
        assertTrue(queue.offer(byteArrayOf(0x01), isKeyframe = true))
        for (i in 1..7) {
            assertTrue(queue.offer(byteArrayOf((0x10 + i).toByte()), isKeyframe = false))
        }
        assertEquals(8, queue.size)

        // Offering K2 must clear [K1, d x 7] so only [K2] remains
        assertTrue(queue.offer(byteArrayOf(0x02), isKeyframe = true))
        assertEquals(1, queue.size)
        assertEquals(7, queue.droppedDeltas)
        val polled = queue.poll()
        assertNotNull(polled)
        assertTrue(polled!!.isKeyframe)
        assertEquals(0x02.toByte(), polled.bytes[0])
        assertNull(queue.poll())
    }
}
