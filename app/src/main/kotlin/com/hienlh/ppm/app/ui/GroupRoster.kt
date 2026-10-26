package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.GroupTab
import com.hienlh.ppm.chat.pulse
import com.hienlh.ppm.design.ChromeCheckbox
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.GroupMember
import com.hienlh.ppm.protocol.MemberStatus
import com.hienlh.ppm.protocol.MemberValues
import com.hienlh.ppm.state.GroupChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Whom the member form is open for: [member], or a member still to add when null. */
private class MemberEdit(val member: GroupMember?)

/**
 * The roster beside the feed on a tablet (`flex w-60 shrink-0 flex-col border-l border-border
 * bg-panel`): "Members" in its head (`h-11 border-b px-3`), under it the reply cap, an error and
 * the members, scrolling (`min-h-0 flex-1 overflow-y-auto`).
 */
@Composable
internal fun GroupRosterPanel(model: AppModel, group: GroupTab, state: GroupChat.State) {
    val tokens = LocalPpmTokens.current
    var editing by remember { mutableStateOf<MemberEdit?>(null) }
    Column(
        Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Start),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) { MembersTitle() }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            RosterBody(group.chat, state, mobile = false) { editing = it }
        }
    }
    editing?.let { edit -> key(edit) { MemberDialog(model, group.chat, edit.member, mobile = false) { editing = null } } }
}

/**
 * The roster on a phone, a sheet (`max-h-[72vh]`) the header's toggle opens over the feed:
 * "Members" (`h-11 px-4`, no border), the rest scrolling under it (`min-h-0 overflow-y-auto`).
 */
@Composable
internal fun GroupRosterSheet(model: AppModel, group: GroupTab, state: GroupChat.State, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<MemberEdit?>(null) }
    // A `vh` is Chrome's large viewport: everything under the status bar.
    val density = LocalDensity.current
    val vh = with(density) { (LocalWindowInfo.current.containerSize.height - WindowInsets.statusBars.getTop(density)).toDp() }
    BottomSheet(onDismiss, Modifier.heightIn(max = vh * 0.72f)) {
        CssColumn(Modifier.fillMaxWidth().weight(1f, fill = false), top = LocalSheetContentTop.current) {
            Box(Modifier.fillMaxWidth().cssHeight(44.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) { MembersTitle() }
            Box(Modifier.cssFlex().fillMaxWidth().verticalScroll(rememberScrollState())) {
                RosterBody(group.chat, state, mobile = true) { editing = it }
            }
        }
    }
    editing?.let { edit -> key(edit) { MemberDialog(model, group.chat, edit.member, mobile = true) { editing = null } } }
}

/** `text-sm font-medium text-foreground`. */
@Composable
private fun MembersTitle() {
    BasicText("Members", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = LocalPpmTokens.current.text.color))
}

