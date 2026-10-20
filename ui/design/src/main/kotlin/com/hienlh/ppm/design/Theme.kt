package com.hienlh.ppm.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.geometry.Size

/** An sRGB colour with alpha, as the web's theme tokens write them. */
@Immutable
data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double) {
    val color: Color get() = Color(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat())

    /** Tailwind's `/NN` modifier: the same colour at [alpha] times its own opacity. */
    fun opacity(alpha: Double) = Rgba(r, g, b, a * alpha)

    /** CSS `color-mix(in srgb, this fraction, other)`. */
    fun mix(other: Rgba, fraction: Double): Rgba {
        val f = fraction.coerceIn(0.0, 1.0)
        val alpha = a * f + other.a * (1 - f)
        if (alpha <= 0) return Rgba(0.0, 0.0, 0.0, 0.0)
        // Premultiplied, as the CSS spec mixes.
        fun ch(x: Double, y: Double) = (x * a * f + y * other.a * (1 - f)) / alpha
        return Rgba(ch(r, other.r), ch(g, other.g), ch(b, other.b), alpha)
    }

    /** This colour painted over an opaque [base]. */
    fun over(base: Rgba) = Rgba(r * a + base.r * (1 - a), g * a + base.g * (1 - a), b * a + base.b * (1 - a), 1.0)

    companion object {
        /** `#rgb`, `#rrggbb` or `#rrggbbaa`; null for anything else. */
        fun hex(text: String): Rgba? {
            var s = text.trim()
            if (!s.startsWith("#")) return null
            s = s.substring(1)
            if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
            if (s.length != 6 && s.length != 8) return null
            val v = s.toLongOrNull(16) ?: return null
            fun byte(shift: Int) = ((v shr shift) and 0xFF) / 255.0
            return if (s.length == 6) Rgba(byte(16), byte(8), byte(0), 1.0) else Rgba(byte(24), byte(16), byte(8), byte(0))
        }
    }
}

@Immutable
data class GradientStop(val color: Rgba, val location: Double)

/** The app backdrop: Aurora's soft radial gradient, or one flat colour. */
@Immutable
sealed interface ThemeBackground {
    data class Solid(val color: Rgba) : ThemeBackground

    /** CSS `radial-gradient(R% R% at X% Y%, …)`; [radius] and the centre are fractions of the box. */
    data class Radial(val radius: Double, val centerX: Double, val centerY: Double, val stops: List<GradientStop>) : ThemeBackground
}

/** A CSS `box-shadow`, in CSS pixels (dp here). */
@Immutable
data class ThemeShadow(val x: Double, val y: Double, val blur: Double, val spread: Double, val color: Rgba)

/** The web's `PpmThemeTokens` (src/web/theme/types.ts), minus `blur`. */
@Immutable
data class ThemeTokens(
    val background: ThemeBackground,
    val bgSolid: Rgba,
    val rail: Rgba,
    val panel: Rgba,
    val panel2: Rgba,
    val border: Rgba,
    val borderSoft: Rgba,
    val text: Rgba,
    val text2: Rgba,
    val text3: Rgba,
    val accent: Rgba,
    val accentFg: Rgba,
    val accent2: Rgba,
    val accentWash: Rgba,
    val accentWashBorder: Rgba,
    val success: Rgba,
    val warning: Rgba,
    val error: Rgba,
    val info: Rgba,
    val shadowPanel: ThemeShadow,
    val shadowFloat: ThemeShadow,
    /** Card / panel radius (`--rad`). */
    val rad: Double,
    /** Chip / button radius (`--rad-sm`). */
    val radSm: Double,
) {
    /** `--color-surface-hover`: the text colour at 8%, so it shows on every theme. */
    val surfaceHover: Rgba get() = text.opacity(0.08)

    /** `--color-text-dim`: halfway between text-2 and text-3 as a colour. */
    val textDim: Rgba get() = text2.mix(text3, 0.5)

    /**
     * An unread session's colour by what happened last (`notificationColor`): an approval or
     * anything unknown in red, a question in amber, a finished turn in the accent.
     */
    fun notification(type: String?): Rgba = when (type) {
        "question" -> warning
        "done" -> accent
        else -> error
    }
}

