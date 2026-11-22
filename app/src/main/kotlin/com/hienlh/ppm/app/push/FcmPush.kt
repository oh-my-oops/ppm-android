package com.hienlh.ppm.app.push

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.FcmAndroidConfig
import com.hienlh.ppm.client.FcmServerConfig
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.subscribeFcmPush
import com.hienlh.ppm.client.unsubscribePush
import com.hienlh.ppm.design.PpmLog
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.CancellationException

/** Why a server's push goes through UnifiedPush rather than Firebase. */
enum class FcmFallbackReason {
    /** The server has no Firebase project set up (or is older than `GET /api/notifications/fcm`). */
    NOT_CONFIGURED,

    /** Google Play services are missing or unusable on this device. */
    NO_PLAY_SERVICES,

    /** This process already runs Firebase for another server's project: one project per app process. */
    OTHER_PROJECT,
}

/** How this device's push reaches it from one server. */
sealed interface PushTransport {
    data class Firebase(val config: FcmAndroidConfig) : PushTransport
    data class UnifiedPush(val reason: FcmFallbackReason) : PushTransport
}

/**
 * Firebase when the server is set up for it, Play services are there and the process's default
 * FirebaseApp (if any) belongs to the same project; UnifiedPush otherwise.
 */
fun choosePushTransport(
    server: FcmServerConfig?,
    playServicesAvailable: Boolean,
    existingProjectId: String?,
): PushTransport {
    val android = server?.android?.takeIf { server.configured }
        ?: return PushTransport.UnifiedPush(FcmFallbackReason.NOT_CONFIGURED)
    if (!playServicesAvailable) return PushTransport.UnifiedPush(FcmFallbackReason.NO_PLAY_SERVICES)
    if (existingProjectId != null && existingProjectId != android.projectId) {
        return PushTransport.UnifiedPush(FcmFallbackReason.OTHER_PROJECT)
    }
    return PushTransport.Firebase(android)
}

/** The PPM payload of an FCM data message: `data["ppm"]` holds the same JSON a Web Push carries. */
fun fcmPushPayload(data: Map<String, String>): WebPushPayload? =
    data[FCM_PAYLOAD_KEY]?.let(WebPushPayload::fromJson)

const val FCM_PAYLOAD_KEY = "ppm"

/** What the app needs from Firebase and Play services; [AndroidFirebaseGateway] is the real one. */
interface FirebaseGateway {
    fun playServicesAvailable(): Boolean

    /** Project of the process's default FirebaseApp, `null` when none is running. */
    fun existingProjectId(): String?

    /** Starts the default FirebaseApp with [config]; `true` when it now runs [config]'s project. */
    fun initialize(config: FcmAndroidConfig): Boolean

    /** This device's FCM registration token (also keeps it refreshed from now on). */
    suspend fun token(): String

    /** Drops the token and stops refreshing it: no server has this device through FCM any more. */
    suspend fun deleteToken()
}

/**
 * Which servers have this device through FCM, kept beside [PushDeviceManager]'s per-server keys
 * (enabled, device id, server URL), plus the Firebase client config the app starts with.
 */
object FcmEnrollment {
    private const val KEY_INSTANCES = "push.fcm.instances"
    private const val KEY_OPTIONS = "push.fcm.options"

    fun instances(store: KeyValueStore): List<String> =
        store.get(KEY_INSTANCES)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun isFcm(store: KeyValueStore, instance: String): Boolean =
        PushDeviceManager.normalizeInstance(instance) in instances(store)

    /** The config the default FirebaseApp is started with at launch, so a push can wake the app. */
    fun savedConfig(store: KeyValueStore): FcmAndroidConfig? =
        store.get(KEY_OPTIONS)?.let { FcmAndroidConfig.from(WireJson.parseOrNull(it)) }

    fun record(store: KeyValueStore, instance: String, deviceId: String, serverUrl: String?, config: FcmAndroidConfig) {
        val norm = PushDeviceManager.normalizeInstance(instance)
        PushDeviceManager.recordServerUrl(store, norm, serverUrl)
        PushDeviceManager.setEnabled(store, true, norm)
        PushDeviceManager.recordDeviceId(store, norm, deviceId)
        store.put(KEY_INSTANCES, (instances(store) - norm + norm).joinToString("\n"))
        store.put(KEY_OPTIONS, config.toJson().toString())
    }

    /** Forgets [instance]; answers whether any server still has this device through FCM. */
    fun remove(store: KeyValueStore, instance: String): Boolean {
        val norm = PushDeviceManager.normalizeInstance(instance)
        PushDeviceManager.clearRegistration(store, norm)
        val left = instances(store) - norm
        store.put(KEY_INSTANCES, left.joinToString("\n").ifEmpty { null })
        if (left.isEmpty()) store.put(KEY_OPTIONS, null)
        return left.isNotEmpty()
    }
}

/** Subscribing this device to servers through FCM, and keeping them current when the token changes. */
class FcmPushRegistration(
    private val store: KeyValueStore,
    private val gateway: FirebaseGateway,
    private val label: String,
) {
    /** Initialises Firebase with [config], subscribes the token on [api]; answers the subscription id. */
    suspend fun enroll(instance: String, api: ApiClient, config: FcmAndroidConfig, serverUrl: String?): String {
        if (!gateway.initialize(config)) {
            throw IllegalStateException("Firebase is already set up for another server's project")
        }
        val token = gateway.token()
        // An earlier subscription of this device (UnifiedPush, or an old token) is replaced in place.
        val previous = PushDeviceManager.savedDeviceId(store, instance)
        val device = api.subscribeFcmPush(token = token, label = label, deviceId = previous)
        FcmEnrollment.record(store, instance, device.id, serverUrl, config)
        return device.id
    }

    /** Removes [instance]'s FCM subscription here and on its server. */
    suspend fun unenroll(instance: String, api: ApiClient?) {
        val deviceId = PushDeviceManager.savedDeviceId(store, instance)
        val anyLeft = FcmEnrollment.remove(store, instance)
        if (api != null && !deviceId.isNullOrBlank()) {
            try {
                api.unsubscribePush(id = deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PpmLog.w(TAG, "Failed to remove the FCM subscription from the PPM server: ${e.message}")
            }
        }
        if (!anyLeft) runCatching { gateway.deleteToken() }
    }

    /** A new token: every server that has this device through FCM gets it, replacing the old entry. */
    suspend fun refreshToken(token: String, apiFor: (String) -> ApiClient?) {
        for (instance in FcmEnrollment.instances(store)) {
            val api = apiFor(instance) ?: continue
            try {
                val previous = PushDeviceManager.savedDeviceId(store, instance)
                val device = api.subscribeFcmPush(token = token, label = label, deviceId = previous)
                PushDeviceManager.recordDeviceId(store, instance, device.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PpmLog.w(TAG, "Failed to send the new FCM token to a PPM server: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "FcmPush"
    }
}
