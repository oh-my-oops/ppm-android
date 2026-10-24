package com.hienlh.ppm.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntRect
import com.hienlh.ppm.app.R
import kotlin.math.max
import kotlin.math.min

/**
 * The list Chrome opens for a `<select>` on a tablet (SelectPopupDropdown), built as Chrome builds
 * it — DropdownPopupWindowImpl over AnchoredPopupWindow — out of the same platform widgets, so the
 * window with its enter and exit transitions, Chrome's own `popup_bg_24dp` nine-patch at 2 dp
 * elevation, the ListView's scrolling and the TextView rows are the ones Chrome shows. The card is
 * as wide as [anchor] (the select's bounds in the window), wider only for a longer label, and its
 * edge sits on the select's bottom or top edge, whichever side has more room; 50 dp rows with a
 * 1 px divider above all but the first, 16 sp labels 10 dp in. A row picks its index; a touch
 * outside closes it and still reaches whatever is under it, as Chrome's does.
 */
@Composable
internal fun ChromeSelectDropdown(anchor: IntRect, labels: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    val pick by rememberUpdatedState(onPick)
    val dismiss by rememberUpdatedState(onDismiss)
    val dropdown = remember(view, labels, selected, dark) { ChromeDropdown(view, anchor, labels, selected, dark, { pick(it) }, { dismiss() }) }
    DisposableEffect(dropdown) {
        dropdown.show()
        onDispose { dropdown.close() }
    }
    // Chrome lays the list out again whenever the select moves or changes size under it.
    SideEffect { dropdown.place(anchor) }
}

/**
 * Whether Chrome opens its dropdown rather than its dialog for a `<select>` (SelectPopup): on a
 * tablet — the smallest width 600 dp or more — unless TalkBack's touch exploration is on.
 */
internal fun opensChromeDropdown(context: Context): Boolean {
    val touchExploration = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    return context.resources.configuration.smallestScreenWidthDp >= 600 && !touchExploration
}

/**
 * PopupSpecCalculator's choice of side for Chrome's dropdown (no margin, never over the select):
 * below when its ideal height fits there and there is at least as much room as above, or when it
 * does not fit above; and the room on that side, which caps the list's height.
 */
internal fun chromeDropdownSide(anchorTop: Int, anchorBottom: Int, freeTop: Int, freeBottom: Int, paddingY: Int, idealHeight: Int): Pair<Boolean, Int> {
    val above = anchorTop - freeTop - paddingY
    val below = freeBottom - anchorBottom - paddingY
    val isBelow = (idealHeight <= below && below >= above) || idealHeight > above
    return isBelow to if (isBelow) below else above
}

/** AnchoredPopupWindowUtils.getPopupX, centred: over the select's middle, kept inside the free area. */
internal fun chromeDropdownX(anchorLeft: Int, anchorWidth: Int, popupWidth: Int, freeRight: Int): Int {
    val x = anchorLeft + (anchorWidth - popupWidth) / 2
    val limit = freeRight - popupWidth
    return x.coerceIn(min(0, limit), max(0, limit))
}

