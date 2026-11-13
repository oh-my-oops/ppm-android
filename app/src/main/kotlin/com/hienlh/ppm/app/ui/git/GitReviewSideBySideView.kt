package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.FileFullDiff
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.editor.buildSideBySideDiffEntries
import com.hienlh.ppm.state.git.GitFileReview
import com.hienlh.ppm.state.git.GitReviewModel
import com.hienlh.ppm.state.git.GitReviewSideBySide
import kotlinx.coroutines.CancellationException

/**
 * The Review changes tab side by side (`git-review-side-by-side.tsx`): the focused file whole, HEAD
 * on the left and the working tree on the right, unchanged stretches folded, with each block's bar
 * (Stage / Discard / Unstage / Undo) drawn above its lines.
 *
 * HEAD on the left rather than the index, so a staged block stays on screen with its Unstage, as
 * it does in the block list. The bars are the block list's own [ReviewBlockBar] with the same
 * answers, and J/K/Y/N reach them unchanged through the tab's key handler. Picking single lines is
 * the block list's, and a file the list shows as one whole-file block other than a deletion stays
 * in the list: the caller decides that with [GitReviewSideBySide.supported].
 */
@Composable
internal fun ReviewSideBySide(
    review: GitFileReview,
    focusKey: String,
    busy: Boolean,
    wordWrap: Boolean,
    readWholeFile: suspend (String) -> FileFullDiff,
    describeError: (Exception) -> String,
    onFocusBlock: (String) -> Unit,
    onStage: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onUndoDiscard: (String) -> Unit,
    onStartPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current

    // The file as HEAD has it and as it is on disk. Read again whenever git's answer for the file
    // changes — a discard or an Undo rewrites the working tree, and so does anyone editing it.
    val signature = review.file?.let(GitReviewModel::fileSignature).orEmpty()
    var content by remember { mutableStateOf<Pair<String, FileFullDiff>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(review.path, signature) {
        error = null
        try {
            content = review.path to readWholeFile(review.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeError(e)
        }
    }

    val current = content?.takeIf { it.first == review.path }?.second
    Box(modifier.fillMaxSize().background(tokens.bgSolid.color).testTag("git-review-side-by-side")) {
        when {
            error != null -> BasicText(
                error!!,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                style = PpmFont.sans(14, lineHeight = 20, color = tokens.error.color).copy(textAlign = TextAlign.Center),
            )
            current == null -> Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Loader2, size = 20.dp, tint = tokens.text3.color)
                BasicText("Reading the file…", style = PpmFont.sans(14, lineHeight = 20, color = tokens.text3.color))
            }
            else -> SideBySideRows(
                review = review,
                diff = current,
                focusKey = focusKey,
                busy = busy,
                wordWrap = wordWrap,
                onFocusBlock = onFocusBlock,
                onStage = onStage,
                onDiscard = onDiscard,
                onUnstage = onUnstage,
                onUndoDiscard = onUndoDiscard,
                onStartPick = onStartPick,
            )
        }
    }
}

