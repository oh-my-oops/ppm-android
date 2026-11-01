package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** `/files/read` includes encoding; `/api/fs/read` returns text without it. */
data class FileContent(val content: String, val encoding: String? = null) {
    val isBinary: Boolean get() = encoding == "base64"
    val isText: Boolean get() = encoding == null || encoding == "utf-8"

    companion object {
        fun from(value: JsonElement?): FileContent? = value["content"].string?.let {
            FileContent(it, value["encoding"].string)
        }
    }
}

/** One directory level only. The server reports symlinks as files, even links to folders. */
data class FileDirectoryEntry(val name: String, val isDirectory: Boolean, val isIgnored: Boolean = false) {
    companion object {
        fun from(value: JsonElement?): FileDirectoryEntry? {
            val name = value["name"].string ?: return null
            val type = value["type"].string ?: return null
            if (type != "file" && type != "directory") return null
            return FileDirectoryEntry(name, type == "directory", value["isIgnored"].bool == true)
        }
    }
}

/** A batch can succeed overall while individual folders are missing or refused. */
data class FileDirectoryResult(
    val path: String,
    val entries: List<FileDirectoryEntry>? = null,
    val error: String? = null,
) {
    companion object {
        fun from(value: JsonElement?): FileDirectoryResult? {
            val path = value["path"].string ?: return null
            return FileDirectoryResult(path, value["entries"].array?.mapNotNull(FileDirectoryEntry::from), value["error"].string)
        }
    }
}
