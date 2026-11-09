package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.StartTruncatedText
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.state.SessionChangeList

/** `text-xs`: 12 on 16. */
private val xsText = ppmText(12f, lineHeight = 16f)

/** `text-[11px]` under `text-xs`'s line height of 4/3. */
private const val XS_11_LINE = 44f / 3

/** `text-[11px]` under the page's line height of 1.5. */
private val pageSmallText = ppmText(11f, lineHeight = 16.5f)

/** What the list leaves out of a session run by [providerId] (`shellChangesHint`). */
fun shellChangesHint(providerId: String?): String =
    if (providerId == "claude") {
        "Shell commands are followed through git: a file outside a repository, or one git ignores, is not listed. Source Control shows everything on disk."
    } else {
        "Files changed by shell commands are not listed — Source Control shows everything on disk."
    }

/** One CSS border's height as Chrome snaps it, in dp. */
@Composable
private fun borderDp(): Dp = with(LocalDensity.current) { cssBorderPx(1.dp).toDp() }

/** The +added −removed pair the tray, the changes chip and the Review tab all show (`ChangeCounts`); ×N for an edit made N times. */
@Composable
fun ChangeCounts(added: Int, removed: Int, style: TextStyle, modifier: Modifier = Modifier, editCount: Int? = null) {
    val tokens = LocalPpmTokens.current
    // `tabular-nums`.
    val numbers = style.copy(fontFeatureSettings = "tnum")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        BasicText("+$added", style = numbers.copy(color = tokens.success.color), maxLines = 1, softWrap = false)
        BasicText("−$removed", style = numbers.copy(color = tokens.error.color), maxLines = 1, softWrap = false)
        if (editCount != null && editCount > 1) BasicText("×$editCount", style = numbers.copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
    }
}

/** A file's counts, or why it has none: `binary`, `too large`. */
@Composable
fun SessionChangeCounts(file: SessionFileChange, style: TextStyle, modifier: Modifier = Modifier) {
    val additions = file.additions
    val deletions = file.deletions
    if (additions == null || deletions == null) {
        val why = if (file.binary) "binary" else if (file.tooLarge) "too large" else ""
        BasicText(why, modifier, style.copy(color = LocalPpmTokens.current.text3.color), maxLines = 1, softWrap = false)
    } else {
        ChangeCounts(additions, deletions, style, modifier)
    }
}

/** The box a Reviewed checkbox draws (`ReviewedBox`): `size-4 rounded border`, filled with a tick once checked. */
@Composable
fun ReviewedBox(checked: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    // `transition-colors`: the fill and the border fade, the tick does not.
    val fill = colorsFade(checked).toDouble()
    Box(
        modifier
            .size(16.dp)
            .clip(shape)
            .background(tokens.accent.opacity(fill).color)
            .cssBorder(tokens.accent.mix(tokens.text3, fill).color, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) PpmIcon(ProductIcon.Check, 12.dp, tokens.accentFg.color)
    }
}

/** Source Control's letter, colour and name for each state. */
private fun statusOf(status: SessionFileChange.Status): Triple<String, String, Boolean?> = when (status) {
    SessionFileChange.Status.ADDED -> Triple("A", "Added", true)
    SessionFileChange.Status.MODIFIED -> Triple("M", "Modified", null)
    SessionFileChange.Status.DELETED -> Triple("D", "Deleted", false)
}

/**
 * One file in the session's change list (`SessionChangeRow`), shared by the changes chip and the
 * Review tab: icon, name over its folder, counts and status letter, then — with
 * [onToggleReviewed] — a Reviewed checkbox of its own beside the row. [dense] is the 36px desktop
 * row; otherwise the 52px touch row.
 */
