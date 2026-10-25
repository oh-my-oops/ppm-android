package com.hienlh.ppm.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.McpSignIn
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.McpAuthFlow
import com.hienlh.ppm.protocol.McpServerName
import com.hienlh.ppm.state.McpSignInFlow

/** `text-sm`: 14 on 20. */
private val bodyText = ppmText(14f, lineHeight = 20f)

/**
 * Signing in to one MCP server (`mcp-sign-in-dialog.tsx`): a sheet on a phone, a dialog on the
 * desktop layout. The flow lives in the model rather than in either, so crossing the breakpoint
 * (a large phone turned) does not start the sign-in over; closing cancels whatever is still open.
 */
@Composable
fun McpSignInPanel(model: AppModel, signIn: McpSignIn) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    // `dark:` is the device's dark mode on the web, not PPM's theme.
    val dark = isSystemInDarkTheme()
    val close = model::closeMcpSignIn
    val title = "Sign in to ${signIn.serverName}"
    val buttons = Buttons(dark, if (mobile) 44.dp else 36.dp)
    val body: @Composable () -> Unit = {
        val flow = signIn.flow
        if (flow == null) ClaudeAiConnectorBody(buttons, close) else SignInBody(signIn.serverName, flow, buttons, close)
    }
    if (mobile) {
        // `className="popover-solid"`: the popover's colour over the page's solid one.
        BottomSheet(onDismiss = close, background = tokens.panel2.over(tokens.bgSolid).color) {
            // `px-4 pb-4 pt-1`, the title `mb-3 text-base font-semibold`: stacked in fractions from
            // where the handle leaves it, as Chrome stacks them. Rounded a row at a time, the sheet
            // came out 4 px taller than the web's at 2.625x.
            CssColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), gap = 12.dp, top = LocalSheetContentTop.current + 4.dp, bottom = 16.dp) {
                BasicText(title, Modifier.cssLines(24f), style = ppmText(16f, FontWeight.SemiBold, lineHeight = 24f).copy(color = tokens.text.color))
                body()
            }
        }
    } else {
        // A sign-in opens on "Preparing", which nothing in takes the focus, so the cross has it; the
        // connector's body leads with a link, which Radix passes over for its Close button.
        WebDialog(
            title,
            onDismiss = close,
            widest = 448.dp,
            description = "Grant PPM's Claude sessions access to this MCP server.",
            closeFocused = signIn.flow != null,
            content = body,
        )
    }
}

/**
 * The body's buttons: `h-11` on a phone, `md:h-9` from there, in the theme's outline look. The
 * body's columns count them at that height exactly, 115.5 px at 2.625x.
 */
private class Buttons(val dark: Boolean, val height: Dp)

@Composable
private fun Buttons.Primary(label: String, modifier: Modifier, icon: ProductIcon? = null, onClick: () -> Unit) =
    DialogButton(label, primary = true, dark, enabled = true, modifier.cssHeight(height), icon = icon, height = height, gap = 8.dp, onClick = onClick)

/** An `outline` one, a spinner in front while [busy]; [label] gives way to it where the web's does. */
@Composable
private fun Buttons.Outline(label: String, modifier: Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) =
    DialogButton(label, primary = false, dark, enabled, modifier.cssHeight(height), icon = if (busy) ProductIcon.Loader2 else null, spinning = busy, height = height, gap = 8.dp, onClick = onClick)

@Composable
private fun SignInBody(serverName: String, flow: McpSignInFlow, buttons: Buttons, onClose: () -> Unit) {
    val state by flow.state.collectAsState()
    val current = state.flow
    when {
        state.starting || (current == null && state.error == null) -> StatusLine("Preparing the sign-in…", ProductIcon.Loader2, spinning = true)
        current == null || current.status in ENDED -> CssColumn(gap = 12.dp) {
            ErrorLine(current?.error ?: state.error ?: "The sign-in did not finish.")
            Row(Modifier.cssHeight(buttons.height), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.Primary("Try again", Modifier.weight(1f), onClick = flow::start)
                buttons.Outline("Close", Modifier, onClick = onClose)
            }
        }
        current.status == McpAuthFlow.Status.DONE -> CssColumn(gap = 12.dp) {
            StatusLine(
                "Signed in. $serverName is available to Claude — open chats reconnect it on their own.",
                ProductIcon.CheckCircle2,
                LocalPpmTokens.current.success.color,
            )
            buttons.Primary("Done", Modifier.fillMaxWidth(), onClick = onClose)
        }
        else -> WaitingBody(current, state.busy, state.error, flow, buttons)
    }
}

private val ENDED = setOf(McpAuthFlow.Status.FAILED, McpAuthFlow.Status.EXPIRED, McpAuthFlow.Status.CANCELLED)

