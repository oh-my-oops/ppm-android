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
import com.hienlh.ppm.app.push.AndroidFirebaseGateway
import com.hienlh.ppm.app.push.FcmEnrollment
import com.hienlh.ppm.app.push.FcmFallbackReason
import com.hienlh.ppm.app.push.FcmPushRegistration
import com.hienlh.ppm.app.push.FirebaseGateway
import com.hienlh.ppm.app.push.NotificationChannels
import com.hienlh.ppm.app.push.PushDeviceManager
import com.hienlh.ppm.app.push.PushTransport
import com.hienlh.ppm.app.push.choosePushTransport
import com.hienlh.ppm.app.ui.phone.settings.areSystemNotificationsEnabled
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.FcmAndroidConfig
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.defaultAndroidPushLabel
import com.hienlh.ppm.client.notificationsGetFcm
import com.hienlh.ppm.state.settings.PushDeviceRow
import kotlinx.coroutines.CancellationException
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

    /** Unregisters (UnifiedPush or FCM) and removes this device's subscription on the server. */
    suspend fun unregister(instance: String)

    /** Which way this device would push from [instance]'s server: Firebase or UnifiedPush. */
    suspend fun transport(instance: String): PushTransport =
        PushTransport.UnifiedPush(FcmFallbackReason.NOT_CONFIGURED)

    /** Whether [instance]'s server has this device through FCM. */
    fun enrolledViaFcm(instance: String): Boolean = false

    /** Subscribes this device through FCM; answers the subscription id, throws when it fails. */
    suspend fun registerFcm(instance: String, serverUrl: String?, config: FcmAndroidConfig): String =
        throw UnsupportedOperationException("Firebase is not available")
}

internal class UnifiedPushThisDeviceRegistrar(
    private val context: Context,
    private val prefs: KeyValueStore,
    private val api: ApiClient?,
    private val firebase: FirebaseGateway = AndroidFirebaseGateway(context),
) : PushThisDeviceRegistrar {
    private val fcm = FcmPushRegistration(prefs, firebase, defaultAndroidPushLabel(Build.MODEL))

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
        if (FcmEnrollment.isFcm(prefs, instance)) {
            fcm.unenroll(instance, api)
            return
        }
        PushDeviceManager.unregisterFromUnifiedPush(
            context = context,
            store = prefs,
            api = api,
            instance = instance,
        )
    }

    override suspend fun transport(instance: String): PushTransport {
        val server = try {
            api?.notificationsGetFcm()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        return choosePushTransport(server, firebase.playServicesAvailable(), firebase.existingProjectId())
    }

    override fun enrolledViaFcm(instance: String): Boolean = FcmEnrollment.isFcm(prefs, instance)

    override suspend fun registerFcm(instance: String, serverUrl: String?, config: FcmAndroidConfig): String {
        val client = api ?: throw IllegalStateException("Not connected to a server")
        NotificationChannels.ensureChannels(context)
        return fcm.enroll(instance, client, config, serverUrl)
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

    /** How the server would push to this device, once [resolveTransport] has asked it. */
    var transport by mutableStateOf<PushTransport?>(null)
        private set

    /** The server has this device through FCM. */
    var enrolledViaFcm by mutableStateOf(registrar.enrolledViaFcm(instance))
        private set

    /** Firebase carries this device's push: enrolled through it, or would be when switched on. */
    val viaFirebase: Boolean
        get() = if (enabledOnThisDevice) enrolledViaFcm else transport is PushTransport.Firebase

    /** The switch can be turned on: through Firebase or a UnifiedPush distributor. */
    val available: Boolean
        get() = viaFirebase || distributorAvailable

    /** What the "via …" subtitle names: Firebase, or the distributor. */
    val viaName: String?
        get() = if (viaFirebase) FIREBASE_NAME else distributorName

    /** The server offers Firebase, but this app already runs another server's Firebase project. */
    val firebaseInUseElsewhere: Boolean
        get() = !viaFirebase && (transport as? PushTransport.UnifiedPush)?.reason == FcmFallbackReason.OTHER_PROJECT

    /** Asks the server whether it pushes through Firebase (an older server answers no). */
    suspend fun resolveTransport(): PushTransport {
        val next = try {
            registrar.transport(instance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PushTransport.UnifiedPush(FcmFallbackReason.NOT_CONFIGURED)
        }
        transport = next
        return next
    }

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

    /** Registers this device with UnifiedPush; answers the status line to show. */
    fun enable(vapidPublicKey: String?): String {
        val ok = registrar.register(instance, serverUrl, vapidPublicKey)
        enabledOnThisDevice = ok
        return if (ok) ENABLED_STATUS else "No UnifiedPush distributor available"
    }

    /**
     * Registers this device through Firebase when the server offers it, else (or when Firebase
     * fails and a distributor is installed) through UnifiedPush; answers the status line to show.
     */
    suspend fun switchOn(vapidPublicKey: String?): String {
        val firebase = (transport ?: resolveTransport()) as? PushTransport.Firebase
            ?: return enable(vapidPublicKey)
        return try {
            savedDeviceId = registrar.registerFcm(instance, serverUrl, firebase.config)
            enrolledViaFcm = true
            enabledOnThisDevice = true
            ENABLED_STATUS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (distributorAvailable) {
                enable(vapidPublicKey)
            } else {
                enabledOnThisDevice = false
                "Could not register with Firebase: ${e.message ?: "unknown error"}"
            }
        }
    }

    /** Unregisters this device; answers the status line to show. */
    suspend fun disable(): String {
        enabledOnThisDevice = false
        registrar.unregister(instance)
        savedDeviceId = null
        enrolledViaFcm = false
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
        if (!available) return
        requestPermission { granted ->
            if (!granted) {
                permissionDenied = true
                return@requestPermission
            }
            permissionDenied = false
            scope.launch {
                onStatus(switchOn(vapidPublicKey))
                if (enabledOnThisDevice) awaitListed(reloadDevices)
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

    internal companion object {
        const val FIREBASE_NAME = "Firebase"
        const val ENABLED_STATUS = "Push notifications enabled on this device"
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

/**
 * The switch's "via …" line: [viaName] is "Firebase" or the distributor. When the server offers
 * Firebase but this app runs another server's project, the distributor line says so.
 */
fun pushViaSubtitle(target: String, viaName: String, firebaseInUseElsewhere: Boolean = false): String {
    val via = "Receive alerts on $target via $viaName"
    return if (firebaseInUseElsewhere) "$via (Firebase is in use by another server)" else via
}
