package com.hienlh.ppm.app.ui.settings.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.chat.VAULT_PUBLIC_TIER_NOTE
import com.hienlh.ppm.chat.VAULT_SECRET_NAME_WARNING
import com.hienlh.ppm.chat.VaultCheckRow
import com.hienlh.ppm.chat.VaultChoiceChip
import com.hienlh.ppm.chat.VaultNote
import com.hienlh.ppm.chat.VaultPasswordField
import com.hienlh.ppm.chat.VaultTextField
import com.hienlh.ppm.chat.VaultValueField
import com.hienlh.ppm.chat.rememberNow
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.VaultFailure
import com.hienlh.ppm.client.vaultAddItem
import com.hienlh.ppm.client.vaultChangePassword
import com.hienlh.ppm.client.vaultEnableTier
import com.hienlh.ppm.client.vaultMove
import com.hienlh.ppm.client.vaultUpdateItem
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.VAULT_MAX_NAME_LENGTH
import com.hienlh.ppm.protocol.VaultItemMeta
import com.hienlh.ppm.protocol.VaultKinds
import com.hienlh.ppm.protocol.VaultTiers
import com.hienlh.ppm.protocol.vaultLooksSecretName
import com.hienlh.ppm.state.vault.vaultMoveAsks
import com.hienlh.ppm.state.vault.vaultMovePickable
import com.hienlh.ppm.state.vault.vaultNewPasswordProblem
import com.hienlh.ppm.state.vault.vaultPasswordStrength
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * The vault's dialogs in Settings (`vault-item-dialog.tsx`, `vault-move-dialog.tsx`,
 * `vault-enable-tier-dialog.tsx`, `vault-change-password-dialog.tsx`). Each stays open on a
 * refusal and says why; every password lives in its dialog's state and goes with it.
 */

/** Adding (no [item]) or editing one item; [value] is a public item's current one (locked values are never loaded). */
data class VaultItemEdit(val tier: String, val item: VaultItemMeta? = null, val value: String? = null, val askPassword: Boolean)

data class VaultMove(val item: VaultItemMeta, val secureEnabled: Boolean, val sealedEnabled: Boolean, val secureOpen: Boolean)

/** Runs [block], keeping its refusal for the dialog; [onOk] once it went through. */
private class DialogRun {
    var busy by mutableStateOf(false)
    var failure by mutableStateOf<VaultFailure?>(null)
}

@Composable
private fun rememberDialogRun(key: Any): DialogRun = remember(key) { DialogRun() }

private fun kotlinx.coroutines.CoroutineScope.runDialog(run: DialogRun, block: suspend () -> Unit, onFailure: (VaultFailure) -> Unit = {}, onOk: () -> Unit) {
    run.busy = true
    run.failure = null
    launch {
        try {
            block()
            onOk()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val f = VaultFailure.of(e)
            onFailure(f)
            run.failure = f
        } finally {
            run.busy = false
        }
    }
}

@Composable
fun VaultItemDialog(api: ApiClient, edit: VaultItemEdit, onClose: () -> Unit, onDone: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val run = rememberDialogRun(edit)
    var name by remember(edit) { mutableStateOf(edit.item?.name.orEmpty()) }
    var kind by remember(edit) { mutableStateOf(edit.item?.kind ?: "other") }
    var value by remember(edit) { mutableStateOf(edit.value.orEmpty()) }
    var note by remember(edit) { mutableStateOf("") }
    var password by remember(edit) { mutableStateOf("") }
    var askPassword by remember(edit) { mutableStateOf(edit.askPassword) }
    val tier = edit.tier
    val item = edit.item
    val locked = tier != VaultTiers.PUBLIC
    val now = rememberNow((run.failure?.lockedUntil ?: 0) > System.currentTimeMillis())
    val ready = !run.busy && run.failure?.lockedOut(now) != true && name.trim().isNotEmpty() && (item != null || value.isNotEmpty()) && (!askPassword || password.isNotEmpty())
    val submit = {
        if (ready) {
            val pw = if (askPassword) password else null
            scope.runDialog(
                run,
                {
                    if (item != null) api.vaultUpdateItem(item.id, name.trim(), kind, value.ifEmpty { null }, note.ifEmpty { null }, pw)
                    else api.vaultAddItem(tier, name.trim(), kind, value, note.ifEmpty { null }, pw)
                },
                onFailure = { if (it.needsPassword && locked) askPassword = true },
            ) {
                onDone()
                onClose()
            }
        }
    }
    VaultDialog("${if (item != null) "Edit" else "Add"} ${VaultTiers.label(tier)} item", null, if (item != null) "Save" else "Add", ready, run.busy, onClose = onClose, onConfirm = submit) {
        if (tier == VaultTiers.PUBLIC) VaultNote(VAULT_PUBLIC_TIER_NOTE, tokens.warning, "vault-public-warning")
        VaultTextField(name, { name = it }, "Name", !run.busy, Modifier.fillMaxWidth(), maxLength = VAULT_MAX_NAME_LENGTH)
        if (tier == VaultTiers.PUBLIC && vaultLooksSecretName(name)) VaultNote(VAULT_SECRET_NAME_WARNING, tokens.error, "vault-secret-name-warning")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in VaultKinds.ALL) VaultChoiceChip(VaultKinds.label(k), kind == k, enabled = !run.busy) { kind = k }
        }
        VaultValueField(value, { value = it }, if (item != null && locked) "New value (leave empty to keep the current one)" else "Value", !run.busy, masked = locked)
        VaultTextField(note, { note = it }, if (item != null) "Note (leave empty to keep)" else "Note (optional)", !run.busy, Modifier.fillMaxWidth())
        if (askPassword && locked) {
            VaultPasswordField("${VaultTiers.label(tier)} tier password", password, { password = it }, !run.busy, run.failure != null, Modifier.fillMaxWidth(), submit)
        }
        VaultFailureText(run.failure)
    }
}

