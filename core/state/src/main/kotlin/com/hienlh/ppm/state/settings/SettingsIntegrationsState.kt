package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ===========================================================================
// 1. Notifications State & Validators
// ===========================================================================

data class NotificationEventDef(
    val key: String,
    val label: String,
    val description: String,
)

val NOTIFICATION_EVENT_DEFS: List<NotificationEventDef> = listOf(
    NotificationEventDef("done", "Task finished", "When an AI chat turn completes"),
    NotificationEventDef("approval_request", "Permission request", "When a tool needs your approval to run"),
    NotificationEventDef("question", "Question asked", "When the assistant asks a clarifying question"),
    NotificationEventDef("schedule", "Scheduled run", "When a scheduled task completes or fails"),
    NotificationEventDef("jira", "Jira watcher", "When a Jira watcher pulls or debugs an issue"),
    NotificationEventDef("design_review", "Design review", "When a Design Canvas review needs input"),
)

val NOTIFICATION_DELAY_OPTIONS: List<Pair<Int, String>> = listOf(
    0 to "Immediately",
    30 to "After 30s idle",
    60 to "After 1m idle",
    120 to "After 2m idle",
    300 to "After 5m idle",
)

data class NotificationSettingsState(
    val events: Map<String, Boolean> = NOTIFICATION_EVENT_DEFS.associate { it.key to true },
    val delaySeconds: Int = 60,
    val telegram: Boolean = true,
    val ntfy: Boolean = true,
) {
    fun toJsonPatch(): JsonObject = buildJsonObject {
        put(
            "events",
            buildJsonObject {
                events.forEach { (k, v) -> put(k, v) }
            },
        )
        put("delay_seconds", delaySeconds)
        put("telegram", telegram)
        put("ntfy", ntfy)
    }
}

fun parseNotificationSettings(element: JsonElement?): NotificationSettingsState {
    val root = element?.obj ?: return NotificationSettingsState()
    val evObj = root["events"]?.obj
    val events = NOTIFICATION_EVENT_DEFS.associate { def ->
        def.key to (evObj?.get(def.key)?.bool ?: true)
    }
    val rawDelay = root["delay_seconds"]?.int ?: 60
    val delaySeconds = if (NOTIFICATION_DELAY_OPTIONS.any { it.first == rawDelay }) rawDelay else 60
    return NotificationSettingsState(
        events = events,
        delaySeconds = delaySeconds,
        telegram = root["telegram"]?.bool ?: true,
        ntfy = root["ntfy"]?.bool ?: true,
    )
}

data class PushDeviceRow(
    val id: String,
    val label: String,
    val endpoint: String,
    val origin: String? = null,
    val createdAt: String? = null,
    val lastSuccessAt: String? = null,
    val lastError: String? = null,
)

data class PushStatusState(
    val publicKey: String? = null,
    val devices: List<PushDeviceRow> = emptyList(),
)

fun parsePushStatus(element: JsonElement?): PushStatusState {
    val root = element?.obj ?: return PushStatusState()
    val devices = root["devices"]?.array.orEmpty().mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val id = obj["id"].string ?: return@mapNotNull null
        PushDeviceRow(
            id = id,
            label = obj["label"].string ?: "Browser device",
            endpoint = obj["endpoint"].string.orEmpty(),
            origin = obj["origin"].string,
            createdAt = obj["createdAt"].string,
            lastSuccessAt = obj["lastSuccessAt"].string,
            lastError = obj["lastError"].string,
        )
    }
    return PushStatusState(
        publicKey = root["publicKey"].string,
        devices = devices,
    )
}

data class TelegramNotifyState(
    val configured: Boolean = false,
    val botUsername: String? = null,
    val sharedWithPpmBot: Boolean = false,
    val chats: List<TelegramPairedChat> = emptyList(),
    val connectActive: Boolean = false,
    val connectUrl: String? = null,
    val connectExpiresAt: String? = null,
    val connectError: String? = null,
)

fun parseTelegramNotifyStatus(element: JsonElement?): TelegramNotifyState {
    val root = element?.obj ?: return TelegramNotifyState()
    val chats = root["chats"]?.array.orEmpty().mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val chatId = obj["chatId"].string ?: obj["chat_id"].string ?: return@mapNotNull null
        TelegramPairedChat(
            chatId = chatId,
            name = obj["name"].string ?: obj["title"].string ?: chatId,
            username = obj["username"].string,
        )
    }
    val conn = root["connect"]?.obj
    return TelegramNotifyState(
        configured = root["configured"]?.bool ?: false,
        botUsername = root["botUsername"].string,
        sharedWithPpmBot = root["sharedWithPPMBot"]?.bool ?: false,
        chats = chats,
        connectActive = conn?.get("active")?.bool ?: false,
        connectUrl = conn?.get("url").string ?: root["url"].string,
        connectExpiresAt = conn?.get("expiresAt").string,
        connectError = conn?.get("error").string,
    )
}

data class NtfyStatusState(
    val configured: Boolean = false,
    val server: String = "https://ntfy.sh",
    val topic: String = "",
    val tokenSet: Boolean = false,
)

fun parseNtfyStatus(element: JsonElement?): NtfyStatusState {
    val root = element?.obj ?: return NtfyStatusState()
    return NtfyStatusState(
        configured = root["configured"]?.bool ?: false,
        server = root["server"].string?.ifBlank { "https://ntfy.sh" } ?: "https://ntfy.sh",
        topic = root["topic"].string.orEmpty(),
        tokenSet = root["tokenSet"]?.bool ?: false,
    )
}

private val NTFY_TOPIC_REGEX = Regex("^[-_A-Za-z0-9]{1,64}$")

/**
 * Mirrors `normalizeNtfyServer(server)` from `src/shared/notification-settings.ts`:
 * requires `http` or `https` scheme, non-blank host, no credentials/query/hash, and strips trailing `/`.
 */
fun normalizeNtfyServer(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host ?: return null
    if (host.isBlank()) return null
    if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val portPart = if (uri.port != -1) ":${uri.port}" else ""
    val pathPart = (uri.rawPath ?: "").trimEnd('/')
    return "$scheme://$host$portPart$pathPart"
}

