package com.hienlh.ppm.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.recentphoto.PhotoOffer
import com.hienlh.ppm.app.recentphoto.RecentPhotos
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The app's [RecentPhotos], provided by the activity; none draws no chip. */
val LocalRecentPhotos = staticCompositionLocalOf<RecentPhotos?> { null }

/**
 * The "Add to chat" chip over the chat [tabId] (none: no chat yet, no chip), as Messenger offers
 * a photo just taken. It shows in one chat only, the one in front ([activeTabId]) when it is on
 * screen, and hides after a minute, a tap or ×. A tap hands the picture to [onAttach], the
 * paperclip's own attach for this chat; a capture the app cannot read asks for photo access first.
 */
@Composable
fun RecentPhotoChipHost(tabId: String?, activeTabId: String?, onAttach: (List<Uri>) -> Unit, modifier: Modifier = Modifier) {
    val photos = LocalRecentPhotos.current ?: return
    // Kept above every early return: the access dialog's answer arrives after the chip is gone.
    val scope = rememberCoroutineScope()
    val attach by rememberUpdatedState(onAttach)
    val askAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { photos.accessAnswered()?.let { attach(listOf(it)) } }
    }
    if (tabId == null) return
    DisposableEffect(photos, tabId) {
        photos.hostShown(tabId)
        onDispose { photos.hostGone(tabId) }
    }
    val offer by photos.detector.offer.collectAsState()
    val current = offer ?: return
    if (!photos.showsIn(tabId, activeTabId)) return
    val remaining = photos.detector.remainingMs(current)
    LaunchedEffect(current) {
        delay(remaining)
        photos.detector.close(current)
    }
    if (remaining <= 0) return
    val thumbnail by produceState<ImageBitmap?>(null, current) {
        if (current is PhotoOffer.Image) value = photos.thumbnail(current.id)
    }
    RecentPhotoChip(
        offer = current,
        thumbnail = thumbnail,
        onTap = {
            when (current) {
                is PhotoOffer.Image -> {
                    photos.detector.close(current)
                    attach(listOf(photos.uriOf(current.id)))
                }
                is PhotoOffer.Unread -> askAccess.launch(photos.accessPermissions)
            }
        },
        onDismiss = { photos.detector.close(current) },
        modifier = modifier,
    )
}

/** What the chip says. */
internal fun recentPhotoLabel(offer: PhotoOffer): String = when (offer) {
    is PhotoOffer.Image -> "Add to chat"
    is PhotoOffer.Unread -> if (offer.screenshot) "Attach the screenshot you just took?" else "Attach the photo you just took?"
}

/**
 * A floating card in the look of the composer's attachment chips: `panel2`, a hairline border and
 * the floating shadow, the picture (or an image icon when it cannot be read yet), the label and ×.
 */
@Composable
internal fun RecentPhotoChip(offer: PhotoOffer, thumbnail: ImageBitmap?, onTap: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(12.dp)
    val label = recentPhotoLabel(offer)
    val picture = if (mobile) 40.dp else 36.dp
    Row(
        modifier
            .testTag("recent-photo-chip")
            .widthIn(max = if (offer is PhotoOffer.Image) 240.dp else 260.dp)
            .height(if (mobile) 56.dp else 48.dp)
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, shape)
            .ppmClickable(shape = shape, onClickLabel = label, onClick = onTap)
            .semantics { contentDescription = label }
            .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(picture).clip(RoundedCornerShape(8.dp)).background(tokens.panel.color),
            contentAlignment = Alignment.Center,
        ) {
            if (offer is PhotoOffer.Image && thumbnail != null) {
                Image(thumbnail, null, Modifier.size(picture).testTag("recent-photo-thumbnail"), contentScale = ContentScale.Crop)
            } else {
                PpmIcon(ProductIcon.Image, 18.dp, tokens.accent.color)
            }
        }
        BasicText(
            label,
            Modifier.weight(1f, fill = false),
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .size(if (mobile) 28.dp else 24.dp)
                .clip(CircleShape)
                .background(tokens.panel.color)
                .ppmClickable(shape = CircleShape, onClickLabel = "Dismiss", onClick = onDismiss)
                .semantics { contentDescription = "Dismiss" },
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.X, if (mobile) 14.dp else 12.dp, tokens.text2.color)
        }
    }
}
