package com.hienlh.ppm.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.FooterUsageTone
import com.hienlh.ppm.state.footerUsageTone
import com.hienlh.ppm.state.formatResetCountdown
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Wall-clock source for usage reset countdowns; overridable in tests. */
val LocalResetClock = compositionLocalOf<() -> Long> { System::currentTimeMillis }

/** Viewer timezone for usage reset clock times; overridable in tests. */
val LocalResetZone = compositionLocalOf<ZoneId> { ZoneId.systemDefault() }

/**
 * Ticks every [intervalMs] while mounted so usage reset countdowns update live while the footer,
 * `ChatMoreMenu`, or `SwitchAccountView` stays open.
 */
@Composable
fun rememberResetClockMillis(intervalMs: Long = 30_000L): Long {
    val clock = LocalResetClock.current
    return produceState(initialValue = clock(), key1 = clock, key2 = intervalMs) {
        while (true) {
            delay(intervalMs)
            value = clock()
        }
    }.value
}

/** Ring/track fill colour for [pct] (`STROKE` / `FILL` in `usage-meters.tsx`). */
@Composable
fun footerUsageFillColor(pct: Int?): Color {
    val tokens = LocalPpmTokens.current
    return when (footerUsageTone(pct)) {
        FooterUsageTone.OK -> tokens.success.color
        FooterUsageTone.WARN -> tokens.warning.color
        FooterUsageTone.ERROR -> tokens.error.color
    }
}

/** Percentage text colour beside the ring (`TEXT` in `usage-meters.tsx`): quiet until >= 70%. */
@Composable
fun footerUsageTextColor(pct: Int?): Color {
    val tokens = LocalPpmTokens.current
    return when (footerUsageTone(pct)) {
        FooterUsageTone.OK -> tokens.text2.color
        FooterUsageTone.WARN -> tokens.warning.color
        FooterUsageTone.ERROR -> tokens.error.color
    }
}

/**
 * Circular usage ring (`UsageRing` in `usage-meters.tsx`): `viewBox 0 0 14 14`, `r=5`, `strokeWidth=2`.
 */
@Composable
fun UsageRing(
    pct: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    val tokens = LocalPpmTokens.current
    val reducedMotion = rememberPrefersReducedMotion()
    val clamped = (pct ?: 0).coerceIn(0, 100)
    val animated by animateFloatAsState(
        targetValue = clamped / 100f,
        animationSpec = tween(if (reducedMotion) 0 else 300),
        label = "usageRing",
    )
    val strokeColor = footerUsageFillColor(pct)
    val trackColor = tokens.border.color

    Canvas(modifier.size(size)) {
        val strokePx = this.size.minDimension * (2f / 14f)
        val radiusPx = this.size.minDimension * (5f / 14f)
        val topLeft = Offset(center.x - radiusPx, center.y - radiusPx)
        val arcSize = Size(radiusPx * 2f, radiusPx * 2f)
        drawCircle(
            color = trackColor,
            radius = radiusPx,
            center = center,
            style = Stroke(width = strokePx),
        )
        if (pct != null && animated > 0f) {
            drawArc(
                color = strokeColor,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * One usage window in the footer (`UsageWindow` in `usage-meters.tsx`): `"◔ 5h 47%"`.
 * Hidden when [pct] is null; drops the ring on narrow/phone while keeping the label and figure.
 */
@Composable
fun UsageWindow(
    label: String,
    pct: Int?,
    narrow: Boolean,
    modifier: Modifier = Modifier,
) {
    if (pct == null) return
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val hideRing = narrow || mobile
    val tone = footerUsageTone(pct)

    Row(
        modifier.testTag("usage-window:$label"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!hideRing) {
            UsageRing(
                pct = pct,
                size = 12.dp,
                modifier = Modifier.testTag("usage-ring-icon:$label"),
            )
        }
        BasicText(
            label,
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
            maxLines = 1,
            softWrap = false,
        )
        BasicText(
            "$pct%",
            Modifier.testTag("usage-pct:$label:${tone.wire}"),
            style = ppmText(11f, FontWeight.Medium, lineHeight = 16f).copy(
                color = footerUsageTextColor(pct),
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Thin horizontal usage bar (`UsageTrack` in `usage-meters.tsx`): `h-1 rounded-sm bg-surface-hover`.
 */
@Composable
fun UsageTrack(
    pct: Int?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val reducedMotion = rememberPrefersReducedMotion()
    val clamped = (pct ?: 0).coerceIn(0, 100)
    val fraction by animateFloatAsState(
        targetValue = clamped / 100f,
        animationSpec = tween(if (reducedMotion) 0 else 300),
        label = "usageTrack",
    )
    val fill = footerUsageFillColor(pct)
    val shape = RoundedCornerShape(2.dp)

    Box(
        modifier
            .height(4.dp)
            .clip(shape)
            .background(tokens.surfaceHover.color),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(fill),
            )
        }
    }
}

/**
 * One labelled meter row in `ChatMoreMenu` (`UsageMeter` in `usage-meters.tsx`):
 * `"5 hours ▬▬▬── 47%  in 3h 12m"`. A quick view, so the reset is the countdown alone (`64dp`
 * right-aligned column); `SwitchAccountView`'s cards add the clock time.
 */
@Composable
fun UsageMeter(
    label: String,
    pct: Int?,
    resetsAt: String? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val now = rememberResetClockMillis()
    val reset = formatResetCountdown(resetsAt, now)
    Row(
        modifier
            .padding(top = 6.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            label,
            Modifier.width(52.dp),
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text2.color),
            maxLines = 1,
            softWrap = false,
        )
        UsageTrack(pct = pct, modifier = Modifier.weight(1f))
        BasicText(
            if (pct != null) "$pct%" else "--%",
            Modifier.width(36.dp),
            style = ppmText(11f, lineHeight = 16f).copy(
                color = tokens.text2.color,
                textAlign = TextAlign.End,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            softWrap = false,
        )
        BasicText(
            reset.orEmpty(),
            Modifier
                .width(64.dp)
                .testTag("meter-reset:$label"),
            style = ppmText(10.5f, lineHeight = 15f).copy(
                color = tokens.text3.color,
                textAlign = TextAlign.End,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}
