package com.hienlh.ppm.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.withComposeActivity
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The terminal's Links sheet as the web draws it (`terminal-links-sheet.tsx`, `terminal-finish-login.tsx`): what each row holds and
 * does, the note and the finish-login step a login that redirects to the machine's own port brings, the toasts, and how tall the
 * sheet gets. The sheet takes what it acts on as parameters, so the whole of it runs here without a server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class TerminalLinksSheetTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val opened = mutableListOf<String>()
    private val copied = mutableListOf<String>()
    private val toasts = mutableListOf<ErrorToastMessage>()
    private val delivered = mutableListOf<String>()
    private var closed = 0
    private var copyWorks = true
    private var refusal: Throwable? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var pageView: android.view.View? = null

    private val plain = "https://example.com/docs?page=2"
    private val login = "https://auth.example.com/authorize?client_id=abc&redirect_uri=http%3A%2F%2Flocalhost%3A54321%2Fcallback&state=xyz"
    private val callback = "http://127.0.0.1:54321/callback?code=abc&state=xyz"

    private fun show(vararg urls: String) = compose.setContent {
        val scope = rememberCoroutineScope()
        val view = LocalView.current
        SideEffect { pageView = view }
        TerminalLinksSheet(
            urls = urls.toList(),
            scope = scope,
            onClose = { closed++ },
            open = { opened += it },
            copy = { copied += it; copyWorks },
            toast = { toasts += it },
            deliver = { url ->
                delivered += url
                gate?.await()
                refusal?.let { throw it }
            },
        )
    }

    private fun type(text: String) {
        compose.onNodeWithContentDescription("Callback address").performTextInput(text)
        compose.waitForIdle()
    }

    @Test
    fun `the header counts the links`() {
        show(plain, login)
        compose.onNodeWithText("Links in terminal (2)").assertExists()
        compose.onNodeWithText(plain).assertExists()
        compose.onNodeWithText(login).assertExists()
    }

    @Test
    fun `with no link the sheet says so, under a header with no count and no rows`() {
        show()
        compose.onNodeWithText("Links in terminal").assertExists()
        compose.onNodeWithText("No links found in the visible scrollback.").assertExists()
        compose.onNodeWithText("Links in terminal (0)").assertDoesNotExist()
        compose.onNodeWithContentDescription("Callback address").assertDoesNotExist()
    }

    @Test
    fun `a row is the address and a Copy button named after it`() {
        show(plain)
        compose.onNodeWithText(plain).assertExists()
        compose.onNodeWithContentDescription("Copy $plain").assertExists()
    }

    @Test
    fun `the Copy button is a 44 dp square, 4 dp from the link it belongs to`() {
        show(plain)
        // The link's own height is not asserted: the text of a Robolectric test is laid out with font metrics that are not a device's.
        // These are layout bounds. The accessibility bounds a device reports for the button are 48 dp wide: Compose widens the touch
        // area of anything smaller than that (measured here: 44 dp layout, 48 dp touch bounds), and the web's button has no such area.
        val link = compose.onNodeWithText(plain).getBoundsInRoot()
        val copy = compose.onNodeWithContentDescription("Copy $plain").getBoundsInRoot()
        assertEquals("Copy width", 44.dp, copy.width)
        assertEquals("Copy height", 44.dp, copy.height)
        assertEquals("gap", 4.dp, copy.left - link.right)
    }

    @Test
    fun `a tap on a link hands it to the browser and closes the sheet`() {
        show(plain, login)
        compose.onNodeWithText(login).performClick()
        compose.waitForIdle()
        assertEquals(listOf(login), opened)
        assertEquals(1, closed)
        assertEquals(emptyList<String>(), copied)
        assertEquals(emptyList<ErrorToastMessage>(), toasts)
    }

    @Test
    fun `Copy copies that link, says so for a second and a half, and closes the sheet`() {
        show(plain, login)
        compose.onNodeWithContentDescription("Copy $login").performClick()
        compose.waitForIdle()
        assertEquals(listOf(login), copied)
        assertEquals(emptyList<String>(), opened)
        assertEquals(1, closed)
        assertEquals(1, toasts.size)
        assertEquals("Link copied", toasts[0].title)
        assertEquals(ErrorToastMessage.Kind.SUCCESS, toasts[0].kind)
        assertEquals(1_500L, toasts[0].durationMillis)
    }

    @Test
    fun `a copy the system refused is an error toast of the same length, and the sheet still closes`() {
        copyWorks = false
        show(plain)
        compose.onNodeWithContentDescription("Copy $plain").performClick()
        compose.waitForIdle()
        assertEquals("Could not copy link", toasts.single().title)
        assertEquals(ErrorToastMessage.Kind.ERROR, toasts.single().kind)
        assertEquals(1_500L, toasts.single().durationMillis)
        assertEquals(1, closed)
    }

    @Test
    fun `a login that redirects to the machine says so under its row, and brings the finish-login step`() {
        show(plain, login)
        compose.onNodeWithText(
            "Signing in here sends you back to port 54321 on the machine, not this device — so the terminal keeps waiting. Afterwards, paste the address bar below to finish it.",
        ).assertExists()
        compose.onNodeWithText("Finished signing in and landed on a page that would not load? Paste that address here — the machine will deliver it to the command still waiting.").assertExists()
        compose.onNodeWithContentDescription("Callback address").assertExists()
        compose.onNodeWithContentDescription("Finish login").assertExists()
        compose.onNodeWithText("http://127.0.0.1:54132/oauth/callback?code=…").assertExists()
    }

    @Test
    fun `only the row of a login has the note, and without one there is no finish-login step`() {
        show(plain)
        compose.onNodeWithText("Signing in here", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Finished signing in", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Finish login").assertDoesNotExist()
    }

    @Test
    fun `Finish login is dead until the pasted address is one the machine can deliver`() {
        show(login)
        compose.onNodeWithContentDescription("Finish login").assertIsNotEnabled()
        compose.onNodeWithText("Needs to be the http://127.0.0.1:… address the sign-in redirected to.").assertDoesNotExist()
        type("hello")
        compose.onNodeWithContentDescription("Finish login").assertIsNotEnabled()
        compose.onNodeWithText("Needs to be the http://127.0.0.1:… address the sign-in redirected to.").assertExists()
        type(" and more")
        compose.onNodeWithText("Needs to be the http://127.0.0.1:… address the sign-in redirected to.").assertExists()
    }

    @Test
    fun `an address on another machine is refused with the reason, a loopback one is not`() {
        show(login)
        type("https://example.com/callback?code=abc")
        compose.onNodeWithContentDescription("Finish login").assertIsNotEnabled()
        compose.onNodeWithText("Needs to be the http://127.0.0.1:… address the sign-in redirected to.").assertExists()
    }

    @Test
    fun `a loopback address enables it and takes the reason away`() {
        show(login)
        type(callback)
        compose.onNodeWithContentDescription("Finish login").assertIsEnabled()
        compose.onNodeWithText("Needs to be the http://127.0.0.1:… address the sign-in redirected to.").assertDoesNotExist()
    }

    @Test
    fun `an address is delivered as it is trimmed, with a toast for two seconds, the field emptied and the sheet closed`() {
        show(login)
        type("  $callback \n")
        compose.onNodeWithContentDescription("Finish login").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf(callback), delivered)
        assertEquals("Sent to the waiting command", toasts.single().title)
        assertEquals(ErrorToastMessage.Kind.SUCCESS, toasts.single().kind)
        assertEquals(2_000L, toasts.single().durationMillis)
        assertEquals(1, closed)
        compose.onNodeWithText(callback, substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Finish login").assertIsNotEnabled()
    }

    @Test
    fun `an address the server refused is the server's reason in an error toast for four seconds, and the sheet and the field stay`() {
        refusal = IllegalStateException("Nothing is waiting on that port")
        show(login)
        type(callback)
        compose.onNodeWithContentDescription("Finish login").performClick()
        compose.waitForIdle()
        assertEquals(listOf(callback), delivered)
        assertEquals("Nothing is waiting on that port", toasts.single().title)
        assertEquals(ErrorToastMessage.Kind.ERROR, toasts.single().kind)
        assertEquals(4_000L, toasts.single().durationMillis)
        assertEquals(0, closed)
        compose.onNodeWithText(callback, substring = true).assertExists()
        compose.onNodeWithContentDescription("Finish login").assertIsEnabled()
    }

    @Test
    fun `a refusal with no message still says what it was`() {
        refusal = RuntimeException()
        show(login)
        type(callback)
        compose.onNodeWithContentDescription("Finish login").performClick()
        compose.waitForIdle()
        assertEquals("java.lang.RuntimeException", toasts.single().title)
    }

    @Test
    fun `while the address is on its way the button cannot be pressed again`() {
        gate = CompletableDeferred()
        show(login)
        type(callback)
        compose.onNodeWithContentDescription("Finish login").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Finish login").assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf(callback), delivered)
        assertEquals(0, closed)
        gate!!.complete(Unit)
        compose.waitForIdle()
        assertEquals(1, closed)
        assertEquals(listOf(callback), delivered)
    }

    @Test
    fun `the sheet is at most seventy percent of the screen tall, and that tall once the links need it`() {
        show(*Array(40) { "https://example.com/page/$it" })
        val height = compose.onNodeWithTag(TERMINAL_LINKS_SHEET_TAG).getBoundsInRoot().height
        assertTrue("sheet is $height", height <= 560.dp + 0.5.dp)
        assertTrue("sheet is $height", height >= 560.dp - 0.5.dp)
    }

    @Test
    fun `seventy percent is of what is under the status bar, as a vh is Chrome's large viewport`() {
        show(*Array(40) { "https://example.com/page/$it" })
        compose.runOnUiThread {
            val statusBar = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0)).build()
            ViewCompat.dispatchApplyWindowInsets(pageView!!, statusBar)
        }
        compose.waitForIdle()
        val height = compose.onNodeWithTag(TERMINAL_LINKS_SHEET_TAG).getBoundsInRoot().height
        assertTrue("sheet is $height", height <= 490.dp + 0.5.dp)
        assertTrue("sheet is $height", height >= 490.dp - 0.5.dp)
    }

    @Test
    fun `a few links make a sheet no taller than they are`() {
        show(plain)
        val height = compose.onNodeWithTag(TERMINAL_LINKS_SHEET_TAG).getBoundsInRoot().height
        assertTrue("sheet is $height", height < 300.dp)
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun `an http address goes to the browser as a view of it, in a task of its own`() {
        var unavailable = 0
        openInBrowser(app, "https://example.com/a?b=1#c", { unavailable++ })
        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/a?b=1#c", intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, unavailable)
    }

    @Test
    fun `a scheme in capitals is the scheme intents are matched on`() {
        openInBrowser(app, "HTTPS://EXAMPLE.COM/A", { })
        assertEquals("https", started()!!.data!!.scheme)
    }

    @Test
    fun `what is not an http address is not opened, and not reported either`() {
        var unavailable = 0
        for (value in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;scheme=http;end", "mailto:a@b.test", "example.com/a", "")) {
            openInBrowser(app, value, { unavailable++ })
            assertNull(value, started())
        }
        assertEquals(0, unavailable)
    }

    @Test
    fun `with no browser on the device it says so`() {
        shadowOf(app).checkActivities(true)
        var unavailable = 0
        openInBrowser(app, "https://example.com/", { unavailable++ })
        assertEquals(1, unavailable)
        assertNull(started())
    }

    private fun confirm(uri: String) = compose.setContent {
        Osc8LinkConfirm(uri, open = { opened += it }, onDismiss = { closed++ })
    }

    @Test
    fun `an OSC 8 link asks first, with its address in the question and the warning under it`() {
        confirm("https://example.com/target")
        compose.onNodeWithText("Do you want to navigate to https://example.com/target?").assertExists()
        compose.onNodeWithText("WARNING: This link could potentially be dangerous").assertExists()
        compose.onNodeWithText("OK").assertExists()
        compose.onNodeWithText("Cancel").assertExists()
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun `OK opens the address and the question goes away`() {
        confirm("https://example.com/target")
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://example.com/target"), opened)
        assertEquals(1, closed)
    }

    @Test
    fun `Cancel opens nothing and the question goes away`() {
        confirm("https://example.com/target")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), opened)
        assertEquals(1, closed)
    }
}
