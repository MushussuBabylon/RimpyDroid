package com.rimpydroid.manager.ui

import android.graphics.RenderEffect as PlatformRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

// Real liquid glass.
//
// The lens is the AGSL shader of the Liquid-Glass-Android library (https://github.com/QWEA0/Liquid-Glass-Android,
// MIT licence, see THIRD_PARTY_NOTICES.md), ported from its View based renderer to Compose. The shader draws a rounded
// box from a signed distance field, works out the edge normal from that field, bends what is behind the glass along
// the normal (refraction: the edge shows a compressed, mirrored band of the background), splits the three colour
// channels (dispersion), lifts the saturation, tints the glass and adds the two specular rim lobes. Everything the
// library does to a View is done here to a GraphicsLayer: the backdrop is recorded into the layer (with a margin
// around it, so that the blur has something to read at the edges), a blur and the lens shader are put on the layer as
// one RenderEffect, and the layer is drawn behind the content of the element.
//
// The library lets the glass read the views behind it. Here the glass reads the app's own backdrop, which is a
// picture that can be drawn from its parameters (see drawWallpaper): every glass element draws the part of it that is
// behind itself, so nothing has to be captured, there is no recursion, and it works in a Dialog window as well.
//
// RuntimeShader needs Android 13 (API 33). Below it, or when a driver refuses the shader, the glass falls back to the
// see-through fill of the earlier version (drawFakeGlass in Glass.kt), so the app looks the same as before there.

/** Whether the shader could be used on this phone. A shader that failed to compile switches the lens off for good. */
internal object LiquidGlassSupport {
    @Volatile
    var broken: Boolean = false

    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !broken
}

/** How thick a pane of glass is: bigger things get a wider bevel and a stronger lens than small controls. */
internal enum class GlassKind(
    val blur: Dp,
    val bevel: Dp,
    val refraction: Dp,
    val specular: Float,
    val saturation: Float,
) {
    /** Cards and panels. */
    PANEL(blur = 4.dp, bevel = 14.dp, refraction = 20.dp, specular = 0.85f, saturation = 1.35f),

    /** Buttons, chips and text fields. */
    CONTROL(blur = 2.dp, bevel = 10.dp, refraction = 12.dp, specular = 1.0f, saturation = 1.25f),

    /** Dialogs and bars: the thickest glass. */
    SHEET(blur = 8.dp, bevel = 18.dp, refraction = 26.dp, specular = 0.9f, saturation = 1.30f),
}

/** The parameters of one lens; the RenderEffect is rebuilt only when they change. */
internal data class LensParams(
    val width: Int,
    val height: Int,
    val margin: Int,
    val radiusTopLeft: Float,
    val radiusTopRight: Float,
    val radiusBottomRight: Float,
    val radiusBottomLeft: Float,
    val blur: Float,
    val bevel: Float,
    val refraction: Float,
    val rimBand: Float,
    val specular: Float,
    val saturation: Float,
    val tint: Int,
    val glassTint: Int,
)

