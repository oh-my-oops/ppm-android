package com.hienlh.ppm.app.ui.settings.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.assistant.AssistantSheet
import com.hienlh.ppm.app.ui.assistant.CardButton
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.chat.VAULT_PUBLIC_TIER_NOTE
import com.hienlh.ppm.chat.VaultCheckRow
import com.hienlh.ppm.chat.copySensitive
import com.hienlh.ppm.chat.rememberNow
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.VaultFailure
import com.hienlh.ppm.client.vaultCountdown
import com.hienlh.ppm.client.vaultDeleteItem
import com.hienlh.ppm.client.vaultDeleteTier
import com.hienlh.ppm.client.vaultItems
import com.hienlh.ppm.client.vaultLock
import com.hienlh.ppm.client.vaultReveal
import com.hienlh.ppm.client.vaultStatus
import com.hienlh.ppm.client.vaultUnlockSecure
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.VAULT_HISTORY_LIMIT
import com.hienlh.ppm.protocol.VAULT_REVEAL_MS
import com.hienlh.ppm.protocol.VaultItemMeta
import com.hienlh.ppm.protocol.VaultKinds
import com.hienlh.ppm.protocol.VaultStatus
import com.hienlh.ppm.protocol.VaultTierStatus
import com.hienlh.ppm.protocol.VaultTiers
import com.hienlh.ppm.state.vault.vaultAsksFirst
import com.hienlh.ppm.state.vault.vaultDeleteTierAcknowledge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * Settings → Vault (`vault-settings-section.tsx`): the three tiers of PPM's secret vault. This
 * section owns the dialogs and decides, per action, whether a password has to be asked for: never
 * for Public, for Secure only while Settings' five-minute unlock is not open (or ended meanwhile, a
 * 423), and always for Sealed. A locked value is never loaded here except on Show, held in this
 * screen's state for [VAULT_REVEAL_MS] and dropped; nothing logs it.
 */

/** What the section shows: the tiers' status, every item's name, and the public tier's values. */
data class VaultSettingsData(val status: VaultStatus, val items: List<VaultItemMeta>, val publicValues: Map<String, String>)

/** `useVaultSettings`'s load: public values are plain text by design, so the pane lists them. */
suspend fun loadVaultSettings(api: ApiClient): VaultSettingsData {
    val status = api.vaultStatus()
    val items = api.vaultItems()
    val values = items.filter { it.tier == VaultTiers.PUBLIC }.associate { it.id to api.vaultReveal(it.id, null) }
    return VaultSettingsData(status, items, values)
}

/** A revealed locked value, on show until [until]. */
data class VaultRevealed(val id: String, val value: String, val until: Long)

/** The one confirm-and-password dialog behind the row and tier actions (`VaultPrompt`). */
data class VaultPrompt(
    val title: String,
    val description: String? = null,
    val confirmLabel: String,
    val danger: Boolean = false,
    /** The tier whose password this action may need; null for the public tier. */
    val tier: String? = null,
    val askPassword: Boolean = false,
    /** A statement the user must tick before the action runs. */
    val acknowledge: String? = null,
    /** Throws to keep the dialog open; the error is shown in it. */
    val action: suspend (password: String?) -> Unit,
)

internal val vaultXs = ppmText(12f, lineHeight = 16f)
internal val vaultSmall = ppmText(11f, lineHeight = 16f)

private val UPDATED = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

internal fun vaultUpdated(ms: Long): String = UPDATED.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private val INTRO = mapOf(
    VaultTiers.SECURE to "Encrypted under its own password. The AI reads an item only after you type that password on a card in PPM, once or for a few minutes.",
    VaultTiers.SEALED to "Encrypted under a second, separate password and never kept open. One item per card, shown to you unless you give it to the AI that once.",
)

