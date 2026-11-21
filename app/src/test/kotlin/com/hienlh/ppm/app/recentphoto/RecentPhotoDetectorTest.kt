package com.hienlh.ppm.app.recentphoto

import com.hienlh.ppm.app.recentphoto.RecentPhotoDetector.Scan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules behind the "Add to chat" chip, over a fake media store and clock. */
class RecentPhotoDetectorTest {
    private var now = 1_800_000_000_000L
    private var access = PhotoAccess.FULL
    private val images = mutableListOf<MediaImage>()
    private val queriedSince = mutableListOf<Long>()
    private val declines = mutableListOf<PhotoAccess?>()

    private fun detector(declinedAt: PhotoAccess? = null) = RecentPhotoDetector(
        query = { since ->
            queriedSince += since
            images.toList()
        },
        now = { now },
        ownPackage = "com.hienlh.ppm",
        access = { access },
        declinedAt = declinedAt,
        onDeclinedChange = { declines += it },
    )

    /** A finished screenshot added [secondsAgo] seconds ago. */
    private fun shot(id: Long, secondsAgo: Long, size: Long = 1000, pending: Boolean = false, owner: String? = "com.android.systemui", path: String? = "Pictures/Screenshots/") =
        MediaImage(id, (now / 1000) - secondsAgo, size, pending, owner, path).also { images += it }

    private fun RecentPhotoDetector.offeredId(): Long? = (offer.value as? PhotoOffer.Image)?.id

    @Test
    fun `the newest picture in the window is offered once its size holds across two scans`() {
        val d = detector()
        shot(1, secondsAgo = 5)
        shot(2, secondsAgo = 2)
        assertEquals("a picture seconds old may still be written", Scan.WAIT, d.scan())
        assertNull(d.offer.value)
        assertEquals(Scan.OFFERED, d.scan())
        assertEquals(2L, d.offeredId())
        assertEquals("asks for the last two minutes", (now - 120_000) / 1000, queriedSince.last())
    }

    @Test
    fun `a picture still growing is waited for`() {
        val d = detector()
        images += MediaImage(1, now / 1000, 100, path = "DCIM/Camera/")
        assertEquals(Scan.WAIT, d.scan())
        images[0] = images[0].copy(size = 5000)
        assertEquals(Scan.WAIT, d.scan())
        assertEquals(Scan.OFFERED, d.scan())
        assertEquals(1L, d.offeredId())
    }

    @Test
    fun `only the last two minutes count`() {
        val d = detector()
        shot(1, secondsAgo = 121)
        assertEquals(Scan.NONE, d.scan())
        shot(2, secondsAgo = 119)
        assertEquals("added over ten seconds ago: written already", Scan.OFFERED, d.scan())
        assertEquals(2L, d.offeredId())
    }

    @Test
    fun `the same picture is never offered twice, whether dismissed, used or expired`() {
        val d = detector()
        shot(1, secondsAgo = 30)
        assertEquals(Scan.OFFERED, d.scan())
        d.close(d.offer.value!!)
        assertNull(d.offer.value)
        assertEquals(Scan.NONE, d.scan())
        assertEquals(Scan.NONE, d.scan())

        shot(2, secondsAgo = 20)
        assertEquals(Scan.OFFERED, d.scan())
        assertEquals(2L, d.offeredId())
    }

    @Test
    fun `an older picture never takes the place of a newest one already offered`() {
        val d = detector()
        shot(1, secondsAgo = 60)
        shot(2, secondsAgo = 30)
        assertEquals(Scan.OFFERED, d.scan())
        d.close(d.offer.value!!)
        assertEquals("picture 1 is in the window but not the newest", Scan.NONE, d.scan())
        assertNull(d.offer.value)
    }

    @Test
    fun `a newer picture replaces the one on offer`() {
        val d = detector()
        shot(1, secondsAgo = 60)
        d.scan()
        assertEquals(1L, d.offeredId())
        shot(2, secondsAgo = 15)
        assertEquals(Scan.OFFERED, d.scan())
        assertEquals(2L, d.offeredId())
    }

    @Test
    fun `a pending picture is waited for and nothing older is offered meanwhile`() {
        val d = detector()
        shot(1, secondsAgo = 60)
        shot(2, secondsAgo = 30, pending = true)
        assertEquals(Scan.WAIT, d.scan())
        assertEquals(Scan.WAIT, d.scan())
        assertNull(d.offer.value)
        images[1] = images[1].copy(pending = false)
        assertEquals(Scan.OFFERED, d.scan())
        assertEquals(2L, d.offeredId())
    }

    @Test
    fun `an empty file is waited for`() {
        val d = detector()
        shot(1, secondsAgo = 30, size = 0)
        assertEquals(Scan.WAIT, d.scan())
        assertEquals(Scan.WAIT, d.scan())
    }

    @Test
    fun `pictures the app wrote and pictures that are no capture are skipped`() {
        val d = detector()
        shot(1, secondsAgo = 50)
        shot(2, secondsAgo = 40, owner = "com.hienlh.ppm", path = "Pictures/Screenshots/")
        shot(3, secondsAgo = 30, path = "Download/")
        shot(4, secondsAgo = 20, path = "Pictures/WhatsApp/")
        assertEquals(Scan.OFFERED, d.scan())
        assertEquals(1L, d.offeredId())
    }

