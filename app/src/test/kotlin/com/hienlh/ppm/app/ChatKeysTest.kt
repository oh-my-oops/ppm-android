package com.hienlh.ppm.app

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.state.ChatViewMode
import com.hienlh.ppm.state.SessionStatusFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat keys (`dispatchChatKeys`): Ctrl+O cycles the view mode from anywhere, the composer
 * included; Alt+T and Alt+P stay another field's own; Alt+Shift+T takes the chat closed last. And
 * the device's chat preferences, kept across a restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatKeysTest {
    private lateinit var model: AppModel
    private lateinit var shortcuts: KeyboardShortcuts
    private val field = TextFieldState()

    @Before
    fun setUp() {
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        shortcuts = KeyboardShortcuts(model)
        model.chatPrefs.chooseViewMode(ChatViewMode.NORMAL)
    }

    @After
    fun release() {
        TextInputFocus.release(field)
    }

    private fun down(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    @Test
    fun `Ctrl+O cycles Normal, Thinking, Verbose, from a text field too`() {
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON)))
        assertEquals(ChatViewMode.THINKING, model.chatPrefs.viewMode)
        TextInputFocus.take(field)
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON)))
        assertEquals(ChatViewMode.VERBOSE, model.chatPrefs.viewMode)
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON)))
        assertEquals(ChatViewMode.NORMAL, model.chatPrefs.viewMode)
    }

    @Test
    fun `Alt+T and Alt+P typed into a field that is no chat's composer stay that field's`() {
        TextInputFocus.take(field)
        assertFalse(shortcuts.dispatch(down(KeyEvent.KEYCODE_T, KeyEvent.META_ALT_ON)))
        assertFalse(shortcuts.dispatch(down(KeyEvent.KEYCODE_P, KeyEvent.META_ALT_ON)))
        TextInputFocus.release(field)
        // With no chat in front they are spent and do nothing.
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_T, KeyEvent.META_ALT_ON)))
    }

    @Test
    fun `Alt+Shift+T takes the chat closed last`() {
        model.closedChats.remember("chat", "elsewhere", "s1", "claude", "One")
        model.closedChats.remember("chat", "elsewhere", "s2", "codex", "Two")
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_T, KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)))
        assertEquals(1, model.closedChats.size)
        assertEquals("s1", model.closedChats.takeLast()?.sessionId)
    }

    @Test
    fun `the view mode, status filter and auto-archive days are kept on the device`() {
        val saved = mutableMapOf<String, String?>()
        val store = object : KeyValueStore {
            override fun get(key: String) = saved[key]
            override fun put(key: String, value: String?) {
                saved[key] = value
            }
        }
        val prefs = ChatDevicePrefs(store)
        assertEquals(SessionStatusFilter.ACTIVE, prefs.statusFilter)
        assertEquals(0, prefs.autoArchiveDays)
        prefs.chooseStatus(SessionStatusFilter.ARCHIVED)
        prefs.chooseAutoArchive(30)
        prefs.chooseAutoArchive(5) // not a choice the select offers
        prefs.cycleViewMode()
        assertEquals("archived", saved["ppm.chatHistory.status"])
        assertEquals("30", saved["ppm.chatHistory.autoArchiveDays"])
        assertEquals("thinking", saved["ppm.chat.viewMode"])
        val again = ChatDevicePrefs(store)
        assertEquals(SessionStatusFilter.ARCHIVED, again.statusFilter)
        assertEquals(30, again.autoArchiveDays)
        assertEquals(ChatViewMode.THINKING, again.viewMode)
    }
}
