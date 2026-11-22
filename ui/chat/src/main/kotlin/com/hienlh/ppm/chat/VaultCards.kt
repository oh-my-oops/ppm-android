package com.hienlh.ppm.chat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.VaultFailure
import com.hienlh.ppm.client.vaultCountdown
import com.hienlh.ppm.design.ChromeCheckbox
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.VAULT_HISTORY_LIMIT
import com.hienlh.ppm.protocol.VAULT_MAX_NAME_LENGTH
import com.hienlh.ppm.protocol.VAULT_MAX_VALUE_LENGTH
import com.hienlh.ppm.protocol.VAULT_SHOW_MS
import com.hienlh.ppm.protocol.VAULT_WINDOW_MINUTES
import com.hienlh.ppm.protocol.VaultCardAnswers
import com.hienlh.ppm.protocol.VaultCardInput
import com.hienlh.ppm.protocol.VaultCardRequester
import com.hienlh.ppm.protocol.VaultCardResult
import com.hienlh.ppm.protocol.VaultKinds
import com.hienlh.ppm.protocol.VaultTiers
import com.hienlh.ppm.protocol.vaultLooksSecretName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/*
 * The vault's cards in a chat (`approval.tool === "ppm:vault"`, vault-unlock-card.tsx,
 * vault-sealed-card.tsx, vault-propose-card.tsx): unlock secure items for the AI, unlock one
 * sealed item (shown to the user unless they give it to the AI), or save what the AI proposes.
 * Every answer goes over HTTP ([PpmApprovals.answerVault]), never through the chat socket, so a
 * wrong password is answered on the card while it stays. The card leaves when `approval_resolved`
 * arrives. Passwords live in the card's state only, and nothing here logs.
 */

private val xs = ppmText(12f, lineHeight = 16f)
private val xsMedium = ppmText(12f, FontWeight.Medium, lineHeight = 16f)
private val small = ppmText(11f, lineHeight = 44f / 3)
private val WHITE = Rgba(1.0, 1.0, 1.0, 1.0)

/** The time, read again every second while [ticking]. */
@Composable
fun rememberNow(ticking: Boolean): Long = produceState(System.currentTimeMillis(), ticking) {
    value = System.currentTimeMillis()
    while (ticking) {
        delay(1000)
        value = System.currentTimeMillis()
    }
}.value

/** A card's answer in flight and its refusal (`useVaultCardAnswer`). */
private class VaultAnswer(lockedUntil: Long) {
    var busy by mutableStateOf(false)
    var failure by mutableStateOf(if (lockedUntil > System.currentTimeMillis()) VaultFailure.lockedOutAt(lockedUntil) else null)
}

@Composable
private fun rememberVaultAnswer(requestId: String, lockedUntil: Long): Pair<VaultAnswer, (JsonObject, (VaultCardResult) -> Unit) -> Unit> {
    val approvals = LocalPpmApprovals.current
    val scope = rememberCoroutineScope()
    val state = remember(requestId) { VaultAnswer(lockedUntil) }
    val send: (JsonObject, (VaultCardResult) -> Unit) -> Unit = { answer, then ->
        val target = approvals
        if (target != null) {
            state.busy = true
            state.failure = null
            scope.launch {
                try {
                    then(target.answerVault(requestId, answer))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.failure = VaultFailure.of(e)
                } finally {
                    state.busy = false
                }
            }
        }
    }
    return state to send
}

@Composable
fun VaultCard(requestId: String, input: VaultCardInput, modifier: Modifier = Modifier) {
    when (input) {
        is VaultCardInput.ReadSecure -> VaultReadSecureCard(requestId, input, modifier)
        is VaultCardInput.ReadSealed -> VaultSealedCard(requestId, input, modifier)
        is VaultCardInput.Propose -> VaultProposeCard(requestId, input, modifier)
    }
}

/** The card's frame: a 2 dp border in [tone], a 10% wash of it, a lock and a heading. */
@Composable
private fun VaultFrame(tag: String, tone: Rgba, border: Rgba, heading: String, modifier: Modifier, strong: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .fillMaxWidth()
            .testTag("vault-card-$tag")
            .background(tone.opacity(0.1).color, shape)
            .cssBorder(border.color, shape, width = 2.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.Lock, if (strong) 20.dp else 16.dp, tone.color)
            BasicText(heading, style = ppmText(14f, if (strong) FontWeight.SemiBold else FontWeight.Medium, lineHeight = 20f).copy(color = tone.color))
        }
        content()
    }
}

