package com.hienlh.ppm.design.kit

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/** One key in the terminal [KeyRow] (Foundation Section 3.9). */
data class TerminalKeySpec(
    val id: String,
    val label: String,
    val sticky: Boolean = false,
)

/** Default terminal keys from Foundation Section 3.9. */
val DefaultTerminalKeys: List<TerminalKeySpec> = listOf(
    TerminalKeySpec("esc", "Esc"),
    TerminalKeySpec("tab", "Tab"),
    TerminalKeySpec("ctrl", "Ctrl", sticky = true),
    TerminalKeySpec("alt", "Alt", sticky = true),
    TerminalKeySpec("up", "\u2191"),
    TerminalKeySpec("down", "\u2193"),
    TerminalKeySpec("left", "\u2190"),
    TerminalKeySpec("right", "\u2192"),
    TerminalKeySpec("pipe", "|"),
    TerminalKeySpec("slash", "/"),
    TerminalKeySpec("dash", "-"),
    TerminalKeySpec("tilde", "~"),
    TerminalKeySpec("ctrl_c", "^C"),
)

/** Returns true when a physical QWERTY keyboard is attached and active. */
@Composable
fun isHardwareKeyboardConnected(): Boolean {
    val config = LocalConfiguration.current
    return config.keyboard == Configuration.KEYBOARD_QWERTY &&
        config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
}

/**
 * 48 dp terminal extra-key row sitting directly above the soft keyboard (Foundation Sections 3.9 & 7).
 *
 * Each key is 44 x 40 dp (10 dp radius, 13.5 sp Monaspace SemiBold) centered in the 48 dp bar.
 * Sticky modifier keys (`Ctrl`, `Alt`) light in `accentWash` while latched. Automatically hides
 * when a hardware keyboard is connected unless [hideWhenHardwareKeyboardConnected] is false.
 */
@Composable
fun KeyRow(
    onKeyPress: (TerminalKeySpec) -> Unit,
    modifier: Modifier = Modifier,
    keys: List<TerminalKeySpec> = DefaultTerminalKeys,
    latchedKeyIds: Set<String> = emptySet(),
    hideWhenHardwareKeyboardConnected: Boolean = true,
) {
    if (hideWhenHardwareKeyboardConnected && isHardwareKeyboardConnected()) return

    val tokens = LocalPpmTokens.current
    val keyShape = RoundedCornerShape(KitSpec.KeyButtonRadius)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(KitSpec.KeyRowHeight)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, width = 1.dp, sides = CssSides.Top)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KitSpec.KeyButtonGap),
    ) {
        for (key in keys) {
            val latched = key.sticky && key.id in latchedKeyIds
            val bgColor = if (latched) tokens.accentWash.color else tokens.panel.color
            val borderColor = if (latched) tokens.accentWashBorder.color else tokens.border.color
            val fgColor = if (latched) tokens.accent.color else tokens.text.color

            Box(
                modifier = Modifier
                    .height(KitSpec.KeyRowHeight)
                    .widthIn(min = KitSpec.KeyButtonMinWidth)
                    .clip(keyShape)
                    .ppmClickable(
                        shape = keyShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = key.label,
                        onClick = { onKeyPress(key) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = KitSpec.KeyButtonMinWidth)
                        .height(KitSpec.KeyButtonHeight)
                        .background(bgColor, keyShape)
                        .cssBorder(borderColor, keyShape, width = 1.dp)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = key.label,
                        style = ppmText(13.5f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 16f)
                            .copy(color = fgColor),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
