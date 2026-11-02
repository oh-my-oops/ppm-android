package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/**
 * `GET /api/upgrade` (routes/upgrade.ts): the version the server runs, and a newer one published,
 * which the server reports only when it is newer.
 */
data class UpgradeStatus(val currentVersion: String, val availableVersion: String?, val installMethod: String) {
    companion object {
        fun from(value: JsonElement?): UpgradeStatus? {
            val current = value["currentVersion"].string ?: return null
            return UpgradeStatus(current, value["availableVersion"].string, value["installMethod"].string.orEmpty())
        }
    }
}

/** `POST /api/upgrade/apply`: installed; [restart] when the supervisor was told to restart into it. */
data class UpgradeResult(val success: Boolean, val newVersion: String?, val restart: Boolean, val message: String?) {
    companion object {
        fun from(value: JsonElement?): UpgradeResult? {
            val success = value["success"].bool ?: return null
            return UpgradeResult(success, value["newVersion"].string, value["restart"].bool ?: false, value["message"].string)
        }
    }
}

/** One version's section of CHANGELOG.md (changelog.ts). [body] is the Markdown up to the next heading. */
data class ChangelogSection(val version: String, val date: String, val body: String)

/** `parseChangelog`: every `## [x.y.z] - date` section, in the file's order (newest first, as authored). */
fun parseChangelog(markdown: String): List<ChangelogSection> {
    val sections = mutableListOf<ChangelogSection>()
    var version: String? = null
    var date = ""
    val body = mutableListOf<String>()
    fun flush() {
        version?.let { sections += ChangelogSection(it, date, body.joinToString("\n").trim()) }
        body.clear()
    }
    for (line in markdown.split(LINE_BREAK)) {
        val match = CHANGELOG_HEADING.matchEntire(line)
        if (match != null) {
            flush()
            version = match.groupValues[1]
            date = match.groupValues[2].trim()
        } else if (version != null) {
            body += line
        }
    }
    flush()
    return sections
}

/** `newestSectionVersion`: the newest version among [sections] whatever their order, or null when there are none. */
fun newestSectionVersion(sections: List<ChangelogSection>): String? =
    sections.fold(null as String?) { best, section ->
        if (best != null && compareVersions(section.version, best) <= 0) best else section.version
    }

private val LINE_BREAK = Regex("\r?\n")
private val CHANGELOG_HEADING = Regex("""##\s*\[(\d+\.\d+\.\d+)\]\s*(?:-\s*(.+))?\s*""")
