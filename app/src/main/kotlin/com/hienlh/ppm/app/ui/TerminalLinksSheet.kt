package com.hienlh.ppm.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.isJsSpace
import com.hienlh.ppm.state.terminal.TerminalText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What `<a target="_blank">` and `window.open` do on the web: the address goes to the browser the device opens it with. Only an
 * `http(s)` address does, as only one is a link; [unavailable] runs when nothing on the device opens it.
 */
internal fun openInBrowser(context: Context, value: String, unavailable: () -> Unit) {
    val uri = Uri.parse(value).normalizeScheme()
    if (uri.scheme != "http" && uri.scheme != "https") return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        unavailable()
    }
}

/** The sheet's panel, for a test to measure. */
internal const val TERMINAL_LINKS_SHEET_TAG = "terminal-links-sheet"

/** How long `terminal-links-sheet.tsx` and `terminal-finish-login.tsx` keep their toasts (`duration`). */
internal const val LINK_COPIED_TOAST_MS = 1_500L
internal const val LOGIN_SENT_TOAST_MS = 2_000L
internal const val LOGIN_FAILED_TOAST_MS = 4_000L

/**
 * The web's `TerminalLinksSheet` (`terminal-links-sheet.tsx`): the URLs in the terminal's scrollback, over a sheet at most 70 vh tall.
 * A touch device cannot activate a link in the terminal itself, so this is its way to open one or to copy it: each row is the link
 * (an address in mono, `min-h-11`) and a 44 dp Copy button. A login that will redirect back to a port of the machine adds a note under
 * its row and, under the list, [TerminalFinishLogin]. [open] hands an address to the browser; [copy] answers whether it was copied.
 */
@Composable
internal fun TerminalLinksSheet(
    urls: List<String>,
    scope: CoroutineScope,
    onClose: () -> Unit,
    open: (String) -> Unit,
    copy: (String) -> Boolean,
    toast: (ErrorToastMessage) -> Unit,
    deliver: suspend (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    // A `vh` is Chrome's large viewport: everything under the status bar.
    val density = LocalDensity.current
    val vh = with(density) { (LocalWindowInfo.current.containerSize.height - WindowInsets.statusBars.getTop(density)).toDp() }
    // The ports any listed login will redirect back to: their presence is what makes the finish-login step relevant.
    val loopback = urls.any { TerminalText.loopbackRedirectPort(it) != null }
    BottomSheet(onClose, Modifier.heightIn(max = vh * 0.7f).testTag(TERMINAL_LINKS_SHEET_TAG)) {
        // `shrink-0 px-4 pb-2 text-xs font-medium text-text-secondary`.
        BasicText(
            if (urls.isNotEmpty()) "Links in terminal (${urls.size})" else "Links in terminal",
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
        )
        // `flex-1 min-h-0 overflow-y-auto px-2 pb-2`.
        Column(Modifier.weight(1f, fill = false).cssScrollY().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            if (urls.isEmpty()) {
                // `flex flex-col items-center gap-2 px-4 py-8 text-center text-sm text-text-secondary`.
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Link2Off, 20.dp, tokens.text3.color)
                    BasicText(
                        "No links found in the visible scrollback.",
                        style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
                    )
                }
            } else {
                for (url in urls) TerminalLinkRow(url, onOpen = { open(url); onClose() }, onCopy = {
                    val copied = copy(url)
                    toast(
                        if (copied) ErrorToastMessage("Link copied", kind = ErrorToastMessage.Kind.SUCCESS, durationMillis = LINK_COPIED_TOAST_MS)
                        else ErrorToastMessage("Could not copy link", durationMillis = LINK_COPIED_TOAST_MS),
                    )
                    onClose()
                })
            }
        }
        if (loopback) TerminalFinishLogin(scope, toast, deliver, onDone = onClose)
    }
}

/**
 * What xterm's default `linkHandler` asks with `confirm` before it follows an OSC 8 link: the address in the question and the
 * warning under it. A program chooses the address behind such a link, and the text shown can be anything.
 */
@Composable
internal fun Osc8LinkConfirm(uri: String, open: (String) -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Do you want to navigate to $uri?",
        message = "WARNING: This link could potentially be dangerous",
        confirm = "OK",
        destructive = false,
        onConfirm = { open(uri) },
        onDismiss = onDismiss,
    )
}