/** The lens shader, as in Liquid-Glass-Android's GlassLensRenderer (inward refraction, one shape, no touch bulge). */
private const val LENS_AGSL = """
    uniform shader content;
    uniform float  margin;
    uniform float2 viewSize;
    uniform float4 shape;
    uniform float4 radii;
    uniform float  bevel;
    uniform float  refractPx;
    uniform float  falloff;
    uniform float2 sampleLo;
    uniform float2 sampleHi;
    uniform float  dispersion;
    uniform float2 lightDir;
    uniform float  specStrength;
    uniform float  rimBandMax;
    uniform float4 tintColor;
    uniform float4 glassTint;
    uniform float  satFactor;

    // signed distance to a rounded box with a radius per corner: radii = (top left, top right, bottom right, bottom left)
    float sdRoundedBox4(float2 p, float2 b, float4 r) {
        float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
        float2 q = abs(p) - b + rx;
        return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;
    }

    float lensSDF(float2 p) {
        return sdRoundedBox4(p - shape.xy, shape.zw, radii);
    }

    half4 main(float2 coord) {
        float2 p = coord - float2(margin, margin);
        // outside of the view rectangle nothing is drawn (the margin only feeds the blur)
        if (p.x < 0.0 || p.y < 0.0 || p.x > viewSize.x || p.y > viewSize.y) {
            return half4(0.0);
        }
        float d = lensSDF(p);

        // coverage: 1.5 px of anti-aliasing, fully transparent outside of the shape
        float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
        if (cov <= 0.004) {
            return half4(0.0);
        }

        // outward normal in screen space from the numeric gradient of the distance field
        float2 n = float2(
            lensSDF(p + float2(1.0, 0.0)) - lensSDF(p - float2(1.0, 0.0)),
            lensSDF(p + float2(0.0, 1.0)) - lensSDF(p - float2(0.0, 1.0))
        );
        float nLen = length(n);
        if (nLen > 0.0001) {
            n = n / nLen;
        } else {
            n = float2(0.0, -1.0);
        }

        // thickness profile: t = 1 on the flat inside, t = 0 at the edge; slope is the steepness of the bevel
        float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
        float edge = 1.0 - t;
        float slope;
        if (falloff > 0.001) {
            // inverse power fall-off (gravitational-lens like): the bend is concentrated in the outermost pixels
            float gB = pow(5.0, -falloff);
            slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);
        } else {
            slope = edge * edge;
        }

        // refraction: sample along the inward normal, so the rim shows a compressed mirror of the inside
        float2 offset = n * (-slope * refractPx);

        // chromatic dispersion: the three channels bend by different amounts, a spectral fringe at the edge
        float2 cR = coord + offset * (1.0 - dispersion * slope);
        float2 cG = coord + offset;
        float2 cB = coord + offset * (1.0 + dispersion * slope);

        // never read outside of the recorded content
        cR = clamp(cR, sampleLo, sampleHi);
        cG = clamp(cG, sampleLo, sampleHi);
        cB = clamp(cB, sampleLo, sampleHi);
        float3 col = float3(content.eval(cR).r, content.eval(cG).g, content.eval(cB).b);

        // saturation, with a vibrancy curve: low-saturation pixels gain more, bright ones are protected
        float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
        if (satFactor <= 1.0) {
            col = mix(float3(lum), col, satFactor);
        } else {
            float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
            float room = 1.0 - smoothstep(0.2, 0.85, satNow);
            float hl = 1.0 - smoothstep(0.75, 0.98, lum);
            float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
            col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
        }

        // base tint of the material
        col = mix(col, tintColor.rgb, tintColor.a);

        // body colour of the glass, as a coloured medium: absorption keeps the light and dark of the background,
        // a little scattering keeps the hue visible on dark backgrounds. Before the lighting: tint is transmission,
        // the specular rim is surface reflection and is not tinted
        if (glassTint.a > 0.002) {
            float lumTint = dot(col, float3(0.2126, 0.7152, 0.0722));
            float3 absorbed = col * mix(float3(1.0), glassTint.rgb, 0.85);
            float3 scattered = glassTint.rgb * (0.38 * (1.0 - lumTint));
            col = mix(col, clamp(absorbed + scattered, float3(0.0), float3(1.0)), glassTint.a);
        }

        // lighting from the same normal field: two symmetric lobes (the lit side and the inner wall opposite to it),
        // no constant term, so no fixed outline
        float facing = dot(n, -lightDir);
        float lobeF = pow(max(facing, 0.0), 4.5);
        float lobeB = pow(max(-facing, 0.0), 4.5);

        // bright hairline 1 px inside the edge plus a soft glow on the lit side
        float bandW = clamp(bevel * 0.3, 2.0, rimBandMax);
        float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
        float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;
        float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;
        float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF) * specStrength;
        col += float3(spec);

        col = clamp(col, float3(0.0), float3(1.0));
        return half4(half3(col * cov), half(cov));
    }
"""

