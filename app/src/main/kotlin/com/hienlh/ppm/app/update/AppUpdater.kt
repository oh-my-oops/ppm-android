package com.hienlh.ppm.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.BuildConfig
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.client.AppReleases
import com.hienlh.ppm.protocol.AppRelease
import com.hienlh.ppm.protocol.compareVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Keeps the app up to date from GitHub Releases ([AppReleases]): looks when the app opens and once
 * a day after, offers a newer version in a toast, and on Update downloads it, makes sure it is this
 * app, newer and signed with this app's key, then opens Android's installer on it. The installer
 * asks before it installs, and first sends the user to "Install unknown apps" when PPM may not
 * install apps yet.
 */
class AppUpdater(private val context: Context, private val model: AppModel) {
    private val prefs = PrefsStore(context, "ppm_updates")
    private val dir = File(context.cacheDir, "updates")
    private var checkedAt: Long? = null
    private var job: Job? = null

    /** The toast this updater shows last, so it takes back only its own. */
    private var shown: ErrorToastMessage? = null

    /** The update the installer was last opened on, while this run of the app has not seen it installed. */
    private var opened: Pair<AppRelease, File>? = null

    init {
        // An app left open for days, on a tablet kept awake, still looks once a day.
        model.scope.launch {
            while (true) {
                delay(HOUR)
                checkIfDue()
            }
        }
    }

    /** The app came to the front. */
    fun becameActive() {
        // Back without the update, from Android's "Install unknown apps" page say, which takes the
        // installer away with it: one tap opens it again.
        opened?.let { (release, file) ->
            opened = null
            show(ready(release, file))
        }
        checkIfDue()
    }

    /** Looks for a newer release, unless this run of the app looked less than a day ago. */
    private fun checkIfDue() {
        // A debug build is nobody's release: it looks only when given a list to test with.
        if (BuildConfig.DEBUG && BuildConfig.RELEASES_URL == null) return
        val now = System.currentTimeMillis()
        if (job?.isActive == true || checkedAt?.let { now - it < DAY } == true) return
        checkedAt = now
        job = model.scope.launch { check(now) }
    }

    private suspend fun check(now: Long) {
        val release = try {
            AppReleases.newest(BuildConfig.RELEASES_URL ?: AppReleases.LIST_URL)
        } catch (_: IOException) {
            return
        }
        if (release == null || compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0) {
            // A download left from before is installed by now.
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            return
        }
        // Offered once a day: closing the toast is "Later". One taken is offered again at the next
        // start, in case it never got installed.
        val offered = "offeredAt.${release.version}"
        if (prefs.get(offered)?.toLongOrNull()?.let { now - it < DAY } == true) return
        prefs.put(offered, now.toString())
        show(
            ErrorToastMessage(
                "Update available",
                "PPM for Android v${BuildConfig.VERSION_NAME} → v${release.version}",
                ErrorToastMessage.Action("Update now") {
                    prefs.put(offered, null)
                    update(release)
                },
                ErrorToastMessage.Kind.INFO,
                persistent = true,
            ),
        )
    }

    private fun update(release: AppRelease) {
        val previous = job
        job = model.scope.launch {
            // Done with, its own file deleted, before this one is written in its place.
            previous?.cancelAndJoin()
            val file = File(dir, "ppm-android-${release.version}.apk")
            val cancel = ErrorToastMessage.Action("Cancel") { job?.cancel() }
            fun progress(percent: Int?) = ErrorToastMessage(
                "Downloading the update",
                "PPM for Android v${release.version}" + (percent?.let { " · $it%" } ?: ""),
                cancel,
                ErrorToastMessage.Kind.PLAIN,
                persistent = true,
            )
            try {
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                show(progress(null))
                var percent: Int? = null
                AppReleases.download(release, file) { share ->
                    val now = (share * 100).toInt()
                    if (now != percent) {
                        percent = now
                        // Closed, it stays closed: the download goes on.
                        withContext(Dispatchers.Main) { if (model.toast === shown) show(progress(now)) }
                    }
                }
                val refusal = withContext(Dispatchers.IO) { refusal(file) }
                if (refusal != null) {
                    file.delete()
                    show(ErrorToastMessage("Update failed", refusal, persistent = true))
                    return@launch
                }
                hide()
                // Android opens no window for an app that is not in front: the update waits there.
                if (model.windowVisible) install(release, file) else show(ready(release, file))
            } catch (error: CancellationException) {
                hide()
                throw error
            } catch (error: IOException) {
                // Android cuts an app's connections a few seconds after it leaves the screen, and
                // a toast that timed out there would never have been seen: this one waits.
                val reason = if (model.windowVisible) error.message else "The download stopped while PPM was in the background."
                show(ErrorToastMessage("Update failed", reason, ErrorToastMessage.Action("Retry") { update(release) }, persistent = true))
            }
        }
    }

    /** Why [file] cannot update this app, or null when it can. */
    @Suppress("DEPRECATION")
    private fun refusal(file: File): String? {
        val manager = context.packageManager
        val update = manager.getPackageArchiveInfo(file.path, SIGNERS) ?: return "Android cannot read the downloaded file."
        val installed = manager.getPackageInfo(context.packageName, SIGNERS)
        return when {
            update.packageName != context.packageName -> "The download is not PPM for Android."
            PackageInfoCompat.getLongVersionCode(update) <= PackageInfoCompat.getLongVersionCode(installed) -> "The download is not newer than this app."
            update.signers().intersect(installed.signers()).isEmpty() ->
                "The update is signed with another key than this app. Uninstall this app, then install the update."
            else -> null
        }
    }

    private fun ready(release: AppRelease, file: File) = ErrorToastMessage(
        "Update downloaded",
        "PPM for Android v${release.version}",
        ErrorToastMessage.Action("Install") { install(release, file) },
        ErrorToastMessage.Kind.INFO,
        persistent = true,
    )

    private fun install(release: AppRelease, file: File) {
        // Android may have cleared the cache it was downloaded to since.
        if (!file.exists()) return update(release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            opened = release to file
        } catch (_: ActivityNotFoundException) {
            show(ErrorToastMessage("Update failed", "This device has no app that installs APKs.", persistent = true))
        }
    }

    private fun show(toast: ErrorToastMessage) {
        shown = toast
        model.toast = toast
    }

    private fun hide() {
        if (model.toast === shown) model.toast = null
        shown = null
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR

        @Suppress("DEPRECATION")
        val SIGNERS = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        /**
         * The certificates an APK is signed with; for one rotated to a new key, every key it has
         * had, so an update signed with the new key still shares the old one with the app it updates.
         */
        @Suppress("DEPRECATION")
        fun PackageInfo.signers() = (
            if (Build.VERSION.SDK_INT >= 28) {
                signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            } else {
                signatures
            }
        ).orEmpty().toSet()
    }
}