@Composable
fun SessionChangeRow(
    file: SessionFileChange,
    projectPath: String?,
    dense: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onToggleReviewed: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val (base, dir) = SessionChangeList.splitPath(SessionChangeList.displayPath(file.path, projectPath))
    val deleted = file.status == SessionFileChange.Status.DELETED
    // Its two lines, or one, between `py-1`/`py-1.5`, and never under `min-h-9`/`min-h-[52px]`.
    val nameLine = if (dense) 16f else 19.5f
    val dirLine = if (dense) XS_11_LINE else 16.5f
    val content = nameLine + (if (dir.isNotEmpty()) dirLine else 0f)
    val height = if (dense) maxOf(36f, content + 8f) else maxOf(52f, content + 12f)
    val countsStyle = ppmText(11f, kind = FontKind.MONO, lineHeight = dirLine)
    Row(modifier.fillMaxWidth().cssHeight(height.dp + borderDp()).cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)) {
        val accent = tokens.accent.color
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .webTitle(file.path)
                .then(
                    // `bg-surface shadow-[inset_2px_0_0_var(--accent)]`.
                    if (selected) Modifier.background(tokens.panel.color).drawBehind { drawRect(accent, size = Size(2.dp.toPx(), size.height)) } else Modifier,
                )
                .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, onClickLabel = file.path, onClick = onClick)
                .padding(horizontal = if (dense) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FileIcon(base, size = 16.dp)
            CssColumn(Modifier.weight(1f)) {
                val nameStyle = ppmText(if (dense) 12f else 13f, lineHeight = nameLine).copy(
                    color = if (deleted || file.reviewed) tokens.text2.color else tokens.text.color,
                    textDecoration = if (deleted) TextDecoration.LineThrough else null,
                )
                TruncatedText(AnnotatedString(base), nameStyle, Modifier.cssLines(nameLine))
                if (dir.isNotEmpty()) {
                    StartTruncatedText(dir, ppmText(11f, lineHeight = dirLine).copy(color = tokens.text3.color), Modifier.fillMaxWidth().cssLines(dirLine))
                }
            }
            SessionChangeCounts(file, countsStyle)
            val (glyph, label, tone) = statusOf(file.status)
            val color = when (tone) {
                true -> tokens.success
                false -> tokens.error
                null -> tokens.warning
            }
            // `text-center`, centred as a box centres it: `TextAlign.Center` evens the line's width
            // first and set the letter a pixel right of Chrome's.
            Box(Modifier.width(12.dp).webTitle(label), contentAlignment = Alignment.Center) {
                BasicText(glyph, style = countsStyle.copy(color = color.color, fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
            }
        }
        if (onToggleReviewed != null) {
            val reviewed = file.reviewed
            Box(
                Modifier
                    .width(if (dense) 32.dp else 44.dp)
                    .fillMaxHeight()
                    .webTitle(if (reviewed) "Reviewed — click to bring it back to the list" else "Mark reviewed")
                    .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, role = Role.Checkbox, onClickLabel = "Mark $base reviewed", onClick = onToggleReviewed),
                contentAlignment = Alignment.Center,
            ) {
                ReviewedBox(reviewed)
            }
        }
    }
}

/** The row that hides or shows the files marked reviewed (`ReviewedToggle`). */
@Composable
fun ReviewedToggle(count: Int, shown: Boolean, dense: Boolean, onToggle: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val style = if (dense) pageSmallText else xsText
    Row(
        Modifier
            .fillMaxWidth()
            // `min-h-8`/`min-h-11` with the border inside it (`border-box`).
            .cssHeight(if (dense) 32.dp else 44.dp)
            .cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom)
            .ppmClickable(pressedColor = tokens.panel.color, hoverColor = tokens.panel.color, onClickLabel = if (shown) "Hide reviewed" else "Show reviewed", onClick = onToggle)
            .padding(horizontal = if (dense) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(if (shown) ProductIcon.EyeOff else ProductIcon.Eye, 14.dp, tokens.text2.color)
        BasicText("$count reviewed", Modifier.weight(1f), style.copy(color = tokens.text2.color), maxLines = 1, softWrap = false)
        BasicText(if (shown) "Hide" else "Show", style = style.copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
    }
}