/** One lens: a RuntimeShader and the RenderEffect built from it. Every glass element has its own. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LensEffect {
    private val shader = RuntimeShader(LENS_AGSL)
    private var lastParams: LensParams? = null
    private var lastEffect: RenderEffect? = null

    fun effect(p: LensParams): RenderEffect {
        val cached = lastEffect
        if (cached != null && p == lastParams) return cached
        shader.setFloatUniform("margin", p.margin.toFloat())
        shader.setFloatUniform("viewSize", p.width.toFloat(), p.height.toFloat())
        shader.setFloatUniform("shape", p.width / 2f, p.height / 2f, p.width / 2f, p.height / 2f)
        shader.setFloatUniform("radii", p.radiusTopLeft, p.radiusTopRight, p.radiusBottomRight, p.radiusBottomLeft)
        shader.setFloatUniform("bevel", p.bevel)
        shader.setFloatUniform("refractPx", p.refraction)
        shader.setFloatUniform("falloff", FALLOFF)
        shader.setFloatUniform("sampleLo", p.margin + 1f, p.margin + 1f)
        shader.setFloatUniform("sampleHi", p.margin + p.width - 1f, p.margin + p.height - 1f)
        shader.setFloatUniform("dispersion", DISPERSION)
        shader.setFloatUniform("lightDir", LIGHT_X, LIGHT_Y)
        shader.setFloatUniform("specStrength", p.specular)
        shader.setFloatUniform("rimBandMax", p.rimBand)
        shader.setFloatUniform("tintColor", red(p.tint), green(p.tint), blue(p.tint), alpha(p.tint))
        shader.setFloatUniform("glassTint", red(p.glassTint), green(p.glassTint), blue(p.glassTint), alpha(p.glassTint))
        shader.setFloatUniform("satFactor", p.saturation)

        val lens = PlatformRenderEffect.createRuntimeShaderEffect(shader, "content")
        val chained = if (p.blur > 0.01f) {
            PlatformRenderEffect.createChainEffect(
                lens,
                PlatformRenderEffect.createBlurEffect(p.blur, p.blur, Shader.TileMode.CLAMP),
            )
        } else {
            lens
        }
        val built = chained.asComposeRenderEffect()
        lastParams = p
        lastEffect = built
        return built
    }

    private fun alpha(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f
    private fun red(argb: Int): Float = ((argb shr 16) and 0xFF) / 255f
    private fun green(argb: Int): Float = ((argb shr 8) and 0xFF) / 255f
    private fun blue(argb: Int): Float = (argb and 0xFF) / 255f

    private companion object {
        // the library's defaults: inverse-power fall-off 2, dispersion 0.10, light from the upper left
        const val FALLOFF = 2f
        const val DISPERSION = 0.10f
        const val LIGHT_X = 0.866f
        const val LIGHT_Y = 0.5f
    }
}

/**
 * The glass of a panel. Where the lens works ([LiquidGlassSupport.available], and a backdrop to read), this is the real
 * thing: refraction, dispersion and light along the rim, over the app's backdrop. Everywhere else it is the see-through
 * fill of the earlier version. [tint] colours the glass body (the container colour of a card, the accent of a button).
 */
@Composable
internal fun Modifier.glassBackground(
    shape: Shape,
    dark: Boolean,
    tint: Color = Color.Transparent,
    kind: GlassKind = GlassKind.PANEL,
): Modifier {
    val host = LocalGlassBackdrop.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && host != null && host.real && LiquidGlassSupport.available) {
        return this.liquidGlass(host, shape, dark, tint, kind)
    }
    return this.drawBehind { drawFakeGlass(shape, dark, tint) }
}

