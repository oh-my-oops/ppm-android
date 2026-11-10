package com.hienlh.ppm.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks device network availability and resolves connection-loss states so the app distinguishes
 * between the phone/tablet itself having no network (`"This phone is offline"` / `"This tablet is offline"`)
 * and the PPM server being unreachable, a quick tunnel closing, a named tunnel not responding, or
 * a server restarting during an upgrade (Requirement 5.3, Phone 2 A1–A4 & S8, Tablet 2 TA1–TA2).
 */
data class DeviceNetworkState(
    val online: Boolean = true,
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    val ethernetOrVpn: Boolean = false,
)

enum class OutageKind {
    NONE,
    QUIET_RECONNECTING,
    UPGRADING_RECONNECTING,
    DEVICE_OFFLINE,
    SERVER_UNREACHABLE,
    QUICK_TUNNEL_CLOSED,
    NAMED_TUNNEL_DOWN,
}

data class ConnectivitySnapshot(
    val kind: OutageKind,
    val deviceOnline: Boolean,
    val serverUp: Boolean,
    val upgrading: Boolean,
    val outageDurationMs: Long,
    val showOverlay: Boolean,
    val showQuietReconnecting: Boolean,
    val showUpgradingStrip: Boolean,
    val shouldPollServer: Boolean,
    val pollIntervalMs: Long,
    val title: String,
    val body: String,
    val advice: String?,
    val statusNote: String,
    val primaryActionLabel: String,
    val offerOpenLocalNetwork: Boolean,
    val offerOpenPpmCloud: Boolean,
)

/**
 * Pure state machine and observable holder for device connectivity + server health.
 * On Android, `ConnectionLostOverlay` registers a `ConnectivityManager.NetworkCallback` that feeds
 * [onNetworkAvailable] / [onNetworkLost] / [updateDeviceNetwork].
 */