/** The reply cap once the group is read, the last failed removal, then the members. */
@Composable
private fun RosterBody(chat: GroupChat, state: GroupChat.State, mobile: Boolean, onEdit: (MemberEdit) -> Unit) {
    val tokens = LocalPpmTokens.current
    CssColumn(Modifier.fillMaxWidth()) {
        state.replyCap?.let { CapRow(chat, it, mobile) }
        // `px-3 py-1 text-xs text-destructive`, in the sheet too.
        state.rosterError?.takeIf { it.isNotEmpty() }?.let {
            BasicText(it, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
        }
        MemberList(chat, state, onEdit)
    }
}

/**
 * "Reply cap" (`flex items-center gap-2 px-3 py-2 text-xs text-text-secondary`, `px-4` in the
 * sheet), its number (`w-14 rounded border bg-background px-1.5 py-1 text-xs`, 16 px on a phone as
 * every input there, on `text-xs`'s lines) and "turns/msg" in the subtle colour. Every change is
 * held to 1–50 as it is typed and saved, as the web's number field does; no focus ring, the page
 * turning Chrome's off. A cap the server gives back, after a save it refused, replaces the text.
 */
@Composable
private fun CapRow(chat: GroupChat, cap: Int, mobile: Boolean) {
    val tokens = LocalPpmTokens.current
    val field = remember { TextFieldState("$cap") }
    LaunchedEffect(cap) {
        if (field.text.toString().toIntOrNull() != cap) field.setTextAndPlaceCursorAtEnd("$cap")
    }
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString().toIntOrNull() }.collect { turns ->
            if (turns != null && turns != chat.state.value.replyCap) chat.setReplyCap(turns)
        }
    }
    val font = if (mobile) 16f else 12f
    val line = font * (4f / 3)
    val label = ppmText(12f, lineHeight = 16f)
    // `py-1` over one line, inside borders Chrome snaps to whole pixels before it adds them up.
    val input = with(LocalDensity.current) { ((line + 8f).dp.toPx() + 2 * cssBorderPx(1.dp)).toDp() }
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .cssHeight(input + 16.dp)
            .padding(horizontal = if (mobile) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("Reply cap", style = label.copy(color = tokens.text2.color))
        Box(
            Modifier
                .width(56.dp)
                .cssHeight(input)
                .clip(shape)
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, shape)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            PpmTextField(
                field,
                "",
                Modifier.fillMaxWidth(),
                style = ppmText(font, lineHeight = line),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                inputTransformation = CapDigits,
            )
        }
        BasicText("turns/msg", style = label.copy(color = tokens.text3.color))
    }
}

/** `flex flex-col gap-0.5 p-2`: a row a member, then Add member (`mt-1`). Each status is the burst's while one runs. */
@Composable
private fun MemberList(chat: GroupChat, state: GroupChat.State, onEdit: (MemberEdit) -> Unit) {
    val live = remember(state.members) { state.members.associate { it.id to it.status } }
    CssColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp), gap = 2.dp, top = 8.dp, bottom = 8.dp) {
        for (member in state.roster) {
            key(member.id) {
                MemberRow(
                    member,
                    live[member.id] ?: member.status,
                    typing = member.name in state.typing,
                    busy = state.rosterBusy,
                    onEdit = { onEdit(MemberEdit(member)) },
                    onRemove = { chat.removeMember(member) },
                )
            }
        }
        Box(Modifier.fillMaxWidth().cssHeight(36.dp).padding(top = 4.dp)) {
            AddMemberRow(busy = state.rosterBusy) { onEdit(MemberEdit(null)) }
        }
    }
}

private fun MemberStatus.label() = when (this) {
    MemberStatus.IDLE -> "Idle"
    MemberStatus.WORKING -> "Working"
    MemberStatus.DONE -> "Done"
    MemberStatus.ERROR -> "Error"
}

/**
 * A member (`flex items-center gap-2 rounded-md px-2 py-1.5`): the avatar (`size-6`, the first
 * letter, a 2 px ring of the background), the name in the member's colour with "lead" after the
 * leader's and under it "typing…", the persona or the status; then Edit and Remove — always shown,
 * a touch screen never matching the web's `can-hover:` — and the status's dot.
 */