/**
 * Validates an ntfy topic (`^[-_A-Za-z0-9]{1,64}$`). Returns an error message or `null` when valid.
 */
fun validateNtfyTopic(topic: String): String? {
    val trimmed = topic.trim()
    if (trimmed.isEmpty()) return "Topic is required"
    if (!NTFY_TOPIC_REGEX.matches(trimmed)) {
        return "Topic must be 1–64 letters, digits, '-' or '_'"
    }
    return null
}

// ===========================================================================
// 2. Remote Access: Tailscale, Public Tunnel, Named Tunnel
// ===========================================================================

enum class TailscaleStepId(val id: String, val title: String) {
    Install("install", "Install Tailscale"),
    Start("start", "Start Tailscale daemon"),
    Operator("operator", "Grant operator access"),
    SignIn("sign-in", "Sign in to Tailscale"),
    Dns("dns", "Enable MagicDNS & HTTPS"),
    Tag("tag", "Tag device for Services"),
    Service("service", "Enable PPM Service"),
}

data class TailscaleLoginState(
    val state: String = "idle", // "idle" | "starting" | "waiting" | "done" | "error" | "cancelled"
    val url: String? = null,
    val message: String? = null,
)

data class TailscaleDeviceInfo(
    val name: String = "",
    val dnsName: String? = null,
    val ips: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

data class TailscaleServiceInfo(
    val name: String = "ppm",
    val url: String? = null,
    val defined: Boolean = false,
    val approved: Boolean = false,
    val advertised: Boolean = false,
    val target: String? = null,
    val pointsAtPpm: Boolean = false,
)

data class TailscaleState(
    val installed: Boolean = false,
    val backendState: String? = null, // "NoState" | "NeedsLogin" | "NeedsMachineAuth" | "Stopped" | "Starting" | "Running" | null
    val canManage: Boolean? = null,
    val osUser: String = "",
    val platform: String = "linux", // "linux" | "darwin" | "win32" | "other"
    val tailnet: String? = null,
    val dnsSuffix: String? = null,
    val magicDns: Boolean = false,
    val httpsCertificates: Boolean = false,
    val device: TailscaleDeviceInfo? = null,
    val service: TailscaleServiceInfo = TailscaleServiceInfo(),
    val enabled: Boolean = false,
    val ppmPort: Int = 8080,
    val login: TailscaleLoginState = TailscaleLoginState(),
    val authEnabled: Boolean = false,
) {
    val operatorCommand: String?
        get() = if (canManage == false && platform == "linux") {
            "sudo tailscale set --operator=${osUser.ifBlank { "\$USER" }}"
        } else {
            null
        }
}

fun parseTailscaleLoginSnapshot(element: JsonElement?): TailscaleLoginState {
    val obj = element?.obj ?: return TailscaleLoginState()
    return TailscaleLoginState(
        state = obj["state"].string ?: "idle",
        url = obj["url"].string,
        message = obj["message"].string,
    )
}

fun parseTailscaleState(element: JsonElement?): TailscaleState {
    val root = element?.obj ?: return TailscaleState()
    val devObj = root["device"]?.obj
    val svcObj = root["service"]?.obj
    return TailscaleState(
        installed = root["installed"]?.bool ?: false,
        backendState = root["backendState"].string,
        canManage = root["canManage"]?.bool,
        osUser = root["osUser"].string.orEmpty(),
        platform = root["platform"].string ?: "linux",
        tailnet = root["tailnet"].string,
        dnsSuffix = root["dnsSuffix"].string?.trimEnd('.'),
        magicDns = root["magicDns"]?.bool ?: false,
        httpsCertificates = root["httpsCertificates"]?.bool ?: false,
        device = devObj?.let { dev ->
            TailscaleDeviceInfo(
                name = dev["name"].string.orEmpty(),
                dnsName = dev["dnsName"].string?.trimEnd('.'),
                ips = dev["ips"]?.array.orEmpty().mapNotNull { it.string },
                tags = dev["tags"]?.array.orEmpty().mapNotNull { it.string },
            )
        },
        service = TailscaleServiceInfo(
            name = svcObj?.get("name").string ?: "ppm",
            url = svcObj?.get("url").string,
            defined = svcObj?.get("defined")?.bool ?: false,
            approved = svcObj?.get("approved")?.bool ?: false,
            advertised = svcObj?.get("advertised")?.bool ?: false,
            target = svcObj?.get("target").string,
            pointsAtPpm = svcObj?.get("pointsAtPpm")?.bool ?: false,
        ),
        enabled = root["enabled"]?.bool ?: false,
        ppmPort = root["ppmPort"]?.int ?: 8080,
        login = parseTailscaleLoginSnapshot(root["login"]),
        authEnabled = root["authEnabled"]?.bool ?: false,
    )
}

/**
 * Applies either a full `TailscaleSettingsState` or a partial `TailscaleLoginSnapshot`
 * returned by `POST /api/tailscale/login` and `POST /api/tailscale/login/cancel`.
 */
fun applyTailscaleLoginSnapshot(current: TailscaleState, element: JsonElement?): TailscaleState {
    val obj = element?.obj ?: return current
    if ("installed" in obj || "service" in obj) return parseTailscaleState(element)
    return current.copy(login = parseTailscaleLoginSnapshot(element))
}

/**
 * Mirrors `currentSetupStep(s)` in `src/shared/tailscale-setup.ts`:
 * `"install" -> "start" -> "operator" -> "sign-in" -> "dns" -> "tag" -> "service"`.
 */
fun currentTailscaleSetupStep(s: TailscaleState): TailscaleStepId? {
    if (!s.installed) return TailscaleStepId.Install
    if (s.backendState == null || s.backendState == "Stopped" || s.backendState == "NoState") {
        return TailscaleStepId.Start
    }
    if (s.canManage == false) return TailscaleStepId.Operator
    if (s.backendState == "NeedsLogin" || s.backendState == "NeedsMachineAuth" || s.backendState != "Running") {
        return TailscaleStepId.SignIn
    }
    if (!s.magicDns || !s.httpsCertificates) return TailscaleStepId.Dns
    if (s.device == null || s.device.tags.isEmpty()) return TailscaleStepId.Tag
    if (!s.enabled || !s.service.pointsAtPpm || !s.service.approved) return TailscaleStepId.Service
    return null
}

/**
 * Mirrors `blockingSetupStep(s)` in `src/shared/tailscale-setup.ts`:
 * returns `currentTailscaleSetupStep(s)` except `Service` (which returns `null`).
 */
fun blockingTailscaleSetupStep(s: TailscaleState): TailscaleStepId? {
    val step = currentTailscaleSetupStep(s)
    return if (step == TailscaleStepId.Service) null else step
}

private val SERVICE_NAME_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

/**
 * Mirrors `serviceNameProblem(name)` in `src/shared/tailscale-setup.ts`.
 */
fun validateTailscaleServiceName(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty()) return "Service name is required"
    if (name.length > 63) return "Service name must be at most 63 characters"
    if (!SERVICE_NAME_REGEX.matches(name)) {
        return "Use lowercase letters, numbers, and hyphens (cannot start or end with a hyphen)"
    }
    return null
}

