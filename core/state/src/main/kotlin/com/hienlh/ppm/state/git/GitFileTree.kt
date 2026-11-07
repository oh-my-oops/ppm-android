package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangedFile

data class GitTreeNode(
    val name: String,
    val path: String,
    val children: MutableList<GitTreeNode> = mutableListOf(),
    val file: ChangedFile? = null,
    var fileCount: Int = 0,
)

/**
 * Pure tree builder for the Source Control panel's Tree view (`src/web/lib/git-file-tree.ts`).
 */
object GitFileTree {
    fun buildTree(files: List<ChangedFile>): List<GitTreeNode> {
        val root: MutableList<GitTreeNode> = mutableListOf()

        for (entry in files) {
            val parts = entry.path.split('/')
            var current = root

            for (i in parts.indices) {
                val part = parts[i]
                val partPath = parts.subList(0, i + 1).joinToString("/")
                val isLeaf = i == parts.lastIndex

                if (isLeaf) {
                    current += GitTreeNode(
                        name = part,
                        path = partPath,
                        children = mutableListOf(),
                        file = entry,
                        fileCount = 1,
                    )
                } else {
                    var folder = current.firstOrNull { it.file == null && it.name == part }
                    if (folder == null) {
                        folder = GitTreeNode(
                            name = part,
                            path = partPath,
                            children = mutableListOf(),
                            file = null,
                            fileCount = 0,
                        )
                        current += folder
                    }
                    current = folder.children
                }
            }
        }

        val sorted = sortTree(root)
        countFiles(sorted)
        return compactTree(sorted)
    }

    private fun sortTree(nodes: List<GitTreeNode>): List<GitTreeNode> =
        nodes.map { node ->
            if (node.children.isNotEmpty()) {
                node.copy(children = sortTree(node.children).toMutableList())
            } else {
                node
            }
        }.sortedWith { a, b ->
            val aFolder = a.file == null
            val bFolder = b.file == null
            if (aFolder != bFolder) {
                if (aFolder) -1 else 1
            } else {
                a.name.compareTo(b.name, ignoreCase = true)
            }
        }

    private fun countFiles(nodes: List<GitTreeNode>): Int {
        var total = 0
        for (node in nodes) {
            if (node.file != null) {
                total += 1
            } else {
                node.fileCount = countFiles(node.children)
                total += node.fileCount
            }
        }
        return total
    }

    /** Collapse single-child folder chains (`src` -> `web` -> `hooks` becomes `src/web/hooks`). */
    fun compactTree(nodes: List<GitTreeNode>): List<GitTreeNode> =
        nodes.map { node ->
            var cur = node
            while (cur.file == null && cur.children.size == 1 && cur.children[0].file == null) {
                val child = cur.children[0]
                cur = GitTreeNode(
                    name = "${cur.name}/${child.name}",
                    path = child.path,
                    children = child.children,
                    file = null,
                    fileCount = cur.fileCount,
                )
            }
            if (cur.children.isNotEmpty()) {
                cur = cur.copy(children = compactTree(cur.children).toMutableList())
            }
            cur
        }

    /** Collect all [ChangedFile]s under a folder node. */
    fun collectFiles(node: GitTreeNode): List<ChangedFile> {
        if (node.file != null) return listOf(node.file)
        val out = mutableListOf<ChangedFile>()
        for (child in node.children) {
            out += collectFiles(child)
        }
        return out
    }

    /**
     * Split a compacted folder name (`"src/web/components"`) into `prefix` (`"src/web/"`)
     * and `leaf` (`"components"`) for dimmed prefix + bold leaf rendering (`git-change-tree.tsx`).
     */
    fun splitFolderName(name: String): Pair<String, String> {
        val slashIdx = name.lastIndexOf('/')
        return if (slashIdx >= 0) {
            name.substring(0, slashIdx + 1) to name.substring(slashIdx + 1)
        } else {
            "" to name
        }
    }
}
