package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.toSize
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.createGroup
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.Group
import com.hienlh.ppm.protocol.MemberValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max

/** The colours the web gives a new group's roster: the leader the first, member i the (i + 1)th, round again. */
private val PALETTE = listOf("#6366f1", "#ec4899", "#14b8a6", "#f59e0b", "#8b5cf6", "#ef4444")

/** The model list the form offers: the web's dialog is always given Claude's. */
private const val PROVIDER = "claude"

/** A member of the form, before anything is sent. */
private class DraftMember {
    val key: String = UUID.randomUUID().toString()
    val name = TextFieldState()
    val persona = TextFieldState()
    var model by mutableStateOf<String?>(null)
}

/** What the form holds while it is open; the next one starts afresh, as the web's `reset` on close has it. */
private class GroupDraft {
    val name = TextFieldState()
    val cap = TextFieldState("10")
    val leaderName = TextFieldState("Leader")
    val leaderPersona = TextFieldState()
    var leaderModel by mutableStateOf<String?>(null)
    val members = mutableStateListOf(DraftMember())
    var submitting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * "New group" (group-create-dialog.tsx): the group's name and reply cap, the leader, and the
 * members, each with a name, a persona and a model; members left unnamed are left out. On a tablet
 * the web's dialog (`max-h-[85vh] sm:max-w-lg`), on a phone a sheet (`max-h-[90vh]`); in both the
 * form scrolls. Creating starts nothing: the group's sessions come with its first message. A
 * group made after the form was closed still lands in the list and opens, as on the web.
 */
@Composable
internal fun GroupCreateDialog(model: AppModel, projectName: String, projectPath: String, onDismiss: () -> Unit, onCreated: (Group) -> Unit) {
    val api = model.api ?: return
    val mobile = LocalPpmMobile.current
    val draft = remember { GroupDraft() }
    fun submit() {
        val groupName = draft.name.text.trim().toString()
        val leader = draft.leaderName.text.trim().toString()
        val named = draft.members.filter { it.name.text.isNotBlank() }
        draft.error = when {
            groupName.isEmpty() -> "Group name is required"
            leader.isEmpty() -> "Leader name is required"
            named.isEmpty() -> "Add at least one member"
            else -> null
        }
        if (draft.error != null) return
        val roster = listOf(MemberValues(leader, "leader", draft.leaderPersona.text.trim().toString().ifEmpty { null }, draft.leaderModel, PALETTE[0])) +
            named.mapIndexed { index, member ->
                MemberValues(member.name.text.trim().toString(), "member", member.persona.text.trim().toString().ifEmpty { null }, member.model, PALETTE[(index + 1) % PALETTE.size])
            }
        draft.submitting = true
        model.scope.launch {
            try {
                val group = api.createGroup(projectName, projectPath, groupName, capValue(draft.cap.text), roster)
                onCreated(group)
                onDismiss()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                draft.error = failure.message ?: "Failed to create group"
                draft.submitting = false
            }
        }
    }
    // A `vh` is Chrome's large viewport: everything under the status bar.
    val density = LocalDensity.current
    val vh = with(density) { (LocalWindowInfo.current.containerSize.height - WindowInsets.statusBars.getTop(density)).toDp() }
    if (mobile) {
        BottomSheet(onDismiss, Modifier.heightIn(max = vh * 0.9f)) {
            // `flex min-h-0 flex-col px-4 pb-2`, the title `mb-2 text-base font-semibold`.
            CssColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 16.dp),
                gap = 8.dp,
                top = LocalSheetContentTop.current,
                bottom = 8.dp,
            ) {
                BasicText("New group", Modifier.cssLines(24f), style = ppmText(16f, FontWeight.SemiBold, lineHeight = 24f).copy(color = LocalPpmTokens.current.text.color))
                GroupForm(model, draft, mobile, Modifier.cssFlex(), onCancel = onDismiss, onSubmit = ::submit)
            }
        }
    } else {
        WebDialog("New group", onDismiss, tallest = vh * 0.85f) {
            GroupForm(model, draft, mobile, Modifier, onCancel = onDismiss, onSubmit = ::submit)
        }
    }
}

/** What the reply cap is when the field reads [typed]: `Math.max(1, Math.min(50, Number(typed) || 1))`. */
internal fun capValue(typed: CharSequence): Int = (typed.toString().toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0).coerceIn(1.0, 50.0).toInt()