/** The flow is waiting on the user: the page to sign in on, and how the answer comes back. */
@Composable
private fun WaitingBody(current: McpAuthFlow, busy: Boolean, error: String?, flow: McpSignInFlow, buttons: Buttons) {
    val tokens = LocalPpmTokens.current
    val uriHandler = LocalUriHandler.current
    val custom = current.redirectScheme == McpAuthFlow.RedirectScheme.CUSTOM
    CssColumn(gap = 16.dp) {
        current.authUrl?.let { url ->
            buttons.Primary("Open sign-in page", Modifier.fillMaxWidth(), icon = ProductIcon.ExternalLink) { uriHandler.openUri(url) }
        }
        if (current.callbackExpected) {
            StatusLine(
                if (custom) "Waiting for you to approve access. The page returns to PPM by itself." else "Waiting for you to approve access in the browser…",
                ProductIcon.Loader2,
                spinning = true,
            )
            PasteForm(custom, busy, flow, buttons)
        } else {
            CssColumn(gap = 8.dp) {
                BasicText("Grant access on the page that opens, then come back here.", Modifier.cssLines(20f), style = bodyText.copy(color = tokens.text2.color))
                buttons.Outline("I've granted access", Modifier.fillMaxWidth(), enabled = !busy, busy = busy, onClick = flow::confirm)
            }
        }
        (current.error ?: error)?.let { ErrorLine(it) }
    }
}

/**
 * The address the browser ended on, for when it cannot come back by itself (`space-y-2 rounded-md
 * border border-border p-3`): a line saying so, the field, and Submit, a spinner while it is out.
 */
@Composable
private fun PasteForm(custom: Boolean, busy: Boolean, flow: McpSignInFlow, buttons: Buttons) {
    val tokens = LocalPpmTokens.current
    val pasted = remember(flow) { TextFieldState() }
    val address = pasted.text.trim().toString()
    fun submit() {
        if (address.isNotEmpty()) flow.submitCallback(address)
    }
    val shape = RoundedCornerShape(8.dp)
    CssColumn(
        Modifier.fillMaxWidth().cssBorder(tokens.border.color, shape).padding(horizontal = 12.dp),
        gap = 8.dp,
        top = 12.dp,
        bottom = 12.dp,
    ) {
        BasicText(pasteHint(custom), Modifier.cssLines(16f), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
        Row(Modifier.cssHeight(buttons.height), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FormInput(
                pasted,
                if (custom) "https://…/callback?code=…" else "http://localhost:…/callback?code=…",
                Modifier.weight(1f),
                height = buttons.height,
                onSubmit = ::submit,
            )
            buttons.Outline(if (busy) "" else "Submit", Modifier, enabled = !busy && address.isNotEmpty(), busy = busy, onClick = ::submit)
        }
    }
}

private fun pasteHint(custom: Boolean): AnnotatedString = buildAnnotatedString {
    if (custom) {
        append("Did the page not come back to PPM? Copy the address the browser ended on after approving and paste it here.")
        return@buildAnnotatedString
    }
    append("Not on the machine running PPM? After approving, the browser lands on a ")
    // A bare `<code>`: the code font at the line's own size.
    withStyle(SpanStyle(fontFamily = PpmFonts.mono)) { append("localhost") }
    append(" page that does not load. Copy that page's address and paste it here.")
}

/** A connector added on claude.ai is connected there, not here. */
@Composable
private fun ClaudeAiConnectorBody(buttons: Buttons, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val uriHandler = LocalUriHandler.current
    CssColumn(gap = 12.dp) {
        BasicText(
            "This connector belongs to your claude.ai account. Connect it in claude.ai's connector settings, then start a new chat to use it here.",
            Modifier.cssLines(20f),
            style = bodyText.copy(color = tokens.text2.color),
        )
        Row(Modifier.cssHeight(buttons.height), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.Primary("Open claude.ai connectors", Modifier.weight(1f), icon = ProductIcon.ExternalLink) {
                uriHandler.openUri(McpServerName.CLAUDE_AI_CONNECTORS_URL)
            }
            buttons.Outline("Close", Modifier, onClick = onClose)
        }
    }
}

/**
 * `flex items-start gap-2 text-sm text-text-secondary`, the icon `mt-0.5` in the text's colour
 * unless given its own. A spinning icon is wrapped in an `inline-flex` span, which stands on the
 * baseline of a line of the row's text, and that line reaches under it as far as the text's own
 * does: a row of one line is 23.143 px tall at 2.625x, not 20.
 */
@Composable
private fun StatusLine(text: String, icon: ProductIcon, iconColor: Color? = null, spinning: Boolean = false) {
    val tokens = LocalPpmTokens.current
    val under = if (spinning) with(LocalDensity.current) { PpmFonts.chromeBelowBaseline(14.sp.toPx(), 20.sp.toPx(), PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT).toDp() } else 0.dp
    Row(Modifier.cssLines(20f, min = 18.dp + under), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PpmIcon(icon, 16.dp, iconColor ?: tokens.text2.color, Modifier.padding(top = 2.dp, bottom = under), spinning = spinning)
        BasicText(text, Modifier.weight(1f), style = bodyText.copy(color = tokens.text2.color))
    }
}

/** `flex items-start gap-2 text-sm text-error`, a triangle `mt-0.5 size-4` before it. */
@Composable
private fun ErrorLine(text: String) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.cssLines(20f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PpmIcon(ProductIcon.TriangleAlert, 16.dp, tokens.error.color, Modifier.padding(top = 2.dp))
        BasicText(text, Modifier.weight(1f), style = bodyText.copy(color = tokens.error.color))
    }
}
