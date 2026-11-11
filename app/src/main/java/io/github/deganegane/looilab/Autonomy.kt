package io.github.deganegane.looilab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * What Stevo does when nobody is asking him anything: he looks around, stretches, sighs when
 * bored, greets you when he sees you and (if allowed) wanders a little over the desk.
 *
 * Borrowed from how Cozmo/Vector-style robots feel alive:
 *  - an energy level: company and play lift it, time alone lets it sink; lively when high
 *    (more often, bigger moves), slow and sighing when low, until he falls asleep;
 *  - the eyes lead: he looks where he is about to turn before the body follows;
 *  - cool-downs, so the same thing does not repeat, and nothing cuts into a running gesture.
 *
 * [mode]: 0 stays still, 1 moves on the spot, 2 also drives around.
 */
class Autonomy(
    private val ble: LooiBle,
    private val face: FaceState,
    private val eyes: CameraEyes,
    private val mode: () -> Int,
    /** Face screen shown, awake, not talking, not lifted. */
    private val free: () -> Boolean,
) {
    var energy = 0.7f
        private set
    private var nextAt = System.currentTimeMillis() + 5_000
    private var last = -1
    /** Dead reckoning, so wandering stays near where he started. */
    private var offset = 0
    @Volatile var edgeAt = 0L

    fun excite(amount: Float = 0.35f) { energy = (energy + amount).coerceAtMost(1f) }

    private fun now() = System.currentTimeMillis()
    private fun usable() = ble.state.value == ConnState.READY && !ble.manualDrive
    private fun safeToDrive() = ble.floor.isNotEmpty() && ble.floor.all { it } && now() - edgeAt > 20_000

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            delay(300)
            energy = (energy - 0.0006f).coerceAtLeast(0.15f)
            val now = now()
            if (mode() == 0 || !free() || !usable()) { if (nextAt < now + 3_000) nextAt = now + 3_000; continue }
            if (now < nextAt || now < ble.busyUntil + 700) continue
            val seen = eyes.face.value?.let { now - it.at < 1_500 } == true
            if (seen) energy = (energy + 0.02f).coerceAtMost(1f)
            runCatching { act(seen) }
            nextAt = now() + (3_500 + (1 - energy) * 13_000).toLong() + Random.nextLong(0, 3_500)
        }
    }

    private suspend fun play(label: String, k: ByteArray) = ble.playScript(k, "sam: $label")

    private suspend fun act(seen: Boolean) {
        val ids = ArrayList<Int>()
        fun w(id: Int, n: Int) { if (id != last) repeat(n) { ids += id } }
        w(0, 3); w(1, 2); w(2, 1); w(3, 1)
        if (energy > 0.55f) w(4, 2)
        if (energy < 0.35f) w(5, 3)
        if (seen) w(6, 3)
        if (mode() >= 2 && !seen && safeToDrive()) w(7, 4)
        if (energy > 0.8f && !ble.atEdge) w(8, 1)
        val id = ids.random(); last = id
        when (id) {
            0 -> glance()
            1 -> { face.flash(Expr.CURIOUS); play("gleda okolo", Moves.lookAround(ble, if (Random.nextBoolean()) 1 else -1)) }
            2 -> { face.flash(Expr.SLEEPY); play("protezanje", Moves.stretch(ble)); delay(2_000); face.flash(Expr.HAPPY) }
            3 -> { face.flash(Expr.CURIOUS); face.lookY = 0.8f; play("gleda stol", Moves.peek(ble)); delay(1_600); face.lookY = 0f }
            4 -> { face.flash(Expr.HAPPY); play("migolji", Moves.wiggle(ble)) }
            5 -> { face.flash(Expr.BORED); play("uzdah", Moves.droop(ble)) }
            6 -> { face.flash(if (Random.nextInt(3) == 0) Expr.WINK else Expr.HAPPY); play("pozdrav", Moves.nod(ble)) }
            7 -> wander()
            8 -> { face.flash(Expr.EXCITED); play("vrtnja", Moves.spin(ble)) }
        }
    }

    /** Eyes first, then the body follows; a look, and back. */
    private suspend fun glance() {
        val dir = if (Random.nextBoolean()) 1 else -1 // +1 = his left
        val v = dir * Random.nextInt(22, 34); val ticks = Random.nextInt(6, 11)
        face.lookX = dir * 1f
        delay(320)
        play("pogled u stranu", Moves.turnBy(ble, v, ticks))
        delay(ticks * 40L + Random.nextLong(700, 1_800))
        if (!free() || !usable()) { face.lookX = 0f; return }
        face.lookX = -dir * 0.8f
        delay(260)
        play("pogled natrag", Moves.turnBy(ble, -v, ticks))
        delay(ticks * 40L); face.lookX = 0f
    }

    /** A few short steps over the desk: turn somewhere, look, roll a little. */
    private suspend fun wander() {
        val dir = if (Random.nextBoolean()) 1 else -1
        face.flash(Expr.CURIOUS); face.lookX = dir * 1f
        delay(300)
        val turnTicks = Random.nextInt(6, 15)
        play("istražuje: okret", Moves.turnBy(ble, dir * 30, turnTicks))
        delay(turnTicks * 40L + 350); face.lookX = 0f
        if (!free() || !usable() || !safeToDrive()) return
        // Keep close to where he started: head back once he has strayed.
        val forward = when { offset > 40 -> false; offset < -40 -> true; else -> Random.nextInt(4) != 0 }
        val ticks = Random.nextInt(9, 16)
        face.lookY = 0.5f
        play(if (forward) "istražuje: naprijed" else "istražuje: natrag", Moves.driveBy(ble, if (forward) 30 else -30, ticks))
        offset += if (forward) ticks else -ticks
        delay(ticks * 40L + 300); face.lookY = 0f
    }

    /** A little show on request (the menu's "Zaigraj"). */
    suspend fun trick() {
        excite(1f)
        if (!usable()) { face.flash(Expr.LAUGH); return }
        when (Random.nextInt(5)) {
            0 -> { face.flash(Expr.EXCITED); play("trik: vrtnja", Moves.spin(ble)) }
            1 -> { face.flash(Expr.LAUGH); play("trik: migoljenje", Moves.wiggle(ble)) }
            2 -> { face.flash(Expr.HAPPY); play("trik: naklon", Moves.bow(ble)) }
            3 -> { face.flash(Expr.SLEEPY); play("trik: protezanje", Moves.stretch(ble)) }
            else -> if (ble.atEdge) { face.flash(Expr.WINK); play("trik: kimanje", Moves.nod(ble)) }
                    else { face.flash(Expr.EXCITED); play("trik: ples", CapturedAnims.all[22].keyframes) }
        }
        nextAt = now() + 5_000
    }
}

/**
 * The reflex at the edge of the table: while driving, the moment a floor sensor sees the drop
 * he stops, rolls back the way he came and looks worried. Faster than any thinking.
 */
class EdgeReflex(
    private val ble: LooiBle,
    private val face: FaceState,
    private val onEdge: () -> Unit,
) {
    private var last = 0L

    /** Call on every floor-sensor report. */
    fun check() {
        val now = System.currentTimeMillis()
        if (!ble.atEdge || now > ble.driveUntil + 1_500 || now - last < 4_000) return
        last = now
        val dir = ble.lastDriveDir
        face.flash(Expr.WORRIED)
        ble.launch {
            stopScript()
            delay(120)
            if (dir != 0) playScript(Moves.driveBy(this, -dir * 45, 7), "refleks: odmak od ruba")
        }
        onEdge()
    }
}
