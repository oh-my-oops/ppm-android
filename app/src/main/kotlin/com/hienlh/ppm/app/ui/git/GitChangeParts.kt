package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitOperation
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.LastCommit
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.git.ChangeTotals
import com.hienlh.ppm.state.git.CheckState
import com.hienlh.ppm.state.git.GitChangesView

internal enum class Edge { Top, Bottom, Start, End }

internal fun Modifier.edge(side: Edge, color: Color, width: Dp = 1.dp): Modifier =
    cssBorder(
        color = color,
        width = width,
        sides = when (side) {
            Edge.Top -> CssSides.Top
            Edge.Bottom -> CssSides.Bottom
            Edge.Start -> CssSides.Start
            Edge.End -> CssSides.End
        },
    )

internal object PpmFont {
    fun sans(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        lineHeight: Int? = null,
        color: Color = Color.Unspecified,
    ): TextStyle = ppmText(
        size = size.toFloat(),
        weight = weight,
        kind = FontKind.SANS,
        lineHeight = lineHeight?.toFloat(),
    ).copy(color = color)

    fun mono(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        lineHeight: Int? = null,
        color: Color = Color.Unspecified,
    ): TextStyle = ppmText(
        size = size.toFloat(),
        weight = weight,
        kind = FontKind.MONO,
        lineHeight = lineHeight?.toFloat(),
    ).copy(color = color)
}

@Composable
internal fun TruncatedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    com.hienlh.ppm.design.TruncatedText(
        text = AnnotatedString(text),
        style = style,
        modifier = modifier,
    )
}

/**
 * Start-ellipsized single-line text (`StartTruncatedText` in `git-change-parts.tsx`), keeping
 * the filename/folder tail visible when space is tight while exposing the full string to
 * semantics/tests.
 */
@Composable
internal fun StartTruncatedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val display = remember(text, style, maxPx) {
            if (text.isEmpty() || maxPx <= 0 || maxPx == Int.MAX_VALUE) {
                text
            } else {
                val fullWidth = measurer.measure(AnnotatedString(text), style = style, maxLines = 1, softWrap = false).size.width
                if (fullWidth <= maxPx) {
                    text
                } else {
                    var lo = 1
                    var hi = text.length
                    var best = "…${text.takeLast(1)}"
                    while (lo <= hi) {
                        val mid = (lo + hi) ushr 1
                        val cand = "…${text.takeLast(mid)}"
                        val w = measurer.measure(AnnotatedString(cand), style = style, maxLines = 1, softWrap = false).size.width
                        if (w <= maxPx) {
                            best = cand
                            lo = mid + 1
                        } else {
                            hi = mid - 1
                        }
                    }
                    best
                }
            }
        }
        BasicText(
            text = display,
            style = style,
            maxLines = 1,
        )
    }
}

internal fun Modifier.rightClick(onClick: () -> Unit): Modifier = pointerInput(onClick) {
    awaitEachGesture {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
            event.changes.forEach { it.consume() }
            onClick()
        }
    }
}

/** Git status color for Explorer and Source Control badges. */
internal fun gitStatusRgba(tokens: ThemeTokens, letter: String, conflict: Boolean = false): Rgba {
    if (conflict) return tokens.error
    return when (letter.uppercase()) {
        "A" -> tokens.success
        "D" -> tokens.error
        "R" -> tokens.accent
        "C" -> tokens.accent2
        "U" -> tokens.text3
        else -> tokens.warning // M
    }
}

internal fun gitStatusRgba(status: String?, tokens: ThemeTokens): Color? =
    status?.takeIf { it.isNotEmpty() }?.let { gitStatusRgba(tokens, it).color }

@Composable
internal fun StatusTile(file: ChangedFile) {
    val tokens = LocalPpmTokens.current
    val letter = GitChangesView.changeLetter(file)
    val conflict = file.conflict
    val tone = when {
        conflict || letter == "D" || letter == "U" -> tokens.error
        letter == "A" -> tokens.success
        letter == "R" -> tokens.accent
        else -> tokens.warning
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(tone.opacity(0.14).color),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = letter,
            style = PpmFont.mono(11, FontWeight.SemiBold, lineHeight = 11, color = tone.color),
        )
    }
}

private const val MAX_DOTS = 6

