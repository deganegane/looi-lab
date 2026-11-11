package io.github.deganegane.looilab

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
    SAD("tužno"), WINK("namig"), LAUGH("smijeh"), THINKING("razmišlja"), WORRIED("zabrinuto"),
    BORED("dosadno"), EXCITED("oduševljeno"), CONFUSED("zbunjeno"), SHY("sramežljivo"),
}

/** What the strip under the eyes shows. */
enum class Hud { NONE, CONNECTING, LISTENING }

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
    /** 0..1 loudness while Stevo speaks: the filaments pulse with his voice. */
    var talk by mutableStateOf(0f)
    /** 0..1 loudness of the microphone while he listens: the line under his eyes. */
    var mic by mutableStateOf(0f)
    var hud by mutableStateOf(Hud.NONE)
    /** Asleep: slow breathing and little z's. */
    var asleep by mutableStateOf(false)
    /** A short line of text shown under the face for a moment. */
    var message by mutableStateOf<String?>(null)
        private set
    var messageId by mutableStateOf(0)
        private set
    fun say(text: String) { message = text; messageId++ }
    fun clearMessage(id: Int) { if (id == messageId) message = null }

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

private fun arcDown(cx: Float, cy: Float, w: Float, h: Float) = Path().apply {
    moveTo(cx - w / 2, cy); quadraticTo(cx, cy + h * 1.6f, cx + w / 2, cy)
}

/** ">" (pointing right) or "<": squeezed-shut laughing eyes. */
private fun chevron(cx: Float, cy: Float, s: Float, right: Boolean) = Path().apply {
    val d = if (right) 1 else -1
    moveTo(cx - d * s * 0.7f, cy - s); lineTo(cx + d * s * 0.7f, cy); lineTo(cx - d * s * 0.7f, cy + s)
}

/** A four-pointed sparkle. */
private fun star(cx: Float, cy: Float, r: Float) = Path().apply {
    val k = r * 0.22f
    moveTo(cx, cy - r)
    quadraticTo(cx + k, cy - k, cx + r, cy); quadraticTo(cx + k, cy + k, cx, cy + r)
    quadraticTo(cx - k, cy + k, cx - r, cy); quadraticTo(cx - k, cy - k, cx, cy - r); close()
}

