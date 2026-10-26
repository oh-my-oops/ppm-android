package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText

/** A feature's tag (`lib/feature-badges.ts`): Teams is in beta. */
enum class FeatureBadgeKind(val label: String) { NEW("new"), BETA("beta") }

/**
 * The web's `FeatureBadge`: the tag in capitals on a pill, `rounded-full uppercase tracking-wide`,
 * "new" in the success tone (`bg-success/15 text-success`) and "beta" on the accent wash. In the
 * [corner] of an icon (`px-1 text-[7px] leading-[1.4] font-bold`, with a `shadow-sm` a class can
 * take away) the caller places it; inline after a label it is `px-1.5 py-px text-[9px]
 * font-semibold leading-[1.4]`.
 */
@Composable
fun FeatureBadge(kind: FeatureBadgeKind, modifier: Modifier = Modifier, corner: Boolean = false, shadow: Boolean = corner) {
    val tokens = LocalPpmTokens.current
    val new = kind == FeatureBadgeKind.NEW
    val fill = if (new) tokens.success.opacity(0.15) else tokens.accentWash
    val text = if (new) tokens.success else tokens.accent
    val shadowed = if (shadow) SHADOW_SM.fold(modifier) { m, layer -> m.ppmShadow(layer, CircleShape) } else modifier
    Box(
        shadowed
            .clip(CircleShape)
            .background(fill.color)
            .padding(horizontal = if (corner) 4.dp else 6.dp, vertical = if (corner) 0.dp else 1.dp),
    ) {
        BasicText(
            kind.label.uppercase(),
            style = (if (corner) ppmText(7f, FontWeight.Bold, lineHeight = 9.8f) else ppmText(9f, FontWeight.SemiBold, lineHeight = 12.6f))
                .copy(color = text.color, letterSpacing = 0.025.em),
            maxLines = 1,
            softWrap = false,
        )
    }
}
