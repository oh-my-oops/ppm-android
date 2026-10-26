package com.hienlh.ppm.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.deleteGroup
import com.hienlh.ppm.client.groups
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.Hover
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.Group
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The Teams section (group-list.tsx): the project's groups, newest first, under a "Teams" header
 * whose + opens the new group form. Tapping a group opens its tab ([onNavigate] after, which the
 * phone's drawer closes on); a long press, or a mouse's right button, offers to delete it — a menu
 * on a tablet, a sheet on a phone. The list is read each time the section is shown, as the web
 * mounts it; an error replaces it, as there, until the section is shown again.
 */
@Composable
fun GroupList(model: AppModel, onNavigate: () -> Unit = {}) {
    val tokens = LocalPpmTokens.current
    val project = model.currentProject
    if (project == null) {
        Box(Modifier.fillMaxWidth().height(96.dp).padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText(
                "Select a project to view groups",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
            )
        }
        return
    }
    val api = model.api ?: return
    val mobile = LocalPpmMobile.current
    var groups by remember(project.path) { mutableStateOf<List<Group>>(emptyList()) }
    var loading by remember(project.path) { mutableStateOf(false) }
    var error by remember(project.path) { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(api, project.path) {
        loading = true
        try {
            groups = api.groups(project.path)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message ?: "Failed to load groups"
        } finally {
            loading = false
        }
    }
    fun open(group: Group) {
        model.openGroup(project.name, group)
        onNavigate()
    }
    fun delete(group: Group) {
        model.scope.launch {
            try {
                api.deleteGroup(group.id)
                groups = groups.filter { it.id != group.id }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "Failed to delete group"
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        GroupListHeader(mobile) { creating = true }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp)) {
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.Loader2, 16.dp, tokens.accent.color, spinning = true)
                }
                error != null -> ListNote(error!!, tokens.error.color)
                groups.isEmpty() -> ListNote("No groups yet. Create one to start.", tokens.text3.color)
                else -> for (group in groups) GroupRow(group, mobile, onDelete = { delete(group) }) { open(group) }
            }
        }
    }
    if (creating) {
        GroupCreateDialog(model, project.name, project.path, onDismiss = { creating = false }) { group ->
            groups = listOf(group) + groups
            open(group)
        }
    }
}

/**
 * `flex h-9 items-center justify-between border-b px-2`: "Teams" (`text-xs font-semibold uppercase
 * tracking-wide text-text-secondary`) with its tag, and the + (`size-11 md:size-7 rounded-md`): on a
 * phone the button is taller than the bar, and centred on it.
 */
@Composable
private fun GroupListHeader(mobile: Boolean, onCreate: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().height(36.dp).cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(start = 8.dp, end = 8.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(
                "TEAMS",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text2.color, letterSpacing = 0.025.em),
            )
            FeatureBadge(FeatureBadgeKind.BETA)
        }
        val hover = rememberHover(instant = true)
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .requiredSize(if (mobile) 44.dp else 28.dp)
                .clip(shape)
                .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, shape = shape, onClickLabel = "New group", source = hover.source, onClick = onCreate),
            contentAlignment = Alignment.Center,
        ) { PpmIcon(ProductIcon.Plus, 16.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color) }
    }
}

/** `px-2 py-3 text-xs`: what the list says instead of groups. */
@Composable
private fun ListNote(text: String, color: androidx.compose.ui.graphics.Color) {
    BasicText(text, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), style = ppmText(12f, lineHeight = 16f).copy(color = color))
}

/**
 * A group (`flex min-h-[40px] items-center gap-2 rounded-md px-2 py-2 text-sm text-text-secondary
 * hover:bg-surface-elevated hover:text-foreground`): the people icon and the name cut to a line.
 */
@Composable
private fun GroupRow(group: Group, mobile: Boolean, onDelete: () -> Unit, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(instant = true)
    // Where the long press was, in the row: the menu opens there.
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    Box {
        GroupRowContent(group, shape, hover, onMenu = { menuAt = it }, onClick = onClick)
        menuAt?.let { at ->
            OneItemMenu(mobile, at, "Delete group", ProductIcon.Trash2, destructive = true, onDismiss = { menuAt = null }) {
                menuAt = null
                onDelete()
            }
        }
    }
}

@Composable
private fun GroupRowContent(group: Group, shape: RoundedCornerShape, hover: Hover, onMenu: (Offset) -> Unit, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .pressable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, shape = shape, source = hover.source, onLongClick = onMenu, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.Users, 16.dp, tokens.text3.color)
        BasicText(
            group.name,
            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.mix(tokens.text2, hover.lit.toDouble()).color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A context menu holding one item (adaptive-context-menu.tsx): at the press on a tablet, on a phone
 * in a sheet (`p-2`, the row `rounded-lg px-3 py-3`, `active:bg-accent`) that is at most 60 vh. A
 * [destructive] item is red (`variant="destructive"`), lit in `destructive/10` (`/20` in the device's
 * dark mode) on a tablet; another's 16 px icon is muted there (`text-muted-foreground`), and in the
 * sheet takes the text's colour.
 */
@Composable
internal fun OneItemMenu(mobile: Boolean, at: Offset, label: String, icon: ProductIcon, destructive: Boolean, onDismiss: () -> Unit, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    if (!mobile) {
        val dark = isSystemInDarkTheme()
        ContextMenuPopup(at, onDismiss) {
            MenuItem(
                label,
                onClick = onClick,
                icon = { PpmIcon(icon, 16.dp, (if (destructive) tokens.error else tokens.text2).color) },
                color = if (destructive) tokens.error else null,
                iconEnd = 0.dp,
                highlight = if (destructive) tokens.error.opacity(if (dark) 0.2 else 0.1) else null,
            )
        }
        return
    }
    // A `vh` is Chrome's large viewport: everything under the status bar.
    val density = LocalDensity.current
    val vh = with(density) { (LocalWindowInfo.current.containerSize.height - WindowInsets.statusBars.getTop(density)).toDp() }
    val color = (if (destructive) tokens.error else tokens.text).color
    BottomSheet(onDismiss, inset = 8.dp, bottomPadding = WindowInsets.navigationBars.add(WindowInsets(bottom = 8.dp))) {
        Column(Modifier.heightIn(max = vh * 0.6f).verticalScroll(rememberScrollState())) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClick = onClick)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(icon, 16.dp, color)
                BasicText(label, style = ppmText(14f, lineHeight = 20f).copy(color = color))
            }
        }
    }
}
