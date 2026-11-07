package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText

/**
 * What app.tsx pins over the layout: the beta ribbon in a top corner and, on a phone, the device
 * name top-left and the screen-awake pill between them (the desktop layout shows that in the
 * status bar instead). Each shell draws it over its content and under its drawer, as the web's
 * fixed `z-50` corners sit under the drawer that comes after them — and under a chat's drop
 * overlay, which comes after them too; menus and sheets are windows of their own, over both.
 * [modifier] goes inside the insets, so a blur stops where the shell's content does.
 */
@Composable
fun CornerChrome(model: AppModel, mobile: Boolean, modifier: Modifier = Modifier) {
    // Inside the same insets as the shell under it.
    Box(Modifier.fillMaxSize().then(if (mobile) Modifier.statusBarsPadding() else Modifier.safeDrawingPadding()).then(modifier)) {
        BetaRibbon(mobile, Modifier.align(if (mobile) Alignment.TopEnd else Alignment.TopStart))
        if (mobile) {
            model.info?.deviceName?.takeIf { it.isNotEmpty() }?.let { DeviceNameBadge(it, Modifier.align(Alignment.TopStart)) }
            if (model.screenKeptAwake()) ScreenAwakePill(Modifier.align(Alignment.TopCenter))
        }
        model.branchPickerProject?.let { proj ->
            val repo = com.hienlh.ppm.state.git.GitRepoScope.resolveRepo(model.gitDiscovery[proj], model.gitChosenRepoPath[proj])
            com.hienlh.ppm.app.ui.git.GitBranchPickerModal(
                model = model,
                project = proj,
                repo = repo,
                onClose = { model.closeBranchPicker() },
                onChanged = { model.notifyGitChanged(proj) },
            )
        }
    }
}

/** Tailwind's `shadow-sm`. */
internal val SHADOW_SM = listOf(
    ThemeShadow(0.0, 1.0, 3.0, 0.0, Rgba(0.0, 0.0, 0.0, 0.1)),
    ThemeShadow(0.0, 1.0, 2.0, -1.0, Rgba(0.0, 0.0, 0.0, 0.1)),
)

/**
 * The beta ribbon (app.tsx): a `w-10 h-10 overflow-hidden` corner crossed by a 60 px band,
 * `bg-warning text-white text-[6px] font-bold leading-none py-[2.5px] shadow-sm`, turned 45°.
 * Top-left on the desktop layout, top-right on a phone.
 */
@Composable
fun BetaRibbon(mobile: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val band = Modifier
        // Wider than the corner it is cut by: measured free of it, placed from its top-left.
        .wrapContentSize(Alignment.TopStart, unbounded = true)
        // `left-[-18px]`, or `right-[-18px]` in the 40 px corner.
        .offset(x = if (mobile) (-2).dp else (-18).dp, y = 4.dp)
        .graphicsLayer { rotationZ = if (mobile) 45f else -45f }
    Box(modifier.size(40.dp).clipToBounds()) {
        // A 6 px line and 2.5 px above and below. The height is set rather than left to the text:
        // Android will not lay a line out shorter than the font, so `leading-none` cannot be had.
        Box(
            SHADOW_SM.fold(band) { m, shadow -> m.ppmShadow(shadow, RectangleShape) }
                .background(tokens.warning.color)
                .size(60.dp, 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Centred on the line, as CSS centres glyphs taller than their line box.
            BasicText("BETA", Modifier.wrapContentHeight(unbounded = true), style = ppmText(6f, FontWeight.Bold).copy(color = Color.White))
        }
    }
}

/** A phone's device-name badge, top-left: `px-2 py-0.5 bg-primary/80 text-primary-foreground text-[10px] font-medium rounded-br`. */
@Composable
fun DeviceNameBadge(name: String, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    BasicText(
        name,
        modifier
            .background(tokens.accent.opacity(0.8).color, RoundedCornerShape(bottomEnd = 4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accentFg.color),
        maxLines = 1,
    )
}

/**
 * A phone's "Screen awake" pill, top-centre between the badge and the ribbon (`WakeLockMobileBadge`):
 * `px-2 py-0.5 rounded-b bg-warning/85 text-white text-[10px] font-medium`, a 12 px sun first.
 */
@Composable
fun ScreenAwakePill(modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier
            .background(tokens.warning.opacity(0.85).color, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(ProductIcon.Sun, 12.dp, Color.White)
        BasicText("Screen awake", style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = Color.White))
    }
}
