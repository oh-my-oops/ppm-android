package com.hienlh.ppm.state

/**
 * How well a query matches one candidate (`score-file-search.ts`); lower is better.
 *
 * Tiers: exact filename (0) > filename prefix (1) > filename contains (2) > path contains (3)
 * > fuzzy filename (4) > fuzzy path (5). Then the match's position or fuzzy gap, a shorter
 * label, fewer path segments.
 */
data class FileSearchScore(val tier: Int, val offset: Int, val nameLen: Int, val depth: Int) : Comparable<FileSearchScore> {
    override fun compareTo(other: FileSearchScore): Int =
        compareValuesBy(this, other, { it.tier }, { it.offset }, { it.nameLen }, { it.depth })
}

/** The last segment of a path, `/` or `\` separated. */
fun searchFilename(path: String): String {
    val i = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (i >= 0) path.substring(i + 1) else path
}

/** Sum of the distances between consecutive matched characters of a subsequence match; -1 for none. */
private fun fuzzyGap(query: String, text: String): Int {
    var ti = 0
    var gap = 0
    var lastMatch = -1
    for (c in query) {
        ti = text.indexOf(c, ti)
        if (ti == -1) return -1
        if (lastMatch >= 0) gap += ti - lastMatch - 1
        lastMatch = ti
        ti++
    }
    return gap
}

/** [scoreFileSearch] for a tight loop: every string must already be lowercase. */
fun scoreFileSearchFast(qLower: String, filenameLower: String, pathLower: String, labelLen: Int, depth: Int): FileSearchScore? {
    // Several words: each scored on its own, and all must match.
    if (' ' in qLower) return scoreWordsFast(qLower.split(WHITESPACE).filter { it.isNotEmpty() }, filenameLower, pathLower, labelLen, depth)
    if (filenameLower == qLower) return FileSearchScore(0, 0, labelLen, depth)
    if (filenameLower.startsWith(qLower)) return FileSearchScore(1, 0, labelLen, depth)
    val inName = filenameLower.indexOf(qLower)
    if (inName >= 0) return FileSearchScore(2, inName, labelLen, depth)
    val inPath = pathLower.indexOf(qLower)
    if (inPath >= 0) return FileSearchScore(3, inPath, labelLen, depth)
    val nameGap = fuzzyGap(qLower, filenameLower)
    if (nameGap >= 0) return FileSearchScore(4, nameGap, labelLen, depth)
    val pathGap = fuzzyGap(qLower, pathLower)
    if (pathGap >= 0) return FileSearchScore(5, pathGap, labelLen, depth)
    return null
}

/** A multi-word query already split: the worst word's tier, the offsets added up. */
fun scoreWordsFast(words: List<String>, filenameLower: String, pathLower: String, labelLen: Int, depth: Int): FileSearchScore? {
    if (words.isEmpty()) return null
    if (words.size == 1) return scoreFileSearchFast(words[0], filenameLower, pathLower, labelLen, depth)
    var maxTier = 0
    var totalOffset = 0
    for (word in words) {
        val score = scoreFileSearchFast(word, filenameLower, pathLower, labelLen, depth) ?: return null
        maxTier = maxOf(maxTier, score.tier)
        totalOffset += score.offset
    }
    return FileSearchScore(maxTier, totalOffset, labelLen, depth)
}

fun scoreFileSearch(query: String, label: String, path: String): FileSearchScore? {
    val pathLower = path.lowercase()
    return scoreFileSearchFast(query.lowercase(), searchFilename(pathLower), pathLower, label.length, path.split('/').size)
}

private val WHITESPACE = Regex("\\s+")
