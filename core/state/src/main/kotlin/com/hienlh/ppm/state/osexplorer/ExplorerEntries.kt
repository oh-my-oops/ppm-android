package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.files.FileDocumentClassifier
import java.math.BigInteger
import java.text.Collator
import java.time.Instant
import java.util.Locale

/** Directories first for every sort direction, then locale/base-sensitive natural names. */
fun sortHostEntries(entries: List<HostFileEntry>, filter: String, prefs: ExplorerViewPreferences, locale: Locale = Locale.getDefault()): List<HostFileEntry> {
    val needle = filter.trim().lowercase(Locale.ROOT)
    val visible = if (needle.isEmpty()) entries else entries.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY; decomposition = Collator.CANONICAL_DECOMPOSITION }
    return visible.sortedWith { a, b ->
        if (a.isDirectory != b.isDirectory) return@sortedWith if (a.isDirectory) -1 else 1
        val value = when (prefs.sort) {
            ExplorerSort.NAME -> naturalName(a.name, b.name, collator)
            ExplorerSort.SIZE -> (a.size ?: 0).compareTo(b.size ?: 0)
            ExplorerSort.MODIFIED -> modifiedTime(a.modified).compareTo(modifiedTime(b.modified))
            ExplorerSort.KIND -> naturalName(FileDocumentClassifier.extensionOf(a.name), FileDocumentClassifier.extensionOf(b.name), collator)
        }.takeIf { it != 0 } ?: naturalName(a.name, b.name, collator)
        if (prefs.descending) -value.compareTo(0) else value.compareTo(0)
    }
}

private val nameParts = Regex("\\p{Nd}+|[^\\p{Nd}]+")
private fun naturalName(a: String, b: String, collator: Collator): Int {
    val left = nameParts.findAll(a).map { it.value }.toList()
    val right = nameParts.findAll(b).map { it.value }.toList()
    for (index in 0 until minOf(left.size, right.size)) {
        val x = left[index]; val y = right[index]
        val order = if (x.first().isDigit() && y.first().isDigit()) BigInteger(x).compareTo(BigInteger(y)) else collator.compare(x, y)
        if (order != 0) return order
    }
    return left.size.compareTo(right.size)
}
private fun modifiedTime(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

fun hostSelectionSize(entries: List<HostFileEntry>): Long = entries.filterNot { it.isDirectory }.sumOf { it.size ?: 0 }

/** A bare host name. Windows-specific checks follow the host, never the Android device. */
fun validateHostEntryName(name: String, platform: String?): String? = when {
    name.isEmpty() -> "Name cannot be empty"
    name == "." || name == ".." -> "Reserved name"
    '/' in name || '\\' in name -> "Name cannot contain path separators"
    name.any { it.code < 32 } -> "Invalid character in name"
    platform == "win32" && name.any { it in "<>:\"|?*" } -> "Cannot contain < > : \" | ? *"
    platform == "win32" && Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$", RegexOption.IGNORE_CASE).matches(name) -> "\"$name\" is a reserved Windows name"
    platform == "win32" && (name.endsWith('.') || name.endsWith(' ')) -> "Cannot end with a dot or space"
    else -> null
}
