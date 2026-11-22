package com.hienlh.ppm.app.push

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.client.FcmAndroidConfig
import com.hienlh.ppm.design.PpmLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The client config the server answered, as Firebase takes it (no google-services.json in the APK). */
fun firebaseOptions(config: FcmAndroidConfig): FirebaseOptions =
    FirebaseOptions.Builder()
        .setProjectId(config.projectId)
        .setApplicationId(config.appId)
        .setApiKey(config.apiKey)
        .setGcmSenderId(config.senderId)
        .apply { config.storageBucket?.let(::setStorageBucket) }
        .build()

/** Firebase and Play services for real. Automatic init is off in the manifest; this starts it. */
class AndroidFirebaseGateway(context: Context) : FirebaseGateway {
    private val context = context.applicationContext ?: context

    override fun playServicesAvailable(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    override fun existingProjectId(): String? = runCatching {
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }?.options?.projectId
    }.getOrNull()

    override fun initialize(config: FcmAndroidConfig): Boolean = synchronized(LOCK) {
        existingProjectId()?.let { return it == config.projectId }
        runCatching {
            FirebaseApp.initializeApp(context, firebaseOptions(config))
            true
        }.getOrElse {
            PpmLog.w(TAG, "Firebase did not start: ${it.message}")
            false
        }
    }

    // Messaging 26 deprecates getToken/deleteToken for register()/unregister(), which hand the token
    // to the service later; the app needs it in hand to subscribe, and these still work.
    @Suppress("DEPRECATION")
    override suspend fun token(): String {
        val messaging = FirebaseMessaging.getInstance()
        // Keeps the token refreshed (onNewToken) from now on; the manifest leaves it off until enrolled.
        messaging.isAutoInitEnabled = true
        return messaging.token.await()
    }

    @Suppress("DEPRECATION")
    override suspend fun deleteToken() {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = false
        messaging.deleteToken().await()
    }

    companion object {
        private const val TAG = "FcmPush"
        private val LOCK = Any()

        /**
         * Starts Firebase at launch with the config of the server(s) this device is enrolled with, so
         * a push that wakes the process finds its FirebaseApp. Nothing happens when none is.
         */
        fun startSaved(context: Context) {
            runCatching {
                val config = FcmEnrollment.savedConfig(PrefsStore(context)) ?: return
                AndroidFirebaseGateway(context).initialize(config)
            }.onFailure { PpmLog.w(TAG, "Firebase did not start at launch: ${it.message}") }
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
