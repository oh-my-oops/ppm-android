package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.files.FileTreeNode

@Composable
internal fun ExplorerDeleteDialog(node: FileTreeNode, busy: Boolean, error: String?, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        val shape = RoundedCornerShape(tokens.rad.dp)
        Column(Modifier.fillMaxWidth().ppmShadow(tokens.shadowPanel, shape).clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color).cssBorder(tokens.border.color, shape).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(if (node.isDirectory) "Delete Folder" else "Delete File",
                style = ppmText(18f, FontWeight.SemiBold, lineHeight = 28f).copy(color = tokens.text.color))
            BasicText("Permanently delete ${node.name}?" + if (node.isDirectory) " This will delete all contents." else "",
                style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
            if (error != null) BasicText(error, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.error.color))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val button = RoundedCornerShape(8.dp)
                BasicText("Cancel", Modifier.weight(1f).clip(button).cssBorder(tokens.border.color, button)
                    .ppmClickable(enabled = !busy, onClick = onDismiss).padding(12.dp),
                    style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text2.color))
                BasicText(if (busy) "Deleting…" else "Delete", Modifier.weight(1f).clip(button).background(tokens.error.color)
                    .ppmClickable(enabled = !busy, onClick = onDelete).padding(12.dp),
                    style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = Color.White))
            }
        }
    }
}
