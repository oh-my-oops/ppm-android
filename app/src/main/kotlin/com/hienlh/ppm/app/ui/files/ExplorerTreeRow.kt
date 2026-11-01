package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.files.FileTreeRow

private class ClickKeys { var toggle = false; var range = false }

@Composable
internal fun ExplorerTreeRow(
    row: FileTreeRow, expanded: Boolean, loading: Boolean, selected: Boolean, focused: Boolean, cut: Boolean = false,
    onClick: (toggle: Boolean, range: Boolean) -> Unit, onMenu: (Offset) -> Unit,
    dropOver: Boolean = false, dragSource: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val node = row.node
    val keys = remember { ClickKeys() }
    val menu by rememberUpdatedState(onMenu)
    val openMenu: (Offset) -> Unit = { at ->
        keys.toggle = false
        keys.range = false
        menu(at)
    }
    val currentMenu by rememberUpdatedState(openMenu)
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (LocalPpmMobile.current) 32.dp else 26.dp)
            .alpha(if (cut) 0.4f else 1f)
            .background(when { selected -> tokens.accentWash.color; focused -> tokens.panel2.color; else -> Color.Transparent })
            // `ring-1 ring-primary bg-primary/10` (DROP_TARGET_CLASS) of a row a drag is over.
            .then(if (dropOver) Modifier.background(tokens.accent.opacity(0.1).color).border(1.dp, tokens.accent.color) else Modifier)
            .semantics {
                this.selected = selected
                if (node.isDirectory) stateDescription = if (loading) "Loading" else if (expanded) "Expanded" else "Collapsed"
                onLongClick("File actions") { currentMenu(Offset.Zero); true }
            }
            .explorerContextGesture(currentMenu, onPress = { modifiers ->
                keys.toggle = modifiers.isCtrlPressed || modifiers.isMetaPressed
                keys.range = modifiers.isShiftPressed
            })
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false) {
                val toggle = keys.toggle
                val range = keys.range
                keys.toggle = false
                keys.range = false
                onClick(toggle, range)
            }
            .then(dragSource)
            .padding(start = (row.depth * 16 + 8).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (node.isDirectory) {
            PpmIcon(if (loading) ProductIcon.Loader2 else if (expanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                14.dp, tokens.text3.color, spinning = loading)
        } else Box(Modifier.size(14.dp))
        FileIcon(node.name, folder = node.isDirectory, open = expanded)
        // `truncate` (tree-node.tsx): a name too long for the row loses its end behind the "…".
        TruncatedText(
            AnnotatedString(node.name),
            ppmText(13f, if (node.isDirectory && expanded) FontWeight.Medium else FontWeight.Normal, lineHeight = 18f).copy(
                color = when { selected -> tokens.text.color; node.isIgnored -> tokens.textDim.color; node.isDirectory && expanded -> tokens.text.color; else -> tokens.text2.color }),
            Modifier.weight(1f),
        )
    }
}