class ConnectivityState(
    initialDeviceNetwork: DeviceNetworkState = DeviceNetworkState(online = true),
) {
    private val _deviceNetwork = MutableStateFlow(initialDeviceNetwork)
    val deviceNetwork: StateFlow<DeviceNetworkState> = _deviceNetwork.asStateFlow()

    val isDeviceOnline: Boolean
        get() = _deviceNetwork.value.online

    private var serverDownSinceMs: Long? = null
    private var serverUp: Boolean = true
    private var upgrading: Boolean = false
    private var upgradingTargetVersion: String? = null

    fun updateDeviceNetwork(state: DeviceNetworkState) {
        _deviceNetwork.value = state
    }

    fun setDeviceOnline(online: Boolean) {
        _deviceNetwork.value = _deviceNetwork.value.copy(online = online)
    }

    fun setUpgrading(active: Boolean, targetVersion: String? = null) {
        upgrading = active
        upgradingTargetVersion = if (active) targetVersion else null
    }

    fun recordServerUp() {
        serverUp = true
        serverDownSinceMs = null
        upgrading = false
        upgradingTargetVersion = null
    }

    fun recordServerDown(nowMs: Long) {
        serverUp = false
        if (serverDownSinceMs == null) {
            serverDownSinceMs = nowMs
        }
    }

    /**
     * Resolves the current [ConnectivitySnapshot] at [nowMs].
     */
    fun snapshot(
        nowMs: Long,
        host: String = "",
        namedHostname: String? = null,
        localUrl: String? = null,
        isTablet: Boolean = false,
        serverName: String = "PPM",
    ): ConnectivitySnapshot = resolve(
        deviceOnline = _deviceNetwork.value.online,
        serverUp = serverUp,
        outageDurationMs = serverDownSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L,
        upgrading = upgrading,
        upgradingVersion = upgradingTargetVersion,
        host = host,
        namedHostname = namedHostname,
        localUrl = localUrl,
        isTablet = isTablet,
        serverName = serverName,
    )

    companion object {
        const val OUTAGE_OVERLAY_MS = 15_000L
        const val HEALTHY_POLL_MS = 10_000L
        const val OUTAGE_POLL_MS = 2_000L

        const val PHONE_OFFLINE_TITLE = "This phone is offline"
        const val TABLET_OFFLINE_TITLE = "This tablet is offline"
        const val DEVICE_OFFLINE_BODY =
            "Wi-Fi and mobile data are both off, or have no connection. PPM will reconnect as soon as you're back online."
        const val DEVICE_OFFLINE_ACTION = "Open network settings"
        const val DEVICE_OFFLINE_NOTE = "Waiting for network"

        const val SERVER_UNREACHABLE_TITLE = "Server Unreachable"
        const val SERVER_UNREACHABLE_BODY =
            "Cannot connect to the PPM server. It may have stopped or is restarting."
        const val SERVER_UNREACHABLE_ADVICE =
            "If the server was stopped, run ppm start to restart it."

        const val CONNECTION_LOST_TITLE = "Connection Lost"
        const val CONNECTION_LOST_BODY =
            "The tunnel appears to have closed. The server may have restarted with a new URL."
        const val CONNECTION_LOST_ADVICE =
            "Check the terminal or PPM Cloud for the updated tunnel link."

        const val DOMAIN_NOT_RESPONDING_TITLE = "Domain Not Responding"
        const val DOMAIN_NOT_RESPONDING_BODY =
            "This address stays the same, so keep it. The machine may be asleep, offline, or restarting — it will answer again on this same URL."

        const val RETRY_ACTION = "Retry Connection"
        const val AUTO_RETRY_NOTE = "Checking again every 2 s"
        const val UPDATING_TITLE = "Updating, reconnecting…"

        fun offlineTitle(isTablet: Boolean): String =
            if (isTablet) TABLET_OFFLINE_TITLE else PHONE_OFFLINE_TITLE

        /**
         * Stateless resolution of the app's connection UI from device network state, server health
         * duration, upgrade status, and server address type.
         */
        fun resolve(
            deviceOnline: Boolean,
            serverUp: Boolean,
            outageDurationMs: Long,
            upgrading: Boolean = false,
            upgradingVersion: String? = null,
            host: String = "",
            namedHostname: String? = null,
            localUrl: String? = null,
            isTablet: Boolean = false,
            serverName: String = "PPM",
        ): ConnectivitySnapshot {
            // 1. Device itself has no network connection (Requirement 5.3, Phone 2 A4)
            if (!deviceOnline && (!serverUp || outageDurationMs > 0L)) {
                val title = offlineTitle(isTablet)
                return ConnectivitySnapshot(
                    kind = OutageKind.DEVICE_OFFLINE,
                    deviceOnline = false,
                    serverUp = false,
                    upgrading = upgrading,
                    outageDurationMs = outageDurationMs,
                    showOverlay = true,
                    showQuietReconnecting = false,
                    showUpgradingStrip = false,
                    shouldPollServer = false,
                    pollIntervalMs = 0L,
                    title = title,
                    body = DEVICE_OFFLINE_BODY,
                    advice = null,
                    statusNote = DEVICE_OFFLINE_NOTE,
                    primaryActionLabel = DEVICE_OFFLINE_ACTION,
                    offerOpenLocalNetwork = false,
                    offerOpenPpmCloud = false,
                )
            }

            // 2. Server is healthy and no upgrade restart is active
            if (serverUp && !upgrading) {
                return ConnectivitySnapshot(
                    kind = OutageKind.NONE,
                    deviceOnline = deviceOnline,
                    serverUp = true,
                    upgrading = false,
                    outageDurationMs = 0L,
                    showOverlay = false,
                    showQuietReconnecting = false,
                    showUpgradingStrip = false,
                    shouldPollServer = deviceOnline,
                    pollIntervalMs = if (deviceOnline) HEALTHY_POLL_MS else 0L,
                    title = "",
                    body = "",
                    advice = null,
                    statusNote = "",
                    primaryActionLabel = "",
                    offerOpenLocalNetwork = false,
                    offerOpenPpmCloud = false,
                )
            }

            // 3. Server is upgrading / restarting and under the 15 s overlay threshold (Phone 2 S8, Tablet 2 TA2)
            if (upgrading && outageDurationMs < OUTAGE_OVERLAY_MS) {
                val verText = upgradingVersion?.removePrefix("v")?.takeIf { it.isNotEmpty() }
                val sub = if (verText != null) {
                    "PPM v$verText is starting on $serverName."
                } else {
                    "PPM is restarting on $serverName."
                }
                return ConnectivitySnapshot(
                    kind = OutageKind.UPGRADING_RECONNECTING,
                    deviceOnline = deviceOnline,
                    serverUp = serverUp,
                    upgrading = true,
                    outageDurationMs = outageDurationMs,
                    showOverlay = false,
                    showQuietReconnecting = false,
                    showUpgradingStrip = true,
                    shouldPollServer = deviceOnline,
                    pollIntervalMs = OUTAGE_POLL_MS,
                    title = UPDATING_TITLE,
                    body = sub,
                    advice = null,
                    statusNote = AUTO_RETRY_NOTE,
                    primaryActionLabel = "",
                    offerOpenLocalNetwork = false,
                    offerOpenPpmCloud = false,
                )
            }

            // 4. Brief outage under 15 s: quiet "Reconnecting…" in app bar / status bar, no overlay (Note 47)
            if (outageDurationMs < OUTAGE_OVERLAY_MS) {
                return ConnectivitySnapshot(
                    kind = OutageKind.QUIET_RECONNECTING,
                    deviceOnline = deviceOnline,
                    serverUp = false,
                    upgrading = upgrading,
                    outageDurationMs = outageDurationMs,
                    showOverlay = false,
                    showQuietReconnecting = true,
                    showUpgradingStrip = false,
                    shouldPollServer = deviceOnline,
                    pollIntervalMs = OUTAGE_POLL_MS,
                    title = "Reconnecting…",
                    body = "",
                    advice = null,
                    statusNote = AUTO_RETRY_NOTE,
                    primaryActionLabel = "",
                    offerOpenLocalNetwork = false,
                    offerOpenPpmCloud = false,
                )
            }

            // 5. Outage >= 15 s with device online: classify by tunnel/address kind (Phone 2 A1–A3)
            val normalizedHost = host.trim().lowercase()
            val isQuickTunnel = normalizedHost.endsWith(".trycloudflare.com")
            val isNamedTunnel = !namedHostname.isNullOrBlank() &&
                normalizedHost == namedHostname.trim().lowercase()

            return when {
                isQuickTunnel -> ConnectivitySnapshot(
                    kind = OutageKind.QUICK_TUNNEL_CLOSED,
                    deviceOnline = true,
                    serverUp = false,
                    upgrading = upgrading,
                    outageDurationMs = outageDurationMs,
                    showOverlay = true,
                    showQuietReconnecting = false,
                    showUpgradingStrip = false,
                    shouldPollServer = true,
                    pollIntervalMs = OUTAGE_POLL_MS,
                    title = CONNECTION_LOST_TITLE,
                    body = CONNECTION_LOST_BODY,
                    advice = CONNECTION_LOST_ADVICE,
                    statusNote = AUTO_RETRY_NOTE,
                    primaryActionLabel = RETRY_ACTION,
                    offerOpenLocalNetwork = !localUrl.isNullOrBlank(),
                    offerOpenPpmCloud = true,
                )
                isNamedTunnel -> ConnectivitySnapshot(
                    kind = OutageKind.NAMED_TUNNEL_DOWN,
                    deviceOnline = true,
                    serverUp = false,
                    upgrading = upgrading,
                    outageDurationMs = outageDurationMs,
                    showOverlay = true,
                    showQuietReconnecting = false,
                    showUpgradingStrip = false,
                    shouldPollServer = true,
                    pollIntervalMs = OUTAGE_POLL_MS,
                    title = DOMAIN_NOT_RESPONDING_TITLE,
                    body = DOMAIN_NOT_RESPONDING_BODY,
                    advice = null,
                    statusNote = AUTO_RETRY_NOTE,
                    primaryActionLabel = RETRY_ACTION,
                    offerOpenLocalNetwork = false,
                    offerOpenPpmCloud = false,
                )
                else -> ConnectivitySnapshot(
                    kind = OutageKind.SERVER_UNREACHABLE,
                    deviceOnline = true,
                    serverUp = false,
                    upgrading = upgrading,
                    outageDurationMs = outageDurationMs,
                    showOverlay = true,
                    showQuietReconnecting = false,
                    showUpgradingStrip = false,
                    shouldPollServer = true,
                    pollIntervalMs = OUTAGE_POLL_MS,
                    title = SERVER_UNREACHABLE_TITLE,
                    body = SERVER_UNREACHABLE_BODY,
                    advice = SERVER_UNREACHABLE_ADVICE,
                    statusNote = AUTO_RETRY_NOTE,
                    primaryActionLabel = RETRY_ACTION,
                    offerOpenLocalNetwork = false,
                    offerOpenPpmCloud = false,
                )
            }
        }
    }
}

/**
 * Per-server health status displayed in the Project Switcher's `Servers` list (Phone 2 S1–S2, Tablet 2 TS1).
 */
enum class SavedServerBadgeKind {
    LIVE,
    ONLINE,
    UPDATING,
    OFFLINE,
    NEEDS_NEWER_PPM,
    SIGNED_OUT,
    CHECKING,
}

data class SavedServerHealth(
    val serverId: String,
    val badgeKind: SavedServerBadgeKind,
    val badgeLabel: String,
    val version: String? = null,
    val detailLine: String? = null,
    val updateAvailableVersion: String? = null,
)
