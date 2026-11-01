package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string

/** Mammoth's HTML is untrusted; the viewer must apply the web allow-list before displaying it. */
suspend fun ApiClient.readDocxHtml(project: String?, path: String): String {
    val absolute = Regex("""^(/|[A-Za-z]:[/\\])""").containsMatchIn(path)
    require(absolute || !project.isNullOrEmpty()) { "A relative file path needs a project." }
    val base = if (absolute) "/api/fs" else "/api/project/${ApiClient.escape(project!!)}/files"
    return call("GET", "$base/docx-html", listOf("path" to path))["html"].string
        ?: throw PpmError.BadResponse("The document conversion could not be read.")
}
