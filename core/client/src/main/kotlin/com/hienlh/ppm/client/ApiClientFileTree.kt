package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.FileContent
import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.FileDirectoryResult
import com.hienlh.ppm.protocol.array
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Lazy project tree endpoint; never asks for the recursive tree or flat index. */
suspend fun ApiClient.listProjectDirectory(project: String, path: String = ""): List<FileDirectoryEntry> {
    val data = call("GET", "${fileTreePath(project)}/list", listOf("path" to path))
    return data.array?.mapNotNull(FileDirectoryEntry::from) ?: throw PpmError.BadResponse("The directory list could not be read.")
}

/** The caller chunks restoration: the server accepts at most 50 paths, root included. */
suspend fun ApiClient.listProjectDirectories(project: String, paths: List<String>): List<FileDirectoryResult> {
    require(paths.size in 1..50) { "A directory batch must contain 1–50 paths." }
    val data = call("POST", "${fileTreePath(project)}/list-batch", body = buildJsonObject {
        put("paths", JsonArray(paths.map(::JsonPrimitive)))
    })
    return data.array?.mapNotNull(FileDirectoryResult::from) ?: throw PpmError.BadResponse("The directory batch could not be read.")
}

/** Typed counterpart of readProjectFile; existing string-returning APIs stay compatible. */
suspend fun ApiClient.readProjectFileContent(project: String, path: String): FileContent =
    FileContent.from(call("GET", "${fileTreePath(project)}/read", listOf("path" to path)))
        ?: throw PpmError.BadResponse("The file content could not be read.")

/** Viewer routing, like rawFile: absolute host paths use fs/read, relative paths need a project. */
suspend fun ApiClient.readFileContent(project: String?, path: String): FileContent {
    if (!FILE_TREE_ABSOLUTE.containsMatchIn(path)) {
        require(!project.isNullOrEmpty()) { "A relative file path needs a project." }
        return readProjectFileContent(project, path)
    }
    return FileContent.from(call("GET", "/api/fs/read", listOf("path" to path)))
        ?: throw PpmError.BadResponse("The file content could not be read.")
}

private fun fileTreePath(project: String) = "/api/project/${ApiClient.escape(project)}/files"
private val FILE_TREE_ABSOLUTE = Regex("""^(/|[A-Za-z]:[/\\])""")
