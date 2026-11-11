package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ChangelogSection
import kotlinx.coroutines.CancellationException

/** `RELEASES_URL` of `release-notes-view.tsx`. */
internal const val PHONE_RELEASES_URL = "https://github.com/hienlh/ppm/releases"

/** `SECTIONS_SHOWN`: enough history to see what changed since a machine was last updated. */
internal const val PHONE_RELEASE_NOTES_SHOWN = 30

/** A piece of a release-notes line: plain text, `**bold**` (whose inside is read again) or `` `code` ``. */
internal sealed interface ReleaseNoteSpan {
    data class Text(val text: String) : ReleaseNoteSpan
    data class Bold(val parts: List<ReleaseNoteSpan>) : ReleaseNoteSpan
    data class Code(val text: String) : ReleaseNoteSpan
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

/** `renderInline` of `release-notes-entry.tsx`: bold and code as spans, everything else text. No HTML is read. */
internal fun releaseNoteSpans(text: String): List<ReleaseNoteSpan> {
    val parts = mutableListOf<ReleaseNoteSpan>()
    var last = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > last) parts += ReleaseNoteSpan.Text(text.substring(last, m.range.first))
        val bold = m.groups[1]
        parts += if (bold != null) ReleaseNoteSpan.Bold(releaseNoteSpans(bold.value)) else ReleaseNoteSpan.Code(m.groupValues[2])
        last = m.range.last + 1
    }
    if (last < text.length) parts += ReleaseNoteSpan.Text(text.substring(last))
    return parts
}

@Composable
private fun releaseNoteText(text: String): AnnotatedString {
    val tokens = LocalPpmTokens.current
    val code = SpanStyle(fontFamily = PpmFonts.mono, color = tokens.text.color, background = tokens.surfaceHover.color, fontSize = 0.9.em)
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold, color = tokens.text.color)
    fun androidx.compose.ui.text.AnnotatedString.Builder.append(spans: List<ReleaseNoteSpan>) {
        for (span in spans) when (span) {
            is ReleaseNoteSpan.Text -> append(span.text)
            is ReleaseNoteSpan.Code -> withStyle(code) { append(span.text) }
            is ReleaseNoteSpan.Bold -> withStyle(bold) { append(span.parts) }
        }
    }
    return buildAnnotatedString { append(releaseNoteSpans(text)) }
}

/** `ReleaseNotesEntry` at its `comfortable` size: the version, its date, the installed mark, then the lines. */
@Composable
internal fun PhoneReleaseNotesEntry(section: ChangelogSection, installed: Boolean) {
    val tokens = LocalPpmTokens.current
    val body = ppmText(14f, lineHeight = 22f).copy(color = tokens.text2.color)
    Column(Modifier.fillMaxWidth().testTag("phone-release-notes-entry-${section.version}")) {
        Row(
            Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("v${section.version}", style = ppmText(16f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.text.color))
            if (section.date.isNotBlank()) {
                BasicText(section.date, style = ppmText(12f).copy(color = tokens.text3.color))
            }
            if (installed) {
                BasicText(
                    "installed",
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(tokens.accent.opacity(0.15).color)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("phone-release-notes-installed"),
                    style = ppmText(11f, FontWeight.Medium).copy(color = tokens.accent.color),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (line in section.body.split("\n")) {
                val t = line.trim()
                when {
                    t.isEmpty() -> Unit
                    t.startsWith("### ") -> BasicText(
                        t.substring(4).uppercase(),
                        Modifier.padding(top = 8.dp),
                        style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text3.color, letterSpacing = 0.025.em),
                    )
                    t.startsWith("- ") -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText("•", style = body.copy(color = tokens.text3.color))
                        BasicText(releaseNoteText(t.substring(2)), Modifier.weight(1f), style = body)
                    }
                    else -> BasicText(releaseNoteText(t), style = body)
                }
            }
        }
    }
}

/**
 * The phone's release notes (`ReleaseNotesView` in its bottom sheet): the recent CHANGELOG
 * sections read on open, the installed version marked, and GitHub when they cannot be read.
 */
@Composable
internal fun PhoneReleaseNotesSheet(
    installedVersion: String?,
    load: suspend () -> List<ChangelogSection>,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val uriHandler = LocalUriHandler.current
    var notes by remember { mutableStateOf<List<ChangelogSection>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            notes = load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val muted = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color)
    Sheet(
        title = "Release notes",
        subtitle = installedVersion?.let { "PPM v$it installed" },
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = Modifier.testTag("phone-release-notes-sheet"),
        headerActions = {
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .cssBorder(tokens.border.color, shape)
                    .ppmClickable(onClickLabel = "Open the releases on GitHub") { runCatching { uriHandler.openUri(PHONE_RELEASES_URL) } }
                    .padding(horizontal = 12.dp)
                    .testTag("phone-release-notes-github"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.ExternalLink, 14.dp, tokens.text2.color)
                BasicText("GitHub", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text2.color))
            }
        },
    ) {
        val loaded = notes
        when {
            loaded == null && !failed -> Row(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp).testTag("phone-release-notes-loading"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
                BasicText("Loading release notes…", style = muted)
            }
            failed || loaded.isNullOrEmpty() -> BasicText(
                "Release notes could not be loaded. Open them on GitHub instead.",
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp).testTag("phone-release-notes-failed"),
                style = muted,
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth().testTag("phone-release-notes-list"),
            ) {
                items(loaded, key = { it.version }) { section ->
                    PhoneReleaseNotesEntry(section, installed = section.version == installedVersion)
                }
            }
        }
    }
}
