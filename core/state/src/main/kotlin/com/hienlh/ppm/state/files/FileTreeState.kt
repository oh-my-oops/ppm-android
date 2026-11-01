package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.listProjectDirectories
import com.hienlh.ppm.client.listProjectDirectory
import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.FileDirectoryResult

interface FileTreeBackend {
    suspend fun list(project: String, path: String): List<FileDirectoryEntry>
    suspend fun listBatch(project: String, paths: List<String>): List<FileDirectoryResult>

    companion object {
        fun api(api: ApiClient): FileTreeBackend = object : FileTreeBackend {
            override suspend fun list(project: String, path: String) = api.listProjectDirectory(project, path)
            override suspend fun listBatch(project: String, paths: List<String>) = api.listProjectDirectories(project, paths)
        }
    }
}

data class FileTreeNode(val name: String, val path: String, val isDirectory: Boolean, val isIgnored: Boolean)
data class FileTreeRow(val node: FileTreeNode, val depth: Int)

/** Cached directory levels retain their children when another level refreshes. Root is always visible. */
data class FileTreeState(
    val project: String? = null,
    val directories: Map<String, List<FileTreeNode>> = emptyMap(),
    val loadedPaths: Set<String> = emptySet(),
    val loadingPaths: Set<String> = emptySet(),
    val expandedPaths: Set<String> = emptySet(),
    val selectedFiles: List<String> = emptyList(),
    val focusedPath: String? = null,
    val errors: Map<String, String> = emptyMap(),
) {
    val loading: Boolean get() = "" in loadingPaths
    val error: String? get() = errors[""]
    val tree: List<FileTreeNode> get() = directories[""].orEmpty()

    /** Server order is already directory-first and locale-sorted. No compact-folder joining. */
    val visibleRows: List<FileTreeRow> get() = buildList {
        val pending = ArrayDeque<FileTreeRow>()
        tree.asReversed().forEach { pending.addLast(FileTreeRow(it, 0)) }
        while (pending.isNotEmpty()) {
            val row = pending.removeLast()
            add(row)
            if (row.node.isDirectory && row.node.path in expandedPaths) {
                directories[row.node.path].orEmpty().asReversed().forEach {
                    pending.addLast(FileTreeRow(it, row.depth + 1))
                }
            }
        }
    }

    fun node(path: String): FileTreeNode? = directories[parentPath(path)]?.firstOrNull { it.path == path }
}

internal fun parentPath(path: String) = path.substringBeforeLast('/', "")
internal fun pathDepth(path: String) = if (path.isEmpty()) 0 else path.count { it == '/' } + 1
internal fun below(path: String, parent: String) = path == parent || path.startsWith("$parent/")
internal fun nodes(entries: List<FileDirectoryEntry>, parent: String) = entries.map {
    FileTreeNode(it.name, if (parent.isEmpty()) it.name else "$parent/${it.name}", it.isDirectory, it.isIgnored)
}
