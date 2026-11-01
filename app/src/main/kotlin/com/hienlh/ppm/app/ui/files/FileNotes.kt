package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.files.FileDocumentState
import com.hienlh.ppm.state.files.FileDocumentViewer

/**
 * What a file view shows in place of its file, as the web words it in code-editor.tsx and in the
 * previews it opens: centred in the view, with no toolbar, path row or Retry around it.
 */
internal sealed interface FileNote {
    /** A spinner with a line beside it, both in the secondary text colour (`gap-2 text-text-secondary`, `text-sm`). */
    data class Loading(val text: String) : FileNote

    /** A spinner alone, in the subtle colour: a preview on its way, or the editor loading. */
    data object Spinner : FileNote

    /** One line in the error colour (`text-error text-sm`): what the server said about reading the file. */
    data class Error(val message: String) : FileNote

    /** The file-warning icon over a line and, under it, a smaller one: a preview that could not show its file. */
    data class Warning(val text: String, val detail: String? = null) : FileNote

    /** One line in the secondary text colour (`text-text-secondary text-sm`): a tab with no file to read. */
    data class Plain(val text: String) : FileNote
}

/**
 * The note a [viewer] shows for [state] instead of its file, or null once there is a file to show.
 * An image and a PDF have no read error line: theirs is "Failed to load image." / "Failed to load PDF."
 * and the docx's names the cause under it. A file read as text (also markdown and CSV) says "Loading
 * file...", then either the server's error (even when an earlier read left content, as the web's
 * does) or, for a response the editor cannot show, that it is a binary format and which file.
 */
internal fun fileNote(viewer: FileDocumentViewer, state: FileDocumentState): FileNote? {
    val error = state.error
    return when (viewer) {
        FileDocumentViewer.IMAGE -> when {
            error != null -> FileNote.Warning("Failed to load image.")
            state.raw == null -> FileNote.Spinner
            else -> null
        }
        FileDocumentViewer.PDF -> when {
            error != null -> FileNote.Warning("Failed to load PDF.")
            state.raw == null -> FileNote.Spinner
            else -> null
        }
        FileDocumentViewer.DOCX -> when {
            error != null -> FileNote.Warning("Failed to load document.", error)
            state.content == null -> FileNote.Loading("Converting document...")
            else -> null
        }
        else -> when {
            error != null -> FileNote.Error(error)
            state.cannotDisplay -> FileNote.Warning("This file is a binary format and cannot be displayed.", state.path)
            state.content == null -> FileNote.Loading("Loading file...")
            else -> null
        }
    }
}

/**
 * The note for a file tab that cannot start reading. With no path it is the editor's own "No file
 * selected."; with a path but no server open nothing has been read yet, which the web words as
 * "Loading file...".
 */
internal fun unreadableFileNote(path: String?): FileNote =
    if (path.isNullOrEmpty()) FileNote.Plain("No file selected.") else FileNote.Loading("Loading file...")

/**
 * What the PDF view says once it has the bytes. The web has no words for a document with no pages;
 * its only PDF failure line is "Failed to load PDF.", so a document that failed to open and one with
 * nothing to show both say that. One still opening is a spinner alone, and null means there are pages.
 */
internal fun pdfRenderNote(failed: Boolean, pageCount: Int?): FileNote? = when {
    failed || pageCount == 0 -> FileNote.Warning("Failed to load PDF.")
    pageCount == null -> FileNote.Spinner
    else -> null
}

@Composable
internal fun FileNoteView(note: FileNote, modifier: Modifier = Modifier.fillMaxSize()) {
    val tokens = LocalPpmTokens.current
    val sm = ppmText(14f, lineHeight = 20f)
    val xs = ppmText(12f, lineHeight = 16f)
    when (note) {
        is FileNote.Loading -> Row(modifier, Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
            PpmIcon(ProductIcon.Loader2, 20.dp, tokens.text2.color, spinning = true)
            BasicText(note.text, style = sm.copy(color = tokens.text2.color))
        }
        FileNote.Spinner -> Box(modifier, Alignment.Center) {
            PpmIcon(ProductIcon.Loader2, 20.dp, tokens.text3.color, spinning = true)
        }
        is FileNote.Error -> Box(modifier, Alignment.Center) {
            BasicText(note.message, style = sm.copy(color = tokens.error.color))
        }
        is FileNote.Plain -> Box(modifier, Alignment.Center) {
            BasicText(note.text, style = sm.copy(color = tokens.text2.color))
        }
        is FileNote.Warning -> Column(modifier, Arrangement.spacedBy(12.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
            PpmIcon(ProductIcon.FileWarning, 40.dp, tokens.text3.color)
            BasicText(note.text, style = sm.copy(color = tokens.text2.color))
            note.detail?.let { BasicText(it, style = xs.copy(color = tokens.text3.color)) }
        }
    }
}
