package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.chat.AgentSessionContent
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.state.AgentSessionPayload

/**
 * An agent's session on a phone (`AgentSessionSheet`): the sheet over the whole of the app's area
 * (`h-[var(--sheet-vh)]`, `p-0`), a title row with its close button, and the same content the
 * tablet's window hosts. Its `zIndex` is 45, under the page's fixed `z-50` corners, so the beta
 * ribbon and the device badge stay over it.
 */
@Composable
fun AgentSessionSheet(model: AppModel, payload: AgentSessionPayload) {
    val tokens = LocalPpmTokens.current
    BottomSheet(
        onDismiss = model::closeAgentSheet,
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxHeight(),
        bottomPadding = WindowInsets.navigationBars,
        overlay = { CornerChrome(model, mobile = true) },
    ) {
        // Stacked from where the handle leaves it, in fractions: 157 px at 2.625x, as Chrome draws it
        // half a pixel into its first, where its parts rounded apiece come to a pixel more.
        CssColumn(Modifier.fillMaxWidth(), top = LocalSheetContentTop.current) {
            // As tall as the close button with `py-2`, which the button's column keeps in fractions.
            val exact = rememberCssExact()
            Row(
                Modifier.fillMaxWidth().cssExact(exact).cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TruncatedText(AnnotatedString(payload.title ?: "Agent session"), ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color), Modifier.weight(1f))
                // `size-11 rounded text-text-secondary hover:text-text-primary`.
                val hover = rememberHover()
                CssColumn(top = 8.dp, bottom = 8.dp, exact = exact) {
                    Box(
                        Modifier.cssHeight(44.dp).width(44.dp).clip(RoundedCornerShape(4.dp)).ppmClickable(source = hover.source, onClickLabel = "Close", onClick = model::closeAgentSheet),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, 20.dp, tokens.text.mix(tokens.text2, hover.lit.toDouble()).color)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AgentSessionContent(payload, model.events, model.agentFallbacks[payload.key])
        }
    }
}
