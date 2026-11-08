package com.hienlh.ppm.app.ui.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.extensions.StatusBarItemUI

private val CODICON_REGEX = Regex("""\$\([^)]+\)\s*""")

@Composable
fun ExtensionStatusBarItems(
    model: AppModel,
    alignment: String,
    modifier: Modifier = Modifier,
) {
    val ext = model.extensions
    val map by (ext?.statusBarItems?.collectAsState() ?: remember { mutableStateOf(emptyMap()) })
    ExtensionStatusBarRow(
        items = map.values.toList(),
        alignment = alignment,
        onCommand = { cmd -> model.dispatchExtCommand(cmd, args = emptyList()) },
        modifier = modifier,
    )
}

@Composable
fun ExtensionStatusBarRow(
    items: List<StatusBarItemUI>,
    alignment: String,
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val filtered = remember(items, alignment) {
        items
            .filter { it.alignment == alignment }
            .sortedByDescending { it.priority }
    }
    if (filtered.isEmpty()) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (item in filtered) {
            val cleanText = remember(item.text) {
                item.text.replace(CODICON_REGEX, "").trim()
            }
            if (cleanText.isEmpty()) continue
            Row(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .let { m ->
                        val cmd = item.command
                        if (!cmd.isNullOrEmpty()) m.clickable { onCommand(cmd) } else m
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("ext-statusbar-${item.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    cleanText,
                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
