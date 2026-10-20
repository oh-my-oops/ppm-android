package com.hienlh.ppm.app.ui

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeShadow
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Tailwind's `shadow-md` and `shadow-lg`, by their larger layer. */
private val shadowMd = ThemeShadow(0.0, 4.0, 6.0, -1.0, Rgba(0.0, 0.0, 0.0, 0.1))
private val shadowLg = ThemeShadow(0.0, 10.0, 15.0, -3.0, Rgba(0.0, 0.0, 0.0, 0.1))

/**
 * A Radix menu's box: `min-w-[8rem] rounded-md border bg-popover p-1`, opaque as `.popover-solid`
 * makes it, as wide as its widest row. The border has no colour of its own, and Tailwind 4 gives
 * such a border the text's (`text-popover-foreground`): the web's menus are outlined in it.
 */
@Composable
fun MenuBox(modifier: Modifier = Modifier, minWidth: Dp = 128.dp, large: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    CompositionLocalProvider(LocalMenuMouse provides remember { MenuMouse() }) {
        Column(
            modifier
                .width(IntrinsicSize.Max)
                .widthIn(min = minWidth)
                .ppmShadow(if (large) shadowLg else shadowMd, shape)
                .clip(shape)
                .background(tokens.panel2.over(tokens.bgSolid).color)
                .border(1.dp, tokens.text.color, shape)
                // `p-1` inside the 1 px border, which CSS counts into the box and `border()` does not.
                .padding(5.dp),
            content = content,
        )
    }
}

/** Which row of a menu the mouse is on, so that moving onto another closes an open submenu. */
private class MenuMouse {
    var on by mutableStateOf<Any?>(null)
}

private val LocalMenuMouse = compositionLocalOf<MenuMouse?> { null }

/**
 * A menu row (`rounded-sm px-2 py-1.5 text-sm gap-2`), lit while pressed or under the mouse, as
 * Radix highlights the item it focuses. The web lights it in `bg-accent`, which is `--panel-2`,
 * the menu's own fill, so the highlight never shows; it is lit here in `surface-hover`, as PPM's
 * own popover rows are (window-dock-list.tsx). The web's icons carry `mr-2` on top of the gap
 * ([iconEnd], 0 for a menu whose icons do not); [vertical] is 12 where the web asks for
 * `max-md:py-3`; [lit] holds the highlight, as an open submenu's trigger keeps it.
 */
@Composable
fun MenuItem(
    label: String,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    color: Rgba? = null,
    vertical: Dp = 6.dp,
    trailing: (@Composable () -> Unit)? = null,
    iconEnd: Dp = 8.dp,
    lit: Boolean = false,
    source: MutableInteractionSource? = null,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    val own = remember { MutableInteractionSource() }
    val interactions = source ?: own
    val hovered by interactions.collectIsHoveredAsState()
    val mouse = LocalMenuMouse.current
    LaunchedEffect(hovered, mouse) { if (hovered) mouse?.on = interactions }
    val highlight = tokens.surfaceHover.color
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (lit) highlight else Color.Transparent)
            .ppmClickable(pressedColor = highlight, hoverColor = highlight, fade = false, shape = shape, source = interactions, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Box(Modifier.padding(end = iconEnd)) { icon() }
        }
        // With a trailing item the label takes the rest of the row, which puts the item at the end
        // (`ml-auto`). A weighted spacer instead would count the label twice in the menu's width.
        BasicText(
            label,
            Modifier.weight(1f, fill = trailing != null),
            style = ppmText(14f, lineHeight = 20f).copy(color = (color ?: tokens.text).color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/** `size-3.5` in `text-muted-foreground`, the colour a Radix item gives an icon of none. */
@Composable
fun MenuIcon(icon: ProductIcon, tint: Rgba? = null) {
    val tokens = LocalPpmTokens.current
    PpmIcon(icon, 14.dp, (tint ?: tokens.text2).color)
}

/** `-mx-1 my-1 h-px bg-border`. */
@Composable
fun MenuSeparator() {
    val tokens = LocalPpmTokens.current
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(tokens.border.color))
}

/**
 * Room around a popup's box for its shadow: a popup window is only as big as what it holds,
 * and a shadow past its edge would be cut off. The positions below place the box, not the room.
 */
private val ShadowRoom = 16.dp

/** A popup holding [content] in a [MenuBox], placed by [place] (given the box's own size). */
@Composable
private fun MenuPopup(
    place: (anchor: IntRect, window: IntSize, box: IntSize) -> IntOffset,
    onDismiss: () -> Unit,
    minWidth: Dp = 128.dp,
    large: Boolean = false,
    passThrough: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlacedPopup(place, onDismiss, passThrough) { MenuBox(minWidth = minWidth, large = large, content = content) }
}

/**
 * A popup holding [content] as it is, placed by [place] (given the content's own size) with room
 * around it for a shadow. Back or a tap anywhere else closes it, the room included; Esc is the
 * content's to handle. With [passThrough], as Radix's non-modal `Popover`, the press that closes
 * it also reaches what it landed on, where a menu's only closes the menu.
 */
@Composable
fun PlacedPopup(
    place: (anchor: IntRect, window: IntSize, box: IntSize) -> IntOffset,
    onDismiss: () -> Unit,
    passThrough: Boolean = false,
    content: @Composable () -> Unit,
) {
    val room = with(LocalDensity.current) { ShadowRoom.roundToPx() }
    val provider = remember(place, room) { RoomyPosition(room, place) }
    val under = LocalView.current.rootView
    if (closedByConnectionLost(onDismiss)) return
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = if (passThrough) PassThroughProperties else MenuProperties,
    ) {
        val taps = remember(room, under, passThrough) { RoomTaps(room, under.takeIf { passThrough }) }
        taps.onDismiss = onDismiss
        Box(
            Modifier
                .onSizeChanged { taps.size = it }
                .pointerInteropFilter(onTouchEvent = taps::onTouch)
                .padding(ShadowRoom),
        ) { content() }
    }
}

