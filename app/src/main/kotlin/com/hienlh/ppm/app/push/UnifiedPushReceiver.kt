package com.hienlh.ppm.app.push

import android.content.Context
import android.os.Build
import com.hienlh.ppm.app.KeystoreSecrets
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ApiClientPush
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.SecretStore
import com.hienlh.ppm.client.ServerProfile
import com.hienlh.ppm.client.ServerStore
import com.hienlh.ppm.client.defaultAndroidPushLabel
import com.hienlh.ppm.client.originUrl
import com.hienlh.ppm.client.unsubscribePush
import com.hienlh.ppm.design.PpmLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush [MessagingReceiver] handling endpoint registration, RFC 8291 decrypted
 * push messages, and unregistration events for PPM servers (Requirements 7.1–7.4).
 */
class UnifiedPushReceiver : MessagingReceiver() {

    override fun onNewEndpoint(
        context: Context,
        endpoint: PushEndpoint,
        instance: String,
    ) {
        val pubKey = endpoint.pubKeySet?.pubKey?.trim().orEmpty()
        val auth = endpoint.pubKeySet?.auth?.trim().orEmpty()
        if (endpoint.url.isBlank() || pubKey.isEmpty() || auth.isEmpty()) {
            PpmLog.w(TAG, "Ignoring UnifiedPush endpoint without WebPush keys for instance=$instance")
            return
        }
        val prefs = PrefsStore(context.applicationContext ?: context)
        PushDeviceManager.recordEndpoint(
            store = prefs,
            instance = instance,
            endpoint = endpoint.url,
            p256dh = pubKey,
            auth = auth,
        )
        val pending = runCatching { goAsync() }.getOrNull()
        scope.launch {
            try {
                subscribeEndpointOnServer(
                    context = context.applicationContext ?: context,
                    prefs = prefs,
                    instance = instance,
                    endpoint = endpoint.url,
                    p256dh = pubKey,
                    auth = auth,
                )
            } catch (e: Throwable) {
                PpmLog.w(TAG, "Failed to register UnifiedPush endpoint with PPM server: ${e.message}")
            } finally {
                runCatching { pending?.finish() }
            }
        }
    }

    override fun onMessage(
        context: Context,
        message: PushMessage,
        instance: String,
    ) {
        val payload = WebPushPayload.fromPushMessage(message)
        if (payload == null) {
            if (!message.decrypted) {
                PpmLog.w(TAG, "Dropped unencrypted UnifiedPush message for instance=$instance")
            }
            return
        }
        val appContext = context.applicationContext ?: context
        val prefs = PrefsStore(appContext)
        val fallbackUrl = PushDeviceManager.resolveServerUrl(appContext, prefs, instance)
        NotificationChannels.showPushNotification(
            context = appContext,
            payload = payload,
            fallbackServerUrl = fallbackUrl,
        )
    }

    override fun onRegistrationFailed(
        context: Context,
        reason: FailedReason,
        instance: String,
    ) {
        val prefs = PrefsStore(context.applicationContext ?: context)
        PushDeviceManager.recordFailure(prefs, instance, reason.name)
    }

    override fun onUnregistered(
        context: Context,
        instance: String,
    ) {
        val appContext = context.applicationContext ?: context
        val prefs = PrefsStore(appContext)
        val oldEndpoint = PushDeviceManager.savedEndpoint(prefs, instance)
        val oldDeviceId = PushDeviceManager.savedDeviceId(prefs, instance)
        PushDeviceManager.clearRegistration(prefs, instance)
        if (oldEndpoint.isNullOrBlank() && oldDeviceId.isNullOrBlank()) return

        val pending = runCatching { goAsync() }.getOrNull()
        scope.launch {
            try {
                val api = PushDeviceManager.resolveApiClient(appContext, prefs, instance)
                api?.unsubscribePush(endpoint = oldEndpoint, id = oldDeviceId)
            } catch (e: Throwable) {
                PpmLog.w(TAG, "Failed to unsubscribe push endpoint from PPM server: ${e.message}")
            } finally {
                runCatching { pending?.finish() }
            }
        }
    }

    companion object {
        private const val TAG = "UnifiedPushReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Overridable hook for unit tests to observe or intercept server subscription calls.
         */
        @Volatile
        var serverSubscriber: (suspend (
            context: Context,
            prefs: KeyValueStore,
            instance: String,
            endpoint: String,
            p256dh: String,
            auth: String,
        ) -> Unit)? = null

        suspend fun subscribeEndpointOnServer(
            context: Context,
            prefs: KeyValueStore,
            instance: String,
            endpoint: String,
            p256dh: String,
            auth: String,
        ) {
            val custom = serverSubscriber
            if (custom != null) {
                custom(context, prefs, instance, endpoint, p256dh, auth)
                return
            }
            val api = PushDeviceManager.resolveApiClient(context, prefs, instance) ?: return
            val label = defaultAndroidPushLabel(Build.MODEL)
            val device = ApiClientPush(api).subscribe(
                endpoint = endpoint,
                p256dh = p256dh,
                auth = auth,
                label = label,
                origin = api.originUrl(),
            )
            PushDeviceManager.recordDeviceId(prefs, instance, device.id)
        }
    }
}

