package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.TaskReference

/** Shows a task's quick view (`TaskChipPeek`) for a chip on a sent message. */
fun interface TaskRefOpener {
    fun open(ref: TaskReference)
}

/** How a task chip opens its task; none, and the chip only names it. */
val LocalTaskRefOpener = staticCompositionLocalOf<TaskRefOpener?> { null }

/** "#12" in a chat of the task's project, "ppm/12" in any other (`taskCode`). */
internal fun taskRefCode(ref: TaskReference, chatProject: String?): String =
    if (chatProject != null && chatProject == ref.projectName) "#${ref.number}" else "${ref.projectName}/${ref.number}"

/**
 * The tasks PPM attached to a sent message, as chips drawn from its own block (`TaskRefChipRow`):
 * the list icon, the code and the title; a tap shows the task's quick view.
 */
@Composable
internal fun TaskRefChipRow(refs: List<TaskReference>, chatProject: String?, modifier: Modifier = Modifier) {
    FlowRow(modifier.padding(bottom = 4.dp).testTag("task-refs"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (ref in refs) TaskRefChip(ref, chatProject)
    }
}

@Composable
private fun TaskRefChip(ref: TaskReference, chatProject: String?) {
    val tokens = LocalPpmTokens.current
    val opener = LocalTaskRefOpener.current
    val shape = RoundedCornerShape(6.dp)
    val code = taskRefCode(ref, chatProject)
    val xs = ppmText(12f, lineHeight = 16f)
    Row(
        Modifier
            .heightIn(min = if (LocalPpmMobile.current) 26.dp else 28.dp)
            .widthIn(max = 288.dp)
            .clip(shape)
            .background(tokens.accentWash.color)
            .then(if (opener != null) Modifier.ppmClickable(pressedColor = tokens.accent.opacity(0.1).color) { opener.open(ref) } else Modifier)
            .cssBorder(tokens.accent.opacity(0.3).color, shape)
            .semantics { contentDescription = "Task $code · ${ref.title} · ${ref.status}. Show the task." }
            .padding(horizontal = 8.dp)
            .testTag("task-ref-${ref.taskId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.ListTodo, 14.dp, tokens.accent.color)
        BasicText(code, style = ppmText(11.5f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text3.color), maxLines = 1)
        BasicText(ref.title, Modifier.weight(1f, fill = false), xs.copy(color = tokens.text.color, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