/**
 * Moving an item to another tier. Every locked tier involved has to open, so the dialog asks for
 * the source's and the target's password — the Secure one only while Settings' unlock is not open,
 * the Sealed one always. Moving down to Public repeats that tier's warning.
 */
@Composable
fun VaultMoveDialog(api: ApiClient, move: VaultMove, onClose: () -> Unit, onDone: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val run = rememberDialogRun(move)
    val pickable = { t: String -> vaultMovePickable(move.item, t, move.secureEnabled, move.sealedEnabled) }
    var to by remember(move) { mutableStateOf(VaultTiers.ALL.firstOrNull(pickable) ?: VaultTiers.PUBLIC) }
    var passwords by remember(move) { mutableStateOf(mapOf<String, String>()) }
    var forceSecure by remember(move) { mutableStateOf(false) }
    val asked = vaultMoveAsks(move.item.tier, to, move.secureOpen, forceSecure)
    val now = rememberNow((run.failure?.lockedUntil ?: 0) > System.currentTimeMillis())
    val ready = !run.busy && run.failure?.lockedOut(now) != true && pickable(to) && asked.all { !passwords[it].isNullOrEmpty() }
    val submit = {
        if (ready) {
            val sent = asked.associateWith { passwords[it].orEmpty() }
            scope.runDialog(run, { api.vaultMove(move.item.id, to, sent) }, onFailure = { if (it.needsPassword) forceSecure = true }) {
                onDone()
                onClose()
            }
        }
    }
    VaultDialog("Move “${move.item.name}”", "Now in the ${VaultTiers.label(move.item.tier)} tier.", "Move", ready, run.busy, onClose = onClose, onConfirm = submit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in VaultTiers.ALL) {
                val on = t == VaultTiers.PUBLIC || (t == VaultTiers.SECURE && move.secureEnabled) || (t == VaultTiers.SEALED && move.sealedEnabled)
                VaultChoiceChip(VaultTiers.label(t) + if (on) "" else " (off)", to == t, enabled = !run.busy && pickable(t)) { to = t }
            }
        }
        if (to == VaultTiers.PUBLIC) VaultNote(VAULT_PUBLIC_TIER_NOTE, tokens.warning, "vault-public-warning")
        for (t in asked) {
            VaultPasswordField("${VaultTiers.label(t)} tier password", passwords[t].orEmpty(), { v -> passwords = passwords + (t to v) }, !run.busy, run.failure != null, Modifier.fillMaxWidth())
        }
        VaultFailureText(run.failure)
    }
}

private const val FORGET_WARNING = "Forget this password and every item in this tier is lost for good; PPM cannot recover it."

