package io.github.deganegane.looilab

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** How Stevo's face is drawn. Nixie is the glowing tube; the rest are flat, minimal, retro-futuristic. */
enum class FaceTheme(val label: String, val about: String) {
    NIXIE("Nixie", "Užarene niti iza saćaste rešetke."),
    DISK("Disk", "Krem oči s odsjajem, obrve i pjegice koje zasvijetle."),
    CRTICA("Crtica", "Radio iz '62.: kazaljka na skali pokazuje raspoloženje."),
    PRSTEN("Prsten", "Oči kao objektivi, s podjelom i narančastom zjenicom."),
    VIZIR("Vizir", "Vizir sa zakovicama i antenom koja zasvijetli."),
    KVADRAT("Kvadrat", "Kvadratne oči sa zjenicama i trakom energije."),
    ATOM("Atom", "Jezgra s orbitama i elektronom koji kruži."),
}

/** Any face, by the theme chosen in [FaceState.theme]. */
@Composable
fun StevoFace(state: FaceState, modifier: Modifier = Modifier) {
    if (state.theme == FaceTheme.NIXIE) NixieFace(state, modifier) else FlatFace(state, state.theme, modifier)
}

private class Pal(val bg: Color, val ink: Color, val accent: Color, val band: Color = Color.Unspecified)

private fun pal(t: FaceTheme) = when (t) {
    FaceTheme.CRTICA -> Pal(Color(0xFF0F1B2B), Color(0xFFF2B544), Color(0xFFFF6B4A))
    FaceTheme.PRSTEN -> Pal(Color(0xFF0C3B3A), Color(0xFFF4EBD6), Color(0xFFFF8A3D))
    FaceTheme.VIZIR -> Pal(Color(0xFFE4572E), Color(0xFFFFF3DC), Color(0xFFFFF3DC), band = Color(0xFF1D1A17))
    FaceTheme.KVADRAT -> Pal(Color(0xFF1B1F22), Color(0xFF9FE3C6), Color(0xFF9FE3C6))
    FaceTheme.ATOM -> Pal(Color(0xFFE8B93C), Color(0xFF16233B), Color(0xFFD9482B))
    else -> Pal(Color(0xFF171512), Color(0xFFEFE6D2), Color(0xFFE4572E)) // Disk
}

// Face space 400 × 200, eyes at x 128 / 272, y 100.
private const val EL = 128f
private const val ER = 272f
private const val EY = 100f

private enum class Kind { EYE, ARC, ARC_DOWN, HEART, STAR, SPIRAL, CHEV }

/** One eye of an expression: how open, lid tilt (+ angry, − sad), size, where, or a special shape. */
private class Eye(val h: Float = 1f, val tilt: Float = 0f, val s: Float = 1f, val dx: Float = 0f, val dy: Float = 0f, val kind: Kind = Kind.EYE)

private fun both(e: Eye) = e to e

private fun eyes(x: Expr): Pair<Eye, Eye> = when (x) {
    Expr.NEUTRAL -> both(Eye())
    Expr.HAPPY -> both(Eye(kind = Kind.ARC))
    Expr.SURPRISED -> both(Eye(s = 1.3f))
    Expr.SLEEPY -> both(Eye(h = 0.16f, dy = 10f))
    Expr.CURIOUS -> Eye(s = 1.15f, dy = -8f) to Eye(h = 0.5f, dy = 6f)
    Expr.LOVE -> both(Eye(kind = Kind.HEART))
    Expr.ANGRY -> both(Eye(tilt = 1f))
    Expr.DIZZY -> both(Eye(kind = Kind.SPIRAL))
    Expr.SAD -> both(Eye(tilt = -1f))
    Expr.WINK -> Eye() to Eye(kind = Kind.ARC)
    Expr.LAUGH -> both(Eye(kind = Kind.CHEV))
    Expr.THINKING -> both(Eye(s = 0.85f, dx = 12f, dy = -14f))
    Expr.WORRIED -> both(Eye(s = 0.75f, tilt = -0.5f))
    Expr.BORED -> both(Eye(h = 0.45f, dy = 4f))
    Expr.EXCITED -> both(Eye(kind = Kind.STAR))
    Expr.CONFUSED -> Eye(s = 1.2f) to Eye(h = 0.3f, dy = 4f)
    Expr.SHY -> both(Eye(kind = Kind.ARC_DOWN, dx = -8f, dy = 10f))
}