/**
 * Coordinates UnifiedPush distributor discovery, local registration state in [KeyValueStore],
 * and server-side subscription lifecycle for Settings -> Notifications.
 */
object PushDeviceManager {
    private val VAPID_REGEX = Regex("^[A-Za-z0-9_-]{87}$")

    private fun keyEnabled(instance: String) = "push.enabled.$instance"
    private fun keyEndpoint(instance: String) = "push.endpoint.$instance"
    private fun keyP256dh(instance: String) = "push.p256dh.$instance"
    private fun keyAuth(instance: String) = "push.auth.$instance"
    private fun keyDeviceId(instance: String) = "push.deviceId.$instance"
    private fun keyError(instance: String) = "push.error.$instance"
    private fun keyServerUrl(instance: String) = "push.serverUrl.$instance"

    /**
     * Normalize an instance identifier (defaults to the active server ID or `"default"`).
     */
    fun normalizeInstance(serverId: String?): String =
        serverId?.trim()?.takeIf { it.isNotEmpty() } ?: INSTANCE_DEFAULT

    /**
     * Return the package names of all UnifiedPush distributors installed on the device
     * (e.g. `io.heckel.ntfy`).
     */
    fun availableDistributors(context: Context): List<String> =
        runCatching { UnifiedPush.getDistributors(context) }.getOrDefault(emptyList())

    /**
     * Whether at least one UnifiedPush distributor is installed on this device.
     */
    fun hasDistributor(context: Context): Boolean =
        availableDistributors(context).isNotEmpty()

    /**
     * Human-friendly label for a distributor package name.
     */
    fun distributorDisplayName(packageName: String?): String = when (packageName) {
        null, "" -> "None installed"
        "io.heckel.ntfy", "io.heckel.ntfy.fdroid" -> "ntfy"
        "org.unifiedpush.distributor.nextpush" -> "NextPush"
        "org.unifiedpush.distributor.sunup" -> "Sunup"
        "im.vector.app" -> "Element"
        else -> packageName.substringAfterLast('.')
    }

    fun isEnabled(store: KeyValueStore, instance: String = INSTANCE_DEFAULT): Boolean =
        store.get(keyEnabled(normalizeInstance(instance)))?.toBooleanStrictOrNull() ?: false

    fun setEnabled(store: KeyValueStore, enabled: Boolean, instance: String = INSTANCE_DEFAULT) {
        val norm = normalizeInstance(instance)
        store.put(keyEnabled(norm), enabled.toString())
        if (!enabled) {
            store.put(keyError(norm), null)
        }
    }

    fun savedEndpoint(store: KeyValueStore, instance: String = INSTANCE_DEFAULT): String? =
        store.get(keyEndpoint(normalizeInstance(instance)))?.takeIf { it.isNotBlank() }

    fun savedP256dh(store: KeyValueStore, instance: String = INSTANCE_DEFAULT): String? =
        store.get(keyP256dh(normalizeInstance(instance)))?.takeIf { it.isNotBlank() }

    fun savedAuth(store: KeyValueStore, instance: String = INSTANCE_DEFAULT): String? =
        store.get(keyAuth(normalizeInstance(instance)))?.takeIf { it.isNotBlank() }

    fun savedDeviceId(store: KeyValueStore, instance: String = INSTANCE_DEFAULT): String? =
        store.get(keyDeviceId(normalizeInstance(instance)))?.takeIf { it.isNotBlank() }

    fun savedError(store: KeyValueStore, instance: String = INSTANCE_DEFAULT): String? =
        store.get(keyError(normalizeInstance(instance)))?.takeIf { it.isNotBlank() }

    fun recordEndpoint(
        store: KeyValueStore,
        instance: String = INSTANCE_DEFAULT,
        endpoint: String,
        p256dh: String,
        auth: String,
    ) {
        val norm = normalizeInstance(instance)
        store.put(keyEnabled(norm), "true")
        store.put(keyEndpoint(norm), endpoint)
        store.put(keyP256dh(norm), p256dh)
        store.put(keyAuth(norm), auth)
        store.put(keyError(norm), null)
    }

