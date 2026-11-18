package com.hienlh.ppm.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.GalleryImage
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * `design_screenshot` (`collab-design-shot.ts`): the pictures a chat's AI took of a design's
 * variants. The result's first text block lists the shots by id; the card shows each from
 * `/api/design-shots/<id>.png`, which a signed-in PPM serves for seven days, so a picture too big
 * to travel inline with the result (or one a reload left as a placeholder) still shows.
 */

/** One picture the tool took (`listed` in `designShotTool`). */
internal data class DesignShot(val id: String, val design: String, val file: String, val label: String?)

/** A call of `design_screenshot`, under any provider's name. */
internal data class DesignShotCall(val design: String)

private val DESIGN_SHOT_TOOL_NAME = Regex("""^(?:mcp__ppm-sessions__|ppm_sessions:|mcp_ppm-sessions_)design_screenshot$""")

/** A shot id (`SHOT_ID_RE`): the name of the file the route serves, never a path. */
private val SHOT_ID = Regex("""^[0-9a-f]{32}$""")

internal fun designShotCall(name: String, input: JsonElement?): DesignShotCall? {
    if (!DESIGN_SHOT_TOOL_NAME.matches(name)) return null
    val obj = input as? JsonObject
    // Codex wraps the arguments as `{ server, tool, arguments }`.
    val args = (obj?.get("arguments") as? JsonObject) ?: obj
    return DesignShotCall(args?.get("design").string.orEmpty())
}

/** The shots the result lists, or null when it lists none (an error, or text the card cannot read). */
internal fun designShots(output: String): List<DesignShot>? {
    val first = tabToolResultText(output).trim().substringBefore("\n\n")
    if (!first.startsWith("{")) return null
    val parsed = WireJson.parseOrNull(first) as? JsonObject ?: return null
    val shots = (parsed["shots"] as? JsonArray)?.mapNotNull { item ->
        val id = item["id"].string?.takeIf(SHOT_ID::matches) ?: return@mapNotNull null
        DesignShot(
            id = id,
            design = item["design"].string.orEmpty(),
            file = item["file"].string.orEmpty(),
            label = item["label"].string?.takeIf { it.isNotBlank() },
        )
    }
    return shots?.takeIf { it.isNotEmpty() }
}

/** Reads a shot's PNG by id; null when the shot is no longer kept. Provided where the chat has a server. */
val LocalDesignShots = staticCompositionLocalOf<(suspend (String) -> ByteArray?)?> { null }

private val xs = ppmText(12f, lineHeight = 16f)

private fun shotName(shot: DesignShot): String = if (shot.label != null) "${shot.file} · ${shot.label}" else shot.file

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesignShotCard(call: DesignShotCall, output: String?, isError: Boolean, done: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val shots = if (output == null || isError) null else designShots(output)
    val rawText = output?.let(::tabToolResultText).orEmpty()
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.info.opacity(0.3).color, shape)
            .testTag("design-shot-card"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToolChip(ToolChipStyle(ProductIcon.Image, tokens.info.opacity(0.15), tokens.info))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val medium = ppmText(12f, FontWeight.Medium, lineHeight = 16f)
                BasicText("Screenshot", style = medium.copy(color = tokens.text.color), maxLines = 1)
                if (call.design.isNotEmpty()) {
                    BasicText(call.design, Modifier.weight(1f, fill = false), medium.copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (shots != null) {
                    BasicText("· ${shots.size} ${if (shots.size == 1) "picture" else "pictures"}", style = medium.copy(color = tokens.text3.color), maxLines = 1)
                }
            }
            StatusIcon(if (isError) Status.FAILED else if (done) Status.DONE else Status.RUNNING)
        }
        val note = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
        when {
            isError && rawText.isNotEmpty() -> BasicText(rawText, note, xs.copy(color = tokens.error.color))
            shots != null -> FlowRow(note, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shots.forEachIndexed { index, shot -> DesignShotTile(shots, index, shot) }
            }
            rawText.isNotEmpty() -> BasicText(rawText, note.heightIn(max = 240.dp), xs.copy(color = tokens.text3.color))
        }
    }
}

private sealed interface ShotLoad {
    data object Loading : ShotLoad

    data object Gone : ShotLoad

    class Ready(val bytes: ByteArray, val bitmap: ImageBitmap) : ShotLoad
}

@Composable
private fun DesignShotTile(all: List<DesignShot>, index: Int, shot: DesignShot) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val read = LocalDesignShots.current
    val viewer = LocalImageViewer.current
    val load by produceState<ShotLoad>(ShotLoad.Loading, shot.id, read) {
        value = if (read == null) {
            ShotLoad.Gone
        } else {
            try {
                val bytes = read(shot.id)
                val bitmap = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                if (bytes != null && bitmap != null) ShotLoad.Ready(bytes, bitmap) else ShotLoad.Gone
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ShotLoad.Gone
            }
        }
    }
    val name = shotName(shot)
    val tileShape = RoundedCornerShape(6.dp)
    val width = if (mobile) 160.dp else 180.dp
    Column(
        Modifier
            .width(width)
            .clip(tileShape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, tileShape)
            .testTag("design-shot-${shot.id}")
            .then(
                if (load is ShotLoad.Ready && viewer != null && read != null) {
                    Modifier.ppmClickable(shape = tileShape, onClickLabel = "Open $name") {
                        val gallery = all.map { s ->
                            GalleryImage("design-shot:${s.id}", shotName(s)) {
                                try {
                                    read(s.id)?.let { ViewerImage(shotName(s), it, "image/png") }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                        viewer.show(gallery, index)
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.fillMaxWidth().height(if (mobile) 112.dp else 128.dp), contentAlignment = Alignment.Center) {
            when (val l = load) {
                is ShotLoad.Ready -> Image(
                    l.bitmap,
                    "${index + 1}. $name",
                    Modifier.fillMaxWidth().height(if (mobile) 112.dp else 128.dp),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
                ShotLoad.Gone -> BasicText("Picture no longer kept", style = xs.copy(color = tokens.text2.color))
                ShotLoad.Loading -> BasicText("Loading…", style = xs.copy(color = tokens.text2.color))
            }
        }
        BasicText(
            "${index + 1}. $name",
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = xs.copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
