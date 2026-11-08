package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/*
 * The messages between a page PPM shows and the bridge script the server puts in it
 * (src/shared/design-bridge-protocol.ts and design-bridge-messages-*.ts).
 *
 * Every message is an envelope `{ppm: "design-bridge", v: 1, nonce, type, …}`. The nonce is minted
 * by the parent for every load (`?n=` on the page's URL) and baked into the page by the server;
 * the bridge echoes it on every message. A child message counts only when it comes from the
 * page's frame *and* carries the current nonce: a page the frame navigated to on its own never
 * saw it, which is why parent → frame envelopes never carry one. Everything a child sends is
 * untrusted — the page's own scripts can post the same shapes — so every field is capped here,
 * and nothing a child says ever causes a write.
 */

const val BRIDGE_CHANNEL = "design-bridge"
const val BRIDGE_VERSION = 1

/** Shape of the per-load nonce. Anything else is dropped by the server and the parent. */
val BRIDGE_NONCE_RE = Regex("^[A-Za-z0-9_-]{16,64}$")

/** Most `issue` messages one page load is kept for. */
const val MAX_BRIDGE_ISSUES = 20

/** Generous for the screenshot library's ~30 KB; anything bigger is not the library. */
const val MAX_CHECK_LIB_CHARS = 256 * 1024

const val MAX_TRANSFORM_PX = 20_000.0
const val MAX_NUDGE_PX = 500
const val MAX_TWEAKS_PER_MESSAGE = 32
const val MAX_PINS_PER_MESSAGE = 100

val BRIDGE_COMMENT_ID_RE = Regex("^[0-9a-f]{12}$")
val BRIDGE_TAG_RE = Regex("^[a-z][a-z0-9-]{0,31}$")
val BRIDGE_CSS_VAR_RE = Regex("^--[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}$")

private val ISSUE_KINDS = listOf("error", "rejection", "resource", "csp")
private val HREF_CONTROL_OR_SPACE_RE = Regex("[\\u0000-\\u0020\\u007f]")
private val FORBIDDEN_TWEAK_VALUE_RE = Regex(
    """[;{}<>\\/\n\r\u0000-\u001f]|url\s*\(|expression\s*\(|@import|/\*|\*/|var\s*\(""",
    RegexOption.IGNORE_CASE,
)
private val PX_PART = "-?\\d{1,5}(?:\\.\\d{1,2})?px"
private val TRANSLATE_RE = Regex("^($PX_PART) ($PX_PART)$")
private val SIZE_PX_RE = Regex("^(\\d{1,5}(?:\\.\\d{1,2})?)px$")
private val TRANSFORM_PROP_NAMES = setOf("translate", "width", "height")

private const val MAX_SLIDES = 200
private const val MAX_SLIDE_ITEMS = 600
private const val MAX_TEXT_RUNS = 400
private const val MAX_RUN_TEXT = 5000
private const val MAX_SLIDE_WARNINGS = 200
private const val MAX_SLIDE_IMAGE_BYTES = 50 * 1024 * 1024
private const val MAX_SLIDE_EDGE_PX = 5376.0
private val SLIDE_HEX_RE = Regex("^[0-9A-F]{6}$")
private val SLIDE_FONT_RE = Regex("^[\\p{L}\\p{N} ._-]{1,64}$")
private val SLIDE_IMAGE_DATA_RE = Regex("^data:image/(?:png|jpeg);base64,[A-Za-z0-9+/]+={0,2}$")
private val SLIDE_ALIGNS = setOf("left", "center", "right", "justify")
private val SLIDE_VALIGNS = setOf("top", "middle", "bottom")

data class BridgeRect(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
)

data class BridgeTextQuote(
    val exact: String,
    val prefix: String = "",
    val suffix: String = "",
)

data class BridgeCommentAnchor(
    val file: String,
    val ppmId: Int? = null,
    val gen: String? = null,
    val tag: String,
    val cssPath: String,
    val quote: BridgeTextQuote,
) {
    val json: JsonObject
        get() = buildJsonObject {
            put("file", file)
            if (ppmId != null) put("ppmId", ppmId) else put("ppmId", JsonNull)
            if (gen != null) put("gen", gen) else put("gen", JsonNull)
            put("tag", tag)
            put("cssPath", cssPath)
            put(
                "quote",
                buildJsonObject {
                    put("exact", quote.exact)
                    put("prefix", quote.prefix)
                    put("suffix", quote.suffix)
                },
            )
        }
}

data class BridgePinTarget(
    val id: String,
    val anchor: BridgeCommentAnchor,
)

data class BridgeHoverElement(
    val tag: String,
    val rect: BridgeRect,
)

data class BridgePickedElement(
    val ppmId: Int?,
    val gen: String?,
    val file: String,
    val tag: String,
    val rect: BridgeRect,
    val cssPath: String,
    val outerHtml: String,
    val text: String,
    val quote: BridgeTextQuote,
)

