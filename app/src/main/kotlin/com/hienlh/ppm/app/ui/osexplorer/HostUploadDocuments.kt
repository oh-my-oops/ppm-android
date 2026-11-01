package com.hienlh.ppm.app.ui.osexplorer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.hienlh.ppm.client.HostUploadSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun uploadDocumentName(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 32 }) {
        "Invalid document filename."
    }
    return name
}

internal fun uploadDocument(resolver: ContentResolver, uri: Uri): HostUploadSource {
    var name: String? = null
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && !cursor.isNull(column)) size = cursor.getLong(column).takeIf { it >= 0 }
        }
    }
    val filename = uploadDocumentName(requireNotNull(name) { "The selected document has no filename." })
    return documentSource(resolver, uri, filename, size, resolver.getType(uri))
}

/** Mirrors webkitRelativePath: chosen-folder/sub/file. Empty folders have no upload body. */
internal suspend fun uploadDocumentTree(resolver: ContentResolver, tree: Uri): List<HostUploadSource> {
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
    val rootName = resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        check(it.moveToFirst()) { "Cannot read the selected folder." }
        uploadDocumentName(it.getString(0))
    } ?: error("Cannot read the selected folder.")
    val pending = ArrayDeque<Pair<String, String>>()
    pending.addLast(rootId to rootName)
    val seen = mutableSetOf<String>()
    val sources = mutableListOf<HostUploadSource>()
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (id, parent) = pending.removeLast()
        check(seen.add(id)) { "The document provider returned a folder cycle." }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val cursor = resolver.query(children, projection, null, null, null) ?: error("Cannot read $parent.")
        cursor.use {
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val childId = it.getString(0)
                val path = "$parent/${uploadDocumentName(it.getString(1))}"
                val mime = it.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending.addLast(childId to path)
                else {
                    val size = if (it.isNull(3)) null else it.getLong(3).takeIf { value -> value >= 0 }
                    sources += documentSource(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, childId), path, size, mime)
                }
            }
        }
    }
    return sources
}

private fun documentSource(resolver: ContentResolver, uri: Uri, path: String, size: Long?, mime: String?) =
    HostUploadSource(path, size, { requireNotNull(resolver.openInputStream(uri)) { "Cannot open $path" } }, mime)
