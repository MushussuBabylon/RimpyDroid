package com.rimpydroid.manager.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.view.WindowManager
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import com.rimpydroid.manager.core.ThemeColors

// Liquid glass: translucent cards with a bright, thin edge over a soft, slowly moving gradient. It is an option
// (Settings > Appearance > Style); the classic white-and-black look stays the default and is not touched by any of
// this. Android has no cheap "blur what is behind", so the glass is made of what is cheap and still looks the part:
// see-through fills, a light edge that fades along the diagonal, and a backdrop of colour that the cards pick up.

/** True while the Liquid glass style is on. */
val LocalGlass = staticCompositionLocalOf { false }

/** The accent that Liquid glass uses while no accent colour has been chosen (the classic look uses black or white). */
val GLASS_DEFAULT_ACCENT: Int = 0xFF4C8DFF.toInt()

/** A little rounder than the classic corners. */
internal val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * The Material colours of the glass look. Everything that sits on the page is see-through; what floats above it
 * (dialogs, menus) is solid, so that text stays readable and nothing ghosts through.
 */
internal fun glassSchemeOf(accentColor: Int, dark: Boolean): ColorScheme {
    val accent = if (accentColor == 0) GLASS_DEFAULT_ACCENT else accentColor
    val p = ThemeColors.palette(accent, dark)
    val primary = Color(p.primary)
    val clear = Color.Transparent
    // Menus and dialogs float above the page. Nothing behind a window can be blurred, and even 3% see-through lets the
    // text underneath ghost through, so these two are fully solid (with a faint tint of the accent).
    val menu = if (dark) lerp(Color(0xFF121833), primary, 0.10f) else lerp(Color.White, primary, 0.05f)
    val dialog = if (dark) lerp(Color(0xFF171E3D), primary, 0.12f) else lerp(Color.White, primary, 0.04f)
    return if (dark) {
        val ink = Color(0xFFF1F4FF)
        darkColorScheme(
            primary = primary, onPrimary = Color(p.onPrimary),
            primaryContainer = primary.copy(alpha = 0.34f), onPrimaryContainer = ink,
            inversePrimary = Color(p.inversePrimary),
            secondary = Color(p.secondary), onSecondary = Color(p.onSecondary),
            secondaryContainer = primary.copy(alpha = 0.28f), onSecondaryContainer = ink,
            tertiary = Color(p.tertiary), onTertiary = Color(p.onTertiary),
            tertiaryContainer = primary.copy(alpha = 0.22f), onTertiaryContainer = ink,
            background = clear, onBackground = ink,
            surface = Color(0x14FFFFFF), onSurface = ink,
            surfaceVariant = Color(0x1AFFFFFF), onSurfaceVariant = Color(0xFFC3CAE3),
            surfaceTint = clear, inverseSurface = Color(p.inverseSurface), inverseOnSurface = Color(p.inverseOnSurface),
            error = Color(p.error), onError = Color(p.onError),
            errorContainer = Color(p.error).copy(alpha = 0.30f), onErrorContainer = Color(0xFFFFDAD6),
            outline = Color(0x59FFFFFF), outlineVariant = Color(0x2EFFFFFF), scrim = Color(p.scrim),
            surfaceBright = Color(0x26FFFFFF), surfaceDim = Color(0x0DFFFFFF),
            surfaceContainerLowest = Color(0x0AFFFFFF), surfaceContainerLow = Color(0x0FFFFFFF),
            surfaceContainer = menu, surfaceContainerHigh = dialog,
            surfaceContainerHighest = Color(0x1FFFFFFF),
        )
    } else {
        val ink = Color(0xFF14172B)
        lightColorScheme(
            primary = primary, onPrimary = Color(p.onPrimary),
            primaryContainer = primary.copy(alpha = 0.22f), onPrimaryContainer = ink,
            inversePrimary = Color(p.inversePrimary),
            secondary = Color(p.secondary), onSecondary = Color(p.onSecondary),
            secondaryContainer = primary.copy(alpha = 0.18f), onSecondaryContainer = ink,
            tertiary = Color(p.tertiary), onTertiary = Color(p.onTertiary),
            tertiaryContainer = primary.copy(alpha = 0.14f), onTertiaryContainer = ink,
            background = clear, onBackground = ink,
            surface = Color(0x99FFFFFF), onSurface = ink,
            surfaceVariant = Color(0x73FFFFFF), onSurfaceVariant = Color(0xFF454B6B),
            surfaceTint = clear, inverseSurface = Color(p.inverseSurface), inverseOnSurface = Color(p.inverseOnSurface),
            error = Color(p.error), onError = Color(p.onError),
            errorContainer = Color(p.error).copy(alpha = 0.18f), onErrorContainer = Color(0xFF7A1111),
            outline = Color(0x5214172B), outlineVariant = Color(0x2414172B), scrim = Color(p.scrim),
            surfaceBright = Color(0xB3FFFFFF), surfaceDim = Color(0x40FFFFFF),
            surfaceContainerLowest = Color(0x59FFFFFF), surfaceContainerLow = Color(0x73FFFFFF),
            surfaceContainer = menu, surfaceContainerHigh = dialog,
            surfaceContainerHighest = Color(0xA6FFFFFF),
        )
    }
}

