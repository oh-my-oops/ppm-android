package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.client.RawFile
import com.hienlh.ppm.state.files.FileDocumentState
import com.hienlh.ppm.state.files.FileDocumentViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words a file view says in each case are the web's (code-editor.tsx and its previews), to the letter. */
class FileNoteTest {
    private val bytes = RawFile(byteArrayOf(1, 2, 3), "application/octet-stream")

    @Test fun `a text file says Loading file before anything arrived, loading or not`() {
        assertEquals(FileNote.Loading("Loading file..."), fileNote(FileDocumentViewer.TEXT, FileDocumentState("app", "a.txt")))
        assertEquals(FileNote.Loading("Loading file..."), fileNote(FileDocumentViewer.TEXT, FileDocumentState("app", "a.txt", loading = true)))
        assertEquals(FileNote.Loading("Loading file..."), fileNote(FileDocumentViewer.MARKDOWN, FileDocumentState("app", "a.md", loading = true)))
        assertEquals(FileNote.Loading("Loading file..."), fileNote(FileDocumentViewer.CSV, FileDocumentState("app", "a.csv", loading = true)))
    }

    @Test fun `a text file read says nothing, also while it is read again`() {
        assertNull(fileNote(FileDocumentViewer.TEXT, FileDocumentState("app", "a.txt", content = "hi", stale = false)))
        assertNull(fileNote(FileDocumentViewer.TEXT, FileDocumentState("app", "a.txt", content = "hi", loading = true)))
        assertNull(fileNote(FileDocumentViewer.TEXT, FileDocumentState("app", "a.txt", content = "", stale = false)))
    }

    @Test fun `a read error is one line of the server's own words, and replaces the file it was read for`() {
        assertEquals(FileNote.Error("Access denied"), fileNote(FileDocumentViewer.TEXT, FileDocumentState("app", "a.txt", error = "Access denied")))
        assertEquals(
            FileNote.Error("File not found: preview/check-page.html"),
            fileNote(FileDocumentViewer.MARKDOWN, FileDocumentState("app", "a.md", content = "old", error = "File not found: preview/check-page.html")),
        )
    }

    @Test fun `a binary file says it is a binary format and names the file`() {
        assertEquals(
            FileNote.Warning("This file is a binary format and cannot be displayed.", "/tmp/a.bin"),
            fileNote(FileDocumentViewer.TEXT, FileDocumentState(null, "/tmp/a.bin", cannotDisplay = true, stale = false)),
        )
    }

    @Test fun `an image waits on a spinner alone and fails with Failed to load image and no cause`() {
        assertEquals(FileNote.Spinner, fileNote(FileDocumentViewer.IMAGE, FileDocumentState("app", "a.png", loading = true)))
        assertEquals(FileNote.Spinner, fileNote(FileDocumentViewer.IMAGE, FileDocumentState("app", "a.png")))
        assertEquals(FileNote.Warning("Failed to load image."), fileNote(FileDocumentViewer.IMAGE, FileDocumentState("app", "a.png", error = "HTTP 500")))
        assertEquals(FileNote.Warning("Failed to load image."), fileNote(FileDocumentViewer.IMAGE, FileDocumentState("app", "a.png", raw = bytes, error = "HTTP 500")))
        assertNull(fileNote(FileDocumentViewer.IMAGE, FileDocumentState("app", "a.png", raw = bytes, stale = false)))
    }

    @Test fun `a PDF waits on a spinner alone and fails with Failed to load PDF and no cause`() {
        assertEquals(FileNote.Spinner, fileNote(FileDocumentViewer.PDF, FileDocumentState("app", "a.pdf", loading = true)))
        assertEquals(FileNote.Warning("Failed to load PDF."), fileNote(FileDocumentViewer.PDF, FileDocumentState("app", "a.pdf", error = "HTTP 500")))
        assertNull(fileNote(FileDocumentViewer.PDF, FileDocumentState("app", "a.pdf", raw = bytes, stale = false)))
    }

    @Test fun `a document says Converting document while it is read and names the cause when it fails`() {
        assertEquals(FileNote.Loading("Converting document..."), fileNote(FileDocumentViewer.DOCX, FileDocumentState("app", "a.docx", loading = true)))
        assertEquals(FileNote.Loading("Converting document..."), fileNote(FileDocumentViewer.DOCX, FileDocumentState("app", "a.docx")))
        assertEquals(
            FileNote.Warning("Failed to load document.", "Failed to convert docx"),
            fileNote(FileDocumentViewer.DOCX, FileDocumentState("app", "a.docx", error = "Failed to convert docx")),
        )
        assertNull(fileNote(FileDocumentViewer.DOCX, FileDocumentState("app", "a.docx", content = "<p>x</p>", stale = false)))
    }

    @Test fun `no viewer says Loading file for a preview that is not read as text`() {
        for (viewer in listOf(FileDocumentViewer.IMAGE, FileDocumentViewer.PDF, FileDocumentViewer.DOCX)) {
            assertNotEquals(FileNote.Loading("Loading file..."), fileNote(viewer, FileDocumentState("app", "a", loading = true)))
        }
    }
}
