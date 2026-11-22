package com.hienlh.ppm.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.push.NotificationChannels
import com.hienlh.ppm.app.push.PushDeviceManager
import com.hienlh.ppm.app.ui.phone.settings.areSystemNotificationsEnabled
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.state.settings.PushDeviceRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What "push notifications on this device" needs from the platform: the UnifiedPush
 * distributors, the local registration and the server subscription. [PushDeviceManager]
 * is the real one; tests pass a fake.
 */
interface PushThisDeviceRegistrar {
    fun distributors(): List<String>
    fun isEnabled(instance: String): Boolean
    fun savedDeviceId(instance: String): String?

    /** Starts the UnifiedPush registration; `false` when no distributor took it. */
    fun register(instance: String, serverUrl: String?, vapidPublicKey: String?): Boolean

    /** Unregisters from UnifiedPush and removes this device's subscription on the server. */
    suspend fun unregister(instance: String)
}

internal class UnifiedPushThisDeviceRegistrar(
    private val context: Context,
    private val prefs: KeyValueStore,
    private val api: ApiClient?,
) : PushThisDeviceRegistrar {
    override fun distributors(): List<String> = PushDeviceManager.availableDistributors(context)
    override fun isEnabled(instance: String): Boolean = PushDeviceManager.isEnabled(prefs, instance)
    override fun savedDeviceId(instance: String): String? = PushDeviceManager.savedDeviceId(prefs, instance)

    override fun register(instance: String, serverUrl: String?, vapidPublicKey: String?): Boolean {
        NotificationChannels.ensureChannels(context)
        return PushDeviceManager.registerWithUnifiedPush(
            context = context,
            store = prefs,
            instance = instance,
            serverUrl = serverUrl,
            vapidPublicKey = vapidPublicKey,
        )
    }

    override suspend fun unregister(instance: String) {
        PushDeviceManager.unregisterFromUnifiedPush(
            context = context,
            store = prefs,
            api = api,
            instance = instance,
        )
    }
}

/**
 * State and actions of the "Push notifications on this device" switch, shared by the phone's
 * Notifications page and the tablet's Notifications pane: notification permission (the
 * POST_NOTIFICATIONS request), the distributor, register/unregister and the saved device id.
 */