@Composable
private fun MemberRow(member: GroupMember, status: MemberStatus, typing: Boolean, busy: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val color = member.color?.let(Rgba::hex)
    Row(
        Modifier.fillMaxWidth().cssHeight(44.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ring = tokens.bgSolid.color
        Box(
            Modifier
                .size(24.dp)
                .drawBehind { drawCircle(ring, radius = size.minDimension / 2 + 2.dp.toPx()) }
                .background((color ?: tokens.accent).color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(member.name.take(1).uppercase(), style = ppmText(10f, FontWeight.SemiBold, lineHeight = 15f).copy(color = Color.White), maxLines = 1, softWrap = false)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(
                    member.name,
                    Modifier.weight(1f, fill = false),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = (color ?: tokens.text).color),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                // `rounded-sm bg-accent-wash px-1 text-[9px] font-semibold uppercase text-primary`.
                if (member.isLeader) {
                    BasicText(
                        "LEAD",
                        Modifier.background(tokens.accentWash.color, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
                        style = ppmText(9f, FontWeight.SemiBold, lineHeight = 13.5f).copy(color = tokens.accent.color),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            BasicText(
                if (typing) "typing…" else member.persona?.takeIf { it.isNotEmpty() } ?: status.label(),
                style = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            RosterAction(ProductIcon.Pencil, "Edit ${member.name}", tokens.text, busy, onEdit)
            RosterAction(ProductIcon.Trash2, "Remove ${member.name}", tokens.error, busy, onRemove)
        }
        val dot = when (status) {
            MemberStatus.IDLE -> tokens.text3
            MemberStatus.WORKING -> tokens.accent.opacity(pulse().toDouble())
            MemberStatus.DONE -> EMERALD_500
            MemberStatus.ERROR -> tokens.error
        }
        Box(Modifier.size(8.dp).background(dot.color, CircleShape))
    }
}

/**
 * `flex size-8 rounded-md text-text-subtle hover:bg-surface-elevated hover:text-… active:bg-surface-elevated
 * disabled:opacity-40`, a 14 px icon; a mouse lights it while it is disabled too, as `:hover` does.
 */
@Composable
private fun RosterAction(icon: ProductIcon, label: String, hoverTint: Rgba, busy: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(32.dp)
            .alpha(if (busy) 0.4f else 1f)
            .clip(shape)
            .ppmClickable(
                enabled = !busy,
                pressedColor = tokens.panel2.color,
                hoverColor = tokens.panel2.color,
                fade = false,
                shape = shape,
                onClickLabel = label,
                source = hover.source,
                hoverWhileDisabled = true,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 14.dp, hoverTint.mix(tokens.text3, hover.lit.toDouble()).color) }
}

/** `flex items-center gap-2 rounded-md px-2 py-2 text-xs font-medium text-primary hover:bg-surface-elevated active:bg-surface-elevated disabled:opacity-40`. */
@Composable
private fun AddMemberRow(busy: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxSize()
            .alpha(if (busy) 0.4f else 1f)
            .clip(shape)
            .ppmClickable(enabled = !busy, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, shape = shape, hoverWhileDisabled = true, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.UserPlus, 16.dp, tokens.accent.color)
        BasicText("Add member", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color))
    }
}

/** What the member form holds while it is open. */
private class MemberForm(member: GroupMember?) {
    val name = TextFieldState(member?.name.orEmpty())
    val persona = TextFieldState(member?.persona.orEmpty())
    var model by mutableStateOf(member?.model)
    var leader by mutableStateOf(member?.isLeader == true)
    var submitting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * "Edit member" or "Add member" (group-member-edit-dialog.tsx): the name, the persona, the model
 * and whether the member leads — a leader stays one until another member is made leader, so its
 * box cannot be cleared. On a tablet the web's dialog (`sm:max-w-md`), on a phone a sheet
 * (`max-h-[90vh]`) whose form does not scroll. Saved, the roster is read again and it closes.
 */
@Composable
private fun MemberDialog(model: AppModel, chat: GroupChat, member: GroupMember?, mobile: Boolean, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val form = remember { MemberForm(member) }
    val title = if (member != null) "Edit member" else "Add member"
    fun submit() {
        val name = form.name.text.trim().toString()
        if (name.isEmpty()) {
            form.error = "Name is required"
            return
        }
        form.submitting = true
        form.error = null
        val values = MemberValues(name, if (form.leader) "leader" else "member", form.persona.text.trim().toString().ifEmpty { null }, form.model)
        model.scope.launch {
            try {
                chat.saveMember(member, values)
                onClose()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                form.error = failure.message ?: "Failed to save member"
                form.submitting = false
            }
        }
    }
    val alreadyLeader = member?.isLeader == true
    if (mobile) {
        val density = LocalDensity.current
        val vh = with(density) { (LocalWindowInfo.current.containerSize.height - WindowInsets.statusBars.getTop(density)).toDp() }
        BottomSheet(onClose, Modifier.heightIn(max = vh * 0.9f)) {
            // `flex min-h-0 flex-col px-4 pb-2`, the title `mb-2 text-base font-semibold`.
            CssColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), gap = 8.dp, top = LocalSheetContentTop.current, bottom = 8.dp) {
                BasicText(title, Modifier.cssLines(24f), style = ppmText(16f, FontWeight.SemiBold, lineHeight = 24f).copy(color = tokens.text.color))
                MemberFormView(model, form, member != null, alreadyLeader, mobile, onCancel = onClose, onSubmit = ::submit)
            }
        }
    } else {
        WebDialog(title, onClose, widest = 448.dp) {
            MemberFormView(model, form, member != null, alreadyLeader, mobile, onCancel = onClose, onSubmit = ::submit)
        }
    }
}

/** The form (`flex flex-col gap-3 px-1 py-1`), focused on the name. */
@Composable
private fun MemberFormView(model: AppModel, form: MemberForm, editing: Boolean, alreadyLeader: Boolean, mobile: Boolean, onCancel: () -> Unit, onSubmit: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    CssColumn(Modifier.fillMaxWidth().padding(horizontal = 4.dp), gap = 12.dp, top = 4.dp, bottom = 4.dp) {
        GroupInput(form.name, "Member name", mobile, focus = focus)
        GroupInput(form.persona, "Persona (optional)", mobile)
        // Nothing clips its list: the form does not scroll.
        FormModelSelector(model, form.model, null) { form.model = it }
        LeaderToggle(form, alreadyLeader)
        form.error?.let { BasicText(it, Modifier.cssLines(16f), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color)) }
        // `flex justify-end gap-2 pt-1`, the buttons at the web's default size (`h-9`).
        val dark = isSystemInDarkTheme()
        Row(Modifier.fillMaxWidth().cssHeight(40.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            DialogButton("Cancel", primary = false, dark, enabled = !form.submitting, Modifier, height = 36.dp, onClick = onCancel)
            // The spinner is the button's own child, so `has-[>svg]:px-3` holds; `mr-1.5` on top of the `gap-2`.
            DialogButton(
                if (editing) "Save" else "Add member", primary = true, dark, enabled = !form.submitting, Modifier,
                icon = if (form.submitting) ProductIcon.Loader2 else null, spinning = form.submitting, height = 36.dp, gap = 14.dp,
                padding = if (form.submitting) 12.dp else 16.dp, onClick = onSubmit,
            )
        }
    }
}

/**
 * The leader box in its label (`flex items-center gap-2 text-sm text-text-secondary`): Chrome's
 * own checkbox (`size-4`) in the accent, the whole label toggling it; at 60% and fixed for the
 * member who leads already.
 */
@Composable
private fun LeaderToggle(form: MemberForm, alreadyLeader: Boolean) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(enabled = !alreadyLeader, instant = true)
    Row(
        Modifier
            .fillMaxWidth()
            .cssHeight(20.dp)
            .alpha(if (alreadyLeader) 0.6f else 1f)
            .hoverable(hover.source)
            .then(
                if (alreadyLeader) Modifier else Modifier.toggleable(form.leader, hover.source, indication = null, role = Role.Checkbox) { form.leader = it },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChromeCheckbox(form.leader, Modifier.size(16.dp), enabled = !alreadyLeader, accent = tokens.accent.color, hovered = hover.lit > 0f)
        BasicText(
            if (alreadyLeader) "Leader (reassign from another member)" else "Set as leader",
            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color),
        )
    }
}
