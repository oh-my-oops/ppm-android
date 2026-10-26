package com.hienlh.ppm.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.VoiceEngine
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.installSpeech
import com.hienlh.ppm.client.speechStatus
import com.hienlh.ppm.client.uninstallSpeech
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.SpeechInstallJob
import com.hienlh.ppm.protocol.SpeechStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `voice-settings-section.tsx`: which engine the chat mic listens with, and the install of the one
 * that runs on the host. Whisper is offered as an engine only once it is installed — one that
 * answers 409 would be a setting that silently does nothing — so until then this pane is its
 * install screen.
 *
 * Where the web's words would not be true here, they are the app's: the device's engine is
 * Android's speech service, not a browser's; there is no plain-HTTP warning, as an app may use
 * the mic over plain HTTP; and a host that needs Homebrew is given the command to run, with no
 * button typing it into a terminal, as this app has no terminal yet. A status read that fails
 * mid-install does not stop the polling, as the web's does.
 */
@Composable
internal fun VoiceSection(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    var status by remember(api) { mutableStateOf<SpeechStatus?>(null) }
    var selectedModel by remember(api) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // A status that cannot be read leaves the pane on "not installed", which is what the user can act on anyway.
    suspend fun load() {
        val next = try {
            api?.speechStatus()
        } catch (_: PpmError) {
            null
        } ?: return
        status = next
        selectedModel = selectedModel ?: next.model?.id ?: next.models.lastOrNull()?.id
    }

    LaunchedEffect(api) { load() }

    val current = status
    val job = current?.install
    val installing = job != null && !job.done
    // Polled only while an install runs: 600 MB takes a while, and the phase and the byte count are
    // the only sign that anything is happening.
    LaunchedEffect(installing, api) {
        if (!installing) return@LaunchedEffect
        while (true) {
            delay(700)
            load()
        }
    }

    // An engine that was removed must not stay selected.
    LaunchedEffect(current, model.voiceEngine) {
        if (current?.ready == false && model.voiceEngine == VoiceEngine.WHISPER) model.selectVoiceEngine(VoiceEngine.DEVICE)
    }

    fun install() {
        val id = selectedModel ?: return
        val client = api ?: return
        busy = true
        model.scope.launch {
            try {
                client.installSpeech(id)
                load()
            } catch (error: PpmError) {
                model.toast(ErrorToastMessage("Could not start the install", error.message))
            } finally {
                busy = false
            }
        }
    }

    fun uninstall() {
        val client = api ?: return
        busy = true
        model.scope.launch {
            try {
                client.uninstallSpeech()
                model.selectVoiceEngine(VoiceEngine.DEVICE)
                load()
            } catch (error: PpmError) {
                model.toast(ErrorToastMessage("Could not remove Whisper", error.message))
            } finally {
                busy = false
            }
        }
    }

    // `sm:` is the viewport's width, not the pane's.
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 640.dp
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("Speech to text", "What the mic button in the chat box uses. This device only.")
            ColumnOrRow(wide) { grow, _ ->
                EngineOption(
                    "Android",
                    "Text appears as you speak. Uses this device's speech service, Google's on most phones, which may send the audio to its servers.",
                    model.voiceEngine == VoiceEngine.DEVICE,
                    grow,
                ) { model.selectVoiceEngine(VoiceEngine.DEVICE) }
                if (current?.ready == true) {
                    EngineOption(
                        "Whisper on this host",
                        "Works on any device and the audio never leaves your machine. Text arrives when you stop talking.",
                        model.voiceEngine == VoiceEngine.WHISPER,
                        grow,
                    ) { model.selectVoiceEngine(VoiceEngine.WHISPER) }
                }
            }
        }

        Separator()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(
                "Whisper on this host",
                if (current?.ready == true) {
                    "Installed — whisper.cpp ${current.version}, model ${current.model?.label}."
                } else {
                    "Runs speech to text on the machine PPM is installed on. Nothing is sent to a cloud service."
                },
            )

            if (current != null && !current.installable) {
                val hint = current.installHint
                BasicText(
                    buildAnnotatedString {
                        if (hint != null) {
                            append("There is no prebuilt whisper.cpp for this platform, so it comes from Homebrew: ")
                            code(hint, tokens.panel.color)
                            append(". Run it in a terminal on the host and PPM will find it.")
                        } else {
                            append("There is no prebuilt whisper.cpp for this platform. Install it yourself and PPM will find it: ")
                            code("whisper-cli on PATH", tokens.panel.color)
                        }
                    },
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }

            if (job != null && !job.done) InstallProgress(job)

            job?.error?.let { BasicText(it, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color)) }

            if (current?.installable == true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in current.models) {
                        OptionCard(selectedModel == option.id, Modifier.fillMaxWidth(), onClick = { selectedModel = option.id }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BasicText(option.label, Modifier.weight(1f), style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (current.model?.id == option.id) PpmIcon(ProductIcon.Check, 14.dp, tokens.success.color)
                                    BasicText("${Math.round(option.bytes / 1e6)} MB", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                                }
                            }
                            BasicText(option.note, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                        }
                    }
                }

                ColumnOrRow(wide) { grow, stretch ->
                    val installed = current.model
                    PrimaryButton(
                        when {
                            installed == null -> "Install"
                            installed.id == selectedModel -> "Installed"
                            else -> "Switch to ${current.models.firstOrNull { it.id == selectedModel }?.label}"
                        },
                        ProductIcon.Download,
                        enabled = !busy && !installing && selectedModel != null && installed?.id != selectedModel,
                        modifier = grow,
                        onClick = ::install,
                    )
                    if (installed != null || current.binarySource == "bundled") {
                        RemoveButton(enabled = !busy && !installing, dark = isSystemInDarkTheme(), modifier = stretch, onClick = ::uninstall)
                    }
                }
            }
        }
    }
}

