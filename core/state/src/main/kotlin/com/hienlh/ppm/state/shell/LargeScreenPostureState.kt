package com.hienlh.ppm.state.shell

import kotlin.math.abs
import kotlin.math.roundToInt

// ===========================================================================
// 13a.1 · Multi-Window & Freeform Resize Stabilizer + 3x3 Panel Budget
// ===========================================================================

data class WindowLaunchSpec(
    val windowId: String,
    val serverId: String?,
    val projectName: String,
    val initialTabType: String? = null,
    val initialTabId: String? = null,
    val initialTabTitle: String? = null,
    val launchAdjacent: Boolean = true,
    val pipMode: Boolean = false,
) {
    val dataUri: String
        get() = if (initialTabId != null) {
            "ppm-window://project/$projectName/tab/$windowId"
        } else {
            "ppm-window://project/$windowId"
        }
}

object FreeformWindowDefaults {
    const val MOBILE_BREAKPOINT_DP = 768
    const val HYSTERESIS_DP = 12
    const val MIN_WINDOW_WIDTH_DP = 360
    const val MIN_WINDOW_HEIGHT_DP = 480
    const val MAX_GRID_PANELS = 9
    const val TARGET_FRAME_MS = 16.67
}

/**
 * Resolves whether the window should use the wide (tablet/desktop) shell (`>= 768 dp`).
 * During active freeform window border dragging ([isResizing] = true), applies a small
 * hysteresis band (`768 ± 12 dp`) and ignores transient `0 dp` measurements so continuous
 * resizing across 768 dp does not thrash layout state or reconnect PTYs.
 */
fun resolveWindowWideMode(
    widthDp: Int,
    previousWide: Boolean,
    isResizing: Boolean = false,
): Boolean {
    if (widthDp <= 0) return previousWide
    if (!isResizing) {
        return widthDp >= FreeformWindowDefaults.MOBILE_BREAKPOINT_DP
    }
    val enterWide = FreeformWindowDefaults.MOBILE_BREAKPOINT_DP + FreeformWindowDefaults.HYSTERESIS_DP
    val leaveWide = FreeformWindowDefaults.MOBILE_BREAKPOINT_DP - FreeformWindowDefaults.HYSTERESIS_DP
    return when {
        widthDp >= enterWide -> true
        widthDp < leaveWide -> false
        else -> previousWide
    }
}

data class JankStatsSummary(
    val totalFrames: Int,
    val jankyFrames: Int,
    val jankPercent: Double,
    val p95FrameMs: Double,
    val maxFrameMs: Double,
) {
    val meetsTargetBudget: Boolean
        get() = jankPercent <= 5.0 && p95FrameMs <= 24.0
}

fun summarizeFrameDurationsMs(durationsMs: List<Double>): JankStatsSummary {
    if (durationsMs.isEmpty()) {
        return JankStatsSummary(
            totalFrames = 0,
            jankyFrames = 0,
            jankPercent = 0.0,
            p95FrameMs = 0.0,
            maxFrameMs = 0.0,
        )
    }
    val sorted = durationsMs.sorted()
    val total = sorted.size
    val janky = sorted.count { it > FreeformWindowDefaults.TARGET_FRAME_MS }
    val p95Idx = ((total - 1) * 0.95).roundToInt().coerceIn(0, total - 1)
    val pct = (janky.toDouble() * 100.0) / total.toDouble()
    return JankStatsSummary(
        totalFrames = total,
        jankyFrames = janky,
        jankPercent = (pct * 100.0).roundToInt() / 100.0,
        p95FrameMs = sorted[p95Idx],
        maxFrameMs = sorted.last(),
    )
}

// ===========================================================================
// 13a.2 · Cross-App Drag-and-Drop, Mouse Pointer Icons & Stylus Input
// ===========================================================================

enum class ExternalDropKind {
    FILES,
    TEXT,
    URI,
    UNSUPPORTED,
}

enum class DropDestinationKind {
    PROJECT_FILES_TREE,
    OS_EXPLORER,
    CHAT_COMPOSER,
    CODE_EDITOR,
    TERMINAL,
}

data class ExternalDropItem(
    val uriString: String? = null,
    val mimeType: String? = null,
    val text: String? = null,
)

data class ClassifiedExternalDrop(
    val kind: ExternalDropKind,
    val fileUris: List<String>,
    val textPayload: String?,
    val needsUriPermission: Boolean,
)

fun classifyExternalDrop(
    mimeTypes: List<String>,
    items: List<ExternalDropItem>,
    isCrossApp: Boolean = true,
): ClassifiedExternalDrop {
    val contentUris = items.mapNotNull { it.uriString?.takeIf { u -> u.isNotBlank() } }
    val hasContentUri = contentUris.any { it.startsWith("content://") || it.startsWith("file://") }
    val hasFileMime = mimeTypes.any {
        it.startsWith("image/") ||
            it.startsWith("video/") ||
            it.startsWith("audio/") ||
            it.startsWith("application/") ||
            it == "vnd.android.cursor.dir/*"
    }

    if (hasContentUri && (hasFileMime || items.all { it.text.isNullOrBlank() })) {
        return ClassifiedExternalDrop(
            kind = ExternalDropKind.FILES,
            fileUris = contentUris,
            textPayload = null,
            needsUriPermission = isCrossApp && contentUris.any { it.startsWith("content://") },
        )
    }

    val combinedText = items.mapNotNull { it.text?.takeIf { t -> t.isNotEmpty() } }
        .joinToString("\n")
        .ifEmpty { null }
    if (combinedText != null) {
        return ClassifiedExternalDrop(
            kind = ExternalDropKind.TEXT,
            fileUris = contentUris,
            textPayload = combinedText,
            needsUriPermission = false,
        )
    }

    if (contentUris.isNotEmpty()) {
        return ClassifiedExternalDrop(
            kind = ExternalDropKind.URI,
            fileUris = contentUris,
            textPayload = contentUris.joinToString("\n"),
            needsUriPermission = isCrossApp && contentUris.any { it.startsWith("content://") },
        )
    }

    return ClassifiedExternalDrop(
        kind = ExternalDropKind.UNSUPPORTED,
        fileUris = emptyList(),
        textPayload = null,
        needsUriPermission = false,
    )
}

