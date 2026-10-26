package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.chat.SessionDebugBody
import com.hienlh.ppm.chat.SessionDebugSource
import com.hienlh.ppm.client.sessionDebug
import com.hienlh.ppm.client.sessionImages
import com.hienlh.ppm.client.sessionTurnUsage
import com.hienlh.ppm.client.stripSessionImages
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.cssScrollY
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Session debug facts and transcript maintenance (session-debug-dialog.tsx): on a tablet the web's
 * dialog, its title read only to a screen reader (`sr-only`); on a phone a `popover-solid` bottom
 * sheet whose body (`max-h-[85vh] overflow-y-auto px-4 pb-4 pt-1`) scrolls. [messageCount] stands
 * in for the web's count of rendered messages, read when the facts arrive.
 *
 * The web's dialog never scrolls, so on a short page a long history pushes its top and bottom out
 * of reach; here it stops between the system bars and scrolls its body instead.
 */
@Composable
internal fun SessionDebugDialog(model: AppModel, project: String, sessionId: String, messageCount: () -> Int, onDismiss: () -> Unit) {
    val api = model.api ?: return
    val tokens = LocalPpmTokens.current
    val source = remember(api, project, sessionId) {
        SessionDebugSource(
            info = { api.sessionDebug(project, sessionId) },
            turns = { api.sessionTurnUsage(project, sessionId) },
            images = { api.sessionImages(project, sessionId) },
            strip = { all, attachments -> api.stripSessionImages(project, sessionId, all, attachments) },
        )
    }
    val stats = { listOf("Rendered messages: ${messageCount()}", heapLine()) }
    // A `vh` is Chrome's large viewport: everything under the status bar.
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val statusBar = WindowInsets.statusBars.getTop(density)
    val viewport = with(density) { DpSize(window.width.toDp(), (window.height - statusBar).toDp()) }
    if (LocalPpmMobile.current) {
        BottomSheet(onDismiss = onDismiss, background = tokens.panel2.over(tokens.bgSolid).color) {
            SessionDebugBody(
                source,
                stats,
                viewport,
                Modifier.heightIn(max = viewport.height * 0.85f).cssScrollY().padding(horizontal = 16.dp),
                top = LocalSheetContentTop.current + 4.dp,
                bottom = 16.dp,
            )
        }
    } else {
        // Nothing in the body takes the focus as it opens (Copy waits for the facts), so the cross has it.
        WebDialog(title = null, onDismiss = onDismiss, closeFocused = true) {
            SessionDebugBody(source, stats, viewport, Modifier.cssFlex().verticalScroll(rememberScrollState()))
        }
    }
}

/** The app's counterpart of the web's `JS heap (page)`: what the Java heap holds now. */
private fun heapLine(): String {
    val runtime = Runtime.getRuntime()
    val used = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576.0
    return "Java heap (app): ${BigDecimal(used).setScale(1, RoundingMode.HALF_UP).toPlainString()} MB"
}
