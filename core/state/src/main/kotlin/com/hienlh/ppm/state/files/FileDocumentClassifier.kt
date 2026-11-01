package com.hienlh.ppm.state.files

import java.util.Locale

/** Mirrors code-editor.tsx and the explorer's stricter can-open-in-ppm.ts gate. */
object FileDocumentClassifier {
    private val images = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico")
    private val videos = setOf("mp4", "webm", "mov", "ogg", "avi", "mkv")
    private val audio = setOf("mp3", "wav", "flac", "aac", "m4a", "wma")
    private val text = setOf(
        "txt", "log", "ini", "cfg", "conf", "env", "properties", "editorconfig",
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts",
        "json", "jsonc", "json5", "yaml", "yml", "toml", "xml", "svg",
        "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "astro",
        "py", "rb", "php", "go", "rs", "java", "kt", "kts", "swift", "c", "h",
        "cpp", "cc", "hpp", "cs", "m", "mm", "scala", "lua", "dart", "r", "pl",
        "sh", "bash", "zsh", "fish", "ps1", "psm1", "bat", "cmd",
        "sql", "graphql", "gql", "proto", "prisma", "tf", "tfvars",
        "dockerfile", "makefile", "gitignore", "gitattributes", "npmrc", "nvmrc",
        "diff", "patch", "lock", "map", "srt", "vtt",
    )
    private val filenames = setOf(
        "dockerfile", "makefile", "license", "licence", "readme", "changelog",
        "authors", "contributing", "notice", "codeowners", "procfile", "brewfile",
        "gemfile", "rakefile", "vagrantfile", "justfile",
    )

    /** Direct editor opens try text for unknown extensions; the read response decides binary. */
    fun classify(path: String): FileDocumentViewer =
        special(path.substringAfterLast('.').lowercase(Locale.ROOT)) ?: FileDocumentViewer.TEXT

    /** A leading dot belongs to the basename, as in the web explorer. */
    fun extensionOf(path: String): String {
        val base = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot <= 0) "" else base.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** Null means the explorer should not open a tab. HTML uses the existing preview surface. */
    fun viewerKindOf(path: String): FileDocumentViewer? {
        val ext = extensionOf(path)
        special(ext)?.let { return it }
        if (ext in text) return FileDocumentViewer.TEXT
        if (ext.isEmpty()) {
            val stem = path.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT).removePrefix(".")
            if (stem in filenames || stem in text) return FileDocumentViewer.TEXT
        }
        return null
    }

    fun canOpenInPpm(path: String): Boolean = viewerKindOf(path) != null

    private fun special(ext: String): FileDocumentViewer? = when (ext) {
        in images -> FileDocumentViewer.IMAGE
        in videos -> FileDocumentViewer.VIDEO
        in audio -> FileDocumentViewer.AUDIO
        "pdf" -> FileDocumentViewer.PDF
        "docx" -> FileDocumentViewer.DOCX
        "db", "sqlite", "sqlite3" -> FileDocumentViewer.SQLITE
        "md", "mdx" -> FileDocumentViewer.MARKDOWN
        "html", "htm" -> FileDocumentViewer.HTML
        "csv" -> FileDocumentViewer.CSV
        else -> null
    }
}