/** Unclipped, so that the room can reach past the screen's edge while the box stays on it. */
private val MenuProperties = PopupProperties(focusable = true, clippingEnabled = false)

/** [MenuProperties], but a press outside the popup's window goes on to the window under it. */
private val PassThroughProperties = PopupProperties(
    flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
)

/**
 * A press in a popup's room — inside its window, outside its box — taken as one outside: it
 * closes the popup, and given the window [under] it, the whole gesture goes on there, the popup
 * closing as it ends (closing at the press would take the window, and the rest with it).
 */
private class RoomTaps(private val room: Int, private val under: View?) {
    var size = IntSize.Zero
    var onDismiss: () -> Unit = {}
    private var forwarding = false

    fun onTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val inBox = event.x >= room && event.y >= room && event.x < size.width - room && event.y < size.height - room
            if (inBox) return false
            if (under == null) {
                onDismiss()
                return true
            }
            forwarding = true
        }
        if (!forwarding || under == null) return false
        val at = IntArray(2).also(under::getLocationOnScreen)
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(event.rawX - event.x - at[0], event.rawY - event.y - at[1])
        under.dispatchTouchEvent(copy)
        copy.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            forwarding = false
            onDismiss()
        }
        return true
    }
}

private class RoomyPosition(
    private val room: Int,
    private val place: (anchor: IntRect, window: IntSize, box: IntSize) -> IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val box = IntSize(popupContentSize.width - 2 * room, popupContentSize.height - 2 * room)
        val at = place(anchorBounds, windowSize, box)
        return IntOffset(at.x - room, at.y - room)
    }
}

private fun IntSize.fitX(x: Int, box: IntSize) = x.coerceIn(0, maxOf(0, width - box.width))

private fun IntSize.fitY(y: Int, box: IntSize) = y.coerceIn(0, maxOf(0, height - box.height))

/**
 * A context menu where the finger was held (Radix's `ContextMenu` on touch): [at] is the press
 * within the parent; it opens to the right of it and below, flipping or shifting to stay on
 * screen. A tap anywhere else closes it.
 */
@Composable
fun ContextMenuPopup(at: Offset, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val gap = with(LocalDensity.current) { 2.dp.roundToPx() }
    val place = remember(at, gap) {
        { anchor: IntRect, window: IntSize, box: IntSize ->
            val px = anchor.left + at.x.roundToInt()
            val py = anchor.top + at.y.roundToInt()
            var x = px + gap
            if (x + box.width > window.width) x = px - gap - box.width
            IntOffset(window.fitX(x, box), window.fitY(py, box))
        }
    }
    MenuPopup(place, onDismiss, content = content)
}

/** A dropdown opened above its trigger, its right edge on the trigger's (`side="top" align="end"`). */
@Composable
fun DropdownAboveEnd(onDismiss: () -> Unit, minWidth: Dp = 128.dp, content: @Composable ColumnScope.() -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val place = remember(gap) {
        { anchor: IntRect, window: IntSize, box: IntSize ->
            var y = anchor.top - gap - box.height
            if (y < 0) y = anchor.bottom + gap
            IntOffset(window.fitX(anchor.right - box.width, box), y)
        }
    }
    MenuPopup(place, onDismiss, minWidth = minWidth, content = content)
}

/**
 * A dropdown under its trigger (Radix's default `side="bottom"`, 4 px off), its left edge on the
 * trigger's; above it when the screen has no room below, as Radix flips it.
 */
@Composable
fun DropdownBelowStart(onDismiss: () -> Unit, minWidth: Dp = 128.dp, content: @Composable ColumnScope.() -> Unit) =
    DropdownBelow(alignEnd = false, onDismiss, minWidth, content)