@Composable
fun VaultSettingsSection(model: AppModel) {
    val api = model.api ?: return
    val scope = rememberCoroutineScope()
    var data by remember(api) { mutableStateOf<VaultSettingsData?>(null) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<VaultPrompt?>(null) }
    var itemEdit by remember { mutableStateOf<VaultItemEdit?>(null) }
    var move by remember { mutableStateOf<VaultMove?>(null) }
    var enableTier by remember { mutableStateOf<String?>(null) }
    var passwordTier by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf<VaultRevealed?>(null) }
    var loads by remember { mutableStateOf(0) }
    LaunchedEffect(api, loads) {
        try {
            data = loadVaultSettings(api)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = VaultFailure.of(e).message
        }
    }
    val done: () -> Unit = { loads++ }
    val now = rememberNow(data != null)
    val current = data
    if (current == null) {
        BasicText(error ?: "Loading…", style = vaultXs.copy(color = LocalPpmTokens.current.text3.color))
        return
    }
    val status = current.status
    val secureOpen = status.secure.settingsOpenUntil > now

    /** Runs [p] on [tier]: at once when Secure is unlocked and nothing needs confirming, otherwise through the dialog. */
    fun act(tier: String?, p: VaultPrompt, confirm: Boolean) {
        val full = p.copy(tier = tier, action = { pw -> try { p.action(pw) } finally { done() } })
        if (tier != null && !confirm && !vaultAsksFirst(tier, secureOpen)) {
            scope.launch {
                try {
                    full.action(null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val f = VaultFailure.of(e)
                    if (f.needsPassword) prompt = full.copy(askPassword = true) else model.toast(ErrorToastMessage(f.message))
                }
            }
            return
        }
        prompt = full.copy(askPassword = tier != null && vaultAsksFirst(tier, secureOpen))
    }

    fun deleteItem(item: VaultItemMeta) = act(
        if (item.tier == VaultTiers.PUBLIC) null else item.tier,
        VaultPrompt(
            title = "Delete “${item.name}”?",
            description = "Deleted items stay in older backups until they expire.",
            confirmLabel = "Delete",
            danger = true,
            action = { pw -> api.vaultDeleteItem(item.id, pw) },
        ),
        confirm = true,
    )

    fun moveItem(item: VaultItemMeta) {
        move = VaultMove(item, status.secure.enabled, status.sealed.enabled, secureOpen)
    }

    Column(Modifier.fillMaxWidth().testTag("vault-settings"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        BasicText(
            "Personal details the AI may need, kept in three tiers. The Secure and Sealed tiers are encrypted under passwords of their own, " +
                "separate from PPM's password; neither password is stored, and a restart locks both. Deleted items stay in older backups until they expire. " +
                VAULT_HISTORY_LIMIT,
            style = vaultXs.copy(color = LocalPpmTokens.current.text3.color),
        )
        VaultTierSection(VaultTiers.PUBLIC, VAULT_PUBLIC_TIER_NOTE) {
            ItemList(current.items.filter { it.tier == VaultTiers.PUBLIC }) { item ->
                VaultItemRow(
                    item,
                    publicValue = current.publicValues[item.id].orEmpty(),
                    onEdit = { itemEdit = VaultItemEdit(VaultTiers.PUBLIC, item, current.publicValues[item.id], askPassword = false) },
                    onMove = { moveItem(item) },
                    onDelete = { deleteItem(item) },
                )
            }
            CardButton("Add", { itemEdit = VaultItemEdit(VaultTiers.PUBLIC, askPassword = false) }, icon = ProductIcon.Plus, variant = CardButtonVariant.PRIMARY)
        }
        for (tier in VaultTiers.LOCKED) {
            val tierStatus = status.tier(tier)
            VaultTierSection(tier, INTRO.getValue(tier)) {
                if (!tierStatus.enabled) {
                    CardButton("Turn on", { enableTier = tier }, icon = ProductIcon.Lock, variant = CardButtonVariant.PRIMARY)
                    return@VaultTierSection
                }
                TierStatusLine(
                    tier,
                    tierStatus,
                    now,
                    onUnlock = {
                        prompt = VaultPrompt(
                            title = "Unlock the Secure tier",
                            description = "Settings keeps it open for five minutes.",
                            confirmLabel = "Unlock",
                            tier = tier,
                            askPassword = true,
                            action = { pw -> try { api.vaultUnlockSecure(pw.orEmpty()) } finally { done() } },
                        )
                    },
                    onLock = {
                        revealed = null
                        scope.launch {
                            try {
                                api.vaultLock(VaultTiers.SECURE)
                                done()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                model.toast(ErrorToastMessage(VaultFailure.of(e).message))
                            }
                        }
                    },
                )
                ItemList(current.items.filter { it.tier == tier }) { item ->
                    VaultItemRow(
                        item,
                        revealed = revealed?.takeIf { it.id == item.id },
                        onShow = {
                            act(
                                tier,
                                VaultPrompt(
                                    title = "Show “${item.name}”",
                                    description = "Shown here for ${VAULT_REVEAL_MS / 1000} seconds.",
                                    confirmLabel = "Show",
                                    action = { pw -> revealed = VaultRevealed(item.id, api.vaultReveal(item.id, pw), System.currentTimeMillis() + VAULT_REVEAL_MS) },
                                ),
                                confirm = false,
                            )
                        },
                        onHide = { revealed = null },
                        onEdit = { itemEdit = VaultItemEdit(tier, item, askPassword = vaultAsksFirst(tier, secureOpen)) },
                        onMove = { moveItem(item) },
                        onDelete = { deleteItem(item) },
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardButton("Add", { itemEdit = VaultItemEdit(tier, askPassword = vaultAsksFirst(tier, secureOpen)) }, icon = ProductIcon.Plus, variant = CardButtonVariant.PRIMARY)
                    CardButton("Change password", { passwordTier = tier }, icon = ProductIcon.Key)
                    CardButton(
                        "Delete tier",
                        {
                            prompt = VaultPrompt(
                                title = "Delete the ${VaultTiers.label(tier)} tier?",
                                description = "The tier is turned off and every item in it is deleted. Deleted items stay in older backups until they expire.",
                                confirmLabel = "Delete tier",
                                danger = true,
                                tier = tier,
                                askPassword = true,
                                acknowledge = vaultDeleteTierAcknowledge(tierStatus.itemCount),
                                action = { pw -> try { api.vaultDeleteTier(tier, pw.orEmpty()) } finally { done() } },
                            )
                        },
                        icon = ProductIcon.Trash2,
                        variant = CardButtonVariant.DANGER,
                    )
                }
            }
        }
    }
    prompt?.let { VaultPromptDialog(it) { prompt = null } }
    itemEdit?.let { VaultItemDialog(api, it, onClose = { itemEdit = null }, onDone = done) }
    move?.let { VaultMoveDialog(api, it, onClose = { move = null }, onDone = done) }
    enableTier?.let { VaultEnableTierDialog(api, it, onClose = { enableTier = null }, onDone = done) }
    passwordTier?.let { VaultChangePasswordDialog(api, it, onClose = { passwordTier = null }, onDone = done) }
}

/** A tier's heading and what it is for (`SectionHeader`), then its body. */
@Composable
private fun VaultTierSection(tier: String, note: String, body: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().testTag("vault-tier-$tier"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(VaultTiers.label(tier), style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color))
            BasicText(note, style = vaultXs.copy(color = tokens.text3.color))
        }
        body()
    }
}

