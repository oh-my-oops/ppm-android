package com.hienlh.ppm.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ChatDevicePrefs
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.state.SessionArchive
import com.hienlh.ppm.state.SessionStatusFilter

/** "Never", "7 days": an auto-archive choice as the select names it. */
internal fun autoArchiveLabel(days: Int) = if (days == 0) "Never" else "$days days"

/**
 * The history list's status filter and the device's auto-archive rule (`session-history-list.tsx`):
 * `flex items-center gap-1 px-2 pb-1.5 overflow-x-auto`, one pill per status (Active hides
 * archived chats), then `ml-auto` the Archive icon and the days select — a menu on a tablet, a
 * sheet on a phone, where the web's `<select>` opens the system's picker.
 */
@Composable
internal fun HistoryStatusFilterRow(prefs: ChatDevicePrefs) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var choosing by remember { mutableStateOf(false) }
    var selectHeight by remember { mutableStateOf(0) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
            .semantics { contentDescription = "Show sessions" }
            .testTag("history-status-filter"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (filter in SessionStatusFilter.entries) {
            Box(Modifier.semantics { role = Role.RadioButton; selected = prefs.statusFilter == filter }.testTag("history-status:" + filter.wire)) {
                PillChip(filter.label, prefs.statusFilter == filter, null) { prefs.chooseStatus(filter) }
            }
        }
        Spacer(Modifier.padding(start = 4.dp))
        Box {
            Row(
                Modifier
                    .then(if (mobile) Modifier.heightIn(min = 44.dp) else Modifier)
                    .clip(RoundedCornerShape(4.dp))
                    .webTitle("Archive chats idle this long (pinned chats never)")
                    .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Auto-archive") { choosing = true }
                    .onSizeChanged { selectHeight = it.height }
                    .semantics { contentDescription = "Auto-archive: " + autoArchiveLabel(prefs.autoArchiveDays) }
                    .testTag("history-auto-archive")
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(ProductIcon.Archive, 12.dp, tokens.text3.color)
                BasicText(autoArchiveLabel(prefs.autoArchiveDays), style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color), maxLines = 1)
                PpmIcon(ProductIcon.ChevronDown, 10.dp, tokens.text3.color)
            }
            if (choosing && !mobile) {
                ContextMenuPopup(Offset(0f, selectHeight.toFloat()), onDismiss = { choosing = false }) {
                    for (days in SessionArchive.AUTO_ARCHIVE_DAYS) {
                        MenuItem(
                            autoArchiveLabel(days),
                            onClick = {
                                choosing = false
                                prefs.chooseAutoArchive(days)
                            },
                            trailing = if (days == prefs.autoArchiveDays) ({ MenuIcon(ProductIcon.Check) }) else null,
                        )
                    }
                }
            }
        }
    }
    if (choosing && mobile) {
        Sheet(title = "Auto-archive", subtitle = "Archive chats idle this long (pinned chats never)", onDismiss = { choosing = false }, forceBottomSheet = true) {
            for (days in SessionArchive.AUTO_ARCHIVE_DAYS) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .ppmClickable(hoverColor = tokens.surfaceHover.color, onClickLabel = autoArchiveLabel(days)) {
                            choosing = false
                            prefs.chooseAutoArchive(days)
                        }
                        .padding(horizontal = 20.dp)
                        .testTag("auto-archive-option:$days"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(autoArchiveLabel(days), Modifier.weight(1f), style = ppmText(14f, lineHeight = 19f).copy(color = tokens.text.color))
                    if (days == prefs.autoArchiveDays) PpmIcon(ProductIcon.Check, 18.dp, tokens.accent.color)
                }
            }
        }
    }
}
