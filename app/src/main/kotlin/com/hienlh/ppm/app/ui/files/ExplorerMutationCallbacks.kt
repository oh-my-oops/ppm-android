package com.hienlh.ppm.app.ui.files

/**
 * Bind these callbacks to one server/project's FileMutations instance in ExplorerBody.
 * All paths (including [cutPaths]) are project-relative; root is "". create/rename receive
 * the trimmed input name, not an absolute path. Successful mutations must invalidate the
 * affected tree levels/index/documents in the core adapter; failures must throw, including
 * partial-paste failures. The UI keeps the input/dialog and displays that exception message.
 * Clipboard ownership stays in core, so it survives this view closing and project switches.
 */
data class ExplorerMutationCallbacks(
    val project: String,
    val create: suspend (parent: String, name: String, directory: Boolean) -> Unit,
    val rename: suspend (path: String, name: String) -> Unit,
    val delete: suspend (path: String) -> Unit,
    val cut: (paths: List<String>) -> Unit,
    val copy: (paths: List<String>) -> Unit,
    val paste: suspend (directory: String) -> Unit,
    val cutPaths: Set<String> = emptySet(),
    val canPaste: Boolean = false,
    val busy: Boolean = false,
)
