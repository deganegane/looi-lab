package io.github.deganegane.looilab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class Expr(val label: String) {
    NEUTRAL("mirno"), HAPPY("sretno"), SURPRISED("iznenađeno"), SLEEPY("pospano"),
    CURIOUS("znatiželjno"), LOVE("zaljubljeno"), ANGRY("ljuto"), DIZZY("ošamućeno"),
}

/** What the face shows. [flash] shows an expression for a moment, then goes back to [base]. */
@Stable
class FaceState {
    var base by mutableStateOf(Expr.NEUTRAL)
    var current by mutableStateOf(Expr.NEUTRAL)
        private set
    /** Bumped on every flash, so a newer flash cancels the older one's return. */
    var flashId by mutableStateOf(0)
        private set
    /** Gaze nudge from outside (-1..1), e.g. while the robot turns. */
    var lookX by mutableStateOf(0f)
    var lookY by mutableStateOf(0f)

    fun set(e: Expr) { base = e; current = e; flashId++ }
    fun flash(e: Expr) { current = e; flashId++ }
    internal fun settle(id: Int) { if (id == flashId) current = base }
}

private enum class Part { L, R }

// Face space: 400 × 200, eyes centred at x 128 / 272, y 92 (same as the design canvas).
private const val LX = 128f
private const val RX = 272f
private const val CY = 92f

private fun pill(cx: Float, cy: Float, w: Float, h: Float) = Path().apply {
    val r = min(w, h) / 2
    addRoundRect(RoundRect(Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), CornerRadius(r, r)))
}

private fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }

private fun heart(cx: Float, cy: Float, s: Float) = Path().apply {
    moveTo(cx, cy + s * 0.9f)
    cubicTo(cx - s * 1.6f, cy - s * 0.1f, cx - s * 0.9f, cy - s * 1.3f, cx, cy - s * 0.45f)
    cubicTo(cx + s * 0.9f, cy - s * 1.3f, cx + s * 1.6f, cy - s * 0.1f, cx, cy + s * 0.9f)
    close()
}

private fun arcUp(cx: Float, cy: Float, w: Float, h: Float) = Path().apply {
    moveTo(cx - w / 2, cy); quadraticTo(cx, cy - h * 1.6f, cx + w / 2, cy)
}

private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

private fun poly(vararg p: Float) = Path().apply {
    moveTo(p[0], p[1]); var i = 2
    while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
    close()
}

