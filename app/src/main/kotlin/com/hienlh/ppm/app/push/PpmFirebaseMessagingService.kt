package com.hienlh.ppm.app.push

import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.client.defaultAndroidPushLabel
import com.hienlh.ppm.design.PpmLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PPM's FCM messages: data-only, `data["ppm"]` is the Web Push payload, drawn on the same channels
 * and with the same tap target as a UnifiedPush message.
 */
class PpmFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = fcmPushPayload(message.data)
        if (payload == null) {
            PpmLog.w(TAG, "Dropped an FCM message without a PPM payload")
            return
        }
        val appContext = applicationContext ?: this
        val prefs = PrefsStore(appContext)
        // A relative url opens on the server that sent it; FCM does not say which, so the first
        // server enrolled through FCM (with one Firebase project per app, usually the only one).
        val fallbackUrl = FcmEnrollment.instances(prefs).firstNotNullOfOrNull {
            PushDeviceManager.resolveServerUrl(appContext, prefs, it)
        }
        NotificationChannels.showPushNotification(appContext, payload, fallbackUrl)
    }

    @Deprecated("Messaging 26 also reports a token through onRegistered; both are handled.")
    override fun onNewToken(token: String) = resubscribe(token)

    override fun onRegistered(token: String) = resubscribe(token)

    /** Sends a new token to every server enrolled through FCM; the same token twice only updates. */
    private fun resubscribe(token: String) {
        val appContext = applicationContext ?: this
        val prefs = PrefsStore(appContext)
        if (FcmEnrollment.instances(prefs).isEmpty()) return
        val registration = FcmPushRegistration(prefs, AndroidFirebaseGateway(appContext), defaultAndroidPushLabel(Build.MODEL))
        // Runs on Firebase's worker thread; blocking keeps the process alive until the servers have it.
        runBlocking {
            withTimeoutOrNull(TOKEN_REFRESH_TIMEOUT_MS) {
                registration.refreshToken(token) { PushDeviceManager.resolveApiClient(appContext, prefs, it) }
            } ?: PpmLog.w(TAG, "Sending the new FCM token to the PPM servers timed out")
        }
    }

    private companion object {
        const val TAG = "FcmPush"
        const val TOKEN_REFRESH_TIMEOUT_MS = 30_000L
    }
}
