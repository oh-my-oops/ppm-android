package com.hienlh.ppm.app.ui.phone.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/** A full-width button in a phone sheet's footer (Cancel / confirm). */
@Composable
internal fun PhoneSheetButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        destructive -> tokens.error.color
        primary -> tokens.accent.color
        else -> tokens.panel2.color
    }
    val fg = if (primary || destructive) tokens.accentFg.color else tokens.text.color
    Box(
        modifier
            .height(KitSpec.MinTouchTarget)
            .clip(shape)
            .background(bg, shape)
            .cssBorder(tokens.border.color, shape, width = 1.dp)
            .ppmClickable(enabled = enabled, shape = shape, onClickLabel = label, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = fg))
    }
}