private fun spiral(cx: Float, cy: Float, r: Float) = Path().apply {
    val n = 120
    for (i in 0..n) {
        val t = i / n.toFloat() * 3.2f * PI.toFloat()
        val rr = r * i / n
        val x = cx + rr * cos(t); val y = cy + rr * sin(t)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
}

private fun shapes(e: Expr): List<Pair<Part, Path>> = when (e) {
    Expr.NEUTRAL -> listOf(Part.L to pill(LX, CY, 46f, 86f), Part.R to pill(RX, CY, 46f, 86f))
    Expr.HAPPY -> listOf(Part.L to arcUp(LX, CY + 14, 70f, 40f), Part.R to arcUp(RX, CY + 14, 70f, 40f))
    Expr.SURPRISED -> listOf(Part.L to circle(LX, CY, 40f), Part.R to circle(RX, CY, 40f))
    Expr.SLEEPY -> listOf(Part.L to line(LX - 36, CY + 16, LX + 36, CY + 16), Part.R to line(RX - 36, CY + 16, RX + 36, CY + 16))
    Expr.CURIOUS -> listOf(Part.L to pill(LX, CY, 50f, 96f), Part.R to pill(RX, CY + 14, 48f, 40f))
    Expr.LOVE -> listOf(Part.L to heart(LX, CY, 30f), Part.R to heart(RX, CY, 30f))
    Expr.ANGRY -> listOf(
        Part.L to poly(LX - 30, CY - 24, LX + 30, CY - 2, LX + 30, CY + 40, LX - 30, CY + 40),
        Part.R to poly(RX + 30, CY - 24, RX - 30, CY - 2, RX - 30, CY + 40, RX + 30, CY + 40),
    )
    Expr.DIZZY -> listOf(Part.L to spiral(LX, CY, 36f), Part.R to spiral(RX, CY, 36f))
}

private val SHAPES = Expr.entries.associateWith { shapes(it) }

// The unlit cathodes stacked behind the glass: they never move.
private val GHOSTS = listOf(Expr.NEUTRAL, Expr.SURPRISED, Expr.LOVE).flatMap { e -> SHAPES[e]!!.map { it.second } }

private val Glass = Color(0xFF140A06)
private val Cathode = Color(0xFF3A2216)
private val Glow = Color(0xFFFF5A00)
private val Filament = Color(0xFFFF8A2A)
private val Core = Color(0xFFFFD9A8)

private fun honeycomb(w: Float, h: Float): Path = Path().apply {
    val cw = 12f; val ch = 20.8f
    var y = 0f
    while (y < h + ch) {
        var x = 0f
        while (x < w + cw) {
            moveTo(x + 6, y); lineTo(x + 12, y + 3.5f); lineTo(x + 12, y + 10.4f)
            lineTo(x + 6, y + 13.9f); lineTo(x, y + 10.4f); lineTo(x, y + 3.5f); close()
            moveTo(x, y + 13.9f); lineTo(x, y + 20.8f)
            x += cw
        }
        y += ch
    }
}

/** A glowing filament: wide faint passes for the glow, then the bright wire and its hot core. */
private fun DrawScope.filament(path: Path, alpha: Float) {
    if (alpha <= 0.01f) return
    val cap = StrokeCap.Round; val join = StrokeJoin.Round
    drawPath(path, Glow, alpha = 0.06f * alpha, style = Stroke(26f, cap = cap, join = join))
    drawPath(path, Glow, alpha = 0.12f * alpha, style = Stroke(16f, cap = cap, join = join))
    drawPath(path, Glow, alpha = 0.28f * alpha, style = Stroke(10f, cap = cap, join = join))
    drawPath(path, Filament, alpha = alpha, style = Stroke(5.5f, cap = cap, join = join))
    drawPath(path, Core, alpha = 0.85f * alpha, style = Stroke(1.8f, cap = cap, join = join))
}

@Composable
fun NixieFace(state: FaceState, modifier: Modifier = Modifier.fillMaxSize()) {
    // Cathode switch: the old shape dims while the new one lights, like a real Nixie tube.
    var shown by remember { mutableStateOf(state.current) }
    var previous by remember { mutableStateOf(state.current) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(state.current) {
        if (state.current != shown) {
            previous = shown; shown = state.current
            swap.snapTo(0f); swap.animateTo(1f, tween(220, easing = LinearEasing))
        }
    }
    // Transient expressions fall back to the base one.
    LaunchedEffect(state.flashId) {
        val id = state.flashId
        if (state.current != state.base) { delay(2_600); state.settle(id) }
    }

    val blink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2_800, 6_500))
            if (state.current == Expr.NEUTRAL || state.current == Expr.CURIOUS) {
                blink.animateTo(0.06f, tween(70)); blink.animateTo(1f, tween(110))
                if (Random.nextFloat() < 0.2f) { delay(120); blink.animateTo(0.06f, tween(70)); blink.animateTo(1f, tween(110)) }
            }
        }
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

    val flicker = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(60, 180))
            flicker.snapTo(if (Random.nextFloat() < 0.04f) 0.82f else 0.95f + Random.nextFloat() * 0.05f)
        }
    }

    // Honeycomb grille, rebuilt only when the size changes (plain cache, not state).
    val mesh = remember { arrayOfNulls<Any>(2) }

    Canvas(modifier) {
        drawRect(Glass)
        val s = min(size.width / 400f, size.height / 200f)
        val ox = (size.width - 400f * s) / 2; val oy = (size.height - 200f * s) / 2
        withTransform({ translate(ox, oy); scale(s, s, pivot = Offset.Zero) }) {
            GHOSTS.forEach { drawPath(it, Cathode, style = Stroke(3f, join = StrokeJoin.Round)) }
            val gx = (gazeX.value + state.lookX).coerceIn(-1.3f, 1.3f) * 16f
            val gy = (gazeY.value + state.lookY).coerceIn(-1.3f, 1.3f) * 10f
            val p = swap.value
            val f = flicker.value
            for ((expr, a) in listOf(previous to (1f - p), shown to p)) {
                if (a <= 0.01f) continue
                for ((part, path) in SHAPES[expr]!!) {
                    val cx = if (part == Part.L) LX else RX
                    withTransform({
                        translate(gx, gy)
                        scale(1f, blink.value, pivot = Offset(cx, CY))
                    }) { filament(path, a * f) }
                }
            }
        }
        if (mesh[0] != size) { mesh[0] = size; mesh[1] = honeycomb(size.width / s, size.height / s) }
        val m = mesh[1] as Path
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawPath(m, Color.Black, alpha = 0.5f, style = Stroke(1.4f))
        }
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f),
                center = center, radius = size.maxDimension * 0.62f,
            )
        )
    }
}
