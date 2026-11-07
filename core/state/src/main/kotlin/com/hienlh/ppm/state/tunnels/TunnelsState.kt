package com.hienlh.ppm.state.tunnels

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement

data class TunnelEntry(
    val pid: Int,
    val port: Int? = null,
    val url: String? = null,
    val source: String = "ppm",
    val protected: Boolean = false,
    val status: String = "running",
    val startedAt: Long? = null,
    val via: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): TunnelEntry? {
            val pid = json["pid"].int ?: return null
            return TunnelEntry(
                pid = pid,
                port = json["port"].int,
                url = json["url"].string,
                source = json["source"].string ?: "ppm",
                protected = json["protected"].bool ?: false,
                status = json["status"].string ?: "running",
                startedAt = json["startedAt"].long,
                via = json["via"].string,
            )
        }
    }
}

data class TailscaleAvailability(
    val available: Boolean,
    val dnsName: String = "",
    val reason: String = "",
) {
    companion object {
        fun from(json: JsonElement?): TailscaleAvailability {
            val ts = json?.get("tailscale") ?: json
            val avail = ts["available"].bool ?: false
            return TailscaleAvailability(
                available = avail,
                dnsName = ts["dnsName"].string.orEmpty(),
                reason = ts["reason"].string ?: "Could not check Tailscale on the host",
            )
        }
    }
}

data class NamedTunnelStatus(
    val mode: String = "quick",
    val hostname: String? = null,
    val certState: String? = null,
    val tunnelWarning: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): NamedTunnelStatus? {
            if (json == null) return null
            return NamedTunnelStatus(
                mode = json["mode"].string ?: "quick",
                hostname = json["hostname"].string,
                certState = json["certState"].string,
                tunnelWarning = json["tunnelWarning"].string,
            )
        }
    }
}

data class SetupState(val status: String, val done: Boolean, val warn: Boolean = false)

object TunnelsModel {
    fun parseList(json: JsonElement?): List<TunnelEntry> =
        json.array.orEmpty().mapNotNull(TunnelEntry::from)

    fun tailscaleSetup(t: TailscaleAvailability?): SetupState {
        if (t == null) return SetupState("Checking…", done = false)
        return if (t.available) SetupState(t.dnsName, done = true) else SetupState(t.reason, done = false)
    }

    fun cloudflareSetup(s: NamedTunnelStatus?, error: Boolean = false): SetupState {
        if (error) return SetupState("Could not check Cloudflare", done = false, warn = true)
        if (s == null) return SetupState("Checking…", done = false)
        if (s.certState == "invalid" || s.certState == "mismatch") {
            return SetupState("Cloudflare sign-in needed", done = false, warn = true)
        }
        if (s.mode == "named" && !s.hostname.isNullOrEmpty()) {
            return if (!s.tunnelWarning.isNullOrEmpty()) {
                SetupState("${s.hostname} is not in use right now", done = true, warn = true)
            } else {
                SetupState(s.hostname, done = true)
            }
        }
        return SetupState("No domain set up", done = false)
    }
}
