package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CursorText
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.TextCursorRegion
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderInset
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssScrollX
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.ChatQuestions
import com.hienlh.ppm.protocol.Question
import com.hienlh.ppm.protocol.SessionCollabApprovalInput
import com.hienlh.ppm.protocol.prettyString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the AI is waiting on (`message-approval-cards.tsx`): a question form for `AskUserQuestion`,
 * a database change or a session tool's card, which are answered over HTTP and never through
 * [respond], else Allow/Deny.
 */
@Composable
fun PendingApprovalCard(request: ApprovalRequest, respond: (approved: Boolean, data: JsonElement?) -> Unit, modifier: Modifier = Modifier) {
    val questions = request.questions
    if (request.tool == "AskUserQuestion" && questions.isNotEmpty()) {
        QuestionCard(
            questions,
            onSubmit = { answers -> respond(true, JsonObject(answers.mapValues { JsonPrimitive(it.value) })) },
            onSkip = { respond(false, null) },
            modifier = modifier,
            previews = remember(request.input) { ChatQuestions.optionPreviews(request.input) },
        )
        return
    }
    val db = remember(request.tool, request.input) { DbExecuteApproval.from(request.tool, request.input) }
    if (db != null) return DbExecuteApprovalCard(request.requestId, db, modifier)
    val collab = remember(request.tool, request.input) { SessionCollabApprovalInput.from(request.tool, request.input) }
    if (collab != null) return CollabApprovalCard(request.requestId, collab, modifier)
    ToolApprovalCard(request, respond, modifier)
}

private val xs = ppmText(12f, lineHeight = 16f)
private val xsMedium = ppmText(12f, FontWeight.Medium, lineHeight = 16f)

/** `ApprovalCard` in message-list.tsx. */
@Composable
private fun ToolApprovalCard(request: ApprovalRequest, respond: (Boolean, JsonElement?) -> Unit, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(tokens.warning.opacity(0.1).color, shape)
            .cssBorder(tokens.warning.opacity(0.4).color, shape, width = 2.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.ShieldAlert, 16.dp, tokens.warning.color)
            BasicText("Tool Approval Required", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.warning.color))
        }
        BasicText(request.tool, style = xsMedium.copy(color = tokens.text.color))
        val pre = RoundedCornerShape(4.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .background(tokens.bgSolid.color, pre)
                .cssBorder(tokens.border.color, pre)
                .cssScrollX(),
        ) {
            SelectionContainer {
                BasicText(
                    request.input.prettyString,
                    Modifier.padding(8.dp),
                    ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
                    softWrap = false,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SolidButton("Allow", tokens.success) { respond(true, null) }
            SolidButton("Deny", tokens.error) { respond(false, null) }
        }
    }
}

/**
 * `px-4 py-1.5 rounded text-xs font-medium text-white`, `hover:bg-…/80`, a shade lighter while
 * pressed. Its label stays centred when a row stretches it ([modifier]), as a `<button>`'s does.
 */
@Composable
private fun SolidButton(
    title: String,
    color: Rgba,
    modifier: Modifier = Modifier,
    foreground: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    // CSS `:hover` matches it disabled too: the web gives it no `disabled:hover:`.
    val hover = rememberHover()
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(color.opacity(1 - 0.2 * hover.lit).color)
            // Submit's `disabled:cursor-not-allowed`.
            .pointerHoverIcon(if (enabled) WebCursor.Default else WebCursor.NotAllowed)
            .ppmClickable(enabled = enabled, pressedColor = Color.White.copy(alpha = 0.2f), source = hover.source, hoverWhileDisabled = true, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(title, style = xsMedium.copy(color = foreground)) }
}

/** `px-3 py-1.5 text-xs rounded border border-border bg-background hover:bg-surface-elevated`. */
@Composable
private fun OutlineButton(
    title: String,
    foreground: Rgba,
    modifier: Modifier = Modifier,
    horizontalPadding: Int = 12,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(tokens.bgSolid.color)
            // Prev's and Next's `disabled:cursor-not-allowed`.
            .pointerHoverIcon(if (enabled) WebCursor.Default else WebCursor.NotAllowed)
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, hoverWhileDisabled = true, onClick = onClick)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = horizontalPadding.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(title, style = xs.copy(color = foreground.color)) }
}