/** `PHASE_LABEL`. */
private val PHASE_LABEL = mapOf(
    "binary" to "Downloading whisper.cpp",
    "model" to "Downloading the model",
    "vad" to "Downloading the voice detector",
    "verify" to "Checking it runs here",
)

/** A `<code className="rounded bg-muted px-1 py-0.5">` in running text, drawn as its monospace on the muted fill. */
private fun AnnotatedString.Builder.code(text: String, fill: Color) {
    withStyle(SpanStyle(fontFamily = PpmFonts.mono, background = fill)) { append(text) }
}

/** An `h3 text-sm font-medium` over its `text-xs text-muted-foreground` line. */
@Composable
private fun SectionHeading(title: String, note: String) {
    val tokens = LocalPpmTokens.current
    Column {
        BasicText(title, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
        BasicText(note, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
    }
}

/**
 * `flex flex-col sm:flex-row gap-2`. [content] is given what a `flex-1` child takes ([grow]) and
 * what any other does ([stretch]), as the items stretch across the line either way.
 */
@Composable
private fun ColumnOrRow(wide: Boolean, content: @Composable (grow: Modifier, stretch: Modifier) -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content(Modifier.weight(1f).fillMaxHeight(), Modifier.fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
        }
    }
}

/** `EngineOption`: the mic and the engine's name over what it means. */
@Composable
private fun EngineOption(label: String, note: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    OptionCard(active, modifier, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.Mic, 16.dp, tokens.text.color)
            BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
        }
        Spacer(Modifier.height(4.dp))
        BasicText(note, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
    }
}

/**
 * The engine and model buttons: `rounded-lg border px-4 py-3 text-left transition-colors`, the one
 * picked `border-primary ring-2 ring-primary`, any other `border-border hover:bg-muted/50`. No
 * `cursor-pointer`, so a mouse keeps the arrow.
 */
@Composable
private fun OptionCard(active: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val fill = tokens.panel.opacity(0.5).color
    val hover = rememberHover(enabled = !active)
    val border by animateColorAsState((if (active) tokens.accent else tokens.border).color, tween(150, easing = FastOutSlowInEasing), label = "border")
    Column(
        modifier
            .then(if (active) Modifier.ring(2.dp, tokens.accent.color, 12.dp) else Modifier)
            .clip(shape)
            .background(fill.copy(alpha = fill.alpha * hover.lit))
            .ppmClickable(pressedColor = if (active) Color.Transparent else fill, shape = shape, source = hover.source, onClick = onClick)
            .cssBorder(border, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

/**
 * The install's progress: the spinner and the phase (`text-xs text-muted-foreground`), over an
 * `h-1.5 rounded-full bg-muted` track filled in `bg-primary` with `transition-[width]`.
 */
@Composable
private fun InstallProgress(job: SpeechInstallJob) {
    val tokens = LocalPpmTokens.current
    val percent = job.percent
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text2.color, spinning = true)
            BasicText(
                PHASE_LABEL[job.phase].orEmpty() + if (percent == null) "…" else " — $percent%",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            )
        }
        val width by animateFloatAsState((percent ?: 0) / 100f, tween(150, easing = FastOutSlowInEasing), label = "progress")
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(tokens.panel.color)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(width).background(tokens.accent.color))
        }
    }
}

/**
 * The `default` button, `flex-1 gap-2 cursor-pointer`: `h-9 px-3 rounded-md`, `bg-primary` fading
 * to `hover:bg-primary/90`, and `disabled:opacity-50` (`transition-all`) with no pointer events.
 */
@Composable
private fun PrimaryButton(label: String, icon: ProductIcon, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled)
    val opacity by animateFloatAsState(if (enabled) 1f else 0.5f, tween(150, easing = FastOutSlowInEasing), label = "disabled")
    Row(
        modifier
            .height(36.dp)
            .alpha(opacity)
            .clip(shape)
            .background(tokens.accent.opacity(1 - 0.1 * hover.lit).color)
            .then(if (enabled) Modifier.pointerHoverIcon(WebCursor.Pointer) else Modifier)
            .ppmClickable(enabled, pressedColor = Color.Black.copy(alpha = 0.1f), shape = shape, source = hover.source, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PpmIcon(icon, 16.dp, tokens.accentFg.color)
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.accentFg.color), maxLines = 1)
    }
}

/**
 * Remove: the `outline` button with `text-error`, whose `hover:text-accent-foreground` still wins
 * under a mouse. In a light theme the bare `border` is the text's colour, so it turns with it.
 */
@Composable
private fun RemoveButton(enabled: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled)
    val opacity by animateFloatAsState(if (enabled) 1f else 0.5f, tween(150, easing = FastOutSlowInEasing), label = "disabled")
    val content = tokens.text.mix(tokens.error, hover.lit.toDouble()).color
    Row(
        modifier
            .height(36.dp)
            .alpha(opacity)
            .ppmShadow(SHADOW_XS, shape)
            .clip(shape)
            .background(outlineFill(tokens, dark, hover.lit.toDouble()))
            .then(if (enabled) Modifier.pointerHoverIcon(WebCursor.Pointer) else Modifier)
            .ppmClickable(enabled, pressedColor = tokens.panel2.color, shape = shape, source = hover.source, onClick = onClick)
            .cssBorder(if (dark) tokens.border.color else content, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PpmIcon(ProductIcon.Trash2, 16.dp, content)
        BasicText("Remove", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = content), maxLines = 1)
    }
}