fun tailscaleServiceUrl(s: TailscaleState, draftName: String = s.service.name): String? {
    val cleanName = draftName.trim().takeIf { validateTailscaleServiceName(it) == null } ?: return null
    val suffix = s.dnsSuffix?.trim('.')?.takeIf { it.isNotEmpty() }
    if (suffix != null) return "https://$cleanName.$suffix"
    if (!s.service.url.isNullOrBlank() && cleanName == s.service.name) return s.service.url
    return null
}

/**
 * Checks whether the current server URL matches a given hostname or IPv4 address.
 */
fun isConnectedViaAddress(currentServerUrl: String?, addressOrHost: String?): Boolean {
    if (currentServerUrl.isNullOrBlank() || addressOrHost.isNullOrBlank()) return false
    val cleanTarget = addressOrHost
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
        .trimEnd('.')
        .lowercase()
    val currentHost = runCatching { URI(currentServerUrl).host }.getOrNull()
        ?.trimEnd('.')
        ?.lowercase()
        ?: return false
    return cleanTarget.isNotEmpty() && currentHost == cleanTarget
}

data class PublicTunnelState(
    val active: Boolean = false,
    val url: String? = null,
    val localUrl: String? = null,
    val enabled: Boolean = false,
)

fun parsePublicTunnelState(element: JsonElement?): PublicTunnelState {
    val root = element?.obj ?: return PublicTunnelState()
    return PublicTunnelState(
        active = root["active"]?.bool ?: false,
        url = root["url"].string,
        localUrl = root["localUrl"].string,
        enabled = root["enabled"]?.bool ?: false,
    )
}

/**
 * Applies `POST /api/tunnel/enabled` (`{ enabled, reload }`) without wiping `active`, `url`, or `localUrl`
 * when enabling, while clearing `active` and `url` when disabling.
 */
fun applyPublicTunnelEnabledResult(current: PublicTunnelState, element: JsonElement?): PublicTunnelState {
    val root = element?.obj ?: return current
    if ("active" in root || "localUrl" in root) return parsePublicTunnelState(element)
    val enabled = root["enabled"]?.bool ?: current.enabled
    return if (enabled) {
        current.copy(enabled = true)
    } else {
        current.copy(enabled = false, active = false, url = null)
    }
}

data class NamedTunnelState(
    val mode: String = "quick", // "quick" | "named"
    val liveMode: String = "quick",
    val hostname: String? = null,
    val tunnelName: String? = null,
    val tokenMasked: String? = null,
    val certState: String = "missing", // "missing" | "valid" | "expired"
    val dismissed: Boolean = false,
    val loginState: String = "idle", // "idle" | "starting" | "waiting" | "slow" | "success" | "timeout" | "cancelled" | "error"
    val loginUrl: String? = null,
    val loginError: String? = null,
    val tunnelWarning: String? = null,
    val authEnabled: Boolean = true,
)

fun parseNamedTunnelState(element: JsonElement?): NamedTunnelState {
    val root = element?.obj ?: return NamedTunnelState()
    val login = root["login"]?.obj
    return NamedTunnelState(
        mode = root["mode"].string ?: "quick",
        liveMode = root["liveMode"].string ?: "quick",
        hostname = root["hostname"].string,
        tunnelName = root["tunnelName"].string,
        tokenMasked = root["tokenMasked"].string,
        certState = root["certState"].string ?: "missing",
        dismissed = root["dismissed"]?.bool ?: false,
        loginState = login?.get("state").string ?: "idle",
        loginUrl = login?.get("url").string,
        loginError = login?.get("message").string ?: login?.get("error").string,
        tunnelWarning = root["tunnelWarning"].string,
        authEnabled = root["authEnabled"]?.bool ?: true,
    )
}

/**
 * Applies `POST /api/tunnel/named/login` (`{ state, url, message }`) or
 * `POST /api/tunnel/named/login/cancel` (`{ state: "cancelled" }`) onto [current]
 * without wiping `mode`, `certState`, `hostname`, `tunnelName`, or `authEnabled`.
 */
fun applyNamedTunnelLoginSnapshot(current: NamedTunnelState, element: JsonElement?): NamedTunnelState {
    val root = element?.obj ?: return current
    if ("login" in root || "certState" in root) return parseNamedTunnelState(element)
    val nextState = root["state"].string ?: current.loginState
    val nextUrl = when {
        "url" in root -> root["url"].string
        nextState == "cancelled" || nextState == "idle" -> null
        else -> current.loginUrl
    }
    val nextError = when {
        "message" in root || "error" in root -> root["message"].string ?: root["error"].string
        nextState == "cancelled" || nextState == "idle" -> null
        else -> current.loginError
    }
    return current.copy(
        loginState = nextState,
        loginUrl = nextUrl,
        loginError = nextError,
        certState = if (nextState == "success") "valid" else current.certState,
    )
}

/**
 * Applies `POST /api/tunnel/named/setup` (`{ hostname, tunnelName, pending?, message? }`) onto [current].
 */