@Composable
private fun RequesterLine(requester: VaultCardRequester, purpose: String? = null) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            buildAnnotatedString {
                append("Asked by ")
                withStyle(SpanStyle(color = tokens.text.color, fontWeight = FontWeight.Medium)) { append(requester.label) }
                if (requester.fromTelegram) withStyle(SpanStyle(color = tokens.text3.color)) { append(" · from Telegram") }
            },
            style = xs.copy(color = tokens.text2.color),
        )
        if (!purpose.isNullOrEmpty()) SelectionContainer { BasicText(purpose, style = xs.copy(color = tokens.text.color)) }
    }
}

@Composable
private fun FailureLine(failure: VaultFailure?) {
    val tokens = LocalPpmTokens.current
    val now = rememberNow((failure?.lockedUntil ?: 0) > System.currentTimeMillis())
    if (failure == null) return
    BasicText(failure.text(now), Modifier.testTag("vault-card-error"), xs.copy(color = tokens.error.color))
}

/** A password field labelled [label] (its placeholder and its accessible name), red-edged after a refusal. */
@Composable
fun VaultPasswordField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, invalid: Boolean, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    VaultTextField(value, onChange, label, enabled, modifier, password = true, invalid = invalid, onDone = onDone)
}

@Composable
fun VaultTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    masked: Boolean = password,
    invalid: Boolean = false,
    mono: Boolean = false,
    maxLength: Int = Int.MAX_VALUE,
    onDone: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val text = (if (mobile) ppmText(16f, lineHeight = 24f) else ppmText(14f, lineHeight = 20f)).let { if (mono) it.copy(fontFamily = ppmText(14f, kind = FontKind.MONO).fontFamily) else it }
    val edge = when {
        invalid -> tokens.error
        focused -> tokens.accent
        else -> tokens.border
    }
    BasicTextField(
        value,
        { onChange(it.take(maxLength)) },
        modifier
            .heightIn(min = if (mobile) 44.dp else 32.dp)
            .cardTextField(focused)
            .semantics { contentDescription = label }
            .background(Color.Transparent, shape)
            .cssBorder(edge.color, shape),
        enabled = enabled,
        textStyle = text.copy(color = tokens.text.color),
        cursorBrush = SolidColor(tokens.accent.color),
        singleLine = true,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        interactionSource = source,
        decorationBox = { field ->
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) BasicText(label, style = text.copy(color = tokens.text3.color))
                field()
            }
        },
    )
}

/** A value field, masked until the user asks to see it (`VaultValueInput`). */
@Composable
fun VaultValueField(value: String, onChange: (String) -> Unit, placeholder: String, enabled: Boolean, masked: Boolean = true) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    // Public values are shown as typed; locked ones start hidden.
    var visible by remember { mutableStateOf(!masked) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        VaultTextField(value, onChange, placeholder, enabled, Modifier.weight(1f), masked = !visible, mono = true, maxLength = VAULT_MAX_VALUE_LENGTH)
        val label = if (visible) "Hide value" else "Show value"
        Box(
            Modifier
                .size(if (mobile) 44.dp else 32.dp)
                .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                .ppmClickable(shape = RoundedCornerShape(8.dp), onClickLabel = label) { visible = !visible }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { PpmIcon(if (visible) ProductIcon.EyeOff else ProductIcon.Eye, 16.dp, tokens.text2.color) }
    }
}

/** A row with a checkbox whose whole label is tappable, at least 44 dp tall on a phone (`VaultCheckRow`). */
@Composable
fun VaultCheckRow(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean, tag: String? = null, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (LocalPpmMobile.current) 44.dp else 32.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChromeCheckbox(checked, Modifier.size(16.dp), enabled = enabled, accent = LocalPpmTokens.current.accent.color)
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A round choice: the accent when picked (`ModeButton`, `VaultTierPicker`). */
@Composable
fun VaultChoiceChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .heightIn(min = if (LocalPpmMobile.current) 44.dp else 32.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background((if (selected) tokens.accent.opacity(0.15) else Rgba(0.0, 0.0, 0.0, 0.0)).color)
            .cssBorder((if (selected) tokens.accent else tokens.border).color, shape)
            .ppmClickable(enabled = enabled, shape = shape, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = xs.copy(color = (if (selected) tokens.text else tokens.text2).color)) }
}

/** One answer: filled in [fill] or outlined. `h-11 md:h-8 px-4 rounded text-xs font-medium`. */
@Composable
private fun VaultButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, fill: Rgba? = null, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .heightIn(min = if (LocalPpmMobile.current) 44.dp else 32.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background((fill ?: Rgba(0.0, 0.0, 0.0, 0.0)).color)
            .then(if (fill == null) Modifier.cssBorder(tokens.border.color, shape) else Modifier)
            .ppmClickable(enabled = enabled, shape = shape, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = xsMedium.copy(color = (if (fill == null) tokens.text else WHITE).color), maxLines = 1) }
}