// ---- the backdrop ---------------------------------------------------------------------------------------

internal class BackdropColors(
    val top: Color, val mid: Color, val bottom: Color,
    val glowA: Color, val glowB: Color, val glowC: Color,
) {
    companion object {
        /** Colours built on the hue of [accent]; a grey accent has no hue, so the glass default's is used. */
        fun of(accent: Int, dark: Boolean): BackdropColors {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(accent, hsv)
            if (hsv[1] < 0.15f) android.graphics.Color.colorToHSV(GLASS_DEFAULT_ACCENT, hsv)
            val hue = hsv[0]
            fun tone(shift: Float, saturation: Float, value: Float, alpha: Float = 1f): Color =
                Color.hsv((hue + shift + 360f) % 360f, saturation, value, alpha)
            return if (dark) {
                BackdropColors(
                    top = tone(0f, 0.60f, 0.12f), mid = tone(22f, 0.62f, 0.20f), bottom = tone(-34f, 0.60f, 0.12f),
                    glowA = tone(0f, 0.75f, 0.95f, 0.42f), glowB = tone(68f, 0.70f, 0.92f, 0.30f), glowC = tone(-58f, 0.72f, 0.92f, 0.30f),
                )
            } else {
                BackdropColors(
                    top = tone(0f, 0.08f, 1.00f), mid = tone(22f, 0.13f, 0.98f), bottom = tone(-34f, 0.12f, 0.99f),
                    glowA = tone(0f, 0.45f, 1f, 0.50f), glowB = tone(68f, 0.40f, 1f, 0.38f), glowC = tone(-58f, 0.42f, 1f, 0.40f),
                )
            }
        }
    }
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(colors = listOf(color, Color.Transparent), center = center, radius = radius),
        radius = radius,
        center = center,
    )
}

