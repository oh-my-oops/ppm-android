package com.hienlh.ppm.app.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatDevicePrefs
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionInfoData
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionInfoSheet
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.SessionBookmark
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.ChatViewMode
import com.hienlh.ppm.state.SessionStatusFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The history's status filter and archive actions, and the ··· menu's View, Bookmarks and Export, on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhoneChatHistoryExtrasUiTest : ChatHistoryExtrasUiTest(mobile = true)

/** The same on a tablet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class TabletChatHistoryExtrasUiTest : ChatHistoryExtrasUiTest(mobile = false)

abstract class ChatHistoryExtrasUiTest(private val mobile: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private fun prefs() = ChatDevicePrefs(
        object : KeyValueStore {
            val saved = mutableMapOf<String, String?>()
            override fun get(key: String) = saved[key]
            override fun put(key: String, value: String?) {
                saved[key] = value
            }
        },
    )

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) = composeRule.setContent {
        PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
            CompositionLocalProvider(LocalReducedMotion provides true) { content() }
        }
    }

    @Test
    fun statusFilter_picksAStatusAndTheAutoArchiveDays() {
        val prefs = prefs()
        themed { Column { HistoryStatusFilterRow(prefs) } }
        composeRule.onNodeWithText("Active").assertExists()
        composeRule.onNodeWithTag("history-status:archived").performClick()
        assertEquals(SessionStatusFilter.ARCHIVED, prefs.statusFilter)
        if (mobile) {
            composeRule.onNodeWithContentDescription("Auto-archive: Never").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Auto-archive").assertExists()
            composeRule.onNodeWithTag("auto-archive-option:30").performClick()
        } else {
            // The tablet's menu is a window Popup, which Robolectric cannot lay out here: the
            // choice is made through the same preferences the menu writes.
            composeRule.onNodeWithContentDescription("Auto-archive: Never").assertExists()
            prefs.chooseAutoArchive(30)
        }
        composeRule.waitForIdle()
        assertEquals(30, prefs.autoArchiveDays)
        composeRule.onNodeWithContentDescription("Auto-archive: 30 days").assertExists()
    }

    @Test
    fun moreMenu_viewCyclesBookmarksShowsItsCountAndExportRuns() {
        var mode by mutableStateOf(ChatViewMode.NORMAL)
        var exports = 0
        var opened = 0
        themed {
            ChatMoreMenu(
                onOpenAISettings = {},
                extras = ChatMoreExtras(
                    viewMode = mode,
                    onCycleView = { mode = mode.next() },
                    bookmarks = ChatMoreExtras.Bookmarks(2) { _, _, _ -> },
                    onExport = { exports++ },
                ),
                onOpenBookmarks = { opened++ },
            )
        }
        composeRule.onNodeWithText("Normal · Ctrl+O").assertExists()
        composeRule.onNodeWithTag("more-view-mode").performClick()
        composeRule.onNodeWithText("Thinking · Ctrl+O").assertExists()
        composeRule.onNodeWithText("2").assertExists()
        composeRule.onNodeWithTag("more-bookmarks").performClick()
        composeRule.onNodeWithTag("more-export-markdown").performClick()
        assertEquals(1, opened)
        assertEquals(1, exports)
    }

    @Test
    fun bookmarksView_jumpsRemovesAndSaysWhenEmpty() {
        var list by mutableStateOf<List<SessionBookmark>?>(null)
        val jumped = mutableListOf<String>()
        themed {
            BookmarksView(list, onBack = {}, onJump = { jumped += it.turnId }, onRemove = { b -> list = list.orEmpty() - b })
        }
        composeRule.onNodeWithText("Loading…").assertExists()
        list = emptyList()
        composeRule.onNodeWithText("No bookmarks yet. Bookmark a turn from the bar under any message you sent.").assertExists()
        list = listOf(SessionBookmark("t3", 3, "Fix the login", "x"), SessionBookmark("t7", 7, "", "x"))
        composeRule.onNodeWithText("Fix the login").performClick()
        assertEquals(listOf("t3"), jumped)
        composeRule.onNodeWithText("Turn 3").assertExists()
        composeRule.onNodeWithTag("bookmark-remove:t7").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("bookmark-row:t7").assertDoesNotExist()
    }

    @Test
    fun archive_rowsMarkAndOfferArchiveOrUnarchive() {
        val archived = SessionInfo("s1", "claude", "Old work", archive = "archived")
        if (mobile) {
            var picked = 0
            themed {
                PhoneSessionActionSheet(
                    session = archived,
                    onDismiss = {},
                    onTogglePin = {},
                    onMarkUnread = {},
                    onRename = {},
                    onDelete = {},
                    archived = true,
                    onArchive = { picked++ },
                )
            }
            composeRule.onNodeWithText("Unarchive").performClick()
            assertEquals(1, picked)
        } else {
            val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
            themed { SidebarSessionRow(model, archived, null, false) {} }
            composeRule.onNodeWithContentDescription("Archived").assertExists()
            composeRule.onNodeWithContentDescription("Unarchive session").assertExists()
        }
    }

    @Test
    fun phoneSessionInfo_holdsViewBookmarksAndExport() {
        if (!mobile) return
        var mode by mutableStateOf(ChatViewMode.NORMAL)
        var dismissed = 0
        var exports = 0
        themed {
            PhoneSessionInfoSheet(
                info = PhoneSessionInfoData("s1", "Chat", "Opus", "Ask", "Me", "$0.01", "ppm"),
                onRename = null,
                onOpenModelSheet = {},
                onOpenModeSheet = {},
                onMarkAsUnread = null,
                onShowLogs = null,
                onOpenSessionDebug = null,
                onDismiss = { dismissed++ },
                extras = ChatMoreExtras(
                    viewMode = mode,
                    onCycleView = { mode = mode.next() },
                    bookmarks = ChatMoreExtras.Bookmarks(1) { _, jumped, header ->
                        assertTrue(!header)
                        androidx.compose.foundation.text.BasicText("Bookmarks body", androidx.compose.ui.Modifier.clickable(onClick = jumped))
                    },
                    onExport = { exports++ },
                ),
            )
        }
        composeRule.onNodeWithText("Normal · Ctrl+O").performClick()
        composeRule.onNodeWithText("Thinking · Ctrl+O").assertExists()
        assertEquals(0, dismissed)
        composeRule.onNodeWithTag("phone-more-bookmarks").performClick()
        composeRule.onNodeWithText("Bookmarks body").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, exports)
    }
}
