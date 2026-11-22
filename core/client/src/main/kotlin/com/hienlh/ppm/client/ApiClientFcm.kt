package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The public client entry of the server's `google-services.json` for the `com.hienlh.ppm` app:
 * everything the app needs to start its default FirebaseApp. Nothing here is a secret.
 */
data class FcmAndroidConfig(
    val apiKey: String,
    val appId: String,
    val senderId: String,
    val projectId: String,
    val storageBucket: String? = null,
) {
    companion object {
        /** `null` unless all four required fields are present and non-blank. */
        fun from(json: JsonElement?): FcmAndroidConfig? {
            val obj = json?.obj ?: return null
            fun field(name: String) = obj[name].string?.trim()?.takeIf { it.isNotEmpty() }
            return FcmAndroidConfig(
                apiKey = field("apiKey") ?: return null,
                appId = field("appId") ?: return null,
                senderId = field("senderId") ?: return null,
                projectId = field("projectId") ?: return null,
                storageBucket = field("storageBucket"),
            )
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("apiKey", apiKey)
        put("appId", appId)
        put("senderId", senderId)
        put("projectId", projectId)
        storageBucket?.let { put("storageBucket", it) }
    }
}

/** `GET /api/notifications/fcm`: whether the server can send through Firebase, and to which app. */
data class FcmServerConfig(
    val configured: Boolean,
    val projectId: String? = null,
    val android: FcmAndroidConfig? = null,
) {
    /** Configured with a usable client entry for this app. */
    val usable: Boolean get() = configured && android != null

    companion object {
        val NOT_CONFIGURED = FcmServerConfig(configured = false)

        fun from(json: JsonElement?): FcmServerConfig {
            val obj = json?.obj ?: return NOT_CONFIGURED
            return FcmServerConfig(
                configured = obj["configured"].bool ?: false,
                projectId = obj["projectId"].string?.trim()?.takeIf { it.isNotEmpty() },
                android = FcmAndroidConfig.from(obj["android"]),
            )
        }
    }
}

/**
 * Reads `GET /api/notifications/fcm`. A server older than the route (404, or the web app's page)
 * answers [FcmServerConfig.NOT_CONFIGURED], so the caller falls back to UnifiedPush.
 */
suspend fun ApiClient.notificationsGetFcm(): FcmServerConfig = try {
    FcmServerConfig.from(call("GET", "/api/notifications/fcm"))
} catch (err: PpmError.Server) {
    if (err.status == 404) FcmServerConfig.NOT_CONFIGURED else throw err
} catch (err: PpmError.OutdatedServer) {
    FcmServerConfig.NOT_CONFIGURED
} catch (err: PpmError.BadResponse) {
    if (err.reason.contains("404")) FcmServerConfig.NOT_CONFIGURED else throw err
}

/** Body of `POST /api/notifications/push/subscribe` for an FCM device. */
fun buildFcmSubscribeRequestBody(
    token: String,
    label: String = defaultAndroidPushLabel(),
    deviceId: String? = null,
    origin: String = "",
): JsonObject = buildJsonObject {
    put("kind", "fcm")
    put("token", token)
    put("label", label)
    deviceId?.takeIf { it.isNotBlank() }?.let { put("deviceId", it) }
    // With it the server sends an absolute `url`, so a tap opens on the server that sent the push.
    if (origin.isNotBlank()) put("origin", origin)
}

/**
 * Subscribes this device's FCM [token] via `POST /api/notifications/push/subscribe`. [deviceId] is
 * this device's earlier subscription (UnifiedPush, or an older token), which the server replaces in
 * place, keeping its id.
 */
suspend fun ApiClient.subscribeFcmPush(
    token: String,
    label: String = defaultAndroidPushLabel(),
    deviceId: String? = null,
    origin: String = originUrl(),
): PushSubscriptionDevice {
    val body = buildFcmSubscribeRequestBody(token, label, deviceId, origin)
    val data = call("POST", "/api/notifications/push/subscribe", body = body)
    return PushSubscriptionDevice.from(data)
        ?: throw PpmError.BadResponse("Invalid push subscription response.")
}