@Composable
internal fun BlockDots(file: ChangedFile) {
    val tokens = LocalPpmTokens.current
    val dots = remember(file) { GitChangesView.blockDots(file) }
    if (dots.isEmpty()) return
    val staged = dots.count { it }
    val title = "$staged of ${dots.size} ${if (dots.size == 1) "block" else "blocks"} staged"
    Row(
        modifier = Modifier
            .semantics { contentDescription = title }
            .testTag("git-block-dots-${file.path}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        dots.take(MAX_DOTS).forEachIndexed { i, on ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .then(
                        if (on) {
                            Modifier.background(tokens.accent.color)
                        } else {
                            Modifier.cssBorder(tokens.text3.opacity(0.85).color, CircleShape)
                        },
                    )
                    .testTag("git-block-dot-${file.path}-$i-${if (on) "on" else "off"}"),
            )
        }
        if (dots.size > MAX_DOTS) {
            BasicText(
                text = "+${dots.size - MAX_DOTS}",
                style = PpmFont.mono(10, lineHeight = 10, color = tokens.text3.color),
            )
        }
    }
}

@Composable
internal fun LineCounts(file: ChangedFile) {
    val tokens = LocalPpmTokens.current
    val counts = remember(file) { GitChangesView.changeCounts(file) }
    val note = remember(file) { GitChangesView.lineNote(file) }
    if (counts != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (counts.first > 0) {
                BasicText(
                    text = "+${counts.first}",
                    style = PpmFont.mono(10, lineHeight = 12, color = tokens.success.color),
                )
            }
            if (counts.second > 0) {
                BasicText(
                    text = "−${counts.second}",
                    style = PpmFont.mono(10, lineHeight = 12, color = tokens.error.color),
                )
            }
        }
    } else if (note != null) {
        BasicText(
            text = note,
            style = PpmFont.mono(10, lineHeight = 12, color = tokens.text3.color),
        )
    }
}

@Composable
internal fun CheckCell(
    state: CheckState,
    disabled: Boolean = false,
    tag: String? = null,
    onToggle: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Box(
        modifier = Modifier
            .width(if (mobile) 48.dp else 30.dp)
            .fillMaxHeight()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .alpha(if (disabled) 0.4f else 1f)
            .clickable(enabled = !disabled, role = Role.Checkbox, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        val checked = state != CheckState.NONE
        val shape = RoundedCornerShape(if (mobile) 5.dp else 3.dp)
        Box(
            modifier = Modifier
                .size(if (mobile) 20.dp else 14.dp)
                .clip(shape)
                .background(if (checked) tokens.accent.color else Color.Transparent)
                .cssBorder(if (checked) tokens.accent.color else tokens.text3.opacity(0.6).color, shape),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                CheckState.ALL -> PpmIcon(ProductIcon.Check, size = 10.dp, tint = tokens.accentFg.color)
                CheckState.SOME -> PpmIcon(ProductIcon.Minus, size = 10.dp, tint = tokens.accentFg.color)
                CheckState.NONE -> Unit
            }
        }
    }
}

@Composable
internal fun CountChip(count: Int, errorTone: Boolean = false) {
    val tokens = LocalPpmTokens.current
    val bg = if (errorTone) tokens.error.opacity(0.15).color else tokens.text.opacity(0.08).color
    val fg = if (errorTone) tokens.error.color else tokens.text2.color
    Box(
        modifier = Modifier
            .height(15.dp)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "$count",
            style = PpmFont.mono(11, FontWeight.Medium, lineHeight = 14, color = fg),
        )
    }
}