fun applyNamedTunnelSetupResult(current: NamedTunnelState, element: JsonElement?): NamedTunnelState {
    val root = element?.obj ?: return current
    if ("certState" in root || "login" in root) return parseNamedTunnelState(element)
    val pending = root["pending"]?.bool ?: false
    return current.copy(
        mode = if (pending) current.mode else "named",
        hostname = root["hostname"].string ?: current.hostname,
        tunnelName = root["tunnelName"].string ?: current.tunnelName,
        tunnelWarning = root["message"].string ?: current.tunnelWarning,
    )
}

/**
 * Applies `POST /api/tunnel/named/disable` (`{ mode: "quick" }`) onto [current].
 */
fun applyNamedTunnelDisableResult(current: NamedTunnelState, element: JsonElement?): NamedTunnelState {
    val root = element?.obj ?: return current
    if ("certState" in root || "login" in root) return parseNamedTunnelState(element)
    return current.copy(
        mode = root["mode"].string ?: "quick",
        liveMode = root["mode"].string ?: "quick",
    )
}

// ===========================================================================
// 3. Cloud & Share, Proxy, Extensions
// ===========================================================================

data class CloudStatusState(
    val loggedIn: Boolean = false,
    val email: String? = null,
    val cloudUrl: String = "https://ppm.bot",
    val linked: Boolean = false,
    val deviceName: String? = null,
    val deviceId: String? = null,
    val tunnelActive: Boolean = false,
    val tunnelUrl: String? = null,
)

fun parseCloudStatus(element: JsonElement?): CloudStatusState {
    val root = element?.obj ?: return CloudStatusState()
    return CloudStatusState(
        loggedIn = root["logged_in"]?.bool ?: false,
        email = root["email"].string,
        cloudUrl = root["cloud_url"].string ?: "https://ppm.bot",
        linked = root["linked"]?.bool ?: false,
        deviceName = root["device_name"].string,
        deviceId = root["device_id"].string,
        tunnelActive = root["tunnel_active"]?.bool ?: false,
        tunnelUrl = root["tunnel_url"].string,
    )
}

data class CloudDeviceCodeFlowState(
    val deviceCode: String = "",
    val userCode: String = "",
    val verificationUri: String = "",
    val cloudUrl: String = "https://ppm.bot",
    val expiresInSec: Int = 600,
    val intervalSec: Int = 5,
    val status: String = "idle", // "idle" | "starting" | "waiting" | "approved" | "expired" | "error"
    val error: String? = null,
)

data class CloudDeviceCodePollState(
    val status: String = "pending", // "pending" | "approved" | "expired" | "error"
    val accessToken: String? = null,
    val email: String? = null,
    val error: String? = null,
)

fun parseCloudLoginUrl(element: JsonElement?): String {
    val root = element?.obj ?: return "https://ppm.bot"
    return root["cloud_url"].string?.ifBlank { "https://ppm.bot" } ?: "https://ppm.bot"
}

fun parseCloudDeviceCodeStart(element: JsonElement?, cloudUrl: String = "https://ppm.bot"): CloudDeviceCodeFlowState {
    val root = element?.obj ?: return CloudDeviceCodeFlowState(cloudUrl = cloudUrl, status = "error", error = "Invalid device code response")
    val deviceCode = root["device_code"].string.orEmpty()
    val userCode = root["user_code"].string.orEmpty()
    val verificationUri = root["verification_uri"].string.orEmpty()
    if (deviceCode.isBlank() || userCode.isBlank() || verificationUri.isBlank()) {
        return CloudDeviceCodeFlowState(
            cloudUrl = cloudUrl,
            status = "error",
            error = root["error"].string ?: "Failed to start device code sign-in",
        )
    }
    return CloudDeviceCodeFlowState(
        deviceCode = deviceCode,
        userCode = userCode,
        verificationUri = verificationUri,
        cloudUrl = cloudUrl,
        expiresInSec = root["expires_in"]?.int ?: 600,
        intervalSec = (root["interval"]?.int ?: 5).coerceAtLeast(1),
        status = "waiting",
        error = null,
    )
}

fun parseCloudDeviceCodePoll(element: JsonElement?): CloudDeviceCodePollState {
    val root = element?.obj ?: return CloudDeviceCodePollState(status = "pending")
    return CloudDeviceCodePollState(
        status = root["status"].string ?: "pending",
        accessToken = root["access_token"].string,
        email = root["email"].string,
        error = root["error"].string,
    )
}

data class ProxySettingsState(
    val enabled: Boolean = false,
    val authKey: String = "",
    val requestCount: Int = 0,
    val localEndpoint: String = "",
    val localOpenAiEndpoint: String = "",
    val tunnelUrl: String? = null,
    val proxyEndpoint: String? = null,
    val openAiEndpoint: String? = null,
)

fun parseProxySettings(element: JsonElement?): ProxySettingsState {
    val root = element?.obj ?: return ProxySettingsState()
    return ProxySettingsState(
        enabled = root["enabled"]?.bool ?: false,
        authKey = root["authKey"].string.orEmpty(),
        requestCount = root["requestCount"]?.int ?: 0,
        localEndpoint = root["localEndpoint"].string.orEmpty(),
        localOpenAiEndpoint = root["localOpenAiEndpoint"].string.orEmpty(),
        tunnelUrl = root["tunnelUrl"].string,
        proxyEndpoint = root["proxyEndpoint"].string,
        openAiEndpoint = root["openAiEndpoint"].string,
    )
}

/**
 * Builds the provider-specific proxy endpoint URL (`anthropic` vs `openai`) for local or public tunnel.
 */
fun buildProxyEndpointUrl(state: ProxySettingsState, provider: String, useTunnel: Boolean): String {
    return if (provider == "openai") {
        if (useTunnel && !state.openAiEndpoint.isNullOrBlank()) state.openAiEndpoint else state.localOpenAiEndpoint
    } else {
        if (useTunnel && !state.proxyEndpoint.isNullOrBlank()) state.proxyEndpoint else state.localEndpoint
    }
}

data class ExtensionItemRow(
    val id: String,
    val version: String,
    val displayName: String,
    val description: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val activated: Boolean = false,
)