/** A smaller orb with a firmer edge: it is what the rim of a pane of glass has something to bend. */
private fun DrawScope.orb(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to color,
            0.62f to color.copy(alpha = color.alpha * 0.80f),
            1.00f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * The picture behind the app, for a window of [w] x [h] pixels. It is a function of its parameters alone, which is what
 * lets every pane of glass draw the part that is behind itself (see LiquidGlass.kt) and lets a Dialog draw it as well.
 * The gradient is drawn far beyond the window, so that a pane at the edge has something to blur and bend there.
 */
internal fun DrawScope.drawWallpaper(c: BackdropColors, w: Float, h: Float, phase: Float) {
    val far = 6000f
    drawRect(
        brush = Brush.verticalGradient(listOf(c.top, c.mid, c.bottom), startY = 0f, endY = h),
        topLeft = Offset(-far, -far),
        size = Size(w + 2 * far, h + 2 * far),
    )
    val reach = maxOf(w, h * 0.6f)
    glow(c.glowA, Offset(w * (0.10f + 0.30f * phase), h * (0.10f + 0.06f * phase)), reach * 0.95f)
    glow(c.glowB, Offset(w * (0.95f - 0.35f * phase), h * (0.45f + 0.10f * phase)), reach * 0.85f)
    glow(c.glowC, Offset(w * (0.20f + 0.25f * phase), h * (0.98f - 0.14f * phase)), reach * 1.00f)
    // smaller, firmer shapes spread over the page: the glass bends their edges
    val unit = minOf(w, h)
    orb(c.glowB.copy(alpha = c.glowB.alpha * 0.9f), Offset(w * 0.82f, h * 0.16f), unit * 0.30f)
    orb(c.glowC.copy(alpha = c.glowC.alpha * 0.9f), Offset(w * 0.14f, h * 0.40f), unit * 0.24f)
    orb(c.glowA.copy(alpha = c.glowA.alpha * 0.8f), Offset(w * 0.72f, h * 0.62f), unit * 0.34f)
    orb(c.glowB.copy(alpha = c.glowB.alpha * 0.8f), Offset(w * 0.30f, h * 0.84f), unit * 0.26f)
    orb(c.glowC.copy(alpha = c.glowC.alpha * 0.7f), Offset(w * 0.92f, h * 0.90f), unit * 0.20f)
    // two thin rings of light
    val ring = if (c.glowA.alpha > 0f) c.glowA.copy(alpha = 0.22f) else c.glowA
    drawCircle(ring, radius = unit * 0.46f, center = Offset(w * 0.78f, h * 0.30f), style = Stroke(width = unit * 0.008f))
    drawCircle(ring, radius = unit * 0.38f, center = Offset(w * 0.18f, h * 0.70f), style = Stroke(width = unit * 0.008f))
}

/**
 * What the backdrop draws with, shared so that a full-window page paints exactly the same picture as the tabs.
 * [real] says that the lens is used (Android 13 and later): the picture then stands still, because every pane of glass
 * has drawn the part of it that is behind itself. [size] and [origin] (on the screen, in pixels) tell where the picture is.
 */
internal class GlassBackdropState(val colors: BackdropColors, val phase: State<Float>, val real: Boolean) {
    var size: IntSize by mutableStateOf(IntSize.Zero)
    var origin: IntOffset by mutableStateOf(IntOffset.Zero)
}

internal val LocalGlassBackdrop = compositionLocalOf<GlassBackdropState?> { null }

/** True when the phone asks for no animation (animations off, or battery saver): the backdrop then stands still. */
private fun motionIsOff(context: Context): Boolean = try {
    val scale = SystemSettings.Global.getFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val saver = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    scale == 0f || saver
} catch (e: Exception) {
    false
}

/**
 * The soft gradient behind the whole app. Its colours follow the accent. The slow drift is read while drawing only,
 * so the app on top of it is not recomposed by it.
 */
@Composable
internal fun GlassBackdrop(dark: Boolean, accent: Int, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val real = remember { LiquidGlassSupport.available }
    // the lens needs a picture that stands still (see GlassBackdropState)
    val still = remember { real || motionIsOff(context) }
    val colors = remember(dark, accent) { BackdropColors.of(accent, dark) }
    val phase: State<Float> = if (still) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "glass").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            // eased, so the drift slows down and turns around gently instead of snapping back at the ends
            animationSpec = infiniteRepeatable(tween(durationMillis = 36000, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "glassPhase",
        )
    }
    val state = remember(colors, phase, real) { GlassBackdropState(colors, phase, real) }
    CompositionLocalProvider(LocalGlassBackdrop provides state) {
        Box(Modifier.fillMaxSize()) {
            Canvas(
                Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                    val inRoot = coordinates.positionInRoot()
                    val screen = IntArray(2)
                    view.getLocationOnScreen(screen)
                    state.size = coordinates.size
                    state.origin = IntOffset((inRoot.x + screen[0]).roundToInt(), (inRoot.y + screen[1]).roundToInt())
                },
            ) { drawWallpaper(colors, size.width, size.height, phase.value) }
            content()
        }
    }
}

/**
 * The frame of a full-window page (details, help, log, folder picker...). The classic style paints it with the
 * background colour. In Liquid glass that colour is see-through, so the page would sit on top of the tab behind it
 * and both would show at once; the page therefore paints the same gradient itself, which is fully opaque.
 */
@Composable
internal fun PageSurface(content: @Composable () -> Unit) {
    if (!LocalGlass.current) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        return
    }
    val backdrop = LocalGlassBackdrop.current
    val dark = LocalDarkTheme.current
    Box(
        Modifier.fillMaxSize().drawBehind {
            if (backdrop != null) {
                drawWallpaper(backdrop.colors, size.width, size.height, backdrop.phase.value)
            } else {
                drawRect(if (dark) Color.Black else Color.White)
            }
        },
    ) {
        // a Surface also keeps taps from reaching what is below the page
        Surface(Modifier.fillMaxSize(), color = Color.Transparent, content = content)
    }
}

// ---- cards ----------------------------------------------------------------------------------------------

private fun glassFill(dark: Boolean): Brush = Brush.linearGradient(
    colors = if (dark) listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF)) else listOf(Color(0xB8FFFFFF), Color(0x5CFFFFFF)),
)

/** The rim: bright where the light hits (top left, bottom right), nearly gone in between. */
internal fun glassEdge(dark: Boolean): Brush = Brush.linearGradient(
    colors = if (dark) {
        listOf(Color(0x80FFFFFF), Color(0x14FFFFFF), Color(0x3DFFFFFF))
    } else {
        listOf(Color(0xFFFFFFFF), Color(0x80FFFFFF), Color(0xCCFFFFFF))
    },
)