@Composable
internal fun GitConfirm(
    title: String,
    detail: String,
    confirmLabel: String,
    confirmIcon: ProductIcon = ProductIcon.Trash2,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val btnHeight = if (mobile) 44.dp else 28.dp
    val shape = if (mobile) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    } else {
        RoundedCornerShape(8.dp)
    }
    val btnBg = if (destructive) tokens.error.color else tokens.accent.color
    val btnFg = if (destructive) Color.White else tokens.accentFg.color

    @Composable
    fun CancelButton(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .height(btnHeight)
                .testTag("git-confirm-cancel")
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCancel)
                .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("Cancel", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
        }
    }

    @Composable
    fun ConfirmButton(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .height(btnHeight)
                .testTag("git-confirm-ok")
                .clip(RoundedCornerShape(6.dp))
                .background(btnBg)
                .clickable(onClick = onConfirm)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(
                    confirmIcon,
                    size = 14.dp,
                    tint = btnFg,
                    modifier = Modifier.testTag("git-confirm-icon-${confirmIcon.name}"),
                )
                BasicText(confirmLabel, style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = btnFg))
            }
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = !mobile),
    ) {
        Box(
            modifier = if (mobile) {
                Modifier.fillMaxSize().clickable(onClick = onCancel).testTag("git-confirm-bottom-sheet")
            } else {
                Modifier
            },
            contentAlignment = if (mobile) Alignment.BottomCenter else Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .then(if (mobile) Modifier.fillMaxWidth() else Modifier.width(340.dp))
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .clickable(enabled = mobile) {}
                    .padding(14.dp)
                    .testTag("git-confirm-dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(
                        ProductIcon.AlertTriangle,
                        size = 16.dp,
                        tint = tokens.error.color,
                        modifier = Modifier.testTag("git-confirm-alert-icon"),
                    )
                    BasicText(
                        text = title,
                        style = PpmFont.sans(13, FontWeight.SemiBold, lineHeight = 18, color = tokens.text.color),
                    )
                }
                BasicText(
                    text = detail,
                    style = PpmFont.sans(12, lineHeight = 16, color = tokens.text2.color),
                )
                if (mobile) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ConfirmButton(Modifier.fillMaxWidth())
                        CancelButton(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    ) {
                        CancelButton()
                        ConfirmButton()
                    }
                }
            }
        }
    }
}