/**
 * The reply cap's field: digits only, and whatever they come to is written back as the cap the web
 * would keep — an emptied field reads 1, 80 reads 50 — unless the field already reads that number.
 */
internal val CapDigits = InputTransformation {
    if (!asCharSequence().all(Char::isDigit)) {
        revertAllChanges()
    } else {
        val turns = capValue(asCharSequence())
        if (asCharSequence().toString().toIntOrNull() != turns) replace(0, length, "$turns")
    }
}

/** The form (`flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-1 py-1`), focused on its first field. */
@Composable
private fun GroupForm(model: AppModel, draft: GroupDraft, mobile: Boolean, modifier: Modifier, onCancel: () -> Unit, onSubmit: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The form on screen: it clips the model lists, as the web's scrolling form does.
    var viewport by remember { mutableStateOf<Rect?>(null) }
    CssColumn(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { viewport = Rect(it.positionOnScreen(), it.size.toSize()) }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        gap = 16.dp,
        top = 4.dp,
        bottom = 4.dp,
    ) {
        Field("Group name") { GroupInput(draft.name, "e.g. Design Review", mobile, focus = focus) }
        Field("Reply cap (max AI turns per message, 1–50)") { GroupInput(draft.cap, "", mobile, number = true) }
        // `rounded-lg border p-3`: "Leader" (`mb-2`), then its fields `gap-2`.
        RosterCard {
            BasicText(
                "LEADER",
                Modifier.cssLines(16f),
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accent.color, letterSpacing = 0.025.em),
            )
            GroupInput(draft.leaderName, "Leader name", mobile)
            GroupInput(draft.leaderPersona, "Persona (optional)", mobile)
            FormModelSelector(model, draft.leaderModel, viewport) { draft.leaderModel = it }
        }
        // "Members" and Add (`mb-2`), then a card each (`gap-3`).
        CssColumn(gap = 8.dp) {
            Row(Modifier.fillMaxWidth().cssHeight(32.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "MEMBERS",
                    Modifier.weight(1f),
                    style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text2.color, letterSpacing = 0.025.em),
                )
                AddMemberButton { draft.members += DraftMember() }
            }
            CssColumn(gap = 12.dp) {
                draft.members.forEachIndexed { index, member ->
                    key(member.key) {
                        RosterCard {
                            // `mb-2 flex items-center justify-between`: as tall as the bin once there is one.
                            val removable = draft.members.size > 1
                            Row(Modifier.fillMaxWidth().cssHeight(if (removable) 32.dp else 16.5.dp), verticalAlignment = Alignment.CenterVertically) {
                                BasicText("Member ${index + 1}", Modifier.weight(1f), style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color))
                                if (removable) RemoveMemberButton { draft.members.remove(member) }
                            }
                            GroupInput(member.name, "Member name", mobile)
                            GroupInput(member.persona, "Persona (optional)", mobile)
                            FormModelSelector(model, member.model, viewport) { member.model = it }
                        }
                    }
                }
            }
        }
        draft.error?.let { BasicText(it, Modifier.cssLines(16f), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color)) }
        // `flex justify-end gap-2 pt-1`, the buttons at the web's default size (`h-9`).
        val dark = isSystemInDarkTheme()
        Row(Modifier.fillMaxWidth().cssHeight(40.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            DialogButton("Cancel", primary = false, dark, enabled = !draft.submitting, Modifier, height = 36.dp, onClick = onCancel)
            // The spinner is the button's own child, so `has-[>svg]:px-3` holds; `mr-1.5` on top of the `gap-2`.
            DialogButton(
                "Create group", primary = true, dark, enabled = !draft.submitting, Modifier,
                icon = if (draft.submitting) ProductIcon.Loader2 else null, spinning = draft.submitting, height = 36.dp, gap = 14.dp,
                padding = if (draft.submitting) 12.dp else 16.dp, onClick = onSubmit,
            )
        }
    }
}

