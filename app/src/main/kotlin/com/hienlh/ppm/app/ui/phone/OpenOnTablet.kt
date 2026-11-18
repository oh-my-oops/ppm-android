package com.hienlh.ppm.app.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.state.workspace.WorkspaceTab

/**
 * Routing decision when a [WorkspaceTab] is active on a phone layout
 * (Requirements 3.1, 3.2, 3.3).
 */
sealed interface PhoneTabRoute {
    /** The tab belongs to one of the 12 [PhoneArea]s supported on phone. */
    data class Area(val area: PhoneArea, val tab: WorkspaceTab) : PhoneTabRoute

    /**
     * The tab belongs to a tablet-only feature. [PhoneShell] renders [OpenOnTablet] with [notice]
     * while leaving [tab] untouched in `WorkspaceStore` so tablet and desktop sessions keep it.
     */
    data class TabletOnly(val tab: WorkspaceTab, val notice: String) : PhoneTabRoute
}

/**
 * Classifies [tab] into either a supported [PhoneTabRoute.Area] or a [PhoneTabRoute.TabletOnly]
 * placeholder without mutating `WorkspaceStore` (Requirements 3.1, 3.2, 3.3).
 */
fun resolvePhoneTabRoute(tab: WorkspaceTab): PhoneTabRoute {
    val area = PhoneArea.fromTab(tab)
    return if (area != null) {
        PhoneTabRoute.Area(area = area, tab = tab)
    } else {
        PhoneTabRoute.TabletOnly(tab = tab, notice = tabletOnlyNoticeFor(tab))
    }
}

/** True when [tab] is a tablet-only feature that renders [OpenOnTablet] on a phone. */
fun isTabletOnlyTab(tab: WorkspaceTab): Boolean = PhoneArea.isTabletOnly(tab)

/**
 * Returns the concise one-line notice explaining that [tab] opens on a tablet
 * (`designs/ppm-mobile-tablet-redesign/foundation/index.html`, Section 4 & Requirement 3.2).
 */
fun tabletOnlyNoticeFor(tab: WorkspaceTab): String =
    tabletOnlyNoticeForType(type = tab.type, title = tab.title)

/**
 * Returns the concise one-line notice for a tablet-only [type] (Requirement 3.2).
 */
fun tabletOnlyNoticeForType(type: String, title: String = ""): String = when (type.lowercase().trim()) {
    "conflict-editor" -> "Resolve conflicts on a tablet."
    "git-review", "session-review" -> "Review changes opens on a tablet."
    "branch-review" -> "Branch review opens on a tablet."
    "git-graph", "git-log" -> "Git Graph opens on a tablet."
    "db-connection" -> "Add or edit connections on a tablet."
    "db-structure" -> "Database structure opens on a tablet."
    "db-impexp" -> "Database import and export open on a tablet."
    "remote-desktop" -> "Remote Desktop opens on a tablet."
    "android", "android-device" -> "Android device opens on a tablet."
    "extension", "extensions" -> "Extensions open on a tablet."
    "design", "designs" -> "Designs open on a tablet."
    "group", "teams" -> "Teams opens on a tablet."
    "ai-resource", "ai-resources" -> "AI Resources open on a tablet."
    "problems" -> "Problems opens on a tablet."
    "os-explorer", "host-explorer" -> "OS File Explorer opens on a tablet."
    "search" -> "Project search and replace open on a tablet."
    else -> {
        val label = title.trim().ifEmpty { type.trim().ifEmpty { "This feature" } }
        "$label opens on a tablet."
    }
}

/**
 * Full-pane phone placeholder for a tablet-only [WorkspaceTab]
 * (`design.md` — `OpenOnTablet.kt`, Requirements 3.2 & 3.3).
 *
 * Shows a concise notice that this feature opens on a tablet while keeping [tab] intact in
 * `WorkspaceStore` so switching back to a tablet or desktop preserves the open tab.
 */
@Composable
fun OpenOnTablet(
    tab: WorkspaceTab,
    modifier: Modifier = Modifier,
) {
    val title = tab.title.ifBlank { tab.type }
    val notice = tabletOnlyNoticeFor(tab)
    OpenOnTablet(
        title = title,
        notice = notice,
        modifier = modifier,
    )
}

/**
 * Full-pane phone placeholder for a tablet-only feature with [title] and [notice].
 */
@Composable
fun OpenOnTablet(
    title: String,
    notice: String = "Open on a tablet.",
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val cardShape = RoundedCornerShape(16.dp)
    val iconBoxShape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .semantics { contentDescription = "$title: $notice" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(tokens.panel.color)
                .cssBorder(color = tokens.borderSoft.color, shape = cardShape, width = 1.dp)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(iconBoxShape)
                    .background(tokens.panel2.color)
                    .cssBorder(color = tokens.borderSoft.color, shape = iconBoxShape, width = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    icon = ProductIcon.Tablet,
                    size = 20.dp,
                    tint = tokens.text2.color,
                )
            }

            Spacer(Modifier.height(14.dp))

            BasicText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = PpmFonts.sans,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.text.color,
                    textAlign = TextAlign.Center,
                ),
            )

            Spacer(Modifier.height(6.dp))

            BasicText(
                text = notice,
                style = TextStyle(
                    fontFamily = PpmFonts.sans,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = tokens.text2.color,
                    textAlign = TextAlign.Center,
                ),
            )

            Spacer(Modifier.height(14.dp))

            OpenOnTabletNotice(
                text = "Kept open in this project for your tablet.",
            )
        }
    }
}

/**
 * Subtle one-line "Open on a tablet" notice bar (`PpmNotice` in Foundation Section 4:
 * `>= 40 dp` height, `12 dp` radius, `16 dp` tablet icon, `13/18 sp` text, no button).
 */
@Composable
fun OpenOnTabletNotice(
    text: String = "Open on a tablet.",
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .clip(shape)
            .background(tokens.panel2.opacity(0.6).color)
            .cssBorder(color = tokens.borderSoft.color, shape = shape, width = 1.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(
            icon = ProductIcon.Tablet,
            size = 16.dp,
            tint = tokens.text3.color,
        )
        BasicText(
            text = text,
            style = TextStyle(
                fontFamily = PpmFonts.sans,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Normal,
                color = tokens.text2.color,
            ),
        )
    }
}

/**
 * Warning-tinted banner for features that require resolution on a tablet, such as merge conflicts
 * (`PpmNotice` banner in Foundation Section 4: `14 dp` radius, warning tint, `16 dp` icon).
 */
@Composable
fun ResolveOnTabletBanner(
    title: String,
    subtitle: String = "Resolve on a tablet.",
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(tokens.warning.opacity(0.10).color)
            .cssBorder(color = tokens.warning.opacity(0.32).color, shape = shape, width = 1.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(
            icon = ProductIcon.AlertTriangle,
            size = 16.dp,
            tint = tokens.warning.color,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = title,
                style = TextStyle(
                    fontFamily = PpmFonts.sans,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.text.color,
                ),
            )
            BasicText(
                text = subtitle,
                style = TextStyle(
                    fontFamily = PpmFonts.sans,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = tokens.text2.color,
                ),
            )
        }
    }
}
