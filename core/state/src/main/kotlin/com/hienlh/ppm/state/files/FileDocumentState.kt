package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.RawFile

enum class FileDocumentViewer { TEXT, MARKDOWN, HTML, CSV, IMAGE, PDF, DOCX, VIDEO, AUDIO, SQLITE }

/** One editor tab. Paths are kept verbatim: absolute and relative aliases remain distinct tabs. */
data class FileDocumentState(
    val project: String?,
    val path: String,
    val viewer: FileDocumentViewer = FileDocumentClassifier.classify(path),
    /** Text only; a base64 response is never exposed as displayable content. */
    val content: String? = null,
    /** Authenticated image/PDF bytes. HTML, DOCX, media and SQLite have their own viewers. */
    val raw: RawFile? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val cannotDisplay: Boolean = false,
    val stale: Boolean = true,
    val dirty: Boolean = false,
    /** Successful loads, or refresh requests for a delegated viewer. */
    val revision: Int = 0,
) {
    val tabId: String get() = "editor:$path"
}
