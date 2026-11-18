package com.hienlh.ppm.app.ui.assistant

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.state.assistant.MAX_REPLY_IMAGES
import com.hienlh.ppm.state.assistant.ReplyImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID

/** The picture types a card reply carries (`REPLY_IMAGE_TYPES`). */
private val REPLY_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** The most one picture may weigh (`MAX_REPLY_IMAGE_BYTES`). */
private const val MAX_REPLY_IMAGE_BYTES = 5 * 1024 * 1024

/** "At most 5 pictures in one reply." */
const val TOO_MANY_PICTURES = "At most $MAX_REPLY_IMAGES pictures in one reply."

/** Reads [uris] as reply pictures; a type or size the server would refuse is left out. */
suspend fun readReplyImages(context: Context, uris: List<Uri>): List<ReplyImage> = withContext(Dispatchers.IO) {
    uris.mapNotNull { uri ->
        val type = context.contentResolver.getType(uri) ?: return@mapNotNull null
        if (type !in REPLY_IMAGE_TYPES) return@mapNotNull null
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@mapNotNull null
        if (bytes.size > MAX_REPLY_IMAGE_BYTES) return@mapNotNull null
        ReplyImage(UUID.randomUUID().toString(), uri.lastPathSegment ?: "picture", InlineImage(Base64.getEncoder().encodeToString(bytes), type))
    }
}

/**
 * "Attach a picture": the system photo picker, at most what is left of [MAX_REPLY_IMAGES]; [onPicked]
 * gets what was read, [onTooMany] when the user chose more than fit.
 */
@Composable
fun rememberReplyImagePicker(have: () -> Int, onPicked: (List<ReplyImage>) -> Unit, onTooMany: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_REPLY_IMAGES)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = MAX_REPLY_IMAGES - have()
        if (uris.size > room) onTooMany()
        scope.launch { onPicked(readReplyImages(context, uris.take(room.coerceAtLeast(0)))) }
    }
    return remember(launcher) {
        {
            if (have() >= MAX_REPLY_IMAGES) onTooMany()
            else launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

/** The pictures picked for a reply, each with its remove button. */
@Composable
fun ReplyImageStrip(images: List<ReplyImage>, onRemove: (ReplyImage) -> Unit) {
    if (images.isEmpty()) return
    val tokens = LocalPpmTokens.current
    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("assistant-reply-images"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (image in images) {
            val bitmap = remember(image.id) {
                runCatching { Base64.getDecoder().decode(image.image.data).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
            }
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(tokens.panel2.color).cssBorder(tokens.border.color, RoundedCornerShape(6.dp))) {
                if (bitmap != null) Image(bitmap, image.name, Modifier.size(56.dp), contentScale = ContentScale.Crop)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .background(tokens.panel.color, CircleShape)
                        .ppmClickable(shape = CircleShape) { onRemove(image) }
                        .semantics { contentDescription = "Remove ${image.name}" },
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(ProductIcon.X, 12.dp, tokens.text2.color) }
            }
        }
    }
}
