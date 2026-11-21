package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.ChatMoreExtras
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.Sheet

/**
 * The phone's View, Bookmarks and Export as Markdown, as the web's ··· menu shows them on a phone,
 * grouped under Session info's own actions. View cycles in place, as the menu row does.
 */
@Composable
internal fun PhoneChatExtraRows(extras: ChatMoreExtras, onOpenBookmarks: () -> Unit, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape),
    ) {
        SessionInfoActionRow(
            icon = ProductIcon.Eye,
            label = "View",
            hint = extras.viewMode.label + " · Ctrl+O",
            testTag = "phone-more-view-mode",
            onClick = extras.onCycleView,
        )
        extras.bookmarks?.let { bookmarks ->
            SessionInfoActionRow(
                icon = ProductIcon.Bookmark,
                label = "Bookmarks",
                showTopBorder = true,
                hint = bookmarks.count.takeIf { it > 0 }?.toString(),
                testTag = "phone-more-bookmarks",
                onClick = onOpenBookmarks,
            )
        }
        extras.onExport?.let { export ->
            SessionInfoActionRow(
                icon = ProductIcon.Download,
                label = "Export as Markdown",
                showTopBorder = true,
                testTag = "phone-more-export-markdown",
                onClick = {
                    onDismiss()
                    export()
                },
            )
        }
    }
}

/** Bookmarks in place of Session info: Back returns to it, a tap on a turn closes both. */
@Composable
internal fun PhoneBookmarksSheet(bookmarks: ChatMoreExtras.Bookmarks, onBack: () -> Unit, onDismiss: () -> Unit) {
    Sheet(title = "Bookmarks", onDismiss = onDismiss, onBack = onBack, forceBottomSheet = true) {
        bookmarks.content(onBack, onDismiss, false)
    }
}
