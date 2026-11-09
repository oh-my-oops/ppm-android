package com.hienlh.ppm.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

private val vectors = ConcurrentHashMap<ProductIcon, ImageVector>()

/**
 * The glyph in its 20×20 box, filled black to be tinted. Path data is generated from the web's own
 * icon module, so a parse failure is a generator bug; it would throw on first use in a test.
 */
val ProductIcon.vector: ImageVector
    get() = vectors.getOrPut(this) {
        ImageVector.Builder(name = name, defaultWidth = 20.dp, defaultHeight = 20.dp, viewportWidth = 20f, viewportHeight = 20f)
            .apply { for (d in pathData) addPath(addPathNodes(separateArcFlags(d)), fill = SolidColor(Color.Black)) }
            .build()
    }

private val lucideVectors = ConcurrentHashMap<LucideIcon, ImageVector>()

/** The glyph in its 24×24 box, stroked black to be tinted: lucide's 2 wide, round caps and joins. */
val LucideIcon.vector: ImageVector
    get() = lucideVectors.getOrPut(this) { lucideOutline(name, pathData) }

/** [pathData] in a 24×24 box drawn as [LucideIcon]s are: for an icon the web writes out as its own `<svg>` in lucide's style. */
fun lucideOutline(name: String, pathData: List<String>): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            for (d in pathData) {
                addPath(
                    addPathNodes(separateArcFlags(d)),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        .build()

/**
 * `<Icon className="size-4" />` — one of the web's product icons at [size], in [tint]. [spinning]
 * is Tailwind's `animate-spin` (one turn a second).
 */
@Composable
fun PpmIcon(icon: ProductIcon, size: Dp, tint: Color, modifier: Modifier = Modifier, spinning: Boolean = false) =
    VectorIcon(icon.vector, size, tint, modifier, spinning)

/** One of the icons the web keeps on lucide, drawn the same way. */
@Composable
fun PpmIcon(icon: LucideIcon, size: Dp, tint: Color, modifier: Modifier = Modifier, spinning: Boolean = false) =
    VectorIcon(icon.vector, size, tint, modifier, spinning)

/** Either kind of icon by its [vector][ProductIcon.vector], for a map that holds both (`TAB_TYPE_ICONS`). */
@Composable
fun PpmIcon(vector: ImageVector, size: Dp, tint: Color, modifier: Modifier = Modifier, spinning: Boolean = false) =
    VectorIcon(vector, size, tint, modifier, spinning)

@Composable
private fun VectorIcon(vector: ImageVector, size: Dp, tint: Color, modifier: Modifier, spinning: Boolean) {
    val painter = rememberVectorPainter(vector)
    var base = modifier.size(size)
    if (spinning) {
        val turn by rememberInfiniteTransition(label = "spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart),
            label = "spin",
        )
        base = base.rotate(turn)
    }
    Box(base.paint(painter, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(tint)))
}

/**
 * [d] with each arc's two flags set apart from their neighbours. SVG lets a flag run into what
 * follows it (`0 00.04.155` is flags 0 and 0, then .04 and .155; `0 01-.017` is flags 0 and 1,
 * then -.017), and Compose's parser reads some of those as numbers, which shifts every argument
 * after them — the Codex mark grew a stray lobe. Only the spacing changes.
 */
internal fun separateArcFlags(d: String): String {
    val out = StringBuilder(d.length + 32)
    var command = ' '
    var arg = 0
    var i = 0
    while (i < d.length) {
        val c = d[i]
        when {
            c.isLetter() -> { command = c; arg = 0; out.append(c); i++ }
            c == ',' || c.isWhitespace() -> i++
            else -> {
                val end = if ((command == 'a' || command == 'A') && arg % 7 in 3..4) i + 1 else svgNumberEnd(d, i)
                out.append(' ').append(d, i, end)
                arg++
                i = end
            }
        }
    }
    return out.toString()
}

/** Where the number at [start] ends: a sign, digits with at most one point, an exponent. */
private fun svgNumberEnd(d: String, start: Int): Int {
    var i = start
    if (d[i] == '+' || d[i] == '-') i++
    var point = false
    while (i < d.length) {
        val c = d[i]
        when {
            c.isDigit() -> i++
            c == '.' && !point -> { point = true; i++ }
            c == 'e' || c == 'E' -> { point = true; i++; if (i < d.length && (d[i] == '+' || d[i] == '-')) i++ }
            else -> break
        }
    }
    return maxOf(i, start + 1)
}

/** The AI providers' own logos, as the web draws them (src/web/lib/provider-logos.tsx). */
object ProviderLogos {
    /** Providers with artwork; others get the lettered tile. */
    fun has(providerId: String) = providerId in setOf("claude", "codex", "cursor", "gemini", "antigravity")

    private val paths = ConcurrentHashMap<String, Path>()

    internal fun pathData(providerId: String): String? = when (providerId) {
        "claude" -> ProviderLogoPaths.CLAUDE
        "codex" -> ProviderLogoPaths.CODEX
        "cursor" -> ProviderLogoPaths.CURSOR
        "gemini" -> ProviderLogoPaths.GEMINI
        "antigravity" -> ProviderLogoPaths.ANTIGRAVITY
        else -> null
    }

    internal fun path(providerId: String): Path? {
        val d = pathData(providerId) ?: return null
        return paths.getOrPut(providerId) {
            PathParser().parsePathString(separateArcFlags(d)).toPath().apply {
                if (providerId == "codex" || providerId == "cursor") fillType = PathFillType.EvenOdd
            }
        }
    }
}

/** A provider's logo at [size]; [foreground] fills Cursor's, which is drawn in the text colour. */
@Composable
fun ProviderLogo(providerId: String, size: Dp, foreground: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val path = ProviderLogos.path(providerId) ?: return@Canvas
        val s = this.size.width / 24f
        fun hex(h: String, alpha: Double = 1.0) = (Rgba.hex(h) ?: Rgba(0.0, 0.0, 0.0, 1.0)).opacity(alpha).color
        fun linear(color: String, start: Offset, end: Offset, fadeAt: Float) =
            Brush.linearGradient(0f to hex(color), fadeAt to hex(color, 0.0), start = start, end = end)
        // Gradients are given in the logo's own 24×24 units, as its SVG gives them.
        scale(s, s, pivot = Offset.Zero) {
            when (providerId) {
                "claude" -> drawPath(path, hex("#D97757"))
                "codex" -> drawPath(
                    path,
                    Brush.linearGradient(0f to hex("#B1A7FF"), 0.5f to hex("#7A9DFF"), 1f to hex("#3941FF"), start = Offset(12f, 0f), end = Offset(12f, 24f)),
                )
                "cursor" -> drawPath(path, foreground)
                "gemini" -> {
                    // One silhouette painted four times: a blue base and three washes that fade out.
                    drawPath(path, hex("#3186FF"))
                    drawPath(path, linear("#08B962", Offset(7f, 15.5f), Offset(11f, 12f), 1f))
                    drawPath(path, linear("#F94543", Offset(8f, 5.5f), Offset(11.5f, 11f), 1f))
                    drawPath(path, linear("#FABC12", Offset(3.5f, 13.5f), Offset(17.5f, 12f), 0.46f))
                }
                "antigravity" -> {
                    // Google Antigravity "A" arch mark: a blue base with green-yellow (top-left),
                    // red-orange (top-right), and peach-gold (apex) radial washes.
                    drawPath(
                        path,
                        Brush.linearGradient(0f to hex("#3186FF"), 1f to hex("#4D9AF9"), start = Offset(12f, 9f), end = Offset(12f, 21.2f)),
                    )
                    drawPath(
                        path,
                        Brush.radialGradient(
                            0f to hex("#FABC12"),
                            0.45f to hex("#4BC76E"),
                            1f to hex("#3FA3BD", 0.0),
                            center = Offset(8.6f, 5.0f),
                            radius = 7.5f,
                        ),
                    )
                    drawPath(
                        path,
                        Brush.radialGradient(
                            0f to hex("#F9543F"),
                            0.5f to hex("#EB6E55", 0.75),
                            1f to hex("#808ADB", 0.0),
                            center = Offset(15.2f, 4.8f),
                            radius = 7.5f,
                        ),
                    )
                    drawPath(
                        path,
                        Brush.radialGradient(
                            0f to hex("#FBBE94"),
                            1f to hex("#FBBE94", 0.0),
                            center = Offset(12f, 3.0f),
                            radius = 3.2f,
                        ),
                    )
                }
            }
        }
    }
}