/** One of the web's built-in themes. */
@Immutable
data class PpmTheme(
    val id: String,
    val name: String,
    /** `aurora`, `slate` or `precision`. */
    val style: String,
    val isDark: Boolean,
    val swatch: List<Rgba>,
    /** The Shiki theme code blocks use. */
    val shikiTheme: String,
    val tokens: ThemeTokens,
    /** [tokens] with the overrides the web applies on phone-sized viewports (`mobileOverrides`). */
    val mobileTokens: ThemeTokens,
) {
    companion object {
        /**
         * `{style, mode}` → theme, as `resolveTheme` does on the web. `system` follows the OS; an
         * imported (custom) theme is not drawn natively yet, so it falls back to Aurora.
         */
        fun resolve(style: String, mode: String, systemIsDark: Boolean): PpmTheme {
            val dark = if (mode == "system") systemIsDark else mode != "light"
            val id = "$style-${if (dark) "dark" else "light"}"
            return BuiltInThemes.all.firstOrNull { it.id == id } ?: if (dark) BuiltInThemes.auroraDark else BuiltInThemes.auroraLight
        }
    }
}

/** The theme every PPM view draws with. */
val LocalPpmTheme = staticCompositionLocalOf { BuiltInThemes.auroraDark }

/** Its tokens as this layout uses them: a phone layout gets the theme's mobile overrides. */
val LocalPpmTokens = staticCompositionLocalOf { BuiltInThemes.auroraDark.tokens }

/** The web's `useIsMobile()`: the phone layout, where touch targets grow to 44px. */
val LocalPpmMobile = staticCompositionLocalOf { false }

/** Provides [theme] below; [mobile] is the web's `(max-width: 767px)`. */
@Composable
fun PpmThemeProvider(theme: PpmTheme, mobile: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPpmTheme provides theme,
        LocalPpmTokens provides if (mobile) theme.mobileTokens else theme.tokens,
        LocalPpmMobile provides mobile,
        content = content,
    )
}

/** The backdrop as a fill for a whole window or pane. */
fun Modifier.themeBackground(background: ThemeBackground): Modifier = when (background) {
    is ThemeBackground.Solid -> drawBehind { drawRect(background.color.color) }
    is ThemeBackground.Radial -> drawBehind { drawRect(RadialBrush(background)) }
}

/**
 * CSS draws `radial-gradient(R% R% at …)` as an ellipse whose radii are R% of the box's width and
 * of its height; a circular shader scaled on one axis draws the same.
 */
private class RadialBrush(private val gradient: ThemeBackground.Radial) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val center = Offset((gradient.centerX * size.width).toFloat(), (gradient.centerY * size.height).toFloat())
        val rx = (gradient.radius * size.width).toFloat().coerceAtLeast(1f)
        val ry = (gradient.radius * size.height).toFloat().coerceAtLeast(1f)
        val shader = RadialGradientShader(
            center = center,
            radius = rx,
            colors = gradient.stops.map { it.color.color },
            colorStops = gradient.stops.map { it.location.toFloat() },
        )
        shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, ry / rx, center.x, center.y) })
        return shader
    }

    override fun equals(other: Any?) = other is RadialBrush && other.gradient == gradient

    override fun hashCode() = gradient.hashCode()
}

/**
 * A CSS box-shadow, spread included (Compose's drop shadow takes the same four numbers), and only
 * outside the box, where CSS draws one: Compose's own `dropShadow` also fills the shape beneath, so
 * a see-through background showed it (the user's `bg-accent-wash` bubble came out grey).
 */
fun Modifier.ppmShadow(shadow: ThemeShadow, shape: Shape): Modifier = this then OuterShadowElement(
    shape,
    Shadow(
        radius = shadow.blur.dp,
        spread = shadow.spread.dp,
        color = shadow.color.color,
        offset = DpOffset(shadow.x.dp, shadow.y.dp),
    ),
)

private data class OuterShadowElement(val shape: Shape, val shadow: Shadow) : ModifierNodeElement<OuterShadowNode>() {
    override fun create() = OuterShadowNode(shape, shadow)

    override fun update(node: OuterShadowNode) = node.update(shape, shadow)
}

/** Compose's `SimpleDropShadowNode`, the shadow drawn with the box itself clipped out. */
private class OuterShadowNode(var shape: Shape, var shadow: Shadow) : Modifier.Node(), DrawModifierNode {
    private var painter: DropShadowPainter? = null
    private val outline = Path()

    fun update(shape: Shape, shadow: Shadow) {
        if (shape == this.shape && shadow == this.shadow) return
        this.shape = shape
        this.shadow = shadow
        painter = null
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val painter = painter ?: requireGraphicsContext().shadowContext.createDropShadowPainter(shape, shadow).also { painter = it }
        outline.reset()
        outline.addOutline(shape.createOutline(size, layoutDirection, this))
        clipPath(outline, ClipOp.Difference) { with(painter) { draw(size) } }
        drawContent()
    }
}