private class ChromeDropdown(
    host: View,
    private var anchor: IntRect,
    labels: List<String>,
    private val selected: Int,
    dark: Boolean,
    onPick: (Int) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val context = host.context
    private val root = host.rootView
    private val density = context.resources.displayMetrics.density

    // menu_bg_baseline: the nine-patch tinted, multiply, by menu_item_bg_color. Not named
    // `background`, which inside PopupWindow.apply would be the window's own default one.
    private val card = context.getDrawable(R.drawable.chrome_popup_bg)!!.mutate().apply {
        setTint(if (dark) 0xFF37393D.toInt() else 0xFFFFFFFF.toInt())
        setTintMode(PorterDuff.Mode.MULTIPLY)
    }
    private val padding = Rect().also { card.getPadding(it) }
    private val rows = Rows(labels, if (dark) 0xFFE3E3E3.toInt() else 0xFF1F1F1F.toInt())
    private val list = ListView(context).apply {
        divider = null
        dividerHeight = 0
        adapter = rows
        setOnItemClickListener { _, _, position, _ ->
            onPick(position)
            popup.dismiss()
        }
    }
    private val popup = PopupWindow(context).apply {
        width = ViewGroup.LayoutParams.WRAP_CONTENT
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        setBackgroundDrawable(card)
        elevation = px(2f).toFloat()
        isOutsideTouchable = true
        setOnDismissListener { onDismiss() }
    }

    private class Spec(val x: Int, val y: Int, val width: Int, val height: Int)

    fun show() {
        val spec = spec() ?: return onDismiss()
        popup.width = spec.width
        popup.height = spec.height
        popup.contentView = list
        popup.showAtLocation(root, Gravity.TOP or Gravity.START, spec.x, spec.y)
        if (selected >= 0) list.setSelection(selected)
    }

    fun place(anchor: IntRect) {
        if (anchor == this.anchor) return
        this.anchor = anchor
        if (!popup.isShowing) return
        val spec = spec() ?: return popup.dismiss()
        popup.update(spec.x, spec.y, spec.width, spec.height)
    }

    fun close() {
        popup.setOnDismissListener(null)
        popup.dismiss()
    }

    /** Resources.getDimensionPixelSize for a dp value. */
    private fun px(dp: Float) = max(1, (dp * density + 0.5f).toInt())

    private fun spec(): Spec? {
        val paddingX = padding.left + padding.right
        val paddingY = padding.top + padding.bottom
        // DropdownPopupWindowImpl.show: as wide as the select, or as the widest label when wider.
        val content = contentWidth()
        val windowWidth = context.resources.displayMetrics.widthPixels
        val maxWidth = when {
            windowWidth < content + paddingX -> windowWidth - paddingX
            anchor.width < content -> content + paddingX
            else -> anchor.width + paddingX
        }
        // ViewRectUpdater: the select's bounds with its top moved down by the bottom padding and its
        // bottom up by the top one, so the card's own edge, not its shadow's, meets the select's.
        val left = max(anchor.left, 0)
        val top = max(anchor.top, 0)
        val rect = Rect(left, top + padding.bottom, left + anchor.width, top + anchor.height - padding.top)
        rect.right = min(max(rect.left, rect.right), root.width)
        rect.bottom = min(max(rect.top, rect.bottom), root.height)
        // RootViewRectProvider: the visible display frame, in the root view's coordinates.
        val free = Rect().also { root.getWindowVisibleDisplayFrame(it) }
        val at = IntArray(2).also { root.getLocationOnScreen(it) }
        free.offset(-at[0], -at[1])
        // PopupSpecCalculator. Measured with no height, a ListView reports its first row alone,
        // and that is the ideal height Chrome compares with the room on each side.
        val maxContentWidth = (if (maxWidth != 0 && maxWidth < root.width) maxWidth else root.width).let { if (it > paddingX) it - paddingX else 0 }
        val widthSpec = MeasureSpec.makeMeasureSpec(maxContentWidth, MeasureSpec.AT_MOST)
        list.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val (below, maxHeight) = chromeDropdownSide(rect.top, rect.bottom, free.top, free.bottom, paddingY, list.measuredHeight)
        list.measure(widthSpec, MeasureSpec.makeMeasureSpec(max(0, maxHeight), MeasureSpec.AT_MOST))
        val width = list.measuredWidth + paddingX
        val height = list.measuredHeight + paddingY
        // AnchoredPopupWindow.hasMinimalSize: smaller than 50 dp either way, it is not shown.
        if (height < density * 50 || width < density * 50) return null
        val x = chromeDropdownX(rect.left, rect.width(), width, free.right)
        val y = if (below) rect.bottom else rect.top - height
        return Spec(x, y, width, height)
    }

    /** UiUtils.computeListAdapterContentDimensions: the widest row, measured unconstrained. */
    private fun contentWidth(): Int {
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var widest = 0
        var row: View? = null
        for (position in 0 until rows.count) {
            row = rows.getView(position, row, list)
            row.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            row.measure(unspecified, unspecified)
            widest = max(widest, row.measuredWidth)
        }
        return widest
    }

    /** DropdownAdapter with dropdown_item.xml: a label in a wrapper as tall as the row, the divider its background. */
    private inner class Rows(private val labels: List<String>, private val textColor: Int) : BaseAdapter() {
        override fun getCount() = labels.size

        override fun getItem(position: Int) = labels[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val layout = convertView as? LinearLayout ?: newRow()
            val divider = layout.background as DividerDrawable
            var height = px(50f)
            if (position == 0) {
                divider.setDividerColor(0)
            } else {
                // dropdown_item_divider_height is 1px, not 1dp.
                height += 1
                divider.setHeight(1)
                divider.setDividerColor(0xFFABABAB.toInt())
            }
            val wrapper = layout.getChildAt(0) as LinearLayout
            wrapper.layoutParams = LinearLayout.LayoutParams(0, height, 1f)
            val label = wrapper.getChildAt(0) as TextView
            label.text = labels[position]
            label.setSingleLine(true)
            label.setTypeface(null, Typeface.NORMAL)
            label.setTextColor(textColor)
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics))
            return layout
        }

        private fun newRow(): LinearLayout {
            val label = TextView(context).apply {
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = px(10f)
                    marginEnd = px(10f)
                })
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = DividerDrawable()
                addView(wrapper)
            }
        }
    }
}

/** Chrome's DropdownDividerDrawable: a line along the top of the row, nothing else. */
private class DividerDrawable : Drawable() {
    private val paint = Paint()
    private val rect = Rect()

    override fun draw(canvas: Canvas) = canvas.drawRect(rect, paint)

    override fun onBoundsChange(bounds: Rect) = rect.set(0, 0, bounds.width(), rect.height())

    fun setHeight(height: Int) = rect.set(0, 0, rect.right, height)

    fun setDividerColor(color: Int) {
        paint.color = color
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSPARENT
}
