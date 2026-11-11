package com.hienlh.ppm.app.ui.phone.tools

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.cloudGetAlias
import com.hienlh.ppm.client.cloudGetLoginUrl
import com.hienlh.ppm.client.cloudGetStatus
import com.hienlh.ppm.client.cloudLink
import com.hienlh.ppm.client.cloudLogin
import com.hienlh.ppm.client.cloudLogout
import com.hienlh.ppm.client.cloudPollDeviceCode
import com.hienlh.ppm.client.cloudRequestDeviceCode
import com.hienlh.ppm.client.cloudSetAlias
import com.hienlh.ppm.client.cloudUnlink
import com.hienlh.ppm.client.namedTunnelStatus
import com.hienlh.ppm.client.tunnelGetStatus
import com.hienlh.ppm.client.tunnelStart
import com.hienlh.ppm.client.tunnelStop
import com.hienlh.ppm.client.tunnelsList
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.settings.CloudDeviceCodeFlowState
import com.hienlh.ppm.state.settings.CloudStatusState
import com.hienlh.ppm.state.settings.PublicTunnelState
import com.hienlh.ppm.state.settings.parseCloudDeviceCodePoll
import com.hienlh.ppm.state.settings.parseCloudDeviceCodeStart
import com.hienlh.ppm.state.settings.parseCloudLoginUrl
import com.hienlh.ppm.state.settings.parseCloudStatus
import com.hienlh.ppm.state.settings.parseNamedTunnelState
import com.hienlh.ppm.state.settings.parsePublicTunnelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.URI

/**
 * State of the phone's Cloud & Share sheet (Phone 7 Section 5), read from and written to the server the way the
 * pre-redesign `CloudSharePopover` and the web's `cloud-share-popover.tsx` do: `/api/cloud/status`, `/api/tunnel`,
 * `/api/cloud/alias`, `/api/tunnel/named/status` and `/api/tunnels`. Nothing is shown that the server did not say.
 */
@Stable
class PhoneCloudShareState {
    var loading: Boolean by mutableStateOf(true)
    var cloud: CloudStatusState? by mutableStateOf(null)
    var tunnel: PublicTunnelState? by mutableStateOf(null)
    var deviceCodeFlow: CloudDeviceCodeFlowState by mutableStateOf(CloudDeviceCodeFlowState())
    var confirmSignOut: Boolean by mutableStateOf(false)
    var confirmUnlink: Boolean by mutableStateOf(false)
    var confirmStopSharing: Boolean by mutableStateOf(false)

    /** The machine's permanent link name (`/api/cloud/alias`): null until read or when it has none. */
    var slug: String? by mutableStateOf(null)
    var aliasUnreadable: Boolean by mutableStateOf(false)
    var editingSlug: Boolean by mutableStateOf(false)
    var slugDraft: String by mutableStateOf("")
    var slugError: String? by mutableStateOf(null)

    /** The named tunnel is what serves PPM now (its hostname is permanent, on the user's own domain). */
    var namedLive: Boolean by mutableStateOf(false)
    var namedHostname: String? by mutableStateOf(null)
    var tempUrl: String? by mutableStateOf(null)
    var tempPid: Int? by mutableStateOf(null)