/** A field: its label (`mb-1 block text-xs font-medium text-text-secondary`) over its input. */
@Composable
private fun Field(label: String, input: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    CssColumn(gap = 4.dp) {
        BasicText(label, Modifier.cssLines(16f), style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
        input()
    }
}

/** `rounded-lg border border-border p-3`, its rows `gap-2`. */
@Composable
private fun RosterCard(content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    CssColumn(
        Modifier.fillMaxWidth().clip(shape).cssBorder(tokens.border.color, shape).padding(horizontal = 12.dp),
        gap = 8.dp,
        top = 12.dp,
        bottom = 12.dp,
        content = content,
    )
}

/**
 * The form's `<input>` (`min-h-[40px] rounded-md border bg-background px-3 py-2 text-sm`), not the
 * shared `FormInput`: 14/20 on a tablet, and on a phone the 16 px globals.css forces on every input,
 * on `text-sm`'s lines (1.4286 of it), which makes it 40.86 px tall. Focused it wears
 * `focus:ring-1 focus:ring-primary`, a 1 px ring outside the border; the placeholder is cut at the
 * edge, as Chrome cuts it.
 */
@Composable
internal fun GroupInput(state: TextFieldState, placeholder: String, mobile: Boolean, focus: FocusRequester? = null, number: Boolean = false) {
    val tokens = LocalPpmTokens.current
    val font = if (mobile) 16f else 14f
    val line = font * (1.25f / 0.875f)
    // `min-h-[40px]` over one line and its `py-2`, inside borders Chrome snaps to whole pixels
    // before it adds them up: 106 px at 2.625x, where borders counted whole would make 107.25.
    val height = with(LocalDensity.current) { max(40.dp.toPx(), (line + 16f).dp.toPx() + 2 * cssBorderPx(1.dp)).toDp() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val ring = tokens.accent.color
    Box(
        Modifier
            .fillMaxWidth()
            .cssHeight(height)
            .drawBehind {
                if (!focused) return@drawBehind
                val px = 1.dp.toPx()
                drawRoundRect(ring, Offset(-px / 2, -px / 2), Size(size.width + px, size.height + px), CornerRadius(8.dp.toPx() + px / 2), style = Stroke(px))
            }
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        PpmTextField(
            state,
            placeholder,
            Modifier.fillMaxWidth(),
            style = ppmText(font, lineHeight = line),
            placeholderColor = tokens.text3.color,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Next),
            inputTransformation = if (number) CapDigits else null,
            focusRequester = focus,
            onFocusChanged = { focused = it },
            placeholderOverflow = TextOverflow.Clip,
        )
    }
}

/** The model chip's height and its baseline (`inline-flex`, the icon first: the icon's bottom). */
private const val CHIP_HEIGHT = 27.25f
private const val CHIP_BASELINE = 20.625f

/**
 * The model chip in a block of its own (`relative`): it sits on a line of the page's 16/24 text,
 * whose part under the baseline is deeper than the chip's, so the block ends below the chip.
 * Nothing at all, gap included, while the provider offers one model or none.
 */
@Composable
internal fun FormModelSelector(model: AppModel, value: String?, viewport: Rect?, onChange: (String) -> Unit) {
    val catalog = model.models ?: return
    val entries by catalog.entries.collectAsState()
    if ((entries[PROVIDER]?.models?.size ?: 0) <= 1) {
        // Asks for the list, and shows nothing until there is a choice.
        ModelSelector(model, value, PROVIDER, viewport, onChange)
        return
    }
    val density = LocalDensity.current.density
    val below = PpmFonts.chromeBelowBaseline(16f * density, 24f * density, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT) / density
    Box(Modifier.cssHeight((CHIP_BASELINE + max(CHIP_HEIGHT - CHIP_BASELINE, below)).dp)) {
        ModelSelector(model, value, PROVIDER, viewport, onChange)
    }
}

/** `inline-flex min-h-[32px] items-center gap-1 text-xs font-medium text-primary hover:underline`, a 14 px +. */
@Composable
private fun AddMemberButton(onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClickLabel = "Add member", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(ProductIcon.Plus, 14.dp, tokens.accent.color)
        BasicText(
            "Add",
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(
                color = tokens.accent.color,
                textDecoration = if (hover.lit > 0f) TextDecoration.Underline else null,
            ),
        )
    }
}

/** `flex size-8 rounded-md text-text-subtle hover:bg-surface-elevated hover:text-destructive`, a 16 px bin. */
@Composable
private fun RemoveMemberButton(onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(32.dp)
            .clip(shape)
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, fade = false, shape = shape, onClickLabel = "Remove member", source = hover.source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(ProductIcon.Trash2, 16.dp, tokens.error.mix(tokens.text3, hover.lit.toDouble()).color) }
}
