package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.McpServerName

/** `text-xs`: 12 on 16. */
private val barText = ppmText(12f, lineHeight = 16f)

/**
 * The MCP servers this chat's Claude cannot use until someone signs in (`mcp-sign-in-bar.tsx`),
 * in a strip above the composer: a button each that starts the sign-in, and an × that hides every
 * one shown — per server rather than per chat, so they stay hidden in every new chat too. Nothing
 * while each of them is hidden.
 */
@Composable
fun McpSignInBar(needsAuth: List<String>, dismissed: List<String>, onSignIn: (String) -> Unit, onHide: (List<String>) -> Unit) {
    val shown = needsAuth.filter { it !in dismissed }
    if (shown.isEmpty()) return
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    // `flex shrink-0 items-center gap-2 border-t border-border bg-surface-elevated/60 px-2 py-1.5 text-xs`.
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel2.opacity(0.6).color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.KeyRound, 14.dp, tokens.warning.color)
        BasicText(
            if (shown.size == 1) "MCP server needs sign-in:" else "MCP servers need sign-in:",
            style = barText.copy(color = tokens.text2.color),
            maxLines = 1,
            softWrap = false,
        )
        // `flex min-w-0 flex-1 gap-1.5 overflow-x-auto`, with the thin scrollbar Chrome keeps
        // under the buttons while they overflow.
        val scroll = rememberScrollState()
        Row(
            Modifier.weight(1f).chromeScrollbar(scroll, vertical = false, tokens.border.color).horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shown.forEach { name -> ServerButton(name, mobile) { onSignIn(name) } }
        }
        HideButton(mobile) { onHide(shown) }
    }
}

/**
 * `shrink-0 rounded border border-border bg-background px-2.5 py-1.5 font-medium text-text-primary
 * transition-colors hover:bg-surface max-md:min-h-9`: a plugin's server by its last part, the
 * whole name in its title.
 */
@Composable
private fun ServerButton(name: String, mobile: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val shape = RoundedCornerShape(4.dp)
    val title = "Sign in to $name"
    Box(
        Modifier
            .heightIn(min = if (mobile) 36.dp else 0.dp)
            .webTitle(title)
            .clip(shape)
            .background(tokens.panel.mix(tokens.bgSolid, hover.lit.toDouble()).color)
            .ppmClickable(pressedColor = tokens.panel.color, shape = shape, onClickLabel = title, source = hover.source, onClick = onClick)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            McpServerName.short(name),
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** `size-8 rounded text-text-secondary hover:bg-surface hover:text-text-primary max-md:size-10`, an × of 14. */
@Composable
private fun HideButton(mobile: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val label = "Hide these servers"
    Box(
        Modifier
            .size(if (mobile) 40.dp else 32.dp)
            .webTitle(label)
            .ppmClickable(
                pressedColor = tokens.panel.color,
                hoverColor = tokens.panel.color,
                shape = RoundedCornerShape(4.dp),
                onClickLabel = label,
                source = hover.source,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(ProductIcon.X, 14.dp, tokens.text.mix(tokens.text2, hover.lit.toDouble()).color)
    }
}
