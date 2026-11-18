package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * A design's share links (`/api/project/:name/designs/:slug/shares`) and the public address they
 * open at (`/api/design-shares/address`), as `api-design-shares.ts` and `design-share-types.ts`.
 * Every link carries only its `/s/<code>` path; the full URL is built when it is shown.
 */

/** `SHARE_LIMITS`: what the server and the Share dialog both enforce. */
object DesignShareLimits {
    /** Links per design, revoked ones included until they are deleted. */
    const val MAX_LINKS_PER_DESIGN = 20

    /** Characters (code points) in a link's label. */
    const val MAX_LABEL_CHARS = 60

    /** Furthest expiry the owner may set, from now. */
    const val MAX_EXPIRY_MS = 366L * 24 * 60 * 60 * 1000
}

/**
 * One link (`DesignShareLink`). [status]: `active`, `expired`, `revoked`, `needs-new-code`,
 * `design-missing` or `signin-off`, as the server decides it. [path] is `/s/<code>`, or null when
 * no code can be given out.
 */
data class DesignShareLink(
    val id: String,
    val label: String,
    val snapshotId: String,
    /** When the pinned snapshot was taken (ISO), or null when it is gone. */
    val snapshotCreatedAt: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Epoch ms, or null for a link that never expires. */
    val expiresAt: Long?,
    val revokedAt: Long?,
    val status: String,
    val path: String?,
) {
    companion object {
        fun fromJson(json: JsonElement?): DesignShareLink? {
            if (json !is JsonObject) return null
            val id = json["id"].string?.takeIf { it.isNotEmpty() } ?: return null
            return DesignShareLink(
                id = id,
                label = json["label"].string.orEmpty(),
                snapshotId = json["snapshotId"].string.orEmpty(),
                snapshotCreatedAt = json["snapshotCreatedAt"].string,
                createdAt = json["createdAt"].long ?: 0L,
                updatedAt = json["updatedAt"].long ?: 0L,
                expiresAt = json["expiresAt"].long,
                revokedAt = json["revokedAt"].long,
                status = json["status"].string ?: "active",
                path = json["path"].string?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** Files that differ between the old and the new pin, relative to the design folder. */
data class DesignShareChangedFiles(
    val added: List<String>,
    val removed: List<String>,
    val changed: List<String>,
    /** More files differed than the server names. */
    val truncated: Boolean,
) {
    val count: Int get() = added.size + removed.size + changed.size

    companion object {
        fun fromJson(json: JsonElement?): DesignShareChangedFiles? {
            if (json !is JsonObject) return null
            fun names(key: String) = json[key].array.orEmpty().mapNotNull { it.string }
            return DesignShareChangedFiles(names("added"), names("removed"), names("changed"), json["truncated"].bool == true)
        }
    }
}

/** `UpdateDesignShareResult`: the link as it is now, and after a re-pin what changed (null: not comparable). */
data class DesignShareUpdateResult(val link: DesignShareLink, val changedFiles: DesignShareChangedFiles?)

/**
 * What a link may change (`UpdateDesignShareInput`); only what is set is sent. [expiry] holds the
 * new `expiresAt`, its value null for a link that never expires.
 */
data class DesignSharePatch(
    val label: String? = null,
    val expiry: Expiry? = null,
    val revoke: Boolean = false,
    val repin: Boolean = false,
    val newCode: Boolean = false,
) {
    data class Expiry(val expiresAt: Long?)
}

/** `DesignShareAddress`: the public address a link opens at right now. */
data class DesignShareAddress(
    /** `named`, `quick` or `none`. */
    val kind: String,
    /** Origin without a trailing slash, or null for `none`. */
    val url: String?,
    val warning: String?,
    /** `share-host`: the review-only address; `ppm`: PPM's own public link. */
    val via: String,
    /** The public tunnel master switch. */
    val enabled: Boolean,
    /** PPM sign-in; sharing is refused while it is off. */
    val authEnabled: Boolean,
) {
    companion object {
        fun fromJson(json: JsonElement?): DesignShareAddress? {
            if (json !is JsonObject) return null
            return DesignShareAddress(
                kind = json["kind"].string?.takeIf { it == "named" || it == "quick" } ?: "none",
                url = json["url"].string?.takeIf { it.isNotEmpty() },
                warning = json["warning"].string?.takeIf { it.isNotEmpty() },
                via = if (json["via"].string == "share-host") "share-host" else "ppm",
                enabled = json["enabled"].bool == true,
                authEnabled = json["authEnabled"].bool != false,
            )
        }
    }
}

private fun sharesPath(project: String, slug: String) =
    "/api/project/${ApiClient.escape(project)}/designs/${ApiClient.escape(slug)}/shares"

private fun sharePath(project: String, slug: String, id: String) = "${sharesPath(project, slug)}/${ApiClient.escape(id)}"

/** The design's links, normalised: an answer without a list is an empty one. */
suspend fun ApiClient.designShares(project: String, slug: String): List<DesignShareLink> =
    call("GET", sharesPath(project, slug))["links"].array.orEmpty().mapNotNull(DesignShareLink::fromJson)

/** A new link; [version] is `"current"` (a snapshot taken now) or a snapshot id. [expiresAt] null: never. */
suspend fun ApiClient.designShareCreate(project: String, slug: String, label: String?, expiresAt: Long?, version: String): DesignShareLink {
    val body = buildJsonObject {
        if (!label.isNullOrEmpty()) put("label", label)
        if (expiresAt == null) put("expiresAt", JsonNull) else put("expiresAt", expiresAt)
        put("version", version)
    }
    return DesignShareLink.fromJson(call("POST", sharesPath(project, slug), body = body)["link"])
        ?: throw PpmError.BadResponse("The new link could not be read.")
}

suspend fun ApiClient.designShareUpdate(project: String, slug: String, id: String, patch: DesignSharePatch): DesignShareUpdateResult {
    val body = buildJsonObject {
        patch.label?.let { put("label", it) }
        patch.expiry?.let { e -> if (e.expiresAt == null) put("expiresAt", JsonNull) else put("expiresAt", e.expiresAt) }
        if (patch.revoke) put("revoke", true)
        if (patch.repin) put("repin", true)
        if (patch.newCode) put("newCode", true)
    }
    val data = call("PATCH", sharePath(project, slug, id), body = body)
    val link = DesignShareLink.fromJson(data["link"]) ?: throw PpmError.BadResponse("The link could not be read.")
    return DesignShareUpdateResult(link, DesignShareChangedFiles.fromJson(data["changedFiles"]))
}

/** What "Update to latest" would change, before it is done; null when the pinned version is gone. */
suspend fun ApiClient.designShareChanges(project: String, slug: String, id: String): DesignShareChangedFiles? =
    DesignShareChangedFiles.fromJson(call("GET", "${sharePath(project, slug, id)}/changes")["changedFiles"])

suspend fun ApiClient.designShareDelete(project: String, slug: String, id: String) {
    call("DELETE", sharePath(project, slug, id))
}

/** Every comment thread reviewers on this link started; answers how many went. */
suspend fun ApiClient.designShareDeleteComments(project: String, slug: String, id: String): Int =
    call("DELETE", "${sharePath(project, slug, id)}/comments")["deleted"].int ?: 0

suspend fun ApiClient.designShareAddress(): DesignShareAddress =
    DesignShareAddress.fromJson(call("GET", "/api/design-shares/address"))
        ?: throw PpmError.BadResponse("The public address could not be read.")

/** One version a new link can pin (`DesignSnapshotInfo`): its id, when it was taken and why. */
data class DesignShareVersion(val id: String, val createdAt: String, val reason: String)

/** The design's history as the version picker lists it (`listDesignHistory`). */
suspend fun ApiClient.designShareVersions(project: String, slug: String): List<DesignShareVersion> {
    val data = call("GET", "/api/project/${ApiClient.escape(project)}/designs/${ApiClient.escape(slug)}/history")
    val list = data.array ?: data["snapshots"].array.orEmpty()
    return list.mapNotNull { item ->
        val id = item["id"].string?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        DesignShareVersion(id, item["createdAt"].string.orEmpty(), item["reason"].string.orEmpty())
    }
}
