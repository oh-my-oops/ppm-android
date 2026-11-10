package com.hienlh.ppm.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Keeps the app up to date from GitHub Releases ([AppReleases]): looks when the app opens and once
 * a day after, offers a newer version in a toast, and on Update downloads it, makes sure it is this
 * app, newer and signed with this app's key, then hands it to Android's installer with its Baseline
 * Profile, so that Android compiles the update as it installs it. The installer asks before it
 * installs, and first sends the user to "Install unknown apps" when PPM may not install apps yet.
 */
class AppUpdater(private val context: Context, private val model: AppModel) {
    sealed interface State {
        data object Idle : State
        data class Available(val currentVersion: String, val release: AppRelease) : State
        data class Downloading(val release: AppRelease, val percent: Int?) : State
        data class ReadyToInstall(val release: AppRelease) : State
        data class Failed(val release: AppRelease, val reason: String, val canInstallDirectly: Boolean = false) : State
    }

    private val prefs = PrefsStore(context, "ppm_updates")
    private val dir = File(context.cacheDir, "updates")
    private var checkedAt: Long? = null
    private var job: Job? = null
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var readyUpdate: Pair<AppRelease, Update>? = null

    /** The toast this updater shows last, so it takes back only its own. */
    private var shown: ErrorToastMessage? = null

    /** The update handed to the installer, while this run of the app has not heard how that ended. */
    private var pending: Pair<AppRelease, Update>? = null

    /** A downloaded update: the APK, and the profile Android compiles it with when there is one. */
    private class Update(val apk: File, val profile: File?)

