package com.hienlh.ppm.app.ui.settings.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.addAssistantNote
import com.hienlh.ppm.client.assistantNotes
import com.hienlh.ppm.client.deleteAssistantNote
import com.hienlh.ppm.client.editAssistantNote
import com.hienlh.ppm.client.verifyAssistantNote
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AssistantNote
import com.hienlh.ppm.protocol.AssistantNotesView
import com.hienlh.ppm.state.assistant.noteSourceLabel
import com.hienlh.ppm.state.assistant.notebookMeterLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Settings → PPM Assistant → Notebook (`assistant-notebook-section.tsx`, `notebook-row.tsx`): the
 * short facts the Assistant keeps across a long session. What the user adds or edits becomes theirs
 * and verified; the meter counts characters against the cap as the server does. Live from
 * `assistant:notes_changed`, and again after a reconnect.
 */

internal const val NOTEBOOK_HEADER_LINE = "A note never grants permission or counts as your OK."

private fun errorText(e: Exception) = e.message ?: e.toString()

@Composable
internal fun AssistantNotebookPane(model: AppModel, api: ApiClient) {
    val scope = rememberCoroutineScope()
    var view by remember(api) { mutableStateOf<AssistantNotesView?>(null) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf("") }
    var reloads by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, reloads) {
        try {
            view = api.assistantNotes()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
    }
    val events = model.events
    DisposableEffect(events) {
        val off = events?.onAssistantEvent { type, _ -> if (type == "assistant:notes_changed") reloads++ }
        val offReady = events?.onGlobalReady { reloads++ }
        onDispose {
            off?.invoke()
            offReady?.invoke()
        }
    }
    fun act(failure: String, work: suspend () -> Unit, after: () -> Unit = {}) {
        busy = true
        scope.launch {
            try {
                work()
                view = api.assistantNotes()
                after()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage(failure, errorText(e)))
            } finally {
                busy = false
            }
        }
    }
    val v = view
    if (v == null) {
        AsstLoading(error)
        return
    }
    val tokens = LocalPpmTokens.current
    val room = v.cap - v.used
    val full = room <= 0
    Column(Modifier.fillMaxWidth().testTag("assistant-notebook-section")) {
        AsstCard(ProductIcon.ScrollText, "Notebook", aside = { Meter(v.used, v.cap) }) {
            BasicText(NOTEBOOK_HEADER_LINE, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), asstNote())
            AsstDivider()
            if (v.notes.isEmpty()) BasicText("No notes yet.", Modifier.padding(14.dp), asstNote())
            v.notes.forEachIndexed { i, note ->
                if (i > 0) AsstDivider()
                if (editing == note.id) {
                    val tooLong = draft.length > v.noteMax
                    EditBox(draft, "Edit note", "asst-note-edit-" + note.id, busy, "${draft.length} / ${v.noteMax}", tooLong, onChange = { draft = it }, onCancel = { editing = null }) {
                        act("Could not save the note", { api.editAssistantNote(note.id, draft) }) { editing = null }
                    }
                } else {
                    NoteRow(
                        note = note,
                        busy = busy,
                        onEdit = { editing = note.id; draft = note.text },
                        onDelete = { act("Could not delete the note", { api.deleteAssistantNote(note.id) }) },
                        onVerify = { act("Could not mark the note verified", { api.verifyAssistantNote(note.id) }) },
                    )
                }
            }
            AsstDivider()
            val text = adding
            if (text != null) {
                val limit = minOf(v.noteMax, maxOf(room, 0))
                val tooLong = text.length > v.noteMax || text.length > room
                EditBox(text, "A fact worth keeping, such as a deadline or how a report should read.", "asst-note-new", busy, "${text.length} / $limit", tooLong, onChange = { adding = it }, onCancel = { adding = null }, confirmLabel = "Add") {
                    act("Could not add the note", { api.addAssistantNote(text) }) { adding = null }
                }
            } else {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    SettingsButton("+ Add line", enabled = !full && !busy, modifier = Modifier.testTag("asst-note-add")) { adding = "" }
                    if (full) BasicText("The notebook is full. Delete or shorten a line first.", Modifier.padding(4.dp), ppmText(11f).copy(color = tokens.text3.color))
                }
            }
        }
    }
}

/** "1,400 / 4,000 characters" and a bar, red past the cap. */
@Composable
private fun Meter(used: Int, cap: Int) {
    val tokens = LocalPpmTokens.current
    val fraction = if (cap > 0) (used.toFloat() / cap).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(notebookMeterLabel(used, cap), Modifier.testTag("asst-notebook-meter"), asstNote())
        Box(Modifier.width(96.dp).height(4.dp).clip(RoundedCornerShape(50)).background(tokens.border.color)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background((if (used > cap) tokens.error else tokens.accent).color))
        }
    }
}

@Composable
private fun NoteRow(note: AssistantNote, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onVerify: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).testTag("asst-note-" + note.id), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(note.text, style = asstBody())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(noteSourceLabel(note.source, note.updatedAt), style = ppmText(11f).copy(color = tokens.text3.color))
                if (note.unverified) {
                    AsstPill("Unverified", warning = true, tag = "asst-note-unverified-" + note.id)
                    Row(
                        Modifier
                            .heightIn(min = 32.dp)
                            .ppmClickable(enabled = !busy, onClickLabel = "Mark as verified", onClick = onVerify)
                            .padding(horizontal = 4.dp)
                            .testTag("asst-note-verify-" + note.id),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        com.hienlh.ppm.design.PpmIcon(ProductIcon.ShieldCheck, 14.dp, tokens.accent.color)
                        BasicText("Mark as verified", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.accent.color))
                    }
                }
            }
        }
        AsstIconButton(ProductIcon.Pencil, "Edit note", Modifier.testTag("asst-note-edit-btn-" + note.id), enabled = !busy, onClick = onEdit)
        AsstIconButton(ProductIcon.Trash2, "Delete note", Modifier.testTag("asst-note-delete-" + note.id), danger = true, enabled = !busy, onClick = onDelete)
    }
}

@Composable
private fun EditBox(
    text: String,
    placeholder: String,
    tag: String,
    busy: Boolean,
    counter: String,
    tooLong: Boolean,
    onChange: (String) -> Unit,
    onCancel: () -> Unit,
    confirmLabel: String = "Save",
    onConfirm: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsTextInput(text, onChange, placeholder, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag), singleLine = false)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(counter, Modifier.weight(1f), ppmText(12f).copy(color = (if (tooLong) tokens.error else tokens.text3).color))
            SettingsButton("Cancel", modifier = Modifier.testTag("$tag-cancel"), onClick = onCancel)
            SettingsButton(confirmLabel, primary = true, enabled = !busy && !tooLong && text.isNotBlank(), modifier = Modifier.testTag("$tag-confirm"), onClick = onConfirm)
        }
    }
}
