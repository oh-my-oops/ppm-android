package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.FileTransferResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Creates an empty file or folder; the server ignores content on this endpoint. */
suspend fun ApiClient.createProjectFile(project: String, path: String, isDirectory: Boolean = false) {
    call("POST", "${fileMutationPath(project)}/create", body = buildJsonObject {
        put("path", path)
        put("type", if (isDirectory) "directory" else "file")
    })
}

suspend fun ApiClient.renameProjectFile(project: String, oldPath: String, newPath: String) {
    call("POST", "${fileMutationPath(project)}/rename", body = buildJsonObject {
        put("oldPath", oldPath); put("newPath", newPath)
    })
}

/** Permanent recursive deletion, as the project tree does; never uses the OS trash route. */
suspend fun ApiClient.deleteProjectFile(project: String, path: String) {
    call("DELETE", "${fileMutationPath(project)}/delete", body = buildJsonObject { put("path", path) })
}

/** Both paths are project-relative FULL paths, not a containing destination directory. */
suspend fun ApiClient.moveProjectFile(project: String, source: String, destination: String): FileTransferResult =
    projectTransfer(project, source, destination, move = true)

suspend fun ApiClient.copyProjectFile(project: String, source: String, destination: String): FileTransferResult =
    projectTransfer(project, source, destination, move = false)

/** Both paths are host-absolute FULL paths; the server reports collisions without overwriting. */
suspend fun ApiClient.fsMove(source: String, destination: String): FileTransferResult =
    filesystemTransfer(source, destination, move = true)

suspend fun ApiClient.fsCopy(source: String, destination: String): FileTransferResult =
    filesystemTransfer(source, destination, move = false)

/** Same absolute/relative routing as code-editor.tsx, including Windows drive paths. */
suspend fun ApiClient.writeFile(project: String?, path: String, content: String) {
    val route = if (FILE_MUTATION_ABSOLUTE.containsMatchIn(path)) "/api/fs/write" else {
        require(!project.isNullOrEmpty()) { "A relative file path needs a project." }
        "${fileMutationPath(project)}/write"
    }
    call("PUT", route, body = buildJsonObject { put("path", path); put("content", content) })
}

private suspend fun ApiClient.projectTransfer(project: String, source: String, destination: String, move: Boolean): FileTransferResult {
    val result = call("POST", "${fileMutationPath(project)}/${if (move) "move" else "copy"}", body = transferBody(source, destination))
    return FileTransferResult.fromProject(result, if (move) "moved" else "copied")
        ?: throw PpmError.BadResponse("The file transfer result could not be read.")
}

private suspend fun ApiClient.filesystemTransfer(source: String, destination: String, move: Boolean): FileTransferResult {
    val result = call("POST", "/api/fs/${if (move) "move" else "copy"}", body = transferBody(source, destination))
    return FileTransferResult.fromFilesystem(result)
        ?: throw PpmError.BadResponse("The file transfer result could not be read.")
}

private fun transferBody(source: String, destination: String) = buildJsonObject {
    put("source", source); put("destination", destination)
}
private fun fileMutationPath(project: String) = "/api/project/${ApiClient.escape(project)}/files"
private val FILE_MUTATION_ABSOLUTE = Regex("""^(/|[A-Za-z]:[/\\])""")
