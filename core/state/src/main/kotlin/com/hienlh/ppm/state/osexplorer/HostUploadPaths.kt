package com.hienlh.ppm.state.osexplorer

/** Web collect-dropped-entries: slash-normalize, drop '.'/empty parts, reject traversal. */
fun hostUploadRelativePath(raw: String): String? {
    if ('\u0000' in raw) return null
    val segments = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty() || segments.any { it == ".." || ':' in it }) return null
    return segments.joinToString("/")
}

internal fun hostUploadDestination(directory: String, relativePath: String, separator: String): String {
    require(separator == "/" || separator == "\\")
    val safe = requireNotNull(hostUploadRelativePath(relativePath)) { "Unsafe upload path: $relativePath" }
    return directory + (if (directory.endsWith(separator)) "" else separator) + safe.replace("/", separator)
}

internal fun hostUploadParent(path: String, separator: String): String {
    val index = path.lastIndexOf(separator)
    if (index <= 0) return separator
    val head = path.substring(0, index)
    return if (head.endsWith(':')) head + separator else head
}

internal fun hostUploadSuffixedName(name: String, number: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "$name ($number)" else "${name.substring(0, dot)} ($number)${name.substring(dot)}"
}
