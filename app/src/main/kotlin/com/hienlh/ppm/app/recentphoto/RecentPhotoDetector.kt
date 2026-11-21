package com.hienlh.ppm.app.recentphoto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One row of `MediaStore.Images`, as much of it as the detector reads. */
data class MediaImage(
    val id: Long,
    /** `DATE_ADDED`, in seconds as MediaStore keeps it. */
    val dateAddedSeconds: Long,
    val size: Long,
    /** `IS_PENDING` (Android 10+): the file is still being written. */
    val pending: Boolean = false,
    /** `OWNER_PACKAGE_NAME` (Android 10+); null when unknown. */
    val ownerPackage: String? = null,
    /** `RELATIVE_PATH` (Android 10+) or the folder of `DATA` before it; null when unknown. */
    val path: String? = null,
)

/** How much of the photo library the app may read. */
enum class PhotoAccess { NONE, PARTIAL, FULL }

/** What the chip offers. */
sealed interface PhotoOffer {
    /** When it was offered, by the detector's clock: the chip hides [RecentPhotoDetector.SHOW_MS] later. */
    val offeredAt: Long

    /** A picture the app can read, offered with its thumbnail. */
    data class Image(val id: Long, override val offeredAt: Long) : PhotoOffer

    /**
     * A capture the app cannot read yet: offered without a thumbnail, and a tap asks for photo
     * access first. [screenshot] when Android said a screenshot of the app was taken.
     */
    data class Unread(val screenshot: Boolean, override val offeredAt: Long) : PhotoOffer
}

/**
 * Decides which newly taken picture the chat offers (the "Add to chat" chip): the newest
 * screenshot or camera photo added in the last [WINDOW_MS], once it is fully written, never one
 * the app wrote itself and never the same one twice. Nothing here touches Android: [query]
 * answers the images added since a time (in seconds), [now] is the clock in milliseconds and
 * [access] how much of the library may be read, so the rules run on the JVM.
 *
 * Every method may be called from any thread.
 */
class RecentPhotoDetector(
    private val query: (sinceSeconds: Long) -> List<MediaImage>,
    private val now: () -> Long,
    private val ownPackage: String,
    private val access: () -> PhotoAccess,
    /** The access level the user last turned down, kept across runs; null when never. */
    declinedAt: PhotoAccess? = null,
    private val onDeclinedChange: (PhotoAccess?) -> Unit = {},
) {
    enum class Scan {
        /** Nothing new to offer. */
        NONE,

        /** [offer] now holds the newest picture. */
        OFFERED,

        /** The newest picture is still being written: scan again shortly. */
        WAIT,
    }

    private val current = MutableStateFlow<PhotoOffer?>(null)
    val offer: StateFlow<PhotoOffer?> = current.asStateFlow()

    /** Pictures already offered (and so dismissed, used or expired), oldest first. */
    private val seen = LinkedHashSet<Long>()

    /** The newest picture at the last scan, and its size then. */
    private var last: Pair<Long, Long>? = null

    private var declined: PhotoAccess? = declinedAt

    /** The user turned photo access down: no chip asks for it again until access grows. */
    val isDeclined: Boolean @Synchronized get() = declined != null

    /** The milliseconds a chip stays before it hides by itself. */
    fun remainingMs(offer: PhotoOffer): Long = (offer.offeredAt + SHOW_MS - now()).coerceAtLeast(0)

    /**
     * Looks for a new picture: on a media change and once each time the app comes back. Only the
     * newest one counts — an older one is never offered in its place — and it is offered once it
     * is no longer pending and its size stayed the same across two scans (or it was added
     * [SETTLED_AFTER_MS] ago).
     */
    @Synchronized
    fun scan(): Scan {
        if (access() == PhotoAccess.NONE) return Scan.NONE
        val newest = candidates().firstOrNull() ?: return Scan.NONE
        if (newest.id in seen) return Scan.NONE
        val before = last
        last = newest.id to newest.size
        // Settled: the same size as at the last scan, or added long enough ago (what the resume check finds).
        val settled = before == last || now() - newest.dateAddedSeconds * 1000 >= SETTLED_AFTER_MS
        if (newest.pending || newest.size <= 0 || !settled) return Scan.WAIT
        last = null
        remember(newest.id)
        current.value = PhotoOffer.Image(newest.id, now())
        return Scan.OFFERED
    }

    /**
     * Android said the user took a screenshot of the app (Android 14+). With full access the
     * media change brings it in; without, the chip offers it unseen, unless access was turned down.
     */
    @Synchronized
    fun screenshotTaken() {
        if (access() == PhotoAccess.FULL || declined != null) return
        if (current.value is PhotoOffer.Image) return
        current.value = PhotoOffer.Unread(screenshot = true, offeredAt = now())
    }

    /**
     * A picture [id] was added that the app cannot read (no access at all): offered unseen, once,
     * unless access was turned down.
     */
    @Synchronized
    fun unreadImageAdded(id: Long) {
        if (access() != PhotoAccess.NONE || declined != null || id in seen) return
        remember(id)
        current.value = PhotoOffer.Unread(screenshot = false, offeredAt = now())
    }

    /**
     * The access dialog a tap on an unread offer opened has been answered: the newest finished
     * picture to attach, or null. No access, or limited access that still does not cover a new
     * picture, counts as turning it down.
     */
    @Synchronized
    fun accessAnswered(): Long? {
        if (current.value is PhotoOffer.Unread) current.value = null
        val level = access()
        val picture = if (level == PhotoAccess.NONE) null else candidates().firstOrNull { !it.pending && it.size > 0 && it.id !in seen }
        if (picture == null) {
            if (level != PhotoAccess.FULL) decline(level)
            return null
        }
        remember(picture.id)
        return picture.id
    }

    /** Access may have changed in Settings: more than was turned down lifts the refusal. */
    @Synchronized
    fun accessChecked() {
        val refused = declined ?: return
        if (access() > refused) {
            declined = null
            onDeclinedChange(null)
        }
    }

    /** The chip was closed with ×, tapped, or hid by itself: its picture is not offered again. */
    @Synchronized
    fun close(offer: PhotoOffer) {
        if (current.value == offer) current.value = null
    }

    private fun decline(level: PhotoAccess) {
        declined = level
        onDeclinedChange(level)
    }

    /** Captures in the window that the app did not write, newest first. */
    private fun candidates(): List<MediaImage> {
        val since = Math.floorDiv(now() - WINDOW_MS, 1000L)
        return query(since)
            .filter { it.dateAddedSeconds >= since && it.ownerPackage != ownPackage && isCapture(it.path) }
            .sortedWith(compareByDescending<MediaImage> { it.dateAddedSeconds }.thenByDescending { it.id })
    }

    private fun remember(id: Long) {
        seen += id
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
    }

    companion object {
        /** How far back a picture still counts as just taken. */
        const val WINDOW_MS = 2 * 60_000L

        /** How long the chip stays. */
        const val SHOW_MS = 60_000L

        /** A picture added this long ago is fully written, whatever the last scan saw. */
        const val SETTLED_AFTER_MS = 10_000L

        private const val MAX_SEEN = 500

        /**
         * A screenshot or a camera photo, by its folder (`Pictures/Screenshots/`, `DCIM/Camera/`,
         * `DCIM/Screenshots/`): not a download or a messenger's picture. Unknown counts as one.
         */
        fun isCapture(path: String?): Boolean {
            if (path == null) return true
            val folder = path.lowercase()
            return "screenshot" in folder || folder.startsWith("dcim/") || "/dcim/" in folder
        }
    }
}