fun parseExtensionsList(element: JsonElement?): List<ExtensionItemRow> {
    val arr = when {
        element is JsonArray -> element
        element?.obj?.get("extensions") is JsonArray -> element.obj?.get("extensions")?.array
        else -> null
    } ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val id = obj["id"].string ?: return@mapNotNull null
        ExtensionItemRow(
            id = id,
            version = obj["version"].string ?: "0.0.0",
            displayName = obj["displayName"].string?.ifBlank { id } ?: id,
            description = obj["description"].string.orEmpty(),
            icon = obj["icon"].string,
            enabled = obj["enabled"]?.bool ?: true,
            activated = obj["activated"]?.bool ?: false,
        )
    }
}

// ===========================================================================
// 4. Jira State & Validators
// ===========================================================================

data class JiraProjectOption(
    val id: Int,
    val name: String,
    val path: String,
    val hasConfig: Boolean,
)

fun parseJiraProjectOptions(element: JsonElement?): List<JiraProjectOption> {
    val arr = element?.obj?.get("projects")?.array ?: element?.array ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val id = obj["id"]?.int ?: return@mapNotNull null
        JiraProjectOption(
            id = id,
            name = obj["name"].string ?: "Project #$id",
            path = obj["path"].string.orEmpty(),
            hasConfig = obj["hasConfig"]?.bool ?: false,
        )
    }
}

data class JiraConfigRow(
    val id: Int,
    val projectId: Int,
    val projectName: String? = null,
    val baseUrl: String,
    val email: String,
    val hasToken: Boolean = false,
    val updatedAt: String? = null,
)

fun parseJiraConfigs(element: JsonElement?): List<JiraConfigRow> {
    val arr = element?.obj?.get("configs")?.array ?: element?.array ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val id = obj["id"]?.int ?: return@mapNotNull null
        val projectId = obj["projectId"]?.int ?: obj["project_id"]?.int ?: id
        JiraConfigRow(
            id = id,
            projectId = projectId,
            projectName = obj["projectName"].string ?: obj["project_name"].string,
            baseUrl = obj["baseUrl"].string ?: obj["base_url"].string.orEmpty(),
            email = obj["email"].string.orEmpty(),
            hasToken = obj["hasToken"]?.bool ?: obj["has_token"]?.bool ?: false,
            updatedAt = obj["updatedAt"].string ?: obj["updated_at"].string,
        )
    }
}

data class JiraWatcherRow(
    val id: Int,
    val configId: Int,
    val name: String,
    val jql: String,
    val promptTemplate: String? = null,
    val enabled: Boolean = true,
    val intervalMs: Int = 120_000,
    val mode: String = "notify", // "debug" | "notify"
    val lastPolledAt: String? = null,
)

fun parseJiraWatchers(element: JsonElement?): List<JiraWatcherRow> {
    val arr = element?.obj?.get("watchers")?.array ?: element?.array ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val id = obj["id"]?.int ?: return@mapNotNull null
        JiraWatcherRow(
            id = id,
            configId = obj["configId"]?.int ?: obj["config_id"]?.int ?: 0,
            name = obj["name"].string ?: "Watcher #$id",
            jql = obj["jql"].string.orEmpty(),
            promptTemplate = obj["promptTemplate"].string ?: obj["prompt_template"].string,
            enabled = if (obj["enabled"] != null) (obj["enabled"]?.bool ?: (obj["enabled"]?.int != 0)) else true,
            intervalMs = obj["intervalMs"]?.int ?: obj["interval_ms"]?.int ?: 120_000,
            mode = obj["mode"].string ?: "notify",
            lastPolledAt = obj["lastPolledAt"].string ?: obj["last_polled_at"].string,
        )
    }
}

data class JiraResultRow(
    val id: Int,
    val watcherId: Int? = null,
    val issueKey: String,
    val issueSummary: String? = null,
    val issueUpdated: String? = null,
    val status: String = "pending", // "pending" | "running" | "done" | "failed"
    val sessionId: String? = null,
    val aiSummary: String? = null,
    val readAt: String? = null,
    val triggeredBy: String = "watcher",
    val createdAt: String? = null,
)

fun parseJiraResults(element: JsonElement?): List<JiraResultRow> {
    val arr = element?.obj?.get("results")?.array ?: element?.array ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val id = obj["id"]?.int ?: return@mapNotNull null
        JiraResultRow(
            id = id,
            watcherId = obj["watcherId"]?.int ?: obj["watcher_id"]?.int,
            issueKey = obj["issueKey"].string ?: obj["issue_key"].string ?: "#$id",
            issueSummary = obj["issueSummary"].string ?: obj["issue_summary"].string,
            issueUpdated = obj["issueUpdated"].string ?: obj["issue_updated"].string,
            status = obj["status"].string ?: "pending",
            sessionId = obj["sessionId"].string ?: obj["session_id"].string,
            aiSummary = obj["aiSummary"].string ?: obj["ai_summary"].string,
            readAt = obj["readAt"].string ?: obj["read_at"].string,
            triggeredBy = obj["triggeredBy"].string ?: obj["triggered_by"].string ?: "watcher",
            createdAt = obj["createdAt"].string ?: obj["created_at"].string,
        )
    }
}

/** Clamps Jira watcher polling interval between 30s (`30_000`) and 1h (`3_600_000`). */
fun clampJiraIntervalMs(ms: Int): Int = ms.coerceIn(30_000, 3_600_000)

/**
 * Mirrors `src/server/routes/jira-watcher-routes.ts:130` and `src/web/components/jira/jira-ticket-card.tsx:41-42`:
 * only `pending` or `failed` results can be debugged (and when `failed` already has a session, Resume is shown instead).
 */
fun canDebugJiraWatcherResult(status: String, sessionId: String? = null): Boolean =
    status == "pending" || (status == "failed" && sessionId.isNullOrBlank())

/**
 * Mirrors `src/server/routes/jira-watcher-routes.ts:146-147`:
 * only `failed` results that have a `sessionId` can be resumed.
 */
fun canResumeJiraWatcherResult(status: String, sessionId: String?): Boolean =
    status == "failed" && !sessionId.isNullOrBlank()

/**
 * Mirrors `src/server/routes/jira-watcher-routes.ts:165`:
 * only `queued` or `running` results can be cancelled.
 */
