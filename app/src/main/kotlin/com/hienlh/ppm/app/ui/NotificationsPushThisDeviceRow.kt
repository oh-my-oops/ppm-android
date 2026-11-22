package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText

/** What the tablet pane's "Push notifications on this device" row draws. */
data class PushThisDeviceUi(
    val checked: Boolean,
    val distributorAvailable: Boolean,
    val distributorName: String? = null,
    val savedDeviceId: String? = null,
    val permissionDenied: Boolean = false,
    /** The server offers Firebase, but this app runs another server's Firebase project. */
    val firebaseInUseElsewhere: Boolean = false,
)

/**
 * The tablet's switch for this device's own push subscription, drawn as [SettingsToggleRow] is
 * (the event rows above it), plus the distributor notice and the blocked-permission line.
 */
@Composable
internal fun PushThisDeviceRow(
    state: PushThisDeviceUi,
    onCheckedChange: (Boolean) -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val enabled = state.distributorAvailable
    val checked = state.checked && enabled
    val subtitle = when {
        !enabled -> "UnifiedPush distributor required (install ntfy)"
        !state.distributorName.isNullOrBlank() ->
            pushViaSubtitle("this device", state.distributorName, state.firebaseInUseElsewhere)
        else -> "Receive alerts on this device when a chat finishes"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (LocalPpmMobile.current) 44.dp else 0.dp)
                .alpha(if (enabled) 1f else 0.55f)
                .semantics {
                    selected = checked
                    if (!enabled) disabled()
                }
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .testTag("notify-push-this-device"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    "Push notifications on this device",
                    style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color),
                )
                BasicText(subtitle, style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color))
            }
            val pillShape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .width(40.dp)
                    .height(22.dp)
                    .clip(pillShape)
                    .background(if (checked) tokens.accent.color else tokens.panel2.color)
                    .cssBorder((if (checked) tokens.accent else tokens.border).color, pillShape)
                    .padding(horizontal = 3.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (checked) tokens.accentFg.color else tokens.text2.color),
                )
            }
        }
        if (!enabled) {
            val noticeShape = RoundedCornerShape(8.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(noticeShape)
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.border.color, noticeShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("notify-push-no-distributor-notice"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    "UnifiedPush distributor required",
                    style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                BasicText(
                    "Install ntfy (or another UnifiedPush distributor app) on this device to enable push notifications.",
                    style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
        } else if (state.permissionDenied) {
            Row(
                Modifier.fillMaxWidth().testTag("notify-push-permission-denied"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "Notifications are off for PPM. Turn them on in Android settings.",
                    style = ppmText(11f, lineHeight = 16f).copy(color = tokens.warning.color),
                    modifier = Modifier.weight(1f),
                )
                SettingsButton(
                    label = "Open settings",
                    modifier = Modifier.testTag("notify-push-open-system-settings"),
                    onClick = onOpenSystemNotificationSettings,
                )
            }
        }
    }
}

/** The "This device" tag beside the device list row this device's subscription is. */
@Composable
internal fun ThisDeviceBadge(modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .clip(shape)
            .background(tokens.accent.opacity(0.16).color)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        BasicText("This device", style = ppmText(10f, FontWeight.Medium).copy(color = tokens.accent.color))
    }
}