/** A new tier password typed twice, with a strength meter and the tier's minimum length (`VaultNewPasswordFields`). */
@Composable
private fun NewPasswordFields(tier: String, password: String, confirm: String, onPassword: (String) -> Unit, onConfirm: (String) -> Unit, enabled: Boolean, label: String = "New password") {
    val tokens = LocalPpmTokens.current
    val min = VaultTiers.minPasswordLength(tier)
    val strength = vaultPasswordStrength(password, min)
    val meter = listOf(tokens.error, tokens.error, tokens.warning, tokens.success, tokens.success)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VaultPasswordField(label, password, onPassword, enabled, false, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().testTag("vault-strength-${strength.score}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (n in 1..4) {
                    val lit = password.isNotEmpty() && strength.score >= n
                    Box(Modifier.weight(1f).height(6.dp).background((if (lit) meter[strength.score] else tokens.panel2).color, RoundedCornerShape(999.dp)))
                }
            }
            BasicText(if (password.isNotEmpty()) strength.label else "$min+ characters", Modifier.width(80.dp), vaultSmall.copy(color = tokens.text3.color))
        }
        val mismatch = confirm.isNotEmpty() && confirm != password
        VaultPasswordField("Type it again", confirm, onConfirm, enabled, mismatch, Modifier.fillMaxWidth())
        if (mismatch) BasicText("The two passwords differ.", style = vaultSmall.copy(color = tokens.error.color))
    }
}

@Composable
private fun ForgetWarning() {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    BasicText(
        FORGET_WARNING,
        Modifier.fillMaxWidth().testTag("vault-forget-warning").background(tokens.error.opacity(0.1).color, shape).padding(8.dp),
        ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.error.color),
    )
}

/**
 * Turning on the Secure or Sealed tier: its password twice, the warning that a forgotten password
 * loses the tier for good, and a tick acknowledging it.
 */
@Composable
fun VaultEnableTierDialog(api: ApiClient, tier: String, onClose: () -> Unit, onDone: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val run = rememberDialogRun(tier)
    var password by remember(tier) { mutableStateOf("") }
    var confirm by remember(tier) { mutableStateOf("") }
    var ack by remember(tier) { mutableStateOf(false) }
    val ready = !run.busy && vaultNewPasswordProblem(tier, password, confirm) == null && ack
    val submit = {
        if (ready) scope.runDialog(run, { api.vaultEnableTier(tier, password) }) {
            onDone()
            onClose()
        }
    }
    VaultDialog(
        "Turn on the ${VaultTiers.label(tier)} tier",
        if (tier == VaultTiers.SECURE) "Items here are encrypted. The AI reads them only after you type this password on a card."
        else "Items here are encrypted under a second password. One item per card, shown to you unless you give it to the AI.",
        "Turn on",
        ready,
        run.busy,
        onClose = onClose,
        onConfirm = submit,
    ) {
        NewPasswordFields(tier, password, confirm, { password = it }, { confirm = it }, !run.busy, "${VaultTiers.label(tier)} tier password")
        if (tier == VaultTiers.SEALED) BasicText("This password must differ from the Secure tier's password.", style = vaultXs.copy(color = tokens.text2.color))
        ForgetWarning()
        VaultCheckRow(ack, { ack = it }, enabled = !run.busy, tag = "vault-forget-ack") {
            BasicText("I understand that PPM cannot recover this password or the items under it.", style = vaultXs.copy(color = tokens.text.color))
        }
        run.failure?.let { BasicText(it.message, Modifier.testTag("vault-dialog-error"), vaultXs.copy(color = tokens.error.color)) }
    }
}

/**
 * Changing a locked tier's password: the current one, then the new one twice. The server re-wraps
 * the tier's key, so nothing is lost, and every open unlock of that tier ends.
 */
@Composable
fun VaultChangePasswordDialog(api: ApiClient, tier: String, onClose: () -> Unit, onDone: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val run = rememberDialogRun(tier)
    var current by remember(tier) { mutableStateOf("") }
    var password by remember(tier) { mutableStateOf("") }
    var confirm by remember(tier) { mutableStateOf("") }
    val now = rememberNow((run.failure?.lockedUntil ?: 0) > System.currentTimeMillis())
    val ready = !run.busy && run.failure?.lockedOut(now) != true && current.isNotEmpty() && vaultNewPasswordProblem(tier, password, confirm) == null
    val submit = {
        if (ready) scope.runDialog(run, { api.vaultChangePassword(tier, current, password) }) {
            onDone()
            onClose()
        }
    }
    VaultDialog("Change the ${VaultTiers.label(tier)} tier password", null, "Change password", ready, run.busy, onClose = onClose, onConfirm = submit) {
        VaultPasswordField("Current password", current, { current = it }, !run.busy, run.failure != null, Modifier.fillMaxWidth())
        NewPasswordFields(tier, password, confirm, { password = it }, { confirm = it }, !run.busy)
        if (tier == VaultTiers.SEALED) BasicText("The new password must differ from the Secure tier's password.", style = vaultXs.copy(color = tokens.text2.color))
        ForgetWarning()
        VaultFailureText(run.failure)
    }
}