fun canCancelJiraWatcherResult(status: String): Boolean =
    status == "queued" || status == "running"

// ===========================================================================
// 5. Schedules State, Cron Validators, Template Parser & Logs Ring Buffer
// ===========================================================================

data class ScheduleProjectOption(
    val name: String,
    val path: String,
)

data class ScheduleInputDeclState(
    val key: String,
    val label: String,
    val type: String = "text", // "text" | "number" | "boolean" | "secret"
    val required: Boolean = false,
    val defaultValue: String? = null,
    val description: String? = null,
)

data class ScheduleStatusState(
    val state: String = "idle", // "idle" | "running" | "backoff" | "stopped"
    val pid: Int? = null,
    val since: Long? = null,
    val restartAt: Long? = null,
    val restarts: Int = 0,
    val lastError: String? = null,
)

data class ScheduleRowState(
    val id: Int,
    val name: String,
    val description: String? = null,
    val kind: String = "prompt", // "prompt" | "script"
    val mode: String = "tick", // "tick" | "always-on"
    val cronExpr: String = "* * * * *",
    val providerId: String = "claude",
    val projectPath: String = "",
    val prompt: String = "",
    val script: String? = null,
    val permissionMode: String = "default",
    val maxTurns: Int = 20,
    val timeoutMs: Int = 900_000,
    val enabled: Boolean = true,
    val sessionId: String? = null,
    val lastRunAt: String? = null,
    val nextFireAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val inputs: List<ScheduleInputDeclState> = emptyList(),
    val inputValues: Map<String, String> = emptyMap(),
    val secretsSet: List<String> = emptyList(),
    val status: ScheduleStatusState = ScheduleStatusState(),
)

data class ScheduleRunRow(
    val id: Int,
    val scheduleId: Int,
    val sessionId: String? = null,
    val rotatedToSessionId: String? = null,
    val status: String,
    val outputTruncated: String? = null,
    val contextWindowPct: Double? = null,
    val costUsd: Double? = null,
    val error: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
)

data class ScheduleLogLine(
    val seq: Int,
    val stream: String, // "stdout" | "stderr" | "meta"
    val ts: Long,
    val text: String,
)

data class ScheduleLogPollResult(
    val status: ScheduleStatusState,
    val lines: List<ScheduleLogLine>,
    val last: Int,
)

private fun parseScheduleStatus(obj: Map<String, JsonElement>?): ScheduleStatusState {
    if (obj == null) return ScheduleStatusState()
    return ScheduleStatusState(
        state = obj["state"].string ?: "idle",
        pid = obj["pid"]?.int,
        since = obj["since"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        restartAt = obj["restartAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        restarts = obj["restarts"]?.int ?: 0,
        lastError = obj["lastError"].string,
    )
}

fun parseSchedulesList(element: JsonElement?): List<ScheduleRowState> {
    val arr = element?.obj?.get("schedules")?.array ?: element?.array ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val id = obj["id"]?.int ?: return@mapNotNull null
        val inputs = obj["inputs"]?.array.orEmpty().mapNotNull { inp ->
            val io = inp.obj ?: return@mapNotNull null
            val key = io["key"].string ?: return@mapNotNull null
            ScheduleInputDeclState(
                key = key,
                label = io["label"].string ?: key,
                type = io["type"].string ?: "text",
                required = io["required"]?.bool ?: false,
                defaultValue = io["default"]?.jsonPrimitive?.contentOrNull,
                description = io["description"].string,
            )
        }
        val inputValues = obj["inputValues"]?.obj?.entries?.associate { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull.orEmpty())
        }.orEmpty()
        val secrets = obj["secretsSet"]?.array.orEmpty().mapNotNull { it.string }
        val enabledVal = obj["enabled"]?.let { el ->
            el.bool ?: (el.int != 0)
        } ?: true
        ScheduleRowState(
            id = id,
            name = obj["name"].string ?: "Schedule #$id",
            description = obj["description"].string,
            kind = obj["kind"].string ?: "prompt",
            mode = obj["mode"].string ?: "tick",
            cronExpr = obj["cron_expr"].string ?: "* * * * *",
            providerId = obj["provider_id"].string ?: "claude",
            projectPath = obj["project_path"].string.orEmpty(),
            prompt = obj["prompt"].string.orEmpty(),
            script = obj["script"].string,
            permissionMode = obj["permission_mode"].string ?: "default",
            maxTurns = obj["max_turns"]?.int ?: 20,
            timeoutMs = obj["timeout_ms"]?.int ?: 900_000,
            enabled = enabledVal,
            sessionId = obj["session_id"].string,
            lastRunAt = obj["last_run_at"].string,
            nextFireAt = obj["next_fire_at"].string,
            createdAt = obj["created_at"].string,
            updatedAt = obj["updated_at"].string,
            inputs = inputs,
            inputValues = inputValues,
            secretsSet = secrets,
            status = parseScheduleStatus(obj["status"]?.obj),
        )
    }
}

