package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

@Composable
internal fun ExplorerMutationToolbar(
    hasProject: Boolean, refreshing: Boolean, revealing: Boolean, busy: Boolean,
    onRefresh: () -> Unit, onCollapse: () -> Unit, onReveal: (() -> Unit)?, onCreate: ((Boolean) -> Unit)?,
    onOpenExplorer: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val actions: @Composable RowScope.() -> Unit = {
        if (hasProject) {
            if (onCreate != null) {
                ExplorerTool("New File", ProductIcon.FilePlus, enabled = !busy) { onCreate(false) }
                ExplorerTool("New Folder", ProductIcon.FolderPlus, enabled = !busy) { onCreate(true) }
            }
            if (!mobile && onOpenExplorer != null) {
                ExplorerTool("Open File Explorer", ProductIcon.FolderOpen, enabled = !busy, onClick = onOpenExplorer)
            }
            if (onReveal != null) ExplorerTool("Reveal Active File", if (revealing) ProductIcon.Loader2 else ProductIcon.Crosshair, revealing, !busy, onReveal)
            ExplorerTool("Collapse All", ProductIcon.ChevronsDownUp, enabled = !busy, onClick = onCollapse)
            ExplorerTool("Refresh", ProductIcon.RefreshCw, refreshing, !busy, onRefresh)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(8.dp)) {
        // At narrow resized sidebar widths all commands remain reachable beside the 52dp rail.
        if (hasProject && onCreate != null && maxWidth < 170.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { ExplorerTitle() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End), content = actions)
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ExplorerTitle()
            actions()
        }
    }
}

@Composable
private fun RowScope.ExplorerTitle() {
    val tokens = LocalPpmTokens.current
    PpmIcon(ProductIcon.FolderOpen, 16.dp, tokens.accent.color)
    BasicText("EXPLORER", Modifier.weight(1f), style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f)
        .copy(color = tokens.text2.color, letterSpacing = 0.05.em), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ExplorerTool(label: String, icon: ProductIcon, spinning: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.size(24.dp).clip(shape).semantics { contentDescription = label }
        .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, shape = shape, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 14.dp, tokens.text3.color, spinning = spinning) }
}