/** The same with its right edge on the trigger's (`align="end"`). */
@Composable
fun DropdownBelowEnd(onDismiss: () -> Unit, minWidth: Dp = 128.dp, content: @Composable ColumnScope.() -> Unit) =
    DropdownBelow(alignEnd = true, onDismiss, minWidth, content)

@Composable
private fun DropdownBelow(alignEnd: Boolean, onDismiss: () -> Unit, minWidth: Dp, content: @Composable ColumnScope.() -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val place = remember(gap, alignEnd) {
        { anchor: IntRect, window: IntSize, box: IntSize ->
            var y = anchor.bottom + gap
            if (y + box.height > window.height && anchor.top - gap - box.height >= 0) y = anchor.top - gap - box.height
            IntOffset(window.fitX(if (alignEnd) anchor.right - box.width else anchor.left, box), window.fitY(y, box))
        }
    }
    MenuPopup(place, onDismiss, minWidth = minWidth, content = content)
}

/**
 * A row that opens a submenu beside it, as Radix's `SubTrigger` does — on a tap, which never
 * closes it, or once the mouse has rested on it 100 ms — to the right, or to the left when the
 * screen has no room there. It stays lit while open (`data-[state=open]:bg-accent`), and the
 * mouse moving onto another row of its menu closes the submenu, as Radix's focus leaving it does.
 */
@Composable
fun SubMenuItem(label: String, icon: (@Composable () -> Unit)?, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val tokens = LocalPpmTokens.current
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val mouse = LocalMenuMouse.current
    LaunchedEffect(hovered) {
        if (!hovered) return@LaunchedEffect
        delay(100)
        open = true
    }
    LaunchedEffect(mouse?.on) { if (mouse?.on != null && mouse.on !== source) open = false }
    Box {
        // A `SubTrigger` has no `gap-2`: the label follows the icon's `mr-2` alone.
        MenuItem(label, onClick = { open = true }, icon = icon, iconEnd = 0.dp, trailing = { PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text2.color) }, lit = open, source = source)
        if (open) {
            val place = remember {
                { anchor: IntRect, window: IntSize, box: IntSize ->
                    var x = anchor.right
                    if (x + box.width > window.width) x = anchor.left - box.width
                    IntOffset(window.fitX(x, box), window.fitY(anchor.top, box))
                }
            }
            // Passing through to the menu under it, which a mouse moving back onto it has to reach,
            // and so does the tap on one of its rows that closes this as well.
            MenuPopup(place, onDismiss = { open = false }, large = true, passThrough = true) { content { open = false } }
        }
    }
}

/** "Set Tag": the project's tags with the session's ticked, and Remove tag while it has one. */
@Composable
fun SetTagMenuItem(model: AppModel, session: SessionInfo, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    SubMenuItem("Set Tag", icon = { MenuIcon(ProductIcon.Tag) }) { close ->
        fun pick(tag: SessionTag?) {
            close()
            onDismiss()
            model.scope.launch { model.setTag(tag, session) }
        }
        for (tag in model.tags.tags) {
            MenuItem(
                tag.name,
                onClick = { pick(tag) },
                icon = { TagDot(tag.color, 10.dp) },
                trailing = if (session.tag?.id == tag.id) ({ PpmIcon(ProductIcon.Check, 12.dp, tokens.text.color) }) else null,
            )
        }
        if (session.tag != null) {
            MenuSeparator()
            MenuItem("Remove tag", onClick = { pick(null) })
        }
    }
}

/**
 * A chat's menu (`session-context-menu.tsx`): pin, mark unread, rename, its tag, delete. A list
 * passes [onRename] and [onDelete] only where the web's does: the home list has neither.
 */
@Composable
fun SessionContextMenu(
    model: AppModel,
    session: SessionInfo,
    at: Offset,
    onDismiss: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val unread = model.unreadSessions()[session.id] != null
    fun run(action: suspend () -> Unit) {
        onDismiss()
        model.scope.launch { action() }
    }
    ContextMenuPopup(at, onDismiss) {
        MenuItem(
            if (session.pinned) "Unpin" else "Pin",
            onClick = { run { model.togglePin(session) } },
            icon = { MenuIcon(if (session.pinned) ProductIcon.PinOff else ProductIcon.Pin) },
        )
        if (!unread) {
            MenuItem("Mark as unread", onClick = {
                onDismiss()
                model.markUnread(session)
            }, icon = { MenuIcon(ProductIcon.Circle, tokens.accent) })
        }
        if (onRename != null) {
            MenuItem("Rename", onClick = {
                onDismiss()
                onRename()
            }, icon = { MenuIcon(ProductIcon.Pencil) })
        }
        if (model.tags.tags.isNotEmpty()) SetTagMenuItem(model, session, onDismiss)
        if (onDelete != null) {
            MenuSeparator()
            MenuItem("Delete", onClick = {
                onDismiss()
                onDelete()
            }, icon = { MenuIcon(ProductIcon.Trash2) }, color = tokens.error)
        }
    }
}
