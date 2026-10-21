package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.protocol.FileIndexAnswer
import com.hienlh.ppm.protocol.array
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `GET …/files/index?max=`: every file and folder of the project, or `tooLarge` past [max]
 * entries. Read off the main thread: a list of fifty thousand is megabytes.
 */
suspend fun ApiClient.fileIndex(project: String, max: Int): FileIndexAnswer {
    val data = call("GET", "${indexPath(project)}/index", listOf("max" to max.toString()))
    return withContext(Dispatchers.Default) { FileIndexAnswer.from(data) } ?: throw PpmError.BadResponse("The file list could not be read.")
}

/**
 * `GET …/files/index/search`: the best [limit] entries for [query], best first, searched on the
 * server; [directories] lists folders too (`kind=all`).
 */
suspend fun ApiClient.searchFileIndex(project: String, query: String, directories: Boolean, limit: Int): List<FileEntry> {
    val params = listOf("q" to query, "kind" to if (directories) "all" else "file", "limit" to limit.toString())
    return call("GET", "${indexPath(project)}/index/search", params).array.orEmpty().mapNotNull(FileEntry::from)
}

private fun indexPath(project: String) = "/api/project/${ApiClient.escape(project)}/files"