fun parseScheduleRuns(element: JsonElement?): List<ScheduleRunRow> {
    val arr = element?.obj?.get("runs")?.array ?: element?.array ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val id = obj["id"]?.int ?: return@mapNotNull null
        ScheduleRunRow(
            id = id,
            scheduleId = obj["schedule_id"]?.int ?: 0,
            sessionId = obj["session_id"].string,
            rotatedToSessionId = obj["rotated_to_session_id"].string,
            status = obj["status"].string ?: "running",
            outputTruncated = obj["output_truncated"].string,
            contextWindowPct = obj["context_window_pct"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            costUsd = obj["cost_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            error = obj["error"].string,
            startedAt = obj["started_at"].string.orEmpty(),
            endedAt = obj["ended_at"].string,
        )
    }
}

fun parseScheduleLogs(element: JsonElement?): ScheduleLogPollResult {
    val root = element?.obj ?: return ScheduleLogPollResult(ScheduleStatusState(), emptyList(), 0)
    val status = parseScheduleStatus(root["status"]?.obj)
    val lines = root["lines"]?.array.orEmpty().mapNotNull { item ->
        val obj = item.obj ?: return@mapNotNull null
        val seq = obj["seq"]?.int ?: return@mapNotNull null
        ScheduleLogLine(
            seq = seq,
            stream = obj["stream"].string ?: "stdout",
            ts = obj["ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            text = obj["text"].string.orEmpty(),
        )
    }
    val last = root["last"]?.int ?: (lines.lastOrNull()?.seq ?: 0)
    return ScheduleLogPollResult(status = status, lines = lines, last = last)
}

data class ScheduleLogBufferState(
    val lines: List<ScheduleLogLine> = emptyList(),
    val afterSeq: Int = 0,
    val status: ScheduleStatusState = ScheduleStatusState(),
    val resetDetected: Boolean = false,
)

/**
 * Merges a new `/api/schedules/:id/logs?after=<afterSeq>` poll response into the client ring buffer:
 * - If `poll.last < current.afterSeq`, the server restarted and its ring buffer reset to 0:
 *   clears old lines, sets `afterSeq = 0` (or `poll.last` if lines came back), and marks `resetDetected = true`.
 * - Caps buffered lines at [maxLines] (default 1000), matching `MAX_LOG_LINES` in the web client.
 */
fun mergeScheduleLogs(
    current: ScheduleLogBufferState,
    poll: ScheduleLogPollResult,
    maxLines: Int = 1000,
): ScheduleLogBufferState {
    if (poll.last < current.afterSeq) {
        val fresh = poll.lines.takeLast(maxLines)
        return ScheduleLogBufferState(
            lines = fresh,
            afterSeq = if (fresh.isNotEmpty()) poll.last else 0,
            status = poll.status,
            resetDetected = true,
        )
    }
    val combined = (current.lines + poll.lines)
        .distinctBy { it.seq }
        .takeLast(maxLines)
    return ScheduleLogBufferState(
        lines = combined,
        afterSeq = maxOf(current.afterSeq, poll.last),
        status = poll.status,
        resetDetected = false,
    )
}

/**
 * Mirrors `cronFieldCount(expr)` in `src/shared/schedule-cron.ts`.
 */
fun cronFieldCount(expr: String): Int {
    val trimmed = expr.trim()
    if (trimmed.isEmpty()) return 0
    return trimmed.split(Regex("\\s+")).size
}

private val CRON_TOKEN_REGEX = Regex("^[0-9*,/\\-]+$")

private fun minSecondsStep(secField: String): Int? {
    val f = secField.trim()
    if (f == "*") return 1
    if (f.startsWith("*/")) {
        val step = f.removePrefix("*/").toIntOrNull() ?: return null
        return if (step in 1..59) step else null
    }
    if (f.contains(",")) {
        val nums = f.split(",").map { it.trim().toIntOrNull() ?: return null }
        if (nums.any { it !in 0..59 }) return null
        val sorted = nums.distinct().sorted()
        if (sorted.size <= 1) return 60
        var minGap = 60
        for (i in 0 until sorted.size) {
            val next = if (i + 1 < sorted.size) sorted[i + 1] else sorted[0] + 60
            minGap = minOf(minGap, next - sorted[i])
        }
        return minGap
    }
    if (f.contains("-")) {
        val parts = f.split("-")
        if (parts.size != 2) return null
        val start = parts[0].substringBefore("/").toIntOrNull() ?: return null
        val endPart = parts[1]
        val end = endPart.substringBefore("/").toIntOrNull() ?: return null
        if (start !in 0..59 || end !in 0..59 || start > end) return null
        val step = if (endPart.contains("/")) endPart.substringAfter("/").toIntOrNull() ?: return null else 1
        return if (start == end) 60 else step
    }
    val single = f.toIntOrNull() ?: return null
    return if (single in 0..59) 60 else null
}

/**
 * Mirrors `cronProblem(expr, kind)` in `src/shared/schedule-cron.ts`:
 * - `prompt` requires a 5-field cron (`minute hour day month weekday`).
 * - `script` allows 5 or 6 fields (seconds first), firing at most every 5 seconds.
 */
fun cronProblem(expr: String, kind: String = "prompt"): String? {
    val trimmed = expr.trim()
    if (trimmed.isEmpty()) return "Cron expression is required"
    val fields = trimmed.split(Regex("\\s+"))
    if (kind == "prompt" && fields.size == 6) {
        return "Prompt schedules use 5 cron fields (minute hour day month weekday); 6-field seconds cron is only for scripts"
    }
    if (fields.size != 5 && fields.size != 6) {
        return if (kind == "script") {
            "Expected 5 or 6 cron fields, got ${fields.size}"
        } else {
            "Expected 5 cron fields, got ${fields.size}"
        }
    }
    if (fields.any { !CRON_TOKEN_REGEX.matches(it) }) {
        return "Invalid cron field syntax"
    }
    if (fields.size == 6) {
        val step = minSecondsStep(fields[0]) ?: return "Invalid seconds field in 6-field cron"
        if (step < 5) {
            return "6-field script schedules can fire at most every 5 seconds"
        }
    }
    return null
}

/**
 * Mirrors `formProblems(...)` in `src/web/components/schedules/schedule-form-dialog.tsx`.
 */
fun scheduleFormProblems(
    name: String,
    projectPath: String,
    kind: String,
    mode: String,
    cron: String,
    prompt: String,
    script: String,
    timeoutMs: Int,
): List<String> {
    val problems = mutableListOf<String>()
    val cleanName = name.trim()
    if (cleanName.isEmpty()) {
        problems.add("Name is required")
    } else if (cleanName.length > 120) {
        problems.add("Name must be 120 characters or fewer")
    }
    if (projectPath.isBlank()) {
        problems.add("Project is required")
    }
    if (kind != "prompt" && kind != "script") {
        problems.add("Kind must be 'prompt' or 'script'")
    }
    if (kind == "prompt" && mode == "always-on") {
        problems.add("Always-on mode is only supported for script schedules")
    }
    if (mode != "always-on") {
        cronProblem(cron, kind)?.let { problems.add(it) }
        if (timeoutMs < 10_000) {
            problems.add("Timeout must be at least 10 seconds")
        }
    }
    if (kind == "prompt" && prompt.trim().isEmpty()) {
        problems.add("Prompt is required")
    }
    if (kind == "script" && script.trim().isEmpty()) {
        problems.add("Script is required")
    }
    return problems
}

private val TEMPLATE_KNOWN_FIELDS = setOf(
    "ppmScheduleTemplate",
    "name",
    "description",
    "kind",
    "mode",
    "cron",
    "prompt",
    "script",
    "providerId",
    "timeoutMs",
    "maxTurns",
    "permissionMode",
    "inputs",
)

private val INPUT_KEY_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

data class ParsedScheduleTemplate(
    val rawJson: JsonObject,
    val name: String,
    val description: String? = null,
    val kind: String,
    val mode: String,
    val cron: String,
    val prompt: String? = null,
    val script: String? = null,
    val providerId: String? = null,
    val timeoutMs: Int = 900_000,
    val maxTurns: Int? = null,
    val permissionMode: String? = null,
    val inputs: List<ScheduleInputDeclState> = emptyList(),
)

/**
 * Mirrors `parseScheduleTemplate(input)` in `src/shared/schedule-template.ts`.
 * Returns `Result.success(ParsedScheduleTemplate)` or `Result.failure(IllegalArgumentException)`.
 * Also unwraps `{ template: { ... } }` if an export response wrapper is passed directly.
 */
fun parseScheduleTemplateJson(rawText: String): Result<ParsedScheduleTemplate> = runCatching {
    val element = runCatching { Json.parseToJsonElement(rawText) }.getOrElse {
        throw IllegalArgumentException("Invalid JSON: ${it.message}")
    }
    val outerObj = element as? JsonObject
        ?: throw IllegalArgumentException("Schedule template must be a JSON object")
    val obj = (outerObj["template"] as? JsonObject) ?: outerObj

    val version = obj["ppmScheduleTemplate"]?.int
    if (version != 1) {
        throw IllegalArgumentException("ppmScheduleTemplate must be 1")
    }
    val unknown = obj.keys - TEMPLATE_KNOWN_FIELDS
    if (unknown.isNotEmpty()) {
        throw IllegalArgumentException("Unknown template field: ${unknown.first()}")
    }

    val name = obj["name"].string?.trim().orEmpty()
    if (name.isEmpty() || name.length > 120) {
        throw IllegalArgumentException("Template name must be 1–120 characters")
    }
    val description = obj["description"].string?.trim()
    if (description != null && description.length > 2000) {
        throw IllegalArgumentException("Template description must be 2000 characters or fewer")
    }
    val kind = obj["kind"].string ?: throw IllegalArgumentException("Template kind is required")
    if (kind != "prompt" && kind != "script") {
        throw IllegalArgumentException("Template kind must be 'prompt' or 'script'")
    }
    val mode = obj["mode"].string ?: "tick"
    if (mode != "tick" && mode != "always-on") {
        throw IllegalArgumentException("Template mode must be 'tick' or 'always-on'")
    }
    if (kind == "prompt" && mode == "always-on") {
        throw IllegalArgumentException("Prompt schedules cannot use always-on mode")
    }

    val cron = obj["cron"].string ?: "* * * * *"
    if (mode == "tick") {
        cronProblem(cron, kind)?.let { throw IllegalArgumentException(it) }
    }

    val prompt = obj["prompt"].string
    val script = obj["script"].string
    if (kind == "prompt") {
        if (prompt.isNullOrBlank()) throw IllegalArgumentException("Prompt template requires a non-empty prompt")
        if (!script.isNullOrBlank()) throw IllegalArgumentException("Prompt template cannot include a script")
    } else {
        if (script.isNullOrBlank()) throw IllegalArgumentException("Script template requires a non-empty script")
        if (!prompt.isNullOrBlank()) throw IllegalArgumentException("Script template cannot include a prompt")
    }

    val timeoutMs = obj["timeoutMs"]?.int ?: 900_000
    if (mode == "tick" && timeoutMs < 10_000) {
        throw IllegalArgumentException("timeoutMs must be at least 10000")
    }
    val maxTurns = obj["maxTurns"]?.int
    if (maxTurns != null && (maxTurns < 1 || maxTurns > 200)) {
        throw IllegalArgumentException("maxTurns must be between 1 and 200")
    }
    val permissionMode = obj["permissionMode"].string
    if (permissionMode != null && permissionMode !in setOf("default", "acceptEdits", "plan", "bypassPermissions")) {
        throw IllegalArgumentException("Invalid permissionMode '$permissionMode'")
    }

    val inputsArr = obj["inputs"]?.array.orEmpty()
    val seenKeys = mutableSetOf<String>()
    val inputs = inputsArr.map { item ->
        val io = item.obj ?: throw IllegalArgumentException("Each input declaration must be an object")
        val key = io["key"].string ?: throw IllegalArgumentException("Input key is required")
        if (!INPUT_KEY_REGEX.matches(key)) {
            throw IllegalArgumentException("Invalid input key '$key'")
        }
        if (!seenKeys.add(key)) {
            throw IllegalArgumentException("Duplicate input key '$key'")
        }
        val type = io["type"].string ?: "text"
        if (type !in setOf("text", "number", "boolean", "secret")) {
            throw IllegalArgumentException("Invalid input type '$type'")
        }
        val defaultPrim = io["default"] as? JsonPrimitive
        if (type == "secret" && defaultPrim != null && defaultPrim.contentOrNull != null) {
            throw IllegalArgumentException("Secret input '$key' cannot have a default value")
        }
        ScheduleInputDeclState(
            key = key,
            label = io["label"].string?.ifBlank { key } ?: key,
            type = type,
            required = io["required"]?.bool ?: false,
            defaultValue = defaultPrim?.contentOrNull,
            description = io["description"].string,
        )
    }

    ParsedScheduleTemplate(
        rawJson = obj,
        name = name,
        description = description,
        kind = kind,
        mode = mode,
        cron = cron,
        prompt = prompt,
        script = script,
        providerId = obj["providerId"].string,
        timeoutMs = timeoutMs,
        maxTurns = maxTurns,
        permissionMode = permissionMode,
        inputs = inputs,
    )
}
