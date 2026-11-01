package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.FileDirectoryResult

internal fun dir(name: String, ignored: Boolean = false) = FileDirectoryEntry(name, true, ignored)
internal fun file(name: String) = FileDirectoryEntry(name, false)
internal class MemoryTreeValues : KeyValueStore {
    val data = mutableMapOf<String, String>()
    override fun get(key: String) = data[key]
    override fun put(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
}
internal class TreeServer : FileTreeBackend {
    val directories = mutableMapOf<String, List<FileDirectoryEntry>>()
    val requests = mutableListOf<Pair<String, String>>()
    val batches = mutableListOf<Pair<String, List<String>>>()
    var listing: (suspend (String, String) -> List<FileDirectoryEntry>)? = null
    var batching: (suspend (String, List<String>) -> List<FileDirectoryResult>)? = null
    override suspend fun list(project: String, path: String): List<FileDirectoryEntry> {
        requests += project to path
        return listing?.invoke(project, path) ?: directories[path] ?: emptyList()
    }
    override suspend fun listBatch(project: String, paths: List<String>): List<FileDirectoryResult> {
        batches += project to paths.toList()
        return batching?.invoke(project, paths) ?: paths.reversed().map {
            directories[it]?.let { entries -> FileDirectoryResult(it, entries) } ?: FileDirectoryResult(it, error = "Missing $it")
        }
    }
}
