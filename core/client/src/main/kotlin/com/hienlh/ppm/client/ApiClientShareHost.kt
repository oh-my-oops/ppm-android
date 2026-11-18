package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The review-only address (`/api/share-host`, `api-share-host.ts`): a public address for design
 * review links that opens the review pages and nothing else of PPM. A switch only writes config
 * and the supervisor applies it, so every change answers whether it was applied.
 */

/** `GET /api/share-host/status`: what the user asked for, and what the supervisor runs. */
data class ShareHostStatus(
    val enabled: Boolean,
    /** `quick`, `named` or `shared`. */
    val mode: String,
    val hostname: String?,
    val tunnelName: String?,
    /** A supervisor runs that can apply a change without a restart. */
    val canApply: Boolean,
    val supervised: Boolean,
    val running: Boolean,
    /** Reviewers can open [url] now. */
    val connected: Boolean,
    val url: String?,
    val warning: String?,
    val ppmTunnel: PpmTunnel,
    /** `none`, `invalid`, `ok` or `mismatch`. */
    val certState: String,
    val authEnabled: Boolean,
) {
    data class PpmTunnel(val named: Boolean, val hostname: String?, val enabled: Boolean)

    companion object {
        fun fromJson(json: JsonElement?): ShareHostStatus? {
            if (json !is JsonObject) return null
            val ppm = json["ppmTunnel"]
            return ShareHostStatus(
                enabled = json["enabled"].bool == true,
                mode = json["mode"].string?.takeIf { it == "named" || it == "shared" } ?: "quick",
                hostname = json["hostname"].string?.takeIf { it.isNotEmpty() },
                tunnelName = json["tunnelName"].string,
                canApply = json["canApply"].bool == true,
                supervised = json["supervised"].bool == true,
                running = json["running"].bool == true,
                connected = json["connected"].bool == true,
                url = json["url"].string?.takeIf { it.isNotEmpty() },
                warning = json["warning"].string?.takeIf { it.isNotEmpty() },
                ppmTunnel = PpmTunnel(
                    named = ppm["named"].bool == true,
                    hostname = ppm["hostname"].string?.takeIf { it.isNotEmpty() },
                    enabled = ppm["enabled"].bool == true,
                ),
                certState = json["certState"].string ?: "none",
                authEnabled = json["authEnabled"].bool != false,
            )
        }
    }
}

/** Whether the running supervisor took a change, and what to tell the user when it did not. */
data class ShareHostApplied(val applied: Boolean, val message: String?) {
    companion object {
        fun fromJson(json: JsonElement?): ShareHostApplied =
            if (json["applied"].bool == true) ShareHostApplied(true, null)
            else ShareHostApplied(false, json["message"].string ?: "The change is saved but not applied yet.")
    }
}

data class ShareHostSetupResult(val hostname: String, val tunnelName: String, val applied: ShareHostApplied)

data class ShareHostZone(val zone: String, val proposedHostname: String)

suspend fun ApiClient.shareHostStatus(): ShareHostStatus =
    ShareHostStatus.fromJson(call("GET", "/api/share-host/status"))
        ?: throw PpmError.BadResponse("The review address's state could not be read.")

suspend fun ApiClient.shareHostZone(): ShareHostZone {
    val data = call("GET", "/api/share-host/zone")
    val zone = data["zone"].string?.takeIf { it.isNotEmpty() } ?: throw PpmError.BadResponse("No Cloudflare domain in the answer.")
    return ShareHostZone(zone, data["proposedHostname"].string.orEmpty())
}

suspend fun ApiClient.shareHostSetEnabled(enabled: Boolean): ShareHostApplied =
    ShareHostApplied.fromJson(call("POST", "/api/share-host/enabled", body = buildJsonObject { put("enabled", enabled) }))

/** Back to a temporary trycloudflare address. */
suspend fun ApiClient.shareHostUseTemporary(): ShareHostApplied =
    ShareHostApplied.fromJson(call("POST", "/api/share-host/quick"))

/** [mode]: `named` (a tunnel of its own) or `shared` (a second hostname on PPM's tunnel). */
suspend fun ApiClient.shareHostSetup(mode: String, hostname: String): ShareHostSetupResult {
    val data = call("POST", "/api/share-host/setup", body = buildJsonObject {
        put("mode", mode)
        put("hostname", hostname)
    })
    return ShareHostSetupResult(
        hostname = data["hostname"].string ?: hostname,
        tunnelName = data["tunnelName"].string.orEmpty(),
        applied = ShareHostApplied.fromJson(data["applied"]),
    )
}
