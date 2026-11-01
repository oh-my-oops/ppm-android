package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

suspend fun ApiClient.addProject(path: String, name: String? = null): ProjectInfo =
    projectResult(call("POST", "/api/projects", body = buildJsonObject {
        put("path", path); name?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
    }))

suspend fun ApiClient.suggestProjectDirectories(query: String, root: String? = null): List<HostPlace> =
    call("GET", "/api/projects/suggest-dirs", buildList { add("q" to query); root?.let { add("path" to it) } })
        .array.orEmpty().mapNotNull(HostPlace::from)

suspend fun ApiClient.lastCloneDirectory(): String? = call("GET", "/api/projects/last-clone-dir")["dir"].string

data class ClonedProject(val path: String, val project: ProjectInfo)

/** Mutations use the existing 600-second client, so long clones do not inherit the GET timeout. */
suspend fun ApiClient.cloneProject(url: String, directory: String, name: String? = null): ClonedProject {
    val data = call("POST", "/api/projects/git/clone", body = buildJsonObject {
        put("url", url); put("targetDir", directory); name?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
    })
    val path = data["path"].string ?: throw PpmError.BadResponse("The cloned path could not be read.")
    return ClonedProject(path, projectResult(data["project"]))
}

suspend fun ApiClient.renameProject(name: String, newName: String): ProjectInfo =
    projectResult(call("PATCH", projectManagementPath(name), body = buildJsonObject { put("name", newName) }))

/** Removes registration only; no host files are deleted by this route. */
suspend fun ApiClient.removeProject(name: String) { call("DELETE", projectManagementPath(name)) }

suspend fun ApiClient.setProjectColor(name: String, color: String?): ProjectInfo =
    projectResult(call("PATCH", projectManagementPath(name) + "/color", body = buildJsonObject {
        put("color", color?.let(::JsonPrimitive) ?: JsonNull)
    }))

/** Caller supplies the same 128px WebP as web's resizeImageToWebp. */
suspend fun ApiClient.setProjectImage(name: String, webp: ByteArray): ProjectInfo {
    require(webp.size <= 2_000_000) { "Image too large (max 2MB)" }
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", "avatar.webp", webp.toRequestBody("image/webp".toMediaType())).build()
    return projectResult(send(PpmHttp.client, request("POST", projectManagementPath(name) + "/image", body = body)))
}

suspend fun ApiClient.removeProjectImage(name: String): ProjectInfo =
    projectResult(call("DELETE", projectManagementPath(name) + "/image"))

private fun projectManagementPath(name: String) = "/api/projects/${ApiClient.escape(name)}"
private fun projectResult(value: kotlinx.serialization.json.JsonElement?): ProjectInfo =
    value?.let(ProjectInfo::from) ?: throw PpmError.BadResponse("The project could not be read.")
