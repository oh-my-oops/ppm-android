package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `POST /api/html-preview`: an address the page and the files beside it are served at for an
 * hour, with no credentials in it, which a sandboxed frame can load; absolute, against [ApiClient.baseUrl].
 */
suspend fun ApiClient.htmlPreviewUrl(filePath: String, projectName: String?): String {
    val data = call(
        "POST", "/api/html-preview",
        body = buildJsonObject {
            put("filePath", filePath)
            projectName?.let { put("projectName", it) }
        },
    )
    val path = data["url"].string ?: throw PpmError.BadResponse("The server sent no preview address.")
    return baseUrl.resolve(path)?.toString() ?: throw PpmError.BadResponse("The server sent no preview address.")
}
