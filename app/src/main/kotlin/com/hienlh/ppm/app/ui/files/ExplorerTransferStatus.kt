package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.downloadSize
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/** Mount in ExplorerTree(transferStatus = { ExplorerTransferStatus(transfers) }). */
@Composable
fun ExplorerTransferStatus(transfers: ExplorerTransfers, modifier: Modifier = Modifier) {
    val state by transfers.state.collectAsState()
    if (state.phase == ExplorerTransferPhase.IDLE) return
    val tokens = LocalPpmTokens.current
    val text = when (state.phase) {
        ExplorerTransferPhase.DOWNLOADING -> "Downloading ${state.name} · ${transferProgressText(state)}"
        ExplorerTransferPhase.UPLOADING -> "Uploading ${state.name} · ${transferProgressText(state)}"
        else -> state.message.orEmpty()
    }
    Column(modifier.fillMaxWidth().padding(8.dp)) {
        // Announce phase/result changes, not every network chunk.
        BasicText(text, Modifier.semantics { if (!state.busy) liveRegion = LiveRegionMode.Polite },
            style = ppmText(12f, lineHeight = 16f).copy(color = if (state.phase == ExplorerTransferPhase.FAILED) tokens.error.color else tokens.text2.color))
        Row(Modifier.ppmClickable(onClickLabel = if (state.busy) "Cancel transfer" else "Dismiss transfer status",
            onClick = { if (state.busy) transfers.cancel() else transfers.dismiss() }).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PpmIcon(ProductIcon.X, 12.dp, tokens.text2.color)
            BasicText(if (state.busy) "Cancel" else "Dismiss", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
        }
    }
}

private fun transferProgressText(state: ExplorerTransferState): String {
    val progress = state.progress
    val total = progress.total
    return if (total == null) downloadSize(progress.bytes)
    else "${downloadSize(progress.bytes)} / ${downloadSize(total)}"
}