    fun recordDeviceId(
        store: KeyValueStore,
        instance: String = INSTANCE_DEFAULT,
        deviceId: String,
    ) {
        val norm = normalizeInstance(instance)
        store.put(keyDeviceId(norm), deviceId)
    }

    fun recordServerUrl(
        store: KeyValueStore,
        instance: String = INSTANCE_DEFAULT,
        serverUrl: String?,
    ) {
        val norm = normalizeInstance(instance)
        store.put(keyServerUrl(norm), serverUrl?.takeIf { it.isNotBlank() })
    }

    fun recordFailure(
        store: KeyValueStore,
        instance: String = INSTANCE_DEFAULT,
        reason: String,
    ) {
        val norm = normalizeInstance(instance)
        store.put(keyEnabled(norm), "false")
        store.put(keyError(norm), reason)
    }

    fun clearRegistration(
        store: KeyValueStore,
        instance: String = INSTANCE_DEFAULT,
    ) {
        val norm = normalizeInstance(instance)
        store.put(keyEnabled(norm), "false")
        store.put(keyEndpoint(norm), null)
        store.put(keyP256dh(norm), null)
        store.put(keyAuth(norm), null)
        store.put(keyDeviceId(norm), null)
    }

    /**
     * Register with the installed UnifiedPush distributor for [instance].
     *
     * Returns `true` if a distributor was found and registration was initiated; `false` if
     * no distributor is installed.
     */
    fun registerWithUnifiedPush(
        context: Context,
        store: KeyValueStore = PrefsStore(context.applicationContext ?: context),
        instance: String = INSTANCE_DEFAULT,
        serverUrl: String? = null,
        vapidPublicKey: String? = null,
        distributorPackage: String? = null,
    ): Boolean {
        val distributors = availableDistributors(context)
        val chosen = distributorPackage?.takeIf { it in distributors }
            ?: runCatching { UnifiedPush.getSavedDistributor(context) }.getOrNull()?.takeIf { it in distributors }
            ?: distributors.firstOrNull()
            ?: return false

        val norm = normalizeInstance(instance)
        recordServerUrl(store, norm, serverUrl)
        setEnabled(store, true, norm)
        val cleanVapid = vapidPublicKey?.trim()?.replace("=", "")?.takeIf { VAPID_REGEX.matches(it) }

        return runCatching {
            UnifiedPush.saveDistributor(context, chosen)
            UnifiedPush.register(
                context = context,
                instance = norm,
                messageForDistributor = "PPM",
                vapid = cleanVapid,
            )
            true
        }.getOrElse { err ->
            recordFailure(store, norm, err.message ?: "REGISTER_ERROR")
            false
        }
    }

    /**
     * Unregister [instance] from UnifiedPush and remove the subscription from the PPM server.
     */
    suspend fun unregisterFromUnifiedPush(
        context: Context,
        store: KeyValueStore = PrefsStore(context.applicationContext ?: context),
        api: ApiClient? = null,
        instance: String = INSTANCE_DEFAULT,
    ) {
        val norm = normalizeInstance(instance)
        val endpoint = savedEndpoint(store, norm)
        val deviceId = savedDeviceId(store, norm)
        clearRegistration(store, norm)
        runCatching {
            UnifiedPush.unregister(context, norm)
        }
        if (api != null && (!endpoint.isNullOrBlank() || !deviceId.isNullOrBlank())) {
            runCatching {
                api.unsubscribePush(endpoint = endpoint, id = deviceId)
            }
        }
    }

    internal fun resolveServerProfile(
        store: KeyValueStore,
        secrets: SecretStore,
        instance: String,
    ): ServerProfile? {
        val serverStore = ServerStore(store, secrets)
        return serverStore.servers.value.firstOrNull { it.id == instance }
            ?: serverStore.selected
    }

    internal fun resolveServerUrl(
        context: Context,
        store: KeyValueStore,
        instance: String,
    ): String? {
        val norm = normalizeInstance(instance)
        store.get(keyServerUrl(norm))?.takeIf { it.isNotBlank() }?.let { return it }
        val profile = runCatching {
            resolveServerProfile(store, KeystoreSecrets(context), norm)
        }.getOrNull()
        return profile?.url
    }

    internal fun resolveApiClient(
        context: Context,
        store: KeyValueStore,
        instance: String,
    ): ApiClient? = runCatching {
        val secrets = KeystoreSecrets(context)
        val serverStore = ServerStore(store, secrets)
        val profile = serverStore.servers.value.firstOrNull { it.id == normalizeInstance(instance) }
            ?: serverStore.selected
            ?: return null
        val httpUrl = ApiClient.normalizedServerUrl(profile.url) ?: return null
        val token = serverStore.token(profile) ?: ""
        ApiClient(httpUrl, token)
    }.getOrNull()
}
