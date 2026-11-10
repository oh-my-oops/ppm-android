package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient

/**
 * Classifies server addresses so the Connect screen can warn before sending a password over
 * unencrypted plain HTTP to a public internet host (Requirement 5.2, Phone 2 C6).
 *
 * Safe for plain HTTP without warning:
 * - Loopback (`localhost`, `*.localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0`)
 * - Private LAN (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `*.local`, `*.lan`, `*.home.arpa`, IPv6 ULA/link-local)
 * - Tailscale (`100.64.0.0/10`, `*.ts.net`, `fd7a:115c:a1e0::/48`)
 * - Any `https://` address
 */
object AddressSafety {
    const val PLAIN_HTTP_TITLE = "Plain HTTP"
    const val PLAIN_HTTP_BANNER_BODY =
        "Your password and everything you do here cross the network unencrypted. Fine at home or over Tailscale; from anywhere else, use the https address of PPM's tunnel."
    const val PLAIN_HTTP_CONFIRM_TITLE = "Unencrypted HTTP connection"
    const val PLAIN_HTTP_CONFIRM_MESSAGE =
        "Your password would cross the internet unencrypted over plain HTTP. Switch to an https:// address, or confirm only if you trust this network."
    const val PLAIN_HTTP_CONFIRM_ACTION = "Send password anyway"

    enum class HostKind {
        LOOPBACK,
        PRIVATE_LAN,
        TAILSCALE,
        PUBLIC,
    }

    data class Inspection(
        val normalizedUrl: String,
        val scheme: String,
        val host: String,
        val hostKind: HostKind,
        val isHttps: Boolean,
        val needsPlainHttpWarning: Boolean,
    )

    /**
     * Parses [urlOrAddress] using the same rules as [ApiClient.normalizedServerUrl] and classifies
     * its host and transport security. Returns `null` when [urlOrAddress] is blank or not a valid
     * HTTP/HTTPS server address.
     */
    fun inspect(urlOrAddress: String): Inspection? {
        val httpUrl = ApiClient.normalizedServerUrl(urlOrAddress) ?: return null
        val scheme = httpUrl.scheme.lowercase()
        val isHttps = scheme == "https"
        val host = httpUrl.host.removeSurrounding("[", "]").trimEnd('.').lowercase()
        if (host.isEmpty()) return null
        val kind = classifyHost(host)
        val needsWarning = !isHttps && kind == HostKind.PUBLIC
        return Inspection(
            normalizedUrl = httpUrl.toString().trimEnd('/'),
            scheme = scheme,
            host = host,
            hostKind = kind,
            isHttps = isHttps,
            needsPlainHttpWarning = needsWarning,
        )
    }

    /**
     * True when [urlOrAddress] is a valid plain-HTTP URL targeting a public internet address
     * (neither loopback, private RFC 1918 LAN, nor Tailscale `100.64.0.0/10` / `*.ts.net`).
     */
    fun needsPlainHttpWarning(urlOrAddress: String): Boolean =
        inspect(urlOrAddress)?.needsPlainHttpWarning == true

    /** Alias of [needsPlainHttpWarning]. */
    fun isInsecurePublicHttp(urlOrAddress: String): Boolean =
        needsPlainHttpWarning(urlOrAddress)

    /**
     * True when [urlOrAddress] uses `https://` or targets a loopback, private LAN, or Tailscale host.
     */
    fun isSafeForPlainHttp(urlOrAddress: String): Boolean {
        val inspection = inspect(urlOrAddress) ?: return true
        return !inspection.needsPlainHttpWarning
    }

    /**
     * Classifies a bare hostname or IP literal into [HostKind].
     */
    fun classifyHost(rawHost: String): HostKind {
        val host = rawHost.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
        if (host.isEmpty()) return HostKind.LOOPBACK

        if (host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0") {
            return HostKind.LOOPBACK
        }
        if (isIpv6Loopback(host)) {
            return HostKind.LOOPBACK
        }

        val ipv4 = parseIpv4Octets(host)
        if (ipv4 != null) {
            val a = ipv4[0]
            val b = ipv4[1]
            return when {
                a == 127 || a == 0 -> HostKind.LOOPBACK
                a == 10 -> HostKind.PRIVATE_LAN
                a == 172 && b in 16..31 -> HostKind.PRIVATE_LAN
                a == 192 && b == 168 -> HostKind.PRIVATE_LAN
                a == 169 && b == 254 -> HostKind.PRIVATE_LAN
                a == 100 && b in 64..127 -> HostKind.TAILSCALE
                else -> HostKind.PUBLIC
            }
        }

        if (host == "ts.net" || host.endsWith(".ts.net") || host.startsWith("fd7a:115c:a1e0:")) {
            return HostKind.TAILSCALE
        }

        if (isIpv6PrivateOrLinkLocal(host)) {
            return HostKind.PRIVATE_LAN
        }

        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) {
            return HostKind.PRIVATE_LAN
        }

        return HostKind.PUBLIC
    }

    private fun parseIpv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isIpv6Loopback(host: String): Boolean {
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host.startsWith("::ffff:")) {
            val mapped = host.removePrefix("::ffff:")
            val ipv4 = parseIpv4Octets(mapped)
            if (ipv4 != null && ipv4[0] == 127) return true
        }
        return false
    }

    private fun isIpv6PrivateOrLinkLocal(host: String): Boolean {
        if (!host.contains(':')) return false
        return host.startsWith("fc") ||
            host.startsWith("fd") ||
            host.startsWith("fe8") ||
            host.startsWith("fe9") ||
            host.startsWith("fea") ||
            host.startsWith("feb")
    }
}