/**
 * The earlier glass, kept as the fallback for phones below Android 13 (and for a driver that refuses the lens shader):
 * the see-through fill, an optional [tint], and a soft sheen along the top edge, which is what makes a flat translucent
 * rectangle read as a pane of glass. The real glass is Modifier.glassBackground in LiquidGlass.kt.
 */
internal fun DrawScope.drawFakeGlass(shape: Shape, dark: Boolean, tint: Color) {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, brush = glassFill(dark))
    if (tint != Color.Transparent) drawOutline(outline, color = tint)
    val sheen = if (dark) Color(0x24FFFFFF) else Color(0x70FFFFFF)
    val reach = minOf(size.height * 0.5f, 64.dp.toPx())
    if (reach > 1f) {
        drawOutline(outline, brush = Brush.verticalGradient(listOf(sheen, Color.Transparent), startY = 0f, endY = reach))
    }
    // the rim, as the border of the earlier version drew it
    drawOutline(outline, brush = glassEdge(dark), style = Stroke(width = 1.dp.toPx()))
}

/**
 * A card. In the classic style this is Material's Card, drawn exactly as before. In Liquid glass it is a see-through
 * panel with a bright edge; [containerColor] then tints it instead of filling it.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalGlass.current) {
        val colors = when {
            containerColor == null -> CardDefaults.cardColors()
            contentColor == null -> CardDefaults.cardColors(containerColor = containerColor)
            else -> CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
        }
        Card(modifier, colors = colors, content = content)
        return
    }
    val dark = LocalDarkTheme.current
    val shape = MaterialTheme.shapes.large
    val tint = containerColor ?: Color.Transparent
    // the rim is part of the glass (drawn by the lens, or by the fallback), so the card has no border of its own
    Card(
        modifier = modifier.glassBackground(shape, dark, tint),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = contentColor ?: MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}

/**
 * A framed block that holds other content (a group of settings, a help topic, the bottom bar). The classic style
 * keeps the plain bordered surface it always had; Liquid glass makes it a pane of glass like [AppCard].
 */
@Composable
fun AppPanel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable () -> Unit,
) {
    if (!LocalGlass.current) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            content = content,
        )
        return
    }
    val dark = LocalDarkTheme.current
    Surface(
        modifier = modifier.glassBackground(shape, dark, kind = GlassKind.PANEL),
        shape = shape,
        color = Color.Transparent,
        content = content,
    )
}

/** The colours of a title bar: the classic ones, or none at all in Liquid glass so that the title sits on the gradient. */
@Composable
internal fun appBarColors(): TopAppBarColors =
    if (LocalGlass.current) {
        TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
    } else {
        TopAppBarDefaults.topAppBarColors()
    }

// ---- dialogs --------------------------------------------------------------------------------------------

/**
 * Blurs what is behind a dialog while it is open (Android 12 and later, when the phone allows window blur). The
 * dialog stays solid, so this cannot make text hard to read; where blur is not available nothing changes.
 */
@Composable
private fun DialogBlurBehind() {
    val view = LocalView.current
    SideEffect {
        if (Build.VERSION.SDK_INT < 31) return@SideEffect
        try {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            val windows = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windows?.isCrossWindowBlurEnabled != true) return@SideEffect
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val attributes = window.attributes
            attributes.blurBehindRadius = 24
            window.attributes = attributes
        } catch (e: Throwable) {
            // no blur is not a problem
        }
    }
}

/**
 * Material's AlertDialog. In Liquid glass it gets the glass rim and the blurred backdrop; in the classic style it
 * is exactly the AlertDialog it was.
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    if (!LocalGlass.current) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            properties = properties,
        )
        return
    }
    val dark = LocalDarkTheme.current
    val shape = MaterialTheme.shapes.extraLarge
    // The dialog is a window of its own, and nothing behind a window can be blurred. The lens does not need that: it
    // draws the app's backdrop itself, at the place where the dialog is on the screen. Without the lens the dialog is
    // solid (a faint see-through lets the text underneath ghost through). Either way the phone blurs the window behind it.
    val real = rememberRealGlass()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            DialogBlurBehind()
            confirmButton()
        },
        modifier = if (real) modifier.glassBackground(shape, dark, kind = GlassKind.SHEET) else modifier.border(1.dp, glassEdge(dark), shape),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = if (real) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        properties = properties,
    )
}
