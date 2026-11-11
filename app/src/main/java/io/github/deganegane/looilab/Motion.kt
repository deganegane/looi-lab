package io.github.deganegane.looilab

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds a motion script out of eased pieces, the way an animator would: the head glides
 * (slow in, slow out) from wherever it is now, wheels ramp up and down instead of jerking.
 * A cursor moves along the timeline; [at] puts it back to run things at the same time.
 * One tick ≈ 40 ms. Head: 0 = up … 100 = down. Turn: + = left. Drive: + = forward.
 */
class Move(startHead: Int) {
    private val k = ArrayList<Int>()
    private var head = startHead
    private var t = 0

    fun at(tick: Int) = apply { t = tick }
    fun wait(ticks: Int) = apply { t += ticks }

    fun head(to: Int, ticks: Int) = apply {
        val target = to.coerceIn(4, 98)
        for (f in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val e = f * f * (3 - 2 * f) // smoothstep
            k += t + (ticks * f).roundToInt(); k += (head + (target - head) * e).roundToInt(); k += 3
        }
        head = target; t += ticks
    }

    private fun wheels(channel: Int, v: Int, ticks: Int) = apply {
        val ramp = min(2, ticks / 3)
        k += t; k += 0; k += channel
        k += t + ramp; k += v; k += channel
        k += t + ticks - ramp; k += v; k += channel
        k += t + ticks; k += 0; k += channel
        t += ticks
    }

    fun turn(v: Int, ticks: Int) = wheels(2, v, ticks)
    fun drive(v: Int, ticks: Int) = wheels(1, v, ticks)

    fun build(): ByteArray = LooiProtocol.keys(*k.toIntArray())
}

/** Stevo's vocabulary of movements. Each one starts from where the head is right now. */
object Moves {
    private fun m(ble: LooiBle) = Move(ble.headPos)

    fun nod(ble: LooiBle): ByteArray { val h = ble.headPos; return m(ble).head(h - 5, 3).head(h + 16, 4).head(h - 4, 4).head(h + 10, 4).head(h, 5).build() }
    fun shake(ble: LooiBle) = m(ble).turn(-10, 2).turn(30, 4).turn(-30, 8).turn(30, 8).turn(-30, 4).turn(10, 2).build()
    fun lookUp(ble: LooiBle) = m(ble).head(24, 9).wait(32).head(50, 11).build()
    fun lookDown(ble: LooiBle) = m(ble).head(78, 9).wait(32).head(50, 11).build()
    fun bow(ble: LooiBle) = m(ble).head(40, 4).head(90, 10).wait(14).head(50, 12).build()
    fun wiggle(ble: LooiBle) = m(ble).turn(32, 3).turn(-32, 6).turn(32, 6).turn(-32, 6).turn(32, 3).at(0).head(38, 6).wait(12).head(50, 6).build()
    fun spin(ble: LooiBle) = m(ble).turn(-12, 3).turn(75, 44).at(0).head(34, 8).wait(30).head(50, 9).build()
    fun peek(ble: LooiBle) = m(ble).head(80, 10).wait(26).head(50, 10).build()
    fun perkUp(ble: LooiBle) = m(ble).head(30, 5).wait(20).head(50, 9).build()
    fun droop(ble: LooiBle) = m(ble).head(74, 22).wait(34).head(52, 16).build()
    fun think(ble: LooiBle) = m(ble).head(28, 10).wait(30).head(50, 12).at(2).turn(20, 6).wait(28).turn(-20, 6).build()
    fun tiltAway(ble: LooiBle) = m(ble).turn(24, 8).wait(34).turn(-24, 8).at(0).head(72, 10).wait(32).head(52, 12).build()
    fun doubleTake(ble: LooiBle) = m(ble).turn(18, 5).turn(-18, 5).wait(4).turn(18, 4).turn(-18, 4).at(0).head(42, 6).wait(14).head(50, 6).build()

    /** A cat-like stretch: down, long reach up, a little shake, settle. */
    fun stretch(ble: LooiBle) = m(ble).head(86, 14).wait(6).head(14, 18).wait(8).head(50, 12)
        .at(40).turn(16, 3).turn(-16, 6).turn(16, 3).build()

    /** Look left, look right, back to the front (net turn zero). */
    fun lookAround(ble: LooiBle, first: Int) = m(ble).turn(first * 28, 9).wait(22).turn(-first * 28, 18).wait(22).turn(first * 28, 9)
        .at(0).head(36, 9).wait(60).head(50, 10).build()

    fun turnBy(ble: LooiBle, v: Int, ticks: Int) = m(ble).turn(v, ticks).build()
    fun driveBy(ble: LooiBle, v: Int, ticks: Int) = m(ble).drive(v, ticks).build()
    /** Startled: a quick hop back and head up. */
    fun startle(ble: LooiBle, driveBack: Boolean): ByteArray {
        val mv = m(ble).head(28, 4).wait(20).head(50, 10)
        if (driveBack) mv.at(0).drive(-45, 6)
        return mv.build()
    }

    fun sleepDown(ble: LooiBle) = m(ble).head(60, 10).wait(8).head(100, 26).build()
    fun wakeUp(ble: LooiBle) = Move(100).head(40, 14).head(50, 6).build()
}
