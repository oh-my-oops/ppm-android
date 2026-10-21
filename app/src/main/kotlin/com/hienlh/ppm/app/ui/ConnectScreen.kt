package com.hienlh.ppm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.tracksTextFocus
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.themeBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Adds a server, or asks for the password again after it was changed on the server — drawn as
 * the web's sign-in screen (`login-screen.tsx`), with the server's address added because this
 * app is not served by the server it talks to.
 */
@Composable
fun ConnectScreen(model: AppModel, signIn: Boolean, onCancel: (() -> Unit)? = null) {
    val tokens = LocalPpmTokens.current
    val fieldStyle = if (LocalPpmMobile.current) ppmText(16f, lineHeight = 24f) else FIELD_DESKTOP
    val scope = rememberCoroutineScope()
    val address = remember { TextFieldState() }
    val password = remember { TextFieldState() }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<String?>(null) }
    val addressFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (signIn) passwordFocus else addressFocus).requestFocus() }

    fun connect() {
        if (busy) return
        if (password.text.isBlank()) {
            error = "Enter your password to continue"
            return
        }
        error = null
        busy = true
        scope.launch {
            try {
                if (signIn) {
                    model.signInAgain(password.text.toString())
                } else {
                    val url = ApiClient.normalizedServerUrl(address.text.toString()) ?: throw PpmError.InvalidUrl()
                    model.addServer(url, password.text.toString())
                }
                onCancel?.invoke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PpmError.Unreachable) {
                error = "Can't reach the server. ${failure.reason}\n\nCheck that this device is on the same network (or Tailscale), and that PPM is running."
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            } finally {
                busy = false
            }
        }
    }

    // Over the app (Add server, a password asked again), Back closes it rather than moving the tabs under it.
    if (onCancel != null) BackHandler(onBack = onCancel)

    Box(Modifier.fillMaxSize().themeBackground(tokens.background)) {
        // The keyboard pans the page rather than shrinking it, as Chrome's does: the card stays
        // centred on the screen, and a field it covers is brought up into view.
        KeyboardPannedPage {
            LoginBackdrop()
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(Modifier.widthIn(max = 392.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Logo()
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BasicText(
                                "Unlock your workspace",
                                style = ppmText(24f, FontWeight.SemiBold, lineHeight = 32f).copy(color = tokens.text.color, letterSpacing = (-0.6).sp, textAlign = TextAlign.Center),
                            )
                            BasicText(
                                if (signIn) "Enter your access password to unlock" else "Enter the server's address and its access password",
                                style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
                            )
                        }
                        if (signIn) ServerChip(model.serverName)
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!signIn) {
                            LoginField(focused = focused == "address") {
                                PpmTextField(
                                    address,
                                    "Server address, e.g. 192.168.1.10:8080",
                                    Modifier.fillMaxWidth(),
                                    desktopStyle = FIELD_DESKTOP,
                                    placeholderColor = tokens.text2.color,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                                    onKeyboardAction = { passwordFocus.requestFocus() },
                                    focusRequester = addressFocus,
                                    onFocusChanged = { if (it) focused = "address" },
                                )
                            }
                        }
                        LoginField(focused = focused == "password", trailing = {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).ppmClickable(pressedColor = tokens.panel2.color) { showPassword = !showPassword },
                                contentAlignment = Alignment.Center,
                            ) {
                                PpmIcon(if (showPassword) ProductIcon.EyeOff else ProductIcon.Eye, 18.dp, tokens.text3.color)
                            }
                        }) {
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (password.text.isEmpty()) {
                                    BasicText("Password", style = fieldStyle.copy(color = tokens.text2.color))
                                }
                                BasicSecureTextField(
                                    state = password,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(passwordFocus)
                                        .tracksTextFocus(password)
                                        .onFocusChanged { if (it.isFocused) focused = "password" },
                                    textStyle = fieldStyle.copy(color = tokens.text.color),
                                    cursorBrush = SolidColor(tokens.accent.color),
                                    textObfuscationMode = if (showPassword) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                                    // The secure field's own options, which any passed in replace: without them the
                                    // keyboard is told it is plain text, learns the password and suggests it.
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                                    onKeyboardAction = { connect() },
                                )
                            }
                        }
                        error?.let { message ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PpmIcon(ProductIcon.AlertCircle, 15.dp, tokens.error.color, Modifier.padding(top = 2.dp))
                                SelectionContainer {
                                    BasicText(message, style = ppmText(13f, lineHeight = 18f).copy(color = tokens.error.color))
                                }
                            }
                        }
                        PrimaryButton(
                            if (busy) "Checking…" else "Unlock",
                            enabled = !busy && (signIn || address.text.isNotBlank()),
                            // `h-[50px] w-full text-[15px]` on the `rounded-md` button, its line the body's 1.5.
                            height = 50.dp,
                            hover = true,
                            radius = 8.dp,
                            fontSize = 15f,
                            lineHeight = 22.5f,
                            onClick = ::connect,
                        )
                        if (onCancel != null) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                BasicText(
                                    "Cancel",
                                    Modifier.clip(RoundedCornerShape(6.dp)).ppmClickable(pressedColor = tokens.panel2.color, onClick = onCancel).padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The sign-in input's size outside the phone layout: `md:text-sm`, which its `text-[15px]` loses to
 * (16 px on a phone, `max-md:text-base`). Its placeholder is `text-muted-foreground`, text-2.
 */
private val FIELD_DESKTOP = ppmText(14f, lineHeight = 20f)

/** The theme's backdrop, an accent glow from above and a faint 26px dot grid. */
@Composable
private fun LoginBackdrop() {
    val tokens = LocalPpmTokens.current
    val dot = tokens.text.opacity(0.05).color
    val glow = tokens.accent.opacity(0.12).color
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                listOf(glow, Color.Transparent),
                center = Offset(size.width / 2, -0.1f * size.height),
                radius = 0.6f * maxOf(size.width, size.height),
            ),
        )
        val step = 26.dp.toPx()
        val radius = 1.dp.toPx()
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                drawCircle(dot, radius, Offset(x + radius, y + radius))
                x += step
            }
            y += step
        }
    }
}