/**
 * The AI's questions (`question-card.tsx`): tabs when there are several, numbered options and
 * an "Other" answer. The answers go back as the web sends them, keyed by [Question.key]: the
 * typed one if any, else the picked labels joined by ", ". [autoFocus] off leaves the keyboard
 * where it is, as a card that does not hold the turn up must.
 */
@Composable
internal fun QuestionCard(
    questions: List<Question>,
    onSubmit: (Map<String, String>) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    /** Each question's option previews ([ChatQuestions.optionPreviews]); none for a Codex question. */
    previews: List<List<String?>> = emptyList(),
) {
    val tokens = LocalPpmTokens.current
    var active by remember { mutableIntStateOf(0) }
    // Picked labels per question, in the order they were picked.
    val picked = remember { mutableStateMapOf<Int, List<String>>() }
    val custom = remember { mutableStateMapOf<Int, String>() }
    val multiple = questions.size > 1
    val current = questions[active.coerceAtMost(questions.lastIndex)]

    fun hasAnswer(index: Int) = !picked[index].isNullOrEmpty() || !custom[index].isNullOrBlank()
    val answered = questions.indices.count(::hasAnswer)
    val allAnswered = answered == questions.size

    fun select(option: Question.Option) {
        if (current.multiSelect) {
            val list = picked[active].orEmpty()
            picked[active] = if (option.label in list) list - option.label else list + option.label
        } else {
            picked[active] = listOf(option.label)
            custom[active] = ""
        }
    }

    fun submit() {
        if (!allAnswered) return
        val answers = questions.indices.associate { index ->
            val typed = custom[index].orEmpty().trim()
            questions[index].key to typed.ifEmpty { picked[index].orEmpty().joinToString(", ") }
        }
        onSubmit(answers)
    }

    // The keyboard's place among the options, "Other" last, ringed (`useQuestionKeyboard`): the
    // first option of each question to begin with, then the one last picked, typed or moved to.
    var focused by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) { focused = 0 }
    val other = current.options.size
    val card = remember { FocusRequester() }
    val otherField = remember { FocusRequester() }
    val otherSource = remember { MutableInteractionSource() }
    val typing by otherSource.collectIsFocusedAsState()
    LaunchedEffect(typing) { if (typing) focused = other }
    // The web focuses the card as it appears, so its keys work at once.
    LaunchedEffect(Unit) { if (autoFocus) runCatching { card.requestFocus() } }

    fun pick(index: Int) {
        if (index < other) select(current.options[index]) else if (index == other) otherField.requestFocus()
    }

    fun next() {
        active = (active + 1).coerceAtMost(questions.lastIndex)
    }

    fun previous() {
        active = (active - 1).coerceAtLeast(0)
    }

    fun onEnter() {
        if (allAnswered) submit() else if (hasAnswer(active)) next()
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val key = event.key
        val digit = digitOf(key)
        when {
            !typing && digit >= 1 -> if (digit - 1 < other) {
                focused = digit - 1
                pick(digit - 1)
            }
            !typing && (key == Key.O || digit == 0) -> {
                otherField.requestFocus()
                focused = other
            }
            key == Key.Tab && multiple -> if (event.isShiftPressed) previous() else next()
            !typing && key == Key.DirectionLeft -> previous()
            !typing && key == Key.DirectionRight -> next()
            !typing && key == Key.DirectionUp -> focused = (focused - 1).coerceAtLeast(0)
            !typing && key == Key.DirectionDown -> focused = (focused + 1).coerceAtMost(other)
            !typing && key == Key.Spacebar -> pick(focused)
            key == Key.Enter || key == Key.NumPadEnter -> onEnter()
            key == Key.Escape && typing -> card.requestFocus()
            else -> return false
        }
        return true
    }

    val shape = RoundedCornerShape(12.dp)
    val line = with(LocalDensity.current) { cssBorderPx(1.dp).toDp() }
    // `p-3 space-y-3`, stacked as Chrome stacks it: at 2.625x a 12 px gap is 31.5 px, and the
    // card's gaps, paddings and lines rounded one by one made it 7 px taller than the web's.
    CssColumn(
        modifier
            .fillMaxWidth()
            .focusRequester(card)
            .onPreviewKeyEvent(::onKey)
            .focusable()
            .background(tokens.accent.opacity(0.05).color, shape)
            .cssBorder(tokens.accent.opacity(0.3).color, shape, width = 2.dp)
            .padding(horizontal = 12.dp),
        gap = 12.dp,
        top = 12.dp,
        bottom = 12.dp,
    ) {
        Row(Modifier.cssLines(20f), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                if (multiple) "AI has ${questions.size} questions" else "AI has a question",
                Modifier.weight(1f),
                ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color),
            )
            BasicText(
                "${if (multiple) "←→ tabs · " else ""}↑↓ options · 1-${minOf(current.options.size, 9)} select · Enter submit",
                // `text-[10px]` keeps the header's 20 px line.
                style = ppmText(10f, lineHeight = 20f).copy(color = tokens.text2.color),
            )
        }
        if (multiple) {
            val tabsShape = RoundedCornerShape(8.dp)
            // `p-1` inside the border, kept at 4 px (10.5 at 2.625x) by a column of the strip's own,
            // around a track as tall as a tab, `py-1.5` and a 16 px line; each tab fills it.
            val strip = rememberCssExact()
            val tabs = rememberScrollState()
            Box(Modifier.cssExact(strip).fillMaxWidth().background(tokens.bgSolid.color, tabsShape).cssBorder(tokens.border.color, tabsShape)) {
                // The bordered box is the web's scroller, its `p-1` included: the thumb runs along its bottom.
                CssColumn(Modifier.chromeScrollbar(tabs, vertical = false, tokens.border.color).padding(horizontal = 4.dp), top = 4.dp, bottom = 4.dp, exact = strip) {
                    Row(
                        Modifier.cssHeight(28.dp).fillMaxWidth().horizontalScroll(tabs),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        questions.forEachIndexed { index, question ->
                            val isActive = index == active
                            val done = hasAnswer(index)
                            val foreground = if (isActive) tokens.accentFg else if (done) tokens.accent else tokens.text2
                            Row(
                                Modifier
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isActive) tokens.accent.color else Color.Transparent)
                                    // Only a tab neither open nor answered has `hover:bg-surface-elevated`.
                                    .ppmClickable(
                                        pressedColor = tokens.surfaceHover.color,
                                        hoverColor = if (isActive || done) Color.Transparent else tokens.panel2.color,
                                    ) { active = index }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val badge = when {
                                    isActive -> Color.White.copy(alpha = 0.2f)
                                    done -> tokens.accent.opacity(0.2).color
                                    else -> tokens.panel2.color
                                }
                                Box(Modifier.size(16.dp).background(badge, CircleShape), contentAlignment = Alignment.Center) {
                                    // On the tab's `text-xs` line, which fills the 16 px badge.
                                    BasicText(if (done) "✓" else "${index + 1}", style = ppmText(10f, FontWeight.SemiBold, lineHeight = 16f).copy(color = foreground.color))
                                }
                                BasicText(
                                    question.header ?: "Q${index + 1}",
                                    Modifier.widthIn(max = 100.dp),
                                    xs.copy(color = foreground.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
        // `space-y-2`. Its 11 px lines are 1.5 apart, the line the card inherits (16 px on 24).
        CssColumn(gap = 8.dp) {
            val header = current.header
            if (!multiple && header != null) {
                BasicText(
                    header.uppercase(),
                    Modifier.cssLines(16.5f),
                    ppmText(11f, FontWeight.SemiBold, lineHeight = 16.5f).copy(color = tokens.text2.color, letterSpacing = 0.275.sp),
                )
            }
            SelectionContainer(Modifier.cssLines(20f)) {
                // `select-text`: the I-beam over the question's glyphs.
                TextCursorRegion {
                    CursorText(current.question, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color))
                }
            }
            if (current.multiSelect) BasicText("Select multiple", Modifier.cssLines(16.5f), ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text2.color))
            val optionList: @Composable (Modifier) -> Unit = { optionsModifier -> CssColumn(optionsModifier, gap = 6.dp) {
                current.options.forEachIndexed { index, option ->
                    val selected = option.label in picked[active].orEmpty()
                    val optionShape = RoundedCornerShape(4.dp)
                    // Not picked: `hover:border-primary/40 hover:bg-primary/5`.
                    val hover = rememberHover(enabled = !selected)
                    val lit = hover.lit.toDouble()
                    val fill = if (selected) tokens.accent.opacity(0.1) else tokens.accent.opacity(0.05).mix(tokens.bgSolid, lit)
                    val edge = if (selected) tokens.accent else tokens.accent.opacity(0.4).mix(tokens.border, lit)
                    // The row is as tall as its text, which its column counts exactly.
                    val text = rememberCssExact()
                    Row(
                        Modifier
                            .cssExact(text)
                            .fillMaxWidth()
                            .focusRing(focused == index)
                            .clip(optionShape)
                            .background(fill.color)
                            // A `div role="button"` with `cursor-pointer`.
                            .pointerHoverIcon(WebCursor.Pointer)
                            .ppmClickable(pressedColor = tokens.surfaceHover.color, source = hover.source) {
                                select(option)
                                focused = index
                            }
                            .cssBorder(edge.color, optionShape)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NumberSquare("${index + 1}", selected)
                        // `flex flex-col gap-0.5`, the description's 11 px on the row's `text-xs` ratio.
                        CssColumn(Modifier.weight(1f), gap = 2.dp, exact = text) {
                            BasicText(option.label, Modifier.cssLines(16f), xsMedium.copy(color = tokens.text.color))
                            option.description?.let {
                                BasicText(it, Modifier.cssLines(44f / 3), ppmText(11f, lineHeight = 44f / 3).copy(color = tokens.text2.color))
                            }
                        }
                    }
                }
                OtherRow(
                    ringed = focused == other,
                    source = otherSource,
                    focusRequester = otherField,
                    value = custom[active].orEmpty(),
                    onValueChange = { value ->
                        custom[active] = value
                        if (value.isNotEmpty()) picked[active] = emptyList()
                    },
                    onDone = ::onEnter,
                )
            } }
            // The preview Claude attached to the options, for the focused one: beside them on a
            // wide card (`@2xl`, the card's own width) and below them on a narrow one.
            val optionPreviews = previews.getOrNull(active).orEmpty()
            if (!ChatQuestions.hasOptionPreviews(optionPreviews)) {
                optionList(Modifier)
            } else {
                val shown = ChatQuestions.previewOptionIndex(current.options, focused, picked[active].orEmpty())
                val option = current.options.getOrNull(shown)
                val preview = optionPreviews.getOrNull(shown)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 672.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            optionList(Modifier.fillMaxWidth(0.4f))
                            QuestionOptionPreview(option, preview, wide = true, modifier = Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            optionList(Modifier)
                            QuestionOptionPreview(option, preview, wide = false)
                        }
                    }
                }
            }
        }
        // `pt-1` over a flex row, which stretches its buttons: Submit, with no border, as tall as
        // Skip, `py-1.5` around a 16 px line inside a 1 px border.
        CssColumn(Modifier.fillMaxWidth(), top = 4.dp) {
            Row(Modifier.cssHeight(28.dp + line * 2).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val stretch = Modifier.fillMaxHeight()
                Spacer(Modifier.weight(1f))
                if (multiple) {
                    OutlineButton("← Prev", tokens.text, stretch, enabled = active > 0, onClick = ::previous)
                    OutlineButton("Next →", tokens.text, stretch, enabled = active < questions.lastIndex, onClick = ::next)
                }
                OutlineButton("Skip", tokens.text2, stretch, horizontalPadding = 16, onClick = onSkip)
                SolidButton(
                    if (allAnswered) "Submit ✓" else "Submit ($answered/${questions.size})",
                    tokens.accent,
                    stretch,
                    foreground = tokens.accentFg.color,
                    enabled = allAnswered,
                    onClick = ::submit,
                )
            }
        }
    }
}

