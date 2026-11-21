package com.hienlh.ppm.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hienlh.ppm.app.MainActivity
import com.hienlh.ppm.app.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Creates Android 8+ notification channels per [PushChannel] and posts incoming
 * [WebPushPayload] notifications with a `ppm://` deep-link [PendingIntent] targeting
 * [MainActivity] (Requirements 7.2, 7.3 & 7.4).
 */
object NotificationChannels {
    const val EXTRA_DEEP_LINK = "com.hienlh.ppm.push.EXTRA_DEEP_LINK"
    const val EXTRA_PROJECT = "com.hienlh.ppm.push.EXTRA_PROJECT"
    const val EXTRA_SESSION_ID = "com.hienlh.ppm.push.EXTRA_SESSION_ID"
    const val EXTRA_PROVIDER_ID = "com.hienlh.ppm.push.EXTRA_PROVIDER_ID"
    const val EXTRA_KIND = "com.hienlh.ppm.push.EXTRA_KIND"

    private const val TAGGED_NOTIFICATION_ID = 7200
    private val nextUntaggedId = AtomicInteger(7201)

    /**
     * Register all four PPM push notification channels with the system [NotificationManager].
     * Safe to call repeatedly; Android updates channel names/descriptions in place.
     */
    fun ensureChannels(context: Context): List<NotificationChannel> {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        val channels = PushChannel.entries.map { spec ->
            NotificationChannel(spec.id, spec.title, spec.importance).apply {
                description = spec.description
            }
        }
        manager.createNotificationChannels(channels)
        return channels
    }

    /**
     * Whether the app currently has permission to post notifications (`POST_NOTIFICATIONS` on
     * Android 13+ and system notifications enabled for the app).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(true)
    }

    /**
     * Whether the runtime `POST_NOTIFICATIONS` permission is granted (always `true` below API 33).
     */
    fun isRuntimePermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Build the [Intent] fired when the user taps a system push notification.
     *
     * Routes through [MainActivity] with `Intent.ACTION_VIEW` and a `ppm://` URI so
     * `AppModel.openLink` opens the target server, project, and chat session or tab.
     */
    fun buildTapIntent(
        context: Context,
        payload: WebPushPayload,
        fallbackServerUrl: String? = null,
    ): Intent {
        val deepLink = buildPpmDeepLink(payload, fallbackServerUrl)
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK
            if (deepLink != null) {
                action = Intent.ACTION_VIEW
                data = Uri.parse(deepLink)
                putExtra(EXTRA_DEEP_LINK, deepLink)
            } else {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            payload.project?.let { putExtra(EXTRA_PROJECT, it) }
            payload.sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            payload.providerId?.let { putExtra(EXTRA_PROVIDER_ID, it) }
            putExtra(EXTRA_KIND, payload.kind.wire)
        }
    }

    /**
     * Post a system notification for [payload] on its mapped [PushChannel].
     *
     * Returns the notification ID posted, or `null` if notifications cannot be posted.
     */
    fun showPushNotification(
        context: Context,
        payload: WebPushPayload,
        fallbackServerUrl: String? = null,
    ): Int? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        ensureChannels(context)
        if (!isRuntimePermissionGranted(context)) return null

        val channel = payload.channel
        val tag = payload.tag?.takeIf { it.isNotBlank() }
        val notificationId = if (tag != null) {
            TAGGED_NOTIFICATION_ID
        } else {
            nextUntaggedId.getAndIncrement()
        }
        val requestCode = (tag ?: "${payload.title}:${payload.url}:$notificationId").hashCode()
        val tapIntent = buildTapIntent(context, payload, fallbackServerUrl)
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val priority = if (channel == PushChannel.APPROVAL_QUESTION) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }
        val category = if (channel == PushChannel.APPROVAL_QUESTION) {
            NotificationCompat.CATEGORY_MESSAGE
        } else {
            NotificationCompat.CATEGORY_STATUS
        }

        val text = pushNotificationText(payload)
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setSubText(text.subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.bigText))
            .setPriority(priority)
            .setCategory(category)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        if (tag != null) {
            manager.notify(tag, notificationId, notification)
        } else {
            manager.notify(notificationId, notification)
        }
        return notificationId
    }
}