fun canDestinationAcceptDrop(
    destination: DropDestinationKind,
    drop: ClassifiedExternalDrop,
): Boolean = when (destination) {
    DropDestinationKind.PROJECT_FILES_TREE,
    DropDestinationKind.OS_EXPLORER,
    -> drop.kind == ExternalDropKind.FILES
    DropDestinationKind.CHAT_COMPOSER ->
        drop.kind == ExternalDropKind.FILES || drop.kind == ExternalDropKind.TEXT || drop.kind == ExternalDropKind.URI
    DropDestinationKind.CODE_EDITOR,
    DropDestinationKind.TERMINAL,
    -> drop.kind == ExternalDropKind.TEXT || drop.kind == ExternalDropKind.URI || drop.kind == ExternalDropKind.FILES
}

/**
 * Mouse / trackpad / stylus hover pointer cursor styles matching Android `PointerIcon.TYPE_*`.
 */
enum class PointerRegionKind(val androidPointerType: Int) {
    DEFAULT(1000), // PointerIcon.TYPE_ARROW
    CLICKABLE_HAND(1002), // PointerIcon.TYPE_HAND
    TEXT_IBEAM(1008), // PointerIcon.TYPE_TEXT
    RESIZE_HORIZONTAL(1014), // PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
    RESIZE_VERTICAL(1015), // PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
    RESIZE_DIAGONAL_NWSE(1017), // PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
    RESIZE_DIAGONAL_NESW(1016), // PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
    GRAB(1020), // PointerIcon.TYPE_GRAB
    GRABBING(1021), // PointerIcon.TYPE_GRABBING
    CROSSHAIR(1007), // PointerIcon.TYPE_CROSSHAIR
}

enum class StylusInputKind {
    PEN_WRITE,
    PEN_HOVER,
    PEN_BUTTON_CONTEXT,
    PEN_ERASER,
}

// ===========================================================================
// 13a.3 · Foldable Posture (Book & Tabletop Hinge Layout)
// ===========================================================================

enum class HingeOrientation {
    VERTICAL,
    HORIZONTAL,
}

enum class HingeOcclusion {
    NONE,
    FULL,
}

enum class FoldablePosture {
    FLAT,
    BOOK,
    TABLETOP,
}

data class HingeBounds(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
    val orientation: HingeOrientation,
    val isSeparating: Boolean,
    val occlusion: HingeOcclusion = HingeOcclusion.NONE,
) {
    val widthPx: Int get() = (rightPx - leftPx).coerceAtLeast(0)
    val heightPx: Int get() = (bottomPx - topPx).coerceAtLeast(0)
    val centerX: Int get() = leftPx + widthPx / 2
    val centerY: Int get() = topPx + heightPx / 2
}

data class FoldableSplitDecision(
    val posture: FoldablePosture,
    /** For BOOK posture: fraction `[0.25f..0.75f]` of window width before the hinge. */
    val bookLeftFraction: Float = 0.5f,
    /** For BOOK posture: pixel gap occupied by an occluded hinge. */
    val hingeGapPx: Int = 0,
    /** For TABLETOP posture: pixel height of the top media/canvas pane above the hinge. */
    val tabletopTopHeightPx: Int = 0,
    /** For TABLETOP posture: pixel height of the bottom control pane below the hinge. */
    val tabletopBottomHeightPx: Int = 0,
)

/**
 * Computes how the shell or media viewers (Remote Desktop / Android Device / Video)
 * should align panels around a foldable device's hinge so content is never cut across
 * a separating or occluded fold.
 */
fun computeFoldableSplit(
    hinge: HingeBounds?,
    windowWidthPx: Int,
    windowHeightPx: Int,
): FoldableSplitDecision {
    if (hinge == null || !hinge.isSeparating || windowWidthPx <= 0 || windowHeightPx <= 0) {
        return FoldableSplitDecision(posture = FoldablePosture.FLAT)
    }

    return when (hinge.orientation) {
        HingeOrientation.VERTICAL -> {
            val rawFraction = hinge.centerX.toFloat() / windowWidthPx.toFloat()
            val clamped = if (abs(rawFraction - 0.5f) <= 0.3f) {
                rawFraction.coerceIn(0.25f, 0.75f)
            } else {
                0.5f
            }
            FoldableSplitDecision(
                posture = FoldablePosture.BOOK,
                bookLeftFraction = clamped,
                hingeGapPx = if (hinge.occlusion == HingeOcclusion.FULL) hinge.widthPx else 0,
            )
        }
        HingeOrientation.HORIZONTAL -> {
            val topH = hinge.topPx.coerceIn(1, windowHeightPx)
            val bottomH = (windowHeightPx - hinge.bottomPx).coerceAtLeast(1)
            FoldableSplitDecision(
                posture = FoldablePosture.TABLETOP,
                hingeGapPx = if (hinge.occlusion == HingeOcclusion.FULL) hinge.heightPx else 0,
                tabletopTopHeightPx = topH,
                tabletopBottomHeightPx = bottomH,
            )
        }
    }
}