/**
 * The password and the answers: stacked bottom-up on a phone with the main answer lowest (nearest
 * the thumb), a row on a tablet (`VaultCardActions`).
 */
@Composable
private fun AnswerRow(password: (@Composable (Modifier) -> Unit)?, primary: @Composable (Modifier) -> Unit, decline: @Composable (Modifier) -> Unit) {
    if (LocalPpmMobile.current) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            password?.invoke(Modifier.fillMaxWidth())
            decline(Modifier.fillMaxWidth())
            primary(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            password?.invoke(Modifier.widthIn(max = 256.dp).weight(1f, fill = false))
            primary(Modifier)
            decline(Modifier)
        }
    }
}

@Composable
private fun VaultReadSecureCard(requestId: String, input: VaultCardInput.ReadSecure, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val canAnswer = LocalPpmApprovals.current != null
    val (answer, send) = rememberVaultAnswer(requestId, input.lockedUntil)
    var ticked by remember(requestId) { mutableStateOf(input.requested.filter { id -> input.items.any { it.id == id } }.toSet()) }
    var windowMinutes by remember(requestId) { mutableStateOf<Int?>(null) }
    var password by remember(requestId) { mutableStateOf("") }
    val now = rememberNow(answer.failure?.lockedOut(System.currentTimeMillis()) == true)
    val canApprove = !answer.busy && answer.failure?.lockedOut(now) != true && ticked.isNotEmpty() && password.isNotEmpty()
    // In the order the card lists them, so the request reads the same as the screen.
    val approve = { if (canApprove) send(VaultCardAnswers.readSecure(password, input.items.filter { it.id in ticked }.map { it.id }, windowMinutes)) {} }
    VaultFrame("read-secure", tokens.warning, tokens.warning.opacity(0.4), "Unlock vault items for the AI", modifier) {
        RequesterLine(input.requester, input.purpose)
        Column {
            BasicText("Secure items — tick what the AI may read", Modifier.padding(bottom = 4.dp), small.copy(color = tokens.text3.color))
            for (item in input.items) {
                VaultCheckRow(item.id in ticked, { on -> ticked = if (on) ticked + item.id else ticked - item.id }, enabled = !answer.busy, tag = "vault-item-${item.id}") {
                    BasicText(
                        buildAnnotatedString {
                            append(item.name)
                            withStyle(SpanStyle(color = tokens.text3.color)) { append(" · ${VaultKinds.label(item.kind)}") }
                        },
                        style = xs.copy(color = tokens.text.color),
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VaultChoiceChip("Read once", windowMinutes == null) { windowMinutes = null }
            for (m in VAULT_WINDOW_MINUTES) VaultChoiceChip("Open $m min for this chat", windowMinutes == m) { windowMinutes = m }
        }
        windowMinutes?.let { BasicText("For $it minutes this chat's AI reads the ticked items again without asking.", style = small.copy(color = tokens.text3.color)) }
        BasicText(VAULT_HISTORY_LIMIT, style = small.copy(color = tokens.text3.color))
        FailureLine(answer.failure)
        if (canAnswer) {
            AnswerRow(
                password = { m -> VaultPasswordField("Secure tier password", password, { password = it }, !answer.busy, answer.failure != null, m, approve) },
                primary = { m -> VaultButton("Approve", canApprove, m, fill = tokens.accent, onClick = approve) },
                decline = { m -> VaultButton("Decline", !answer.busy, m) { send(VaultCardAnswers.decline()) {} } },
            )
        }
    }
}

/**
 * The card for one sealed item: drawn apart from every other card (red lock, red border), and by
 * default it shows the value to the user only. Giving it to the AI is a separate, explicit tick
 * below the main button — and is not offered at all for a turn started from Telegram.
 */
@Composable
private fun VaultSealedCard(requestId: String, input: VaultCardInput.ReadSealed, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val canAnswer = LocalPpmApprovals.current != null
    val (answer, send) = rememberVaultAnswer(requestId, input.lockedUntil)
    var password by remember(requestId) { mutableStateOf("") }
    var giveToAi by remember(requestId) { mutableStateOf(false) }
    val now = rememberNow(answer.failure?.lockedOut(System.currentTimeMillis()) == true)
    val canApprove = !answer.busy && answer.failure?.lockedOut(now) != true && password.isNotEmpty()
    val toAi = input.allowGiveToAi && giveToAi
    val approve = {
        if (canApprove) {
            send(VaultCardAnswers.readSealed(password, giveToAi, input.allowGiveToAi)) { result ->
                val value = result.shownValue
                if (value != null) VaultShownValues.show(requestId, result.shownName ?: input.item.name, value)
            }
        }
    }
    VaultFrame("read-sealed", tokens.error, tokens.error, "Sealed vault item", modifier, strong = true) {
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(input.item.name) }
                withStyle(SpanStyle(color = tokens.text3.color)) { append(" · ${VaultKinds.label(input.item.kind)}") }
            },
            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
        )
        RequesterLine(input.requester, input.purpose)
        FailureLine(answer.failure)
        if (canAnswer) {
            AnswerRow(
                password = { m -> VaultPasswordField("Sealed tier password", password, { password = it }, !answer.busy, answer.failure != null, m, approve) },
                primary = { m -> VaultButton(if (toAi) "Give to the AI once" else "Show to me", canApprove, m, fill = tokens.error, onClick = approve) },
                decline = { m -> VaultButton("Decline", !answer.busy, m) { send(VaultCardAnswers.decline()) {} } },
            )
        }
        if (input.allowGiveToAi) {
            Column(Modifier.fillMaxWidth().padding(top = 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(tokens.error.opacity(0.3).color))
                VaultCheckRow(giveToAi, { giveToAi = it }, enabled = !answer.busy, tag = "vault-give-to-ai") {
                    BasicText("Let the AI read this item once (the AI will see the value)", style = xs.copy(color = tokens.text.color))
                }
                BasicText(VAULT_HISTORY_LIMIT, style = small.copy(color = tokens.text3.color))
            }
        } else {
            BasicText("This turn came from Telegram, so the value can only be shown to you.", style = small.copy(color = tokens.text3.color))
        }
    }
}