    /** The action in flight (`signin`, `link`, `unlink`, `signout`, `slug`, `start`, `stop`, `temp`), or null. */
    var busy: String? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)

    val loggedIn: Boolean get() = cloud?.loggedIn == true
    val linked: Boolean get() = loggedIn && cloud?.linked == true

    /** The public address PPM answers on: the running tunnel's, else the one the cloud has for this machine. */
    val shareUrl: String?
        get() = tunnel?.url?.takeIf { it.isNotBlank() } ?: cloud?.tunnelUrl?.takeIf { it.isNotBlank() }

    val localUrl: String? get() = tunnel?.localUrl?.takeIf { it.isNotBlank() }

    val cloudHost: String
        get() = (cloud?.cloudUrl ?: "").replace(Regex("^https?://"), "").trimEnd('/')

    val permanentUrl: String?
        get() = slug?.takeIf { it.isNotBlank() }?.let { "${(cloud?.cloudUrl ?: "").trimEnd('/')}/$it" }

    val isSlugDraftValid: Boolean get() = isValidSlug(slugDraft.trim().lowercase())

    val slugValidationMessage: String = SLUG_HINT

    fun startEditSlug() {
        slugDraft = slug ?: cloud?.deviceName.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)
            .takeIf { isValidSlug(it) }.orEmpty()
        slugError = null
        editingSlug = true
    }

    fun cancelEditSlug() {
        editingSlug = false
        slugError = null
    }

    suspend fun load(api: ApiClient) {
        try {
            cloud = parseCloudStatus(api.cloudGetStatus())
            tunnel = parsePublicTunnelState(api.tunnelGetStatus())
            error = null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            error = t.message ?: "Could not read the sharing status"
        }
        if (linked) loadAlias(api) else slug = null
        loadNamed(api)
        loading = false
    }

    private suspend fun loadAlias(api: ApiClient) {
        try {
            val res = api.cloudGetAlias()
            slug = res["slug"].string ?: res.obj?.get("alias")?.obj?.get("slug").string
            aliasUnreadable = false
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            aliasUnreadable = true
        }
    }

    private suspend fun loadNamed(api: ApiClient) {
        try {
            val s = parseNamedTunnelState(api.namedTunnelStatus())
            val live = s.authEnabled && !s.hostname.isNullOrBlank() && s.liveMode.ifBlank { s.mode } == "named"
            namedLive = live
            namedHostname = s.hostname?.takeIf { live }
            if (!live) {
                tempUrl = null
                tempPid = null
                return
            }
            val namedUrl = "https://${s.hostname}"
            val port = publicPort()
            val found = api.tunnelsList().array.orEmpty().firstOrNull { entry ->
                entry["source"].string == "ppm" &&
                    entry["protected"].bool != true &&
                    entry["port"].int == port &&
                    !entry["url"].string.isNullOrBlank() &&
                    entry["url"].string != namedUrl
            }
            tempPid = found?.get("pid")?.int
            tempUrl = found?.get("url")?.string
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
    }

    private fun publicPort(): Int? {
        val u = tunnel?.localUrl ?: return null
        return runCatching { URI(u).port.takeIf { it > 0 } }.getOrNull()
    }

    private suspend fun act(name: String, fallback: String, block: suspend () -> Unit) {
        busy = name
        error = null
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            error = t.message?.takeIf { it.isNotBlank() } ?: fallback
        } finally {
            busy = null
        }
    }

    /** Device-code sign-in (`cloud-share-popover.tsx`): asks the cloud for a code; [pollDeviceCode] waits for it. */
    suspend fun signIn(api: ApiClient) = act("signin", "Failed to start login") {
        val cloudUrl = runCatching { parseCloudLoginUrl(api.cloudGetLoginUrl()) }
            .getOrDefault(cloud?.cloudUrl?.ifBlank { DEFAULT_CLOUD_URL } ?: DEFAULT_CLOUD_URL)
        deviceCodeFlow = parseCloudDeviceCodeStart(api.cloudRequestDeviceCode(cloudUrl), cloudUrl)
    }

    /** Polls the device code until it is approved or expires, then saves the login on the server. */
    suspend fun pollDeviceCode(api: ApiClient) {
        val active = deviceCodeFlow
        if (active.status != "waiting" || active.deviceCode.isBlank()) return
        val intervalMs = active.intervalSec.coerceAtLeast(1) * 1_000L
        val deadline = System.currentTimeMillis() + active.expiresInSec.coerceAtLeast(10) * 1_000L
        while (deviceCodeFlow.status == "waiting" && deviceCodeFlow.deviceCode == active.deviceCode) {
            delay(intervalMs)
            if (System.currentTimeMillis() > deadline) {
                deviceCodeFlow = CloudDeviceCodeFlowState()
                error = "Login expired. Try again."
                return
            }
            val poll = try {
                parseCloudDeviceCodePoll(api.cloudPollDeviceCode(active.cloudUrl, active.deviceCode))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            } ?: continue
            if (poll.status == "approved" && !poll.accessToken.isNullOrBlank() && !poll.email.isNullOrBlank()) {
                try {
                    api.cloudLogin(accessToken = poll.accessToken!!, email = poll.email!!, cloudUrl = active.cloudUrl)
                    deviceCodeFlow = CloudDeviceCodeFlowState()
                    cloud = parseCloudStatus(api.cloudGetStatus())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    deviceCodeFlow = CloudDeviceCodeFlowState()
                    error = t.message ?: "Failed to save login"
                }
                return
            }
        }
    }

    /** Signing out of a linked machine unlinks it first, as the popover does. */
    suspend fun signOut(api: ApiClient) = act("signout", "Could not sign out") {
        confirmSignOut = false
        if (linked) {
            try {
                api.cloudUnlink()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // The web signs out even when the unlink fails.
            }
        }
        api.cloudLogout()
        cloud = cloud?.copy(loggedIn = false, email = null, linked = false, deviceName = null, deviceId = null)
        slug = null
    }

    /** Links this machine; a link needs a running tunnel, so one is started first when none is. */
    suspend fun link(api: ApiClient) = act("link", "Failed to link device") {
        if (tunnel?.active != true) {
            val started = api.tunnelStart()
            tunnel = PublicTunnelState(active = true, url = started["url"].string, localUrl = tunnel?.localUrl, enabled = true)
        }
        api.cloudLink()
        cloud = parseCloudStatus(api.cloudGetStatus())
        if (linked) loadAlias(api)
    }

    suspend fun unlink(api: ApiClient) = act("unlink", "Could not unlink this device") {
        confirmUnlink = false
        api.cloudUnlink()
        cloud = parseCloudStatus(api.cloudGetStatus())
        slug = null
    }

    suspend fun saveSlug(api: ApiClient) {
        val next = slugDraft.trim().lowercase()
        if (!isValidSlug(next)) {
            slugError = SLUG_HINT
            return
        }
        busy = "slug"
        slugError = null
        try {
            val res = api.cloudSetAlias(next)
            slug = res["slug"].string ?: next
            editingSlug = false
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            slugError = t.message ?: "Could not save that name"
        } finally {
            busy = null
        }
    }

    /** `POST /api/tunnel/start`: the quick public tunnel the web's Start Sharing raises. */
    suspend fun startSharing(api: ApiClient) = act("start", "Failed to start tunnel") {
        val res = api.tunnelStart()
        tunnel = PublicTunnelState(active = true, url = res["url"].string, localUrl = tunnel?.localUrl, enabled = true)
        cloud = parseCloudStatus(api.cloudGetStatus())
    }

    /** `POST /api/tunnel/stop`: ends the quick public tunnel. */
    suspend fun stopSharing(api: ApiClient) = act("stop", "Could not stop sharing") {
        confirmStopSharing = false
        api.tunnelStop()
        tunnel = parsePublicTunnelState(api.tunnelGetStatus())
        cloud = parseCloudStatus(api.cloudGetStatus())
    }

    /** A temporary quick link next to a live named tunnel (`CloudShareNamedTunnelRow`). */
    suspend fun startTempLink(api: ApiClient) = act("temp", "Could not create a temporary link") {
        val port = publicPort() ?: return@act
        val res = api.tunnelStart(port = port)
        tempUrl = res["url"].string
        loadNamed(api)
    }

    suspend fun stopTempLink(api: ApiClient) = act("temp", "Could not stop the temporary link") {
        val pid = tempPid ?: return@act
        api.tunnelStop(pid)
        tempPid = null
        tempUrl = null
    }

    companion object {
        const val DEFAULT_CLOUD_URL = "https://ppm.bot"
        const val SLUG_HINT = "2–32 characters: lowercase letters, numbers, hyphens."
        private val SLUG_REGEX = Regex("^[a-z0-9][a-z0-9-]{1,31}$")

        fun isValidSlug(candidate: String): Boolean = SLUG_REGEX.matches(candidate.trim())
    }
}
