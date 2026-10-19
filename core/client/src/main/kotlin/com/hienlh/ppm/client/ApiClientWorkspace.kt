package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonObject

/** A project's layout as the server keeps it for every device, with the ISO time it was saved. */
data class SavedWorkspace(val layout: JsonObject, val updatedAt: String)

/** The server's copy of [project]'s layout; null when no device has saved one. */
suspend fun ApiClient.workspace(project: String): SavedWorkspace? {
    val data = call("GET", workspacePath(project)) as? JsonObject ?: return null
    val layout = data["layout"] as? JsonObject ?: return null
    return SavedWorkspace(layout, data["updatedAt"].string.orEmpty())
}

/** Replaces the server's copy; answers the time the server stamped it with. */
suspend fun ApiClient.putWorkspace(project: String, layout: JsonObject): String? =
    call("PUT", workspacePath(project), body = JsonObject(mapOf("layout" to layout)))["updatedAt"].string

private fun workspacePath(project: String) = "/api/project/${ApiClient.escape(project)}/workspace"