/**
 * The text Claude attached to an option (`QuestionOptionPreview`): monospace with its whitespace
 * kept, since previews are mostly mockups, code and step lists, still wrapping; capped at a third
 * of the window on a narrow card (35dvh) and 60% beside the options, scrolling inside.
 */
@Composable
private fun QuestionOptionPreview(option: Question.Option?, preview: String?, wide: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val text = preview?.trim()?.takeIf { it.isNotEmpty() }
    val shape = RoundedCornerShape(4.dp)
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(modifier.fillMaxWidth().background(tokens.bgSolid.color, shape).cssBorder(tokens.border.color, shape)) {
        BasicText(
            ("Preview" + (option?.let { " · ${it.label}" } ?: "")).uppercase(),
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            ppmText(10f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text2.color, letterSpacing = 0.25.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
        if (text != null) {
            Box(Modifier.fillMaxWidth().heightIn(max = window * (if (wide) 0.6f else 0.35f)).cssScrollY()) {
                SelectionContainer {
                    BasicText(
                        text,
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        ppmText(11f, kind = FontKind.MONO, lineHeight = 11f * 1.625f).copy(color = tokens.text.color),
                    )
                }
            }
        } else {
            BasicText(
                if (option != null) "No preview for this option." else "Choose an option to see its preview.",
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                ppmText(11f, lineHeight = 44f / 3).copy(color = tokens.text3.color),
            )
        }
    }
}

/** The 18px numbered square in front of an option. */
@Composable
private fun NumberSquare(text: String, selected: Boolean) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .padding(top = 1.dp)
            .size(18.dp)
            .background((if (selected) tokens.accent.opacity(0.2) else tokens.panel2).color, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // On the option's `text-xs` line, 16 px, centred in the square as its flex box centres it.
        BasicText(text, style = ppmText(10f, FontWeight.SemiBold, lineHeight = 16f).copy(color = (if (selected) tokens.accent else tokens.text2).color))
    }
}

/**
 * The "Other" answer: a dashed row with a field to type into, [ringed] where the keyboard is. The
 * field is `text-xs`, and 16 px on a phone (`globals.css`) with that size's line ratio.
 */
@Composable
private fun OtherRow(
    ringed: Boolean,
    source: MutableInteractionSource,
    focusRequester: FocusRequester,
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val focused by source.collectIsFocusedAsState()
    val text = if (LocalPpmMobile.current) ppmText(16f, lineHeight = 64f / 3) else xs
    val dash = tokens.border.color
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(ringed)
            .drawBehind {
                val line = cssBorderPx(1.dp).toFloat()
                drawRoundRect(
                    dash,
                    topLeft = Offset(line / 2, line / 2),
                    size = Size(size.width - line, size.height - line),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                )
            }
            .cssBorderInset(1.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NumberSquare("O", selected = false)
        val fieldShape = RoundedCornerShape(4.dp)
        BasicTextField(
            value,
            onValueChange,
            Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .background(tokens.panel.color, fieldShape)
                .cssBorder((if (focused) tokens.accent else tokens.border).color, fieldShape)
                .padding(horizontal = 8.dp),
            textStyle = text.copy(color = tokens.text.color),
            cursorBrush = SolidColor(tokens.accent.color),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            singleLine = true,
            interactionSource = source,
            decorationBox = { field ->
                // `py-1`: 10.5 px at 2.625x above and below, which padding would round to 11 each.
                CssColumn(top = 4.dp, bottom = 4.dp) {
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) BasicText("Other (press O to type)...", style = text.copy(color = tokens.text3.color))
                        field()
                    }
                }
            },
        )
    }
}