/** True when panels are drawn with the real lens (so they need no extra border: the shader draws the rim). */
@Composable
internal fun rememberRealGlass(): Boolean {
    val host = LocalGlassBackdrop.current
    return LocalGlass.current && host != null && host.real && LiquidGlassSupport.available
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.liquidGlass(
    host: GlassBackdropState,
    shape: Shape,
    dark: Boolean,
    tint: Color,
    kind: GlassKind,
): Modifier {
    val view = LocalView.current
    val layer = rememberGraphicsLayer()
    val lens = remember {
        try {
            LensEffect()
        } catch (e: Throwable) {
            // the shader did not compile on this phone: no lens, for good
            LiquidGlassSupport.broken = true
            null
        }
    }
    // where this element is on the screen, in pixels; it changes while a list scrolls
    var spot by remember { mutableStateOf(Offset.Zero) }
    if (lens == null) return this.drawBehind { drawFakeGlass(shape, dark, tint) }
    return this
        .onGloballyPositioned { coordinates ->
            val inRoot = coordinates.positionInRoot()
            val screen = IntArray(2)
            view.getLocationOnScreen(screen)
            val next = Offset(inRoot.x + screen[0], inRoot.y + screen[1])
            if (next != spot) spot = next
        }
        .drawBehind {
            val drawn = try {
                drawLiquidGlass(host, lens, layer, shape, dark, tint, kind, spot)
            } catch (e: Throwable) {
                LiquidGlassSupport.broken = true
                false
            }
            if (!drawn) drawFakeGlass(shape, dark, tint)
        }
}

/** Draws the lens for the element this scope belongs to. False when it cannot be drawn (the caller then draws the fallback). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawLiquidGlass(
    host: GlassBackdropState,
    lens: LensEffect,
    layer: GraphicsLayer,
    shape: Shape,
    dark: Boolean,
    tint: Color,
    kind: GlassKind,
    spot: Offset,
): Boolean {
    val backdrop = host.size
    val origin = host.origin
    if (backdrop.width <= 0 || backdrop.height <= 0) return false
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    if (w < 2 || h < 2) return false

    val outline = shape.createOutline(size, layoutDirection, this)
    val half = min(w, h) / 2f
    val radii = when (outline) {
        is Outline.Rounded -> {
            val r = outline.roundRect
            floatArrayOf(
                r.topLeftCornerRadius.x, r.topRightCornerRadius.x,
                r.bottomRightCornerRadius.x, r.bottomLeftCornerRadius.x,
            )
        }
        is Outline.Rectangle -> floatArrayOf(0f, 0f, 0f, 0f)
        else -> return false
    }
    for (i in radii.indices) radii[i] = radii[i].coerceIn(0f, half)

    // size-adaptive lens, as in the library: small things get a thinner bevel and a weaker bend
    val minDim = min(w, h).toFloat()
    val referenceSize = 110.dp.toPx()
    val blur = kind.blur.toPx()
    val bevel = min(kind.bevel.toPx(), minDim * 0.3f).coerceAtLeast(2f)
    val refraction = min(kind.refraction.toPx(), 0.7f * minDim * min(1f, minDim / referenceSize))
    val rimBand = min(6.dp.toPx(), minDim * 0.05f).coerceAtLeast(2f)
    // room around the element for the blur to read from, in steps of 16 px so that the effect is not rebuilt for nothing
    val margin = ((maxOf(32f, blur * 3f).toInt() + 15) / 16) * 16

    val baseTint = if (dark) Color(1f, 1f, 1f, 0.07f) else Color(1f, 1f, 1f, 0.30f)
    val bodyTint = if (tint.alpha > 0.004f) tint.copy(alpha = (tint.alpha * 2.2f).coerceAtMost(0.9f)) else Color.Transparent
    val params = LensParams(
        width = w, height = h, margin = margin,
        radiusTopLeft = radii[0], radiusTopRight = radii[1], radiusBottomRight = radii[2], radiusBottomLeft = radii[3],
        blur = blur, bevel = bevel, refraction = refraction, rimBand = rimBand,
        specular = kind.specular * if (dark) 0.8f else 1f,
        saturation = kind.saturation,
        tint = baseTint.toArgb(), glassTint = bodyTint.toArgb(),
    )

    // the part of the backdrop that is behind this element, with the margin around it
    layer.topLeft = IntOffset(-margin, -margin)
    // the member form, whose parameters are all given: a DrawScope is a Density
    layer.record(this, layoutDirection, IntSize(w + 2 * margin, h + 2 * margin)) {
        translate(left = margin + (origin.x - spot.x), top = margin + (origin.y - spot.y)) {
            drawWallpaper(host.colors, backdrop.width.toFloat(), backdrop.height.toFloat(), 0f)
        }
    }
    layer.renderEffect = lens.effect(params)
    drawLayer(layer)

    if (!dark) {
        // a faint edge, so that a pane of glass over a white page still has a visible outline
        drawOutline(outline, color = Color(0x2614172B), style = Stroke(width = 0.75.dp.toPx()))
    }
    return true
}