/** Brows per eye as (lift, tilt): tilt + = inner end down. */
private fun browsOf(x: Expr): Pair<Offset, Offset> = when (x) {
    Expr.HAPPY, Expr.LOVE -> Offset(4f, 0f) to Offset(4f, 0f)
    Expr.SURPRISED -> Offset(16f, 0f) to Offset(16f, 0f)
    Expr.EXCITED -> Offset(14f, 0f) to Offset(14f, 0f)
    Expr.SLEEPY, Expr.BORED -> Offset(-7f, 0f) to Offset(-7f, 0f)
    Expr.CURIOUS -> Offset(16f, 0f) to Offset(-2f, 0f)
    Expr.CONFUSED -> Offset(14f, 0f) to Offset(-4f, 0.5f)
    Expr.THINKING -> Offset(12f, 0f) to Offset(2f, 0f)
    Expr.WINK -> Offset(8f, 0f) to Offset(-2f, 0f)
    Expr.LAUGH -> Offset(6f, 0f) to Offset(6f, 0f)
    Expr.ANGRY -> Offset(-4f, 1f) to Offset(-4f, 1f)
    Expr.SAD -> Offset(2f, -1f) to Offset(2f, -1f)
    Expr.WORRIED -> Offset(6f, -0.6f) to Offset(6f, -0.6f)
    Expr.SHY -> Offset(0f, -0.4f) to Offset(0f, -0.4f)
    else -> Offset.Zero to Offset.Zero
}

/** 0..1: how good he feels, for the tuner needle and the energy bar. */
private fun mood(x: Expr): Float = when (x) {
    Expr.EXCITED -> 1f; Expr.SURPRISED -> 0.95f; Expr.LOVE -> 0.88f; Expr.LAUGH -> 0.82f; Expr.HAPPY -> 0.75f
    Expr.WINK -> 0.68f; Expr.CURIOUS -> 0.62f; Expr.THINKING -> 0.55f; Expr.NEUTRAL -> 0.5f; Expr.SHY -> 0.45f
    Expr.CONFUSED -> 0.4f; Expr.DIZZY -> 0.35f; Expr.WORRIED -> 0.3f; Expr.SAD -> 0.22f; Expr.ANGRY -> 0.15f
    Expr.BORED -> 0.1f; Expr.SLEEPY -> 0.04f
}

private val bright = setOf(Expr.HAPPY, Expr.LOVE, Expr.LAUGH, Expr.EXCITED, Expr.WINK)

// ------------------------------------------------------------------ shapes

private fun heartPath(cx: Float, cy: Float, s: Float) = Path().apply {
    moveTo(cx, cy + s * 0.9f)
    cubicTo(cx - s * 1.6f, cy - s * 0.1f, cx - s * 0.9f, cy - s * 1.3f, cx, cy - s * 0.45f)
    cubicTo(cx + s * 0.9f, cy - s * 1.3f, cx + s * 1.6f, cy - s * 0.1f, cx, cy + s * 0.9f)
    close()
}

private fun arcPath(cx: Float, cy: Float, w: Float, h: Float, up: Boolean) = Path().apply {
    if (up) { moveTo(cx - w / 2, cy + h * 0.4f); quadraticTo(cx, cy - h * 1.2f, cx + w / 2, cy + h * 0.4f) }
    else { moveTo(cx - w / 2, cy - h * 0.4f); quadraticTo(cx, cy + h * 1.2f, cx + w / 2, cy - h * 0.4f) }
}

private fun starPath(cx: Float, cy: Float, r: Float) = Path().apply {
    val k = r * 0.22f
    moveTo(cx, cy - r)
    quadraticTo(cx + k, cy - k, cx + r, cy); quadraticTo(cx + k, cy + k, cx, cy + r)
    quadraticTo(cx - k, cy + k, cx - r, cy); quadraticTo(cx - k, cy - k, cx, cy - r); close()
}