/** The warnings the public tier carries (`PUBLIC_TIER_NOTE`). */
const val VAULT_PUBLIC_TIER_NOTE =
    "Items in this tier are read freely by the AI, may be sent through Telegram and are stored as plain text on this machine. Do not keep secrets here."

const val VAULT_SECRET_NAME_WARNING =
    "This name looks like a secret. Keep it in the Secure tier instead, where the AI needs your password to read it."

/** A note in a tinted box (`PublicTierWarning`, `SecretNameWarning`). */
@Composable
fun VaultNote(text: String, tone: Rgba, tag: String) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .background(tone.opacity(0.1).color, shape)
            .cssBorder(tone.opacity(0.4).color, shape)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.AlertTriangle, 14.dp, tone.color, Modifier.padding(top = 1.dp))
        BasicText(text, style = xs.copy(color = tokens.text.color))
    }
}

/**
 * The card for `vault_propose`: the AI suggests saving an item, the user settles the name, kind,
 * tier and value and confirms with that tier's password (none for the public tier). A value the
 * AI filled in is fetched masked, never sent over the chat socket; left as it is, it is not sent
 * back either: the server saves its own copy.
 */
@Composable
private fun VaultProposeCard(requestId: String, input: VaultCardInput.Propose, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val approvals = LocalPpmApprovals.current
    val (answer, send) = rememberVaultAnswer(requestId, 0)
    var tier by remember(requestId) { mutableStateOf(if (input.tierOn(input.suggestedTier)) input.suggestedTier else VaultTiers.PUBLIC) }
    var name by remember(requestId) { mutableStateOf(input.name) }
    var kind by remember(requestId) { mutableStateOf(input.itemKind) }
    var prefill by remember(requestId) { mutableStateOf<String?>(null) }
    var prefillError by remember(requestId) { mutableStateOf<String?>(null) }
    // What the user typed; null while the AI's value stands untouched.
    var typed by remember(requestId) { mutableStateOf<String?>(null) }
    var password by remember(requestId) { mutableStateOf("") }
    LaunchedEffect(requestId, input.hasPrefill, approvals) {
        if (!input.hasPrefill || approvals == null) return@LaunchedEffect
        try {
            prefill = approvals.vaultPrefill(requestId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefillError = VaultFailure.of(e).message
        }
    }
    val shown = typed ?: prefill ?: ""
    val canSave = !answer.busy && name.trim().isNotEmpty() && shown.isNotEmpty() && (tier == VaultTiers.PUBLIC || password.isNotEmpty())
    val save = { if (canSave) send(VaultCardAnswers.propose(tier, name.trim(), kind, typed, password)) {} }
    VaultFrame("propose", tokens.accent, tokens.accent.opacity(0.4), "Save to the vault", modifier) {
        RequesterLine(input.requester)
        VaultTextField(name, { name = it }, "Name", !answer.busy, Modifier.fillMaxWidth(), maxLength = VAULT_MAX_NAME_LENGTH)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in VaultKinds.ALL) VaultChoiceChip(VaultKinds.label(k), kind == k, enabled = !answer.busy) { kind = k }
        }
        if (tier == VaultTiers.PUBLIC && vaultLooksSecretName(name)) VaultNote(VAULT_SECRET_NAME_WARNING, tokens.error, "vault-secret-name-warning")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in VaultTiers.ALL) {
                val on = input.tierOn(t)
                VaultChoiceChip(VaultTiers.label(t) + if (on) "" else " (off)", tier == t, enabled = on && !answer.busy) { tier = t }
            }
        }
        if (tier == VaultTiers.PUBLIC) VaultNote(VAULT_PUBLIC_TIER_NOTE, tokens.warning, "vault-public-warning")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VaultValueField(shown, { typed = it }, if (input.hasPrefill && prefill == null) "Loading…" else "Value", !answer.busy)
            if (input.hasPrefill && prefill != null && typed == null) {
                BasicText("This value is already in the chat history.", style = small.copy(color = tokens.warning.color))
            }
            prefillError?.let { BasicText(it, style = small.copy(color = tokens.error.color)) }
        }
        FailureLine(answer.failure)
        if (approvals != null) {
            AnswerRow(
                password = if (tier == VaultTiers.PUBLIC) null else { m -> VaultPasswordField("${VaultTiers.label(tier)} tier password", password, { password = it }, !answer.busy, answer.failure != null, m, save) },
                primary = { m -> VaultButton("Save", canSave, m, fill = tokens.accent, onClick = save) },
                decline = { m -> VaultButton("Decline", !answer.busy, m) { send(VaultCardAnswers.decline()) {} } },
            )
        }
    }
}

