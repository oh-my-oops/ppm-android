package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/**
 * Registered Web Push / UnifiedPush subscription record returned by
 * `GET /api/notifications/push` and `POST /api/notifications/push/subscribe`.
 */
data class PushSubscriptionDevice(
    val id: String,
    val endpoint: String,
    val label: String,
    val origin: String = "",
    val createdAt: String = "",
    val lastSuccessAt: String? = null,
    val lastError: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): PushSubscriptionDevice? {
            val obj = json?.obj ?: return null
            val id = obj["id"].string?.takeIf { it.isNotBlank() } ?: return null
            val endpoint = obj["endpoint"].string ?: ""
            val label = obj["label"].string?.takeIf { it.isNotBlank() } ?: "Android"
            val origin = obj["origin"].string ?: ""
            val createdAt = obj["createdAt"].string ?: ""
            val lastSuccessAt = obj["lastSuccessAt"].string?.takeIf { it.isNotBlank() }
            val lastError = obj["lastError"].string?.takeIf { it.isNotBlank() }
            return PushSubscriptionDevice(
                id = id,
                endpoint = endpoint,
                label = label,
                origin = origin,
                createdAt = createdAt,
                lastSuccessAt = lastSuccessAt,
                lastError = lastError,
            )
        }
    }
}

/**
 * Push status returned by `GET /api/notifications/push`.
 */
data class PushStatusResponse(
    val publicKey: String? = null,
    val devices: List<PushSubscriptionDevice> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): PushStatusResponse {
            val obj = json?.obj ?: return PushStatusResponse()
            val publicKey = obj["publicKey"].string?.takeIf { it.isNotBlank() }
            val devices = obj["devices"].array?.mapNotNull(PushSubscriptionDevice::from) ?: emptyList()
            return PushStatusResponse(
                publicKey = publicKey,
                devices = devices,
            )
        }
    }
}

/**
 * Delivery summary returned by `POST /api/notifications/push/test`.
 */
data class PushTestResponse(
    val sent: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
    val error: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): PushTestResponse {
            val obj = json?.obj ?: return PushTestResponse()
            return PushTestResponse(
                sent = obj["sent"].int ?: 0,
                failed = obj["failed"].int ?: 0,
                removed = obj["removed"].int ?: 0,
                error = obj["error"].string?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Format [ApiClient.baseUrl] as an HTTP(S) origin (`scheme://host[:port]`).
 */
fun ApiClient.originUrl(): String {
    val defaultPort = HttpUrl.defaultPort(baseUrl.scheme)
    return if (baseUrl.port == defaultPort) {
        "${baseUrl.scheme}://${baseUrl.host}"
    } else {
        "${baseUrl.scheme}://${baseUrl.host}:${baseUrl.port}"
    }
}

/**
 * Build the default human-readable push subscription label (`"Android · <device>"`).
 */
fun defaultAndroidPushLabel(deviceName: String? = null): String {
    val resolved = deviceName?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getProperty("ppm.device.model")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Device"
    return if (resolved.startsWith("Android ·", ignoreCase = true)) {
        resolved
    } else {
        "Android · $resolved"
    }
}

/**
 * Build the JSON body sent to `POST /api/notifications/push/subscribe`.
 */
fun buildPushSubscribeRequestBody(
    endpoint: String,
    p256dh: String,
    auth: String,
    label: String = defaultAndroidPushLabel(),
    origin: String = "",
): JsonObject = buildJsonObject {
    put(
        "subscription",
        buildJsonObject {
            put("endpoint", endpoint)
            put(
                "keys",
                buildJsonObject {
                    put("p256dh", p256dh)
                    put("auth", auth)
                },
            )
        },
    )
    put("label", label)
    if (origin.isNotBlank()) {
        put("origin", origin)
    }
}

/**
 * Register a Web Push / UnifiedPush subscription on the PPM server via
 * `POST /api/notifications/push/subscribe`.
 */
suspend fun ApiClient.notificationsSubscribePush(
    endpoint: String,
    p256dh: String,
    auth: String,
    label: String = defaultAndroidPushLabel(),
    origin: String = originUrl(),
): PushSubscriptionDevice {
    val body = buildPushSubscribeRequestBody(
        endpoint = endpoint,
        p256dh = p256dh,
        auth = auth,
        label = label,
        origin = origin,
    )
    val data = call("POST", "/api/notifications/push/subscribe", body = body)
    return PushSubscriptionDevice.from(data)
        ?: throw PpmError.BadResponse("Invalid push subscription response.")
}

/**
 * Alias for [notificationsSubscribePush].
 */
suspend fun ApiClient.subscribePush(
    endpoint: String,
    p256dh: String,
    auth: String,
    label: String = defaultAndroidPushLabel(),
    origin: String = originUrl(),
): PushSubscriptionDevice = notificationsSubscribePush(
    endpoint = endpoint,
    p256dh = p256dh,
    auth = auth,
    label = label,
    origin = origin,
)

/**
 * Remove a registered push subscription via `POST /api/notifications/push/unsubscribe`.
 */
suspend fun ApiClient.unsubscribePush(
    endpoint: String? = null,
    id: String? = null,
): Boolean {
    val data = notificationsUnsubscribePush(endpoint = endpoint, id = id)
    return data["removed"].bool ?: false
}

/**
 * Fetch VAPID public key and subscribed devices from `GET /api/notifications/push`.
 */
suspend fun ApiClient.pushStatus(): PushStatusResponse =
    PushStatusResponse.from(notificationsGetPush())

/**
 * Send a test push notification via `POST /api/notifications/push/test`.
 */
suspend fun ApiClient.testPush(id: String? = null): PushTestResponse =
    PushTestResponse.from(notificationsTestPush(id = id))

/**
 * Wrapper helper around [ApiClient] for Web Push / UnifiedPush subscription endpoints.
 */
class ApiClientPush(private val api: ApiClient) {
    suspend fun subscribe(
        endpoint: String,
        p256dh: String,
        auth: String,
        label: String = defaultAndroidPushLabel(),
        origin: String = api.originUrl(),
    ): PushSubscriptionDevice = api.notificationsSubscribePush(
        endpoint = endpoint,
        p256dh = p256dh,
        auth = auth,
        label = label,
        origin = origin,
    )

    suspend fun unsubscribe(
        endpoint: String? = null,
        id: String? = null,
    ): Boolean = api.unsubscribePush(endpoint = endpoint, id = id)

    suspend fun status(): PushStatusResponse = api.pushStatus()

    suspend fun test(id: String? = null): PushTestResponse = api.testPush(id = id)

    companion object {
        fun defaultLabel(deviceName: String? = null): String = defaultAndroidPushLabel(deviceName)

        suspend fun subscribe(
            api: ApiClient,
            endpoint: String,
            p256dh: String,
            auth: String,
            label: String = defaultAndroidPushLabel(),
            origin: String = api.originUrl(),
        ): PushSubscriptionDevice = ApiClientPush(api).subscribe(
            endpoint = endpoint,
            p256dh = p256dh,
            auth = auth,
            label = label,
            origin = origin,
        )
    }
}