@Composable
private fun SideBySideRows(
    review: GitFileReview,
    diff: FileFullDiff,
    focusKey: String,
    busy: Boolean,
    wordWrap: Boolean,
    onFocusBlock: (String) -> Unit,
    onStage: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onUndoDiscard: (String) -> Unit,
    onStartPick: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val original = diff.original
    val modified = diff.modified
    val blocks = review.blocks
    val language = remember(review.path) { gitDiffLanguage(review.path) }
    val lineDiff = remember(original, modified) { ReviewBlocks.computeBlocks(original, modified) }
    val openedGaps = remember(review.path) { mutableStateMapOf<Int, Boolean>() }
    val entries = remember(lineDiff, original, modified, openedGaps.toMap()) {
        buildSideBySideDiffEntries(diff = lineDiff, original = original, modified = modified, openedGaps = openedGaps)
    }
    val places = remember(blocks) { GitReviewSideBySide.placeBlocks(blocks) }
    val items = remember(entries, places) { GitReviewSideBySide.layout(entries, places) }
    val oldLines = remember(original) { ReviewBlocks.splitLines(original).map(::stripDiffEol) }
    val newLines = remember(modified) { ReviewBlocks.splitLines(modified).map(::stripDiffEol) }
    val oldTokens = rememberShikiTokenLines(oldLines.joinToString("\n"), language)
    val newTokens = if (modified == original) oldTokens else rememberShikiTokenLines(newLines.joinToString("\n"), language)

    // J/K and an answer move the focus: bring its bar into view, a third of the way down, as the web does.
    val listState = rememberLazyListState()
    LaunchedEffect(focusKey, items) {
        val index = items.indexOfFirst { it is GitReviewSideBySide.Item.Bar && it.blockKey == focusKey }
        if (index < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val shown = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (shown == null || shown.offset < 0 || shown.offset + shown.size * 2 > layout.viewportEndOffset) {
            listState.scrollToItem(index)
            listState.scrollBy(-(listState.layoutInfo.viewportSize.height / 3f))
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("git-review-side-by-side-rows")) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is GitReviewSideBySide.Item.Bar -> {
                    val block = blocks.getOrNull(item.index) ?: return@items
                    val focused = block.key == focusKey
                    val edgeColor = when {
                        focused -> tokens.accent.opacity(0.6).color
                        block.state == "staged" -> tokens.accent.opacity(0.45).color
                        else -> tokens.border.color
                    }
                    val accent = tokens.accent.color
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .background(tokens.panel.color)
                            .then(if (focused) Modifier.background(tokens.accent.opacity(0.07).color) else Modifier)
                            .edge(Edge.Top, edgeColor)
                            .edge(Edge.Bottom, edgeColor)
                            .then(
                                if (focused) {
                                    Modifier.drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                                } else {
                                    Modifier
                                },
                            )
                            .clickable(enabled = !focused) { onFocusBlock(block.key) }
                            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                            .testTag("git-review-side-bar-${block.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReviewBlockBar(
                            block = block,
                            index = item.index,
                            total = blocks.size,
                            focused = focused,
                            pick = null,
                            pickable = emptyList(),
                            compact = false,
                            busy = busy,
                            onStage = { onStage(block.key) },
                            onDiscard = { onDiscard(block.key) },
                            onUnstage = { onUnstage(block.key) },
                            onUndoDiscard = onUndoDiscard,
                            onStartPick = { onStartPick(block.key) },
                            onSetPick = {},
                            onStagePicked = {},
                        )
                    }
                }
                is GitReviewSideBySide.Item.Gap -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.opacity(0.45).color)
                        .edge(Edge.Top, tokens.border.opacity(0.5).color)
                        .edge(Edge.Bottom, tokens.border.opacity(0.5).color)
                        .clickable { openedGaps[item.gap.fromNew] = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("git-review-side-gap-${item.gap.fromNew}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.ChevronDown, size = 12.dp, tint = tokens.text3.color)
                    BasicText(
                        text = "${item.gap.count} unchanged ${if (item.gap.count == 1) "line" else "lines"}",
                        style = PpmFont.sans(11, lineHeight = 14, color = tokens.text3.color),
                    )
                }
                is GitReviewSideBySide.Item.Row -> {
                    val row = item.row
                    // A tap in the code focuses the block it belongs to, as a tap on a card does.
                    val owner = row.rightLineNumber?.let { GitReviewSideBySide.blockAtLine(blocks, places, it) }
                    com.hienlh.ppm.app.ui.editor.GitSideBySideDiffRowItem(
                        row = row,
                        wordWrap = wordWrap,
                        leftTokens = row.leftLineNumber?.let { oldTokens?.getOrNull(it - 1) },
                        rightTokens = row.rightLineNumber?.let { newTokens?.getOrNull(it - 1) },
                        modifier = if (owner != null && owner != focusKey) {
                            Modifier.clickable(interactionSource = null, indication = null) { onFocusBlock(owner) }
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}