@Composable
private fun ItemList(items: List<VaultItemMeta>, row: @Composable (VaultItemMeta) -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, shape)) {
        if (items.isEmpty()) BasicText("No items yet.", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), vaultXs.copy(color = tokens.text3.color))
        items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(tokens.border.color))
            row(item)
        }
    }
}

/** Secure: unlocked for m:ss with Lock now, or Locked with Unlock; Sealed: always asks. Then any lock-out. */
@Composable
private fun TierStatusLine(tier: String, status: VaultTierStatus, now: Long, onUnlock: () -> Unit, onLock: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val openMs = status.settingsOpenUntil - now
    val lockedOutMs = status.lockedUntil - now
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (tier == VaultTiers.SECURE) {
            if (openMs > 0) {
                Pill(ProductIcon.ShieldCheck, "Unlocked, ${vaultCountdown(openMs)} left", tokens.success.opacity(0.15), tokens.success, "vault-unlocked")
                CardButton("Lock now", onLock)
            } else {
                Pill(ProductIcon.Lock, "Locked", tokens.panel2, tokens.text2, "vault-locked")
                CardButton("Unlock for 5 minutes", onUnlock)
            }
        } else {
            BasicText("Asks for its password at every action.", style = vaultXs.copy(color = tokens.text3.color))
        }
        if (lockedOutMs > 0) {
            BasicText(
                "Too many wrong passwords — try again in ${(lockedOutMs + 999) / 1000} s.",
                Modifier.testTag("vault-locked-out"),
                vaultXs.copy(color = tokens.error.color),
            )
        }
    }
}

@Composable
private fun Pill(icon: ProductIcon, label: String, fill: com.hienlh.ppm.design.Rgba, ink: com.hienlh.ppm.design.Rgba, tag: String) {
    Row(
        Modifier.testTag(tag).background(fill.color, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(icon, 14.dp, ink.color)
        BasicText(label, style = vaultXs.copy(color = ink.color))
    }
}

/**
 * One item (`VaultItemRow`): its name, kind and last change, and its actions as always-visible
 * buttons. A public row shows its value; a locked row shows it only after Show, with Copy.
 */
@Composable
private fun VaultItemRow(
    item: VaultItemMeta,
    publicValue: String? = null,
    revealed: VaultRevealed? = null,
    onShow: (() -> Unit)? = null,
    onHide: () -> Unit = {},
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val now = rememberNow(revealed != null)
    var copied by remember(revealed) { mutableStateOf(false) }
    val showing = revealed != null && revealed.until > now
    LaunchedEffect(revealed, showing) { if (revealed != null && !showing) onHide() }
    val shown = publicValue ?: revealed?.value?.takeIf { showing }
    val mobile = LocalPpmMobile.current
    val body: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                buildAnnotatedString {
                    append(item.name)
                    withStyle(SpanStyle(color = tokens.text3.color, fontSize = vaultXs.fontSize)) { append(" · ${VaultKinds.label(item.kind)}") }
                },
                style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
                maxLines = 1,
            )
            BasicText("Updated ${vaultUpdated(item.updatedAt)}", style = vaultSmall.copy(color = tokens.text3.color))
            if (shown != null) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer(Modifier.weight(1f)) {
                        BasicText(
                            shown,
                            Modifier.testTag("vault-value-${item.id}").background(tokens.panel2.color, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                            ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                        )
                    }
                    if (showing) BasicText(vaultCountdown(revealed!!.until - now), style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shown != null) {
                RowIcon(ProductIcon.Copy, if (copied) "Copied" else "Copy") {
                    copySensitive(context, item.name, shown)
                    copied = true
                }
            }
            if (onShow != null) {
                if (showing) RowIcon(ProductIcon.EyeOff, "Hide", onClick = onHide) else RowIcon(ProductIcon.Eye, "Show", onClick = onShow)
            }
            RowIcon(ProductIcon.Pencil, "Edit", onClick = onEdit)
            RowIcon(ProductIcon.ArrowLeftRight, "Move to another tier", onClick = onMove)
            RowIcon(ProductIcon.Trash2, "Delete", danger = true, onClick = onDelete)
        }
    }
    val pad = Modifier.fillMaxWidth().testTag("vault-item-${item.id}").padding(horizontal = 16.dp, vertical = 8.dp)
    if (mobile) {
        Column(pad, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            body(Modifier.fillMaxWidth())
            actions()
        }
    } else {
        Row(pad, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            body(Modifier.weight(1f))
            actions()
        }
    }
}