    @Test
    fun `captures are told by their folder`() {
        assertTrue(RecentPhotoDetector.isCapture("Pictures/Screenshots/"))
        assertTrue(RecentPhotoDetector.isCapture("DCIM/Screenshots/"))
        assertTrue(RecentPhotoDetector.isCapture("DCIM/Camera/"))
        assertTrue("before Android 10, the file's folder", RecentPhotoDetector.isCapture("/storage/emulated/0/DCIM/Camera/"))
        assertTrue("unknown counts", RecentPhotoDetector.isCapture(null))
        assertFalse(RecentPhotoDetector.isCapture("Download/"))
        assertFalse(RecentPhotoDetector.isCapture("Pictures/Telegram/"))
    }

    @Test
    fun `the resume check offers what was taken while away, in one scan`() {
        val d = detector()
        // Taken 90 s ago in another app; the app comes back now.
        shot(7, secondsAgo = 90)
        assertEquals(Scan.OFFERED, d.scan())
        assertEquals(7L, d.offeredId())
        // Coming back again finds it already offered.
        d.close(d.offer.value!!)
        assertEquals(Scan.NONE, d.scan())
    }

    @Test
    fun `the resume check finds nothing taken over two minutes ago`() {
        val d = detector()
        shot(7, secondsAgo = 150)
        assertEquals(Scan.NONE, d.scan())
        assertNull(d.offer.value)
    }

    @Test
    fun `the chip has a minute`() {
        val d = detector()
        shot(1, secondsAgo = 30)
        d.scan()
        val offer = d.offer.value!!
        assertEquals(60_000L, d.remainingMs(offer))
        now += 45_000
        assertEquals(15_000L, d.remainingMs(offer))
        now += 30_000
        assertEquals(0L, d.remainingMs(offer))
    }

    @Test
    fun `without access nothing is read, and a screenshot is offered unseen`() {
        access = PhotoAccess.NONE
        val d = detector()
        shot(1, secondsAgo = 30)
        assertEquals(Scan.NONE, d.scan())
        assertTrue("the store is not even asked", queriedSince.isEmpty())
        d.screenshotTaken()
        assertEquals(PhotoOffer.Unread(screenshot = true, offeredAt = now), d.offer.value)
    }

    @Test
    fun `with full access a screenshot waits for the media store`() {
        val d = detector()
        d.screenshotTaken()
        assertNull(d.offer.value)
    }

    @Test
    fun `granting access attaches the newest finished picture, and it is not offered again`() {
        access = PhotoAccess.NONE
        val d = detector()
        d.screenshotTaken()
        shot(1, secondsAgo = 50)
        shot(2, secondsAgo = 3)
        access = PhotoAccess.FULL
        assertEquals(2L, d.accessAnswered())
        assertNull(d.offer.value)
        assertFalse(d.isDeclined)
        assertEquals(Scan.NONE, d.scan())
    }

    @Test
    fun `a denial stops the offers until access is granted`() {
        access = PhotoAccess.NONE
        val d = detector()
        d.screenshotTaken()
        assertNull("still no access", d.accessAnswered())
        assertNull(d.offer.value)
        assertTrue(d.isDeclined)
        assertEquals(listOf<PhotoAccess?>(PhotoAccess.NONE), declines)

        d.screenshotTaken()
        d.unreadImageAdded(5)
        assertNull("no nagging", d.offer.value)
        d.accessChecked()
        assertTrue(d.isDeclined)

        // Granted later in Settings.
        access = PhotoAccess.FULL
        d.accessChecked()
        assertFalse(d.isDeclined)
        assertEquals(null, declines.last())
    }

    @Test
    fun `a denial is remembered across runs`() {
        access = PhotoAccess.NONE
        val d = detector(declinedAt = PhotoAccess.NONE)
        d.screenshotTaken()
        assertNull(d.offer.value)
    }

    @Test
    fun `partial access offers the pictures it covers`() {
        access = PhotoAccess.PARTIAL
        val d = detector()
        shot(3, secondsAgo = 30)
        assertEquals(Scan.OFFERED, d.scan())
        assertEquals(3L, d.offeredId())
    }

    @Test
    fun `partial access still offers a screenshot it does not cover, and a selection without it counts as no`() {
        access = PhotoAccess.PARTIAL
        val d = detector()
        d.screenshotTaken()
        assertTrue(d.offer.value is PhotoOffer.Unread)
        assertNull("the selection still does not hold it", d.accessAnswered())
        assertTrue(d.isDeclined)
        d.accessChecked()
        assertTrue("still partial", d.isDeclined)
        access = PhotoAccess.FULL
        d.accessChecked()
        assertFalse(d.isDeclined)
    }

    @Test
    fun `an unreadable new picture is offered unseen once`() {
        access = PhotoAccess.NONE
        val d = detector()
        d.unreadImageAdded(9)
        val offer = d.offer.value
        assertEquals(PhotoOffer.Unread(screenshot = false, offeredAt = now), offer)
        d.close(offer!!)
        d.unreadImageAdded(9)
        assertNull(d.offer.value)
    }

    @Test
    fun `closing an offer that was replaced leaves the new one`() {
        val d = detector()
        shot(1, secondsAgo = 60)
        d.scan()
        val first = d.offer.value!!
        shot(2, secondsAgo = 20)
        d.scan()
        d.close(first)
        assertEquals(2L, d.offeredId())
    }
}