/**
 * Sealed values the user unlocked to see for themselves (`showVaultValue`). The value travels only
 * in the card's HTTP answer, and the card leaves the moment `approval_resolved` arrives, so it is
 * shown by [VaultShownValueHost] instead, with Copy, and hides itself after [VAULT_SHOW_MS]. This
 * list, in memory, is the only copy the app keeps.
 */
object VaultShownValues {
    data class Shown(val id: String, val name: String, val value: String, val until: Long)

    val entries = mutableStateListOf<Shown>()

    fun show(requestId: String, name: String, value: String, now: Long = System.currentTimeMillis()) {
        entries.removeAll { it.id == requestId }
        entries += Shown(requestId, name, value, now + VAULT_SHOW_MS)
    }

    fun hide(id: String) {
        entries.removeAll { it.id == id }
    }
}

/** Over the whole app, at the bottom: every sealed value on show (`VaultShownValue`). */
@Composable
fun VaultShownValueHost(modifier: Modifier = Modifier) {
    val shown = VaultShownValues.entries.toList()
    if (shown.isEmpty()) return
    Box(modifier.padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in shown) androidx.compose.runtime.key(entry.id) { ShownValue(entry) }
        }
    }
}

@Composable
private fun ShownValue(entry: VaultShownValues.Shown) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val now = rememberNow(true)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(entry.until) {
        delay((entry.until - System.currentTimeMillis()).coerceAtLeast(0))
        VaultShownValues.hide(entry.id)
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .widthIn(max = 352.dp)
            .fillMaxWidth()
            .testTag("vault-shown")
            .background(tokens.bgSolid.color, shape)
            .cssBorder(tokens.error.opacity(0.5).color, shape, width = 2.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.Lock, 16.dp, tokens.error.color)
            BasicText(entry.name, Modifier.weight(1f), xsMedium.copy(color = tokens.error.color), maxLines = 1)
            BasicText(vaultCountdown(entry.until - now), style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text3.color))
        }
        val box = RoundedCornerShape(4.dp)
        SelectionContainer {
            BasicText(
                entry.value,
                Modifier.fillMaxWidth().background(tokens.bgSolid.color, box).cssBorder(tokens.border.color, box).padding(8.dp),
                ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
            )
        }
        BasicText("Shown to you only. The AI did not get it.", style = small.copy(color = tokens.text3.color))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VaultButton(if (copied) "Copied" else "Copy", true, Modifier.weight(1f), fill = tokens.accent) {
                copySensitive(context, entry.name, entry.value)
                copied = true
            }
            VaultButton("Hide", true, Modifier.weight(1f)) { VaultShownValues.hide(entry.id) }
        }
    }
}

/** Copies with the clipboard's "sensitive" flag, so Android masks its preview. */
fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        else putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(clip)
}
