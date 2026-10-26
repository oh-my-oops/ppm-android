package com.hienlh.ppm.design

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The web's small display rules, kept identical so the two clients read the same. */
object PpmFormat {
    private val htmlWhitespace = Regex("[ \t\n\r\u000C]+")

    /**
     * What HTML shows of text by default (`white-space: normal` or `nowrap`): each run of spaces,
     * tabs and line breaks is one space, and there is none at either end.
     */
    fun collapsedWhitespace(text: String): String = text.split(htmlWhitespace).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * `formatRelativeDate` (src/web/lib/format-date.ts): "just now", "5m ago", "3h ago", "2d ago",
     * then the month and day in the device's locale ("Apr 21").
     */
    fun relative(date: Instant, now: Instant = Instant.now()): String {
        val minutes = Math.floorDiv(Duration.between(date, now).toMillis(), 60_000L)
        if (minutes < 1) return "just now"
        if (minutes < 60) return "${minutes}m ago"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ago"
        val days = hours / 24
        if (days < 7) return "${days}d ago"
        return monthDay(date)
    }

    /** `toLocaleDateString(undefined, { day: "numeric", month: "short" })`: "Nov 5" in the device's locale. */
    fun monthDay(date: Instant): String {
        val locale = Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(date)
    }

    /**
     * `toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" })`: "Nov 5, 2026, 3:04 PM"
     * in the device's locale, with the plain space Chrome keeps before "PM" where newer ICU data
     * writes a narrow no-break one.
     */
    fun mediumDateShortTime(date: Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(date)
            .replace('\u202F', ' ')

    /**
     * `new Date(iso).toLocaleString()`, the time's `title` in the action bar: the date and the time
     * to the second in the device's locale, as Chrome writes them ("10/20/2026, 11:43:17 PM").
     */
    fun fullDate(date: Instant): String {
        val locale = Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMdjms")
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(date)
    }

    /** `toLocaleTimeString()`: the time to the second in the device's locale ("10:02:00 AM"), with Chrome's plain space before "AM". */
    fun timeOfDay(date: Instant): String {
        val locale = Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "jms")
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(date).replace('\u202F', ' ')
    }

    /**
     * `toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })`: "09:05:07 PM",
     * the hour in two figures, as V8 asks ICU for it (`UDATPG_MATCH_HOUR_FIELD_LENGTH`).
     */
    fun twoDigitTimeOfDay(date: Instant): String {
        val locale = Locale.getDefault()
        val pattern = android.icu.text.DateTimePatternGenerator.getInstance(locale)
            .getBestPattern("jjmmss", android.icu.text.DateTimePatternGenerator.MATCH_HOUR_FIELD_LENGTH)
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(date).replace(' ', ' ')
    }

    /**
     * `turnTime`: "14:02" for today, "Oct 1, 14:02" before it — `toLocaleTimeString([], { hour:
     * "2-digit", minute: "2-digit" })` after `toLocaleDateString([], { month: "short", day: "numeric" })`.
     */
    fun turnTime(date: Instant, now: Instant = Instant.now()): String {
        val zone = ZoneId.systemDefault()
        val time = hourMinute(date)
        return if (date.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) time else "${monthDay(date)}, $time"
    }

    /** `toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })`: "02:05 PM", or "14:05" where the locale counts 24 hours. */
    fun hourMinute(date: Instant): String {
        val locale = Locale.getDefault()
        val pattern = android.icu.text.DateTimePatternGenerator.getInstance(locale)
            .getBestPattern("jjmm", android.icu.text.DateTimePatternGenerator.MATCH_HOUR_FIELD_LENGTH)
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(date).replace('\u202F', ' ')
    }

    /** `toLocaleDateString()`: the date in figures in the device's locale ("10/24/2026"). */
    fun numericDate(date: Instant): String {
        val locale = Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMd")
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(date)
    }

    /**
     * `getProjectInitials` (src/web/lib/project-avatar.ts): one letter when it is unique among the
     * projects, two when that is, else the project's 1-based position.
     */
    fun projectInitials(name: String, allNames: List<String>): String {
        fun words(n: String) = n.split('-', '_', '.', ' ', '\t', '\n').filter { it.isNotEmpty() }
        fun first(n: String): String {
            val w = words(n)
            return (w.firstOrNull()?.take(1) ?: n.take(1).ifEmpty { "?" }).uppercase()
        }
        fun two(n: String): String {
            val w = words(n)
            val f = first(n)
            return if (w.size > 1) f + w[1].take(1).uppercase() else f
        }
        val others = allNames.filter { it != name }
        val one = first(name)
        if (others.none { first(it) == one }) return one
        val pair = two(name)
        if (others.none { two(it) == pair }) return pair
        val index = allNames.indexOf(name)
        return if (index >= 0) "${index + 1}" else "?"
    }

    /**
     * `PROJECT_PALETTE` (src/web/lib/project-palette.ts): the gradient a project without a colour
     * of its own gets from its position.
     */
    val projectPalette: List<Pair<Rgba, Rgba>> = listOf(
        "#667eea" to "#764ba2", "#f5576c" to "#f093fb", "#4facfe" to "#00c6ff", "#43e97b" to "#38f9d7",
        "#fa709a" to "#fee140", "#a18cd1" to "#6a3de8", "#fd7043" to "#ff8a65", "#26c6da" to "#0097a7",
        "#ab47bc" to "#7b1fa2", "#ef5350" to "#b71c1c", "#1976d2" to "#42a5f5", "#2e7d32" to "#66bb6a",
    ).map { (a, b) -> Rgba.hex(a)!! to Rgba.hex(b)!! }

    private val hexColor = Regex("#[0-9a-fA-F]{3,8}\\b")

    /**
     * A project's avatar colours: its own (`#hex` or the palette's `linear-gradient(135deg, a, b)`
     * form), else the palette entry for [index].
     */
    fun projectColors(color: String?, index: Int): Pair<Rgba, Rgba> {
        if (color != null) {
            val hexes = hexColor.findAll(color).mapNotNull { Rgba.hex(it.value) }.toList()
            if (hexes.size >= 2) return hexes[0] to hexes[1]
            hexes.firstOrNull()?.let { return it to it }
        }
        return projectPalette[Math.floorMod(index, projectPalette.size)]
    }

    private val maxTurns = Regex("(?i)maximum number of turns \\((\\d+)\\)")

    /** `describeTurnStop` (src/shared/turn-stop.ts): the strip's title and detail. */
    fun turnStop(message: String, subtype: String?): Pair<String, String?> {
        if (subtype == "error_max_turns") {
            val limit = maxTurns.find(message)?.groupValues?.get(1)
            return (limit?.let { "Stopped after $it steps (Max Turns)" } ?: "Stopped at the Max Turns limit") to
                "That is the step limit for one message, set in Settings → AI Provider."
        }
        val lines = message.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: return "Stopped by an error" to null
        val title = "Stopped: " + first.removeSuffix(".")
        return title to if (lines.size > 1) lines.drop(1).joinToString(" ") else null
    }
}