private fun spiralPath(cx: Float, cy: Float, r: Float) = Path().apply {
    val n = 90
    for (i in 0..n) {
        val t = i / n.toFloat() * 3.2f * PI.toFloat(); val rr = r * i / n
        val x = cx + rr * cos(t); val y = cy + rr * sin(t)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
}

private fun chevPath(cx: Float, cy: Float, s: Float, right: Boolean) = Path().apply {
    val d = if (right) 1f else -1f
    moveTo(cx - d * s * 0.7f, cy - s); lineTo(cx + d * s * 0.7f, cy); lineTo(cx - d * s * 0.7f, cy + s)
}

/** What is left of a round eye under its lids: a band for "how open", a slant for the tilt. */
private fun lidPath(cx: Float, cy: Float, r: Float, e: Eye, left: Boolean) = Path().apply {
    val r2 = r + 12f
    var top = cy - r2; var bot = cy + r2
    if (e.h < 1f) { top = cy - r * e.h; bot = cy + r * e.h }
    var outer = top; var inner = top
    if (e.tilt > 0f) { outer = cy - r * 1.15f; inner = cy - r * (1.15f - 1.1f * e.tilt) }
    if (e.tilt < 0f) { inner = cy - r * 1.15f; outer = cy - r * (1.15f + 1.1f * e.tilt) }
    val yl = if (left) outer else inner; val yr = if (left) inner else outer
    moveTo(cx - r2, yl); lineTo(cx + r2, yr); lineTo(cx + r2, bot); lineTo(cx - r2, bot); close()
}

private fun round(w: Float) = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)

// ------------------------------------------------------------------ one eye