/** `IconButton` of the settings rows: 44 dp on a phone, 32 dp on a tablet, named for a screen reader. */
@Composable
private fun RowIcon(icon: ProductIcon, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val size = if (LocalPpmMobile.current) 44.dp else 32.dp
    Box(
        Modifier
            .size(size)
            .ppmClickable(shape = RoundedCornerShape(6.dp), onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 16.dp, (if (danger) tokens.error else tokens.text2).color) }
}

/** A sheet with a scrolling body and Cancel / confirm in its footer, closed only while nothing runs. */
@Composable
internal fun VaultDialog(
    title: String,
    description: String?,
    confirmLabel: String,
    ready: Boolean,
    busy: Boolean,
    danger: Boolean = false,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AssistantSheet(
        title = title,
        subtitle = description,
        onDismiss = { if (!busy) onClose() },
        footer = {
            CardButton("Cancel", onClose, Modifier.weight(1f), enabled = !busy)
            CardButton(confirmLabel, onConfirm, Modifier.weight(1f), variant = if (danger) CardButtonVariant.DANGER else CardButtonVariant.PRIMARY, enabled = ready)
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** A refusal with its detail ("1 attempt left.", "Try again in 42 s."), counting down while locked out. */
@Composable
internal fun VaultFailureText(failure: VaultFailure?) {
    val now = rememberNow((failure?.lockedUntil ?: 0) > System.currentTimeMillis())
    if (failure == null) return
    BasicText(failure.text(now), Modifier.testTag("vault-dialog-error"), vaultXs.copy(color = LocalPpmTokens.current.error.color))
}

@Composable
private fun VaultPromptDialog(prompt: VaultPrompt, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // A new prompt starts clean; the password never outlives the dialog it was typed into.
    var password by remember(prompt) { mutableStateOf("") }
    var ack by remember(prompt) { mutableStateOf(false) }
    var busy by remember(prompt) { mutableStateOf(false) }
    var failure by remember(prompt) { mutableStateOf<VaultFailure?>(null) }
    var askPassword by remember(prompt) { mutableStateOf(prompt.askPassword) }
    val now = rememberNow((failure?.lockedUntil ?: 0) > System.currentTimeMillis())
    val ready = !busy && failure?.lockedOut(now) != true && (!askPassword || password.isNotEmpty()) && (prompt.acknowledge == null || ack)
    val submit = {
        if (ready) {
            busy = true
            failure = null
            scope.launch {
                try {
                    prompt.action(if (askPassword) password else null)
                    onClose()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val f = VaultFailure.of(e)
                    if (f.needsPassword && prompt.tier != null) askPassword = true
                    failure = f
                } finally {
                    busy = false
                }
            }
        }
    }
    VaultDialog(prompt.title, prompt.description, prompt.confirmLabel, ready, busy, prompt.danger, onClose, submit) {
        val tier = prompt.tier
        if (askPassword && tier != null) {
            com.hienlh.ppm.chat.VaultPasswordField("${VaultTiers.label(tier)} tier password", password, { password = it }, !busy, failure != null, Modifier.fillMaxWidth(), submit)
        }
        prompt.acknowledge?.let { text ->
            VaultCheckRow(ack, { ack = it }, enabled = !busy, tag = "vault-acknowledge") {
                BasicText(text, style = vaultXs.copy(color = LocalPpmTokens.current.text.color))
            }
        }
        VaultFailureText(failure)
    }
}
