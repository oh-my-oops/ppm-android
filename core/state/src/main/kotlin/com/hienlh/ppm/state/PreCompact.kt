package com.hienlh.ppm.state

import kotlin.math.abs

/**
 * The conversation a compaction replaced (`pre-compact-button.tsx`, `flatten-expansions.ts`):
 * Claude's compact summary names the transcript it was cut from, and the server reads the
 * segment before the summary out of that file.
 */
internal object PreCompact {
    private val PATH = Regex("""read the full transcript at:\s*(\S+\.jsonl)""", RegexOption.IGNORE_CASE)
    private val PREFIX = Regex("^pc-[^-]+-")

    /** The transcript a compact summary's [text] names, if it names one (`extractJsonlPath`). */
    fun transcriptPath(text: String): String? = PATH.find(text)?.groupValues?.get(1)?.trim()

    /**
     * Put before the ids of a segment read from [path], so that none collides with this
     * session's (`prefixPreCompactIds`): the web's 32-bit string hash, in base 36.
     */
    fun idPrefix(path: String): String {
        var h = 0
        for (c in path) h = (h shl 5) - h + c.code
        return "pc-" + abs(h.toLong()).toString(36) + "-"
    }

    /** The id the server knows a shown message by: without the prefix its segment's ids carry. */
    fun rawId(id: String): String = id.replaceFirst(PREFIX, "")

    /** Whether a shown message was loaded from before a compaction, by the prefix its id carries. */
    fun isExpanded(id: String): Boolean = PREFIX.containsMatchIn(id)
}