/** One link of the sheet and, when it is a login that redirects to the machine's own port, the note under it. */
@Composable
private fun TerminalLinkRow(url: String, onOpen: () -> Unit, onCopy: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val port = TerminalText.loopbackRedirectPort(url)
    Column(Modifier.fillMaxWidth()) {
        // `flex items-stretch gap-1`.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val shape = RoundedCornerShape(12.dp)
            // The `<a>`: `flex min-h-11 flex-1 min-w-0 items-center gap-2 rounded-lg px-3 py-2 active:bg-accent`; `bg-accent` is `--panel-2`.
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .pointerHoverIcon(WebCursor.Pointer)
                    .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.ExternalLink, 16.dp, tokens.text3.color)
                BasicText(
                    url,
                    Modifier.weight(1f),
                    style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The Copy button: `flex size-11 shrink-0 items-center justify-center rounded-lg text-text-secondary active:bg-accent`.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(shape)
                    .semantics(mergeDescendants = true) { contentDescription = "Copy $url" }
                    .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClick = onCopy),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.Copy, 16.dp, tokens.text2.color)
            }
        }
        if (port != null) {
            // `px-3 pb-2 text-[11px] leading-snug text-text-secondary`, the machine in `<strong>`.
            BasicText(
                buildAnnotatedString {
                    append("Signing in here sends you back to port $port ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("on the machine") }
                    append(", not this device — so the terminal keeps waiting. Afterwards, paste the address bar below to finish it.")
                },
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                style = ppmText(11f, lineHeight = 15.125f).copy(color = tokens.text2.color),
            )
        }
    }
}

/**
 * The web's `TerminalFinishLogin` (`terminal-finish-login.tsx`): completes a CLI login that was started here and signed in on another
 * device. The CLI listens on a loopback port of the machine PPM runs on, but the browser that signed in was sent to *its own* loopback
 * address, so nothing arrived. The address pasted here is handed to [deliver] (the server makes the call on the browser's behalf) and the
 * sheet closes through [onDone]; [scope] outlives the sheet, as the web's request does, so the toast of one that was closed still shows.
 */
@Composable
internal fun TerminalFinishLogin(
    scope: CoroutineScope,
    toast: (ErrorToastMessage) -> Unit,
    deliver: suspend (String) -> Unit,
    onDone: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val address = rememberTextFieldState()
    var sending by remember { mutableStateOf(false) }
    val trimmed = address.text.toString().trim(::isJsSpace)
    val deliverable = TerminalText.isDeliverableCallback(trimmed)
    val ready = !sending && deliverable
    fun send() {
        sending = true
        scope.launch {
            try {
                deliver(trimmed)
                toast(ErrorToastMessage("Sent to the waiting command", kind = ErrorToastMessage.Kind.SUCCESS, durationMillis = LOGIN_SENT_TOAST_MS))
                address.edit { replace(0, length, "") }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(ErrorToastMessage(e.message ?: e.toString(), durationMillis = LOGIN_FAILED_TOAST_MS))
            } finally {
                sending = false
            }
        }
    }
    // `shrink-0 border-t border-border px-3 py-3`.
    Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top).padding(12.dp)) {
        BasicText(
            "Finished signing in and landed on a page that would not load? Paste that address here — the machine will deliver it to the command still waiting.",
            Modifier.padding(bottom = 8.dp),
            style = ppmText(11f, lineHeight = 15.125f).copy(color = tokens.text2.color),
        )
        // `flex items-center gap-2`.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CallbackAddressField(address, Modifier.weight(1f))
            val shape = RoundedCornerShape(12.dp)
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            // `flex size-11 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground disabled:opacity-40 active:opacity-80`.
            Box(
                Modifier
                    .size(44.dp)
                    .alpha(if (!ready) 0.4f else if (pressed) 0.8f else 1f)
                    .clip(shape)
                    .background(tokens.accent.color)
                    .semantics(mergeDescendants = true) { contentDescription = "Finish login" }
                    .ppmClickable(enabled = ready, pressedColor = Color.Transparent, shape = shape, source = source, onClick = ::send),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) PpmIcon(ProductIcon.Loader2, 16.dp, tokens.accentFg.color, spinning = true)
                else PpmIcon(ProductIcon.LogIn, 16.dp, tokens.accentFg.color)
            }
        }
        if (trimmed.isNotEmpty() && !deliverable) {
            // `pt-1.5 text-[11px] text-error`: no line height of its own, so the page's 1.5.
            BasicText(
                "Needs to be the http://127.0.0.1:… address the sign-in redirected to.",
                Modifier.padding(top = 6.dp),
                style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.error.color),
            )
        }
    }
}

/**
 * The address field: `h-11 min-w-0 flex-1 rounded-lg border border-border bg-surface px-3 font-mono text-xs`, which a phone's
 * stylesheet sets to 16 px (`input { font-size: 16px !important }` under 768 px) at the line height `text-xs` gives that size.
 * Its border stays `border-border` while it has the focus.
 */
@Composable
private fun CallbackAddressField(state: TextFieldState, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .cssHeight(44.dp)
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        PpmTextField(
            state,
            "http://127.0.0.1:54132/oauth/callback?code=…",
            Modifier.fillMaxWidth().semantics { contentDescription = "Callback address" },
            style = ppmText(16f, kind = FontKind.MONO, lineHeight = 21.333f),
            desktopStyle = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            placeholderOverflow = TextOverflow.Clip,
            cssStart = 12.dp,
        )
    }
}
