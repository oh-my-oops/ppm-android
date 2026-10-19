package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** `.markdown-content pre`: 0.75rem Monaspace with the paragraph's line-height ratio. */
internal const val CODE_SIZE = 12f
internal const val CODE_LINE_HEIGHT = 12f * 20f / 14f

/**
 * A fenced code block (`.markdown-content pre`): 12px Monaspace on the page background inside a
 * 1px border, scrolling sideways, with the copy button a touch screen always shows
 * (`@media (hover: none)`).
 */
@Composable
fun CodeBlockView(code: String, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.bgSolid.color)
            .border(1.dp, tokens.border.color, shape),
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            BasicText(
                code,
                // The padding sits inside the border's own pixel, as CSS's border box puts it.
                Modifier.padding(start = 9.dp, top = 9.dp, end = 9.dp, bottom = 13.dp),
                style = ppmText(CODE_SIZE, kind = FontKind.MONO, lineHeight = CODE_LINE_HEIGHT).copy(color = tokens.text.color),
                softWrap = false,
            )
        }
        CopyIconButton(code, Modifier.align(Alignment.TopEnd).padding(4.dp))
    }
}

/** The code block's 24px copy button (`ActionBtn`): a check for 2 s after a copy. */
@Composable
internal fun CopyIconButton(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .size(24.dp)
            .clip(shape)
            .background(tokens.panel2.opacity(0.8).color)
            .border(1.dp, tokens.border.opacity(0.5).color, shape)
            .clickable(role = Role.Button) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", text)))
                    copied = true
                    delay(2_000)
                    copied = false
                }
            }
            .semantics { contentDescription = "Copy" },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(if (copied) ProductIcon.Check else ProductIcon.Copy, 12.dp, tokens.text2.color)
    }
}