@Composable
internal fun GitNoRepo(onRescan: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag("git-no-repo"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PpmIcon(LucideIcon.FolderGit2, size = 32.dp, tint = tokens.text3.opacity(0.6).color)
        Spacer(Modifier.height(12.dp))
        BasicText(
            text = "No git repository in this project, or in any folder two levels below it.",
            style = PpmFont.sans(13, lineHeight = 18, color = tokens.text2.color).copy(textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .height(if (mobile) 44.dp else 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                .clickable(onClick = onRescan)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(ProductIcon.RefreshCw, size = 14.dp, tint = tokens.text2.color)
            BasicText("Scan again", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text2.color))
        }
    }
}

@Composable
internal fun GitRepoChoice(repos: List<GitRepo>, onSelect: (GitRepo) -> Unit) {
    val tokens = LocalPpmTokens.current
    var query by remember { mutableStateOf("") }
    val showSearch = repos.size > 5
    val filtered = remember(repos, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) repos else repos.filter { it.name.lowercase().contains(q) || it.relative.lowercase().contains(q) }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("git-repo-choice"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(LucideIcon.FolderGit2, size = 16.dp, tint = tokens.text3.color)
            BasicText(
                "${repos.size} repositories in this folder",
                style = PpmFont.sans(14, FontWeight.Medium, lineHeight = 18, color = tokens.text.color),
            )
        }
        BasicText(
            "This project folder is not a git repository itself. Pick the one to work with — you can switch later from the header.",
            style = PpmFont.sans(12, lineHeight = 16, color = tokens.text2.color),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (showSearch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Search, size = 14.dp, tint = tokens.text3.color)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color),
                    cursorBrush = SolidColor(tokens.accent.color),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            BasicText("Filter repositories…", style = PpmFont.sans(12, lineHeight = 16, color = tokens.text3.color))
                        }
                        inner()
                    },
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (repo in filtered) {
                val candShape = RoundedCornerShape(6.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(candShape)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, candShape)
                        .clickable { onSelect(repo) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PpmIcon(ProductIcon.GitBranch, size = 16.dp, tint = tokens.text3.color)
                    Column(Modifier.weight(1f)) {
                        TruncatedText(repo.name, style = PpmFont.sans(13, FontWeight.Medium, lineHeight = 18, color = tokens.text.color))
                        if (repo.relative != repo.name) {
                            TruncatedText(repo.relative, style = PpmFont.mono(11, lineHeight = 14, color = tokens.text3.color))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GitRepoBar(repo: GitRepo, repos: List<GitRepo>, onSelect: (GitRepo) -> Unit) {
    val tokens = LocalPpmTokens.current
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    if (repos.size < 2) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(tokens.panel2.opacity(0.4).color)
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(LucideIcon.FolderGit2, size = 14.dp, tint = tokens.text3.color)
            TruncatedText(repo.relative, style = PpmFont.mono(11, lineHeight = 14, color = tokens.text3.color))
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .edge(Edge.Bottom, tokens.border.color),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(tokens.panel2.opacity(0.4).color)
                .clickable { open = !open }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PpmIcon(LucideIcon.FolderGit2, size = 14.dp, tint = tokens.accent.color)
            TruncatedText(
                repo.name,
                style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
                modifier = Modifier.weight(1f),
            )
            BasicText("${repos.size}", style = PpmFont.mono(10, lineHeight = 12, color = tokens.text3.color))
            PpmIcon(ProductIcon.ChevronDown, size = 14.dp, tint = tokens.text3.color)
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { open = false; query = "" },
                properties = PopupProperties(focusable = true),
            ) {
                val shape = RoundedCornerShape(6.dp)
                val filtered = remember(repos, query) {
                    val q = query.trim().lowercase()
                    if (q.isEmpty()) repos else repos.filter { it.name.lowercase().contains(q) || it.relative.lowercase().contains(q) }
                }
                Column(
                    modifier = Modifier
                        .width(260.dp)
                        .heightIn(max = 260.dp)
                        .clip(shape)
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, shape),
                ) {
                    if (repos.size > 5) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .edge(Edge.Bottom, tokens.border.color)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PpmIcon(ProductIcon.Search, size = 12.dp, tint = tokens.text3.color)
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color),
                                cursorBrush = SolidColor(tokens.accent.color),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                        for (r in filtered) {
                            val active = r.path == repo.path
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
                                    .clickable {
                                        onSelect(r)
                                        open = false
                                        query = ""
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PpmIcon(
                                    if (active) ProductIcon.Check else ProductIcon.GitBranch,
                                    size = 14.dp,
                                    tint = if (active) tokens.accent.color else tokens.text3.color,
                                )
                                TruncatedText(
                                    r.name,
                                    style = PpmFont.sans(
                                        12,
                                        if (active) FontWeight.Medium else FontWeight.Normal,
                                        lineHeight = 16,
                                        color = if (active) tokens.accent.color else tokens.text.color,
                                    ),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GitBranchRow(
    branch: GitBranchState,
    operation: GitOperation?,
    fileCount: Int,
    busyAction: String?,
    onOpenPicker: () -> Unit,
    onSync: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mode = remember(branch) { GitChangesView.syncMode(branch) }
    val primary = remember(mode, fileCount) { GitChangesView.syncIsPrimary(mode, fileCount) }
    val headLabel = branch.head ?: (branch.oid?.take(7)?.let { "$it (detached)" } ?: "No commits yet")
    val syncing = busyAction == "sync" || busyAction == "pull" || busyAction == "push" || busyAction == "publish" || busyAction == "fetch"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .edge(Edge.Bottom, tokens.border.color)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenPicker)
                .padding(horizontal = 6.dp)
                .testTag("git-branch-row-button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (operation != null) {
                PpmIcon(ProductIcon.GitMerge, size = 14.dp, tint = tokens.warning.color)
            } else {
                PpmIcon(ProductIcon.GitBranch, size = 14.dp, tint = tokens.text2.color)
            }
            TruncatedText(
                headLabel,
                style = PpmFont.sans(
                    12,
                    FontWeight.Medium,
                    lineHeight = 16,
                    color = if (branch.head != null) tokens.text.color else tokens.text2.color,
                ),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (operation != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(tokens.warning.opacity(0.15).color)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    BasicText(
                        text = if (operation.kind == "merge") "merging" else "${operation.kind} stopped",
                        style = PpmFont.sans(10, FontWeight.Medium, lineHeight = 12, color = tokens.warning.color),
                    )
                }
            }
            PpmIcon(ProductIcon.ChevronDown, size = 12.dp, tint = tokens.text3.color)
        }

        if (mode != null && operation == null) {
            val shape = RoundedCornerShape(6.dp)
            val bg = if (primary) tokens.accent.color else Color.Transparent
            val fg = if (primary) tokens.accentFg.color else tokens.text2.color
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(shape)
                    .background(bg)
                    .then(if (!primary) Modifier.cssBorder(tokens.border.color, shape) else Modifier)
                    .alpha(if (busyAction != null) 0.5f else 1f)
                    .clickable(enabled = busyAction == null, onClick = onSync)
                    .padding(horizontal = 8.dp)
                    .testTag("git-sync-button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (syncing) {
                    PpmIcon(ProductIcon.Loader2, size = 14.dp, tint = fg)
                } else {
                    when (mode) {
                        "publish" -> {
                            PpmIcon(ProductIcon.Cloud, size = 14.dp, tint = fg)
                            BasicText("Publish", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = fg))
                        }
                        "pull" -> {
                            PpmIcon(ProductIcon.ArrowDown, size = 14.dp, tint = fg)
                            BasicText("${branch.behind}", style = PpmFont.mono(11, FontWeight.Medium, lineHeight = 14, color = fg))
                            if (primary) {
                                BasicText("Pull", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = fg))
                            }
                        }
                        "push" -> {
                            PpmIcon(ProductIcon.ArrowUp, size = 14.dp, tint = fg)
                            BasicText("${branch.ahead}", style = PpmFont.mono(11, FontWeight.Medium, lineHeight = 14, color = fg))
                            if (primary) {
                                BasicText("Push", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = fg))
                            }
                        }
                        "sync" -> {
                            PpmIcon(ProductIcon.RefreshCw, size = 14.dp, tint = fg)
                            BasicText(
                                "↓${branch.behind} ↑${branch.ahead}",
                                style = PpmFont.mono(11, FontWeight.Medium, lineHeight = 14, color = fg),
                            )
                        }
                        "synced" -> {
                            PpmIcon(ProductIcon.RefreshCw, size = 14.dp, tint = fg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GitOperationBanner(
    operation: GitOperation,
    branch: GitBranchState,
    conflicts: Int,
    busyAction: String?,
    onContinue: () -> Unit,
    onAbort: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val noun = GitChangesView.OPERATION_NOUN[operation.kind] ?: operation.kind
    val title = remember(operation, branch.head) { GitChangesView.operationTitle(operation, branch.head) }
    val continuing = busyAction == "continue" || busyAction == "op:continue"
    val aborting = busyAction == "abort" || busyAction == "op:abort"
    val skipping = busyAction == "skip" || busyAction == "op:skip"
    val btnHeight = if (mobile) 44.dp else 26.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.warning.opacity(0.1).color)
            .edge(Edge.Bottom, tokens.warning.opacity(0.3).color)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("git-operation-banner"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.AlertTriangle, size = 14.dp, tint = tokens.warning.color)
            Column(Modifier.weight(1f)) {
                BasicText(title, style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
                BasicText(
                    text = if (conflicts > 0) {
                        "Resolve $conflicts ${if (conflicts == 1) "conflict" else "conflicts"}, then continue."
                    } else {
                        "No conflicts left. Continue to finish the $noun."
                    },
                    style = PpmFont.sans(11, lineHeight = 14, color = tokens.text2.color),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(btnHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                    .alpha(if (busyAction != null) 0.4f else 1f)
                    .clickable(enabled = busyAction == null, onClick = onAbort)
                    .testTag("git-op-abort"),
                contentAlignment = Alignment.Center,
            ) {
                if (aborting) {
                    PpmIcon(ProductIcon.Loader2, size = 12.dp, tint = tokens.text.color)
                } else {
                    BasicText("Abort $noun", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
                }
            }
            if (operation.kind == "rebase" && onSkip != null) {
                Box(
                    modifier = Modifier
                        .height(btnHeight)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                        .alpha(if (busyAction != null) 0.4f else 1f)
                        .clickable(enabled = busyAction == null, onClick = onSkip)
                        .padding(horizontal = 10.dp)
                        .testTag("git-op-skip"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (skipping) {
                        PpmIcon(ProductIcon.Loader2, size = 12.dp, tint = tokens.text.color)
                    } else {
                        BasicText("Skip", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color))
                    }
                }
            }
            val canCont = busyAction == null && conflicts == 0
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(btnHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(tokens.accent.color)
                    .alpha(if (canCont) 1f else 0.4f)
                    .clickable(enabled = canCont, onClick = onContinue)
                    .testTag("git-op-continue"),
                contentAlignment = Alignment.Center,
            ) {
                if (continuing) {
                    PpmIcon(ProductIcon.Loader2, size = 12.dp, tint = tokens.accentFg.color)
                } else {
                    BasicText("Continue", style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.accentFg.color))
                }
            }
        }
    }
}

@Composable
internal fun GitCommitComposer(
    message: String,
    onMessageChange: (String) -> Unit,
    totals: ChangeTotals,
    operation: GitOperation?,
    lastCommit: LastCommit?,
    syncMode: String?,
    busyAction: String?,
    onCommit: (amend: Boolean, signoff: Boolean, andPush: Boolean) -> Unit,
    onUndoLast: () -> Unit,
    isBottom: Boolean = false,
    branch: String? = null,
    onInputFocusChanged: ((Boolean) -> Unit)? = null,
    onWriteWithAi: (() -> Unit)? = null,
    aiLoading: Boolean = false,
    aiError: String? = null,
    touchPlaceholder: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var menuOpen by remember { mutableStateOf(false) }
    DisposableEffect(onInputFocusChanged) {
        onDispose { onInputFocusChanged?.invoke(false) }
    }

    val rewritable = lastCommit != null && !lastCommit.pushed
    val canSend = GitChangesView.canCommit(totals, message, operation, amend = false)
    val canAmend = rewritable && (totals.filesStaged > 0 || message.trim().isNotEmpty()) && (operation == null || operation.kind == "merge")
    val canUndo = rewritable && lastCommit.hasParent && operation == null
    val ready = canSend && busyAction == null
    val amendable = canAmend && busyAction == null
    val undoable = canUndo && busyAction == null
    val label = GitChangesView.commitLabel(totals)
    val hint = GitChangesView.commitHint(totals, message)
    val committing = busyAction == "commit"
    val barHeight = if (mobile) 44.dp else 32.dp
    val triggerWidth = if (mobile) 44.dp else 32.dp
    val placeholderText = when {
        touchPlaceholder -> "Message — or let the AI write it"
        !branch.isNullOrEmpty() -> "Message (Ctrl+↵ to commit on $branch)"
        else -> "Message (Ctrl+↵ to commit)"
    }

    var inputFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isBottom) {
                    Modifier.edge(Edge.Top, tokens.border.color).background(tokens.bgSolid.color)
                } else {
                    Modifier.edge(Edge.Bottom, tokens.border.color)
                },
            )
            .padding(10.dp)
            .testTag("git-commit-composer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val inputShape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (mobile) 52.dp else 58.dp, max = 140.dp)
                .clip(inputShape)
                .background(tokens.bgSolid.color)
                .cssBorder(if (inputFocused) tokens.accent.color else tokens.border.color, inputShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = message,
                onValueChange = onMessageChange,
                textStyle = PpmFont.sans(13, lineHeight = 19, color = tokens.text.color),
                cursorBrush = SolidColor(tokens.accent.color),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (onWriteWithAi != null) 34.dp else 0.dp)
                    .testTag("git-commit-input")
                    .onFocusChanged {
                        inputFocused = it.isFocused
                        onInputFocusChanged?.invoke(it.isFocused)
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && (ev.isCtrlPressed || ev.isMetaPressed)) {
                            if (ready) onCommit(false, false, false)
                            true
                        } else if (ev.type == KeyEventType.KeyDown && ev.key == Key.P && (ev.isCtrlPressed || ev.isMetaPressed) && ev.isAltPressed) {
                            if (ready) onCommit(false, false, true)
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    if (message.isEmpty()) {
                        BasicText(
                            text = placeholderText,
                            style = PpmFont.sans(13, lineHeight = 19, color = tokens.text3.color),
                        )
                    }
                    inner()
                },
            )
            if (onWriteWithAi != null) {
                val aiEnabled = !aiLoading && busyAction == null && totals.files > 0 && totals.conflicts == 0
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel2.color)
                        .alpha(if (aiEnabled) 1f else 0.4f)
                        .semantics { contentDescription = "Write commit message with AI" }
                        .clickable(enabled = aiEnabled, onClick = onWriteWithAi)
                        .testTag("git-ai-msg-btn"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (aiLoading) {
                        PpmIcon(ProductIcon.Loader2, size = 12.dp, tint = tokens.accent.color)
                    } else {
                        BasicText("✦", style = PpmFont.sans(12, FontWeight.Bold, lineHeight = 14, color = tokens.accent.color))
                    }
                }
            }
        }

        if (!aiError.isNullOrEmpty() && onWriteWithAi != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(tokens.error.opacity(0.12).color)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("git-ai-error-banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    text = aiError,
                    style = PpmFont.sans(11, lineHeight = 14, color = tokens.error.color),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onWriteWithAi)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("git-ai-retry-btn"),
                ) {
                    BasicText("Retry", style = PpmFont.sans(11, FontWeight.SemiBold, lineHeight = 14, color = tokens.error.color))
                }
            }
        }

        Box(Modifier.fillMaxWidth()) {
            val radius = if (mobile) 10.dp else 6.dp
            val leftShape = RoundedCornerShape(topStart = radius, bottomStart = radius)
            val rightShape = RoundedCornerShape(topEnd = radius, bottomEnd = radius)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(barHeight)
                        .alpha(if (ready) 1f else 0.5f)
                        .clip(leftShape)
                        .background(tokens.accent.color)
                        .clickable(enabled = ready) { onCommit(false, false, false) }
                        .padding(horizontal = 12.dp)
                        .testTag("git-commit-button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    if (committing) {
                        PpmIcon(ProductIcon.Loader2, size = 14.dp, tint = tokens.accentFg.color)
                    } else {
                        PpmIcon(ProductIcon.Check, size = 14.dp, tint = tokens.accentFg.color)
                    }
                    BasicText(label, style = PpmFont.sans(13, FontWeight.Medium, lineHeight = 16, color = tokens.accentFg.color))
                }
                Box(
                    modifier = Modifier
                        .width(triggerWidth)
                        .height(barHeight)
                        .alpha(if (busyAction == null) 1f else 0.5f)
                        .clip(rightShape)
                        .background(tokens.accent.color)
                        .cssBorder(tokens.accentFg.opacity(0.2).color, sides = CssSides.Start)
                        .clickable(enabled = busyAction == null) { menuOpen = !menuOpen }
                        .testTag("git-commit-more-button"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.ChevronDown, size = 14.dp, tint = tokens.accentFg.color)
                }
            }

            if (menuOpen) {
                Popup(
                    alignment = if (isBottom) Alignment.TopEnd else Alignment.BottomEnd,
                    onDismissRequest = { menuOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    val popShape = RoundedCornerShape(6.dp)
                    Column(
                        modifier = Modifier
                            .width(208.dp)
                            .clip(popShape)
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color, popShape)
                            .padding(vertical = 4.dp),
                    ) {
                        CommitMenuItem(
                            label = "Commit and push",
                            enabled = ready,
                        ) {
                            menuOpen = false
                            onCommit(false, false, true)
                        }
                        CommitMenuItem(
                            label = "Amend last commit",
                            enabled = amendable,
                        ) {
                            menuOpen = false
                            onCommit(true, false, false)
                        }
                        CommitMenuItem(
                            label = "Commit with sign-off",
                            enabled = ready,
                        ) {
                            menuOpen = false
                            onCommit(false, true, false)
                        }
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
                        CommitMenuItem(
                            label = "Undo last commit",
                            enabled = undoable,
                        ) {
                            menuOpen = false
                            onUndoLast()
                        }
                    }
                }
            }
        }

        if (hint != null) {
            BasicText(
                text = hint,
                style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
            )
        }
    }
}

