package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.chat.ComposerTurnChipView
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.ComposerTurnChip
import com.hienlh.ppm.state.ComposerTurnChips

/** The tab's turn chips as they are now; empty before the tab is started. */
@Composable
internal fun rememberTurnChips(tab: ChatTab?): List<ComposerTurnChip> {
    val flow = tab?.turnLinks?.chips?.chips ?: return emptyList()
    val chips by flow.collectAsState()
    return chips
}

/**
 * The composer's turn chips, above the input, and why the send waits when it does
 * (`ComposerTurnRefRow`). Nothing while there are none.
 */
@Composable
internal fun ComposerTurnChipRow(tab: ChatTab, modifier: Modifier = Modifier) {
    val owner = tab.turnLinks.chips ?: return
    val chips = rememberTurnChips(tab)
    if (chips.isEmpty()) return
    val tokens = LocalPpmTokens.current
    Column(modifier.testTag("composer-turn-chips")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (chip in chips) {
                key(chip.key) {
                    ComposerTurnChipView(chip, tab.project, onRemove = {
                        owner.remove(chip.key)
                        tab.requestFocus()
                    })
                }
            }
        }
        ComposerTurnChips.blockedReason(chips)?.let { reason ->
            BasicText(
                reason,
                Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
        }
    }
}