data class BridgePinRect(
    val id: String,
    val rect: BridgeRect?,
    val ppmId: Int?,
    val gen: String?,
    val reanchored: Boolean,
)

data class BridgeTransformBox(
    val tx: Double,
    val ty: Double,
    val w: Double,
    val h: Double,
)

data class SlideColor(
    val hex: String,
    val alpha: Double,
)

data class SlideTextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: SlideColor? = null,
    val sizePx: Double? = null,
    val font: String? = null,
    val breakLine: Boolean = false,
)

sealed interface SlideItem {
    val x: Double
    val y: Double
    val w: Double
    val h: Double
    val rotation: Double?

    data class Text(
        override val x: Double,
        override val y: Double,
        override val w: Double,
        override val h: Double,
        override val rotation: Double? = null,
        val runs: List<SlideTextRun>,
        val align: String,
        val valign: String,
        val lineHeightPx: Double? = null,
        val bullet: String? = null,
    ) : SlideItem

    data class Shape(
        override val x: Double,
        override val y: Double,
        override val w: Double,
        override val h: Double,
        override val rotation: Double? = null,
        val fill: SlideColor? = null,
        val borderWidthPx: Double? = null,
        val borderColor: SlideColor? = null,
        val radiusPx: Double? = null,
    ) : SlideItem

    data class Image(
        override val x: Double,
        override val y: Double,
        override val w: Double,
        override val h: Double,
        override val rotation: Double? = null,
        val data: String,
    ) : SlideItem
}

data class Slide(
    val background: SlideColor? = null,
    val items: List<SlideItem>,
)

data class SlideDoc(
    val width: Double,
    val height: Double,
    val slides: List<Slide>,
    val warnings: List<String>,
)

/**
 * `data-ppm-id`: non-negative 31-bit integer (`parsePpmId` in `src/shared/design-comment-types.ts`).
 */
fun parseBridgePpmId(value: JsonElement?): Int? {
    val n = value.int ?: return null
    return if (n >= 0) n else null
}

/**
 * HTTP(S) URLs only (`isSafeExternalHref` in `src/shared/design-bridge-protocol.ts`).
 */
