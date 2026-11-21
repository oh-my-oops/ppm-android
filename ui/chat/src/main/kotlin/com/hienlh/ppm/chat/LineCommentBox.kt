package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover

private val xs = ppmText(12f, lineHeight = 16f)
private val xsMedium = ppmText(12f, FontWeight.Medium, lineHeight = 16f)

/**
 * The comment under a line (`line-comment-box.tsx`): a box to write it in, or the saved text with
 * Edit and Delete. Shared by the Review tab's diff and the plan card. A keyboard's Enter saves,
 * Shift+Enter breaks the line, Escape cancels.
 */
@Composable
fun LineCommentBox(
    body: String,
    editing: Boolean,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    if (!editing) {
        // `flex items-start gap-2 rounded-md border border-border-soft bg-panel px-2.5 py-1.5 text-xs`.
        Row(
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Line comment" }
                .background(tokens.panel.color, shape)
                .cssBorder(tokens.borderSoft.color, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.MessageCircle, 14.dp, tokens.accent.color, Modifier.padding(top = 2.dp))
            BasicText(body, Modifier.weight(1f), xs.copy(color = tokens.text.color))
            CommentIconButton(ProductIcon.Pencil, "Edit comment", tokens.text.color, onEdit)
            CommentIconButton(ProductIcon.Trash2, "Delete comment", tokens.error.color, onDelete)
        }
        return
    }
    var draft by rememberSaveable { mutableStateOf(body) }
    // `space-y-1.5 rounded-md border border-primary/50 bg-panel p-2`.
    Column(
        modifier
            .fillMaxWidth()
            .background(tokens.panel.color, shape)
            .cssBorder(tokens.accent.opacity(0.5).color, shape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CardTextArea(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "Comment on this line",
            label = "Comment on this line",
            onEnter = { onSave(draft) },
            onEscape = onCancel,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CommentButton("Comment", filled = true, enabled = draft.isNotBlank()) { onSave(draft) }
            CommentButton("Cancel", filled = false, onClick = onCancel)
        }
    }
}

/** `grid size-6 place-items-center rounded text-text-3 hover:bg-surface-hover hover:text-…`, a 14 px icon. */
@Composable
private fun CommentIconButton(icon: ProductIcon, label: String, hoverInk: Color, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(24.dp)
            .semantics { contentDescription = label }
            .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 14.dp, if (hover.lit > 0.5f) hoverInk else tokens.text3.color)
    }
}

/**
 * `inline-flex h-7 items-center rounded-md px-2.5 text-xs font-medium` (26 px on a phone): the
 * accent fill for [filled], else `border border-border text-text-2`.
 */
@Composable
internal fun CommentButton(label: String, filled: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    val hover = rememberHover(enabled = enabled)
    Box(
        modifier
            .height(if (LocalPpmMobile.current) 26.dp else 28.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (filled) tokens.accent.opacity(1 - 0.1 * hover.lit).color else Color.Transparent)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, shape = shape, onClickLabel = label, source = hover.source, onClick = onClick)
            .then(if (filled) Modifier else Modifier.cssBorder(tokens.border.color, shape))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (filled) tokens.accentFg else tokens.text2.mix(tokens.text, hover.lit.toDouble())
        BasicText(label, style = xsMedium.copy(color = ink.color), maxLines = 1, softWrap = false)
    }
}

/**
 * A card's `<textarea rows={2}>` (`w-full resize-y rounded border border-border bg-background p-2
 * text-xs`, the accent edge while focused), focused as it appears as the web's `autoFocus` does. A
 * keyboard's Enter is [onEnter] and Escape [onEscape]; Shift+Enter, and a phone keyboard's Enter,
 * break the line.
 */
@Composable
internal fun CardTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    onEnter: (() -> Unit)? = null,
    onEscape: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    val shape = RoundedCornerShape(4.dp)
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    BasicTextField(
        value,
        onValueChange,
        modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics { contentDescription = label }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Enter && !event.isShiftPressed && onEnter != null -> { onEnter(); true }
                    event.key == Key.Escape && onEscape != null -> { onEscape(); true }
                    else -> false
                }
            }
            .background(tokens.bgSolid.color, shape)
            .cssBorder((if (focused) tokens.accent else tokens.border).color, shape),
        textStyle = xs.copy(color = tokens.text.color),
        cursorBrush = SolidColor(tokens.accent.color),
        minLines = 2,
        interactionSource = source,
        decorationBox = { field ->
            Box(Modifier.fillMaxWidth().padding(8.dp)) {
                if (value.isEmpty()) BasicText(placeholder, style = xs.copy(color = tokens.text2.color), maxLines = 1)
                field()
            }
        },
    )
}