/**
 * `ring-2 ring-primary/40 ring-offset-1 ring-offset-background` around a `rounded` box, when [on]:
 * a pixel of the background, then two of the ring, both outside the box, their corners rounded
 * by how far out they sit, as a box-shadow's spread rounds them.
 */
@Composable
private fun Modifier.focusRing(on: Boolean): Modifier {
    if (!on) return this
    val tokens = LocalPpmTokens.current
    val ring = tokens.accent.opacity(0.4).color
    val gap = tokens.bgSolid.color
    return drawBehind {
        val px = 1.dp.toPx()
        val radius = 4.dp.toPx()
        drawRoundRect(ring, Offset(-2 * px, -2 * px), Size(size.width + 4 * px, size.height + 4 * px), CornerRadius(radius + 2 * px), style = Stroke(2 * px))
        drawRoundRect(gap, Offset(-px / 2, -px / 2), Size(size.width + px, size.height + px), CornerRadius(radius + px / 2), style = Stroke(px))
    }
}

private val DIGITS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val NUMPAD_DIGITS = listOf(
    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
    Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
)

/** The digit [key] types, on the main row or the keypad; -1 for any other key. */
private fun digitOf(key: Key): Int = DIGITS.indexOf(key).takeIf { it >= 0 } ?: NUMPAD_DIGITS.indexOf(key)