/** Half-closed lid: flat top, round bottom. */
private fun lid(cx: Float, cy: Float, w: Float, h: Float) = Path().apply {
    moveTo(cx - w / 2, cy); lineTo(cx + w / 2, cy)
    quadraticTo(cx + w / 2, cy + h, cx, cy + h); quadraticTo(cx - w / 2, cy + h, cx - w / 2, cy); close()
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
    // Drooping outer corners.
    Expr.SAD -> listOf(
        Part.L to poly(LX - 30, CY - 2, LX + 30, CY - 20, LX + 30, CY + 34, LX - 30, CY + 34),
        Part.R to poly(RX + 30, CY - 2, RX - 30, CY - 20, RX - 30, CY + 34, RX + 30, CY + 34),
    )
    Expr.WINK -> listOf(Part.L to pill(LX, CY, 46f, 86f), Part.R to arcUp(RX, CY + 14, 66f, 36f))
    Expr.LAUGH -> listOf(Part.L to chevron(LX, CY, 30f, right = true), Part.R to chevron(RX, CY, 30f, right = false))
    // Short eyes up and to the side, as if looking for the answer.
    Expr.THINKING -> listOf(Part.L to pill(LX + 16, CY - 22, 40f, 46f), Part.R to pill(RX + 16, CY - 22, 40f, 46f))
    Expr.WORRIED -> listOf(Part.L to circle(LX, CY + 4, 22f), Part.R to circle(RX, CY + 4, 22f))
    Expr.BORED -> listOf(Part.L to lid(LX, CY + 6, 60f, 26f), Part.R to lid(RX, CY + 6, 60f, 26f))
    Expr.EXCITED -> listOf(Part.L to star(LX, CY, 42f), Part.R to star(RX, CY, 42f))
    Expr.CONFUSED -> listOf(Part.L to circle(LX, CY - 4, 34f), Part.R to line(RX - 26, CY + 12, RX + 26, CY - 4))
    // Happy arcs, looking down and away.
    Expr.SHY -> listOf(Part.L to arcDown(LX - 8, CY + 30, 50f, 18f), Part.R to arcDown(RX - 8, CY + 30, 50f, 18f))
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
private fun DrawScope.filament(path: Path, intensity: Float) {
    if (intensity <= 0.01f) return
    val alpha = intensity.coerceAtMost(1f)
    val boost = (intensity - 1f).coerceAtLeast(0f) * 3f // louder voice → wider glow
    val cap = StrokeCap.Round; val join = StrokeJoin.Round
    drawPath(path, Glow, alpha = 0.06f * alpha + 0.08f * boost, style = Stroke(26f + 10f * boost, cap = cap, join = join))
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
            if (state.current in setOf(Expr.NEUTRAL, Expr.CURIOUS, Expr.THINKING, Expr.WORRIED, Expr.CONFUSED, Expr.SAD)) {
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

    // A new expression pops in with a little overshoot.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state.current) {
        pop.snapTo(1.16f); pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
    }
    // Waking up: the eyes open slowly.
    LaunchedEffect(state.asleep) {
        if (!state.asleep) { blink.snapTo(0.06f); blink.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    }
    // Always-running clocks: breathing, the z's, the connecting dot.
    val clock = rememberInfiniteTransition(label = "face")
    val breath by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(4_200, easing = LinearEasing)), label = "breath")
    val drift by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(5_000, easing = LinearEasing)), label = "drift")
    val sweep by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "sweep")
    val mic by animateFloatAsState(state.mic, tween(120), label = "mic")

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
            val t = state.talk
            val f = (flicker.value + t * 0.25f).coerceAtMost(1.2f)
            val wave = sin(breath * 2 * PI.toFloat())
            val breathe = 1f + wave * if (state.asleep) 0.05f else 0.018f
            val sink = if (state.asleep) wave * 3f else 0f
            for ((expr, a) in listOf(previous to (1f - p), shown to p)) {
                if (a <= 0.01f) continue
                for ((part, path) in SHAPES[expr]!!) {
                    val cx = if (part == Part.L) LX else RX
                    withTransform({
                        translate(gx, gy + sink)
                        scale((1f + t * 0.06f) * pop.value, blink.value * (1f + t * 0.16f) * pop.value * breathe, pivot = Offset(cx, CY))
                    }) { filament(path, a * f) }
                }
            }
        }
        withTransform({ translate(ox, oy); scale(s, s, pivot = Offset.Zero) }) {
            // Asleep: three little z's drift up from his right eye.
            if (state.asleep) for (i in 0..2) {
                val q = (drift + i / 3f) % 1f
                val x = 312f + q * 46f; val y = 74f - q * 52f; val z = 7f + q * 9f
                val a = sin(q * PI.toFloat()) * 0.8f
                val path = Path().apply { moveTo(x, y); lineTo(x + z, y); lineTo(x, y + z); lineTo(x + z, y + z) }
                drawPath(path, Glow, alpha = 0.3f * a, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, Filament, alpha = a, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // The strip under the eyes: a line that follows your voice, or a dot while connecting.
            when (state.hud) {
                Hud.LISTENING -> {
                    val half = 12f + mic.coerceIn(0f, 1f) * 110f
                    val a = 0.45f + mic.coerceIn(0f, 1f) * 0.55f
                    drawLine(Glow, Offset(200f - half, 184f), Offset(200f + half, 184f), strokeWidth = 7f, cap = StrokeCap.Round, alpha = 0.22f * a)
                    drawLine(Filament, Offset(200f - half, 184f), Offset(200f + half, 184f), strokeWidth = 2.4f, cap = StrokeCap.Round, alpha = a)
                }
                Hud.CONNECTING -> {
                    val x = 170f + (if (sweep < 0.5f) sweep * 2 else 2 - sweep * 2) * 60f
                    drawCircle(Glow, 6f, Offset(x, 184f), alpha = 0.3f)
                    drawCircle(Filament, 2.6f, Offset(x, 184f))
                }
                Hud.NONE -> {}
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
