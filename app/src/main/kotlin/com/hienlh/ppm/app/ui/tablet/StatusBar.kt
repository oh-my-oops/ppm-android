package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.DropdownAboveEnd
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.ThemeSwatch
import com.hienlh.ppm.app.ui.screenKeptAwake
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ResourceSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The status bar (status-bar.tsx): `h-[26px] px-3.5 bg-panel border-t border-border-soft
 * text-[11px] font-mono text-text-3`. On the left the open project's branch and the dock's
 * toggle; on the right the machine's CPU and memory, the theme picker and the server's version.
 * Problems and an extension's items come with the features they belong to.
 */
@Composable
fun StatusBar(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val text = ppmText(11f, kind = FontKind.MONO, lineHeight = 16.5f).copy(color = tokens.text3.color)
    BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp).background(tokens.panel.color).edge(Edge.Top, tokens.borderSoft.color)) {
        // The bar is its own `@container`: under 48rem the theme keeps only its icon, under 36rem
        // the branch's counts give way.
        val compact = maxWidth < 768.dp
        val narrow = maxWidth < 576.dp
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The side that gives way: the branch name ellipsizes, then the group clips.
            Row(
                Modifier.weight(1f).clipToBounds(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GitStatus(model, text, narrow)
                DockToggle(model, text)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.screenKeptAwake()) Awake(text)
                model.api?.let { Resources(it, text) }
                ThemePicker(model, text, compact)
                model.info?.version?.let { BasicText("v$it", Modifier.padding(horizontal = 4.dp), style = text, maxLines = 1) }
            }
        }
    }
}

/**
 * The open project's branch (`GitStatus`) in the accent, then how far it is ahead of and behind
 * its upstream, or "synced". Read while the bar shows and the app is in front. Checking out
 * another branch from here comes with Source Control.
 */
@Composable
private fun GitStatus(model: AppModel, text: TextStyle, narrow: Boolean) {
    val tokens = LocalPpmTokens.current
    val project = model.selectedProject ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.api, project, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.watchGit(project) }
    }
    val git = model.gitBranches[project] ?: return
    val branch = git.branch ?: return
    val synced = git.tracking != null && git.ahead == 0 && git.behind == 0
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PpmIcon(ProductIcon.GitBranch, 12.dp, tokens.accent.color)
            BasicText(branch, Modifier.widthIn(max = 140.dp), style = text.copy(color = tokens.accent.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!narrow && (git.ahead > 0 || git.behind > 0)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (git.ahead > 0) GitCount(ProductIcon.ArrowUp, git.ahead, text)
                if (git.behind > 0) GitCount(ProductIcon.ArrowDown, git.behind, text)
            }
        }
        if (!narrow && synced) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PpmIcon(ProductIcon.Check, 12.dp, tokens.success.color)
                BasicText("synced", style = text.copy(color = tokens.success.color), maxLines = 1)
            }
        }
    }
}

@Composable
private fun GitCount(icon: ProductIcon, count: Int, text: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        PpmIcon(icon, 12.dp, text.color)
        BasicText("$count", style = text, maxLines = 1)
    }
}

/**
 * The dock's toggle: its icon and the open project's dock tab count, in the accent while the dock
 * shows. The web's toggle opens a terminal in an empty dock, so until this app has terminals it
 * is offered only when there is something to show or hide.
 */
@Composable
private fun DockToggle(model: AppModel, text: TextStyle) {
    val tokens = LocalPpmTokens.current
    val visible = model.layout?.dock?.visible == true
    val count = model.dockTabs.size
    if (count == 0 && !visible) return
    val color = if (visible) tokens.accent else tokens.text3
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .ppmClickable(pressedColor = tokens.panel2.opacity(0.15).color, onClickLabel = if (visible) "Hide panel" else "Show panel") {
                model.setDockVisible(!visible)
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(ProductIcon.PanelBottom, 11.dp, color.color)
        if (count > 0) BasicText("$count", style = text.copy(color = color.color))
    }
}

/** `WakeLockStatusBarItem`: the screen is being kept on while a turn runs. */
@Composable
private fun Awake(text: TextStyle) {
    val color = LocalPpmTokens.current.warning.color
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PpmIcon(ProductIcon.Sun, 12.dp, color)
        BasicText("awake", style = text.copy(color = color), maxLines = 1)
    }
}

/**
 * `ResourceStatusBar compact`: `CPU N%` in green, amber over 50 and red over 80, then `MEM`. Silent
 * until the first sample, and while the app is in the background the stream is let go.
 */
@Composable
private fun Resources(api: ApiClient, text: TextStyle) {
    val tokens = LocalPpmTokens.current
    var sample by remember(api) { mutableStateOf<ResourceSample?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    try {
                        api.resourceSamples().collect { sample = it }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: PpmError.Unauthorized) {
                        sample = null
                        return@launch
                    } catch (_: Exception) {
                        // Read again shortly, as an EventSource reconnects.
                    }
                    sample = null
                    delay(5_000)
                }
            }
        }
    }
    val current = sample ?: return
    val cpu = when {
        current.cpu > 80 -> tokens.error
        current.cpu > 50 -> tokens.warning
        else -> tokens.success
    }
    // `toFixed`: a point whatever the locale.
    val mem = if (current.memoryUsedMB < 1024) "%.0fM".format(Locale.ROOT, current.memoryUsedMB) else "%.1fG".format(Locale.ROOT, current.memoryUsedMB / 1024)
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("CPU %.0f%%".format(Locale.ROOT, current.cpu), style = text.copy(color = cpu.color), maxLines = 1)
        BasicText("MEM $mem", style = text.copy(color = tokens.text2.color), maxLines = 1)
    }
}

/** The palette and the theme's name (only the palette on a narrow bar); opens the six themes, for every device. */
@Composable
private fun ThemePicker(model: AppModel, text: TextStyle, compact: Boolean) {
    val tokens = LocalPpmTokens.current
    var open by remember { mutableStateOf(false) }
    val active = model.theme
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .ppmClickable(pressedColor = tokens.panel2.opacity(0.4).color, onClickLabel = "Theme") { open = true }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.Palette, 11.dp, (if (open) tokens.text else tokens.text3).color)
            if (!compact) {
                BasicText(active.name, Modifier.widthIn(max = 90.dp), style = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (open) {
            DropdownAboveEnd(onDismiss = { open = false }, minWidth = 224.dp) {
                BasicText(
                    "Theme",
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color),
                )
                for (theme in BuiltInThemes.all) {
                    val on = theme.id == active.id
                    MenuItem(
                        theme.name,
                        onClick = {
                            open = false
                            model.scope.launch { model.pickTheme(theme) }
                        },
                        icon = { ThemeSwatch(theme) },
                        iconEnd = 0.dp,
                        trailing = { PpmIcon(ProductIcon.Check, 16.dp, tokens.text.color, Modifier.alpha(if (on) 1f else 0f)) },
                    )
                }
            }
        }
    }
}
