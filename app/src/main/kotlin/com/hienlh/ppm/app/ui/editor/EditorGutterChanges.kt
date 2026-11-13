package com.hienlh.ppm.app.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.IndexBase
import com.hienlh.ppm.client.gitIndexBase
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.state.editor.GutterChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

/** How often an on-screen editor checks whether the index moved under it (`GUTTER_POLL_MS`). */
internal const val GUTTER_POLL_MS = 5_000L

/** How long after `git:changed` the base is read, so a burst of writes costs one read. */
private const val CHANGED_DELAY_MS = 300L

/** Pause in typing before the bars are redrawn. */
private const val REDRAW_DELAY_MS = 150L

/** The base held for one file: its blob id, and its lines, or none when there is nothing to compare with. */
private class HeldBase(val oid: String?, val lines: List<String>?)

/**
 * VS Code's change bars (`use-gutter-changes.ts`): which lines of the buffer differ from the file in
 * git's index, so the bars mean "not staged yet" — staging or committing a change clears its bar,
 * and an untracked file gets none rather than every line green.
 *
 * The diff runs here against the live buffer ([readText], read off the main thread), redrawn a
 * moment after typing stops; only the base comes from the server (`GET /git/index-base`), and it
 * is asked again whenever it may have moved: on `git:changed`, every few seconds while the editor
 * is composed and the app is in front, and when the app comes back to the front. Each of those
 * asks names the blob already held, so an unchanged index answers `same` with no content.
 */
@Composable
internal fun rememberGutterChanges(
    model: AppModel,
    project: String?,
    repoFile: String?,
    fileRepo: GitRepo?,
    enabled: Boolean,
    bufferVersion: Long,
    readText: () -> String,
): List<GutterChanges.Change> {
    val api = model.api
    val active = enabled && api != null && project != null && !repoFile.isNullOrBlank()
    var base by remember(api, project, repoFile, fileRepo?.path) { mutableStateOf<HeldBase?>(null) }
    val inFlight = remember(api, project, repoFile, fileRepo?.path) { booleanArrayOf(false) }

    suspend fun revalidate() {
        if (!active || inFlight[0]) return
        val held = base
        inFlight[0] = true
        try {
            when (val answer = api.gitIndexBase(project, repoFile, known = held?.oid, repo = fileRepo)) {
                is IndexBase.Same -> Unit
                is IndexBase.Ok -> base = HeldBase(answer.oid, withContext(Dispatchers.Default) { GutterChanges.splitBaseLines(answer.content) })
                is IndexBase.None -> base = HeldBase(answer.oid, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed check keeps the bars that were drawn; with none drawn yet there is nothing to keep.
            if (held == null) base = HeldBase(null, null)
        } finally {
            inFlight[0] = false
        }
    }

    // On open, then every few seconds while the app is in front: a `git add` in a terminal, or the
    // AI committing from its own shell, reports nothing. Coming back to the front checks at once.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active, api, project, repoFile, fileRepo?.path, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                revalidate()
                delay(GUTTER_POLL_MS)
            }
        }
    }
    // A stage, commit or discard made through PPM.
    LaunchedEffect(active, api, project, repoFile, fileRepo?.path) {
        if (!active) return@LaunchedEffect
        model.gitChangedEvents.filter { it == project }.collectLatest {
            delay(CHANGED_DELAY_MS)
            revalidate()
        }
    }

    val read by rememberUpdatedState(readText)
    var changes by remember(api, project, repoFile, fileRepo?.path) { mutableStateOf<List<GutterChanges.Change>>(emptyList()) }
    LaunchedEffect(active, base, bufferVersion) {
        val lines = base?.lines
        if (!active || lines == null) {
            changes = emptyList()
            return@LaunchedEffect
        }
        delay(REDRAW_DELAY_MS)
        val text = read()
        changes = withContext(Dispatchers.Default) {
            // Monaco keeps a byte-order mark out of the model, so the base is split without one.
            val current = (if (text.startsWith('﻿')) text.substring(1) else text).split('\n')
            if (current.size > GutterChanges.MAX_LINES) emptyList() else GutterChanges.compute(lines, current)
        }
    }
    return if (active) changes else emptyList()
}

/**
 * Draws a line's change mark in the gap between its number and its code, where Monaco's
 * line-decorations column is: a 3 dp bar, green added and blue modified, and a red wedge on the
 * boundary deleted lines sat on. [gap] is that gap's width at the end of the number's box.
 */
internal fun Modifier.gutterChangeMark(mark: GutterChanges.RowMark?, line: Int, gap: Dp, tokens: ThemeTokens): Modifier {
    if (mark == null) return this
    val tag = buildList {
        mark.bar?.let { add(it.name.lowercase()) }
        if (mark.deletedBelow) add("deleted")
        if (mark.deletedAbove) add("deleted-top")
    }.joinToString("+")
    val barColor = when (mark.bar) {
        GutterChanges.Kind.ADDED -> tokens.success.color
        GutterChanges.Kind.MODIFIED -> tokens.info.color
        else -> Color.Transparent
    }
    val deleted = tokens.error.color
    return testTag("editor-gutter-$tag-$line").drawBehind {
        val x = size.width - gap.toPx() + 2.dp.toPx()
        if (mark.bar != null) drawRect(barColor, topLeft = Offset(x, 0f), size = Size(3.dp.toPx(), size.height))
        val w = 5.dp.toPx()
        val h = 4.dp.toPx()
        if (mark.deletedBelow) {
            drawPath(
                Path().apply {
                    moveTo(x, size.height - h)
                    lineTo(x + w, size.height)
                    lineTo(x, size.height + h)
                    close()
                },
                deleted,
            )
        }
        if (mark.deletedAbove) {
            drawPath(
                Path().apply {
                    moveTo(x, 0f)
                    lineTo(x + w, 0f)
                    lineTo(x, h)
                    close()
                },
                deleted,
            )
        }
    }
}