/** `size-16 rounded-2xl` tile holding a `size-[52px]` square with the wordmark. */
@Composable
private fun Logo() {
    val tokens = LocalPpmTokens.current
    val outer = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(64.dp)
            .ppmShadow(tokens.shadowFloat, outer)
            .clip(outer)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, outer),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(tokens.panel2.color), contentAlignment = Alignment.Center) {
            BasicText("PPM", style = ppmText(15f, FontWeight.Bold, lineHeight = 20f).copy(color = tokens.accent.color, letterSpacing = 0.375.sp))
        }
    }
}

/** The device chip under the title: which server the password is for. */
@Composable
private fun ServerChip(name: String) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.clip(CircleShape).background(tokens.panel2.color).cssBorder(tokens.border.color, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.Monitor, 12.dp, tokens.text3.color)
        // The page's 1.5 line height, inherited.
        BasicText(name, style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16.5f).copy(color = tokens.text2.color), maxLines = 1)
    }
}

/**
 * `h-[50px] rounded-xl bg-surface-elevated`, the border lit while typing in it. Its text starts at
 * `px-3` inside the border; with a button at `right-2` from the outer edge it stops at `pr-12`, as
 * the password's does.
 */
@Composable
private fun LoginField(focused: Boolean, trailing: (@Composable () -> Unit)? = null, field: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(tokens.panel2.color)
                .cssBorder(if (focused) tokens.accent.color else tokens.border.color, shape)
                .padding(start = 12.dp, end = if (trailing != null) 48.dp else 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) { field() }
        if (trailing != null) Box(Modifier.padding(end = 8.dp)) { trailing() }
    }
}