@Stable
class PushThisDevice internal constructor(
    val instance: String,
    private val registrar: PushThisDeviceRegistrar,
    private val notificationsEnabledOverride: Boolean? = null,
    distributorAvailableOverride: Boolean? = null,
    private val hasPermission: () -> Boolean = { true },
) {
    internal var serverUrl: String? = null

    /** `true` when the system must ask for the runtime permission first (Android 13+). */
    internal var needsRuntimeRequest: () -> Boolean = { false }

    /** Launches the system request; `false` when it could not be launched. */
    internal var launchRuntimeRequest: () -> Boolean = { false }

    private var pendingPermission: ((Boolean) -> Unit)? = null

    private val distributors: List<String> = registrar.distributors()

    val distributorAvailable: Boolean = distributorAvailableOverride ?: distributors.isNotEmpty()
    val distributorName: String? = distributors.firstOrNull()?.let(PushDeviceManager::distributorDisplayName)

    var permissionGranted by mutableStateOf(notificationsEnabledOverride ?: hasPermission())
        private set

    var enabledOnThisDevice by mutableStateOf(registrar.isEnabled(instance))
        private set

    var savedDeviceId by mutableStateOf(registrar.savedDeviceId(instance))
        private set

    /** The last switch-on was refused by the notification permission. */
    var permissionDenied by mutableStateOf(false)
        private set

    /** Re-reads the device id the server gave this device's subscription. */
    fun refreshSavedDeviceId(): String? = registrar.savedDeviceId(instance).also { savedDeviceId = it }

    /** Asks for notification permission when needed; [callback] gets whether notifications may show. */
    fun requestPermission(callback: (Boolean) -> Unit) {
        notificationsEnabledOverride?.let {
            callback(it)
            return
        }
        if (needsRuntimeRequest()) {
            pendingPermission = callback
            if (!launchRuntimeRequest()) {
                pendingPermission = null
                callback(false)
            }
        } else {
            val allowed = hasPermission()
            permissionGranted = allowed
            callback(allowed)
        }
    }

    internal fun onPermissionResult(effective: Boolean) {
        permissionGranted = effective
        val callback = pendingPermission
        pendingPermission = null
        callback?.invoke(effective)
    }

    /** Registers this device; answers the status line to show. */
    fun enable(vapidPublicKey: String?): String {
        val ok = registrar.register(instance, serverUrl, vapidPublicKey)
        enabledOnThisDevice = ok
        return if (ok) "Push notifications enabled on this device" else "No UnifiedPush distributor available"
    }

    /** Unregisters this device; answers the status line to show. */
    suspend fun disable(): String {
        enabledOnThisDevice = false
        registrar.unregister(instance)
        savedDeviceId = null
        return "Push notifications turned off on this device"
    }

    /**
     * The whole switch, as the tablet pane runs it: permission first, then register (and wait
     * for the server to list this device) or unregister. [reloadDevices] reloads the server's
     * device list and answers it.
     */
    fun toggle(
        wantEnabled: Boolean,
        vapidPublicKey: String?,
        scope: CoroutineScope,
        onStatus: (String) -> Unit,
        reloadDevices: suspend () -> List<PushDeviceRow>?,
    ) {
        if (!wantEnabled) {
            // Also with no distributor left: this device's server row can still be removed.
            scope.launch {
                onStatus(disable())
                reloadDevices()
            }
            return
        }
        if (!distributorAvailable) return
        requestPermission { granted ->
            if (!granted) {
                permissionDenied = true
                return@requestPermission
            }
            permissionDenied = false
            onStatus(enable(vapidPublicKey))
            if (enabledOnThisDevice) {
                scope.launch { awaitListed(reloadDevices) }
            }
        }
    }

    /**
     * The distributor hands the endpoint over asynchronously and [com.hienlh.ppm.app.push.UnifiedPushReceiver]
     * subscribes it on the server afterwards, so the device list is reloaded until it holds this device.
     */
    private suspend fun awaitListed(reloadDevices: suspend () -> List<PushDeviceRow>?) {
        repeat(LIST_POLL_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(LIST_POLL_INTERVAL_MS)
            if (!enabledOnThisDevice) return
            val id = refreshSavedDeviceId()
            val devices = reloadDevices()
            if (id != null && devices?.any { it.id == id } == true) return
        }
    }

    private companion object {
        const val LIST_POLL_ATTEMPTS = 20
        const val LIST_POLL_INTERVAL_MS = 1_000L
    }
}

/**
 * [PushThisDevice] for the selected server [instance], with the system permission request wired
 * to an activity-result launcher.
 */
@Composable
fun rememberPushThisDevice(
    api: ApiClient?,
    instance: String,
    serverUrl: String?,
    notificationsEnabledOverride: Boolean? = null,
    distributorAvailableOverride: Boolean? = null,
): PushThisDevice {
    val context = LocalContext.current
    val prefs = remember(context) { PrefsStore(context.applicationContext ?: context) }
    val registrar = remember(context, prefs, api) { UnifiedPushThisDeviceRegistrar(context, prefs, api) }
    val push = remember(instance, registrar, notificationsEnabledOverride, distributorAvailableOverride) {
        PushThisDevice(
            instance = instance,
            registrar = registrar,
            notificationsEnabledOverride = notificationsEnabledOverride,
            distributorAvailableOverride = distributorAvailableOverride,
            hasPermission = { NotificationChannels.hasNotificationPermission(context) },
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        push.onPermissionResult(granted && areSystemNotificationsEnabled(context))
    }
    push.serverUrl = serverUrl
    push.needsRuntimeRequest = {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationChannels.isRuntimePermissionGranted(context)
    }
    push.launchRuntimeRequest = {
        runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }.isSuccess
    }
    return push
}
