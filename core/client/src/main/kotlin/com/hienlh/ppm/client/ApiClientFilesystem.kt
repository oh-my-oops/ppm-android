package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFilesystem
import com.hienlh.ppm.protocol.HostFileInfo
import com.hienlh.ppm.protocol.HostDeleteResult
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

suspend fun ApiClient.fsBrowse(path: String, showHidden: Boolean = false): HostDirectory =
    HostDirectory.from(send(PpmHttp.getClient, request("GET", "/api/fs/browse", buildList {
        add("path" to path)
        if (showHidden) add("showHidden" to "true")
    }))) ?: throw PpmError.BadResponse("The directory listing could not be read.")

suspend fun ApiClient.hostFilesystem(refresh: Boolean = false): HostFilesystem =
    HostFilesystem.from(call("GET", "/api/system/host", if (refresh) listOf("refresh" to "true") else emptyList()))
        ?: throw PpmError.BadResponse("Host information could not be read.")

suspend fun ApiClient.fsInfo(path: String): HostFileInfo =
    HostFileInfo.from(call("GET", "/api/fs/stat", listOf("path" to path)))
        ?: throw PpmError.BadResponse("File properties could not be read.")

/** Explicit false: the server's legacy default initializes a repository in a new folder. */
suspend fun ApiClient.fsMkdir(path: String) {
    call("POST", "/api/fs/mkdir", body = buildJsonObject { put("path", path); put("gitInit", false) })
}

suspend fun ApiClient.fsTouch(path: String) {
    call("POST", "/api/fs/touch", body = buildJsonObject { put("path", path) })
}

suspend fun ApiClient.fsRename(path: String, newName: String): String =
    call("POST", "/api/fs/rename", body = buildJsonObject { put("path", path); put("newName", newName) })["to"].string
        ?: throw PpmError.BadResponse("The renamed path could not be read.")

suspend fun ApiClient.fsDelete(path: String, permanent: Boolean = false): HostDeleteResult {
    val data = call("DELETE", "/api/fs/delete", body = buildJsonObject { put("path", path); put("permanent", permanent) })
    return HostDeleteResult(data["removed"].string ?: throw PpmError.BadResponse("The deleted path could not be read."),
        data["trashed"].bool == true, data["permanent"].bool == true)
}

/** Legacy picker route moves the directory to host Trash; the server enforces protected roots. */
suspend fun ApiClient.fsRemoveDirectory(path: String) {
    call("DELETE", "/api/fs/rmdir", body = buildJsonObject { put("path", path) })
}