    init {
        activeInstance = WeakReference(this)
        // An app left open for days, on a tablet kept awake, still looks once a day.
        model.scope.launch {
            while (true) {
                delay(HOUR)
                checkIfDue()
            }
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = installStatus(intent)
            },
            IntentFilter(INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** The app came to the front. */
    fun becameActive() {
        // Back without the update, from Android's "Install unknown apps" page say, which takes the
        // installer away with it: one tap opens it again.
        pending?.let { (release, update) ->
            pending = null
            readyUpdate = release to update
            _state.value = State.ReadyToInstall(release)
            show(ready(release, update))
        }
        checkIfDue()
    }

    fun startUpdate() {
        val current = _state.value
        val release = when (current) {
            is State.Available -> current.release
            is State.Failed -> current.release
            else -> return
        }
        prefs.put("offeredAt.${release.version}", null)
        update(release)
    }

    fun cancelDownload() {
        job?.cancel()
        _state.value = State.Idle
        hide()
    }

    fun installReadyUpdate() {
        val pair = readyUpdate ?: return
        installLater(pair.first, pair.second)
    }

    fun dismissOffer() {
        _state.value = State.Idle
        hide()
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
        _state.value = State.Available(BuildConfig.VERSION_NAME, release)
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
            val apk = File(dir, "ppm-android-${release.version}.apk")
            val cancel = ErrorToastMessage.Action("Cancel") { cancelDownload() }
            fun progress(percent: Int?) = ErrorToastMessage(
                "Downloading the update",
                "PPM for Android v${release.version}" + (percent?.let { " · $it%" } ?: ""),
                cancel,
                ErrorToastMessage.Kind.PLAIN,
                persistent = true,
            )
            try {
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                _state.value = State.Downloading(release, null)
                show(progress(null))
                var percent: Int? = null
                AppReleases.download(release, apk) { share ->
                    val now = (share * 100).toInt()
                    if (now != percent) {
                        percent = now
                        _state.value = State.Downloading(release, now)
                        // Closed, it stays closed: the download goes on.
                        withContext(Dispatchers.Main) { replace(progress(now)) }
                    }
                }
                val refusal = withContext(Dispatchers.IO) { refusal(apk) }
                if (refusal != null) {
                    apk.delete()
                    _state.value = State.Failed(release, refusal)
                    show(ErrorToastMessage("Update failed", refusal, persistent = true))
                    return@launch
                }
                val update = Update(apk, profile(release))
                readyUpdate = release to update
                _state.value = State.ReadyToInstall(release)
                hide()
                // Android opens no window for an app that is not in front: the update waits there.
                if (model.windowVisible) install(release, update) else show(ready(release, update))
            } catch (error: CancellationException) {
                hide()
                throw error
            } catch (error: IOException) {
                // Android cuts an app's connections a few seconds after it leaves the screen, and
                // a toast that timed out there would never have been seen: this one waits.
                val reason = if (model.windowVisible) error.message else "The download stopped while PPM was in the background."
                _state.value = State.Failed(release, reason ?: "Download failed.")
                show(ErrorToastMessage("Update failed", reason, ErrorToastMessage.Action("Retry") { update(release) }, persistent = true))
            }
        }
    }

    /**
     * [release]'s Baseline Profile for this device, or null when it has none or it cannot be had:
     * the update installs all the same, and Android compiles it when the device is next idle.
     */
    private suspend fun profile(release: AppRelease): File? {
        val profile = release.profileFor(Build.VERSION.SDK_INT) ?: return null
        val file = File(dir, "ppm-android-${release.version}.dm")
        try {
            AppReleases.download(profile, file)
        } catch (_: IOException) {
            return null
        }
        return withContext(Dispatchers.IO) {
            if (isProfileOnly(file)) {
                file
            } else {
                file.delete()
                null
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

    private fun ready(release: AppRelease, update: Update) = ErrorToastMessage(
        "Update downloaded",
        "PPM for Android v${release.version}",
        ErrorToastMessage.Action("Install") { installLater(release, update) },
        ErrorToastMessage.Kind.INFO,
        persistent = true,
    )

    /** [install], from a toast's button. */
    private fun installLater(release: AppRelease, update: Update) {
        val previous = job
        job = model.scope.launch {
            previous?.cancelAndJoin()
            install(release, update)
        }
    }

    private suspend fun install(release: AppRelease, update: Update) {
        // Android may have cleared the cache it was downloaded to since.
        if (!update.apk.exists()) return update(release)
        // Before the commit: the installer may answer before this coroutine is back on its thread.
        pending = release to update
        try {
            withContext(Dispatchers.IO) { commit(update) }
        } catch (error: IOException) {
            pending = null
            val reason = error.message ?: "Android's installer did not take the update."
            _state.value = State.Failed(release, reason, canInstallDirectly = true)
            show(ErrorToastMessage("Update failed", reason, ErrorToastMessage.Action("Retry") { installLater(release, update) }, persistent = true))
        }
    }

    /** Writes [update] into an install session and commits it; the installer answers [installStatus]. */
    private fun commit(update: Update) {
        val installer = context.packageManager.packageInstaller
        // What an earlier try left open would otherwise wait there until Android drops it.
        for (session in installer.mySessions) installer.abandonSession(session.sessionId)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.write("base.apk", update.apk)
                // Android takes the dex metadata named as the APK is as that APK's profile.
                update.profile?.takeIf { it.exists() }?.let { session.write("base.dm", it) }
                session.commit(statusTarget(id))
            }
        } catch (error: Throwable) {
            installer.abandonSession(id)
            throw error
        }
    }

    private fun PackageInstaller.Session.write(name: String, file: File) {
        openWrite(name, 0, file.length()).use { output ->
            file.inputStream().use { it.copyTo(output) }
            fsync(output)
        }
    }

    private fun statusTarget(id: Int): IntentSender {
        val intent = Intent(INSTALL_STATUS).setPackage(context.packageName)
        // Mutable: the installer writes how the install went into it.
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender
    }

    /** How the install of [pending] is going, as the installer tells it. */
    private fun installStatus(intent: Intent) {
        val (release, update) = pending ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            // Android opens no window for an app that is not in front: the update waits there.
            if (confirm != null && model.windowVisible) {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                pending = null
                readyUpdate = release to update
                _state.value = State.ReadyToInstall(release)
                show(ready(release, update))
            }
            return
        }
        pending = null
        when (status) {
            // Seldom heard: installing the update ends this process.
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle
            // Turned down in the installer's window: one tap asks again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                readyUpdate = release to update
                _state.value = State.ReadyToInstall(release)
                show(ready(release, update))
            }
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android did not install the update."
                readyUpdate = release to update
                _state.value = State.Failed(release, reason, canInstallDirectly = true)
                show(ErrorToastMessage("Update failed", reason, ErrorToastMessage.Action("Retry") { installLater(release, update) }, persistent = true))
            }
        }
    }

    /** [toast] in place of this updater's last one while that is up, as one raised again under its id; else on top. */
    private fun show(toast: ErrorToastMessage) {
        if (!replace(toast)) model.toast(toast)
        shown = toast
    }

    /** [toast] in place of this updater's last one, only while that is up. */
    private fun replace(toast: ErrorToastMessage): Boolean {
        val replaced = shown?.let { model.replaceToast(it, toast) } == true
        if (replaced) shown = toast
        return replaced
    }

    private fun hide() {
        shown?.let(model::dismissToast)
        shown = null
    }

    companion object {
        internal var activeInstance: WeakReference<AppUpdater>? = null

        private const val HOUR = 60 * 60 * 1000L
        private const val DAY = 24 * HOUR

        /** The broadcast the installer answers an install session with. */
        private const val INSTALL_STATUS = "com.hienlh.ppm.app.update.INSTALL_STATUS"

        @Suppress("DEPRECATION")
        private val SIGNERS = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        /**
         * The certificates an APK is signed with; for one rotated to a new key, every key it has
         * had, so an update signed with the new key still shares the old one with the app it updates.
         */
        @Suppress("DEPRECATION")
        private fun PackageInfo.signers() = (
            if (Build.VERSION.SDK_INT >= 28) {
                signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            } else {
                signatures
            }
        ).orEmpty().toSet()
    }
}