private fun DrawScope.drawEye(t: FaceTheme, p: Pal, e: Eye, cx0: Float, left: Boolean, look: Offset, phase: Float) {
    val cx = cx0 + e.dx; val cy = EY + e.dy
    val sgn = if (left) 1f else -1f
    val outline = t == FaceTheme.PRSTEN || t == FaceTheme.KVADRAT
    val sw = when (t) { FaceTheme.DISK -> 15f; FaceTheme.CRTICA, FaceTheme.VIZIR -> 14f; FaceTheme.ATOM -> 9f; else -> 7f }
    val heartColor = if (t == FaceTheme.KVADRAT || t == FaceTheme.VIZIR) p.ink else p.accent
    when (e.kind) {
        Kind.ARC -> drawPath(arcPath(cx, cy, 72f, 34f, true), p.ink, style = round(sw))
        Kind.ARC_DOWN -> drawPath(arcPath(cx, cy, 60f, 22f, false), p.ink, style = round(sw))
        Kind.HEART -> if (outline) drawPath(heartPath(cx, cy, 30f), heartColor, style = round(7f)) else drawPath(heartPath(cx, cy, 30f), heartColor)
        Kind.STAR -> if (outline) drawPath(starPath(cx, cy, 40f), p.ink, style = round(6f)) else drawPath(starPath(cx, cy, 40f), p.ink)
        Kind.SPIRAL -> drawPath(spiralPath(cx, cy, 34f), p.ink, style = round(min(sw, 8f)))
        Kind.CHEV -> drawPath(chevPath(cx, cy, 28f, left), p.ink, style = round(min(sw, 10f)))
        Kind.EYE -> when (t) {
            FaceTheme.PRSTEN -> {
                val r = 34f * e.s
                clipPath(lidPath(cx, cy, r, e, left)) {
                    drawCircle(p.ink, r - 3.5f, Offset(cx, cy), style = Stroke(7f))
                    if (e.h >= 0.3f) drawCircle(p.accent, if (e.s > 1.2f) 7f else 10f, Offset(cx + look.x * 12f, cy + look.y * 9f))
                }
                if (e.h < 0.3f) drawLine(p.ink, Offset(cx - r, cy), Offset(cx + r, cy), 7f, StrokeCap.Round)
            }
            FaceTheme.CRTICA, FaceTheme.VIZIR, FaceTheme.KVADRAT -> {
                val w0 = when (t) { FaceTheme.CRTICA -> 64f; FaceTheme.VIZIR -> 46f; else -> 58f }
                val h0 = if (t == FaceTheme.CRTICA) 26f else w0
                val rad = when (t) { FaceTheme.CRTICA -> 13f; FaceTheme.VIZIR -> 10f; else -> 9f }
                val ww = w0 * e.s
                val hh = max(h0 * e.s * e.h * (if (t == FaceTheme.CRTICA && e.s > 1.2f) 1.9f else 1f), 7f)
                withTransform({ rotate(e.tilt * 16f * sgn, Offset(cx, cy)) }) {
                    val c = min(rad, hh / 2)
                    drawRoundRect(p.ink, Offset(cx - ww / 2, cy - hh / 2), Size(ww, hh), CornerRadius(c, c), style = if (outline) Stroke(7f) else Fill)
                    if (t == FaceTheme.KVADRAT && e.h >= 0.5f) {
                        val q = 16f * e.s
                        drawRoundRect(p.ink, Offset(cx - q / 2 + look.x * 13f, cy - q / 2 + 3f + look.y * 11f), Size(q, q), CornerRadius(3f, 3f))
                    }
                }
            }
            FaceTheme.ATOM -> {
                val flat = e.h < 0.3f
                val rx = 44f * e.s; val ry = 15f * e.s * (if (flat) 0.25f else 1f)
                // a second, fainter orbit
                withTransform({ rotate(sgn * (40f - e.tilt * 30f), Offset(cx, cy)) }) {
                    drawOval(p.ink, Offset(cx - rx, cy - ry * 0.85f), Size(rx * 2, ry * 1.7f), alpha = 0.45f, style = Stroke(1.5f))
                }
                // the main orbit, with an electron going round
                withTransform({ rotate(sgn * (-24f + e.tilt * 46f), Offset(cx, cy)) }) {
                    drawOval(p.ink, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), style = Stroke(3.5f))
                    val a = phase * 2 * PI.toFloat() * sgn
                    drawCircle(p.accent, 4.5f, Offset(cx + rx * cos(a), cy + ry * sin(a)))
                }
                val dot = 13f * e.s * (if (flat) 0.45f else if (e.h < 1f) 0.8f else 1f)
                drawCircle(p.ink, dot, Offset(cx, cy))
                if (e.h >= 0.5f) drawCircle(Color(0xFFFFF6DD), 3.2f * e.s, Offset(cx - 4f * e.s, cy - 4f * e.s))
            }
            else -> { // Disk
                val r = 38f * e.s
                clipPath(lidPath(cx, cy, r, e, left)) {
                    drawCircle(p.ink, r, Offset(cx, cy))
                    if (e.h >= 0.5f) drawCircle(p.bg, r * 0.2f, Offset(cx - r * 0.3f, cy - r * 0.3f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the character around the eyes

private fun DrawScope.brows(x: Expr, color: Color, dist: Float, w: Float, sw: Float) {
    val (bl, br) = browsOf(x)
    for ((b, cx, sgn) in listOf(Triple(bl, EL, 1f), Triple(br, ER, -1f))) {
        val y = EY - dist - b.x
        withTransform({ rotate(b.y * 18f * sgn, Offset(cx, y)) }) {
            drawLine(color, Offset(cx - w / 2, y), Offset(cx + w / 2, y), sw, StrokeCap.Round)
        }
    }
}

private fun DrawScope.text(s: String, x: Float, y: Float, color: Color, alignRight: Boolean = false, size: Float = 9f) {
    val paint = Paint().apply {
        this.color = color.toArgb(); textSize = size; typeface = Typeface.MONOSPACE; letterSpacing = 0.16f; isAntiAlias = true
        textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
    }
    drawContext.canvas.nativeCanvas.drawText(s, x, y, paint)
}

private fun DrawScope.deco(t: FaceTheme, p: Pal, x: Expr, name: String, needle: Float, e: Pair<Eye, Eye>) {
    when (t) {
        FaceTheme.CRTICA -> {
            // a radio tuner scale; the needle sits where his mood is
            for (i in 0..28) {
                val tx = 60f + i * 10f; val h = if (i % 5 == 0) 9f else 5f
                drawLine(p.ink, Offset(tx, 186f - h), Offset(tx, 186f), 1.5f, alpha = 0.55f)
            }
            val n = 60f + needle * 280f
            drawLine(p.accent, Offset(n, 170f), Offset(n, 190f), 3f, StrokeCap.Round)
            drawCircle(p.accent, 3.5f, Offset(n, 168f))
            brows(x, p.ink, 44f, 40f, 5f)
            text("FM", 60f, 22f, p.ink); text("88 · 108", 340f, 22f, p.ink, alignRight = true)
        }
        FaceTheme.PRSTEN -> {
            // dial ticks around each eye, like an instrument
            for ((eye, cx0) in listOf(e.first to EL, e.second to ER)) {
                if (eye.kind != Kind.EYE) continue
                val r = 34f * eye.s + 9f; val cx = cx0 + eye.dx; val cy = EY + eye.dy
                for (i in 0 until 24) {
                    val a = i / 24f * 2 * PI.toFloat(); val big = i % 6 == 0
                    val r2 = r + if (big) 7f else 4f
                    drawLine(p.ink, Offset(cx + r * cos(a), cy + r * sin(a)), Offset(cx + r2 * cos(a), cy + r2 * sin(a)),
                        if (big) 2f else 1.2f, alpha = if (big) 0.9f else 0.5f)
                }
            }
            drawLine(p.accent, Offset(200f, 14f), Offset(200f, 26f), 1.5f); drawLine(p.accent, Offset(194f, 20f), Offset(206f, 20f), 1.5f)
            text("OPTIK", 22f, 186f, p.ink); text("f/2", 378f, 186f, p.ink, alignRight = true)
        }
        FaceTheme.VIZIR -> {
            drawRoundRect(p.band, Offset(40f, 46f), Size(320f, 108f), CornerRadius(54f, 54f))
            for (rx in listOf(58f, 342f)) drawCircle(p.ink, 4f, Offset(rx, 100f), alpha = 0.6f, style = Stroke(1.5f))
            for (x0 in listOf(70f, 330f)) for (i in 0..2)
                drawLine(p.band, Offset(x0 - 10f, 168f + i * 7f), Offset(x0 + 10f, 168f + i * 7f), 3f, StrokeCap.Round)
            // the antenna: its bulb lights up when he is pleased
            drawLine(p.band, Offset(200f, 46f), Offset(200f, 22f), 4f, StrokeCap.Round)
            drawCircle(if (x in bright || x == Expr.SURPRISED) p.ink else p.band, 6f, Offset(200f, 18f))
        }
        FaceTheme.KVADRAT -> {
            for ((mx, my, sx, sy) in listOf(listOf(14f, 14f, 1f, 1f), listOf(386f, 14f, -1f, 1f), listOf(14f, 186f, 1f, -1f), listOf(386f, 186f, -1f, -1f))) {
                drawLine(p.ink, Offset(mx, my + sy * 14f), Offset(mx, my), 2f, alpha = 0.6f)
                drawLine(p.ink, Offset(mx, my), Offset(mx + sx * 14f, my), 2f, alpha = 0.6f)
            }
            brows(x, p.ink, 52f, 36f, 5f)
            val lit = (needle * 9f + 0.5f).toInt()
            for (i in 0 until 9) drawRect(p.ink, Offset(164f + i * 8f, 186f), Size(4f, 4f), alpha = if (i < lit) 0.9f else 0.25f)
        }
        FaceTheme.ATOM -> {
            val stars = when (x) { Expr.HAPPY, Expr.LOVE, Expr.EXCITED, Expr.LAUGH -> 3; Expr.SURPRISED -> 2; Expr.CURIOUS, Expr.WINK -> 1; else -> 0 }
            listOf(Triple(44f, 40f, 9f), Triple(356f, 52f, 7f), Triple(330f, 158f, 6f)).take(stars).forEach { (sx, sy, r) -> drawPath(starPath(sx, sy, r), p.ink) }
            text("ATOM", 22f, 186f, p.ink); text("No. 7", 378f, 186f, p.ink, alignRight = true)
        }
        else -> { // Disk
            drawRoundRect(p.ink, Offset(10f, 10f), Size(380f, 180f), CornerRadius(22f, 22f), alpha = 0.3f, style = Stroke(1.5f))
            // speaker-grille freckles; they glow when he is pleased
            val glow = x in bright
            for ((cx, sgn) in listOf(EL to -1f, ER to 1f)) {
                val x0 = cx + sgn * 44f
                for ((dx, dy) in listOf(0f to 0f, 9f to 0f, 4.5f to 8f, -4.5f to 8f, -9f to 0f))
                    drawCircle(if (glow) p.accent else p.ink, 2.4f, Offset(x0 + dx, 150f + dy), alpha = if (glow) 1f else 0.55f)
            }
            brows(x, p.ink, 56f, 34f, 7f)
            text(name.uppercase().take(12), 24f, 182f, p.ink); text("MK·I", 376f, 182f, p.ink, alignRight = true)
        }
    }
}

// ------------------------------------------------------------------ the face

@Composable
fun FlatFace(state: FaceState, theme: FaceTheme, modifier: Modifier = Modifier) {
    val p = remember(theme) { pal(theme) }

    // Transient expressions fall back to the base one.
    LaunchedEffect(state.flashId) {
        val id = state.flashId
        if (state.current != state.base) { delay(2_600); state.settle(id) }
    }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state.current) { pop.snapTo(1.14f); pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow)) }

    val blink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2_800, 6_500))
            val (l, r) = eyes(state.current)
            if (l.kind == Kind.EYE && r.kind == Kind.EYE && l.h > 0.3f) {
                blink.animateTo(0.06f, tween(70)); blink.animateTo(1f, tween(110))
                if (Random.nextFloat() < 0.2f) { delay(120); blink.animateTo(0.06f, tween(70)); blink.animateTo(1f, tween(110)) }
            }
        }
    }
    LaunchedEffect(state.asleep) {
        if (!state.asleep) { blink.snapTo(0.06f); blink.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    }
    val gazeX = remember { Animatable(0f) }
    val gazeY = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(1_800, 5_000))
            val tx = if (Random.nextFloat() < 0.35f) 0f else Random.nextFloat() * 2 - 1
            val ty = if (Random.nextFloat() < 0.6f) 0f else (Random.nextFloat() * 2 - 1) * 0.5f
            launch { gazeX.animateTo(tx, tween(260, easing = FastOutSlowInEasing)) }
            gazeY.animateTo(ty, tween(260, easing = FastOutSlowInEasing))
        }
    }
    val clock = rememberInfiniteTransition(label = "flat")
    val breath by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(4_200, easing = LinearEasing)), label = "breath")
    val drift by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(5_000, easing = LinearEasing)), label = "drift")
    val sweep by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "sweep")
    val mic by animateFloatAsState(state.mic, tween(120), label = "mic")
    val needle by animateFloatAsState(mood(state.current), spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow), label = "needle")

    Canvas(modifier) {
        drawRect(p.bg)
        val s = min(size.width / 400f, size.height / 200f)
        val ox = (size.width - 400f * s) / 2; val oy = (size.height - 200f * s) / 2
        withTransform({ translate(ox, oy); scale(s, s, pivot = Offset.Zero) }) {
            val pair = eyes(state.current)
            val t = state.talk
            deco(theme, p, state.current, state.name, (needle + t * 0.06f).coerceIn(0f, 1f), pair)
            val look = Offset((gazeX.value + state.lookX).coerceIn(-1.3f, 1.3f), (gazeY.value + state.lookY).coerceIn(-1.3f, 1.3f))
            // Themes with pupils look mostly with the pupils; the others move the whole eye.
            val pupils = theme == FaceTheme.PRSTEN || theme == FaceTheme.KVADRAT
            val gx = look.x * if (pupils) 6f else 16f; val gy = look.y * if (pupils) 4f else 10f
            val wave = sin(breath * 2 * PI.toFloat())
            val breathe = 1f + wave * if (state.asleep) 0.05f else 0.018f
            val sink = if (state.asleep) wave * 3f else 0f
            for ((eye, cx, left) in listOf(Triple(pair.first, EL, true), Triple(pair.second, ER, false))) {
                withTransform({
                    translate(gx, gy + sink)
                    scale((1f + t * 0.06f) * pop.value, blink.value * (1f + t * 0.16f) * pop.value * breathe, pivot = Offset(cx + eye.dx, EY + eye.dy))
                }) { drawEye(theme, p, eye, cx, left, look, drift) }
            }
            // Asleep: three little z's drift up.
            val mark = if (theme == FaceTheme.VIZIR) p.band else p.ink
            if (state.asleep) for (i in 0..2) {
                val q = (drift + i / 3f) % 1f
                val zx = 312f + q * 46f; val zy = 44f - q * 30f; val z = 6f + q * 8f
                val path = Path().apply { moveTo(zx, zy); lineTo(zx + z, zy); lineTo(zx, zy + z); lineTo(zx + z, zy + z) }
                drawPath(path, mark, alpha = sin(q * PI.toFloat()) * 0.9f, style = round(2f))
            }
            // The strip under the eyes: a line that follows your voice, or a dot while connecting.
            val hudY = if (theme == FaceTheme.CRTICA || theme == FaceTheme.KVADRAT) 160f else 176f
            when (state.hud) {
                Hud.LISTENING -> {
                    val half = 12f + mic.coerceIn(0f, 1f) * 110f
                    drawLine(mark, Offset(200f - half, hudY), Offset(200f + half, hudY), 3f, StrokeCap.Round, alpha = 0.5f + mic.coerceIn(0f, 1f) * 0.5f)
                }
                Hud.CONNECTING -> drawCircle(mark, 3f, Offset(170f + (if (sweep < 0.5f) sweep * 2 else 2 - sweep * 2) * 60f, hudY))
                Hud.NONE -> {}
            }
        }
    }
}
