package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag

/**
 * The backdrop above a sheet opened inside a page. A phone page's sheets are laid out under its app bar, so
 * their own scrim stops there and leaves the app bar and the status bar lit. Each such [Sheet] registers here,
 * and the shell's [SheetScrimLayerHost] dims the strip above it with the same alpha: one backdrop over the
 * whole window, as a system bottom sheet has. A tap on that strip dismisses the sheet, as on its own scrim.
 */
class SheetScrimLayer {
    class Entry internal constructor(internal val dismiss: () -> Unit) {
        /** Where the sheet's own scrim starts, in window pixels. */
        internal var topPx by mutableFloatStateOf(0f)
        internal var alpha by mutableFloatStateOf(0f)
    }

    private val entries = mutableStateListOf<Entry>()

    internal fun add(entry: Entry) {
        entries += entry
    }

    internal fun remove(entry: Entry) {
        entries -= entry
    }

    internal val top: Entry? get() = entries.lastOrNull()
}

/** Null outside a shell that hosts the layer: sheets then keep to their own scrim. */
val LocalSheetScrimLayer = staticCompositionLocalOf<SheetScrimLayer?> { null }

/** Put at the top of the shell's root box, after the page: dims from there down to the open sheet's scrim. */
@Composable
fun SheetScrimLayerHost(layer: SheetScrimLayer, modifier: Modifier = Modifier) {
    var hostTopPx by remember { mutableFloatStateOf(0f) }
    val entry = layer.top
    val gapPx = (entry?.topPx ?: 0f) - hostTopPx
    val height = with(LocalDensity.current) { gapPx.coerceAtLeast(0f).toDp() }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned { hostTopPx = it.positionInWindow().y }
            .then(
                if (entry != null && gapPx > 0f) {
                    Modifier
                        .background(Color(0f, 0f, 0f, entry.alpha))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "Dismiss sheet",
                            onClick = entry.dismiss,
                        )
                        .testTag("sheet-scrim-above")
                } else {
                    Modifier
                },
            ),
    )
}
