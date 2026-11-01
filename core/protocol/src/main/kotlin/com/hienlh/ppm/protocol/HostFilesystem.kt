package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

data class HostFileEntry(val name: String, val path: String, val type: String, val kind: String,
                         val size: Long? = null, val modified: String = "") {
    val isDirectory get() = type == "directory"
    companion object {
        fun from(value: JsonElement?): HostFileEntry? {
            val name = value["name"].string ?: return null
            val path = value["path"].string ?: return null
            val type = value["type"].string ?: return null
            return HostFileEntry(name, path, type, value["kind"].string ?: type, value["size"].long, value["modified"].string.orEmpty())
        }
    }
}

data class HostPlace(val name: String, val path: String, val kind: String? = null, val label: String? = null,
                     val key: String? = null, val source: String? = null) {
    companion object {
        fun from(value: JsonElement?): HostPlace? = value["name"].string?.let { name -> value["path"].string?.let { path ->
            HostPlace(name, path, value["kind"].string, value["label"].string, value["key"].string, value["source"].string)
        } }
    }
}

data class HostDirectory(val entries: List<HostFileEntry>, val current: String, val parent: String?,
                         val breadcrumbs: List<HostPlace>, val separator: String, val truncated: Boolean) {
    companion object {
        fun from(value: JsonElement?): HostDirectory? {
            val current = value["current"].string ?: return null
            val entries = value["entries"].array ?: return null
            val separator = value["sep"].string ?: return null
            return HostDirectory(entries.mapNotNull(HostFileEntry::from), current, value["parent"].string,
                value["breadcrumbs"].array.orEmpty().mapNotNull(HostPlace::from), separator, value["truncated"].bool == true)
        }
    }
}

data class HostFilesystem(val platform: String, val separator: String, val home: String, val hostname: String,
                          val drives: List<HostPlace>, val knownFolders: List<HostPlace>, val pinned: List<HostPlace>, val warnings: List<String>) {
    companion object {
        fun from(value: JsonElement?): HostFilesystem? {
            val platform = value["platform"].string ?: return null
            val home = value["homedir"].string ?: return null
            val separator = value["sep"].string ?: return null
            return HostFilesystem(platform, separator, home, value["hostname"].string.orEmpty(),
                value["drives"].array.orEmpty().mapNotNull(HostPlace::from),
                value["knownFolders"].array.orEmpty().mapNotNull(HostPlace::from),
                value["pinned"].array.orEmpty().mapNotNull(HostPlace::from), value["warnings"].array.orEmpty().mapNotNull { it.string })
        }
    }
}

data class HostFileInfo(val path: String, val name: String, val kind: String, val size: Long,
                        val modified: String, val changed: String, val created: String, val mode: Int,
                        val readOnly: Boolean, val hidden: Boolean, val target: String?, val childCount: Int?, val truncated: Boolean) {
    companion object {
        fun from(value: JsonElement?): HostFileInfo? {
            val path = value["path"].string ?: return null
            val kind = value["kind"].string ?: return null
            return HostFileInfo(path, value["name"].string.orEmpty(), kind, value["size"].long ?: 0,
                value["mtime"].string.orEmpty(), value["ctime"].string.orEmpty(), value["birthtime"].string.orEmpty(),
                value["mode"].int ?: 0, value["readonly"].bool == true, value["isHidden"].bool == true,
                value["target"].string, value["childCount"].int, value["truncated"].bool == true)
        }
    }
}

data class HostDeleteResult(val removed: String, val trashed: Boolean, val permanent: Boolean)