fun isSafeExternalHref(href: String): Boolean {
    if (href.isEmpty() || href.length > 2048) return false
    if (HREF_CONTROL_OR_SPACE_RE.containsMatchIn(href)) return false
    return try {
        val u = URI(href)
        val scheme = u.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !u.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

/**
 * Validates `transform-commit` CSS properties (`parseTransformProps` in `design-bridge-messages-transform.ts`).
 */
fun parseBridgeTransformProps(value: JsonElement?): Map<String, String>? {
    if (value !is JsonObject) return null
    val entries = value.entries
    if (entries.isEmpty() || entries.size > 3) return null
    val out = LinkedHashMap<String, String>()
    for ((k, raw) in entries) {
        if (k !in TRANSFORM_PROP_NAMES) return null
        val v = raw.string ?: return null
        if (k == "translate") {
            val m = TRANSLATE_RE.matchEntire(v) ?: return null
            val tx = m.groupValues[1].removeSuffix("px").toDoubleOrNull() ?: return null
            val ty = m.groupValues[2].removeSuffix("px").toDoubleOrNull() ?: return null
            if (abs(tx) > MAX_TRANSFORM_PX || abs(ty) > MAX_TRANSFORM_PX) return null
        } else {
            val m = SIZE_PX_RE.matchEntire(v) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            if (n < 1.0 || n > MAX_TRANSFORM_PX) return null
        }
        out[k] = v
    }
    return out
}

fun parseBridgeCommentAnchor(raw: JsonElement?): BridgeCommentAnchor? {
    if (raw !is JsonObject) return null
    val file = raw["file"].string?.takeIf {
        it.isNotEmpty() && it.length <= 256 && !it.startsWith("/") && !it.contains("..") && !it.contains("\\")
    } ?: return null
    val rawPpmId = raw["ppmId"]
    val ppmId = when {
        rawPpmId == null || rawPpmId is JsonNull -> null
        else -> parseBridgePpmId(rawPpmId) ?: return null
    }
    val rawGen = raw["gen"]
    val gen = when {
        rawGen == null || rawGen is JsonNull -> null
        else -> rawGen.string?.takeIf { DESIGN_GEN_RE.matches(it) } ?: return null
    }
    val tag = raw["tag"].string?.lowercase()?.takeIf { BRIDGE_TAG_RE.matches(it) } ?: return null
    val cssPath = raw["cssPath"].string?.takeIf { it.length <= 512 } ?: return null
    val quoteObj = raw["quote"] as? JsonObject ?: return null
    val exact = quoteObj["exact"].string ?: return null
    val prefix = quoteObj["prefix"].string ?: return null
    val suffix = quoteObj["suffix"].string ?: return null
    return BridgeCommentAnchor(
        file = file,
        ppmId = ppmId,
        gen = gen,
        tag = tag,
        cssPath = cssPath,
        quote = BridgeTextQuote(
            exact = exact.take(240),
            prefix = prefix.take(48),
            suffix = suffix.take(48),
        ),
    )
}

fun parseSlideDoc(raw: JsonElement?): SlideDoc? {
    if (raw !is JsonObject) return null
    val width = finiteCoord(raw["width"], MAX_SLIDE_EDGE_PX)?.takeIf { it >= 1.0 } ?: return null
    val height = finiteCoord(raw["height"], MAX_SLIDE_EDGE_PX)?.takeIf { it >= 1.0 } ?: return null
    val slidesArr = raw["slides"].array?.takeIf { it.size in 1..MAX_SLIDES } ?: return null
    val warningsArr = raw["warnings"].array?.takeIf { it.size <= MAX_SLIDE_WARNINGS } ?: return null
    val warnings = ArrayList<String>(warningsArr.size)
    for (w in warningsArr) {
        val s = w.string ?: return null
        warnings.add(s.take(300))
    }
    var imageBytesBudget = 0
    val slides = ArrayList<Slide>(slidesArr.size)
    for (slideEl in slidesArr) {
        if (slideEl !is JsonObject) return null
        val itemsArr = slideEl["items"].array?.takeIf { it.size <= MAX_SLIDE_ITEMS } ?: return null
        val bg = if (slideEl.containsKey("background") && slideEl["background"] !is JsonNull) {
            parseSlideColor(slideEl["background"]) ?: return null
        } else {
            null
        }
        val items = ArrayList<SlideItem>(itemsArr.size)
        for (itemEl in itemsArr) {
            if (itemEl !is JsonObject) return null
            val x = finiteCoord(itemEl["x"]) ?: return null
            val y = finiteCoord(itemEl["y"]) ?: return null
            val w = finiteCoord(itemEl["w"])?.takeIf { it > 0.0 } ?: return null
            val h = finiteCoord(itemEl["h"])?.takeIf { it > 0.0 } ?: return null
            val rotation = if (itemEl.containsKey("rotation") && itemEl["rotation"] !is JsonNull) {
                val r = finiteCoord(itemEl["rotation"], 360.0) ?: return null
                if (r != 0.0) r else null
            } else {
                null
            }
            when (itemEl["kind"].string) {
                "text" -> {
                    val runsArr = itemEl["runs"].array?.takeIf { it.size in 1..MAX_TEXT_RUNS } ?: return null
                    val runs = ArrayList<SlideTextRun>(runsArr.size)
                    for (rEl in runsArr) {
                        if (rEl !is JsonObject) return null
                        val text = rEl["text"].string?.take(MAX_RUN_TEXT) ?: return null
                        val color = if (rEl.containsKey("color") && rEl["color"] !is JsonNull) {
                            parseSlideColor(rEl["color"]) ?: return null
                        } else null
                        val sizePx = if (rEl.containsKey("sizePx") && rEl["sizePx"] !is JsonNull) {
                            finiteCoord(rEl["sizePx"], 4000.0)?.takeIf { it > 0.0 } ?: return null
                        } else null
                        val font = rEl["font"].string?.takeIf { SLIDE_FONT_RE.matches(it) }
                        runs.add(
                            SlideTextRun(
                                text = text,
                                bold = rEl["bold"].bool == true,
                                italic = rEl["italic"].bool == true,
                                underline = rEl["underline"].bool == true,
                                color = color,
                                sizePx = sizePx,
                                font = font,
                                breakLine = rEl["breakLine"].bool == true,
                            ),
                        )
                    }
                    val align = itemEl["align"].string?.takeIf { it in SLIDE_ALIGNS } ?: return null
                    val valign = itemEl["valign"].string?.takeIf { it in SLIDE_VALIGNS } ?: return null
                    val lh = if (itemEl.containsKey("lineHeightPx") && itemEl["lineHeightPx"] !is JsonNull) {
                        finiteCoord(itemEl["lineHeightPx"], 4000.0)?.takeIf { it > 0.0 } ?: return null
                    } else null
                    val bullet = itemEl["bullet"].string?.takeIf { it == "bullet" || it == "number" }
                    items.add(
                        SlideItem.Text(
                            x = x,
                            y = y,
                            w = w,
                            h = h,
                            rotation = rotation,
                            runs = runs,
                            align = align,
                            valign = valign,
                            lineHeightPx = lh,
                            bullet = bullet,
                        ),
                    )
                }
                "shape" -> {
                    val fill = if (itemEl.containsKey("fill") && itemEl["fill"] !is JsonNull) {
                        parseSlideColor(itemEl["fill"]) ?: return null
                    } else null
                    var borderW: Double? = null
                    var borderC: SlideColor? = null
                    if (itemEl.containsKey("border") && itemEl["border"] !is JsonNull) {
                        val bObj = itemEl["border"] as? JsonObject ?: return null
                        borderW = finiteCoord(bObj["widthPx"], 1000.0)?.takeIf { it > 0.0 } ?: return null
                        borderC = parseSlideColor(bObj["color"]) ?: return null
                    }
                    val radiusPx = if (itemEl.containsKey("radiusPx") && itemEl["radiusPx"] !is JsonNull) {
                        val r = finiteCoord(itemEl["radiusPx"]) ?: return null
                        if (r < 0.0) return null
                        if (r > 0.0) r else null
                    } else null
                    if (fill == null && borderC == null) return null
                    items.add(
                        SlideItem.Shape(
                            x = x,
                            y = y,
                            w = w,
                            h = h,
                            rotation = rotation,
                            fill = fill,
                            borderWidthPx = borderW,
                            borderColor = borderC,
                            radiusPx = radiusPx,
                        ),
                    )
                }
                "image" -> {
                    val data = itemEl["data"].string ?: return null
                    if (data.length > MAX_SLIDE_IMAGE_BYTES - imageBytesBudget) return null
                    if (!SLIDE_IMAGE_DATA_RE.matches(data)) return null
                    imageBytesBudget += data.length
                    items.add(
                        SlideItem.Image(
                            x = x,
                            y = y,
                            w = w,
                            h = h,
                            rotation = rotation,
                            data = data,
                        ),
                    )
                }
                else -> return null
            }
        }
        slides.add(Slide(background = bg, items = items))
    }
    return SlideDoc(width = width, height = height, slides = slides, warnings = warnings)
}

private fun parseSlideColor(raw: JsonElement?): SlideColor? {
    if (raw !is JsonObject) return null
    val hex = raw["hex"].string?.takeIf { SLIDE_HEX_RE.matches(it) } ?: return null
    val alpha = finiteCoord(raw["alpha"], 1.0)?.takeIf { it >= 0.0 } ?: return null
    return SlideColor(hex = hex, alpha = alpha)
}

private fun finiteCoord(raw: JsonElement?, max: Double = 100_000.0): Double? {
    val n = raw.double ?: return null
    return if (n.isFinite() && abs(n) <= max) n else null
}

/** A message from the page's bridge, validated; [nonce] is for the caller to compare. */
sealed interface BridgeChildMessage {
    val nonce: String?

    /** A new document has loaded and the bridge in it is listening. */
    data class Ready(
        override val nonce: String?,
        val gen: String,
        val cssGens: Map<String, String>,
        val file: String,
        val instrumented: Boolean,
        val title: String,
        val docHeight: Double,
    ) : BridgeChildMessage

    data class Scroll(
        override val nonce: String?,
        val x: Double,
        val y: Double,
    ) : BridgeChildMessage

    /** One of [ISSUE_KINDS]: an error, a rejected promise, a failed load or a CSP refusal. */
    data class Issue(
        override val nonce: String?,
        val kind: String,
        val message: String,
        val source: String? = null,
        val line: Double? = null,
    ) : BridgeChildMessage

    data class NavigateBlocked(
        override val nonce: String?,
        val href: String,
    ) : BridgeChildMessage

    data class OpenLink(
        override val nonce: String?,
        val href: String,
    ) : BridgeChildMessage

    data class Expired(override val nonce: String?) : BridgeChildMessage

    data class Hover(
        override val nonce: String?,
        val el: BridgeHoverElement?,
    ) : BridgeChildMessage

    data class Select(
        override val nonce: String?,
        val el: BridgePickedElement,
    ) : BridgeChildMessage

    data class ElementMenu(
        override val nonce: String?,
        val el: BridgePickedElement,
    ) : BridgeChildMessage

    data class PickerExit(override val nonce: String?) : BridgeChildMessage

    data class PinsRects(
        override val nonce: String?,
        val pins: List<BridgePinRect>,
    ) : BridgeChildMessage

    data class TweakValues(
        override val nonce: String?,
        val values: Map<String, String>,
        val winners: Map<String, String> = emptyMap(),
    ) : BridgeChildMessage

    data class TransformLive(
        override val nonce: String?,
        val ppmId: Int,
        val rect: BridgeRect?,
        val box: BridgeTransformBox?,
    ) : BridgeChildMessage

    data class TransformCommit(
        override val nonce: String?,
        val ppmId: Int,
        val tag: String,
        val gen: String,
        val file: String,
        val props: Map<String, String>,
    ) : BridgeChildMessage

    data class SlidesData(
        override val nonce: String?,
        val requestId: String,
        val doc: SlideDoc,
    ) : BridgeChildMessage

    data class SlidesError(
        override val nonce: String?,
        val requestId: String,
        val message: String,
    ) : BridgeChildMessage

    data class CheckResult(override val nonce: String?, val requestId: String, val report: LayoutCheckReport) : BridgeChildMessage

    data class CheckError(override val nonce: String?, val requestId: String, val message: String) : BridgeChildMessage

    companion object {
        /**
         * A validated child message, or null: a wrong envelope, an unknown type, or a field out of shape
         * (`parseChildMessage`).
         */
        fun parse(data: JsonElement?): BridgeChildMessage? {
            if (data !is JsonObject) return null
            if (data["ppm"].string != BRIDGE_CHANNEL || data["v"].double != BRIDGE_VERSION.toDouble()) return null
            val type = data["type"].string ?: return null
            val rawNonce = data["nonce"]
            val nonce = when {
                rawNonce is JsonNull -> null
                else -> rawNonce.string?.takeIf { BRIDGE_NONCE_RE.matches(it) } ?: return null
            }
            return when (type) {
                "ready" -> ready(data, nonce)
                "scroll" -> {
                    val x = finiteInRange(data["x"], 0.0, 100_000.0) ?: return null
                    val y = finiteInRange(data["y"], 0.0, 100_000.0) ?: return null
                    Scroll(nonce, x, y)
                }
                "issue" -> {
                    val kind = data["kind"].string?.takeIf { it in ISSUE_KINDS } ?: return null
                    val message = data["message"].string?.take(500) ?: return null
                    Issue(nonce, kind, message, data["source"].string?.take(1024), data["line"].double?.takeIf { it.isFinite() })
                }
                "navigate-blocked" -> {
                    val href = data["href"].string ?: return null
                    NavigateBlocked(nonce, href.take(2048))
                }
                "open-link" -> {
                    val href = data["href"].string?.takeIf { isSafeExternalHref(it) } ?: return null
                    OpenLink(nonce, href)
                }
                "expired" -> Expired(nonce)
                "hover" -> {
                    if (!data.containsKey("el")) return null
                    val rawEl = data["el"]
                    val el = if (rawEl is JsonNull) null else parseHoverEl(rawEl) ?: return null
                    Hover(nonce, el)
                }
                "select" -> {
                    val el = parsePickedElement(data["el"]) ?: return null
                    Select(nonce, el)
                }
                "element-menu" -> {
                    val el = parsePickedElement(data["el"]) ?: return null
                    ElementMenu(nonce, el)
                }
                "picker-exit" -> PickerExit(nonce)
                "pins-rects" -> parsePinsRects(data, nonce)
                "tweak-values" -> parseTweakValues(data, nonce)
                "transform-live" -> parseTransformLive(data, nonce)
                "transform-commit" -> parseTransformCommit(data, nonce)
                "slides-data" -> {
                    val id = requestId(data["requestId"]) ?: return null
                    val doc = parseSlideDoc(data["doc"]) ?: return null
                    SlidesData(nonce, id, doc)
                }
                "slides-error" -> {
                    val id = requestId(data["requestId"]) ?: return null
                    val message = data["message"].string ?: return null
                    SlidesError(nonce, id, message.take(300))
                }
                "check-result" -> {
                    val id = requestId(data["requestId"]) ?: return null
                    val report = LayoutCheckReport.from(data["report"]) ?: return null
                    CheckResult(nonce, id, report)
                }
                "check-error" -> {
                    val id = requestId(data["requestId"]) ?: return null
                    val message = data["message"].string ?: return null
                    CheckError(nonce, id, message.take(300))
                }
                else -> null
            }
        }

        /**
         * Validates [data] and verifies that its nonce matches [expectedNonce].
         */
        fun parseForNonce(data: JsonElement?, expectedNonce: String): BridgeChildMessage? {
            if (!BRIDGE_NONCE_RE.matches(expectedNonce)) return null
            val msg = parse(data) ?: return null
            return if (msg.nonce == expectedNonce) msg else null
        }

        private fun ready(m: JsonObject, nonce: String?): Ready? {
            val gen = m["gen"].string?.takeIf { DESIGN_GEN_RE.matches(it) } ?: return null
            val file = m["file"].string?.take(1024) ?: return null
            val gens = cssGens(m["cssGens"]) ?: return null
            val instrumented = m["instrumented"].bool ?: return null
            val docHeight = m["docHeight"].double?.takeIf { it.isFinite() } ?: 0.0
            return Ready(nonce, gen, gens, file, instrumented, m["title"].string?.take(200) ?: "", max(0.0, docHeight))
        }

        private fun cssGens(value: JsonElement?): Map<String, String>? {
            if (value !is JsonObject || value.size > 64) return null
            val out = LinkedHashMap<String, String>()
            for ((file, gen) in value) {
                val g = gen.string
                if (file.length > 512 || g == null || !DESIGN_GEN_RE.matches(g)) return null
                out[file] = g
            }
            return out
        }

        private fun finiteInRange(value: JsonElement?, min: Double, max: Double): Double? {
            val n = value.double ?: return null
            return if (n.isFinite() && n in min..max) n else null
        }

        private fun parseRect(value: JsonElement?): BridgeRect? {
            if (value !is JsonObject) return null
            val x = finiteInRange(value["x"], -100_000.0, 100_000.0) ?: return null
            val y = finiteInRange(value["y"], -100_000.0, 100_000.0) ?: return null
            val w = finiteInRange(value["w"], 0.0, 100_000.0) ?: return null
            val h = finiteInRange(value["h"], 0.0, 100_000.0) ?: return null
            return BridgeRect(x = x, y = y, w = w, h = h)
        }

        private fun parseHoverEl(value: JsonElement?): BridgeHoverElement? {
            if (value !is JsonObject) return null
            val tag = value["tag"].string?.lowercase()?.takeIf { BRIDGE_TAG_RE.matches(it) } ?: return null
            val rect = parseRect(value["rect"]) ?: return null
            return BridgeHoverElement(tag = tag, rect = rect)
        }

        private fun parseQuote(value: JsonElement?): BridgeTextQuote? {
            if (value !is JsonObject) return null
            val exact = value["exact"].string ?: return null
            val prefix = value["prefix"].string ?: return null
            val suffix = value["suffix"].string ?: return null
            return BridgeTextQuote(
                exact = exact.take(240),
                prefix = prefix.take(48),
                suffix = suffix.take(48),
            )
        }

        private fun parsePickedElement(value: JsonElement?): BridgePickedElement? {
            if (value !is JsonObject) return null
            val rawPpmId = value["ppmId"]
            val ppmId = when {
                rawPpmId == null || rawPpmId is JsonNull -> null
                else -> parseBridgePpmId(rawPpmId) ?: return null
            }
            val rawGen = value["gen"]
            val gen = when {
                rawGen == null || rawGen is JsonNull -> null
                else -> rawGen.string?.takeIf { DESIGN_GEN_RE.matches(it) } ?: return null
            }
            val file = value["file"].string?.takeIf { it.length in 1..256 } ?: return null
            val tag = value["tag"].string?.lowercase()?.takeIf { BRIDGE_TAG_RE.matches(it) } ?: return null
            val rect = parseRect(value["rect"]) ?: return null
            val cssPath = value["cssPath"].string?.takeIf { it.length <= 512 } ?: return null
            val outerHtml = value["outerHtml"].string ?: return null
            val text = value["text"].string ?: return null
            val quote = parseQuote(value["quote"]) ?: return null
            return BridgePickedElement(
                ppmId = ppmId,
                gen = gen,
                file = file,
                tag = tag,
                rect = rect,
                cssPath = cssPath,
                outerHtml = outerHtml.take(2048),
                text = text.take(240),
                quote = quote,
            )
        }

        private fun parsePinsRects(m: JsonObject, nonce: String?): PinsRects? {
            val arr = m["pins"].array?.takeIf { it.size <= MAX_PINS_PER_MESSAGE } ?: return null
            val out = ArrayList<BridgePinRect>(arr.size)
            for (item in arr) {
                if (item !is JsonObject) return null
                val id = item["id"].string?.takeIf { BRIDGE_COMMENT_ID_RE.matches(it) } ?: return null
                val rawRect = item["rect"]
                val rect = if (rawRect == null || rawRect is JsonNull) null else parseRect(rawRect) ?: return null
                val rawPpmId = item["ppmId"]
                val ppmId = when {
                    rawPpmId == null || rawPpmId is JsonNull -> null
                    else -> parseBridgePpmId(rawPpmId) ?: return null
                }
                val rawGen = item["gen"]
                val gen = when {
                    rawGen == null || rawGen is JsonNull -> null
                    else -> rawGen.string?.takeIf { DESIGN_GEN_RE.matches(it) } ?: return null
                }
                val reanchored = item["reanchored"].bool ?: return null
                if (reanchored && (ppmId == null || gen == null)) return null
                out.add(BridgePinRect(id = id, rect = rect, ppmId = ppmId, gen = gen, reanchored = reanchored))
            }
            return PinsRects(nonce, out)
        }

        private fun parseTweakValues(m: JsonObject, nonce: String?): TweakValues? {
            val values = parseTweakValuesMap(m["values"], allowEmpty = true) ?: return null
            val winners = if (!m.containsKey("winners") || m["winners"] is JsonNull) {
                emptyMap()
            } else {
                val wObj = m["winners"] as? JsonObject ?: return null
                if (wObj.size > MAX_TWEAKS_PER_MESSAGE) return null
                val wOut = LinkedHashMap<String, String>()
                for ((k, v) in wObj) {
                    val str = v.string ?: return null
                    if (!BRIDGE_CSS_VAR_RE.matches(k) || str.isEmpty() || str.length > 256) return null
                    wOut[k] = str
                }
                wOut
            }
            return TweakValues(nonce, values, winners)
        }

        private fun parseTransformLive(m: JsonObject, nonce: String?): TransformLive? {
            val ppmId = parseBridgePpmId(m["ppmId"]) ?: return null
            if (!m.containsKey("rect") || !m.containsKey("box")) return null
            val rawRect = m["rect"]
            val rect = if (rawRect is JsonNull) null else parseRect(rawRect) ?: return null
            val rawBox = m["box"]
            val box = if (rawBox is JsonNull) null else parseTransformBox(rawBox) ?: return null
            return TransformLive(nonce, ppmId, rect, box)
        }

        private fun parseTransformBox(value: JsonElement?): BridgeTransformBox? {
            if (value !is JsonObject) return null
            val tx = finiteInRange(value["tx"], -MAX_TRANSFORM_PX, MAX_TRANSFORM_PX) ?: return null
            val ty = finiteInRange(value["ty"], -MAX_TRANSFORM_PX, MAX_TRANSFORM_PX) ?: return null
            val w = finiteInRange(value["w"], 0.0, MAX_TRANSFORM_PX) ?: return null
            val h = finiteInRange(value["h"], 0.0, MAX_TRANSFORM_PX) ?: return null
            return BridgeTransformBox(tx = tx, ty = ty, w = w, h = h)
        }

        private fun parseTransformCommit(m: JsonObject, nonce: String?): TransformCommit? {
            val ppmId = parseBridgePpmId(m["ppmId"]) ?: return null
            val tag = m["tag"].string?.lowercase()?.takeIf { BRIDGE_TAG_RE.matches(it) } ?: return null
            val gen = m["gen"].string?.takeIf { DESIGN_GEN_RE.matches(it) } ?: return null
            val file = m["file"].string?.takeIf { it.length in 1..256 } ?: return null
            val props = parseBridgeTransformProps(m["props"]) ?: return null
            return TransformCommit(nonce, ppmId, tag, gen, file, props)
        }

        private fun requestId(value: JsonElement?): String? = value.string?.takeIf { CHECK_REQUEST_ID_RE.matches(it) }
    }
}

private fun parseTweakValuesMap(value: JsonElement?, allowEmpty: Boolean): Map<String, String>? {
    if (value !is JsonObject) return null
    val entries = value.entries
    if ((!allowEmpty && entries.isEmpty()) || entries.size > MAX_TWEAKS_PER_MESSAGE) return null
    val out = LinkedHashMap<String, String>()
    for ((k, raw) in entries) {
        val v = raw.string ?: return null
        val trimmed = v.trim()
        if (!BRIDGE_CSS_VAR_RE.matches(k) || trimmed.isEmpty() || trimmed.length > 64 || FORBIDDEN_TWEAK_VALUE_RE.containsMatchIn(trimmed)) {
            return null
        }
        out[k] = trimmed
    }
    return out
}

private fun parentEnvelope(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("type", type)
        build()
        put("ppm", BRIDGE_CHANNEL)
        put("v", BRIDGE_VERSION)
        put("nonce", null as String?)
    }

/** Parent → frame: `restore-scroll` (`nonce: null`). */
fun restoreScrollEnvelope(x: Double, y: Double): JsonObject? {
    if (!x.isFinite() || !y.isFinite() || x !in 0.0..100_000.0 || y !in 0.0..100_000.0) return null
    return parentEnvelope("restore-scroll") {
        put("x", x)
        put("y", y)
    }
}

/** Parent → frame: `picker` (`{ type: "picker", on, ppm, v, nonce: null }`). */
fun pickerEnvelope(on: Boolean): JsonObject =
    parentEnvelope("picker") {
        put("on", on)
    }

/** Parent → frame: `pins-set` (`{ type: "pins-set", pins: [{ id, anchor }], ppm, v, nonce: null }`). */
fun pinsSetEnvelope(pins: List<BridgePinTarget>): JsonObject? {
    if (pins.size > MAX_PINS_PER_MESSAGE) return null
    for (pin in pins) {
        if (!BRIDGE_COMMENT_ID_RE.matches(pin.id)) return null
        if (parseBridgeCommentAnchor(pin.anchor.json) == null) return null
    }
    return parentEnvelope("pins-set") {
        put(
            "pins",
            buildJsonArray {
                for (pin in pins) {
                    add(
                        buildJsonObject {
                            put("id", pin.id)
                            put("anchor", pin.anchor.json)
                        },
                    )
                }
            },
        )
    }
}

/** Parent → frame: `select-parent` (`nonce: null`). */
fun selectParentEnvelope(): JsonObject = parentEnvelope("select-parent")

/** Parent → frame: `clear-selection` (`nonce: null`). */
fun clearSelectionEnvelope(): JsonObject = parentEnvelope("clear-selection")

/** Parent → frame: `tweak-set` (`{ type: "tweak-set", values, ppm, v, nonce: null }`). */
fun tweakSetEnvelope(values: Map<String, String>): JsonObject? {
    if (values.isEmpty() || values.size > MAX_TWEAKS_PER_MESSAGE) return null
    val clean = LinkedHashMap<String, String>()
    for ((k, v) in values) {
        val trimmed = v.trim()
        if (!BRIDGE_CSS_VAR_RE.matches(k) || trimmed.isEmpty() || trimmed.length > 64 || FORBIDDEN_TWEAK_VALUE_RE.containsMatchIn(trimmed)) {
            return null
        }
        clean[k] = trimmed
    }
    return parentEnvelope("tweak-set") {
        put("values", buildJsonObject { clean.forEach { (k, v) -> put(k, v) } })
    }
}

fun tweakSetEnvelope(name: String, value: String): JsonObject? =
    tweakSetEnvelope(mapOf(name to value))

/** Parent → frame: `tweak-reset` (`{ type: "tweak-reset", vars?, ppm, v, nonce: null }`). */
fun tweakResetEnvelope(vars: List<String>? = null): JsonObject? {
    if (vars != null) {
        if (vars.size > MAX_TWEAKS_PER_MESSAGE || vars.any { !BRIDGE_CSS_VAR_RE.matches(it) }) return null
    }
    return parentEnvelope("tweak-reset") {
        if (vars != null) {
            put("vars", buildJsonArray { vars.forEach { add(JsonPrimitive(it)) } })
        }
    }
}

/** Parent → frame: `tweak-read` (`{ type: "tweak-read", vars, ppm, v, nonce: null }`). */
fun tweakReadEnvelope(vars: List<String>): JsonObject? {
    if (vars.size > MAX_TWEAKS_PER_MESSAGE || vars.any { !BRIDGE_CSS_VAR_RE.matches(it) }) return null
    return parentEnvelope("tweak-read") {
        put("vars", buildJsonArray { vars.forEach { add(JsonPrimitive(it)) } })
    }
}

/** Parent → frame: `transform-mode` (`{ type: "transform-mode", on, scale, ppm, v, nonce: null }`). */
fun transformModeEnvelope(on: Boolean, scale: Double = 1.0): JsonObject? {
    if (!scale.isFinite() || scale !in 0.05..10.0) return null
    return parentEnvelope("transform-mode") {
        put("on", on)
        put("scale", scale)
    }
}

/** Parent → frame: `transform-target` (`{ type: "transform-target", ppmId, tag, ppm, v, nonce: null }`). */
fun transformTargetEnvelope(ppmId: Int?, tag: String = ""): JsonObject? {
    if (ppmId == null) {
        return parentEnvelope("transform-target") {
            put("ppmId", JsonNull)
            put("tag", "")
        }
    }
    if (ppmId < 0) return null
    val lowerTag = tag.lowercase()
    if (!BRIDGE_TAG_RE.matches(lowerTag)) return null
    return parentEnvelope("transform-target") {
        put("ppmId", ppmId)
        put("tag", lowerTag)
    }
}

/** Parent → frame: `transform-nudge` (`{ type: "transform-nudge", dx, dy, ppm, v, nonce: null }`). */
fun transformNudgeEnvelope(dx: Int, dy: Int): JsonObject? {
    if ((dx == 0 && dy == 0) || abs(dx) > MAX_NUDGE_PX || abs(dy) > MAX_NUDGE_PX) return null
    return parentEnvelope("transform-nudge") {
        put("dx", dx)
        put("dy", dy)
    }
}

/** Parent → frame: `transform-cancel` (`nonce: null`). */
fun transformCancelEnvelope(): JsonObject = parentEnvelope("transform-cancel")

/** Parent → frame: `slides-extract` (`{ type: "slides-extract", requestId, ppm, v, nonce: null }`). */
fun slidesExtractEnvelope(requestId: String): JsonObject? {
    if (!CHECK_REQUEST_ID_RE.matches(requestId)) return null
    return parentEnvelope("slides-extract") {
        put("requestId", requestId)
    }
}

/**
 * `check-run`, as the parent posts it: asks the bridge to measure the live page, with an image
 * when [screenshot], for which [lib] carries the screenshot library's source — the frame can load
 * nothing from the app, so the parent hands the code over. Null when the request is out of shape,
 * as the web's parent validates a message before it leaves.
 *
 * No nonce: the frame never needs one to trust its parent, and sending one would hand it to any
 * page the frame navigated itself to, for that page to echo straight back and forge a `ready`.
 */
fun checkRunEnvelope(requestId: String, screenshot: Boolean, lib: String? = null): JsonObject? {
    if (!CHECK_REQUEST_ID_RE.matches(requestId)) return null
    if (lib != null && lib.length > MAX_CHECK_LIB_CHARS) return null
    return buildJsonObject {
        put("type", "check-run")
        put("requestId", requestId)
        put("screenshot", screenshot)
        if (lib != null) put("lib", lib)
        put("ppm", BRIDGE_CHANNEL)
        put("v", BRIDGE_VERSION)
        put("nonce", null as String?)
    }
}

private val random = SecureRandom()

/** 16 random bytes as base64url: 22 characters, inside the nonce's shape. Also a check's request id. */
fun newBridgeNonce(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
