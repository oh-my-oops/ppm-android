package com.hienlh.ppm.state.files

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class FileDocumentClassifierTest {
    @Test fun `specialized viewers match actual web extension sets`() {
        val extensions = mapOf(
            FileDocumentViewer.IMAGE to "png jpg jpeg gif webp svg ico",
            FileDocumentViewer.VIDEO to "mp4 webm mov ogg avi mkv",
            FileDocumentViewer.AUDIO to "mp3 wav flac aac m4a wma",
            FileDocumentViewer.PDF to "pdf",
            FileDocumentViewer.DOCX to "docx",
            FileDocumentViewer.SQLITE to "db sqlite sqlite3",
            FileDocumentViewer.MARKDOWN to "md mdx",
            FileDocumentViewer.CSV to "csv",
            FileDocumentViewer.HTML to "html htm",
        )
        for ((viewer, suffixes) in extensions) for (suffix in suffixes.split(' ')) {
            val path = "C:\\folder.name\\Document.${suffix.uppercase(Locale.ROOT)}"
            assertEquals(path, viewer, FileDocumentClassifier.classify(path))
            assertEquals(path, viewer, FileDocumentClassifier.viewerKindOf(path))
            assertTrue(FileDocumentClassifier.canOpenInPpm(path))
        }
    }

    @Test fun `text dotfiles conventional names and nested paths remain openable`() {
        val names = listOf(".gitignore", ".env", ".editorconfig", "Dockerfile", "LICENSE", "CODEOWNERS", "Justfile",
            "code.TSX", "script.kts", "file.jsonc", "main.cjs", "options.tfvars", "captions.srt", "a.lock")
        for (name in names) {
            val path = "/parent.with.dots/$name"
            assertEquals(path, FileDocumentViewer.TEXT, FileDocumentClassifier.viewerKindOf(path))
            assertEquals(path, FileDocumentViewer.TEXT, FileDocumentClassifier.classify(path))
        }
        assertEquals("", FileDocumentClassifier.extensionOf("/a.b/.env"))
        assertEquals("md", FileDocumentClassifier.extensionOf("/a.b/.env.example.MD"))
        assertEquals(FileDocumentViewer.MARKDOWN, FileDocumentClassifier.classify("/a.b/.env.example.MD"))
    }

    @Test fun `unknown explorer files are blocked but direct editor opens try text`() {
        // In particular m4v appears in the plan, but NOT the actual web viewer set.
        for (name in listOf("clip.m4v", "archive.zip", "a.bin", "slides.pptx", "notes.tsv", "a.ipynb",
            ".env.local", "unknown", "a.", "", "dir.md/file")) {
            assertNull(name, FileDocumentClassifier.viewerKindOf(name))
            assertFalse(name, FileDocumentClassifier.canOpenInPpm(name))
            assertEquals(name, FileDocumentViewer.TEXT, FileDocumentClassifier.classify(name))
        }
        // Preserve the web's distinction between its editor extension and explorer dotfile gate.
        assertNull(FileDocumentClassifier.viewerKindOf(".png"))
        assertEquals(FileDocumentViewer.IMAGE, FileDocumentClassifier.classify(".png"))
    }

    @Test fun `classification is independent of device locale and paths keep separate tab identities`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(FileDocumentViewer.VIDEO, FileDocumentClassifier.viewerKindOf("CLIP.AVI"))
            assertEquals(FileDocumentViewer.IMAGE, FileDocumentClassifier.classify("PICTURE.GIF"))
            assertEquals(FileDocumentViewer.TEXT, FileDocumentClassifier.viewerKindOf("LICENSE"))
        } finally { Locale.setDefault(old) }
        assertEquals("editor:src/a.kt", FileDocumentState("app", "src/a.kt").tabId)
        assertEquals("editor:/work/app/src/a.kt", FileDocumentState("app", "/work/app/src/a.kt").tabId)
    }
}
