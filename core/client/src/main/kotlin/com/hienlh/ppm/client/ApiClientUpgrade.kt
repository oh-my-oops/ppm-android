package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.UpgradeResult
import com.hienlh.ppm.protocol.UpgradeStatus

/**
 * `GET /api/upgrade`. [refresh] has the server ask the registry instead of answering from its
 * cache, as the web does when the version popover opens; the minute's poll does not.
 */
suspend fun ApiClient.upgradeStatus(refresh: Boolean = false): UpgradeStatus {
    val json = call("GET", "/api/upgrade", if (refresh) listOf("refresh" to "1") else emptyList())
    return UpgradeStatus.from(json) ?: throw PpmError.BadResponse(ApiClient.NOT_PPM)
}

/** `POST /api/upgrade/apply`: installs the newest version, then has the supervisor restart into it. */
suspend fun ApiClient.applyUpgrade(): UpgradeResult =
    UpgradeResult.from(call("POST", "/api/upgrade/apply")) ?: throw PpmError.BadResponse(ApiClient.NOT_PPM)
