package com.hienlh.ppm.app.recentphoto

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.hienlh.ppm.app.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Add to chat" chip's source: [detector]'s rules over this device's media store, watched
 * while an activity of the app is in the foreground. [thumbnails] and [uriOf] turn a picture's id
 * into what the chip shows and what the paperclip's attach takes. Tests build one over a fake
 * detector and never call [start].
 */
class RecentPhotos(
    val detector: RecentPhotoDetector,
    private val thumbnails: suspend (Long) -> ImageBitmap?,
    val uriOf: (Long) -> Uri,
    /** What the access dialog asks for on this Android version. */
    val accessPermissions: Array<String> = photoPermissions(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
) {
    /**
     * The chats on screen, in the order they appeared: the chip shows in one only (see [showsIn]).
     * Read by composition, written from the main thread.
     */
    private val hosts = mutableStateListOf<String>()

    private var checking: Job? = null
    private var started = 0
    private var observer: ContentObserver? = null
    private val screenCallbacks = HashMap<Activity, Any>()

    fun hostShown(tabId: String) {
        hosts.remove(tabId)
        hosts += tabId
    }

    fun hostGone(tabId: String) {
        hosts.remove(tabId)
    }

    /** The chat in front when it is on screen, else the chat that appeared last. */
    fun showsIn(tabId: String, activeTabId: String?): Boolean {
        if (tabId !in hosts) return false
        return if (activeTabId != null && activeTabId in hosts) activeTabId == tabId else hosts.last() == tabId
    }

    suspend fun thumbnail(id: Long): ImageBitmap? = withContext(Dispatchers.IO) { runCatching { thumbnails(id) }.getOrNull() }

    /** Scans now and again while the newest picture is still being written. */
    fun check() {
        checking?.cancel()
        checking = scope.launch {
            repeat(MAX_WAITS) {
                if (detector.scan() != RecentPhotoDetector.Scan.WAIT) return@launch
                delay(WAIT_MS)
            }
        }
    }

    /** The access dialog was answered: the picture to attach, if there is one now. */
    suspend fun accessAnswered(): Uri? = withContext(Dispatchers.IO) { detector.accessAnswered()?.let(uriOf) }

    /** Photo access may have changed (the launch-time request was answered): look again with it. */
    fun accessMayHaveChanged() {
        detector.accessChecked()
        check()
    }

    /** An activity came to the foreground: watch the media store, and look once for what was taken meanwhile. */
    fun start(activity: Activity) {
        if (started++ == 0) {
            val watcher = MediaWatcher(activity.applicationContext)
            runCatching { activity.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, watcher) }
                .onSuccess { observer = watcher }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = Activity.ScreenCaptureCallback { detector.screenshotTaken() }
            runCatching { activity.registerScreenCaptureCallback(activity.mainExecutor, callback) }.onSuccess { screenCallbacks[activity] = callback }
        }
        detector.accessChecked()
        check()
    }

    fun stop(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            (screenCallbacks.remove(activity) as? Activity.ScreenCaptureCallback)?.let { runCatching { activity.unregisterScreenCaptureCallback(it) } }
        }
        if (started > 0 && --started == 0) {
            observer?.let { activity.contentResolver.unregisterContentObserver(it) }
            observer = null
            checking?.cancel()
        }
    }

    private inner class MediaWatcher(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = check()

        override fun onChange(selfChange: Boolean, uri: Uri?) = check()

        override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
            // With no access at all only the notice itself says a picture came: Android 11-13 tell
            // an insert apart (14+ hears its screenshots through the screen capture callback).
            if (Build.VERSION.SDK_INT in 30..33 && flags and ContentResolver.NOTIFY_INSERT != 0 && photoAccess(context) == PhotoAccess.NONE) {
                for (uri in uris) runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it >= 0 }?.let(detector::unreadImageAdded)
                return
            }
            check()
        }
    }

    companion object {
        /** Rescans while a picture is still being written: about ten seconds. */
        private const val MAX_WAITS = 25
        private const val WAIT_MS = 400L
        private const val DECLINED_KEY = "declined"

        /** The one for this device's media store. */
        fun forDevice(context: Context): RecentPhotos {
            val app = context.applicationContext
            val resolver = app.contentResolver
            val prefs = PrefsStore(app, "recent-photo")
            val detector = RecentPhotoDetector(
                query = { since -> queryImages(resolver, since) },
                now = System::currentTimeMillis,
                ownPackage = app.packageName,
                access = { photoAccess(app) },
                declinedAt = prefs.get(DECLINED_KEY)?.let { runCatching { PhotoAccess.valueOf(it) }.getOrNull() },
                onDeclinedChange = { prefs.put(DECLINED_KEY, it?.name) },
            )
            return RecentPhotos(detector, thumbnails = { id -> loadThumbnail(resolver, id) }, uriOf = ::imageUri)
        }

        fun imageUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

        /** Full, partial (Android 14's "selected photos") or none. */
        fun photoAccess(context: Context): PhotoAccess {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            return when {
                Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
                Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
                Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
                else -> PhotoAccess.NONE
            }
        }

        /** What to ask for when PPM opens: the photo permissions until access is full, then nothing. */
        fun permissionsToAskAtLaunch(context: Context): Array<String>? =
            photoPermissions().takeIf { photoAccess(context) != PhotoAccess.FULL }

        fun photoPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        /** The images added since [sinceSeconds], newest first, pending ones included so the newest is waited for. */
        @Suppress("DEPRECATION")
        fun queryImages(resolver: ContentResolver, sinceSeconds: Long): List<MediaImage> {
            val modern = Build.VERSION.SDK_INT >= 29
            val projection = listOfNotNull(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                if (modern) MediaStore.Images.Media.IS_PENDING else null,
                if (modern) MediaStore.Images.Media.OWNER_PACKAGE_NAME else null,
                if (modern) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA,
            ).toTypedArray()
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val args = arrayOf(sinceSeconds.toString())
            val cursor = runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    val query = Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media._ID))
                        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                        putInt(ContentResolver.QUERY_ARG_LIMIT, QUERY_LIMIT)
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    }
                    resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, query, null)
                } else {
                    val uri = if (modern) MediaStore.setIncludePending(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val order = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC LIMIT $QUERY_LIMIT"
                    resolver.query(uri, projection, selection, args, order)
                }
            }.getOrNull() ?: return emptyList()
            return cursor.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val pending = if (modern) c.getColumnIndex(MediaStore.Images.Media.IS_PENDING) else -1
                val owner = if (modern) c.getColumnIndex(MediaStore.Images.Media.OWNER_PACKAGE_NAME) else -1
                val path = c.getColumnIndex(if (modern) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA)
                buildList {
                    while (c.moveToNext()) {
                        add(
                            MediaImage(
                                id = c.getLong(id),
                                dateAddedSeconds = c.getLong(added),
                                size = c.getLong(size),
                                pending = pending >= 0 && c.getInt(pending) != 0,
                                ownerPackage = if (owner >= 0) c.getString(owner) else null,
                                path = if (path >= 0) c.getString(path)?.let { if (modern) it else it.substringBeforeLast('/') + "/" } else null,
                            ),
                        )
                    }
                }
            }
        }

        private const val QUERY_LIMIT = 8

        @Suppress("DEPRECATION")
        private fun loadThumbnail(resolver: ContentResolver, id: Long): ImageBitmap? {
            val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                resolver.loadThumbnail(imageUri(id), Size(THUMB_PX, THUMB_PX), null)
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            }
            return bitmap?.asImageBitmap()
        }

        private const val THUMB_PX = 160
    }
}
