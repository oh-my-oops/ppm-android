package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/**
 * The web's `Button` as Settings → Accounts' resets and Settings → Memory use it: `default`
 * (`bg-primary`) or `outline`, an optional 16 px icon (or the spinning loader while [busy]) before
 * the label, `disabled:opacity-50`. On a phone it is 44 tall (`min-h-11`), elsewhere 32 (`h-8`),
 * unless [minHeight] says otherwise.
 */
@Composable
internal fun SettingsActionButton(
    label: String,
    modifier: Modifier = Modifier,
    outline: Boolean = false,
    icon: ProductIcon? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(6.dp)
    val fg = if (outline) tokens.text.color else tokens.accentFg.color
    Row(
        modifier
            .heightIn(min = minHeight ?: if (mobile) 44.dp else 32.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (outline) Color.Transparent else tokens.accent.color)
            .then(if (outline) Modifier.cssBorder(tokens.border.color, shape) else Modifier)
            .ppmClickable(enabled = enabled, shape = shape, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (busy) PpmIcon(ProductIcon.Loader2, 16.dp, fg, spinning = true) else if (icon != null) PpmIcon(icon, 16.dp, fg)
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = fg), maxLines = 1)
    }
}