@Composable
private fun CommitMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (mobile) 44.dp else 28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = PpmFont.sans(12, lineHeight = 16, color = tokens.text.color))
    }
}

@Composable
internal fun CleanState(
    last: LastCommit?,
    operation: GitOperation?,
    busy: Boolean,
    onUndo: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val canUndo = last != null && !last.pushed && last.hasParent && operation == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag("git-clean-state"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tokens.success.opacity(0.1).color),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.Check, size = 16.dp, tint = tokens.success.color)
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = "Nothing to commit",
            style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = "The working tree matches the last commit.",
            style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
        )
        if (last != null) {
            Spacer(Modifier.height(14.dp))
            val cardShape = RoundedCornerShape(6.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(tokens.panel2.opacity(0.4).color)
                    .cssBorder(tokens.border.color, cardShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(
                        text = "Last commit",
                        style = PpmFont.sans(10, FontWeight.Medium, lineHeight = 14, color = tokens.text3.color),
                    )
                    if (canUndo) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .alpha(if (busy) 0.4f else 1f)
                                .clickable(enabled = !busy, onClick = onUndo)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("git-undo-last-commit"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PpmIcon(ProductIcon.Undo2, size = 12.dp, tint = tokens.text2.color)
                            BasicText("Undo", style = PpmFont.sans(11, lineHeight = 14, color = tokens.text2.color))
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenHistory),
                ) {
                    TruncatedText(
                        text = last.subject,
                        style = PpmFont.sans(12, FontWeight.Medium, lineHeight = 16, color = tokens.text.color),
                    )
                }
                BasicText(
                    text = "${last.shortHash} · ${GitChangesView.formatRelativeTime(last.date)}",
                    style = PpmFont.mono(10, lineHeight = 14, color = tokens.text3.color),
                )
            }
        }
    }
}
